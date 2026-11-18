package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.PinOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The notes put in order on the back: the carousel's motion (NoteCarousel). Lifted, carried, dropped, swapped, back
 * to the page; all of it on springs, drawn every frame, nothing that jumps between two frames, nothing that fades.
 */
class NoteCarouselTest {
    private val w = 976f; private val h = 596f
    private val spacing = NoteCarousel.SPACING
    private fun ids(n: Int) = (0 until n).map { 100L + it }

    /** Frames of 16 ms until it rests (at most [most]); each frame's cards checked against the last's. */
    private fun run(c: NoteCarousel, from: Long, most: Int = 400, each: (List<NoteCarousel.Placed>) -> Unit = {}): Long {
        var now = from
        var last = c.placed(w, h).associateBy { it.id }
        var frames = 0
        while (c.step(now) && frames < most) {
            val placed = c.placed(w, h)
            for (p in placed) last[p.id]?.let { q ->
                // Never a cut: the pages' own measure (EveryPageMovesTest: under 12% of the width in 8 ms), for a frame of 16 ms.
                assertTrue("no jump: ${p.id} moved ${abs(p.cx - q.cx)} px in a frame", abs(p.cx - q.cx) < w * 0.24f)
                assertTrue("no jump in size: ${p.id}", abs(p.scale - q.scale) < 0.12f)
            }
            each(placed)
            last = placed.associateBy { it.id }
            now += 16; frames++
        }
        assertTrue("it comes to rest", frames < most)
        return now
    }

    private fun held(c: NoteCarousel) = c.placed(w, h).single { it.held }

    /** The finger to [x] at [at], the carousel drawn that frame too, as the shell's tick does while a note is carried. */
    private fun dragTo(c: NoteCarousel, x: Float, at: Long): List<Int> = c.drag(x, at).also { c.step(at) }

    @Test fun `held, the page shrinks into the card in the middle of what he sees, a little raised, the neighbours smaller beside it`() {
        val c = NoteCarousel()
        c.enter(ids(5), 102L)
        // The first frame is the page itself: no cut from it.
        val first = held(c)
        assertEquals(w / 2f, first.cx, 0.5f); assertEquals(1f, first.scale, 0.001f)
        var opened = false
        run(c, 1_000) { if (it.single { p -> p.held }.scale < 0.6f) opened = true }
        assertTrue(opened)
        val p = held(c)
        assertEquals(NoteCarousel.CENTRE_X, p.cx, 1f)
        assertEquals(NoteCarousel.CENTRE_Y - NoteCarousel.RAISE, p.cy, 1f)
        assertEquals(NoteCarousel.HELD, p.scale, 0.005f)
        val sides = c.placed(w, h).filter { !it.held }
        val left = sides.single { abs(it.cx - (NoteCarousel.CENTRE_X - spacing)) < 1f }
        val right = sides.single { abs(it.cx - (NoteCarousel.CENTRE_X + spacing)) < 1f }
        for (s in listOf(left, right)) { assertTrue(s.scale < p.scale); assertTrue("a little darker", s.dim > 0f) }
        assertEquals(101L, left.id); assertEquals(103L, right.id)
        // In front: drawn last.
        assertTrue(c.placed(w, h).last().held)
        // Some of each neighbour is seen beside it, right of the lenses (x 296).
        val heldLeft = p.cx - w * p.scale / 2f
        assertTrue(heldLeft - 296f > 100f)
        assertTrue(left.cx + w * left.scale / 2f > heldLeft)
    }

