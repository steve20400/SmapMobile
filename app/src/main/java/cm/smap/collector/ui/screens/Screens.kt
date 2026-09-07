package cm.smap.collector.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import cm.smap.collector.R
import cm.smap.collector.SmapApp
import cm.smap.collector.data.BatchEntity
import cm.smap.collector.data.PropertyDraft
import cm.smap.collector.service.CollectService
import cm.smap.collector.ui.Langue
import cm.smap.collector.ui.SmapColors as C
import cm.smap.collector.ui.sendServiceAction
import cm.smap.collector.work.SyncWorker
import cm.smap.collector.work.exporterLotsEnAttente
import kotlinx.coroutines.launch

/* ─────────────────────────── composants partagés ─────────────────────────── */

@Composable
fun SmapCard(content: @Composable ColumnScope.() -> Unit) = Card(
    colors = CardDefaults.cardColors(containerColor = C.Surface),
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
) { Column(Modifier.padding(14.dp), content = content) }

@Composable
fun Stat(label: String, value: String, tint: Color = C.Ink) {
    Column(Modifier.padding(end = 18.dp)) {
        Text(label.uppercase(), fontSize = 10.sp, color = C.Ink3, letterSpacing = 1.sp)
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

@Composable
fun BigButton(text: String, color: Color = C.Green, enabled: Boolean = true,
              onClick: () -> Unit) = Button(
    onClick = onClick, enabled = enabled,
    colors = ButtonDefaults.buttonColors(containerColor = color),
    shape = RoundedCornerShape(13.dp),
    modifier = Modifier.fillMaxWidth().height(54.dp).padding(vertical = 2.dp),
) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }

/** L'anneau de précision — l'élément central de l'application terrain. */
@Composable
fun PrecisionRing(accuracy: Float?, size: Int = 168) {
    val color = when {
        accuracy == null -> C.Ink3
        accuracy <= 2f -> C.Ok
        accuracy <= 10f -> C.Warn
        else -> C.Crit
    }
    val label = when {
        accuracy == null -> "—"
        accuracy <= 2f -> stringResource(R.string.precision_excellent)
        accuracy <= 10f -> stringResource(R.string.precision_medium)
        else -> stringResource(R.string.precision_poor)
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round)
            drawArc(C.Surface2, -90f, 360f, false, style = stroke)
            val sweep = accuracy?.let {
                (1f - (it / 15f).coerceIn(0.05f, 0.95f)) * 360f } ?: 0f
            drawArc(color, -90f, sweep, false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(accuracy?.let { "±%.1f".format(it) } ?: "—",
                 fontSize = 33.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Text(stringResource(R.string.precision_meters, label),
                 fontSize = 10.sp, color = C.Ink2, letterSpacing = 1.sp)
        }
    }
}

/* ─────────────────────────────── Tournée ─────────────────────────────── */

@Composable
fun HomeScreen(nav: NavController) {
    val st by CollectService.state.collectAsState()
    val pending by SmapApp.db.dao().pendingCount().collectAsState(initial = 0)
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.nav_home),
             fontSize = 23.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Text(stringResource(if (SmapApp.api.isRegistered) R.string.home_registered
                            else R.string.home_configure),
             fontSize = 12.sp, color = if (SmapApp.api.isRegistered) C.Ink2 else C.Warn)
        Spacer(Modifier.height(14.dp))
        SmapCard {
            Row {
                Stat(stringResource(R.string.stat_state),
                     stringResource(if (st.running) R.string.state_active
                                    else R.string.state_stopped),
                     if (st.running) C.GreenLight else C.Ink3)
                Stat(stringResource(R.string.stat_points), "%,d".format(st.points))
                Stat(stringResource(R.string.stat_distance),
                     "%.1f km".format(st.distanceM / 1000))
            }
            Spacer(Modifier.height(6.dp))
            Row { Stat(stringResource(R.string.stat_pending), "$pending",
                       if (pending > 0) C.Warn else C.Ok) }
        }
        Spacer(Modifier.height(10.dp))
        if (!st.running)
            BigButton(stringResource(R.string.btn_start_collect)) {
                (nav.context as ComponentActivity).sendServiceAction(CollectService.ACTION_START)
                nav.navigate("collect")
            }
        else BigButton(stringResource(R.string.btn_resume_collect), C.Surface2) {
            nav.navigate("collect")
        }
        BigButton(stringResource(R.string.btn_property_no_survey), C.Surface2) {
            nav.navigate("property")
        }
        // Ancrage sans impression : on scanne un code déjà présent dans le
        // bâtiment plutôt que d'en poser un. Le serveur ne fait pas la
        // différence, et cela évite des planches à imprimer et à replacer.
        BigButton(stringResource(R.string.btn_anchor), C.Surface2) {
            nav.navigate("scan/ancre")
        }
    }
}

