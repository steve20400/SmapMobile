package cm.smap.collector.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import cm.smap.collector.SmapApp
import cm.smap.collector.data.BatchEntity
import cm.smap.collector.data.FixEntity
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * Service de premier plan de collecte GNSS.
 *
 * - GPS à 1 Hz → Room chiffrée, par lots de 1 500 points maximum ;
 * - survit à l'écran éteint et aux économiseurs de batterie (notification
 *   permanente + type `location`, requis par Android 14) ;
 * - mode « relevé 60 s » : agrégation immobile pour la consolidation serveur.
 *
 * L'horodatage conservé est celui du GPS (`location.time`), jamais l'horloge
 * du téléphone — elle dérive et peut être modifiée par l'utilisateur.
 */
class CollectService : Service() {

    companion object {
        const val ACTION_START = "smap.start"
        const val ACTION_STOP = "smap.stop"
        const val ACTION_SURVEY = "smap.survey"          // extra: ref_code, duration_s
        private const val BATCH_MAX_POINTS = 1500
        private const val CHANNEL = "smap-collect"

        /**
         * Période demandée au GPS. C'est une DEMANDE : le matériel livre ce
         * qu'il sait faire, souvent 1 Hz sur les puces d'entrée de gamme, et
         * jusqu'à 5 ou 10 Hz sur les puces récentes. On demande donc le
         * maximum et on mesure ce qui arrive vraiment (champ `hz` de l'état).
         *
         * Densifier les points ne suffit pas à lisser un tracé : le bruit GPS
         * (±5 m) reste bien supérieur à l'espacement obtenu (0,3 m à 5 Hz au
         * pas de marche). Plus de points sert à MOYENNER ce bruit, pas à le
         * supprimer — le lissage côté rendu reste indispensable.
         */
        private const val GPS_INTERVAL_MS = 200L

        /** État observable par l'interface (précision courante, compteurs…). */
        val state = MutableStateFlow(CollectState())
    }

