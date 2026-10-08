package app.motif.importer

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.motif.data.ArtworkStore
import app.motif.data.LibraryStore
import app.motif.data.RawTags
import app.motif.data.TagCleaner
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
        /** Not imported, for [reason] (a copy at equal or better quality is in the library). */
        data class Skipped(val reason: String) : Stage
        data class Failed(val message: String) : Stage
    }
}

/**
 * Add Music: copies picked files (or files Motif downloaded) into the library
 * untouched (no transcoding, bit-perfect), reads their tags and format, then
 * analyses them on device.
 * Runs in the app scope so it carries on when the sheet is closed.
 */
class Importer(
    private val context: Context,
    private val store: LibraryStore,
    private val artwork: ArtworkStore,
    private val scope: CoroutineScope,
    private val analyzeOnImport: () -> Boolean,
) {
    private val _jobs = MutableStateFlow<List<ImportJob>>(emptyList())
    val jobs: StateFlow<List<ImportJob>> = _jobs.asStateFlow()
    private val queue = Mutex()

    fun importFiles(uris: List<Uri>) = enqueue(uris.map { Picked(it, null) })

    /**
     * Imports every audio file under a picked folder (internal storage, SD card
     * or USB drive). A cover.jpg or similar beside the files is their art when
     * they have none embedded.
     */
    fun importFolder(tree: Uri) {
        scope.launch(Dispatchers.IO) {
            val files = mutableListOf<Picked>()
            collectAudio(tree, DocumentsContract.getTreeDocumentId(tree), files)
            launch(Dispatchers.Main) { enqueue(files) }
        }
    }

    /** An audio file to import and the folder cover image next to it, if any. */
    private class Picked(val uri: Uri, val cover: Uri?)

    private fun enqueue(picked: List<Picked>) {
        if (picked.isEmpty()) return
        val pending = picked.map { ImportJob(UUID.randomUUID().toString(), displayName(it.uri), ImportJob.Stage.Copying) to it }
        _jobs.update { it + pending.map { p -> p.first } }
        scope.launch(Dispatchers.IO) {
            queue.withLock { pending.forEach { (job, file) -> run(job, file.uri, file.cover) } }
        }
    }

    fun clearFinished() {
        _jobs.update { list -> list.filter { it.stage is ImportJob.Stage.Copying || it.stage is ImportJob.Stage.Analyzing } }
    }

    /**
     * Re-analyses tracks analysed before beat grids existed, one at a time
     * behind any imports, then calls [onDone].
     */
    fun backfillBeatGrids(tracks: List<Track>, onDone: () -> Unit) {
        val todo = tracks.filter { it.bpm != null && it.firstDownbeat == null }
        scope.launch(Dispatchers.IO) {
            for (track in todo) {
                queue.withLock {
                    val file = store.fileFor(track)
                    val r = if (file.exists()) runCatching { TrackAnalyzer.analyze(file) {} }.getOrNull() else null
                    if (r != null) store.updateAnalysis(track.id, r.bpm, r.loudnessDb, r.camelotKey, r.waveform, r.firstDownbeat)
                }
            }
            launch(Dispatchers.Main) { onDone() }
        }
    }

    private fun setStage(id: String, stage: ImportJob.Stage) {
        _jobs.update { list -> list.map { if (it.id == id) ImportJob(it.id, it.fileName, stage) else it } }
    }

    private suspend fun run(job: ImportJob, uri: Uri, cover: Uri?) {
        runCatching {
            add(job, null, cover?.let(::readCover)) { dest ->
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it, 256 * 1024) }
                } ?: error("Couldn't open the file")
            }
        }
    }

    /**
     * Adds a file Motif downloaded from a catalog. [info] fills in whatever the
     * file's own tags leave out, and [cover] is the catalog's art. Returns once
     * the track is in the library (and analysed, when that's on).
     */
    suspend fun importDownloaded(file: File, fileName: String, info: SourceInfo, cover: ByteArray?): Track {
        val job = ImportJob(UUID.randomUUID().toString(), fileName, ImportJob.Stage.Copying)
        _jobs.update { it + job }
        return queue.withLock {
            add(job, info, cover) { dest -> moveInto(file, dest) }
        }
    }

    /**
     * The track a file in the app's own storage would become, without adding it:
     * tags cleaned, format read. Throws for files that aren't readable audio.
     */
    fun probe(file: File, fileName: String): Track = readTrack(UUID.randomUUID().toString(), file, fileName, null).first

    /** Adds a file from the app's own storage (moved, not copied), like a picked file. */
    suspend fun importStaged(file: File, fileName: String, cover: ByteArray?): Track {
        val job = ImportJob(UUID.randomUUID().toString(), fileName, ImportJob.Stage.Copying)
        _jobs.update { it + job }
        return queue.withLock { add(job, null, cover) { dest -> moveInto(file, dest) } }
    }

    /**
     * Swaps [existing]'s audio for [file], a better copy of the same recording
     * ([probed] is what [probe] read from it). The track keeps its id, tags,
     * analysis and history; only the file and its format change.
     */
    suspend fun replaceFile(existing: Track, file: File, fileName: String, probed: Track, cover: ByteArray?): Track {
        val job = ImportJob(UUID.randomUUID().toString(), fileName, ImportJob.Stage.Copying)
        _jobs.update { it + job }
        return queue.withLock {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val dest = File(store.mediaDir, if (ext.isEmpty()) UUID.randomUUID().toString() else "${UUID.randomUUID()}.$ext")
            try {
                moveInto(file, dest)
                val track = existing.copy(
                    filePath = dest.name, format = probed.format, sampleRate = probed.sampleRate,
                    bitDepth = probed.bitDepth, channels = probed.channels, durationMs = probed.durationMs,
                )
                store.replaceFile(track)
                store.fileFor(existing).delete()
                if (!artwork.fileFor(existing.id).exists()) artwork.extract(existing.id, dest, fallback = cover)
                setStage(job.id, ImportJob.Stage.Done(track))
                track
            } catch (e: Exception) {
                dest.delete()
                setStage(job.id, ImportJob.Stage.Failed(e.message ?: "Import failed"))
                throw e
            }
        }
    }

    /** Lists a file that wasn't imported, with why. */
    fun skipped(fileName: String, reason: String) {
        _jobs.update { it + ImportJob(UUID.randomUUID().toString(), fileName, ImportJob.Stage.Skipped(reason)) }
    }

    /** Lists a file that couldn't be read at all. */
    fun failed(fileName: String, message: String) {
        _jobs.update { it + ImportJob(UUID.randomUUID().toString(), fileName, ImportJob.Stage.Failed(message)) }
    }

    private fun moveInto(file: File, dest: File) {
        if (!file.renameTo(dest)) {
            file.copyTo(dest, overwrite = true)
            file.delete()
        }
    }

    /** Where a downloaded file came from, and the catalog's tags for it. */
    class SourceInfo(
        val source: String,
        val sourceRef: String,
        val licenseUrl: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
    )

    /**
     * Puts the file in the media folder via [fill], reads it, saves it and
     * analyses it. [cover] (a folder cover.jpg or the catalog's art) is used
     * when the file has no embedded picture.
     */
    private suspend fun add(job: ImportJob, info: SourceInfo?, cover: ByteArray?, fill: (File) -> Unit): Track {
        val ext = job.fileName.substringAfterLast('.', "").lowercase()
        val id = UUID.randomUUID().toString()
        val dest = File(store.mediaDir, if (ext.isEmpty()) id else "$id.$ext")
        try {
            fill(dest)
            val (read, raw) = readTrack(id, dest, job.fileName, info)
            var track = read
            // Art first, so the row shows it as soon as the track appears.
            artwork.extract(id, dest, fallback = cover)
            store.insert(track, raw)

            if (analyzeOnImport()) {
                setStage(job.id, ImportJob.Stage.Analyzing(0f))
                val result = runCatching { TrackAnalyzer.analyze(dest) { setStage(job.id, ImportJob.Stage.Analyzing(it)) } }.getOrNull()
                if (result != null) {
                    store.updateAnalysis(id, result.bpm, result.loudnessDb, result.camelotKey, result.waveform, result.firstDownbeat)
                    track = track.copy(
                        bpm = result.bpm, loudnessDb = result.loudnessDb, musicalKey = result.camelotKey,
                        waveform = result.waveform, firstDownbeat = result.firstDownbeat,
                    )
                }
            }
            setStage(job.id, ImportJob.Stage.Done(track))
            return track
        } catch (e: Exception) {
            dest.delete()
            artwork.delete(id)
            setStage(job.id, ImportJob.Stage.Failed(e.message ?: "Import failed"))
            throw e
        }
    }

    /** The track with cleaned tags (the catalog's where the file has none), and the tags as read. */
    private fun readTrack(id: String, file: File, fileName: String, info: SourceInfo?): Pair<Track, RawTags> {
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
            val raw = RawTags(
                title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            )
            val suffixes = store.siteSuffixes + listOfNotNull(TagCleaner.siteSuffix(raw))
            val albumArtist = TagCleaner.clean(raw.albumArtist, suffixes)
            return Track(
                id = id,
                title = TagCleaner.clean(raw.title, suffixes) ?: info?.title ?: fileName.substringBeforeLast('.'),
                artist = TagCleaner.clean(raw.artist, suffixes) ?: albumArtist ?: info?.artist,
                album = TagCleaner.clean(raw.album, suffixes) ?: info?.album,
                albumArtist = albumArtist,
                durationMs = durationMs,
                filePath = file.name,
                format = formatName(fileName.substringAfterLast('.', "").lowercase(), codecMime ?: mime),
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                channels = channels,
                source = info?.source ?: "local",
                sourceRef = info?.sourceRef,
                licenseUrl = info?.licenseUrl,
                addedAt = System.currentTimeMillis() / 1000,
            ) to raw
        } finally {
            retriever.release()
        }
    }

    private fun collectAudio(tree: Uri, docId: String, out: MutableList<Picked>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val audio = mutableListOf<Uri>()
        val images = mutableMapOf<String, String>()
        context.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val mime = c.getString(1) ?: ""
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> collectAudio(tree, id, out)
                    mime.startsWith("audio/") -> audio += DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    mime.startsWith("image/") -> c.getString(2)?.let { images[it] = id }
                }
            }
        }
        val cover = ArtworkStore.pickFolderCover(images.keys.toList())?.let { DocumentsContract.buildDocumentUriUsingTree(tree, images[it]) }
        audio.forEach { out += Picked(it, cover) }
    }

    /** The last folder cover read; a folder's tracks import one after another and share it. */
    private var lastCover: Pair<Uri, ByteArray?>? = null

    /** A folder cover's bytes, skipping anything implausibly large for a picture. */
    private fun readCover(uri: Uri): ByteArray? {
        lastCover?.let { (u, bytes) -> if (u == uri) return bytes }
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().takeIf { it.size <= 20 * 1024 * 1024 }
            }
        }.getOrNull()
        lastCover = uri to bytes
        return bytes
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
