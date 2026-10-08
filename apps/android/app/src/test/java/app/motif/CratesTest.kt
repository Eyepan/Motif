package app.motif

import app.motif.data.Crate
import app.motif.data.CrateChange
import app.motif.data.CrateLog
import app.motif.data.HistoryEvent
import app.motif.data.HistoryStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class CratesTest {
    /** Unit tests run from apps/android/app. */
    private val fixture = JSONObject(File("../../../schemas/fixtures/crates.json").readText())

    /** Every platform must rebuild the same crates from schemas/fixtures/crates.json. */
    @Test fun foldMatchesSharedFixture() {
        val raw = fixture.getJSONArray("events")
        val events = (0 until raw.length()).map { i ->
            val e = raw.getJSONObject(i)
            HistoryEvent(
                id = e.getString("id"), type = e.getString("type"), v = e.getInt("v"), atMs = e.getLong("at_ms"),
                tzMin = e.getInt("tz_min"), deviceId = e.getString("device_id"), payload = e.getJSONObject("payload").toString(),
            )
        }
        val crates = fixture.getJSONArray("crates")
        val expected = (0 until crates.length()).map { i ->
            val c = crates.getJSONObject(i)
            val tracks = c.getJSONArray("tracks")
            Crate(c.getString("id"), c.getString("name"), (0 until tracks.length()).map(tracks::getString))
        }
        assertEquals(expected, CrateLog.fold(events))
        assertEquals(expected, CrateLog.fold(events.reversed()))
    }

    @Test fun largeChangesSplitUnderServerLimit() {
        val keys = (0 until 450).map { "%064d".format(it) }
        val parts = CrateLog.chunked(CrateChange("c", name = "Big", added = keys.take(300), removed = keys.drop(300)))
        assertEquals(3, parts.size)
        assertEquals("Big", parts[0].name)
        assertTrue(parts.drop(1).all { it.name == null })
        assertEquals(keys.take(300), parts.flatMap { it.added })
        assertEquals(keys.drop(300), parts.flatMap { it.removed })
        assertTrue(parts.all { it.toJson().toByteArray().size < 16 * 1024 })
        assertEquals(1, CrateLog.chunked(CrateChange("c", added = listOf("a"))).size)
    }

    @Test fun payloadRoundTripsAndOmitsUnsetFields() {
        val change = CrateChange("c", name = "Warm up", added = listOf("k"))
        assertEquals(change, CrateChange.parse(change.toJson()))
        val json = JSONObject(CrateChange("c", deleted = true).toJson())
        assertEquals(setOf("crate_id", "deleted"), json.keySet())
        assertNull(CrateChange.parse("""{"name":"No id"}"""))
        assertNull(CrateChange.parse("not json"))
    }

    @Test fun eventIdsAreTimeOrderedUuidV7() {
        val a = HistoryStore.uuidV7(1_727_000_000_000)
        val b = HistoryStore.uuidV7(1_727_000_000_001)
        assertTrue(a < b)
        assertEquals(a, a.lowercase())
        assertEquals(7, UUID.fromString(a).version())
        assertEquals(2, UUID.fromString(a).variant())
        assertEquals(1_727_000_000_000, UUID.fromString(a).mostSignificantBits ushr 16)
    }
}
