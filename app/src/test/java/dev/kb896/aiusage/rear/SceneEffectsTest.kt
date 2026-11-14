package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.Condition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The moon face's weather and glitch: the Earth is never displaced, lightning only in a storm, the glitch rare, short and weak, and off means off. */
class SceneEffectsTest {
    @Test fun `the Earth is never displaced, whatever the weather`() {
        for (w in Condition.values().toList() + null) {
            val fx = SceneEffects { 0.5f }
            for (t in 0L until 30_000L step 40L) {
                val v = fx.at(1_000L + t, w, on = true)
                assertEquals("$w", 0f, v[0], 0f); assertEquals("$w", 0f, v[1], 0f)
            }
        }
    }

    @Test fun `the glitch is a short twitch, seldom, and never strong`() {
        val fx = SceneEffects { 0.5f }
        var on = 0; var bursts = 0; var was = false; var strongest = 0f
        for (t in 0L until 120_000L step 20L) {
            val g = fx.at(1_000L + t, Condition.PARTLY, on = true)[3]
            if (g > 0f) { on++; strongest = maxOf(strongest, g) }
            if (g > 0f && !was) bursts++
            was = g > 0f
        }
        assertTrue("some in two minutes: $bursts", bursts in 4..14)
        assertTrue("on screen under 3% of the time: ${on * 20} ms", on * 20L < 3_600L)
        assertTrue(strongest <= SceneEffects.GLITCH_STRENGTH + 1e-6f)
    }

    @Test fun `held still, on another clip, or switched off, nothing moves`() {
        val fx = SceneEffects { 0.5f }
        for (t in 0L until 60_000L step 50L) for (v in fx.at(t, Condition.THUNDER, on = false)) assertEquals(0f, v, 0f)
        val quiet = SceneEffects { 0.5f }
        for (t in 0L until 60_000L step 50L) {
            val v = quiet.at(1_000L + t, Condition.THUNDER, on = true, clouds = false, glitch = false)
            assertEquals("no stir", 0f, v[1], 0f); assertEquals("no lightning", 0f, v[2], 0f); assertEquals("no glitch", 0f, v[3], 0f)
        }
    }

    @Test fun `only a thunderstorm has lightning`() {
        val rain = SceneEffects { 0.3f }; val storm = SceneEffects { 0.3f }
        var rainFlash = 0f; var stormFlash = 0f
        for (t in 0L until 60_000L step 20L) {
            rainFlash = maxOf(rainFlash, rain.at(1_000L + t, Condition.RAIN, on = true)[2])
            stormFlash = maxOf(stormFlash, storm.at(1_000L + t, Condition.THUNDER, on = true)[2])
        }
        assertEquals(0f, rainFlash, 0f)
        assertTrue(stormFlash > 0.3f)
    }
}
