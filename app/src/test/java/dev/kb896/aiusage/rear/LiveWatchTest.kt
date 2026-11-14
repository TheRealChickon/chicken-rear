package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.RearNav.Anim
import dev.kb896.aiusage.rear.RearNav.Input
import dev.kb896.aiusage.rear.RearNav.Live
import dev.kb896.aiusage.rear.RearNav.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is live on the phone, told to the navigation (LiveWatch -> RearNav): the same thing going on is nothing,
 * something new comes, and a page he sent away stays away, through a clock's re-posting too.
 */
class LiveWatchTest {
    /** The shell's own loop in small: what is live is looked at, its inputs go through the table, the animations are kept. */
    private class Back {
        val watch = LiveWatch()
        var nav = RearNav.State(lit = true)
        val anims = ArrayList<Anim>()
        var call: String? = null; var timer: String? = null; var music: String? = null; var charge: String? = null
        var timerOver = false
        var again = 0L
        fun look(now: Long, atOnce: Boolean = false) {
            val marks = arrayOfNulls<String>(4)
            marks[Live.CALL.ordinal] = call; marks[Live.TIMER.ordinal] = timer; marks[Live.MUSIC.ordinal] = music; marks[Live.CHARGE.ordinal] = charge
            val told = watch.look(now, marks, nav, atOnce) { it == Live.CALL || (it == Live.TIMER && timerOver) }
            again = told.againAt
            for (i in told.inputs) go(i)
        }
        fun go(i: Input) { val step = RearNav.next(nav, i); nav = step.state; if (step.anim != Anim.NONE) anims += step.anim }
        fun stroke(way: RearNav.Way) = go(Input.Stroke(way))
        val seen get() = RearNav.seen(nav)
    }
    private fun timerMark(key: String, total: Long, over: Boolean) = PageRow.timer(key, total, over).let { "${it.key}#${it.phase}" }
    private fun musicMark(starts: Long) = PageRow.music(starts).let { "${it.key}#${it.phase}" }

    @Test fun `a timer comes up, and sent away it stays away while it runs, is paused and goes on`() {
        val b = Back()
        b.timer = timerMark("clock", 600_000L, false); b.look(0)
        assertEquals(Seen.LIVE, b.seen); assertEquals(listOf(Anim.LIVE_IN), b.anims)
        b.stroke(RearNav.Way.RIGHT)
        assertEquals(Seen.MOON, b.seen)
        for (second in 1..600) { b.look(second * 1_000L); assertEquals(Seen.MOON, b.seen) }
        assertEquals("nothing moved by itself", listOf(Anim.LIVE_IN, Anim.LIVE_OUT), b.anims)
    }

    @Test fun `it comes back by itself when the timer runs out, and for another timer`() {
        val b = Back()
        b.timer = timerMark("clock", 600_000L, false); b.look(0); b.stroke(RearNav.Way.RIGHT)
        b.timer = timerMark("clock", 600_000L, true); b.timerOver = true; b.look(600_000L)
        assertEquals(Seen.LIVE, b.seen)
        b.stroke(RearNav.Way.RIGHT)
        b.timerOver = false; b.timer = timerMark("clock", 300_000L, false); b.look(700_000L)
        assertEquals("another timer", Seen.LIVE, b.seen)
    }

    @Test fun `a clock that takes its notification down for a moment does not bring the page he sent away back`() {
        val b = Back()
        val mark = timerMark("clock", 600_000L, false)
        b.timer = mark; b.look(0); b.stroke(RearNav.Way.RIGHT)
        b.timer = null; b.look(10_000L)
        assertEquals(Seen.MOON, b.seen)
        assertTrue("it is still the one he sent away", b.nav.away)
        assertEquals("looked at again when the gap would be an end", 10_000L + LiveWatch.FORGET_MS, b.again)
        b.timer = mark; b.look(12_000L)
        assertEquals(Seen.MOON, b.seen); assertTrue(b.nav.away)
        // Gone for good: forgotten, and the very same timer set again later shows.
        b.timer = null; b.look(20_000L); b.look(20_000L + LiveWatch.FORGET_MS)
        assertEquals(0, b.nav.lives)
        b.timer = mark; b.look(60_000L)
        assertEquals(Seen.LIVE, b.seen)
    }

