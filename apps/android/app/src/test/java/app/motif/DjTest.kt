package app.motif

import app.motif.data.Track
import app.motif.playback.DeckSnapshot
import app.motif.playback.DeckState
import app.motif.playback.DjListen
import app.motif.playback.DjListenTracker
import app.motif.playback.DjTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjTest {
    private val track = Track(
        id = "t", title = "t", durationMs = 300_000, filePath = "t.flac", format = "flac",
        bpm = 120.0, firstDownbeat = 0.25, addedAt = 0,
    )

    @Test fun beatInBarFollowsTheGrid() {
        // 120 BPM: a beat every 500 ms from the downbeat at 250 ms.
        fun beatAt(ms: Long) = DeckState(track = track, positionMs = ms).beatInBar
        assertEquals(0, beatAt(250))
        assertEquals(0, beatAt(749))
        assertEquals(1, beatAt(750))
        assertEquals(3, beatAt(2_249))
        assertEquals(0, beatAt(2_250))
        // Before the first downbeat counts back from it.
        assertEquals(3, beatAt(0))
        assertNull(DeckState(track = track.copy(firstDownbeat = null)).beatInBar)
        assertNull(DeckState().beatInBar)
    }

    @Test fun tempoAndGrid() {
        assertEquals(126.0, DeckState(track = track, speed = 1.05).bpm!!, 1e-9)
        assertTrue(track.hasBeatGrid)
        assertFalse(track.copy(bpm = null).hasBeatGrid)
    }

    private val other = track.copy(id = "u", title = "u", bpm = 122.0)

    private class Recorded {
        val listens = mutableListOf<DjListen>()
        val transitions = mutableListOf<DjTransition>()
        val tracker = DjListenTracker({ listens += it }, { transitions += it })
    }

    private fun deck(t: Track?, playing: Boolean, gain: Float, pos: Long = 0, synced: Boolean = false) =
        DeckSnapshot(t, playing, gain, pos, if (synced) 1.01 else 1.0, synced)

    @Test fun listensCountOnlyAudibleTime() {
        val r = Recorded()
        r.tracker.loaded(0, track)
        r.tracker.loaded(1, other)
        // A plays alone for 10 s; B plays silently under it (cued in headphones, fader on A).
        for (t in 0..10) r.tracker.tick(1_000L * t, deck(track, true, 1f, 1_000L * t), deck(other, true, 0f))
        r.tracker.endAll("stopped")
        assertEquals(1, r.listens.size)
        val l = r.listens.single()
        assertEquals("t", l.track.id)
        assertEquals(10_000L, l.listenedMs)
        assertEquals(0L, l.startedAtMs)
        assertEquals("stopped", l.endReason)
        assertFalse(l.mixedIn)
    }

    @Test fun crossfaderHandoverIsATransition() {
        val r = Recorded()
        r.tracker.loaded(0, track)
        r.tracker.loaded(1, other)
        var now = 0L
        fun tick(ga: Float, gb: Float, auto: Boolean = false) {
            r.tracker.tick(now, deck(track, true, ga), deck(other, true, gb, synced = true), auto)
            now += 500
        }
        repeat(4) { tick(1f, 0f) }
        // Fader across over 3 s.
        repeat(6) { tick(0.7f, 0.7f, auto = true) }
        repeat(4) { tick(0f, 1f) }
        val t = r.transitions.single()
        assertEquals("t", t.from.id)
        assertEquals("u", t.to.id)
        assertEquals(3_000L, t.lengthMs)
        assertTrue(t.beatmatched)
        assertFalse(t.manual)
        assertEquals(1.0, t.tempoShiftPct, 1e-9)

        // A is replaced: its listen says it was mixed out; B's says mixed in.
        r.tracker.loaded(0, track.copy(id = "v"))
        r.tracker.endAll("stopped")
        val a = r.listens.first { it.track.id == "t" }
        assertTrue(a.mixedOut)
        assertEquals("replaced", a.endReason)
        assertTrue(r.listens.first { it.track.id == "u" }.mixedIn)
    }

    @Test fun unheardTracksAreNotListens() {
        val r = Recorded()
        r.tracker.loaded(0, track)
        r.tracker.tick(0, deck(track, false, 1f), deck(null, false, 0f))
        r.tracker.loaded(0, other)
        r.tracker.endAll("stopped")
        assertTrue(r.listens.isEmpty())
        assertTrue(r.transitions.isEmpty())
    }

    @Test fun replayAfterTheEndIsANewListen() {
        val r = Recorded()
        r.tracker.loaded(0, track)
        for (t in 0..4) r.tracker.tick(500L * t, deck(track, true, 1f, 500L * t), deck(null, false, 0f))
        r.tracker.end(0, "completed", 300_000)
        r.tracker.tick(10_000, deck(track, true, 1f), deck(null, false, 0f))
        r.tracker.tick(10_500, deck(track, true, 1f, 500), deck(null, false, 0f))
        r.tracker.endAll("stopped")
        assertEquals(listOf("completed", "stopped"), r.listens.map { it.endReason })
        assertEquals(listOf(2_000L, 500L), r.listens.map { it.listenedMs })
        assertEquals(300_000L, r.listens[0].endPosMs)
    }
}
