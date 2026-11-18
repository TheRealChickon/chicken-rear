package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.IntBuffer

/**
 * THE MASK SHOWN IN TWO COLUMNS IS THE MASK (RearShape.columns, RearShape.lay). The display's shape used to be one layer
 * the back's size; it is now two layers, each holding the whole mask drawn as before and cropped to a column at one
 * side. What the display shows is then the mask's own pixels inside the columns and nothing outside them. That is the
 * mask bit for bit (premultiplied, as the buffers hold them) only if the mask has nothing but clear pixels outside the
 * columns: held here for the back's own sizes and many others.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RearShapeColumnsTest {

    private fun full(w: Int, h: Int, r: Int): IntArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        RearShape.drawMask(Canvas(b), w.toFloat(), h.toFloat(), r.toFloat())
        return IntArray(w * h).also { b.copyPixelsToBuffer(IntBuffer.wrap(it)) }
    }

    /** What the display shows of the column layers: the mask's pixels inside each column's crop, clear elsewhere. */
    private fun shown(mask: IntArray, w: Int, h: Int, r: Int): IntArray {
        val out = IntArray(w * h)
        for (col in RearShape.columns(w, r)) for (y in 0 until h) for (x in col.x until col.x + col.width) out[y * w + x] = mask[y * w + x]
        return out
    }

    private fun same(w: Int, h: Int, r: Int) {
        val want = full(w, h, r)
        val got = shown(want, w, h, r)
        var differ = 0
        var first = ""
        for (i in want.indices) if (want[i] != got[i]) {
            if (differ++ == 0) first = "first at (${i % w}, ${i / w}): ${Integer.toHexString(want[i])}"
        }
        assertEquals("${w}x$h, radius $r: pixels of the mask outside the columns ($first)", 0, differ)
    }

    @Test fun `the back's own sizes show the full mask bit for bit`() {
        same(976, 596, 101)
        same(976, 596, 105)
        same(904, 572, 97)
    }

    @Test fun `other sizes and radii too, odd ones and a radius past half the height`() {
        for (r in listOf(1, 2, 7, 33, 64, 100, 150, 297, 310)) same(977, 597, r)
        same(640, 360, 50)
        same(300, 200, 160)
    }

    @Test fun `the mask has something in each corner, so the check above looked at real pixels`() {
        val w = 976; val h = 596
        val px = full(w, h, 101)
        for ((x, y) in listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1)) assertTrue("corner ($x, $y) black", px[y * w + x] != 0)
        assertEquals("middle clear", 0, px[(h / 2) * w + w / 2])
    }

    @Test fun `two columns at the sides, each the radius and its spare, never overlapping`() {
        val cols = RearShape.columns(976, 101)
        assertEquals(listOf(RearShape.Column(0, 103), RearShape.Column(873, 103)), cols)
        // What the display reads and blends: under a quarter of the back.
        assertTrue(cols.sumOf { it.width } * 4 < 976)
        for (w in 1..400) for (r in 0..220) {
            val c = RearShape.columns(w, r)
            if (r == 0) { assertTrue(c.isEmpty()); continue }
            assertTrue(c.all { it.x >= 0 && it.width > 0 && it.x + it.width <= w })
            if (c.size == 2) assertTrue("no overlap at $w, $r", c[0].x + c[0].width <= c[1].x)
            // Every corner's square, and its spare, is inside a column.
            val reach = minOf(r + RearShape.COLUMN_SPARE, w)
            assertTrue("left corners at $w, $r", c.any { it.x == 0 && it.width >= reach })
            assertTrue("right corners at $w, $r", c.any { it.x <= w - reach && it.x + it.width == w })
        }
    }

    /**
     * The mask as the phone's hardware canvas draws it (ShellLayer.lockPanelCanvas): pre-rotated a quarter turn into a
     * buffer the panel's way up, [h] wide and [w] high, either way round. The crop is in the layer's own w x h space, so
     * the buffer must be clear wherever the mask's own x is outside the columns. Logical pixel (x, y) lands in buffer
     * pixel (h-1-y, x) turned clockwise, (y, w-1-x) the other way. Drawn by the host's software rasterizer, not the
     * phone's GPU.
     */
    private fun rotatedOutside(w: Int, h: Int, r: Int, clockwise: Boolean): Int {
        val b = Bitmap.createBitmap(h, w, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (clockwise) { c.translate(h.toFloat(), 0f); c.rotate(90f) } else { c.translate(0f, w.toFloat()); c.rotate(-90f) }
        RearShape.drawMask(c, w.toFloat(), h.toFloat(), r.toFloat())
        val px = IntArray(w * h).also { b.copyPixelsToBuffer(IntBuffer.wrap(it)) }
        val cols = RearShape.columns(w, r)
        // Something drawn in each corner, so the count below looked at real pixels.
        for ((x, y) in listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1)) {
            val (bx, by) = if (clockwise) (h - 1 - y) to x else y to (w - 1 - x)
            assertTrue("${w}x$h, radius $r, ${if (clockwise) "clockwise" else "the other way"}: corner ($x, $y) black", px[by * h + bx] != 0)
        }
        var outside = 0
        for (by in 0 until w) {
            val x = if (clockwise) by else w - 1 - by
            if (cols.any { x >= it.x && x < it.x + it.width }) continue
            for (bx in 0 until h) if (px[by * h + bx] != 0) outside++
        }
        return outside
    }

    @Test fun `drawn pre-rotated either way, as the phone's hardware canvas does, nothing lies outside the columns`() {
        for (clockwise in listOf(true, false)) {
            for ((w, h, r) in listOf(Triple(976, 596, 101), Triple(976, 596, 105), Triple(904, 572, 97), Triple(640, 360, 50), Triple(300, 200, 160))) {
                assertEquals("${w}x$h, radius $r, clockwise $clockwise", 0, rotatedOutside(w, h, r, clockwise))
            }
            for (r in listOf(1, 2, 7, 33, 64, 100, 150, 297, 310)) assertEquals("977x597, radius $r, clockwise $clockwise", 0, rotatedOutside(977, 597, r, clockwise))
        }
    }

    @Test fun `no radius, no layer, as the mask was clear all over`() {
        assertTrue(RearShape.columns(976, 0).isEmpty())
        assertTrue(full(976, 596, 0).all { it == 0 })
    }
}
