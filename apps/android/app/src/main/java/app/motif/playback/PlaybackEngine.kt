package app.motif.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import kotlinx.coroutines.withTimeoutOrNull

data class PlayerState(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val mixIntoNext: Boolean = false,
    val isMixing: Boolean = false,
) {
    val current: Track? get() = queue.getOrNull(currentIndex)
    val upNext: Track? get() = queue.getOrNull(currentIndex + 1)
}

/**
 * Lossless playback on Media3 ExoPlayer: original files, no transcoding,
 * gapless between tracks. With "Mix into next" on, the end of each track is
 * blended into the next on a second player: the incoming track is
 * tempo-matched (pitch kept) and the two crossfade on the DSP core's
 * equal-power curve, then the main player takes over at the same spot.
 *
 * [player] is the main deck; PlaybackService wraps it in a MediaSession so the
 * notification, lock screen, headset buttons and Bluetooth all drive it.
 */
class PlaybackEngine(
    private val context: Context,
    private val store: LibraryStore,
    private val scope: CoroutineScope,
    initialMixIntoNext: Boolean,
    private val onMixIntoNextChanged: (Boolean) -> Unit,
) {
    val player: ExoPlayer = buildPlayer(handleFocus = true)

    private val _state = MutableStateFlow(PlayerState(mixIntoNext = initialMixIntoNext))
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var helper: ExoPlayer? = null
    private var mixJob: Job? = null
    private var handingOff = false
    private var ticker: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Keep the helper deck in step with play/pause, except while the main deck rebuffers during hand-off.
                if (!handingOff) helper?.let { if (isPlaying) it.play() else it.pause() }
                if (isPlaying) startTicker()
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                // A seek from anywhere (app, notification, headset) abandons a blend in progress.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !handingOff) cancelMix()
            }
        })
    }

    fun play(tracks: List<Track>, startAt: Int = 0) {
        if (tracks.isEmpty()) return
        cancelMix()
        _state.update { it.copy(queue = tracks) }
        player.setMediaItems(tracks.map(::mediaItem), startAt, 0)
        player.prepare()
        player.play()
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(0)
            player.play()
        }
    }

    fun next() {
        cancelMix()
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun previous() {
        cancelMix()
        if (player.currentPosition > 3000 || !player.hasPreviousMediaItem()) player.seekTo(0) else player.seekToPreviousMediaItem()
    }

    fun skipTo(index: Int) {
        cancelMix()
        player.seekToDefaultPosition(index)
        player.play()
    }

    fun seekTo(fraction: Float) {
        cancelMix()
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun setMixIntoNext(on: Boolean) {
        if (!on) cancelMix()
        _state.update { it.copy(mixIntoNext = on) }
        onMixIntoNextChanged(on)
    }

    /** Drops a deleted track from the queue without interrupting what's playing. */
    fun remove(track: Track) {
        val index = _state.value.queue.indexOfFirst { it.id == track.id }
        if (index < 0) return
        if (index == _state.value.currentIndex + 1) cancelMix()
        player.removeMediaItem(index)
        _state.update { it.copy(queue = it.queue.filterIndexed { i, _ -> i != index }) }
    }

    private fun publish() {
        _state.update {
            it.copy(
                currentIndex = if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition,
                durationMs = player.duration.takeIf { d -> d != C.TIME_UNSET } ?: it.current?.durationMs ?: 0,
            )
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive && (player.isPlaying || mixJob?.isActive == true)) {
                publish()
                startMixIfDue()
                delay(if (mixJob?.isActive == true) 100 else 250)
            }
            publish()
        }
    }

    // region Mixing

    private fun startMixIfDue() {
        val s = _state.value
        if (!s.mixIntoNext || mixJob?.isActive == true || !player.isPlaying) return
        val outgoing = s.current ?: return
        val incoming = s.upNext ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val remainingMs = duration - player.currentPosition
        val lengthMs = (Mixing.mixLength(outgoing) * 1000).toLong()
        if (remainingMs in 2_000..lengthMs) beginMix(outgoing, incoming, s.currentIndex + 1, remainingMs)
    }

    private fun beginMix(outgoing: Track, incoming: Track, index: Int, remainingMs: Long) {
        val deck = buildPlayer(handleFocus = false).apply {
            setMediaItem(mediaItem(incoming))
            playbackParameters = PlaybackParameters(Mixing.tempoRatio(outgoing, incoming).toFloat())
            volume = 0f
            prepare()
            play()
        }
        helper = deck
        _state.update { it.copy(isMixing = true) }
        val startPos = player.currentPosition
        val fadeMs = (remainingMs - 300).coerceAtLeast(1)
        mixJob = scope.launch {
            // Fade on the main deck's clock, so pausing pauses the blend.
            while (isActive) {
                val t = ((player.currentPosition - startPos).toFloat() / fadeMs).coerceIn(0f, 1f)
                val (a, b) = MotifDsp.crossfade(t)
                player.volume = a
                deck.volume = b
                if (t >= 1f) break
                delay(40)
            }
            handOff(deck, index)
        }
    }

    /** Moves the main deck onto the incoming track where the helper is, then retires the helper. */
    private suspend fun handOff(deck: ExoPlayer, index: Int) {
        handingOff = true
        val speed = deck.playbackParameters.speed
        player.volume = 0f
        player.playbackParameters = PlaybackParameters(speed)
        // Aim slightly ahead: the helper keeps playing while the main deck buffers.
        player.seekTo(index, deck.currentPosition + (120 * speed).toLong())
        withTimeoutOrNull(2_000) {
            while (player.playbackState != Player.STATE_READY) delay(10)
        }
        player.volume = 1f
        retireHelper()
        handingOff = false
        _state.update { it.copy(isMixing = false) }
        // Ease the tempo back to the track's own over ~4 s.
        for (step in 1..40) {
            delay(100)
            player.playbackParameters = PlaybackParameters(speed + (1 - speed) * step / 40)
        }
    }

    private fun cancelMix() {
        mixJob?.cancel()
        mixJob = null
        retireHelper()
        handingOff = false
        player.volume = 1f
        player.playbackParameters = PlaybackParameters.DEFAULT
        _state.update { it.copy(isMixing = false) }
    }

    private fun retireHelper() {
        helper?.release()
        helper = null
    }

    // endregion

    private fun buildPlayer(handleFocus: Boolean): ExoPlayer =
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                handleFocus,
            )
            .setHandleAudioBecomingNoisy(handleFocus)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

    private fun mediaItem(track: Track): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.fromFile(store.fileFor(track)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
}
