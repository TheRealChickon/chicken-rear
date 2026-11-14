package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.RearLight.Doing
import dev.kb896.aiusage.service.AmbientLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The back's brightness by the light around it.
 */
class RearLightTest {
    @Test fun `the curve - very dim in the dark, moderate indoors, all of it by daylight, and never a step`() {
        assertEquals(0.10f, RearLight.share(0f), 0.001f)
        assertEquals(0.62f, RearLight.share(200f), 0.001f)
        assertEquals(1f, RearLight.share(1_500f), 0.001f)
        assertEquals(1f, RearLight.share(60_000f), 0f)
        var was = RearLight.share(0f)
        var lux = 0f
        while (lux < 3_000f) {
            lux = lux * 1.03f + 0.05f
            val now = RearLight.share(lux)
            assertTrue("rising at $lux", now >= was - 1e-5f)
            assertTrue("no step at $lux: ${now - was}", now - was < 0.02f)
            was = now
        }
    }

    @Test fun `what the back is doing multiplies the curve - face down the whole of it, a tap over the hand's, a dim by hand under it`() {
        for (lux in listOf(0f, 20f, 200f)) {
            val hand = RearLight.light(lux, Doing(3))
            assertTrue("in the hand a little under the curve at $lux", hand < RearLight.share(lux) && hand > RearLight.share(lux) * 0.8f)
            // LYING ON ITS FRONT THE BACK IS BRIGHT: all the room's light asks
            // for, more than in the hand; it was the dim posture (0.40) until then.
            assertEquals("face down: the whole curve at $lux", RearLight.share(lux), RearLight.light(lux, Doing(2)), 1e-5f)
            assertTrue("and over the hand's at $lux", RearLight.light(lux, Doing(2)) > hand)
            assertTrue("his own dim still dims it there at $lux", RearLight.light(lux, Doing(2, dimmedByHand = true)) < RearLight.light(lux, Doing(2)) * 0.5f)
            assertTrue("saving lower at $lux", RearLight.light(lux, Doing(1)) < hand * 0.5f)
            assertTrue("a tap is brighter at $lux", RearLight.light(lux, Doing(3, tapped = true)) > hand * 1.5f || RearLight.light(lux, Doing(3, tapped = true)) == 1f)
            assertTrue("a dim by hand is a step down at $lux", RearLight.light(lux, Doing(3, dimmedByHand = true)) < hand * 0.5f)
            assertEquals("the camera: all the curve gives at $lux", RearLight.share(lux), RearLight.light(lux, Doing(4)), 1e-5f)
        }
        // In a lit room, in the hand: what the back had before this (picture x0.74).
        assertEquals(0.74f, RearLight.gain(RearLight.light(200f, Doing(3))), 0.02f)
        // In the dark, in the hand: some 3 of the panel's 35 nits.
        assertEquals(3f, 35.2f * RearLight.light(0f, Doing(3)), 0.3f)
        // A tap in the dark is something to see.
        assertTrue(RearLight.light(0f, Doing(3, tapped = true)) >= RearLight.TAP_LEAST)
        // Never so dark that it cannot be found again; never over all of it.
        assertTrue(RearLight.light(0f, Doing(1, dimmedByHand = true), bias = -1f) >= RearLight.FLOOR)
        assertEquals(1f, RearLight.light(50_000f, Doing(4), bias = 1f), 0f)
    }

