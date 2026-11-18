package dev.kb896.aiusage.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    /** Upright strokes of [stroke] px every [pitch] px, with a bar across the top every third stroke. */
    private fun strokes(w: Int, h: Int, stroke: Int, pitch: Int, phase: Int = 0): BooleanArray {
        val m = BooleanArray(w * h)
        var x = 3 + phase; var n = 0
        while (x + stroke < w) {
            for (y in 0 until h) for (i in 0 until stroke) m[y * w + x + i] = true
            if (n % 3 == 0) for (y in h / 5 until h / 5 + stroke) for (i in 0 until pitch - 2) if (x + i < w) m[y * w + x + i] = true
            x += pitch; n++
        }
        return m
    }

    @Test fun strokeWidthIsMeasured() {
        for (s in listOf(2, 3, 5, 8)) {
            val ink = Look.measure(strokes(400, 40, s, 22), 400, 40)
            assertNotNull(ink); assertEquals("stroke $s", s.toFloat(), ink!!.stem, 0.5f)
        }
    }

    @Test fun weightLiesBetweenTheDrawnOnes() {
        val drawn = listOf(2.0f, 3.0f, 3.6f, 4.2f, 5.0f, 5.8f)       // 300 .. 800
        assertEquals(300, Look.weight(1.2f, drawn))
        assertEquals(400, Look.weight(3.05f, drawn))
        assertEquals(500, Look.weight(3.5f, drawn))
        assertEquals(700, Look.weight(5.0f, drawn))
        assertEquals(800, Look.weight(9f, drawn))
        // between 400 and 500, nearer to 400
        assertEquals(400, Look.weight(3.2f, drawn))
        // a typeface without light and medium draws 300, 400 and 500 alike: the answer is regular, not the first of them
        val two = listOf(3.0f, 3.0f, 3.0f, 5.0f, 5.0f, 5.0f)
        assertEquals(400, Look.weight(2.9f, two))
        assertEquals(400, Look.weight(3.3f, two))
        assertEquals(600, Look.weight(4.9f, two))
        // thinner than regular without being as thin as light: regular
        assertEquals(400, Look.weight(2.4f, drawn))
    }

    @Test fun anotherKindNeedsToFitClearlyBetter() {
        val seen = Look.measure(strokes(400, 40, 3, 22), 400, 40)!!
        val same = Look.measure(strokes(400, 40, 3, 22), 400, 40)!!
        val other = Look.measure(strokes(400, 40, 3, 31, phase = 9), 400, 40)
        assertEquals(0, Look.family(seen, listOf(same, other, null)))
        assertEquals(1, Look.family(seen, listOf(other, same, null)))
        // a candidate that fits clearly worse than the line's own typeface
        var off: Look.Ink? = null
        var t = 0.05f
        while (t < 0.95f && off == null) {
            val cand = Look.Ink(other!!.stem, FloatArray(Look.BINS) { i -> same.profile[i] * (1 - t) + other.profile[i] * t }, same.aspect, same.height)
            if (1f - Look.correlation(seen.profile, cand.profile) in 0.25f..0.6f) off = cand
            t += 0.02f
        }
        assertNotNull(off)
        // the better fit is taken when the words are as wide as it draws them, and not when they would be a fifth wider
        val wide = Look.Ink(same.stem, same.profile, same.aspect * 1.2f, same.height)
        assertEquals(1, Look.family(seen, listOf(off, same, null)))
        assertEquals(0, Look.family(seen, listOf(off, wide, null)))
        // nothing fits well (a misread word shifts everything): the default stays, however the candidates compare
        assertEquals(0, Look.family(seen, listOf(other, off, null)))
        // nearly the same fit: the default stays
        assertEquals(0, Look.family(seen, listOf(same, same, same)))
        assertTrue(Look.correlation(seen.profile, same.profile) > 0.99f)
    }
}
