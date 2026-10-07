package app.motif.importer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.max

/**
 * Cover art a catalog supplies for a download, kept as `files/artwork/<track id>.jpg`:
 * the same place and size the library's embedded-art extraction uses, so the
 * library shows it like any other cover.
 */
object CatalogArt {
    private const val MAX_EDGE = 1024

    fun fileFor(filesDir: File, trackId: String) = File(File(filesDir, "artwork").apply { mkdirs() }, "$trackId.jpg")

    /** Decodes, scales down to [MAX_EDGE] px and saves as JPEG. False when [bytes] aren't an image. */
    fun save(bytes: ByteArray, dest: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val edge = max(bounds.outWidth, bounds.outHeight)
        if (edge <= 0) return false
        var sample = 1
        while (edge / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val scale = MAX_EDGE.toFloat() / max(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (bitmap !== decoded) bitmap.recycle()
        decoded.recycle()
        return true
    }
}
