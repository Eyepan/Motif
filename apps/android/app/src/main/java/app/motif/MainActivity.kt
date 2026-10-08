package app.motif

import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.motif.playback.PlaybackService
import app.motif.ui.LocalArtwork
import app.motif.ui.MotifRoot
import app.motif.ui.theme.MotifTheme
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val app = application as MotifApp
        setContent {
            MotifTheme {
                CompositionLocalProvider(LocalArtwork provides app.artwork) { MotifRoot(app) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Android can't watch a folder for an app in the background; catch up on what arrived meanwhile.
        (application as MotifApp).folderImporter.scan()
        // Connecting a controller starts PlaybackService, which owns the media notification.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }
}
