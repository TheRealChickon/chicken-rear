package dev.kb896.aiusage.service

import dev.kb896.aiusage.service.StayOn.Facts
import dev.kb896.aiusage.service.StayOn.Level
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rule for the back staying lit, case by case. */
class StayOnTest {
    private val base = Facts(enabled = true, inUse = false, faceDown = false, onBack = false, pocketed = false, manualOff = false, recentTouch = false, dimFaceDown = true)
    private fun level(f: Facts) = StayOn.level(f).first

    @Test fun `in use in the hand it is on`() = assertEquals(Level.ON, level(base.copy(inUse = true)))

    @Test fun `in the hand with fingers over the rear sensor it is still on`() =
        assertEquals(Level.ON, level(base.copy(inUse = true, pocketed = true)))

    @Test fun `lying face down it is on, dimmed, front locked or not`() {
        assertEquals(Level.DIM, level(base.copy(faceDown = true)))
        assertEquals(Level.DIM, level(base.copy(faceDown = true, inUse = true)))
    }

    @Test fun `lying face down and just touched it is at full, and without the dim setting always`() {
        assertEquals(Level.ON, level(base.copy(faceDown = true, recentTouch = true)))
        assertEquals(Level.ON, level(base.copy(faceDown = true, dimFaceDown = false)))
    }

    @Test fun `in a pocket it is off, face down or not`() {
        assertEquals(Level.OFF, level(base.copy(pocketed = true)))
        assertEquals(Level.OFF, level(base.copy(pocketed = true, faceDown = true)))
    }

    @Test fun `lying on its back it is off even while the front is in use`() =
        assertEquals(Level.OFF, level(base.copy(onBack = true, inUse = true)))

    @Test fun `after a double tap it is off whatever else holds`() {
        assertEquals(Level.OFF, level(base.copy(manualOff = true, inUse = true)))
        assertEquals(Level.OFF, level(base.copy(manualOff = true, faceDown = true)))
    }

    @Test fun `with the camera open it is at full, in the hand or lying face down, and still off on its back`() {
        assertEquals(Level.FULL, level(base.copy(inUse = true, camera = true)))
        assertEquals(Level.FULL, level(base.copy(faceDown = true, camera = true)))
        assertEquals(Level.OFF, level(base.copy(onBack = true, inUse = true, camera = true)))
    }

    @Test fun `lying face down it goes off after half an hour, sooner on a low battery, and a touch starts it again`() {
        val min = 60_000L
        assertEquals(Level.DIM, level(base.copy(faceDown = true, battery = 80, faceDownForMs = 29 * min)))
        assertEquals(Level.OFF, level(base.copy(faceDown = true, battery = 80, faceDownForMs = 30 * min)))
        assertEquals(Level.OFF, level(base.copy(faceDown = true, battery = 40, faceDownForMs = 15 * min)))
        assertEquals(Level.DIM, level(base.copy(faceDown = true, battery = 40, faceDownForMs = 14 * min)))
        assertEquals(Level.OFF, level(base.copy(faceDown = true, battery = 20, faceDownForMs = 5 * min)))
        // On the charger the level does not shorten it.
        assertEquals(Level.DIM, level(base.copy(faceDown = true, battery = 20, charging = true, faceDownForMs = 20 * min)))
        assertEquals(30 * min, StayOn.faceDownLimitMs(20, charging = true))
    }

    @Test fun `in battery saver there is no table mode, a still picture for a minute in the hand, and the camera still gets full`() {
        assertEquals(Level.OFF, level(base.copy(faceDown = true, saver = true)))
        assertEquals(Level.STILL, level(base.copy(inUse = true, saver = true, quietForMs = 10_000)))
        assertEquals(Level.OFF, level(base.copy(inUse = true, saver = true, quietForMs = StayOn.SAVER_STILL_MS)))
        assertEquals(Level.FULL, level(base.copy(inUse = true, saver = true, camera = true)))
    }

    @Test fun `under 15 percent off the charger it behaves as in battery saver, on the charger as usual`() {
        assertEquals(Level.STILL, level(base.copy(inUse = true, battery = 10, quietForMs = 5_000)))
        assertEquals(Level.ON, level(base.copy(inUse = true, battery = 10, charging = true)))
        assertEquals(Level.OFF, level(base.copy(faceDown = true, battery = 10)))
    }

    @Test fun `front off and not lying face down it is off`() = assertEquals(Level.OFF, level(base))

    @Test fun `switched off in the settings it is off`() =
        assertEquals(Level.OFF, level(base.copy(enabled = false, inUse = true, faceDown = true)))
}
