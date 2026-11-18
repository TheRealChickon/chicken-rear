package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.PageRow.Page
import dev.kb896.aiusage.ui.home.PreviewMode
import dev.kb896.aiusage.ui.home.previewSwipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The row: left from a live page is home, right of it is usage; one swipe, one page. */
class PageRowTest {

    @Test fun `with a live page the row is home, the live page, usage`() {
        assertEquals(Page.LIVE, PageRow.next(Page.HOME, goRight = true, live = true))
        assertEquals(Page.USAGE, PageRow.next(Page.LIVE, goRight = true, live = true))
        assertNull(PageRow.next(Page.USAGE, goRight = true, live = true))
        assertEquals(Page.LIVE, PageRow.next(Page.USAGE, goRight = false, live = true))
        assertEquals(Page.HOME, PageRow.next(Page.LIVE, goRight = false, live = true))
    }

    @Test fun `with nothing live going right from home is the usage page, as it always was`() {
        assertEquals(Page.USAGE, PageRow.next(Page.HOME, goRight = true, live = false))
        assertEquals(Page.HOME, PageRow.next(Page.USAGE, goRight = false, live = false))
    }

    @Test fun `left of home there is no page - that swipe stays Xiaomi's Camera`() {
        assertNull(PageRow.next(Page.HOME, goRight = false, live = true))
        assertNull(PageRow.next(Page.HOME, goRight = false, live = false))
    }

    @Test fun `there and back again - home, timer, usage, timer, home`() {
        var at = Page.HOME
        val seen = mutableListOf(at)
        for (right in listOf(true, true, false, false)) { at = PageRow.next(at, right, live = true)!!; seen += at }
        assertEquals(listOf(Page.HOME, Page.LIVE, Page.USAGE, Page.LIVE, Page.HOME), seen)
    }

    // -- the real timer's own data (the Clock's rear notification, read every second) -----------------------

    @Test fun `the Clock's timer is one and the same thing through every update, a pause and going on`() {
        val key = "0|com.example.timer|2147483646|null|10192"
        val running = PageRow.timer(key, totalMs = 1_200_000L, over = false)
        // Each second the time left changes and the end moves with a pause; the timer's whole length does not.
        for (total in listOf(1_200_000L, 1_200_000L + 7L, 1_200_000L + 999L)) assertEquals("a few ms of jitter in the length", running, PageRow.timer(key, total, over = false))
        // It runs out: another thing (LiveWatch tells the navigation it is new).
        assertFalse(running == PageRow.timer(key, 1_200_000L, over = true))
    }

    // -- one stroke, one page ---------------------------------------------------------------------------------

    @Test fun `sent away, then one swipe is the timer and not the usage page`() {
        val strokes = PageRow.Strokes()
        var at = Page.LIVE
        // He sends the timer away.
        assertTrue(strokes.mayMove(landedAt = 1_000L)); at = PageRow.next(at, goRight = false, live = true)!!; strokes.moved(1_150L)
        assertEquals(Page.HOME, at)
        // Later, one swipe right: the timer. The row still has it: sending it away did not take it out.
        assertTrue(strokes.mayMove(landedAt = 5_000L)); at = PageRow.next(at, goRight = true, live = true)!!; strokes.moved(5_150L)
        assertEquals(Page.LIVE, at)
        // The same stroke's contact breaks and lands again while the page is still sliding in: nothing.
        assertFalse(strokes.mayMove(landedAt = 5_150L + 120L))
        assertFalse(strokes.mayMove(landedAt = 5_150L + PageRow.SETTLE_MS - 1L))
        assertEquals(Page.LIVE, at)
        // A REAL SECOND SWIPE MOVES (two swipes 0.73 and 0.76 s apart were "ignored" while the wait was 0.7 s; a hand
        // swipes about once every 0.7 to 1 s, landing half a second after the move).
        assertTrue(strokes.mayMove(landedAt = 5_150L + 500L))
        assertTrue(strokes.mayMove(landedAt = 5_150L + 350L))
        // A second, separate swipe made on the timer: the usage page.
        assertTrue(strokes.mayMove(landedAt = 5_150L + PageRow.SETTLE_MS)); at = PageRow.next(at, goRight = true, live = true)!!
        assertEquals(Page.USAGE, at)
    }

