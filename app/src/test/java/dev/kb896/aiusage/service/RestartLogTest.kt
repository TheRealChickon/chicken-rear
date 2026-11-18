package dev.kb896.aiusage.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartLogTest {
    @Test fun `the binds are looked at again after 10 s, a minute, five minutes, then every quarter of an hour`() {
        val b = Backoff()
        assertEquals(listOf(10_000L, 60_000L, 300_000L, 900_000L, 900_000L, 900_000L), List(6) { b.next() })
        b.reset()
        assertEquals("a bind that took starts it over", 10_000L, b.next())
    }

    @Test fun `never a loop under ten seconds`() {
        val b = Backoff()
        repeat(100) { assertTrue(b.next() >= 10_000L) }
        assertTrue(Backoff.STEPS.all { it >= 10_000L })
    }

    @Test fun `each line says how long after the boot it came`() {
        assertEquals("47 s after boot", RestartLog.line(47_900L))
        assertEquals(3L, RestartLog.seconds(1_000L, 3_600L))
        assertEquals("a clock that went back is 0, not less", 0L, RestartLog.seconds(5_000L, 1_000L))
        assertEquals("AiUsageRestart", RestartLog.TAG)
    }
}
