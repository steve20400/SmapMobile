package cm.smap.collector.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import cm.smap.collector.R
import cm.smap.collector.SmapApp
import cm.smap.collector.data.BatchEntity
import cm.smap.collector.ui.SmapColors as C
import cm.smap.collector.work.SyncWorker
import com.google.android.gms.location.LocationServices
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Pose d'ancre par lecture d'un code DÉJÀ présent dans le bâtiment.
 *
 * Le problème de départ est économique : imprimer et plastifier des planches
 * de QR pour chaque bâtiment coûte cher, et il faut y retourner quand une
 * étiquette tombe. Or un couloir d'université est déjà rempli de codes —
 * étiquettes d'inventaire, contrôles d'extincteurs, tableaux électriques.
 *
 * Ce que le serveur attend est une simple chaîne unique. Peu lui importe qui
 * l'a imprimée : `nav_nodes.marker_code` est un texte unique. On enregistre
 * donc ce qu'on trouve, avec sa provenance, et le lot part par la file de
 * synchronisation comme n'importe quelle autre collecte.
 *
 * Un garde-fou de terrain, rappelé à l'écran : ne jamais ancrer sur du
 * mobilier. Une étiquette collée sur une armoire part avec l'armoire, et une
 * ancre qui déménage corrompt silencieusement tout le couloir.
 */
@SuppressLint("MissingPermission")
@Composable
fun ScanScreen(mode: String) {
    val ctx = LocalContext.current
    val cycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val config = mode == "config"

    var codeLu by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var batiment by remember { mutableStateOf("") }
    var niveau by remember { mutableStateOf("0") }
    var repere by remember { mutableStateOf("") }

    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED)
    }
    val demande = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { permission = it }
    LaunchedEffect(Unit) { if (!permission) demande.launch(Manifest.permission.CAMERA) }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text(stringResource(if (config) R.string.scan_config_title else R.string.scan_anchor_title),
             fontSize = 21.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Text(stringResource(if (config) R.string.scan_config_hint else R.string.scan_anchor_hint),
             fontSize = 11.5.sp, color = C.Ink3)
        Spacer(Modifier.height(10.dp))

        if (!permission) {
            Text(stringResource(R.string.scan_camera_needed), fontSize = 12.sp, color = C.Warn)
            BigButton(stringResource(R.string.scan_allow_camera)) {
                demande.launch(Manifest.permission.CAMERA)
            }
            return@Column
        }

        if (codeLu == null) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { c ->
                    val vue = PreviewView(c)
                    val executeur = Executors.newSingleThreadExecutor()
                    val lecteur = BarcodeScanning.getClient()
                    val futur = ProcessCameraProvider.getInstance(c)
                    futur.addListener({
                        val fournisseur = futur.get()
                        val apercu = Preview.Builder().build().also {
                            it.setSurfaceProvider(vue.surfaceProvider)
                        }
                        val analyse = ImageAnalysis.Builder()
                            .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analyse.setAnalyzer(executeur) { image ->
                            val media = image.image
                            if (media == null) { image.close() }
                            else {
                                lecteur.process(
                                    InputImage.fromMediaImage(
                                        media, image.imageInfo.rotationDegrees))
                                    .addOnSuccessListener { codes ->
                                        codes.firstOrNull()?.rawValue?.let { v ->
                                            if (codeLu == null) codeLu = v
                                        }
                                    }
                                    .addOnCompleteListener { image.close() }
                            }
                        }
                        fournisseur.unbindAll()
                        fournisseur.bindToLifecycle(
                            cycle, CameraSelector.DEFAULT_BACK_CAMERA, apercu, analyse)
                    }, ContextCompat.getMainExecutor(c))
                    vue
                })
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.scan_aim), fontSize = 12.sp, color = C.Ink2)
        } else {
            SmapCard {
                Text(stringResource(R.string.scan_code_read), fontSize = 11.sp, color = C.Ink3)
                Text(codeLu!!, fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.Medium)
            }

            if (config) {
                BigButton(stringResource(R.string.scan_use_address)) {
                    SmapApp.api.baseUrl = codeLu!!.trim()
                    message = ctx.getString(R.string.scan_address_saved, SmapApp.api.baseUrl)
                }
            } else {
                OutlinedTextField(batiment, { batiment = it },
                    label = { Text(stringResource(R.string.scan_building), fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                OutlinedTextField(niveau, { niveau = it },
                    label = { Text(stringResource(R.string.scan_level), fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                OutlinedTextField(repere, { repere = it },
                    label = { Text(stringResource(R.string.scan_where), fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp))

                Spacer(Modifier.height(8.dp))
                BigButton(stringResource(R.string.scan_place_anchor)) {
                    LocationServices.getFusedLocationProviderClient(ctx)
                        .lastLocation.addOnSuccessListener { loc ->
                            if (loc == null) {
                                // Une ancre sans position n'ancre rien : on le
                                // dit ici plutôt que de laisser le serveur la
                                // mettre en quarantaine après coup.
                                message = ctx.getString(R.string.scan_no_position)
                                return@addOnSuccessListener
                            }
                            val charge = buildString {
                                append("""{"marker_code":${q(codeLu!!)}""")
                                append(""","source":"code_externe"""")
                                append(""","kind":"junction"""")
                                append(""","lon":${loc.longitude},"lat":${loc.latitude}""")
                                append(""","precision_m":${loc.accuracy}""")
                                if (batiment.isNotBlank())
                                    append(""","building_code":${q(batiment)}""")
                                niveau.toIntOrNull()?.let { append(""","level_ordinal":$it""") }
                                if (repere.isNotBlank()) append(""","landmark":${q(repere)}""")
                                append("}")
                            }
                            scope.launch {
                                SmapApp.db.dao().insertBatch(BatchEntity(
                                    clientUuid = UUID.randomUUID().toString(),
                                    kind = "anchor", payloadExtra = charge, pointCount = 1))
                                SyncWorker.enqueue(ctx)
                                message = ctx.getString(R.string.scan_anchor_saved)
                                codeLu = null
                            }
                        }
                }
            }
            BigButton(stringResource(R.string.scan_another), C.Surface2) {
                codeLu = null; message = null
            }
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.5.sp, color = C.GreenLight)
        }
    }
}

/** Échappement JSON minimal — même règle que le service de collecte. */
private fun q(s: String) =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
