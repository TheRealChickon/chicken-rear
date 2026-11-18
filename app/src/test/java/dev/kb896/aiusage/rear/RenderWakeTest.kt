package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Semaphore

/**
 * The render thread's sleep at rest (RenderWake, ShellOverlay): no change is ever lost, whenever it comes, and a change
 * already drawn wakes nobody.
 */
class RenderWakeTest {

    /**
     * A semaphore that says what [RenderWake.await] would do, without waiting: [slept] when it reached the acquire with
     * no permit. [afterDrain] and [beforeAcquire] put a change exactly between its steps.
     */
    private class Probe : Semaphore(0) {
        var afterDrain: () -> Unit = {}
        var beforeAcquire: () -> Unit = {}
        var slept = false
        override fun drainPermits(): Int = super.drainPermits().also { afterDrain() }
        override fun acquire() {
            beforeAcquire()
            if (availablePermits() == 0) { slept = true; return }
            super.acquire()
        }
    }

    @Test fun `a change before the wait - back at once`() {
        val probe = Probe(); val wake = RenderWake(probe)
        val drawn = wake.version
        wake.changed()
        wake.await(drawn)
        assertFalse(probe.slept)
    }

    @Test fun `nothing changed since the frame - it sleeps, also with a permit left by a change already drawn`() {
        val probe = Probe(); val wake = RenderWake(probe)
        wake.changed()
        val drawn = wake.version
        assertEquals(1, probe.availablePermits())
        wake.await(drawn)
        assertTrue(probe.slept)
    }

    @Test fun `a change between the drain and the look at the version - back at once`() {
        val probe = Probe(); val wake = RenderWake(probe)
        val drawn = wake.version
        probe.afterDrain = { wake.changed() }
        wake.await(drawn)
        assertFalse(probe.slept)
    }

    @Test fun `a change between the look at the version and the acquire - its permit wakes it`() {
        val probe = Probe(); val wake = RenderWake(probe)
        val drawn = wake.version
        probe.beforeAcquire = { wake.changed() }
        wake.await(drawn)
        assertFalse(probe.slept)
        assertEquals(0, probe.availablePermits())
    }

    @Test fun `two changes are one wake`() {
        val probe = Probe(); val wake = RenderWake(probe)
        val before = wake.version
        wake.changed(); wake.changed()
        assertEquals(1, probe.availablePermits())
        wake.await(before)
        assertFalse(probe.slept)
        // Both drawn by the next frame: no second wake for the second change.
        wake.await(wake.version)
        assertTrue(probe.slept)
    }

    @Test fun `a change from another thread wakes a sleeping render thread`() {
        val wake = RenderWake()
        var woke = false
        val t = Thread { wake.await(wake.version); woke = true }
        t.start()
        waitUntilWaiting(t)
        wake.changed()
        t.join(2_000)
        assertFalse(t.isAlive)
        assertTrue(woke)
    }

    @Test fun `an interrupt ends the wait`() {
        val wake = RenderWake()
        var ended: Throwable? = null
        val t = Thread { try { wake.await(wake.version) } catch (e: InterruptedException) { ended = e } }
        t.start()
        waitUntilWaiting(t)
        t.interrupt()
        t.join(2_000)
        assertFalse(t.isAlive)
        assertTrue(ended is InterruptedException)
    }

    /** Until [t] sleeps in the acquire (two seconds at most). */
    private fun waitUntilWaiting(t: Thread) {
        val until = System.nanoTime() + 2_000_000_000L
        while (t.state != Thread.State.WAITING && System.nanoTime() < until) Thread.sleep(1)
        assertEquals(Thread.State.WAITING, t.state)
    }
}
