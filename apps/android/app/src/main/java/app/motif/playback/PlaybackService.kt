package app.motif.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.motif.MainActivity
import app.motif.MotifApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the media session controls. */
enum class SessionSource { Playback, Dj }

/**
 * Background playback: exposes the engine's main deck, or the DJ decks while
 * a mix plays, as a MediaSession, so Media3 posts the media notification,
 * keeps the service in the foreground while playing, and routes lock screen,
 * headset and Bluetooth controls.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var follow: Job? = null

    override fun onCreate() {
        super.onCreate()
        val engine = (application as MotifApp).playback
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val app = application as MotifApp
        val session = MediaSession.Builder(this, engine.player).setSessionActivity(openApp).build()
        this.session = session
        follow = app.scope.launch {
            app.sessionSource.collect { source ->
                val player = if (source == SessionSource.Dj) app.djSessionPlayer else engine.player
                if (session.player !== player) session.player = player
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // The players belong to the app-wide engines and outlive the service.
        follow?.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }
}
