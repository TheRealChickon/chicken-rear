package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A pinned note is beamed from the front to the back in ONE movement: the front's
 * card leaves as the back's comes, at the same angle, and the card is the notes page when it has landed.
 */
class PinBeamTest {
    @Test fun `the hand-off - the back's card comes exactly as the front's goes, never gone from both, never whole on both`() {
        for (ms in 0L..180L step 4) {
            val front = PinBeam.front(PinBeam.ARRIVE_AT + ms).cardAlpha
            val back = PinBeam.back(ms).alpha
            assertEquals("at $ms ms of the hand-off", 1f, front + back, 0.03f)
        }
        assertEquals("whole on the front until then", 1f, PinBeam.front(PinBeam.ARRIVE_AT - 1).cardAlpha, 0.02f)
        assertEquals(0f, PinBeam.back(0).alpha, 0.001f)
        assertEquals("gone from the front", 0f, PinBeam.front(PinBeam.ARRIVE_AT + 180).cardAlpha, 0.001f)
        assertEquals("whole on the back", 1f, PinBeam.back(180).alpha, 0.001f)
    }

    @Test fun `the hand-off - the back shows the card at the very angle the front has it at, from the other side, and finishes its turn`() {
        for (ms in 0L..PinBeam.ARRIVE_MS step 5) {
            val front = PinBeam.front(PinBeam.ARRIVE_AT + ms).cardTurn
            assertEquals("at $ms ms", -(180f - front), PinBeam.back(ms).turn, 0.01f)
        }
        assertTrue("it comes turned well away: ${PinBeam.back(0).turn}", PinBeam.back(0).turn < -40f)
        assertEquals("the front's turn ends 300 ms into the back's arrival", 180f, PinBeam.front(PinBeam.ARRIVE_AT + 300).cardTurn, 0.01f)
        assertEquals(0f, PinBeam.back(300).turn, 0.01f)
    }

    @Test fun `the hand-off - the back's arrival begins while the front's card is leaving, small, and the back is told before`() {
        assertTrue(PinBeam.ARRIVE_AT > PinBeam.LEAVE_AT && PinBeam.ARRIVE_AT < PinBeam.FRONT_MS)
        assertTrue("small on the front as it hands over: ${PinBeam.front(PinBeam.ARRIVE_AT).cardScale}", PinBeam.front(PinBeam.ARRIVE_AT).cardScale < 0.45f)
        assertEquals("small on the back as it comes", 0.06f, PinBeam.back(0).scale, 0.001f)
        assertTrue("told after the card has begun to leave", PinBeam.ARRIVE_AT - dev.kb896.aiusage.ui.PinActivity.TELL_LEAD_MS > PinBeam.LEAVE_AT)
    }

    @Test fun `landed, the card is the whole back, flat, square-cornered - the notes page - and its light dies away`() {
        val b = PinBeam.back(PinBeam.ARRIVE_MS)
        assertEquals(1f, b.scale, 0.001f); assertEquals(0f, b.turn, 0.001f); assertEquals(0f, b.corner, 0.001f); assertEquals(1f, b.alpha, 0.001f)
        assertEquals(1f, b.glow, 0.001f)
        assertEquals(0f, PinBeam.back(PinBeam.BACK_MS).glow, 0.001f)
        assertEquals("and stays so", b.copy(glow = 0f), PinBeam.back(PinBeam.BACK_MS + 5_000))
        var last = 0f
        for (ms in 0L..PinBeam.ARRIVE_MS step 4) { val s = PinBeam.back(ms).scale; assertTrue("it only grows", s >= last - 0.0001f); last = s }
    }

    @Test fun `the front - Xiaomi's own motion, at its own times`() {
        // What is behind blurs and the words lift off, larger than life.
        assertEquals(0f, PinBeam.front(0).blur, 0.001f)
        assertTrue(PinBeam.front(250).blur > 0.8f)
        assertEquals(1.2f, PinBeam.front(0).noteScale, 0.001f)
        assertEquals(1.05f, PinBeam.front(PinBeam.CARD_AT + 600).noteScale, 0.02f)
        assertEquals(0f, PinBeam.front(0).noteAlpha, 0.001f)
        assertTrue(PinBeam.front(200).noteAlpha > 0.9f)
        // The card comes down from the top edge, from its back to its face, to where the back screen sits.
        val start = PinBeam.front(PinBeam.CARD_AT)
        assertEquals(PinBeam.TOP_Y, start.cardY, 0.001f); assertEquals(-180f, start.cardTurn, 0.01f); assertEquals(0.06f, start.cardScale, 0.001f)
        val there = PinBeam.front(PinBeam.CARD_AT + 1000)
        assertEquals(0f, there.cardTurn, 0.01f); assertEquals(PinBeam.CARD_Y, there.cardY, 0.001f); assertEquals(PinBeam.CARD_X, there.cardX, 0.001f)
        assertEquals(0.68f, PinBeam.front(PinBeam.CARD_AT + 900).cardScale, 0.005f)
        // The words fly up into it; as they land (0.85 s) the card gives a little, takes them as its face, and its edge lights.
        assertEquals(0.6f, PinBeam.front(PinBeam.CARD_AT + 1080).cardScale, 0.005f)
        assertEquals(0.68f, PinBeam.front(PinBeam.LEAVE_AT).cardScale, 0.02f)
        assertEquals(0f, PinBeam.front(PinBeam.CARD_AT + 850).face, 0.001f)
        assertTrue(PinBeam.front(PinBeam.CARD_AT + 850).noteAlpha > 0.98f)
        assertEquals(0f, PinBeam.front(PinBeam.CARD_AT + 1050).noteAlpha, 0.001f)
        assertTrue(PinBeam.front(PinBeam.CARD_AT + 1050).face > 0.4f)
        assertEquals("the words are where the card is when they become its face", PinBeam.front(PinBeam.CARD_AT + 930).cardY, PinBeam.front(PinBeam.CARD_AT + 930).noteY, 0.002f)
        assertTrue(PinBeam.front(PinBeam.LEAVE_AT).glow > 0.8f)
        // It turns away, shrinks and goes into the top edge.
        val gone = PinBeam.front(PinBeam.LEAVE_AT + 680)
        assertEquals(180f, gone.cardTurn, 0.01f); assertEquals(PinBeam.TOP_Y, gone.cardY, 0.001f); assertEquals(0.06f, gone.cardScale, 0.001f)
    }

