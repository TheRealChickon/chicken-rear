package dev.kb896.aiusage.rear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class DustFieldTest {
    /** What the draft measured (its face.py, 976 x 596, sRGB levels of the green channel, no dither). */
    private object Draft {
        // At rest (t = 0): the mean level in the beam (where it is over 0.9) and out of it, the 99.9th percentile, the
        // pixels over 40 and over 100.
        const val IN_BEAM = 12.39; const val OUT_BEAM = 3.01; const val P999 = 110.0
        const val OVER_40 = 10594; const val OVER_100 = 963
        // Its current over time (t = 0 to 158 s, every 2 s, at its motes' first places; the noise's period is 160 s): the
        // median speed of near motes (z < 0.12) and far ones (z > 0.65), px/s, the wind included. (At t = 0 alone, a
        // fast moment: 30 and 12.6.)
        const val NEAR_MEDIAN = 22.5; const val FAR_MEDIAN = 10.4
        // A finger held 0.4 s at (700, 300): pixels over 40 within 40 px of it, 40 to 90, 90 to 150, before and after.
        val REST_RINGS = intArrayOf(63, 702, 2061); val HELD_RINGS = intArrayOf(0, 18, 2737)
    }

    private val m = DustField.motes()
    /** The draft's own light (DustField.LIGHT = 1): what the draft's figures are held against. */
    private val draft = DustField.motes(light = 1f)

    @Test fun `the motes are the same for a seed, others for another, as many as asked`() {
        val a = DustField.motes(); val b = DustField.motes(); val c = DustField.motes(seed = 6L)
        assertEquals(DustField.COUNT, a.count)
        assertArrayEquals(a.x0, b.x0, 0f); assertArrayEquals(a.z, b.z, 0f); assertArrayEquals(a.b, b.b, 0f); assertArrayEquals(a.sig, b.sig, 0f)
        assertFalse(a.x0.contentEquals(c.x0))
        // The same density as the draft's 1400 in its 1136 x 756 box.
        val draftDensity = 1400.0 / (1136.0 * 756.0)
        val ours = DustField.COUNT / (DustField.BOX_W.toDouble() * DustField.BOX_H)
        assertEquals(draftDensity, ours, draftDensity * 0.01)
        for (i in 0 until a.count) {
            assertTrue(a.x0[i] >= -DustField.MARGIN && a.x0[i] < DustField.W + DustField.MARGIN)
            assertTrue(a.y0[i] >= -DustField.MARGIN && a.y0[i] < DustField.H + DustField.MARGIN)
            assertTrue(a.z[i] in 0f..1f)
            assertTrue(a.b[i] > 0f && a.b[i] < 0.7f * DustField.LIGHT)
            // The face's light is the draft's times LIGHT, mote for mote: the same places, depths and sizes.
            assertEquals(draft.b[i] * DustField.LIGHT, a.b[i], 1e-6f)
            assertEquals(draft.x0[i], a.x0[i], 0f); assertEquals(draft.z[i], a.z[i], 0f); assertEquals(draft.sig[i], a.sig[i], 0f)
        }
    }

    @Test fun `a few near motes are out of focus, big and dim, the rest sized by depth, never under 2 px across`() {
        val bokeh = (0 until m.count).filter { m.sig[it] > 0f }
        assertEquals(DustField.BOKEH, bokeh.size)
        for (i in bokeh) {
            assertTrue(m.sig[i] in 3.6f..5.4f); assertTrue(m.z[i] <= 0.05f); assertTrue(draft.b[i] in 0.1f..0.18f)
        }
        // Size by depth: the draft's bands (2.0 near ... 0.85 far), smaller with depth, never finer than 2 px at half light.
        assertEquals(2.0f, DustField.sigmaOf(0f), 1e-4f)
        assertEquals(0.85f, DustField.sigmaOf(1f), 1e-4f)
        var last = Float.MAX_VALUE
        for (k in 0..100) {
            val s = DustField.sigmaOf(k / 100f)
            assertTrue(s <= last + 1e-6f); last = s
            assertTrue("at least about 2 px across (half light): ${2.355f * s}", 2.355f * s >= 2.0f)
        }
        // Near motes are brighter than far ones, on the whole.
        val near = (0 until m.count).filter { m.sig[it] == 0f && m.z[it] < 0.2f }.map { m.b[it] }.average()
        val far = (0 until m.count).filter { m.z[it] > 0.8f }.map { m.b[it] }.average()
        assertTrue("near $near far $far", near > 2.5 * far)
    }

    @Test fun `near warm, far cool, never purple, no colour of its own`() {
        val c = FloatArray(3)
        DustField.colour(0f, false, c); assertTrue(c[0] > c[1] && c[1] > c[2])     // warm: red over blue
        DustField.colour(1f, false, c); assertTrue(c[2] > c[1] && c[1] > c[0])     // cool: blue over red
        DustField.colour(0.3f, true, c); assertArrayEquals(DustField.NEAR_COL, c, 0f) // out of focus: near
        for (k in 0..20) {
            DustField.colour(k / 20f, false, c)
            // Purple is green the least of the three: never; and a hair only, never a colour (each within 15% of white).
            assertTrue(c[1] >= minOf(c[0], c[2]) - 1e-6f)
            for (ch in c) assertTrue(ch in 0.85f..1.0f)
        }
    }

    @Test fun `the current is the draft's - its speeds by depth over time, and it neither gathers nor thins the dust`() {
        val inp = DustField.Inputs(); val v = FloatArray(2)
        val near = ArrayList<Double>(); val far = ArrayList<Double>(); val alone = ArrayList<Double>()
        val calm = DustField.Inputs().apply { windX = 0f; windY = 0f }
        for (t in 0..156 step 2) {
            inp.phases(t.toDouble()); calm.phases(t.toDouble())
            for (i in 0 until m.count) {
                DustField.current(m.x0[i], m.y0[i], m.z[i], inp, v)
                val s = hypot(v[0].toDouble(), v[1].toDouble())
                if (m.z[i] < 0.12f) near += s
                if (m.z[i] > 0.65f) far += s
                DustField.current(m.x0[i], m.y0[i], 0f, calm, v)
                alone += hypot(v[0].toDouble(), v[1].toDouble())
            }
        }
        val nearMedian = near.sorted()[near.size / 2]; val farMedian = far.sorted()[far.size / 2]
        val fast = alone.sorted()[(alone.size * 0.99).toInt()]
        println("dust: the current over time, near median ${"%.1f".format(nearMedian)} px/s (the draft's ${Draft.NEAR_MEDIAN}), far ${"%.1f".format(farMedian)} (${Draft.FAR_MEDIAN}), its fast near motes (99th percentile, the current alone) ${"%.1f".format(fast)}")
        assertEquals(Draft.NEAR_MEDIAN, nearMedian, Draft.NEAR_MEDIAN * 0.06)
        assertEquals(Draft.FAR_MEDIAN, farMedian, Draft.FAR_MEDIAN * 0.08)
        // What DustMotion's pace takes as the current's fast motes.
        assertEquals(DustField.CURRENT_FAST.toDouble(), fast, DustField.CURRENT_FAST * 0.06)
        // Divergence free: the curl of a potential.
        val r = DustField.Rng(3)
        val a = FloatArray(2); val b = FloatArray(2)
        repeat(200) {
            val x = r.next() * DustField.BOX_W - DustField.MARGIN; val y = r.next() * DustField.BOX_H - DustField.MARGIN
            val h = 0.5f
            DustField.current(x + h, y, 0f, inp, a); DustField.current(x - h, y, 0f, inp, b)
            val dxx = (a[0] - b[0]) / (2 * h)
            DustField.current(x, y + h, 0f, inp, a); DustField.current(x, y - h, 0f, inp, b)
            val dyy = (a[1] - b[1]) / (2 * h)
            assertEquals(0.0, (dxx + dyy).toDouble(), 2e-3)
        }
        // Periodic over the box: no seam where it wraps.
        DustField.current(-DustField.MARGIN + 1f, 100f, 0f, inp, a); DustField.current(-DustField.MARGIN + 1f + DustField.BOX_W, 100f, 0f, inp, b)
        assertEquals(a[0], b[0], 1e-2f); assertEquals(a[1], b[1], 1e-2f)
    }

    @Test fun `stepped for two minutes the dust stays in its box, finite, and evenly over the panel`() {
        val s = DustField.initial(m)
        val driver = DustDriver()
        val counts = ArrayList<Int>()
        val cells = IntArray(8 * 5)
        val seenAt = FloatArray(2)
        var t = 0L
        repeat(120 * 30) { k ->
            val inp = driver.next(1f / 30f, t); t += 33
            DustField.step(m, s, inp, 1f / 30f)
            if (k % 30 == 0) {
                var on = 0
                for (i in 0 until m.count) {
                    assertTrue(s.px[i].isFinite() && s.py[i].isFinite() && s.dx[i].isFinite())
                    assertTrue(s.px[i] >= -DustField.MARGIN && s.px[i] < DustField.W + DustField.MARGIN)
                    assertTrue(s.py[i] >= -DustField.MARGIN && s.py[i] < DustField.H + DustField.MARGIN)
                    DustField.seen(s.px[i], s.py[i], m.z[i], inp, seenAt)
                    if (seenAt[0] in 0f..DustField.W && seenAt[1] in 0f..DustField.H) {
                        on++
                        cells[(seenAt[0] / DustField.W * 8).toInt().coerceAtMost(7) + 8 * (seenAt[1] / DustField.H * 5).toInt().coerceAtMost(4)]++
                    }
                }
                counts += on
            }
        }
        val expected = DustField.COUNT * (DustField.W * DustField.H) / (DustField.BOX_W * DustField.BOX_H)
        for (c in counts) assertEquals("motes on the panel", expected.toDouble(), c.toDouble(), expected * 0.1)
        // No part of the panel is left empty for long: every cell of an 8 x 5 grid keeps its share over the two minutes.
        val share = cells.sum() / cells.size.toDouble()
        for (c in cells) assertTrue("a cell had $c of $share", c > share * 0.6 && c < share * 1.4)
    }

    @Test fun `the seen place is the box shifted and turned whole - no hole, no seam on the panel, however far`() {
        // The largest sprite any mote can have (a near out-of-focus one, lit three times over by a finger).
        val inp = DustField.Inputs().apply { glow = 1f }
        val sp = DustField.Sprite(); val scratch = FloatArray(2)
        val st = DustField.initial(m)
        var reach = 0f
        for (i in 0 until m.count) {
            inp.glowX = st.px[i]; inp.glowY = st.py[i]
            DustField.sprite(m, st, i, inp, 1f, sp, scratch)
            reach = max(reach, kotlin.math.sqrt(sp.cut2))
        }
        assertTrue("the largest sprite reaches $reach px", reach in 15f..30f)
        for (spin in floatArrayOf(-DustMotion.SPIN_MAX, 0f, DustMotion.SPIN_MAX)) {
            val margin = DustField.seamMargin(spin)
            assertTrue("the seam is $margin px off the panel at a swirl of $spin, sprites reach $reach", margin > reach + 4f)
        }
        // A grid of places all over the box, shifted and turned as far as they ever are: the panel is covered evenly.
        val fullX = 1.2f * Parallax.MARGIN * DustField.W; val fullY = 1.2f * Parallax.MARGIN * DustField.H
        val step = 4f
        val nx = (DustField.BOX_W / step).toInt(); val ny = (DustField.BOX_H / step).toInt()
        for ((sx, sy, lag, turn) in listOf(
            floatArrayOf(fullX, fullY, DustMotion.LAG_MAX, DustMotion.SPIN_MAX), floatArrayOf(-fullX, fullY, -DustMotion.LAG_MAX, -DustMotion.SPIN_MAX),
            floatArrayOf(fullX, -fullY, DustMotion.LAG_MAX, -DustMotion.SPIN_MAX), floatArrayOf(0f, 0f, 0f, 0f),
        )) {
            val q = DustField.Inputs().apply { tiltX = sx; tiltY = sy; lagX = lag; lagY = -lag * 0.7f; spin = turn }
            val cells = IntArray(16 * 10)
            for (j in 0 until ny) for (i in 0 until nx) {
                DustField.seen(-DustField.MARGIN + (i + 0.5f) * step, -DustField.MARGIN + (j + 0.5f) * step, 0f, q, scratch)
                val x = scratch[0]; val y = scratch[1]
                if (x >= 0f && x < DustField.W && y >= 0f && y < DustField.H) cells[(x / DustField.W * 16).toInt() + 16 * (y / DustField.H * 10).toInt()]++
            }
            val each = DustField.W * DustField.H / 160f / (step * step)
            for (c in cells) assertEquals("an even panel at $sx, $sy, $lag, $turn", each.toDouble(), c.toDouble(), each * 0.12)
        }
    }

    @Test fun `tilted, near motes move more than far ones - 1_2 and 0_2 of the margin`() {
        val full = Parallax.MARGIN * DustField.W
        val q = DustField.Inputs().apply { tiltX = full }
        val at = FloatArray(2)
        DustField.seen(500f, 300f, 0f, q, at); assertEquals(500f + 1.2f * full, at[0], 1e-3f)
        DustField.seen(500f, 300f, 1f, q, at); assertEquals(500f + 0.2f * full, at[0], 1e-3f)
        assertEquals(70.3f, 1.2f * full, 0.1f)
        // The movement's lag: near fully, far 35%.
        val l = DustField.Inputs().apply { lagY = 40f }
        DustField.seen(500f, 300f, 0f, l, at); assertEquals(340f, at[1], 1e-3f)
        DustField.seen(500f, 300f, 1f, l, at); assertEquals(314f, at[1], 1e-3f)
    }

    // -- the pictures, against the draft ---------------------------------------------------------------------------------

    private fun green(px: IntArray) = IntArray(px.size) { (px[it] shr 8) and 0xFF }

    private fun beamMask(): BooleanArray = BooleanArray((DustField.W * DustField.H).toInt()) {
        val x = it % DustField.W.toInt(); val y = it / DustField.W.toInt()
        DustField.beam(x + 0.5f, y + 0.5f) > 0.9f
    }

    @Test fun `at rest it looks like the draft - its light in and out of the beam, its brightest motes`() {
        val g = green(DustField.render(draft, DustField.initial(draft), DustField.Inputs()))
        val beam = beamMask()
        val inBeam = g.indices.filter { beam[it] }.map { g[it] }.average()
        val outBeam = g.indices.filter { !beam[it] }.map { g[it] }.average()
        val p999 = g.sorted()[(g.size * 0.999).toInt()].toDouble()
        val over40 = g.count { it > 40 }; val over100 = g.count { it > 100 }
        println("dust at rest: in beam %.2f (draft %.2f), out %.2f (%.2f), p99.9 %.0f (%.0f), >40 %d (%d), >100 %d (%d)".format(
            inBeam, Draft.IN_BEAM, outBeam, Draft.OUT_BEAM, p999, Draft.P999, over40, Draft.OVER_40, over100, Draft.OVER_100))
        assertEquals(Draft.IN_BEAM, inBeam, Draft.IN_BEAM * 0.2)
        assertEquals(Draft.OUT_BEAM, outBeam, Draft.OUT_BEAM * 0.35)
        assertEquals(Draft.P999, p999, Draft.P999 * 0.25)
        assertEquals(Draft.OVER_40.toDouble(), over40.toDouble(), Draft.OVER_40 * 0.3)
        assertEquals(Draft.OVER_100.toDouble(), over100.toDouble(), Draft.OVER_100 * 0.4)
        // The beam's own haze: barely there, 9 to 10 levels at its middle, black far outside it.
        val mid = DustField.srgb8(DustField.HAZE * DustField.HAZE_BLOOM * DustField.NEAR_COL[1])
        assertTrue("haze $mid", mid in 8..11)
        assertEquals(0f, DustField.beam(100f, 550f), 0f)
    }

    @Test fun `on the back it is the draft made brighter - the same look, more light, nothing clipped flat`() {
        val beam = beamMask()
        // The beam's haze alone (no motes): it stays as it was, so the motes' own light is the picture minus it.
        val none = DustField.motes(count = 0, bokeh = 0)
        val haze = green(DustField.render(none, DustField.initial(none), DustField.Inputs()))
        fun stats(g: IntArray): DoubleArray {
            val inBeam = g.indices.filter { beam[it] }.map { g[it] - haze[it] }.average()
            val outBeam = g.indices.filter { !beam[it] }.map { g[it] - haze[it] }.average()
            return doubleArrayOf(inBeam, outBeam, g.sorted()[(g.size * 0.999).toInt()].toDouble(), g.count { it > 40 }.toDouble())
        }
        val d = stats(green(DustField.render(draft, DustField.initial(draft), DustField.Inputs())))
        val faceG = green(DustField.render(m, DustField.initial(m), DustField.Inputs()))
        val f = stats(faceG)
        val flat = faceG.count { it >= 255 }
        println("dust on the back (x${DustField.LIGHT}), the motes' own light: in beam %.2f (the draft's %.2f), out %.2f (%.2f); p99.9 %.0f (%.0f), >40 %.0f (%.0f), at 255 %d".format(
            f[0], d[0], f[1], d[1], f[2], d[2], f[3], d[3], flat))
        // The haze is the draft's (9 to 10 levels at its middle: never a grey fog).
        assertTrue(haze.max() in 8..11)
        // The motes brighter in and out of the beam alike (LIGHT in linear light is about x1.3 to x1.8 in sRGB levels).
        assertTrue("in beam x${f[0] / d[0]}", f[0] / d[0] in 1.3..1.8)
        assertTrue("out of beam x${f[1] / d[1]}", f[1] / d[1] in 1.3..1.8)
        assertTrue("p99.9 x${f[2] / d[2]}", f[2] / d[2] in 1.15..1.5)
        assertTrue("over 40 x${f[3] / d[3]}", f[3] / d[3] in 1.3..2.0)
        // The look kept: the motes in the beam against those out of it as the draft's, within 10%.
        assertEquals(d[0] / d[1], f[0] / f[1], d[0] / d[1] * 0.10)
        // The brightest motes meet the shoulder: nothing a flat white blot.
        assertTrue("$flat pixels at 255", flat < 20)
    }

    /** Pixels over 40 within 40 px of ([x], [y]), 40 to 90 and 90 to 150. */
    private fun rings(g: IntArray, x: Float, y: Float): IntArray {
        val out = IntArray(3)
        for (p in g.indices) {
            if (g[p] <= 40) continue
            val r = hypot(p % 976 + 0.5f - x, p / 976 + 0.5f - y)
            when { r < 40f -> out[0]++; r < 90f -> out[1]++; r < 150f -> out[2]++ }
        }
        return out
    }

    /**
     * [seconds] of the face through its driver as the phone draws it (every refresh of the rear while DustDriver.quick,
     * else every other, a reacting picture's step at 60 in two: DustPace), from [t0] ms; [touch] at the touch reader's
     * pace (120 a second).
     */
    private fun run(s: DustField.State, driver: DustDriver, t0: Long, seconds: Float, touch: (Long) -> Unit = {}): DustField.Inputs {
        var inp = driver.inputs
        var t = t0.toDouble()
        var since = 0
        repeat((seconds * 120).toInt()) {
            t += 1000.0 / 120.0
            touch(t.toLong())
            since++
            if (driver.quick || since >= 2) {
                val dt = since / DustPace.QUICK_HZ
                val n = DustPace.steps(dt, driver.active)
                inp = driver.next(dt, t.toLong(), n)
                DustField.step(m, s, inp, dt / n, n)
                since = 0
            }
        }
        return inp
    }

    @Test fun `a finger held parts the dust round it and lights the motes near it, as the draft's`() {
        val s = DustField.initial(m)
        val rest = rings(green(DustField.render(m, s, DustField.Inputs())), 700f, 300f)
        val driver = DustDriver()
        driver.finger.down(700f, 300f, 10L)
        val inp = run(s, driver, 10L, 0.4f)
        val held = rings(green(DustField.render(m, s, inp)), 700f, 300f)
        println("dust, a finger held 0.4 s: ${held.toList()} (rest ${rest.toList()}); draft ${Draft.HELD_RINGS.toList()} (rest ${Draft.REST_RINGS.toList()})")
        // The clearing: what was lit within 90 px is all but gone (the draft: 63 -> 0 and 702 -> 18).
        assertTrue(held[0] <= max(2, rest[0] / 10))
        assertTrue(held[1] <= max(10, rest[1] / 8))
        // The motes round it light up (the draft: x1.33 lit pixels in the ring).
        assertTrue("ring ${held[2]} vs ${rest[2]}", held[2] > rest[2] * 1.1)
    }

    @Test fun `brighter, a finger's glow still lifts the motes round it, and only a few pixels beside it clip`() {
        val s = DustField.initial(m)
        val driver = DustDriver()
        driver.finger.down(700f, 300f, 10L)
        val inp = run(s, driver, 10L, 0.4f)
        val lit = green(DustField.render(m, s, inp))
        // The same motes in the same places without the glow: what the glow alone adds.
        val unlit = green(DustField.render(m, s, DustField.Inputs().also { q ->
            q.wave.indices.forEach { q.wave[it] = inp.wave[it] }
            q.tiltX = inp.tiltX; q.tiltY = inp.tiltY; q.lagX = inp.lagX; q.lagY = inp.lagY; q.spin = inp.spin; q.gain = inp.gain
        }))
        var flat = 0
        val lifts = ArrayList<Pair<Int, Double>>()
        for (p in lit.indices) {
            val r = hypot(p % 976 + 0.5f - 700f, p / 976 + 0.5f - 300f)
            if (r < 220f && lit[p] >= 255) flat++
            if (r in 80f..170f && unlit[p] > 40) lifts += unlit[p] to lit[p].toDouble() / unlit[p]
        }
        val median = lifts.map { it.second }.sorted()[lifts.size / 2]
        val top = lifts.sortedByDescending { it.first }.take(lifts.size / 10).map { it.second }.sorted()
        val topMedian = top[top.size / 2]
        println("dust, a finger held 0.4 s at x${DustField.LIGHT}: $flat pixels at 255 within 220 px; the glow lifts the lit pixels 80 to 170 px from it x%.2f (median), the brightest tenth x%.2f".format(median, topMedian))
        assertTrue("$flat pixels at 255 beside the finger", flat <= 20)
        assertTrue("median lift x$median", median >= 1.3)
        assertTrue("the brightest tenth's lift x$topMedian", topMedian >= 1.15)
    }

    @Test fun `lifted, the motes come back on their spring - closed within two seconds, the glow gone`() {
        val s = DustField.initial(m)
        val driver = DustDriver()
        driver.finger.down(700f, 300f, 10L)
        run(s, driver, 10L, 0.5f)
        driver.finger.up(510L)
        var far = 0f
        for (i in 0 until m.count) far = max(far, hypot(s.dx[i], s.dy[i]))
        assertTrue("pushed up to $far px", far > 60f)
        val inp = run(s, driver, 510L, 2.0f)
        var left = 0f
        for (i in 0 until m.count) left = max(left, hypot(s.dx[i], s.dy[i]))
        assertTrue("still $left px off after 2 s", left < 1f)
        assertTrue(inp.glow < 0.01f)
        assertFalse(driver.finger.active(2600L))
    }

    @Test fun `a finger drawn across opens a lane that closes behind it`() {
        // The draft's stroke: (470, 440) to (900, 200) over 1.6 s, through (690, 330).
        fun at(t: Long): Pair<Float, Float> {
            val u = ((t - 10L) / 1600f).coerceIn(0f, 1f)
            return if (u < 0.5f) Pair(470f + 440f * u, 440f - 220f * u) else Pair(690f + 420f * (u - 0.5f), 330f - 260f * (u - 0.5f))
        }
        val s = DustField.initial(m); val calm = DustField.initial(m)
        val driver = DustDriver(); val alone = DustDriver()
        driver.finger.down(470f, 440f, 10L)
        // 0.2 s after it passed (690, 330): the lane behind it, against the same dust with no finger.
        var inp = run(s, driver, 10L, 0.99f) { t -> val (x, y) = at(t); driver.finger.move(x, y, t) }
        var still = run(calm, alone, 10L, 0.99f)
        val lane = rings(green(DustField.render(m, s, inp)), 690f, 330f)
        val laneCalm = rings(green(DustField.render(m, calm, still)), 690f, 330f)
        inp = run(s, driver, 1000L, 0.61f) { t -> val (x, y) = at(t); driver.finger.move(x, y, t) }
        still = run(calm, alone, 1000L, 0.61f)
        driver.finger.up(1610L)
        inp = run(s, driver, 1610L, 1.5f)
        still = run(calm, alone, 1610L, 1.5f)
        val after = rings(green(DustField.render(m, s, inp)), 690f, 330f)
        val afterCalm = rings(green(DustField.render(m, calm, still)), 690f, 330f)
        println("dust, the lane at (690, 330): ${lane.toList()} (no finger ${laneCalm.toList()}), 1.5 s after the lift ${after.toList()} (${afterCalm.toList()})")
        assertTrue(lane[0] + lane[1] < (laneCalm[0] + laneCalm[1]) * 0.5)
        assertTrue(after[0] + after[1] > (afterCalm[0] + afterCalm[1]) * 0.75)
        var left = 0f
        for (i in 0 until m.count) left = max(left, hypot(s.dx[i], s.dy[i]))
        assertTrue("closed: $left px", left < 2f)
    }

    @Test fun `the card is the first picture at rest, opaque, the panel's size, the same every time`() {
        val a = DustField.card(); val b = DustField.card()
        assertEquals(976 * 596, a.size)
        assertArrayEquals(a, b)
        assertTrue(a.all { (it ushr 24) == 0xFF })
        assertArrayEquals(DustField.render(m, DustField.initial(m), DustField.Inputs()), a)
        // Behind the lenses too (the picture fills the panel): motes there as anywhere.
        val g = green(a)
        val lensSide = (0 until 596).sumOf { y -> (0 until 296).count { x -> g[y * 976 + x] > 20 } }
        assertTrue("lit pixels behind the lenses: $lensSide", lensSide > 300)
    }

    @Test fun `dimmed, it is darker in the same way as the moon's - its gain on the sRGB values`() {
        val s = DustField.initial(m)
        val full = green(DustField.render(m, s, DustField.Inputs()))
        for (gain in floatArrayOf(0.75f, 0.5f, 0.3f)) {
            val dim = green(DustField.render(m, s, DustField.Inputs().apply { this.gain = gain }))
            var sum = 0.0; var worst = 0
            for (p in full.indices) {
                val d = abs(dim[p] - Math.round(full[p] * gain))
                sum += d; worst = max(worst, d)
            }
            println("dust dimmed to $gain: off the moon's way by ${"%.3f".format(sum / full.size)} levels on average, $worst at most")
            // Off by a level or so only where motes meet (each is dimmed on its own, their sum is not the moon's sum).
            assertTrue(sum / full.size < 0.25)
            assertTrue(worst <= 7)
        }
        assertTrue(DustField.render(m, s, DustField.Inputs().apply { gain = 0f }).all { it == 0xFF000000.toInt() })
    }

    @Test fun `every number in the shaders is defined, and the twin and the shaders share their formulas`() {
        for (src in listOf(DustField.UPDATE_VS, DustField.DRAW_VS, DustField.HAZE_VS, DustField.HAZE_FS)) {
            val defined = Regex("#define (\\w+)").findAll(src).map { it.groupValues[1] }.toSet()
            val body = src.lines().filterNot { it.startsWith("#") }.joinToString("\n")
            for (name in Regex("\\b[A-Z][A-Z0-9_]{1,}\\b").findAll(body).map { it.value }.toSet()) assertTrue("$name is defined", name in defined)
        }
        // The seen place: wrapped, then turned (the shader and the twin alike).
        assertTrue(DustField.UPDATE_VS.contains("vec2 e = wrapBox(p + IN_SHIFT.xy * par + IN_SHIFT.zw * lag) - vec2(SPIN_X, SPIN_Y);"))
        // The frame's inputs as one array a program, in the places DustField.packUpdate and packDraw put them.
        assertTrue(DustField.UPDATE_VS.contains("uniform vec4 uIn[WAVES + 5];") && DustField.IN_U == DustField.WAVES + 5)
        assertTrue(DustField.DRAW_VS.contains("uniform vec4 uIn[2];") && DustField.IN_D == 2)
        // A picture's steps, all in one pass (no more calls for a second step).
        assertTrue(DustField.UPDATE_VS.contains("for (int n = 0; n < steps; n++)"))
        // Quads, not points (a point is clipped by its centre: its halo would pop at the panel's edges).
        assertFalse(DustField.DRAW_VS.contains("gl_PointSize"))
        assertTrue(DustField.DRAW_VS.contains("gl_VertexID"))
    }

    @Test fun `the sprites the GPU draws - how many fragments a frame at rest`() {
        val s = DustField.initial(m)
        val sp = DustField.Sprite(); val scratch = FloatArray(2)
        var quads = 0L; var lit = 0L; var drawn = 0
        val inp = DustField.Inputs()
        for (i in 0 until m.count) {
            DustField.sprite(m, s, i, inp, 1f, sp, scratch)
            if (sp.cut2 <= 0f) continue
            val half = kotlin.math.sqrt(sp.cut2) + 1f
            // Only what lies on the panel is shaded.
            val x0 = max(0f, sp.cx - half); val x1 = minOf(DustField.W, sp.cx + half)
            val y0 = max(0f, sp.cy - half); val y1 = minOf(DustField.H, sp.cy + half)
            if (x1 <= x0 || y1 <= y0) continue
            drawn++
            quads += ((x1 - x0) * (y1 - y0)).toLong()
            lit += (Math.PI * sp.cut2).toLong()
        }
        println("dust: $drawn motes on the panel, $quads fragments shaded, about $lit added; the haze ${976 * 596}")
        assertTrue(quads < 1_500_000)
    }

    @Test fun `no NaN anywhere, however hard it is pushed and shaken`() {
        val s = DustField.initial(m)
        val inp = DustField.Inputs().apply {
            push = 1f; fingerX = 300f; fingerY = 200f; fingerVX = 30000f; fingerVY = -30000f
            tiltX = 70f; tiltY = -40f; lagX = 80f; lagY = 80f; spin = 0.12f; flow *= 2.5f
        }
        repeat(600) { DustField.step(m, s, inp, 1f / 30f) }
        for (i in 0 until m.count) assertTrue(s.px[i].isFinite() && s.py[i].isFinite() && s.dx[i].isFinite() && s.vx[i].isFinite())
        assertTrue(DustField.render(m, s, inp, 244, 149).all { (it ushr 24) == 0xFF })
        assertTrue(abs(1f) > 0f)
    }
}
