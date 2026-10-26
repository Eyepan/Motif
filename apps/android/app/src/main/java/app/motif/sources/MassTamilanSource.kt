package app.motif.sources

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/** masstamilan.dev's public HTML; no clearance cookies, challenge solving, or permanent media URL cache. */
class MassTamilanSource(
    private val load: suspend (String) -> String = { Http.html(it, "www.masstamilan.dev") },
    private val now: () -> Long = { System.currentTimeMillis() },
) : MusicSource {
    override val id = "masstamilan"
    override val displayName = "MassTamilan"
    override val shortName = "Tamil"
    private val cache = linkedMapOf<String, Pair<Long, List<SourceResult>>>()

    override suspend fun search(query: String): List<SourceResult> {
        val q = query.trim()
        val url = if (q.isEmpty()) ORIGIN else Http.url("$ORIGIN/search", listOf("keyword" to q))
        return listing(url, "search:$q") { parseSearch(it) }
    }

    override suspend fun tracks(result: SourceResult): List<SourceResult> {
        if (!result.isAlbum) return listOf(result)
        val url = resultUrl(result.id)
        return listing(url, "album:${result.id}") { parsePage(it, url).tracks }
    }

    override suspend fun release(result: SourceResult): SourceRelease {
        val url = resultUrl(result.id)
        // Fresh signatures for every preview/download; don't cache this page or its downloader links.
        val page = parsePage(load(url), url)
        if (page.files.isEmpty()) throw SourceException(UNAVAILABLE)
        return SourceRelease(page.files, page.coverUrl)
    }

    override suspend fun previewUrl(result: SourceResult): String? = release(result).files.firstOrNull()?.url

    private suspend fun listing(url: String, key: String, parse: (String) -> List<SourceResult>): List<SourceResult> {
        synchronized(cache) { cache[key]?.takeIf { now() - it.first < 300_000 }?.second }?.let { return it }
        val results = parse(load(url))
        synchronized(cache) {
            if (cache.size >= 16) cache.remove(cache.minBy { it.value.first }.key)
            cache[key] = now() to results
        }
        return results
    }

    internal fun parseSearch(html: String): List<SourceResult> {
        val doc = document(html)
        if (doc.selectFirst(".bots") == null || doc.selectFirst("form[action='/search'] input[name=keyword]") == null) {
            throw SourceException(INVALID_PAGE)
        }
        val songs = doc.select(".bots .srg .srl li").mapNotNull { item ->
            val a = item.selectFirst("a") ?: return@mapNotNull null
            val path = stablePath(a.attr("href"))?.takeIf { SONG_PATH.matches(it) } ?: return@mapNotNull null
            val title = a.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SourceResult(path, title, null, item.selectFirst(".srm")?.text()?.substringBefore(" · "), null, null, quality = "MP3")
        }
        val albums = doc.select(".bots .a-i").mapNotNull { item ->
            val a = item.selectFirst("a") ?: return@mapNotNull null
            val path = stablePath(a.attr("href")) ?: return@mapNotNull null
            val title = a.selectFirst("h2")?.text()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val music = a.selectFirst("p")?.html()?.split(Regex("<br\\s*/?>"))
                ?.map { Jsoup.parseBodyFragment(it).text() }?.firstOrNull { it.startsWith("Music:") }
                ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() && it != "Not Available" }
            SourceResult(path, title, music, title, null, image(a), quality = "MP3", isAlbum = true)
        }
        return (songs + albums).distinctBy { it.id }.take(50)
    }

    internal data class Page(val tracks: List<SourceResult>, val files: List<SourceDownload>, val coverUrl: String?)

    internal fun parsePage(html: String, url: String): Page {
        val doc = document(html)
        val article = doc.selectFirst("article.post-content") ?: throw SourceException(INVALID_PAGE)
        val albumElement = article.selectFirst("[itemtype='http://schema.org/MusicAlbum']")
        val album = albumElement?.selectFirst("meta[itemprop=name]")?.attr("content")
        val cover = image(article)
        val song = SONG_PATH.matches(URI(url).path)
        val root = if (song) article.selectFirst("[itemtype='http://schema.org/MusicRecording']") else albumElement
        if (root == null) throw SourceException(INVALID_PAGE)
        val tracks = mutableListOf<SourceResult>()
        val files = mutableListOf<SourceDownload>()
        for (row in root.select("table tr")) {
            val name = row.selectFirst("h2 [itemprop=name]") ?: continue
            val title = name.text().takeIf { it.isNotBlank() } ?: continue
            val artist = row.selectFirst("[itemprop=byArtist]")?.text()
            val duration = row.selectFirst("[itemprop=duration]")?.text()?.split(':')
            val minutes = duration?.getOrNull(0)?.toIntOrNull()
            val remainder = duration?.getOrNull(1)?.toIntOrNull()
            val seconds = if (duration?.size == 2 && minutes != null && minutes in 0 until Int.MAX_VALUE / 60 &&
                remainder != null && remainder in 0..59) minutes * 60 + remainder else null
            val path = if (song) URI(url).path else name.selectFirst("a")?.attr("href")?.let(::stablePath)
            if (path == null) continue
            tracks += SourceResult(path, title, artist, album, null, cover, quality = "MP3", durationSeconds = seconds)
            val best = row.select("a.dlink").mapNotNull { a ->
                val text = a.text().lowercase()
                if ("zip" in text || "rar" in text) return@mapNotNull null
                val bitrate = listOf(320, 128).firstOrNull { text.startsWith("${it}kbps") } ?: return@mapNotNull null
                val link = pageUrl(a.attr("href"))?.takeIf { URI(it).path.startsWith("/downloader/") } ?: return@mapNotNull null
                bitrate to link
            }.maxByOrNull { it.first }
            if (best != null) files += SourceDownload(
                best.second, "${path.substringAfterLast('/')}.mp3", "mp3", path, title, artist, album,
            )
        }
        return Page(tracks, files, cover)
    }

    private fun document(html: String): org.jsoup.nodes.Document {
        if (html.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024) throw SourceException(INVALID_PAGE)
        if ("cf_chl_" in html || "<title>Just a moment" in html) throw SourceException(CHALLENGE)
        return Jsoup.parse(html)
    }

    private fun image(element: Element): String? = element.selectFirst("img[src]")?.attr("src")?.let(::pageUrl)

    private fun stablePath(value: String): String? = pageUrl(value)?.let { URI(it).path }

    private fun resultUrl(id: String): String {
        if (!RESULT_PATH.matches(id)) throw SourceException(INVALID_PAGE)
        return pageUrl(id) ?: throw SourceException(INVALID_PAGE)
    }

    internal fun pageUrl(value: String): String? = runCatching {
        val url = URI(ORIGIN).resolve(value)
        url.takeIf { it.scheme == "https" && it.host == "www.masstamilan.dev" && it.userInfo == null && it.port in listOf(-1, 443) }?.toASCIIString()
    }.getOrNull()

    private companion object {
        const val ORIGIN = "https://www.masstamilan.dev"
        const val INVALID_PAGE = "MassTamilan returned an unrecognized page. Its website may have changed."
        const val UNAVAILABLE = "This MassTamilan track is unavailable or has no public MP3 download."
        const val CHALLENGE = "MassTamilan challenged this request. Motif does not bypass website protection."
        val SONG_PATH = Regex("^/[0-9]+/[^/]+$")
        val RESULT_PATH = Regex("^/(?:[0-9]+/)?[A-Za-z0-9_-]+$")
    }
}
