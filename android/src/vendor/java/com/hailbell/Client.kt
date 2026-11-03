package com.hailbell

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class Client(private val context: Context, val appId: String, val baseUrl: String) {
    @Volatile var token: String? = null

    val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()
    private val uploads = http.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()

    suspend fun startSession(identity: Identity): Session {
        val metadata = JSONObject()
        (deviceMetadata() + identity.metadata).forEach { (k, v) -> metadata.put(k, v) }
        val body = JSONObject().put("appId", appId).put("anonymousId", installId(context)).put("metadata", metadata)
            .put("timeZone", TimeZone.getDefault().id)
        identity.userId?.let { body.put("userId", it) }
        identity.name?.let { body.put("name", it) }
        identity.email?.let { body.put("email", it) }
        identity.identityToken?.let { body.put("identityToken", it) }
        // A fetch that fails (offline, Firebase busy) must not fall back to the anonymous chat: nothing
        // would sign them in again until the next launch, so the error fails the session and it is
        // retried with backoff. No token at all means they signed out: the install's anonymous chat.
        identity.firebaseIdToken?.let { fetch ->
            val idToken = try { fetch() } catch (e: Exception) { throw java.io.IOException("Firebase ID token fetch failed", e) }
            idToken?.let { body.put("firebaseIdToken", it) }
        }
        // The caller stores session.token: only the newest of several overlapping sessions may.
        return Session.parse(request("POST", "/v1/session", body, authorized = false))
    }

    class Page(val messages: List<Message>, val hasMore: Boolean?, val agentReadAt: Double?, val agentDeliveredAt: Double?)

    /** With [after], what arrived since then; otherwise the latest page, or the page before [before]. */
    suspend fun messages(after: Double = 0.0, before: Double? = null): Page {
        val path = when {
            after > 0 -> "/v1/messages?after=${after.toLong()}"
            before != null -> "/v1/messages?before=${before.toLong()}"
            else -> "/v1/messages"
        }
        val o = request("GET", path)
        return Page(
            o.optJSONArray("messages").objects().map(Message::parse),
            o.optBoolOrNull("hasMore"), o.optDoubleOrNull("agentReadAt"), o.optDoubleOrNull("agentDeliveredAt"),
        )
    }

    suspend fun deleteData() { request("DELETE", "/v1/me") }

    suspend fun send(text: String, attachments: List<String>, clientId: String): Message {
        val body = JSONObject().put("text", text).put("attachments", JSONArray(attachments)).put("clientId", clientId)
        return Message.parse(request("POST", "/v1/messages", body).getJSONObject("message"))
    }

    /** Uploads one JPEG and returns its id, which a following [send] attaches. */
    suspend fun upload(jpeg: ByteArray, width: Int, height: Int): String {
        val token = token ?: throw ApiError(401, "Chat is still connecting")
        val req = Request.Builder().url(url("/v1/attachments"))
            .header("Authorization", "Bearer $token")
            .header("X-Filename", "photo.jpg")
            .header("X-Width", width.toString())
            .header("X-Height", height.toString())
            .post(jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        return execute(uploads, req, "Upload failed").getJSONObject("attachment").getString("id")
    }

    suspend fun cardAction(messageId: String, action: String) {
        request("POST", "/v1/cards/$messageId", JSONObject().put("action", action))
    }

    suspend fun reportPlacements(placements: List<String>) {
        request("POST", "/v1/placements", JSONObject().put("placements", JSONArray(placements)))
    }

    suspend fun markRead() { request("POST", "/v1/read", JSONObject()) }

    suspend fun track(name: String, properties: Map<String, String>) {
        request("POST", "/v1/events", JSONObject().put("name", name).put("props", JSONObject(properties)))
    }

    suspend fun registerPush(token: String) {
        request("POST", "/v1/push-token", JSONObject().put("token", token).put("platform", "fcm").put("environment", "production"))
    }

    fun socketUrl(): String? {
        val token = token ?: return null
        val http = url("/v1/socket").newBuilder().addQueryParameter("token", token).build().toString()
        return if (http.startsWith("http://")) "ws://" + http.removePrefix("http://") else "wss://" + http.removePrefix("https://")
    }

    /** An absolute URL for a path the API returned, such as an image's. */
    fun resolve(path: String): String = url(path).toString()

    private fun url(path: String) = baseUrl.toHttpUrl().resolve(path) ?: throw ApiError(0, "Bad URL")

    suspend fun request(method: String, path: String, body: JSONObject? = null, authorized: Boolean = true): JSONObject {
        val builder = Request.Builder().url(url(path))
        if (authorized) {
            val token = token ?: throw ApiError(401, "Chat is still connecting")
            builder.header("Authorization", "Bearer $token")
        }
        val payload = body?.toString()?.toRequestBody(JSON)
        builder.method(method, payload ?: if (method == "GET") null else "".toRequestBody(JSON))
        return execute(http, builder.build(), "Request failed")
    }

    private suspend fun execute(client: OkHttpClient, req: Request, fallback: String): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val message = runCatching { JSONObject(text).optStringOrNull("error") }.getOrNull()
                throw ApiError(res.code, message ?: "$fallback (${res.code})")
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun deviceMetadata(): Map<String, String> = mapOf(
        "appVersion" to appVersion(context),
        "os" to "Android ${Build.VERSION.RELEASE}",
        "locale" to Locale.getDefault().toLanguageTag(),
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    companion object {
        private val JSON = "application/json".toMediaType()

        fun appVersion(context: Context): String = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${info.versionName} ($code)"
        }.getOrDefault("?")

        /** A random id for this install. Android Auto Backup restores it on a new phone with the app's data. */
        // Synchronized: two sessions starting at once on a fresh install must not mint two ids.
        @Synchronized
        fun installId(context: Context): String {
            val prefs = prefs(context)
            prefs.getString("install-id", null)?.let { return it }
            val id = "${UUID.randomUUID()}-${UUID.randomUUID()}".uppercase()
            prefs.edit().putString("install-id", id).apply()
            return id
        }

        fun prefs(context: Context) = context.getSharedPreferences("hailbell", Context.MODE_PRIVATE)
    }
}

internal data class Identity(
    val userId: String? = null,
    val name: String? = null,
    val email: String? = null,
    val identityToken: String? = null,
    /** Fetches a current Firebase ID token for each session. Null when the app doesn't use Firebase Auth. */
    val firebaseIdToken: (suspend () -> String?)? = null,
    val metadata: Map<String, String> = emptyMap(),
)
