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
 * The back's navigation, the whole table (RearNav): every state x every input -> one next state, one animation;
 * and every sequence of three inputs from every state that can be reached.
 */
class RearNavTest {
    private fun bits(vararg k: Live) = k.fold(0) { a, l -> a or (1 shl l.ordinal) }
    private val moon = State(lit = true)
    private fun live(vararg k: Live) = State(lit = true, row = Row.LIVE, lives = bits(*k))
    private fun usage(vararg k: Live) = State(lit = true, row = Row.USAGE, lives = bits(*k))
    /** The live page (the highest of [k]) sent away: the moon. */
    private fun away(vararg k: Live) = State(lit = true, row = Row.HOME, lives = bits(*k), sent = 1 shl k.maxOf { it.ordinal })
    private fun stroke(way: Way, from: From = From.MIDDLE, sliding: Boolean = false) = Input.Stroke(way, from, sliding)
    private val L = Way.LEFT; private val R = Way.RIGHT; private val U = Way.UP; private val D = Way.DOWN

    private fun State.go(vararg inputs: Input): State = inputs.fold(this) { s, i -> RearNav.next(s, i).state }
    private fun check(from: State, input: Input, to: State, anim: Anim = Anim.NONE, does: Does = Does.NOTHING) {
        val step = RearNav.next(from, input)
        assertEquals("$from + $input", RearNav.Step(to, anim, does), step)
    }

    /** Every state that can be reached from a dark back with nothing live. */
    private val reachable: List<State> by lazy {
        val seen = LinkedHashSet<State>(); val todo = ArrayDeque<State>()
        seen += State(); todo += State()
        while (todo.isNotEmpty()) {
            val s = todo.removeFirst()
            for (i in RearNav.INPUTS) { val n = RearNav.next(s, i).state; if (seen.add(n)) todo += n }
        }
        seen.toList()
    }

    // -- ALONG THE PINNED NOTES ------------------------------------

    private fun notes(pins: Int, pin: Int = 0, under: State = moon) = under.copy(notes = true, pins = pins, pin = pin)

    @Test fun `on the notes, going right is the next older note and going back the next newer, one at a time, and it does not go round`() {
        // Three notes: 1 (newest) -> 2 -> 3, and there it ends.
        check(notes(3, 0), stroke(L), notes(3, 1), Anim.NOTE_OLDER)
        check(notes(3, 1), stroke(L), notes(3, 2), Anim.NOTE_OLDER)
        check(notes(3, 2), stroke(L), notes(3, 2), Anim.NOTE_END_OLDER)
        check(notes(3, 2), stroke(R), notes(3, 1), Anim.NOTE_NEWER)
        check(notes(3, 1), stroke(R), notes(3, 0), Anim.NOTE_NEWER)
        check(notes(3, 0), stroke(R), notes(3, 0), Anim.NOTE_END_NEWER)
        // Two notes.
        check(notes(2, 0), stroke(L), notes(2, 1), Anim.NOTE_OLDER)
        check(notes(2, 1), stroke(L), notes(2, 1), Anim.NOTE_END_OLDER)
        check(notes(2, 1), stroke(R), notes(2, 0), Anim.NOTE_NEWER)
        // One note, and none: only the give, either way.
        for (n in 0..1) { check(notes(n), stroke(L), notes(n), Anim.NOTE_END_OLDER); check(notes(n), stroke(R), notes(n), Anim.NOTE_END_NEWER) }
        // Many.
        var s = notes(20, 0)
        for (i in 1..19) { s = RearNav.next(s, stroke(L)).also { assertEquals(Anim.NOTE_OLDER, it.anim) }.state; assertEquals(i, s.pin) }
        check(s, stroke(L), s, Anim.NOTE_END_OLDER)
    }

    @Test fun `the same from every edge, over whatever page lies under, and never while the last turn still slides`() {
        for (under in listOf(moon, live(Live.TIMER), away(Live.MUSIC), live(Live.CALL))) for (from in From.values()) {
            check(notes(3, 1, under), stroke(L, from), notes(3, 2, under), Anim.NOTE_OLDER)
            check(notes(3, 1, under), stroke(R, from), notes(3, 0, under), Anim.NOTE_NEWER)
        }
        check(notes(3, 1), stroke(L, sliding = true), notes(3, 1))
        check(notes(3, 1), stroke(R, sliding = true), notes(3, 1))
    }

