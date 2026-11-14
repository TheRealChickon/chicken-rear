package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearPostureTest {
    private val on = RearPosture.Settings()
    private fun level(f: RearPosture.Facts, s: RearPosture.Settings = on) = RearPosture.wanted(f, s)

    @Test fun `in the hand, with nothing else known, the back is on at normal brightness`() {
        assertEquals(RearLevel.NORMAL, level(RearPosture.Facts()))
    }

    @Test fun `manual off wins over everything automatic`() {
        val everything = RearPosture.Facts(
            manualOff = true, faceDown = true, cameraInUse = true, callActive = true,
            recentTouch = true, recentNotification = true, plugged = true,
        )
        assertEquals(RearLevel.OFF, level(everything))
        assertEquals(RearLevel.OFF, level(RearPosture.Facts(manualOff = true)))
        assertEquals(RearLevel.OFF, level(RearPosture.Facts(manualOff = true, cameraInUse = true)))
        assertEquals(RearLevel.OFF, level(RearPosture.Facts(manualOff = true, faceDown = true)))
        // and with every switch off too: the latch is not a setting
        val off = RearPosture.Settings(dimFaceDown = false, offOnBack = false, stayOnInHand = false, cameraLight = false)
        assertEquals(RearLevel.OFF, level(RearPosture.Facts(manualOff = true), off))
    }

    @Test fun `a pocket is off, whatever the camera or the table say`() {
        assertEquals(RearLevel.OFF, level(RearPosture.Facts(pocketed = true, cameraInUse = true)))
        assertEquals(RearLevel.OFF, level(RearPosture.Facts(pocketed = true, faceDown = true, callActive = true)))
    }

    @Test fun `lying on its back is off, the camera and a call included, unless switched off`() {
        assertEquals(RearLevel.OFF, level(RearPosture.Facts(lyingOnBack = true)))
        assertEquals(RearLevel.OFF, level(RearPosture.Facts(lyingOnBack = true, cameraInUse = true, callActive = true, recentNotification = true)))
        assertEquals(RearLevel.NORMAL, level(RearPosture.Facts(lyingOnBack = true), on.copy(offOnBack = false)))
        // picked up: on again by itself
        assertEquals(RearLevel.NORMAL, level(RearPosture.Facts(lyingOnBack = false)))
    }

    @Test fun `face down is dim, with no limit, charger or not`() {
        assertEquals(RearLevel.DIM, level(RearPosture.Facts(faceDown = true, plugged = false)))
        assertEquals(RearLevel.DIM, level(RearPosture.Facts(faceDown = true, plugged = true)))
    }

    @Test fun `face down goes to normal for a touch, a card or a call, and back to dim after`() {
        assertEquals(RearLevel.NORMAL, level(RearPosture.Facts(faceDown = true, recentTouch = true)))
        assertEquals(RearLevel.NORMAL, level(RearPosture.Facts(faceDown = true, recentNotification = true)))
        assertEquals(RearLevel.NORMAL, level(RearPosture.Facts(faceDown = true, callActive = true)))
        assertEquals(RearLevel.DIM, level(RearPosture.Facts(faceDown = true, recentTouch = false, recentNotification = false, callActive = false)))
    }

    @Test fun `face down with a camera in use is full, and dim again once it closes`() {
        assertEquals(RearLevel.FULL, level(RearPosture.Facts(faceDown = true, cameraInUse = true)))
        assertEquals(RearLevel.DIM, level(RearPosture.Facts(faceDown = true, cameraInUse = false)))
    }

    @Test fun `a camera in use in the hand is full, unless its switch is off`() {
        assertEquals(RearLevel.FULL, level(RearPosture.Facts(cameraInUse = true)))
        assertEquals(RearLevel.NORMAL, level(RearPosture.Facts(cameraInUse = true), on.copy(cameraLight = false)))
    }

    @Test fun `only while charging - face down on the battery is off, on a charger dim`() {
        val s = on.copy(faceDownChargingOnly = true)
        assertEquals(RearLevel.OFF, level(RearPosture.Facts(faceDown = true, plugged = false), s))
        assertEquals(RearLevel.DIM, level(RearPosture.Facts(faceDown = true, plugged = true), s))
        // the camera still lights it there
        assertEquals(RearLevel.FULL, level(RearPosture.Facts(faceDown = true, plugged = false, cameraInUse = true), s))
    }

    @Test fun `dim when face down switched off - face down is just the hand's normal`() {
        assertEquals(RearLevel.NORMAL, level(RearPosture.Facts(faceDown = true), on.copy(dimFaceDown = false)))
    }

    @Test fun `the idle timer runs only at normal with stay on in the hand off`() {
        assertFalse(RearPosture.idleSleeps(RearLevel.NORMAL, on))
        assertTrue(RearPosture.idleSleeps(RearLevel.NORMAL, on.copy(stayOnInHand = false)))
        for (l in listOf(RearLevel.OFF, RearLevel.DIM, RearLevel.FULL)) {
            assertFalse(RearPosture.idleSleeps(l, on.copy(stayOnInHand = false)))
        }
    }

    @Test fun `a call is the ringtone, a phone call, a call in another app, or screening`() {
        assertTrue(RearPosture.inCall(android.media.AudioManager.MODE_RINGTONE))
        assertTrue(RearPosture.inCall(android.media.AudioManager.MODE_IN_CALL))
        assertTrue(RearPosture.inCall(android.media.AudioManager.MODE_IN_COMMUNICATION))
        assertTrue(RearPosture.inCall(android.media.AudioManager.MODE_CALL_SCREENING))
        assertFalse(RearPosture.inCall(android.media.AudioManager.MODE_NORMAL))
    }

    @Test fun `the dim is about a tenth of normal, and a touch lasts thirty seconds`() {
        val share = RearPosture.dimShare()
        assertTrue("dim share $share", share in 0.08..0.18)
        assertEquals(30_000L, RearPosture.TOUCH_MS)
    }

    /** A veil over the rear's own brightness: whatever auto-brightness has chosen, the dim is below it. */
    @Test fun `the dim is never brighter than normal`() {
        for (normal in doubleArrayOf(0.002, 0.019, 0.045, 0.1, 0.5, 1.0)) {
            val dim = normal * RearPosture.dimShare()
            assertTrue("normal $normal dim $dim", dim < normal)
        }
        assertEquals(1.0, RearPosture.dimShare(0f), 1e-9)
        assertTrue(RearPosture.dimShare(RearPosture.DIM_VEIL_ALPHA) < RearPosture.dimShare(0.3f))
    }

    @Test fun `the settings follow the config`() {
        val c = dev.kb896.aiusage.data.PanelConfig(tableMode = false, tableChargingOnly = true, offOnBack = false, stayOnInHand = false, cameraLight = false)
        assertEquals(
            RearPosture.Settings(dimFaceDown = false, faceDownChargingOnly = true, offOnBack = false, stayOnInHand = false, cameraLight = false),
            RearPosture.Settings.of(c),
        )
        assertEquals(RearPosture.Settings(), RearPosture.Settings.of(dev.kb896.aiusage.data.PanelConfig()))
    }
}
