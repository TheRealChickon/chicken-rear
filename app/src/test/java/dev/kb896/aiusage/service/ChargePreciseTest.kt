package dev.kb896.aiusage.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The charging page's hundredths: only from what is known, and never ahead of the battery's own whole percent. */
class ChargePreciseTest {
    @Test fun `nothing past the whole percent until the battery has stepped once`() {
        val p = ChargePrecise()
        assertTrue(p.level(71, 1_000L).fraction < 0f)
        assertTrue("the same reading again", p.level(71, 5_000L).fraction < 0f)
    }

    @Test fun `a step is the moment of point nought nought, and the last percent's time is the pace`() {
        val p = ChargePrecise()
        p.level(71, 0L)
        val first = p.level(72, 60_000L)
        assertEquals(0f, first.fraction, 0f); assertEquals(60_000L, first.at)
        assertEquals("the pace is not known from one step", 0f, first.ratePerSec, 0f)
        val second = p.level(73, 180_000L)
        assertEquals(73, second.level); assertEquals(0f, second.fraction, 0f)
        assertEquals("a percent took two minutes: a hundredth every 1.2 s", 1f / 120f, second.ratePerSec, 1e-6f)
    }

    @Test fun `a jump or a drop forgets what was known`() {
        val p = ChargePrecise()
        p.level(71, 0L); p.level(72, 60_000L)
        assertTrue(p.level(75, 61_000L).fraction < 0f)
        p.reset()
        assertTrue(p.level(75, 62_000L).fraction < 0f)
    }

    @Test fun `a level that wavers at a whole percent - 93, 92, 93, 92 within a minute, as seen on the phone - shows the reported whole percent and keeps its measured pace`() {
        val p = ChargePrecise()
        p.level(91, 0L); p.level(92, 500_000L)
        val up = p.level(93, 1_176_000L)                       // the percent took 676 s: 0.148 hundredths a second
        assertEquals(0f, up.fraction, 0f); assertEquals(1000f / 676_000f, up.ratePerSec, 1e-7f)
        val back = p.level(92, 1_238_000L)                     // 62 s later it says 92 again
        assertEquals("shows the actual lower percent immediately", 92, back.level)
        assertTrue(back.fraction < 0f); assertEquals("standing", 0f, back.ratePerSec, 0f)
        assertEquals(92, p.detail(1_250_000L).level)
        val again = p.level(93, 1_258_000L)                    // and 93 again 20 s after that
        assertEquals(93, again.level); assertEquals(0f, again.fraction, 0f)
        assertEquals("its pace is the last true percent's, not 'a percent in twenty seconds'", 1000f / 676_000f, again.ratePerSec, 1e-7f)
        p.level(92, 1_279_000L); p.level(93, 1_300_000L)
        assertEquals(1000f / 676_000f, p.detail(1_300_000L).ratePerSec, 1e-7f)
        // On from there as ever: the next true step measures from the last time it reached 93.
        val next = p.level(94, 1_900_000L)
        assertEquals(1000f / 600_000f, next.ratePerSec, 1e-7f)
    }

    @Test fun `two percent down, or a drop where no step was seen, is no waver - nothing is known past it`() {
        val p = ChargePrecise()
        p.level(71, 0L)
        assertTrue("a drop with no step before it", p.level(70, 10_000L).fraction < 0f)
        p.level(71, 20_000L); p.level(72, 80_000L)
        assertEquals("under the line once: the actual percent", 71, p.level(71, 90_000L).level)
        assertTrue("and under it once more: really falling, as the battery says", p.level(70, 100_000L).let { it.level == 70 && it.fraction < 0f })
    }

    // ---- ON THE CHARGER BUT NOT FILLING ----

    @Test fun `a charge limit reached at 98 - the phone says not charging - the figure stands whole and nothing counts up`() {
        val p = ChargePrecise()
        p.filling(true, 0L); p.level(96, 0L); p.level(97, 300_000L); p.level(98, 600_000L)
        assertEquals("filling: the hundredths run", 1000f / 300_000f, p.detail(600_000L).ratePerSec, 1e-7f)
        val held = p.filling(false, 660_000L)
        assertTrue(held.holding); assertEquals(98, held.level); assertTrue("no hundredths", held.fraction < 0f); assertEquals(0f, held.ratePerSec, 0f)
        for (at in listOf(700_000L, 3_600_000L, 36_000_000L)) { val d = p.level(98, at); assertTrue("still at $at", d.holding && d.fraction < 0f && d.ratePerSec == 0f && d.level == 98) }
    }

