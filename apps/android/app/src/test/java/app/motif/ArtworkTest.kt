package app.motif

import app.motif.data.ArtworkStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkTest {
    @Test fun folderCoverPrefersCommonNames() {
        assertEquals("Cover.JPG", ArtworkStore.pickFolderCover(listOf("back.jpg", "Folder.png", "Cover.JPG", "notes.txt")))
        assertEquals("folder.jpg", ArtworkStore.pickFolderCover(listOf("scan1.jpg", "folder.jpg")))
        assertEquals("front.webp", ArtworkStore.pickFolderCover(listOf("front.webp")))
    }

    @Test fun folderCoverIgnoresOtherImagesAndNonImages() {
        assertNull(ArtworkStore.pickFolderCover(listOf("booklet-03.jpg", "cover.txt", "cover")))
        assertNull(ArtworkStore.pickFolderCover(emptyList()))
    }

    @Test fun sampleSizeKeepsAtLeastTheTarget() {
        assertEquals(1, ArtworkStore.sampleSize(500, 1024))
        assertEquals(2, ArtworkStore.sampleSize(3000, 1024))
        assertEquals(8, ArtworkStore.sampleSize(3000, 300))
        assertEquals(1, ArtworkStore.sampleSize(3000, 0))
    }

    @Test fun backdropTintIsDarkAndKeepsTheHue() {
        // A bright red cover with a white border: the tint is a dark red, not grey.
        val pixels = IntArray(256) { i -> if (i < 32) 0xFFFFFFFF.toInt() else 0xFFE02020.toInt() }
        val tint = ArtworkStore.backdropTint(pixels)
        val r = tint shr 16 and 0xFF
        val g = tint shr 8 and 0xFF
        val b = tint and 0xFF
        assertEquals(0xFF, tint ushr 24)
        assertTrue("dark enough for light text: $r", r <= (0.42 * 255).toInt() + 1)
        assertTrue("red stays red: $r $g $b", r > g * 2 && r > b * 2)
    }

    @Test fun backdropTintOfBlackIsBlack() {
        assertEquals(0xFF000000.toInt(), ArtworkStore.backdropTint(IntArray(16) { 0xFF000000.toInt() }))
    }
}
