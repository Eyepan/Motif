package app.motif

import app.motif.data.LibraryFilter
import app.motif.data.Track
import app.motif.playback.Mixing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private fun track(title: String, bpm: Double? = null, key: String? = null, durationMs: Long = 300_000) = Track(
        id = title, title = title, artist = "Artist", album = "Album", durationMs = durationMs,
        filePath = "$title.flac", format = "flac", sampleRate = 96_000, bitDepth = 24,
        bpm = bpm, musicalKey = key, addedAt = 0,
    )

    @Test fun filterParsesBpmAndKey() {
        val f = LibraryFilter("night bpm:120-126 key:8a")
        assertEquals("night", f.text)
        assertEquals(120.0..126.0, f.bpm)
        assertEquals("8A", f.key)
        assertTrue(f.matches(track("Night Drive", bpm = 124.0, key = "8A")))
        assertFalse(f.matches(track("Night Drive", bpm = 130.0, key = "8A")))
        assertFalse(f.matches(track("Night Drive", bpm = 124.0)))
        assertTrue(LibraryFilter("bpm:124").matches(track("x", bpm = 124.3)))
        assertTrue(LibraryFilter("").isEmpty)
    }

    @Test fun qualityLabels() {
        val t = track("x")
        assertEquals("FLAC · 24-bit / 96 kHz", t.qualityLabel)
        assertEquals("FLAC 24/96", t.shortQualityLabel)
        assertEquals("WAV 16/44.1", t.copy(format = "wav", sampleRate = 44_100, bitDepth = 16).shortQualityLabel)
    }

    @Test fun camelotNeighbours() {
        assertEquals(setOf("12A", "1A", "11A", "12B"), track("x", key = "12A").compatibleKeys)
    }

    @Test fun mixLengthAndTempo() {
        assertEquals(30.97, Mixing.mixLength(track("x", bpm = 124.0)), 0.01)
        assertEquals(10.0, Mixing.mixLength(track("x", durationMs = 30_000)), 0.001)
        assertEquals(124.0 / 120.0, Mixing.tempoRatio(track("a", bpm = 124.0), track("b", bpm = 120.0)), 1e-9)
        assertEquals(1.0, Mixing.tempoRatio(track("a", bpm = 124.0), track("b", bpm = 100.0)), 1e-9)
        assertEquals(1.0, Mixing.tempoRatio(track("a", bpm = 140.0), track("b", bpm = 70.0)), 1e-9)
    }
}
