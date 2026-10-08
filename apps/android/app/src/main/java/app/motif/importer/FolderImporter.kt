package app.motif.importer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.motif.data.ArtworkStore
import app.motif.data.LibraryStore
import app.motif.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * "Read from Downloads": imports the music that lands in a folder the user
 * picked, whatever site or app put it there. It finds audio files, album
 * folders and .zip archives of audio, unpacks archives, and keeps one copy of
 * each recording: a file already in the library at equal or better quality is
 * skipped, a better one replaces the library's file (see [Dedupe]). Files go
 * through [Importer], so tags, art and analysis work as for any import. It
 * only reads local files; it never downloads anything.
 *
 * Android can't watch a folder for an app that isn't running, so the folder
 * is read when Motif opens (and comes back to the foreground). Each file or
 * archive is read once: what was handled (document id, size and modification
 * time) is remembered. Files changed in the last few seconds are left for a
 * later pass, since they may still be downloading.
 */
class FolderImporter(
    private val context: Context,
    private val importer: Importer,
    private val store: LibraryStore,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
) {
    data class Report(var added: Int = 0, var upgraded: Int = 0, var duplicates: Int = 0, var failed: Int = 0, var waiting: Boolean = false)

    private val _folder = MutableStateFlow(prefs.getString(KEY_FOLDER, null)?.let(Uri::parse))
    /** The picked folder (a tree URI), or null. */
    val folder: StateFlow<Uri?> = _folder.asStateFlow()

    private val staging = File(context.cacheDir, "folder-import").apply {
        // Leftovers from a pass the system killed.
        deleteRecursively()
        mkdirs()
    }
    private val running = Mutex()
    @Volatile private var again = false
    private var retry: Job? = null

    /** Starts reading [tree] now and on every launch. */
    fun watch(tree: Uri) {
        // The system picker grants this; a provider that already shares the tree may not need it.
        runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs.edit().putString(KEY_FOLDER, tree.toString()).remove(KEY_SEEN).apply()
        _folder.value = tree
        scan()
    }

    fun stop() {
        _folder.value?.let { tree ->
            runCatching { context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs.edit().remove(KEY_FOLDER).remove(KEY_SEEN).apply()
        _folder.value = null
    }

    /** Reads the folder in the background. A call while a pass runs adds one more pass after it. */
    fun scan() {
        val tree = _folder.value ?: return
        scope.launch(Dispatchers.IO) {
            if (!running.tryLock()) {
                again = true
                return@launch
            }
            try {
                do {
                    again = false
                    val report = runCatching { scanOnce(tree) }.getOrElse { Report(failed = 1) }
                    if (report.waiting) {
                        retry?.cancel()
                        retry = scope.launch { delay(SETTLE_MS + 2_000); scan() }
                    }
                } while (again)
            } finally {
                running.unlock()
            }
        }
    }

    /** One pass over [tree]; returns what it did. Runs on the IO dispatcher. */
    suspend fun scanOnce(tree: Uri, now: Long = System.currentTimeMillis()): Report {
        // Duplicates are judged against the whole library.
        store.awaitLoaded()
        val report = Report()
        val seen = prefs.getStringSet(KEY_SEEN, emptySet())!!.toMutableSet()
        val present = mutableSetOf<String>()
        val pending = mutableListOf<Item>()
        val covers = mutableMapOf<String, Uri>()
        collect(tree, DocumentsContract.getTreeDocumentId(tree), 0, pending, covers)
        val ready = pending.filter { item ->
            val settled = now - item.modified >= SETTLE_MS
            if (!settled) report.waiting = true
            if (settled) present += item.fingerprint
            settled && item.fingerprint !in seen
        }

        // Loose files import a folder at a time, so an album folder holding both
        // MP3 and FLAC copies imports only the FLAC.
        val batches = ready.filter { it.isArchive }.map { listOf(it) } +
            ready.filterNot { it.isArchive }.groupBy { it.parent }.values
        for (batch in batches.sortedBy { b -> b.minOf { it.modified } }) {
            val dir = File(staging, UUID.randomUUID().toString()).apply { mkdirs() }
            try {
                val files = if (batch[0].isArchive) {
                    unzip(batch[0], dir, report)
                } else {
                    val cover = covers[batch[0].parent]?.let(::readSmall)
                    batch.mapNotNull { item -> stage(item, dir, report)?.let { Staged(it, item.name, cover) } }
                }
                importBatch(files, report)
            } finally {
                dir.deleteRecursively()
            }
            seen += batch.map { it.fingerprint }
            prefs.edit().putStringSet(KEY_SEEN, seen.toSet()).apply()
        }
        // Forget files that are gone, so the record doesn't grow forever.
        prefs.edit().putStringSet(KEY_SEEN, seen.intersect(present)).apply()
        return report
    }

    // Finding files

    private class Item(val uri: Uri, val name: String, val parent: String, val size: Long, val modified: Long, val docId: String) {
        val fingerprint get() = "$docId|$size|$modified"
        val isArchive get() = extension(name) in ARCHIVE_EXTENSIONS
    }

    private fun collect(tree: Uri, docId: String, depth: Int, out: MutableList<Item>, covers: MutableMap<String, Uri>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        val images = mutableMapOf<String, String>()
        context.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val mime = c.getString(1) ?: ""
                val name = c.getString(2) ?: continue
                if (name.startsWith(".") || extension(name) in PARTIAL_EXTENSIONS) continue
                val ext = extension(name)
                when {
                    mime == Document.MIME_TYPE_DIR -> if (depth + 1 < MAX_DEPTH) collect(tree, id, depth + 1, out, covers)
                    ext in AUDIO_EXTENSIONS || ext in ARCHIVE_EXTENSIONS -> out += Item(
                        DocumentsContract.buildDocumentUriUsingTree(tree, id), name, docId,
                        if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4), id,
                    )
                    mime.startsWith("image/") -> images[name] = id
                }
            }
        }
        ArtworkStore.pickFolderCover(images.keys.toList())?.let { covers[docId] = DocumentsContract.buildDocumentUriUsingTree(tree, images[it]) }
    }

    // Staging: every candidate is copied into the app's cache, read, then moved into the library or dropped.

    private class Staged(val file: File, val name: String, val cover: ByteArray?)

    private fun stage(item: Item, dir: File, report: Report): File? {
        val dest = File(dir, "${UUID.randomUUID()}.${extension(item.name)}")
        return runCatching {
            context.contentResolver.openInputStream(item.uri)!!.use { input -> dest.outputStream().use { input.copyTo(it, 256 * 1024) } }
            dest
        }.getOrElse {
            dest.delete()
            report.failed++
            importer.failed(item.name, "Couldn't read the file")
            null
        }
    }

    /** Audio entries of an archive into [dir]; each folder's cover image goes with its tracks. */
    private fun unzip(item: Item, dir: File, report: Report): List<Staged> {
        class Entry(val name: String, val folder: String, val file: File)
        val entries = mutableListOf<Entry>()
        val covers = mutableMapOf<String, ByteArray>()
        val result = runCatching {
            ZipInputStream(context.contentResolver.openInputStream(item.uri)!!.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = entry.name.replace('\\', '/')
                    val name = path.substringAfterLast('/')
                    val folder = path.substringBeforeLast('/', "")
                    val hidden = path.split('/').any { it.startsWith(".") || it == "__MACOSX" }
                    val ext = extension(name)
                    if (entry.isDirectory || hidden) continue
                    if (ext in AUDIO_EXTENSIONS) {
                        // Named by Motif, never by the archive, so entry paths can't point outside the folder.
                        val dest = File(dir, "${UUID.randomUUID()}.$ext")
                        if (copyLimited(zip, dest, MAX_TRACK_BYTES)) {
                            entries += Entry(name, folder, dest)
                        } else {
                            dest.delete()
                            report.failed++
                            importer.failed(name, "Too large to unpack from ${item.name}")
                        }
                    } else if (ArtworkStore.pickFolderCover(listOf(name)) != null) {
                        readLimited(zip, MAX_COVER_BYTES)?.let { covers[folder] = it }
                    }
                }
            }
        }
        if (result.isFailure && entries.isEmpty()) {
            report.failed++
            importer.failed(item.name, "Couldn't open the archive")
            return emptyList()
        }
        return entries.map { Staged(it.file, it.name, covers[it.folder] ?: covers.values.singleOrNull()) }
    }

    /** Reads every staged file, then imports best copy first against what the library holds. */
    private suspend fun importBatch(files: List<Staged>, report: Report) {
        val probed = files.mapNotNull { s ->
            runCatching { s to importer.probe(s.file, s.name) }.getOrElse {
                report.failed++
                importer.failed(s.name, it.message ?: "Not an audio file")
                null
            }
        }
        val copies = probed.map { (s, t) -> Dedupe.Copy.of(t, s.file.length()) }
        for (i in Dedupe.bestFirst(copies)) {
            val (staged, track) = probed[i]
            val key = Dedupe.key(track.title, track.artist)
            val matches = store.tracks.value.filter { keyOf(it) == key }
            runCatching {
                when (val verdict = Dedupe.resolve(copies[i], matches.map { Dedupe.Copy.of(it, store.fileFor(it).length()) })) {
                    is Dedupe.Verdict.Duplicate -> {
                        report.duplicates++
                        importer.skipped(staged.name, "Already in your library as ${matches[verdict.index].shortQualityLabel}")
                    }
                    is Dedupe.Verdict.Upgrade -> {
                        importer.replaceFile(matches[verdict.index], staged.file, staged.name, track, staged.cover)
                        report.upgraded++
                    }
                    Dedupe.Verdict.New -> {
                        importer.importStaged(staged.file, staged.name, staged.cover)
                        report.added++
                    }
                }
            }.onFailure { report.failed++ }
        }
    }

    private val keys = HashMap<String, String>()

    /** [Dedupe.key] per library track, cached by id and title. */
    private fun keyOf(track: Track): String =
        keys.getOrPut("${track.id}\u001f${track.title}\u001f${track.artist}") { Dedupe.key(track.title, track.artist) }

    private fun readSmall(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_COVER_BYTES) }
    }.getOrNull()

    companion object {
        private const val KEY_FOLDER = "watched_folder"
        private const val KEY_SEEN = "watched_folder_seen"
        /** A file this recently modified may still be downloading. */
        const val SETTLE_MS = 10_000L
        /** Album folders nest a level or two (Album/CD1/01.flac); deeper trees are projects, not music. */
        private const val MAX_DEPTH = 3
        private const val MAX_TRACK_BYTES = 4L shl 30
        private const val MAX_COVER_BYTES = 20 * 1024 * 1024
        val AUDIO_EXTENSIONS = setOf("flac", "wav", "wave", "aif", "aiff", "m4a", "mp4", "aac", "mp3", "ogg", "oga", "opus")
        private val ARCHIVE_EXTENSIONS = setOf("zip")
        /** Downloads still in progress (Chrome, Firefox, Safari, generic). */
        private val PARTIAL_EXTENSIONS = setOf("crdownload", "part", "download", "tmp")

        fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

        /** Copies at most [limit] bytes; false if the stream holds more. */
        fun copyLimited(input: InputStream, dest: File, limit: Long): Boolean = dest.outputStream().use { out ->
            val buffer = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) return false
                out.write(buffer, 0, n)
            }
            true
        }

        /** The stream's bytes if it holds at most [limit]. */
        fun readLimited(input: InputStream, limit: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return out.toByteArray()
                if (out.size() + n > limit) return null
                out.write(buffer, 0, n)
            }
        }
    }
}