/* ─────────────────────────────── Collecte ─────────────────────────────── */

@Composable
fun CollectScreen(nav: NavController) {
    val st by CollectService.state.collectAsState()
    val ctx = LocalContext.current
    Column(Modifier.padding(16.dp).fillMaxSize(),
           horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(
                if (st.running) C.Ok else C.Ink3, RoundedCornerShape(99.dp)))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (st.running) R.string.collect_active
                                else R.string.collect_stopped),
                 fontSize = 12.sp, color = if (st.running) C.GreenLight else C.Ink3,
                 letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(18.dp))
        PrecisionRing(st.accuracy)
        Spacer(Modifier.height(16.dp))
        Row {
            Stat(stringResource(R.string.stat_points), "%,d".format(st.points))
            Stat(stringResource(R.string.stat_distance), "%.1f km".format(st.distanceM / 1000))
            // Cadence réellement livrée par la puce, et non celle demandée :
            // c'est la seule façon de savoir ce que vaut le téléphone.
            Stat(stringResource(R.string.stat_rate),
                 st.hz?.let { "%.1f Hz".format(it) } ?: "—")
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.collect_hint),
             fontSize = 12.sp, color = C.Ink3, textAlign = TextAlign.Center,
             modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        BigButton(stringResource(R.string.btn_mark_property), C.Ocre, enabled = st.running) {
            (ctx as ComponentActivity).sendServiceAction(CollectService.ACTION_SURVEY) {
                putExtra("ref_code", "bien-" + System.currentTimeMillis() / 1000)
                putExtra("duration_s", 60)
            }
            nav.navigate("survey")
        }
        BigButton(stringResource(if (st.running) R.string.btn_stop_collect
                                 else R.string.btn_start_collect),
                  if (st.running) C.Surface2 else C.Green) {
            (ctx as ComponentActivity).sendServiceAction(
                if (st.running) CollectService.ACTION_STOP else CollectService.ACTION_START)
        }
    }
}

/* ─────────────────────────── Relevé 60 s ─────────────────────────── */

@Composable
fun SurveyScreen(nav: NavController) {
    val st by CollectService.state.collectAsState()
    LaunchedEffect(st.surveyRemainingS) {
        if (st.surveyRemainingS == null && st.running) {
            // relevé terminé → formulaire du bien
            nav.navigate("property") { popUpTo("collect") }
        }
    }
    Column(Modifier.padding(16.dp).fillMaxSize(),
           horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(30.dp))
        Box(contentAlignment = Alignment.Center) {
            PrecisionRing(st.surveyLiveAccuracy, size = 200)
        }
        Spacer(Modifier.height(10.dp))
        Text("${st.surveyRemainingS ?: 0}", fontSize = 44.sp,
             fontWeight = FontWeight.Bold, color = C.Ocre)
        Text(stringResource(R.string.survey_seconds_left),
             fontSize = 11.sp, color = C.Ink2, letterSpacing = 1.sp)
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.survey_stay_still),
             fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
        Text(stringResource(R.string.survey_posture),
             fontSize = 12.5.sp, color = C.Ink2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        st.surveyLiveAccuracy?.let {
            Text(stringResource(R.string.survey_live_precision, "%.1f".format(it)),
                 fontSize = 13.sp, color = if (it <= 2f) C.GreenLight else C.Warn)
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.survey_auto_stop),
             fontSize = 11.sp, color = C.Ink3, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
    }
}

/* ─────────────────────────── Fiche du bien ─────────────────────────── */

