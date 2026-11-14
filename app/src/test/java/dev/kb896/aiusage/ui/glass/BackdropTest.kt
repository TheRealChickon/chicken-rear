package dev.kb896.aiusage.ui.glass

import dev.kb896.aiusage.rear.GlassLuminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackdropTest {
    @Test fun `the gradient lifts the colour a little toward white at the top and drops it toward black at the bottom`() {
        val stops = Backdrop.gradientStops(0xFF1D1F21.toInt())
        assertEquals(Ink.mix(0xFF1D1F21.toInt(), 0xFFFFFFFF.toInt(), 0.03f), stops[0])
        assertEquals(Ink.mix(0xFF1D1F21.toInt(), 0xFF000000.toInt(), 0.25f), stops[1])
    }

    @Test fun `paint makes a window-sized bitmap`() {
        val out = Backdrop.paint(120, 300)
        assertEquals(120, out.width); assertEquals(300, out.height)
    }

    /**
     * W4: the wallpaper is a still near-black with no hue, not the purple silk. The stops
     * are what paint() draws (Robolectric's canvas does not shade a gradient, so the
     * colours are checked where they are made).
     */
    @Test fun `the wallpaper is near-black and neutral, top to bottom`() {
        for (c in Backdrop.gradientStops(Backdrop.GRAPHITE).toList() + Backdrop.GRAPHITE) {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            assertEquals(0xFF, (c ushr 24))
            assertTrue("dark: ${Integer.toHexString(c)}", maxOf(r, g, b) <= 0x18)
            assertTrue("no hue: ${Integer.toHexString(c)}", maxOf(r, g, b) - minOf(r, g, b) <= 4)
        }
        assertTrue(GlassLuminance.of(Backdrop.GRAPHITE) < 0.1f)
    }

    @Test fun `a missing image file decodes to nothing rather than throwing`() {
        assertNull(Backdrop.decodeNear("/definitely/not/here.png", 100, 100))
    }

    @Test fun `luminance is zero and safe before anything is built`() {
        assertEquals(0f, Backdrop.luminance(0f, 0f, 10f, 10f), 0f)
    }
}
