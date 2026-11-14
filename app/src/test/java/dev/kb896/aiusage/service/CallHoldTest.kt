package dev.kb896.aiusage.service

import dev.kb896.aiusage.rear.LiveWatch
import dev.kb896.aiusage.rear.PageRow
import dev.kb896.aiusage.rear.RearNav
import dev.kb896.aiusage.rear.RearNav.Anim
import dev.kb896.aiusage.rear.RearNav.Live
import dev.kb896.aiusage.service.CallHold.Companion.MODE_IN_CALL
import dev.kb896.aiusage.service.CallHold.Companion.MODE_IN_COMMUNICATION
import dev.kb896.aiusage.service.CallHold.Companion.MODE_NORMAL
import dev.kb896.aiusage.service.CallHold.Companion.MODE_RINGTONE
import dev.kb896.aiusage.service.CallHold.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANSWERING A CALL ONLY CHANGES ITS PAGE'S BUTTONS AND THE LINE UNDER THE NAME (seen with a real call: the log had
 * "call ended: call page -> moon, live_out" and 0.2 s later "call is new (urgent): moon -> call page, live_in"). The
 * dialer takes its "incoming" notification down and posts its "going on" one about 0.9 s later; [CallHold] keeps the
 * call over that gap by what the phone's audio mode says.
 */
class CallHoldTest {
    @Test fun `answered - the incoming notification gone with the phone in a call is the call going on, answered at once`() {
        val h = CallHold()
        h.seen(MODE_RINGTONE)
        assertEquals(Verdict.Wait(CallHold.AGAIN_MS, talking = true), h.gone(1_000L, "ring", ringing = true, mode = MODE_IN_CALL))
        assertEquals("ring", h.key); assertTrue(h.ringing)
        for (t in 1_200L..4_800L step 200L) assertEquals(Verdict.Wait(CallHold.AGAIN_MS, talking = true), h.look(t, MODE_IN_CALL))
        h.seen(MODE_IN_CALL)
        assertNull("its own notification has come: nothing is held", h.key)
    }

    @Test fun `still ringing in the mode as the notification goes - held, and answered when the mode says so`() {
        val h = CallHold()
        h.seen(MODE_RINGTONE)
        assertEquals(Verdict.Wait(CallHold.AGAIN_MS, talking = false), h.gone(0L, "ring", true, MODE_RINGTONE))
        assertEquals(Verdict.Wait(CallHold.AGAIN_MS, talking = true), h.look(200L, MODE_IN_CALL))
    }

    @Test fun `declined or given up - no call in the mode twice in a row is the end, for sure, within half a second`() {
        val h = CallHold()
        h.seen(MODE_RINGTONE)
        assertEquals(Verdict.Wait(CallHold.AGAIN_MS, talking = false), h.gone(0L, "ring", true, MODE_NORMAL))
        assertEquals(Verdict.Clear(sure = true), h.look(200L, MODE_NORMAL))
        assertNull(h.key)
    }

    @Test fun `one moment of no call between ringing and answered is not an end`() {
        val h = CallHold()
        h.seen(MODE_RINGTONE)
        assertEquals(Verdict.Wait(CallHold.AGAIN_MS, talking = false), h.gone(0L, "ring", true, MODE_NORMAL))
        assertEquals(Verdict.Wait(CallHold.AGAIN_MS, talking = true), h.look(200L, MODE_IN_CALL))
        assertEquals("and the count starts again", Verdict.Wait(CallHold.AGAIN_MS, talking = false), h.look(400L, MODE_NORMAL))
        assertEquals(Verdict.Clear(sure = true), h.look(600L, MODE_NORMAL))
    }

    @Test fun `hung up while talking - the end, for sure`() {
        val h = CallHold()
        h.seen(MODE_IN_CALL)
        assertEquals(Verdict.Wait(CallHold.AGAIN_MS, talking = false), h.gone(0L, "talk", false, MODE_NORMAL))
        assertEquals(Verdict.Clear(sure = true), h.look(200L, MODE_NORMAL))
    }

    @Test fun `an app's call in the communication mode is a call too`() {
        val h = CallHold()
        h.seen(MODE_RINGTONE)
        assertEquals(Verdict.Wait(CallHold.AGAIN_MS, talking = true), h.gone(0L, "voip", true, MODE_IN_COMMUNICATION))
    }

    @Test fun `a phone whose mode says nothing - a second and a half of patience, and the end is not for sure`() {
        val h = CallHold()
        h.seen(MODE_NORMAL)                         // the mode said "no call" while the call's notification was up: not believed
        assertEquals(Verdict.Wait(CallHold.AGAIN_MS, talking = false), h.gone(0L, "ring", true, MODE_NORMAL))
        assertEquals(Verdict.Wait(CallHold.AGAIN_MS, talking = false), h.look(1_400L, MODE_NORMAL))
        assertEquals(Verdict.Clear(sure = false), h.look(1_500L, MODE_NORMAL))
    }

    @Test fun `a mode that never leaves a call does not hold a page for ever`() {
        val h = CallHold()
        h.seen(MODE_IN_COMMUNICATION)
        h.gone(0L, "voip", false, MODE_IN_COMMUNICATION)
        assertEquals(Verdict.Wait(CallHold.AGAIN_MS, talking = true), h.look(CallHold.MAX_MS - 1, MODE_IN_COMMUNICATION))
        assertEquals(Verdict.Clear(sure = false), h.look(CallHold.MAX_MS, MODE_IN_COMMUNICATION))
    }

