package app.motif.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import app.motif.data.LibraryStore
import app.motif.data.Track
import app.motif.dsp.MotifDsp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

enum class DeckId {
    A, B;

    val other: DeckId get() = if (this == A) B else A

    /** Crossfader position that plays only this deck. */
    val side: Float get() = if (this == A) 0f else 1f
}

/** A deck's active loop, on the beat grid. */
data class DeckLoop(val startMs: Long, val endMs: Long, val beats: Double)

data class DeckState(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Tempo fader, -[DjEngine.PITCH_RANGE]..+[DjEngine.PITCH_RANGE]. */
    val pitch: Double = 0.0,
    /** Playback speed: 1 + [pitch], or whatever SYNC set. Pitch is kept either way. */
    val speed: Double = 1.0,
    /** Following the other deck's tempo and beat. */
    val synced: Boolean = false,
    val cueMs: Long = 0,
    val fx: DeckFxKnobs = DeckFxKnobs(),
    val loop: DeckLoop? = null,
) {
    /** Tempo as it plays now. */
    val bpm: Double? get() = track?.bpm?.let { it * speed }

    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Beat of the bar (0..3) at the playhead, or null without a beat grid. */
    val beatInBar: Int?
        get() {
            val t = track ?: return null
            val bpm = t.bpm ?: return null
            val downbeat = t.firstDownbeat ?: return null
            val beat = floor((positionMs / 1000.0 - downbeat) * bpm / 60).toLong()
            return Math.floorMod(beat, 4L).toInt()
        }
}

/**
 * An automatic blend from one deck into the other: the incoming deck starts on
 * the outgoing one's next downbeat and the crossfader moves across over [bars]
 * bars. Positions are seconds of the outgoing track.
 */
data class DjBlend(val from: DeckId, val startS: Double, val lengthS: Double, val startFader: Float, val bars: Int?) {
    val to: DeckId get() = from.other
}

data class DjState(
    val a: DeckState = DeckState(),
    val b: DeckState = DeckState(),
    /** 0 = deck A only, 1 = deck B only. */
    val crossfader: Float = 0.5f,
    val blend: DjBlend? = null,
    /** A short message for the last thing that couldn't be done, e.g. a failed SYNC. */
    val notice: String? = null,
) {
    operator fun get(id: DeckId): DeckState = if (id == DeckId.A) a else b

    /** The deck being heard: the playing one the crossfader favours, else the loaded one it favours. */
    val lead: DeckId?
        get() {
            val favoured = if (crossfader <= 0.5f) listOf(DeckId.A, DeckId.B) else listOf(DeckId.B, DeckId.A)
            return favoured.firstOrNull { this[it].isPlaying && this[it].track != null }
                ?: favoured.firstOrNull { this[it].track != null }
        }

    val anyPlaying: Boolean get() = a.isPlaying || b.isPlaying
}

/**
 * Two-deck DJ mixing on two ExoPlayers: load, play, cue, tempo fader, SYNC,
 * EQ and filter, beat loops, BLEND and an equal-power crossfader. SYNC, loops,
 * blends and the EQ all come from the shared DSP core, the same maths that
 * drives Mix into next. Listening is written to history ([DjHistory]).
 *
 * [onStart] runs when a deck starts playing, so regular playback can pause.
 */
