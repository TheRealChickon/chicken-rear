package dev.kb896.aiusage.place

import dev.kb896.aiusage.place.Arrivals.Event
import dev.kb896.aiusage.place.Arrivals.Fix
import dev.kb896.aiusage.place.Arrivals.Place
import dev.kb896.aiusage.place.Arrivals.Quality
import dev.kb896.aiusage.place.Arrivals.Visit
import dev.kb896.aiusage.place.Arrivals.Where
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalsTest {
    // A made-up place (not his): a point and a 150 m zone.
    private val place = Place(48.0, 11.0, 150)
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    /** A point [north] metres north and [east] metres east of the place. */
    private fun fix(north: Double = 0.0, east: Double = 0.0, accuracy: Float = 20f, speed: Float? = null, age: Long = 5_000L, mock: Boolean = false) =
        Fix(place.lat + north / 111_195.0, place.lng + east / (111_195.0 * kotlin.math.cos(Math.toRadians(place.lat))), accuracy, speed, age, now - age, mock)

    private val outside = Visit(where = Where.OUTSIDE, owed = false)

    @Test fun `distance is metres`() {
        val f = fix(north = 100.0)
        assertEquals(100.0, Arrivals.distance(place, f), 0.5)
        assertEquals(1000.0, Arrivals.distance(place, fix(east = 1000.0)), 2.0)
    }

    @Test fun `a clean enter is an arrival at once`() {
        val r = Arrivals.fence(Event.ENTER, place, outside, fix(north = 60.0), null, now)
        assertTrue(r.arrived)
        assertEquals(Where.INSIDE, r.visit.where)
        assertEquals(now, r.visit.arrivedAt)
    }

    @Test fun `a doubtful enter is held - no fix, old, mock, loose, outside, driving past, a jump`() {
        val last = Arrivals.Seen(place.lat + 0.5, place.lng, now - 60_000L)   // 55 km away a minute ago: 900 m/s
        val cases = mapOf(
            Quality.NO_FIX to null,
            Quality.OLD to fix(age = 3 * min),
            Quality.MOCK to fix(mock = true),
            Quality.LOOSE to fix(accuracy = 400f),
            Quality.OUTSIDE to fix(north = 400.0),
            Quality.DRIVING to fix(speed = 12f),
        )
        for ((q, f) in cases) {
            assertEquals(q, Arrivals.judge(place, f, null))
            val r = Arrivals.fence(Event.ENTER, place, outside, f, null, now)
            assertFalse("$q", r.arrived)
            assertEquals("$q", now, r.visit.heldAt)
            assertEquals("$q", Where.OUTSIDE, r.visit.where)
        }
        assertEquals(Quality.JUMP, Arrivals.judge(place, fix(), last))
        assertFalse(Arrivals.fence(Event.ENTER, place, outside, fix(), last, now).arrived)
        // Walking speed with a fix is clean.
        assertEquals(Quality.CLEAN, Arrivals.judge(place, fix(speed = 1.4f), null))
    }

    @Test fun `a held entry becomes an arrival by DWELL or by the look on waking, and an EXIT first drops it`() {
        val held = Arrivals.fence(Event.ENTER, place, outside, fix(speed = 12f), null, now).visit
        val dwell = Arrivals.fence(Event.DWELL, place, held, fix(), null, now + 2 * min)
        assertTrue(dwell.arrived); assertEquals(0L, dwell.visit.heldAt)
        val look = Arrivals.look(place, held, fix(), null, now + min)
        assertTrue(look.arrived)
        val exit = Arrivals.fence(Event.EXIT, place, held, fix(north = 900.0), null, now + min)
        assertFalse(exit.arrived)
        assertEquals(0L, exit.visit.heldAt)
        assertEquals("dropped without a trace: the last exit is as it was", held.exitAt, exit.visit.exitAt)
        // And then a DWELL is not reached (Play services would not send one); a look outside does not arrive.
        assertFalse(Arrivals.look(place, exit.visit, fix(north = 900.0), null, now + 2 * min).arrived)
    }

    @Test fun `the look on waking is an arrival of its own when the ENTER never came`() {
        val r = Arrivals.look(place, outside, fix(north = 50.0), null, now)
        assertTrue(r.arrived)
        // But not driving through, not a fix too old or too loose for the zone.
        assertFalse(Arrivals.look(place, outside, fix(speed = 15f), null, now).arrived)
        assertFalse(Arrivals.look(place, outside, fix(age = 2 * min), null, now).arrived)
        assertFalse(Arrivals.look(place, outside, fix(accuracy = 300f), null, now).arrived)
        assertFalse(Arrivals.look(place, outside, fix(mock = true), null, now).arrived)
    }

    @Test fun `a state not known is settled without an arrival when found inside, and an ENTER from it is one`() {
        val unknown = Visit()
        val found = Arrivals.look(place, unknown, fix(), null, now)
        assertFalse(found.arrived); assertEquals(Where.INSIDE, found.visit.where)
        val out = Arrivals.look(place, unknown, fix(north = 2000.0), null, now)
        assertFalse(out.arrived); assertEquals(Where.OUTSIDE, out.visit.where)
        assertEquals("not known to have been inside: he may arrive at once", 0L, out.visit.exitAt)
        assertTrue(Arrivals.fence(Event.ENTER, place, unknown, fix(), null, now).arrived)
    }

    @Test fun `one arrival per visit - 10 minutes after a clean exit, 45 after a doubtful one`() {
        val inside = Arrivals.fence(Event.ENTER, place, outside, fix(), null, now).visit
        assertFalse(Arrivals.fence(Event.ENTER, place, inside, fix(), null, now + min).arrived)
        val clean = Arrivals.fence(Event.EXIT, place, inside, fix(north = 500.0), null, now + min).visit
        assertTrue(clean.exitClean)
        assertFalse(Arrivals.fence(Event.ENTER, place, clean, fix(), null, now + 9 * min).arrived)
        assertTrue(Arrivals.fence(Event.ENTER, place, clean, fix(), null, now + 12 * min).arrived)
        // An exit whose fix wobbles at the rim (or none) is doubtful.
        val doubtful = Arrivals.fence(Event.EXIT, place, inside, fix(north = 170.0, accuracy = 60f), null, now + min).visit
        assertFalse(doubtful.exitClean)
        assertFalse(Arrivals.fence(Event.ENTER, place, doubtful, fix(), null, now + 30 * min).arrived)
        assertTrue(Arrivals.fence(Event.ENTER, place, doubtful, fix(), null, now + 47 * min).arrived)
        assertFalse(Arrivals.fence(Event.EXIT, place, inside, null, null, now).visit.exitClean)
        // Too soon: he is inside again, quietly; the next exit starts the count over.
        val soon = Arrivals.fence(Event.ENTER, place, clean, fix(), null, now + 3 * min)
        assertEquals(Where.INSIDE, soon.visit.where)
    }

    @Test fun `two sources, one arrival - the state decides, not who says it`() {
        val first = Arrivals.fence(Event.ENTER, place, outside, fix(), null, now)
        assertTrue(first.arrived)
        assertFalse(Arrivals.look(place, first.visit, fix(), null, now + 10_000L).arrived)
        assertFalse(Arrivals.fence(Event.DWELL, place, first.visit, fix(), null, now + 2 * min).arrived)
        val byLook = Arrivals.look(place, outside, fix(), null, now)
        assertTrue(byLook.arrived)
        assertFalse(Arrivals.fence(Event.ENTER, place, byLook.visit, fix(), null, now + 30_000L).arrived)
    }

    @Test fun `pinned while inside - no chime now, the next visit`() {
        val v = Arrivals.pinned(place, fix(north = 20.0))
        assertEquals(Where.INSIDE, v.where)
        assertFalse(Arrivals.fence(Event.DWELL, place, v, fix(), null, now).arrived)
        assertEquals(Where.OUTSIDE, Arrivals.pinned(place, fix(north = 3000.0)).where)
        assertEquals("no fix in time", Where.UNKNOWN, Arrivals.pinned(place, null).where)
        assertEquals("an old one is not used", Where.UNKNOWN, Arrivals.pinned(place, fix(north = 3000.0, age = 5 * min)).where)
        assertEquals("at the rim it says neither", Where.UNKNOWN, Arrivals.pinned(place, fix(north = 170.0, accuracy = 40f)).where)
        assertTrue(v.owed)
    }

    @Test fun `which initial trigger a place gets when its fence is set again`() {
        val inside = Visit(where = Where.INSIDE, owed = false)
        assertEquals("outside: a place reached while the phone was off still arrives", Arrivals.Initial.ENTER, Arrivals.initial(outside))
        assertEquals("inside: an EXIT missed meanwhile is made up for, and a restart where he is does not chime", Arrivals.Initial.EXIT, Arrivals.initial(inside))
        assertEquals("not known: neither", Arrivals.Initial.NONE, Arrivals.initial(Visit()))
        // At the tap on Pin never an ENTER; an EXIT all the same.
        assertEquals(Arrivals.Initial.NONE, Arrivals.initial(outside, atPin = true))
        assertEquals(Arrivals.Initial.EXIT, Arrivals.initial(inside, atPin = true))
        assertEquals(Arrivals.Initial.NONE, Arrivals.initial(Visit(), atPin = true))
        for (v in listOf(outside, inside, Visit())) assertNotEquals("$v", Arrivals.Initial.ENTER, Arrivals.initial(v, atPin = true))
    }

    @Test fun `the initial EXIT of a place thought inside - he has left it, no arrival, and the next visit chimes`() {
        val inside = Visit(where = Where.INSIDE, owed = false)
        // Play services' EXIT as the fence is set again with him far away: an EXIT like any other.
        val r = Arrivals.fence(Event.EXIT, place, inside, fix(north = 3_000.0), null, now)
        assertFalse(r.arrived)
        assertEquals(Where.OUTSIDE, r.visit.where)
        assertTrue("a fresh fix far out: a clean exit", r.visit.exitClean)
        assertTrue(Arrivals.fence(Event.ENTER, place, r.visit, fix(north = 30.0), null, now + Arrivals.AGAIN_CLEAN_MS).arrived)
    }

    @Test fun `the fix at pin time - a new place from it, one thought inside found clearly left, nothing else`() {
        val inside = Visit(where = Where.INSIDE, arrivedAt = 0L, owed = false)
        // A new place (not known, no arrival): the state from the fix, as ever.
        assertEquals(Where.INSIDE, Arrivals.settled(place, Visit(owed = true), Visit(owed = false), fix(north = 20.0), null, now)!!.where)
        assertEquals("its fence's mark kept", false, Arrivals.settled(place, Visit(owed = true), Visit(owed = false), fix(north = 20.0), null, now)!!.owed)
        assertEquals(Where.UNKNOWN, Arrivals.settled(place, Visit(owed = true), Visit(owed = true), null, null, now)!!.where)
        // Thought inside, and clearly out by a fix that counts: he has left it, a clean exit now.
        val left = Arrivals.settled(place, inside, inside, fix(north = 3_000.0), null, now)!!
        assertEquals(Where.OUTSIDE, left.where)
        assertEquals(now, left.exitAt)
        assertTrue(left.exitClean)
        assertFalse(left.owed)
        // Only its fence's owed mark changed since (its set came back first): still nothing said of the place.
        assertEquals(Where.OUTSIDE, Arrivals.settled(place, inside, inside.copy(owed = true), fix(north = 3_000.0), null, now)!!.where)
        // Inside by the fix, at the rim, no fix, one that does not count, a jump: nothing said.
        assertEquals(null, Arrivals.settled(place, inside, inside, fix(north = 30.0), null, now))
        assertEquals(null, Arrivals.settled(place, inside, inside, fix(north = 175.0, accuracy = 40f), null, now))
        assertEquals(null, Arrivals.settled(place, inside, inside, null, null, now))
        assertEquals(null, Arrivals.settled(place, inside, inside, fix(north = 3_000.0, age = 2 * min), null, now))
        assertEquals(null, Arrivals.settled(place, inside, inside, fix(north = 3_000.0, mock = true), null, now))
        val last = Arrivals.Seen(place.lat - 0.5, place.lng, now - 60_000L)   // 55 km away a minute ago
        assertEquals(null, Arrivals.settled(place, inside, inside, fix(north = 3_000.0), last, now))
        // A fence has said something since the note was kept (left and came back): that stands.
        assertEquals(null, Arrivals.settled(place, inside, inside.copy(exitAt = now - min, exitClean = false), fix(north = 3_000.0), null, now))
        // Outside found inside: no arrival at pin time (pinning never chimes).
        assertEquals(null, Arrivals.settled(place, outside, outside, fix(north = 20.0), null, now))
        // Standing there (arrived): found clearly out, it has been left; its arrival still stands.
        val standing = inside.copy(arrivedAt = now - 5 * min)
        val gone = Arrivals.settled(place, standing, standing, fix(north = 3_000.0), null, now)!!
        assertEquals(Where.OUTSIDE, gone.where)
        assertEquals(now - 5 * min, gone.arrivedAt)
    }

    @Test fun `the chime - stale, in a call, one with the last`() {
        assertEquals(Arrivals.Sound.NOW, Arrivals.sound(5_000L, false, 0L, now))
        assertEquals(Arrivals.Sound.NOW, Arrivals.sound(null, false, 0L, now))
        assertEquals(Arrivals.Sound.SILENT_STALE, Arrivals.sound(11 * min, false, 0L, now))
        assertEquals(Arrivals.Sound.AFTER_CALL, Arrivals.sound(5_000L, true, 0L, now))
        assertEquals(Arrivals.Sound.ONE_WITH_LAST, Arrivals.sound(5_000L, false, now - 30_000L, now))
        assertTrue(Arrivals.afterCall(now - 5 * min, now))
        assertFalse(Arrivals.afterCall(now - 11 * min, now))
        assertFalse(Arrivals.afterCall(0L, now))
    }

    @Test fun `the fence id is stable, to five decimals and the zone`() {
        assertEquals("p:48.00000,11.00000,150", place.key)
        assertEquals(place.key, Place(48.000001, 10.999999, 150).key)
        assertNotEquals(place.key, Place(48.0, 11.0, 200).key)
        assertNotEquals(place.key, Place(48.0001, 11.0, 150).key)
        // Not the locale's comma.
        val was = java.util.Locale.getDefault()
        try { java.util.Locale.setDefault(java.util.Locale.GERMANY); assertEquals("p:48.00000,11.00000,150", place.key) } finally { java.util.Locale.setDefault(was) }
    }

    @Test fun `the zones - never under 100 m, nearest of the slider's`() {
        assertEquals(100, Arrivals.ZONES.first())
        assertEquals(100, Arrivals.zone(10))
        assertEquals(150, Arrivals.zone(160))
        assertEquals(2000, Arrivals.zone(9000))
        assertEquals("150 m", Arrivals.zoneName(150))
        assertEquals("1 km", Arrivals.zoneName(1000))
        assertEquals("1.5 km", Arrivals.zoneName(1500))
    }
}
