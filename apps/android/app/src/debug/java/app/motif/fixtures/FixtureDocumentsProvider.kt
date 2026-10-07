package app.motif.fixtures

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Debug-only documents provider over `cacheDir/fixtures`, so emulator tests can
 * hand the importer a folder (tree URI) the way the system picker would. It
 * lists no roots, so it never shows up in the Files app or the picker.
 * Document ids are "root" for the fixtures folder and "root/<relative path>" below it.
 */
class FixtureDocumentsProvider : DocumentsProvider() {
    private val base: File get() = File(context!!.cacheDir, "fixtures")

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT).also { add(it, documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DEFAULT).also { c ->
            fileFor(parentDocumentId).listFiles()?.sortedBy { it.name }?.forEach { add(c, idFor(it)) }
        }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith("$parentDocumentId/")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.MODE_READ_ONLY)

    private fun add(c: MatrixCursor, id: String) {
        val file = fileFor(id)
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(Document.COLUMN_MIME_TYPE, mimeOf(file))
            .add(Document.COLUMN_SIZE, file.length())
            .add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
    }

    private fun fileFor(id: String): File {
        val file = if (id == TOP) base else File(base, id.removePrefix("$TOP/"))
        if (!file.canonicalPath.startsWith(base.canonicalPath) || !file.exists()) throw FileNotFoundException(id)
        return file
    }

    private fun idFor(file: File): String = "$TOP/" + file.relativeTo(base).path

    private fun mimeOf(file: File): String = when {
        file.isDirectory -> Document.MIME_TYPE_DIR
        file.extension.equals("flac", ignoreCase = true) -> "audio/flac"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    }

    companion object {
        const val TOP = "root"
        private val DEFAULT = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_FLAGS, Document.COLUMN_LAST_MODIFIED,
        )
    }
}