@OptIn(UnstableApi::class)
class DjEngine(
    private val context: Context,
    private val store: LibraryStore,
    private val scope: CoroutineScope,
    private val history: DjHistory? = null,
    private val onStart: () -> Unit,
) {
    private val _state = MutableStateFlow(DjState())
    val state: StateFlow<DjState> = _state.asStateFlow()

    private val players = mutableMapOf<DeckId, ExoPlayer>()
    private val fx = mutableMapOf<DeckId, DeckFxProcessor>()
    private val loopMessages = mutableMapOf<DeckId, PlayerMessage>()
    private var ticker: Job? = null

    /** Decks [pauseAll] stopped, so the media session's play button can bring them back. */
    private var pausedByAll = emptySet<DeckId>()

    private val tracker = DjListenTracker(
        onListen = { history?.play(it) },
        onTransition = {
            session.transitions++
            history?.transition(it)
        },
    )

    private class SessionCounters {
        var startedAtMs = -1L
        var lastActiveMs = 0L
        var tracksLoaded = 0
        var transitions = 0
        var cuesUsed = 0
        var loopsUsed = 0
        var eqMoves = 0
    }

    private var session = SessionCounters()
    private var sessionTimeout: Job? = null
    private val lastFxMove = mutableMapOf<DeckId, Long>()

    fun load(id: DeckId, track: Track) {
        val p = player(id)
        cancelBlend()
        clearLoop(id)
        val cueMs = ((track.firstDownbeat ?: 0.0) * 1000).roundToLong()
        p.setMediaItem(MediaItem.Builder().setMediaId(track.id).setUri(Uri.fromFile(store.fileFor(track))).build(), cueMs)
        p.playbackParameters = PlaybackParameters.DEFAULT
        p.prepare()
        tracker.loaded(id.ordinal, track)
        session.tracksLoaded++
        val fxKnobs = _state.value[id].fx
        updateDeck(id) { DeckState(track = track, durationMs = track.durationMs, positionMs = cueMs, cueMs = cueMs, fx = fxKnobs) }
        // Whatever followed this deck has lost its master.
        if (_state.value[id.other].synced) updateDeck(id.other) { it.copy(synced = false) }
        applyGains()
    }

    fun togglePlay(id: DeckId) {
        val p = players[id] ?: return
        if (_state.value[id].track == null) return
        if (p.isPlaying) {
            p.pause()
            if (_state.value.blend?.from == id) cancelBlend()
        } else {
            play(id)
        }
    }

    private fun play(id: DeckId) {
        val p = players[id] ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(_state.value[id].cueMs)
        // A synced deck starts on the beat.
        if (_state.value[id].synced) syncNow(id, snap = true)
        onStart()
        p.play()
        beginSession()
        startTicker()
    }

    /** Playing: back to the cue point and stop. Stopped: set the cue here, on the nearest beat. */
    fun cue(id: DeckId) {
        val p = players[id] ?: return
        val deck = _state.value[id]
        val track = deck.track ?: return
        session.cuesUsed++
        clearLoop(id)
        if (p.isPlaying) {
            p.pause()
            p.seekTo(deck.cueMs)
            if (_state.value.blend?.from == id) cancelBlend()
        } else {
            val cueMs = nearestBeatMs(track, p.currentPosition)
            p.seekTo(cueMs)
            updateDeck(id) { it.copy(cueMs = cueMs) }
        }
        publish()
    }

    fun seek(id: DeckId, fraction: Float) {
        val p = players[id] ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: _state.value[id].durationMs
        clearLoop(id)
        p.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        tracker.seeked(id.ordinal)
        if (_state.value[id].synced && p.isPlaying) syncNow(id, snap = true)
        publish()
    }

    /** Moving the tempo fader takes a deck out of SYNC. */
    fun setPitch(id: DeckId, pitch: Double) {
        val clamped = pitch.coerceIn(-PITCH_RANGE, PITCH_RANGE)
        updateDeck(id) { it.copy(pitch = clamped, speed = 1 + clamped, synced = false) }
        setSpeed(id, 1 + clamped)
    }

    fun toggleSync(id: DeckId) {
        val deck = _state.value[id]
        if (deck.synced) {
            updateDeck(id) { it.copy(synced = false, speed = 1 + it.pitch) }
            setSpeed(id, 1 + deck.pitch)
            return
        }
        // One deck follows the other; turning SYNC on here releases the other one.
        if (_state.value[id.other].synced) toggleSync(id.other)
        if (syncNow(id, snap = true)) updateDeck(id) { it.copy(synced = true) }
    }

    /** EQ and filter for a deck. Each knob is -1..1 with 0 flat. */
    fun setFx(id: DeckId, knobs: DeckFxKnobs) {
        val clamped = DeckFxKnobs(
            knobs.low.coerceIn(-1f, 1f), knobs.mid.coerceIn(-1f, 1f),
            knobs.high.coerceIn(-1f, 1f), knobs.filter.coerceIn(-1f, 1f),
        )
        if (!MotifDsp.available && !clamped.isFlat) {
            _state.update { it.copy(notice = "EQ isn't available in this build") }
            return
        }
        // A gesture is a run of moves; count a new one after a second's rest.
        val now = SystemClock.elapsedRealtime()
        if (now - (lastFxMove[id] ?: 0L) > 1_000) session.eqMoves++
        lastFxMove[id] = now
        fx[id]?.knobs = clamped
        updateDeck(id) { it.copy(fx = clamped) }
    }

    /**
     * Loops [beats] beats from the beat at or just before the playhead. The
     * same length again exits the loop; another length resizes it from the same start.
     */
    fun toggleLoop(id: DeckId, beats: Double) {
        val deck = _state.value[id]
        val track = deck.track ?: return
        val current = deck.loop
        if (current?.beats == beats) {
            clearLoop(id)
            return
        }
        if (!track.hasBeatGrid || !MotifDsp.available) {
            _state.update { it.copy(notice = "Loops need a beat grid") }
            return
        }
        val from = current?.startMs?.div(1000.0) ?: position(id)
        val bounds = MotifDsp.loopAt(track.bpm!!, track.firstDownbeat!!, from, beats) ?: return
        val loop = DeckLoop((bounds[0] * 1000).roundToLong(), (bounds[1] * 1000).roundToLong(), beats)
        val durationMs = players[id]?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: deck.durationMs
        if (durationMs > 0 && loop.endMs > durationMs) {
            _state.update { it.copy(notice = "Not enough track left for that loop") }
            return
        }
        if (current == null) session.loopsUsed++
        updateDeck(id) { it.copy(loop = loop) }
        armLoop(id)
        // Shrunk behind the playhead: wrap into the new loop now.
        val pos = players[id]?.currentPosition ?: 0L
        if (pos >= loop.endMs) players[id]?.seekTo(loop.startMs + (pos - loop.startMs) % (loop.endMs - loop.startMs))
        publish()
    }

    fun exitLoop(id: DeckId) = clearLoop(id)

    /**
     * Blends from the playing deck the crossfader favours into the other:
     * SYNC on, the other deck starts on the next downbeat, and the crossfader
     * moves across over [BLEND_BARS] bars. Pressed again, it stops where it is.
     */
    fun toggleBlend() {
        if (_state.value.blend != null) {
            cancelBlend()
            return
        }
        val s = _state.value
        val favoured = if (s.crossfader <= 0.5f) listOf(DeckId.A, DeckId.B) else listOf(DeckId.B, DeckId.A)
        val from = favoured.firstOrNull { s[it].isPlaying }
        if (from == null) {
            _state.update { it.copy(notice = "Play a deck to blend from") }
            return
        }
        val to = from.other
        if (s[to].track == null) {
            _state.update { it.copy(notice = "Load a track on deck ${to.name} to blend into") }
            return
        }
        val outgoing = s[from].track!!
        val pos = position(from)
        val gridded = outgoing.hasBeatGrid && s[to].track!!.hasBeatGrid && MotifDsp.available
        if (gridded && !s[to].synced) toggleSync(to)
        val blend = if (gridded && _state.value[to].synced) {
            val bar = Mixing.barLength(outgoing.bpm!!)
            // A downbeat at least a beat away, so the incoming deck has time to start.
            val start = MotifDsp.nextDownbeat(outgoing.bpm, outgoing.firstDownbeat!!, pos + bar / 4)
            DjBlend(from, start, BLEND_BARS * bar, s.crossfader, BLEND_BARS)
        } else {
            DjBlend(from, pos, FREE_BLEND_SECONDS * s[from].speed, s.crossfader, null)
        }
        if (players[to]?.playbackState == Player.STATE_ENDED) players[to]?.seekTo(s[to].cueMs)
        _state.update { it.copy(blend = blend) }
        startTicker()
    }

    fun setCrossfader(x: Float) {
        // Taking the fader stops BLEND.
        if (_state.value.blend != null) cancelBlend()
        moveCrossfader(x)
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** Plays again whatever [pauseAll] stopped (the media notification's play button). */
    fun resume() {
        val decks = pausedByAll.ifEmpty { setOfNotNull(_state.value.lead) }
        pausedByAll = emptySet()
        decks.filter { _state.value[it].track != null }.forEach(::play)
    }

    fun pauseAll() {
        pausedByAll = players.filterValues { it.isPlaying }.keys
        cancelBlend()
        players.values.forEach { it.pause() }
        publish()
    }

    /** Regular playback took over: pause, and close the DJ session's history. */
    fun stopForPlayback() {
        pauseAll()
        endSession()
    }

    /** Unloads a deleted track. */
    fun remove(track: Track) {
        DeckId.entries.filter { _state.value[it].track?.id == track.id }.forEach { id ->
            if (_state.value.blend?.let { it.from == id || it.to == id } == true) cancelBlend()
            clearLoop(id)
            tracker.end(id.ordinal, "stopped", reopen = false)
            players[id]?.let { it.stop(); it.clearMediaItems() }
            updateDeck(id) { DeckState(fx = it.fx) }
        }
    }

    /**
     * Puts deck [id] on the other deck's tempo and beat. Returns false, with a
     * notice, when it can't.
     */
    private fun syncNow(id: DeckId, snap: Boolean): Boolean {
        val slave = _state.value[id]
        val master = _state.value[id.other]
        val failure = when {
            slave.track == null || master.track == null -> "Load a track on both decks to sync"
            !slave.track.hasBeatGrid || !master.track.hasBeatGrid -> "Sync needs a beat grid on both tracks"
            !MotifDsp.available -> "Sync isn't available in this build"
            else -> null
        }
        val result = if (failure == null) {
            MotifDsp.deckSync(
                master.track!!.bpm!!, master.track.firstDownbeat!!, position(id.other), master.speed,
                slave.track!!.bpm!!, slave.track.firstDownbeat!!, position(id), snap,
            )
        } else null
        if (result == null) {
            _state.update { it.copy(notice = failure ?: "Tempos are too far apart to sync") }
            return false
        }
        val (speed, pos) = result[0] to result[1]
        // A looping deck keeps its loop; only its speed follows.
        if (snap && slave.loop == null) players[id]?.seekTo((pos * 1000).roundToLong().coerceAtLeast(0))
        updateDeck(id) { it.copy(speed = speed) }
        setSpeed(id, speed)
        return true
    }

    private fun position(id: DeckId): Double = (players[id]?.currentPosition ?: 0L) / 1000.0

    private fun nearestBeatMs(track: Track, positionMs: Long): Long {
        val bpm = track.bpm ?: return positionMs
        val downbeat = track.firstDownbeat ?: return positionMs
        val beat = 60 / bpm
        val n = Math.round((positionMs / 1000.0 - downbeat) / beat)
        return ((downbeat + n * beat) * 1000).roundToLong().coerceAtLeast(0)
    }

    private fun setSpeed(id: DeckId, speed: Double) {
        val p = players[id] ?: return
        if (abs(p.playbackParameters.speed - speed) > 1e-4) p.playbackParameters = PlaybackParameters(speed.toFloat())
    }

    private fun moveCrossfader(x: Float) {
        _state.update { it.copy(crossfader = x.coerceIn(0f, 1f)) }
        applyGains()
    }

    private fun gains(): Pair<Float, Float> = MotifDsp.crossfade(_state.value.crossfader)

    private fun applyGains() {
        val (a, b) = gains()
        players[DeckId.A]?.volume = a
        players[DeckId.B]?.volume = b
    }

    /**
     * Repeats the loop with a message at its end that jumps back to its start.
     * The players keep a back buffer, so the jump reads from memory.
     */
    private fun armLoop(id: DeckId) {
        loopMessages.remove(id)?.cancel()
        val loop = _state.value[id].loop ?: return
        val p = players[id] ?: return
        loopMessages[id] = p.createMessage { _, _ ->
            val current = _state.value[id].loop
            if (current != null) p.seekTo(current.startMs)
        }
            .setLooper(Looper.getMainLooper())
            .setPosition(loop.endMs)
            .setDeleteAfterDelivery(false)
            .send()
    }

    private fun clearLoop(id: DeckId) {
        loopMessages.remove(id)?.cancel()
        if (_state.value[id].loop != null) updateDeck(id) { it.copy(loop = null) }
    }

    private fun cancelBlend() {
        if (_state.value.blend != null) _state.update { it.copy(blend = null) }
    }

    /** Moves BLEND along: starts the incoming deck on the downbeat, then the crossfader, then stops the outgoing deck. */
    private fun stepBlend() {
        val blend = _state.value.blend ?: return
        val outgoing = players[blend.from] ?: return cancelBlend()
        val incoming = players[blend.to] ?: return cancelBlend()
        if (!outgoing.isPlaying) return cancelBlend()
        val pos = position(blend.from)
        // Start a tick early: the player takes a moment, and SYNC snaps it onto the beat anyway.
        if (!incoming.isPlaying && pos >= blend.startS - 0.04) play(blend.to)
        val progress = ((pos - blend.startS) / blend.lengthS).coerceIn(0.0, 1.0).toFloat()
        moveCrossfader(blend.startFader + (blend.to.side - blend.startFader) * progress)
        if (progress >= 1f) {
            cancelBlend()
            outgoing.pause()
        }
    }

    private fun updateDeck(id: DeckId, change: (DeckState) -> DeckState) =
        _state.update { if (id == DeckId.A) it.copy(a = change(it.a)) else it.copy(b = change(it.b)) }

    private fun publish() {
        for (id in DeckId.entries) {
            val p = players[id] ?: continue
            updateDeck(id) {
                it.copy(
                    isPlaying = p.isPlaying,
                    positionMs = p.currentPosition,
                    durationMs = p.duration.takeIf { d -> d != C.TIME_UNSET } ?: it.durationMs,
                )
            }
        }
    }

    private fun trackListening() {
        val s = _state.value
        val (ga, gb) = gains()
        fun snap(d: DeckState, gain: Float) = DeckSnapshot(d.track, d.isPlaying, gain, d.positionMs, d.speed, d.synced)
        tracker.tick(System.currentTimeMillis(), snap(s.a, ga), snap(s.b, gb), auto = s.blend != null)
        if (s.anyPlaying) session.lastActiveMs = System.currentTimeMillis()
    }

    /** Publishes positions, keeps a synced deck locked, runs BLEND and counts listening while anything plays. */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive && (players.values.any { it.isPlaying } || _state.value.blend != null)) {
                publish()
                stepBlend()
                DeckId.entries.firstOrNull { _state.value[it].synced }?.let { id ->
                    if (players[id]?.isPlaying == true && players[id.other]?.isPlaying == true && !syncNow(id, snap = false)) {
                        updateDeck(id) { it.copy(synced = false) }
                    }
                }
                trackListening()
                delay(40)
            }
            publish()
            trackListening()
            scheduleSessionEnd()
        }
    }

    private fun beginSession() {
        sessionTimeout?.cancel()
        if (session.startedAtMs < 0) session.startedAtMs = System.currentTimeMillis()
        session.lastActiveMs = System.currentTimeMillis()
    }

    /** A DJ session ends after [SESSION_IDLE_MS] with nothing playing. */
    private fun scheduleSessionEnd() {
        sessionTimeout?.cancel()
        sessionTimeout = scope.launch {
            delay(SESSION_IDLE_MS)
            if (!_state.value.anyPlaying) endSession()
        }
    }

    private fun endSession() {
        sessionTimeout?.cancel()
        tracker.endAll("stopped")
        val s = session
        if (s.startedAtMs >= 0) {
            history?.session(
                DjSessionSummary(
                    startedAtMs = s.startedAtMs, endedAtMs = maxOf(s.lastActiveMs, s.startedAtMs),
                    tracksLoaded = s.tracksLoaded, transitions = s.transitions,
                    cuesUsed = s.cuesUsed, loopsUsed = s.loopsUsed, eqMoves = s.eqMoves,
                ),
            )
        }
        session = SessionCounters()
    }

    private fun player(id: DeckId): ExoPlayer {
        players[id]?.let { return it }
        val processor = DeckFxProcessor().also { it.knobs = _state.value[id].fx }
        fx[id] = processor
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
        }
        val p = ExoPlayer.Builder(context, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false,
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // Keep what was just played, so loop jumps and cue returns read from memory.
            .setLoadControl(DefaultLoadControl.Builder().setBackBuffer(BACK_BUFFER_MS, true).build())
            .setSeekParameters(SeekParameters.EXACT)
            .build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startTicker()
                publish()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    publish()
                    tracker.end(id.ordinal, "completed", p.duration.takeIf { it != C.TIME_UNSET })
                }
            }
        })
        players[id] = p
        applyGains()
        return p
    }

    companion object {
        /** Tempo fader range either side of the track's own tempo. */
        const val PITCH_RANGE = 0.08

        /** Loop lengths offered on the deck, in beats. */
        val LOOP_BEATS = listOf(1.0, 2.0, 4.0, 8.0, 16.0)

        /** BLEND length on the beat. */
        const val BLEND_BARS = 16

        /** BLEND length without beat grids, seconds. */
        const val FREE_BLEND_SECONDS = 15.0

        private const val BACK_BUFFER_MS = 60_000
        private const val SESSION_IDLE_MS = 5 * 60_000L
    }
}
