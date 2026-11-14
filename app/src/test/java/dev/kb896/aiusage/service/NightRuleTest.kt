package dev.kb896.aiusage.service

import dev.kb896.aiusage.rear.StaySilence
import dev.kb896.aiusage.service.StayOn.Facts
import dev.kb896.aiusage.service.StayOn.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Sleep at night": the back does not
 * hold the phone awake at night, by day everything is as it was.
 */
class NightRuleTest {
    private val base = Facts(enabled = true, inUse = false, faceDown = false, onBack = false, pocketed = false, manualOff = false, recentTouch = false, dimFaceDown = true)
    private fun level(f: Facts) = StayOn.level(f).first
    private val min = 60_000L

    // -- 1. the night rule ---------------------------------------------------------------------------

    @Test fun `night is the night's hours or Do Not Disturb, and only with the switch on`() {
        for (h in listOf(22, 23, 0, 3, 6)) assertTrue("$h", NightRule.night(true, h, false))
        for (h in listOf(7, 8, 12, 18, 21)) assertFalse("$h", NightRule.night(true, h, false))
        assertTrue(NightRule.night(true, 14, doNotDisturb = true))
        assertFalse(NightRule.night(false, 2, false))
        assertFalse(NightRule.night(false, 14, doNotDisturb = true))
    }

    @Test fun `at night with the front off the back has two minutes, face down or not`() {
        assertEquals(2 * min, NightRule.ON_MS)
        val down = base.copy(faceDown = true, night = true)
        assertEquals("just off: still lit, as face down is", Level.DIM, level(down.copy(frontOffForMs = 10_000L)))
        assertEquals(Level.DIM, level(down.copy(frontOffForMs = 2 * min - 1)))
        assertEquals(Level.OFF, level(down.copy(frontOffForMs = 2 * min)))
        assertEquals(Level.OFF, level(down.copy(frontOffForMs = 6 * 60 * min)))
        assertEquals("night, the front off for 2 minutes", StayOn.level(down.copy(frontOffForMs = 3 * min)).second)
        // Not face down with the front off: off anyway, as by day.
        assertEquals(Level.OFF, level(base.copy(night = true, frontOffForMs = 10_000L)))
    }

    @Test fun `at night the phone being moved starts nothing - only a finger on the back or the front does`() {
        val down = base.copy(faceDown = true, night = true)
        // Moved: it lies face down afresh (its face-down time starts again), the front's time does not.
        assertEquals(Level.OFF, level(down.copy(faceDownForMs = 0L, frontOffForMs = 40 * min)))
        // A finger on the back: the time since is short again, and it is lit, at full as a touched back is.
        assertEquals(Level.ON, level(down.copy(recentTouch = true, frontOffForMs = 1_000L)))
        // The front on: the night rule says nothing, it is in use.
        assertEquals(Level.ON, level(base.copy(inUse = true, night = true, frontOffForMs = 0L)))
        assertEquals(Level.DIM, level(down.copy(inUse = true, frontOffForMs = 0L)))
        // And the motion watch is not armed at night at all.
        assertFalse(NightRule.watchesMotion(night = true))
        assertTrue(NightRule.watchesMotion(night = false))
    }

    @Test fun `by day the face-down rule is exactly as it was, front off and all`() {
        val down = base.copy(faceDown = true, night = false, battery = 40)
        assertEquals(Level.DIM, level(down.copy(frontOffForMs = 10 * min, faceDownForMs = 10 * min)))
        assertEquals(Level.DIM, level(down.copy(frontOffForMs = 14 * min, faceDownForMs = 14 * min)))
        assertEquals(Level.OFF, level(down.copy(frontOffForMs = 15 * min, faceDownForMs = 15 * min)))
        assertEquals(Level.DIM, level(down.copy(battery = 80, frontOffForMs = 29 * min, faceDownForMs = 29 * min)))
        // The switch off: the night is a day.
        assertFalse(NightRule.timeUp(NightRule.night(false, 2, false), frontOn = false, quietForMs = 60 * min))
    }

