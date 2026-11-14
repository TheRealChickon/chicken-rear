package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipStoreTest {
    @Test fun `a file name reads as a title`() {
        assertEquals("Sandevistan", ClipStore.title("sandevistan.wake.mp4"))
        assertEquals("Sandevistan kick", ClipStore.title("sandevistan-kick.wake.mp4"))
        assertEquals("Spine", ClipStore.title("spine.loop.mp4"))
        assertEquals("My own clip", ClipStore.title("my_own_clip.mp4"))
    }

    @Test fun `each slot lists the clips named for it first and keeps the rest`() {
        val clips = listOf("a.loop.mp4", "b.wake.mp4", "c.mp4").map { ClipStore.of(it, "/x/$it") }
        assertEquals(listOf("b.wake.mp4", "c.mp4", "a.loop.mp4"), ClipStore.ordered(clips, forLoop = false).map { it.file })
        assertEquals(listOf("a.loop.mp4", "c.mp4", "b.wake.mp4"), ClipStore.ordered(clips, forLoop = true).map { it.file })
    }

    @Test fun `the black crush switches near-black off and leaves white white`() {
        val cube = ClipStore.crushCube(33)
        // Level 0, 8 and 16 of 255 (cube steps 0, 1, 2) are all off.
        for (i in 0..2) assertEquals(0xFF000000.toInt(), cube[i][i][i])
        assertEquals(0xFFFFFFFF.toInt(), cube[32][32][32])
        // A dim saturated colour keeps its hue: only the brightest channel decides.
        val dimRed = cube[4][0][0]
        assertTrue((dimRed shr 16 and 0xFF) > 0)
        assertEquals(0, dimRed and 0xFFFF)
        // Monotonic along the grey axis.
        var last = -1
        for (i in 0..32) { val v = cube[i][i][i] and 0xFF; assertTrue(v >= last); last = v }
    }
}
