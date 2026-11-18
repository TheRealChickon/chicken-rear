package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MamlFeedTest {
    @Test fun `the battery as MAML's own BatteryVariableUpdater puts it`() {
        assertEquals(42.0, MamlFeed.batteryLevel(42)!!, 0.0)
        assertEquals(100.0, MamlFeed.batteryLevel(100)!!, 0.0)
        assertEquals(100.0, MamlFeed.batteryLevel(130)!!, 0.0)
        assertEquals(0.0, MamlFeed.batteryLevel(0)!!, 0.0)
        assertNull("no level in the intent: nothing set", MamlFeed.batteryLevel(-1))
        assertEquals(1.0, MamlFeed.darkMode(true), 0.0); assertEquals(0.0, MamlFeed.darkMode(false), 0.0)
    }

    @Test fun `the next minute boundary, across a summer-time change too`() {
        assertEquals(120_000L, MamlFeed.nextMinute(60_000L))
        assertEquals(120_000L, MamlFeed.nextMinute(119_999L))
        assertEquals(60_000L, MamlFeed.nextMinute(0L))
        val zone = ZoneId.of("Europe/Berlin")
        val before = LocalDateTime.of(2026, 3, 29, 1, 59, 30).atZone(zone).toInstant().toEpochMilli()
        val next = MamlFeed.nextMinute(before)
        assertEquals(30_000L, next - before)
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 0, 0), java.time.Instant.ofEpochMilli(next).atZone(zone).toLocalDateTime())
        // And in a zone a half hour off (India), still on the local minute.
        val kolkata = ZoneId.of("Asia/Kolkata")
        val t = LocalDateTime.of(2026, 11, 15, 10, 41, 12).atZone(kolkata).toInstant().toEpochMilli()
        assertEquals(LocalDateTime.of(2026, 11, 15, 10, 42, 0), java.time.Instant.ofEpochMilli(MamlFeed.nextMinute(t)).atZone(kolkata).toLocalDateTime())
        assertTrue(MamlFeed.untilNextMinute(119_999L) >= 5L)
    }
}
