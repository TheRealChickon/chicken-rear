package dev.kb896.aiusage.ui

import dev.kb896.aiusage.rear.PinFace
import dev.kb896.aiusage.ui.PinViewMotion.Spring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** How the front's picture moves: springs, the fit, where it comes from, pinch, pan, double tap, the stroke down. (Robolectric: the back's page's places are PinFace's, which holds paints.) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinViewMotionTest {
    private val vw = 1200f
    private val vh = 2608f

    /** [s] stepped at 120 Hz until [ms]: its values, a frame each. */
    private fun run(s: Spring, ms: Int): List<Float> = (1..ms * 120 / 1000).map { s.step(1f / 120f); s.value }

    @Test fun `the open is short and natural - near its end in about 400 ms, no bounce to be seen, nothing jumps`() {
        val s = Spring(0f, 1f, PinViewMotion.OPEN_DAMPING, PinViewMotion.OPEN_RESPONSE)
        val way = run(s, 700)
        val at400 = way[400 * 120 / 1000 - 1]
        assertTrue("at 400 ms: $at400", at400 > 0.97f)
        assertTrue("overshoot: ${way.max()}", way.max() < 1.005f)
        for (i in 1 until way.size) assertTrue("frame $i", abs(way[i] - way[i - 1]) < 0.12f)
        assertTrue(s.settled(0.001f, 0.05f))
    }

    @Test fun `the close back into the back's place never overshoots it`() {
        val s = Spring(1f, 0f, PinViewMotion.CLOSE_DAMPING, PinViewMotion.CLOSE_RESPONSE)
        val way = run(s, 600)
        assertTrue(way.min() >= -1e-4f)
        assertTrue(way.last() < 0.004f)
    }

    @Test fun `a spring keeps the finger's speed as it is let go, and a long frame does not break it`() {
        val s = Spring(0f, 0f, 1f, 0.4f)
        s.velocity = 3000f
        s.step(1f / 120f)
        assertTrue("it carries on the finger's way", s.value > 20f && s.velocity > 2000f)
        val a = Spring(100f, 0f, 1f, 0.4f); val b = Spring(100f, 0f, 1f, 0.4f)
        a.step(0.05f)
        repeat(6) { b.step(0.05f / 6f) }
        assertEquals("exact: one long frame is six short ones", b.value, a.value, 0.01f)
        val h = Spring(5f); h.velocity = 9f; h.hold(42f)
        h.step(0.1f)
        assertEquals("held where the finger put it", 42f, h.value, 0f)
    }

    @Test fun `the picture fills the screen in its own shape`() {
        val wide = PinViewMotion.fit(2400, 1800, vw, vh)
        assertEquals(0f, wide[0], 0.01f); assertEquals(vw, wide[2], 0.01f)
        assertEquals(900f, wide[3] - wide[1], 0.5f)
        assertEquals(vh / 2f, (wide[1] + wide[3]) / 2f, 0.5f)
        val tall = PinViewMotion.fit(1800, 2400, vw, vh)
        assertEquals(1600f, tall[3] - tall[1], 0.5f); assertEquals(vw, tall[2] - tall[0], 0.5f)
        // A back copy only (a picture from before the front's copies): fitted all the same, larger.
        val small = PinViewMotion.fit(478, 359, vw, vh)
        assertEquals(vw, small[2] - small[0], 0.5f)
    }

    @Test fun `it comes from where the back's page has it, as the pin's card stands on the front`() {
        val place = PinFace.picturePlace(478, 359)
        val o = PinViewMotion.origin(vw, vh, 0, place, 2400, 1800)
        val cx = (o[0] + o[2]) / 2f; val cy = (o[1] + o[3]) / 2f
        // In the card (x 135 to 1085, y 92 to 672 on Xiaomi's 1200 x 2608 front), the picture's own place in it.
        assertTrue("x $cx", cx in 135f..1085f); assertTrue("y $cy", cy in 92f..672f)
        val k = 950f / 976f
        assertEquals((place[2] - place[0]) * k, o[2] - o[0], 0.5f)
        assertEquals((o[2] - o[0]) * 1800f / 2400f, o[3] - o[1], 0.5f)
        assertEquals(PinFace.PIC_CORNER * k, o[4], 0.01f)
        assertTrue("smaller than the screen's fit", o[2] - o[0] < vw)
        // Turned: from the phone's top, wherever that is on the screen now, upright in its own shape.
        val left = PinViewMotion.origin(vh, vw, 1, place, 2400, 1800)
        assertTrue("the top to the left", (left[0] + left[2]) / 2f < vh * 0.3f)
        assertEquals((o[2] - o[0]) / (o[3] - o[1]), (left[2] - left[0]) / (left[3] - left[1]), 0.01f)
        val right = PinViewMotion.origin(vh, vw, 3, place, 2400, 1800)
        assertTrue("the top to the right", (right[0] + right[2]) / 2f > vh * 0.7f)
        val down = PinViewMotion.origin(vw, vh, 2, place, 2400, 1800)
        assertTrue("upside down: from the bottom", (down[1] + down[3]) / 2f > vh * 0.7f)
    }

    @Test fun `past an edge it gives less and less, never as far as the finger`() {
        var last = 0f
        for (over in listOf(10f, 50f, 200f, 800f, 3000f)) {
            val g = PinViewMotion.rubber(over, 1200f)
            assertTrue(g > last && g < over && g < 1200f)
            last = g
        }
        assertEquals(-PinViewMotion.rubber(100f, 500f), PinViewMotion.rubber(-100f, 500f), 0f)
        assertEquals(30f, PinViewMotion.held(30f, 100f, 1200f), 0f)
        assertTrue(PinViewMotion.held(160f, 100f, 1200f) in 100f..160f)
        assertTrue(PinViewMotion.held(-160f, 100f, 1200f) in -160f..-100f)
    }

    @Test fun `caught in its give, it is taken on from there - the give undone gives the same place back`() {
        for (over in listOf(-900f, -120f, -3f, 0f, 3f, 120f, 900f)) {
            val shown = PinViewMotion.held(100f + over, 100f, 1200f)
            assertEquals("pan $over", shown, PinViewMotion.held(PinViewMotion.unheld(shown, 100f, 1200f), 100f, 1200f), 0.01f)
        }
        assertEquals("within the edges, as it is", 42f, PinViewMotion.unheld(42f, 100f, 1200f), 0f)
        for (z in listOf(0.2f, 0.5f, 0.7f, 0.95f, 1f, 2f, 3f, 3.4f, 5f)) {
            val shown = PinViewMotion.pinchHeld(z, 3f)
            assertEquals("zoom $z", shown, PinViewMotion.pinchHeld(PinViewMotion.pinchUnheld(shown, 3f), 3f), 0.0005f)
        }
        assertEquals(2f, PinViewMotion.pinchUnheld(2f, 3f), 0f)
    }

    @Test fun `zoomed in it moves as far as its edges, at the fit not at all`() {
        assertEquals(0f to 0f, PinViewMotion.panLimit(1200f, 900f, 1f, vw, vh))
        val (lx, ly) = PinViewMotion.panLimit(1200f, 900f, 3f, vw, vh)
        assertEquals(1200f, lx, 0.01f); assertEquals(46f, ly, 0.01f)
        assertEquals("a glide stops at the edge", lx, PinViewMotion.glide(1000f, 5000f, lx), 0f)
        assertEquals(100f + 1000f * PinViewMotion.GLIDE_SECONDS, PinViewMotion.glide(100f, 1000f, lx), 0.01f)
    }

    @Test fun `a pinch zooms about the fingers, gives past its ends, and far under the fit closes`() {
        // The point under the fingers stays there.
        val pan = 40f; val focus = 300f
        val q = (focus - pan) / 1.5f
        val after = PinViewMotion.panAbout(pan, focus, 1.5f, 3f)
        assertEquals(focus, after + q * 3f, 0.01f)
        assertEquals(1f, PinViewMotion.pinchHeld(1f, 3f), 0f)
        assertTrue(PinViewMotion.pinchHeld(0.6f, 3f) in PinViewMotion.PINCH_LEAST..1f)
        assertTrue(PinViewMotion.pinchHeld(0.6f, 3f) > 0.6f)
        assertTrue(PinViewMotion.pinchHeld(5f, 3f) in 3f..5f)
        assertTrue(PinViewMotion.pinchHeld(0.01f, 3f) >= PinViewMotion.PINCH_LEAST)
        assertTrue(PinViewMotion.PINCH_CLOSE < 1f)
        assertEquals(3f, PinViewMotion.maxZoom(0.4f), 0f)
        assertEquals(4f, PinViewMotion.maxZoom(2f), 0f)
    }

    @Test fun `a double tap goes in - to fill where that is a real step, at least twice - and back out`() {
        // A wide picture on a tall screen: in to fill its height.
        val fillWide = PinViewMotion.doubleTapZoom(1f, 1200f, 900f, vw, vh, 4f)
        assertEquals(vh / 900f, fillWide, 0.01f)
        // A tall one that nearly fills it already: twice and a half.
        assertEquals(2.5f, PinViewMotion.doubleTapZoom(1f, 1200f, 2500f, vw, vh, 4f), 0f)
        // Never past the most.
        assertEquals(2f, PinViewMotion.doubleTapZoom(1f, 1200f, 900f, vw, vh, 2f), 0f)
        // In already: out to the fit.
        assertEquals(1f, PinViewMotion.doubleTapZoom(2.5f, 1200f, 900f, vw, vh, 4f), 0f)
    }

    @Test fun `a stroke down closes it past the pages' line or quickly, and not upwards or short and slow`() {
        assertTrue(PinViewMotion.dismisses(vw * 0.16f, 0f, vw))
        assertFalse(PinViewMotion.dismisses(vw * 0.1f, 0f, vw))
        assertTrue("quick", PinViewMotion.dismisses(vw * 0.05f, vw * 0.7f, vw))
        assertFalse("quick but hardly moved", PinViewMotion.dismisses(vw * 0.02f, vw * 2f, vw))
        assertFalse("up", PinViewMotion.dismisses(-vw * 0.5f, -vw, vw))
        assertFalse("far, but thrown back up", PinViewMotion.dismisses(vw * 0.2f, -vw, vw))
        // On the way down it is smaller and the black lighter, smoothly; no change sideways or up.
        assertEquals(0f, PinViewMotion.dragPart(-300f, vh), 0f)
        assertEquals(1f, PinViewMotion.dragPart(vh, vh), 0f)
        assertEquals(1f - PinViewMotion.DRAG_SHRINK, PinViewMotion.dragScale(1f), 0f)
        assertEquals(1f, PinViewMotion.dragScale(0f), 0f)
    }
}
