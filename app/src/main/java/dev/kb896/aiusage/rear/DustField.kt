package dev.kb896.aiusage.rear

import java.nio.FloatBuffer
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object DustField {
    const val W = 976f
    const val H = 596f
    /** The box past each side of the panel (more than the largest sprite where the largest swirl brings the seam nearest). */
    const val MARGIN = 120f
    const val BOX_W = W + 2 * MARGIN
    const val BOX_H = H + 2 * MARGIN
    /** As many motes in the box as the draft has in its own (1400 in 1136 x 756): the same density. */
    const val COUNT = 1660
    /** Near motes out of focus (the draft's 36, for the same density). */
    const val BOKEH = 43
    const val SEED = 5L

    // ---- depth -------------------------------------------------------------------------------------------------------
    /** How near a mote is for a push: 1 - this x z (0 near .. 1 far): a far one feels 40% of it. */
    const val NEAR_FALL = 0.6f
    /** The tilt's parallax: near motes 1.2 x, far ones 0.2 x (1.2 - z). */
    const val PAR_NEAR = 1.2f
    const val PAR_SPAN = 1.0f
    /** The phone's movement: a near mote lags fully, a far one 35% (1 - 0.65 z). */
    const val LAG_FAR = 0.65f

    // ---- the motes' size and light --------------------------------------------------------------------------------
    const val LIGHT = 1.8f
    /** Size by depth (gaussian sigma, px), through the draft's four bands: never under about 2 px across. */
    val SIGMA_Z = floatArrayOf(0.06f, 0.235f, 0.5f, 0.825f)
    val SIGMA_PX = floatArrayOf(2.0f, 1.4f, 1.05f, 0.85f)
    /** Out-of-focus motes: sigma 3.6 to 5.4 px, dim. */
    const val BOKEH_SIGMA = 3.6f
    const val BOKEH_SIGMA_SPAN = 1.8f
    /**
     * The draft placed each mote by a bilinear splat before its blur, which widens it by this much variance (px²): a
     * mote as the draft drew it is sigma² + this wide, its peak lower by as much, its light the same.
     */
    const val SPLAT_VAR = 1f / 6f
    /** At a smaller output (the settings' preview) a mote is never drawn finer than this, its light kept. */
    const val MIN_SIGMA = 0.7f
    /** Linear light under this is not drawn (0.66 of a level after sRGB): it bounds each mote's sprite. */
    const val CUT = 2e-4f
    /** The draft's bloom: 12% of the light again, blurred 4 px (its 5% at 14 px is under a level and left out). */
    const val BLOOM = 0.12f
    const val BLOOM_SIGMA = 4f
    /** The beam's haze takes both of the draft's blooms (a smooth light: 1 + 0.12 + 0.05). */
    const val HAZE_BLOOM = 1.17f
    val NEAR_COL = floatArrayOf(1.00f, 0.95f, 0.88f)
    val FAR_COL = floatArrayOf(0.86f, 0.92f, 1.00f)

    // ---- the beam ---------------------------------------------------------------------------------------------------
    /** It enters at the top right and falls down to the left, 30 degrees from upright. */
    const val BEAM_X = 930f
    const val BEAM_Y = -60f
    const val BEAM_DX = -0.5f
    const val BEAM_DY = 0.866f
    /** Half its width (px), with soft edges: full light within [BEAM_HALF] - [BEAM_IN], none past [BEAM_HALF] + [BEAM_OUT]. */
    const val BEAM_HALF = 150f
    const val BEAM_IN = 50f
    const val BEAM_OUT = 70f
    /** A mote outside the beam keeps this much of its light. */
    const val OUT_OF_BEAM = 0.16f
    /** The beam's own haze, barely there (never a grey fog over black): 9 to 10 levels of 255 at its middle. */
    const val HAZE = 0.0025f

    // ---- the current ------------------------------------------------------------------------------------------------
    /** Its eddies' size (the draft's noise unit, px). */
    const val FLOW_PX = 220f
    /** px/s for near motes per unit of the noise's gradient; far ones a third of it ([FAR_SLOW]). */
    const val FLOW_SPEED = 9f
    const val FAR_SLOW = 0.67f
    /**
     * How steep the current's potential is: the rms of one component of its gradient, in the draft's noise units. The
     * draft's noise has 2.09 over its whole periodic volume (its face.py and facekit, measured): over time its near
     * motes go at a median 22.5 px/s and its far ones 10.4 (at t = 0 alone, a fast moment of it, 30 and 12.6). This
     * puts the same rms on the waves below: ours over time 22.4 and 10.5.
     */
    const val NOISE_GRAD = 2.09f
    /**
     * The current's fast motes: the 99th percentile of a near mote's speed from the current alone (px/s, over time; no
     * wind, no breath; 52 to 54 measured). Its fastest of all is about 67. DustMotion's pace adds to it what a stir adds.
     */
    const val CURRENT_FAST = 53f
    /** Everything drifts gently up and to the left (px/s). */
    const val WIND_X = -4f
    const val WIND_Y = -1.5f
    /**
     * THE CURRENT'S POTENTIAL: a sum of [WAVES] travelling waves whose wave numbers are whole numbers of the box's
     * periods (so it wraps without a seam), wavelengths 340 to 690 px, each drifting at its own slow pace
     * ([WAVE_RATE] to + [WAVE_RATE_SPAN] rad/s, either way): the field changes over ten to thirty seconds, as the
     * draft's. The motes follow its curl (divergence free: they never bunch up or thin out).
     */
    const val WAVES = 12
    val WAVE_N = intArrayOf(3, 0, 2, 1, 1, 2, 2, -1, 1, -2, 3, 1, 3, -1, 0, 2, 2, 2, 2, -2, 1, 1, 1, -1)
    const val WAVE_RATE = 0.05f
    const val WAVE_RATE_SPAN = 0.08f
    const val WAVE_SEED = 7L

    // ---- the idle act -----------------------------------------------------------------------------------------------
    /** A breath of current across (+28, -6 px/s at its top, near motes), a sine² bump over [GUST_S]. */
    const val GUST_X = 28f
    const val GUST_Y = -6f
    /** The breath's speed at its top (px/s, near motes): what it adds to a mote's at most. */
    val GUST_PACE = sqrt(GUST_X * GUST_X + GUST_Y * GUST_Y)
    const val GUST_S = 1.8f
    /** The first a few seconds after the face comes up, then about every 15 s. */
    const val GUST_FIRST_S = 6f
    const val GUST_EVERY_S = 15f
    const val GUST_JITTER_S = 3f

    // ---- the finger -------------------------------------------------------------------------------------------------
    /** px/s² at the finger, near motes; gaussian reach (px): a clearing about 90 px round. */
    const val PUSH = 21000f
    const val PUSH_R = 90f
    /** The push comes in over this after the finger lands. */
    const val PUSH_RAMP_S = 0.08f
    /** A moving finger sweeps the motes along: x its speed (px/s²) per px/s. */
    const val CARRY = 3f
    /** How a parted mote comes back: Folme's spring (damping, response s). */
    const val SPRING_DAMPING = 0.85f
    const val SPRING_RESPONSE = 0.70f
    val SPRING_K = ((2.0 * PI / SPRING_RESPONSE).pow(2)).toFloat()
    val SPRING_C = (4.0 * PI * SPRING_DAMPING / SPRING_RESPONSE).toFloat()
    /** Motes within this of the finger light up to x(1 + [GLOW]): in [GLOW_IN_S], gone [GLOW_OUT_S] after the lift. */
    const val GLOW = 2.2f
    const val GLOW_R = 150f
    const val GLOW_IN_S = 0.12f
    const val GLOW_OUT_S = 0.35f

    /** Where the swirl turns about: the middle of what is seen (the lenses cover x 0 to 296). */
    const val SPIN_X = 636f
    const val SPIN_Y = 298f

    // ==================================================================================================================
    // THE MOTES

    /** Deterministic per seed (SplitMix64): the same motes on the phone, in a test and in the card. */
    class Rng(seed: Long) {
        private var s = seed
        fun next(): Float {
            s += -0x61c8864680b583ebL
            var z = s
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            return (z ushr 40).toFloat() / (1 shl 24).toFloat()
        }
    }

    /**
     * The motes, fixed for the show: [z] depth (0 near .. 1 far; more far than near), [b] light, [sig] an out-of-focus
     * mote's own sigma (0 for the others, whose size is by depth), [x0], [y0] where each starts in the box.
     */
    class Motes(val count: Int, val z: FloatArray, val b: FloatArray, val sig: FloatArray, val x0: FloatArray, val y0: FloatArray)

    /** The motes; [light] x the draft's light ([LIGHT] on the back; 1 is the draft's own, for the tests against it). */
    fun motes(seed: Long = SEED, count: Int = COUNT, bokeh: Int = BOKEH, light: Float = LIGHT): Motes {
        val r = Rng(seed)
        val z = FloatArray(count); val b = FloatArray(count); val sig = FloatArray(count)
        val x0 = FloatArray(count); val y0 = FloatArray(count)
        for (i in 0 until count) {
            x0[i] = r.next() * BOX_W - MARGIN
            y0[i] = r.next() * BOX_H - MARGIN
            val zz = r.next().pow(0.8f)
            val shade = r.next()
            if (i < bokeh) {
                z[i] = r.next() * 0.05f
                b[i] = (0.10f + 0.08f * r.next()) * light
                sig[i] = BOKEH_SIGMA + BOKEH_SIGMA_SPAN * r.next()
            } else {
                z[i] = zz
                b[i] = (0.9f - 0.75f * zz).pow(1.5f) * (0.35f + 0.65f * shade * shade) * 0.75f * light
            }
        }
        return Motes(count, z, b, sig, x0, y0)
    }

    /** What changes per mote, frame to frame: where it is ([px], [py]), the finger's push ([dx], [dy]) and its pace. */
    class State(val n: Int) {
        val px = FloatArray(n); val py = FloatArray(n)
        val dx = FloatArray(n); val dy = FloatArray(n)
        val vx = FloatArray(n); val vy = FloatArray(n)
    }

    fun initial(m: Motes): State = State(m.count).also { s ->
        for (i in 0 until m.count) { s.px[i] = m.x0[i]; s.py[i] = m.y0[i] }
    }

    /** Size by depth: through the draft's bands, linearly between their middles. */
    fun sigmaOf(z: Float): Float = when {
        z < SIGMA_Z[1] -> mix(SIGMA_PX[0], SIGMA_PX[1], ((z - SIGMA_Z[0]) / (SIGMA_Z[1] - SIGMA_Z[0])).coerceIn(0f, 1f))
        z < SIGMA_Z[2] -> mix(SIGMA_PX[1], SIGMA_PX[2], (z - SIGMA_Z[1]) / (SIGMA_Z[2] - SIGMA_Z[1]))
        else -> mix(SIGMA_PX[2], SIGMA_PX[3], ((z - SIGMA_Z[2]) / (SIGMA_Z[3] - SIGMA_Z[2])).coerceIn(0f, 1f))
    }

    /** The colour of a mote at depth [z] (an out-of-focus one is near: [NEAR_COL]), into [out]. */
    fun colour(z: Float, bokeh: Boolean, out: FloatArray) {
        val t = if (bokeh) 0f else z
        for (c in 0..2) out[c] = mix(NEAR_COL[c], FAR_COL[c], t)
    }

    // ==================================================================================================================
    // THE CURRENT

    /** The waves, per wave: kx, ky (rad/px), amplitude, start phase, pace (rad/s). Fixed. */
    class Waves(val kx: FloatArray, val ky: FloatArray, val amp: FloatArray, val phase0: DoubleArray, val rate: DoubleArray)

    val waves: Waves by lazy {
        val r = Rng(WAVE_SEED)
        val kx = FloatArray(WAVES); val ky = FloatArray(WAVES); val amp = FloatArray(WAVES)
        val p0 = DoubleArray(WAVES); val rate = DoubleArray(WAVES)
        var s = 0.0
        for (k in 0 until WAVES) {
            kx[k] = (2.0 * PI * WAVE_N[2 * k] / BOX_W).toFloat()
            ky[k] = (2.0 * PI * WAVE_N[2 * k + 1] / BOX_H).toFloat()
            amp[k] = 0.7f + 0.6f * r.next()
            p0[k] = 2.0 * PI * r.next()
            rate[k] = (WAVE_RATE + WAVE_RATE_SPAN * r.next()) * (if (r.next() < 0.5f) -1.0 else 1.0)
            // Mean square of one gradient component, over the box and over both components: a² |k|² / 4.
            s += amp[k].toDouble().pow(2) * (kx[k] * kx[k] + ky[k] * ky[k]) / 4.0
        }
        val want = (NOISE_GRAD / FLOW_PX).toDouble()
        val scale = (want / sqrt(s)).toFloat()
        for (k in 0 until WAVES) amp[k] *= scale
        Waves(kx, ky, amp, p0, rate)
    }

    // ==================================================================================================================
    // ONE FRAME'S INPUTS (the uniforms)

    /** What the GPU gets for a frame, filled in place by DustDriver (nothing allocated per frame). */
    class Inputs {
        /** Per wave: kx, ky, amplitude, phase now. */
        val wave = FloatArray(4 * WAVES)
        /** px/s per unit of the potential's gradient, near motes (the current's pace, stirred or not). */
        var flow = FLOW_SPEED * FLOW_PX
        var windX = WIND_X
        var windY = WIND_Y
        /** The idle act's breath now (px/s, near motes). */
        var gustX = 0f
        var gustY = 0f
        /** The finger (panel px), its push now (0: none down), its speed (px/s). */
        var fingerX = 0f
        var fingerY = 0f
        var push = 0f
        var fingerVX = 0f
        var fingerVY = 0f
        /** Where the glow is round, and how strong (0..1). */
        var glowX = 0f
        var glowY = 0f
        var glow = 0f
        /** The tilt's shift at parallax 1 (px), the movement's lag at near 1 (px), and its swirl at near 1 (rad). */
        var tiltX = 0f
        var tiltY = 0f
        var lagX = 0f
        var lagY = 0f
        var spin = 0f
        /** The picture's gain (the dim and the fade), as the moon's uGain. */
        var gain = 1f
        /**
         * THE MESSAGE ISLAND'S TINT (NoticeVeil, "Island · Tint"): how much of the app's colour the motes and the beam's
         * haze take (0: none, the face as it always is), the motes' colour (linear light) and the haze's (its brightest
         * channel 1), inside the page's outline ([tintBox]: centre x, y, half width, half height; [tintShape]: corner
         * radius, soft edge; panel px). Set by the scene's loop from the veil each picture; [DRAW_FS] and [HAZE_FS] draw it.
         */
        var tint = 0f
        val tintCol = floatArrayOf(1f, 1f, 1f)
        val tintHaze = floatArrayOf(1f, 1f, 1f)
        val tintBox = floatArrayOf(W / 2f, H / 2f, W / 2f, H / 2f)
        val tintShape = floatArrayOf(0f, 1f)

        init { phases(0.0) }

        /** The waves' phases at field time [t] (s), kept within one turn (the shader's cos never sees a large angle). */
        fun phases(t: Double) {
            val w = waves
            for (k in 0 until WAVES) {
                wave[4 * k] = w.kx[k]; wave[4 * k + 1] = w.ky[k]; wave[4 * k + 2] = w.amp[k]
                val p = (w.phase0[k] + w.rate[k] * t) % (2.0 * PI)
                wave[4 * k + 3] = (if (p < 0) p + 2.0 * PI else p).toFloat()
            }
        }
    }

    /**
     * THE UNIFORMS OF A FRAME, AS TWO ARRAYS (DustGl uploads each in one call, where it made thirteen calls a frame for
     * them): [UPDATE_VS]'s `uIn` ([IN_U] vec4) and [DRAW_VS]'s `uIn` ([IN_D] vec4). What does not change from frame to
     * frame (the output's size, the gain) is set apart, only when it changes. The places, as the shaders' IN_ names say:
     *
     *     update  0 .. WAVES-1  the waves: kx, ky, amplitude, phase
     *             WAVES         step (s), steps, swirl, 0
     *             WAVES + 1     the current's pace, wind x, wind y, 0
     *             WAVES + 2     the breath x, y, the finger's speed x, y
     *             WAVES + 3     the finger x, y, its push, 0
     *             WAVES + 4     the tilt's shift x, y, the lag x, y
     *     draw    0             the tilt's shift x, y, the lag x, y
     *             1             swirl, the glow x, y, strength
     *
     * Absolute puts into [out] (its position is left at 0), nothing allocated.
     */
    fun packUpdate(inp: Inputs, dt: Float, steps: Int, out: FloatBuffer) {
        for (k in 0 until 4 * WAVES) out.put(k, inp.wave[k])
        var o = 4 * WAVES
        out.put(o, dt); out.put(o + 1, steps.toFloat()); out.put(o + 2, inp.spin); out.put(o + 3, 0f); o += 4
        out.put(o, inp.flow); out.put(o + 1, inp.windX); out.put(o + 2, inp.windY); out.put(o + 3, 0f); o += 4
        out.put(o, inp.gustX); out.put(o + 1, inp.gustY); out.put(o + 2, inp.fingerVX); out.put(o + 3, inp.fingerVY); o += 4
        out.put(o, inp.fingerX); out.put(o + 1, inp.fingerY); out.put(o + 2, inp.push); out.put(o + 3, 0f); o += 4
        out.put(o, inp.tiltX); out.put(o + 1, inp.tiltY); out.put(o + 2, inp.lagX); out.put(o + 3, inp.lagY)
    }

    /** [DRAW_VS]'s `uIn`, as [packUpdate] says. */
    fun packDraw(inp: Inputs, out: FloatBuffer) {
        out.put(0, inp.tiltX); out.put(1, inp.tiltY); out.put(2, inp.lagX); out.put(3, inp.lagY)
        out.put(4, inp.spin); out.put(5, inp.glowX); out.put(6, inp.glowY); out.put(7, inp.glow)
    }

    /** The vec4s of [UPDATE_VS]'s and [DRAW_VS]'s `uIn`. */
    const val IN_U = WAVES + 5
    const val IN_D = 2
    /** The most steps one picture's update takes ([UPDATE_VS]'s loop; DustPace.steps). */
    const val MAX_STEPS = 4

    // ==================================================================================================================
    // THE KOTLIN TWINS OF THE SHADERS

    /** Into the box: [-MARGIN, box - MARGIN). */
    fun wrap(v: Float, box: Float): Float = v - box * floor((v + MARGIN) / box)

    /**
     * Where mote [z]'s place ([x], [y], pushed) is seen: shifted by the tilt and the lag, wrapped into the box, and the
     * box then turned by the swirl about ([SPIN_X], [SPIN_Y]). Into [out].
     */
    fun seen(x: Float, y: Float, z: Float, inp: Inputs, out: FloatArray) {
        val par = PAR_NEAR - PAR_SPAN * z
        val lag = 1f - LAG_FAR * z
        val ex = wrap(x + inp.tiltX * par + inp.lagX * lag, BOX_W) - SPIN_X
        val ey = wrap(y + inp.tiltY * par + inp.lagY * lag, BOX_H) - SPIN_Y
        val a = inp.spin * lag
        val c = cos(a); val s = sin(a)
        out[0] = SPIN_X + c * ex - s * ey
        out[1] = SPIN_Y + s * ex + c * ey
    }

    /**
     * How far the box's seam stays outside the panel at a swirl of [spin] rad (the box turned about [SPIN_X], [SPIN_Y]):
     * the least distance from the panel's corners to the turned box's edges. For the tests and DustMotion.SPIN_MAX.
     */
    fun seamMargin(spin: Float): Float {
        val c = cos(spin.toDouble()); val s = sin(spin.toDouble())
        var least = Double.MAX_VALUE
        for (px in floatArrayOf(0f, W)) for (py in floatArrayOf(0f, H)) {
            // The panel's corner in the box's own (unturned) frame.
            val ex = px - SPIN_X; val ey = py - SPIN_Y
            val bx = SPIN_X + c * ex + s * ey; val by = SPIN_Y - s * ex + c * ey
            least = minOf(least, bx + MARGIN, BOX_W - MARGIN - bx, by + MARGIN, BOX_H - MARGIN - by)
        }
        return least.toFloat()
    }

    /**
     * [UPDATE_VS], in Kotlin: every mote [steps] steps of [dt] seconds on, all with the same inputs (one picture's: a
     * picture drawn at 60 while something reacts is stepped in two halves, DustPace.steps).
     */
    fun step(m: Motes, s: State, inp: Inputs, dt: Float, steps: Int = 1, scratch: FloatArray = FloatArray(2)) {
        val wv = inp.wave
        for (i in 0 until m.count) {
            val z = m.z[i]
            val near = 1f - NEAR_FALL * z
            val sp = inp.flow * (1f - FAR_SLOW * z)
            var px = s.px[i]; var py = s.py[i]
            var dx = s.dx[i]; var dy = s.dy[i]
            var vx = s.vx[i]; var vy = s.vy[i]
            for (n in 0 until steps) {
                var gx = 0f; var gy = 0f
                for (k in 0 until WAVES) {
                    val c = cos(wv[4 * k] * px + wv[4 * k + 1] * py + wv[4 * k + 3]) * wv[4 * k + 2]
                    gx += wv[4 * k] * c; gy += wv[4 * k + 1] * c
                }
                val fx = gy * sp + inp.windX + inp.gustX * near
                val fy = -gx * sp + inp.windY + inp.gustY * near
                px = wrap(px + fx * dt, BOX_W)
                py = wrap(py + fy * dt, BOX_H)
                var ax = -SPRING_K * dx - SPRING_C * vx
                var ay = -SPRING_K * dy - SPRING_C * vy
                if (inp.push > 0f) {
                    seen(px + dx, py + dy, z, inp, scratch)
                    val ex = scratch[0] - inp.fingerX; val ey = scratch[1] - inp.fingerY
                    val r = sqrt(ex * ex + ey * ey) + 0.001f
                    val fall = exp(-(r * r) / (PUSH_R * PUSH_R)) * near
                    ax += PUSH * inp.push * fall * ex / r + CARRY * inp.fingerVX * fall
                    ay += PUSH * inp.push * fall * ey / r + CARRY * inp.fingerVY * fall
                }
                vx += ax * dt; vy += ay * dt
                dx += vx * dt; dy += vy * dt
            }
            s.px[i] = px; s.py[i] = py
            s.vx[i] = vx; s.vy[i] = vy
            s.dx[i] = dx; s.dy[i] = dy
        }
    }

    /** The current's velocity at ([x], [y]) for a mote at depth [z] (px/s), into [out]: what [step] moves it by. */
    fun current(x: Float, y: Float, z: Float, inp: Inputs, out: FloatArray) {
        val wv = inp.wave
        var gx = 0f; var gy = 0f
        for (k in 0 until WAVES) {
            val c = cos(wv[4 * k] * x + wv[4 * k + 1] * y + wv[4 * k + 3]) * wv[4 * k + 2]
            gx += wv[4 * k] * c; gy += wv[4 * k + 1] * c
        }
        val sp = inp.flow * (1f - FAR_SLOW * z)
        val near = 1f - NEAR_FALL * z
        out[0] = gy * sp + inp.windX + inp.gustX * near
        out[1] = -gx * sp + inp.windY + inp.gustY * near
    }

    /**
     * THE DIM, AS THE MOON'S: the picture's gain (the dim, the fade) taken on the sRGB values, as SceneEngine's shader
     * takes it, each mote's light and the haze each on their own (where two meet, their sum is near enough). Linear
     * light [c] in, linear light out. (A gain^2.2 on linear light would take the dark end, the haze and the motes'
     * edges, down by up to three levels more than the moon's.)
     */
    fun dimmed(c: Float, gain: Float): Float = if (gain >= 0.999f) c else linear(gain.coerceIn(0f, 1f) * srgb(c))

    /** sRGB to linear light. */
    fun linear(s: Float): Float {
        val v = s.coerceIn(0f, 1f)
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    fun smoothstep(a: Float, b: Float, v: Float): Float {
        val t = ((v - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** How lit a place is by the beam (0 outside .. 1 in it), before [OUT_OF_BEAM]. */
    fun beam(x: Float, y: Float): Float =
        smoothstep(BEAM_HALF + BEAM_OUT, BEAM_HALF - BEAM_IN, kotlin.math.abs((x - BEAM_X) * BEAM_DY - (y - BEAM_Y) * BEAM_DX))

    /** The draft's soft shoulder above 0.8, per channel, so nothing clips flat. */
    fun shoulder(c: Float): Float = if (c > 0.8f) 0.8f + 0.2f * (1f - exp(-(c - 0.8f) / 0.2f)) else c

    /** One mote as [DRAW_VS] sees it: everything the fragment needs, for an output [scale] px per panel px. */
    class Sprite {
        var cx = 0f; var cy = 0f
        var amp = 0f; var halo = 0f
        /** 1 / (2 sigma²) of the core and of the bloom, in output px. */
        var core = 0f; var wide = 0f
        /** Past this squared distance (output px²) nothing is drawn; 0: the mote is too faint to draw at all. */
        var cut2 = 0f
        var r = 0f; var g = 0f; var b = 0f
    }

    /** [DRAW_VS], in Kotlin. */
    fun sprite(m: Motes, s: State, i: Int, inp: Inputs, scale: Float, out: Sprite, scratch: FloatArray) {
        val z = m.z[i]
        seen(s.px[i] + s.dx[i], s.py[i] + s.dy[i], z, inp, scratch)
        val qx = scratch[0]; val qy = scratch[1]
        val ex = qx - inp.glowX; val ey = qy - inp.glowY
        val glow = 1f + GLOW * inp.glow * exp(-(ex * ex + ey * ey) / (GLOW_R * GLOW_R))
        val lit = OUT_OF_BEAM + (1f - OUT_OF_BEAM) * beam(qx, qy)
        val w = m.b[i] * glow * lit
        val bokeh = m.sig[i] > 0.5f
        val sig = (if (bokeh) m.sig[i] else sigmaOf(z)) * scale
        val so = max(sqrt(sig * sig + SPLAT_VAR * scale * scale), MIN_SIGMA)
        val amp = w * (sig * sig) / (so * so)
        val hs = BLOOM_SIGMA * scale
        val hs2 = so * so + hs * hs
        val halo = BLOOM * amp * (so * so) / hs2
        // The sprite's reach at this gain: the light it keeps at its faint edge (an sRGB gain keeps [gain] of it there).
        val g = inp.gain.coerceIn(0f, 1f)
        val ac = amp * g; val ah = halo * g
        val rc2 = if (ac > CUT) 2f * so * so * ln(ac / CUT) else 0f
        val rh2 = if (ah > CUT) 2f * hs2 * ln(ah / CUT) else 0f
        out.cut2 = max(rc2, rh2)
        out.cx = qx * scale; out.cy = qy * scale
        out.amp = amp; out.halo = halo
        out.core = 1f / (2f * so * so); out.wide = 1f / (2f * hs2)
        val col = if (bokeh) 0f else z
        out.r = mix(NEAR_COL[0], FAR_COL[0], col); out.g = mix(NEAR_COL[1], FAR_COL[1], col); out.b = mix(NEAR_COL[2], FAR_COL[2], col)
    }

    /**
     * The whole picture, as the shaders draw it ([HAZE_FS] then every mote added in linear light), at [w] x [h], as
     * sRGB pixels (opaque ARGB). No dither (the shader adds half a level of it to the haze). For the tests and the card.
     */
    fun render(m: Motes, s: State, inp: Inputs, w: Int = W.toInt(), h: Int = H.toInt()): IntArray {
        val lin = renderLinear(m, s, inp, w, h)
        val out = IntArray(w * h)
        for (p in 0 until w * h) {
            out[p] = (0xFF shl 24) or (srgb8(lin[3 * p]) shl 16) or (srgb8(lin[3 * p + 1]) shl 8) or srgb8(lin[3 * p + 2])
        }
        return out
    }

    /** [render] before its encoding: linear light, three floats a pixel, top row first. */
    fun renderLinear(m: Motes, s: State, inp: Inputs, w: Int = W.toInt(), h: Int = H.toInt()): FloatArray {
        val scale = w / W
        val gain = inp.gain
        val lin = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val qx = (x + 0.5f) / scale; val qy = (y + 0.5f) / scale
            val v = HAZE * HAZE_BLOOM * beam(qx, qy)
            val o = 3 * (y * w + x)
            val k = if (inp.tint > 0f && v > 0f) inp.tint * tintRegion(qx, qy, inp) else 0f
            lin[o] = dimmed(mix(NEAR_COL[0], inp.tintHaze[0], k) * v, gain); lin[o + 1] = dimmed(mix(NEAR_COL[1], inp.tintHaze[1], k) * v, gain); lin[o + 2] = dimmed(mix(NEAR_COL[2], inp.tintHaze[2], k) * v, gain)
        }
        val sp = Sprite(); val scratch = FloatArray(2)
        for (i in 0 until m.count) {
            sprite(m, s, i, inp, scale, sp, scratch)
            if (sp.cut2 <= 0f) continue
            val rad = sqrt(sp.cut2)
            val x0 = max(0, floor(sp.cx - rad - 0.5f).toInt()); val x1 = minOf(w - 1, (sp.cx + rad).toInt())
            val y0 = max(0, floor(sp.cy - rad - 0.5f).toInt()); val y1 = minOf(h - 1, (sp.cy + rad).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val ex = x + 0.5f - sp.cx; val ey = y + 0.5f - sp.cy
                val r2 = ex * ex + ey * ey
                if (r2 > sp.cut2) continue
                val k = sp.amp * exp(-r2 * sp.core) + sp.halo * exp(-r2 * sp.wide)
                val o = 3 * (y * w + x)
                if (inp.tint > 0f) {
                    val region = tintRegion((x + 0.5f) / scale, (y + 0.5f) / scale, inp)
                    lin[o] += dimmed(shoulder(tinted(sp.r, inp.tintCol[0], k, region, inp) * k), gain)
                    lin[o + 1] += dimmed(shoulder(tinted(sp.g, inp.tintCol[1], k, region, inp) * k), gain)
                    lin[o + 2] += dimmed(shoulder(tinted(sp.b, inp.tintCol[2], k, region, inp) * k), gain)
                } else {
                    lin[o] += dimmed(shoulder(sp.r * k), gain); lin[o + 1] += dimmed(shoulder(sp.g * k), gain); lin[o + 2] += dimmed(shoulder(sp.b * k), gain)
                }
            }
        }
        return lin
    }

    /** How much of the page's outline a point is inside (the tint's region, [TINT_REGION] on the GPU): 0 out .. 1 in. */
    fun tintRegion(x: Float, y: Float, inp: Inputs): Float {
        val b = inp.tintBox; val r = inp.tintShape[0]; val f = max(inp.tintShape[1], 1e-3f)
        val qx = kotlin.math.abs(x - b[0]) - (b[2] - r)
        val qy = kotlin.math.abs(y - b[1]) - (b[3] - r)
        val d = sqrt(max(qx, 0f) * max(qx, 0f) + max(qy, 0f) * max(qy, 0f)) + kotlin.math.min(max(qx, qy), 0f) - r
        val u = (0.5f - d / f).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    /** A mote's colour channel [k] under the tint at [region], its light [k0] (the brightest cores keep a little white: [TINT_WHITE]). */
    fun tinted(col: Float, tint: Float, k0: Float, region: Float, inp: Inputs): Float {
        if (inp.tint <= 0f) return col
        val white = TINT_WHITE * smoothstep(0.4f, 1.2f, k0)
        val t = tint + (1f - tint) * white
        return mix(col, t, inp.tint * region * smoothstep(0.007f, 0.15f, k0))
    }

    /** The share of white the tinted motes' brightest cores keep, so they still read as light. */
    const val TINT_WHITE = 0.2f

    /** The card's picture is made again when this changes (a new look). */
    const val CARD_VERSION = 3

    /**
     * THE CARD (the picker's and the settings'): the face's first picture, at rest, drawn by [render] (the shaders'
     * twin) at the panel's size. Opaque ARGB.
     */
    fun card(): IntArray { val m = motes(); return render(m, initial(m), Inputs()) }

    fun srgb(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }

    fun srgb8(c: Float): Int = (srgb(c) * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ==================================================================================================================
    // THE SHADERS (GLSL ES 3.00). Every number is one of the constants above, put in by [DEFINES].

    private fun f(v: Float): String = String.format(Locale.ROOT, "%.7e", v)
    private fun v3(c: FloatArray) = "vec3(${f(c[0])}, ${f(c[1])}, ${f(c[2])})"

    val DEFINES: String by lazy {
        buildString {
            append("#define WAVES ").append(WAVES).append('\n')
            append("#define MAX_STEPS ").append(MAX_STEPS).append('\n')
            for ((k, v) in listOf(
                "W" to W, "H" to H, "MARGIN" to MARGIN, "BOX_W" to BOX_W, "BOX_H" to BOX_H, "NEAR_FALL" to NEAR_FALL,
                "PAR_NEAR" to PAR_NEAR, "PAR_SPAN" to PAR_SPAN, "LAG_FAR" to LAG_FAR, "MIN_SIGMA" to MIN_SIGMA, "SPLAT_VAR" to SPLAT_VAR, "CUT" to CUT,
                "BLOOM" to BLOOM, "BLOOM_SIGMA" to BLOOM_SIGMA, "HAZE_BLOOM" to HAZE_BLOOM, "BEAM_X" to BEAM_X, "BEAM_Y" to BEAM_Y,
                "BEAM_DX" to BEAM_DX, "BEAM_DY" to BEAM_DY, "BEAM_HALF" to BEAM_HALF, "BEAM_IN" to BEAM_IN, "BEAM_OUT" to BEAM_OUT,
                "OUT_OF_BEAM" to OUT_OF_BEAM, "HAZE" to HAZE, "FAR_SLOW" to FAR_SLOW, "PUSH" to PUSH, "PUSH_R" to PUSH_R,
                "CARRY" to CARRY, "SPRING_K" to SPRING_K, "SPRING_C" to SPRING_C, "GLOW" to GLOW, "GLOW_R" to GLOW_R,
                "SPIN_X" to SPIN_X, "SPIN_Y" to SPIN_Y,
                "SZ0" to SIGMA_Z[0], "SZ1" to SIGMA_Z[1], "SZ2" to SIGMA_Z[2], "SZ3" to SIGMA_Z[3],
                "SP0" to SIGMA_PX[0], "SP1" to SIGMA_PX[1], "SP2" to SIGMA_PX[2], "SP3" to SIGMA_PX[3],
            )) append("#define ").append(k).append(' ').append(f(v)).append('\n')
            append("#define NEAR_COL ").append(v3(NEAR_COL)).append('\n')
            append("#define FAR_COL ").append(v3(FAR_COL)).append('\n')
        }
    }

    /**
     * What both vertex shaders share: the box, and where a mote is seen ([seen]). Each shader says first where its
     * IN_SHIFT (the tilt's shift at parallax 1, the lag at near 1, px) and IN_SPIN (the swirl at near 1, rad) are.
     */
    private const val COMMON = """
vec2 wrapBox(vec2 p) { vec2 box = vec2(BOX_W, BOX_H); return p - box * floor((p + MARGIN) / box); }
vec2 seenAt(vec2 p, float z) {
    float par = PAR_NEAR - PAR_SPAN * z;
    float lag = 1.0 - LAG_FAR * z;
    vec2 e = wrapBox(p + IN_SHIFT.xy * par + IN_SHIFT.zw * lag) - vec2(SPIN_X, SPIN_Y);
    float a = IN_SPIN * lag;
    float c = cos(a); float s = sin(a);
    return vec2(SPIN_X + c * e.x - s * e.y, SPIN_Y + s * e.x + c * e.y);
}
"""

    /**
     * [step] on the GPU: read one state buffer, write the other (transform feedback; nothing is rasterised), in
     * IN_STEPS steps of IN_DT, all with this picture's inputs. Its uniforms are one array ([packUpdate]).
     */
    val UPDATE_VS: String by lazy {
        "#version 300 es\nprecision highp float;\n" + DEFINES + """
uniform vec4 uIn[WAVES + 5];
#define IN_DT uIn[WAVES].x                // s, each step
#define IN_STEPS uIn[WAVES].y
#define IN_SPIN uIn[WAVES].z
#define IN_FLOW uIn[WAVES + 1].xyz        // px/s per unit gradient (near), wind x, y
#define IN_GUST uIn[WAVES + 2].xy         // px/s, near
#define IN_FINGER_V uIn[WAVES + 2].zw     // px/s
#define IN_FINGER uIn[WAVES + 3].xyz      // x, y, push (0: none)
#define IN_SHIFT uIn[WAVES + 4]
""" + COMMON + """
layout(location = 0) in vec4 aMote;   // z, light, own sigma (out of focus) or 0
layout(location = 1) in vec2 aP;
layout(location = 2) in vec2 aD;
layout(location = 3) in vec2 aV;
out vec2 oP;
out vec2 oD;
out vec2 oV;
void main() {
    float z = aMote.x;
    float near = 1.0 - NEAR_FALL * z;
    float sp = IN_FLOW.x * (1.0 - FAR_SLOW * z);
    vec2 p = aP; vec2 d = aD; vec2 v = aV;
    int steps = clamp(int(IN_STEPS + 0.5), 1, MAX_STEPS);
    for (int n = 0; n < steps; n++) {
        vec2 g = vec2(0.0);
        for (int k = 0; k < WAVES; k++) {
            vec4 w = uIn[k];   // kx, ky, amplitude, phase
            g += w.xy * (cos(w.x * p.x + w.y * p.y + w.w) * w.z);
        }
        vec2 f = vec2(g.y, -g.x) * sp + IN_FLOW.yz + IN_GUST * near;
        p = wrapBox(p + f * IN_DT);
        vec2 a = -SPRING_K * d - SPRING_C * v;
        if (IN_FINGER.z > 0.0) {
            vec2 e = seenAt(p + d, z) - IN_FINGER.xy;
            float r = sqrt(dot(e, e)) + 0.001;
            float fall = exp(-(r * r) / (PUSH_R * PUSH_R)) * near;
            a += PUSH * IN_FINGER.z * fall * e / r + CARRY * IN_FINGER_V * fall;
        }
        v = v + a * IN_DT;
        d = d + v * IN_DT;
    }
    oP = p;
    oV = v;
    oD = d;
    gl_Position = vec4(0.0, 0.0, 0.0, 1.0);
}
"""
    }

    /** Nothing to draw: the update's fragment shader (never run: the rasteriser is off). */
    const val UPDATE_FS = "#version 300 es\nprecision mediump float;\nout vec4 o;\nvoid main() { o = vec4(0.0); }\n"

    /**
     * [sprite] on the GPU: each mote a quad (one instance, four corners from gl_VertexID, a triangle strip) just big
     * enough for its light above [CUT]; one too faint to show is a quad of no size, which draws nothing.
     */
    val DRAW_VS: String by lazy {
        "#version 300 es\nprecision highp float;\n" + DEFINES + """
uniform vec4 uIn[2];
#define IN_SHIFT uIn[0]
#define IN_SPIN uIn[1].x
#define IN_GLOW uIn[1].yzw   // x, y, strength
""" + COMMON + """
layout(location = 0) in vec4 aMote;
layout(location = 1) in vec2 aP;
layout(location = 2) in vec2 aD;
uniform vec4 uOut;      // output px per panel px, width, height, the picture's gain (on sRGB values)
flat out vec3 vCol;
flat out vec4 vSpot;    // centre (output px, y down), 1/(2 sigma²) of the core and of the bloom
flat out vec3 vAmp;     // core, bloom, squared reach
float sigmaOf(float z) {
    if (z < SZ1) return mix(SP0, SP1, clamp((z - SZ0) / (SZ1 - SZ0), 0.0, 1.0));
    if (z < SZ2) return mix(SP1, SP2, (z - SZ1) / (SZ2 - SZ1));
    return mix(SP2, SP3, clamp((z - SZ2) / (SZ3 - SZ2), 0.0, 1.0));
}
void main() {
    float z = aMote.x;
    vec2 q = seenAt(aP + aD, z);
    vec2 e = q - IN_GLOW.xy;
    float glow = 1.0 + GLOW * IN_GLOW.z * exp(-dot(e, e) / (GLOW_R * GLOW_R));
    float across = abs((q.x - BEAM_X) * BEAM_DY - (q.y - BEAM_Y) * BEAM_DX);
    float lit = OUT_OF_BEAM + (1.0 - OUT_OF_BEAM) * smoothstep(BEAM_HALF + BEAM_OUT, BEAM_HALF - BEAM_IN, across);
    float w = aMote.y * glow * lit;
    bool bokeh = aMote.z > 0.5;
    float sig = (bokeh ? aMote.z : sigmaOf(z)) * uOut.x;
    float so = max(sqrt(sig * sig + SPLAT_VAR * uOut.x * uOut.x), MIN_SIGMA);
    float amp = w * (sig * sig) / (so * so);
    float hs = BLOOM_SIGMA * uOut.x;
    float hs2 = so * so + hs * hs;
    float halo = BLOOM * amp * (so * so) / hs2;
    float ac = amp * uOut.w;
    float ah = halo * uOut.w;
    float rc2 = ac > CUT ? 2.0 * so * so * log(ac / CUT) : 0.0;
    float rh2 = ah > CUT ? 2.0 * hs2 * log(ah / CUT) : 0.0;
    float r2 = max(rc2, rh2);
    vCol = mix(NEAR_COL, FAR_COL, bokeh ? 0.0 : z);
    vec2 c = q * uOut.x;
    vSpot = vec4(c, 1.0 / (2.0 * so * so), 1.0 / (2.0 * hs2));
    vAmp = vec3(amp, halo, r2);
    // The corner of the quad (0..3: a strip), one pixel past the reach; none at all for a mote too faint to show.
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)) * 2.0 - 1.0;
    vec2 at = c + corner * (r2 > 0.0 ? sqrt(r2) + 1.0 : 0.0);
    gl_Position = vec4(at.x / uOut.y * 2.0 - 1.0, 1.0 - at.y / uOut.z * 2.0, 0.0, 1.0);
}
"""
    }

    /**
     * Linear light out, dimmed on its sRGB values as the moon is ([dimmed]); encoded to sRGB here only when the surface
     * does not do it (ENCODE, [encoding]). Added (ONE, ONE).
     *
     * THE SAME SUMS, ONLY WHERE THEY CHANGE ANYTHING: the shoulder is the identity for a channel under 0.8 (its own step
     * picks the channel there), so it is taken only when one is at 0.8 or over (a few cores a picture); dimmed, a
     * fragment whose every channel is in sRGB's linear toe (under 0.0031308) has toSrgb's and toLinear's straight lines
     * (x 12.92, then / 12.92: the gain under 0.999 keeps it under 0.04045), exactly what their curves pick there.
     */
    val DRAW_FS: String by lazy {
        "#version 300 es\nprecision highp float;\n" + ENCODE_OFF + SRGB + TOE + TINT_REGION + """
flat in vec3 vCol;
flat in vec4 vSpot;
flat in vec3 vAmp;
uniform vec4 uFrag;     // output height, the picture's gain (on sRGB values), unused (ENCODE), output px per panel px
out vec4 o;
vec3 shoulder(vec3 c) { return mix(c, 0.8 + 0.2 * (1.0 - exp(-(c - 0.8) / 0.2)), step(vec3(0.8), c)); }
void main() {
    vec2 e = vec2(gl_FragCoord.x, uFrag.x - gl_FragCoord.y) - vSpot.xy;
    float r2 = dot(e, e);
    if (r2 > vAmp.z) discard;
    float k = vAmp.x * exp(-r2 * vSpot.z) + vAmp.y * exp(-r2 * vSpot.w);
    vec3 col = vCol;
    // The message island's tint (none at all while uTint.w is 0: the face exactly as always).
    if (uTint.w > 0.0) {
        float region = tintRegion(vec2(gl_FragCoord.x, uFrag.x - gl_FragCoord.y) / uFrag.w);
        vec3 t = mix(uTint.rgb, vec3(1.0), $TINT_WHITE * smoothstep(0.4, 1.2, k));
        col = mix(vCol, t, uTint.w * region * smoothstep(0.007, 0.15, k));
    }
    vec3 lin = col * k;
    if (max(lin.r, max(lin.g, lin.b)) >= 0.8) lin = shoulder(lin);
    if (uFrag.y < 0.999) {
        if (max(lin.r, max(lin.g, lin.b)) < 0.0031308) lin = toeLinear(uFrag.y * toeSrgb(lin));
        else lin = toLinear(uFrag.y * toSrgb(lin));
    }
#if ENCODE
    o = vec4(toSrgb(lin), 1.0);
#else
    o = vec4(lin, 1.0);
#endif
}
"""
    }

    /**
     * THE BEAM'S BAND, the only place the haze has any light: a quad along the beam, [BEAM_HALF] + [BEAM_OUT] (and a
     * pixel) to each side, from just above where it enters to past the panel's far corner. The rest of the frame is
     * cleared to black (a clear is free on a tiling GPU, and spares it reading the last frame back).
     */
    val HAZE_VS: String by lazy {
        "#version 300 es\nprecision highp float;\n" + DEFINES + """
uniform vec3 uOutV;     // output px per panel px, width, height
void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    vec2 dir = vec2(BEAM_DX, BEAM_DY);
    vec2 across = vec2(BEAM_DY, -BEAM_DX);
    vec2 p = vec2(BEAM_X, BEAM_Y) + across * (corner.x * 2.0 - 1.0) * (BEAM_HALF + BEAM_OUT + 2.0) + dir * mix(-60.0, W + H, corner.y);
    vec2 at = p * uOutV.x;
    gl_Position = vec4(at.x / uOutV.y * 2.0 - 1.0, 1.0 - at.y / uOutV.z * 2.0, 0.0, 1.0);
}
"""
    }

    /** One triangle over the whole output, with no buffer (the clock). */
    const val FULL_VS = "#version 300 es\nvoid main() { vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)); gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }\n"

    /**
     * The message island's tint's region on the GPU ([tintRegion]): the page's outline, soft at its edge. Its uniforms:
     * uTint (colour, how much: 0 none), uTintBox (centre, half size), uTintShape (corner radius, soft edge), panel px.
     */
    private const val TINT_REGION = """
uniform vec4 uTint;
uniform vec4 uTintBox;
uniform vec2 uTintShape;
float tintRegion(vec2 q) {
    vec2 b = abs(q - uTintBox.xy) - (uTintBox.zw - vec2(uTintShape.x));
    float d = length(max(b, vec2(0.0))) + min(max(b.x, b.y), 0.0) - uTintShape.x;
    float u = clamp(0.5 - d / max(uTintShape.y, 1e-3), 0.0, 1.0);
    return u * u * (3.0 - 2.0 * u);
}
"""

    private const val SRGB = """
vec3 toSrgb(vec3 c) { c = clamp(c, 0.0, 1.0); return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c)); }
vec3 toLinear(vec3 s) { s = clamp(s, 0.0, 1.0); return mix(s / 12.92, pow((s + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), s)); }
"""

    /**
     * toSrgb and toLinear for what is known to lie in sRGB's linear toe (under 0.0031308 in linear light, under 0.04045
     * in sRGB), where their step is 0 and their mix gives the straight line: the same functions with the curve's half,
     * never taken there, made a plain product instead of three pow. Written as the curves are (the same clamp, mix and
     * step around the very same line), so the GPU's compiler makes the line's sums as it made them there: a line written
     * on its own came out a rounding apart on the PC's GPU (one level, one pixel, at some gains).
     */
    private const val TOE = """
vec3 toeSrgb(vec3 c) { c = clamp(c, 0.0, 1.0); return mix(c * 12.92, c * c, step(vec3(0.0031308), c)); }
vec3 toeLinear(vec3 s) { s = clamp(s, 0.0, 1.0); return mix(s / 12.92, s * s, step(vec3(0.04045), s)); }
"""

    /**
     * Whether a fragment shader encodes to sRGB itself (a surface that does not) is known when it is linked, not asked
     * per fragment: ENCODE, 0 in [DRAW_FS], [HAZE_FS] and [CLOCK_FS] (an sRGB surface, the usual), 1 with [encode].
     */
    private const val ENCODE_OFF = "#define ENCODE 0\n"

    fun encoding(fs: String, encode: Boolean): String {
        require(fs.contains(ENCODE_OFF)) { "a shader without ENCODE" }
        return if (encode) fs.replaceFirst(ENCODE_OFF, "#define ENCODE 1\n") else fs
    }

    /**
     * The beam's haze over the cleared frame, in its band ([HAZE_VS]). Half a level of dither where it is lit.
     *
     * IT NEVER LEAVES sRGB's LINEAR TOE (DustShaderToeTest holds it): its brightest channel is under 0.0031308 in linear
     * light, and with any gain up to 1 and the dither under 0.04045 in sRGB, never under 0. There toSrgb is its x 12.92
     * and toLinear its / 12.92 (TOE), exactly what their curves pick: the same sums, without their six pow.
     */
    val HAZE_FS: String by lazy {
        "#version 300 es\nprecision highp float;\n" + ENCODE_OFF + DEFINES + TOE + TINT_REGION + """
uniform vec4 uFrag;     // output height, the picture's gain (on sRGB values), unused (ENCODE), output px per panel px
out vec4 o;
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
void main() {
    vec2 q = vec2(gl_FragCoord.x, uFrag.x - gl_FragCoord.y) / uFrag.w;
    float across = abs((q.x - BEAM_X) * BEAM_DY - (q.y - BEAM_Y) * BEAM_DX);
    float beam = smoothstep(BEAM_HALF + BEAM_OUT, BEAM_HALF - BEAM_IN, across);
    vec3 s = toeSrgb(NEAR_COL * (HAZE * HAZE_BLOOM * beam)) * uFrag.y;
    // The beam in the app's colour under the message island (its brightest channel 1: never brighter than it is, so still
    // in the toe the shortcut holds for). Nothing of it while there is no island (uTint.w 0).
    if (uTint.w > 0.0) s = toeSrgb(mix(NEAR_COL, uTint.rgb, uTint.w * tintRegion(q)) * (HAZE * HAZE_BLOOM * beam)) * uFrag.y;
    s += (hash(floor(gl_FragCoord.xy)) - 0.5) / 255.0 * step(1.0 / 255.0, s.g);
#if ENCODE
    o = vec4(s, 1.0);
#else
    o = vec4(toeLinear(s), 1.0);
#endif
}
"""
    }

    /** The clock (SceneFace) over the dust, both its parts (no matte here): premultiplied, gained as the picture. */
    val CLOCK_FS: String by lazy {
        "#version 300 es\nprecision mediump float;\n" + ENCODE_OFF + SRGB + """
uniform sampler2D uFace;
uniform sampler2D uFront;
uniform vec4 uFrag;     // output width, output height, gain on sRGB values, unused (ENCODE)
uniform float uAlpha;
out vec4 o;
void main() {
    vec2 uv = vec2(gl_FragCoord.x / uFrag.x, 1.0 - gl_FragCoord.y / uFrag.y);
    vec4 back = texture(uFace, uv) * uAlpha;
    vec4 top = texture(uFront, uv) * uAlpha;
    vec4 c = vec4(back.rgb * (1.0 - top.a) + top.rgb, back.a + top.a * (1.0 - back.a));
    vec3 s = (c.a > 0.0 ? c.rgb / c.a : vec3(0.0)) * uFrag.z;
#if ENCODE
    o = vec4(s * c.a, c.a);
#else
    o = vec4(toLinear(s) * c.a, c.a);
#endif
}
"""
    }
}
