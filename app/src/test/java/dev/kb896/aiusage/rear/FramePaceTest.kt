package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * The pages' pictures on the vsync (FramePace, FramePacer). A fake vsync and a fake thread stand in for the
 * Choreographer and the page thread: the ask is made the way FramePacer makes it (FramePace.dueNs, the arm rounded up to
 * the handler's whole milliseconds, then the first vsync after it), with the thread late by a random amount each time,
 * and the pictures must land on exact refreshes, the slides at exact multiples of one refresh.
 */
class FramePaceTest {
    private val p120 = FramePace.periodNsOf(120f)
    private val p60 = FramePace.periodNsOf(60f)

    /** The vsync's times: [period] apart from [phase]. */
    private fun vsyncAfter(t: Long, period: Long, phase: Long): Long {
        val k = Math.floorDiv(t - phase, period) + 1
        return phase + k * period
    }

    /**
     * [n] pictures of a page that wants one every [targetMs] on a vsync of [period] (the rear at [rearPeriod]): the times
     * of the vsyncs they were drawn on. After each picture the thread asks [late] ns later (its draw and its queue).
     */
    private fun run(targetMs: Float, period: Long, rearPeriod: Long, n: Int, seed: Int, maxLateNs: Long = 4_000_000L, phase: Long = 3_141_593L): List<Long> {
        val rnd = Random(seed)
        val want = FramePace.targetOn(targetMs, rearPeriod)
        val out = ArrayList<Long>()
        var last = 0L
        var now = 1_000_000_000L
        repeat(n) {
            val due = FramePace.dueNs(last, now, want, period)
            // Due now: the vsync asked for at once; else the arm by the handler's clock (whole ms, rounded up) asks for it.
            val askAt = if (due <= now) now else (due + 999_999L) / 1_000_000L * 1_000_000L
            val frame = vsyncAfter(askAt, period, phase)
            assertTrue("the vsync came before its stand-in by the clock", frame < FramePace.lateNs(due, period, rearPeriod))
            out += frame
            last = frame
            // The callback runs a little after the vsync, the picture takes its time, and it asks for the next one.
            now = frame + rnd.nextLong(0L, 1_500_000L) + rnd.nextLong(200_000L, maxLateNs)
        }
        return out
    }

    private fun gaps(t: List<Long>) = t.zipWithNext { a, b -> b - a }

    @Test fun `at 120 Hz the 16 ms pages get every 2nd refresh, the 33 ms ones every 4th, the slides every one`() {
        for (seed in 0 until 20) {
            assertTrue(gaps(run(16f, p120, p120, 400, seed)).all { it == 2 * p120 })
            assertTrue(gaps(run(33f, p120, p120, 400, seed)).all { it == 4 * p120 })
            assertTrue(gaps(run(0f, p120, p120, 400, seed, maxLateNs = 3_000_000L)).all { it == p120 })
        }
    }

    @Test fun `at 60 Hz the 16 ms pages get every refresh and the 33 ms ones every 2nd`() {
        for (seed in 0 until 20) {
            assertTrue(gaps(run(16f, p60, p60, 300, seed, maxLateNs = 9_000_000L)).all { it == p60 })
            assertTrue(gaps(run(33f, p60, p60, 300, seed, maxLateNs = 9_000_000L)).all { it == 2 * p60 })
        }
    }

    @Test fun `a vsync faster than the rear does not give a slide more pictures than the rear shows`() {
        // The rear at 60 Hz and the vsync (the front) at 120: a slide every 2nd vsync.
        assertTrue(gaps(run(0f, p120, p60, 200, 7)).all { it == 2 * p120 })
    }

    @Test fun `as many pictures as before, never fewer`() {
        // Before: 16 ms and 33 ms sleeps after the picture (at best 62.5 and 30.3 a second, fewer by the draw's time).
        val music = 1e9 / gaps(run(16f, p120, p120, 300, 3)).average()
        val timer = 1e9 / gaps(run(33f, p120, p120, 300, 3)).average()
        val slide = 1e9 / gaps(run(0f, p120, p120, 300, 3, maxLateNs = 3_000_000L)).average()
        assertEquals(60.0, music, 0.01)
        assertEquals(30.0, timer, 0.01)
        // The slides asked every 12 / 14 ms (83 / 71 a second); now every refresh.
        assertEquals(120.0, slide, 0.01)
    }

    @Test fun `a slide's place comes from the frame times, an exact share of it every refresh, done in its 260 ms`() {
        assertEquals(260f, ShellPage.SLIDE_MS, 0f)
        // As ShellPage.step: the first step (no frame yet) moves nothing; each vsync after it moves by its own time.
        val frames = run(0f, p120, p120, 40, 11, maxLateNs = 3_000_000L)
        var at = 0f; var movedAt = 0L; val places = ArrayList<Float>()
        val start = frames.first() - 2_000_000L
        movedAt = start
        for (f in frames) {
            val d = FramePace.share(movedAt, f, ShellPage.SLIDE_MS)
            movedAt = maxOf(movedAt, f)
            at = minOf(1f, at + d)
            places += at
            if (at == 1f) break
        }
        val steps = places.zipWithNext { a, b -> b - a }.dropLast(1)
        val one = p120 / 1e6f / ShellPage.SLIDE_MS
        assertTrue("every step is one refresh's share: $steps", steps.all { kotlin.math.abs(it - one) < 1e-5f })
        val took = (frames[places.size - 1] - start) / 1e6
        assertTrue("in place within one refresh of 260 ms: $took", took >= 260.0 && took < 260.0 + 8.34)
        // The easing is the pages' own, unchanged.
        assertEquals(0f, ShellPage.ease(0f), 0f); assertEquals(1f, ShellPage.ease(1f), 0f)
        assertEquals(1f - 0.5f * 0.5f * 0.5f, ShellPage.ease(0.5f), 1e-6f)
    }

    @Test fun `a frame time a little before the last step moves nothing, never backwards`() {
        assertEquals(0f, FramePace.share(10_000_000L, 9_000_000L, 260f), 0f)
        assertEquals(1f, FramePace.share(0L, 260_000_000L, 260f), 1e-6f)
    }

    @Test fun `the first picture and one after a long rest are asked for now`() {
        assertEquals(5L, FramePace.dueNs(0L, 5L, 16f, p120))
        val now = 10_000_000_000L
        assertEquals(now, FramePace.dueNs(now - 2_000_000_000L, now, 16f, p120))
        assertEquals(now, FramePace.clockNextNs(0L, now, 16f, p120))
        // Already past the wanted time (the thread was slow): now, not in the past.
        assertEquals(now, FramePace.dueNs(now - 40_000_000L, now, 16f, p120))
    }

    @Test fun `the wanted refreshes are the nearest whole number, at least one`() {
        assertEquals(2, FramePace.every(16f, p120)); assertEquals(4, FramePace.every(33f, p120))
        assertEquals(1, FramePace.every(16f, p60)); assertEquals(2, FramePace.every(33f, p60))
        assertEquals(1, FramePace.every(0f, p120)); assertEquals(1, FramePace.every(5f, p60))
        assertEquals(3, FramePace.every(33f, FramePace.periodNsOf(90f)))
    }

    @Test fun `a vsync slower than the rear is not used, an unknown one is tried`() {
        assertTrue(FramePace.vsyncFits(0L, p120))
        assertTrue(FramePace.vsyncFits(p120, p120))
        assertTrue(FramePace.vsyncFits(p120 + 300_000L, p120))
        assertFalse(FramePace.vsyncFits(p60, p120))
        assertFalse(FramePace.vsyncFits(FramePace.periodNsOf(90f), p120))
        assertTrue(FramePace.vsyncFits(p120, p60))
    }

    @Test fun `by the clock the pictures keep the rear's grid whatever the thread's delays, and never burst`() {
        val rnd = Random(5)
        var last = 0L; var now = 2_000_000_000L
        val planned = ArrayList<Long>()
        repeat(500) {
            val next = FramePace.clockNextNs(last, now, 16f, p120)
            planned += next
            last = next
            now = next + rnd.nextLong(0L, 6_000_000L)
        }
        assertTrue(gaps(planned).all { it == 2 * p120 })
        // Fallen far behind: from now, not a run of pictures to catch up.
        assertEquals(now, FramePace.clockNextNs(now - 100_000_000L, now, 16f, p120))
    }

    @Test fun `a rear that does not say its rate is taken at its panel's 120 Hz, never slower`() {
        assertEquals(p120, FramePace.periodNsOf(0f))
        assertEquals(p120, FramePace.periodNsOf(1f))
        assertEquals(8_333_333L, p120)
        assertEquals(16_666_667L, p60)
    }

    @Test fun `a refresh is measured only from frame timelines that are one refresh apart`() {
        assertEquals(p120, FramePace.periodFrom(1_000L, 1_000L + p120))
        assertEquals(0L, FramePace.periodFrom(1_000L, 1_000L))
        assertEquals(0L, FramePace.periodFrom(1_000L, 1_000L + 1_000_000_000L))
    }

    @Test fun `the vsync asked for is stood in for by the clock within about one refresh more`() {
        val due = 1_000_000_000L
        val late = FramePace.lateNs(due, p120, p120)
        assertTrue(late - due > p120)
        assertTrue(late - due < 2 * p120)
    }

    @Test fun `a vsync that is slower than measured is found within a few pictures, a right one never`() {
        // Measured at 120 Hz, the vsync really at 60 (the lead display gone down, or off and made up): pages ask, it comes.
        for ((real, expectFound) in listOf(p60 to true, p120 to false)) {
            for (seed in 0 until 20) {
                val rnd = Random(seed)
                val w = FramePace.LateWindow()
                var found = -1
                var now = 1_000_000_000L
                for (i in 0 until 200) {
                    val frame = vsyncAfter(now, real, 1_234_567L)
                    if (w.note(FramePace.cameLate(now, frame, p120))) { found = i; break }
                    now = frame + rnd.nextLong(200_000L, 9_000_000L)
                }
                if (expectFound) assertTrue("found by the ${found}th", found in 0..24) else assertEquals(-1, found)
            }
        }
    }

    @Test fun `three late of the last eight, and the window starts over`() {
        val w = FramePace.LateWindow()
        assertFalse(w.note(true)); assertFalse(w.note(false)); assertFalse(w.note(true))
        repeat(5) { assertFalse(w.note(false)) }
        // The first late goes out of the last eight, then the second as two more come: still two in the window, then a third.
        assertFalse(w.note(false)); assertFalse(w.note(true)); assertFalse(w.note(true)); assertTrue(w.note(true))
        assertFalse(w.note(true))
        assertFalse(FramePace.cameLate(0L, p120, p120))
        assertTrue(FramePace.cameLate(0L, 2 * p120, p120))
    }

    // ---- the pages, from the source ----

    private val rear = File("src/main/java/dev/kb896/aiusage/rear")
    private fun src(name: String) = File(rear, name).readText()

    @Test fun `every page on the pages' thread is paced, and nothing of theirs sleeps a fixed frame any more`() {
        val service = src("RearUserService.kt")
        for (name in listOf("usage", "notes", "arrival")) assertTrue("$name is paced", Regex(""""$name", ShellPage\.Edge\.\w+, ::rearLayerStack, \{ geometry \}, musicHandler, pacer = pagePacer,""").containsMatchIn(service))
        val cards = Regex("""ShellCard\("(\w+)"[^\n]*""").findAll(service).toList()
        assertEquals(setOf("music", "timer", "call", "navigation"), cards.map { it.groupValues[1] }.toSet())
        for (c in cards) assertTrue("${c.groupValues[1]} is paced", c.value.contains("pacer = pagePacer"))
        for (old in listOf("postDelayed(stepper, 8L)", "postDelayed(timerTick, MUSIC_FRAME_MS)", "postAtTime(musicTick, began + MUSIC_PAGE_FRAME_MS)",
            "postAtTime(this, began + USAGE_FRAME_MS)", "postDelayed(usageTick, USAGE_FRAME_MS)", "postDelayed(this, ARRANGE_FRAME_MS)"))
            assertFalse("no fixed frame sleep: $old", service.contains(old))
        // Each of the two page classes steps on the pacer when it has one, by the frame's own time.
        for (f in listOf("ShellPage.kt", "ShellCard.kt")) {
            val s = src(f)
            assertTrue(f, s.contains("pacer.post(stepper, 0f)") && s.contains("step(pacer?.frameNs ?: System.nanoTime())") && s.contains("FramePace.share("))
        }
    }

    @Test fun `the pacer never waits on the vsync alone`() {
        val s = src("FramePacer.kt")
        assertTrue("every vsync asked for has its stand-in by the clock", s.contains("handler.postAtTime(s.fallback, FramePace.lateNs(due, period, rearPeriodNs) / 1_000_000L)"))
        assertTrue("on the clock without a Choreographer, while a probe is out, before it is measured, or with a slow vsync",
            s.contains("val onClock = c == null || probing || periodNs == 0L || !FramePace.vsyncFits(periodNs, rearPeriodNs)"))
        assertTrue("a vsync late or missing is counted", s.contains("lates.note(FramePace.cameLate(s.askNs, at, periodNs))") && s.contains("lates.note(true)"))
        assertTrue("the Choreographer is got on the pages' thread", s.contains("Choreographer.getInstance()") && s.contains("onThread()) acquire()"))
    }
}