    @Test fun `on home sideways is the timer and Claude as before, however many notes are pinned`() {
        for (pins in listOf(0, 1, 2, 7)) {
            check(moon.copy(pins = pins), stroke(L), usage().copy(pins = pins), Anim.USAGE_IN)
            check(live(Live.TIMER).copy(pins = pins), stroke(L), usage(Live.TIMER).copy(pins = pins), Anim.USAGE_IN)
            check(away(Live.TIMER).copy(pins = pins), stroke(L), live(Live.TIMER).copy(pins = pins), Anim.LIVE_IN)
            check(moon.copy(pins = pins), stroke(R), moon.copy(pins = pins), does = Does.OPEN_CAMERA)
        }
    }

    @Test fun `leaving the notes is the stroke down from whichever note he is on, and opening them again starts at the newest`() {
        for (pin in 0..2) {
            val left = RearNav.next(notes(3, pin), stroke(D))
            assertEquals(RearNav.Step(moon.copy(pins = 3), Anim.NOTES_OUT), left)
            assertEquals(RearNav.Step(notes(3, 0), Anim.NOTES_IN), RearNav.next(left.state, stroke(U)))
        }
        // Left alone, the back dark, another's app: the same, the newest next time.
        for (i in listOf(Input.NotesAlone, Input.Dark, Input.OthersTake)) assertEquals("$i", 0, RearNav.next(notes(3, 2), i).state.pin)
        assertEquals(0, notes(3, 2).go(Input.Dark, Input.Lit, stroke(U)).pin)
    }

    @Test fun `not on the pop-up - a stroke there only takes the pop-up away`() {
        val popped = notes(3, 1).copy(pop = true)
        check(popped, stroke(L), notes(3, 1), Anim.POP_OUT)
        check(popped, stroke(R), notes(3, 1), Anim.POP_OUT)
    }

    @Test fun `a note's bin - the next older one shows in its place, the last if it was the oldest, and a note before his moves him with it`() {
        check(notes(3, 1), Input.PinRemoved(1), notes(2, 1))            // on the middle one: the one that was third shows
        check(notes(3, 2), Input.PinRemoved(2), notes(2, 1))            // on the oldest: the last there is now
        check(notes(3, 0), Input.PinRemoved(0), notes(2, 0))            // on the newest: the next
        check(notes(1, 0), Input.PinRemoved(0), notes(0, 0))            // the only one: the page that says nothing is pinned
        check(notes(3, 2), Input.PinRemoved(0), notes(2, 1))            // a newer one removed on the phone: he stays on his note
        check(notes(3, 0), Input.PinRemoved(2), notes(2, 0))
        check(notes(3, 1), Input.PinRemoved(7), notes(3, 1))            // no such note
        // While a turn is under way (the state is already the note he turned to): the same rule from there.
        assertEquals(notes(2, 1), notes(3, 0).go(stroke(L), Input.PinRemoved(1)))
        // Not on the notes: only the count.
        check(moon.copy(pins = 3), Input.PinRemoved(1), moon.copy(pins = 2))
    }

    @Test fun `a new pin while he is on an older note - he stays on his note until it arrives, and its arrival shows the new one`() {
        // The list grows by one in front: his note is one further back now.
        check(notes(3, 2), Input.PinsAre(4), notes(4, 3))
        check(notes(3, 1), Input.PinsAre(5), notes(5, 3))
        // On the newest he stays on the first place (the new note is what the page is about to show anyway).
        check(notes(3, 0), Input.PinsAre(4), notes(4, 0))
        // The arrival itself (the beam has landed): the newest, whichever he was on; and the notes come if they were not up.
        check(notes(4, 3), Input.NotePinned, notes(4, 0))
        check(moon.copy(pins = 4), Input.NotePinned, notes(4, 0), Anim.NOTES_IN)
        // Fewer by another way (cleared on the phone): never past the last.
        check(notes(4, 3), Input.PinsAre(2), notes(2, 1))
        check(notes(4, 3), Input.PinsAre(0), notes(0, 0))
        check(moon, Input.PinsAre(3), moon.copy(pins = 3))
    }

