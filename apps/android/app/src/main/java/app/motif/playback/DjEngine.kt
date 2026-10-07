package app.motif.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
}

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

data class DjState(
    val a: DeckState = DeckState(),
    val b: DeckState = DeckState(),
    /** 0 = deck A only, 1 = deck B only. */
    val crossfader: Float = 0.5f,
    /** A short message for the last thing that couldn't be done, e.g. a failed SYNC. */
    val notice: String? = null,
) {
    operator fun get(id: DeckId): DeckState = if (id == DeckId.A) a else b
}

/**
 * Two-deck DJ mixing on two ExoPlayers: load, play, cue, tempo fader, SYNC
 * and an equal-power crossfader. SYNC and the beat lock come from the shared
 * DSP core (mix::sync), the same maths that drives Mix into next.
 *
 * [onStart] runs when a deck starts playing, so regular playback can pause.
 */
class DjEngine(
    private val context: Context,
    private val store: LibraryStore,
    private val scope: CoroutineScope,
    private val onStart: () -> Unit,
) {
    private val _state = MutableStateFlow(DjState())
    val state: StateFlow<DjState> = _state.asStateFlow()

    private val players = mutableMapOf<DeckId, ExoPlayer>()
    private var ticker: Job? = null

    fun load(id: DeckId, track: Track) {
        val p = player(id)
        val cueMs = ((track.firstDownbeat ?: 0.0) * 1000).roundToLong()
        p.setMediaItem(MediaItem.Builder().setMediaId(track.id).setUri(Uri.fromFile(store.fileFor(track))).build(), cueMs)
        p.playbackParameters = PlaybackParameters.DEFAULT
        p.prepare()
        updateDeck(id) { DeckState(track = track, durationMs = track.durationMs, positionMs = cueMs, cueMs = cueMs) }
        // Whatever followed this deck has lost its master.
        if (_state.value[id.other].synced) updateDeck(id.other) { it.copy(synced = false) }
        applyGains()
    }

    fun togglePlay(id: DeckId) {
        val p = players[id] ?: return
        if (_state.value[id].track == null) return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(_state.value[id].cueMs)
            // A synced deck starts on the beat.
            if (_state.value[id].synced) syncNow(id, snap = true)
            onStart()
            p.play()
        }
    }

    /** Playing: back to the cue point and stop. Stopped: set the cue here, on the nearest beat. */
    fun cue(id: DeckId) {
        val p = players[id] ?: return
        val deck = _state.value[id]
        val track = deck.track ?: return
        if (p.isPlaying) {
            p.pause()
            p.seekTo(deck.cueMs)
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
        p.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
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

    fun setCrossfader(x: Float) {
        _state.update { it.copy(crossfader = x.coerceIn(0f, 1f)) }
        applyGains()
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun pauseAll() = players.values.forEach { it.pause() }

    /** Unloads a deleted track. */
    fun remove(track: Track) {
        DeckId.entries.filter { _state.value[it].track?.id == track.id }.forEach { id ->
            players[id]?.let { it.stop(); it.clearMediaItems() }
            updateDeck(id) { DeckState() }
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
        if (snap) players[id]?.seekTo((pos * 1000).roundToLong().coerceAtLeast(0))
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

    private fun applyGains() {
        val (a, b) = MotifDsp.crossfade(_state.value.crossfader)
        players[DeckId.A]?.volume = a
        players[DeckId.B]?.volume = b
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

    /** Publishes positions and keeps a synced deck locked to its master while anything plays. */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive && players.values.any { it.isPlaying }) {
                publish()
                DeckId.entries.firstOrNull { _state.value[it].synced }?.let { id ->
                    if (players[id]?.isPlaying == true && players[id.other]?.isPlaying == true && !syncNow(id, snap = false)) {
                        updateDeck(id) { it.copy(synced = false) }
                    }
                }
                delay(40)
            }
            publish()
        }
    }

    private fun player(id: DeckId): ExoPlayer {
        players[id]?.let { return it }
        val p = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false,
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startTicker()
                publish()
            }
        })
        players[id] = p
        applyGains()
        return p
    }

    companion object {
        /** Tempo fader range either side of the track's own tempo. */
        const val PITCH_RANGE = 0.08
    }
}
