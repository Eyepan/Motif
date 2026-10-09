package app.motif

import app.motif.data.PlayContext
import app.motif.data.PlayEndReason
import app.motif.data.PlayPayload
import app.motif.data.PlayRecorder
import app.motif.data.PlaySession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PlaysTest {
    /** Unit tests run from apps/android/app. */
    private val fixture = JSONObject(File("../../../schemas/fixtures/plays.json").readText())

    /** Every platform must turn the same playback into the same payload (schemas/fixtures/plays.json). */
    @Test fun sessionMatchesSharedFixture() {
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val play = c.getJSONObject("play")
            val session = PlaySession(
                play.getString("track_id"), play.getLong("duration_ms"), PlayContext.of(play.getString("context"))!!,
                play.optString("context_ref").takeIf { play.has("context_ref") }, play.optBoolean("mixed_in"),
            )
            var result: PlayPayload? = null
            val steps = c.getJSONArray("steps")
            for (j in 0 until steps.length()) {
                val step = steps.getJSONObject(j)
                val at = step.getLong("at")
                when (step.getString("op")) {
                    "resume" -> session.resume(at, step.getLong("pos"))
                    "progress" -> session.progress(at, step.getLong("pos"))
                    "pause" -> session.pause(at, step.getLong("pos"))
                    "seek" -> session.seek(at, step.getLong("from"), step.getLong("to"))
                    "end" -> result = session.end(
                        at, step.getLong("pos"), PlayEndReason.of(step.getString("reason"))!!, step.optBoolean("mixed_out"),
                    )
                    "snapshot" -> result = session.snapshot(at)
                    else -> error("unknown op in $name")
                }
            }
            val expected = c.optJSONObject("expected")
            if (expected == null) {
                assertNull(name, result)
            } else {
                assertEquals(name, expected.toMap(), result!!.toJson().toMap())
                assertEquals(name, result, PlayPayload.fromJson(result.toJson()))
            }
        }
    }

    @Test fun checkpointRoundTrips() {
        val payload = PlayPayload(
            trackId = "t", startedAtMs = 1, listenedMs = 2, endReason = PlayEndReason.INTERRUPTED,
            context = PlayContext.CRATE, contextRef = "c",
        )
        assertEquals(payload to 99L, PlayRecorder.parseCheckpoint(PlayRecorder.checkpointJson(payload, 99)))
        assertNull(PlayRecorder.parseCheckpoint("{}"))
    }

    /** Numbers compared as longs, since org.json keeps whatever width it parsed. */
    private fun JSONObject.toMap(): Map<String, Any> = keys().asSequence().associateWith { key ->
        when (val v = get(key)) {
            is Number -> v.toLong()
            else -> v
        }
    }
}
