package dev.kb896.aiusage.rear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class BlurLevelsTest {
    private fun picture(w: Int, h: Int, at: (Int, Int) -> Int): ByteArray = ByteArray(w * h * 4).also { b ->
        for (y in 0 until h) for (x in 0 until w) { val v = at(x, y); val i = (y * w + x) * 4; b[i] = v.toByte(); b[i + 1] = v.toByte(); b[i + 2] = v.toByte(); b[i + 3] = 255.toByte() }
    }
    private fun px(b: ByteArray, w: Int, x: Int, y: Int) = b[(y * w + x) * 4].toInt() and 0xFF

    @Test fun `seven levels, the first the picture itself, each the same size`() {
        val p = picture(97, 61) { x, y -> (x * 7 + y * 3) % 256 }
        val lv = BlurLevels.levels(p, 97, 61)
        assertEquals(7, lv.size)
        assertArrayEquals(p, lv[0])
        assertTrue(lv.all { it.size == p.size })
    }

    @Test fun `an even picture stays even to its edges (the edge carried outwards, no dark border)`() {
        val p = picture(120, 80) { _, _ -> 200 }
        for (l in BlurLevels.levels(p, 120, 80)) for (y in listOf(0, 40, 79)) for (x in listOf(0, 60, 119)) assertEquals(200, px(l, 120, x, y))
    }

    @Test fun `a line spreads as a Gaussian of half the radius`() {
        val w = 200; val h = 9
        val p = picture(w, h) { x, _ -> if (x == 100) 255 else 0 }
        // Radius 8: sigma 4. The light across is that of a Gaussian of sigma 4 (three boxes are close to one).
        val b = BlurLevels.blurred(p, w, h, 4f)
        var sum = 0.0; var var2 = 0.0
        for (x in 0 until w) { val v = px(b, w, x, 4).toDouble(); sum += v; var2 += v * (x - 100) * (x - 100) }
        assertEquals("nothing lost", 255.0, sum, 12.0)
        assertEquals("its width", 4.0, sqrt(var2 / sum), 0.5)
        val peak = 255.0 / (4.0 * sqrt(2 * Math.PI))
        assertEquals(peak, px(b, w, 100, 4).toDouble(), peak * 0.2)
        assertTrue("falls off", px(b, w, 112, 4) < peak * exp(-9.0 / 2) * 3 + 2)
    }

    @Test fun `a wide blur is made smaller first and is still smooth and in place`() {
        val w = 300; val h = 40
        val p = picture(w, h) { x, _ -> if (x < 150) 0 else 255 }
        val b = BlurLevels.blurred(p, w, h, 40f)
        assertEquals("the middle of the step stays in the middle", 128.0, px(b, w, 150, 20).toDouble(), 20.0)
        var last = -1
        for (x in 0 until w) { val v = px(b, w, x, 20); assertTrue("it only rises at $x", v >= last - 1); last = v }
        assertTrue(px(b, w, 100, 20) in 20..110)
        for (x in 0 until w - 1) assertTrue("no step at $x", abs(px(b, w, x + 1, 20) - px(b, w, x, 20)) <= 6)
    }
}