    @Test fun `the front - nothing of ours is left on it at the end, and nothing but the page behind before the start`() {
        val end = PinBeam.front(PinBeam.FRONT_MS)
        assertEquals(0f, end.cardAlpha, 0.001f); assertEquals(0f, end.noteAlpha, 0.001f)
        assertTrue("what is behind is clear again: ${end.blur}", end.blur < 0.03f)
        val begin = PinBeam.front(0)
        assertEquals(0f, begin.cardAlpha, 0.001f); assertEquals(0f, begin.noteAlpha, 0.001f); assertEquals(0f, begin.blur, 0.001f)
    }

    @Test fun `one movement - nothing jumps from one frame to the next, on either screen`() {
        var f = PinBeam.front(0)
        for (ms in 8L..PinBeam.FRONT_MS + 200 step 8) {
            val n = PinBeam.front(ms)
            val at = "front at $ms ms"
            assertTrue("$at: blur", abs(n.blur - f.blur) < 0.12f)
            assertTrue("$at: the words' place", abs(n.noteX - f.noteX) < 0.01f && abs(n.noteY - f.noteY) < 0.02f)
            assertTrue("$at: the words' size and light", abs(n.noteScale - f.noteScale) < 0.06f && abs(n.noteAlpha - f.noteAlpha) < 0.2f)   // Xiaomi's own: 1.05 to 0.65 in 200 ms as it lands
            assertTrue("$at: the card's place", abs(n.cardX - f.cardX) < 0.01f && abs(n.cardY - f.cardY) < 0.012f)
            assertTrue("$at: the card's size ${f.cardScale} -> ${n.cardScale}", abs(n.cardScale - f.cardScale) < 0.03f)
            assertTrue("$at: the card's turn ${f.cardTurn} -> ${n.cardTurn}", abs(n.cardTurn - f.cardTurn) < 6f)
            assertTrue("$at: the card's light", abs(n.cardAlpha - f.cardAlpha) < 0.2f && abs(n.glow - f.glow) < 0.12f && abs(n.face - f.face) < 0.14f)
            f = n
        }
        var b = PinBeam.back(0)
        for (ms in 8L..PinBeam.BACK_MS + 200 step 8) {
            val n = PinBeam.back(ms)
            val at = "back at $ms ms"
            assertTrue("$at: size ${b.scale} -> ${n.scale}", abs(n.scale - b.scale) < 0.045f)
            assertTrue("$at: turn", abs(n.turn - b.turn) < 6f)
            assertTrue("$at: light", abs(n.alpha - b.alpha) < 0.12f && abs(n.glow - b.glow) < 0.06f && abs(n.shade - b.shade) < 0.06f)
            assertTrue("$at: corners", abs(n.corner - b.corner) < 0.02f)
            b = n
        }
    }

    @Test fun `from where the words are - the sheet starts at the caller's rectangle and is on its own way within the lift`() {
        assertEquals("at the words' own place as the pin begins", 0f, PinBeam.joined(0), 0.0001f)
        assertEquals("on its way by the time the lift is over", 1f, PinBeam.joined(PinBeam.JOIN_MS), 0.0001f)
        assertEquals(1f, PinBeam.joined(5_000), 0.0001f)
        assertTrue("joined before the card comes for it", PinBeam.JOIN_MS <= PinBeam.CARD_AT + 150)
        var last = 0f
        for (ms in 0L..PinBeam.JOIN_MS step 4) { val k = PinBeam.joined(ms); assertTrue(k >= last); assertTrue("no jump", k - last < 0.06f); last = k }
    }

    @Test fun `the easings are what their names say`() {
        for (e in listOf(PinBeam::linear, PinBeam::cubicOut, PinBeam::cubicInOut, PinBeam::quartInOut, PinBeam::sineOut, PinBeam::sineInOut)) {
            assertEquals(0f, e(0f), 0.0001f); assertEquals(1f, e(1f), 0.0001f)
            var last = 0f
            for (i in 1..100) { val v = e(i / 100f); assertTrue(v >= last - 0.0001f); last = v }
        }
        assertEquals(0.5f, PinBeam.cubicInOut(0.5f), 0.0001f); assertEquals(0.5f, PinBeam.quartInOut(0.5f), 0.0001f); assertEquals(0.5f, PinBeam.sineInOut(0.5f), 0.0001f)
        // A spring: let go at 0, there (within a hundredth) after a few responses, hardly past it when well damped.
        assertEquals(0f, PinBeam.spring(0.95f, 0.3f, 0f), 0.0001f)
        assertEquals(1f, PinBeam.spring(0.95f, 0.3f, 900f), 0.01f)
        var most = 0f
        for (ms in 0..2000 step 4) most = maxOf(most, PinBeam.spring(0.95f, 0.3f, ms.toFloat()))
        assertTrue("overshoot $most", most < 1.01f)
        assertEquals(1f, PinBeam.spring(1f, 0.25f, 900f), 0.01f)
    }
}
