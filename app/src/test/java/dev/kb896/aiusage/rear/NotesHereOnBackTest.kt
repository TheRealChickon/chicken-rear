package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.data.PinOrder
import dev.kb896.aiusage.place.Here
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE BACK'S NOTES PAGE WITH A NOTE FOR A PLACE HE IS NOT AT AMONG THE OTHERS: the shell lists Here.onBack of the
 * payload's notes and counts, pages, bins and orders those; every one keeps its own name (Pin.at), so the bin takes
 * the note it shows and never the hidden one, and an order told to the app leaves the hidden note where it was.
 * The shell itself (RearUserService) is not a unit; its steps are played here as it takes them.
 */
class NotesHereOnBackTest {
    // Made-up notes: the newest first, as the app keeps them; the gym note is for a place he is not at.
    private val a = Pin("plain newest", 6)
    private val gym = Pin("towels", 5, 10.0, 20.0, radius = 150, here = false)
    private val b = Pin("plain", 4)
    private val shop = Pin("milk", 3, 10.5, 20.5, radius = 300, here = true)
    private val full = listOf(a, gym, b, shop)

    private val moon = RearNav.State(lit = true)

    @Test fun `the hidden note is not listed, not counted, and never shown`() {
        val shown = Here.onBack(full)
        assertEquals(listOf(a, b, shop), shown)
        // The table is told how many there are (PinsAre): three, not four.
        var s = RearNav.next(moon, RearNav.Input.PinsAre(shown.size)).state
        s = RearNav.next(s, RearNav.Input.Stroke(RearNav.Way.UP, RearNav.From.MIDDLE)).state
        assertTrue(s.notes); assertEquals(3, s.pins)
        // Along all of them, to the oldest and past its end: the gym note never comes.
        val seen = ArrayList<Pin>()
        repeat(5) { seen += shown[s.pin]; s = RearNav.next(s, RearNav.Input.Stroke(RearNav.Way.LEFT, RearNav.From.MIDDLE)).state }
        assertTrue(gym !in seen)
        assertEquals(setOf(a, b, shop), seen.toSet())
    }

    @Test fun `a bin on the back removes the note it shows, by its own name, and the hidden note stays`() {
        var shown = Here.onBack(full)
        var s = moon.copy(notes = true, pins = shown.size, pin = 0)
        s = RearNav.next(s, RearNav.Input.Stroke(RearNav.Way.LEFT, RearNav.From.MIDDLE)).state
        assertEquals(1, s.pin)
        // The shell's bin: the note at the page's place in ITS list, taken off there and told to the app by its time.
        val gone = shown[s.pin]
        assertEquals(b, gone)
        shown = shown.filter { it.at != gone.at }
        s = RearNav.next(s, RearNav.Input.PinRemoved(1)).state
        assertEquals(shop, shown[s.pin])
        // The app (Graph.onPinRemoved) takes it off its whole list by that time: the gym note is still there.
        val app = full.filter { it.at != gone.at }
        assertEquals(listOf(a, gym, shop), app)
        // Its next payload lists what the back has already.
        assertEquals(shown, Here.onBack(app))
        assertEquals(shown.size, s.pins)
    }

    @Test fun `an order made on the back keeps the hidden note in its place on the app's list`() {
        val shown = Here.onBack(full)
        // Arranged on the back: the oldest to the front (NoteCarousel; told to the app as the listed notes' times).
        val arranged = PinOrder.move(shown, 2, 0)
        assertEquals(listOf(shop, a, b), arranged)
        val told = arranged.map { it.at }
        // The app puts it on its whole list (Graph.onPinsOrdered): the gym note keeps its slot.
        val app = PinOrder.applyPins(full, told)
        assertEquals(listOf(shop, gym, a, b), app)
        assertEquals(arranged, Here.onBack(app))
        // The shell's keptOrder sees the app's payload agree with what it told (and forgets it), and a payload from
        // before it is put in that order meanwhile, the hidden note in its place there too.
        assertTrue(PinOrder.agrees(app, told) { it.at })
        assertEquals(app, PinOrder.applyPins(full, told))
        // The carousel's order over a payload (NoteCarousel.ordered) and the filter after it: the same notes listed.
        assertEquals(arranged, Here.onBack(PinOrder.applyPins(full, told)))
    }

    @Test fun `he reaches the gym - the note comes onto the page, and leaves it again as he goes`() {
        val atGym = Here.marked(full, setOf(Keys.gym, Keys.shop))
        assertEquals(listOf("plain newest", "towels", "plain", "milk"), Here.onBack(atGym).map { it.text })
        val home = Here.marked(full, emptySet())
        assertEquals(listOf("plain newest", "plain"), Here.onBack(home).map { it.text })
    }

    /** The shell's step for a new payload while the notes page is at [s]: what it lists, and the table after (listPins). */
    private fun payload(s: RearNav.State, was: List<Pin>, full: List<Pin>, fresh: Boolean = false): Pair<List<Pin>, RearNav.State> {
        val next = Here.onBack(full)
        val shown = if (s.notes) was.getOrNull(s.pin)?.at else null
        val told = RearNav.pinsAre(s, shown, next.map { it.at }, fresh)
        return next to (told?.let { RearNav.next(s, it).state } ?: s)
    }

