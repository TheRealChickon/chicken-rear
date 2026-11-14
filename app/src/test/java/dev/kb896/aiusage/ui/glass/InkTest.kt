package dev.kb896.aiusage.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkTest {
    /** Text is white whatever is behind it — it no longer flips to black over light cells. */
    @Test fun `ink is white over a light cell and a dark one`() {
        assertEquals(0xFFFFFFFF.toInt(), Ink.primary(0.8f))
        assertEquals(0xFFFFFFFF.toInt(), Ink.primary(0.2f))
        assertEquals(0xFFFFFFFF.toInt(), Ink.primary(0.5f))
    }

    @Test fun `secondary ink is white at sixty percent, light cell or dark`() {
        assertEquals(0x99FFFFFF.toInt(), Ink.secondary(0.9f))
        assertEquals(0x99FFFFFF.toInt(), Ink.secondary(0.1f))
    }

    /** Nothing on the front reacts to the cell behind it: light or dark, the dark background's tokens. */
    @Test fun `the catalog tokens are the dark background's, whatever the cell`() {
        for (cell in floatArrayOf(0.7f, 0.3f)) {
            assertEquals(0xFF9A9AA0.toInt(), Ink.on(cell))
            assertEquals(0xFFECECEF.toInt(), Ink.accent(cell))
            assertEquals(0x73FFFFFF, Ink.tertiary(cell))
            assertEquals(0x5C787880, Ink.track(cell))
            assertEquals(0.56f, Ink.scrimAlpha(cell), 0.001f)
        }
    }

    /** The front is neutral: no hue in the accent or the switch's on colour (W4). */
    @Test fun `the accent and the on colour are grey, not a hue`() {
        for (c in intArrayOf(Ink.ACCENT, Ink.ON)) {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            assertTrue("channels within 8 of each other: ${Integer.toHexString(c)}",
                maxOf(r, g, b) - minOf(r, g, b) <= 8)
        }
        // The white thumb must stand out on a switch that is on.
        assertTrue(((Ink.ON shr 16) and 0xFF) < 0xC0)
    }

    /** A note is quieter than a title, which is quieter than a value. */
    @Test fun `tertiary is dimmer than secondary`() {
        assertTrue((Ink.tertiary(0f) ushr 24) < (Ink.secondary(0f) ushr 24))
    }

    @Test fun `mix moves each channel toward the target by t`() {
        // 0x1D1F21 toward white by 0.08: 0x1D=29 -> 29 + (255-29)*0.08 = 47.08 -> 47; B: 33 + 222*0.08 = 50.76 -> 51
        assertEquals(0xFF2F3133.toInt(), Ink.mix(0xFF1D1F21.toInt(), 0xFFFFFFFF.toInt(), 0.08f))
        // toward black by 0.12: 29 * 0.88 = 25.52 -> 26 (rounded)
        assertEquals(0xFF1A1B1D.toInt(), Ink.mix(0xFF1D1F21.toInt(), 0xFF000000.toInt(), 0.12f))
    }
}
