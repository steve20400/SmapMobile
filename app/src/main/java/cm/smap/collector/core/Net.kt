package cm.smap.collector.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/* ═══════════════ Clés d'appareil (Android Keystore) ═══════════════
   La clé privée EC P-256 est générée DANS le Keystore matériel et n'en
   sort jamais. Le serveur ne connaît que la clé publique : un téléphone
   volé se révoque côté serveur, rien à changer sur les autres. */

object DeviceKeys {
    private const val ALIAS = "smap-device-key"

    private val ks get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun ensureKey() {
        if (ks.containsAlias(ALIAS)) return
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build())
        kpg.generateKeyPair()
    }

    fun publicKeyPem(): String {
        ensureKey()
        val pub = ks.getCertificate(ALIAS).publicKey.encoded
        val b64 = Base64.encodeToString(pub, Base64.NO_WRAP).chunked(64).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
    }

    fun sign(message: String): String {
        val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        val sig = Signature.getInstance("SHA256withECDSA")
            .apply { initSign(entry.privateKey); update(message.toByteArray()) }
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    /** Phrase de passe stable de la base SQLCipher, dérivée de la clé publique. */
    fun dbPassphrase(): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(publicKeyPem().toByteArray())
}

/* ═══════════════════════ Client API SMAP ═══════════════════════ */

class SmapApi(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("smap", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    var baseUrl: String
        get() = prefs.getString("baseUrl", "") ?: ""
        set(v) { prefs.edit().putString("baseUrl", v.trimEnd('/')).apply() }

    var deviceId: String?
        get() = prefs.getString("deviceId", null)
        set(v) { prefs.edit().putString("deviceId", v).apply() }

    private var token: String?
        get() = prefs.getString("token", null)
        set(v) { prefs.edit().putString("token", v).apply() }

    val isRegistered get() = deviceId != null && baseUrl.isNotBlank()

    /**
     * Vérifie qu'une adresse répond AVANT de partir collecter.
     *
     * Sans ce contrôle, la panne se découvre au retour de tournée, quand les
     * lots refusent de partir — c'est-à-dire trop tard. Le message distingue
     * les causes, parce que « ça ne marche pas » n'aide personne : une adresse
     * injoignable, un serveur qui répond mais n'est pas SMAP, ou un serveur
     * sain appellent trois gestes différents.
     */
    fun testerConnexion(adresse: String): Result<String> = runCatching {
        val base = adresse.trim().trimEnd('/')
        require(base.isNotBlank()) { "adresse vide" }
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "l'adresse doit commencer par http:// ou https://"
        }
        val req = Request.Builder().url("$base/health").get().build()
        http.newCall(req).execute().use { r ->
            val corps = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("le serveur répond ${r.code}")
            if (!corps.contains("smap-api")) {
                error("quelque chose répond, mais ce n'est pas SMAP")
            }
            corps
        }
    }

    /** Enregistrement initial : une seule fois par téléphone. */
    fun register(name: String, agent: String): Result<String> = runCatching {
        val body = buildJsonObject {
            put("name", name)
            put("model", android.os.Build.MODEL)
            put("agent_name", agent)
            put("public_key_pem", DeviceKeys.publicKeyPem())
        }
        val resp = call("POST", "/v1/devices/register", body.toString())
        json.parseToJsonElement(resp).jsonObject["device_id"]!!.jsonPrimitive.content
            .also { deviceId = it }
    }

    /** Jeton de session : signature ES256 de "device_id.timestamp". */
    private fun refreshToken() {
        val id = deviceId ?: error("appareil non enregistré")
        val ts = System.currentTimeMillis() / 1000
        val body = buildJsonObject {
            put("device_id", id); put("ts", ts)
            put("signature", DeviceKeys.sign("$id.$ts"))
        }
        val resp = call("POST", "/v1/auth/token", body.toString())
        token = json.parseToJsonElement(resp).jsonObject["access_token"]!!.jsonPrimitive.content
    }

    /** Envoi d'un lot, JSON compressé gzip. Retourne (status, duplicate). */
    fun sendBatch(clientUuid: String, kind: String, payload: JsonObject): Pair<String, Boolean> {
        val doc = buildJsonObject {
            put("client_batch_uuid", clientUuid); put("kind", kind); put("payload", payload)
        }.toString()
        val gz = ByteArrayOutputStream().also { bo ->
            GZIPOutputStream(bo).use { it.write(doc.toByteArray()) }
        }.toByteArray()
        val resp = authed("POST", "/v1/ingest/batch", gz.toRequestBody(jsonType),
                          gzip = true)
        val o = json.parseToJsonElement(resp).jsonObject
        return o["status"]!!.jsonPrimitive.content to
               (o["duplicate"]?.jsonPrimitive?.booleanOrNull ?: false)
    }

    fun batchStatus(clientUuid: String): Pair<String, String?> {
        val resp = authed("GET", "/v1/ingest/status/$clientUuid", null)
        val o = json.parseToJsonElement(resp).jsonObject
        return o["status"]!!.jsonPrimitive.content to
               o["reason"]?.jsonPrimitive?.contentOrNull
    }

    fun createProperty(d: JsonObject): Long {
        val resp = authed("POST", "/v1/properties", d.toString().toRequestBody(jsonType))
        return json.parseToJsonElement(resp).jsonObject["id"]!!.jsonPrimitive.long
    }

    fun uploadPhoto(file: File, propertyId: Long?, kind: String, bearing: Float?) {
        val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name,
                file.asRequestBody("image/jpeg".toMediaType()))
            .addFormDataPart("kind", kind)
        propertyId?.let { mp.addFormDataPart("property_id", it.toString()) }
        bearing?.let { mp.addFormDataPart("bearing_deg", it.toString()) }
        authed("POST", "/v1/ingest/media", mp.build())
    }

    /* ── plomberie HTTP ── */
    private fun authed(method: String, path: String, body: RequestBody?,
                       gzip: Boolean = false, retried: Boolean = false): String {
        if (token == null) refreshToken()
        return try {
            call(method, path, body, mapOf("Authorization" to "Bearer $token")
                    + if (gzip) mapOf("Content-Encoding" to "gzip") else emptyMap())
        } catch (e: ApiError) {
            if (e.code == 401 && !retried) { refreshToken(); authed(method, path, body, gzip, true) }
            else throw e
        }
    }

    private fun call(method: String, path: String, body: Any?,
                     headers: Map<String, String> = emptyMap()): String {
        val rb: RequestBody? = when (body) {
            null -> null
            is RequestBody -> body
            is String -> body.toRequestBody(jsonType)
            else -> error("corps invalide")
        }
        val req = Request.Builder().url(baseUrl + path).method(method, rb)
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw ApiError(r.code, text.take(300))
            return text
        }
    }

    class ApiError(val code: Int, message: String) : Exception("HTTP $code : $message")
}