    @Test fun `the note shown is always one that is there, and the newest whenever the notes are not up - in every state that can be reached`() {
        for (s in reachable) {
            assertTrue("$s", s.pins >= 0 && s.pin >= 0 && s.pin <= maxOf(s.pins - 1, 0))
            if (!s.notes) assertEquals("$s", 0, s.pin)
        }
        assertTrue("notes on an older note are reached", reachable.any { it.notes && it.pin == 2 })
    }

    // -- LEAVING USAGE WITH A LIVE PAGE UNDER IT ----------------------------------------------------------------

    @Test fun `from usage one stroke back is the timer page, directly, and a second the moon`() {
        val onUsage = moon.go(Input.LiveComes(Live.TIMER), stroke(L))
        assertEquals(usage(Live.TIMER), onUsage)
        // The live page is in its place under usage the whole time: usage slides off it, nothing comes in after.
        assertTrue(onUsage.liveShown)
        val back = RearNav.next(onUsage, stroke(R))
        assertEquals(RearNav.Step(live(Live.TIMER), Anim.USAGE_OUT), back)
        assertEquals(Seen.LIVE, RearNav.seen(back.state))
        val home = RearNav.next(back.state, stroke(R))
        assertEquals(RearNav.Step(away(Live.TIMER), Anim.LIVE_OUT), home)
        assertEquals(Seen.MOON, RearNav.seen(home.state))
        // Left alone on usage: the same, the timer page, not the moon.
        assertEquals(RearNav.Step(live(Live.TIMER), Anim.USAGE_OUT), RearNav.next(onUsage, Input.UsageAlone))
        // And with nothing live, back from usage is the moon.
        assertEquals(RearNav.Step(moon, Anim.USAGE_OUT), RearNav.next(usage(), stroke(R)))
    }

    // -- THE TABLE: the row ---------------------------------------------------------------------------------------

    @Test fun `the moon`() {
        check(moon, stroke(L), usage(), Anim.USAGE_IN)                       // nothing live: right of home is usage
        check(moon, stroke(R), moon, does = Does.OPEN_CAMERA)
        check(moon, stroke(U), moon.copy(notes = true), Anim.NOTES_IN)
        check(moon, stroke(D), moon)
        check(moon, stroke(D, From.TOP_EDGE), moon.copy(shade = true), Anim.SHADE_IN)
        check(moon, Input.Tap, moon, does = Does.TAP)
        check(moon, Input.DoubleTap, moon, does = Does.DIM)
        check(moon, Input.Hold, moon, does = Does.NEXT_FACE)
        check(moon, Input.DoubleTapHold, moon, does = Does.BACK_OFF)
        // The live page he sent away is one stroke right of home, and usage one more.
        check(away(Live.TIMER), stroke(L), live(Live.TIMER), Anim.LIVE_IN)
        check(away(Live.TIMER), stroke(R), away(Live.TIMER), does = Does.OPEN_CAMERA)
        check(away(Live.TIMER), stroke(U), away(Live.TIMER).copy(notes = true), Anim.NOTES_IN)
    }

    @Test fun `a live page - timer, music, call`() {
        for (k in listOf(Live.TIMER, Live.MUSIC, Live.CALL)) {
            check(live(k), stroke(L), usage(k), Anim.USAGE_IN)
            check(live(k), stroke(R), away(k), Anim.LIVE_OUT)
            check(live(k), stroke(U), live(k).copy(notes = true), Anim.NOTES_IN)
            check(live(k), stroke(D), live(k))
            check(live(k), stroke(D, From.TOP_EDGE), live(k).copy(shade = true), Anim.SHADE_IN)
            check(live(k), Input.Tap, live(k), does = Does.TAP)
            check(live(k), Input.DoubleTap, live(k), does = Does.DIM)
            check(live(k), Input.Hold, live(k), does = Does.NEXT_FACE)
            check(live(k), Input.DoubleTapHold, live(k), does = Does.BACK_OFF)
        }
    }

    @Test fun `the charging page goes for any stroke but the one to usage`() {
        val c = live(Live.CHARGE)
        check(c, stroke(L), usage(Live.CHARGE), Anim.USAGE_IN)
        check(c, stroke(R), away(Live.CHARGE), Anim.LIVE_OUT)
        check(c, stroke(U), away(Live.CHARGE), Anim.LIVE_OUT)
        check(c, stroke(D), away(Live.CHARGE), Anim.LIVE_OUT)
        check(c, Input.Hold, c)
        check(c, Input.DoubleTapHold, c)
        check(c, Input.Tap, c, does = Does.TAP)
        // Sent away with the charger still in: one stroke right of home, as any live page.
        check(away(Live.CHARGE), stroke(L), c, Anim.LIVE_IN)
    }

