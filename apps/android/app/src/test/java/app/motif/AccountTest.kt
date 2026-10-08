package app.motif

import app.motif.account.AccountModel
import app.motif.account.ApiException
import app.motif.account.DeviceInfo
import app.motif.account.MemoryTokenStore
import app.motif.account.MotifApi
import app.motif.account.StoredSession
import app.motif.account.Transport
import app.motif.data.HistoryEvent
import app.motif.data.HistoryStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccountTest {
    private data class Request(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    /** Answers from a script, in order, and keeps every request. */
    private class Script(vararg answers: Pair<Int, String>) : Transport {
        val answers = ArrayDeque(answers.toList())
        val requests = mutableListOf<Request>()
        override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
            requests += Request(method, url, headers, body)
            return answers.removeFirst()
        }
    }

    private fun session(access: String, refresh: String) =
        200 to """{"user_id":"u1","access_token":"$access","expires_in":900,"refresh_token":"$refresh"}"""

    private val me = 200 to """{"user_id":"u1","username":"pan","display_name":null,"created_at_ms":1700000000000,"identities":[]}"""

    @Test fun loginStoresSessionAndSendsDevice() = runBlocking {
        val tokens = MemoryTokenStore()
        val transport = Script(session("a1", "r1"))
        val api = MotifApi("https://example.test/", tokens, transport)
        api.login("pan", "correct horse", DeviceInfo("Pixel 9"))

        assertEquals(StoredSession("u1", "r1"), tokens.load())
        assertTrue(api.isSignedIn)
        val request = transport.requests.single()
        assertEquals("https://example.test/v1/auth/login", request.url)
        val device = JSONObject(request.body!!).getJSONObject("device")
        assertEquals("Pixel 9", device.getString("name"))
        assertEquals("android", device.getString("platform"))
        assertFalse(request.headers.containsKey("Authorization"))
    }

    @Test fun errorEnvelopeBecomesApiException() = runBlocking {
        val api = MotifApi("https://example.test", MemoryTokenStore(), Script(401 to """{"error":{"code":"invalid_credentials","message":"Wrong username or password."}}"""))
        try {
            api.login("pan", "nope", DeviceInfo("Pixel 9"))
            fail("expected an error")
        } catch (e: ApiException) {
            assertEquals(401, e.status)
            assertEquals("invalid_credentials", e.code)
            assertEquals("Wrong username or password.", e.message)
        }
    }

    @Test fun unreadableErrorKeepsStatus() {
        val e = MotifApi.parseError(502, "<html>Bad gateway</html>")
        assertEquals(502, e.status)
        assertFalse(e.isNetwork)
    }

    @Test fun refreshesOnceAfter401AndRetries() = runBlocking {
        val tokens = MemoryTokenStore(StoredSession("u1", "r1"))
        val transport = Script(
            session("a1", "r2"), // no access token yet: refresh first
            401 to """{"error":{"code":"unauthorized","message":"Expired."}}""",
            session("a2", "r3"),
            me,
        )
        val account = MotifApi("https://example.test", tokens, transport).me()

        assertEquals("pan", account.username)
        assertNull(account.displayName)
        assertEquals(StoredSession("u1", "r3"), tokens.load())
        assertEquals(listOf("/v1/auth/refresh", "/v1/me", "/v1/auth/refresh", "/v1/me"), transport.requests.map { it.url.removePrefix("https://example.test") })
        assertEquals("Bearer a2", transport.requests.last().headers["Authorization"])
    }

    @Test fun revokedRefreshSignsOut() = runBlocking {
        val tokens = MemoryTokenStore(StoredSession("u1", "r1"))
        val api = MotifApi("https://example.test", tokens, Script(401 to """{"error":{"code":"unauthorized","message":"Signed out."}}"""))
        try {
            api.me()
            fail("expected an error")
        } catch (e: ApiException) {
            assertEquals(401, e.status)
        }
        assertFalse(api.isSignedIn)
    }

    @Test fun uploadCountsRejectedPositions() {
        val result = MotifApi.parseUpload(JSONObject("""{"inserted":3,"duplicates":1,"rejected":[{"index":2,"reason":"bad payload"}]}"""))
        assertEquals(4, result.acknowledged)
        assertEquals(listOf(2), result.rejected)
    }

    @Test fun eventsRoundTripThroughTheServerEnvelope() {
        val event = HistoryEvent("e1", "play", 1, 1_700_000_000_000, 60, "d1", "k".repeat(64), """{"ms_played":1000}""")
        assertEquals(event.copy(payload = JSONObject(event.payload).toString()), HistoryEvent.fromJson(event.toJson()))

        val page = MotifApi.parsePage(
            JSONObject().put("events", org.json.JSONArray().put(event.toJson()).put(JSONObject().put("id", "broken")))
                .put("next_cursor", "c2").put("has_more", true),
        )
        assertEquals(listOf("e1"), page.events.map { it.id })
        assertEquals("c2", page.nextCursor)
        assertTrue(page.hasMore)
    }

    @Test fun deletionTombstoneDefaultsToListeningTypes() {
        val full = HistoryStore.parseDeletion("""{"from_ms":10,"to_ms":20,"types":["play"]}""")
        assertEquals(HistoryStore.Companion.Deletion(10, 20, listOf("play")), full)
        assertEquals(HistoryStore.LISTENING_TYPES, HistoryStore.parseDeletion("""{"from_ms":10,"to_ms":20}""")?.types)
        assertNull(HistoryStore.parseDeletion("""{"types":["play"]}"""))
    }

    @Test fun serverAddresses() {
        assertEquals("https://motif.example.com", AccountModel.parseServer("motif.example.com/"))
        assertEquals("http://localhost:8080", AccountModel.parseServer("http://localhost:8080"))
        assertNull(AccountModel.parseServer("http://motif.example.com"))
    }
}
