package app.motif.importer

import app.motif.data.TagCleaner
import app.motif.data.Track
import app.motif.dsp.MotifDsp

/**
 * Duplicate detection and quality ranking with the shared rules in
 * core/dsp/src/dedupe.rs, so Android and Apple keep the same copy of a song.
 * Two files are the same recording when lead artist and title match (without
 * case, punctuation, track numbers, "feat." or "(320kbps)") and durations
 * agree within about two seconds. Lossless beats lossy; then sample rate and
 * bit depth, or bitrate between lossy copies.
 *
 * Without the native core (a local build that skipped scripts/build-dsp.sh)
 * only identical titles and artists count as duplicates, and nothing is upgraded.
 */
object Dedupe {
    /** What the rules look at for one file or library track. */
    data class Copy(
        val title: String,
        val artist: String?,
        val durationMs: Long,
        val format: String,
        val sampleRate: Int? = null,
        val bitDepth: Int? = null,
        /** Average bitrate; only ranks lossy copies. */
        val bitrateKbps: Int? = null,
    ) {
        companion object {
            /** [fileBytes] gives a lossy track a bitrate to rank by. */
            fun of(track: Track, fileBytes: Long? = null) = Copy(
                track.title, track.artist, track.durationMs, track.format, track.sampleRate, track.bitDepth,
                if (track.isLossless) null else fileBytes?.let { bitrateKbps(it, track.durationMs) },
            )
        }
    }

    sealed interface Verdict {
        /** No copy in the library yet. */
        data object New : Verdict
        /** `existing[index]` is as good or better: skip the incoming file. */
        data class Duplicate(val index: Int) : Verdict
        /** `existing[index]` is worse: replace its file with the incoming one. */
        data class Upgrade(val index: Int) : Verdict
    }

    /** Equal keys are the same song; use it to index the library. */
    fun key(title: String, artist: String?): String =
        if (MotifDsp.available) MotifDsp.dedupeKey(title, artist)
        else TagCleaner.norm(artist ?: "") + "\u001f" + TagCleaner.norm(title)

    fun resolve(incoming: Copy, existing: List<Copy>): Verdict {
        if (!MotifDsp.available) {
            val i = existing.indexOfFirst { key(it.title, it.artist) == key(incoming.title, incoming.artist) }
            return if (i < 0) Verdict.New else Verdict.Duplicate(i)
        }
        val all = listOf(incoming) + existing
        val r = MotifDsp.dedupeResolve(
            all.map { it.title }.toTypedArray(),
            all.map { it.artist }.toTypedArray(),
            all.map { it.format }.toTypedArray(),
            numbers(all),
        ) ?: return Verdict.New
        return when (r[0]) {
            1 -> Verdict.Duplicate(r[1])
            2 -> Verdict.Upgrade(r[1])
            else -> Verdict.New
        }
    }

    /** Indexes of [copies], best copy first. */
    fun bestFirst(copies: List<Copy>): List<Int> {
        if (!MotifDsp.available || copies.size < 2) return copies.indices.toList()
        return MotifDsp.dedupeBestFirst(copies.map { it.format }.toTypedArray(), numbers(copies))?.toList()
            ?: copies.indices.toList()
    }

    /** Average bitrate from file size, for formats whose headers aren't read for it. */
    fun bitrateKbps(bytes: Long, durationMs: Long): Int? =
        if (durationMs > 0) Math.round(bytes * 8.0 / durationMs).toInt() else null

    private fun numbers(copies: List<Copy>) = LongArray(copies.size * 4).also { n ->
        copies.forEachIndexed { i, c ->
            n[i * 4] = c.durationMs
            n[i * 4 + 1] = (c.sampleRate ?: 0).toLong()
            n[i * 4 + 2] = (c.bitDepth ?: 0).toLong()
            n[i * 4 + 3] = (c.bitrateKbps ?: 0).toLong()
        }
    }
}
