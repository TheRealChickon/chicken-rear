package dev.kb896.aiusage.service

import dev.kb896.aiusage.rear.PinBeam
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pin sounds as Xiaomi's does: the phone's notification sound, as a notification's (silent on silent and vibrate,
 * held back by Do Not Disturb), when the card has gone; never for a demo or a test unless that asks.
 */
class PinChimeTest {
    private val normal = 2; private val vibrate = 1; private val silent = 0
    private val all = 1; private val priority = 2; private val none = 3; private val alarms = 4; private val unknown = 0

    @Test fun `it sounds with the ringer on and nothing held back`() {
        assertTrue(PinChime.plays(normal, all, demo = false, asked = false))
        assertTrue("a filter not known is no filter", PinChime.plays(normal, unknown, demo = false, asked = false))
    }

    @Test fun `silent and vibrate are silent`() {
        assertFalse(PinChime.plays(silent, all, demo = false, asked = false))
        assertFalse(PinChime.plays(vibrate, all, demo = false, asked = false))
    }

    @Test fun `Do Not Disturb holds it back, in each of its kinds`() {
        for (filter in listOf(priority, none, alarms)) assertFalse("filter $filter", PinChime.plays(normal, filter, demo = false, asked = false))
    }

    @Test fun `a demo or a test's pin is silent unless it asks, and asking does not get past silent or Do Not Disturb`() {
        assertFalse(PinChime.plays(normal, all, demo = true, asked = false))
        assertTrue(PinChime.plays(normal, all, demo = true, asked = true))
        assertFalse(PinChime.plays(silent, all, demo = true, asked = true))
        assertFalse(PinChime.plays(normal, priority, demo = true, asked = true))
    }

    @Test fun `it comes when the card has gone into the top edge, as Xiaomi's notification does at its animation's end`() {
        assertTrue(PinBeam.CHIME_AT == PinBeam.LEAVE_AT + 550L)
        assertTrue("the card is all but gone from the front: ${PinBeam.front(PinBeam.CHIME_AT).cardAlpha}", PinBeam.front(PinBeam.CHIME_AT).cardAlpha < 0.06f)
        assertTrue("and the back has it", PinBeam.back(PinBeam.CHIME_AT - PinBeam.ARRIVE_AT).alpha > 0.9f)
        assertTrue(PinBeam.CHIME_AT < PinBeam.FRONT_MS)
    }

    @Test fun `it is asked for before it is to be heard - the file's own silence and the way to the speaker - and while the pin's screen is still there`() {
        // Measured: the sound's attack is 34 to 41 ms into its file; a pool that is ready plays within a few tens of
        // ms.
        assertTrue(PinChime.LEAD_MS in 40L..120L)
        val asked = PinBeam.CHIME_AT - PinChime.LEAD_MS
        assertTrue("after the card has begun to leave", asked > PinBeam.ARRIVE_AT)
        assertTrue("well before the front is over, so it is asked for by the animation and not by its ending", PinBeam.FRONT_MS - asked >= 150L)
    }
}
