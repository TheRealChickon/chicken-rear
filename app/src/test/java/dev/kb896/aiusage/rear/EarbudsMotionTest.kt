package dev.kb896.aiusage.rear

import dev.kb896.aiusage.service.EarbudsConnections
import dev.kb896.aiusage.service.EarbudsGate
import dev.kb896.aiusage.service.SonyArt
import dev.kb896.aiusage.ui.home.RowState
import dev.kb896.aiusage.ui.home.RowStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Earbuds connecting: the back's clock (draft B), when it plays, once per connection. */
class EarbudsMotionTest {
    private fun frames(step: Float = 1000f / 120f, until: Float = EarbudsMotion.WHOLE_MS.toFloat()) = generateSequence(0f) { it + step }.takeWhile { it <= until }

    @Test fun `the back begins only when the front's event is over, and is gone at its end`() {
        for (t in frames(until = EarbudsMotion.BACK_AT - 1f)) {
            val b = EarbudsMotion.back(t)
            assertEquals(0f, b.picture, 0f); assertEquals(0f, b.blur, 0f); assertEquals(-1f, b.veil, 0f)
        }
        assertTrue(EarbudsMotion.back(EarbudsMotion.BACK_AT + 900f).picture > 0.99f)
        assertEquals(EarbudsEdge.RUN_MS, EarbudsMotion.BACK_AT)
        val end = EarbudsMotion.back(EarbudsMotion.BACK_END_MS - 1f)
        assertTrue(end.picture < 0.01f && end.figures < 0.01f && end.dim < 0.05f)
    }

    @Test fun `xiaomi's veil blooms, drains to the edge and fades within a second`() {
        assertEquals(0f, EarbudsMotion.veil(0f).first, 1e-3f)
        assertTrue(EarbudsMotion.veil(350f).first > 0.99f)
        assertTrue(EarbudsMotion.veil(650f).second > 0.8f)
        assertEquals(0f, EarbudsMotion.veil(960f).second, 1e-3f)
        assertEquals(0f, EarbudsMotion.veil(960f).first, 1e-3f)
    }

    @Test fun `it plays only when everything allows it, and says why not`() {
        val way = EarbudsGate.Way.APP_OVERLAY
        assertTrue(EarbudsGate.decide(true, way, inCall = false, frontOn = true, newConnection = true, xiaomiShowsItsOwn = false).plays)
        assertFalse(EarbudsGate.decide(false, way, false, true, true, false).plays)
        assertFalse(EarbudsGate.decide(true, way, true, true, true, false).plays)
        assertFalse(EarbudsGate.decide(true, way, false, false, true, false).plays)
        assertFalse(EarbudsGate.decide(true, way, false, true, false, false).plays)
        assertFalse(EarbudsGate.decide(true, way, false, true, true, true).plays)
        assertFalse(EarbudsGate.decide(true, EarbudsGate.Way.NONE, false, true, true, false).plays)
        assertEquals(EarbudsGate.Way.GLASS_SERVICE, EarbudsGate.way(glassRunning = true, overlayAllowed = true))
        assertEquals(EarbudsGate.Way.NONE, EarbudsGate.way(false, false))
        assertTrue(EarbudsGate.inCall(2)); assertTrue(EarbudsGate.inCall(3)); assertTrue(EarbudsGate.inCall(1)); assertFalse(EarbudsGate.inCall(0))
    }

    @Test fun `once per connection - two profiles of one pair, a short drop, the system telling what is there`() {
        val c = EarbudsConnections(goneMs = 4_000)
        assertTrue(c.added("AA", 10, 0))           // A2DP
        assertFalse(c.added("AA", 11, 50))         // the headset profile of the same pair
        assertFalse(c.added("AA", 10, 60))         // the same output told again
        c.removed("AA", 10, 1_000); c.removed("AA", 11, 1_000)
        assertFalse(c.added("AA", 12, 2_000))      // back within four seconds: the same connection
        c.removed("AA", 12, 3_000)
        assertTrue(c.added("AA", 13, 9_000))       // a new one
        c.seed("BB", 20)
        assertFalse(c.added("BB", 20, 9_100))      // there before the watch began
    }

    @Test fun `levels, keys and sony's picture ids`() {
        assertNull(EarbudsGate.level(-1)); assertNull(EarbudsGate.level(101)); assertEquals(82, EarbudsGate.level(82))
        assertEquals("name", EarbudsGate.key("00:00:00:00:00:00", "name"))
        assertNull(EarbudsGate.key("", ""))
        assertTrue(SonyArt.imageId("WF-1000XM6") != null)
        assertNull(SonyArt.imageId("Some speaker"))
        assertTrue(SonyArt.url(SonyArt.imageId("wf-1000xm6")!!).startsWith("https://"))
    }

    @Test fun `one at a time - a second start while one plays is not put over it`() {
        assertFalse(EarbudsGate.busy(now = 10_000, lastStart = 0))
        assertTrue(EarbudsGate.busy(10_000, 9_000))
        assertTrue(EarbudsGate.busy(10_000 + EarbudsMotion.WHOLE_MS - 1, 10_000))
        assertFalse(EarbudsGate.busy(10_000 + EarbudsMotion.WHOLE_MS, 10_000))
        // a clock that went back (another boot's value) does not block for ever
        assertFalse(EarbudsGate.busy(5_000, 9_000))
    }

    @Test fun `the back follows actual front removal and shows nothing when told too late`() {
        assertTrue(EarbudsMotion.LEVEL_AGAIN_MS < EarbudsMotion.BACK_AT)
        for (endedAt in listOf(EarbudsEdge.RUN_MS, EarbudsEdge.RUN_MS + 250L, EarbudsEdge.RUN_MS + 1_500L)) {
            val start = EarbudsMotion.rearStartAt(endedAt)
            assertEquals(endedAt, start + EarbudsMotion.BACK_AT)
            assertEquals(0f, EarbudsMotion.back((endedAt - start).toFloat()).blur, 0f)
        }
        val at = 100_000L
        assertFalse(EarbudsMotion.backLate(at + EarbudsMotion.BACK_AT, at))
        assertFalse(EarbudsMotion.backLate(at + EarbudsMotion.BACK_AT + EarbudsMotion.BACK_LATE_MS, at))
        assertTrue(EarbudsMotion.backLate(at + EarbudsMotion.BACK_AT + EarbudsMotion.BACK_LATE_MS + 1, at))
        // late by that much the veil has not yet bloomed fully: it is still the moment's beginning
        assertTrue(EarbudsMotion.veil(EarbudsMotion.BACK_LATE_MS.toFloat()).first < 1f)
    }

    @Test fun `the settings row - on only with a way onto the front`() {
        assertEquals(RowState(false), RowStates.earbuds(stored = false, overlay = true, glassRunning = true, nearby = true))
        assertFalse(RowStates.earbuds(true, overlay = false, glassRunning = false, nearby = true).on)
        assertTrue(RowStates.earbuds(true, overlay = false, glassRunning = true, nearby = true).on)
        val noLevel = RowStates.earbuds(true, overlay = true, glassRunning = false, nearby = false)
        assertTrue(noLevel.on && noLevel.note != null)
        assertEquals(RowState(true), RowStates.earbuds(true, true, false, true))
    }
}