    @Test fun `a long fast stroke is one swipe, and so is a slow long one - the reader gives one per landing`() {
        for ((stepMs, steps) in listOf(4L to 40, 16L to 40)) {
            val touch = SceneTouch()
            val outs = ArrayList<SceneTouch.Out>()
            var t = 1_000L
            outs += touch.feed(RearTouches.Down(860f, 300f, t)) { SceneTouch.Mode.SCENE }
            // Right across the back: more than five times a swipe's travel.
            for (i in 1..steps) { t += stepMs; outs += touch.feed(RearTouches.Move(860f - i * 20f, 300f, t)) { SceneTouch.Mode.SCENE } }
            outs += touch.feed(RearTouches.Up(60f, 300f, t + 10L)) { SceneTouch.Mode.SCENE }
            val swipes = outs.filterIsInstance<SceneTouch.Out.Swipe>()
            assertEquals("one swipe for a stroke of ${steps * 20} units in ${steps * stepMs} ms", 1, swipes.size)
            assertEquals(SceneTouch.Way.LEFT, swipes[0].way)
            assertEquals("it says when its finger landed", 1_000L, swipes[0].downAt)
        }
    }

    @Test fun `a contact that breaks and lands again is one page for the row, and a second swipe on purpose is a second page`() {
        val touch = SceneTouch(); val strokes = PageRow.Strokes()
        var moves = 0
        fun stroke(from: Long) {
            val outs = ArrayList<SceneTouch.Out>()
            outs += touch.feed(RearTouches.Down(800f, 300f, from)) { SceneTouch.Mode.SCENE }
            for (i in 1..10) outs += touch.feed(RearTouches.Move(800f - i * 30f, 300f, from + i * 12L)) { SceneTouch.Mode.SCENE }
            outs += touch.feed(RearTouches.Up(500f, 300f, from + 130L)) { SceneTouch.Mode.SCENE }
            for (s in outs.filterIsInstance<SceneTouch.Out.Swipe>()) if (strokes.mayMove(s.downAt)) { moves++; strokes.moved(s.at) }
        }
        // The first is a swipe at 1 060 (150 units of travel) and lifts at 1 130; its contact lands again 40 ms later.
        stroke(1_000L); stroke(1_000L + 130L + 40L)
        assertEquals(1, moves)
        // A second swipe made on purpose, the finger landing 0.45 s after the first one lifted (a real hand's pace).
        stroke(3_000L); stroke(3_000L + 130L + 450L)
        assertEquals("both of two swipes made one after the other move", 3, moves)
    }

    // -- the settings' preview takes the same swipes ---------------------------------------------------------

    @Test fun `the preview goes along the same row`() {
        // No live page there: home and usage.
        assertEquals(PreviewMode.USAGE, previewSwipe(PreviewMode.IDLE, live = null, across = true, forward = true))
        assertEquals(PreviewMode.IDLE, previewSwipe(PreviewMode.USAGE, live = null, across = true, forward = false))
        // A timer showing: swiped right it is sent away, swiped left from home it is back, again left is usage.
        assertEquals(PreviewMode.IDLE, previewSwipe(PreviewMode.TIMER, live = PreviewMode.TIMER, across = true, forward = false))
        assertEquals(PreviewMode.TIMER, previewSwipe(PreviewMode.IDLE, live = PreviewMode.TIMER, across = true, forward = true))
        assertEquals(PreviewMode.USAGE, previewSwipe(PreviewMode.TIMER, live = PreviewMode.TIMER, across = true, forward = true))
        assertEquals(PreviewMode.TIMER, previewSwipe(PreviewMode.USAGE, live = PreviewMode.TIMER, across = true, forward = false))
        // Music and charging the same.
        assertEquals(PreviewMode.MUSIC, previewSwipe(PreviewMode.IDLE, live = PreviewMode.MUSIC, across = true, forward = true))
        assertEquals(PreviewMode.CHARGING, previewSwipe(PreviewMode.IDLE, live = PreviewMode.CHARGING, across = true, forward = true))
        // Left of home: nothing in the preview (the Camera on the back).
        assertEquals(PreviewMode.IDLE, previewSwipe(PreviewMode.IDLE, live = null, across = true, forward = false))
    }

