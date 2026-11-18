package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lying on its front the front is dimmed a lot, and it is never left dim on a phone he has picked up.
 */
class FrontDimTest {
    private val faceDown = FrontDim.FACE_DOWN_LEVEL

    @Test fun `only lying face down, by the app's rule said a moment ago`() {
        assertTrue(FrontDim.wanted(faceDown, saidAgoMs = 3_000L, call = false, resting = true))
        // Off (night's rule, a pocket, on its back), a still while saving, in the hand, the Camera: never.
        for (stay in listOf(0, 1, 3, 4)) assertFalse("stay $stay", FrontDim.wanted(stay, 3_000L, call = false, resting = true))
    }

    @Test fun `picked up or turned over - the stay rule changes, and it is off`() {
        assertTrue(FrontDim.wanted(faceDown, 100L, false, true))
        assertFalse("in the hand", FrontDim.wanted(3, 100L, false, true))
        assertFalse("on its back", FrontDim.wanted(0, 100L, false, true))
    }

    @Test fun `the shared resting decision takes it off whatever the app still says`() {
        assertFalse(FrontDim.wanted(faceDown, 100L, call = false, resting = false))
    }

    @Test fun `an app gone silent does not keep the front dim - twelve seconds, and it says its level every eight`() {
        assertTrue(FrontDim.wanted(faceDown, 8_500L, false, true))
        assertFalse(FrontDim.wanted(faceDown, FrontDim.STALE_MS, false, true))
        assertFalse(FrontDim.wanted(faceDown, 60_000L, false, true))
        assertTrue("longer than the app's own confirming", FrontDim.STALE_MS > 8_000L)
        assertFalse("a clock that jumped", FrontDim.wanted(faceDown, -5L, false, true))
    }

    @Test fun `a call cancels it`() {
        assertFalse(FrontDim.wanted(faceDown, 100L, call = true, resting = true))
    }

    @Test fun `a ton - the front keeps about an eighth of its light, comes down slowly and comes back fast`() {
        assertEquals(0.12f, FrontDim.LEFT, 0.001f)
        assertTrue("never wholly black: it is still a lit screen he can find", FrontDim.ALPHA < 0.95f)
        assertTrue("back faster than down", FrontDim.OUT_MS < FrontDim.IN_MS && FrontDim.OUT_MS <= 200f)
    }
}
