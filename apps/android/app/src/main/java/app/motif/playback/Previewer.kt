package app.motif.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Streams a catalog result before it's downloaded. Its own small player, so the
 * queue is untouched; taking audio focus pauses the main player, as any other
 * app would. Built on first use and released by [stop].
 */
class Previewer(private val context: Context) {
    private var player: ExoPlayer? = null

    /** Key of the result being previewed, or null. */
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing.asStateFlow()

    fun play(key: String, url: String) {
        val p = player ?: ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { created ->
                created.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) _playing.value = null
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // Paused by losing focus or unplugging headphones.
                        if (!isPlaying && created.playbackState == Player.STATE_READY && !created.playWhenReady) _playing.value = null
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        _playing.value = null
                    }
                })
                player = created
            }
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.play()
        _playing.value = key
    }

    fun pause() {
        player?.pause()
        _playing.value = null
    }

    fun stop() {
        player?.release()
        player = null
        _playing.value = null
    }
}
