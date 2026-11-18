package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow

/**
 * THE DUST'S FRAGMENT SHADERS DO LESS AND GIVE THE SAME (DustField.HAZE_FS, DRAW_FS, CLOCK_FS): the haze never leaves
 * sRGB's linear toe, so its toSrgb and toLinear are their straight lines; the shoulder is taken only where a channel
 * reaches it; dimmed, a fragment in the toe takes the toe's lines; whether to encode is known when they are linked.
 * Each shortcut is held here to the very sums the old shader made (GLSL's mix and step, in float), bit for bit.
 *
 * WHAT THIS DOES NOT PROVE: these sums are the JVM's IEEE floats, one operation at a time. A GPU's compiler may fuse or
 * reorder them (a multiply-add, `12.92 * uFrag.y` hoisted) differently for the old and the new shape of the dim path,
 * which could move a rare faint channel by one level of 255. The pictures were found byte-identical on the PC's GPU only
 * (the GL check, src/test/gl/dust-gl.c, run once beside a copy of the old shaders, both encodings); the GL check in
 * the repo draws ENCODE 0 alone (DustGlCheckTest writes the shaders as they are). Not compared on the phone's GPU.
 */
class DustShaderToeTest {
    // GLSL's own definitions, in float.
    private fun mix(x: Float, y: Float, a: Float) = x * (1f - a) + y * a
    private fun step(edge: Float, x: Float) = if (x < edge) 0f else 1f
    private fun toSrgb(v: Float): Float { val c = v.coerceIn(0f, 1f); return mix(c * 12.92f, 1.055f * c.pow(1f / 2.4f) - 0.055f, step(0.0031308f, c)) }
    private fun toLinear(v: Float): Float { val s = v.coerceIn(0f, 1f); return mix(s / 12.92f, ((s + 0.055f) / 1.055f).pow(2.4f), step(0.04045f, s)) }
    private fun shoulder(c: Float) = mix(c, 0.8f + 0.2f * (1f - exp(-(c - 0.8f) / 0.2f)), step(0.8f, c))
    // The shaders' toe lines (DustField's TOE): the curves with their pow made a plain product.
    private fun toeSrgb(v: Float): Float { val c = v.coerceIn(0f, 1f); return mix(c * 12.92f, c * c, step(0.0031308f, c)) }
    private fun toeLinear(v: Float): Float { val s = v.coerceIn(0f, 1f); return mix(s / 12.92f, s * s, step(0.04045f, s)) }

    private val gains = floatArrayOf(0f, 1e-4f, 0.01f, 0.1f, 0.25f, 0.42f, 0.5f, 0.75f, 0.9f, 0.998f, 0.999f, 1f)

    @Test fun `the haze never leaves sRGB's linear toe at any gain up to 1`() {
        val brightest = DustField.HAZE * DustField.HAZE_BLOOM * DustField.NEAR_COL.max()
        // In linear light, under toSrgb's knee where the beam is full.
        assertTrue("$brightest", brightest <= 0.0031308f)
        // In sRGB at the most gain, with the most dither: under toLinear's knee.
        assertTrue(12.92f * brightest + 0.5f / 255f < 0.04045f)
        // And never under 0: the dither (half a level either way) only where the green is a level or more, and no
        // channel is under half the green.
        assertTrue(DustField.NEAR_COL.min() / DustField.NEAR_COL[1] - 0.5f > 0f)
    }

