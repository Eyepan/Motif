package app.motif.playback

import app.motif.data.Track
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure mix maths, shared in spirit (and numbers) with the Apple PlaybackEngine. */
object Mixing {
    /** Largest tempo change applied to the incoming track, as a fraction. */
    const val MAX_TEMPO_ADJUST = 0.08

    /** Blend length in seconds: 16 bars at the track's tempo, clamped to 8..32 s and to a third of the track. */
    fun mixLength(track: Track): Double {
        val bars = track.bpm?.let { 16 * 4 * 60 / it } ?: 16.0
        return minOf(bars.coerceIn(8.0, 32.0), track.durationSeconds / 3)
    }

    /**
     * Playback rate that brings [incoming] to [outgoing]'s tempo, within
     * [MAX_TEMPO_ADJUST]. Half and double time count as matches.
     */
    fun tempoRatio(outgoing: Track?, incoming: Track): Double {
        val a = outgoing?.bpm ?: return 1.0
        val b = incoming.bpm ?: return 1.0
        if (a <= 0 || b <= 0) return 1.0
        val best = listOf(a / b, a * 2 / b, a / (2 * b)).minBy { abs(it - 1) }
        return if (abs(best - 1) <= MAX_TEMPO_ADJUST) best else 1.0
    }

    /** "On · 124 to 126 BPM · 8A to 9A · 16-bar blend" for the Mix into next row. */
    fun detail(current: Track?, next: Track?, enabled: Boolean): String {
        if (!enabled) return "Off · plays straight through"
        if (current == null || next == null) return "On · nothing queued next"
        val parts = mutableListOf<String>()
        if (current.bpm != null && next.bpm != null) parts += "${current.bpm.roundToInt()} to ${next.bpm.roundToInt()} BPM"
        if (current.musicalKey != null && next.musicalKey != null) parts += "${current.musicalKey} to ${next.musicalKey}"
        val length = mixLength(current)
        parts += current.bpm?.let { "${(it * length / 240).roundToInt()}-bar blend" } ?: "${length.toInt()} s blend"
        return parts.joinToString(" · ")
    }
}
