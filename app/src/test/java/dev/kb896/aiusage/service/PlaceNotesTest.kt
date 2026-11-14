package dev.kb896.aiusage.service

import android.graphics.Bitmap
import android.graphics.Canvas
import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.rear.PinFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Notes with a place: what an arrival does to the list, and the mark such a note has on the back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaceNotesTest {

    private val home = Pin("Take the keys", 10, 52.5, 13.4)
    private val plain = Pin("Milk", 20)

    @Test fun `a note has a place only with both figures`() {
        assertTrue(home.placed)
        assertFalse(plain.placed)
        assertFalse(Pin("half", 1, lat = 52.5).placed)
    }

    @Test fun `arriving brings the note to the front, as if just pinned`() {
        val next = PlaceNotes.arrived(listOf(plain, home), at = 10, now = 500)!!
        assertEquals(listOf("Take the keys", "Milk"), next.map { it.text })
        assertEquals("the back shows what is newest", 500L, next.first().freshAt)
        assertEquals("and it is still the same note, for its bin and its watch", 10L, next.first().at)
        assertEquals(20L, next.last().freshAt)
    }

    @Test fun `an arrival for a note that is gone, or has no place, is nothing`() {
        assertNull(PlaceNotes.arrived(listOf(plain), at = 10, now = 500))
        assertNull("a plain note with that stamp", PlaceNotes.arrived(listOf(plain), at = 20, now = 500))
    }

    @Test fun `a note for a place has its mark in the chip`() {
        File("build").mkdirs()
        fun page(placed: Boolean): Bitmap = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888).also {
            PinFace.draw(Canvas(it), 976f, 596f, "Take the keys", "09:41", 0, 1, placed)
        }
        val a = page(false); val b = page(true)
        File("build/pins-placed.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var changed = 0; var outside = 0
        for (y in 0 until 596 step 2) for (x in 0 until 976 step 2) if (a.getPixel(x, y) != b.getPixel(x, y)) {
            changed++
            if (y !in 50..112 || x < 700) outside++
        }
        assertNotEquals("the mark is drawn", 0, changed)
        assertEquals("and nothing but the chip changes", 0, outside)
    }
}
