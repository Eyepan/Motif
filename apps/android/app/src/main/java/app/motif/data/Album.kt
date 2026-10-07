package app.motif.data

import java.text.Normalizer
import java.util.Locale

/** An album in the library, built from its tracks' album tags. */
class Album(val key: String, val title: String, val artist: String?, val tracks: List<Track>)

/**
 * Albums in library order (newest first). Tracks group by album title alone,
 * compared by [albumKey] (case, whitespace, dash style and
 * invisible characters ignored), so a soundtrack or
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
            val artists = list.mapNotNull { it.artist?.let(::cleanTag)?.takeIf(String::isNotEmpty) }.distinctBy(::albumKey)
            val artist = when {
                artists.isEmpty() -> null
                artists.size == 1 -> artists[0]
                else -> "Various artists"
            }
            Album(key, cleanTag(list[0].album!!), artist, list)
        }

/**
 * Tag text as it should be stored and shown: Unicode-normalized, with the
 * invisible characters some taggers leave behind (byte-order marks,
 * zero-width spaces, soft hyphens) removed, control characters such as NUL
 * turned into spaces, and whitespace trimmed and collapsed. Joiners are kept
 * because Indic scripts use them for rendering.
 */
fun cleanTag(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace(INVISIBLE, "")
        .replace(CONTROL, " ")
        .replace(Regex(" {2,}"), " ")
        .trim()

/**
 * Comparison key for tag text: [cleanTag], then every format character
 * dropped, dashes unified, runs of any Unicode whitespace collapsed to one
 * space, and case folded. Combining marks are kept, since in scripts like
 * Tamil they are vowels, not accents.
 */
fun albumKey(text: String): String =
    cleanTag(text)
        .replace(Regex("\\p{Cf}+"), "")
        .replace(Regex("\\p{Pd}"), "-")
        .replace(Regex("[\\s\\p{Z}]+"), " ")
        .trim()
        .lowercase(Locale.ROOT)

private val INVISIBLE = Regex("[\\uFEFF\\u200B\\u2060\\u00AD]+")
private val CONTROL = Regex("\\p{Cc}+")
