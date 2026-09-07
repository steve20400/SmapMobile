package cm.smap.collector.work

import android.content.Context
import cm.smap.collector.SmapApp
import cm.smap.collector.data.BatchEntity
import cm.smap.collector.data.SmapDao
import kotlinx.serialization.json.*
import java.io.File

/**
 * Export des lots vers un fichier, pour les cas où aucun réseau ne relie le
 * téléphone à la machine.
 *
 * Pourquoi ce chemin existe : au Cameroun la coupure n'est pas un incident
 * mais un état fréquent. Un agent peut rentrer avec une journée de collecte et
 * aucun moyen de la pousser — routeur en panne, forfait épuisé, tunnel tombé.
 * Le fichier voyage alors par le canal qu'on a sous la main : Bluetooth, câble
 * USB, carte SD, messagerie.
 *
 * Ce n'est PAS un transport Bluetooth maison. Android sait déjà envoyer un
 * fichier par Bluetooth, avec une pile éprouvée que nous n'aurons ni à écrire
 * ni à déboguer sur le terrain. Un fichier couvre en plus tous les autres
 * canaux d'un coup.
 *
 * Sûr à répéter : le serveur rejette les doublons sur « client_batch_uuid ».
 * L'agent peut donc réimporter sans réfléchir, ce qui compte quand on ne sait
 * plus si le premier envoi est passé.
 *
 * Règle d'écriture, apprise à la compilation : les lambdas de buildJsonObject
 * et putJsonArray ne sont pas des corps de coroutine. Tout appel suspendu
 * (accès Room) doit donc être fait AVANT d'entrer dans le constructeur JSON,
 * jamais à l'intérieur.
 */

/**
 * Construit la charge utile d'un lot. Partagée avec l'envoi réseau : deux
 * constructeurs distincts finiraient par diverger, et le fichier exporté ne
 * représenterait plus ce que le serveur reçoit d'habitude.
 */
suspend fun chargeUtile(dao: SmapDao, batch: BatchEntity): JsonObject = when (batch.kind) {
    "track" -> {
        val fixes = dao.fixesOf(batch.id)            // suspendu : hors du builder
        buildJsonObject {
            putJsonArray("points") {
                fixes.forEach { f ->
                    addJsonObject {
                        put("ts", f.ts); put("lat", f.lat); put("lon", f.lon)
                        put("acc", f.acc)
                        f.alt?.let { put("alt", it) }
                        f.spd?.let { put("spd", it) }
                        f.brg?.let { put("brg", it) }
                        f.sats?.let { put("sats", it) }
                    }
                }
            }
        }
    }
    else -> Json.parseToJsonElement(batch.payloadExtra ?: "{}").jsonObject
}

/**
 * Écrit les lots en attente dans un fichier du cache partageable.
 *
 * Renvoie le fichier, ou null si rien n'est en attente — on préfère ne rien
 * produire plutôt qu'un fichier vide, qui donnerait l'illusion d'un export
 * réussi.
 */
suspend fun exporterLotsEnAttente(ctx: Context): File? {
    val dao = SmapApp.db.dao()
    val lots = dao.pendingBatches(500)
    if (lots.isEmpty()) return null

    // Charges utiles calculées d'abord, en contexte suspendu ; le constructeur
    // JSON ne fait ensuite qu'assembler des valeurs déjà prêtes.
    val charges = lots.map { it to chargeUtile(dao, it) }
    val appareil = SmapApp.api.deviceId

    val contenu = buildJsonObject {
        put("format", "smap-export-1")
        put("exported_at", System.currentTimeMillis())
        // L'identité de l'appareil voyage avec le fichier : le lot doit rester
        // attribué à l'agent qui l'a collecté, pas à celui qui l'importe.
        appareil?.let { put("device_id", it) }
        putJsonArray("batches") {
            charges.forEach { (b, charge) ->
                addJsonObject {
                    put("client_batch_uuid", b.clientUuid)
                    put("kind", b.kind)
                    appareil?.let { put("device_id", it) }
                    put("payload", charge)
                }
            }
        }
    }

    val dossier = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val fichier = File(dossier, "smap-${System.currentTimeMillis()}.json")
    fichier.writeText(contenu.toString())
    return fichier
}
