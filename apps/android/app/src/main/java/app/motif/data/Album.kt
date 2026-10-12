package app.motif.data

import java.text.Normalizer
import java.util.Locale

/** An album in the library, built from its tracks' album tags. [tracks] are in album order. */
class Album(val key: String, val title: String, val artist: String?, val tracks: List<Track>) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs }

    /** "12 songs · 48 min" */
    val summary: String
        get() = "${tracks.size} ${if (tracks.size == 1) "song" else "songs"} · ${Math.round(durationMs / 60_000.0)} min"

    /** "FLAC 24/96" when every track shares one quality, else "Mixed formats". */
    val qualitySummary: String
        get() = tracks.map { it.shortQualityLabel }.distinct().singleOrNull() ?: "Mixed formats"
}

/**
 * Albums in library order (newest first). Tracks group by album title alone,
 * compared without case or extra whitespace, so a soundtrack or
 * compilation whose songs credit different singers stays one album. The
 * album's artist is the one every credited track shares, else "Various artists".
 *
 * Grouping moves to album artist + title once `album_artist` is stored (see
 * design/metadata-proposal.md); until then two different albums with the
 * same title share an entry.
 */
fun albumsOf(tracks: List<Track>): List<Album> =
    tracks.filter { !it.album.isNullOrBlank() }
        .groupBy { albumKey(it.album!!) }
        .map { (key, group) ->
            val list = albumOrder(group)
            val artists = list.mapNotNull { it.artist?.trim()?.takeIf(String::isNotEmpty) }.distinctBy(::albumKey)
            val artist = when {
                artists.isEmpty() -> null
                artists.size == 1 -> artists[0]
                else -> "Various artists"
            }
            Album(key, list[0].album!!.trim(), artist, list)
        }

/** Disc then track number; tracks without a number after, in import order, then by title. */
fun albumOrder(tracks: List<Track>): List<Track> =
    tracks.sortedWith(
        compareBy<Track>({ it.trackNumber == null }, { it.discNumber ?: 1 }, { it.trackNumber }, { it.addedAt }, { it.title }),
    )

/** Where a track sits on its album, as tagged. */
data class AlbumPosition(val track: Int?, val disc: Int?) {
    companion object {
        /** "3", "3/12" or "03"; null when missing or zero. */
        fun number(tag: String?): Int? =
            tag?.trim()?.takeWhile(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }
    }
}

/**
 * Comparison key for tag text: Unicode-normalized, trimmed, single-spaced and
 * case-folded. Combining marks are kept, since in scripts like Tamil they are
 * vowels, not accents.
 */
fun albumKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)
