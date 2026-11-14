package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyingOnBackTest {
    private val g = 9.81f

    /** Readings every 200 ms from [from] to [to], at x, y, z. */
    private fun run(b: LyingOnBack, from: Long, to: Long, x: Float = 0f, y: Float = 0f, z: Float = g): List<Boolean?> =
        (from..to step 200).map { b.reading(x, y, z, it) }

    @Test fun `still and flat on its back settles after three seconds`() {
        val b = LyingOnBack()
        assertTrue(run(b, 0, 2_800).all { it == null })
        assertFalse(b.down)
        assertEquals(true, b.reading(0.02f, -0.01f, g, 3_000))
        assertTrue(b.down)
        assertNull(b.reading(0f, 0f, g, 6_000)) // settled: no news
    }

    @Test fun `held flat in the hand never settles - the tremor breaks the stillness`() {
        val b = LyingOnBack()
        // flat enough, but moving by half a m/s² every reading or so, as a hand does
        for (i in 0..100) {
            val wobble = if (i % 2 == 0) 0.25f else -0.25f
            assertNull(b.reading(wobble, -wobble, g + wobble, i * 200L))
        }
        assertFalse(b.down)
    }

    @Test fun `a small drift within the stillness still settles`() {
        val b = LyingOnBack()
        var t = 0L
        var got: Boolean? = null
        while (t <= 3_000) { got = b.reading(0.1f * (t / 1_000f), 0f, g, t); t += 200 } // 0.3 m/s² over three seconds
        assertEquals(true, got)
    }

    @Test fun `tilted, face down or on edge is never lying on its back`() {
        val b = LyingOnBack()
        assertTrue(run(b, 0, 6_000, x = 2f).all { it == null }) // 12° to the side
        assertTrue(run(b, 0, 6_000, z = -g).all { it == null }) // face down
        assertTrue(run(b, 0, 6_000, y = g, z = 0f).all { it == null }) // standing on edge
        assertTrue(run(b, 0, 6_000, z = 8.9f).all { it == null }) // not enough along +z
        assertFalse(b.down)
    }

    @Test fun `a jolt mid-run starts the three seconds over`() {
        val b = LyingOnBack()
        run(b, 0, 2_000)
        assertNull(b.reading(0.8f, 0f, g, 2_200)) // moved past 0.4: a new run from here
        assertNull(b.reading(0.8f, 0f, g, 4_000))
        assertNull(b.reading(0.8f, 0f, g, 5_000))
        assertEquals(true, b.reading(0.8f, 0f, g, 5_200))
    }

    @Test fun `picked up - tilted, turned over or moved - is a lift at once`() {
        fun settled() = LyingOnBack().also { b -> run(b, 0, 3_000); assertTrue(b.down) }
        settled().let { b -> assertEquals(false, b.reading(3f, 0f, 9.3f, 3_200)); assertFalse(b.down) }
        settled().let { b -> assertEquals(false, b.reading(0f, 0f, -g, 3_200)) }
        settled().let { b -> assertEquals(false, b.reading(0f, 0f, g + 3f, 3_200)) } // lifted straight up
    }

    @Test fun `a tap on the front while it lies there is not a lift`() {
        val b = LyingOnBack()
        run(b, 0, 3_000)
        assertNull(b.reading(0.6f, -0.4f, g + 1.2f, 3_200))
        assertNull(b.reading(-0.3f, 0.2f, g - 0.9f, 3_400))
        assertTrue(b.down)
    }

    @Test fun `after a lift it settles again only after three more seconds`() {
        val b = LyingOnBack()
        run(b, 0, 3_000)
        b.reading(0f, 0f, -g, 3_200)
        assertTrue(run(b, 3_400, 6_200).all { it == null })
        assertEquals(true, b.reading(0f, 0f, g, 6_400))
    }

    @Test fun `reset forgets a run and a settled lie`() {
        val b = LyingOnBack()
        run(b, 0, 3_000)
        b.reset()
        assertFalse(b.down)
        assertTrue(run(b, 3_200, 6_000).all { it == null })
        assertEquals(true, b.reading(0f, 0f, g, 6_200))
    }
}
