package app.motif

import app.motif.importer.Dedupe
import app.motif.importer.FolderImporter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/** The parts of Read from Downloads that don't need a device; the rest is FolderImportTest on the emulator. */
class FolderImporterTest {
    @Test fun unpacksWithinLimits() {
        val dest = File.createTempFile("motif", ".flac")
        try {
            val data = ByteArray(10_000) { it.toByte() }
            assertTrue(FolderImporter.copyLimited(ByteArrayInputStream(data), dest, 10_000))
            assertArrayEquals(data, dest.readBytes())
            assertFalse(FolderImporter.copyLimited(ByteArrayInputStream(data), dest, 9_999))
        } finally {
            dest.delete()
        }
        assertEquals(3, FolderImporter.readLimited(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)?.size)
        assertNull(FolderImporter.readLimited(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2))
    }

    @Test fun recognisesFiles() {
        assertEquals("flac", FolderImporter.extension("01 Song.FLAC"))
        assertEquals("", FolderImporter.extension("README"))
        assertTrue("mp3" in FolderImporter.AUDIO_EXTENSIONS && "wav" in FolderImporter.AUDIO_EXTENSIONS)
    }

    @Test fun bitrateFromSize() {
        assertEquals(320, Dedupe.bitrateKbps(8_000_000, 200_000))
        assertNull(Dedupe.bitrateKbps(8_000_000, 0))
    }
}
