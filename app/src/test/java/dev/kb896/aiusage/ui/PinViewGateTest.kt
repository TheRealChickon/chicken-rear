package dev.kb896.aiusage.ui

import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.ui.PinViewGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether the front shows a pinned picture, and which: one number in, his own picture out; never in a call, never over the lock. */
class PinViewGateTest {
    private val pins = listOf(
        Pin("words only", 300L),
        Pin("A caption", 200L, image = "200-478x359.jpg"),
        Pin("", 100L, image = "100-321x428.webp"),
        // Stored wrongly (never by us): a name that is a path is not a picture of ours.
        Pin("", 50L, image = "../../shared_prefs/x.xml"),
    )

    @Test fun `the note's time is the one input - zero, negative or missing asks for nothing`() {
        assertEquals(200L, PinViewGate.at(200L))
        assertEquals(0L, PinViewGate.at(0L))
        assertEquals(0L, PinViewGate.at(-5L))
        assertEquals(Verdict.NOT_ASKED, PinViewGate.verdict(0L, null, inCall = false, locked = false))
    }

    @Test fun `only his note of that time with a picture of ours is shown`() {
        assertSame(pins[1], PinViewGate.pin(pins, 200L))
        assertSame(pins[2], PinViewGate.pin(pins, 100L))
        assertNull("a note of words", PinViewGate.pin(pins, 300L))
        assertNull("no such note", PinViewGate.pin(pins, 999L))
        assertNull("a name that is a path", PinViewGate.pin(pins, 50L))
        assertNull(PinViewGate.pin(pins, 0L))
        assertEquals(Verdict.NO_PICTURE, PinViewGate.verdict(300L, PinViewGate.pin(pins, 300L), inCall = false, locked = false))
    }

    @Test fun `never during a call, never over the lock screen`() {
        val p = pins[1]
        assertEquals(Verdict.SHOW, PinViewGate.verdict(200L, p, inCall = false, locked = false))
        assertEquals(Verdict.UNLOCK_FIRST, PinViewGate.verdict(200L, p, inCall = false, locked = true))
        assertEquals(Verdict.IN_CALL, PinViewGate.verdict(200L, p, inCall = true, locked = false))
        assertEquals("a call wins over the lock: nothing asked of him", Verdict.IN_CALL, PinViewGate.verdict(200L, p, inCall = true, locked = true))
        assertFalse(Verdict.IN_CALL.shows); assertFalse(Verdict.NO_PICTURE.shows); assertFalse(Verdict.NOT_ASKED.shows)
        // As he unlocks, asked again.
        assertTrue(PinViewGate.afterUnlock(p, inCall = false, locked = false))
        assertFalse("the lock is back", PinViewGate.afterUnlock(p, inCall = false, locked = true))
        assertFalse("a call came", PinViewGate.afterUnlock(p, inCall = true, locked = false))
        assertFalse("binned meanwhile", PinViewGate.afterUnlock(null, inCall = false, locked = false))
    }

    @Test fun `a call is any audio mode but normal - ringing, talking, a call or chat app's`() {
        assertFalse(PinViewGate.inCall(0))
        for (m in 1..6) assertTrue("mode $m", PinViewGate.inCall(m))
        assertFalse("unknown", PinViewGate.inCall(-1))
    }
}
