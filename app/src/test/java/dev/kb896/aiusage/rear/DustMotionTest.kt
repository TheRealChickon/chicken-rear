package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * THE PHONE MOVED (DustMotion): the dust lags a push, swirls against a turn and settles within a few seconds, never past
 * its limits; a still phone (a resting hand's noise) adds nothing; a tilt is a tilt (the parallax), not a push.
 */
class DustMotionTest {
    /** The phone held up with its back to him, tipped back a little: gravity in the phone's axes. */
    private val held = floatArrayOf(0f, 8.5f, -4.9f)

    private class Run(val motion: DustMotion = DustMotion()) {
        var t = 0.0
        var lagMax = 0f; var lagMinX = 0f; var lagMaxX = 0f; var spinMin = 0f; var spinMax = 0f; var seenLagMax = 0f; var seenSpinMax = 0f
        var tiltMax = 0f
        private var nextSample = 0.0

        /**
         * Pictures, how many of them were reacting (DustMotion.active) and for how long (s), how many were drawn at 120
         * (DustMotion.quick, [paced]), and the most [DustMotion.pace].
         */
        var pictures = 0; var fast = 0; var fastS = 0.0; var quick = 0; var paceMax = 0f

        /**
         * [seconds] at 120 frames a second (or [paced]: as on the phone, 120 a second while the dust moves too fast for 60
         * (DustMotion.quick, as the last picture left it), else 60, a reacting picture's step at 60 taken in two,
         * DustPace.steps), the sensors at 50 a second: [sample] gives the accelerometer and gyroscope at a time (s),
         * [gyro] false for a phone without one. [picture] hears each picture's time step.
         */
        fun go(seconds: Double, gyro: Boolean = true, paced: Boolean = false, picture: (Float) -> Unit = {}, sample: (Double, FloatArray, FloatArray) -> Unit) {
            val a = FloatArray(3); val w = FloatArray(3)
            val end = t + seconds
            while (t < end) {
                while (nextSample <= t) {
                    sample(nextSample, a, w)
                    val ns = (nextSample * 1e9).toLong() + 1_000_000L
                    if (gyro) motion.gyro(w[0], w[1], w[2], ns)
                    motion.accel(a[0], a[1], a[2], ns)
                    nextSample += DustMotion.RATE_US / 1e6
                }
                val dt = if (paced && !motion.quick()) 1f / DustPace.HZ else 1f / DustPace.QUICK_HZ
                val n = if (paced) DustPace.steps(dt, motion.active) else 1
                repeat(n) { motion.step(dt / n) }
                t += dt
                pictures++; if (motion.active) { fast++; fastS += dt }; if (paced && dt < 0.012f) quick++
                paceMax = max(paceMax, motion.pace)
                picture(dt)
                lagMax = max(lagMax, hypot(motion.lagX, motion.lagY))
                lagMinX = minOf(lagMinX, motion.lagX); lagMaxX = max(lagMaxX, motion.lagX)
                spinMin = minOf(spinMin, motion.spin); spinMax = max(spinMax, motion.spin)
                seenLagMax = max(seenLagMax, hypot(motion.seenLagX(), motion.seenLagY()))
                seenSpinMax = max(seenSpinMax, abs(motion.seenSpin()))
                tiltMax = max(tiltMax, max(abs(motion.tiltX), abs(motion.tiltY)))
            }
        }

        fun reset() { lagMax = 0f; lagMinX = 0f; lagMaxX = 0f; spinMin = 0f; spinMax = 0f; seenLagMax = 0f; seenSpinMax = 0f; tiltMax = 0f; pictures = 0; fast = 0; fastS = 0.0; quick = 0; paceMax = 0f }
    }

    /** Settled to a fraction of a pixel: no lag, no swirl, no stir to speak of (not the pace, which asks less). */
    private fun calm(m: DustMotion) = abs(m.lagX) + abs(m.lagY) < 0.3f && abs(m.spin) < 0.0008f && m.stir < 0.1f

    private val noise = DustField.Rng(11)
    private fun n(amp: Float) = (noise.next() * 2f - 1f) * amp

    /** A resting hand: the accelerometer's noise and a hand's tremor (±0.06 m/s²), the gyroscope's (±0.02 rad/s). */
    private fun still(g: FloatArray): (Double, FloatArray, FloatArray) -> Unit = { _, a, w ->
        a[0] = g[0] + n(0.06f); a[1] = g[1] + n(0.06f); a[2] = g[2] + n(0.06f)
        w[0] = n(0.02f); w[1] = n(0.02f); w[2] = n(0.02f)
    }

    private fun made(m: DustMotion.Made, from: Double): (Double, FloatArray, FloatArray) -> Unit = { t, a, w ->
        m.at((t - from).toFloat(), a, w)
        a[0] += n(0.03f); a[1] += n(0.03f); a[2] += n(0.03f)
    }

    @Test fun `a still phone adds nothing - no lag, no swirl, no stir, the slow pace`() {
        val r = Run()
        r.go(10.0, sample = still(held))
        println("still: lag up to ${r.lagMax} px, swirl ${r.spinMin}..${r.spinMax}, stir ${r.motion.stir}, pace up to ${r.paceMax} px/s")
        assertTrue(r.lagMax < 0.5f)
        assertTrue(r.spinMax < 0.002f && r.spinMin > -0.002f)
        assertTrue(r.motion.stir < 0.03f)
        assertEquals(0, r.fast)
        // And the tilt parallax stays put.
        assertTrue(r.tiltMax < 0.05f)
    }

    @Test fun `a hand's wobble past the dead zone moves the dust a little, never enough to count as reacting`() {
        val r = Run()
        // ±0.25 m/s² on each axis of the accelerometer (well past the dead zone at times), the gyroscope's ±0.02 rad/s.
        r.go(20.0, paced = true, sample = { _, a, w ->
            a[0] = held[0] + n(0.25f); a[1] = held[1] + n(0.25f); a[2] = held[2] + n(0.25f)
            w[0] = n(0.02f); w[1] = n(0.02f); w[2] = n(0.02f)
        })
        println("wobble: lag up to ${r.lagMax} px, stir ${r.motion.stir}, pace up to ${r.paceMax} px/s, ${r.fast} of ${r.pictures} pictures reacting")
        assertTrue("it is seen: ${r.lagMax}", r.lagMax > 0.3f)
        assertEquals(0, r.fast)
        assertTrue(r.paceMax < DustMotion.PACE_FAST)
    }

    @Test fun `pushed, the dust lags the other way, near motes most, and settles within three seconds`() {
        val r = Run()
        r.go(1.0, sample = still(held))
        // A brisk move to the right as he sees it, started and stopped: 3 m/s² at its briskest over 0.5 s (12 cm).
        r.reset()
        val from = r.t
        var lastFast = from; var calmFrom = from
        val watch = { _: Float -> if (r.motion.active) lastFast = r.t; if (!calm(r.motion)) calmFrom = r.t }
        r.go(2.0, paced = true, picture = watch, sample = made(DustMotion.Made(held, pushX = 3f, pushS = 0.5f), r.t))
        println("push: lag x ${r.lagMinX}..${r.lagMaxX}, seen up to ${r.seenLagMax}, pace up to ${r.paceMax} px/s")
        assertTrue("lags to the left: ${r.lagMinX}", r.lagMinX < -15f)
        assertTrue("never violent: ${r.seenLagMax}", r.seenLagMax <= DustMotion.LAG_MAX)
        assertTrue(abs(r.motion.lagY) < 2f)
        r.go(4.0, paced = true, picture = watch, sample = still(held))
        val fastFor = r.fastS
        println("push: reacting for ${"%.2f".format(fastFor)} s (${r.quick} pictures at 120), the last at ${"%.2f".format(lastFast - from)} s; settled from ${"%.2f".format(calmFrom - from)} s")
        // Reacting (at 120 while it is quick, stepped in steps of 1/120 s at 60 after) while the dust moves fast; the
        // last pixels of its way back (about a second of it here) as at rest.
        assertTrue(r.paceMax > 2 * DustMotion.PACE_FAST)
        assertTrue("at 120 while it is quick: ${r.quick}", r.quick > 20)
        assertTrue("reacting for $fastFor s", fastFor > 0.3)
        assertTrue("reacting until ${lastFast - from} s, settled from ${calmFrom - from} s", lastFast + 0.8 < calmFrom)
        r.reset()
        r.go(2.0, sample = still(held))
        assertTrue("settled: ${r.motion.lagX}", abs(r.motion.lagX) < 1f)
        assertTrue(calm(r.motion))
        assertFalse(r.motion.active)
        // Near motes lag fully, far ones 35% (DustField.seen).
        val at = FloatArray(2)
        val inp = DustField.Inputs().apply { lagX = -40f }
        DustField.seen(500f, 300f, 0f, inp, at); assertEquals(460f, at[0], 1e-3f)
        DustField.seen(500f, 300f, 1f, inp, at); assertEquals(486f, at[0], 1e-3f)
    }

    @Test fun `shaken hard, never past its limits, and calm again within five seconds`() {
        val r = Run()
        r.go(0.5, sample = still(held))
        val from = r.t
        // Shaken to and fro along the back, 4 times a second, ±15 and ±12 m/s².
        r.go(2.0, sample = { t, a, w ->
            val k = kotlin.math.sin(2 * Math.PI * 4 * (t - from)).toFloat()
            a[0] = held[0] + 15f * k; a[1] = held[1] - 12f * k; a[2] = held[2]
            w[0] = 0f; w[1] = 0f; w[2] = 0f
        })
        // And flicked a quarter turn about the back's own axis and back in 0.6 s (8 rad/s at its fastest).
        r.go(0.6, sample = made(DustMotion.Made(held, turn = (Math.PI / 2).toFloat(), turnS = 0.6f), r.t))
        println("shake: seen lag up to ${r.seenLagMax}, swirl ${r.seenSpinMax}, stir ${r.motion.stir}")
        assertTrue(r.seenLagMax <= DustMotion.LAG_MAX + 1e-3f)
        assertTrue(r.seenSpinMax <= DustMotion.SPIN_MAX + 1e-6f)
        assertTrue(r.seenSpinMax > DustMotion.SPIN_MAX * 0.5f)
        assertTrue(r.motion.stir <= 1f)
        assertTrue(r.motion.active)
        r.go(5.0, sample = still(held))
        assertTrue("still moving 5 s later: lag ${r.motion.lagX}, ${r.motion.lagY}, swirl ${r.motion.spin}, stir ${r.motion.stir}", calm(r.motion))
        assertFalse(r.motion.active)
    }

    @Test fun `turned about the back's own axis, the dust swirls the other way and settles`() {
        val r = Run()
        r.go(0.5, sample = still(held))
        // 30 degrees clockwise to him and back, over 0.8 s.
        r.go(1.0, sample = made(DustMotion.Made(held, turn = Math.toRadians(30.0).toFloat(), turnS = 0.8f), r.t))
        println("turn: swirl ${r.spinMin}..${r.spinMax}, seen up to ${r.seenSpinMax}")
        assertTrue("against the turn first: ${r.spinMin}", r.spinMin < -0.02f)
        assertTrue(r.seenSpinMax <= DustMotion.SPIN_MAX)
        assertTrue("a turn is no push: ${r.lagMax}", r.lagMax < 6f)
        r.go(3.5, sample = still(held))
        assertTrue(abs(r.motion.spin) < 0.002f)
        assertTrue(calm(r.motion))
        assertFalse(r.motion.active)
    }

    @Test fun `tipped, the depth shows by the tilt - and the gyroscope keeps it from reading as a push`() {
        val r = Run()
        r.go(0.5, sample = still(held))
        r.go(1.5, sample = made(DustMotion.Made(held, tilt = Math.toRadians(15.0).toFloat(), turnS = 1.5f), r.t))
        println("tilt with the gyroscope: tilt up to ${r.tiltMax}, lag up to ${r.lagMax}")
        assertTrue(r.tiltMax > 0.4f)
        assertTrue("a tilt is not a push: ${r.lagMax}", r.lagMax < 3f)
        // The same tilt with the accelerometer alone reads partly as a push (why the gyroscope is used).
        val alone = Run()
        alone.go(0.5, gyro = false, sample = still(held))
        alone.go(1.5, gyro = false, sample = made(DustMotion.Made(held, tilt = Math.toRadians(15.0).toFloat(), turnS = 1.5f), alone.t))
        println("tilt, the accelerometer alone: lag up to ${alone.lagMax}")
        assertTrue(alone.lagMax > r.lagMax)
    }

    @Test fun `a tilt moves the dust on every picture, never one held between two samples, and at 120 while it is quick`() {
        val r = Run()
        r.go(1.0, paced = true, sample = still(held))
        // A hand's tilt of 15 degrees and back over a second, the sensors at 50 a second, the pictures at the pace asked.
        val steps = ArrayList<Float>(); val dts = ArrayList<Float>()
        var wasX = r.motion.tiltX; var wasY = r.motion.tiltY
        r.reset()
        r.go(1.6, paced = true, picture = { dt ->
            steps += hypot((r.motion.tiltX - wasX) * DustMotion.TILT_NEAR_X, (r.motion.tiltY - wasY) * DustMotion.TILT_NEAR_Y)
            dts += dt
            wasX = r.motion.tiltX; wasY = r.motion.tiltY
        }, sample = made(DustMotion.Made(held, tilt = Math.toRadians(15.0).toFloat(), turnS = 1.0f), r.t))
        // Its speed on the panel picture by picture (px/s: a step at 60 is twice one at 120 for the same speed).
        val v = steps.indices.map { steps[it] / dts[it] }
        val at60 = steps.indices.filter { dts[it] > 0.01f }.maxOf { steps[it] }
        println("tilt per picture: up to ${steps.max()} px, at 60 up to $at60 px, ${r.quick} of ${r.pictures} at 120, reacting for ${"%.2f".format(r.fastS)} s, tilt up to ${r.tiltMax}")
        assertTrue(r.tiltMax > 0.4f)
        assertTrue("every refresh while it is quick", r.quick > 30)
        for (k in 1 until v.size - 1) {
            val around = (v[k - 1] + v[k + 1]) / 2f
            // Moving: no picture stands still (or nearly) between two that move.
            if (around > 20f) assertTrue("picture $k: ${v[k]} px/s between ${v[k - 1]} and ${v[k + 1]}", v[k] > 0.5f * around)
        }
        // At 60, about a pixel a picture at most (the picture that crosses DustMotion.PACE_SMOOTH included).
        assertTrue("at 60: $at60 px", at60 < 1.2f)
    }

    @Test fun `without a gyroscope a still phone still adds nothing and a push still shows`() {
        val r = Run()
        r.go(5.0, gyro = false, sample = still(held))
        assertTrue(r.lagMax < 0.5f); assertFalse(r.motion.active)
        r.go(2.0, gyro = false, sample = made(DustMotion.Made(held, pushX = 4f, pushS = 0.5f), r.t))
        assertTrue(r.lagMinX < -10f)
    }

    @Test fun `reset - the next sample is the new rest and the dust is put back at the next step`() {
        val r = Run()
        r.go(0.5, sample = still(held))
        r.go(0.4, sample = made(DustMotion.Made(held, pushX = 5f, pushS = 0.5f), r.t))
        assertTrue(r.motion.active)
        r.motion.reset()
        r.motion.step(1f / 120f)
        assertEquals(0f, r.motion.lagX, 0f); assertEquals(0f, r.motion.spin, 0f); assertEquals(0f, r.motion.stir, 0f)
        // A very different gravity next (the phone laid down meanwhile): no jump.
        r.reset()
        r.go(2.0, sample = still(floatArrayOf(0f, 0f, -9.81f)))
        assertTrue(r.lagMax < 0.5f)
    }

    @Test fun `a made-up movement has the samples while it lasts, then the real phone is the rest again`() {
        val m = DustMotion()
        val g0 = FloatArray(3)
        val a = FloatArray(3); val w = FloatArray(3)
        m.accel(0f, 0f, 9.81f, 1_000_000L)
        m.makeUp(2_000_000_000L, g0)
        assertEquals(9.81f, g0[2], 1e-3f)
        val made = DustMotion.Made(g0, pushX = 3f, pushS = 0.5f)
        var t = 1_000_000L
        while (t < 2_000_000_000L) {
            t += 20_000_000L
            made.at((t - 1_000_000L) / 1e9f, a, w)
            m.accel(a[0], a[1], a[2], t, made = true)
            // The real phone, lying still on its back meanwhile: not heard.
            m.accel(0f, 9.81f, 0f, t + 1)
            m.step(0.02f)
        }
        assertTrue(abs(m.lagX) + abs(m.lagY) < 80f)
        // Over: the real sample is the new rest, with no jump.
        val before = m.lagY
        m.accel(0f, 9.81f, 0f, 2_100_000_000L); m.accel(0f, 9.81f, 0f, 2_120_000_000L)
        m.step(0.02f)
        assertTrue(abs(m.lagY - before) < 2f)
    }

    @Test fun `the finger - its push comes in, its glow rises and fades, and a silent finger is let go`() {
        val f = DustFinger(); val inp = DustField.Inputs()
        f.down(100f, 200f, 1000L)
        f.frame(1000L, inp); assertEquals(0f, inp.push, 0f); assertEquals(0f, inp.glow, 0f)
        f.frame(1040L, inp); assertEquals(0.5f, inp.push, 1e-3f)
        f.frame(1200L, inp); assertEquals(1f, inp.push, 0f); assertEquals(1f, inp.glow, 1e-4f)
        f.move(130f, 200f, 1210L); f.move(160f, 200f, 1220L)
        f.frame(1220L, inp); assertTrue("it carries the motes its way: ${inp.fingerVX}", inp.fingerVX > 500f)
        f.frame(1600L, inp); assertEquals(0f, inp.fingerVX, 1f)     // held still: no speed
        f.up(2000L)
        f.frame(2000L, inp); assertEquals(0f, inp.push, 0f); assertEquals(1f, inp.glow, 1e-3f)
        f.frame(2350L, inp); assertEquals(Math.exp(-1.0).toFloat(), inp.glow, 1e-3f)
        assertTrue(f.active(3500L)); assertFalse(f.active(3700L))
        // Down and never heard of again (the reader lost it): let go after STALE_MS.
        f.down(10f, 10f, 10_000L)
        f.frame(10_000L + DustFinger.STALE_MS + 1, inp)
        assertFalse(f.down); assertEquals(0f, inp.push, 0f)
        // Down when its show ended (the back went dark under it, its lift never heard): forgotten, so the next show
        // opens with no clearing, no glow and at the slow pace.
        f.down(300f, 300f, 30_000L)
        f.frame(30_200L, inp); assertEquals(1f, inp.push, 0f)
        f.reset()
        f.frame(31_000L, inp)
        assertFalse(f.down); assertEquals(0f, inp.push, 0f); assertEquals(0f, inp.glow, 0f); assertEquals(0f, inp.fingerVX, 0f)
        assertFalse(f.active(31_000L))
        // A move after it is not a finger down.
        f.move(320f, 300f, 31_010L); f.frame(31_010L, inp); assertEquals(0f, inp.push, 0f)
    }
}