    @Test fun `up and down in the preview are as they were`() {
        assertEquals(PreviewMode.NOTES, previewSwipe(PreviewMode.IDLE, null, across = false, forward = true))
        assertEquals(PreviewMode.ALERT, previewSwipe(PreviewMode.IDLE, null, across = false, forward = false))
        assertEquals(PreviewMode.IDLE, previewSwipe(PreviewMode.NOTES, null, across = false, forward = false))
        assertEquals(PreviewMode.IDLE, previewSwipe(PreviewMode.ALERT, null, across = true, forward = true))
    }
}

/** A double tap while dark is the wake; on a lit back the double tap is the dim. */
class DarkBackTest {
    @org.junit.Test fun `a double tap on a dark back wakes it, on a lit one it dims`() {
        org.junit.Assert.assertEquals(DarkBack.Act.WAKE, DarkBack.doubleTap(rearLit = false))
        org.junit.Assert.assertEquals(DarkBack.Act.DIM, DarkBack.doubleTap(rearLit = true))
    }
}

/** A timer or a call already going when the app starts is the back's live page at once. */
class LivePickTest {
    private fun c(key: String, call: Boolean = false, rear: Boolean = false) = dev.kb896.aiusage.service.LivePick.Candidate(key, call, rear)

    @org.junit.Test fun `a call before a timer, the Clock's rear note before its other one, nothing for nothing`() {
        val pick = dev.kb896.aiusage.service.LivePick::first
        org.junit.Assert.assertEquals("rear", pick(listOf(c("plain"), c("rear", rear = true))))
        org.junit.Assert.assertEquals("call", pick(listOf(c("rear", rear = true), c("call", call = true))))
        org.junit.Assert.assertEquals("plain", pick(listOf(c("plain"))))
        org.junit.Assert.assertNull(pick(emptyList()))
    }

    @org.junit.Test fun `the Clock's alarm is no timer - by its notification channel`() {
        // Xiaomi's Clock, read from the phone.
        org.junit.Assert.assertTrue(dev.kb896.aiusage.service.LivePick.clockAlarm("channel_id_deskclock_alarm"))
        org.junit.Assert.assertTrue(dev.kb896.aiusage.service.LivePick.clockAlarm("1100_bedtimeReminder"))
        org.junit.Assert.assertFalse(dev.kb896.aiusage.service.LivePick.clockAlarm("channel_id_deskclock_timer"))
        org.junit.Assert.assertFalse(dev.kb896.aiusage.service.LivePick.clockAlarm("channel_id_deskclock_stopwatch"))
        org.junit.Assert.assertFalse(dev.kb896.aiusage.service.LivePick.clockAlarm("channel_back_screen_timer_id"))
        // Google's Clock: "Firing alarms & timers" carries a timer that has run out as well.
        org.junit.Assert.assertFalse(dev.kb896.aiusage.service.LivePick.clockAlarm("Firing alarms & timers"))
        org.junit.Assert.assertTrue(dev.kb896.aiusage.service.LivePick.clockAlarm("Upcoming alarms"))
        org.junit.Assert.assertFalse(dev.kb896.aiusage.service.LivePick.clockAlarm(null))
    }

