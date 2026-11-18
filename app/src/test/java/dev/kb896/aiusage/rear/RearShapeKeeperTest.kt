package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RearShapeKeeperTest {
    private val proMax = RearGeometry(976, 596, 296, 101, 440)

    private class Fake(var stack: Int? = 1, var geometry: RearGeometry? = null) {
        var fail = 0
        val made = ArrayList<Pair<Int, RearGeometry>>()
        var drops = 0
        var stackReads = 0
        /** What is on the back: a make puts its mask in place of the one before, a failed one leaves it, a drop takes it. */
        var shown: Pair<Int, RearGeometry>? = null
        /** Whether a mask was ever taken away while a make that then succeeded was coming (a moment with none). */
        var bareBeforeMake = false
        private var droppedSinceShown = false
        val keeper = RearShape.Keeper(
            backStack = { stackReads++; stack },
            geometry = { geometry },
            make = { s, g ->
                if (fail > 0) { fail--; false } else {
                    if (droppedSinceShown) bareBeforeMake = true
                    made += s to g; shown = s to g; droppedSinceShown = false; true
                }
            },
            drop = { drops++; if (shown != null) droppedSinceShown = true; shown = null },
        )
    }

    @Test fun `made at the first look, then looked at again soon and later seldom`() {
        val f = Fake(geometry = proMax)
        assertEquals(RearShape.LOOK_SOON_MS, f.keeper.step(0L))
        assertEquals(listOf(1 to proMax), f.made)
        // Nothing changed: nothing made again, the stack not read again.
        assertEquals(RearShape.LOOK_SOON_MS, f.keeper.step(3_000L))
        assertEquals(1, f.made.size)
        assertEquals(1, f.stackReads)
        assertEquals(RearShape.LOOK_LATER_MS, f.keeper.step(RearShape.LOOK_SOON_FOR_MS + 1))
        assertEquals(1, f.made.size)
    }

    @Test fun `a failed try is tried again, 1 s then doubling, never given up`() {
        val f = Fake(geometry = proMax).apply { fail = 12 }
        val waits = (0 until 12).map { f.keeper.step(it * 1_000L) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), waits.take(8))
        assertTrue(waits.all { it <= RearShape.RETRY_MAX_MS })
        assertNull(f.keeper.madeFor)
        f.keeper.step(100_000L)
        assertEquals(1 to proMax, f.keeper.madeFor)
        // After it is made, a later failure starts from 1 s again.
        f.geometry = proMax.copy(cornerPx = 90); f.fail = 1
        assertEquals(RearShape.RETRY_FIRST_MS, f.keeper.step(200_000L))
    }

    @Test fun `the back's stack or size not to be had yet, tried again, nothing made meanwhile`() {
        val f = Fake(stack = null, geometry = proMax)
        assertEquals(RearShape.RETRY_FIRST_MS, f.keeper.step(0L))
        f.stack = 1; f.geometry = null
        assertTrue(f.keeper.step(1_000L) <= RearShape.RETRY_MAX_MS)
        assertTrue(f.made.isEmpty())
        f.geometry = proMax
        f.keeper.step(3_000L)
        assertEquals(listOf(1 to proMax), f.made)
    }

    @Test fun `a stand-in size is replaced at the next look once the real one is known`() {
        val f = Fake(geometry = RearGeometry.PRO17)
        val wait = f.keeper.step(0L)
        assertEquals(1 to RearGeometry.PRO17, f.keeper.madeFor)
        assertTrue("looked at again within seconds", wait <= 3_000L)
        f.geometry = proMax
        f.keeper.step(wait)
        assertEquals("made over the one before, not after taking it away", 0, f.drops)
        assertFalse(f.bareBeforeMake)
        assertEquals(1 to proMax, f.keeper.madeFor)
        assertEquals(1 to proMax, f.shown)
    }

    @Test fun `a layer of ours on another stack that is now the back's takes the shape there`() {
        val f = Fake(geometry = proMax)
        f.keeper.step(0L)
        f.stack = 3
        assertTrue(f.keeper.layerOn(3, 1_000L))
        f.keeper.step(1_000L)
        assertEquals(3 to proMax, f.keeper.madeFor)
        assertEquals(0, f.drops)
        assertFalse(f.bareBeforeMake)
        assertEquals(3 to proMax, f.shown)
    }

    @Test fun `a new mask that cannot be made takes the one before away, and is tried again with its wait`() {
        val f = Fake(geometry = RearGeometry.PRO17)
        f.keeper.step(0L)
        f.geometry = proMax; f.fail = 2
        // The one before is the wrong size: not left up while the right one cannot be had (as when it was dropped first).
        assertEquals(RearShape.RETRY_FIRST_MS, f.keeper.step(3_000L))
        assertEquals(1, f.drops)
        assertNull(f.keeper.madeFor)
        assertNull(f.shown)
        assertEquals(2 * RearShape.RETRY_FIRST_MS, f.keeper.step(4_000L))
        assertEquals("nothing more to take away", 1, f.drops)
        f.keeper.step(6_000L)
        assertEquals(1 to proMax, f.shown)
        assertEquals(1 to proMax, f.keeper.madeFor)
    }

    @Test fun `the keeper's own layers are not handed back to it, others' are`() {
        assertFalse("before it runs", RearShape.handOver(1, keeping = false, maskStack = null, byKeeper = false))
        assertFalse("on the mask's own stack", RearShape.handOver(1, keeping = true, maskStack = 1, byKeeper = false))
        assertTrue("another stack", RearShape.handOver(0, keeping = true, maskStack = 1, byKeeper = false))
        assertTrue("before the mask is anywhere", RearShape.handOver(1, keeping = true, maskStack = null, byKeeper = false))
        assertFalse("the mask's own layers as it lays them", RearShape.handOver(1, keeping = true, maskStack = null, byKeeper = true))
        assertFalse("the mask's own layers on a new stack", RearShape.handOver(3, keeping = true, maskStack = 1, byKeeper = true))
    }

    /**
     * The keeper's loop (RearShape.keep) as it runs: a layer handed over cuts the wait short. A try that makes a layer
     * and then fails (one column made, the other refused) keeps its wait, 1 s doubling: before, its own layer was
     * handed over and the next try came at once, again and again while the failure lasted.
     */
    @Test fun `a try that made a layer before it failed still waits before the next`() {
        val pending = ArrayList<Int>()
        var maskStack: Int? = null
        val keeper = RearShape.Keeper(
            backStack = { 1 },
            geometry = { proMax },
            make = { s, _ ->
                // The left column is made (ShellLayer.create tells RearShape.made), then the right one is refused.
                if (RearShape.handOver(s, keeping = true, maskStack = maskStack, byKeeper = true)) pending += s
                false
            },
            drop = { maskStack = null },
        )
        var now = 0L
        val looks = ArrayList<Long>()
        repeat(6) {
            looks += now
            for (s in pending) keeper.layerOn(s, now)
            pending.clear()
            val wait = keeper.step(now)
            // Handed over during the look: the keeper was poked and does not wait.
            now += if (pending.isNotEmpty()) 0L else wait
        }
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L), looks)
    }

    @Test fun `the front's stack is asked about once, not at every layer made there`() {
        val f = Fake(geometry = proMax)
        f.keeper.step(0L)
        val reads = f.stackReads
        assertTrue(f.keeper.layerOn(0, 1_000L))
        f.keeper.step(1_000L)
        assertEquals(reads + 1, f.stackReads)
        assertEquals(1 to proMax, f.keeper.madeFor)
        assertFalse(f.keeper.layerOn(0, 2_000L))
        assertFalse("a layer on the mask's own stack is no news", f.keeper.layerOn(1, 2_000L))
        assertTrue("asked again after a while", f.keeper.layerOn(0, 1_000L + RearShape.NOT_THE_BACK_MS + 1))
    }

    @Test fun `a layer made before anything is known is not lost`() {
        val f = Fake(stack = null, geometry = proMax)
        assertTrue(f.keeper.layerOn(1, 0L))
        f.keeper.step(0L)
        f.stack = 1
        f.keeper.step(1_000L)
        assertEquals(1 to proMax, f.keeper.madeFor)
    }
}
