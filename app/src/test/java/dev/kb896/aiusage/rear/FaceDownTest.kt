package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceDownTest {
    private val g = 9.81f

    @Test
    fun `face down settles only after a full second of flat readings`() {
        val f = FaceDown()
        assertNull(f.reading(0f, 0f, -g, 0))
        assertNull(f.reading(0.1f, -0.2f, -g, 500))
        assertNull(f.reading(0f, 0f, -g, 999))
        assertFalse(f.down)
        assertEquals(true, f.reading(0f, 0f, -g, 1_000))
        assertTrue(f.down)
        // settled: further flat readings are no change
        assertNull(f.reading(0f, 0f, -g, 5_000))
    }

    @Test
    fun `a break in the flat run starts the second over`() {
        val f = FaceDown()
        f.reading(0f, 0f, -g, 0)
        assertNull(f.reading(3f, 0f, -g, 600)) // tilted: x beyond 2.2
        assertNull(f.reading(0f, 0f, -g, 700))
        assertNull(f.reading(0f, 0f, -g, 1_600)) // 900 ms since the run began again
        assertEquals(true, f.reading(0f, 0f, -g, 1_700))
    }

    @Test
    fun `face up, on edge or only slightly tipped is never the table`() {
        val f = FaceDown()
        for (t in 0L..5_000L step 100) assertNull(f.reading(0f, 0f, g, t)) // face up
        for (t in 0L..5_000L step 100) assertNull(f.reading(0f, g, 0f, t)) // on edge
        for (t in 0L..5_000L step 100) assertNull(f.reading(0f, 0f, -8.5f, t)) // not enough along -z
        for (t in 0L..5_000L step 100) assertNull(f.reading(0f, 2.2f, -g, t)) // y on the limit is out
        assertFalse(f.down)
    }

    @Test
    fun `lifted at once, on the first reading off the table`() {
        val f = FaceDown()
        f.reading(0f, 0f, -g, 0)
        f.reading(0f, 0f, -g, 1_000)
        assertTrue(f.down)
        assertEquals(false, f.reading(0f, 5f, -7f, 1_050))
        assertFalse(f.down)
        assertNull(f.reading(0f, 5f, -7f, 1_100))
    }

    @Test
    fun `told it is down already, flat readings are no news and the first lift is heard`() {
        val f = FaceDown()
        f.assumeDown()
        assertTrue(f.down)
        assertNull(f.reading(0f, 0f, -g, 0))
        assertNull(f.reading(0f, 0f, -g, 2_000))
        assertEquals(false, f.reading(0f, 0f, g, 2_100))
    }

    @Test
    fun `reset forgets a run in progress and a settled table`() {
        val f = FaceDown()
        f.reading(0f, 0f, -g, 0)
        f.reading(0f, 0f, -g, 1_000)
        f.reset()
        assertFalse(f.down)
        assertNull(f.reading(0f, 0f, -g, 1_100))
        assertNull(f.reading(0f, 0f, -g, 2_000))
        assertEquals(true, f.reading(0f, 0f, -g, 2_100))
    }
}
