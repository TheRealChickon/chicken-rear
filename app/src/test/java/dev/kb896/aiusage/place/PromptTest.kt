package dev.kb896.aiusage.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTest {
    @Test fun `the sheet stays on the screen and leaves its top third free`() {
        for ((w, h) in listOf(400f to 869f, 360f to 640f, 600f to 1000f, 300f to 400f)) {
            val b = Prompt.sheet(w, h)
            assertTrue("$w x $h: $b", b.left >= Prompt.MARGIN - 0.01f && b.left + b.width <= w - Prompt.MARGIN + 0.01f)
            assertTrue("$w x $h: $b", b.top >= h / 3f - 0.01f && b.top + b.height <= h - Prompt.MARGIN + 0.01f)
        }
        val phone = Prompt.sheet(400f, 869f)
        assertEquals(384f, phone.width); assertEquals(560f, phone.height)
        assertEquals(8f, phone.left); assertEquals(869f - 8f - 560f, phone.top)
    }

    @Test fun `the name is the search result's while he stays within the zone, else none`() {
        assertEquals("Gym One", Prompt.name("Gym One", 48.0, 11.0, 48.0005, 11.0, 150))
        assertNull("moved 220 m away", Prompt.name("Gym One", 48.0, 11.0, 48.002, 11.0, 150))
        assertEquals("a bigger zone keeps it", "Gym One", Prompt.name("Gym One", 48.0, 11.0, 48.002, 11.0, 300))
        assertNull(Prompt.name(null, 48.0, 11.0, 48.0, 11.0, 150))
        assertNull(Prompt.name("  ", 48.0, 11.0, 48.0, 11.0, 150))
    }

    @Test fun `the slider's steps are the zones`() {
        assertEquals(1, Prompt.step(150))
        assertEquals(150, Prompt.zoneAt(1))
        assertEquals(2000, Prompt.zoneAt(99))
        assertEquals(100, Prompt.zoneAt(-1))
    }

    @Test fun `the zoom that brings the ring back to its resting size`() {
        // Drawn twice as large as it rests: one zoom level out.
        assertEquals(14.0, Prompt.zoomFor(15.0, 132f * 3f, 3f), 1e-6)
        assertEquals(16.0, Prompt.zoomFor(15.0, 33f * 3f, 3f), 1e-6)
        assertEquals(15.0, Prompt.zoomFor(15.0, 0f, 3f), 0.0)
    }
}