    @Test fun `he reaches a place while reading another note - he stays on the note he was reading`() {
        // Made-up: three plain notes and one for a place at the end of his order, not here yet. He reads the second.
        val c = Pin("plain oldest", 2)
        val p = Pin("towels", 1, 10.0, 20.0, radius = 150, here = false)
        val was = Here.onBack(listOf(a, b, c, p))
        assertEquals(listOf(a, b, c), was)
        val s = moon.copy(notes = true, pins = 3, pin = 1)
        // ENTER: the place's note comes onto the page behind him.
        val (now, after) = payload(s, was, listOf(a, b, c, p.copy(here = true)))
        assertEquals(4, after.pins)
        assertEquals(b, now[after.pin])
        // On the first, a note for a place in front of it: still the first note he was reading.
        val q = Pin("milk", 7, 10.5, 20.5, radius = 300, here = false)
        val front = Here.onBack(listOf(q, a, b))
        val (now2, after2) = payload(moon.copy(notes = true, pins = 2, pin = 0), front, listOf(q.copy(here = true), a, b))
        assertEquals(a, now2[after2.pin])
    }

    @Test fun `he leaves a place while reading another note - he stays on his, and on its own leaving he sees the next`() {
        val q = Pin("milk", 7, 10.5, 20.5, radius = 300, here = true)
        val c = Pin("plain oldest", 2)
        val was = Here.onBack(listOf(q, a, b, c))
        // EXIT: the place's note in front of him goes; he was reading a.
        val (now, after) = payload(moon.copy(notes = true, pins = 4, pin = 1), was, listOf(q.copy(here = false), a, b, c))
        assertEquals(3, after.pins)
        assertEquals(a, now[after.pin])
        // Reading the place's own note as it goes: the next older one, as a bin would show.
        val (now2, after2) = payload(moon.copy(notes = true, pins = 4, pin = 0), was, listOf(q.copy(here = false), a, b, c))
        assertEquals(a, now2[after2.pin])
    }

    @Test fun `one place here and another not, at one look - as many notes, and still his`() {
        val p1 = Pin("towels", 5, 10.0, 20.0, radius = 150, here = true)
        val p2 = Pin("milk", 1, 10.5, 20.5, radius = 300, here = false)
        val was = Here.onBack(listOf(a, p1, b, p2))
        assertEquals(listOf(a, p1, b), was)
        val (now, after) = payload(moon.copy(notes = true, pins = 3, pin = 2), was, listOf(a, p1.copy(here = false), b, p2.copy(here = true)))
        assertEquals(3, after.pins)
        assertEquals(b, now[after.pin])
    }

    @Test fun `a note just pinned in front while he reads the first - the new one, as ever`() {
        val fresh = Pin("new", 99)
        val was = listOf(a, b)
        val (now, after) = payload(moon.copy(notes = true, pins = 2, pin = 0), was, listOf(fresh, a, b), fresh = true)
        assertEquals(fresh, now[after.pin])
        val (now2, after2) = payload(moon.copy(notes = true, pins = 2, pin = 1), was, listOf(fresh, a, b), fresh = true)
        assertEquals(b, now2[after2.pin])
    }

    @Test fun `the shell keeps him on his note as the list changes - each payload and each landing's end`() {
        val src = File("src/main/java/dev/kb896/aiusage/rear/RearUserService.kt").readText()
        val onPins = src.substringAfter("private fun onPins(").substringBefore("private val landing = NoteLanding()")
        assertTrue(onPins.contains("listPins(next, fresh, "))
        val landingOver = src.substringAfter("private fun landingOver(").substringBefore("private fun listPins(")
        assertTrue(landingOver.contains("listPins(next, fresh = false, "))
        val listPins = src.substringAfter("private fun listPins(").substringBefore("\n    }\n")
        // The note shown is read off the old list before the new one takes its place, then told by its own name.
        assertTrue(listPins.indexOf("pins.getOrNull(s.pin)?.at") in 0 until listPins.indexOf("pins = next"))
        assertTrue(listPins.contains("RearNav.pinsAre(s, shown, next.map { it.at }, fresh)"))
    }

    @Test fun `the shell lists by the rule, its arrivals from the whole list, its landing note while it lands`() {
        val src = File("src/main/java/dev/kb896/aiusage/rear/RearUserService.kt").readText()
        val onPins = src.substringAfter("private fun onPins(").substringBefore("private val landing = NoteLanding()")
        // Arrivals and what is fresh are read from the whole list, before it is filtered.
        assertTrue(onPins.indexOf("onArrivals(next)") in 0 until onPins.indexOf("Here.onBack(next, landing.at)"))
        assertTrue(onPins.indexOf("maxByOrNull { it.freshAt }") in 0 until onPins.indexOf("next = dev.kb896.aiusage.place.Here.onBack(next, landing.at)"))
        // What is listed is the filtered list (listPins sets pins to it).
        assertTrue(onPins.indexOf("listPins(next, fresh, ") > onPins.indexOf("next = dev.kb896.aiusage.place.Here.onBack(next, landing.at)"))
        // The landing ends with its page (applyNav) or its time.
        assertTrue(src.contains("musicHandler.post { landingOver(up, reason) }"))
        assertTrue(src.contains("musicHandler.postDelayed(landingLook, NoteLanding.MOST_MS)"))
    }

    private object Keys {
        val gym = dev.kb896.aiusage.place.Arrivals.key(10.0, 20.0, 150)
        val shop = dev.kb896.aiusage.place.Arrivals.key(10.5, 20.5, 300)
    }
}
