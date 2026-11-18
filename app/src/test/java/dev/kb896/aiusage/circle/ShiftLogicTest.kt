package dev.kb896.aiusage.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ShiftLogicTest {
    private val K = ShiftLogic.K
    /** A long page: text lines (noisy rows) separated by empty rows, some uniform picture bands. */
    private fun page(len: Int, seed: Long): ByteArray {
        val rnd = Random(seed); val f = ByteArray(len * K)
        var r = 0
        while (r < len) {
            val gap = 6 + rnd.nextInt(14)
            for (i in 0 until gap) { if (r >= len) break; for (k in 0 until K) f[r * K + k] = 240.toByte(); r++ }
            val line = 14 + rnd.nextInt(10)
            for (i in 0 until line) { if (r >= len) break; for (k in 0 until K) f[r * K + k] = (60 + rnd.nextInt(180)).toByte(); r++ }
        }
        return f
    }
    /** The screen at scroll position [y]: a fixed bar of [bar] rows on top, the page below it. */
    private fun screen(page: ByteArray, y: Int, h: Int, bar: Int, noise: Int = 0, seed: Long = 1): ShiftLogic.Rows {
        val rnd = Random(seed); val f = ByteArray(h * K)
        for (r in 0 until h) for (k in 0 until K) {
            val v = if (r < bar) (if (r % 7 < 3) 30 + k * 9 else 200) else page[(y + r) * K + k].toInt() and 255
            f[r * K + k] = (v + (if (noise > 0) rnd.nextInt(2 * noise + 1) - noise else 0)).coerceIn(0, 255).toByte()
        }
        return ShiftLogic.Rows(h, f)
    }

    @Test fun findsTheScrollDistance() {
        val p = page(9000, 7)
        for (dy in listOf(1, 37, 240, 900, 1500, -333, -1200)) {
            val a = screen(p, 3000, 2000, 150); val b = screen(p, 3000 + dy, 2000, 150)
            assertEquals("scrolled by $dy", dy, ShiftLogic.shift(a, b))
        }
    }

    @Test fun toleratesSmallPixelNoise() {
        val p = page(9000, 11)
        val a = screen(p, 2000, 2000, 150, noise = 5, seed = 3); val b = screen(p, 2420, 2000, 150, noise = 5, seed = 4)
        assertEquals(420, ShiftLogic.shift(a, b))
    }

    @Test fun aDifferentPageGivesNoAnswer() {
        val a = screen(page(9000, 1), 1000, 2000, 150); val b = screen(page(9000, 2), 1000, 2000, 150)
        val dy = ShiftLogic.shift(a, b)
        // the bar is the same in both: the only acceptable answers are "nothing" or "the bar did not move"
        assertTrue("got $dy", dy == null || dy == 0)
    }

    @Test fun scrolledAlmostAWholeScreenIsRefused() {
        val p = page(9000, 5)
        val a = screen(p, 1000, 2000, 0); val b = screen(p, 2950, 2000, 0)     // 2.5 % overlap
        assertNull(ShiftLogic.shift(a, b))
    }

    @Test fun matchTellsMovedPartsFromFixedParts() {
        val p = page(9000, 9)
        val a = screen(p, 3000, 2000, 150); val b = screen(p, 3400, 2000, 150)
        // the page area moved by 400, the bar did not
        assertTrue(ShiftLogic.match(a, b, 400, 900, 400) > 0.95f)
        assertTrue(ShiftLogic.match(a, b, 400, 900, 0) < 0.3f)
        assertTrue(ShiftLogic.match(a, b, 10, 140, 0) > 0.95f)
        assertTrue(ShiftLogic.match(a, b, 10, 140, 400) < 0.3f)
        // the moved part begins under the bar and ends where the old screen ended
        val reg = ShiftLogic.region(a, b, 400)!!
        assertTrue("region $reg", reg.first in 150..215 && reg.last in 1540..1600)
        // a part that was not on the old screen has nothing to compare with
        assertEquals(-1f, ShiftLogic.match(a, b, 1700, 1900, 400), 0f)
    }
}
