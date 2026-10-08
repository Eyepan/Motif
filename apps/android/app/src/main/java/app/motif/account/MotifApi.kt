package app.motif.account

import app.motif.data.HistoryEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** An error the server returned (`{"error": {"code", "message"}}`), or no answer at all (status 0). */
class ApiException(val status: Int, val code: String, override val message: String) : Exception(message) {
    val isNetwork get() = status == 0
}

data class DeviceInfo(val name: String, val platform: String = "android")

data class AuthSession(val userId: String, val accessToken: String, val expiresIn: Int, val refreshToken: String)

data class Account(val userId: String, val username: String?, val displayName: String?, val createdAtMs: Long)

data class DeviceSession(
    val id: String,
    val deviceName: String?,
    val platform: String?,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val current: Boolean,
)

data class HistorySummary(val events: Int, val plays: Int, val listenedMs: Long, val lastUploadAtMs: Long?, val bytes: Long)

data class ServerHealth(val status: String, val version: String, val database: String, val region: String?) {
    val isOk get() = status == "ok"
}

data class UsernameCheck(val username: String, val available: Boolean)

data class EventPage(val events: List<HistoryEvent>, val nextCursor: String, val hasMore: Boolean)

/** Inserted plus duplicates mean the server has the event; [rejected] are batch positions it never will accept. */
data class UploadResult(val acknowledged: Int, val rejected: List<Int>)

/** The refresh token between launches. */
interface TokenStore {
    fun load(): StoredSession?
    fun save(session: StoredSession?)
}

data class StoredSession(val userId: String, val refreshToken: String)

class MemoryTokenStore(private var session: StoredSession? = null) : TokenStore {
    @Synchronized override fun load() = session
    @Synchronized override fun save(session: StoredSession?) {
        this.session = session
    }
}

/** Sends one request; a seam so tests answer without a network. Returns status and body. */
fun interface Transport {
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String>
}