    @Test fun `full at 100 stands`() {
        val p = ChargePrecise()
        p.filling(true, 0L); p.level(99, 0L); p.level(100, 200_000L)
        val full = p.filling(false, 201_000L)
        assertTrue(full.holding); assertEquals(100, full.level); assertEquals(0f, full.ratePerSec, 0f)
    }

    @Test fun `the charger in but paused, and going on again - it picks up from what is known, with no jump`() {
        val p = ChargePrecise()
        p.filling(true, 0L); p.level(60, 0L); p.level(61, 120_000L); p.level(62, 240_000L)
        assertTrue(p.filling(false, 300_000L).holding)
        val on = p.filling(true, 900_000L)
        assertFalse(on.holding)
        assertTrue("nothing is known past the whole percent until it next steps: no figure jumps to .99 or back to .00", on.fraction < 0f)
        assertEquals(62, on.level)
        val step = p.level(63, 1_000_000L)
        assertEquals(0f, step.fraction, 0f); assertEquals("and the pace from the next percent on, not across the pause", 0f, step.ratePerSec, 0f)
        assertEquals(1000f / 100_000f, p.level(64, 1_100_000L).ratePerSec, 1e-7f)
    }

    @Test fun `it says it charges but the level does not come - a limit that still reports charging - and it holds too`() {
        val p = ChargePrecise()
        p.filling(true, 0L); p.level(96, 0L); p.level(97, 300_000L); p.level(98, 600_000L)   // five minutes a percent
        assertFalse("within twice its pace and a minute it is only slow", p.detail(600_000L + 600_000L + 59_000L).holding)
        val d = p.detail(600_000L + 600_000L + 61_000L)
        assertTrue(d.holding); assertTrue(d.fraction < 0f); assertEquals(0f, d.ratePerSec, 0f)
        // And should it step after all, it is filling again from that moment.
        val on = p.level(99, 2_400_000L)
        assertFalse(on.holding); assertEquals(0f, on.fraction, 0f)
    }

    @Test fun `unplugged, everything known belongs to another charge`() {
        val p = ChargePrecise()
        p.filling(true, 0L); p.level(96, 0L); p.level(97, 300_000L); p.filling(false, 310_000L)
        p.reset()
        val d = p.level(97, 400_000L)
        assertFalse(d.holding); assertTrue(d.fraction < 0f)
    }

    @Test fun `Xiaomi's own hundredths are used as they come, until the next whole percent`() {
        val p = ChargePrecise()
        p.level(71, 0L)
        val d = p.xiaomi(35, 20, 10_000L)
        assertEquals(0.35f, d.fraction, 1e-6f); assertEquals(10_000L, d.at)
        assertEquals("twenty hundredths in ten seconds", 0.02f, d.ratePerSec, 1e-6f)
        assertEquals("the next whole percent starts from nought", 0f, p.level(72, 40_000L).fraction, 0f)
    }

    @Test fun `Xiaomi hundredths cannot keep a whole percent through an unknown or another session`() {
        val p = ChargePrecise()
        p.level(84, 1_000L)
        assertEquals(84, p.xiaomi(60, 20, 2_000L).level)
        assertEquals(ChargePrecise.UNKNOWN, p.level(-1, 3_000L).level)
        assertEquals(ChargePrecise.UNKNOWN, p.xiaomi(60, 20, 4_000L).level)
        val fresh = p.level(50, 5_000L)
        assertEquals(50, fresh.level)
        assertTrue(fresh.fraction < 0f)
        assertEquals(50, p.xiaomi(70, 20, 6_000L).level)
    }

    @Test fun `the charger in words`() {
        assertEquals("", ChargePrecise.kind(0, 0))
        assertEquals("Fast charging", ChargePrecise.kind(1, 0))
        assertEquals("Super fast charging · 90 W", ChargePrecise.kind(3, 90))
    }
}
