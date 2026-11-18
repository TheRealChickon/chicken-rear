package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Pin to location on the back: the place's chip, its hollow mark, the page that stands when he has arrived. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinFaceArrivalTest {
    private fun page(draw: (Canvas) -> Unit): Bitmap = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }

    private fun changed(a: Bitmap, b: Bitmap, inside: (Int, Int) -> Boolean): Pair<Int, Int> {
        var n = 0; var out = 0
        for (y in 0 until 596 step 2) for (x in 0 until 976 step 2) if (a.getPixel(x, y) != b.getPixel(x, y)) { n++; if (!inside(x, y)) out++ }
        return n to out
    }

    @Test fun `a waiting place note's chip says the place, and its mark is hollow while not watched - only the chip changes`() {
        val whole = page { PinFace.draw(it, 976f, 596f, "Bring the towels", "09:41", 0, 1, true, place = "Gym", hollow = false) }
        val hollow = page { PinFace.draw(it, 976f, 596f, "Bring the towels", "09:41", 0, 1, true, place = "Gym", hollow = true) }
        val (n, out) = changed(whole, hollow) { x, y -> y in 50..112 && x >= 600 }
        assertNotEquals(0, n); assertEquals(0, out)
        File("build").mkdirs()
        File("build/pin-place-chip.png").outputStream().use { whole.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `the arrival page has the place's name where Pinned message stands, and the rest is the notes page`() {
        val note = page { PinFace.draw(it, 976f, 596f, "Bring the towels", "09:41", 0, 1, true) }
        val arrival = page { PinFace.draw(it, 976f, 596f, "Bring the towels", "09:41", 0, 1, true, place = "Gym", arrival = true) }
        File("build/pin-arrival.png").outputStream().use { arrival.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Only the head (mark and title) and the chip differ; the note, the bin and the handle are the notes page's.
        val (n, out) = changed(note, arrival) { _, y -> y < 120 }
        assertNotEquals(0, n); assertEquals(0, out)
    }

    private fun same(a: Bitmap, b: Bitmap) = changed(a, b) { _, _ -> true }.first == 0

    @Test fun `every notes page of the shell draws a place note with its name and its mark, hollow while not watched`() {
        // The shell's notes page, the beam's landing and the veil over Xiaomi's pin all draw through PinFace.note (the
        // review: they passed neither the place nor the mark's state, so a place note's chip was empty and never hollow).
        val now = 1_763_140_000_000L
        val at = now - 60_000L
        val stamp = PinFace.stamp(at, now)
        val gym = dev.kb896.aiusage.data.Pin("Bring the towels", at, lat = 48.1, lng = 11.6, place = "Gym", watched = false)
        assertTrue("unwatched: the name and a hollow mark",
            same(page { PinFace.note(it, 976f, 596f, gym, 0, 1, now) }, page { PinFace.draw(it, 976f, 596f, gym.text, stamp, 0, 1, true, place = "Gym", hollow = true) }))
        assertTrue("watched: the name and a whole mark",
            same(page { PinFace.note(it, 976f, 596f, gym.copy(watched = true), 0, 1, now) }, page { PinFace.draw(it, 976f, 596f, gym.text, stamp, 0, 1, true, place = "Gym", hollow = false) }))
        assertFalse("the name is drawn (not the empty chip of before)",
            same(page { PinFace.note(it, 976f, 596f, gym.copy(watched = true), 0, 1, now) }, page { PinFace.draw(it, 976f, 596f, gym.text, stamp, 0, 1, true) }))
        val plain = dev.kb896.aiusage.data.Pin("Take the keys", at)
        assertTrue("a note with no place: its time, as ever",
            same(page { PinFace.note(it, 976f, 596f, plain, 1, 3, now) }, page { PinFace.draw(it, 976f, 596f, plain.text, stamp, 1, 3, false) }))
        assertTrue("the veil before the note is in the list: its words and the time now",
            same(page { PinFace.note(it, 976f, 596f, null, 0, 1, now, text = "Hello") }, page { PinFace.draw(it, 976f, 596f, "Hello", PinFace.stamp(now, now), 0, 1, false) }))
    }

    @Test fun `a long name is cut with an ellipsis, and the page drifts by 2 px only after ten minutes`() {
        assertEquals("Fitness Stud…", PinFace.chipName("Fitness Studio Mitte", 12))
        assertEquals("Gym", PinFace.chipName(" Gym ", 12))
        assertEquals(0f to 0f, PinFace.drift(9 * 60_000L))
        val seen = (10..20).map { PinFace.drift(it * 60_000L) }.toSet()
        assert(seen.size > 1)
        for ((dx, dy) in seen) { assert(dx in 0f..2f && dy in 0f..2f) }
    }
}
