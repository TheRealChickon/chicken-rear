package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Companion mode: Xiaomi's rear screen is the base. A fresh install starts there,
 * and a config stored before the mode existed (no rearMode key) is moved there once, with the
 * rules that act on the rear by themselves switched off. A config that has the key is read as
 * it is, so the move never runs twice and a rule turned back on stays on.
 */
class PanelConfigCompanionTest {

    /** A config stored before companion mode: written out with every default of the day, posture rules on. */
    private val before = """{"showNotifications":true,"alwaysOn":true,"stayOnInHand":true,"tableDim":true,""" +
        """"offOnBack":true,"cameraLight":true,"aodPocket":true,"title":"MINE","portalApps":["org.mozilla.firefox"]}"""

    @Test fun `a fresh install starts in companion mode, its posture rules off`() {
        val c = PanelConfig.FRESH
        assertEquals(RearMode.COMPANION, c.rearMode)
        assertTrue(c.companion)
        assertFalse(c.alwaysOn)
        assertFalse(c.stayOnInHand)
        assertFalse(c.tableMode)
        assertFalse(c.cameraLight)
        assertFalse(c.pocketMode)
        assertFalse(c.wakeOnLift)
        // Kept: it only declines this app's own wakes now.
        assertTrue(c.offOnBack)
    }

    @Test fun `the four additions are on by default, and the rest at their own defaults`() {
        val c = PanelConfig()
        assertEquals(RearMode.COMPANION, c.rearMode)
        assertTrue(c.moreApps)
        assertTrue(c.chargeAnim)
        assertTrue(c.chargeWhenDark)
        assertTrue(c.wakeAnim)
        assertEquals(ChargeAnimStyle.LIQUID, c.chargeStyle)
        assertEquals(WakeAnimStyle.EDGE_GLOW, c.wakeStyle)
        assertEquals(PortalPullEdge.TOP, c.portalPullEdge)
        assertEquals(emptySet<String>(), c.noticeApps)
        assertEquals(NoticeAppsMode.ALL_EXCEPT, c.noticeAppsMode)
        assertTrue("shown while locked too", c.noticeTextWhenLocked)
        assertFalse(c.wakeOnLift)
    }

    @Test fun `a config from before companion mode is moved to it, exactly the listed rules off`() {
        val c = PanelConfig.decode(before)
        assertEquals(RearMode.COMPANION, c.rearMode)
        assertFalse(c.alwaysOn)
        assertFalse(c.stayOnInHand)
        assertFalse(c.tableMode)
        assertFalse(c.cameraLight)
        assertFalse(c.pocketMode)
        assertFalse(c.wakeOnLift)
        // Everything else as it was stored.
        assertTrue(c.offOnBack)
        assertTrue(c.showNotifications)
        assertEquals("MINE", c.title)
        assertEquals(listOf("org.mozilla.firefox"), c.portalApps)
        // And nothing beyond the listed fields differs from a plain read of the same blob.
        val plain = PanelConfig.decode(before.dropLast(1) + ""","rearMode":"COMPANION"}""")
        assertEquals(
            plain.copy(alwaysOn = false, stayOnInHand = false, tableMode = false, cameraLight = false, pocketMode = false, wakeOnLift = false),
            c,
        )
    }

    @Test fun `the move runs once — a rule turned back on afterwards stays on`() {
        val moved = PanelConfig.decode(before)
        val raw = PanelConfig.encode(moved.copy(cameraLight = true, tableMode = true))
        assertTrue(raw.contains("\"${PanelConfig.REAR_MODE_KEY}\":\"COMPANION\""))
        val again = PanelConfig.decode(raw)
        assertTrue(again.cameraLight)
        assertTrue(again.tableMode)
        // And read once more, unchanged.
        assertEquals(again, PanelConfig.decode(PanelConfig.encode(again)))
    }

    @Test fun `a Replace config is read as companion mode, as a fresh one is`() {
        // Replace mode and its screen were removed: nothing in the app is in it again.
        val c = PanelConfig.decode(before.dropLast(1) + ""","rearMode":"REPLACE"}""")
        assertEquals(RearMode.COMPANION, c.rearMode)
        assertTrue(c.companion)
        assertEquals("with companion mode's own start", c, c.companionStart())
    }

    @Test fun `the new fields round-trip`() {
        val c = PanelConfig(
            rearMode = RearMode.COMPANION,
            wakeOnLift = true,
            noticeApps = setOf("com.whatsapp", "org.telegram.messenger"),
            noticeAppsMode = NoticeAppsMode.ONLY,
            noticeTextWhenLocked = true,
            chargeAnim = false,
            chargeStyle = ChargeAnimStyle.RING,
            chargeWhenDark = false,
            wakeAnim = false,
            wakeStyle = WakeAnimStyle.RIPPLE,
            moreApps = false,
            portalPullEdge = PortalPullEdge.BOTTOM_RIGHT,
        )
        assertEquals(c, PanelConfig.decode(PanelConfig.encode(c)))
    }

    @Test fun `an unknown style from a newer build reads as the default, not a lost config`() {
        val c = PanelConfig.decode("""{"rearMode":"COMPANION","title":"KEEP","wakeStyle":"FIREWORKS","chargeStyle":"PLASMA"}""")
        assertEquals("KEEP", c.title)
        assertEquals(WakeAnimStyle.EDGE_GLOW, c.wakeStyle)
        assertEquals(ChargeAnimStyle.LIQUID, c.chargeStyle)
    }
}

/** Replace mode is gone: a stored config that says it is read as companion, and known so Xiaomi's app can be put back. */
class ReplaceModeGoneTest {
    @org.junit.Test fun `a stored Replace is read as companion mode`() {
        val stored = PanelConfig.encode(PanelConfig.DEFAULT.copy(rearMode = RearMode.REPLACE))
        org.junit.Assert.assertTrue(PanelConfig.storedReplace(stored))
        val read = PanelConfig.decode(stored)
        org.junit.Assert.assertEquals(RearMode.COMPANION, read.rearMode)
        org.junit.Assert.assertTrue(read.companion)
        // Written back, it no longer says Replace: the service's move happens once.
        org.junit.Assert.assertFalse(PanelConfig.storedReplace(PanelConfig.encode(read)))
        org.junit.Assert.assertFalse(PanelConfig.storedReplace(null))
        org.junit.Assert.assertFalse(PanelConfig.storedReplace(PanelConfig.encode(PanelConfig.FRESH)))
    }
}
