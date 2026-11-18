package dev.kb896.aiusage.place

import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.Pin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinListTest {
    private val gym = PinList.Where(48.1, 11.5, 200, "Gym")
    private val shop = PinList.Where(48.2, 11.6, 150, null)

    @Test fun `old pins decode with the new fields at their defaults`() {
        val raw = """{"pins":[{"text":"milk","at":5,"lat":48.0,"lng":11.0,"shownAt":7},{"text":"plain","at":3}]}"""
        val pins = PanelConfig.decode(raw).pins
        assertEquals(2, pins.size)
        assertEquals(150, pins[0].zone)
        assertNull(pins[0].place)
        assertEquals(0L, pins[0].arrivedAt)
        assertFalse(pins[0].watched)
        assertTrue(pins[0].placed); assertFalse(pins[1].placed)
        // And they go round whole.
        val back = PanelConfig.decode(PanelConfig.encode(PanelConfig.DEFAULT.copy(pins = listOf(Pin("x", 1, 48.0, 11.0, radius = 500, place = "Gym", arrivedAt = 9, watched = true))))).pins.single()
        assertEquals(500, back.zone); assertEquals("Gym", back.place); assertEquals(9L, back.arrivedAt); assertTrue(back.watched)
    }

    @Test fun `a place note is never replaced by a plain note with the same words, nor by one for another place`() {
        var pins = PinList.added(emptyList(), "towels", 1, gym).pins
        pins = PinList.added(pins, "towels", 2).pins
        assertEquals(2, pins.size)
        pins = PinList.added(pins, "towels", 3, shop).pins
        assertEquals(3, pins.size)
        // The same words for the same place: that note again, moved up.
        pins = PinList.added(pins, "towels", 4, gym).pins
        assertEquals(3, pins.size)
        assertEquals(4L, pins.first().at); assertEquals("Gym", pins.first().place)
        // The same plain words: moved up.
        pins = PinList.added(pins, " towels ", 5).pins
        assertEquals(3, pins.size)
    }

    @Test fun `twenty at most - plain notes go first, then the oldest place note, and its fence when it was the last there`() {
        var pins = emptyList<Pin>()
        for (i in 1..15) pins = PinList.added(pins, "place $i", i.toLong(), PinList.Where(48.0 + i, 11.0, 150, null)).pins
        for (i in 16..20) pins = PinList.added(pins, "plain $i", i.toLong()).pins
        val a = PinList.added(pins, "one more", 21, gym)
        assertEquals(20, a.pins.size)
        assertEquals("plain 16", a.dropped.single().text)
        var full = emptyList<Pin>()
        for (i in 1..20) full = PinList.added(full, "place $i", i.toLong(), PinList.Where(48.0 + i, 11.0, 150, null)).pins
        val b = PinList.added(full, "new", 30)
        assertEquals("place 1", b.dropped.single().text)
        assertEquals("new", b.pins.first().text)
    }

    @Test fun `the name is 40 characters at most, a blank one is none, the zone is the slider's`() {
        val p = PinList.added(emptyList(), "x", 1, PinList.Where(1.0, 2.0, 160, "a".repeat(60))).pins.single()
        assertEquals(40, p.place!!.length)
        assertEquals(150, p.zone)
        assertNull(PinList.added(emptyList(), "x", 1, PinList.Where(1.0, 2.0, 150, "  ")).pins.single().place)
    }

    @Test fun `several notes at one place arrive as one, and stand until dismissed`() {
        var pins = PinList.added(emptyList(), "a", 1, gym).pins
        pins = PinList.added(pins, "b", 2, gym).pins
        pins = PinList.added(pins, "c", 3, shop).pins
        pins = PinList.added(pins, "plain", 4).pins
        val key = gym.key
        val arrived = PinList.arrived(pins, key, 100)!!
        assertEquals(listOf("b", "a"), PinList.arrivals(arrived).map { it.text })
        assertTrue(PinList.standing(arrived, key))
        assertNull("standing already: no new arrival", PinList.arrived(arrived, key, 200))
        assertNull(PinList.arrived(pins, "p:nowhere", 100))
    }

    @Test fun `not now - all at that place wait again, the bin - that note is done, and the fence goes with the last`() {
        var pins = PinList.added(emptyList(), "a", 1, gym).pins
        pins = PinList.added(pins, "b", 2, gym).pins
        pins = PinList.arrived(pins, gym.key, 100)!!
        val later = PinList.later(pins, 1)!!
        assertTrue(PinList.arrivals(later).isEmpty())
        assertEquals(2, later.size)
        assertNull("nothing standing", PinList.later(later, 1))
        val one = PinList.binned(pins, 2)
        assertEquals(listOf("a"), one.pins.map { it.text })
        assertNull("a note is left there: the fence stays", one.lastAt)
        val two = PinList.binned(one.pins, 1)
        assertEquals(gym.key, two.lastAt)
        assertTrue(two.pins.isEmpty())
        assertNull(PinList.binned(listOf(Pin("plain", 5)), 5).lastAt)
    }

    @Test fun `watched marks follow the held fences`() {
        val pins = PinList.added(PinList.added(emptyList(), "a", 1, gym).pins, "p", 2).pins
        val w = PinList.watched(pins) { it == gym.key }
        assertTrue(w.first { it.text == "a" }.watched)
        assertFalse(w.first { it.text == "p" }.watched)
        assertFalse(PinList.watched(w) { false }.first { it.text == "a" }.watched)
    }

    @Test fun `a place moved takes its note along and gives the old key for its fence`() {
        val pins = PinList.added(emptyList(), "a", 1, gym).pins
        val (next, old) = PinList.moved(pins, 1, 50.0, 12.0, 300, "New gym")
        assertEquals(gym.key, old)
        assertEquals(Arrivals.key(50.0, 12.0, 300), PinList.keyOf(next.single()))
        assertEquals("New gym", next.single().place)
    }
}
