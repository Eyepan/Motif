package app.motif.importer

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.motif.data.LibraryStore
import app.motif.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class ImportJob(val id: String, val fileName: String, val stage: Stage) {
    sealed interface Stage {
        data object Copying : Stage
        data class Analyzing(val progress: Float) : Stage
        data class Done(val track: Track) : Stage
        data class Failed(val message: String) : Stage
    }
}

/**
 * Add Music: copies picked files into the library untouched (no transcoding,
 * bit-perfect), reads their tags and format, then analyses them on device.
 * Runs in the app scope so it carries on when the sheet is closed.
 */
class Importer(
    private val context: Context,
    private val store: LibraryStore,
    private val scope: CoroutineScope,
    private val analyzeOnImport: () -> Boolean,
) {
    private val _jobs = MutableStateFlow<List<ImportJob>>(emptyList())
    val jobs: StateFlow<List<ImportJob>> = _jobs.asStateFlow()
    private val queue = Mutex()

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val pending = uris.map { ImportJob(UUID.randomUUID().toString(), displayName(it), ImportJob.Stage.Copying) to it }
        _jobs.update { it + pending.map { p -> p.first } }
        scope.launch(Dispatchers.IO) {
            queue.withLock { pending.forEach { (job, uri) -> run(job, uri) } }
        }
    }

    /** Imports every audio file under a picked folder (internal storage, SD card or USB drive). */
    fun importFolder(tree: Uri) {
        scope.launch(Dispatchers.IO) {
            val files = mutableListOf<Uri>()
            collectAudio(tree, DocumentsContract.getTreeDocumentId(tree), files)
            launch(Dispatchers.Main) { importFiles(files) }
        }
    }

    fun clearFinished() {
        _jobs.update { list -> list.filter { it.stage is ImportJob.Stage.Copying || it.stage is ImportJob.Stage.Analyzing } }
    }

    private fun setStage(id: String, stage: ImportJob.Stage) {
        _jobs.update { list -> list.map { if (it.id == id) ImportJob(it.id, it.fileName, stage) else it } }
    }

    private suspend fun run(job: ImportJob, uri: Uri) {
        val ext = job.fileName.substringAfterLast('.', "").lowercase()
        val id = UUID.randomUUID().toString()
        val dest = File(store.mediaDir, if (ext.isEmpty()) id else "$id.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it, 256 * 1024) }
            } ?: error("Couldn't open the file")
            var track = readTrack(id, dest, job.fileName)
            store.insert(track)

            if (analyzeOnImport()) {
                setStage(job.id, ImportJob.Stage.Analyzing(0f))
                val result = runCatching { TrackAnalyzer.analyze(dest) { setStage(job.id, ImportJob.Stage.Analyzing(it)) } }.getOrNull()
                if (result != null) {
                    store.updateAnalysis(id, result.bpm, result.loudnessDb, result.camelotKey, result.waveform)
                    track = track.copy(bpm = result.bpm, loudnessDb = result.loudnessDb, musicalKey = result.camelotKey, waveform = result.waveform)
                }
            }
            setStage(job.id, ImportJob.Stage.Done(track))
        } catch (e: Exception) {
            dest.delete()
            setStage(job.id, ImportJob.Stage.Failed(e.message ?: "Import failed"))
        }
    }

    private fun readTrack(id: String, file: File, fileName: String): Track {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            fun meta(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val mime = meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            if (mime != null && !mime.startsWith("audio/")) error("Not an audio file")
            var sampleRate: Int? = null
            var bitDepth: Int? = null
            if (Build.VERSION.SDK_INT >= 31) {
                sampleRate = meta(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
                bitDepth = meta(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
            }
            var channels: Int? = null
            var codecMime: String? = null
            var durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            runCatching {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                        .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    if (format != null) {
                        codecMime = format.getString(MediaFormat.KEY_MIME)
                        if (sampleRate == null) sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (durationMs == 0L && format.containsKey(MediaFormat.KEY_DURATION)) durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
                        if (bitDepth == null && format.containsKey("bits-per-sample")) bitDepth = format.getInteger("bits-per-sample")
                    }
                } finally {
                    extractor.release()
                }
            }
            return Track(
                id = id,
                title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fileName.substringBeforeLast('.'),
                artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = durationMs,
                filePath = file.name,
                format = formatName(fileName.substringAfterLast('.', "").lowercase(), codecMime ?: mime),
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                channels = channels,
                addedAt = System.currentTimeMillis() / 1000,
            )
        } finally {
            retriever.release()
        }
    }

    private fun collectAudio(tree: Uri, docId: String, out: MutableList<Uri>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE)
        context.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val mime = c.getString(1) ?: ""
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> collectAudio(tree, id, out)
                    mime.startsWith("audio/") -> out += DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            }
        }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled"

    private fun formatName(ext: String, mime: String?): String = when {
        ext in setOf("flac", "wav", "aiff", "mp3", "ogg") -> ext
        ext == "aif" -> "aiff"
        ext == "wave" -> "wav"
        ext == "oga" || ext == "opus" -> "ogg"
        ext == "m4a" || ext == "mp4" || ext == "aac" -> if (mime?.contains("alac") == true) "alac" else "aac"
        mime == "audio/flac" -> "flac"
        mime == "audio/mpeg" -> "mp3"
        else -> ext.ifEmpty { "audio" }
    }
}
