package dev.kb896.aiusage.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home detection in the always-on rule: at home lit while the front is on, by day and at night, face down as everywhere, nothing changed away from home. */
class HomeRuleTest {
    private val inUse = StayOn.Facts(enabled = true, inUse = true, faceDown = false, onBack = false, pocketed = false, manualOff = false, recentTouch = false, dimFaceDown = true)

    @Test fun `away from home nothing changes`() {
        assertEquals(StayOn.Level.ON, StayOn.level(inUse.copy(quietForMs = 30 * 60_000L)).first)
        assertEquals(StayOn.Level.ON, StayOn.level(inUse.copy(bedtime = true, quietForMs = 30 * 60_000L)).first)
    }

    @Test fun `at home by day the front being on is the phone in use, however long he does not touch it`() {
        // A video watched for an hour without a touch: the back stays lit (it went off after two minutes).
        for (quiet in listOf(0L, 60_000L, StayOn.HOME_ON_MS, 10 * 60_000L, 60 * 60_000L)) {
            val (level, why) = StayOn.level(inUse.copy(home = true, quietForMs = quiet))
            assertEquals("quiet for ${quiet / 1000} s", StayOn.Level.ON, level)
            assertEquals("in use, at home", why)
        }
    }

    @Test fun `at home at night it stays lit while the front is on, and is off once the front is off`() {
        val (level, why) = StayOn.level(inUse.copy(home = true, bedtime = true, night = true, quietForMs = 30 * 60_000L))
        assertEquals(StayOn.Level.ON, level)
        assertEquals("in use, at home at night", why)
        assertEquals(StayOn.Level.OFF, StayOn.level(inUse.copy(inUse = false, home = true, bedtime = true, night = true)).first)
    }

    @Test fun `lying face down is as everywhere, at home and at night too`() {
        val down = inUse.copy(inUse = false, faceDown = true, home = true, bedtime = true)
        assertEquals(StayOn.Level.DIM, StayOn.level(down).first)
        assertEquals(StayOn.level(down.copy(home = false, bedtime = false)), StayOn.level(down))
    }

    @Test fun `night is from ten to seven, or Do Not Disturb at any hour`() {
        assertTrue(HomeNet.bedtime(23, false)); assertTrue(HomeNet.bedtime(0, false)); assertTrue(HomeNet.bedtime(6, false))
        assertFalse(HomeNet.bedtime(7, false)); assertFalse(HomeNet.bedtime(21, false))
        assertTrue(HomeNet.bedtime(15, true))
    }

    @Test fun `a network is the same one whatever order its name servers come in`() {
        assertEquals(HomeNet.identity("192.168.1.1", listOf("8.8.8.8", "1.1.1.1")), HomeNet.identity("192.168.1.1", listOf("1.1.1.1", "8.8.8.8")))
        assertFalse(HomeNet.identity("192.168.1.1", listOf("1.1.1.1")) == HomeNet.identity("192.168.0.1", listOf("1.1.1.1")))
    }
}

/** At home from a Wi-Fi already read (StayOn reads it only with home detection on). */
class HomeNetReadTest {
    private val home = HomeNet.identity("192.168.1.1", listOf("192.168.1.1"))
    private val other: String = HomeNet.identity("10.0.0.1", listOf("10.0.0.1"))
    private val none: String? = null
    private val on = dev.kb896.aiusage.data.PanelConfig(homeDetect = true, homeNet = home)

    @Test fun `at home only with detection on, a home set, and that Wi-Fi`() {
        assertTrue(HomeNet.atHome(on, home))
        assertFalse(HomeNet.atHome(on, other))
        assertFalse(HomeNet.atHome(on, none))
        assertFalse(HomeNet.atHome(on.copy(homeDetect = false), home))
        assertFalse(HomeNet.atHome(on.copy(homeNet = ""), ""))
    }
}
