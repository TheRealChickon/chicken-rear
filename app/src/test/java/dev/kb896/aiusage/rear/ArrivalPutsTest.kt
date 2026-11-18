package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.Pin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalPutsTest {
    private val a = Pin("towels", 1, 48.0, 11.0, radius = 150, arrivedAt = 100)
    private val b = Pin("shoes", 2, 48.0, 11.0, radius = 150, arrivedAt = 100)
    private val waiting = Pin("milk", 3, 49.0, 11.0)

    @Test fun `what he put away stays away and is told again until the app has taken it`() {
        val p = ArrivalPuts()
        assertEquals(listOf(b, a), p.visible(listOf(a, b, waiting)))
        p.bin(a)
        // The app was frozen: the next payload still carries the arrival.
        assertEquals(listOf(b), p.visible(listOf(a, b, waiting)))
        assertEquals(listOf(ArrivalPuts.Put(1, 100, bin = true)), p.untaken(listOf(a, b, waiting)))
        // It took it: the note is gone, nothing to tell.
        assertTrue(p.untaken(listOf(b, waiting)).isEmpty())
        assertTrue(p.untaken(listOf(a.copy(arrivedAt = 0), b)).isEmpty())
    }

    @Test fun `not now - the same, and a new arrival of that note stands again`() {
        val p = ArrivalPuts()
        p.later(a)
        assertEquals(listOf(ArrivalPuts.Put(1, 100, bin = false)), p.untaken(listOf(a)))
        assertEquals(emptyList<Pin>(), p.visible(listOf(a)))
        // Taken (waiting again), and later he arrives there anew: it stands.
        assertTrue(p.untaken(listOf(a.copy(arrivedAt = 0))).isEmpty())
        val again = a.copy(arrivedAt = 900)
        assertEquals(listOf(again), p.visible(listOf(again)))
        // Not taken yet but a new arrival came in its place: the old put is dropped.
        p.later(a)
        assertTrue(p.untaken(listOf(again)).isEmpty())
        assertEquals(listOf(again), p.visible(listOf(again)))
    }
}
