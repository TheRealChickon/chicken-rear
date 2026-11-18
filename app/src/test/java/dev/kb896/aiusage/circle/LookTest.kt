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
    @Test fun sampledColorComesFromGlyphCoresAlongAGradient() {
        val ink = 0xff286caa.toInt()
        val pixels = IntArray(100)
        val distances = IntArray(pixels.size)
        for (i in pixels.indices) {
            val bg = 236 - i / 4
            val ground = (0xff shl 24) or (bg shl 16) or (bg shl 8) or bg
            pixels[i] = when {
                i == 3 -> 0xff101010.toInt() // isolated high-contrast pixel
                i % 4 == 0 -> ink
                i % 4 == 1 -> 0xff9eacbd.toInt() // antialiased edge
                else -> ground
            }
            distances[i] = Look.colorDistance(pixels[i], ground)
        }
        assertEquals(ink, Look.inkColor(pixels, distances))
    }

    @Test fun modestContrastDoesNotBecomeBlackOrWhite() {
        val ink = 0xff494949.toInt()
        val ground = 0xff555555.toInt()
        val pixels = IntArray(24) { if (it % 3 == 0) ink else ground }
        assertEquals(ink, Look.inkColor(pixels, IntArray(pixels.size) { Look.colorDistance(pixels[it], ground) }))
    }

    @Test fun fontSizeUsesXHeightWhenItAgreesWithTheLine() {
        assertEquals(41f, Look.fontSize(32f, 80f, 21f, 50f), 0.01f)
        assertEquals(40f, Look.fontSize(32f, 80f, 11f, 50f), 0.01f)
    }

    @Test fun fittingKeepsOriginalSizeOrShrinksOnlyAsMuchAsNeeded() {
        assertEquals(1f, Look.fitScale { it * 180 <= 200 }, 0f)
        assertEquals(0.8f, Look.fitScale { it * 250 <= 200 }, 0.002f)
        assertEquals(0.6f, Look.fitScale { it * 400 <= 200 }, 0f)
    }

    @Test fun letterSpacingHasBoundsAndNeedsEnoughGlyphs() {
        assertEquals(0.025f, Look.letterSpacing(105f, 100f, 20f, 10), 0.0001f)
        assertEquals(-0.035f, Look.letterSpacing(50f, 100f, 20f, 10), 0f)
        assertEquals(0f, Look.letterSpacing(105f, 100f, 20f, 2), 0f)
    }

    @Test fun smallContentChangesAreNotLostInAFullScreenAverage() {
        val w = 144
        val h = 312
        val before = IntArray(w * h) { 0xfffaf8f2.toInt() }
        val after = before.copyOf()
        val ink = 0xff28465c.toInt()
        for (x in 30..36) after[20 * w + x] = ink
        assertTrue(!Look.sameFrame(before, after, w, h, 16, 10, 5))
    }

    @Test fun statusNavigationAndIsolatedNoiseLeaveTheFrameUnchanged() {
        val w = 144
        val h = 312
        val before = IntArray(w * h) { 0xfffaf8f2.toInt() }
        val after = before.copyOf()
        for (x in 0 until w) {
            after[4 * w + x] = 0
            after[(h - 2) * w + x] = 0
        }
        for (y in 0 until h) after[y * w + w - 1] = 0
        after[50 * w + 50] = 0
        assertTrue(Look.sameFrame(before, after, w, h, 16, 10, 5))
    }

    @Test fun aGentleGradientDoesNotBecomeASolidPatch() {
        val w = 100
        val h = 30
        val ground = 0xffc6e9e2.toInt()
        val flat = IntArray(w * h) { ground }
        for (y in 5..24) for (x in 20..66 step 3) flat[y * w + x] = 0xff164742.toInt()
        val gradient = IntArray(flat.size) { i -> if (flat[i] == ground) ground + (i % w / 8 shl 16) else flat[i] }
        assertTrue(Look.evenBackground(flat, w, h))
        assertTrue(!Look.evenBackground(gradient, w, h))
    }
}
