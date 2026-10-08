package app.motif

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.motif.importer.ImportJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end library flows on a device: add a folder through Add Music, check
 * what the importer read and how the library groups it, and play a track.
 * The folder is generated FLAC served by the debug-only FixtureDocumentsProvider,
 * so the import runs the same Storage Access Framework code as a picked folder.
 */
@RunWith(AndroidJUnit4::class)
class LibraryFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val app = ApplicationProvider.getApplicationContext<MotifApp>()
    private val folder: Uri = DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures", "root")

    @Before fun setUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.playback.player.clearMediaItems()
            app.importer.clearFinished()
        }
        runBlocking {
            app.library.load()
            app.library.tracks.value.forEach { app.library.delete(it) }
            app.crates.load()
            app.crates.crates.value.forEach { app.crates.delete(it.id) }
        }
        writeFixtures(File(app.cacheDir, "fixtures"))
        Intents.init()
    }

    @After fun tearDown() {
        Intents.release()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.playback.player.clearMediaItems() }
    }

    @Test fun importFolderFromAddMusic() {
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(folder)))

        compose.onNodeWithContentDescription("Add music").performClick()
        compose.onNodeWithText("Folder").performClick()
        awaitImports(5)
        compose.onNodeWithText("IMPORTING · 5 OF 5").assertExists()
        Screenshots.take("01-import-folder")

        // Every audio file in the folder and its subfolder, nothing else, untouched and tagged.
        val tracks = app.library.tracks.value
        assertEquals(setOf("Afterglow", "Neon Glide", "Slow Burn", "Morning Static", "Sunlit"), tracks.map { it.title }.toSet())
        tracks.forEach { t ->
            assertEquals("flac", t.format)
            assertEquals(44_100, t.sampleRate)
            assertEquals(2, t.channels)
            assertTrue("${t.title} lasts ${t.durationMs} ms", t.durationMs in 7_500..8_500)
            assertEquals(File(fixtureFile(t.title)).length(), app.library.fileFor(t).length())
        }

        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        Screenshots.take("02-library-songs")
    }

    @Test fun albumsGroupByAlbumTag() {
        importDirectly()

        compose.onNodeWithText("Albums").performClick()
        compose.onAllNodesWithText("Night Drive").assertCountEquals(1)
        compose.onNodeWithText("Motif Test Band · 3 songs").assertExists()
        compose.onAllNodesWithText("Daylight").assertCountEquals(1)
        compose.onNodeWithText("Other Artist · 2 songs").assertExists()
        Screenshots.take("03-library-albums")
    }

    @Test fun playTrackFromLibrary() {
        importDirectly()

        // Search narrows the list to one row, so it's on screen whatever the import order.
        compose.onNode(hasSetTextAction()).performTextInput("Afterglow")
        compose.onNodeWithContentDescription("Afterglow,", substring = true).performClick()
        compose.waitUntil(15_000) {
            val s = app.playback.state.value
            s.current?.title == "Afterglow" && s.isPlaying && s.positionMs > 1_000
        }
        compose.onNodeWithContentDescription("Pause").assertExists()
        Screenshots.take("04-playing")

        compose.onNodeWithContentDescription("Pause").performClick()
        compose.waitUntil(5_000) { !app.playback.state.value.isPlaying }
        compose.onNodeWithContentDescription("Play").assertExists()
    }

    @Test fun addTrackToNewCrate() {
        importDirectly()

        compose.onNode(hasSetTextAction()).performTextInput("Afterglow")
        compose.onNodeWithContentDescription("Afterglow,", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("Add to crate").performClick()
        compose.onNodeWithText("New crate…").performClick()
        compose.onNodeWithTag("crate-name").performTextInput("Warm up")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(10_000) { app.crates.crates.value.singleOrNull()?.trackKeys?.size == 1 }

        // The key is the SHA-256 of the imported file.
        val afterglow = app.library.tracks.value.first { it.title == "Afterglow" }
        assertEquals(afterglow.contentHash, app.crates.crates.value.single().trackKeys.single())
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(app.library.fileFor(afterglow).readBytes())
        assertEquals(sha.joinToString("") { "%02x".format(it) }, afterglow.contentHash)

        compose.onNode(hasText("Crates") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.onNodeWithText("Warm up").performClick()
        compose.onNodeWithContentDescription("Afterglow,", substring = true).assertExists()
        Screenshots.take("05-crate")
    }

    /** Imports the fixture folder without going through the sheet, for tests about what comes after. */
    private fun importDirectly() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.importer.importFolder(folder) }
        awaitImports(5)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.importer.clearFinished() }
        compose.waitForIdle()
    }

    private fun awaitImports(count: Int) {
        compose.waitUntil(120_000) {
            val jobs = app.importer.jobs.value
            jobs.size == count && jobs.none { it.stage is ImportJob.Stage.Copying || it.stage is ImportJob.Stage.Analyzing }
        }
        val failed = app.importer.jobs.value.mapNotNull { j -> (j.stage as? ImportJob.Stage.Failed)?.let { "${j.fileName}: ${it.message}" } }
        assertTrue("Failed imports: $failed", failed.isEmpty())
        compose.waitUntil(5_000) { app.library.tracks.value.size == count }
    }

    private fun fixtureFile(title: String) = File(app.cacheDir, "fixtures/" + FIXTURES.first { it.title == title }.path).path

    private class Fixture(val path: String, val title: String, val artist: String, val album: String, val bpm: Double)

    private companion object {
        val FIXTURES = listOf(
            Fixture("Night Drive/01 Afterglow.flac", "Afterglow", "Motif Test Band", "Night Drive", 120.0),
            Fixture("Night Drive/02 Neon Glide.flac", "Neon Glide", "Motif Test Band", "Night Drive", 124.0),
            Fixture("Night Drive/03 Slow Burn.flac", "Slow Burn", "Motif Test Band", "Night Drive", 96.0),
            Fixture("Night Drive/B-sides/01 Morning Static.flac", "Morning Static", "Other Artist", "Daylight", 128.0),
            Fixture("Night Drive/B-sides/02 Sunlit.flac", "Sunlit", "Other Artist", "Daylight", 110.0),
        )

        fun writeFixtures(dir: File) {
            dir.deleteRecursively()
            FIXTURES.forEach { TestAudio.writeFlac(File(dir, it.path), it.title, it.artist, it.album, it.bpm) }
            // Not audio: the importer should skip it.
            File(dir, "Night Drive/notes.txt").writeText("liner notes")
        }
    }
}
