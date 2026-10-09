package app.motif.data

import org.json.JSONObject

/** Why a play ended (`end_reason` in schemas/events/play.v1.schema.json). Written lowercase. */
enum class PlayEndReason {
    COMPLETED, SKIPPED, PREVIOUS, STOPPED, REPLACED, ERROR, INTERRUPTED;

    val wire: String get() = name.lowercase()

    companion object {
        fun of(wire: String): PlayEndReason? = entries.firstOrNull { it.wire == wire }
    }
}

/** What started a play (`context`). [MIX] is a DJ Mix deck. */
enum class PlayContext {
    LIBRARY, ALBUM, ARTIST, CRATE, SEARCH, QUEUE, AUTOPLAY, MIX;

    val wire: String get() = name.lowercase()

    companion object {
        fun of(wire: String): PlayContext? = entries.firstOrNull { it.wire == wire }
    }
}

/** A finished play: the `play` v1 payload. */
data class PlayPayload(
    val trackId: String,
    val startedAtMs: Long,
    val listenedMs: Long,
    val startPosMs: Long = 0,
    val endPosMs: Long = 0,
    val durationMs: Long = 0,
    val endReason: PlayEndReason,
    val seeks: Int = 0,
    val pauses: Int = 0,
    val pausedMs: Long = 0,
    val context: PlayContext,
    val contextRef: String? = null,
    val mixedIn: Boolean = false,
    val mixedOut: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("track_id", trackId)
        put("started_at_ms", startedAtMs)
        put("listened_ms", listenedMs)
        put("start_pos_ms", startPosMs)
        put("end_pos_ms", endPosMs)
        put("duration_ms", durationMs)
        put("end_reason", endReason.wire)
        put("seeks", seeks)
        put("pauses", pauses)
        put("paused_ms", pausedMs)
        put("context", context.wire)
        contextRef?.let { put("context_ref", it) }
        put("mixed_in", mixedIn)
        put("mixed_out", mixedOut)
    }

    companion object {
        fun fromJson(obj: JSONObject): PlayPayload? = runCatching {
            PlayPayload(
                trackId = obj.getString("track_id"),
                startedAtMs = obj.optLong("started_at_ms"),
                listenedMs = obj.optLong("listened_ms"),
                startPosMs = obj.optLong("start_pos_ms"),
                endPosMs = obj.optLong("end_pos_ms"),
                durationMs = obj.optLong("duration_ms"),
                endReason = PlayEndReason.of(obj.getString("end_reason"))!!,
                seeks = obj.optInt("seeks"),
                pauses = obj.optInt("pauses"),
                pausedMs = obj.optLong("paused_ms"),
                context = PlayContext.of(obj.getString("context"))!!,
                contextRef = if (obj.has("context_ref") && !obj.isNull("context_ref")) obj.getString("context_ref") else null,
                mixedIn = obj.optBoolean("mixed_in"),
                mixedOut = obj.optBoolean("mixed_out"),
            )
        }.getOrNull()
    }
}

/**
 * Accumulates one playback of one track into a `play` payload. Pure
 * bookkeeping: the player reports what happened with wall-clock and track
 * positions in ms. The rules are pinned by schemas/fixtures/plays.json,
 * shared with the Apple apps.
 */
class PlaySession(
    val trackId: String,
    val durationMs: Long,
    val context: PlayContext,
    val contextRef: String? = null,
    val mixedIn: Boolean = false,
) {
    var started = false
        private set
    private var playing = false
    private var startedAtMs = 0L
    private var startPosMs = 0L
    private var lastPosMs = 0L
    private var lastAtMs = 0L
    private var listenedMs = 0L
    private var seeks = 0
    private var pauses = 0
    private var pausedMs = 0L
    private var pausedSinceMs = 0L

    /** Audio is playing. The first call starts the play. */
    fun resume(at: Long, pos: Long) {
        when {
            !started -> {
                started = true
                startedAtMs = at
                startPosMs = pos
            }
            playing -> return progress(at, pos)
            else -> pausedMs += at - pausedSinceMs
        }
        playing = true
        lastAtMs = at
        lastPosMs = pos
    }

    /** Forward movement while playing counts, capped so an unreported jump doesn't. */
    fun progress(at: Long, pos: Long) {
        if (playing) {
            val delta = pos - lastPosMs
            if (delta > 0) listenedMs += minOf(delta, maxOf(0L, at - lastAtMs) * 2 + 1000)
            lastAtMs = at
        }
        lastPosMs = pos
    }

    fun pause(at: Long, pos: Long) {
        progress(at, pos)
        if (!playing) return
        playing = false
        pauses++
        pausedSinceMs = at
    }

    fun seek(at: Long, from: Long, to: Long) {
        progress(at, from)
        if (started) seeks++
        lastPosMs = to
    }

    /** The finished play, or null if audio never started. */
    fun end(at: Long, pos: Long, reason: PlayEndReason, mixedOut: Boolean = false): PlayPayload? {
        if (!started) return null
        progress(at, pos)
        return payload(at, reason, mixedOut)
    }

    /** The play so far, as it would be recovered after a crash. */
    fun snapshot(at: Long): PlayPayload? = if (started) payload(at, PlayEndReason.INTERRUPTED, false) else null

    private fun payload(at: Long, reason: PlayEndReason, mixedOut: Boolean) = PlayPayload(
        trackId = trackId, startedAtMs = startedAtMs, listenedMs = listenedMs, startPosMs = startPosMs,
        endPosMs = lastPosMs, durationMs = durationMs, endReason = reason, seeks = seeks, pauses = pauses,
        pausedMs = pausedMs + if (playing) 0L else maxOf(0L, at - pausedSinceMs),
        context = context, contextRef = contextRef, mixedIn = mixedIn, mixedOut = mixedOut,
    )
}
