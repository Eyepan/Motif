package app.motif

import app.motif.data.Track
import app.motif.sources.InternetArchiveSource
import app.motif.sources.JamendoSource
import app.motif.sources.SourceException
import app.motif.sources.SourceResult
import app.motif.sources.isIn
import app.motif.sources.licenseLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {
    @Test
    fun jamendoTracks() {
        val json = """
            {"headers":{"status":"success","results_count":2},"results":[
              {"id":"1532771","name":"Night Drive","duration":214,"artist_name":"Aves","album_name":"Lights",
               "album_image":"https://usercontent.jamendo.com/?type=album&id=1&width=600","audio":"https://prod-1.storage.jamendo.com/?trackid=1532771&format=mp32",
               "audiodownload":"https://prod-1.storage.jamendo.com/download/track/1532771/flac/","audiodownload_allowed":true,
               "license_ccurl":"http://creativecommons.org/licenses/by-nc-sa/3.0/"},
              {"id":"99","name":"Stream Only","duration":0,"artist_name":"X","album_name":"","audio":"a",
               "audiodownload":"","audiodownload_allowed":false,"license_ccurl":null}
            ]}
        """.trimIndent()
        val results = JamendoSource { "id" }.parseTracks(json)
        assertEquals(2, results.size)
        val first = results[0]
        assertEquals("Night Drive", first.title)
        assertEquals("Aves · Lights", first.subtitle)
        assertEquals(214, first.durationSeconds)
        assertEquals("FLAC", first.quality)
        assertTrue(first.downloadable)
        assertEquals("CC BY-NC-SA", licenseLabel(first.licenseUrl))
        assertFalse(results[1].downloadable)
        assertNull(results[1].album)
        assertNull(results[1].durationSeconds)
    }

    @Test(expected = SourceException::class)
    fun jamendoErrorsSurface() {
        JamendoSource { "bad" }.parseTracks("""{"headers":{"status":"failed","error_message":"Your credential is not authorized."},"results":[]}""")
    }

    @Test
    fun archiveSearchHandlesStringsAndArrays() {
        val json = """
            {"response":{"numFound":2,"docs":[
              {"identifier":"gd1977-05-08","title":"Cornell 1977","creator":["Grateful Dead"],"format":["Flac","VBR MP3"],
               "licenseurl":"http://creativecommons.org/licenses/by-nc-nd/3.0/"},
              {"identifier":"hires_item","title":["Hi-res"],"format":["24bit Flac","Flac"]}
            ]}}
        """.trimIndent()
        val results = InternetArchiveSource().parseSearch(json)
        assertEquals(listOf("Cornell 1977", "Hi-res"), results.map { it.title })
        assertEquals("Grateful Dead", results[0].artist)
        assertEquals("FLAC", results[0].quality)
        assertEquals("FLAC 24", results[1].quality)
        assertNull(results[1].artist)
        assertEquals("https://archive.org/services/img/gd1977-05-08", results[0].imageUrl)
    }

    @Test
    fun archiveItemKeepsBestFormatPerTrackInOrder() {
        val json = """
            {"metadata":{"identifier":"album1","title":"Album One","creator":"The Band"},
             "files":[
              {"name":"02 Second.flac","format":"Flac","track":"2/3","title":"Second","source":"original"},
              {"name":"02 Second.wav","format":"WAVE","track":"2","source":"original"},
              {"name":"01 First.wav","format":"WAVE","track":"1","title":"First","source":"original"},
              {"name":"01 First.mp3","format":"VBR MP3","track":"1","source":"derivative"},
              {"name":"02 Second.mp3","format":"VBR MP3","track":"2","source":"derivative"},
              {"name":"disc 2/03 Third.flac","format":"24bit Flac","track":"3","album":"Album One (Deluxe)","source":"original"},
              {"name":"back.jpg","format":"JPEG","source":"original"},
              {"name":"Front Cover.jpg","format":"JPEG","source":"original"},
              {"name":"album1_thumb.jpg","format":"JPEG Thumb","source":"derivative"}
            ]}
        """.trimIndent()
        val item = InternetArchiveSource().parseItem("album1", json)
        assertEquals(listOf("01 First.wav", "02 Second.flac", "03 Third.flac"), item.files.map { it.fileName })
        assertEquals(listOf("wav", "flac", "flac"), item.files.map { it.format })
        assertEquals("https://archive.org/download/album1/disc%202/03%20Third.flac", item.files[2].url)
        assertEquals("album1/disc 2/03 Third.flac", item.files[2].sourceRef)
        assertEquals("The Band", item.files[0].artist)
        assertEquals("Album One", item.files[0].album)
        assertEquals("Album One (Deluxe)", item.files[2].album)
        assertEquals("Second", item.files[1].title)
        assertEquals("https://archive.org/download/album1/Front%20Cover.jpg", item.coverUrl)
        assertEquals("https://archive.org/download/album1/01%20First.mp3", item.previewUrl)
    }

    @Test
    fun archiveItemWithoutCoverUsesThumbnailService() {
        val json = """{"metadata":{},"files":[{"name":"a.flac","format":"Flac"}]}"""
        val item = InternetArchiveSource().parseItem("x", json)
        assertEquals("https://archive.org/services/img/x", item.coverUrl)
        assertEquals("https://archive.org/download/x/a.flac", item.previewUrl)
    }

    @Test
    fun licenseLabels() {
        assertEquals("CC BY", licenseLabel("https://creativecommons.org/licenses/by/4.0/"))
        assertEquals("CC0", licenseLabel("http://creativecommons.org/publicdomain/zero/1.0/"))
        assertEquals("Public domain", licenseLabel("http://creativecommons.org/publicdomain/mark/1.0/"))
        assertNull(licenseLabel("https://example.com/terms"))
        assertNull(licenseLabel(null))
    }

    @Test
    fun knowsWhatIsAlreadyDownloaded() {
        val item = SourceResult("album1", "Album", null, null, null, null)
        val track = Track(id = "t", title = "x", durationMs = 0, filePath = "t.flac", format = "flac", addedAt = 0,
            source = "internet_archive", sourceRef = "album1/01 First.flac")
        assertTrue(item.isIn(listOf(track), "internet_archive"))
        assertFalse(item.isIn(listOf(track), "jamendo"))
        assertFalse(SourceResult("album", "Album", null, null, null, null).isIn(listOf(track), "internet_archive"))
    }
}
