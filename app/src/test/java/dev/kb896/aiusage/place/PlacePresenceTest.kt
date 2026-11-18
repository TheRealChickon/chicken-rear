package dev.kb896.aiusage.place

import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.place.Arrivals.Fix
import dev.kb896.aiusage.place.Arrivals.Place
import dev.kb896.aiusage.place.Arrivals.Visit
import dev.kb896.aiusage.place.Arrivals.Where
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacePresenceTest {
    private val place = Place(10.0, 20.0, 150)
    private val now = 1_800_000_000_000L
    private val inside = Visit(where = Where.INSIDE, confirmedAt = now - 60_000L)

    private fun fix(north: Double, accuracy: Float = 20f, at: Long = now) =
        Fix(place.lat + north / 111_195.0, place.lng, accuracy, null, now - at, at)

    private fun check(visit: Visit, fix: Fix?) = Arrivals.look(place, visit, fix, null, now).visit

    @Test fun `enter at the chosen radius and leave beyond a wider radius plus accuracy`() {
        val outside = Visit(where = Where.OUTSIDE)
        assertEquals(Where.OUTSIDE, check(outside, fix(151.0)).where)
        assertEquals(Where.INSIDE, check(outside, fix(149.0)).where)
        assertEquals(inside, check(inside, fix(251.0)))
        assertEquals(inside, check(inside, fix(269.0)))
        val left = check(inside, fix(271.0))
        assertEquals(Where.OUTSIDE, left.where)
        assertTrue(left.exitClean)
        assertEquals(0L, left.confirmedAt)
        assertEquals(inside, Arrivals.fenceExit(place, inside, fix(265.0), null, now).visit)
        assertEquals(Where.OUTSIDE, Arrivals.fenceExit(place, inside, null, null, now).visit.where)
    }

    @Test fun `missing old or inaccurate positions cannot extend a visit`() {
        val stale = inside.copy(confirmedAt = now - Arrivals.CONFIRM_FOR_MS)
        for (fix in listOf(null, fix(50.0, accuracy = 200f), fix(1500.0, accuracy = 200f), fix(20.0, at = now - 90_000))) {
            assertEquals(inside, check(inside, fix))
            assertEquals(Where.UNKNOWN, check(stale, fix).where)
        }
        assertEquals(now, check(stale, fix(50.0)).confirmedAt)
        assertEquals(Where.OUTSIDE, check(stale, fix(400.0)).where)
        assertEquals(Where.UNKNOWN, check(inside.copy(confirmedAt = 0), null).where)
    }

    @Test fun `a cached fix never renews the deadline and delayed events cannot revive a visit`() {
        val recent = fix(30.0, at = now - 2000)
        val confirmed = check(inside, recent)
        assertEquals(recent.at, confirmed.confirmedAt)
        assertEquals(confirmed, Arrivals.look(place, confirmed, recent.copy(ageMs = 3000), null, now + 1000).visit)
        val left = check(confirmed, fix(400.0))
        assertEquals(left, Arrivals.fence(Arrivals.Event.ENTER, place, left, recent, null, now + 1000).visit)
    }

    @Test fun `cached rear payload hides both pages at the deadline even without another app update`() {
        val pin = Pin("reminder", 1, place.lat, place.lng, arrivedAt = now, here = true, hereConfirmedAt = now)
        val lastMoment = Here.current(pin, now + Arrivals.CONFIRM_FOR_MS - 1)
        assertTrue(Here.listed(lastMoment))
        assertEquals(listOf(pin), PinList.arrivals(listOf(lastMoment)))
        val expired = Here.current(pin, now + Arrivals.CONFIRM_FOR_MS)
        assertFalse(Here.listed(expired))
        assertTrue(PinList.arrivals(listOf(expired)).isEmpty())
        assertFalse(Here.listed(Here.current(pin.copy(hereConfirmedAt = 0L), now)))
        assertFalse(Here.listed(Here.current(pin, now - 1)))
        assertTrue(Here.listed(Here.current(Pin("plain", 2), now)))
        assertTrue(Here.listed(expired, landing = pin.at))
    }
}
