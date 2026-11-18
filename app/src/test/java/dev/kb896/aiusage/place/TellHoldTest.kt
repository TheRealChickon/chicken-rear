package dev.kb896.aiusage.place

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A place note is told to the back by its pin's animation, never before (the review's blocker: two arrivals on the back). */
class TellHoldTest {
    @Test fun `while a pin plays, the note and what follows it are held, and the pin's tell ends the hold`() {
        val h = TellHold()
        assertTrue("no pin playing: told at once", h.mayTell(1_000L))
        val t = h.hold(1_000L, 6_000L)
        assertFalse("the note itself, kept at the tap", h.mayTell(1_000L))
        assertFalse("its fence set 400 ms later (the mark whole)", h.mayTell(1_400L))
        h.release()   // PinActivity's tell, about 2 s in: it sends what is stored, all of it
        assertTrue(h.mayTell(3_100L))
        assertFalse("nothing is owed after the pin told it", h.ranOut(t, 7_000L))
    }

    @Test fun `a pin that never tells is told when the hold runs out, once`() {
        val h = TellHold()
        val t = h.hold(0L, 6_000L)
        assertFalse(h.mayTell(10L))
        assertFalse("not before its time", h.ranOut(t, 5_999L))
        assertTrue(h.ranOut(t, 6_000L))
        assertFalse("once", h.ranOut(t, 6_100L))
        assertTrue(h.mayTell(6_100L))
    }

    @Test fun `a second pin's hold is not ended by the first one's running out`() {
        val h = TellHold()
        val first = h.hold(0L, 6_000L)
        assertFalse(h.mayTell(100L))
        val second = h.hold(4_000L, 6_000L)
        assertFalse("the first's time is over, the second still plays", h.ranOut(first, 6_000L))
        assertFalse(h.mayTell(6_000L))
        assertTrue(h.ranOut(second, 10_000L))
    }

    @Test fun `nothing written while no pin plays is ever held`() {
        val h = TellHold()
        val t = h.hold(0L, 6_000L)
        h.release()
        assertTrue(h.mayTell(1L))
        assertFalse(h.ranOut(t, 6_000L))
    }
}
