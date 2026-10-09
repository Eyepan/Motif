package app.motif.playback

import app.motif.data.HistoryStore
import app.motif.data.LibraryStore
import app.motif.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One deck's play of a track in DJ Mix: a `play` event with context `dj` (docs/analytics.md). */
data class DjListen(
    val track: Track,
    val startedAtMs: Long,
    val listenedMs: Long,
    val startPosMs: Long,
    val endPosMs: Long,
    /** `completed`, `replaced` or `stopped`. */
    val endReason: String,
    val seeks: Int,
    val pauses: Int,
    val pausedMs: Long,
    val mixedIn: Boolean,
    val mixedOut: Boolean,
)

/** A handover from one deck to the other: a `transition` event. */
data class DjTransition(
    val from: Track,
    val to: Track,
    val lengthMs: Long,
    val beatmatched: Boolean,
    /** Tempo change on the incoming deck, percent. */
    val tempoShiftPct: Double,
    /** The DJ moved the crossfader, rather than BLEND. */
    val manual: Boolean,
)

/** Counters for one stretch of DJing: a `dj_session` event. */
data class DjSessionSummary(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val tracksLoaded: Int,
    val transitions: Int,
    val cuesUsed: Int,
    val loopsUsed: Int,
    val eqMoves: Int,
)

/** A deck as the tracker sees it at one tick. */
data class DeckSnapshot(
    val track: Track?,
    val playing: Boolean,
    /** Crossfader gain, 0..1. */
    val gain: Float,
    val positionMs: Long,
    val speed: Double,
    val synced: Boolean,
)

/**
 * Turns DJ decks, sampled a few times a second, into listening history: one
 * listen per track per deck that counts only the time it was audible, and a
 * transition each time the crossfader hands over from one playing deck to
 * the other. DJ listening counts toward history like any other play.
 */
class DjListenTracker(
    private val onListen: (DjListen) -> Unit,
    private val onTransition: (DjTransition) -> Unit,
) {
    private class Open(val track: Track) {
        var startedAtMs = -1L
        var startPosMs = 0L
        var lastPosMs = 0L
        var listenedMs = 0L
        var seeks = 0
        var pauses = 0
        var pausedMs = 0L
        var mixedIn = false
        var mixedOut = false
        var wasPlaying = false
        var wasAudible = false
        val started: Boolean get() = startedAtMs >= 0
    }

    private val open = arrayOfNulls<Open>(2)
    private var lastTickMs = -1L

    /** The deck heard on its own before both were, or -1. */
    private var solo = -1
    private var overlapStartMs = -1L
    private var overlapFrom = -1
    private var overlapAuto = false

    /** A track went onto deck [deck] (0 = A): whatever played there before was replaced. */
    fun loaded(deck: Int, track: Track) {
        end(deck, "replaced", reopen = false)
        open[deck] = Open(track)
    }

    fun seeked(deck: Int) {
        open[deck]?.takeIf { it.started }?.let { it.seeks++ }
    }

    /**
     * Closes deck [deck]'s listen; it's written only if the track was heard at
     * all. With [reopen] the track stays on the deck and playing it again starts a new listen.
     */
    fun end(deck: Int, reason: String, positionMs: Long? = null, reopen: Boolean = true) {
        val o = open[deck] ?: return
        open[deck] = if (reopen) Open(o.track) else null
        if (!o.started) return
        onListen(
            DjListen(
                track = o.track, startedAtMs = o.startedAtMs, listenedMs = o.listenedMs,
                startPosMs = o.startPosMs, endPosMs = positionMs ?: o.lastPosMs, endReason = reason,
                seeks = o.seeks, pauses = o.pauses, pausedMs = o.pausedMs, mixedIn = o.mixedIn, mixedOut = o.mixedOut,
            ),
        )
    }

    fun endAll(reason: String) {
        end(0, reason)
        end(1, reason)
        solo = -1
        overlapStartMs = -1
    }

    /** [auto] is true while BLEND is moving the crossfader. */
    fun tick(nowMs: Long, a: DeckSnapshot, b: DeckSnapshot, auto: Boolean = false) {
        val dt = if (lastTickMs < 0) 0 else (nowMs - lastTickMs).coerceIn(0, MAX_TICK_MS)
        lastTickMs = nowMs
        val decks = listOf(a, b)
        val audible = decks.map { it.track != null && it.playing && it.gain >= AUDIBLE_GAIN }

        decks.forEachIndexed { i, d ->
            val o = open[i]?.takeIf { it.track.id == d.track?.id } ?: return@forEachIndexed
            if (audible[i]) {
                if (!o.started) {
                    o.startedAtMs = nowMs
                    o.startPosMs = d.positionMs
                    o.mixedIn = audible[1 - i]
                } else {
                    o.listenedMs += dt
                }
                o.mixedOut = false
            } else if (o.wasAudible && d.playing && audible[1 - i]) {
                // Faded out under the other deck.
                o.mixedOut = true
            }
            if (o.started) {
                if (o.wasPlaying && !d.playing) o.pauses++
                if (!d.playing) o.pausedMs += dt
            }
            o.wasPlaying = d.playing
            o.wasAudible = audible[i]
            o.lastPosMs = d.positionMs
        }

        when {
            audible[0] && audible[1] -> {
                if (overlapStartMs < 0 && solo >= 0) {
                    overlapStartMs = nowMs
                    overlapFrom = solo
                    overlapAuto = false
                }
                overlapAuto = overlapAuto || auto
            }
            audible[0] != audible[1] -> {
                val now = if (audible[0]) 0 else 1
                if (overlapStartMs >= 0 && overlapFrom == 1 - now) {
                    val from = decks[overlapFrom]
                    val to = decks[now]
                    if (from.track != null && to.track != null) {
                        onTransition(
                            DjTransition(
                                from = from.track, to = to.track, lengthMs = nowMs - overlapStartMs,
                                beatmatched = (from.synced || to.synced) && from.track.hasBeatGrid && to.track.hasBeatGrid,
                                tempoShiftPct = (to.speed - 1) * 100,
                                manual = !(overlapAuto || auto),
                            ),
                        )
                    }
                }
                overlapStartMs = -1
                solo = now
            }
            else -> {
                overlapStartMs = -1
                solo = -1
            }
        }
    }

    companion object {
        /** Below this crossfader gain a deck counts as not heard (the fader's last few percent). */
        const val AUDIBLE_GAIN = 0.1f

        /** Longest gap credited between ticks, so a frozen process doesn't add phantom listening. */
        const val MAX_TICK_MS = 1_000L
    }
}