    @org.junit.Test fun `one timer told by two notifications is one timer for the row`() {
        // Xiaomi's Clock: its own notification and the rear's, the same package, the same length.
        org.junit.Assert.assertEquals(PageRow.timer("com.android.deskclock", 22_138_000L, false), PageRow.timer("com.android.deskclock", 22_138_000L, false))
    }
}

/** Leaving the Camera on the back, by any way, brings our face back lit: it does not go dark. */
class CameraExitTest {
    @org.junit.Test fun `the Camera leaving the back is seen when the launcher returns or focus clears`() {
        val cam = RearCover.CAMERA
        org.junit.Assert.assertTrue(CameraExit.left(cam, RearCover.LAUNCHER))
        org.junit.Assert.assertTrue(CameraExit.left(cam, null))
        org.junit.Assert.assertTrue(CameraExit.left(" $cam ", RearCover.LAUNCHER))
    }

    @org.junit.Test fun `nothing else is a Camera exit - it coming up, it staying, other apps changing`() {
        val cam = RearCover.CAMERA
        org.junit.Assert.assertFalse(CameraExit.left(RearCover.LAUNCHER, cam))
        org.junit.Assert.assertFalse(CameraExit.left(cam, cam))
        org.junit.Assert.assertFalse(CameraExit.left(null, RearCover.LAUNCHER))
        org.junit.Assert.assertFalse(CameraExit.left("com.whatsapp/.Main", RearCover.LAUNCHER))
    }
}

/** The row's pages slide as the notes page does. */
class PageSlideTest {
    @org.junit.Test fun `one slide for every page - off its edge at 0, in place at 1, quick at first and settling, never back on itself`() {
        org.junit.Assert.assertEquals(0f, ShellPage.ease(0f), 0f)
        org.junit.Assert.assertEquals(1f, ShellPage.ease(1f), 0f)
        var prev = 0f
        for (i in 1..100) {
            val e = ShellPage.ease(i / 100f)
            org.junit.Assert.assertTrue("always on, never back: $prev then $e", e >= prev)
            prev = e
        }
        org.junit.Assert.assertTrue("more than half the way in the first third of the time", ShellPage.ease(1f / 3f) > 0.5f)
        org.junit.Assert.assertEquals("the notes page's own time", 260f, ShellPage.SLIDE_MS, 0f)
        // Outside its range it stays at its ends: a late frame does not throw the page past its place.
        org.junit.Assert.assertEquals(1f, ShellPage.ease(1.4f), 0f)
        org.junit.Assert.assertEquals(0f, ShellPage.ease(-0.2f), 0f)
    }
}

/**
 * No Back gesture on our pages: a stroke is the
 * row's own wherever on the back it starts, the very edge included. Strokes towards usage from the timer that began
 * at the right edge were once taken as Back; and second swipes 0.73 and 0.76 s after the ones before were dropped by
 * a 0.7 s wait.
 */
class EdgeStrokeTest {
    /** One stroke right to left starting at [x0], on the scene's mode (the moon and the live pages); the swipes it gave. */
    private fun stroke(touch: SceneTouch, x0: Float, from: Long, mode: SceneTouch.Mode = SceneTouch.Mode.SCENE): List<SceneTouch.Out.Swipe> {
        val outs = ArrayList<SceneTouch.Out>()
        outs += touch.feed(RearTouches.Down(x0, 300f, from)) { mode }
        for (i in 1..12) outs += touch.feed(RearTouches.Move(x0 - i * 28f, 300f, from + i * 12L)) { mode }
        outs += touch.feed(RearTouches.Up(x0 - 336f, 300f, from + 160L)) { mode }
        return outs.filterIsInstance<SceneTouch.Out.Swipe>()
    }

