package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A page follows the finger (PageDrag): the turn is decided at the lift, far enough or flung; short of that the
 * page goes back (the review's 11).
 */
class PageDragTest {
    private fun run(points: List<Triple<Float, Float, Long>>, tracks: Boolean = true): List<PageDrag.Out> {
        val d = PageDrag(); val out = ArrayList<PageDrag.Out>()
        val t: (PageDrag.Way, Float, Float, Long) -> Boolean = { _, _, _, _ -> tracks }
        out += d.feed(RearTouches.Down(points[0].first, points[0].second, points[0].third), t)
        for (p in points.drop(1).dropLast(1)) out += d.feed(RearTouches.Move(p.first, p.second, p.third), t)
        val last = points.last()
        out += d.feed(RearTouches.Up(last.first, last.second, last.third), t)
        return out
    }
    /** A straight stroke from [x0] by [dx] over [ms], in 12 ms steps; the last point is the lift. */
    private fun across(x0: Float, dx: Float, ms: Long, y: Float = 300f): List<Triple<Float, Float, Long>> {
        val n = (ms / 12L).toInt().coerceAtLeast(2)
        return (0..n).map { Triple(x0 + dx * it / n, y, 1_000L + ms * it / n) } + Triple(x0 + dx, y, 1_000L + ms + 8L)
    }
    private fun List<PageDrag.Out>.end() = filterIsInstance<PageDrag.Out.End>().single()

    @Test fun `a slow drag moves the page with the finger and turns it at the lift`() {
        val out = run(across(800f, -300f, 2_000L))
        val begin = out.filterIsInstance<PageDrag.Out.Begin>().single()
        assertEquals(PageDrag.Way.LEFT, begin.way)
        assertEquals(800f, begin.downX, 0f); assertEquals(1_000L, begin.downAt)
        val at = out.filterIsInstance<PageDrag.Out.At>().map { it.travel }
        assertTrue("the page is put where the finger is, move by move: ${at.size} places", at.size > 100)
        assertEquals(at, at.sorted())
        assertEquals(300f, at.last(), 0.5f)
        // Nothing is decided before the lift, and the lift is the only end.
        assertTrue(out.last() is PageDrag.Out.End)
        assertTrue(out.end().turns)
        assertEquals(300f, out.end().travel, 0.5f)
    }

    @Test fun `let go short of the threshold, however it got there, the page goes back`() {
        assertFalse(run(across(800f, -100f, 900L)).end().turns)
        assertFalse(run(across(800f, -149f, 3_000L)).end().turns)
        // Out past the threshold and back again before the lift: taken back.
        val there = across(800f, -260f, 600L).dropLast(1)
        val back = (1..20).map { Triple(540f + it * 10f, 300f, 1_600L + it * 20L) }
        val out = run(there + back + Triple(740f, 300f, 2_010L))
        assertFalse(out.end().turns)
        assertEquals(60f, out.end().travel, 0.5f)
    }

    @Test fun `a fling turns the page by its speed, short as it is`() {
        assertTrue(run(across(800f, -80f, 60L)).end().turns)                 // 1.3 units a millisecond
        assertFalse("too short to be a fling", run(across(800f, -30f, 20L)).end().turns)
        // A slow drag that ends in a flick: the last stretch's speed counts.
        val slow = (0..40).map { Triple(800f - it * 1f, 300f, 1_000L + it * 25L) }                 // 40 units in a second
        val flick = (1..4).map { Triple(760f - it * 20f, 300f, 2_000L + it * 12L) }                // 80 more in 48 ms
        assertTrue(run(slow + flick + Triple(680f, 300f, 2_050L)).end().turns)
        // The lift a report or two after the last move (a touchscreen says so 8 to 30 ms later): still the fling.
        assertTrue(run(across(800f, -80f, 60L).dropLast(1) + Triple(720f, 300f, 1_090L)).end().turns)
        // And a finger that stops and rests before it lifts is no fling.
        val rest = (1..10).map { Triple(720f, 300f, 2_050L + it * 40L) }
        assertFalse(run(across(800f, -80f, 60L).dropLast(1) + rest + Triple(720f, 300f, 2_500L)).end().turns)
    }

    @Test fun `the rule of the lift`() {
        assertTrue(PageDrag.turns(PageDrag.COMMIT, 0f))
        assertFalse(PageDrag.turns(PageDrag.COMMIT - 1f, 0.1f))
        assertTrue(PageDrag.turns(PageDrag.FLING_TRAVEL, PageDrag.FLING))
        assertFalse(PageDrag.turns(PageDrag.FLING_TRAVEL - 1f, 3f))
        assertFalse(PageDrag.turns(0f, 0f))
    }

    @Test fun `the way is decided once, by the first travel, and kept`() {
        // Left first, then mostly up: still a stroke to the left, measured across.
        val pts = listOf(Triple(800f, 300f, 1_000L), Triple(780f, 298f, 1_020L), Triple(700f, 200f, 1_100L), Triple(640f, 100f, 1_200L), Triple(640f, 100f, 1_210L))
        val out = run(pts)
        assertEquals(PageDrag.Way.LEFT, out.filterIsInstance<PageDrag.Out.Begin>().single().way)
        assertEquals(160f, out.end().travel, 0.5f)
        for (way in PageDrag.Way.values()) {
            val (dx, dy) = when (way) { PageDrag.Way.LEFT -> -200f to 0f; PageDrag.Way.RIGHT -> 200f to 0f; PageDrag.Way.UP -> 0f to -200f; PageDrag.Way.DOWN -> 0f to 200f }
            val p = (0..20).map { Triple(500f + dx * it / 20f, 300f + dy * it / 20f, 1_000L + it * 15L) } + Triple(500f + dx, 300f + dy, 1_310L)
            assertEquals(way, run(p).end().way)
        }
        // Back past where it landed: nothing of the page has moved.
        val wrong = across(800f, -40f, 200L).dropLast(1) + (1..10).map { Triple(760f + it * 20f, 300f, 1_200L + it * 20L) } + Triple(960f, 300f, 1_410L)
        assertEquals(0f, run(wrong).end().travel, 0f)
    }

    @Test fun `a tap, a rest, and a finger whose way turns no page are not this one's`() {
        assertTrue(run(listOf(Triple(600f, 300f, 1_000L), Triple(604f, 303f, 1_040L), Triple(604f, 303f, 1_080L))).isEmpty())
        assertTrue(run(across(800f, -300f, 400L), tracks = false).isEmpty())
        val d = PageDrag()
        assertFalse(d.following)
        d.feed(RearTouches.Down(800f, 300f, 0)) { _, _, _, _ -> true }
        d.feed(RearTouches.Move(700f, 300f, 50)) { _, _, _, _ -> true }
        assertTrue(d.following)
        d.feed(RearTouches.Up(700f, 300f, 60)) { _, _, _, _ -> true }
        assertFalse(d.following)
    }

    @Test fun `asked once which page, as the way is known`() {
        var asked = 0
        val d = PageDrag()
        val t: (PageDrag.Way, Float, Float, Long) -> Boolean = { _, _, _, _ -> asked++; true }
        d.feed(RearTouches.Down(800f, 300f, 0), t)
        for (i in 1..50) d.feed(RearTouches.Move(800f - i * 5f, 300f, i * 10L), t)
        d.feed(RearTouches.Up(550f, 300f, 510L), t)
        assertEquals(1, asked)
    }

    @Test fun `where the page stands under the finger, and that a page let go goes on from there`() {
        // Coming in: the part of the back's width the finger has gone. Going out: what is left of it.
        assertEquals(0.25f, PageDrag.inPart(coming = true, travel = 226f, extent = 904f), 1e-4f)
        assertEquals(0.75f, PageDrag.inPart(coming = false, travel = 226f, extent = 904f), 1e-4f)
        assertEquals(1f, PageDrag.inPart(coming = true, travel = 5_000f, extent = 904f), 0f)
        assertEquals(0f, PageDrag.inPart(coming = false, travel = 5_000f, extent = 904f), 0f)
        // The slide's own measure: eased again it is the place the finger left it at.
        for (k in 0..20) { val part = k / 20f; assertEquals(part, ShellPage.ease(PageDrag.unease(part)), 1e-4f) }
    }

    @Test fun `the table still decides - a drag that turns is the table's own stroke, on every state`() {
        // What the shell follows with the finger is what RearNav gives for that stroke; this holds the set of animations a drag may follow.
        val followed = setOf(RearNav.Anim.USAGE_IN, RearNav.Anim.USAGE_OUT, RearNav.Anim.NOTES_IN, RearNav.Anim.NOTES_OUT, RearNav.Anim.LIVE_IN, RearNav.Anim.LIVE_OUT)
        val moon = RearNav.State(lit = true)
        val timer = RearNav.next(moon, RearNav.Input.LiveComes(RearNav.Live.TIMER)).state
        fun anim(s: RearNav.State, way: RearNav.Way) = RearNav.next(s, RearNav.Input.Stroke(way)).anim
        assertEquals(RearNav.Anim.USAGE_IN, anim(timer, RearNav.Way.LEFT))
        assertEquals(RearNav.Anim.LIVE_OUT, anim(timer, RearNav.Way.RIGHT))
        assertEquals(RearNav.Anim.NOTES_IN, anim(timer, RearNav.Way.UP))
        assertEquals(RearNav.Anim.USAGE_IN, anim(moon, RearNav.Way.LEFT))
        // The Camera left of home, a stroke under a pop-up, the shade's own: nothing follows the finger there.
        assertFalse(anim(moon, RearNav.Way.RIGHT) in followed)
        assertFalse(anim(moon.copy(pop = true), RearNav.Way.LEFT) in followed)
        assertFalse(anim(moon.copy(shade = true), RearNav.Way.LEFT) in followed)
        assertFalse(anim(RearNav.State(), RearNav.Way.LEFT) in followed)
    }
}
