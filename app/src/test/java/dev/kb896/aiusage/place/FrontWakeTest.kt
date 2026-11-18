package dev.kb896.aiusage.place

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The look on waking follows the FRONT display's own state (service/FrontScreen), not the screen broadcasts: on this
 * phone the back alone lit makes it "interactive" (SCREEN_ON with the front off), and a front coming on while the
 * back was lit sends no SCREEN_ON at all.
 */
class FrontWakeTest {
    @Test fun `only the front going from off to on is a wake`() {
        PlaceWatch.seedFront(false)
        assertFalse("SCREEN_ON from the back woken by itself: the front still off", PlaceWatch.frontSeen(false))
        assertTrue("the front on (seen by its display's listener, no broadcast needed)", PlaceWatch.frontSeen(true))
        assertFalse("a brightness ramp or refresh change of a front already on", PlaceWatch.frontSeen(true))
        assertFalse("the SCREEN_ON broadcast arriving after the listener: no second look", PlaceWatch.frontSeen(true))
        assertFalse(PlaceWatch.frontSeen(false))
        assertTrue(PlaceWatch.frontSeen(true))
    }

    @Test fun `the first sight of the front is no wake`() {
        PlaceWatch.seedFront(true)
        assertFalse(PlaceWatch.frontSeen(true))
    }
}
