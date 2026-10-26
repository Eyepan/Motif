package app.motif

import app.motif.sources.Http
import app.motif.sources.MassTamilanSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in JVM HTTP probes; these do not establish Android device playback support. */
class MassTamilanLiveTest {
    @Test fun nativeCatalogRequests() = runBlocking {
        assumeTrue(System.getenv("MOTIF_MASSTAMILAN_LIVE") == "1")
        val source = MassTamilanSource()
        val hits = source.search("mascara")
        assertTrue(hits.any { !it.isAlbum })
        val album = hits.first { it.isAlbum }
        assertTrue(source.tracks(album).isNotEmpty())
    }

    @Test fun nativeMP3Download() = runBlocking {
        assumeTrue(System.getenv("MOTIF_MASSTAMILAN_LIVE_MEDIA") == "1")
        val source = MassTamilanSource()
        val hit = source.search("mascara").first { !it.isAlbum }
        val file = source.release(hit).files.single()
        val tmp = File.createTempFile("motif-live-", ".mp3")
        try {
            Http.download(file.url, tmp, setOf("www.masstamilan.dev", "masstamilan.download")) { _, _ -> }
            assertTrue(tmp.length() > 0)
        } finally { tmp.delete() }
    }
}
