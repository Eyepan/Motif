package app.motif

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.motif.importer.FolderImporter
import app.motif.importer.ImportJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Read from Downloads on a device: a watched folder holding an album .zip, a
 * second copy of one of its songs and a better copy of another, served through
 * the Storage Access Framework by the debug-only FixtureDocumentsProvider.
 */
@RunWith(AndroidJUnit4::class)
class FolderImportTest {
    private val app = ApplicationProvider.getApplicationContext<MotifApp>()
    private val folder: Uri = DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures", "root")

    @Before fun setUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.importer.clearFinished() }
        runBlocking {
            app.library.load()
            app.library.tracks.value.forEach { app.library.delete(it) }
        }
        writeFixtures(File(app.cacheDir, "fixtures"))
    }

    @After fun tearDown() {
        // Other tests reuse the fixtures folder; don't let launches read it.
        app.folderImporter.stop()
    }

    @Test fun importsZipsAndKeepsTheBestCopy() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.folderImporter.watch(folder) }
        awaitJobs(4)

        val jobs = app.importer.jobs.value
        val failed = jobs.mapNotNull { j -> (j.stage as? ImportJob.Stage.Failed)?.let { "${j.fileName}: ${it.message}" } }
        assertTrue("Failed imports: $failed", failed.isEmpty())
        // The loose Hukum is the same 44.1 kHz FLAC as the album's: skipped.
        assertEquals(1, jobs.count { it.stage is ImportJob.Stage.Skipped })

        val tracks = app.library.tracks.value
        assertEquals(setOf("Hukum", "Veramaari"), tracks.map { it.title }.toSet())
        // The 48 kHz Veramaari replaced the album's 44.1 kHz file.
        assertEquals(48_000, tracks.first { it.title == "Veramaari" }.sampleRate)
        assertEquals(44_100, tracks.first { it.title == "Hukum" }.sampleRate)
        tracks.forEach { assertTrue("${it.title} has its file", app.library.fileFor(it).exists()) }

        // Nothing new: a second pass does nothing.
        val again = runBlocking { app.folderImporter.scanOnce(folder) }
        assertEquals(FolderImporter.Report(), again)
        assertEquals(2, app.library.tracks.value.size)
    }

    private fun awaitJobs(count: Int) {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val jobs = app.importer.jobs.value
            if (jobs.size >= count && jobs.none { it.stage is ImportJob.Stage.Copying || it.stage is ImportJob.Stage.Analyzing }) return
            Thread.sleep(250)
        }
        throw AssertionError("Imports didn't finish: ${app.importer.jobs.value.map { "${it.fileName} ${it.stage}" }}")
    }

    private companion object {
        const val ARTIST = "Motif Test Band"
        const val ALBUM = "Jailer 2"

        fun writeFixtures(dir: File) {
            dir.deleteRecursively()
            val staging = File(dir.parentFile, "fixtures-staging").apply { deleteRecursively() }
            val now = System.currentTimeMillis()

            TestAudio.writeFlac(File(staging, "01 Hukum.flac"), "Hukum", ARTIST, ALBUM, 120.0)
            TestAudio.writeFlac(File(staging, "02 Veramaari.flac"), "Veramaari", ARTIST, ALBUM, 124.0)
            val zip = File(dir, "Jailer-2-MassTamilan.zip")
            zip.parentFile!!.mkdirs()
            ZipOutputStream(zip.outputStream()).use { out ->
                staging.listFiles()!!.sortedBy { it.name }.forEach { f ->
                    out.putNextEntry(ZipEntry("Jailer 2/${f.name}"))
                    f.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
                // macOS metadata the importer must ignore.
                out.putNextEntry(ZipEntry("__MACOSX/Jailer 2/._01 Hukum.flac"))
                out.write(ByteArray(16))
                out.closeEntry()
            }
            staging.deleteRecursively()
            zip.setLastModified(now - 300_000)

            val single = File(dir, "Singles/Hukum.flac")
            TestAudio.writeFlac(single, "Hukum", ARTIST, ALBUM, 120.0)
            single.setLastModified(now - 200_000)
            File(dir, "Singles/notes.txt").writeText("not music")

            val hiRes = File(dir, "Hi-Res/Veramaari.flac")
            TestAudio.writeFlac(hiRes, "Veramaari", ARTIST, ALBUM, 124.0, sampleRate = 48_000)
            hiRes.setLastModified(now - 100_000)
        }
    }
}
