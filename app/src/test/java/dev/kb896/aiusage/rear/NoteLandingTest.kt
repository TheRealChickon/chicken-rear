package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.place.Here
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pin always ends on the notes page showing the note just pinned, also a note for a place he is not at, or one
 * that turns out not to be here while it lands; after that landing the note follows the rule (on the back only while
 * he is there: place/Here).
 */
class NoteLandingTest {
    // Made-up notes and places.
    private val plain = Pin("plain", 1)
    private val away = Pin("for the shop", 9, 10.5, 20.5, radius = 300, here = false)
    private val there = Pin("for the gym", 8, 10.0, 20.0, radius = 150, here = true)

    @Test fun `a fresh note for a place lands, here or not - a plain one is always listed`() {
        val l = NoteLanding()
        assertFalse(l.fresh(plain, 0L)); assertEquals(0L, l.at)
        assertFalse(l.fresh(null, 0L))
        assertTrue(l.fresh(away, 0L)); assertEquals(9L, l.at)
        // It is listed while it lands, and only it.
        assertEquals(listOf("for the shop", "plain"), Here.onBack(listOf(away, Pin("towels", 5, 10.0, 20.0), plain), l.at).map { it.text })
        assertTrue(l.fresh(there, 0L)); assertEquals(8L, l.at)
    }

    @Test fun `a note for a place thought here that turns out left while its pin lands - listed until it has landed`() {
        val l = NoteLanding()
        l.fresh(there, 0L)
        // The fix at pin time (or Play services' first look as the fence is set) finds the place left: the next payload.
        val left = there.copy(here = false)
        assertEquals(listOf("for the gym", "plain"), Here.onBack(listOf(left, plain), l.at).map { it.text })
        l.page(true, 2_000L)
        assertTrue(l.page(false, 10_000L))
        assertEquals(listOf("plain"), Here.onBack(listOf(left, plain), l.at).map { it.text })
        // Still here as it lands: it stays after, by the rule.
        val k = NoteLanding()
        k.fresh(there, 0L); k.page(true, 2_000L); k.page(false, 10_000L)
        assertEquals(listOf("for the gym", "plain"), Here.onBack(listOf(there, plain), k.at).map { it.text })
    }

    @Test fun `seen on the notes page, it is over when the page goes down`() {
        val l = NoteLanding()
        l.fresh(away, 1_000L)
        assertFalse("the page comes up with it", l.page(true, 3_000L))
        assertFalse("still up when its time is up: it stays while he reads", l.page(true, 1_000L + NoteLanding.MOST_MS))
        assertTrue("the confirmation ran out, or he sent it down", l.page(false, 11_000L + NoteLanding.MOST_MS))
        assertEquals(0L, l.at)
        assertEquals(listOf("plain"), Here.onBack(listOf(away, plain), l.at).map { it.text })
        assertFalse("over once", l.page(false, 30_000L))
    }

    @Test fun `the page going down before the pin arrives does not end it - its own page does`() {
        val l = NoteLanding()
        l.fresh(away, 0L)
        assertFalse(l.page(false, 500L))
        assertEquals(9L, l.at)
        l.page(true, 2_600L)
        assertTrue(l.page(false, 10_600L))
    }

    @Test fun `a landing nobody saw (a dark back) is over at its time`() {
        val l = NoteLanding()
        l.fresh(away, 0L)
        assertFalse(l.page(false, NoteLanding.MOST_MS - 1))
        assertTrue(l.page(false, NoteLanding.MOST_MS))
        assertEquals(0L, l.at)
    }

    @Test fun `a newer landing takes the place of an older one, and clear ends it`() {
        val l = NoteLanding()
        l.fresh(away, 0L)
        l.fresh(away.copy(text = "another", at = 12), 100L)
        assertEquals(12L, l.at)
        l.clear()
        assertEquals(0L, l.at)
        assertFalse(l.page(false, 99_000L))
    }

    @Test fun `long enough for either pin to land, ours or Xiaomi's`() {
        assertTrue(NoteLanding.MOST_MS > PinVia.ENTER_LATEST + PinVia.OURS_AFTER + PinVia.OURS_MS)
        assertTrue(NoteLanding.MOST_MS > PinBeam.ARRIVE_AT + PinBeam.BACK_MS)
    }
}
