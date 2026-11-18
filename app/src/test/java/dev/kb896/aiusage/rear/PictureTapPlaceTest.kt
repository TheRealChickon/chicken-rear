package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.kb896.aiusage.data.Pin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where the back's shell takes a tap to be on a note's picture (PinFace.pictureAt) is where the page draws it, with a
 * caption of one line, of two, and none. Made-up pictures only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PictureTapPlaceTest {
    @After fun noPictures() { PinFace.pictures = { null } }

    private fun green(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.rgb(0, 255, 0)) }
    private fun isGreen(p: Int) = Color.green(p) > 200 && Color.red(p) < 60 && Color.blue(p) < 60

    private fun greenBox(b: Bitmap): IntArray {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var bo = -1
        for (y in 0 until b.height) for (x in 0 until b.width) if (isGreen(b.getPixel(x, y))) { l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); bo = maxOf(bo, y) }
        return intArrayOf(l, t, r, bo)
    }

    @Test fun `the place a tap is taken on is the place the picture is drawn, caption or none`() {
        for ((caption, w, h) in listOf(Triple("", 478, 359), Triple("A caption", 300, 428), Triple("A caption long enough to need a second line on the page, surely", 478, 200))) {
            val name = "7000-${w}x$h.jpg"
            val picture = green(w, h)
            PinFace.pictures = { if (it == name) picture else null }
            val page = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
            PinFace.note(Canvas(page), 976f, 596f, Pin(caption, 7_000L, image = name), 0, 1)
            val drawn = greenBox(page)
            val at = PinFace.pictureAt(caption, name)!!
            // Within a pixel or two (the rounded corners take the very corner pixels; edges are anti-aliased).
            assertEquals("left, '$caption'", at[0], drawn[0].toFloat(), 2f)
            assertEquals("top, '$caption'", at[1], drawn[1].toFloat(), 2f)
            assertEquals("right, '$caption'", at[2], drawn[2].toFloat() + 1f, 2f)
            assertEquals("bottom, '$caption'", at[3], drawn[3].toFloat() + 1f, 2f)
            // A tap in the middle of what is drawn is on it; one on the bin is not.
            assertTrue(PictureTap.onPicture(at, (drawn[0] + drawn[2]) / 2f, (drawn[1] + drawn[3]) / 2f))
        }
    }

    @Test fun `a note with no picture of ours has no place`() {
        assertNull(PinFace.pictureAt("words", null))
        assertNull(PinFace.pictureAt("", "../x.jpg"))
        assertNotNull(PinFace.pictureAt("", "1-10x10.jpg"))
    }
}
