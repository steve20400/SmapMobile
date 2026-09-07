package cm.smap.collector.ui.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cm.smap.collector.R
import cm.smap.collector.SmapApp
import cm.smap.collector.data.FixEntity
import cm.smap.collector.ui.SmapColors as C
import com.google.android.gms.location.LocationServices
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/* ─────────────────────────── Géométrie ───────────────────────────
   Repris à l'identique du rendu de la console : une distance mesurée sur le
   téléphone et la même distance mesurée sur le PC doivent donner le même
   nombre, sans quoi l'agent cesse de faire confiance à l'outil. */

fun distanceM(a: GeoPoint, b: GeoPoint): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
            sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(s))
}

private fun longueurEntre(pts: List<GeoPoint>, i: Int, j: Int): Double {
    val a = minOf(i, j)
    val b = maxOf(i, j)
    var d = 0.0
    for (k in a until b) d += distanceM(pts[k], pts[k + 1])
    return d
}

/** Lissage de Chaikin : arrondit les angles vifs du GPS sans s'éloigner des
 *  mesures, en remplaçant chaque segment par deux points au quart et aux
 *  trois quarts. Deux passes — au-delà, on gagne en douceur ce qu'on perd en
 *  fidélité, ce qui serait malhonnête pour une donnée de relevé. */
private fun lisser(pts: List<GeoPoint>, passes: Int = 2): List<GeoPoint> {
    var p = pts
    repeat(passes) {
        if (p.size > 2) {
            val out = ArrayList<GeoPoint>(p.size * 2)
            out.add(p.first())
            for (i in 0 until p.size - 1) {
                val a = p[i]
                val b = p[i + 1]
                out.add(GeoPoint(a.latitude * .75 + b.latitude * .25,
                                 a.longitude * .75 + b.longitude * .25))
                out.add(GeoPoint(a.latitude * .25 + b.latitude * .75,
                                 a.longitude * .25 + b.longitude * .75))
            }
            out.add(p.last())
            p = out
        }
    }
    return p
}

private fun fmtDist(m: Double) =
    if (m < 1000) "%.1f m".format(m) else "%.2f km".format(m / 1000)

private data class Trouvaille(val libelle: String, val detail: String, val point: GeoPoint)

/* ─────────────────────────── Écran ─────────────────────────── */