    @Test fun `a page that is showing goes when its thing has ended - a notification taken down for a moment is not an end`() {
        val b = Back()
        val mark = timerMark("clock", 600_000L, false)
        b.timer = mark; b.look(0)
        // The clock takes its notification down (a pause) and posts it again 300 ms later: the page never moved.
        b.timer = null; b.look(5_000L)
        assertEquals(Seen.LIVE, b.seen)
        assertEquals(5_000L + LiveWatch.GRACE_MS, b.again)
        b.timer = mark; b.look(5_300L)
        assertEquals(listOf(Anim.LIVE_IN), b.anims)
        // Gone for good: the page goes once the moment's patience is over.
        b.timer = null; b.look(9_000L); b.look(9_000L + LiveWatch.GRACE_MS)
        assertEquals(Seen.MOON, b.seen)
        assertEquals(listOf(Anim.LIVE_IN, Anim.LIVE_OUT), b.anims)
    }

    @Test fun `THE X - an end he made is at once, and what the clock still says of that timer is the shell's to ignore`() {
        val b = Back()
        b.timer = timerMark("clock", 600_000L, false); b.look(0)
        b.timer = null; b.look(3_000L, atOnce = true)
        assertEquals(Seen.MOON, b.seen)
        assertEquals(0L, b.again)
        // Sent away first, then ended by him (the demo taken down): at once too.
        b.timer = timerMark("clock", 300_000L, false); b.look(10_000L); b.stroke(RearNav.Way.RIGHT)
        b.timer = null; b.look(12_000L, atOnce = true)
        assertEquals(0, b.nav.lives)
    }

    @Test fun `music sent away stays away through the next tracks and comes back when playing starts again after a stop`() {
        val b = Back()
        b.music = musicMark(1); b.look(0); b.stroke(RearNav.Way.RIGHT)
        for (track in 1..5) { b.look(track * 200_000L); assertEquals(Seen.MOON, b.seen) }
        b.music = null; b.look(1_100_000L); b.look(1_100_000L + LiveWatch.FORGET_MS)
        b.music = musicMark(2); b.look(1_200_000L)
        assertEquals(Seen.LIVE, b.seen)
    }

    @Test fun `a call taking a timer's place is one change of page, and the timer is the page again when the call ends`() {
        val b = Back()
        b.timer = timerMark("clock", 600_000L, false); b.look(0)
        b.call = "call#0"; b.look(1_000L)
        assertEquals(Live.CALL, b.nav.live)
        assertEquals(listOf(Anim.LIVE_IN, Anim.LIVE_SWAP), b.anims)
        b.call = null; b.look(60_000L); b.look(60_000L + LiveWatch.GRACE_MS)
        assertEquals(Live.TIMER, b.nav.live); assertEquals(Seen.LIVE, b.seen)
        assertEquals(listOf(Anim.LIVE_IN, Anim.LIVE_SWAP, Anim.LIVE_SWAP), b.anims)
    }

    @Test fun `on usage with his timer running, back is the timer page at once, whatever the clock says meanwhile`() {
        val b = Back()
        val mark = timerMark("com.android.deskclock", 22_138_000L, false)
        b.timer = mark; b.look(0)
        b.stroke(RearNav.Way.LEFT)
        assertEquals(Seen.USAGE, b.seen)
        for (second in 1..30) b.look(second * 1_000L)                       // the Clock's note, every second
        b.stroke(RearNav.Way.RIGHT)
        assertEquals(Seen.LIVE, b.seen)
        for (second in 31..40) b.look(second * 1_000L)
        assertEquals("usage in, usage out, and nothing after it", listOf(Anim.LIVE_IN, Anim.USAGE_IN, Anim.USAGE_OUT), b.anims)
        b.stroke(RearNav.Way.RIGHT)
        assertEquals(Seen.MOON, b.seen)
    }

    @Test fun `the charger - its page under a timer has none, and comes when the timer ends if he is on the live page`() {
        val b = Back()
        b.timer = timerMark("clock", 600_000L, false); b.look(0)
        b.charge = "charger#1"; b.look(1_000L)
        assertEquals(Live.TIMER, b.nav.live)
        b.timer = null; b.look(600_000L); b.look(600_000L + LiveWatch.GRACE_MS)
        assertEquals(Live.CHARGE, b.nav.live); assertEquals(Seen.LIVE, b.seen)
        // Swiped away, the charger out and in again: new, it comes.
        b.stroke(RearNav.Way.UP); assertEquals(Seen.MOON, b.seen)
        b.charge = null; b.look(700_000L); b.look(700_000L + LiveWatch.FORGET_MS)
        b.charge = "charger#2"; b.look(800_000L)
        assertEquals(Seen.LIVE, b.seen)
    }
}
