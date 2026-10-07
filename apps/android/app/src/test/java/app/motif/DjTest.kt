package app.motif

import app.motif.data.Track
import app.motif.playback.DeckState
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
}
