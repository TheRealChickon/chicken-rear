package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dim by hand: how opaque its layer is for the light the back should give. */
class RearDimTest {
    @Test fun `over a scene at its in-hand level the back gives the dim level`() {
        val a = RearDim.alphaFor(want = 0.42f, has = 0.74f)
        assertEquals(0.42f, 0.74f * (1f - a), 0.001f)
    }

    @Test fun `a back as dim as that already gets no layer`() {
        assertEquals(0f, RearDim.alphaFor(0.42f, 0.42f), 0f)
        assertEquals(0f, RearDim.alphaFor(0.42f, 0.3f), 0f)
    }

    @Test fun `never so opaque that an app under it would lose its touch`() {
        assertTrue(RearDim.alphaFor(0.05f, 1f) <= 0.8f)
        assertEquals(0.58f, RearDim.alphaFor(0.42f, 1f), 0.001f)
    }
}