    @Test fun `carried, each note it passes makes room - one answer for each, and the order is the move`() {
        for (n in 2..12) for (from in 0 until n) for (to in 0 until n) {
            val c = NoteCarousel()
            val start = ids(n)
            c.enter(start, start[from])
            var now = run(c, 0)
            c.grab(600f, now, w)
            val passes = ArrayList<Int>()
            // The finger across as a finger goes (a place in about 0.2 s), a frame at a time.
            val steps = 12 * maxOf(1, abs(to - from))
            for (k in 1..steps) { now += 16; passes += dragTo(c, 600f + (to - from) * spacing * k / steps, now) }
            assertEquals("$n: $from -> $to passes ${abs(to - from)} notes", abs(to - from), passes.size)
            assertEquals("$n: each pass is the next place", (if (to >= from) (from + 1..to) else (from - 1 downTo to)).toList(), passes)
            c.drop(600f + (to - from) * spacing, now + 16)
            assertEquals(PinOrder.move(start, from, to), c.ids)
            assertEquals(to, c.heldAt)
            run(c, now + 16)
            assertEquals(NoteCarousel.CENTRE_X, held(c).cx, 1f)
        }
    }

    @Test fun `the row follows the finger and the held note is where the finger has it`() {
        val c = NoteCarousel()
        c.enter(ids(6), 102L)
        var now = run(c, 0)
        c.grab(500f, now, w)
        now += 16; dragTo(c, 560f, now)
        // Where the finger has it, at once; the row's camera after it.
        assertTrue(held(c).cx > NoteCarousel.CENTRE_X + 10f)
        for (k in 0 until 30) { now += 16; c.drag(560f, now); c.step(now) }
        assertEquals("the row has come after it", NoteCarousel.CENTRE_X, held(c).cx, 2f)
    }

    @Test fun `let go fast, the note takes the finger's speed into its place - it goes on a little past it and back`() {
        val c = NoteCarousel()
        c.enter(ids(6), 102L)
        var now = run(c, 0)
        c.grab(400f, now, w)
        // Quick, a place in 60 ms, and let go just past the middle of the next place.
        for (k in 1..5) { now += 12; dragTo(c, 400f + spacing * 1.1f * k / 5, now) }
        c.drop(400f + spacing * 1.1f, now + 8)
        assertEquals(3, c.heldAt)
        var furthest = 0f
        run(c, now + 8) { furthest = maxOf(furthest, c.heldPlace) }
        assertTrue("past its place by the finger's speed ($furthest)", furthest > 3.05f)
        assertEquals(NoteCarousel.CENTRE_X, held(c).cx, 1f)
        // Slowly, and stood still before the lift: no overshoot to speak of.
        val s = NoteCarousel()
        s.enter(ids(6), 102L)
        now = run(s, 0)
        s.grab(400f, now, w)
        for (k in 1..60) { now += 16; dragTo(s, 400f + spacing * k / 60, now) }
        for (k in 1..10) { now += 16; dragTo(s, 400f + spacing, now) }
        s.drop(400f + spacing, now + 16)
        var past = 0f
        run(s, now + 16) { past = maxOf(past, s.heldPlace - 3f) }
        assertTrue("slow: hardly past ($past)", past < 0.02f)
    }

    @Test fun `past either end it gives and comes back - no place beyond the last`() {
        val c = NoteCarousel()
        c.enter(ids(3), 102L)
        var now = run(c, 0)
        c.grab(500f, now, w)
        val passes = ArrayList<Int>()
        for (k in 1..20) { now += 16; passes += dragTo(c, 500f + spacing * 3f * k / 20, now) }
        assertEquals(emptyList<Int>(), passes)
        assertTrue(held(c).cx < NoteCarousel.CENTRE_X + spacing * NoteCarousel.RUBBER + 1f)
        assertFalse("nothing changed", c.drop(500f + spacing * 3f, now + 16))
        run(c, now + 16)
        assertEquals(ids(3), c.ids)
    }