@Composable
fun PropertyScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("villa") }
    var rooms by remember { mutableStateOf("4") }
    var price by remember { mutableStateOf("") }
    var neighborhood by remember { mutableStateOf("") }
    var landmark by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.property_title),
             fontSize = 23.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        SmapApp.lastSurveyUuid?.let {
            Text(stringResource(R.string.property_linked_survey, it.take(8)),
                 fontSize = 11.5.sp, color = C.GreenLight)
        }
        Spacer(Modifier.height(12.dp))

        listOf<Triple<String, String, (String) -> Unit>>(
            Triple(stringResource(R.string.property_type), type) { type = it },
            Triple(stringResource(R.string.property_rooms), rooms) { rooms = it },
            Triple(stringResource(R.string.property_rent), price) { price = it },
            Triple(stringResource(R.string.property_neighborhood), neighborhood) { neighborhood = it },
        ).forEach { (label, value, setter) ->
            OutlinedTextField(value = value, onValueChange = setter,
                label = { Text(label, fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = C.GreenLight, unfocusedBorderColor = C.Line))
        }
        OutlinedTextField(value = landmark, onValueChange = { landmark = it },
            label = { Text(stringResource(R.string.property_landmark), fontSize = 12.sp) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = C.GreenLight, unfocusedBorderColor = C.Line))

        Spacer(Modifier.height(12.dp))
        BigButton(stringResource(if (saved) R.string.property_saved else R.string.property_save),
                  enabled = !saved && landmark.length >= 10) {
            scope.launch {
                SmapApp.db.dao().insertDraft(PropertyDraft(
                    type = type, rooms = rooms.toIntOrNull(),
                    bathrooms = null, priceFcfa = price.toIntOrNull(),
                    neighborhood = neighborhood.ifBlank { null },
                    landmark = landmark,
                    surveyBatchUuid = SmapApp.lastSurveyUuid, photoPaths = ""))
                SyncWorker.enqueue(nav.context)
                saved = true
            }
        }
        if (landmark.length in 1..9)
            Text(stringResource(R.string.property_landmark_short),
                 fontSize = 11.5.sp, color = C.Warn)
    }
}

/* ─────────────────────────── Synchronisation ─────────────────────────── */

@Composable
fun SyncScreen() {
    val batches by SmapApp.db.dao().recentBatches().collectAsState(initial = emptyList())
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var noteExport by remember { mutableStateOf<String?>(null) }

    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sync_title),
                     fontSize = 23.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                Text(stringResource(R.string.sync_subtitle), fontSize = 11.5.sp, color = C.Ink3)
            }
            TextButton(onClick = { SyncWorker.enqueue(ctx) }) {
                Text(stringResource(R.string.sync_send), color = C.GreenLight) }
        }

        /* Dernier recours quand aucun réseau ne relie le téléphone à la
           machine : on exporte un fichier et on le fait voyager par le canal
           disponible — Bluetooth, câble, carte SD, messagerie. Android sait
           déjà tout cela ; écrire notre propre transport Bluetooth aurait été
           un chantier entier pour un gain nul.
           Sans risque à répéter : le serveur rejette les doublons sur
           « client_batch_uuid ». */
        TextButton(onClick = {
            scope.launch {
                val f = exporterLotsEnAttente(ctx)
                if (f == null) {
                    noteExport = ctx.getString(R.string.sync_export_nothing)
                } else {
                    val uri = FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", f)
                    val envoi = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    noteExport = ctx.getString(R.string.sync_export_ready, f.name)
                    ctx.startActivity(Intent.createChooser(
                        envoi, ctx.getString(R.string.sync_export_chooser)))
                }
            }
        }) { Text(stringResource(R.string.sync_export), color = C.Ocre, fontSize = 12.sp) }

        noteExport?.let {
            Text(it, fontSize = 11.5.sp, color = C.Ink2)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(batches, key = { it.id }) { b -> BatchRow(b) }
        }
    }
}

@Composable
private fun BatchRow(b: BatchEntity) {
    val scope = rememberCoroutineScope()
    var confirmer by remember { mutableStateOf(false) }
    val (label, color) = when (b.status) {
        "PENDING" -> stringResource(R.string.batch_pending) to C.Warn
        "UPLOADING" -> stringResource(R.string.batch_uploading) to C.Ocre
        "ACKED" -> stringResource(R.string.batch_acked) to C.Ok
        else -> b.status to C.Ink3
    }
    SmapCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${b.clientUuid.take(8)} · ${b.kind}",
                     fontSize = 13.sp, color = C.Ink, fontWeight = FontWeight.Medium)
                Text(b.serverReason ?: stringResource(R.string.batch_points, b.pointCount),
                     fontSize = 11.sp, color = C.Ink3, maxLines = 2)
            }
            Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { confirmer = true }) {
                Text(stringResource(R.string.btn_delete), fontSize = 11.sp, color = C.Warn)
            }
        }
    }
    if (confirmer) {
        // Un lot non confirmé n'existe nulle part ailleurs : le supprimer,
        // c'est perdre la tournée. On le dit explicitement plutôt que de
        // poser une question générique à laquelle on répond machinalement.
        val jamaisEnvoye = b.status != "ACKED"
        AlertDialog(
            onDismissRequest = { confirmer = false },
            title = { Text(stringResource(if (jamaisEnvoye) R.string.delete_unsent_title
                                          else R.string.delete_title)) },
            text = {
                Text(if (jamaisEnvoye) stringResource(R.string.delete_unsent_body, b.pointCount)
                     else stringResource(R.string.delete_acked_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmer = false
                    scope.launch {
                        SmapApp.db.dao().deleteFixesOf(b.id)
                        SmapApp.db.dao().deleteBatch(b.id)
                    }
                }) { Text(stringResource(R.string.btn_delete), color = C.Warn) }
            },
            dismissButton = {
                TextButton(onClick = { confirmer = false }) {
                    Text(stringResource(R.string.btn_cancel)) }
            })
    }
}

