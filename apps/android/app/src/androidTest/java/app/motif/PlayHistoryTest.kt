package app.motif

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.motif.data.HistoryStore
import app.motif.data.PlayContext
import app.motif.importer.ImportJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File

/** Playing music writes `play` events to the listening history: a skip, then a track played to the end. */
@RunWith(AndroidJUnit4::class)
class PlayHistoryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val app = ApplicationProvider.getApplicationContext<MotifApp>()
    private val folder: Uri = DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures", "root")
    private val main = InstrumentationRegistry.getInstrumentation()

    @Before fun setUp() {
        main.runOnMainSync {
            app.playback.player.clearMediaItems()
            app.importer.clearFinished()
            app.account.continueOffline()
            app.account.historyPaused.value = false
        }
        runBlocking {
            app.library.load()
            app.library.tracks.value.forEach { app.library.delete(it) }
            app.history.delete(HistoryStore.LISTENING_TYPES, null, null)
        }
        val dir = File(app.cacheDir, "fixtures").apply { deleteRecursively() }
        listOf("01 First", "02 Second").forEach { name ->
            TestAudio.writeFlac(File(dir, "Plays/$name.flac"), name.drop(3), "Motif Test Band", "Plays", seconds = 6.0)
        }
    }

    @Test fun skipAndCompletionAreRecorded() {
        main.runOnMainSync { app.importer.importFolder(folder) }
        compose.waitUntil(120_000) {
            val jobs = app.importer.jobs.value
            jobs.size == 2 && jobs.none { it.stage is ImportJob.Stage.Copying || it.stage is ImportJob.Stage.Analyzing }
        }
        compose.waitUntil(5_000) { app.library.tracks.value.size == 2 }
        val tracks = app.library.tracks.value.sortedBy { it.title }

        main.runOnMainSync { app.play(tracks, 0, PlayContext.ALBUM) }
        compose.waitUntil(10_000) { app.playback.state.value.isPlaying && app.playback.state.value.positionMs > 2_000 }
        main.runOnMainSync { app.playback.next() }
        compose.waitUntil(30_000) { plays(tracks.map { it.id }).size == 2 }

        val plays = plays(tracks.map { it.id })
        val (skip, skipped) = plays[0]
        assertEquals(tracks[0].id, skipped.getString("track_id"))
        assertEquals("skipped", skipped.getString("end_reason"))
        assertEquals("album", skipped.getString("context"))
        assertTrue("listened ${skipped.getLong("listened_ms")}", skipped.getLong("listened_ms") in 1_500..4_000)
        assertEquals(tracks[0].contentHash ?: runBlocking { app.library.contentHash(tracks[0]) }, skip.trackKey)

        val (_, completed) = plays[1]
        assertEquals(tracks[1].id, completed.getString("track_id"))
        assertEquals("completed", completed.getString("end_reason"))
        assertEquals("album", completed.getString("context"))
        assertTrue("listened ${completed.getLong("listened_ms")}", completed.getLong("listened_ms") > 5_000)
    }

    @Test fun pausedHistoryRecordsNothing() {
        main.runOnMainSync { app.importer.importFolder(folder) }
        compose.waitUntil(120_000) {
            val jobs = app.importer.jobs.value
            jobs.size == 2 && jobs.none { it.stage is ImportJob.Stage.Copying || it.stage is ImportJob.Stage.Analyzing }
        }
        compose.waitUntil(5_000) { app.library.tracks.value.size == 2 }
        val tracks = app.library.tracks.value.sortedBy { it.title }

        main.runOnMainSync {
            app.account.historyPaused.value = true
            app.play(tracks, 0)
        }
        compose.waitUntil(10_000) { app.playback.state.value.positionMs > 2_000 }
        main.runOnMainSync { app.playback.next() }
        Thread.sleep(1_000)
        // Ends the second track's play while still paused.
        main.runOnMainSync { app.playback.player.clearMediaItems() }
        Thread.sleep(1_000)
        main.runOnMainSync { app.account.historyPaused.value = false }
        assertEquals(emptyList<Any>(), plays(tracks.map { it.id }))
    }

    /** Plays of these tracks, oldest first. Other tests' plays may still be landing. */
    private fun plays(ids: List<String>) = runBlocking { app.history.events("play") }
        .map { it to JSONObject(it.payload) }
        .filter { (_, payload) -> payload.getString("track_id") in ids }
}
