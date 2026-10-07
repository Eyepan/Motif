package app.motif

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Generated test audio: a stereo 16-bit / 44.1 kHz FLAC with Vorbis comment
 * tags, so the importer reads real tags and format details and the analyser
 * has a beat to find. Frames are stored VERBATIM (uncompressed), which keeps
 * the writer small while staying a valid FLAC stream.
 */
object TestAudio {
    const val SAMPLE_RATE = 44_100
    private const val BLOCK = 4096

    fun writeFlac(file: File, title: String, artist: String, album: String, bpm: Double = 120.0, seconds: Double = 8.0) {
        val frames = (SAMPLE_RATE * seconds).toInt()
        val samples = beat(frames, bpm)
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        out.write(blockHeader(last = false, type = 0, length = 34))
        out.write(streamInfo(frames))
        val tags = vorbisComment(listOf("TITLE=$title", "ARTIST=$artist", "ALBUM=$album"))
        out.write(blockHeader(last = true, type = 4, length = tags.size))
        out.write(tags)
        var start = 0
        var index = 0L
        while (start < frames) {
            val n = minOf(BLOCK, frames - start)
            out.write(frame(index++, samples, start, n))
            start += n
        }
        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }

    /** A kick-like decaying 60 Hz thump on every beat over a quiet 440 Hz tone. */
    private fun beat(frames: Int, bpm: Double): ShortArray {
        val beatLen = SAMPLE_RATE * 60.0 / bpm
        return ShortArray(frames) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val sinceBeat = (i % beatLen) / SAMPLE_RATE
            val kick = sin(2 * PI * 60 * sinceBeat) * exp(-sinceBeat * 18)
            val tone = 0.08 * sin(2 * PI * 440 * t)
            ((kick * 0.7 + tone) * Short.MAX_VALUE).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun blockHeader(last: Boolean, type: Int, length: Int) = byteArrayOf(
        ((if (last) 0x80 else 0) or type).toByte(),
        (length shr 16).toByte(), (length shr 8).toByte(), length.toByte(),
    )

    private fun streamInfo(totalFrames: Int): ByteArray {
        val b = Bits()
        b.put(BLOCK.toLong(), 16) // min block size
        b.put(BLOCK.toLong(), 16) // max block size
        b.put(0, 24) // min frame size: unknown
        b.put(0, 24) // max frame size: unknown
        b.put(SAMPLE_RATE.toLong(), 20)
        b.put(2 - 1L, 3) // channels - 1
        b.put(16 - 1L, 5) // bits per sample - 1
        b.put(totalFrames.toLong(), 36)
        repeat(16) { b.put(0, 8) } // MD5 unset
        return b.bytes()
    }

    private fun vorbisComment(comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        val vendor = "Motif tests".toByteArray()
        le32(vendor.size)
        out.write(vendor)
        le32(comments.size)
        comments.forEach { c ->
            val bytes = c.toByteArray(Charsets.UTF_8)
            le32(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun frame(index: Long, samples: ShortArray, start: Int, n: Int): ByteArray {
        val h = Bits()
        h.put(0b11111111111110, 14) // sync
        h.put(0, 1) // reserved
        h.put(0, 1) // fixed block size
        h.put(0b0111, 4) // block size: 16-bit (n - 1) after the header
        h.put(0b1001, 4) // 44.1 kHz
        h.put(0b0001, 4) // left, right
        h.put(0b100, 3) // 16 bits per sample
        h.put(0, 1) // reserved
        utf8(index).forEach { h.put(it.toLong() and 0xFF, 8) }
        h.put(n - 1L, 16)
        val header = h.bytes()

        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(crc8(header))
        repeat(2) { // same signal on both channels
            out.write(0b00000010) // VERBATIM subframe, no wasted bits
            for (i in start until start + n) {
                val s = samples[i].toInt()
                out.write(s shr 8)
                out.write(s)
            }
        }
        val crc = crc16(out.toByteArray())
        out.write(crc shr 8)
        out.write(crc)
        return out.toByteArray()
    }

    /** FLAC's UTF-8-style coding of the frame number. */
    private fun utf8(v: Long): ByteArray {
        if (v < 0x80) return byteArrayOf(v.toByte())
        var bytes = 2
        while (v >= (1L shl (5 * bytes + 1))) bytes++
        val out = ByteArray(bytes)
        var rest = v
        for (i in bytes - 1 downTo 1) {
            out[i] = (0x80 or (rest and 0x3F).toInt()).toByte()
            rest = rest shr 6
        }
        out[0] = ((0xFF00 shr bytes) and 0xFF or rest.toInt()).toByte()
        return out
    }

    private fun crc8(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x07 else crc shl 1 }
            crc = crc and 0xFF
        }
        return crc
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x8005 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }

    /** Big-endian bit writer. */
    private class Bits {
        private val out = ByteArrayOutputStream()
        private var acc = 0
        private var count = 0

        fun put(value: Long, bits: Int) {
            for (i in bits - 1 downTo 0) {
                acc = (acc shl 1) or ((value shr i) and 1).toInt()
                if (++count == 8) {
                    out.write(acc)
                    acc = 0
                    count = 0
                }
            }
        }

        fun bytes(): ByteArray {
            check(count == 0) { "not byte aligned" }
            return out.toByteArray()
        }
    }
}
