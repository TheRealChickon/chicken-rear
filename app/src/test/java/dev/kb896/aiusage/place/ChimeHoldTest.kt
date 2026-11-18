package dev.kb896.aiusage.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fence's broadcast is held until the arrival's chime was heard, 3 s at most (SPEC section 5, R18). */
class ChimeHoldTest {
    @Test fun `with no chime playing the broadcast lets go at once`() {
        val p = ArrivalChime.Playing()
        val t0 = System.nanoTime()
        assertTrue(p.await(3_000L))
        assertTrue((System.nanoTime() - t0) / 1_000_000L < 100L)
    }

    @Test fun `a chime playing keeps the broadcast until it is over`() {
        val p = ArrivalChime.Playing()
        p.begin()
        val t0 = System.nanoTime()
        Thread { Thread.sleep(250L); p.end() }.start()
        assertTrue(p.await(3_000L))
        val waited = (System.nanoTime() - t0) / 1_000_000L
        assertTrue("waited $waited ms", waited in 200L..2_000L)
    }

    @Test fun `a chime that does not end is waited for no longer than allowed`() {
        val p = ArrivalChime.Playing()
        p.begin()
        val t0 = System.nanoTime()
        assertFalse(p.await(150L))
        val waited = (System.nanoTime() - t0) / 1_000_000L
        assertTrue("waited $waited ms", waited in 140L..1_000L)
    }

    @Test fun `the wait is counted from the broadcast, never over 3 s, never under none`() {
        assertEquals(3_000L, FenceReceiver.left(1_000L, 1_000L))
        assertEquals(2_200L, FenceReceiver.left(1_000L, 1_800L))
        assertEquals(0L, FenceReceiver.left(1_000L, 9_000L))
        assertTrue("the chime's own wait and its sound fit in the hold", ArrivalChime.READY_MS + ArrivalChime.HEARD_MS < FenceReceiver.HOLD_MS)
    }
}