@SuppressLint("MissingPermission")
@Composable
fun MapScreen() {
    val ctx = LocalContext.current
    val fixes by SmapApp.db.dao().fixesPourCarte().collectAsState(initial = emptyList())
    val drafts by SmapApp.db.dao().draftsPourCarte().collectAsState(initial = emptyList())

    var mesure by remember { mutableStateOf<String?>(null) }
    var requete by remember { mutableStateOf("") }
    var legendeOuverte by remember { mutableStateOf(false) }
    val mapRef = remember { mutableStateOf<MapView?>(null) }

    /* La sélection doit SURVIVRE à la recomposition. Déclarée dans le bloc
       « update », elle était remise à zéro dès l'affichage du message du
       premier point : le second toucher repartait donc de zéro et la mesure
       était impossible. */
    val lotChoisi = remember { mutableStateOf<Long?>(null) }
    val indiceChoisi = remember { mutableStateOf(0) }

    // Un lot = une portion continue de tournée. On ne relie jamais deux lots :
    // la droite tracée entre eux serait une rue qui n'existe pas.
    val parLot: Map<Long, List<FixEntity>> = remember(fixes) {
        fixes.groupBy { it.batchLocalId }.filterValues { it.size >= 2 }
    }

    // Résolu ici, en contexte composable, puis réutilisé dans le rappel de la
    // carte qui, lui, n'a pas accès à stringResource.
    val libApprox = stringResource(R.string.map_approx_position)

    val index: List<Trouvaille> = remember(parLot, drafts) {
        buildList {
            parLot.forEach { (lot, pts) ->
                add(Trouvaille(ctx.getString(R.string.map_track, lot), "${pts.size} points",
                               GeoPoint(pts.first().lat, pts.first().lon)))
            }
            val dernier = parLot.values.lastOrNull()?.lastOrNull()
            if (dernier != null) drafts.forEach { d ->
                add(Trouvaille(d.landmark.take(40),
                               listOfNotNull(d.type, d.neighborhood).joinToString(" · "),
                               GeoPoint(dernier.lat, dernier.lon)))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.map_title),
                     fontSize = 21.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                Text(stringResource(R.string.map_subtitle), fontSize = 11.sp, color = C.Ink3)
            }
            TextButton(onClick = {
                LocationServices.getFusedLocationProviderClient(ctx)
                    .lastLocation.addOnSuccessListener { loc ->
                        if (loc == null) {
                            mesure = ctx.getString(R.string.map_no_position)
                        } else {
                            mapRef.value?.controller?.animateTo(
                                GeoPoint(loc.latitude, loc.longitude))
                            mapRef.value?.controller?.setZoom(18.0)
                        }
                    }
            }) { Text(stringResource(R.string.map_my_position), color = C.GreenLight, fontSize = 12.sp) }
        }

        OutlinedTextField(
            value = requete, onValueChange = { requete = it },
            label = { Text(stringResource(R.string.map_search), fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp))

        val trouves = index.filter {
            requete.length >= 2 &&
                "${it.libelle} ${it.detail}".contains(requete, ignoreCase = true)
        }.take(5)
        if (trouves.isNotEmpty()) {
            LazyColumn(Modifier.heightIn(max = 150.dp).padding(horizontal = 14.dp)) {
                items(trouves) { t ->
                    TextButton(onClick = {
                        mapRef.value?.controller?.animateTo(t.point)
                        mapRef.value?.controller?.setZoom(19.0)
                        requete = ""
                    }) { Text("${t.libelle} — ${t.detail}", fontSize = 12.sp, color = C.Ink) }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    /* La politique d'usage d'OpenStreetMap impose un agent
                       identifiable. Sans lui les tuiles finissent refusées —
                       et c'est l'application entière qui devient aveugle,
                       pas seulement une requête. */
                    Configuration.getInstance().userAgentValue = c.packageName
                    MapView(c).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(16.0)
                        mapRef.value = this
                    }
                },
                update = { map ->
                    map.overlays.clear()
                    var centre: GeoPoint? = null

                    parLot.forEach { (lot, pts) ->
                        val brut = pts.map { GeoPoint(it.lat, it.lon) }
                        if (centre == null) centre = brut.first()
                        val ligne = Polyline(map).apply {
                            setPoints(lisser(brut))
                            outlinePaint.strokeWidth = 8f
                            outlinePaint.color = android.graphics.Color.parseColor("#C2410C")
                        }
                        ligne.setOnClickListener { _, _, pos ->
                            /* La mesure porte sur les points BRUTS, jamais sur
                               le tracé lissé : lisser puis mesurer donnerait un
                               chiffre qui n'a été relevé nulle part. */
                            var idx = 0
                            var best = Double.MAX_VALUE
                            brut.forEachIndexed { i, p ->
                                val d = distanceM(p, pos)
                                if (d < best) { best = d; idx = i }
                            }
                            if (lotChoisi.value != lot) {
                                lotChoisi.value = lot
                                indiceChoisi.value = idx
                                mesure = ctx.getString(R.string.map_point_a)
                            } else {
                                val parcouru = longueurEntre(brut, indiceChoisi.value, idx)
                                val vol = distanceM(brut[indiceChoisi.value], brut[idx])
                                mesure = ctx.getString(R.string.map_distance,
                                                       fmtDist(parcouru), fmtDist(vol))
                                lotChoisi.value = null
                            }
                            true
                        }
                        map.overlays.add(ligne)
                    }

                    /* Les biens enregistrés n'ont pas encore de position
                       consolidée — elle est calculée par le serveur. On les
                       pose donc sur la dernière position connue, et on le dit
                       dans l'infobulle plutôt que de laisser croire à une
                       position mesurée. */
                    val dernier = parLot.values.lastOrNull()?.lastOrNull()
                    if (dernier != null) drafts.forEach { d ->
                        map.overlays.add(Marker(map).apply {
                            position = GeoPoint(dernier.lat, dernier.lon)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = d.landmark
                            snippet = listOfNotNull(d.type, d.neighborhood)
                                .joinToString(" · ") + libApprox
                        })
                    }

                    centre?.let { map.controller.setCenter(it) }
                    map.invalidate()
                })

            /* Légende repliée par défaut, et en feuille depuis le bas. Un
               panneau flottant masquerait le centre de la carte, c'est-à-dire
               exactement là où l'agent regarde. */
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                mesure?.let {
                    Surface(color = C.Surface2) {
                        Row(Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(it, fontSize = 12.sp, color = C.Ink,
                                 modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                mesure = null
                                lotChoisi.value = null
                            }) { Text(stringResource(R.string.map_clear), fontSize = 11.sp, color = C.Warn) }
                        }
                    }
                }
                Surface(color = C.Surface) {
                    Column(Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)) {
                        TextButton(onClick = { legendeOuverte = !legendeOuverte }) {
                            Text(stringResource(if (legendeOuverte) R.string.map_legend_hide
                                                else R.string.map_legend),
                                 fontSize = 12.sp, color = C.GreenLight)
                        }
                        if (legendeOuverte) {
                            Text(stringResource(R.string.map_legend_track),
                                 fontSize = 11.sp, color = C.Ink2)
                            Text(stringResource(R.string.map_legend_property),
                                 fontSize = 11.sp, color = C.Ink2)
                            Text(stringResource(R.string.map_legend_measure),
                                 fontSize = 11.sp, color = C.Ink3)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.map_legend_counts,
                                                parLot.size, fixes.size, drafts.size),
                                 fontSize = 11.sp, color = C.Ink3)
                        }
                    }
                }
            }
        }
    }
}