    @Test fun `the usage page`() {
        for (s in listOf(usage(), usage(Live.TIMER), usage(Live.MUSIC, Live.CHARGE))) {
            check(s, stroke(L), s); check(s, stroke(U), s); check(s, stroke(D), s)
            check(s, stroke(D, From.TOP_EDGE), s.copy(shade = true), Anim.SHADE_IN)
            check(s, Input.Tap, s, does = Does.TAP)
            check(s, Input.DoubleTap, s, does = Does.DIM)
            check(s, Input.Hold, s); check(s, Input.DoubleTapHold, s)
        }
        check(usage(Live.MUSIC, Live.CHARGE), stroke(R), live(Live.MUSIC, Live.CHARGE), Anim.USAGE_OUT)
    }

    @Test fun `the notes lie over the row and leave downwards`() {
        for (under in listOf(moon, live(Live.TIMER), away(Live.MUSIC))) {
            val n = under.copy(notes = true)
            check(n, stroke(D), under, Anim.NOTES_OUT)
            // With one note or none, sideways only gives (the cells with more: the tests of the notes below).
            check(n, stroke(L), n, Anim.NOTE_END_OLDER)
            check(n, stroke(R), n, Anim.NOTE_END_NEWER)
            check(n, stroke(U), n)
            check(n, stroke(D, From.TOP_EDGE), n.copy(shade = true), Anim.SHADE_IN)
            check(n, Input.NotesAlone, under, Anim.NOTES_OUT)
            check(n, Input.Tap, n, does = Does.TAP)
            check(n, Input.Hold, n)
        }
        // Pinned from the phone while usage is up: the notes, over the page that was under usage.
        check(usage(Live.TIMER), Input.NotePinned, live(Live.TIMER).copy(notes = true), Anim.NOTES_IN)
        check(usage(), Input.NotePinned, moon.copy(notes = true), Anim.NOTES_IN)
    }

    @Test fun `the notifications' pages take every stroke and tap themselves until they shut`() {
        for (under in listOf(moon, live(Live.TIMER), usage(Live.TIMER), moon.copy(notes = true))) {
            val s = under.copy(shade = true)
            for (from in From.values()) for (way in Way.values()) check(s, stroke(way, from), s, does = Does.SHADE_OWN)
            check(s, Input.Tap, s, does = Does.SHADE_OWN)
            check(s, Input.ShadeShut, under, Anim.SHADE_OUT)
        }
    }

    @Test fun `a stroke is the same from the middle and from every edge - there is no Back gesture`() {
        for (s in reachable) for (way in Way.values()) for (from in From.values()) {
            if (from == From.TOP_EDGE && way == D) continue
            assertEquals("$s $way from $from", RearNav.next(s, stroke(way)), RearNav.next(s, stroke(way, from)))
        }
        // A STROKE FROM THE EDGE: on the timer page from the very right edge to the left = usage.
        check(live(Live.TIMER), stroke(L, From.RIGHT_EDGE), usage(Live.TIMER), Anim.USAGE_IN)
    }

    @Test fun `a stroke whose finger landed while the last page was sliding moves nothing`() {
        for (s in reachable) for (way in Way.values()) for (from in From.values()) {
            val step = RearNav.next(s, stroke(way, from, sliding = true))
            if (from == From.TOP_EDGE && way == D && RearNav.seen(s) !in setOf(Seen.DARK, Seen.OTHERS, Seen.SHADE) && s.lit && s.ours) continue
            // On a pop-up any stroke takes the pop-up away, and that is all it does.
            if (s.pop && s.lit && s.ours && !s.shade) { assertEquals("$s $way $from", s.copy(pop = false), step.state); continue }
            assertEquals("$s $way $from", s, step.state)
            assertEquals(Anim.NONE, step.anim)
        }
    }

    // -- what is live ----------------------------------------------------------------------------------------------

