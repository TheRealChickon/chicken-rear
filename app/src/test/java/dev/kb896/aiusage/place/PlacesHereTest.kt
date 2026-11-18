package dev.kb896.aiusage.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.place.Arrivals.Event
import dev.kb896.aiusage.place.Arrivals.Fix
import dev.kb896.aiusage.place.Arrivals.Place
import dev.kb896.aiusage.place.Arrivals.Visit
import dev.kb896.aiusage.place.Arrivals.Where
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE BACK FOLLOWS A CHANGE OF PLACE: a fence's EXIT or ENTER, a fix at pin time, each is saved onto the notes
 * (Pin.here) and sent to the back as a new payload at once; nothing changed, nothing is sent. A place thought here
 * that the fix at pin time finds clearly left is left. The bridge to the shell and the service's start are replaced by
 * recorders (Places.teller, Places.waker).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlacesHereTest {
    private lateinit var app: Context
    private val told = ArrayList<PanelConfig>()
    private var woken = 0
    private val oldTeller = Places.teller
    private val oldWaker = Places.waker

    // Made-up places, none of his.
    private val gym = Place(10.0, 20.0, 150)
    private val shop = Place(10.5, 20.5, 300)

    private fun note(text: String, at: Long, place: Place? = null, here: Boolean = false) =
        if (place == null) Pin(text, at) else Pin(text, at, place.lat, place.lng, radius = place.radius, here = here)

    /** A fix [north] metres north of [place], seconds old. */
    private fun fix(place: Place, north: Double): Fix {
        val now = System.currentTimeMillis()
        return Fix(place.lat + north / 111_195.0, place.lng, 20f, null, 3_000L, now - 3_000L)
    }

    private fun stored() = ConfigStore(app).load().pins
    private fun lastShown() = Here.onBack(told.last().pins).map { it.text }

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Places.teller = { _, config, _ -> told += config }
        Places.waker = { woken++ }
    }

    @After fun tearDown() {
        Places.teller = oldTeller
        Places.waker = oldWaker
    }

    private fun given(pins: List<Pin>, visits: Map<String, Visit>) {
        ConfigStore(app).save(ConfigStore(app).load().copy(pins = pins))
        PlaceStore(app).save(PlaceStore.State(visits = visits))
    }

    @Test fun `leaving the place - the fence's EXIT takes its note off the back at once`() {
        given(listOf(note("plain", 3), note("towels", 2, gym, here = true)), mapOf(gym.key to Visit(where = Where.INSIDE, owed = false)))
        Places.onFence(app, Event.EXIT, listOf(gym.key), fix(gym, north = 2_000.0))
        assertEquals("one payload", 1, told.size)
        assertEquals(listOf("plain"), lastShown())
        assertFalse(stored().single { it.text == "towels" }.here)
        assertEquals("the service made sure of: the broadcast may have started the process", 1, woken)
        // The settings' list still has it.
        assertEquals(2, stored().size)
    }

    @Test fun `coming back inside soon after - not a new arrival, but the note is on the back again`() {
        given(listOf(note("towels", 2, gym)), mapOf(gym.key to Visit(where = Where.OUTSIDE, exitAt = System.currentTimeMillis(), owed = false)))
        Places.onFence(app, Event.ENTER, listOf(gym.key), fix(gym, north = 30.0))
        assertEquals(1, told.size)
        assertEquals(listOf("towels"), lastShown())
        assertEquals("no arrival: it stands nowhere", 0L, stored().single().arrivedAt)
        assertEquals(Where.INSIDE, PlaceStore(app).load().visits.getValue(gym.key).where)
    }

    @Test fun `an event that changes nothing on the back sends nothing`() {
        given(listOf(note("milk", 2, shop)), mapOf(shop.key to Visit(where = Where.OUTSIDE, exitAt = 1L, owed = false)))
        // Already outside: a second EXIT.
        Places.onFence(app, Event.EXIT, listOf(shop.key), fix(shop, north = 3_000.0))
        assertTrue(told.isEmpty())
        assertEquals(0, woken)
        assertNull(Places.marks(app, "again"))
    }

    @Test fun `a note made at the place it is for - the fix at pin time puts it on the back`() {
        given(listOf(note("towels", 2, gym)), mapOf(gym.key to Visit(owed = true)))
        Places.settled(app, gym, fix(gym, north = 40.0))
        assertEquals(Where.INSIDE, PlaceStore(app).load().visits.getValue(gym.key).where)
        assertEquals(1, told.size)
        assertEquals(listOf("towels"), lastShown())
        assertTrue(stored().single().here)
    }

    @Test fun `a note made far from its place, or with no fix in time - not on the back`() {
        given(listOf(note("milk", 2, shop)), mapOf(shop.key to Visit(owed = true)))
        Places.settled(app, shop, fix(shop, north = 5_000.0))
        assertEquals(Where.OUTSIDE, PlaceStore(app).load().visits.getValue(shop.key).where)
        assertFalse(stored().single().here)
        assertTrue("nothing changed on the back", told.isEmpty())
        given(listOf(note("towels", 3, gym)), mapOf(gym.key to Visit(owed = true)))
        Places.settled(app, gym, null)
        assertEquals(Where.UNKNOWN, PlaceStore(app).load().visits.getValue(gym.key).where)
        assertFalse(stored().single().here)
    }

    @Test fun `a fix at pin time does not overrule what a fence has said since`() {
        given(listOf(note("towels", 2, gym, here = false)), mapOf(gym.key to Visit(where = Where.OUTSIDE, exitAt = 5L, owed = false)))
        Places.settled(app, gym, fix(gym, north = 10.0))
        assertEquals(Where.OUTSIDE, PlaceStore(app).load().visits.getValue(gym.key).where)
        assertTrue(told.isEmpty())
    }

    @Test fun `another note for a place thought here, kept far from it - the fix at pin time takes the place's notes off the back`() {
        // The place is still stored as inside (its EXIT was missed while its fence was not held); a second note for it.
        val inside = Visit(where = Where.INSIDE, owed = false)
        given(listOf(note("water", 3, gym, here = true), note("plain", 2), note("towels", 1, gym, here = true)), mapOf(gym.key to inside))
        Places.settled(app, gym, fix(gym, north = 3_000.0), before = inside)
        val v = PlaceStore(app).load().visits.getValue(gym.key)
        assertEquals(Where.OUTSIDE, v.where)
        assertTrue("a clean exit", v.exitClean)
        assertEquals("one payload", 1, told.size)
        assertEquals(listOf("plain"), lastShown())
        assertTrue(stored().filter { it.placed }.none { it.here })
    }

    @Test fun `another note for a place he is at - the fix at pin time keeps its notes on the back`() {
        val inside = Visit(where = Where.INSIDE, owed = false)
        given(listOf(note("water", 3, gym, here = true), note("towels", 1, gym, here = true)), mapOf(gym.key to inside))
        Places.settled(app, gym, fix(gym, north = 30.0), before = inside)
        assertEquals(Where.INSIDE, PlaceStore(app).load().visits.getValue(gym.key).where)
        assertTrue("nothing changed on the back", told.isEmpty())
    }

    @Test fun `a fence's word between the pin and its fix stands - the fix does not take the place back out`() {
        val inside = Visit(where = Where.INSIDE, owed = false)
        // Left and back again (an EXIT, then an ENTER) while the fix was on its way: what the fences said stands.
        val since = inside.copy(exitAt = System.currentTimeMillis() - 60_000L, exitClean = false)
        given(listOf(note("water", 3, gym, here = true)), mapOf(gym.key to since))
        Places.settled(app, gym, fix(gym, north = 3_000.0), before = inside)
        assertEquals(since, PlaceStore(app).load().visits.getValue(gym.key))
        assertTrue(told.isEmpty())
    }

    @Test fun `each initial trigger is Play services' own flag`() {
        assertEquals(0, Fences.trigger(Arrivals.Initial.NONE))
        assertEquals(com.google.android.gms.location.GeofencingRequest.INITIAL_TRIGGER_ENTER, Fences.trigger(Arrivals.Initial.ENTER))
        assertEquals(com.google.android.gms.location.GeofencingRequest.INITIAL_TRIGGER_EXIT, Fences.trigger(Arrivals.Initial.EXIT))
    }
}
