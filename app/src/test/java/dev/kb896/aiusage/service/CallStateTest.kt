package dev.kb896.aiusage.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call that showed "pick up" while he was talking: whether a call is answered is the phone's to
 * say, and of the dialer's several notifications the call's own has the page.
 */
class CallStateTest {
    private val NORMAL = 0; private val RINGTONE = 1; private val IN_CALL = 2; private val IN_COMMUNICATION = 3

    @Test fun `answered is what the phone says - in a call, whatever notification speaks and whether or not its clock runs`() {
        assertTrue("the dialer's note for the rear screen, no clock on it, while he talks", LivePick.callTalking(IN_CALL, chronometer = false))
        assertTrue(LivePick.callTalking(IN_CALL, chronometer = true))
        assertTrue("an app's call", LivePick.callTalking(IN_COMMUNICATION, chronometer = false))
    }

    @Test fun `ringing is ringing, even on a notification whose clock already runs`() {
        assertFalse(LivePick.callTalking(RINGTONE, chronometer = false))
        assertFalse(LivePick.callTalking(RINGTONE, chronometer = true))
    }

    @Test fun `a phone whose mode says nothing - the notification's own clock decides, as before`() {
        assertTrue(LivePick.callTalking(NORMAL, chronometer = true))
        assertFalse(LivePick.callTalking(NORMAL, chronometer = false))
    }

    @Test fun `of the dialer's four notifications as they were up while he talked, the call's own has the page, in whatever order they spoke`() {
        // The dialer's notifications during a call: id 10000 (its recorder), id 100 tag backscreen (its note for the
        // rear screen, ongoing, no clock), id 1 (the call going on: ongoing, its clock running), and the group's
        // summary.
        val recorder = LivePick.CallNote("0|com.android.incallui|10000|null|10122", chronometer = false, ongoing = false)
        val backscreen = LivePick.CallNote("0|com.android.incallui|100|backscreen|10122", chronometer = false, ongoing = true)
        val incall = LivePick.CallNote("0|com.android.incallui|1|null|10122", chronometer = true, ongoing = true)
        val summary = LivePick.CallNote("0|com.android.incallui|0|g:Aggregate_AlertingSection|10122", chronometer = false, ongoing = true)
        val four = listOf(recorder, backscreen, incall, summary)
        for (order in listOf(four, four.reversed(), listOf(backscreen, summary, recorder, incall), listOf(incall, backscreen, recorder, summary)))
            assertEquals(incall.key, LivePick.callNote(order))
        // And whichever of them is the one told to the back, the state is the phone's: talking.
        for (note in four) assertTrue(note.key, LivePick.callTalking(IN_CALL, note.chronometer))
        // While it rings there is no running clock yet: the ongoing note, and ringing.
        assertEquals(backscreen.key, LivePick.callNote(listOf(recorder, backscreen)))
        assertFalse(LivePick.callTalking(RINGTONE, backscreen.chronometer))
        assertEquals(null, LivePick.callNote(emptyList()))
    }

    @Test fun `with the hold - the incoming notification going as he answers and the mode in a call is one call, answered`() {
        // The two rules agree: the hold keeps the page over a gap between notifications, the mode says it is answered.
        val h = CallHold()
        h.seen(RINGTONE)
        assertEquals(CallHold.Verdict.Wait(CallHold.AGAIN_MS, talking = true), h.gone(0L, "incoming", ringing = true, mode = IN_CALL))
        assertTrue(LivePick.callTalking(IN_CALL, chronometer = false))
    }
}