    data class CollectState(
        val running: Boolean = false,
        val accuracy: Float? = null,
        val sats: Int? = null,
        val points: Int = 0,
        val distanceM: Double = 0.0,
        val surveyRemainingS: Int? = null,
        val surveyLiveAccuracy: Float? = null,
        /** Cadence réellement livrée par le matériel, mesurée sur 10 s. */
        val hz: Float? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fused: FusedLocationProviderClient
    private var currentBatchId = 0L
    private var currentBatchPoints = 0
    private var last: Location? = null
    private var distance = 0.0

    private var surveyJob: Job? = null
    private val surveyBuffer = mutableListOf<Location>()
    private var surveyRef: String? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_SURVEY -> startSurvey(
                intent.getStringExtra("ref_code") ?: "point",
                intent.getIntExtra("duration_s", 60))
            ACTION_STOP -> stopAll()
        }
        return START_STICKY
    }

    private fun start() {
        if (state.value.running) return
        createChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(cm.smap.collector.R.string.notif_title))
            .setContentText(getString(cm.smap.collector.R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, notif)

        fused = LocationServices.getFusedLocationProviderClient(this)
        scope.launch { newBatch() }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MS)
            // 0 : aucun plancher, on accepte tout ce que le matériel livre.
            .setMinUpdateIntervalMillis(0L)
            // Aucun regroupement des positions. Le regroupement économiserait
            // la batterie, mais retarderait l'affichage de la précision, dont
            // l'agent a besoin en direct pendant les 60 s d'un relevé.
            .setMaxUpdateDelayMillis(0L)
            // 0 m : indispensable au relevé immobile. Un filtre de déplacement
            // couperait exactement les mesures que l'on cherche à moyenner.
            .setMinUpdateDistanceMeters(0f)
            .setGranularity(Granularity.GRANULARITY_FINE)
            // Ne pas attendre une première position « précise » : l'agent
            // verrait l'écran figé au démarrage de la tournée.
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } catch (_: SecurityException) { stopSelf(); return }
        state.value = CollectState(running = true)
    }

    /** Horodatages des positions récentes — sert à mesurer la cadence réelle.
     *  Uniquement touché depuis le Looper principal, d'où l'absence de verrou. */
    private val arrivees = ArrayDeque<Long>()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(res: LocationResult) {
            val loc = res.lastLocation ?: return
            // Mesuré ici, sur le Looper principal : pas de concurrence.
            val now = android.os.SystemClock.elapsedRealtime()
            arrivees.addLast(now)
            while (arrivees.isNotEmpty() && now - arrivees.first() > 10_000) arrivees.removeFirst()
            val ecart = arrivees.last() - arrivees.first()
            val hz = if (arrivees.size >= 2 && ecart > 0)
                (arrivees.size - 1) * 1000f / ecart else null
            scope.launch { onFix(loc, hz) }
        }
    }

    private suspend fun onFix(loc: Location, hz: Float?) {
        last?.let { if (loc.accuracy < 30) distance += it.distanceTo(loc) }
        last = loc
        if (currentBatchPoints >= BATCH_MAX_POINTS) newBatch()
        SmapApp.db.dao().insertFix(FixEntity(
            batchLocalId = currentBatchId,
            ts = loc.time, lat = loc.latitude, lon = loc.longitude,
            alt = if (loc.hasAltitude()) loc.altitude else null,
            acc = loc.accuracy,
            spd = if (loc.hasSpeed()) loc.speed else null,
            brg = if (loc.hasBearing()) loc.bearing else null,
            sats = loc.extras?.getInt("satellites")))
        currentBatchPoints++
        if (surveyJob?.isActive == true) synchronized(surveyBuffer) { surveyBuffer += loc }
        state.value = state.value.copy(
            accuracy = loc.accuracy, points = state.value.points + 1,
            distanceM = distance, hz = hz,
            surveyLiveAccuracy = liveSurveyAccuracy())
    }

    /** Précision estimée du centroïde en cours (1/√n de la médiane des σ). */
    private fun liveSurveyAccuracy(): Float? = synchronized(surveyBuffer) {
        if (surveyBuffer.isEmpty()) return null
        val med = surveyBuffer.map { it.accuracy }.sorted()[surveyBuffer.size / 2]
        (med / kotlin.math.sqrt(surveyBuffer.size.toFloat()))
    }

    /** Relevé immobile : N secondes de mesures → un lot `survey` dédié. */
    private fun startSurvey(refCode: String, durationS: Int) {
        if (surveyJob?.isActive == true) return
        surveyRef = refCode
        synchronized(surveyBuffer) { surveyBuffer.clear() }
        surveyJob = scope.launch {
            for (s in durationS downTo 1) {
                state.value = state.value.copy(surveyRemainingS = s)
                delay(1000)
                // arrêt anticipé : précision cible atteinte et stable
                val live = liveSurveyAccuracy()
                if (s < durationS - 15 && live != null && live < 1.0f) break
            }
            finishSurvey()
        }
    }

    private suspend fun finishSurvey() {
        val pts = synchronized(surveyBuffer) { surveyBuffer.toList() }
        state.value = state.value.copy(surveyRemainingS = null)
        if (pts.size < 5) return                    // trop court : ignoré
        val uuid = UUID.randomUUID().toString()
        val dao = SmapApp.db.dao()
        val payload = buildString {
            append("""{"ref_code":${jsonStr(surveyRef ?: "point")},"points":[""")
            pts.forEachIndexed { i, l ->
                if (i > 0) append(',')
                append("""{"ts":${l.time},"lat":${l.latitude},"lon":${l.longitude},"acc":${l.accuracy}}""")
            }
            append("]}")
        }
        val id = dao.insertBatch(BatchEntity(
            clientUuid = uuid, kind = "survey",
            payloadExtra = payload, pointCount = pts.size))
        SmapApp.lastSurveyUuid = uuid               // proposé au formulaire de bien
        cm.smap.collector.work.SyncWorker.enqueue(this)
    }

    private suspend fun newBatch() {
        // le lot précédent devient éligible à l'envoi
        currentBatchId = SmapApp.db.dao().insertBatch(
            BatchEntity(clientUuid = UUID.randomUUID().toString(), kind = "track"))
        currentBatchPoints = 0
    }

    private fun stopAll() {
        runCatching { fused.removeLocationUpdates(callback) }
        surveyJob?.cancel()
        state.value = CollectState(running = false)
        cm.smap.collector.work.SyncWorker.enqueue(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(
                    CHANNEL, getString(cm.smap.collector.R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
