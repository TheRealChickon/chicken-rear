package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.RearNav.Anim
import dev.kb896.aiusage.rear.RearNav.Does
import dev.kb896.aiusage.rear.RearNav.From
import dev.kb896.aiusage.rear.RearNav.Input
import dev.kb896.aiusage.rear.RearNav.Live
import dev.kb896.aiusage.rear.RearNav.Row
import dev.kb896.aiusage.rear.RearNav.Seen
import dev.kb896.aiusage.rear.RearNav.State
import dev.kb896.aiusage.rear.RearNav.Way
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PIN TO LOCATION ON THE BACK (SPEC section 5, R19): a note whose place he reached STANDS as a page over the row until
 * he dismisses it. The whole table and every sequence of three are RearNavTest's (the arrival's inputs are in
 * RearNav.INPUTS); here what the arrival itself must do.
 */
class RearNavArrivalTest {
    private fun bits(vararg k: Live) = k.fold(0) { a, l -> a or (1 shl l.ordinal) }
    private val moon = State(lit = true)
    private fun stands(n: Int = 1, at: Int = 0, under: State = moon) = under.copy(arrivals = n, arrival = at)
    private fun stroke(way: Way, from: From = From.MIDDLE) = Input.Stroke(way, from)
    private fun check(from: State, input: Input, to: State, anim: Anim = Anim.NONE, does: Does = Does.NOTHING) =
        assertEquals("$from + $input", RearNav.Step(to, anim, does), RearNav.next(from, input))

    @Test fun `an arrival comes up over the moon, a live page, usage and the notes, and stands`() {
        check(moon, Input.ArrivalsAre(1), stands(), Anim.ARRIVAL_IN)
        assertEquals(Seen.ARRIVAL, RearNav.seen(stands()))
        val timer = State(lit = true, row = Row.LIVE, lives = bits(Live.TIMER))
        check(timer, Input.ArrivalsAre(1), stands(under = timer), Anim.ARRIVAL_IN)
        check(moon.copy(row = Row.USAGE), Input.ArrivalsAre(1), stands(), Anim.ARRIVAL_IN)
        check(moon.copy(notes = true, pins = 2), Input.ArrivalsAre(1), stands(under = moon.copy(pins = 2)), Anim.ARRIVAL_IN)
        // NO TIMEOUT: nothing but his dismissal takes it (being left alone is the notes' and usage's input, not its).
        check(stands(), Input.UsageAlone, stands()); check(stands(), Input.NotesAlone, stands())
    }

    @Test fun `on a dark back it is the state, seen when the back lights, and nothing wakes it`() {
        check(State(), Input.ArrivalsAre(1), State(arrivals = 1))
        check(State(arrivals = 1), Input.Lit, stands(), Anim.CUT)
        check(stands(), Input.Dark, State(arrivals = 1), Anim.CUT)
        assertEquals(Seen.DARK, RearNav.seen(State(arrivals = 1)))
    }

    @Test fun `down is not now, the bin is the shell's tap, sideways goes along the ones standing`() {
        check(stands(), stroke(Way.DOWN), moon, Anim.ARRIVAL_OUT, Does.ARRIVAL_LATER)
        check(stands(), Input.Tap, stands(), does = Does.TAP)
        check(stands(), Input.ArrivalRemoved(0), moon, Anim.ARRIVAL_OUT)
        check(stands(3, 0), stroke(Way.LEFT), stands(3, 1), Anim.ARRIVAL_OLDER)
        check(stands(3, 2), stroke(Way.LEFT), stands(3, 2), Anim.ARRIVAL_END_OLDER)
        check(stands(3, 1), stroke(Way.RIGHT), stands(3, 0), Anim.ARRIVAL_NEWER)
        check(stands(3, 0), stroke(Way.RIGHT), stands(3, 0), Anim.ARRIVAL_END_NEWER)
        // The bin on the second of three: the next older shows; one before his moves him with it.
        check(stands(3, 1), Input.ArrivalRemoved(1), stands(2, 1))
        check(stands(3, 2), Input.ArrivalRemoved(0), stands(2, 1))
        // A new arrival: he stays on the one he reads.
        check(stands(2, 1), Input.ArrivalsAre(3), stands(3, 2))
    }

    @Test fun `while it stands usage, the notes and the Camera are not reached, and up does nothing`() {
        check(stands(), stroke(Way.UP), stands())
        assertFalse(RearNav.next(stands(1), stroke(Way.RIGHT)).does == Does.OPEN_CAMERA)
        check(stands(), stroke(Way.LEFT), stands(), Anim.ARRIVAL_END_OLDER)
        check(stands(), Input.NotePinned, stands())
        check(stands(), Input.Hold, stands())
        // Off by hand as everywhere; a double tap dims.
        check(stands(), Input.DoubleTapHold, stands(), does = Does.BACK_OFF)
        check(stands(), Input.DoubleTap, stands(), does = Does.DIM)
    }

    @Test fun `a pull from the top is still the notifications, and a pop-up and the shade lie over it`() {
        check(stands(), stroke(Way.DOWN, From.TOP_EDGE), stands().copy(shade = true), Anim.SHADE_IN)
        assertEquals(Seen.SHADE, RearNav.seen(stands().copy(shade = true)))
        check(stands(), Input.PopArrives(), stands().copy(pop = true), Anim.POP_IN)
        check(stands().copy(pop = true), stroke(Way.DOWN), stands(), Anim.POP_OUT)
    }

    @Test fun `a call comes over it and it is there again when the call ends - a timer or music go under it`() {
        val call = RearNav.next(stands(), Input.LiveComes(Live.CALL, urgent = true))
        assertEquals(Anim.LIVE_IN, call.anim)
        assertEquals(Seen.LIVE, RearNav.seen(call.state))
        assertFalse(call.state.arrivalUp)
        val ended = RearNav.next(call.state, Input.LiveEnds(Live.CALL))
        assertEquals(Seen.ARRIVAL, RearNav.seen(ended.state))
        val timer = RearNav.next(stands(), Input.LiveComes(Live.TIMER))
        assertEquals(Anim.NONE, timer.anim)
        assertEquals(Seen.ARRIVAL, RearNav.seen(timer.state))
        assertEquals("the timer's page is in its place under it", Row.LIVE, timer.state.row)
        // On the call's page with an arrival under it: no usage, no notes.
        check(call.state, stroke(Way.LEFT), call.state)
        check(call.state, stroke(Way.UP), call.state)
    }

    @Test fun `every reachable state with an arrival standing has no notes and no usage`() {
        val seen = LinkedHashSet<State>(); val todo = ArrayDeque<State>()
        seen += State(); todo += State()
        while (todo.isNotEmpty()) { val s = todo.removeFirst(); for (i in RearNav.INPUTS) { val n = RearNav.next(s, i).state; if (seen.add(n)) todo += n } }
        val standing = seen.filter { it.arrivals > 0 }
        assertTrue(standing.isNotEmpty())
        for (s in standing) { assertFalse("$s", s.notes); assertFalse("$s", s.row == Row.USAGE); assertTrue("$s", s.arrival in 0 until s.arrivals) }
        println("RearNav: ${seen.size} states, ${standing.size} with an arrival standing")
    }
}
