package app.motif.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The DJ decks as one player for the media session, so a mix keeps playing
 * in the background under the media notification like regular playback:
 * it shows the deck being heard, and play/pause stops and resumes the decks.
 */
@OptIn(UnstableApi::class)
class DjSessionPlayer(private val dj: DjEngine, scope: CoroutineScope) : SimpleBasePlayer(Looper.getMainLooper()) {
    init {
        // Positions change every tick; the notification only needs what's on, and whether it plays.
        scope.launch {
            dj.state
                .map { s -> Triple(s.lead?.let { s[it].track?.id }, s.anyPlaying, s.lead?.let { s[it].cueMs }) }
                .distinctUntilChanged()
                .collect { invalidateState() }
        }
    }

    override fun getState(): State {
        val s = dj.state.value
        val lead = s.lead
        val deck = lead?.let { s[it] }
        val track = deck?.track
        val commands = Player.Commands.Builder()
            .addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE)
            .build()
        val builder = State.Builder().setAvailableCommands(commands)
        if (track == null) {
            return builder.setPlaybackState(Player.STATE_IDLE).setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle("DJ Mix · deck ${lead.name}")
            .build()
        val item = MediaItemData.Builder(track.id)
            .setMediaItem(MediaItem.Builder().setMediaId(track.id).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs(deck.durationMs * 1000)
            .build()
        return builder
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(s.anyPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs { dj.state.value[lead].positionMs }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) dj.resume() else dj.pauseAll()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        dj.pauseAll()
        return Futures.immediateVoidFuture()
    }
}
