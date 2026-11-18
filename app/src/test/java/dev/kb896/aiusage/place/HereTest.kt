package dev.kb896.aiusage.place

import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.place.Arrivals.Event
import dev.kb896.aiusage.place.Arrivals.Fix
import dev.kb896.aiusage.place.Arrivals.Place
import dev.kb896.aiusage.place.Arrivals.Visit
import dev.kb896.aiusage.place.Arrivals.Where
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HereTest {
    // Made-up places (none of his): two points with their zones.
    private val gym = Place(10.0, 20.0, 150)
    private val shop = Place(10.5, 20.5, 300)
    private val now = 1_800_000_000_000L

    private fun note(text: String, at: Long, place: Place? = null, here: Boolean = false, arrivedAt: Long = 0L) =
        if (place == null) Pin(text, at) else Pin(text, at, place.lat, place.lng, radius = place.radius, arrivedAt = arrivedAt, here = here)

    /** A fix [north] metres north of [place], fresh. */
    private fun fix(place: Place, north: Double, accuracy: Float = 20f, age: Long = 5_000L) =
        Fix(place.lat + north / 111_195.0, place.lng, accuracy, null, age, now - age)

    @Test fun `inside is here - outside and not known are not`() {
        val visits = mapOf(
            gym.key to Visit(where = Where.INSIDE),
            shop.key to Visit(where = Where.OUTSIDE),
            "p:1.00000,2.00000,150" to Visit(where = Where.UNKNOWN),
        )
        assertEquals(setOf(gym.key), Here.keys(visits))
        assertTrue(Here.at(visits, gym.key))
        assertFalse(Here.at(visits, shop.key))
        assertFalse("not known", Here.at(visits, "p:1.00000,2.00000,150"))
        assertFalse("no state at all", Here.at(visits, "p:3.00000,4.00000,150"))
        assertTrue(Here.keys(emptyMap()).isEmpty())
    }

    @Test fun `each note is marked by its own place - plain notes never are`() {
        val pins = listOf(note("plain", 5), note("towels", 4, gym), note("milk", 3, shop), note("water bottle", 2, gym))
        val marked = Here.marked(pins, setOf(gym.key))
        assertEquals(listOf(false, true, false, true), marked.map { it.here })
        // A plain note carrying the flag by mistake loses it.
        assertFalse(Here.marked(listOf(note("plain", 5).copy(here = true)), setOf(gym.key)).single().here)
        // Away again: all off.
        assertEquals(listOf(false, false, false, false), Here.marked(marked, emptySet()).map { it.here })
        // Nothing to change: the very same list (Places.write then sends nothing).
        assertSame(marked, Here.marked(marked, setOf(gym.key)))
    }

    @Test fun `the back lists every plain note and a place note only while it is here, in his order`() {
        val pins = listOf(note("plain new", 6), note("towels", 5, gym, here = false), note("milk", 4, shop, here = true), note("plain old", 3))
        assertEquals(listOf("plain new", "milk", "plain old"), Here.onBack(pins).map { it.text })
        // Nothing for a place: the same list.
        val plain = listOf(note("a", 2), note("b", 1))
        assertSame(plain, Here.onBack(plain))
        // All of them here: the same list.
        val all = pins.map { if (it.placed) it.copy(here = true) else it }
        assertSame(all, Here.onBack(all))
        // Only place notes, none here: nothing on the back.
        assertTrue(Here.onBack(listOf(note("towels", 5, gym))).isEmpty())
    }

    @Test fun `a note landing is listed whatever its place, and only that one`() {
        val pins = listOf(note("new for the shop", 7, shop), note("towels", 5, gym), note("plain", 4))
        assertEquals(listOf("new for the shop", "plain"), Here.onBack(pins, landing = 7).map { it.text })
        assertEquals(listOf("plain"), Here.onBack(pins, landing = 0).map { it.text })
        assertTrue(Here.listed(pins[0], 7)); assertFalse(Here.listed(pins[1], 7)); assertTrue(Here.listed(pins[2]))
    }

    @Test fun `several places - each by its own state`() {
        val visits = mapOf(gym.key to Visit(where = Where.INSIDE), shop.key to Visit(where = Where.INSIDE))
        val pins = listOf(note("towels", 3, gym), note("milk", 2, shop), note("plain", 1))
        assertEquals(3, Here.onBack(Here.marked(pins, Here.keys(visits))).size)
        val leftTheShop = visits + (shop.key to Visit(where = Where.OUTSIDE))
        assertEquals(listOf("towels", "plain"), Here.onBack(Here.marked(pins, Here.keys(leftTheShop))).map { it.text })
    }

    @Test fun `a note made while inside its zone is on the back from the fix at pin time`() {
        val pins = PinList.added(emptyList(), "towels", now, PinList.Where(gym.lat, gym.lng, gym.radius, "Gym")).pins
        // Kept at the tap: the new place is not known yet, the note is not listed (its pin lands it: NoteLanding).
        assertTrue(Here.onBack(Here.marked(pins, Here.keys(mapOf(gym.key to Visit(owed = true))))).isEmpty())
        // The fix at pin time puts the phone inside the zone: here.
        val inside = mapOf(gym.key to Arrivals.pinned(gym, fix(gym, north = 40.0)))
        assertEquals(Where.INSIDE, inside.getValue(gym.key).where)
        assertEquals(listOf("towels"), Here.onBack(Here.marked(pins, Here.keys(inside))).map { it.text })
        // Made from far away, or with no fix in time: not on the back.
        val away = mapOf(gym.key to Arrivals.pinned(gym, fix(gym, north = 5_000.0)))
        assertEquals(Where.OUTSIDE, away.getValue(gym.key).where)
        assertTrue(Here.onBack(Here.marked(pins, Here.keys(away))).isEmpty())
        assertTrue(Here.onBack(Here.marked(pins, Here.keys(mapOf(gym.key to Arrivals.pinned(gym, null))))).isEmpty())
        // A note for a place already known to be here (another note there): listed as it is kept.
        val second = PinList.added(Here.marked(pins, Here.keys(inside)), "shoes", now + 1, PinList.Where(gym.lat, gym.lng, gym.radius, "Gym")).pins
        assertEquals(listOf("shoes", "towels"), Here.onBack(Here.marked(second, Here.keys(inside))).map { it.text })
    }

    @Test fun `he leaves and comes back - the fence's EXIT takes the note off the back, an ENTER brings it back`() {
        val pins = listOf(note("towels", 3, gym), note("plain", 1))
        var v = Arrivals.pinned(gym, fix(gym, north = 30.0))
        fun shown() = Here.onBack(Here.marked(pins, Here.keys(mapOf(gym.key to v)))).map { it.text }
        assertEquals(listOf("towels", "plain"), shown())
        v = Arrivals.fence(Event.EXIT, gym, v, fix(gym, north = 2_000.0), null, now).visit
        assertEquals(listOf("plain"), shown())
        // A doubtful ENTER (no fix) is held: not here yet; its DWELL confirms it.
        v = Arrivals.fence(Event.ENTER, gym, v, null, null, now + 20 * 60_000L).visit
        assertEquals(listOf("plain"), shown())
        v = Arrivals.fence(Event.DWELL, gym, v, null, null, now + 22 * 60_000L).visit
        assertEquals(listOf("towels", "plain"), shown())
    }

    @Test fun `the look on waking settles a place not known, and finds him gone`() {
        val pins = listOf(note("towels", 3, gym))
        var v = Visit()
        fun shown() = Here.onBack(Here.marked(pins, Here.keys(mapOf(gym.key to v)))).size
        assertEquals(0, shown())
        v = Arrivals.look(gym, v, fix(gym, north = 50.0), null, now).visit
        assertEquals(1, shown())
        v = Arrivals.look(gym, v, fix(gym, north = 3_000.0), null, now + 30 * 60_000L).visit
        assertEquals(0, shown())
    }

    @Test fun `a note moved to another place in the settings waits there, off the back until known to be there`() {
        val pins = listOf(note("towels", 3, gym, here = true))
        val (moved, old) = PinList.moved(pins, 3, shop.lat, shop.lng, shop.radius, null)
        assertEquals(gym.key, old)
        assertFalse(moved.single().here)
        assertTrue(Here.onBack(moved).isEmpty())
    }
}
