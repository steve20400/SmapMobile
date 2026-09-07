package cm.smap.collector.work

import android.content.Context
import androidx.work.*
import cm.smap.collector.SmapApp
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit

/**
 * Synchronisation résiliente des lots vers le serveur.
 *
 * Contrat (voir documentation, protocole de synchronisation) :
 * - ne part que si le réseau est disponible (contrainte WorkManager) ;
 * - repli exponentiel automatique en cas d'échec (30 s → … → plafond) ;
 * - un lot n'est marqué ACKED qu'après réponse du serveur ; l'identifiant
 *   `client_batch_uuid` rend tout renvoi inoffensif (idempotence) ;
 * - purge locale uniquement 7 jours APRÈS l'accusé.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val api = SmapApp.api
        if (!api.isRegistered) return Result.success()      // rien à faire avant l'enregistrement
        val dao = SmapApp.db.dao()

        val pending = dao.pendingBatches()
        var failures = 0

        for (batch in pending) {
            try {
                dao.setBatchStatus(batch.id, "UPLOADING", null, null)
                val payload: JsonObject = when (batch.kind) {
                    "track" -> {
                        val fixes = dao.fixesOf(batch.id)
                        if (fixes.isEmpty()) {              // lot vide : rien à envoyer
                            dao.setBatchStatus(batch.id, "ACKED", "vide",
                                               System.currentTimeMillis())
                            continue
                        }
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

                api.sendBatch(batch.clientUuid, batch.kind, payload)
                // on interroge l'état de traitement (consolidation, quarantaine…)
                val (status, reason) = runCatching { api.batchStatus(batch.clientUuid) }
                    .getOrDefault("processed" to null)
                dao.setBatchStatus(batch.id, "ACKED",
                    if (status == "quarantined") "quarantaine : $reason" else reason,
                    System.currentTimeMillis())
            } catch (e: Exception) {
                failures++
                dao.setBatchStatus(batch.id, "PENDING", e.message?.take(200), null)
            }
        }

        // Brouillons de biens en attente
        for (draft in dao.pendingDrafts()) {
            try {
                val body = buildJsonObject {
                    put("type", draft.type)
                    draft.rooms?.let { put("rooms", it) }
                    draft.bathrooms?.let { put("bathrooms", it) }
                    draft.priceFcfa?.let { put("price_fcfa", it) }
                    draft.neighborhood?.let { put("neighborhood", it) }
                    put("landmark", draft.landmark)
                    // le point consolidé du relevé lié, si le serveur l'a déjà calculé
                    draft.surveyBatchUuid?.let { }
                    put("lon", SmapApp.lastKnownLon); put("lat", SmapApp.lastKnownLat)
                }
                val pid = SmapApp.api.createProperty(body)
                draft.photoPaths.split("|").filter { it.isNotBlank() }.forEach { p ->
                    runCatching {
                        SmapApp.api.uploadPhoto(java.io.File(p), pid, "photo", null)
                    }
                }
                dao.markDraftSent(draft.id)
            } catch (_: Exception) { failures++ }
        }

        // Purge de sécurité : ACKED depuis plus de 7 jours
        val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        dao.purgeAckedFixes(cutoff)
        dao.purgeAckedBatches(cutoff)

        return if (failures == 0) Result.success() else Result.retry()
    }

    companion object {
        /** Envoi dès que le réseau le permet (appelé à chaque fin de lot). */
        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("smap-sync", ExistingWorkPolicy.KEEP, req)
        }

        /** Filet de sécurité : tentative périodique toutes les 30 min. */
        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "smap-sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
