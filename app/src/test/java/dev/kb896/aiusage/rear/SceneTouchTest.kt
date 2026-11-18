package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.SceneTouch.Mode
import dev.kb896.aiusage.rear.SceneTouch.Out
import dev.kb896.aiusage.rear.SceneTouch.Way
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTouchTest {
    private fun down(x: Float, y: Float, at: Long) = RearTouches.Down(x, y, at)
    private fun move(x: Float, y: Float, at: Long) = RearTouches.Move(x, y, at)
    private fun up(x: Float, y: Float, at: Long) = RearTouches.Up(x, y, at)
    private val scene = { Mode.SCENE }
    private val none = emptyList<Out>()

    /** One quick swipe from [x0], [y0] to [x1], [y1] on [mode]: what it said, the landing's own word left out. */
    private fun swipe(mode: Mode, x0: Float, y0: Float, x1: Float, y1: Float, ms: Long = 120): List<Out> {
        val t = SceneTouch()
        val m = { mode }
        val out = ArrayList<Out>()
        t.feed(down(x0, y0, 0), m)
        for (i in 1..6) out += t.feed(move(x0 + (x1 - x0) * i / 6f, y0 + (y1 - y0) * i / 6f, ms * i / 6), m)
        out += t.feed(up(x1, y1, ms + 20), m)
        return out
    }

    @Test fun `a tap on the scene is a tap`() {
        val t = SceneTouch()
        assertEquals(listOf(Out.FirstDown), t.feed(down(500f, 300f, 0), scene))
        assertEquals(none, t.feed(move(504f, 303f, 40), scene))
        assertFalse("a tap is not a hold", t.heldAlone(90))
        assertEquals(listOf(Out.Tap(504f, 303f, Mode.SCENE)), t.feed(up(504f, 303f, 90), scene))
    }

    @Test fun `a double tap is one thing, said as the second lifts`() {
        val t = SceneTouch()
        t.feed(down(500f, 300f, 1000), scene)
        assertEquals(listOf(Out.Tap(500f, 300f, Mode.SCENE)), t.feed(up(500f, 300f, 1060), scene))
        assertEquals(listOf(Out.SecondDown), t.feed(down(505f, 298f, 1200), scene))
        assertEquals(listOf(Out.DoubleTap(505f, 298f, Mode.SCENE, 140, 5f, 60)), t.feed(up(505f, 298f, 1260), scene))
        assertFalse("lifted: not held", t.held(1900))
        // And it is not the first of another: a third tap is a tap.
        assertEquals(listOf(Out.FirstDown), t.feed(down(505f, 298f, 1400), scene))
        assertEquals(listOf(Out.Tap(505f, 298f, Mode.SCENE)), t.feed(up(505f, 298f, 1450), scene))
    }

    @Test fun `a real finger's double tap - slow, apart and rolling as it lifts`() {
        val t = SceneTouch()
        t.feed(down(500f, 300f, 1000), scene)
        t.feed(move(512f, 309f, 1090), scene)                       // the tip rolls a millimetre
        assertEquals(1, t.feed(up(518f, 314f, 1130), scene).size)   // a tap of 130 ms
        t.feed(down(610f, 380f, 1130 + 380), scene)                 // 380 ms later, 7 mm away
        t.feed(move(622f, 372f, 1700), scene)
        val out = t.feed(up(626f, 370f, 1130 + 380 + 300), scene)   // held 300 ms: still a tap, not the hold
        assertTrue("said: $out", out.single() is Out.DoubleTap)
    }

    @Test fun `too late or too far is two taps`() {
        for ((x, at) in listOf(500f to 1060L + SceneTouch.DOUBLE_MS + 1, 500f + SceneTouch.DOUBLE_SLOP + 1 to 1200L)) {
            val t = SceneTouch()
            t.feed(down(500f, 300f, 1000), scene); t.feed(up(500f, 300f, 1060), scene)
            assertEquals(listOf(Out.FirstDown), t.feed(down(x, 300f, at), scene))
            assertEquals(listOf(Out.Tap(x, 300f, Mode.SCENE)), t.feed(up(x, 300f, at + 60), scene))
        }
    }

    @Test fun `a double tap is read on every page of ours, and the hold only on the scene`() {
        for (mode in listOf(Mode.USAGE, Mode.NOTES, Mode.CHARGE)) {
            val t = SceneTouch()
            val m = { mode }
            t.feed(down(700f, 300f, 1000), m); t.feed(up(700f, 300f, 1060), m)
            assertEquals("no hold is armed on $mode", none, t.feed(down(700f, 300f, 1200), m))
            assertFalse(t.held(1200 + SceneTouch.HOLD_MS + 10))
            assertEquals(listOf(Out.DoubleTap(700f, 300f, mode, 140, 0f, 100)), t.feed(up(700f, 300f, 1300), m))
        }
    }

    @Test fun `a tap that was a button's is not the first of a double tap`() {
        val t = SceneTouch()
        t.feed(down(500f, 300f, 1000), scene); t.feed(up(500f, 300f, 1060), scene)
        t.forget()
        assertEquals(listOf(Out.FirstDown), t.feed(down(500f, 300f, 1200), scene))
        assertEquals(listOf(Out.Tap(500f, 300f, Mode.SCENE)), t.feed(up(500f, 300f, 1260), scene))
    }

    @Test fun `the second tap held switches off, once, and is no tap as it lifts`() {
        val t = SceneTouch()
        t.feed(down(500f, 300f, 0), scene)
        t.feed(up(500f, 300f, 60), scene)
        assertEquals(listOf(Out.SecondDown), t.feed(down(500f, 300f, 200), scene))
        assertFalse("too soon", t.held(400))
        assertFalse("not the picker", t.heldAlone(200 + SceneTouch.PICKER_HOLD_MS))
        assertTrue(t.held(200 + SceneTouch.HOLD_MS))
        assertFalse(t.held(200 + SceneTouch.HOLD_MS + 50))
        assertEquals(none, t.feed(up(500f, 300f, 1200), scene))
    }

    @Test fun `a press and hold opens the picker, once, and is no tap as it lifts`() {
        val t = SceneTouch()
        assertEquals(listOf(Out.FirstDown), t.feed(down(500f, 300f, 0), scene))
        t.feed(move(503f, 302f, 200), scene)
        assertFalse("too soon", t.heldAlone(499))
        assertTrue(t.heldAlone(500))
        assertFalse(t.heldAlone(600))
        assertEquals(none, t.feed(up(503f, 302f, 1500), scene))
    }

    @Test fun `the hold is 500 ms, longer than a tap may be down, within Xiaomi's 20 units`() {
        assertEquals(500L, SceneTouch.PICKER_HOLD_MS)
        // Longer than a tap, as the notes' hold is: a slow tap is never the picker.
        assertTrue(SceneTouch.PICKER_HOLD_MS > SceneTouch.TAP_MS)
        val at499 = SceneTouch(); at499.feed(down(500f, 300f, 0), scene); assertFalse(at499.heldAlone(499))
        val at500 = SceneTouch(); at500.feed(down(500f, 300f, 0), scene); assertTrue(at500.heldAlone(500))
        // 19 units off is still held (past SLOP's 16, which other readers use); 21 is not.
        val near = SceneTouch(); near.feed(down(500f, 300f, 0), scene); near.feed(move(519f, 300f, 100), scene); assertTrue(near.heldAlone(500))
        val off = SceneTouch(); off.feed(down(500f, 300f, 0), scene); off.feed(move(521f, 300f, 100), scene); assertFalse(off.heldAlone(500))
    }

    @Test fun `a slow tap is a tap, and a double tap or a double tap held whose first tap is slow is still that`() {
        // Every first tap from 300 ms (Xiaomi's long press) to the longest a tap may be down.
        for (downMs in listOf(300L, 303L, 350L, 400L, SceneTouch.TAP_MS)) {
            // Alone: a tap, and the picker's hold, asked as the shell asks it (PICKER_HOLD_MS after landing), says no.
            val tap = SceneTouch()
            tap.feed(down(500f, 300f, 0), scene)
            assertFalse("held $downMs ms: no picker while down", tap.heldAlone(downMs))
            assertEquals("held $downMs ms", listOf(Out.Tap(500f, 300f, Mode.SCENE)), tap.feed(up(500f, 300f, downMs), scene))
            assertFalse("held $downMs ms: no picker after the lift", tap.heldAlone(SceneTouch.PICKER_HOLD_MS))
            // The double tap's dim: the first tap slow, the second quick.
            val dim = SceneTouch()
            dim.feed(down(500f, 300f, 0), scene); dim.feed(up(500f, 300f, downMs), scene)
            assertFalse(dim.heldAlone(SceneTouch.PICKER_HOLD_MS))
            assertEquals(listOf(Out.SecondDown), dim.feed(down(505f, 300f, downMs + 200), scene))
            assertTrue("first tap $downMs ms: a double tap", dim.feed(up(505f, 300f, downMs + 260), scene).single() is Out.DoubleTap)
            // The double tap held: off.
            val off = SceneTouch()
            off.feed(down(500f, 300f, 0), scene); off.feed(up(500f, 300f, downMs), scene)
            assertEquals(listOf(Out.SecondDown), off.feed(down(505f, 300f, downMs + 200), scene))
            assertFalse(off.heldAlone(downMs + 200 + SceneTouch.PICKER_HOLD_MS))
            assertTrue("first tap $downMs ms: held is off", off.held(downMs + 200 + SceneTouch.HOLD_MS))
        }
    }

    @Test fun `a double tap's second held is still the back off at 550 ms, not the picker`() {
        val t = SceneTouch()
        t.feed(down(500f, 300f, 0), scene); t.feed(up(500f, 300f, 60), scene)
        assertEquals(listOf(Out.SecondDown), t.feed(down(500f, 300f, 200), scene))
        assertFalse(t.heldAlone(200 + SceneTouch.PICKER_HOLD_MS))
        assertFalse(t.held(200 + 549))
        assertTrue(t.held(200 + 550))
    }

    @Test fun `in the picker nothing comes of a finger here, and its tap is not the first of a double tap`() {
        val t = SceneTouch()
        val picker = { Mode.PICKER }
        assertEquals(none, t.feed(down(500f, 300f, 0), picker))
        assertEquals(none, t.feed(move(200f, 305f, 100), picker))
        assertEquals(none, t.feed(up(200f, 305f, 150), picker))
        assertEquals(none, t.feed(down(500f, 300f, 300), picker))
        assertEquals(none, t.feed(up(500f, 300f, 360), picker))
        assertFalse(t.heldAlone(1000))
        // The picker shut by that tap; the next tap on the face is a first tap, not a double tap's second.
        assertEquals(listOf(Out.FirstDown), t.feed(down(500f, 300f, 450), scene))
        assertEquals(listOf(Out.Tap(500f, 300f, Mode.SCENE)), t.feed(up(500f, 300f, 500), scene))
    }

    @Test fun `a hold that wanders, a lifted finger, or one on a page picks nothing`() {
        val moved = SceneTouch()
        moved.feed(down(500f, 300f, 0), scene)
        moved.feed(move(540f, 300f, 300), scene)
        assertFalse(moved.heldAlone(SceneTouch.PICKER_HOLD_MS))
        val lifted = SceneTouch()
        lifted.feed(down(500f, 300f, 0), scene)
        lifted.feed(up(500f, 300f, 100), scene)
        assertFalse(lifted.heldAlone(SceneTouch.PICKER_HOLD_MS))
        val page = SceneTouch()
        assertEquals(none, page.feed(down(500f, 300f, 0)) { Mode.USAGE })
        assertFalse(page.heldAlone(SceneTouch.PICKER_HOLD_MS))
    }

    @Test fun `a swipe says which way and on what, once, whatever the page`() {
        for (mode in listOf(Mode.SCENE, Mode.USAGE, Mode.NOTES)) {
            assertEquals(listOf<Out>(Out.Swipe(Way.LEFT, mode)), swipe(mode, 700f, 300f, 400f, 305f))
            assertEquals(listOf<Out>(Out.Swipe(Way.RIGHT, mode)), swipe(mode, 300f, 300f, 600f, 295f))
            assertEquals(listOf<Out>(Out.Swipe(Way.UP, mode)), swipe(mode, 500f, 500f, 505f, 200f))
            assertEquals(listOf<Out>(Out.Swipe(Way.DOWN, mode)), swipe(mode, 500f, 150f, 495f, 450f))
        }
    }

    @Test fun `the holding hand's drift is not a swipe - short, slow or slanting`() {
        assertEquals("two fingers' width is a shifted grip", none, swipe(Mode.SCENE, 300f, 300f, 400f, 300f))
        assertEquals("far, but over two seconds", none, swipe(Mode.SCENE, 300f, 300f, 600f, 300f, ms = 2000))
        assertEquals("as much down as across", none, swipe(Mode.SCENE, 300f, 300f, 480f, 470f))
    }

    @Test fun `a finger that is not ours to read means nothing`() {
        assertEquals(none, swipe(Mode.NONE, 500f, 5f, 500f, 300f))
        val t = SceneTouch()
        t.feed(down(500f, 300f, 0)) { Mode.NONE }
        assertEquals(none, t.feed(up(500f, 300f, 60)) { Mode.NONE })
    }

    @Test fun `held on the notes page it is the note lifted to be put in order - not a tap, not the face picker, and only there`() {
        val notes = { Mode.NOTES }
        val t = SceneTouch()
        assertEquals(listOf(Out.NotesDown), t.feed(down(600f, 300f, 0), notes))
        assertFalse("too soon", t.heldOnNotes(SceneTouch.NOTE_HOLD_MS - 50))
        assertFalse("not the face picker's hold", t.heldAlone(SceneTouch.PICKER_HOLD_MS))
        assertTrue(t.heldOnNotes(SceneTouch.NOTE_HOLD_MS))
        assertFalse("once", t.heldOnNotes(SceneTouch.NOTE_HOLD_MS + 100))
        // The finger then goes sideways and lifts: no swipe, no tap of this reader's (the carousel has it).
        assertEquals(none, t.feed(move(900f, 300f, 700), notes))
        assertEquals(none, t.feed(up(900f, 300f, 760), notes))
        // A tap is no hold; nor is a finger that wandered.
        val tap = SceneTouch()
        tap.feed(down(600f, 300f, 0), notes)
        assertEquals(listOf(Out.Tap(600f, 300f, Mode.NOTES)), tap.feed(up(600f, 300f, 120), notes))
        assertFalse(tap.heldOnNotes(SceneTouch.NOTE_HOLD_MS))
        val wander = SceneTouch()
        wander.feed(down(600f, 300f, 0), notes); wander.feed(move(640f, 300f, 200), notes)
        assertFalse(wander.heldOnNotes(SceneTouch.NOTE_HOLD_MS))
        // Longer than a tap may be down.
        assertTrue(SceneTouch.NOTE_HOLD_MS > SceneTouch.TAP_MS)
        // The moon's hold is untouched by it, and on usage nothing is armed.
        val moon = SceneTouch()
        assertEquals(listOf(Out.FirstDown), moon.feed(down(600f, 300f, 0), scene))
        assertFalse(moon.heldOnNotes(SceneTouch.NOTE_HOLD_MS))
        assertTrue(moon.heldAlone(SceneTouch.PICKER_HOLD_MS))
        assertEquals(none, SceneTouch().feed(down(600f, 300f, 0)) { Mode.USAGE })
    }

    @Test fun `a tap on a page says where, for its buttons`() {
        val t = SceneTouch()
        val notes = { Mode.NOTES }
        t.feed(down(810f, 476f, 0), notes)
        assertEquals(listOf(Out.Tap(810f, 476f, Mode.NOTES)), t.feed(up(810f, 476f, 80), notes))
    }

    @Test fun `our faces are the loop clips by name`() {
        val faces = SceneFaces.list(arrayOf("falling.loop.mp4", "rain.wake.mp4", "david-moon.loop.mp4", "falling.loop.json"))
        assertEquals(listOf("david-moon.loop.mp4", "falling.loop.mp4"), faces)
        assertEquals(emptyList<String>(), SceneFaces.list(null))
    }

    @Test fun `a notification's buttons are found where they are drawn`() {
        assertEquals(-1, NoticeShade.actionAt(200f, 110f, 0))
        assertEquals(-1, NoticeShade.actionAt(200f, 60f, 2))
        assertEquals(0, NoticeShade.actionAt(NoticeShade.TEXT_X + 20f, 115f, 2))
        assertEquals(1, NoticeShade.actionAt(NoticeShade.TEXT_X + NoticeShade.PILL_W + NoticeShade.PILL_GAP + 20f, 115f, 2))
        assertEquals(-1, NoticeShade.actionAt(NoticeShade.TEXT_X + NoticeShade.PILL_W + NoticeShade.PILL_GAP + 20f, 115f, 1))
        assertEquals(-1, NoticeShade.actionAt(20f, 115f, 2))
    }
}