    @Test fun `something new that is live comes up by itself over the moon, and stays under usage and the notes unless urgent`() {
        check(moon, Input.LiveComes(Live.TIMER), live(Live.TIMER), Anim.LIVE_IN)
        check(moon, Input.LiveComes(Live.CHARGE), live(Live.CHARGE), Anim.LIVE_IN)
        // On usage: it is under it, one stroke back; usage stays.
        check(usage(), Input.LiveComes(Live.MUSIC), usage(Live.MUSIC))
        // In the notes: under them.
        check(moon.copy(notes = true), Input.LiveComes(Live.MUSIC), live(Live.MUSIC).copy(notes = true))
        // Under the shade: under it.
        check(moon.copy(shade = true), Input.LiveComes(Live.TIMER), live(Live.TIMER).copy(shade = true))
        // A call, a timer run out: over everything of ours.
        check(usage(), Input.LiveComes(Live.CALL, urgent = true), live(Live.CALL), Anim.USAGE_OUT)
        check(moon.copy(notes = true), Input.LiveComes(Live.CALL, urgent = true), live(Live.CALL), Anim.NOTES_OUT)
        check(usage(Live.TIMER).copy(shade = true), Input.LiveComes(Live.TIMER, urgent = true), live(Live.TIMER), Anim.SHADE_OUT)
        // Dark: it is the live page when the back lights.
        check(State(), Input.LiveComes(Live.TIMER), State(row = Row.LIVE, lives = bits(Live.TIMER)))
    }

    @Test fun `sent away stays away until something new is on top`() {
        val a = away(Live.TIMER)
        check(a, Input.LiveUpdates(Live.TIMER), a)
        check(a, Input.Dark, a.copy(lit = false), Anim.CUT)
        assertEquals(a, a.go(Input.Dark, Input.Lit))
        assertEquals(a, a.go(stroke(U), stroke(D)))
        assertEquals(a, a.go(stroke(D, From.TOP_EDGE), Input.ShadeShut))
        assertEquals(a, a.go(Input.PopArrives, Input.PopEnds))
        assertEquals(a, a.go(Input.OthersTake, Input.OthersLeave))
        // Music starting under a timer he sent away is not on top: the moon stays.
        check(a, Input.LiveComes(Live.MUSIC), away(Live.TIMER, Live.MUSIC))
        // Another timer, or its running out: new on top, it comes.
        check(a, Input.LiveComes(Live.TIMER), live(Live.TIMER), Anim.LIVE_IN)
        // (The timer under the call is still the one he sent away: it does not take the page when the call ends.)
        check(a, Input.LiveComes(Live.CALL, urgent = true), live(Live.TIMER, Live.CALL).copy(sent = bits(Live.TIMER)), Anim.LIVE_IN)
        // It ends: nothing is away any more.
        check(a, Input.LiveEnds(Live.TIMER), moon)
    }

    @Test fun `TIMER AND MUSIC - the timer is the live page, music has none until the timer ends`() {
        val both = moon.go(Input.LiveComes(Live.MUSIC), Input.LiveComes(Live.TIMER))
        assertEquals(live(Live.MUSIC, Live.TIMER), both)
        assertEquals(Live.TIMER, both.live)
        check(live(Live.MUSIC), Input.LiveComes(Live.TIMER), both, Anim.LIVE_SWAP)
        // The row is still home | one live page | usage.
        assertEquals(usage(Live.MUSIC, Live.TIMER), both.go(stroke(L)))
        // Music starting while the timer shows: nothing moves.
        check(live(Live.TIMER), Input.LiveComes(Live.MUSIC), both)
        // The timer ends: what is playing takes its place, where he is looking; no slide.
        check(both, Input.LiveEnds(Live.TIMER), live(Live.MUSIC), Anim.LIVE_SWAP)
        // Music ends under the timer: nothing to see.
        check(both, Input.LiveEnds(Live.MUSIC), live(Live.TIMER))
        // He had sent the timer away: when it ends the moon stays (music was playing all along, it is not new).
        check(away(Live.MUSIC, Live.TIMER), Input.LiveEnds(Live.TIMER), away(Live.MUSIC))
        // SENT AWAY IS KEPT PER KIND (the review's 5 and 22): music he sent away is still away when a timer that came
        // over it ends, though he was looking at the timer's page...
        val musicAway = away(Live.MUSIC)
        val timerOver = musicAway.go(Input.LiveComes(Live.TIMER))
        assertEquals(Seen.LIVE, RearNav.seen(timerOver)); assertEquals(Live.TIMER, timerOver.live)
        check(timerOver, Input.LiveEnds(Live.TIMER), musicAway, Anim.LIVE_OUT)
        // ...and a timer he sent away is not brought back by a call that came, was sent away too, and ended.
        val timerAway = away(Live.TIMER)
        val callAway = timerAway.go(Input.LiveComes(Live.CALL, urgent = true), stroke(R))
        assertEquals(Seen.MOON, RearNav.seen(callAway))
        check(callAway, Input.LiveEnds(Live.CALL), timerAway)
        // Music starting again after a stop is new: it comes, whatever he did with the last.
        check(musicAway, Input.LiveComes(Live.MUSIC), live(Live.MUSIC), Anim.LIVE_IN)
        // The order: a call over a timer over music over charging.
        assertEquals(Live.CALL, live(Live.CHARGE, Live.MUSIC, Live.TIMER, Live.CALL).live)
        assertEquals(Live.MUSIC, live(Live.CHARGE, Live.MUSIC).live)
    }

