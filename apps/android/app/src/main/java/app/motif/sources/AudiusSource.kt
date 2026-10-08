package app.motif.sources

import org.json.JSONObject

/**
 * Audius: artists upload their own music and choose whether fans may download
 * it, often as the original WAV or FLAC. Public API, no key; `app_name`
 * identifies Motif. Only tracks the artist made downloadable (and didn't gate
 * behind a purchase or follow) can be downloaded.
 */
class AudiusSource : MusicSource {
    override val id = "audius"
    override val displayName = "Audius"

    override suspend fun search(query: String): List<SourceResult> =
        parseSearch(Http.text(Http.url("$API/tracks/search", listOf("query" to query, "app_name" to APP_NAME))))

    override suspend fun release(result: SourceResult): SourceRelease {
        if (!result.downloadable) throw SourceException("The artist only allows streaming this track.")
        val format = result.quality?.lowercase()?.takeIf { it in LOSSLESS || it == "mp3" } ?: "mp3"
        val file = SourceDownload(
            url = Http.url("$API/tracks/${result.id}/download", listOf("app_name" to APP_NAME)),
            fileName = "${result.id}.$format",
            format = format,
            sourceRef = result.id,
            title = result.title,
            artist = result.artist,
            album = result.album,
        )
        return SourceRelease(listOf(file), result.imageUrl)
    }

    internal fun parseSearch(json: String): List<SourceResult> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val t = data.getJSONObject(i)
            val id = t.getString("id")
            val gated = t.optBoolean("is_download_gated", false)
            val downloadable = !gated && (t.optBoolean("is_downloadable", false) || t.optBoolean("downloadable", false))
            val ext = t.str("orig_filename")?.substringAfterLast('.', "")?.lowercase()
            val art = t.optJSONObject("artwork")
            SourceResult(
                id = id,
                title = t.str("title") ?: id,
                artist = t.optJSONObject("user")?.str("name"),
                album = null,
                licenseUrl = null,
                imageUrl = art?.str("480x480") ?: art?.str("1000x1000") ?: art?.str("150x150"),
                previewUrl = Http.url("$API/tracks/$id/stream", listOf("app_name" to APP_NAME)),
                quality = when (ext) {
                    "flac" -> "FLAC"
                    "wav", "wave" -> "WAV"
                    "aif", "aiff" -> "AIFF"
                    "mp3" -> "MP3"
                    else -> null
                },
                durationSeconds = t.optInt("duration").takeIf { it > 0 },
                downloadable = downloadable,
            )
        }
    }

    private companion object {
        const val API = "https://api.audius.co/v1"
        const val APP_NAME = "Motif"
        val LOSSLESS = setOf("flac", "wav", "aiff")
    }
}