    @Test fun `a panel that is itself low in the dark is not cut to nothing - at least the dark end's nits, as far as it has them`() {
        // Xiaomi's 35 nits in the dark: the curve as drawn (the floor in nits is the curve's own dark end there).
        assertEquals(RearLight.light(0f, Doing(3)), RearLight.light(0f, Doing(3), panelNits = 35.2f), 1e-3f)
        // At night: the panel at 1 and at 2 nits in the same darkness. All of it, not a tenth.
        assertEquals(1f, RearLight.light(0f, Doing(3), panelNits = 1f), 0f)
        assertEquals(1f, RearLight.light(0f, Doing(3), panelNits = 2f), 0f)
        // Between: the same nits out, whatever the panel stands at.
        for (panel in listOf(5f, 10f, 20f, 35.2f)) assertEquals("at $panel nits", 3.5f * RearLight.IN_HAND, panel * RearLight.light(0f, Doing(3), panelNits = panel), 0.02f)
        // His hand still shows on a low panel: a double tap's dim is a step down, a tap is all of it.
        assertEquals(RearLight.HAND_DIM, RearLight.light(0f, Doing(3, dimmedByHand = true), panelNits = 1f), 1e-4f)
        assertEquals(1f, RearLight.light(0f, Doing(3, tapped = true), panelNits = 1f), 0f)
        // In the light the panel is high and the curve stands as it is.
        assertEquals(RearLight.light(200f, Doing(3)), RearLight.light(200f, Doing(3), panelNits = 180f), 0f)
        // Not known, or nonsense: the curve alone.
        assertEquals(RearLight.light(0f, Doing(3)), RearLight.light(0f, Doing(3), panelNits = Float.NaN), 0f)
        assertEquals(RearLight.light(0f, Doing(3)), RearLight.light(0f, Doing(3), panelNits = 0f), 0f)
    }

    @Test fun `the rear panel's nits by its own table`() {
        assertEquals(35.2f, RearPanel.nits(0.021757571f), 0.1f)
        assertEquals(2.0f, RearPanel.nits(9.914585E-4f), 0.01f)
        assertEquals(1f, RearPanel.nits(3.66256E-4f), 0f)
        assertEquals(600f, RearPanel.nits(0.37486264f), 0.01f)
        assertEquals(3_500f, RearPanel.nits(1f), 0f)
        assertTrue(RearPanel.nits(-1f).isNaN())
    }

    @Test fun `the slider moves all of it, and no reading is a lit room`() {
        assertEquals(RearLight.light(60f, Doing(3)) * 2.5f, RearLight.light(60f, Doing(3), bias = 1f), 1e-4f)
        assertEquals(RearLight.light(60f, Doing(3)) / 2.5f, RearLight.light(60f, Doing(3), bias = -1f), 1e-4f)
        assertEquals(RearLight.share(200f), RearLight.share(Float.NaN), 0f)
    }

    @Test fun `the picture's factor gives that share of the light`() {
        for (l in listOf(0.02f, 0.1f, 0.5f, 1f)) assertEquals(l, RearLight.lightOf(RearLight.gain(l)), 1e-4f)
    }

    @Test fun `a hand passing over the sensor is not followed - a lamp switched on is, and darkness that stays`() {
        val s = RearLight.Steady()
        s.feed(120f, 0L)
        assertEquals("the first reading, as it comes", 120f, s.at(10L), 0f)
        // A hand: dark for a second, then the room again.
        s.feed(1f, 5_000L)
        for (t in 5_000L..6_000L step 100L) assertEquals("at $t", 120f, s.at(t), 0f)
        s.feed(118f, 6_000L)
        for (t in 6_000L..12_000L step 500L) assertEquals("at $t", 120f, s.at(t), 0f)
        // The light goes out, and stays out: followed after two and a half seconds, not before.
        s.feed(0.5f, 20_000L)
        assertEquals(120f, s.at(22_000L), 0f)
        assertEquals(0.5f, s.at(22_600L), 0f)
        // A lamp is switched on: followed within a second.
        s.feed(90f, 30_000L)
        assertEquals(0.5f, s.at(30_500L), 0f)
        assertEquals(90f, s.at(30_900L), 0f)
        // Small wanderings of the same light are nothing.
        s.feed(100f, 40_000L); s.feed(84f, 41_000L)
        assertEquals(90f, s.at(50_000L), 0f)
        // After the back was dark, the next reading is taken as it comes; until it comes, the last one.
        s.fresh()
        assertEquals(84f, s.at(60_000L), 0f)
        s.feed(3f, 60_100L)
        assertEquals(3f, s.at(60_100L), 0f)
    }