object UrlConnectionTransport : Transport {
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            return status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * The Motif server API (docs/server.md, schemas/api/openapi.yaml). Keeps the access token in
 * memory and the refresh token in a [TokenStore]; on a 401 it refreshes once and retries.
 */
class MotifApi(
    val baseUrl: String,
    private val tokens: TokenStore,
    private val transport: Transport = UrlConnectionTransport,
) {
    private var accessToken: String? = null
    private var accessExpiresAt = 0L
    private val refreshLock = Mutex()

    val isSignedIn get() = tokens.load() != null
    val userId get() = tokens.load()?.userId

    suspend fun register(username: String, password: String, device: DeviceInfo) = signIn("/v1/auth/register", username, password, device)

    suspend fun login(username: String, password: String, device: DeviceInfo) = signIn("/v1/auth/login", username, password, device)

    private suspend fun signIn(path: String, username: String, password: String, device: DeviceInfo) {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("device", JSONObject().put("name", device.name).put("platform", device.platform))
        store(parseSession(JSONObject(send("POST", path, body = body, auth = false))))
    }

    suspend fun checkUsername(username: String): UsernameCheck {
        val obj = JSONObject(send("GET", "/v1/auth/username", query = listOf("username" to username), auth = false))
        return UsernameCheck(obj.getString("username"), obj.getBoolean("available"))
    }

    suspend fun changePassword(current: String, new: String) {
        val body = JSONObject().put("current_password", current).put("new_password", new)
        store(parseSession(JSONObject(send("POST", "/v1/auth/password", body = body))))
    }

    /** Forgets the session on this device; tells the server when it can. */
    suspend fun logout() {
        tokens.load()?.let { stored ->
            runCatching { send("POST", "/v1/auth/logout", body = JSONObject().put("refresh_token", stored.refreshToken), auth = false) }
        }
        clear()
    }

    suspend fun me(): Account = parseAccount(JSONObject(send("GET", "/v1/me")))

    suspend fun updateAccount(username: String?, displayName: String?): Account {
        val body = JSONObject()
        username?.let { body.put("username", it) }
        displayName?.let { body.put("display_name", it) }
        return parseAccount(JSONObject(send("PATCH", "/v1/me", body = body)))
    }

    /** Deletes the account and its synced history on the server, then signs out here. */
    suspend fun deleteAccount(password: String) {
        send("DELETE", "/v1/me", body = JSONObject().put("password", password))
        clear()
    }

    suspend fun sessions(): List<DeviceSession> {
        val array = JSONObject(send("GET", "/v1/sessions")).getJSONArray("sessions")
        return List(array.length()) { i ->
            val s = array.getJSONObject(i)
            DeviceSession(
                id = s.getString("id"),
                deviceName = s.optStringOrNull("device_name"),
                platform = s.optStringOrNull("platform"),
                createdAtMs = s.getLong("created_at_ms"),
                lastUsedAtMs = s.getLong("last_used_at_ms"),
                current = s.optBoolean("current"),
            )
        }
    }

    suspend fun signOut(sessionId: String) {
        send("DELETE", "/v1/sessions/${enc(sessionId)}")
    }

    suspend fun signOutOtherDevices(): Int = JSONObject(send("DELETE", "/v1/sessions")).optInt("signed_out")

    suspend fun historySummary(): HistorySummary {
        val o = JSONObject(send("GET", "/v1/history/summary"))
        return HistorySummary(
            events = o.optInt("events"), plays = o.optInt("plays"), listenedMs = o.optLong("listened_ms"),
            lastUploadAtMs = if (o.isNull("last_upload_at_ms")) null else o.optLong("last_upload_at_ms"),
            bytes = o.optLong("bytes"),
        )
    }

    /** Deletes play, transition, app_session and search events with from <= at < to (null: open). */
    suspend fun deleteHistory(fromMs: Long?, toMs: Long?): Int {
        val body = JSONObject()
        fromMs?.let { body.put("from_ms", it) }
        toMs?.let { body.put("to_ms", it) }
        return JSONObject(send("DELETE", "/v1/history", body = body)).optInt("deleted")
    }

    suspend fun health(): ServerHealth {
        val o = JSONObject(send("GET", "/health", auth = false))
        return ServerHealth(o.getString("status"), o.optString("version"), o.optString("database"), o.optStringOrNull("region"))
    }

    suspend fun upload(events: List<HistoryEvent>): UploadResult {
        val body = JSONObject().put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })
        return parseUpload(JSONObject(send("POST", "/v1/events", body = body)))
    }

    suspend fun pull(after: String?, excludeDevice: String, limit: Int = 500): EventPage {
        val query = listOfNotNull("limit" to limit.toString(), "exclude_device" to excludeDevice, after?.let { "after" to it })
        return parsePage(JSONObject(send("GET", "/v1/events", query = query)))
    }

    // Plumbing

    private fun store(session: AuthSession) {
        accessToken = session.accessToken
        // A little early, so a token never expires mid-request.
        accessExpiresAt = System.currentTimeMillis() + (session.expiresIn - 30).coerceAtLeast(0) * 1000L
        tokens.save(StoredSession(session.userId, session.refreshToken))
    }

    private fun clear() {
        accessToken = null
        accessExpiresAt = 0
        tokens.save(null)
    }

    private suspend fun send(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: JSONObject? = null,
        auth: Boolean = true,
    ): String {
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?" + query.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        if (!auth) return perform(method, url, null, body)
        ensureAccessToken()
        return try {
            perform(method, url, accessToken, body)
        } catch (e: ApiException) {
            if (e.status != 401) throw e
            // The access token may have been revoked or expired early: refresh once and retry.
            accessToken = null
            ensureAccessToken()
            perform(method, url, accessToken, body)
        }
    }

    private suspend fun ensureAccessToken() = refreshLock.withLock {
        if (accessToken != null && System.currentTimeMillis() < accessExpiresAt) return@withLock
        val stored = tokens.load() ?: throw ApiException(401, "unauthorized", "You're signed out.")
        try {
            val body = JSONObject().put("refresh_token", stored.refreshToken)
            store(parseSession(JSONObject(perform("POST", baseUrl.trimEnd('/') + "/v1/auth/refresh", null, body))))
        } catch (e: ApiException) {
            // Revoked: signed out from another device, password changed, or account deleted.
            if (e.status == 401) clear()
            throw e
        }
    }

    private suspend fun perform(method: String, url: String, bearer: String?, body: JSONObject?): String = withContext(Dispatchers.IO) {
        val headers = buildMap {
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
            if (bearer != null) put("Authorization", "Bearer $bearer")
        }
        val (status, text) = try {
            transport.send(method, url, headers, body?.toString())
        } catch (e: IOException) {
            throw ApiException(0, "network", "Couldn't reach the server. ${e.message ?: ""}".trim())
        }
        if (status !in 200..299) throw parseError(status, text)
        text
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://motif-server.vercel.app"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

        fun parseSession(o: JSONObject) = AuthSession(
            userId = o.getString("user_id"), accessToken = o.getString("access_token"),
            expiresIn = o.optInt("expires_in", 900), refreshToken = o.getString("refresh_token"),
        )

        fun parseAccount(o: JSONObject) = Account(
            userId = o.getString("user_id"), username = o.optStringOrNull("username"),
            displayName = o.optStringOrNull("display_name"), createdAtMs = o.optLong("created_at_ms"),
        )

        fun parseUpload(o: JSONObject): UploadResult {
            val rejected = o.optJSONArray("rejected") ?: JSONArray()
            return UploadResult(
                acknowledged = o.optInt("inserted") + o.optInt("duplicates"),
                rejected = List(rejected.length()) { rejected.getJSONObject(it).getInt("index") },
            )
        }

        fun parsePage(o: JSONObject): EventPage {
            val raw = o.optJSONArray("events") ?: JSONArray()
            val events = (0 until raw.length()).mapNotNull { HistoryEvent.fromJson(raw.getJSONObject(it)) }
            return EventPage(events, o.optString("next_cursor"), o.optBoolean("has_more"))
        }

        fun parseError(status: Int, text: String): ApiException = runCatching {
            val error = JSONObject(text).getJSONObject("error")
            ApiException(status, error.getString("code"), error.getString("message"))
        }.getOrElse { ApiException(status, "http_$status", "The server answered HTTP $status.") }
    }
}
