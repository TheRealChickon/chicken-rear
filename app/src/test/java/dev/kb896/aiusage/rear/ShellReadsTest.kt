package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the shell reads less often for the same answer: a display's state shared (DisplayStates), the panel's own
 * level kept two seconds (RearPanel.Kept), the rear's layer stack once per process (RearStack).
 */
class ShellReadsTest {
    private val on = 2
    private val off = 1

    @Test fun `a shared reading is never older than the look allows`() {
        var now = 1_000L
        var state = on
        var reads = 0
        val d = DisplayStates({ reads++; state }, { now })
        assertEquals(on, d.recent(1, 100L))
        assertEquals("nothing kept: read", 1, reads)
        now += 99L
        assertEquals(on, d.recent(1, 100L))
        assertEquals("under the age: the kept one", 1, reads)
        state = off
        now += 1L
        assertEquals("at the age: read again", off, d.recent(1, 100L))
        assertEquals(2, reads)
        state = on
        assertEquals("another display is its own", on, d.recent(0, 100L))
        assertEquals(3, reads)
    }

    @Test fun `every fresh read is the one a look takes`() {
        var now = 0L
        var state = on
        var reads = 0
        val d = DisplayStates({ reads++; state }, { now })
        assertEquals(on, d.fresh(1))
        state = off
        now += 10L
        assertEquals(off, d.fresh(1))
        assertEquals("the newest kept", off, d.recent(1, 100L))
        assertEquals(2, reads)
        now += 50L
        assertEquals(off, d.recent(1, 0L))
        assertEquals("an age of 0 always reads", 3, reads)
    }

    @Test fun `an unreadable display is unknown, as before`() {
        val d = DisplayStates({ null }, { 0L })
        assertEquals(null, d.recent(9, 100L))
        assertEquals(null, d.fresh(9))
    }

    @Test fun `the panel's level is kept two seconds and read again when the light changes`() {
        var reads = 0
        var nits = 35f
        val k = RearPanel.Kept(read = { reads++; nits })
        assertEquals(35f, k.nits(1, 10_000L))
        nits = 2f
        assertEquals(35f, k.nits(1, 11_999L))
        assertEquals(1, reads)
        assertEquals("two seconds on", 2f, k.nits(1, 12_000L))
        nits = 40f
        k.forget()
        assertEquals("the light changed: at once", 40f, k.nits(1, 12_001L))
        assertEquals("another display: read", 40f, k.nits(2, 12_002L))
        assertEquals(4, reads)
        assertEquals(2_000L, RearPanel.KEEP_MS)
        assertTrue("well inside the back's own ease", RearPanel.KEEP_MS <= RearLight.EASE_MS + 500L)
    }

    @Test fun `no level of the panel is not kept`() {
        var reads = 0
        var nits = Float.NaN
        val k = RearPanel.Kept(read = { reads++; nits })
        assertTrue("off: not had", k.nits(1, 20_000L).isNaN())
        nits = 1.5f
        assertEquals("read again at the next look, as before", 1.5f, k.nits(1, 20_500L))
        assertEquals(2, reads)
        assertEquals("a level is kept", 1.5f, k.nits(1, 21_000L))
        assertEquals(2, reads)
        nits = Float.NaN
        k.forget()
        assertTrue(k.nits(1, 21_001L).isNaN())
        nits = 3f
        assertEquals("the NaN was not kept", 3f, k.nits(1, 21_002L))
        assertEquals(4, reads)
    }

    @Test fun `the layer stack is kept once two reads agree, and a dump without it is read again`() {
        RearStack.forget()
        var reads = 0
        var dump = ""
        val exec = { cmd: String -> assertEquals(RearStack.READ, cmd); reads++; dump }
        assertEquals("none found: the display's id, as before", 1, RearStack.of(exec))
        assertEquals(1, reads)
        dump = "  mDisplayId=1\n    mLayerStack=7\n  mDisplayId=0\n    mLayerStack=0"
        assertEquals("found once: taken, not kept yet", 7, RearStack.of(exec))
        assertEquals(2, reads)
        assertEquals("found again: kept", 7, RearStack.of(exec))
        assertEquals(3, reads)
        assertEquals("kept", 7, RearStack.of(exec))
        assertEquals(3, reads)
        RearStack.forget()
        dump = "  mDisplayId=1\n    layerStack 3"
        assertEquals("forgotten: read again", 3, RearStack.of(exec))
        // A dump taken while the displays change: the rear's block without its stack, the next display's taken.
        dump = "  mDisplayId=1\n    mLayerStack=5"
        assertEquals("a read that differs is taken for its layer, and kept nothing", 5, RearStack.of(exec))
        dump = "  mDisplayId=1\n    layerStack 3"
        assertEquals(3, RearStack.of(exec))
        assertEquals("not kept yet: the last two differed", 6, reads)
        assertEquals(3, RearStack.of(exec))
        assertEquals(7, reads)
        assertEquals("kept", 3, RearStack.of(exec))
        assertEquals(7, reads)
        RearStack.forget()
    }
}