    @Test fun `the other reasons for off still say their own word at night`() {
        val late = base.copy(night = true, frontOffForMs = 30 * min)
        assertEquals("switched off by hand", StayOn.level(late.copy(manualOff = true, faceDown = true)).second)
        assertEquals("lying on its back", StayOn.level(late.copy(onBack = true)).second)
        assertEquals("in a pocket", StayOn.level(late.copy(pocketed = true, faceDown = true)).second)
        assertEquals("always on is switched off", StayOn.level(late.copy(enabled = false)).second)
        // The camera with the front off at night: not lit either.
        assertEquals(Level.OFF, level(late.copy(faceDown = true, camera = true)))
    }

    // -- 2. no wake of ours at night ------------------------------------------------------------------

    @Test fun `a notification, the charger or being picked up wake nothing at night with the front off`() {
        assertTrue(NightRule.declinesWake(night = true, frontOn = false))
        assertFalse("the front on: he is there", NightRule.declinesWake(night = true, frontOn = true))
        assertFalse("by day", NightRule.declinesWake(night = false, frontOn = false))
    }

    // -- 3. the shell lets go of a level the app no longer confirms --------------------------------------

    @Test fun `the shell lets the back go after half a minute without the app's word, the front being off`() {
        assertEquals(30_000L, StaySilence.SILENT_MS)
        assertFalse(StaySilence.letGo(level = 3, silentForMs = 29_000L, frontOn = false))
        assertTrue(StaySilence.letGo(level = 3, silentForMs = 30_000L, frontOn = false))
        assertTrue(StaySilence.letGo(level = 2, silentForMs = 5 * min, frontOn = false))
        assertFalse("nothing held, nothing to let go", StaySilence.letGo(level = 0, silentForMs = 5 * min, frontOn = false))
    }

    @Test fun `not while the front is on - the Camera freezes the app with him holding the phone`() {
        assertFalse(StaySilence.letGo(level = 4, silentForMs = 5 * min, frontOn = true))
    }

    @Test fun `the app says its level again well inside the shell's patience`() {
        assertTrue(StayOn.CONFIRM_MS * 3 <= StaySilence.SILENT_MS)
    }

    // -- 4. in use is the front display, not the phone's interactivity ----------------------------------

    @Test fun `the front is on only in the display's on state - dozing and off are not`() {
        assertTrue(FrontScreen.isOn(android.view.Display.STATE_ON))
        assertFalse(FrontScreen.isOn(android.view.Display.STATE_OFF))
        assertFalse(FrontScreen.isOn(android.view.Display.STATE_DOZE))
        assertFalse(FrontScreen.isOn(android.view.Display.STATE_DOZE_SUSPEND))
        assertFalse(FrontScreen.isOn(android.view.Display.STATE_UNKNOWN))
    }

    // -- 5. nothing runs while nothing can come of it ----------------------------------------------------

    @Test fun `with the front off and the back off the rule stops looking`() {
        assertNull(StayOn.nextTickMs(looking = false, enabled = true, inUse = false, level = Level.OFF, listening = false))
        assertNull(StayOn.nextTickMs(looking = false, enabled = false, inUse = true, level = Level.OFF, listening = false))
        // Once more while the accelerometer is still registered, so that it is let go.
        assertEquals(2_000L, StayOn.nextTickMs(looking = false, enabled = true, inUse = false, level = Level.OFF, listening = true))
    }

    @Test fun `in use or with the back lit it looks every two seconds, and quickly after being moved`() {
        assertEquals(2_000L, StayOn.nextTickMs(looking = false, enabled = true, inUse = true, level = Level.OFF, listening = false))
        assertEquals(2_000L, StayOn.nextTickMs(looking = false, enabled = true, inUse = false, level = Level.DIM, listening = true))
        assertEquals(500L, StayOn.nextTickMs(looking = true, enabled = true, inUse = false, level = Level.OFF, listening = true))
    }

    @Test fun `what is only seen waits while the front and the back are both off`() {
        assertTrue(StayOn.nothingToSee(inUse = false, level = Level.OFF))
        assertFalse(StayOn.nothingToSee(inUse = true, level = Level.OFF))
        assertFalse("face down by day with the front off: the back is seen", StayOn.nothingToSee(inUse = false, level = Level.DIM))
    }

    @Test fun `the parked loops start again the moment there is something to see`() {
        PanelAwake.setDozing(true)
        var ran = 0
        PanelAwake.whenAwake { ran++ }
        assertEquals(0, ran)
        assertFalse(PanelAwake.isAwake())
        PanelAwake.setDozing(false)
        assertEquals(1, ran)
        assertTrue(PanelAwake.isAwake())
    }
}
