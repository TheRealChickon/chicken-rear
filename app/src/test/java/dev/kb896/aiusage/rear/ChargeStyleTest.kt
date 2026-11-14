package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.ChargeAnimStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The charging animation's geometry and timing on the 17 Pro Max's rear, 976 x 596 with a 296 px island. */
class ChargeStyleTest {

    private val safe = ChargeStyle.safeArea(976, 596, 296)

    @Test fun `the safe area is right of the camera island, 296 to 976`() {
        assertEquals(296f, safe.left, 0f)
        assertEquals(976f, safe.right, 0f)
        assertEquals(0f, safe.top, 0f)
        assertEquals(596f, safe.bottom, 0f)
    }

    @Test fun `every style, full and compact, stays inside the safe area`() {
        for (style in ChargeAnimStyle.entries) for (compact in listOf(false, true)) {
            val b = ChargeStyle.body(style, safe, compact)
            val what = "$style${if (compact) " compact" else ""}: $b"
            assertTrue("under the lenses — $what", b.left >= 296f)
            assertTrue("off the right edge — $what", b.right <= 976f)
            assertTrue("off the top — $what", b.top >= 0f)
            assertTrue("off the bottom — $what", b.bottom <= 596f)
            assertTrue("no room to draw — $what", b.width > 0f && b.height > 0f)
            // The compact strip carries no figure (ShellCharge draws none over a guest).
            if (!compact) {
                val (fx, fy) = ChargeStyle.figureCentre(style, safe, b)
                assertTrue("the figure stands under the lenses — $what", fx in 296f..976f && fy in 0f..596f)
            }
        }
    }

    @Test fun `the fill height follows the level`() {
        val b = ChargeStyle.body(ChargeAnimStyle.LIQUID, safe, compact = false)
        assertEquals("empty is the bottom", b.bottom, ChargeStyle.fillTop(b, 0f), 0.001f)
        assertEquals("full is the top", b.top, ChargeStyle.fillTop(b, 1f), 0.001f)
        assertEquals("70% is 70% of the glass up", b.bottom - b.height * 0.7f, ChargeStyle.fillTop(b, 0.7f), 0.001f)
        assertEquals("past full is full", b.top, ChargeStyle.fillTop(b, 1.3f), 0.001f)
    }

    @Test fun `filled over time to the battery's level, and no further`() {
        val t = ChargeStyle.FULL
        assertEquals(0f, ChargeStyle.fill(0f, 0.63f, t), 0f)
        assertEquals(0.63f, ChargeStyle.fill(t.fillMs, 0.63f, t), 0.0001f)
        assertEquals(0.63f, ChargeStyle.fill(t.totalMs, 0.63f, t), 0.0001f)
        var last = -1f
        var ms = 0f
        while (ms <= t.fillMs) {
            val f = ChargeStyle.fill(ms, 0.63f, t)
            assertTrue("the fill went down at $ms", f >= last - 0.0001f)
            last = f
            ms += 50f
        }
    }

    @Test fun `full is ChargeOverlay's five seconds, compact two`() {
        assertEquals(ChargeOverlay.TOTAL_MS, ChargeStyle.FULL.totalMs, 0f)
        assertEquals(ChargeOverlay.FILL_MS, ChargeStyle.FULL.fillMs, 0f)
        assertEquals(2_000f, ChargeStyle.COMPACT.totalMs, 0f)
        assertTrue(ChargeStyle.COMPACT.fillMs < ChargeStyle.COMPACT.totalMs)
        assertTrue(ChargeStyle.done(2_000f, ChargeStyle.timing(compact = true)))
        assertFalse(ChargeStyle.done(2_000f, ChargeStyle.timing(compact = false)))
    }

    @Test fun `the compact one is a thin strip down the right edge, the same for every style`() {
        val strip = ChargeStyle.body(ChargeAnimStyle.LIQUID, safe, compact = true)
        for (style in ChargeAnimStyle.entries) assertEquals(strip, ChargeStyle.body(style, safe, compact = true))
        assertEquals(ChargeStyle.STRIP_W, strip.width, 0.001f)
        assertEquals(976f - ChargeStyle.MARGIN, strip.right, 0.001f)
        assertTrue("it reaches over the app's middle", strip.left > 976f - 100f)
        assertTrue("it is not down the whole edge", strip.height > 596f * 0.8f)
    }

    @Test fun `it comes in, holds and goes, and is nothing outside its time`() {
        for (t in listOf(ChargeStyle.FULL, ChargeStyle.COMPACT)) {
            assertEquals(0f, ChargeStyle.alpha(0f, t), 0f)
            assertEquals(1f, ChargeStyle.alpha(t.inMs, t), 0.0001f)
            assertEquals(1f, ChargeStyle.alpha(t.totalMs - t.outMs - 1f, t), 0.0001f)
            assertEquals(0f, ChargeStyle.alpha(t.totalMs, t), 0f)
        }
    }

    @Test fun `the ring sweeps with the level, and the bar lights with it`() {
        assertEquals(0f, ChargeStyle.ringSweep(0f), 0f)
        assertEquals(180f, ChargeStyle.ringSweep(0.5f), 0.001f)
        assertEquals(360f, ChargeStyle.ringSweep(1.2f), 0.001f)
        val bar = ChargeStyle.body(ChargeAnimStyle.MINIMAL, safe, compact = false)
        assertEquals(bar.left, ChargeStyle.barRight(bar, 0f), 0.001f)
        assertEquals(bar.right, ChargeStyle.barRight(bar, 1f), 0.001f)
    }

    @Test fun `the ring is round and in the middle of the safe area`() {
        val ring = ChargeStyle.body(ChargeAnimStyle.RING, safe, compact = false)
        assertEquals(ring.width, ring.height, 0.001f)
        assertEquals(safe.centreX, ring.centreX, 0.001f)
        assertEquals(safe.centreY, ring.centreY, 0.001f)
    }

    @Test fun `unplugged, it drains and fades to nothing in 400 ms`() {
        assertEquals(0.8f, ChargeStyle.drainFill(0.8f, 0f), 0.0001f)
        assertEquals(0f, ChargeStyle.drainFill(0.8f, ChargeStyle.DRAIN_MS), 0.0001f)
        assertEquals(1f, ChargeStyle.drainAlpha(1f, 0f), 0.0001f)
        assertEquals(0.5f, ChargeStyle.drainAlpha(1f, 200f), 0.0001f)
        assertEquals(0f, ChargeStyle.drainAlpha(1f, ChargeStyle.DRAIN_MS), 0.0001f)
        assertFalse(ChargeStyle.drained(399f))
        assertTrue(ChargeStyle.drained(400f))
    }

    @Test fun `no camera island, the whole width is safe`() {
        val all = ChargeStyle.safeArea(904, 572, 0)
        assertEquals(0f, all.left, 0f)
        assertEquals(904f, all.right, 0f)
    }
}