    @Test fun `a tap on another swaps the two - both move there at once, past each other, not a cut`() {
        val c = NoteCarousel()
        c.enter(ids(5), 102L)
        val now = run(c, 0)
        assertTrue(c.swapWith(103L))
        assertEquals(listOf(100L, 101L, 103L, 102L, 104L), c.ids)
        var crossed = false
        var dipped = false
        var heldWasBehindOrBeside = false
        run(c, now) { placed ->
            val a = placed.first { it.id == 102L }; val b = placed.first { it.id == 103L }
            if (abs(a.cx - b.cx) < 40f) crossed = true
            if (b.cy > NoteCarousel.CENTRE_Y + 4f) dipped = true
            if (placed.last().id != 102L) heldWasBehindOrBeside = true
        }
        assertTrue("they pass each other", crossed)
        assertTrue("the other goes under", dipped)
        assertFalse("the held note stays in front the whole time", heldWasBehindOrBeside)
        assertEquals(NoteCarousel.CENTRE_X, held(c).cx, 1f)
        assertEquals(NoteCarousel.CENTRE_X - spacing, c.placed(w, h).single { it.id == 103L }.cx, 1f)
        assertFalse("not with itself", c.swapWith(102L))
    }

    @Test fun `a tap lands on the card under it - the held one in front, a neighbour beside it, else the ground`() {
        val c = NoteCarousel()
        c.enter(ids(4), 101L)
        run(c, 0)
        assertEquals(101L, c.hit(NoteCarousel.CENTRE_X, NoteCarousel.CENTRE_Y, w, h))
        assertEquals("where they overlap, the one in front", 101L, c.hit(NoteCarousel.CENTRE_X - 190f, NoteCarousel.CENTRE_Y, w, h))
        assertEquals(102L, c.hit(NoteCarousel.CENTRE_X + 230f, NoteCarousel.CENTRE_Y, w, h))
        assertEquals(100L, c.hit(NoteCarousel.CENTRE_X - 260f, NoteCarousel.CENTRE_Y, w, h))
        assertNull("above the cards: the ground", c.hit(NoteCarousel.CENTRE_X, 40f, w, h))
        assertNull("below them", c.hit(NoteCarousel.CENTRE_X, 560f, w, h))
    }

    @Test fun `leaving, the held note grows back into the page exactly where the page is, then the page is the page`() {
        val c = NoteCarousel()
        c.enter(ids(4), 101L)
        var now = run(c, 0)
        c.swapWith(102L)
        now = run(c, now)
        c.leave()
        assertFalse("not taking a finger on its way back", c.open)
        var last: NoteCarousel.Placed? = null
        var frames = 0
        while (c.step(now)) { last = held(c); now += 16; frames++ }
        assertTrue("it moves back, not a cut ($frames frames)", frames > 8)
        assertNotNull(last)
        assertEquals(w / 2f, last!!.cx, 3f); assertEquals(1f, last.scale, 0.02f)
        assertFalse(c.active)
        assertEquals(emptyList<NoteCarousel.Placed>(), c.placed(w, h))
    }

    @Test fun `left in the middle of a carry, the note is dropped where it is`() {
        val c = NoteCarousel()
        c.enter(ids(4), 100L)
        var now = run(c, 0)
        c.grab(400f, now, w)
        for (k in 1..24) { now += 16; dragTo(c, 400f + spacing * 2f * k / 24, now) }
        // The order it made is answered, to be told: only a lift or a swap tells it otherwise, and the lift never comes.
        assertEquals(PinOrder.move(ids(4), 0, 2), c.leave())
        assertFalse(c.carrying)
        assertEquals(2, c.heldAt)
        run(c, now)
        assertFalse(c.active)
    }

