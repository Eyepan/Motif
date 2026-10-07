package app.motif.importer

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.motif.data.LibraryStore
import app.motif.dsp.MotifDsp
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** Result of on-device analysis, in the library's units. */
class AnalysisResult(
    val bpm: Double?,
    val loudnessDb: Double?,
    val camelotKey: String?,
    val waveform: ByteArray,
    /** Seconds to the first downbeat, when a beat grid was found. */
    val firstDownbeat: Double?,
)

/**
 * Decodes a file with the platform codecs and streams PCM through the DSP
 * core's analyzer: tempo, beat grid, Camelot key, loudness and a waveform overview.
 * Memory stays flat: one decoder buffer at a time.
 */
object TrackAnalyzer {
    suspend fun analyze(file: File, onProgress: (Float) -> Unit): AnalysisResult? {
        if (!MotifDsp.available) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var handle = 0L
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            // Ask for float output; decoders that ignore it report what they chose in the output format.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var samples = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var lastProgress = -1

            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = codec.outputFormat
                    encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    if (handle == 0L) {
                        handle = MotifDsp.analyzerNew(out.getInteger(MediaFormat.KEY_SAMPLE_RATE), out.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    }
                } else if (outIndex >= 0) {
                    if (handle == 0L) {
                        // Some decoders never send a format change; use the input format.
                        handle = MotifDsp.analyzerNew(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    }
                    val buffer = codec.getOutputBuffer(outIndex)!!
                    buffer.position(info.offset).limit(info.offset + info.size)
                    buffer.order(ByteOrder.nativeOrder())
                    val count = when (encoding) {
                        AudioFormat.ENCODING_PCM_FLOAT -> info.size / 4
                        AudioFormat.ENCODING_PCM_32BIT -> info.size / 4
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> info.size / 3
                        AudioFormat.ENCODING_PCM_8BIT -> info.size
                        else -> info.size / 2
                    }
                    if (samples.size < count) samples = FloatArray(count)
                    when (encoding) {
                        AudioFormat.ENCODING_PCM_FLOAT -> buffer.asFloatBuffer().get(samples, 0, count)
                        AudioFormat.ENCODING_PCM_32BIT -> {
                            val ints = buffer.asIntBuffer()
                            for (i in 0 until count) samples[i] = ints.get(i) / 2147483648f
                        }
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                            val base = buffer.position()
                            for (i in 0 until count) {
                                val p = base + i * 3
                                val v = (buffer.get(p).toInt() and 0xFF) or ((buffer.get(p + 1).toInt() and 0xFF) shl 8) or (buffer.get(p + 2).toInt() shl 16)
                                samples[i] = v / 8388608f
                            }
                        }
                        AudioFormat.ENCODING_PCM_8BIT -> {
                            val base = buffer.position()
                            for (i in 0 until count) samples[i] = ((buffer.get(base + i).toInt() and 0xFF) - 128) / 128f
                        }
                        else -> {
                            val shorts = buffer.asShortBuffer()
                            for (i in 0 until count) samples[i] = shorts.get(i) / 32768f
                        }
                    }
                    if (handle != 0L && count > 0) MotifDsp.analyzerPush(handle, samples, count)
                    codec.releaseOutputBuffer(outIndex, false)
                    if (durationUs > 0) {
                        val pct = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                        if (pct != lastProgress) {
                            lastProgress = pct
                            onProgress(pct / 100f)
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }

            if (handle == 0L) return null
            val r = MotifDsp.analyzerFinish(handle) ?: return null
            val overview = MotifDsp.analyzerOverview(handle, LibraryStore.WAVEFORM_LENGTH) ?: FloatArray(LibraryStore.WAVEFORM_LENGTH)
            val camelot = r[3].toInt()
            return AnalysisResult(
                bpm = r[2].takeIf { it > 0 }?.toDouble(),
                loudnessDb = r[1].takeIf { it.isFinite() }?.toDouble(),
                camelotKey = if (camelot in 1..12) "$camelot${if (r[4] > 0f) "A" else "B"}" else null,
                waveform = ByteArray(overview.size) { (overview[it] * 255).roundToInt().coerceIn(0, 255).toByte() },
                firstDownbeat = r.getOrNull(5)?.takeIf { it >= 0f && r[2] > 0f }?.toDouble(),
            )
        } finally {
            if (handle != 0L) MotifDsp.analyzerFree(handle)
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}