    @Test fun `flickering between dark and light is never followed - no pumping`() {
        val s = RearLight.Steady()
        s.feed(100f, 0L)
        var changes = 0; var was = s.at(0L)
        for (t in 0L..30_000L step 100L) {
            if (t % 1_400L == 0L) s.feed(if ((t / 1_400L) % 2L == 0L) 2f else 100f, t)
            val now = s.at(t)
            if (now != was) changes++
            was = now
        }
        assertEquals("the lux in force never moved", 0, changes)
    }

    @Test fun `every change is eased - from where it is, never a jump`() {
        val e = RearLight.Ease(0.74f)
        e.send(0.30f, 1_000L, RearLight.EASE_MS)
        var was = e.at(1_000L)
        assertEquals(0.74f, was, 0f)
        for (t in 1_000L..1_000L + RearLight.EASE_MS step 16L) {
            val now = e.at(t)
            assertTrue("falling at $t", now <= was + 1e-6f)
            assertTrue("a little at a time at $t: ${was - now}", was - now < 0.012f)
            was = now
        }
        assertEquals(0.30f, e.at(1_000L + RearLight.EASE_MS), 1e-4f)
        assertFalse(e.moving(1_000L + RearLight.EASE_MS + 1L))
        // Sent elsewhere half way: on from where it is.
        e.send(0.9f, 5_000L, 1_000L); val mid = e.at(5_500L)
        e.send(0.2f, 5_500L, 1_000L)
        assertEquals(mid, e.at(5_500L), 1e-5f)
    }

    @Test fun `the rear sensor is the one - the front one only when the rear one is covered or missing`() {
        assertEquals(40f to AmbientLight.REAR, AmbientLight.pick(40f, 300f))
        assertEquals("face down on a table: the front sees the table, the rear the room", 150f to AmbientLight.REAR, AmbientLight.pick(150f, 0f))
        assertEquals("a dark room is not a covered sensor", 0.4f to AmbientLight.REAR, AmbientLight.pick(0.4f, 3f))
        assertEquals("a finger on it, in a lit room: half the front's", 90f to AmbientLight.FRONT, AmbientLight.pick(0.5f, 180f))
        assertEquals(60f to AmbientLight.FRONT, AmbientLight.pick(Float.NaN, 60f))
        assertEquals(AmbientLight.NONE, AmbientLight.pick(Float.NaN, Float.NaN).second)
        assertTrue(AmbientLight.isRear("xiaomi.sensor.back_lux", "tcs3760_sec_back Ambient Light Back Sensor Non-wakeup"))
        assertFalse("the raw stream is not it", AmbientLight.isRear("xiaomi.sensor.ambientlightback.raw", "TCS3760_SEC_BACK ALSPRX Ambient Light Back Sensor Raw Data Non-wakeup"))
        assertFalse(AmbientLight.isRear("xiaomi.sensor.back_cct", "tcs3760_sec_back Cct Back Sensor Non-wakeup"))
        assertFalse(AmbientLight.isRear("android.sensor.light", "tcs3760 Ambient Light Sensor Non-wakeup"))
        assertFalse("a change too small to tell", AmbientLight.worthTelling(100f, 96f))
        assertTrue(AmbientLight.worthTelling(100f, 60f))
        assertTrue(AmbientLight.worthTelling(0f, 2f))
    }

    @Test fun `a back he lit himself while the rule has it off is as in the hand, not at full strength`() {
        for (lux in listOf(0f, 20f, 200f)) assertEquals(RearLight.light(lux, Doing(3)), RearLight.light(lux, Doing(0)), 1e-6f)
        assertEquals(RearLight.posture(Doing(3)), RearLight.posture(Doing(0)), 0f)
    }
}
