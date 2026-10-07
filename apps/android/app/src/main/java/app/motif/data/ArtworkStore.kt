package app.motif.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Album art, extracted once at import from the file's embedded picture (ID3
 * APIC, FLAC PICTURE, MP4 covr, Vorbis comment) or a cover image beside it in
 * the picked folder, scaled down and kept as `files/artwork/<track id>.jpg`.
 * A `.none` marker records a track that has no art, so it isn't looked at again.
 *
 * The files are a local cache: the source file is never rewritten, and
 * schema v3's `artwork_hash` can take over the naming once it lands.
 */
class ArtworkStore(filesDir: File) {
    private val dir = File(filesDir, "artwork").apply { mkdirs() }
    private val backfill = Mutex()

    /** Ids of tracks that have art on disk; Compose reads this to know what to load. */
    private val _available = MutableStateFlow<Set<String>>(emptySet())
    val available: StateFlow<Set<String>> = _available.asStateFlow()

    /** Decoded bitmaps by "id@size", capped at [CACHE_BYTES] so long lists stay light. */
    private val bitmaps = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val tints = HashMap<String, Int>()

    fun fileFor(trackId: String) = File(dir, "$trackId.jpg")
    private fun markerFor(trackId: String) = File(dir, "$trackId.none")

    /**
     * Extracts art for a newly imported track: the embedded picture first, else
     * [folderCover] (bytes of cover.jpg or similar from the same folder).
     */
    suspend fun extract(trackId: String, audio: File, folderCover: ByteArray? = null) = withContext(Dispatchers.IO) {
        val bytes = runCatching { embeddedPicture(audio) }.getOrNull() ?: folderCover
        val saved = bytes != null && runCatching { save(bytes, fileFor(trackId)) }.getOrDefault(false)
        if (saved) {
            markerFor(trackId).delete()
            _available.update { it + trackId }
        } else {
            markerFor(trackId).createNewFile()
        }
    }

    /** Finds art on disk and extracts it for tracks imported before art was read. */
    suspend fun sync(tracks: List<Track>, fileFor: (Track) -> File) = backfill.withLock {
        withContext(Dispatchers.IO) {
            val names = dir.list()?.toHashSet() ?: hashSetOf()
            val found = tracks.mapNotNullTo(HashSet()) { t -> t.id.takeIf { "${t.id}.jpg" in names } }
            _available.update { it + found }
            tracks.filter { "${it.id}.jpg" !in names && "${it.id}.none" !in names }
                .forEach { extract(it.id, fileFor(it)) }
        }
    }

    fun delete(trackId: String) {
        fileFor(trackId).delete()
        markerFor(trackId).delete()
        _available.update { it - trackId }
    }

    /** Already-decoded art, without touching disk. */
    fun cached(trackId: String, sizePx: Int): Bitmap? = bitmaps.get("$trackId@$sizePx")

    /** The art for [trackId] decoded to about [sizePx] square, or null. Call off the main thread. */
    fun load(trackId: String, sizePx: Int): Bitmap? {
        val key = "$trackId@$sizePx"
        bitmaps.get(key)?.let { return it }
        val file = fileFor(trackId)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight), sizePx)
        }
        val bitmap = BitmapFactory.decodeFile(file.path, opts) ?: return null
        bitmaps.put(key, bitmap)
        return bitmap
    }

    /** A dark backdrop colour taken from the art, as ARGB, or null without art. Call off the main thread. */
    fun tint(trackId: String): Int? {
        synchronized(tints) { tints[trackId]?.let { return it } }
        val small = load(trackId, 64) ?: return null
        val scaled = Bitmap.createScaledBitmap(small, 16, 16, true)
        val pixels = IntArray(16 * 16)
        scaled.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        if (scaled !== small) scaled.recycle()
        val tint = backdropTint(pixels)
        synchronized(tints) { tints[trackId] = tint }
        return tint
    }

    private fun embeddedPicture(audio: File): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audio.absolutePath)
            retriever.embeddedPicture
        } finally {
            retriever.release()
        }
    }

    /** Re-encodes to a JPEG no bigger than [MAX_EDGE] px, so a 3000 px scan doesn't cost memory on every screen. */
    private fun save(bytes: ByteArray, dest: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val edge = max(bounds.outWidth, bounds.outHeight)
        if (edge <= 0) return false
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize(edge, MAX_EDGE) },
        ) ?: return false
        val scale = MAX_EDGE.toFloat() / max(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
                .also { decoded.recycle() }
        } else decoded
        val tmp = File(dest.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return tmp.renameTo(dest)
    }

    companion object {
        const val MAX_EDGE = 1024
        private const val CACHE_BYTES = 24 * 1024 * 1024

        /** Largest power-of-two step down that still leaves the image at least [target] px. */
        fun sampleSize(edge: Int, target: Int): Int {
            var n = 1
            while (target > 0 && edge / (n * 2) >= target) n *= 2
            return n
        }

        /**
         * Picks the folder image to use as art, by the names players commonly
         * write: cover, folder, front, album, in that order. Null if none fits.
         */
        fun pickFolderCover(names: List<String>): String? {
            val images = names.filter { it.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            return COVER_NAMES.firstNotNullOfOrNull { stem ->
                images.firstOrNull { it.substringBeforeLast('.').lowercase() == stem }
            }
        }

        private val COVER_NAMES = listOf("cover", "folder", "front", "album", "albumart", "albumartsmall")

        /**
         * The art's most colourful average, darkened enough that white text and
         * the accent stay readable on it: value at most 0.42, saturation at most 0.6.
         */
        fun backdropTint(pixels: IntArray): Int {
            var r = 0.0; var g = 0.0; var b = 0.0; var weight = 0.0
            for (p in pixels) {
                val pr = (p shr 16 and 0xFF) / 255.0
                val pg = (p shr 8 and 0xFF) / 255.0
                val pb = (p and 0xFF) / 255.0
                val hi = maxOf(pr, pg, pb)
                val lo = minOf(pr, pg, pb)
                // Saturated, mid-bright pixels say more about the cover than black borders or white type.
                val w = 0.05 + (hi - lo) * (1 - kotlin.math.abs(hi + lo - 1))
                r += pr * w; g += pg * w; b += pb * w; weight += w
            }
            val hsv = rgbToHsv(r / weight, g / weight, b / weight)
            return hsvToArgb(hsv[0], minOf(hsv[1], 0.6), minOf(hsv[2], 0.42))
        }

        private fun rgbToHsv(r: Double, g: Double, b: Double): DoubleArray {
            val hi = maxOf(r, g, b)
            val lo = minOf(r, g, b)
            val d = hi - lo
            val h = when {
                d == 0.0 -> 0.0
                hi == r -> 60 * (((g - b) / d).mod(6.0))
                hi == g -> 60 * ((b - r) / d + 2)
                else -> 60 * ((r - g) / d + 4)
            }
            return doubleArrayOf(h, if (hi == 0.0) 0.0 else d / hi, hi)
        }

        private fun hsvToArgb(h: Double, s: Double, v: Double): Int {
            val c = v * s
            val x = c * (1 - kotlin.math.abs((h / 60).mod(2.0) - 1))
            val m = v - c
            val (r, g, b) = when ((h / 60).toInt().coerceIn(0, 5)) {
                0 -> Triple(c, x, 0.0)
                1 -> Triple(x, c, 0.0)
                2 -> Triple(0.0, c, x)
                3 -> Triple(0.0, x, c)
                4 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
            fun ch(v: Double) = ((v + m) * 255).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
    }
}
