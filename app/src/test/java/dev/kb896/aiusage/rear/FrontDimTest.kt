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
        assertTrue(FrontDim.wanted(faceDown, saidAgoMs = 3_000L, call = false, lifted = false))
        // Off (night's rule, a pocket, on its back), a still while saving, in the hand, the Camera: never.
        for (stay in listOf(0, 1, 3, 4)) assertFalse("stay $stay", FrontDim.wanted(stay, 3_000L, call = false, lifted = false))
    }

    @Test fun `picked up or turned over - the stay rule changes, and it is off`() {
        assertTrue(FrontDim.wanted(faceDown, 100L, false, false))
        assertFalse("in the hand", FrontDim.wanted(3, 100L, false, false))
        assertFalse("on its back", FrontDim.wanted(0, 100L, false, false))
    }

    @Test fun `the phone's own tilt takes it off whatever the app still says`() {
        assertFalse(FrontDim.wanted(faceDown, 100L, call = false, lifted = true))
        // Flat on its front gravity is about -9.8 along z; a little tilt on a soft surface is still face down; on edge, upright or on its back it is lifted.
        for (z in listOf(-9.8f, -9.0f, -7.2f)) assertFalse("z $z", FrontDim.liftedBy(z))
        for (z in listOf(-6.0f, -2f, 0f, 4f, 9.8f)) assertTrue("z $z", FrontDim.liftedBy(z))
    }

    @Test fun `an app gone silent does not keep the front dim - twelve seconds, and it says its level every eight`() {
        assertTrue(FrontDim.wanted(faceDown, 8_500L, false, false))
        assertFalse(FrontDim.wanted(faceDown, FrontDim.STALE_MS, false, false))
        assertFalse(FrontDim.wanted(faceDown, 60_000L, false, false))
        assertTrue("longer than the app's own confirming", FrontDim.STALE_MS > 8_000L)
        assertFalse("a clock that jumped", FrontDim.wanted(faceDown, -5L, false, false))
    }

    @Test fun `a call cancels it`() {
        assertFalse(FrontDim.wanted(faceDown, 100L, call = true, lifted = false))
    }

    @Test fun `a ton - the front keeps about an eighth of its light, comes down slowly and comes back fast`() {
        assertEquals(0.12f, FrontDim.LEFT, 0.001f)
        assertTrue("never wholly black: it is still a lit screen he can find", FrontDim.ALPHA < 0.95f)
        assertTrue("back faster than down", FrontDim.OUT_MS < FrontDim.IN_MS && FrontDim.OUT_MS <= 200f)
    }
}
