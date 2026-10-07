package app.motif.playback

import app.motif.data.Track
import app.motif.dsp.MotifDsp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A planned blend from the end of one track into the next (core/dsp mix::Plan).
 * Positions in seconds of each track; [lock] is 0 when the blend isn't beat aligned.
 */
data class MixPlan(val outStart: Double, val inStart: Double, val length: Double, val rate: Double, val lock: Double) {
    val beatAligned: Boolean get() = lock > 0

    /** Where the incoming track should be when the outgoing one is at [outPos]. */
    fun inPositionAt(outPos: Double): Double = inStart + (outPos - outStart).coerceAtLeast(0.0) * rate

    fun toArray(): DoubleArray = doubleArrayOf(outStart, inStart, length, rate, lock)
}

/** One step of a blend: crossfader gains, and either a speed or a jump for the incoming track. */
data class MixStep(val progress: Double, val gainOut: Float, val gainIn: Float, val speed: Double, val seekTo: Double?)

/** Mix planning. The maths lives in the shared DSP core; the fallbacks here only run without it. */
object Mixing {
    /** Largest tempo change applied to the incoming track, as a fraction. */
    const val MAX_TEMPO_ADJUST = 0.08

    /** Seconds per bar at [bpm], in 4/4. */
    fun barLength(bpm: Double): Double = 240 / bpm

    /** Blend from [outgoing] (lasting [outDuration] s) into [incoming]. */
    fun plan(outgoing: Track, incoming: Track, outDuration: Double = outgoing.durationSeconds): MixPlan {
        if (MotifDsp.available) {
            MotifDsp.mixPlan(
                outgoing.bpm ?: 0.0, outgoing.firstDownbeat ?: -1.0, outDuration,
                incoming.bpm ?: 0.0, incoming.firstDownbeat ?: -1.0, incoming.durationSeconds,
            )?.let { return MixPlan(it[0], it[1], it[2], it[3], it[4]) }
        }
        val length = mixLength(outgoing)
        return MixPlan((outDuration - length - 0.3).coerceAtLeast(0.0), 0.0, length, tempoRatio(outgoing, incoming), 0.0)
    }

    /** Where a blend stands with the outgoing track at [outPos] and the incoming one at [inPos]. */
    fun follow(plan: MixPlan, outPos: Double, inPos: Double): MixStep {
        if (MotifDsp.available) {
            MotifDsp.mixFollow(plan.toArray(), outPos, inPos)?.let {
                val seek = it[3] == 1.0
                return MixStep(it[0], it[1].toFloat(), it[2].toFloat(), if (seek) plan.rate else it[4], if (seek) it[4] else null)
            }
        }
        val t = ((outPos - plan.outStart) / plan.length).coerceIn(0.0, 1.0)
        val (a, b) = MotifDsp.crossfade(t.toFloat())
        return MixStep(t, a, b, plan.rate, null)
    }

    /** Blend length in seconds without a beat grid: 16 bars at the track's tempo, clamped to 8..32 s and to a third of the track. */
    fun mixLength(track: Track): Double {
        val bars = track.bpm?.let { 16 * barLength(it) } ?: 16.0
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

    /** "On · 124 to 126 BPM · 8A to 9A · 16-bar blend on the beat" for the Mix into next row. */
    fun detail(current: Track?, next: Track?, enabled: Boolean): String {
        if (!enabled) return "Off · plays straight through"
        if (current == null || next == null) return "On · nothing queued next"
        val parts = mutableListOf<String>()
        if (current.bpm != null && next.bpm != null) parts += "${current.bpm.roundToInt()} to ${next.bpm.roundToInt()} BPM"
        if (current.musicalKey != null && next.musicalKey != null) parts += "${current.musicalKey} to ${next.musicalKey}"
        val plan = plan(current, next)
        val bars = current.bpm?.let { (plan.length / barLength(it)).roundToInt() }
        parts += when {
            plan.beatAligned && bars != null -> "$bars-bar blend on the beat"
            bars != null -> "$bars-bar blend"
            else -> "${plan.length.toInt()} s blend"
        }
        return parts.joinToString(" · ")
    }
}
