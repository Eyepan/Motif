package app.motif.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.motif.dsp.MotifDsp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** A deck's EQ and filter knobs, each -1..1 with 0 flat (core/dsp fx::Knobs). */
data class DeckFxKnobs(val low: Float = 0f, val mid: Float = 0f, val high: Float = 0f, val filter: Float = 0f) {
    val isFlat: Boolean get() = low == 0f && mid == 0f && high == 0f && filter == 0f
}

/**
 * Runs a deck's EQ and filter (the shared DSP core's fx::DeckFx) inside its
 * ExoPlayer, before the time stretch. [knobs] may be set from any thread; the
 * core reads them with each buffer and glides to them, so knob moves don't click.
 * Without the native core it passes audio through untouched.
 */
@OptIn(UnstableApi::class)
class DeckFxProcessor : BaseAudioProcessor() {
    @Volatile var knobs = DeckFxKnobs()

    private var handle = 0L
    private var handleFormat: AudioFormat? = null
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!MotifDsp.available) return AudioFormat.NOT_SET
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val count = if (isFloat) bytes / 4 else bytes / 2
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val start = input.position()
        val buf = scratch(count)
        for (i in 0 until count) {
            buf.putFloat(i * 4, if (isFloat) input.getFloat(start + i * 4) else input.getShort(start + i * 2) / 32768f)
        }
        val k = knobs
        MotifDsp.fxProcess(handle, buf, count, k.low.toDouble(), k.mid.toDouble(), k.high.toDouble(), k.filter.toDouble())
        input.position(input.limit())

        val output = replaceOutputBuffer(bytes)
        for (i in 0 until count) {
            val x = buf.getFloat(i * 4)
            if (isFloat) output.putFloat(x) else output.putShort((x * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
        }
        output.flip()
    }

    override fun onFlush() {
        // A seek or a new format: start the filters clean, at the current knobs.
        val format = inputAudioFormat
        if (format != handleFormat) {
            free()
            if (format != AudioFormat.NOT_SET) {
                handle = MotifDsp.fxNew(format.sampleRate, format.channelCount)
                handleFormat = format
            }
        } else if (handle != 0L) {
            MotifDsp.fxReset(handle)
        }
    }

    override fun onReset() {
        free()
        scratch = ByteBuffer.allocateDirect(0)
    }

    private fun free() {
        if (handle != 0L) MotifDsp.fxFree(handle)
        handle = 0L
        handleFormat = null
    }

    private fun scratch(floats: Int): ByteBuffer {
        if (scratch.capacity() < floats * 4) scratch = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())
        return scratch
    }
}
