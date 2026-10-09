package app.motif.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Writes `play` events to the history log (docs/analytics.md).
 * [app.motif.playback.PlaybackEngine] reports each finished [PlaySession] and
 * checkpoints the one in progress, so a crash or the system killing the app
 * still records the listen as `interrupted` on the next launch. DJ decks
 * report their listens through [recordListen].
 *
 * Call from the main thread; writes happen in order on a background worker.
 */
class PlayRecorder(
    private val history: HistoryStore,
    private val library: LibraryStore,
    scope: CoroutineScope,
    /** Pause history: while true nothing is written or checkpointed. */
    private val isPaused: () -> Boolean,
    /** After a play is written, e.g. to refresh counts and schedule an upload. Called off the main thread. */
    private val onRecorded: () -> Unit,
) {
    /** One worker, so a checkpoint can't land after the play that cleared it. */
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var lastCheckpointMs = 0L

    init {
        scope.launch(Dispatchers.IO) {
            for (write in writes) runCatching { write() }
        }
        // Before any checkpoint of this launch can replace it.
        writes.trySend { recoverInterrupted() }
    }

    /** A play from the player ended: write it and drop its checkpoint. */
    fun finish(payload: PlayPayload, track: Track) {
        lastCheckpointMs = 0
        writes.trySend { history.setSyncValue(CHECKPOINT_KEY, null) }
        record(payload, track, System.currentTimeMillis())
    }

    /** One listen on a DJ Mix deck. */
    fun recordListen(track: Track, startedAtMs: Long, listenedMs: Long, endReason: PlayEndReason, mixedIn: Boolean, mixedOut: Boolean) {
        record(
            PlayPayload(
                trackId = track.id, startedAtMs = startedAtMs, listenedMs = listenedMs, durationMs = track.durationMs,
                endReason = endReason, context = PlayContext.MIX, mixedIn = mixedIn, mixedOut = mixedOut,
            ),
            track, System.currentTimeMillis(),
        )
    }

    /** Saves the play in progress, at most every 15 s. */
    fun checkpoint(session: PlaySession) {
        val now = System.currentTimeMillis()
        if (now - lastCheckpointMs < CHECKPOINT_INTERVAL_MS) return
        lastCheckpointMs = now
        val value = if (isPaused()) null else session.snapshot(now)?.let { checkpointJson(it, now) }
        writes.trySend { history.setSyncValue(CHECKPOINT_KEY, value) }
    }

    /** On launch: a play the app didn't live to finish becomes an `interrupted` play. */
    private suspend fun recoverInterrupted() {
        val (payload, atMs) = history.syncValue(CHECKPOINT_KEY)?.let { parseCheckpoint(it) } ?: return
        history.setSyncValue(CHECKPOINT_KEY, null)
        if (payload.listenedMs <= 0) return
        library.awaitLoaded()
        val key = library.tracks.value.firstOrNull { it.id == payload.trackId }
            ?.let { runCatching { library.contentHash(it) }.getOrNull() }
        history.append("play", 1, payload.toJson().toString(), trackKey = key, atMs = atMs)
        onRecorded()
    }

    private fun record(payload: PlayPayload, track: Track, atMs: Long) {
        if (isPaused() || payload.listenedMs <= 0) return
        writes.trySend {
            val key = track.contentHash ?: runCatching { library.contentHash(track) }.getOrNull()
            history.append("play", 1, payload.toJson().toString(), trackKey = key, atMs = atMs)
            onRecorded()
        }
    }

    companion object {
        const val CHECKPOINT_KEY = "open_play"
        const val CHECKPOINT_INTERVAL_MS = 15_000L

        fun checkpointJson(payload: PlayPayload, atMs: Long): String =
            JSONObject().put("at_ms", atMs).put("play", payload.toJson()).toString()

        fun parseCheckpoint(json: String): Pair<PlayPayload, Long>? = runCatching {
            val obj = JSONObject(json)
            PlayPayload.fromJson(obj.getJSONObject("play"))?.let { it to obj.getLong("at_ms") }
        }.getOrNull()
    }
}