    @Test fun `what is live ends`() {
        check(live(Live.TIMER), Input.LiveEnds(Live.TIMER), moon, Anim.LIVE_OUT)
        check(usage(Live.TIMER), Input.LiveEnds(Live.TIMER), usage())
        check(live(Live.TIMER).copy(notes = true), Input.LiveEnds(Live.TIMER), moon.copy(notes = true))
        check(moon, Input.LiveEnds(Live.TIMER), moon)
    }

    // -- pop-up, dark, others -----------------------------------------------------------------------------------

    @Test fun `a pop-up arrives on every page and leaves it as it was`() {
        for (s in listOf(moon, live(Live.TIMER), usage(Live.MUSIC), away(Live.CALL), moon.copy(notes = true), live(Live.CHARGE).copy(shade = true))) {
            check(s, Input.PopArrives, s.copy(pop = true), Anim.POP_IN)
            check(s.copy(pop = true), Input.PopEnds, s, Anim.POP_OUT)
            assertEquals(Seen.POP, RearNav.seen(s.copy(pop = true)))
        }
        // IT HAS THE WHOLE BACK (the review's 3 and 38): a stroke takes it away and does nothing to the page under it,
        // on the moon too (no Camera opened unseen); a tap presses no button under it.
        for (way in Way.values()) {
            check(live(Live.TIMER).copy(pop = true), stroke(way), live(Live.TIMER), Anim.POP_OUT)
            check(moon.copy(pop = true), stroke(way), moon, Anim.POP_OUT)
            check(usage(Live.CALL).copy(pop = true), stroke(way), usage(Live.CALL), Anim.POP_OUT)
        }
        check(live(Live.CALL).copy(pop = true), Input.Tap, live(Live.CALL).copy(pop = true), does = Does.BRIGHT)
        check(live(Live.CALL).copy(pop = true), Input.Hold, live(Live.CALL).copy(pop = true))
        check(live(Live.CALL).copy(pop = true), Input.DoubleTapHold, live(Live.CALL).copy(pop = true))
        // Pulling the notifications down takes it away.
        check(moon.copy(pop = true), stroke(D, From.TOP_EDGE), moon.copy(shade = true), Anim.SHADE_IN)
        check(State(), Input.PopArrives, State())
    }

    @Test fun `dark and lit again on every page - usage, the notes and the shade are gone, the row's page stays`() {
        check(moon, Input.Dark, State(), Anim.CUT)
        check(live(Live.TIMER), Input.Dark, live(Live.TIMER).copy(lit = false), Anim.CUT)
        check(usage(Live.TIMER), Input.Dark, live(Live.TIMER).copy(lit = false), Anim.CUT)
        check(usage(), Input.Dark, State(), Anim.CUT)
        check(live(Live.MUSIC).copy(notes = true), Input.Dark, live(Live.MUSIC).copy(lit = false), Anim.CUT)
        check(away(Live.TIMER).copy(shade = true, pop = true), Input.Dark, away(Live.TIMER).copy(lit = false), Anim.CUT)
        check(live(Live.TIMER).copy(lit = false), Input.Lit, live(Live.TIMER), Anim.CUT)
        // Dark: no stroke or tap is ours.
        for (s in reachable.filter { !it.lit }) for (i in RearNav.INPUTS) if (i is Input.Stroke || i == Input.Tap || i == Input.DoubleTap || i == Input.Hold || i == Input.DoubleTapHold) {
            assertEquals("$s $i", RearNav.Step(s), RearNav.next(s, i))
        }
    }

