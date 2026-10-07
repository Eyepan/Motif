package app.motif

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.motif.data.Track
import app.motif.playback.MixMatch
import app.motif.ui.theme.Motif
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MixColoursTest {
    private fun track(bpm: Double? = null, key: String? = null) = Track(
        id = "$bpm$key", title = "t", durationMs = 300_000, filePath = "t.flac", format = "flac",
        bpm = bpm, musicalKey = key, addedAt = 0,
    )

    /** Unit tests run from apps/android/app. */
    private val schema = File("../../../schemas/mix-colours.json").readText()

    private fun hex(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)

    @Test fun keyColoursMatchSharedSchema() {
        val shared = Regex("\"(\\d{1,2}[AB])\": \"(#[0-9A-F]{6})\"").findAll(schema).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(24, shared.size)
        assertEquals(shared, Motif.keyColors.mapValues { hex(it.value) })
    }

    @Test fun bpmColoursMatchSharedSchema() {
        val bins = schema.substringAfter("\"bins\"")
        val shared = Regex("\"(#[0-9A-F]{6})\"").findAll(bins).map { it.groupValues[1] }.toList()
        assertEquals(shared, Motif.bpmColors.map(::hex))
    }

    @Test fun bpmBinsFoldOctavesAndKeepNeighboursClose() {
        assertEquals(0, Motif.bpmBin(120.0))
        assertEquals(Motif.bpmBin(140.0), Motif.bpmBin(70.0))
        assertEquals(Motif.bpmBin(87.0), Motif.bpmBin(174.0))
        assertEquals(1, Motif.bpmBin(124.0))
        assertEquals(23, Motif.bpmBin(116.0))
        assertEquals(Motif.secondary, Motif.bpmColor(null))
        assertEquals(Motif.secondary, Motif.keyColor(null))
        assertEquals(Motif.secondary, Motif.keyColor("13A"))
    }

    @Test fun mixMatchFollowsCamelotAndTempoRules() {
        val playing = track(bpm = 124.0, key = "8A")
        assertTrue(MixMatch.keys(playing, track(key = "9A")))
        assertTrue(MixMatch.keys(playing, track(key = "8B")))
        assertFalse(MixMatch.keys(playing, track(key = "9B")))
        assertFalse(MixMatch.keys(playing, track()))
        assertTrue(MixMatch.tempos(playing, track(bpm = 128.0)))
        assertTrue(MixMatch.tempos(playing, track(bpm = 62.0)))
        assertFalse(MixMatch.tempos(playing, track(bpm = 100.0)))
        assertFalse(MixMatch.tempos(playing, track()))
    }
}
