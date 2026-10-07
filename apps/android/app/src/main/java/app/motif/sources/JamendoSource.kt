package app.motif.sources

import org.json.JSONObject

/**
 * Jamendo's Creative Commons catalog (API v3). Needs a free client id from
 * developer.jamendo.com: built in from `JAMENDO_CLIENT_ID`, or pasted in Discover.
 */
class JamendoSource(private val clientId: () -> String) : MusicSource {
    override val id = "jamendo"
    override val displayName = "Jamendo"
    override val isConfigured: Boolean get() = clientId().isNotBlank()

    override suspend fun search(query: String): List<SourceResult> =
        parseTracks(Http.text(tracksUrl(listOf("search" to query, "limit" to "50"))))

    override suspend fun release(result: SourceResult): SourceRelease {
        val track = parseTracks(Http.text(tracksUrl(listOf("id" to result.id)))).firstOrNull()
        val url = track?.let { downloadUrls[it.id] }
        if (track == null || url == null || !track.downloadable) {
            throw SourceException("The artist only allows streaming this track.")
        }
        val file = SourceDownload(
            url = url, fileName = "${track.id}.flac", format = "flac", sourceRef = track.id,
            title = track.title, artist = track.artist, album = track.album,
        )
        return SourceRelease(listOf(file), track.imageUrl)
    }

    /** Download links seen in the last parse, by track id; [SourceResult] stays catalog-neutral. */
    private val downloadUrls = HashMap<String, String>()

    private fun tracksUrl(params: List<Pair<String, String>>): String {
        if (!isConfigured) throw SourceException("Jamendo needs a client id.")
        return Http.url(
            "https://api.jamendo.com/v3.0/tracks/",
            listOf(
                "client_id" to clientId().trim(),
                "format" to "json",
                "audioformat" to "mp32",
                "audiodlformat" to "flac",
                "imagesize" to "600",
            ) + params,
        )
    }

    internal fun parseTracks(json: String): List<SourceResult> {
        val root = JSONObject(json)
        val headers = root.optJSONObject("headers")
        if (headers != null && headers.optString("status") == "failed") {
            throw SourceException("Jamendo: " + headers.optString("error_message").ifEmpty { "request failed" })
        }
        val results = root.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val t = results.getJSONObject(i)
            val id = t.getString("id")
            val download = t.str("audiodownload")
            val allowed = t.optBoolean("audiodownload_allowed", download != null)
            if (download != null) synchronized(downloadUrls) { downloadUrls[id] = download }
            SourceResult(
                id = id,
                title = t.str("name") ?: id,
                artist = t.str("artist_name"),
                album = t.str("album_name"),
                licenseUrl = t.str("license_ccurl"),
                imageUrl = t.str("album_image") ?: t.str("image"),
                previewUrl = t.str("audio"),
                quality = "FLAC",
                durationSeconds = t.optInt("duration").takeIf { it > 0 },
                downloadable = allowed && download != null,
            )
        }
    }
}

internal fun JSONObject.str(name: String): String? =
    if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }
