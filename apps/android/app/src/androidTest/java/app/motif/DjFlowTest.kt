package app.motif

import android.provider.DocumentsContract
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.motif.data.Track
import app.motif.dsp.MotifDsp
import app.motif.importer.ImportJob
import app.motif.importer.TrackAnalyzer
import app.motif.playback.DeckId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
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
            app.account.continueOffline()
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

    /** The imported tracks by title, failing with the reason when one has no beat grid. */
    private fun griddedTracks(): Map<String, Track> {
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
        return tracks
    }

    @Test fun syncLocksDeckBToDeckA() {
        val tracks = griddedTracks()

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

    @Test fun loopHoldsTheDeckEqKillsAndListeningIsHistory() {
        val afterglow = griddedTracks().getValue("Afterglow")

        compose.onNodeWithContentDescription("Open DJ mix").performClick()
        instrumentation.runOnMainSync {
            app.dj.load(DeckId.A, afterglow)
            app.dj.setCrossfader(0f)
        }
        compose.onNodeWithContentDescription("Play deck A").performClick()
        compose.waitUntil(5_000) { app.dj.state.value.a.isPlaying && app.dj.state.value.a.positionMs > 300 }

        // Two beats at 120 BPM: a one-second loop that the deck keeps returning to.
        compose.onNodeWithContentDescription("Deck A loop 2 beats").performClick()
        val loop = app.dj.state.value.a.loop ?: error("no loop: ${app.dj.state.value.notice}")
        assertEquals(1_000.0, (loop.endMs - loop.startMs).toDouble(), 2.0)
        Thread.sleep(2_600)
        val pos = app.dj.state.value.a.positionMs
        assertTrue("position $pos outside loop ${loop.startMs}..${loop.endMs}", pos in (loop.startMs - 60)..(loop.endMs + 200))

        // Kill the lows through the knob's accessibility action.
        compose.onNodeWithContentDescription("Deck A low").performSemanticsAction(SemanticsActions.SetProgress) { it(-1f) }
        assertEquals(-1f, app.dj.state.value.a.fx.low)
        Screenshots.take("11-dj-loop-eq")

        // Regular playback takes over: the DJ listen lands in history as a play.
        instrumentation.runOnMainSync { app.dj.stopForPlayback() }
        compose.waitUntil(10_000) {
            runBlocking { app.history.events("play") }.any {
                val p = JSONObject(it.payload)
                p.optString("context") == "mix" && p.optString("track_id") == afterglow.id && p.optLong("listened_ms") > 1_000
            }
        }
    }
}
