package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * "Island · Tint" on the live face (NoticeVeil): the scene reads the island's own timeline at its own pictures, so the
 * colour follows the outline the island's layer shows, at the face's own pace and with no lag; nothing at all while no
 * island is up; it goes in a moment when the island is taken away. And the face's pictures counted while one is up
 * (VeilGaps), said in one line as it goes.
 */
class NoticeVeilTest {
    private val green = 0xFF25D366.toInt()
    private val pillW = 560f

    @Test fun `none up - nothing to draw, nothing moving`() {
        val v = NoticeVeil(); val f = NoticeVeil.Frame()
        assertFalse(v.up)
        assertFalse(v.read(1_000L, f))
        assertEquals(0f, f.amount, 0f); assertFalse(f.moving)
    }

    @Test fun `the tint comes with the opening, in the page's outline as the island has it at that moment`() {
        val v = NoticeVeil(); val f = NoticeVeil.Frame()
        v.begin(green, 10_000L, pillW)
        assertTrue(v.up)
        // A pill: no tint yet (the face is not under it).
        assertFalse(v.read(10_000L + 1_000L, f))
        // Opening: some, moving, its outline the island's own at that moment.
        val t = 1_300L
        assertTrue(v.read(10_000L + t, f))
        val island = MessageIsland.at(t.toFloat(), pillW, MessageIsland.Frame())
        assertEquals(island.cx, f.cx, 0f); assertEquals(island.cy, f.cy, 0f)
        assertEquals(island.w, f.w, 0f); assertEquals(island.h, f.h, 0f); assertEquals(island.r, f.r, 0f)
        assertTrue(f.moving)
        assertTrue(f.amount > 0f && f.amount < NoticeVeil.AMOUNT)
        // Open and held: all of it, still (the face is not drawn again for it).
        assertTrue(v.read(10_000L + MessageIsland.IN_MS + 1_000L, f))
        assertEquals(NoticeVeil.AMOUNT, f.amount, 1e-5f)
        assertFalse(f.moving)
        assertEquals(976f, f.w, 0.01f); assertEquals(596f, f.h, 0.01f)
        // Folding: going with it.
        assertTrue(v.read(10_000L + MessageIsland.IN_MS + MessageIsland.HOLD_MS + 300L, f))
        assertTrue(f.moving && f.amount < NoticeVeil.AMOUNT)
        // Back to a pill: none.
        assertFalse(v.read(10_000L + MessageIsland.IN_MS + MessageIsland.HOLD_MS + MessageIsland.FOLD_MS.toLong() + 10L, f))
    }

    @Test fun `taken away it goes in a moment where it was, and then nothing is up`() {
        val v = NoticeVeil(); val f = NoticeVeil.Frame()
        v.begin(green, 0L, pillW)
        val at = MessageIsland.IN_MS + 2_000L
        assertTrue(v.read(at, f))
        val w = f.w
        v.end(at)
        assertTrue(v.read(at + 60L, f))
        assertTrue(f.amount < NoticeVeil.AMOUNT && f.amount > 0f && f.moving)
        assertEquals(w, f.w, 0f)
        assertFalse(v.read(at + NoticeVeil.FADE_MS, f))
        assertFalse(v.up)
        // An end before any tint: gone at once.
        v.begin(green, 0L, pillW); v.end(500L)
        assertFalse(v.up)
    }

    @Test fun `an island whose face does not tint itself is up for the count, with nothing to draw`() {
        val v = NoticeVeil(); val f = NoticeVeil.Frame()
        v.begin(green, 0L, pillW, tints = false)
        assertTrue(v.up)
        assertFalse(v.read(MessageIsland.IN_MS + 1_000L, f))
        v.end(MessageIsland.IN_MS + 1_000L)
        assertFalse(v.up)
    }

    @Test fun `a finger holding the page across moves the tint with it, and a new time moves its timeline`() {
        val v = NoticeVeil(); val f = NoticeVeil.Frame()
        v.begin(green, 0L, pillW)
        v.dx = 120f
        assertTrue(v.read(3_000L, f))
        assertEquals(488f + 120f, f.cx, 0.01f)
        v.retime(1_000L)
        assertTrue(v.read(2_500L, f))
        assertTrue(f.moving)      // a second and a half in again: opening
    }

    @Test fun `the app's colour three ways - as much light as the picture had, the beam never brighter`() {
        val s = FloatArray(3); val l = FloatArray(3); val h = FloatArray(3)
        NoticeVeil.colours(green, s, l, h)
        assertEquals(0.95f, 0.2126f * s[0] + 0.7152f * s[1] + 0.0722f * s[2], 0.01f)
        assertTrue(max(s[0], max(s[1], s[2])) <= 1.25f + 1e-5f)
        assertTrue("green", s[1] > s[0] && s[1] > s[2])
        assertEquals(NoticeVeil.MOTE_LUMA, 0.2126f * l[0] + 0.7152f * l[1] + 0.0722f * l[2], 0.02f)
        assertEquals(1f, max(h[0], max(h[1], h[2])), 1e-6f)
        // A grey app: grey, as much light.
        NoticeVeil.colours(0xFF9A9A9A.toInt(), s, l, h)
        assertEquals(s[0], s[1], 1e-4f); assertEquals(s[1], s[2], 1e-4f)
    }

    @Test fun `the face's pictures while one is up are counted and said once, with the longest gap`() {
        val g = VeilGaps()
        assertNull(g.picture(false, 0L))
        var ns = 1_000_000_000L
        for (i in 0 until 120) { assertNull(g.picture(true, ns)); ns += 8_333_333L }
        // One stutter of 50 ms.
        ns += 41_666_667L
        assertNull(g.picture(true, ns))
        val line = g.picture(false, ns + 8_000_000L)
        assertNotNull(line)
        assertTrue(line!!, line.contains("121 pictures") && line.contains("the longest gap 50.0 ms") && line.contains("1 gaps over 40 ms"))
        // Said once only.
        assertNull(g.picture(false, ns + 16_000_000L))
    }
}
