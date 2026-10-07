package app.motif.playback

import app.motif.data.Track
import kotlin.math.abs

/**
 * Whether two tracks mix well, for highlighting matches in lists and for the
 * DJ Mix browser. The rule is shared across apps in `schemas/mix-colours.json`.
 */
object MixMatch {
    /** [incoming]'s key is the same as [outgoing]'s, a wheel neighbour, or its relative major/minor. */
    fun keys(outgoing: Track, incoming: Track): Boolean =
        incoming.musicalKey != null && incoming.musicalKey in outgoing.compatibleKeys

    /** [incoming] can be beatmatched to [outgoing] within [Mixing.MAX_TEMPO_ADJUST], counting half and double time. */
    fun tempos(outgoing: Track, incoming: Track): Boolean {
        val a = outgoing.bpm ?: return false
        val b = incoming.bpm ?: return false
        if (a <= 0 || b <= 0) return false
        return listOf(a / b, a * 2 / b, a / (2 * b)).any { abs(it - 1) <= Mixing.MAX_TEMPO_ADJUST }
    }
}