    @Test fun `nothing held - nothing to wait for`() {
        assertEquals(Verdict.Clear(sure = false), CallHold().look(0L, MODE_IN_CALL))
    }

    // ---- with the navigation: the relay, the live watch and the table together ----

    /** The relay and the shell in small: the call's notifications come and go, the mode changes, and what the back does is kept. */
    private class Phone {
        val hold = CallHold(); val watch = LiveWatch()
        var nav = RearNav.State(lit = true)
        val anims = ArrayList<Anim>()
        var mode = MODE_NORMAL
        private var key: String? = null; private var running = false       // what the back was last told
        private var again = Long.MAX_VALUE
        private fun mark() = key?.let { k -> PageRow.call(k, !running).let { "${it.key}#${it.phase}" } }
        private fun tell(now: Long, atOnce: Boolean = false) {
            val marks = arrayOfNulls<String>(Live.values().size); marks[Live.CALL.ordinal] = mark()
            for (i in watch.look(now, marks, nav, atOnce) { it == Live.CALL }.inputs) { val s = RearNav.next(nav, i); nav = s.state; if (s.anim != Anim.NONE) anims += s.anim }
        }
        fun posted(now: Long, k: String, talking: Boolean) { hold.seen(mode); again = Long.MAX_VALUE; key = k; running = talking; tell(now) }
        fun removed(now: Long) = verdict(now, hold.gone(now, key!!, !running, mode))
        private fun verdict(now: Long, v: Verdict) {
            when (v) {
                is Verdict.Wait -> { if (v.talking && !running) { running = true; tell(now) }; again = now + v.againMs }
                is Verdict.Clear -> { key = null; again = Long.MAX_VALUE; tell(now, atOnce = v.sure) }
            }
        }
        /** Time passes, 50 ms at a step: the hold is asked when it wanted to be, the live watch as the shell's own loop does. */
        fun until(from: Long, to: Long) { var t = from; while (t <= to) { if (t >= again) verdict(t, hold.look(t, mode)); tell(t); t += 50L } }
        val seen get() = RearNav.seen(nav)
    }

    @Test fun `answering changes the call page in place - no page goes out or comes in, whatever the gap between the two notifications`() {
        for (gap in listOf(0L, 200L, 700L, 900L, 1_400L, 3_000L)) {
            val p = Phone()
            p.mode = MODE_RINGTONE; p.posted(0L, "incoming", talking = false)
            assertEquals(listOf(Anim.LIVE_IN), p.anims)
            p.until(50L, 4_000L)
            p.mode = MODE_IN_CALL; p.removed(4_050L)                    // he answers: the incoming notification goes
            p.until(4_100L, 4_050L + gap)
            p.posted(4_050L + gap + 50L, "ongoing", talking = true)     // and the call's own notification comes
            p.until(4_150L + gap, 20_000L)
            assertEquals("gap $gap ms: only the page's coming, at the start", listOf(Anim.LIVE_IN), p.anims)
            assertEquals(RearNav.Seen.LIVE, p.seen)
        }
    }

    @Test fun `the call ending takes its page away once, and soon`() {
        val p = Phone()
        p.mode = MODE_RINGTONE; p.posted(0L, "incoming", false); p.until(50L, 2_000L)
        p.mode = MODE_IN_CALL; p.removed(2_050L); p.posted(2_900L, "ongoing", true); p.until(2_950L, 60_000L)
        p.mode = MODE_NORMAL; p.removed(60_050L)
        p.until(60_100L, 60_300L)
        assertEquals("gone a quarter of a second after he hung up", RearNav.Seen.MOON, p.seen)
        p.until(60_350L, 70_000L)
        assertEquals(listOf(Anim.LIVE_IN, Anim.LIVE_OUT), p.anims)
    }

    @Test fun `a call he sent away and then answers comes back once, as an answered call`() {
        val p = Phone()
        p.mode = MODE_RINGTONE; p.posted(0L, "incoming", false); p.until(50L, 1_000L)
        p.nav = RearNav.next(p.nav, RearNav.Input.Stroke(RearNav.Way.RIGHT)).state
        assertEquals(RearNav.Seen.MOON, p.seen)
        p.mode = MODE_IN_CALL; p.removed(1_050L); p.until(1_100L, 1_900L); p.posted(1_950L, "ongoing", true); p.until(2_000L, 9_000L)
        assertEquals(RearNav.Seen.LIVE, p.seen)
        assertEquals("in once at the start, in once more at the answer", listOf(Anim.LIVE_IN, Anim.LIVE_IN), p.anims)
    }

    @Test fun `without the hold a gap of 900 ms is the page out and in again - what was seen on the phone`() {
        val watch = LiveWatch(); var nav = RearNav.State(lit = true); val anims = ArrayList<Anim>()
        fun tell(now: Long, mark: String?) {
            val marks = arrayOfNulls<String>(Live.values().size); marks[Live.CALL.ordinal] = mark
            for (i in watch.look(now, marks, nav) { true }.inputs) { val s = RearNav.next(nav, i); nav = s.state; if (s.anim != Anim.NONE) anims += s.anim }
        }
        tell(0L, "incoming#0")
        var t = 4_000L; while (t < 4_900L) { tell(t, null); t += 50L }
        tell(4_900L, "ongoing#1")
        assertEquals(listOf(Anim.LIVE_IN, Anim.LIVE_OUT, Anim.LIVE_IN), anims)
    }
}
