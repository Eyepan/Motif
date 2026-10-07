package app.motif

import app.motif.data.LibraryFilter
import app.motif.data.artistsOf
import app.motif.data.Track
import app.motif.data.albumsOf
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

    private fun song(title: String, artist: String?, album: String?) =
        track(title).copy(id = title, artist = artist, album = album)

    @Test fun soundtrackWithDifferentSingersIsOneAlbum() {
        val albums = albumsOf(listOf(
            song("Song 1", "Singer A", "Film OST"),
            song("Song 2", "Singer B, Singer C", "Film OST"),
            song("Song 3", "Singer D", " film  ost "),
            song("Song 4", null, "FILM OST"),
        ))
        assertEquals(1, albums.size)
        assertEquals("Film OST", albums[0].title)
        assertEquals("Various artists", albums[0].artist)
        assertEquals(listOf("Song 1", "Song 2", "Song 3", "Song 4"), albums[0].tracks.map { it.title })
    }

    @Test fun albumsKeepOrderAndSharedArtist() {
        val albums = albumsOf(listOf(
            song("a", "Band", "Second"),
            song("b", "Other", "First"),
            song("c", "band", "Second"),
            song("d", "Band", null),
            song("e", "Band", "  "),
        ))
        assertEquals(listOf("Second", "First"), albums.map { it.title })
        assertEquals("Band", albums[0].artist)
        assertEquals("Other", albums[1].artist)
        assertEquals(2, albums[0].tracks.size)
    }

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

    /** Stand-in for the native splitter: commas, and "&" only between known names. */
    private fun fakeSplit(credit: String, known: Collection<String>): List<Pair<String, String>> =
        credit.split(",").map { it.trim() }.flatMap { part ->
            val halves = part.split(" & ").map { it.trim() }
            if (halves.size > 1 && halves.all { it.lowercase() in known }) halves else listOf(part)
        }.map { it to "primary" }

    @Test fun artistsCountEveryCreditedArtist() {
        val tracks = listOf(
            track("a").copy(artist = "Anirudh Ravichander, Shankar Mahadevan"),
            track("b").copy(artist = "anirudh ravichander"),
            track("c").copy(artist = "Simon & Garfunkel"),
            track("d").copy(artist = null),
        )
        val artists = artistsOf(tracks, ::fakeSplit) { it.lowercase() }
        assertEquals(
            listOf("Anirudh Ravichander" to 2, "Shankar Mahadevan" to 1, "Simon & Garfunkel" to 1, "Unknown artist" to 1),
            artists.map { it.name to it.tracks.size },
        )
    }

    @Test fun ampersandSplitsWhenBothNamesAreKnown() {
        val tracks = listOf(
            track("a").copy(artist = "Simon & Garfunkel"),
            track("b").copy(artist = "Simon"),
            track("c").copy(artist = "Garfunkel"),
        )
        val artists = artistsOf(tracks, ::fakeSplit) { it.lowercase() }
        assertEquals(listOf("Garfunkel" to 2, "Simon" to 2), artists.map { it.name to it.tracks.size })
    }
}
