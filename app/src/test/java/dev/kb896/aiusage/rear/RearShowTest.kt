package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.RearShow.Decision
import dev.kb896.aiusage.rear.RearShow.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One transient show at a time on the rear: banner > charge > wake, with a 120 ms cross-fade. */
class RearShowTest {

    @Test fun `idle at first, anything starts and cuts nothing`() {
        val s = RearShow()
        assertNull(s.current(0))
        assertEquals(Decision.Start(Kind.WAKE, null, 120), s.request(Kind.WAKE, 0, 800))
        assertEquals(Kind.WAKE, s.current(100))
    }

    @Test fun `the order is banner over charge over wake`() {
        assertTrue(Kind.BANNER.rank > Kind.CHARGE.rank)
        assertTrue(Kind.CHARGE.rank > Kind.WAKE.rank)
    }

    @Test fun `a higher one cuts the running one short with the cross-fade`() {
        val s = RearShow()
        s.request(Kind.CHARGE, 0, 5_000)
        val d = s.request(Kind.BANNER, 1_000, 4_480)
        assertEquals(Decision.Start(Kind.BANNER, Kind.CHARGE, RearShow.CROSS_FADE_MS), d)
        assertEquals(120L, RearShow.CROSS_FADE_MS)
        assertEquals(Kind.BANNER, s.current(1_001))
        // The wake under a charge is cut by the charge the same way.
        val w = RearShow()
        w.request(Kind.WAKE, 0, 700)
        assertEquals(Decision.Start(Kind.CHARGE, Kind.WAKE, 120), w.request(Kind.CHARGE, 100, 5_000))
    }

    @Test fun `a lower one arriving while a higher one plays is dropped`() {
        val s = RearShow()
        s.request(Kind.BANNER, 0, 4_480)
        assertEquals(Decision.Drop(Kind.CHARGE, Kind.BANNER), s.request(Kind.CHARGE, 100, 5_000))
        assertEquals(Decision.Drop(Kind.WAKE, Kind.BANNER), s.request(Kind.WAKE, 200, 700))
        // Dropped means not shown and not queued: the banner is still the one, and ends on time.
        assertEquals(Kind.BANNER, s.current(4_479))
        assertNull(s.current(4_480))
    }

    @Test fun `the same kind again restarts it and cuts nothing`() {
        val s = RearShow()
        s.request(Kind.BANNER, 0, 4_000)
        assertEquals(Decision.Start(Kind.BANNER, null, 120), s.request(Kind.BANNER, 3_000, 4_000))
        assertEquals("the restart did not run on from its own start", Kind.BANNER, s.current(6_000))
        assertNull(s.current(7_000))
    }

    @Test fun `idle after its time, and then a lower one may start`() {
        val s = RearShow()
        s.request(Kind.BANNER, 0, 4_480)
        assertNull(s.current(4_480))
        assertEquals(Decision.Start(Kind.WAKE, null, 120), s.request(Kind.WAKE, 4_500, 700))
    }

    @Test fun `ended early, it is idle at once`() {
        val s = RearShow()
        s.request(Kind.CHARGE, 0, 5_000)
        s.end(Kind.CHARGE, 1_000)
        assertNull(s.current(1_000))
        assertEquals(Decision.Start(Kind.WAKE, null, 120), s.request(Kind.WAKE, 1_001, 700))
    }

    @Test fun `a show that was cut short does not end its successor`() {
        val s = RearShow()
        s.request(Kind.CHARGE, 0, 5_000)
        s.request(Kind.BANNER, 1_000, 4_480)
        // The charge's layer stops after its fade and says it ended: the banner stays.
        s.end(Kind.CHARGE, 1_120)
        assertTrue(s.playing(Kind.BANNER, 1_200))
        assertFalse(s.playing(Kind.CHARGE, 1_200))
    }

    @Test fun `the cut one fades from full to nothing over the cross-fade`() {
        val s = RearShow()
        assertEquals(1f, s.cutAlpha(1_000, 1_000), 0.0001f)
        assertEquals(0.5f, s.cutAlpha(1_000, 1_060), 0.0001f)
        assertEquals(0f, s.cutAlpha(1_000, 1_120), 0.0001f)
        assertEquals(0f, s.cutAlpha(1_000, 9_999), 0.0001f)
    }

    // -- the wake animation against the others, and the re-wake guard ---------------------------

    @Test fun `a wake under a charge or a banner is dropped, and the charge after it still plays`() {
        val c = RearShow()
        c.request(Kind.CHARGE, 0, 6_500)
        assertEquals(Decision.Drop(Kind.WAKE, Kind.CHARGE), c.request(Kind.WAKE, 50, 920))
        assertEquals(Kind.CHARGE, c.current(1_000))
        val b = RearShow()
        b.request(Kind.BANNER, 0, 4_480)
        assertEquals(Decision.Drop(Kind.WAKE, Kind.BANNER), b.request(Kind.WAKE, 10, 920))
        assertEquals(Kind.BANNER, b.current(4_000))
    }

    @Test fun `a charge or a banner arriving during a wake cuts it with the cross-fade`() {
        val s = RearShow()
        s.request(Kind.WAKE, 0, 920)
        assertEquals(Decision.Start(Kind.CHARGE, Kind.WAKE, 120), s.request(Kind.CHARGE, 300, 6_500))
        val t = RearShow()
        t.request(Kind.WAKE, 0, 920)
        assertEquals(Decision.Start(Kind.BANNER, Kind.WAKE, 120), t.request(Kind.BANNER, 300, 4_480))
        // The wake's layer stops after its fade: the banner is not ended by it.
        t.end(Kind.WAKE, 420)
        assertTrue(t.playing(Kind.BANNER, 500))
    }

    @Test fun `the first wake plays, one under 3 s later does not, one 3 s later does`() {
        val s = RearShow()
        assertEquals(3_000L, RearShow.REWAKE_GUARD_MS)
        assertTrue(s.wakeEdge(10_000))
        assertFalse(s.wakeEdge(12_999))
        // Counted from the last wake, played or not: a run of quick wakes plays only the first.
        assertFalse(s.wakeEdge(15_500))
        assertTrue(s.wakeEdge(18_500))
    }

    @Test fun `the guard is about wakes, not shows`() {
        val s = RearShow()
        assertTrue(s.wakeEdge(0))
        // A charge or a banner in between neither resets nor opens the guard.
        s.request(Kind.CHARGE, 100, 6_500)
        s.request(Kind.BANNER, 200, 4_480)
        assertFalse(s.wakeEdge(2_000))
        assertTrue(s.wakeEdge(5_000))
    }
}