    @org.junit.Test fun `on the timer page a stroke from the very right edge to the left is the usage page`() {
        for (x0 in listOf(903f, 890f, 860f, 842f, 800f, 600f)) {
            val swipe = stroke(SceneTouch(), x0, 1_000L).single()
            org.junit.Assert.assertEquals("from x $x0", SceneTouch.Way.LEFT, swipe.way)
            org.junit.Assert.assertTrue("at its travel, the finger still down", swipe.at < 1_160L)
        }
        org.junit.Assert.assertEquals(PageRow.Page.USAGE, PageRow.next(PageRow.Page.LIVE, goRight = true, live = true))
        org.junit.Assert.assertEquals("and from the moon the same stroke is the timer", PageRow.Page.LIVE, PageRow.next(PageRow.Page.HOME, goRight = true, live = true))
    }

    @org.junit.Test fun `every way is a swipe from an edge as from the middle - nothing is held back for a lift`() {
        fun one(x0: Float, y0: Float, dx: Float, dy: Float, mode: SceneTouch.Mode): List<SceneTouch.Out.Swipe> {
            val t = SceneTouch(); val outs = ArrayList<SceneTouch.Out>()
            outs += t.feed(RearTouches.Down(x0, y0, 1_000L)) { mode }
            for (i in 1..12) outs += t.feed(RearTouches.Move(x0 + dx * i, y0 + dy * i, 1_000L + i * 12L)) { mode }
            val during = outs.filterIsInstance<SceneTouch.Out.Swipe>()
            org.junit.Assert.assertTrue("nothing new at the lift", t.feed(RearTouches.Up(x0 + dx * 12, y0 + dy * 12, 1_160L)) { mode }.none { it is SceneTouch.Out.Swipe })
            return during
        }
        for (mode in listOf(SceneTouch.Mode.SCENE, SceneTouch.Mode.USAGE, SceneTouch.Mode.NOTES, SceneTouch.Mode.CHARGE)) {
            org.junit.Assert.assertEquals("$mode", listOf(SceneTouch.Way.LEFT), one(900f, 300f, -28f, 0f, mode).map { it.way })
            org.junit.Assert.assertEquals("$mode", listOf(SceneTouch.Way.RIGHT), one(330f, 300f, 28f, 0f, mode).map { it.way })
            org.junit.Assert.assertEquals("$mode", listOf(SceneTouch.Way.UP), one(600f, 568f, 0f, -28f, mode).map { it.way })
            org.junit.Assert.assertEquals("$mode", listOf(SceneTouch.Way.DOWN), one(600f, 60f, 0f, 28f, mode).map { it.way })
        }
    }

    @org.junit.Test fun `his second swipe 0-7 s after the first is not dropped - home, the timer, usage`() {
        val touch = SceneTouch(); val strokes = PageRow.Strokes()
        var at = PageRow.Page.HOME
        fun go(from: Long) {
            for (s in stroke(touch, 900f, from)) if (strokes.mayMove(s.downAt)) { at = PageRow.next(at, goRight = true, live = true) ?: at; strokes.moved(s.at) }
        }
        // A swipe, and the next 0.76 s later (it was "ignored"). And the pair 0.73 s apart.
        go(10_000L); org.junit.Assert.assertEquals(PageRow.Page.LIVE, at)
        go(10_760L); org.junit.Assert.assertEquals(PageRow.Page.USAGE, at)
        at = PageRow.Page.HOME
        go(20_000L); go(20_730L)
        org.junit.Assert.assertEquals(PageRow.Page.USAGE, at)
        // Faster than he does it, half a second apart: still two pages.
        at = PageRow.Page.HOME
        go(30_000L); go(30_500L)
        org.junit.Assert.assertEquals(PageRow.Page.USAGE, at)
        // One stroke whose contact breaks and lands again 40 ms after it lifted: one page.
        at = PageRow.Page.HOME
        go(40_000L); go(40_000L + 160L + 40L)
        org.junit.Assert.assertEquals(PageRow.Page.LIVE, at)
    }
}
