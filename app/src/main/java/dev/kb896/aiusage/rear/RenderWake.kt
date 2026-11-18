package dev.kb896.aiusage.rear

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * How a render thread at rest sleeps until something to draw has changed (ShellOverlay): a version every change moves
 * on, and a permit that wakes the sleeper. [changed] from any thread; [await] on the render thread. ONE PER RUN, so a
 * render thread outliving its run never takes the wake meant for the next one's.
 *
 * THE ORDER IS THE POINT: [changed] moves the version BEFORE it gives the permit, and [await] takes away any permit left
 * BEFORE it looks at the version. A change after that either shows in the version (back at once) or leaves its permit
 * for the acquire (back at once), so none is lost; a permit left by a change already drawn wakes nobody. Changes made
 * while nobody sleeps are one wake, not one each.
 *
 * @param permits the wake's semaphore, from outside only for the tests.
 */
internal class RenderWake(private val permits: Semaphore = Semaphore(0)) {
    private val count = AtomicInteger()

    /** The version, read by a frame before it draws: [await] is given the one it drew. */
    val version: Int get() = count.get()

    /** Something to draw changed: a sleeper wakes, the next frame draws it. Any thread. */
    fun changed() {
        count.incrementAndGet()
        if (permits.availablePermits() == 0) permits.release()
    }

    /**
     * Back as soon as anything has [changed] since [drawn] was drawn (at once if it already has); asleep until then.
     * An interrupt ends the wait (InterruptedException).
     */
    fun await(drawn: Int) {
        permits.drainPermits()
        if (count.get() != drawn) return
        permits.acquire()
    }
}