    @Test fun `the Camera in and out`() {
        check(moon, stroke(R), moon, does = Does.OPEN_CAMERA)
        check(moon, Input.OthersTake, moon.copy(ours = false), Anim.CUT)
        check(usage(Live.TIMER), Input.OthersTake, live(Live.TIMER).copy(ours = false), Anim.CUT)
        check(moon.copy(ours = false), Input.OthersLeave, moon, Anim.CUT)
        assertEquals(Seen.OTHERS, RearNav.seen(moon.copy(ours = false)))
        // While it is theirs nothing of ours answers a finger.
        for (s in reachable.filter { !it.ours }) for (i in RearNav.INPUTS) if (i is Input.Stroke || i == Input.Tap || i == Input.DoubleTap || i == Input.Hold || i == Input.DoubleTapHold) {
            assertEquals("$s $i", RearNav.Step(s), RearNav.next(s, i))
        }
    }

    // -- the whole table: every state, every input, every sequence of three --------------------------------------------

    private fun holds(s: State): String? = when {
        s.sent and s.lives.inv() != 0 -> "something sent away that is not live"
        s.row == Row.LIVE && !s.liveShown -> "the live page with nothing to show"
        s.row == Row.HOME && s.liveShown -> "the moon while a live page is in the row and not sent away"
        s.row == Row.USAGE && s.notes -> "usage under the notes"
        !s.lit && (s.notes || s.shade || s.pop || s.row == Row.USAGE) -> "something up on a dark back"
        !s.ours && (s.notes || s.shade || s.row == Row.USAGE) -> "a page of ours on another's back"
        else -> null
    }

    @Test fun `every state x every input gives one sound state, and nothing ever needs mending`() {
        // 1188 before the notes had a count and a place among them (none, one, two, three pinned; the first, second or third shown): 5724 with.
        assertTrue("states: ${reachable.size}", reachable.size in 100..20_000)
        var n = 0
        for (s in reachable) {
            assertEquals("$s", null, holds(s))
            for (i in RearNav.INPUTS) {
                val raw = RearNav.unmended(s, i)
                assertEquals("$s + $i needed mending", RearNav.norm(raw.state), raw.state)
                assertEquals(raw, RearNav.next(s, i))
                // No change of what is there without its animation, and none with nothing changed.
                // Nothing changed, nothing moves: but for the notes page's give at its first and last note, which is
                // exactly that (a sideways stroke on the notes with no note that way).
                val give = raw.anim == Anim.NOTE_END_OLDER || raw.anim == Anim.NOTE_END_NEWER
                if (raw.state == s && !give) assertEquals("$s + $i", Anim.NONE, raw.anim)
                if (give) assertTrue("$s + $i: a give is only a sideways stroke on the notes, and changes nothing", raw.state == s && s.notes && i is Input.Stroke && (i.way == Way.LEFT || i.way == Way.RIGHT))
                n++
            }
        }
        println("RearNav: ${reachable.size} states x ${RearNav.INPUTS.size} inputs = $n steps")
    }

    private fun rowAt(s: State) = s.row.ordinal

