package dev.kb896.aiusage.rear

/**
 * THE GL CHECK'S TIMELINE, FOR THE TESTS (DustGlCheckTest, DustPaceTest): 12 s of the dust face with a finger held at
 * (700, 300) from 4.4 to 5.0 s, drawn across from 5.8 to 7.4 s, and the phone tipped (8.6 s), moved (10.4 s) and turned
 * (11.0 s) through DustMotion as its two sensors would give it. The sensors come at their own rate ([rateUs]) and the
 * finger at the touch reader's (120 a second), whatever the pictures' pace; the pictures go on the rear's 120 Hz grid
 * as [pace] says:
 *  - [Pace.PHONE]: as SceneEngine.playDust draws it now, by DustPace (120 a second while DustDriver.quick, else 60;
 *    the step by the time on the grid, in steps of 1/120 s while something reacts);
 *  - [Pace.OLD]: as it drew it before, 120 a second while something reacted (DustDriver.active), else 60, one step a
 *    picture;
 *  - [Pace.FLAT60]: 60 a second, one step a picture (what the steps are for).
 * A picture also lands on each of [stops] (ms), so two paces can be held against each other there. The finger lifts at
 * the first touch sample from 5.0 s and from 7.4 s (the touch reader's samples come every 8.3 ms, never on the dot).
 */
internal class DustTimeline(private val pace: Pace, private val rateUs: Int = DustMotion.RATE_US, private val stops: List<Int> = CUES) {
    enum class Pace { PHONE, OLD, FLAT60 }

    val motes = DustField.motes()
    val state = DustField.initial(motes)
    val driver = DustDriver()
    private val held = floatArrayOf(0f, 8.5f, -4.9f)
    private val moves = listOf(
        8600 to DustMotion.Made(held, tilt = Math.toRadians(15.0).toFloat(), turnS = 1.5f),
        10400 to DustMotion.Made(held, pushX = 3f, pushY = -1f, pushS = 0.5f),
        11000 to DustMotion.Made(held, turn = Math.toRadians(30.0).toFloat(), turnS = 0.8f),
    )
    private val a = FloatArray(3)
    private val w = FloatArray(3)
    private var nextSampleUs = 0L
    private var nextTouchUs = 0L

    private fun stroke(t: Long): Pair<Float, Float> {
        val u = ((t - 5800L) / 1600f).coerceIn(0f, 1f)
        return if (u < 0.5f) Pair(470f + 440f * u, 440f - 220f * u) else Pair(690f + 420f * (u - 0.5f), 330f - 260f * (u - 0.5f))
    }

    /** The sensors and the finger up to [us]. */
    private fun feed(us: Long) {
        while (nextSampleUs <= us) {
            val ms = nextSampleUs / 1000.0
            val mv = moves.lastOrNull { ms >= it.first }
            if (mv != null) mv.second.at(((ms - mv.first) / 1000.0).toFloat(), a, w) else { held.copyInto(a); w.fill(0f) }
            val ns = nextSampleUs * 1000L + 1
            driver.motion.gyro(w[0], w[1], w[2], ns); driver.motion.accel(a[0], a[1], a[2], ns)
            nextSampleUs += rateUs
        }
        while (nextTouchUs <= us) {
            val t = nextTouchUs / 1000L
            when {
                t in 4400L..4999L -> if (!driver.finger.down) driver.finger.down(700f, 300f, t)
                t in 5000L..5799L -> if (driver.finger.down) driver.finger.up(t)
                t in 5800L..7399L -> { val (x, y) = stroke(t); if (!driver.finger.down) driver.finger.down(x, y, t) else driver.finger.move(x, y, t) }
                t >= 7400L -> if (driver.finger.down) driver.finger.up(t)
            }
            nextTouchUs += TOUCH_US
        }
    }

    /**
     * Up to [endMs]: each picture's time (ms), its inputs, its step (s) and steps, after the motes ([state]) have been
     * stepped by the twin as the GPU steps them.
     */
    fun run(endMs: Long = 12_000L, picture: (tMs: Long, inp: DustField.Inputs, dt: Float, steps: Int) -> Unit) {
        val period = FramePace.periodNsOf(FramePace.REAR_HZ)
        val dustPace = DustPace(period)
        var refresh = 0L                 // the picture's place on the rear's grid
        while (true) {
            val nowNs: Long; val dt: Float; val steps: Int
            when (pace) {
                Pace.PHONE -> {
                    val at = dustPace.dueNs / period
                    // A stop before the next picture at 60: one refresh on instead, so a picture lands on it.
                    val stop = stops.any { val s = it * 120L / 1000L; s > at && s < at + 2 }
                    nowNs = if (dustPace.dueNs == 0L) 2 * period else dustPace.next(dustPace.dueNs, driver.quick || stop)
                    dt = dustPace.picture(nowNs)
                    steps = DustPace.steps(dt, driver.active)
                }
                Pace.OLD, Pace.FLAT60 -> {
                    var every = if (pace == Pace.OLD && driver.active) 1L else 2L
                    if (stops.any { val s = it * 120L / 1000L; s > refresh && s < refresh + every }) every = 1L
                    refresh += every
                    nowNs = refresh * period
                    dt = every / 120f
                    steps = 1
                }
            }
            val tMs = Math.round(nowNs / 1e6)
            if (tMs > endMs) return
            feed(nowNs / 1000L)
            val inp = driver.next(dt, tMs, steps)
            DustField.step(motes, state, inp, dt / steps, steps)
            picture(tMs, inp, dt, steps)
        }
    }

    companion object {
        /** The timeline's cues (ms), whose pictures are compared. */
        val CUES = listOf(2000, 4800, 6600, 9400, 10700, 11500)
        const val TOUCH_US = 8_333L
    }
}
