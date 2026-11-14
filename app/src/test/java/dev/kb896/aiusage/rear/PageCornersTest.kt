package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A page pulled aside over black has the display's own corners. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PageCornersTest {
    private val w = 976; private val h = 596
    private val r = PageCorners.radius(RearGeometry.PRO17, w.toFloat())

    /** A white page pulled [aside] px to the left over black, as the notes page draws its give at an end. */
    private fun pulled(aside: Float): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        c.save(); c.translate(-aside, 0f)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), android.graphics.Paint().apply { color = Color.WHITE })   // the page, in its own place
        PageCorners.cut(c, w.toFloat(), h.toFloat(), r)
        c.restore()
        return b
    }

    private fun light(b: Bitmap, x: Int, y: Int) = Color.red(b.getPixel(x, y))

    @Test fun `the radius is the rear display's own`() {
        // PRO17's corner is 97 of its 904 units across: 104.7 px of the panel's 976.
        assertEquals(97f * 976f / RearGeometry.PRO17.widthPx, r, 0.01f)
        assertEquals(97f, PageCorners.radius(RearGeometry.PRO17, RearGeometry.PRO17.widthPx.toFloat()), 0.01f)
        assertTrue(r in 95f..110f)
    }

    @Test fun `the corners of the pulled page are round - black outside the display's arc, the page inside it`() {
        val aside = 0.14f * w
        val b = pulled(aside)
        val edge = (w - aside).toInt()                      // the page's right edge, now inside the screen
        // Its right corners: the very corner is black, a point inside the arc is the page.
        for (y in listOf(0, h - 1)) {
            assertEquals("the very corner at y $y", 0, light(b, edge - 1, y))
            assertEquals(0, light(b, edge - 12, if (y == 0) 12 else h - 13))
        }
        for ((dx, dy) in listOf(32 to 32, 60 to 14, 14 to 60)) {
            assertEquals("inside the arc, top", 255, light(b, edge - 1 - dx, dy))
            assertEquals("inside the arc, bottom", 255, light(b, edge - 1 - dx, h - 1 - dy))
        }
        // Along the arc itself the edge is soft (anti-aliased), not a stair of black and white alone.
        val soft = (0 until r.toInt()).flatMap { y -> (edge - r.toInt() until edge).map { x -> light(b, x, y) } }.count { it in 20..235 }
        assertTrue("$soft pixels of the corner are between black and white", soft > 40)
        // The page's middle and its straight edges are untouched; beside the page it is black.
        assertEquals(255, light(b, edge - 1, h / 2)); assertEquals(255, light(b, edge / 2, 0)); assertEquals(255, light(b, edge / 2, h - 1))
        assertEquals(0, light(b, edge + 3, h / 2)); assertEquals(0, light(b, w - 1, h / 2))
    }

    @Test fun `a page in its place loses only what the display's own corners hide anyway`() {
        val b = pulled(0f)
        var cut = 0
        for (y in 0 until h) for (x in 0 until w) if (light(b, x, y) < 128) {
            cut++
            val cx = if (x < w / 2) r else w - r; val cy = if (y < h / 2) r else h - r
            assertTrue("($x, $y) is in a corner's square", kotlin.math.abs(x - if (x < w / 2) 0 else w) <= r + 1 && kotlin.math.abs(y - if (y < h / 2) 0 else h) <= r + 1)
            assertTrue("and outside its arc", kotlin.math.hypot((x + 0.5 - cx), (y + 0.5 - cy)) >= r - 1.5)
        }
        // Four corners of r squared times (1 - pi / 4).
        assertEquals(4 * r * r * (1 - Math.PI / 4), cut.toDouble(), 250.0)
    }
}
