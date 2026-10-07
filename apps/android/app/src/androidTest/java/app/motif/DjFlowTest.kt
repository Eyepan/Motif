package app.motif

import android.provider.DocumentsContract
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.motif.dsp.MotifDsp
import app.motif.importer.ImportJob
import app.motif.importer.TrackAnalyzer
import app.motif.playback.DeckId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * DJ Mix on a device: tracks get beat grids at import, two decks play, and
 * SYNC puts deck B on deck A's tempo and beat.
 */
@RunWith(AndroidJUnit4::class)
class DjFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val app = ApplicationProvider.getApplicationContext<MotifApp>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun setUp() {
        instrumentation.runOnMainSync {
            app.playback.player.clearMediaItems()
            app.importer.clearFinished()
        }
        runBlocking {
            app.library.load()
            app.library.tracks.value.forEach { app.library.delete(it) }
        }
        val dir = File(app.cacheDir, "fixtures").apply { deleteRecursively() }
        TestAudio.writeFlac(File(dir, "A/Afterglow.flac"), "Afterglow", "Motif Test Band", "Night Drive", 120.0, seconds = 20.0)
        TestAudio.writeFlac(File(dir, "A/Neon Glide.flac"), "Neon Glide", "Motif Test Band", "Night Drive", 124.0, seconds = 20.0)
        instrumentation.runOnMainSync {
            app.importer.importFolder(DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures", "root"))
        }
        compose.waitUntil(120_000) {
            val jobs = app.importer.jobs.value
            jobs.size == 2 && jobs.none { it.stage is ImportJob.Stage.Copying || it.stage is ImportJob.Stage.Analyzing }
        }
        compose.waitUntil(5_000) { app.library.tracks.value.size == 2 }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { app.dj.pauseAll() }
    }

    @Test fun syncLocksDeckBToDeckA() {
        val tracks = app.library.tracks.value.associateBy { it.title }
        tracks.values.forEach { t ->
            if (!t.hasBeatGrid) {
                // Say why: analysis off, no native core, or what the analyser returns for this file.
                val direct = runBlocking { runCatching { TrackAnalyzer.analyze(app.library.fileFor(t)) {} } }
                fail(
                    "${t.title} has no beat grid: bpm ${t.bpm}, downbeat ${t.firstDownbeat}, analyse on import ${app.analyzeOnImport.value}, " +
                        "DSP ${MotifDsp.available}, direct analysis ${direct.map { r -> r?.let { "bpm ${it.bpm} downbeat ${it.firstDownbeat} key ${it.camelotKey} loudness ${it.loudnessDb}" } }}",
                )
            }
        }

        compose.onNodeWithContentDescription("Open DJ mix").performClick()
        instrumentation.runOnMainSync {
            app.dj.load(DeckId.A, tracks.getValue("Afterglow"))
            app.dj.load(DeckId.B, tracks.getValue("Neon Glide"))
        }
        compose.onNodeWithContentDescription("Play deck A").performClick()
        compose.onNodeWithContentDescription("Play deck B").performClick()
        compose.onAllNodesWithText("SYNC")[1].performClick()

        compose.waitUntil(5_000) { app.dj.state.value.b.synced && app.dj.state.value.b.isPlaying }
        val b = app.dj.state.value.b
        val a = app.dj.state.value.a
        assertEquals(a.bpm!!, b.bpm!!, 0.5)
        assertTrue("deck B speed ${b.speed}", abs(b.speed - a.bpm!! / tracks.getValue("Neon Glide").bpm!!) < 0.01)
        Screenshots.take("10-dj-synced")
    }
}