/* ─────────────────────────────── Réglages ─────────────────────────────── */

@Composable
fun SettingsScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(SmapApp.api.baseUrl) }
    var name by remember { mutableStateOf("A-01") }
    var agent by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(
        if (SmapApp.api.isRegistered)
            ctx.getString(R.string.settings_registered, SmapApp.api.deviceId?.take(8) ?: "")
        else ctx.getString(R.string.settings_not_registered)) }
    val langue = Langue.courante(ctx)

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.settings_title),
             fontSize = 23.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Spacer(Modifier.height(12.dp))

        /* Langue — indépendante du téléphone : un même appareil peut passer
           d'un agent francophone à un partenaire anglophone. La bascule
           recrée l'activité, c'est le seul moyen de faire relire toutes les
           ressources d'un coup ; l'état des écrans est léger, rien n'est perdu. */
        Text(stringResource(R.string.settings_language),
             fontSize = 12.sp, color = C.Ink2)
        Row(Modifier.padding(vertical = 6.dp)) {
            listOf(Langue.FR to R.string.lang_fr, Langue.EN to R.string.lang_en)
                .forEach { (code, libelle) ->
                    val actif = langue == code
                    Button(
                        onClick = {
                            if (!actif) {
                                Langue.definir(ctx, code)
                                (ctx as? Activity)?.recreate()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (actif) C.Green else C.Surface2),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(stringResource(libelle), fontSize = 13.sp) }
                }
        }
        Text(stringResource(R.string.settings_language_note),
             fontSize = 11.sp, color = C.Ink3)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(value = url, onValueChange = { url = it },
            label = { Text(stringResource(R.string.settings_url), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = C.GreenLight, unfocusedBorderColor = C.Line))
        OutlinedTextField(value = name, onValueChange = { name = it },
            label = { Text(stringResource(R.string.settings_device_name), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = C.GreenLight, unfocusedBorderColor = C.Line))
        OutlinedTextField(value = agent, onValueChange = { agent = it },
            label = { Text(stringResource(R.string.settings_agent), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = C.GreenLight, unfocusedBorderColor = C.Line))
        Spacer(Modifier.height(12.dp))
        // Saisir une adresse à la main sur un téléphone est une source
        // d'erreur à elle seule — un caractère manquant et rien ne part, sans
        // que la cause soit lisible. La console affiche l'adresse en QR.
        BigButton(stringResource(R.string.settings_scan_url), C.Surface2) {
            nav.navigate("scan/config")
        }
        // Tester AVANT de partir : sans cela, la panne se découvre au retour
        // de tournée, quand les lots refusent de partir. Trop tard.
        BigButton(stringResource(R.string.settings_test), C.Surface2) {
            status = ctx.getString(R.string.settings_testing)
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                status = SmapApp.api.testerConnexion(url).fold(
                    onSuccess = { ctx.getString(R.string.settings_reachable) },
                    onFailure = { ctx.getString(R.string.settings_unreachable, it.message ?: "") })
            }
        }
        BigButton(stringResource(R.string.settings_register)) {
            SmapApp.api.baseUrl = url
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                status = SmapApp.api.register(name, agent).fold(
                    onSuccess = { ctx.getString(R.string.settings_registered_ok, it.take(8)) },
                    onFailure = { ctx.getString(R.string.settings_failed, it.message ?: "") })
            }
        }
        Text(status, fontSize = 12.5.sp,
             color = if (status.startsWith("✓")) C.GreenLight else C.Ink2)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.settings_key_note), fontSize = 11.5.sp, color = C.Ink3)
    }
}
