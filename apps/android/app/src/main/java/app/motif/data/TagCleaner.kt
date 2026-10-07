package app.motif.data

import app.motif.dsp.MotifDsp
import java.util.Locale

/** Tag values exactly as read from a file. Kept in `track_tags` so the library can be re-cleaned when rules change. */
data class RawTags(val title: String?, val artist: String?, val album: String?, val albumArtist: String?) {
    val fields: Array<String?> get() = arrayOf(title, artist, album, albumArtist)

    /** `track_tags` keys, lowercased tag names. */
    fun entries(): List<Pair<String, String>> =
        listOf("title" to title, "artist" to artist, "album" to album, "albumartist" to albumArtist)
            .mapNotNull { (key, value) -> value?.let { key to it } }

    companion object {
        fun of(entries: Map<String, String>) =
            RawTags(entries["title"], entries["artist"], entries["album"], entries["albumartist"])
    }
}

/**
 * Turns messy tags into library values with the shared rules in
 * core/dsp/src/meta.rs, so Android and Apple clean and split identically:
 * strips site names appended to tags ("Jailer 2 - Site"), and splits artist
 * credits ("A, B feat. C") into separate artists. Without the native core
 * (a local build that skipped scripts/build-dsp.sh) values pass through as tagged.
 */
object TagCleaner {
    /** Changes when the shared rules do; the library re-cleans from raw tags then. 0 without the native core. */
    val version: Int get() = if (MotifDsp.available) MotifDsp.metaCleanerVersion() else 0

    fun norm(text: String): String =
        if (MotifDsp.available) MotifDsp.metaNorm(text)
        else text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    /** A site name appended to several of this file's tags, or null. */
    fun siteSuffix(raw: RawTags): String? =
        if (MotifDsp.available) MotifDsp.metaDetectSiteSuffix(raw.fields) else null

    /** Cleaned value, or null for a missing or blank tag. [suffixes] are site names seen anywhere in the library. */
    fun clean(text: String?, suffixes: Collection<String>): String? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (MotifDsp.available) MotifDsp.metaCleanField(value, suffixes.toTypedArray()) else value
    }

    /** Credited artists as (name, role) pairs, role "primary" or "featured". */
    fun splitArtists(credit: String, known: Collection<String>): List<Pair<String, String>> {
        val flat = if (MotifDsp.available) MotifDsp.metaSplitArtists(credit, known.toTypedArray()) else null
        return flat?.toList()?.chunked(2)?.map { it[0] to it[1] } ?: listOf(credit.trim() to "primary")
    }
}
