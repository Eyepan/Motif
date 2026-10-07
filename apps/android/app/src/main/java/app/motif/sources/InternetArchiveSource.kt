package app.motif.sources

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Internet Archive audio items that carry FLAC or WAV files. Each result is a
 * whole item (usually an album). APIs: advancedsearch.php and /metadata/{id}.
 */
class InternetArchiveSource : MusicSource {
    override val id = "internet_archive"
    override val displayName = "Internet Archive"

    private val items = object : LinkedHashMap<String, Item>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Item>?) = size > 16
    }

    override suspend fun search(query: String): List<SourceResult> {
        val q = "($query) AND mediatype:audio AND (format:Flac OR format:\"24bit Flac\" OR format:WAVE)"
        val params = listOf("q" to q, "rows" to "50", "output" to "json") +
            listOf("identifier", "title", "creator", "licenseurl", "format").map { "fl[]" to it }
        return parseSearch(Http.text(Http.url("https://archive.org/advancedsearch.php", params)))
    }

    override suspend fun release(result: SourceResult): SourceRelease {
        val item = item(result.id)
        if (item.files.isEmpty()) throw SourceException("This item has no FLAC or WAV files.")
        return SourceRelease(item.files, item.coverUrl)
    }

    override suspend fun previewUrl(result: SourceResult): String? = item(result.id).previewUrl

    private suspend fun item(identifier: String): Item {
        synchronized(items) { items[identifier] }?.let { return it }
        val item = parseItem(identifier, Http.text("https://archive.org/metadata/${path(identifier)}"))
        synchronized(items) { items[identifier] = item }
        return item
    }

    internal class Item(val files: List<SourceDownload>, val coverUrl: String, val previewUrl: String?)

    internal fun parseSearch(json: String): List<SourceResult> {
        val docs = JSONObject(json).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            val identifier = d.getString("identifier")
            val formats = d.many("format")
            SourceResult(
                id = identifier,
                title = d.many("title").firstOrNull() ?: identifier,
                artist = d.many("creator").firstOrNull(),
                album = null,
                licenseUrl = d.many("licenseurl").firstOrNull(),
                imageUrl = "https://archive.org/services/img/${path(identifier)}",
                quality = when {
                    "24bit Flac" in formats -> "FLAC 24"
                    "Flac" in formats -> "FLAC"
                    "WAVE" in formats -> "WAV"
                    else -> null
                },
            )
        }
    }

    internal fun parseItem(identifier: String, json: String): Item {
        val root = JSONObject(json)
        val meta = root.optJSONObject("metadata") ?: JSONObject()
        val itemArtist = meta.many("creator").firstOrNull()
        val itemTitle = meta.many("title").firstOrNull()
        val files = root.optJSONArray("files") ?: JSONArray()
        val all = (0 until files.length()).map { files.getJSONObject(it) }
        val base = "https://archive.org/download/${path(identifier)}/"

        // Items often carry the same recording in several formats; keep the best per track.
        val best = HashMap<String, Pair<Int, JSONObject>>()
        for (f in all) {
            val rank = f.str("format")?.let(FORMAT_RANK::get) ?: continue
            val name = f.getString("name")
            val key = name.substringBeforeLast('.')
            val existing = best[key]
            if (existing == null || rank < existing.first) best[key] = rank to f
        }
        val downloads = best.values.map { it.second }
            .sortedWith(compareBy<JSONObject>({ trackNumber(it) ?: Int.MAX_VALUE }, { it.getString("name") }))
            .map { f ->
                val name = f.getString("name")
                SourceDownload(
                    url = base + path(name),
                    fileName = name.substringAfterLast('/'),
                    format = name.substringAfterLast('.', "").lowercase(),
                    sourceRef = "$identifier/$name",
                    title = f.str("title"),
                    artist = f.str("creator") ?: f.str("artist") ?: itemArtist,
                    album = f.str("album") ?: itemTitle,
                )
            }

        val images = all.filter { (it.str("format") ?: "") in IMAGE_FORMATS && it.str("source") == "original" }
        val cover = images.minByOrNull { f ->
            val n = f.getString("name").lowercase()
            when {
                "cover" in n || "front" in n || "folder" in n -> 0
                else -> 1
            }
        }
        val preview = all.filter { it.str("format") == "VBR MP3" }
            .minWithOrNull(compareBy<JSONObject>({ trackNumber(it) ?: Int.MAX_VALUE }, { it.getString("name") }))
            ?.let { base + path(it.getString("name")) }
            ?: downloads.firstOrNull()?.url

        return Item(
            files = downloads,
            coverUrl = cover?.let { base + path(it.getString("name")) } ?: "https://archive.org/services/img/${path(identifier)}",
            previewUrl = preview,
        )
    }

    /** "3" or "3/12". */
    private fun trackNumber(f: JSONObject): Int? = f.str("track")?.substringBefore('/')?.trim()?.toIntOrNull()

    /** Escapes each path segment, keeping the slashes of files in subfolders. */
    private fun path(name: String) = name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private companion object {
        /** Lower is better. */
        val FORMAT_RANK = mapOf("24bit Flac" to 0, "Flac" to 1, "WAVE" to 2)
        val IMAGE_FORMATS = setOf("JPEG", "PNG", "Item Image")
    }
}

/** Archive fields are a string or an array of strings depending on the item. */
internal fun JSONObject.many(name: String): List<String> {
    val value = opt(name) ?: return emptyList()
    return when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).trim().takeIf(String::isNotEmpty) }
        JSONObject.NULL -> emptyList()
        else -> listOfNotNull(value.toString().trim().takeIf(String::isNotEmpty))
    }
}
