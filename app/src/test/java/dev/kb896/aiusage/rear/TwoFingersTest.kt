package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoFingersTest {

    private class Back(val mode: SceneTouch.Mode = SceneTouch.Mode.SCENE, val tracks: Boolean = false) {
        val touches = RearTouches(90399, 57199)
        val scene = SceneTouch()
        val drag = PageDrag()
        val said = ArrayList<SceneTouch.Out>()
        val drags = ArrayList<PageDrag.Out>()
        /** A page that followed the finger was put back by the crowding. */
        var wentBack = 0

        fun feed(lines: List<String>, at: Long) {
            for (l in lines) {
                val got = touches.feed(l, at)
                val c = if (touches.crowdBegan) RearTouches.crowdIndex(got) else -1
                got.forEachIndexed { i, e -> if (i == c) crowd(); one(e) }
                if (c == got.size) crowd()
            }
        }

        private fun crowd() { scene.crowded(); if (drag.crowded()) wentBack++ }

        private fun one(e: RearTouches.Event) {
            for (d in drag.feed(e) { _, _, _, _ -> tracks }) { drags += d; if (d is PageDrag.Out.Begin) scene.take() }
            said += scene.feed(e) { mode }
        }

        /** What came of the fingers, the landings' own words (asks for a hold's check) left out. */
        fun gestures() = said.filter { it !is SceneTouch.Out.FirstDown && it !is SceneTouch.Out.NotesDown && it !is SceneTouch.Out.SecondDown }
    }

    private fun hex(v: Int) = "%08x".format(v)
    private val syn = "0000 0000 00000000"
    private fun slot(n: Int) = "0003 002f ${hex(n)}"
    /** Panel units in (raw = units x 100 on this panel). */
    private fun land(s: Int, id: Int, x: Int, y: Int) = listOf(slot(s), "0003 0039 ${hex(id)}", "0003 0035 ${hex(x * 100)}", "0003 0036 ${hex(y * 100)}", syn)
    private fun move(s: Int, x: Int, y: Int) = listOf(slot(s), "0003 0035 ${hex(x * 100)}", "0003 0036 ${hex(y * 100)}", syn)
    private fun lift(s: Int) = listOf(slot(s), "0003 0039 ffffffff", syn)

    @Test fun `two fingers tapped together are no tap, and the tap after them is no double tap`() {
        val b = Back()
        b.feed(land(0, 1, 500, 300), 0L)
        b.feed(land(1, 2, 600, 300), 30L)
        b.feed(lift(1), 110L)
        b.feed(lift(0), 120L)
        assertEquals(emptyList<SceneTouch.Out>(), b.gestures())
        // One finger, a moment later: a tap of its own, not the second of a double tap.
        b.feed(land(0, 3, 505, 300), 300L)
        b.feed(lift(0), 350L)
        assertEquals(listOf(SceneTouch.Out.Tap(505f, 300f, SceneTouch.Mode.SCENE)), b.gestures())
    }

    @Test fun `a tap and then two fingers are no double tap, and held they do not switch the back off`() {
        val b = Back()
        b.feed(land(0, 1, 500, 300), 0L)
        b.feed(lift(0), 60L)
        assertTrue(b.gestures().single() is SceneTouch.Out.Tap)
        b.feed(land(0, 2, 505, 300), 200L)               // would be a double tap's second...
        assertTrue(b.said.last() is SceneTouch.Out.SecondDown)
        b.feed(land(1, 3, 650, 300), 230L)               // ...but a second finger comes with it
        assertFalse("no back off", b.scene.held(200L + SceneTouch.HOLD_MS + 20L))
        b.feed(lift(1), 900L); b.feed(lift(0), 910L)
        assertEquals(1, b.gestures().size)
    }

    @Test fun `held with a second finger it is no picker, and on the notes no note lifted`() {
        val face = Back()
        face.feed(land(0, 1, 500, 300), 0L)
        face.feed(land(1, 2, 650, 300), 100L)
        assertFalse(face.scene.heldAlone(SceneTouch.PICKER_HOLD_MS + 20L))
        assertFalse(face.scene.heldAlone(5_000L))
        val notes = Back(SceneTouch.Mode.NOTES)
        notes.feed(land(0, 1, 500, 300), 0L)
        notes.feed(land(1, 2, 650, 300), 100L)
        assertFalse(notes.scene.heldOnNotes(SceneTouch.NOTE_HOLD_MS + 20L))
        // Alone, as before, the hold is the picker's.
        val alone = Back()
        alone.feed(land(0, 1, 500, 300), 0L)
        assertTrue(alone.scene.heldAlone(SceneTouch.PICKER_HOLD_MS + 20L))
    }

    @Test fun `a stroke with a second finger down is no swipe`() {
        val b = Back()
        b.feed(land(0, 1, 800, 300), 0L)
        b.feed(land(1, 2, 820, 400), 40L)
        for (i in 1..8) b.feed(move(0, 800 - 30 * i, 300), 40L + 15L * i)
        b.feed(lift(0), 200L); b.feed(lift(1), 210L)
        assertEquals(emptyList<SceneTouch.Out>(), b.gestures())
    }

    @Test fun `a page following the finger goes back when a second finger lands, and turns at nothing after`() {
        val b = Back(tracks = true)
        b.feed(land(0, 1, 800, 300), 0L)
        for (i in 1..4) b.feed(move(0, 800 - 20 * i, 300), 15L * i)
        assertTrue(b.drags.first() is PageDrag.Out.Begin)
        b.feed(land(1, 2, 820, 420), 80L)
        assertEquals("put back", 1, b.wentBack)
        val before = b.drags.size
        for (i in 5..12) b.feed(move(0, 800 - 20 * i, 300), 15L * i)
        b.feed(lift(0), 200L)
        assertEquals("no more of it: no place, no turn at the lift", before, b.drags.size)
        assertTrue(b.drags.none { it is PageDrag.Out.End })
        assertEquals(emptyList<SceneTouch.Out>(), b.gestures())
    }

    @Test fun `a resting grip finger and one real finger work as before - a tap, a swipe`() {
        val tap = Back()
        tap.feed(land(0, 1, 50, 300), 0L)                // the hand's finger at the edge, resting
        tap.feed(land(1, 2, 600, 300), 2_000L)
        tap.feed(lift(1), 2_080L)
        assertEquals(SceneTouch.Out.Tap(600f, 300f, SceneTouch.Mode.SCENE), tap.gestures().last())
        val swipe = Back()
        swipe.feed(land(0, 1, 50, 300), 0L)
        swipe.feed(land(1, 2, 800, 300), 2_000L)
        for (i in 1..8) swipe.feed(move(1, 800 - 30 * i, 300), 2_000L + 15L * i)
        swipe.feed(lift(1), 2_150L)
        val s = swipe.gestures().filterIsInstance<SceneTouch.Out.Swipe>().single()
        assertEquals(SceneTouch.Way.LEFT, s.way)
    }

    @Test fun `two grip fingers, one of them drifted before it settled, and a tap - the tap counts`() {
        // The review's case: the hand's second finger crept 17 units as it settled. Resting is judged on the last
        // 700 ms, not on the whole touch, so it is let go with the other and the real finger taps.
        for (drift in listOf(0, 17, 20, 60)) {
            val b = Back()
            b.feed(land(0, 1, 50, 300), 0L)
            b.feed(land(1, 2, 60, 450), 100L)
            b.feed(move(1, 60, 450 + drift), 400L)
            b.feed(land(2, 3, 600, 300), 2_000L)
            b.feed(lift(2), 2_080L)
            assertEquals("drift $drift", SceneTouch.Out.Tap(600f, 300f, SceneTouch.Mode.SCENE), b.gestures().last())
        }
    }

    @Test fun `a finger still moving when the next one lands is no resting finger - it crowds`() {
        val b = Back()
        b.feed(land(0, 1, 500, 300), 0L)
        b.feed(land(1, 2, 650, 300), 50L)
        b.feed(lift(0), 300L)
        // The other, down long since, moved just now: it is doing something, not holding the phone.
        b.feed(move(1, 650, 340), 1_700L)
        b.feed(land(0, 3, 400, 300), 2_000L)
        b.feed(lift(0), 2_060L)
        assertEquals(emptyList<SceneTouch.Out>(), b.gestures())
    }

    @Test fun `a lost lift crowds no later finger once it has lain still, and dropped events forget it at once`() {
        // The review's case: a second finger moves, its -1 is lost, the first lifts.
        val b = Back()
        b.feed(land(0, 1, 500, 300), 0L)
        b.feed(land(1, 2, 600, 300), 30L)
        b.feed(move(1, 640, 300), 60L)
        b.feed(lift(0), 100L)
        for ((i, t) in listOf(1_000L, 5_000L, 60_000L).withIndex()) {
            b.feed(land(0, 10 + i, 500, 300), t)
            b.feed(lift(0), t + 50L)
            assertEquals("at $t", SceneTouch.Out.Tap(500f, 300f, SceneTouch.Mode.SCENE), b.gestures().last())
            b.said.clear()
        }
        // The same, with the kernel's SYN_DROPPED where the lift went: the very next tap counts.
        val d = Back()
        d.feed(land(0, 1, 500, 300), 0L)
        d.feed(land(1, 2, 600, 300), 30L)
        d.feed(move(1, 640, 300), 60L)
        d.feed(listOf("0000 0003 00000000"), 70L)
        d.feed(lift(0), 100L)
        d.feed(land(0, 3, 500, 300), 400L)
        d.feed(lift(0), 450L)
        assertEquals(SceneTouch.Out.Tap(500f, 300f, SceneTouch.Mode.SCENE), d.gestures().last())
    }

    @Test fun `in the picker a second finger takes the drag away and chooses nothing`() {
        val p = PickerTouch()
        p.feed(RearTouches.Down(500f, 300f, 0L))
        p.feed(RearTouches.Move(530f, 300f, 30L))
        assertTrue(p.feed(RearTouches.Move(560f, 300f, 60L)).any { it is PickerTouch.Out.Drag })
        assertEquals(listOf(PickerTouch.Out.Cancel), p.crowded())
        assertTrue(p.feed(RearTouches.Move(600f, 300f, 90L)).isEmpty())
        assertTrue(p.feed(RearTouches.Up(600f, 300f, 120L)).isEmpty())
        // Not dragging: the row it stopped settles on, and the lift is no tap.
        val q = PickerTouch()
        q.feed(RearTouches.Down(500f, 300f, 0L))
        assertEquals(listOf<PickerTouch.Out>(PickerTouch.Out.Release(0.0)), q.crowded())
        assertTrue(q.crowded().isEmpty())
        assertTrue(q.feed(RearTouches.Up(500f, 300f, 100L)).isEmpty())
        // Alone, that lift chooses.
        val r = PickerTouch()
        r.feed(RearTouches.Down(500f, 300f, 0L))
        assertTrue(r.feed(RearTouches.Up(500f, 300f, 100L)).contains(PickerTouch.Out.Choose))
    }
}