    @Test fun `whatever ends arranging under a carrying finger, the order it made is answered to be told - once, and only if it changed`() {
        for (n in 2..12) for (from in 0 until n) for (to in 0 until n) for (dark in listOf(false, true)) {
            val c = NoteCarousel()
            val start = ids(n)
            c.enter(start, start[from])
            var now = run(c, 0)
            c.grab(600f, now, w)
            val steps = 12 * maxOf(1, abs(to - from))
            for (k in 1..steps) { now += 16; dragTo(c, 600f + (to - from) * spacing * k / steps, now) }
            // A pop-up, another app, an urgent call (leave) or the back dark, the list changed (reset), finger still down.
            val untold = if (dark) c.reset() else c.leave()
            if (from == to) assertNull("$n: $from -> $to: nothing new", untold)
            else assertEquals("$n: $from -> $to (${if (dark) "dark" else "left"})", PinOrder.move(start, from, to), untold)
            // The finger's lift after it finds nothing carried: nothing is told twice.
            assertFalse(c.drop(600f + (to - from) * spacing, now + 16))
            assertNull(c.leave()); assertNull(c.reset())
        }
        // Carried there and back: the order is what it was, nothing to tell.
        val c = NoteCarousel()
        c.enter(ids(4), 101L)
        var now = run(c, 0)
        c.grab(500f, now, w)
        for (k in 1..12) { now += 16; dragTo(c, 500f + spacing * k / 12, now) }
        for (k in 1..12) { now += 16; dragTo(c, 500f + spacing - spacing * k / 12, now) }
        assertNull(c.leave())
        // A swap is told by itself (the shell's arrangeTap): leaving after one answers nothing more.
        val s = NoteCarousel()
        s.enter(ids(4), 101L)
        run(s, 0)
        assertTrue(s.swapWith(103L))
        assertNull(s.leave())
    }

    @Test fun `a payload from before his move does not put the old order back - while carried, after the last pass, as it leaves`() {
        val pins = ids(5).map { dev.kb896.aiusage.data.Pin("note $it", it) }
        val c = NoteCarousel()
        c.enter(ids(5), 101L)
        var now = run(c, 0)
        // Off: the payload as it is.
        assertEquals(pins, NoteCarousel().ordered(pins))
        c.grab(400f, now, w)
        for (k in 1..24) { now += 16; dragTo(c, 400f + spacing * 2f * k / 24, now) }
        val arranged = PinOrder.move(ids(5), 1, 3)
        assertEquals(arranged, c.ids)
        // A payload in the app's old order lands now, after the last pass and before the lift.
        val stale = c.ordered(pins)
        assertEquals("the carousel's order over it", arranged, stale.map { it.at })
        // The lift crosses no further place: what is told is the carousel's order (the shell puts its list in it first).
        assertTrue(c.drop(400f + spacing * 2f, now + 16))
        assertEquals(arranged, PinOrder.applyPins(pins, c.ids).map { it.at })
        // Pinned since: in front; binned since: not there; the rest in his order.
        val pinned = listOf(dev.kb896.aiusage.data.Pin("new", 999L)) + pins.filter { it.at != 104L }
        assertEquals(listOf(999L) + arranged.filter { it != 104L }, c.ordered(pinned).map { it.at })
        // On its way back to the page still his order (the page it grows into is the note it holds).
        c.leave()
        assertEquals(arranged, c.ordered(pins).map { it.at })
        assertEquals(101L, c.ordered(pins)[c.heldAt].at)
        run(c, now + 16)
        assertFalse(c.active)
        assertEquals("off again: the payload as it is", pins, c.ordered(pins))
        // Only giving (one note): nothing to put in order.
        val g = NoteCarousel()
        g.give(100L)
        val one = listOf(dev.kb896.aiusage.data.Pin("new", 999L), pins[0])
        assertEquals(one, g.ordered(one))
    }

    @Test fun `with one note it gives a little under the finger and back, and changes nothing`() {
        val c = NoteCarousel()
        c.give(100L)
        assertTrue(c.active)
        var smallest = 1f
        run(c, 0) { smallest = minOf(smallest, it.single().scale) }
        assertTrue("a give ($smallest)", smallest < 0.97f && smallest > 0.85f)
        assertFalse(c.active)
    }

    @Test fun `no finger is taken before it is open or on its way back`() {
        val c = NoteCarousel()
        c.grab(400f, 0, w)
        assertFalse(c.carrying)
        assertFalse(c.swapWith(1L))
        c.enter(ids(3), 100L)
        c.leave()
        c.grab(400f, 0, w)
        assertFalse(c.carrying)
    }

    @Test fun `a late frame neither throws it nor makes it leap`() {
        val c = NoteCarousel()
        c.enter(ids(3), 101L)
        c.step(0); c.step(16)
        val before = held(c).scale
        c.step(5_000)   // five seconds late
        assertTrue(abs(held(c).scale - before) < 0.2f)
    }
}
