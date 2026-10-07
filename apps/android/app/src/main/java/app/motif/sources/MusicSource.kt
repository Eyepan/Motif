package app.motif.sources

import app.motif.data.Track

/**
 * A catalog Motif may legally download from (see docs/sources.md). A source
 * only talks to its catalog: downloading, tagging and adding to the library
 * are shared by [app.motif.importer.Downloads].
 */
interface MusicSource {
    /** Stored in the `source` column of every track it supplies. */
    val id: String
    val displayName: String
    val isConfigured: Boolean get() = true

    suspend fun search(query: String): List<SourceResult>

    /** The files to download for [result], best quality first, and its cover. */
    suspend fun release(result: SourceResult): SourceRelease

    /** A stream URL for a short listen before downloading, or null. */
    suspend fun previewUrl(result: SourceResult): String? = result.previewUrl
}

/** A search hit: one track (Jamendo) or one release of several files (Internet Archive). */
data class SourceResult(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val licenseUrl: String?,
    val imageUrl: String?,
    val previewUrl: String? = null,
    /** "FLAC", "FLAC 24", "WAV": what the download will be. */
    val quality: String? = null,
    val durationSeconds: Int? = null,
    /** False when the artist only allows streaming. */
    val downloadable: Boolean = true,
) {
    val subtitle: String get() = listOfNotNull(artist, album?.takeIf { it != title }).joinToString(" · ")
}

data class SourceRelease(val files: List<SourceDownload>, val coverUrl: String?)

data class SourceDownload(
    val url: String,
    val fileName: String,
    /** Lowercased extension: flac, wav, ... */
    val format: String,
    /** Catalog id for this file, stored as Track.sourceRef. */
    val sourceRef: String,
    val title: String?,
    val artist: String?,
    val album: String?,
)

class SourceException(message: String) : Exception(message)

/** Whether [tracks] already holds something downloaded from this result. */
fun SourceResult.isIn(tracks: List<Track>, sourceId: String): Boolean =
    tracks.any { it.source == sourceId && (it.sourceRef == id || it.sourceRef?.startsWith("$id/") == true) }

/** "CC BY-SA", "CC0" or "Public domain" from a license URL; null when it isn't one we recognise. */
fun licenseLabel(url: String?): String? {
    val u = url?.lowercase() ?: return null
    return when {
        "publicdomain/zero" in u -> "CC0"
        "publicdomain" in u -> "Public domain"
        "creativecommons.org/licenses/" in u ->
            u.substringAfter("/licenses/").substringBefore('/').takeIf { it.isNotEmpty() }?.let { "CC " + it.uppercase() }
        else -> null
    }
}
