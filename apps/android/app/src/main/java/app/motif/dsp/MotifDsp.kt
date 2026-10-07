package app.motif.dsp

import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bridge to the shared Rust DSP core (core/dsp) through apps/android/dsp-jni.
 * If the native library isn't packaged (a local build that skipped
 * scripts/build-dsp.sh), [available] is false: import skips analysis and
 * crossfades fall back to the same equal-power curve in Kotlin.
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
    /** [peak dB, RMS dB, BPM, Camelot number (0 = none), minor (1/0)]. */
    @JvmStatic external fun analyzerFinish(handle: Long): FloatArray?
    @JvmStatic external fun analyzerOverview(handle: Long, count: Int): FloatArray?
    @JvmStatic external fun analyzerFree(handle: Long)
    @JvmStatic private external fun crossfadeGains(t: Float, curve: Int): FloatArray?

    // Tag cleanup and artist credits (core/dsp/src/meta.rs). Use through data.TagCleaner.
    @JvmStatic external fun metaCleanerVersion(): Int
    @JvmStatic external fun metaNorm(text: String): String
    @JvmStatic external fun metaDetectSiteSuffix(fields: Array<String?>): String?
    @JvmStatic external fun metaCleanField(text: String, suffixes: Array<String>): String
    /** [name, role, name, role, ...]; [known] holds names already passed through [metaNorm]. */
    @JvmStatic external fun metaSplitArtists(credit: String, known: Array<String>): Array<String>?
}
