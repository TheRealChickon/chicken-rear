package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who has the pull with nothing waiting (EmptyPull): the rim's light, the pages, or nobody. */
class EmptyPullTest {
    private fun lit(): EmptyPull = EmptyPull().apply { assertEquals(EmptyPull.Start.RIM, start(count = 0, shadeUp = false, lightOver = true)) }

    @Test fun `nothing waiting lights the rim, something waiting brings the pages`() {
        val p = EmptyPull()
        assertEquals(EmptyPull.Start.PAGES, p.start(count = 2, shadeUp = false, lightOver = true))
        assertFalse(p.up)
        assertEquals(EmptyPull.Start.RIM, p.start(count = 0, shadeUp = false, lightOver = true))
        assertTrue(p.up); assertTrue(p.pulling)
        // The pages already up with none left (the last one went while they were open): theirs, not the light's.
        assertEquals(EmptyPull.Start.PAGES, EmptyPull().start(count = 0, shadeUp = true, lightOver = true))
    }

    @Test fun `one coming under a finger still pulling brings the pages down to it`() {
        val p = lit()
        assertEquals(EmptyPull.Arrival.NONE, p.arrived(0))
        assertEquals(EmptyPull.Arrival.PAGES, p.arrived(1))
        assertFalse(p.up); assertFalse(p.pulling)
        // Its own end then shuts nothing (the pages have the shade).
        assertFalse(p.ended(playing = false))
    }

    @Test fun `one coming after the lift shuts the light, so its pop-up comes`() {
        val p = lit(); p.lifted()
        assertEquals(EmptyPull.Arrival.SHUT, p.arrived(1))
        assertFalse(p.up)
        assertFalse(p.ended(playing = false))
        // Nothing up: a list changing is nothing to it.
        assertEquals(EmptyPull.Arrival.NONE, EmptyPull().arrived(3))
    }

    @Test fun `a pull while the light still runs is nobody's, once it is over it plays again`() {
        val p = lit(); p.lifted()
        assertEquals(EmptyPull.Start.NOTHING, p.start(count = 0, shadeUp = true, lightOver = false))
        assertTrue(p.up); assertFalse(p.pulling)
        assertEquals(EmptyPull.Start.RIM, p.start(count = 0, shadeUp = true, lightOver = true))
        assertTrue(p.up); assertTrue(p.pulling)
    }

    @Test fun `with one waiting by now a pull over the light brings the pages`() {
        val p = lit(); p.lifted()
        assertEquals(EmptyPull.Start.PAGES_FROM_RIM, p.start(count = 1, shadeUp = true, lightOver = false))
        assertFalse(p.up)
    }

    @Test fun `the light gone by itself shuts the shade, unless a newer play runs`() {
        val p = lit(); p.lifted()
        assertFalse(p.ended(playing = true))
        assertTrue(p.up)
        assertTrue(p.ended(playing = false))
        assertFalse(p.up)
        assertFalse(p.ended(playing = false))
    }

    @Test fun `shut from outside and the demo`() {
        val p = lit(); p.shut()
        assertFalse(p.up); assertFalse(p.pulling)
        p.demo()
        assertTrue(p.up); assertFalse(p.pulling)
        assertEquals(EmptyPull.Arrival.SHUT, p.arrived(1))
    }
}
