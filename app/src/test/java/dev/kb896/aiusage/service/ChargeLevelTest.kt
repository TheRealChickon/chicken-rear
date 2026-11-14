package dev.kb896.aiusage.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeLevelTest {

    @Test fun `the percent from level and scale`() {
        assertEquals(63, ChargeLevel.percent(63, 100))
        assertEquals(50, ChargeLevel.percent(128, 256))
        assertEquals(100, ChargeLevel.percent(120, 100))
    }

    @Test fun `no reading is no percent`() {
        assertNull(ChargeLevel.percent(-1, 100))
        assertNull(ChargeLevel.percent(50, 0))
    }

    @Test fun `a 3 A charger at 9 V is fast, 2 A at 5 V is not`() {
        assertTrue(ChargeLevel.fast(3_000_000, 9_000_000))
        assertFalse(ChargeLevel.fast(2_000_000, 5_000_000))
    }

    @Test fun `no voltage is taken as 5 V, no current as not fast`() {
        assertTrue(ChargeLevel.fast(3_000_000, 0))
        assertFalse(ChargeLevel.fast(2_000_000, 0))
        assertFalse(ChargeLevel.fast(0, 20_000_000))
    }
}
