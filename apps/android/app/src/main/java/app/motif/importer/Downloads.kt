package app.motif.importer

import app.motif.sources.Http
import app.motif.sources.MusicSource
import app.motif.sources.SourceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID

/**
 * One-tap downloads from Discover: fetches every file of a result plus its
 * cover, then hands each to [Importer] so it lands in the library tagged,
 * with art, and analysed. Runs in the app scope, so leaving Discover doesn't
 * stop it. Downloads two results at a time; the rest wait their turn.
 */
class Downloads(cacheDir: File, private val importer: Importer, private val scope: CoroutineScope) {
    sealed interface State {
        data object Waiting : State
        /** [fraction] covers every file of the result. */
        data class Downloading(val file: Int, val files: Int, val fraction: Float) : State
        data class Done(val files: Int) : State
        data class Failed(val message: String) : State
    }

    private val dir = File(cacheDir, "downloads").apply {
        mkdirs()
        // Leftovers from a download the system killed.
        listFiles()?.forEach(File::delete)
    }
    private val slots = Semaphore(2)
    private val running = HashMap<String, Job>()

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    fun key(source: MusicSource, result: SourceResult) = "${source.id}:${result.id}"

    fun start(source: MusicSource, result: SourceResult) {
        val key = key(source, result)
        synchronized(running) {
            if (running[key]?.isActive == true || _states.value[key] is State.Done) return
            set(key, State.Waiting)
            running[key] = scope.launch(Dispatchers.IO) {
                try {
                    slots.withPermit { fetch(key, source, result) }
                } catch (e: CancellationException) {
                    _states.update { it - key }
                    throw e
                } catch (e: Exception) {
                    set(key, State.Failed(e.message ?: "Download failed"))
                }
            }
        }
    }

    fun cancel(source: MusicSource, result: SourceResult) {
        synchronized(running) { running.remove(key(source, result)) }?.cancel()
    }

    private suspend fun fetch(key: String, source: MusicSource, result: SourceResult) {
        set(key, State.Downloading(0, 1, 0f))
        val release = source.release(result)
        val files = release.files
        val cover = release.coverUrl?.let { runCatching { Http.bytes(it) }.getOrNull() }
        // The next file downloads while the last one is imported and analysed.
        coroutineScope {
            files.forEachIndexed { index, file ->
                val tmp = File(dir, "${UUID.randomUUID()}.${file.format}")
                try {
                    Http.download(file.url, tmp) { done, total ->
                        val part = if (total > 0) done.toFloat() / total else 0f
                        set(key, State.Downloading(index, files.size, (index + part.coerceIn(0f, 1f)) / files.size))
                    }
                } catch (e: Exception) {
                    tmp.delete()
                    throw e
                }
                val info = Importer.SourceInfo(
                    source = source.id,
                    sourceRef = file.sourceRef,
                    licenseUrl = result.licenseUrl,
                    title = file.title,
                    artist = file.artist ?: result.artist,
                    album = file.album ?: result.album,
                )
                launch {
                    try {
                        importer.importDownloaded(tmp, file.fileName, info, cover)
                    } finally {
                        tmp.delete()
                    }
                }
            }
        }
        set(key, State.Done(files.size))
    }

    private fun set(key: String, state: State) = _states.update { it + (key to state) }
}
