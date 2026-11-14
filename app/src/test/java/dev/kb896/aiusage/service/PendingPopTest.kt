package dev.kb896.aiusage.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** A banner that arrived while unbound is handed over on connect if it is under five seconds old. */
class PendingPopTest {

    @Test fun `nothing held is nothing to pop`() {
        assertEquals("", PendingPop().take(1_000))
    }

    @Test fun `delivered when under five seconds old`() {
        val p = PendingPop()
        p.hold("k1", 10_000)
        assertEquals("k1", p.take(10_000 + 4_999))
    }

    @Test fun `delivered at exactly five seconds, dropped after`() {
        val p = PendingPop()
        p.hold("k1", 10_000)
        assertEquals("k1", p.take(15_000))
        p.hold("k2", 10_000)
        assertEquals("", p.take(15_001))
    }

    @Test fun `taken once only`() {
        val p = PendingPop()
        p.hold("k1", 0)
        assertEquals("k1", p.take(100))
        assertEquals("", p.take(200))
    }

    @Test fun `the newest wins`() {
        val p = PendingPop()
        p.hold("old", 0)
        p.hold("new", 1_000)
        assertEquals("new", p.take(5_500))
    }

    @Test fun `an empty key holds nothing and does not replace a real one`() {
        val p = PendingPop()
        p.hold("k1", 0)
        p.hold("", 100)
        assertEquals("k1", p.take(200))
    }

    @Test fun `cleared once shown`() {
        val p = PendingPop()
        p.hold("k1", 0)
        p.clear()
        assertEquals("", p.take(100))
    }

    @Test fun `a clock gone backwards is not a fresh pop`() {
        val p = PendingPop()
        p.hold("k1", 10_000)
        assertEquals("", p.take(9_000))
    }
}
