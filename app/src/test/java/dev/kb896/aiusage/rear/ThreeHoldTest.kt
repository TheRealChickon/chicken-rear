package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeHoldTest {
    // The 17 Pro's rear touchscreen: 0..90399 x 0..57199, so raw = panel units x 100.
    private fun hold() = ThreeHold(90399, 57199)

    private fun hex(v: Int) = "%08x".format(v)
    private val syn = "0000 0000 00000000"
    private fun slot(n: Int) = "0003 002f ${hex(n)}"
    private fun land(s: Int, id: Int, x: Int, y: Int) = listOf(slot(s), "0003 0039 ${hex(id)}", "0003 0035 ${hex(x * 100)}", "0003 0036 ${hex(y * 100)}", syn)
    private fun moveTo(s: Int, x: Int, y: Int) = listOf(slot(s), "0003 0035 ${hex(x * 100)}", "0003 0036 ${hex(y * 100)}", syn)
    private fun lift(s: Int) = listOf(slot(s), "0003 0039 ffffffff", syn)
    private fun palm(s: Int, id: Int, x: Int, y: Int) = listOf(slot(s), "0003 0039 ${hex(id)}", "0003 0037 00000002", "0003 0035 ${hex(x * 100)}", "0003 0036 ${hex(y * 100)}", syn)
    /** A landing as the rear panel sends one: its size (TOUCH_MAJOR, TOUCH_MINOR), and no tool type. */
    private fun sized(s: Int, id: Int, x: Int, y: Int, major: Int, minor: Int = major) =
        listOf(slot(s), "0003 0039 ${hex(id)}", "0003 0030 ${hex(major)}", "0003 0031 ${hex(minor)}", "0003 0035 ${hex(x * 100)}", "0003 0036 ${hex(y * 100)}", syn)
    private fun grow(s: Int, major: Int) = listOf(slot(s), "0003 0030 ${hex(major)}", syn)

    private fun ThreeHold.run(lines: List<String>, at: Long): List<ThreeHold.Out> = lines.mapNotNull { feed(it, at) }

    /** Three fingers side by side, landing at [t0], [t0] + [gap], [t0] + 2 [gap], in slots [s0] on. */
    private fun ThreeHold.three(t0: Long, gap: Long = 60L, s0: Int = 0, id0: Int = 10): List<ThreeHold.Out> =
        run(land(s0, id0, 450, 300), t0) + run(land(s0 + 1, id0 + 1, 620, 280), t0 + gap) + run(land(s0 + 2, id0 + 2, 790, 300), t0 + 2 * gap)

    @Test fun `three fingers landing together and held fire once, 500 ms after the third landed`() {
        val h = hold()
        val out = h.three(1_000L)
        // The hold is under way from the third's landing: complete at 1120 + 500.
        assertEquals(listOf(ThreeHold.Out.Arm(1_620L)), out)
        assertNull("not before its time", h.due(1_619L))
        val fire = h.due(1_620L)!!
        assertEquals(3, fire.fingers)
        assertEquals(120L, fire.spreadMs)
        assertEquals(500L, fire.heldMs)
        assertEquals(450f, fire.x, 0.5f); assertEquals(300f, fire.y, 0.5f)
        // Once: asked again, or with the fingers still there and trembling, nothing more.
        assertNull(h.due(1_700L))
        assertTrue(h.run(moveTo(1, 622, 281), 2_000L).isEmpty())
        assertNull(h.due(3_000L))
    }

    @Test fun `a line that comes after the hold is complete fires it as well`() {
        val h = hold()
        h.three(0L)
        val out = h.run(moveTo(0, 451, 300), 700L)
        assertTrue("$out", out.single() is ThreeHold.Out.Fire)
        assertNull(h.due(800L))
    }

    @Test fun `two fingers are never three`() {
        val h = hold()
        assertTrue(h.run(land(0, 1, 450, 300), 0L).isEmpty())
        assertTrue(h.run(land(1, 2, 620, 300), 50L).isEmpty())
        assertNull(h.due(5_000L))
        assertEquals(2, h.fingersDown())
    }

    @Test fun `landings spread out in time are a hand shifting, not three fingers - 350 ms at most from the first to the third`() {
        val spread = hold()
        assertTrue(spread.three(0L, gap = 200L).isEmpty())
        assertNull(spread.due(10_000L))
        // Right at the edge it is still three together.
        val edge = hold()
        assertEquals(listOf(ThreeHold.Out.Arm(350L + 500L)), edge.three(0L, gap = 175L))
        assertTrue(edge.due(850L) != null)
        val past = hold()
        assertTrue(past.three(0L, gap = 176L).isEmpty())
        assertEquals(350L, ThreeHold.LAND_MS)
        assertEquals(500L, ThreeHold.HOLD_MS)
        assertEquals(40f, ThreeHold.SLOP)
    }

    @Test fun `a finger that slides more than 40 units is not held, and the hold is off`() {
        val h = hold()
        h.three(0L)
        // 45 units to the right: that finger is out, and with it the hold (nothing said: the check finds nothing).
        assertTrue(h.run(moveTo(1, 665, 280), 300L).isEmpty())
        assertNull(h.due(620L))
        // Back where it landed is not held either: it went.
        h.run(moveTo(1, 620, 280), 400L)
        assertNull(h.due(1_000L))
        // Within 40 (30 across, 20 down: 36) it is still held.
        val within = hold()
        within.three(0L)
        within.run(moveTo(2, 820, 320), 300L)
        assertTrue(within.due(620L) != null)
    }

    @Test fun `a grip finger down long before and two quick ones are not three fingers`() {
        // Decided: "together" is the three landing within 350 ms; the hand's resting finger never is.
        val h = hold()
        h.run(land(0, 1, 330, 500), 0L)
        h.run(land(1, 2, 600, 300), 3_000L)
        h.run(land(2, 3, 770, 300), 3_060L)
        assertNull(h.due(4_000L))
        assertNull(h.due(60_000L))
        // With the grip there, three new ones landing together are three: the grip is not one of them.
        val g = hold()
        g.run(land(0, 1, 330, 500), 0L)
        g.three(3_000L, s0 = 1)
        val fire = g.due(3_620L)!!
        assertEquals(3, fire.fingers)
    }

    @Test fun `it fires once per hold - not again while any finger of it is down, again after they all lifted`() {
        val h = hold()
        h.three(0L)
        assertTrue(h.due(620L) != null)
        // One lifts and lands again at once, beside the two still down: the hold is the same.
        h.run(lift(0), 900L)
        h.run(land(0, 20, 455, 302), 950L)
        h.run(land(3, 21, 300, 200), 980L)
        assertNull(h.due(2_000L))
        // All of them up.
        for (s in 0..3) h.run(lift(s), 2_100L)
        assertEquals(0, h.fingersDown())
        // The next hold fires.
        assertEquals(listOf(ThreeHold.Out.Arm(3_120L + 500L)), h.three(3_000L, id0 = 30))
        assertTrue(h.due(3_620L) != null)
    }

    @Test fun `a grip finger left resting does not keep the next hold out`() {
        val h = hold()
        h.run(land(0, 1, 330, 500), 0L)
        h.three(2_000L, s0 = 1)
        assertTrue(h.due(2_620L) != null)
        for (s in 1..3) h.run(lift(s), 3_000L)
        // The grip is still down: a new hold of three fires all the same.
        h.three(4_000L, s0 = 1, id0 = 40)
        assertTrue(h.due(4_620L) != null)
    }

    @Test fun `four fingers together fire once, as four`() {
        val h = hold()
        h.run(land(0, 1, 400, 300), 0L)
        h.run(land(1, 2, 520, 280), 50L)
        // The hold is armed by the third: 100 + 500.
        assertEquals(listOf(ThreeHold.Out.Arm(600L)), h.run(land(2, 3, 640, 280), 100L))
        // A fourth does not move it later.
        assertTrue(h.run(land(3, 4, 760, 300), 150L).isEmpty())
        val fire = h.due(600L)!!
        assertEquals(4, fire.fingers)
        assertNull(h.due(1_000L))
    }

    @Test fun `a contact a panel calls a palm is never a finger - though the rear panel never calls one so`() {
        // The rear panel reports no ABS_MT_TOOL_TYPE at all: this holds only for a panel that does.
        val h = hold()
        h.run(palm(0, 1, 300, 400), 0L)
        h.run(land(1, 2, 600, 300), 30L)
        h.run(land(2, 3, 770, 300), 60L)
        assertNull(h.due(1_000L))
    }

    @Test fun `on the rear panel's own lines a contact that fills the size axis is no fingertip`() {
        // Three landing together, one of them as wide as the axis goes (0..255): not three fingers.
        val h = hold()
        h.run(sized(0, 1, 300, 400, major = 255, minor = 200), 0L)
        h.run(sized(1, 2, 600, 300, major = 9), 30L)
        h.run(sized(2, 3, 770, 300, major = 8), 60L)
        assertNull(h.due(1_000L))
        // One that grows to it as it presses (a palm rolling down) is out from then on.
        val g = hold()
        g.run(sized(0, 1, 300, 400, major = 12), 0L)
        g.run(sized(1, 2, 600, 300, major = 9), 30L)
        g.run(sized(2, 3, 770, 300, major = 8), 60L)
        g.run(grow(0, 255), 200L)
        assertNull(g.due(1_000L))
        assertEquals(255, ThreeHold.WIDE)
    }

    @Test fun `fingertips of any size below it hold, and the widest of them is told for the log`() {
        val h = hold()
        h.run(sized(0, 1, 450, 300, major = 11, minor = 7), 0L)
        h.run(sized(1, 2, 620, 280, major = 14, minor = 9), 40L)
        h.run(sized(2, 3, 790, 300, major = 254, minor = 10), 80L)
        val fire = h.due(580L)!!
        assertEquals(3, fire.fingers)
        assertEquals(254, fire.widest)
        // A panel that sends no size: 0.
        val bare = hold()
        bare.three(0L)
        assertEquals(0, bare.due(620L)!!.widest)
    }

    @Test fun `a hold finger whose lift was lost does not keep every later hold out`() {
        val h = hold()
        h.three(0L)
        assertTrue(h.due(620L) != null)
        // Slot 2's -1 never comes; 0 and 1 lift.
        h.run(lift(0), 900L); h.run(lift(1), 900L)
        // The next touch puts a new contact in slot 2 (its old one has lifted after all), and the hold fires.
        assertEquals(listOf(ThreeHold.Out.Arm(2_620L)), h.three(2_000L, id0 = 20))
        assertTrue(h.due(2_620L) != null)
    }

    @Test fun `events dropped by the kernel forget every contact, and the next hold fires`() {
        val h = hold()
        h.three(0L)
        assertTrue(h.due(620L) != null)
        // getevent fell behind: SYN_DROPPED, and the lifts were among what was lost.
        assertTrue(h.feed("0000 0003 00000000", 900L) == null)
        assertEquals(0, h.fingersDown())
        // Three new fingers in other slots fire.
        assertEquals(listOf(ThreeHold.Out.Arm(2_620L)), h.three(2_000L, s0 = 3, id0 = 30))
        assertTrue(h.due(2_620L) != null)
    }

    @Test fun `three fingers tapped, or one lifted before the time, do not fire`() {
        val tap = hold()
        tap.three(0L)
        for (s in 0..2) tap.run(lift(s), 300L)
        assertNull(tap.due(620L))
        val one = hold()
        one.three(0L)
        one.run(lift(1), 500L)
        assertNull(one.due(620L))
    }

    @Test fun `lines with the device's name in front are read`() {
        val h = hold()
        val out = (land(0, 1, 450, 300) + land(1, 2, 620, 280) + land(2, 3, 790, 300)).mapNotNull { h.feed("/dev/input/event7: $it", 10L) }
        assertEquals(listOf(ThreeHold.Out.Arm(510L)), out)
        assertTrue(h.feed("add device 3: /dev/input/event7", 20L) == null)
    }
}