    /** What must hold of one step, whatever came before. Null when it does. */
    private fun stepFault(a: State, i: Input, b: State, anim: Anim): String? {
        val sa = RearNav.seen(a); val sb = RearNav.seen(b)
        // Leaving usage shows the live page that is under it, or the moon when there is none: never the moon first.
        if (a.row == Row.USAGE && b.row != Row.USAGE && b.row != (if (b.liveShown) Row.LIVE else Row.HOME)) return "left usage for the wrong page"
        // And a stroke or its own time takes nothing live away or back with it: what was under usage is what shows.
        if (a.row == Row.USAGE && b.row != Row.USAGE && (i is Input.Stroke || i == Input.UsageAlone) && (b.liveShown != a.liveShown || b.lives != a.lives)) return "leaving usage changed the live page"
        // The live page never appears out of the moon but for something new or his own stroke to it.
        if (sa == Seen.MOON && sb == Seen.LIVE && !(i is Input.LiveComes || (i is Input.Stroke && i.way == Way.LEFT && !i.sliding))) return "the live page popped up"
        // A thing that leaves uncovers what was under it, in its place: the live page is never slid in then.
        if (anim == Anim.LIVE_IN && !(sa == Seen.MOON || sa == Seen.POP)) return "the live page slid in from under something"
        // Under a pop-up no stroke moves the row and no tap or hold reaches the page.
        if (a.pop && i is Input.Stroke && a.lit && a.ours && !a.shade && !(i.from == From.TOP_EDGE && i.way == Way.DOWN) && (b.row != a.row || b.notes != a.notes || b.sent != a.sent)) return "a stroke moved the page under a pop-up"
        if (i is Input.Stroke) {
            // One stroke, one page: never two steps along the row, and home to usage only with no live page between.
            val d = rowAt(b) - rowAt(a)
            if (d > 1 && !(a.row == Row.HOME && a.lives == 0)) return "two pages for one stroke"
            // Back from usage is the live page when it is there under it; sent away (or with none), it is the moon.
            if (d < -1 && a.liveShown) return "two pages back for one stroke"
            if (i.way == Way.RIGHT && rowAt(b) > rowAt(a)) return "a stroke to the right went right along the row"
            if (i.way == Way.LEFT && rowAt(b) < rowAt(a)) return "a stroke to the left went left along the row"
            if (b.lives != a.lives) return "a stroke changed what is live"
        }
        if ((i == Input.Tap || i == Input.DoubleTap || i == Input.Hold || i == Input.DoubleTapHold || i is Input.LiveUpdates) && b != a) return "it changed the page"
        return null
    }

    @Test fun `every sequence of three inputs from every state`() {
        val index = HashMap<State, Int>(); reachable.forEachIndexed { k, s -> index[s] = k }
        val inputs = RearNav.INPUTS
        val to = Array(reachable.size) { IntArray(inputs.size) }
        val fault = Array(reachable.size) { arrayOfNulls<String>(inputs.size) }
        for ((k, s) in reachable.withIndex()) for ((j, i) in inputs.withIndex()) {
            val step = RearNav.next(s, i)
            to[k][j] = index[step.state] ?: error("$s + $i leaves the table: ${step.state}")
            fault[k][j] = stepFault(s, i, step.state, step.anim)
        }
        // Each step of each sequence is sound (so every path is), and the sequences are walked for what only a path shows.
        var sequences = 0L
        val left = inputs.indexOf(Input.Stroke(Way.LEFT)); val right = inputs.indexOf(Input.Stroke(Way.RIGHT))
        for (k in reachable.indices) for (a in inputs.indices) {
            fault[k][a]?.let { error("${reachable[k]} + ${inputs[a]}: $it") }
            val k1 = to[k][a]
            for (b in inputs.indices) {
                val k2 = to[k1][b]
                for (c in inputs.indices) {
                    val k3 = to[k2][c]
                    sequences++
                    // A STROKE OUT AND THE STROKE BACK: from a live page, left then right is that live page again, whatever came first.
                    if (b == left && c == right) {
                        val s1 = reachable[k1]
                        if (RearNav.seen(s1) == Seen.LIVE && k3 != k1) error("${reachable[k]} + ${inputs[a]}, then left and right from the live page, is ${reachable[k3]}")
                    }
                }
            }
        }
        println("RearNav: $sequences sequences of three inputs from ${reachable.size} states")
        assertEquals(reachable.size.toLong() * inputs.size * inputs.size * inputs.size, sequences)
    }

    @Test fun `the row both ways, with a live page, sent away and brought back`() {
        var s = moon.go(Input.LiveComes(Live.TIMER))
        val seen = ArrayList<Seen>()
        for (i in listOf(stroke(L), stroke(R), stroke(R), stroke(L), stroke(L), stroke(L), stroke(R), stroke(R), stroke(R))) { s = s.go(i); seen += RearNav.seen(s) }
        assertEquals(listOf(Seen.USAGE, Seen.LIVE, Seen.MOON, Seen.LIVE, Seen.USAGE, Seen.USAGE, Seen.LIVE, Seen.MOON, Seen.MOON), seen)
        assertFalse(moon.go(Input.LiveComes(Live.TIMER), stroke(R)).liveShown)
    }
}
