package app.motif.data

import java.util.Locale

/** A library track. Mirrors `schemas/library.sql` and `schemas/track.schema.json`. */
data class Track(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long,
    /** Path relative to [LibraryStore.mediaDir]. */
    val filePath: String,
    val format: String,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val source: String = "local",
    val sourceRef: String? = null,
    val licenseUrl: String? = null,
    val bpm: Double? = null,
    val loudnessDb: Double? = null,
    /** Camelot notation, e.g. "8A". */
    val musicalKey: String? = null,
    /** Seconds to the first downbeat of the beat grid; beats follow every 60 / [bpm] s. */
    val firstDownbeat: Double? = null,
    /** Loudness overview for drawing, [LibraryStore.WAVEFORM_LENGTH] bytes. Local cache, not synced. */
    val waveform: ByteArray? = null,
    /** Unix seconds. */
    val addedAt: Long,
    val albumArtist: String? = null,
    /**
     * SHA-256 of the audio file, lowercase hex: the track's identity across
     * devices (`track_key`). Filled lazily by [LibraryStore.contentHash].
     */
    val contentHash: String? = null,
) {
    val isLossless: Boolean get() = format in setOf("flac", "wav", "alac", "aiff")

    val durationSeconds: Double get() = durationMs / 1000.0

    val subtitle: String get() = listOfNotNull(artist, album).joinToString(" · ")

    val artSeed: String get() = album ?: artist ?: title

    val monogram: String get() = title.firstOrNull()?.uppercase() ?: "♪"

    val bpmText: String get() = bpm?.let { "${Math.round(it)} BPM" } ?: "— BPM"

    /** Whether the track has a beat grid, so it can be mixed beat on beat. */
    val hasBeatGrid: Boolean get() = bpm != null && firstDownbeat != null

    /** "FLAC · 24-bit / 96 kHz" */
    val qualityLabel: String
        get() {
            val parts = mutableListOf(format.uppercase(Locale.ROOT))
            sampleRate?.let { rate ->
                val khz = khzText(rate) + " kHz"
                parts += bitDepth?.let { "$it-bit / $khz" } ?: khz
            }
            return parts.joinToString(" · ")
        }

    /** "FLAC 24/96", for dense lists. */
    val shortQualityLabel: String
        get() {
            val rate = sampleRate ?: return format.uppercase(Locale.ROOT)
            val khz = khzText(rate)
            return bitDepth?.let { "${format.uppercase(Locale.ROOT)} $it/$khz" } ?: "${format.uppercase(Locale.ROOT)} ${khz}k"
        }

    /**
     * Keys that mix harmonically with this one on the Camelot wheel: the same
     * key, one step either way, and the relative major/minor.
     */
    val compatibleKeys: Set<String>
        get() {
            val key = musicalKey ?: return emptySet()
            val letter = key.lastOrNull() ?: return emptySet()
            val n = key.dropLast(1).toIntOrNull() ?: return emptySet()
            val other = if (letter == 'A') 'B' else 'A'
            val up = n % 12 + 1
            val down = (n + 10) % 12 + 1
            return setOf("$n$letter", "$up$letter", "$down$letter", "$n$other")
        }

    // ByteArray needs content equality for state diffing.
    override fun equals(other: Any?): Boolean =
        other is Track && id == other.id && title == other.title && artist == other.artist &&
            album == other.album && albumArtist == other.albumArtist && durationMs == other.durationMs && filePath == other.filePath &&
            format == other.format && sampleRate == other.sampleRate && bitDepth == other.bitDepth &&
            bpm == other.bpm && musicalKey == other.musicalKey && loudnessDb == other.loudnessDb &&
            firstDownbeat == other.firstDownbeat && contentHash == other.contentHash &&
            waveform.contentEquals(other.waveform)

    override fun hashCode(): Int = id.hashCode()

    private fun khzText(rate: Int): String {
        val khz = rate / 1000.0
        return if (khz == Math.rint(khz)) khz.toInt().toString() else String.format(Locale.ROOT, "%.1f", khz)
    }
}

fun formatTime(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) return "0:00"
    val s = seconds.toLong()
    return "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
}
