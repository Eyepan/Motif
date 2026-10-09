package app.motif.dsp

import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bridge to the shared Rust DSP core (core/dsp) through apps/android/dsp-jni.
 * If the native library isn't packaged (a local build that skipped
 * scripts/build-dsp.sh), [available] is false: import skips analysis,
 * crossfades fall back to the same equal-power curve in Kotlin, and blends
 * aren't beat aligned.
 */
object MotifDsp {
    val available: Boolean = try {
        System.loadLibrary("motif_dsp_jni")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.w("MotifDsp", "DSP core not packaged; analysis disabled", e)
        false
    }

    const val CURVE_EQUAL_POWER = 0

    /** Gains (outgoing, incoming) at fade position [t] in 0..1. */
    fun crossfade(t: Float, curve: Int = CURVE_EQUAL_POWER): Pair<Float, Float> {
        if (available) crossfadeGains(t, curve)?.let { return it[0] to it[1] }
        val x = t.coerceIn(0f, 1f) * (PI / 2).toFloat()
        return cos(x) to sin(x)
    }

    @JvmStatic external fun analyzerNew(sampleRate: Int, channels: Int): Long
    @JvmStatic external fun analyzerPush(handle: Long, samples: FloatArray, count: Int)
    /** [peak dB, RMS dB, BPM, Camelot number (0 = none), minor (1/0), first downbeat s (-1 = none)]. */
    @JvmStatic external fun analyzerFinish(handle: Long): FloatArray?
    @JvmStatic external fun analyzerOverview(handle: Long, count: Int): FloatArray?
    @JvmStatic external fun analyzerFree(handle: Long)
    @JvmStatic private external fun crossfadeGains(t: Float, curve: Int): FloatArray?

    // Mixing (core/dsp/src/mix.rs). Seconds throughout; a downbeat of -1 means no beat grid.

    /** [out start, in start, length, rate, lock]. */
    @JvmStatic external fun mixPlan(
        outBpm: Double, outDownbeat: Double, outDuration: Double,
        inBpm: Double, inDownbeat: Double, inDuration: Double,
    ): DoubleArray?

    /** [progress, outgoing gain, incoming gain, action (0 = set speed, 1 = seek), value]. */
    @JvmStatic external fun mixFollow(plan: DoubleArray, outPos: Double, inPos: Double): DoubleArray?

    /** [speed, position] for the slave deck, or null when the decks can't sync. */
    @JvmStatic external fun deckSync(
        masterBpm: Double, masterDownbeat: Double, masterPos: Double, masterSpeed: Double,
        slaveBpm: Double, slaveDownbeat: Double, slavePos: Double, snap: Boolean,
    ): DoubleArray?

    /** [start, end] in seconds of a loop [beats] long on the beat at or just before [pos], or null without a grid. */
    @JvmStatic external fun loopAt(bpm: Double, downbeat: Double, pos: Double, beats: Double): DoubleArray?

    /** The first downbeat at or after [pos], or NaN without a grid. */
    @JvmStatic external fun nextDownbeat(bpm: Double, downbeat: Double, pos: Double): Double

    // Deck EQ and filter (core/dsp/src/fx.rs). Use through playback.DeckFxProcessor, on the audio thread only.

    @JvmStatic external fun fxNew(sampleRate: Int, channels: Int): Long

    /** Sets the knobs (-1..1, 0 flat) and filters the first [count] floats of the direct, native-order [samples]. */
    @JvmStatic external fun fxProcess(handle: Long, samples: ByteBuffer, count: Int, low: Double, mid: Double, high: Double, filter: Double)
    @JvmStatic external fun fxReset(handle: Long)
    @JvmStatic external fun fxFree(handle: Long)

    // Tag cleanup and artist credits (core/dsp/src/meta.rs). Use through data.TagCleaner.
    @JvmStatic external fun metaCleanerVersion(): Int
    @JvmStatic external fun metaNorm(text: String): String
    @JvmStatic external fun metaDetectSiteSuffix(fields: Array<String?>): String?
    @JvmStatic external fun metaCleanField(text: String, suffixes: Array<String>): String
    /** [name, role, name, role, ...]; [known] holds names already passed through [metaNorm]. */
    @JvmStatic external fun metaSplitArtists(credit: String, known: Array<String>): Array<String>?

    // Import dedupe (core/dsp/src/dedupe.rs). Use through importer.Dedupe.
    @JvmStatic external fun dedupeKey(title: String, artist: String?): String
    /**
     * Track 0 against tracks 1..: [verdict (0 new, 1 duplicate, 2 upgrade), index among tracks 1..].
     * [numbers] holds four per track: duration ms, sample rate, bit depth, kbps (0 = unknown).
     */
    @JvmStatic external fun dedupeResolve(titles: Array<String?>, artists: Array<String?>, formats: Array<String?>, numbers: LongArray): IntArray?
    /** Indexes best copy first; [numbers] as in [dedupeResolve]. */
    @JvmStatic external fun dedupeBestFirst(formats: Array<String?>, numbers: LongArray): IntArray?
}
