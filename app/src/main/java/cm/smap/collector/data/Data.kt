package cm.smap.collector.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/* ═════════════════════════════ Entités ═════════════════════════════
   La règle d'or : rien n'est supprimé avant l'accusé serveur (ACKED)
   + 7 jours de marge. Le téléphone est la sauvegarde de dernier recours. */

@Entity(tableName = "fixes")
data class FixEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchLocalId: Long,            // lot local d'appartenance
    val ts: Long,                      // horodatage GPS (ms epoch), jamais l'horloge système
    val lat: Double, val lon: Double,
    val alt: Double?, val acc: Float,
    val spd: Float?, val brg: Float?, val sats: Int?,
)

@Entity(tableName = "batches")
data class BatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientUuid: String,            // idempotence côté serveur
    val kind: String,                  // track | survey | traverse | fingerprint
    val status: String = "PENDING",    // PENDING → UPLOADING → ACKED → PROCESSED
    val payloadExtra: String? = null,  // JSON : ref_code, poses/scans du lever…
    val createdAt: Long = System.currentTimeMillis(),
    val ackedAt: Long? = null,
    val serverReason: String? = null,
    val pointCount: Int = 0,
)

@Entity(tableName = "property_drafts")
data class PropertyDraft(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, val rooms: Int?, val bathrooms: Int?,
    val priceFcfa: Int?, val neighborhood: String?,
    val landmark: String,
    val surveyBatchUuid: String?,      // relie le bien à son relevé 60 s
    val photoPaths: String,            // chemins locaux séparés par |
    val status: String = "PENDING",    // PENDING → SENT
    val createdAt: Long = System.currentTimeMillis(),
)

/* ═════════════════════════════ DAO ═════════════════════════════ */

@Dao
interface SmapDao {
    // Lots
    @Insert suspend fun insertBatch(b: BatchEntity): Long
    @Query("UPDATE batches SET status=:st, serverReason=:reason, ackedAt=:acked WHERE id=:id")
    suspend fun setBatchStatus(id: Long, st: String, reason: String?, acked: Long?)
    @Query("SELECT * FROM batches WHERE status IN ('PENDING','UPLOADING') ORDER BY id LIMIT :n")
    suspend fun pendingBatches(n: Int = 10): List<BatchEntity>
    @Query("SELECT * FROM batches ORDER BY id DESC LIMIT 40")
    fun recentBatches(): Flow<List<BatchEntity>>
    @Query("SELECT COUNT(*) FROM batches WHERE status IN ('PENDING','UPLOADING')")
    fun pendingCount(): Flow<Int>
    // Purge : uniquement les lots confirmés depuis plus de 7 jours
    @Query("DELETE FROM fixes WHERE batchLocalId IN " +
           "(SELECT id FROM batches WHERE status='ACKED' AND ackedAt < :cutoff)")
    suspend fun purgeAckedFixes(cutoff: Long)
    @Query("DELETE FROM batches WHERE status='ACKED' AND ackedAt < :cutoff")
    suspend fun purgeAckedBatches(cutoff: Long)

    /* Suppression manuelle d'un lot par l'agent.
       La purge automatique n'efface qu'après accusé du serveur + 7 jours ;
       celle-ci est décidée par l'agent et peut donc détruire un relevé jamais
       envoyé. L'interface demande confirmation avant de l'appeler.
       Les points sont retirés d'abord : l'inverse laisserait des points
       orphelins, invisibles et impossibles à envoyer. */
    @Query("DELETE FROM fixes WHERE batchLocalId=:batchId")
    suspend fun deleteFixesOf(batchId: Long)
    @Query("DELETE FROM batches WHERE id=:batchId")
    suspend fun deleteBatch(batchId: Long)

    // Points
    @Insert suspend fun insertFix(f: FixEntity)
    @Query("SELECT * FROM fixes WHERE batchLocalId=:batchId ORDER BY ts")
    suspend fun fixesOf(batchId: Long): List<FixEntity>
    @Query("SELECT COUNT(*) FROM fixes") fun fixCount(): Flow<Int>

    /* Lecture pour le visualiseur embarqué. Le plafond est délibéré : une
       tournée produit des milliers de points et un téléphone d'entrée de gamme
       ne peut pas en dessiner un nombre illimité sans devenir inutilisable. */
    @Query("SELECT * FROM fixes ORDER BY batchLocalId, ts LIMIT 20000")
    fun fixesPourCarte(): Flow<List<FixEntity>>
    @Query("SELECT * FROM property_drafts ORDER BY id DESC")
    fun draftsPourCarte(): Flow<List<PropertyDraft>>

    // Biens
    @Insert suspend fun insertDraft(d: PropertyDraft): Long
    @Query("SELECT * FROM property_drafts WHERE status='PENDING'")
    suspend fun pendingDrafts(): List<PropertyDraft>
    @Query("UPDATE property_drafts SET status='SENT' WHERE id=:id")
    suspend fun markDraftSent(id: Long)
}

@Database(entities = [FixEntity::class, BatchEntity::class, PropertyDraft::class],
          version = 1, exportSchema = false)
abstract class SmapDb : RoomDatabase() {
    abstract fun dao(): SmapDao

    companion object {
        @Volatile private var instance: SmapDb? = null

        /** Base chiffrée SQLCipher — la phrase de passe vit dans le Keystore. */
        fun get(ctx: Context, passphrase: ByteArray): SmapDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(ctx, SmapDb::class.java, "smap.db")
                    .openHelperFactory(SupportFactory(SQLiteDatabase.getBytes(
                        passphrase.toString(Charsets.ISO_8859_1).toCharArray())))
                    .build().also { instance = it }
            }
    }
}
