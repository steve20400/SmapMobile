package cm.smap.collector.indoor

/**
 * ═══════════════ MODULE INTÉRIEUR — EXPÉRIMENTAL (phase P4) ═══════════════
 *
 * Lever intérieur par odométrie visuelle-inertielle (ARCore) + ancres QR.
 * Ce module implémente le cœur métier ; l'activité d'interface (aperçu caméra
 * GLSurfaceView) sera branchée en phase P4 — voir SMAP_documentation.pdf,
 * chapitre « Cartographie intérieure ».
 *
 * Principe (validé côté serveur par tests automatisés) :
 *  1. une session ARCore fournit la pose 6-DoF en continu (~30 Hz) ;
 *  2. chaque scan de QR (ML Kit) enregistre {t, marker} — le serveur pose
 *     l'ancre et ferme les boucles ;
 *  3. le lot `traverse` {poses, scans, doors} part par la même file de
 *     synchronisation que la collecte extérieure.
 */
import android.app.Activity
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable data class PoseSample(val t: Long, val x: Float, val y: Float, val z: Float)
@Serializable data class ScanSample(val t: Long, val marker: String)
@Serializable data class DoorSample(
    val t: Long, val space_code: String, val landmark: String,
    val side: String, val category: String? = null, val wheelchair: Boolean = true)

@Serializable
data class TraversePayload(
    val building_code: String,
    val level_ordinal: Int,
    val level_name: String,
    val poses: List<PoseSample>,
    val scans: List<ScanSample>,
    val doors: List<DoorSample>,
)

/**
 * Enregistreur de cheminement. À utiliser depuis l'activité de lever :
 * `onFrame()` à chaque image ARCore, `onQrScanned()` à chaque QR décodé,
 * `markDoor()` quand l'agent marque une porte, puis `buildPayload()`.
 */
class TraverseRecorder(
    private val buildingCode: String,
    private val levelOrdinal: Int,
    private val levelName: String = levelOrdinal.toString(),
) {
    private val poses = ArrayList<PoseSample>(4096)
    private val scans = ArrayList<ScanSample>()
    private val doors = ArrayList<DoorSample>()
    private val t0 = System.currentTimeMillis()
    private var lastPoseAt = 0L

    val distinctMarkers get() = scans.map { it.marker }.toSet().size
    val loopsClosed get() = scans.size - distinctMarkers
    val walkedMeters: Double
        get() = poses.zipWithNext().sumOf { (a, b) ->
            val dx = (b.x - a.x).toDouble(); val dz = (b.z - a.z).toDouble()
            kotlin.math.sqrt(dx * dx + dz * dz)
        }
    /** Mètres parcourus depuis la dernière fermeture possible — pour
     *  l'alerte « repassez sur un QR avant 90 m ». */
    var metersSinceLastScan = 0.0; private set

    /** À appeler à chaque frame ARCore en état TRACKING (échantillonné à 2 Hz). */
    fun onFrame(frame: Frame) {
        val cam = frame.camera
        if (cam.trackingState != TrackingState.TRACKING) return
        val now = System.currentTimeMillis()
        if (now - lastPoseAt < 500) return
        lastPoseAt = now
        val p = cam.pose
        val sample = PoseSample(now - t0, p.tx(), p.ty(), p.tz())
        poses.lastOrNull()?.let {
            val dx = (sample.x - it.x).toDouble(); val dz = (sample.z - it.z).toDouble()
            metersSinceLastScan += kotlin.math.sqrt(dx * dx + dz * dz)
        }
        poses += sample
    }

    /** À appeler quand ML Kit décode un QR `SMAP:...` sur l'image caméra. */
    fun onQrScanned(content: String): Boolean {
        if (!content.startsWith("SMAP:")) return false
        val last = scans.lastOrNull()
        val now = System.currentTimeMillis() - t0
        if (last?.marker == content && now - last.t < 5000) return false   // anti-rebond
        scans += ScanSample(now, content)
        metersSinceLastScan = 0.0
        return true
    }

    fun markDoor(spaceCode: String, landmark: String, side: String,
                 category: String?, wheelchair: Boolean) {
        doors += DoorSample(System.currentTimeMillis() - t0, spaceCode,
                            landmark, side, category, wheelchair)
    }

    fun buildPayload(): String = Json.encodeToString(TraversePayload(
        buildingCode, levelOrdinal, levelName, poses, scans, doors))

    /** Verdict local avant envoi : circuit exploitable ? */
    fun readyToSend(): Pair<Boolean, String> = when {
        scans.size < 2 -> false to "Scannez au moins 2 QR"
        loopsClosed < 1 -> false to "Aucune boucle fermée — repassez sur un QR déjà scanné"
        poses.size < 10 -> false to "Cheminement trop court"
        else -> true to "Circuit prêt : ${distinctMarkers} ancres, " +
                        "$loopsClosed boucle(s), ${"%.0f".format(walkedMeters)} m"
    }
}

/** Vérification ARCore au premier lancement du mode intérieur. */
object ArAvailability {
    /** @return null si ARCore est prêt, sinon un message d'explication. */
    fun check(activity: Activity): String? = try {
        when (ArCoreApk.getInstance().checkAvailability(activity)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> null
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> {
                ArCoreApk.getInstance().requestInstall(activity, true)
                "Installation d'ARCore demandée — relancez ensuite"
            }
            else -> "Ce téléphone n'est pas certifié ARCore : le lever intérieur " +
                    "n'est pas disponible (repli : comptage de pas, phase P9)"
        }
    } catch (e: UnavailableException) {
        "ARCore indisponible : ${e.message}"
    }
}
