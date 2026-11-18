package dev.kb896.aiusage.rear

import kotlin.math.PI
import kotlin.math.sin

/**
 * ONE FRAME OF THE DUST FACE'S INPUTS (DustField.Inputs, the shaders' uniforms), from the clock, the finger
 * (DustFinger), the phone's movement and tilt (DustMotion) and the idle act. Its own clock starts with the show. Pure:
 * the scene's thread calls [next] once a picture; nothing is allocated there.
 *
 * ITS PACE (DustPace): 120 PICTURES A SECOND WHERE 60 WOULD BE SEEN TO STEP, 60 EVERYWHERE ELSE.
 * - [quick]: every refresh of the rear (120 a second) while a finger is down or its motes are on their way back, and
 *   while the phone's movement with the breath moves the dust faster than 60 pictures a second ever drew it
 *   (DustMotion.PACE_SMOOTH), and [QUICK_HOLD_S] after that, so a pace about that line (the tilt's comes in spurts
 *   with the sensors' samples) does not switch the pace from picture to picture. A pushed mote moves up to several
 *   hundred px a second, which at 60 would step (the draft's 60 fps render showed it as a smear): hence 120 then, one
 *   step of the simulation a picture, as before.
 * - otherwise every other refresh (60). At rest a near mote drifts about 22 px a second (a third of a pixel a picture),
 *   its fast ones 53 (DustField.CURRENT_FAST), and at the top of a breath of the idle act the very fastest about 90
 *   (1.5 px a picture): smooth at 60, with a soft edge, never a step. Under 60 the fastest would step 3 px at a time.
 *   Half the pictures at rest is half the GPU's and the compositor's work.
 * - [active]: SOMETHING REACTS (a finger, or the movement past DustMotion.PACE_FAST). Drawn at 60 (the movement slow
 *   enough), a picture is then stepped in steps of 1/120 s (DustPace.steps), the phone's movement's springs here and
 *   the motes on the GPU, as the 120 pictures a second stepped them: the same motion, half the pictures.
 */
class DustDriver(
    val finger: DustFinger = DustFinger(),
    val motion: DustMotion = DustMotion(),
    seed: Long = DustField.SEED,
) {
    val inputs = DustField.Inputs()
    private val rng = DustField.Rng(seed * 31 + 11)
    /** Seconds since the show began, and the current's own time (it runs faster while stirred). */
    var time = 0.0
        private set
    private var fieldTime = 0.0
    private var gustAt = DustField.GUST_FIRST_S.toDouble()

    /** Something reacts: a picture drawn at 60 is stepped in steps of 1/120 s (DustPace.steps). */
    var active = false
        private set

    /** A finger is down or its motes are on their way back (DustFinger.active). */
    var touched = false
        private set

    /** Every refresh of the rear for the next picture (DustPace.next): see the class. */
    var quick = false
        private set
    private var quickUntil = -1.0

    /**
     * [dt] seconds after the last picture, at [nowMs] (the touch reader's clock, uptime): this picture's inputs. The
     * phone's movement's springs are stepped in [steps] equal steps (the motes' steps on the GPU, DustPace.steps).
     */
    fun next(dt: Float, nowMs: Long, steps: Int = 1): DustField.Inputs {
        time += dt
        val n = steps.coerceIn(1, DustField.MAX_STEPS)
        val h = dt / n
        for (k in 0 until n) motion.step(h)
        val stir = motion.stir
        fieldTime += dt * (1.0 + DustMotion.STIR_TIME * stir)
        val inp = inputs
        inp.phases(fieldTime)
        inp.flow = DustField.FLOW_SPEED * DustField.FLOW_PX * (1f + DustMotion.STIR_SPEED * stir)
        inp.windX = DustField.WIND_X; inp.windY = DustField.WIND_Y
        // The idle act: a breath of current now and then.
        while (time > gustAt + DustField.GUST_S) gustAt += DustField.GUST_EVERY_S + DustField.GUST_JITTER_S * (2f * rng.next() - 1f)
        val b = gust(time - gustAt)
        inp.gustX = DustField.GUST_X * b; inp.gustY = DustField.GUST_Y * b
        finger.frame(nowMs, inp)
        // The app's parallax: the ground moves against the tip (RearDialView), near motes most.
        inp.tiltX = -motion.tiltX * Parallax.MARGIN * DustField.W
        inp.tiltY = -motion.tiltY * Parallax.MARGIN * DustField.H
        inp.lagX = motion.seenLagX(); inp.lagY = motion.seenLagY()
        inp.spin = motion.seenSpin()
        touched = finger.active(nowMs)
        active = touched || motion.active
        val fast = motion.quick(DustField.GUST_PACE * b)
        if (fast) quickUntil = time + QUICK_HOLD_S
        quick = touched || fast || time < quickUntil
        return inp
    }

    companion object {
        /** After the last picture the movement needed every refresh for, every refresh this long still (s). */
        const val QUICK_HOLD_S = 0.1

        /** The breath's shape at [t] seconds into it: a sine² bump over DustField.GUST_S, 0 outside. */
        fun gust(t: Double): Float {
            if (t < 0.0 || t > DustField.GUST_S) return 0f
            val s = sin(PI * t / DustField.GUST_S)
            return (s * s).toFloat()
        }
    }
}
