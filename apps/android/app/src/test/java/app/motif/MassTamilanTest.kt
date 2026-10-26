package app.motif

import app.motif.sources.MassTamilanSource
import app.motif.sources.SourceException
import app.motif.sources.SourceResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MassTamilanTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("masstamilan/$name.html")!!.readText()
    private val song = SourceResult("/996/mascara-pottu-mp3-song-1", "Mascara Pottu", null, null, null, null)
    private val origin = "https://www.masstamilan.dev"

    @Test fun searchAndDiscoveryUseCapturedMarkup() {
        val source = MassTamilanSource()
        val hits = source.parseSearch(fixture("search"))
        assertEquals(11, hits.size)
        assertEquals(song.id, hits.first().id)
        assertEquals("Salim", hits.first().album)
        assertEquals(6, hits.count { it.isAlbum })
        assertTrue(hits.any { it.title == "Maara & Paaru" })
        assertFalse(hits.any { it.title == "Maskara" })
        assertTrue(hits.all { it.licenseUrl == null && '?' !in it.id })
        val home = source.parseSearch(fixture("home"))
        assertEquals(10, home.size)
        assertTrue(home.all { it.isAlbum })
        assertEquals("$origin/i/ramayana-tamil-2026.jpg", home.first().imageUrl)
        assertEquals("A.R.Rahman, Hans Zimmer", home.first().artist)
    }

    @Test fun extractsOnlyTheCurrentSongAndBestQuality() {
        val page = MassTamilanSource().parsePage(fixture("track"), origin + song.id)
        assertEquals(1, page.tracks.size)
        assertEquals(296, page.tracks.first().durationSeconds)
        val file = page.files.single()
        assertEquals("Mascara Pottu", file.title)
        assertEquals("Supriya Joshi, Sharmila, Vijay Antony", file.artist)
        assertEquals("Salim", file.album)
        assertEquals(song.id, file.sourceRef)
        assertEquals("$origin/downloader/fixture-2/4102444800/test", file.url)
        assertEquals("$origin/i/salim-2014.jpg", page.coverUrl)
    }

    @Test fun browsesActualEmbeddedAlbumFragment() {
        val page = MassTamilanSource().parsePage(fixture("track"), "$origin/salim-songs-1")
        assertEquals(listOf("Avala Nambithan", "Prayer", "Ulagam Unnai", "Unnai Kanda Naal Mudhal"), page.tracks.map { it.title })
        assertEquals(4, page.files.size)
    }

    @Test fun rejectsMalformedChallengesAndForeignAddresses() {
        val source = MassTamilanSource()
        for (html in listOf("broken <html>", "<title>Just a moment...</title>")) {
            assertThrows(SourceException::class.java) { source.parseSearch(html) }
        }
        for (url in listOf("//evil.example/a", "https://evil.example/a", "http://www.masstamilan.dev/a", "https://user@www.masstamilan.dev/a")) {
            assertNull(source.pageUrl(url))
        }
        assertEquals("$origin/i/a.jpg", source.pageUrl("/i/a.jpg"))
        assertTrue(source.parseSearch("<section class=bots><form action=/search><input name=keyword></form></section>").isEmpty())
    }

    @Test fun missingAudioFailsInsteadOfImportingOtherSongs() = runBlocking {
        val html = fixture("track").replace("class=\"dlink\"", "class=\"unavailable\"")
        val source = MassTamilanSource(load = { html })
        try { source.release(song); fail("Expected unavailable") } catch (_: SourceException) {}
    }

    @Test fun fallsBackTo128AndRejectsForeignDownloaderLinks() {
        val html = fixture("track")
        val source = MassTamilanSource()
        val lower = html.replace(Regex("<a class=\"dlink\"[^>]*>320kbps[^<]*</a>"), "")
        assertEquals("$origin/downloader/fixture-1/4102444800/test", source.parsePage(lower, origin + song.id).files.single().url)
        val unsafe = html.replace("href=\"/downloader/", "href=\"https://evil.example/downloader/")
        assertTrue(source.parsePage(unsafe, origin + song.id).files.isEmpty())
        assertNull(source.parsePage(html.replace("04:56", "04:bad:56"), origin + song.id).tracks.first().durationSeconds)
    }

    @Test fun cachesMetadataButAlwaysResolvesFreshAudio() = runBlocking {
        var calls = 0
        var clock = 0L
        val queries = mutableListOf<String>()
        val source = MassTamilanSource(load = { url ->
            calls++
            if ("/search?" in url) { queries += url; fixture("search") }
            else fixture("track").replace("fixture-2/", "fresh-$calls/")
        }, now = { clock })
        val hits = source.search("Mascara & Pottu")
        source.search("Mascara & Pottu")
        assertEquals(1, calls)
        val preview = source.previewUrl(hits.first())
        val download = source.release(hits.first()).files.single().url
        assertNotEquals(preview, download)
        assertEquals(3, calls)
        assertTrue(queries.first().contains("keyword=Mascara+%26+Pottu"))
        clock = 300_001
        source.search("Mascara & Pottu")
        assertEquals(4, calls)
    }
}