    @Test fun `the haze's straight lines are the very sums the curves made - bit for bit, every beam, gain and dither`() {
        val dithers = floatArrayOf(0f, 0.1f, 0.25f, 0.4999f, 0.5f, 0.75f, 0.999999f)
        var n = 0
        for (b in 0..4096) {
            val beam = b / 4096f
            for (g in gains) for (h in dithers) {
                val c = FloatArray(3) { DustField.NEAR_COL[it] * (DustField.HAZE * DustField.HAZE_BLOOM * beam) }
                val old = FloatArray(3) { toSrgb(c[it]) * g }
                val new = FloatArray(3) { toeSrgb(c[it]) * g }
                val ditherOld = (h - 0.5f) / 255f * step(1f / 255f, old[1])
                val ditherNew = (h - 0.5f) / 255f * step(1f / 255f, new[1])
                for (k in 0..2) {
                    val so = old[k] + ditherOld; val sn = new[k] + ditherNew
                    assertEquals(so.toRawBits(), sn.toRawBits())                         // encoded by the shader
                    assertEquals(toLinear(so).toRawBits(), toeLinear(sn).toRawBits())     // by an sRGB surface
                    n++
                }
            }
        }
        println("dust haze: $n channels the same, bit for bit")
    }

    @Test fun `the shoulder changes nothing under 0_8, and dimmed the toe's lines are the curves' sums`() {
        // Every float from 0 up to the shoulder, sampled finely: the shoulder gives the channel back as it was.
        var c = 0f
        while (c < 0.8f) { assertEquals(c.toRawBits(), shoulder(c).toRawBits()); c = if (c < 1e-6f) c + 1e-9f else c * 1.0001f }
        assertTrue(shoulder(0.9f) < 0.9f)
        // Dimmed (a gain under 0.999), a channel in the toe: the shortcut is the curves' sums.
        var v = 0f; var n = 0
        while (v < 0.0031308f) {
            for (g in gains) {
                if (g >= 0.999f) continue
                assertEquals(toLinear(g * toSrgb(v)).toRawBits(), toeLinear(g * toeSrgb(v)).toRawBits())
                n++
            }
            v = if (v < 1e-7f) v + 1e-10f else Math.nextUp(v * 1.0002f)
        }
        println("dust motes dimmed in the toe: $n channels the same, bit for bit")
    }

    @Test fun `the shaders take the shortcuts only where they hold, and know when linked whether to encode`() {
        val haze = DustField.HAZE_FS
        assertFalse(haze.contains("toSrgb(") || haze.contains("toLinear("))
        assertFalse(haze.contains("pow("))
        assertTrue(haze.contains("vec3 s = toeSrgb(NEAR_COL * (HAZE * HAZE_BLOOM * beam)) * uFrag.y;"))
        assertTrue(haze.contains("o = vec4(toeLinear(s), 1.0);"))
        // The toe's lines are the curves' own (clamp, mix, step), their pow made a plain product.
        assertTrue(haze.contains("vec3 toeSrgb(vec3 c) { c = clamp(c, 0.0, 1.0); return mix(c * 12.92, c * c, step(vec3(0.0031308), c)); }"))
        assertTrue(haze.contains("vec3 toeLinear(vec3 s) { s = clamp(s, 0.0, 1.0); return mix(s / 12.92, s * s, step(vec3(0.04045), s)); }"))
        val draw = DustField.DRAW_FS
        assertTrue(draw.contains("if (max(lin.r, max(lin.g, lin.b)) >= 0.8) lin = shoulder(lin);"))
        assertTrue(draw.contains("if (max(lin.r, max(lin.g, lin.b)) < 0.0031308) lin = toeLinear(uFrag.y * toeSrgb(lin));"))
        assertTrue(draw.contains("else lin = toLinear(uFrag.y * toSrgb(lin));"))
        for (fs in listOf(DustField.DRAW_FS, DustField.HAZE_FS, DustField.CLOCK_FS)) {
            assertFalse(fs.contains("uFrag.z > 0.5") || fs.contains("uFrag.w > 0.5"))
            assertEquals(1, Regex("#define ENCODE 0\n").findAll(fs).count())
            assertTrue(fs.startsWith("#version 300 es\n"))
            val on = DustField.encoding(fs, true)
            assertTrue(on.contains("#define ENCODE 1\n") && !on.contains("#define ENCODE 0"))
            assertEquals(fs, DustField.encoding(fs, false))
        }
    }
}
