package app.motif

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.motif.importer.ImportJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Album art and Now Playing on a device: art comes from a FLAC's embedded
 * PICTURE block or, failing that, a cover.jpg in the picked folder; it shows
 * in the library, mini player and Now Playing, whose text must be light on its
 * dark ground.
 */
@RunWith(AndroidJUnit4::class)
class NowPlayingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val app = ApplicationProvider.getApplicationContext<MotifApp>()
    private val folder: Uri = DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures", "root")

    @Before fun setUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.playback.player.clearMediaItems()
            app.importer.clearFinished()
            app.account.continueOffline()
        }
        runBlocking {
            app.library.load()
            app.library.tracks.value.forEach {
                app.library.delete(it)
                app.artwork.delete(it.id)
            }
        }
        val dir = File(app.cacheDir, "fixtures").apply { deleteRecursively() }
        // Album with a folder cover and untagged art: blue.
        listOf("01 Afterglow" to 122.0, "02 Neon Glide" to 124.0, "03 Slow Burn" to 96.0).forEach { (name, bpm) ->
            TestAudio.writeFlac(File(dir, "Night Drive/$name.flac"), name.drop(3), "Motif Test Band", "Night Drive", bpm)
        }
        File(dir, "Night Drive/cover.jpg").writeBytes(cover(0xFF2B6CB0.toInt(), 0xFFC6F432.toInt()))
        // A single with its art embedded and no folder cover: red.
        val single = File(dir, "Singles/Ember.flac")
        TestAudio.writeFlac(single, "Ember", "Other Artist", "Ember", 128.0)
        embedPicture(single, cover(0xFFC53030.toInt(), 0xFFF6E05E.toInt()))
    }

    @Test fun artFromEmbeddedPictureAndFolderCover() {
        importFolder()
        val tracks = app.library.tracks.value
        compose.waitUntil(5_000) { tracks.all { it.id in app.artwork.available.value } }

        tracks.forEach { t ->
            val art = BitmapFactory.decodeFile(app.artwork.fileFor(t.id).path)
            val p = art.getPixel(art.width / 10, art.height / 10)
            val (r, b) = (p shr 16 and 0xFF) to (p and 0xFF)
            if (t.title == "Ember") assertTrue("${t.title} uses its embedded red art", r > b) else assertTrue("${t.title} uses the blue folder cover", b > r)
        }

        compose.onNodeWithText("Albums").performClick()
        compose.waitForIdle()
        Screenshots.take("10-albums-with-art")
        compose.onNodeWithText("Songs").performClick()
        compose.waitForIdle()
        Screenshots.take("11-songs-with-art")
    }

    @Test fun nowPlayingShowsArtChipsAndUpNext() {
        importFolder()
        val album = app.library.tracks.value.filter { it.album == "Night Drive" }.sortedBy { it.title }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.playback.play(album, 0) }
        compose.waitUntil(15_000) { app.playback.state.value.isPlaying }

        compose.onNode(SemanticsMatcher("opens Now Playing") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open Now Playing" })
            .performClick()
        compose.onNodeWithText("Up next").assertExists()
        compose.onNodeWithText("Lossless").assertExists()
        compose.onNodeWithText("Queue · 3").assertExists()
        Thread.sleep(800) // backdrop colour animation
        Screenshots.take("12-now-playing")

        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.playback.togglePlayPause() }
        compose.waitUntil(5_000) { !app.playback.state.value.isPlaying }
        Thread.sleep(500) // art shrink animation
        Screenshots.take("13-now-playing-paused")
    }

    private fun importFolder() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.importer.importFolder(folder) }
        compose.waitUntil(120_000) {
            val jobs = app.importer.jobs.value
            jobs.size == 4 && jobs.none { it.stage is ImportJob.Stage.Copying || it.stage is ImportJob.Stage.Analyzing }
        }
        val failed = app.importer.jobs.value.mapNotNull { j -> (j.stage as? ImportJob.Stage.Failed)?.let { "${j.fileName}: ${it.message}" } }
        assertTrue("Failed imports: $failed", failed.isEmpty())
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.importer.clearFinished() }
        compose.waitUntil(5_000) { app.library.tracks.value.size == 4 }
        assertEquals(4, app.library.tracks.value.size)
    }

    /** A 600 px cover: a flat ground with two overlapping discs. */
    private fun cover(ground: Int, disc: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ground)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = disc
        canvas.drawCircle(380f, 360f, 150f, paint)
        paint.color = 0xCC0B0C0F.toInt()
        canvas.drawCircle(250f, 260f, 110f, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** Inserts a FLAC PICTURE metadata block (front cover) straight after STREAMINFO. */
    private fun embedPicture(flac: File, jpeg: ByteArray) {
        val bytes = flac.readBytes()
        val block = ByteArrayOutputStream()
        fun be32(v: Int) = block.write(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
        val mime = "image/jpeg".toByteArray()
        be32(3) // front cover
        be32(mime.size)
        block.write(mime)
        be32(0) // no description
        be32(600)
        be32(600)
        be32(24)
        be32(0)
        be32(jpeg.size)
        block.write(jpeg)
        val body = block.toByteArray()
        val header = byteArrayOf(6, (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte())
        val split = 4 + 4 + 34 // "fLaC" + STREAMINFO header + body, which is never the last block here
        flac.writeBytes(bytes.copyOfRange(0, split) + header + body + bytes.copyOfRange(split, bytes.size))
    }
}
