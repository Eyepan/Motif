package app.motif.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.motif.data.ArtworkStore
import app.motif.data.LibraryStore
import app.motif.data.PlayContext
import app.motif.data.PlayEndReason
import app.motif.data.PlayRecorder
import app.motif.data.PlaySession
import app.motif.data.Track
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
import kotlin.math.abs

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
 * blended into the next on a second player, following a plan from the DSP
 * core: with beat grids on both tracks the blend runs phrase-aligned from
 * downbeat to downbeat with the incoming track tempo-matched (pitch kept) and
 * held on the beat; without, it's a tempo-matched crossfade. Then the main
 * player takes over at the same spot.
 *
 * [player] is the main deck; PlaybackService wraps it in a MediaSession so the
 * notification, lock screen, headset buttons and Bluetooth all drive it.
 */
class PlaybackEngine(
    private val context: Context,
    private val store: LibraryStore,
    private val artwork: ArtworkStore,
    private val scope: CoroutineScope,
    initialMixIntoNext: Boolean,
    private val onMixIntoNextChanged: (Boolean) -> Unit,
) {
    val player: ExoPlayer = buildPlayer(handleFocus = true)

    private val _state = MutableStateFlow(PlayerState(mixIntoNext = initialMixIntoNext))
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var helper: ExoPlayer? = null
    /** Whether the helper is playing in a blend, rather than loaded ahead of one. */
    private var helperLive = false
    private var mixJob: Job? = null
    private var handingOff = false
    private var ticker: Job? = null

    /** Receives finished plays and checkpoints of the one in progress (listening history). */
    var recorder: PlayRecorder? = null
    /** The current track's play, and the incoming track's while a blend runs. */
    private var openPlay: PlaySession? = null
    private var openTrack: Track? = null
    private var mixPlay: PlaySession? = null
    private var mixTrack: Track? = null
    /** What started the queue, for the tracks next and previous move to. */
    private var queueContext: Pair<PlayContext, String?> = PlayContext.LIBRARY to null
    /** Set by [skipTo] for the track the player moves to next. */
    private var pendingContext: Pair<PlayContext, String?>? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Keep the helper deck in step with play/pause, except while the main deck rebuffers during hand-off.
                if (!handingOff && helperLive) helper?.let { if (isPlaying) it.play() else it.pause() }
                if (isPlaying) startTicker()
                val now = System.currentTimeMillis()
                val helperPos = helper?.currentPosition ?: 0L
                when {
                    isPlaying -> {
                        openPlay?.resume(now, player.currentPosition)
                        if (helperLive) mixPlay?.resume(now, helperPos)
                    }
                    // Paused by someone, rather than buffering or at the end.
                    !player.playWhenReady -> {
                        openPlay?.pause(now, player.currentPosition)
                        if (helperLive) mixPlay?.pause(now, helperPos)
                    }
                    else -> openPlay?.progress(now, player.currentPosition)
                }
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                // A seek from anywhere (app, notification, headset) abandons a blend in progress.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !handingOff) cancelMix()
                trackPlay(old, new, reason)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) endPlay(PlayEndReason.COMPLETED, player.currentPosition)
            }

            override fun onPlayerError(error: PlaybackException) = endPlay(PlayEndReason.ERROR, player.currentPosition)
        })
    }

    /** [context] says what started playback, for listening history. */
    fun play(tracks: List<Track>, startAt: Int = 0, context: PlayContext = PlayContext.LIBRARY, contextRef: String? = null) {
        if (tracks.isEmpty()) return
        cancelMix()
        endPlay(PlayEndReason.REPLACED, player.currentPosition)
        queueContext = context to contextRef
        beginPlay(tracks.getOrNull(startAt), queueContext)
        _state.update { it.copy(queue = tracks) }
        player.setMediaItems(tracks.map(::mediaItem), startAt, 0)
        player.prepare()
        player.play()
    }

    fun pause() = player.pause()

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
        pendingContext = PlayContext.QUEUE to null
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

    // region Listening history

    /** Ends or starts plays as the player moves between tracks or seeks within one. */
    private fun trackPlay(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        val now = System.currentTimeMillis()
        val context = pendingContext
        pendingContext = null
        val newId = new.mediaItem?.mediaId
        val sameItem = old.mediaItemIndex == new.mediaItemIndex && old.mediaItem?.mediaId == newId
        if (sameItem && openPlay != null) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                openPlay?.seek(now, old.positionMs, new.positionMs)
            }
            return
        }
        // play() already opened the new queue's track.
        if (!sameItem && openTrack != null && openTrack?.id == newId && old.mediaItem?.mediaId != newId) return
        if (!sameItem) {
            val ending = when {
                handingOff -> PlayEndReason.COMPLETED
                reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> PlayEndReason.COMPLETED
                reason == Player.DISCONTINUITY_REASON_REMOVE -> PlayEndReason.STOPPED
                new.mediaItemIndex < old.mediaItemIndex -> PlayEndReason.PREVIOUS
                else -> PlayEndReason.SKIPPED
            }
            endPlay(ending, old.positionMs, mixedOut = handingOff)
        }
        if (handingOff && mixPlay != null && mixTrack?.id == newId) {
            openPlay = mixPlay
            openTrack = mixTrack
            mixPlay = null
            mixTrack = null
            return
        }
        val track = _state.value.queue.firstOrNull { it.id == newId }
        beginPlay(track, context ?: if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) PlayContext.AUTOPLAY to null else queueContext)
        if (player.isPlaying) openPlay?.resume(now, new.positionMs)
    }

    private fun beginPlay(track: Track?, context: Pair<PlayContext, String?>) {
        openTrack = track
        openPlay = track?.let { PlaySession(it.id, it.durationMs, context.first, context.second) }
    }

    private fun endPlay(reason: PlayEndReason, posMs: Long, mixedOut: Boolean = false) {
        val session = openPlay ?: return
        val track = openTrack
        openPlay = null
        openTrack = null
        val payload = session.end(System.currentTimeMillis(), posMs, reason, mixedOut) ?: return
        if (track != null) recorder?.finish(payload, track)
    }

    // endregion

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive && (player.isPlaying || mixJob?.isActive == true)) {
                if (player.isPlaying) openPlay?.let { session ->
                    session.progress(System.currentTimeMillis(), player.currentPosition)
                    recorder?.checkpoint(session)
                }
                publish()
                startMixIfDue()
                delay(if (mixJob?.isActive == true) 100 else 250)
            }
            publish()
        }
    }

    // region Mixing

    private fun outPosition(): Double = player.currentPosition / 1000.0

    private fun startMixIfDue() {
        val s = _state.value
        if (!s.mixIntoNext || mixJob?.isActive == true || !player.isPlaying) return
        val outgoing = s.current ?: return
        val incoming = s.upNext ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val plan = Mixing.plan(outgoing, incoming, duration / 1000.0)
        val pos = outPosition()
        if (pos >= plan.outStart - PRELOAD_SECONDS && pos < plan.outStart + plan.length - 2) {
            val index = s.currentIndex + 1
            mixJob = scope.launch { blend(plan, incoming, index) }
        }
    }

    /**
     * Loads the incoming track on a helper deck a few seconds early, starts it
     * on the blend's first downbeat, then follows the plan: crossfade on the
     * DSP core's equal-power curve, and keep the incoming track on the beat by
     * jumping (only while it's still quiet) or nudging its speed.
     */
    private suspend fun blend(plan: MixPlan, incoming: Track, index: Int) {
        val deck = buildPlayer(handleFocus = false).apply {
            setMediaItem(mediaItem(incoming), (plan.inPositionAt(outPosition()) * 1000).toLong())
            playbackParameters = PlaybackParameters(plan.rate.toFloat())
            volume = 0f
            prepare()
        }
        helper = deck
        // Wait for the start on the main deck's clock, so pausing waits too.
        while (outPosition() < plan.outStart) {
            delay(((plan.outStart - outPosition()) * 1000).toLong().coerceIn(5, 200))
        }
        // Started late (the blend was turned on mid-way): catch up first.
        val target = plan.inPositionAt(outPosition())
        if (abs(deck.currentPosition / 1000.0 - target) > 0.025) deck.seekTo((target * 1000).toLong())
        helperLive = true
        mixTrack = incoming
        mixPlay = PlaySession(incoming.id, incoming.durationMs, PlayContext.AUTOPLAY, mixedIn = true)
        if (player.isPlaying) {
            deck.play()
            mixPlay?.resume(System.currentTimeMillis(), deck.currentPosition)
        }
        _state.update { it.copy(isMixing = true) }

        var lastSeek = 0L
        while (true) {
            val step = Mixing.follow(plan, outPosition(), deck.currentPosition / 1000.0)
            player.volume = step.gainOut
            deck.volume = step.gainIn
            if (player.isPlaying) mixPlay?.progress(System.currentTimeMillis(), deck.currentPosition)
            val settled = deck.playbackState == Player.STATE_READY && deck.isPlaying
            val now = SystemClock.elapsedRealtime()
            if (step.seekTo != null) {
                // Give each jump time to land before judging it.
                if (settled && now - lastSeek > 500) {
                    deck.seekTo((step.seekTo * 1000).toLong())
                    lastSeek = now
                }
            } else if (abs(deck.playbackParameters.speed - step.speed) > 1e-4) {
                deck.playbackParameters = PlaybackParameters(step.speed.toFloat())
            }
            if (step.progress >= 1.0) break
            delay(40)
        }
        handOff(deck, index, plan.rate.toFloat())
    }

    /** Moves the main deck onto the incoming track where the helper is, then retires the helper. */
    private suspend fun handOff(deck: ExoPlayer, index: Int, speed: Float) {
        handingOff = true
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
        mixPlay = null
        mixTrack = null
        retireHelper()
        handingOff = false
        player.volume = 1f
        player.playbackParameters = PlaybackParameters.DEFAULT
        _state.update { it.copy(isMixing = false) }
    }

    private fun retireHelper() {
        helper?.release()
        helper = null
        helperLive = false
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
                    // The notification, lock screen and Bluetooth displays show it.
                    .setArtworkUri(if (track.id in artwork.available.value) Uri.fromFile(artwork.fileFor(track.id)) else null)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

    private companion object {
        /** How early the incoming track is loaded before a blend. */
        const val PRELOAD_SECONDS = 6.0
    }
}