/** Writes DJ Mix history: `play` (context `dj`), `transition` and `dj_session` events. Nothing while [paused] says so. */
class DjHistory(
    private val history: HistoryStore,
    private val library: LibraryStore,
    private val scope: CoroutineScope,
    private val paused: () -> Boolean = { false },
) {
    fun play(l: DjListen) = write("play", l.track) {
        put("track_id", l.track.id)
        put("started_at_ms", l.startedAtMs)
        put("listened_ms", l.listenedMs)
        put("start_pos_ms", l.startPosMs)
        put("end_pos_ms", l.endPosMs)
        put("duration_ms", l.track.durationMs)
        put("end_reason", l.endReason)
        put("seeks", l.seeks)
        put("pauses", l.pauses)
        put("paused_ms", l.pausedMs)
        put("context", "dj")
        put("control", "app")
        put("mixed_in", l.mixedIn)
        put("mixed_out", l.mixedOut)
    }

    fun transition(t: DjTransition) = write("transition", t.to) {
        put("from_track_id", t.from.id)
        put("to_track_id", t.to.id)
        put("kind", if (t.beatmatched) "beatmatched" else "crossfade")
        put("length_ms", t.lengthMs)
        t.from.bpm?.let { put("from_bpm", it) }
        t.to.bpm?.let { put("to_bpm", it) }
        t.from.musicalKey?.let { put("from_key", it) }
        t.to.musicalKey?.let { put("to_key", it) }
        if (t.from.musicalKey != null && t.to.musicalKey != null) put("harmonic", MixMatch.keys(t.from, t.to))
        put("tempo_shift_pct", Math.round(t.tempoShiftPct * 100) / 100.0)
        put("manual", t.manual)
        put("context", "dj")
    }

    fun session(s: DjSessionSummary) = write("dj_session", null) {
        put("started_at_ms", s.startedAtMs)
        put("ended_at_ms", s.endedAtMs)
        put("tracks_loaded", s.tracksLoaded)
        put("transitions", s.transitions)
        put("cues_used", s.cuesUsed)
        put("loops_used", s.loopsUsed)
        put("eq_moves", s.eqMoves)
    }

    private fun write(type: String, track: Track?, fill: JSONObject.() -> Unit) {
        if (paused()) return
        val payload = JSONObject().apply(fill).toString()
        scope.launch {
            val key = track?.let { runCatching { library.contentHash(it) }.getOrNull() }
            history.append(type, 1, payload, trackKey = key)
        }
    }
}
