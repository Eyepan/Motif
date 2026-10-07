package app.motif.data

import java.text.Normalizer
import java.util.Locale

/** An album in the library, built from its tracks' album tags. */
class Album(val key: String, val title: String, val artist: String?, val tracks: List<Track>)

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
        .map { (key, list) ->
            val artists = list.mapNotNull { it.artist?.trim()?.takeIf(String::isNotEmpty) }.distinctBy(::albumKey)
            val artist = when {
                artists.isEmpty() -> null
                artists.size == 1 -> artists[0]
                else -> "Various artists"
            }
            Album(key, list[0].album!!.trim(), artist, list)
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
