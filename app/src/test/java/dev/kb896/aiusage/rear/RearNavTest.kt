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
    private fun stroke(way: Way, from: From = From.MIDDLE, sliding: Boolean = false, aimed: Boolean = true) = Input.Stroke(way, from, sliding, aimed)
    private val L = Way.LEFT; private val R = Way.RIGHT; private val U = Way.UP; private val D = Way.DOWN

    private fun State.go(vararg inputs: Input): State = inputs.fold(this) { s, i -> RearNav.next(s, i).state }
    private fun check(from: State, input: Input, to: State, anim: Anim = Anim.NONE, does: Does = Does.NOTHING) {
        val step = RearNav.next(from, input)
        assertEquals("$from + $input", RearNav.Step(to, anim, does), step)
    }

    /** What a pop-up thrown out going [way] does to its notification: by the one constant (RearNav.DISMISS_WAY). */
    private fun thrownDoes(way: Way) = if (way == RearNav.DISMISS_WAY) Does.DISMISS else Does.SNOOZE

    @Test fun `our camera uses the existing activity ownership and returns to the moon`() {
        val launch = RearNav.next(moon, stroke(R))
        assertEquals(Does.OPEN_CAMERA, launch.does)
        // A launch request is not proof of focus: a failed launch leaves the moon usable.
        assertEquals(moon, launch.state)
        val camera = moon.copy(ours = false)
        check(moon, Input.OthersTake, camera, Anim.CUT)
        for (way in Way.entries) check(camera, stroke(way), camera)
        check(camera, Input.Tap, camera)
        check(camera, Input.OthersLeave, moon, Anim.CUT)
        check(camera, Input.Dark, camera.copy(lit = false), Anim.CUT)
        check(camera.copy(lit = false), Input.OthersLeave, moon.copy(lit = false), Anim.CUT)
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

    @Test fun `not on the pop-up - a stroke there only takes the pop-up away, or throws it out, and the notes stay as they were`() {
        val popped = notes(3, 1).copy(pop = true)
        check(popped, stroke(U), notes(3, 1), Anim.POP_OUT)
        check(popped, stroke(D), notes(3, 1), Anim.POP_OUT)
        check(popped, stroke(L), notes(3, 1), Anim.POP_THROWN_LEFT, thrownDoes(L))
        check(popped, stroke(R), notes(3, 1), Anim.POP_THROWN_RIGHT, thrownDoes(R))
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

    @Test fun `the list changes elsewhere than in front - he stays on his note wherever it went`() {
        // One more behind him (a note for a place he has reached): where he was.
        check(notes(3, 1), Input.PinsAre(4, keep = 1), notes(4, 1))
        // One more in front of the first: one further back, also from the first place.
        check(notes(3, 0), Input.PinsAre(4, keep = 1), notes(4, 1))
        // One fewer in front of him (a note for a place he has left): one nearer.
        check(notes(3, 2), Input.PinsAre(2, keep = 1), notes(2, 1))
        // As many as before, his note moved (one came, another went).
        check(notes(3, 2), Input.PinsAre(3, keep = 1), notes(3, 1))
        check(arranging(3, 2), Input.PinsAre(3, keep = 1), notes(3, 1), Anim.ARRANGE_OUT)
        // Where it was already, nothing; past the end, or the notes not up, by the count's rule.
        check(notes(3, 1), Input.PinsAre(3, keep = 1), notes(3, 1))
        check(notes(3, 2), Input.PinsAre(4, keep = 9), notes(4, 3))
        check(moon.copy(pins = 3), Input.PinsAre(2, keep = 1), moon.copy(pins = 2))
    }

    @Test fun `what the shell tells of a new list - where his note is, by its own name`() {
        val a = 10L; val b = 9L; val c = 8L; val p = 7L
        // Not on the notes: only a count, and only when it changed.
        assertEquals(Input.PinsAre(4), RearNav.pinsAre(moon.copy(pins = 3), null, listOf(a, b, c, p), fresh = false))
        assertEquals(null, RearNav.pinsAre(moon.copy(pins = 3), null, listOf(a, b, c), fresh = false))
        // On b: wherever b is now.
        assertEquals(Input.PinsAre(4, keep = 1), RearNav.pinsAre(notes(3, 1), b, listOf(a, b, c, p), fresh = false))
        assertEquals(Input.PinsAre(3, keep = 0), RearNav.pinsAre(notes(3, 1), b, listOf(b, c, p), fresh = false))
        assertEquals(null, RearNav.pinsAre(notes(3, 1), b, listOf(a, b, p), fresh = false))
        // His own note gone: the count's rule.
        assertEquals(Input.PinsAre(2, keep = -1), RearNav.pinsAre(notes(3, 1), b, listOf(a, c), fresh = false))
        // A note just pinned in front: on the first he stays on the first (the new one), further back on his own.
        assertEquals(Input.PinsAre(4, keep = -1), RearNav.pinsAre(notes(3, 0), a, listOf(p, a, b, c), fresh = true))
        assertEquals(Input.PinsAre(4, keep = 2), RearNav.pinsAre(notes(3, 1), b, listOf(p, a, b, c), fresh = true))
    }

    @Test fun `the note shown is always one that is there, and the newest whenever the notes are not up - in every state that can be reached`() {
        for (s in reachable) {
            assertTrue("$s", s.pins >= 0 && s.pin >= 0 && s.pin <= maxOf(s.pins - 1, 0))
            if (!s.notes) assertEquals("$s", 0, s.pin)
        }
        assertTrue("notes on an older note are reached", reachable.any { it.notes && it.pin == 2 })
    }


    private fun arranging(pins: Int, pin: Int = 0, under: State = moon) = notes(pins, pin, under).copy(arrange = true)

    @Test fun `held on the notes with two or more, the note shown is lifted to be put in order - with one it gives, with none nothing`() {
        for (n in 2..3) for (pin in 0 until n) check(notes(n, pin), Input.NoteHold, arranging(n, pin), Anim.ARRANGE_IN)
        check(notes(1), Input.NoteHold, notes(1), does = Does.GIVE)
        check(notes(0), Input.NoteHold, notes(0))
        // Over whatever page lies under the notes.
        for (under in listOf(live(Live.TIMER), away(Live.MUSIC), live(Live.CHARGE))) check(notes(3, 1, under), Input.NoteHold, arranging(3, 1, under), Anim.ARRANGE_IN)
        // Held again while arranging: nothing more.
        check(arranging(3, 1), Input.NoteHold, arranging(3, 1))
        // Not on the notes: the moon's and the pages' hold are Input.Hold, as they were; this input means nothing there.
        for (s in listOf(moon.copy(pins = 3), live(Live.TIMER).copy(pins = 3), usage().copy(pins = 3), moon.copy(pins = 3, pop = true), State(pins = 3))) check(s, Input.NoteHold, s)
        check(notes(3).copy(pop = true), Input.NoteHold, notes(3).copy(pop = true))
        check(moon.copy(pins = 3), Input.Hold, moon.copy(pins = 3, picker = true), Anim.PICKER_IN)
        check(moon.copy(pins = 3), Input.DoubleTapHold, moon.copy(pins = 3), does = Does.BACK_OFF)
        check(notes(3), Input.Hold, notes(3))
    }

    @Test fun `carried along, the held note is where it was taken, one place at a time or any - and only while arranging`() {
        check(arranging(3, 0), Input.NoteCarried(1), arranging(3, 1), Anim.NOTE_CARRIED)
        check(arranging(3, 1), Input.NoteCarried(2), arranging(3, 2), Anim.NOTE_CARRIED)
        check(arranging(3, 2), Input.NoteCarried(1), arranging(3, 1), Anim.NOTE_CARRIED)
        check(arranging(3, 1), Input.NoteCarried(1), arranging(3, 1))
        check(arranging(3, 1), Input.NoteCarried(3), arranging(3, 1))
        check(arranging(3, 1), Input.NoteCarried(-1), arranging(3, 1))
        // Twelve notes, carried from the first to the last and back.
        var s = arranging(12, 0)
        for (to in 1..11) s = RearNav.next(s, Input.NoteCarried(to)).also { assertEquals(Anim.NOTE_CARRIED, it.anim) }.state
        assertEquals(arranging(12, 11), s)
        for (to in 10 downTo 0) s = s.go(Input.NoteCarried(to))
        assertEquals(arranging(12, 0), s)
        // Not arranging: nothing.
        for (st in listOf(notes(3, 1), moon.copy(pins = 3))) { check(st, Input.NoteCarried(2), st); check(st, Input.NoteSwapped(2), st) }
    }

    @Test fun `a tap on another swaps them - the held note is then at its place - and one on it or the ground ends it, the order kept`() {
        check(arranging(3, 0), Input.NoteSwapped(2), arranging(3, 2), Anim.NOTE_SWAP)
        check(arranging(3, 2), Input.NoteSwapped(1), arranging(3, 1), Anim.NOTE_SWAP)
        check(arranging(3, 1), Input.NoteSwapped(1), arranging(3, 1))
        check(arranging(3, 1), Input.NoteSwapped(5), arranging(3, 1))
        // Done: the notes page again, on the held note where it now is.
        check(arranging(3, 2), Input.ArrangeDone, notes(3, 2), Anim.ARRANGE_OUT)
        check(notes(3, 2), Input.ArrangeDone, notes(3, 2))
        // The tap itself is still only a tap (its place is the shell's to read): it changes nothing in the table.
        check(arranging(3, 1), Input.Tap, arranging(3, 1), does = Does.TAP)
        check(arranging(3, 1), Input.DoubleTap, arranging(3, 1), does = Does.DIM)
    }

    @Test fun `while arranging, sideways is the carousel's own and up nothing - down leaves the notes as ever`() {
        for (from in From.values()) {
            if (from == From.TOP_EDGE) continue
            check(arranging(3, 1), stroke(L, from), arranging(3, 1))
            check(arranging(3, 1), stroke(R, from), arranging(3, 1))
            check(arranging(3, 1), stroke(U, from), arranging(3, 1))
            check(arranging(3, 1), stroke(D, from), moon.copy(pins = 3), Anim.NOTES_OUT)
        }
        check(arranging(3, 1, live(Live.TIMER)), stroke(D), live(Live.TIMER).copy(pins = 3), Anim.NOTES_OUT)
        // The notifications' pull is over everything of ours, arranging too.
        check(arranging(3, 1), stroke(D, From.TOP_EDGE), notes(3, 1).copy(shade = true), Anim.SHADE_IN)
        check(arranging(3, 1), stroke(L, sliding = true), arranging(3, 1))
    }

    @Test fun `arranging ends with the back dark, a pop-up, the shade, an urgent live thing, another's app, a note pinned or taken away`() {
        check(arranging(3, 2), Input.Dark, moon.copy(lit = false, pins = 3), Anim.CUT)
        check(arranging(3, 2), Input.PopArrives(), notes(3, 2).copy(pop = true), Anim.POP_IN)
        check(arranging(3, 2), Input.OthersTake, moon.copy(ours = false, pins = 3), Anim.CUT)
        check(arranging(3, 2), Input.NotesAlone, moon.copy(pins = 3), Anim.NOTES_OUT)
        check(arranging(3, 2), Input.LiveComes(Live.CALL, urgent = true), live(Live.CALL).copy(pins = 3), Anim.NOTES_OUT)
        // Something live that is not urgent stays under the notes, and so does the arranging.
        check(arranging(3, 2), Input.LiveComes(Live.MUSIC), arranging(3, 2, live(Live.MUSIC)))
        // A note pinned (the beam's landing shows the newest), the list grown or shrunk, one binned on the phone.
        check(arranging(3, 2), Input.NotePinned, notes(3, 0), Anim.ARRANGE_OUT)
        check(arranging(3, 2), Input.PinsAre(4), notes(4, 3), Anim.ARRANGE_OUT)
        check(arranging(3, 2), Input.PinsAre(3), arranging(3, 2))
        check(arranging(3, 2), Input.PinsAre(1), notes(1, 0), Anim.ARRANGE_OUT)
        check(arranging(3, 2), Input.PinRemoved(0), notes(2, 1), Anim.ARRANGE_OUT)
        // And back again it is the notes page, the held note where he left it, not arranging.
        assertEquals(notes(3, 0), arranging(3, 1).go(Input.NoteCarried(2), Input.PopArrives(), Input.PopEnds, stroke(D), stroke(U)))
        assertEquals(notes(3, 2), arranging(3, 1).go(Input.NoteCarried(2), Input.PopArrives(), Input.PopEnds))
    }

    @Test fun `an arrival at a place ends arranging, and a hold under a standing arrival lifts nothing`() {
        val s = arranging(3, 1).go(Input.ArrivalsAre(1))
        assertFalse("$s", s.arrange)
        assertFalse("$s", s.notes)
        assertEquals(Seen.ARRIVAL, RearNav.seen(s))
        assertFalse(s.go(Input.NoteHold).arrange)
        // Put away ("not now"), the notes are one stroke up again and can be put in order as before.
        assertTrue(s.go(stroke(D), stroke(U), Input.NoteHold).arrange)
    }

    @Test fun `arranging never changes which page shows, and is only ever on the notes page with two or more - in every state that can be reached`() {
        assertTrue("arranging is reached", reachable.any { it.arrange && it.pin == 2 })
        for (s in reachable) if (s.arrange) {
            assertEquals("$s", Seen.NOTES, RearNav.seen(s))
            assertTrue("$s", s.pins >= 2)
        }
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
        check(moon, Input.Hold, moon.copy(picker = true), Anim.PICKER_IN)
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
            // A hold on a live page is nothing (it was "the next face", which with one face did nothing anyway).
            check(live(k), Input.Hold, live(k))
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

    @Test fun `the pull with nothing waiting is the shade's own state, and gives back what was under it`() {
        for (under in listOf(moon, live(Live.TIMER), live(Live.MUSIC), usage(Live.TIMER), usage(), moon.copy(notes = true), away(Live.TIMER), live(Live.CHARGE))) {
            val rim = under.copy(shade = true)
            check(under, stroke(D, From.TOP_EDGE), rim, Anim.SHADE_IN)
            for (from in From.values()) for (way in Way.values()) check(rim, stroke(way, from), rim, does = Does.SHADE_OWN)
            for (i in listOf(Input.Tap, Input.DoubleTap, Input.Hold)) assertEquals(Does.SHADE_OWN, RearNav.next(rim, i).does)
            check(rim, Input.ShadeShut, under, Anim.SHADE_OUT)
            assertEquals(under, under.go(stroke(D, From.TOP_EDGE), Input.ShadeShut))
            // Taken away at once.
            assertFalse(RearNav.next(rim, Input.Dark).state.shade)
            assertFalse(RearNav.next(rim, Input.OthersTake).state.shade)
            assertEquals(Seen.LIVE, RearNav.seen(RearNav.next(rim, Input.LiveComes(Live.CALL, urgent = true)).state))
        }
        // A pop-up up as he pulls is gone with the pull; one that comes meanwhile lies over the light for its time.
        check(moon.copy(pop = true), stroke(D, From.TOP_EDGE), moon.copy(shade = true), Anim.SHADE_IN)
        check(moon.copy(shade = true), Input.PopArrives(), moon.copy(shade = true, pop = true), Anim.POP_IN)
        // On a dark back or another's there is no pull: nothing plays.
        check(State(), stroke(D, From.TOP_EDGE), State())
        check(moon.copy(ours = false), stroke(D, From.TOP_EDGE), moon.copy(ours = false))
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
            // A pop-up too: such a stroke was not meant for it, and it stays, its notification untouched.
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
        assertEquals(a, a.go(Input.PopArrives(), Input.PopEnds))
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

    @Test fun `navigation sits below calls and above timer music and charging`() {
        val lower = live(Live.CHARGE, Live.MUSIC, Live.TIMER)
        val route = live(Live.CHARGE, Live.MUSIC, Live.TIMER, Live.NAVIGATION)
        check(lower, Input.LiveComes(Live.NAVIGATION), route, Anim.LIVE_SWAP)
        check(route, Input.LiveComes(Live.CALL, urgent = true), route.copy(lives = route.lives or bits(Live.CALL)), Anim.LIVE_SWAP)
        check(route.copy(lives = route.lives or bits(Live.CALL)), Input.LiveEnds(Live.CALL), route, Anim.LIVE_SWAP)
        check(route, Input.LiveEnds(Live.NAVIGATION), lower, Anim.LIVE_SWAP)
        assertEquals(Live.NAVIGATION, route.live)
        assertEquals(listOf(Live.CHARGE, Live.MUSIC, Live.TIMER, Live.NAVIGATION, Live.CALL), Live.values().toList())
    }

    @Test fun `navigation updates never bring back a sent away route and taps only brighten`() {
        val sent = away(Live.NAVIGATION, Live.TIMER)
        check(sent, Input.LiveUpdates(Live.NAVIGATION), sent)
        check(sent, Input.LiveEnds(Live.NAVIGATION), away(Live.TIMER))
        check(sent, stroke(L), live(Live.NAVIGATION, Live.TIMER), Anim.LIVE_IN)
        val route = live(Live.NAVIGATION)
        check(route, Input.Tap, route, does = Does.TAP)
        check(usage(Live.NAVIGATION), stroke(R), route, Anim.USAGE_OUT)
        check(route.copy(notes = true), stroke(D), route, Anim.NOTES_OUT)
        check(route, Input.Dark, route.copy(lit = false), Anim.CUT)
        check(route.copy(lit = false), Input.Lit, route, Anim.CUT)
        check(route, Input.OthersTake, route.copy(ours = false), Anim.CUT)
    }

    @Test fun `a new navigation route stays under usage notes and the notification shade`() {
        check(usage(), Input.LiveComes(Live.NAVIGATION), usage(Live.NAVIGATION))
        check(moon.copy(notes = true), Input.LiveComes(Live.NAVIGATION), live(Live.NAVIGATION).copy(notes = true))
        check(moon.copy(shade = true), Input.LiveComes(Live.NAVIGATION), live(Live.NAVIGATION).copy(shade = true))
    }

    @Test fun `on the route's page the row's strokes are as on any live page, and a double tap held is the back off`() {
        val route = live(Live.NAVIGATION)
        check(moon, Input.LiveComes(Live.NAVIGATION), route, Anim.LIVE_IN)
        check(route, stroke(L), usage(Live.NAVIGATION), Anim.USAGE_IN)
        check(route, stroke(R), away(Live.NAVIGATION), Anim.LIVE_OUT)
        check(away(Live.NAVIGATION), stroke(L), route, Anim.LIVE_IN)
        check(route, stroke(U), route.copy(notes = true), Anim.NOTES_IN)
        check(route, stroke(D), route)
        check(route, Input.DoubleTap, route, does = Does.DIM)
        check(route, Input.DoubleTapHold, route, does = Does.BACK_OFF)
        // Music he had sent away stays away under a route that ends.
        val musicAway = away(Live.MUSIC)
        val both = musicAway.go(Input.LiveComes(Live.NAVIGATION))
        assertEquals(live(Live.MUSIC, Live.NAVIGATION).copy(sent = bits(Live.MUSIC)), both)
        assertEquals(musicAway, both.go(Input.LiveEnds(Live.NAVIGATION)))
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
            check(s, Input.PopArrives(), s.copy(pop = true), Anim.POP_IN)
            check(s.copy(pop = true), Input.PopEnds, s, Anim.POP_OUT)
            assertEquals(Seen.POP, RearNav.seen(s.copy(pop = true)))
        }
        // IT HAS THE WHOLE BACK (the review's 3 and 38): a stroke takes it away and does nothing to the page under it,
        // on the moon too (no Camera opened unseen); a tap presses no button under it.
        // Up and down it is taken away; sideways it is thrown out (dismissed or snoozed, NoticeThrow): the page under is the same.
        for (way in Way.values()) {
            val anim = when (way) { L -> Anim.POP_THROWN_LEFT; R -> Anim.POP_THROWN_RIGHT; else -> Anim.POP_OUT }
            val does = if (way == L || way == R) thrownDoes(way) else Does.NOTHING
            check(live(Live.TIMER).copy(pop = true), stroke(way), live(Live.TIMER), anim, does)
            check(moon.copy(pop = true), stroke(way), moon, anim, does)
            check(usage(Live.CALL).copy(pop = true), stroke(way), usage(Live.CALL), anim, does)
        }
        check(live(Live.CALL).copy(pop = true), Input.Tap, live(Live.CALL).copy(pop = true), does = Does.BRIGHT)
        check(live(Live.CALL).copy(pop = true), Input.Hold, live(Live.CALL).copy(pop = true))
        check(live(Live.CALL).copy(pop = true), Input.DoubleTapHold, live(Live.CALL).copy(pop = true))
        // Pulling the notifications down takes it away.
        check(moon.copy(pop = true), stroke(D, From.TOP_EDGE), moon.copy(shade = true), Anim.SHADE_IN)
        check(State(), Input.PopArrives(), State())
    }


    @Test fun `the way is one constant - right to left dismisses, left to right snoozes`() {
        // The default asked of him (his to flip): the finger moving right to left, the page flying off to the left.
        assertEquals(Way.LEFT, RearNav.DISMISS_WAY)
        assertEquals(Does.DISMISS, RearNav.next(moon.copy(pop = true), stroke(L)).does)
        assertEquals(Does.SNOOZE, RearNav.next(moon.copy(pop = true), stroke(R)).does)
    }

    @Test fun `a pop-up thrown sideways, from anywhere on the back, on every page - its notification dismissed or snoozed, the page under as it was`() {
        var n = 0
        for (s in reachable.filter { it.pop && !it.popFixed && it.lit && it.ours && !it.shade }) for (from in From.values()) for (way in listOf(L, R)) {
            val step = RearNav.next(s, stroke(way, from))
            assertEquals("$s $way $from", RearNav.Step(s.copy(pop = false), if (way == L) Anim.POP_THROWN_LEFT else Anim.POP_THROWN_RIGHT, thrownDoes(way)), step)
            // Landed while a page still slid, or on its way before this pop-up came: not meant for it, nothing at all.
            assertEquals("$s $way $from sliding", RearNav.Step(s), RearNav.next(s, stroke(way, from, sliding = true)))
            assertEquals("$s $way $from not aimed", RearNav.Step(s), RearNav.next(s, stroke(way, from, aimed = false)))
            n++
        }
        assertTrue("pop-ups that may be thrown are reached: $n", n > 100)
    }

    @Test fun `a stroke whose finger was on its way before the pop-up came does nothing to it - no dismiss, no snooze, nothing under it`() {
        // The skeptic's timeline: his finger lands on the moon going right to left towards usage, a WhatsApp pop-up
        // comes 100 ms later, he lifts 200 ms after that. Before: thrown out, dismissed on the phone, unseen.
        val came = moon.go(Input.PopArrives())
        check(came, stroke(L, aimed = false), came)
        check(came, stroke(R, aimed = false), came)
        var n = 0
        // (Under the pulled-down pile its strokes are the pile's own: SHADE_OWN, as ever.)
        for (s in reachable.filter { it.pop && !it.shade }) for (way in Way.values()) for (sliding in listOf(false, true)) {
            val step = RearNav.next(s, stroke(way, sliding = sliding, aimed = false))
            assertEquals("$s $way", RearNav.Step(s), step)
            n++
        }
        assertTrue("pop-ups reached: $n", n > 100)
        // With no pop-up up "aimed" means nothing: the stroke is what it always was.
        for (s in reachable.filter { !it.pop }) for (way in Way.values()) for (from in From.values()) for (sliding in listOf(false, true)) {
            assertEquals("$s $way $from", RearNav.next(s, stroke(way, from, sliding)), RearNav.next(s, stroke(way, from, sliding, aimed = false)))
        }
        // The pull from the top edge is the notifications' own as ever (the pop-up goes, nothing is thrown).
        check(came, stroke(D, From.TOP_EDGE, aimed = false), moon.copy(shade = true), Anim.SHADE_IN)
        // And the table has such strokes among its inputs (the whole table and every sequence of three walk them).
        for (way in Way.values()) assertTrue(stroke(way, aimed = false) in RearNav.INPUTS)
    }

    @Test fun `a pop-up whose notification may not be thrown out gives and stays, and still goes up and down`() {
        var n = 0
        for (s in reachable.filter { it.pop && it.popFixed && it.lit && it.ours && !it.shade }) {
            for (way in listOf(L, R)) assertEquals("$s $way", RearNav.Step(s, Anim.POP_GIVES), RearNav.next(s, stroke(way)))
            assertEquals(RearNav.Step(s.copy(pop = false, popFixed = false), Anim.POP_OUT), RearNav.next(s, stroke(U)))
            assertEquals(RearNav.Step(s.copy(pop = false, popFixed = false), Anim.POP_OUT), RearNav.next(s, stroke(D)))
            assertEquals(s.copy(pop = false, popFixed = false), RearNav.next(s, Input.PopEnds).state)
            n++
        }
        assertTrue("fixed pop-ups are reached: $n", n > 10)
        // It comes as such, and the next pop-up over it says for itself.
        check(moon, Input.PopArrives(fixed = true), moon.copy(pop = true, popFixed = true), Anim.POP_IN)
        check(moon.copy(pop = true, popFixed = true), Input.PopArrives(), moon.copy(pop = true))
        check(moon.copy(pop = true), Input.PopArrives(fixed = true), moon.copy(pop = true, popFixed = true))
        // Dark, it is gone and says nothing any more.
        check(moon.copy(pop = true, popFixed = true), Input.Dark, State(), Anim.CUT)
        // The notifications pulled down over it: gone with it.
        check(moon.copy(pop = true, popFixed = true), stroke(D, From.TOP_EDGE), moon.copy(shade = true), Anim.SHADE_IN)
    }

    @Test fun `on the pulled-down pile sideways is the pile's own, whichever way - it throws its page out itself`() {
        for (s in reachable.filter { it.shade && it.lit && it.ours }) for (way in listOf(L, R)) for (from in From.values()) {
            assertEquals("$s $way", RearNav.Step(s, does = Does.SHADE_OWN), RearNav.next(s, stroke(way, from)))
        }
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


    private val picker = moon.copy(picker = true)

    @Test fun `a hold on the face opens the picker, and only there`() {
        check(moon, Input.Hold, picker, Anim.PICKER_IN)
        assertEquals(Seen.PICKER, RearNav.seen(picker))
        // On the face with a live page sent away: the face is what shows.
        check(away(Live.TIMER), Input.Hold, away(Live.TIMER).copy(picker = true), Anim.PICKER_IN)
        // Nowhere else.
        for (s in listOf(live(Live.TIMER), live(Live.MUSIC), live(Live.CALL), live(Live.CHARGE), usage(), moon.copy(notes = true), moon.copy(pop = true))) check(s, Input.Hold, s)
        check(moon.copy(shade = true), Input.Hold, moon.copy(shade = true), does = Does.SHADE_OWN)
        check(State(), Input.Hold, State())
        check(moon.copy(ours = false), Input.Hold, moon.copy(ours = false))
    }

    @Test fun `the picker takes every stroke and tap itself, from every edge, sliding or not`() {
        for (sliding in listOf(false, true)) for (from in From.values()) for (way in Way.values()) check(picker, stroke(way, from, sliding), picker, does = Does.PICKER_OWN)
        check(picker, Input.Tap, picker, does = Does.PICKER_OWN)
        check(picker, Input.DoubleTap, picker)
        check(picker, Input.Hold, picker)
        check(picker, Input.DoubleTapHold, picker)
    }

    @Test fun `it shuts by itself, chosen or not, and the face is home again`() {
        check(picker, Input.PickerShut(true), moon, Anim.PICKER_OUT)
        check(picker, Input.PickerShut(false), moon, Anim.PICKER_OUT)
        // Not up: nothing.
        for (s in listOf(moon, live(Live.TIMER), usage(), moon.copy(notes = true))) for (c in listOf(true, false)) check(s, Input.PickerShut(c), s)
    }

    @Test fun `a shut that comes after a pop-up arrived still takes the picker down, and the face is touched again after it`() {
        // The shell posts the shut; a pop-up's arrival is handled first. The shut must not be lost under it.
        for (c in listOf(true, false)) check(picker.copy(pop = true), Input.PickerShut(c), moon.copy(pop = true), Anim.PICKER_OUT)
        // The race as a sequence: picker, pop-up, the shut, the pop-up's end: the face, its hold opening the picker again.
        var s = RearNav.next(picker, Input.PopArrives()).state
        s = RearNav.next(s, Input.PickerShut(false)).state
        s = RearNav.next(s, Input.PopEnds).state
        assertEquals(moon, s)
        assertEquals(Seen.MOON, RearNav.seen(s))
        check(s, Input.Hold, picker, Anim.PICKER_IN)
        // The fallback said by the shell when the exit has played out (onGone) is accepted under the pop-up too.
        check(picker.copy(pop = true), Input.PickerShut(false), moon.copy(pop = true), Anim.PICKER_OUT)
    }

    @Test fun `what takes the back drops the picker at once`() {
        check(picker, Input.Dark, State(), Anim.CUT)
        check(picker, Input.OthersTake, moon.copy(ours = false), Anim.CUT)
        check(picker, Input.NotePinned, moon.copy(notes = true), Anim.NOTES_IN)
        // Something live on top: its page, urgent or not.
        check(picker, Input.LiveComes(Live.TIMER), live(Live.TIMER), Anim.LIVE_IN)
        check(picker, Input.LiveComes(Live.CALL, urgent = true), live(Live.CALL), Anim.LIVE_IN)
        val a = away(Live.CALL).copy(picker = true)
        check(a, Input.LiveComes(Live.MUSIC), away(Live.CALL, Live.MUSIC).copy(picker = true))
        check(a, Input.LiveUpdates(Live.CALL), a)
        check(a, Input.LiveEnds(Live.CALL), picker)
        // Data only: it stays.
        for (i in listOf(Input.Lit, Input.OthersLeave, Input.UsageAlone, Input.NotesAlone, Input.ShadeShut, Input.PinsAre(2), Input.PinRemoved(0))) assertTrue("$i", RearNav.next(picker, i).state.picker)
    }

    @Test fun `a pop-up over the picker, and the picker again under it`() {
        check(picker, Input.PopArrives(), picker.copy(pop = true), Anim.POP_IN)
        assertEquals(Seen.POP, RearNav.seen(picker.copy(pop = true)))
        // A stroke on it is the pop-up's as on any page: up and down (from the top edge too) take it away, sideways
        // throw it out (dismissed one way, snoozed the other), one that may not be thrown gives; the picker is under it.
        for (from in From.values()) {
            check(picker.copy(pop = true), stroke(Way.UP, from), picker, Anim.POP_OUT)
            check(picker.copy(pop = true), stroke(Way.DOWN, from), picker, Anim.POP_OUT)
            check(picker.copy(pop = true), stroke(Way.LEFT, from), picker, Anim.POP_THROWN_LEFT,
                if (RearNav.DISMISS_WAY == Way.LEFT) Does.DISMISS else Does.SNOOZE)
            check(picker.copy(pop = true), stroke(Way.RIGHT, from), picker, Anim.POP_THROWN_RIGHT,
                if (RearNav.DISMISS_WAY == Way.RIGHT) Does.DISMISS else Does.SNOOZE)
            for (way in listOf(Way.LEFT, Way.RIGHT)) check(picker.copy(pop = true, popFixed = true), stroke(way, from), picker.copy(pop = true, popFixed = true), Anim.POP_GIVES)
        }
        // A stroke not meant for it (on its way before it came, or while a page slid) does nothing, to it or the picker.
        for (way in Way.values()) {
            check(picker.copy(pop = true), Input.Stroke(way, aimed = false), picker.copy(pop = true))
            check(picker.copy(pop = true), stroke(way, From.MIDDLE, true), picker.copy(pop = true))
        }
        check(picker.copy(pop = true), Input.PopEnds, picker, Anim.POP_OUT)
        check(picker.copy(pop = true), Input.Tap, picker.copy(pop = true), does = Does.BRIGHT)
        check(picker.copy(pop = true), Input.Dark, State(), Anim.CUT)
    }

    @Test fun `the picker is reached, and nothing but the face carries it`() {
        assertTrue(reachable.any { it.picker })
        assertTrue(reachable.any { it.picker && it.pop })
        assertTrue(reachable.any { it.picker && it.lives != 0 })
        for (s in reachable.filter { it.picker }) assertTrue("$s", s.lit && s.ours && s.row == Row.HOME && !s.notes && !s.shade && RearNav.seen(s) in setOf(Seen.PICKER, Seen.POP))
    }

    // -- the whole table: every state, every input, every sequence of three --------------------------------------------

    private fun holds(s: State): String? = when {
        s.sent and s.lives.inv() != 0 -> "something sent away that is not live"
        s.row == Row.LIVE && !s.liveShown -> "the live page with nothing to show"
        s.row == Row.HOME && s.liveShown -> "the moon while a live page is in the row and not sent away"
        s.row == Row.USAGE && s.notes -> "usage under the notes"
        !s.lit && (s.notes || s.shade || s.pop || s.row == Row.USAGE) -> "something up on a dark back"
        s.popFixed && !s.pop -> "a pop-up's own say with no pop-up"
        !s.ours && (s.notes || s.shade || s.row == Row.USAGE) -> "a page of ours on another's back"
        s.picker && !(s.lit && s.ours && s.row == Row.HOME && !s.notes && !s.shade && s.arrivals == 0) -> "the picker off the face"
        s.arrange && !(s.notes && s.pins >= 2 && s.lit && s.ours && !s.pop && !s.shade && !s.arrivalUp) -> "arranging off the notes page, or with nothing to put in order"
        else -> null
    }

    @Test fun `every state x every input gives one sound state, and nothing ever needs mending`() {
        // 1188 before the notes had a count and a place among them (none, one, two, three pinned; the first, second or third shown): 5724 with.
        // About 59 500 with navigation, a fifth kind of live thing (live or not, sent away or not, for each kind).
        assertTrue("states: ${reachable.size}", reachable.size in 100..120_000)
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
                // The same give on a standing arrival (place/Arrivals) at its first and last note.
                val arrivalGive = raw.anim == Anim.ARRIVAL_END_OLDER || raw.anim == Anim.ARRIVAL_END_NEWER
                // And a pop-up whose notification may not be thrown out, giving under a sideways stroke.
                val popGives = raw.anim == Anim.POP_GIVES
                if (raw.state == s && !give && !arrivalGive && !popGives) assertEquals("$s + $i", Anim.NONE, raw.anim)
                if (give) assertTrue("$s + $i: a give is only a sideways stroke on the notes, and changes nothing", raw.state == s && s.notes && i is Input.Stroke && (i.way == Way.LEFT || i.way == Way.RIGHT))
                if (arrivalGive) assertTrue("$s + $i: an arrival's give is only a sideways stroke on it, and changes nothing", raw.state == s && RearNav.seen(s) == Seen.ARRIVAL && i is Input.Stroke && (i.way == Way.LEFT || i.way == Way.RIGHT))
                if (popGives) assertTrue("$s + $i: a pop-up gives only for a sideways stroke, when it may not be thrown, and nothing changes", raw.state == s && s.pop && s.popFixed && i is Input.Stroke && (i.way == Way.LEFT || i.way == Way.RIGHT) && raw.does == Does.NOTHING)
                // Its notification is dismissed or snoozed only by a sideways stroke on a pop-up that may be thrown, which takes it away.
                if (raw.does == Does.DISMISS || raw.does == Does.SNOOZE) assertTrue("$s + $i: thrown out", s.pop && !s.popFixed && !raw.state.pop && i is Input.Stroke && i.aimed && !i.sliding && raw.does == thrownDoes(i.way))
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
        // (A call comes over a standing arrival: it slides in over it, the one live page that may; and over the picker.)
        if (anim == Anim.LIVE_IN && !(sa == Seen.MOON || sa == Seen.POP || sa == Seen.PICKER || sa == Seen.ARRIVAL && b.live == Live.CALL)) return "the live page slid in from under something"
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
        // The one exception: a hold on the face opens the picker, and nothing else changes with it.
        if (i == Input.Hold && sa == Seen.MOON && b == a.copy(picker = true)) return null
        if ((i == Input.Tap || i == Input.DoubleTap || i == Input.Hold || i == Input.DoubleTapHold || i is Input.LiveUpdates) && b != a) return "it changed the page"
        // The picker is only ever on the face, and only its own end or something that takes the back shuts it.
        if (b.picker && !(b.lit && b.ours && b.row == Row.HOME && !b.notes && !b.shade && b.arrivals == 0)) return "the picker somewhere else than on the face"
        if (a.picker && !b.picker && !(i is Input.PickerShut || i == Input.Dark || i == Input.OthersTake || i is Input.LiveComes || i == Input.NotePinned || i is Input.ArrivalsAre)) return "the picker shut by $i"
        // Putting the notes in order is the notes page's own: it changes the note held and nothing else.
        if (i == Input.NoteHold || i == Input.ArrangeDone || i is Input.NoteCarried || i is Input.NoteSwapped) {
            if (sb != sa) return "arranging changed which page shows"
            if (b.copy(pin = a.pin, arrange = a.arrange) != a) return "arranging changed more than the note held"
        }
        if (b.arrange && !a.arrange && i != Input.NoteHold) return "arranging began by itself"
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
                        // (Not over a standing arrival under a call: there the stroke left is nothing, RearNavArrivalTest.)
                        if (RearNav.seen(s1) == Seen.LIVE && s1.arrivals == 0 && k3 != k1) error("${reachable[k]} + ${inputs[a]}, then left and right from the live page, is ${reachable[k3]}")
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
