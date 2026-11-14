package dev.kb896.aiusage.ui.glass

import android.content.res.Configuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** The settings follow the phone's light or dark mode: every token has both, and both read. */
class InkThemeTest {
    @After fun dark() { Ink.dark = true }

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun lum(c: Int) = 0.2126 * channel((c shr 16) and 0xFF) +
        0.7152 * channel((c shr 8) and 0xFF) + 0.0722 * channel(c and 0xFF)

    /** [top] (with its alpha) laid over the opaque [under]. */
    private fun over(top: Int, under: Int): Int {
        val a = (top ushr 24) / 255.0
        fun ch(shift: Int) = Math.round(((top shr shift) and 0xFF) * a + ((under shr shift) and 0xFF) * (1 - a)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun contrast(fg: Int, bg: Int): Double {
        val a = lum(fg); val b = lum(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun neutral(c: Int): Boolean {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b) <= 8
    }

    @Test fun `follow takes the night bit of the configuration`() {
        val config = Configuration()
        config.uiMode = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_NO
        Ink.follow(config)
        assertFalse(Ink.dark)
        assertTrue(Ink.isLight(0f))
        config.uiMode = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES
        Ink.follow(config)
        assertTrue(Ink.dark)
        assertFalse(Ink.isLight(1f))
    }

    @Test fun `the light theme has dark text on a near-white ground`() {
        Ink.dark = false
        assertEquals(Ink.PRIMARY_LIGHT, Ink.primary(0.2f))
        assertEquals(0x99000000.toInt(), Ink.secondary(0.9f))
        assertEquals(Ink.TERTIARY_LIGHT, Ink.tertiary(0f))
        assertEquals(Ink.ACCENT_LIGHT, Ink.accent(0f))
        assertEquals(Ink.ON_LIGHT, Ink.on(0f))
        assertEquals(Backdrop.PAPER, Backdrop.ground())
        assertTrue(lum(Backdrop.PAPER) > 0.85)
        assertTrue((Ink.tertiary(0f) ushr 24) < (Ink.secondary(0f) ushr 24))
    }

    /** Text on a card, over the wallpaper, in each theme: titles and values above 4.5:1, notes and hints above 3. */
    @Test fun `text reads on a card in both themes`() {
        for (dark in booleanArrayOf(true, false)) {
            Ink.dark = dark
            val card = over(Pane.CARD_BODY, Backdrop.ground())
            for (ink in intArrayOf(Ink.primary(0f), Ink.secondary(0f), Ink.accent(0f))) {
                val c = contrast(over(ink, card), card)
                assertTrue("dark=$dark ${Integer.toHexString(ink)}: $c", c >= 4.5)
            }
            val note = contrast(over(Ink.tertiary(0f), card), card)
            assertTrue("dark=$dark note: $note", note >= 4.0)
            // Plain text outside the glass, on the wallpaper itself.
            assertTrue("dark=$dark fg", contrast(Ink.fg(), Backdrop.ground()) >= 4.5)
        }
        Ink.dark = false
        assertTrue("light hint", contrast(Ink.dim(), Backdrop.ground()) >= 3.0)
    }

    /** A row's end icon (white drawables, tinted with Ink.icon) shows on a card in both themes. */
    @Test fun `row icons read on a card in both themes`() {
        for (dark in booleanArrayOf(true, false)) {
            Ink.dark = dark
            val card = over(Pane.CARD_BODY, Backdrop.ground())
            val c = contrast(over(Ink.icon(), card), card)
            assertTrue("dark=$dark icon: $c", c >= 4.5)
        }
        Ink.dark = false
        // the untinted white would not
        assertTrue(contrast(0xFFFFFFFF.toInt(), over(Pane.CARD_BODY, Backdrop.ground())) < 1.5)
    }

    @Test fun `a light card is a pale translucent sheet with a dark hairline`() {
        Ink.dark = false
        val body = Pane.CARD_BODY
        assertTrue((body ushr 24) in 0x80..0xF0)
        assertEquals(0xFFFFFF, body and 0xFFFFFF)
        assertEquals(0, Pane.CARD_EDGE and 0xFFFFFF)
        assertTrue((Pane.CARD_EDGE ushr 24) in 0x08..0x30)
    }

    /** No purple, or any hue, in either theme's accent, switch, track, scrim or wallpaper. */
    @Test fun `every theme token is neutral`() {
        for (dark in booleanArrayOf(true, false)) {
            Ink.dark = dark
            for (c in intArrayOf(Ink.primary(0f), Ink.accent(0f), Ink.on(0f), Ink.track(0f), Ink.fg(), Ink.dim(),
                Ink.scrimColor(0f), Backdrop.ground(), Pane.CARD_BODY, Pane.CARD_EDGE) +
                Backdrop.gradientStops(Backdrop.ground())) {
                assertTrue("dark=$dark ${Integer.toHexString(c)}", neutral(c))
            }
        }
    }

    /** The white thumb shows on a switch that is on, and on and off look different, in both. */
    @Test fun `the switch's on colour stands off the white thumb and the off track`() {
        for (dark in booleanArrayOf(true, false)) {
            Ink.dark = dark
            val card = over(Pane.CARD_BODY, Backdrop.ground())
            assertTrue("dark=$dark thumb on on", contrast(0xFFFFFFFF.toInt(), Ink.on(0f)) >= 2.5)
            assertTrue("dark=$dark on vs off", contrast(Ink.on(0f), over(Ink.track(0f), card)) >= 1.8)
        }
    }

    @Test fun `the light wallpaper stays near-white top to bottom`() {
        val stops = Backdrop.gradientStops(Backdrop.PAPER, dark = false)
        for (c in stops) assertTrue(Integer.toHexString(c), lum(c) > 0.8)
        assertTrue(lum(stops[0]) > lum(stops[1]))
    }
}
