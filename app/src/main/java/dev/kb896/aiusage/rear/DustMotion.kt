package dev.kb896.aiusage.rear

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * THE PHONE MOVED: DUST IN THE AIR OF A BOX (asked of the dust face: reactive to the phone being moved as well, with
 * little battery). When the phone is moved or turned the motes lag behind it, swirl a little and settle again within
 * about three seconds; near motes most (DustField.LAG_FAR), never violently ([LAG_MAX], [SPIN_MAX]), and never with a
 * hole (DustField wraps the seen place, then turns the whole box). A still phone does nothing new: the box's
 * acceleration and its turning each have a dead zone above a resting hand's ([DEAD], [TURN_DEAD]), and the tilt
 * recentres by itself (TiltTracker).
 *
 * TWO SENSORS, BOTH PLAIN, AT [RATE_US] (DustSensor registers them only while the face is drawn on a lit back): the
 * accelerometer and the gyroscope. The accelerometer alone cannot tell a tilt from a push: a hand's 2 degree wobble
 * changes gravity's share along the panel by 0.34 m/s², as much as a gentle push; read as one it would swing the near
 * dust about 20 px, and a dead zone wide enough to hide it hides gentle moves too. So gravity is followed by the
 * gyroscope's turn and only pulled towards the accelerometer slowly
 * ([GRAVITY_S]), a complementary filter, and what is left is the box's own acceleration. The fused sensors
 * (TYPE_LINEAR_ACCELERATION, TYPE_GRAVITY) do the same on most phones with the same two parts, behind a fusion we do
 * not see. Without a gyroscope the accelerometer alone is used, with a quicker gravity and a wider dead zone
 * ([GRAVITY_S_ALONE], [DEAD_ALONE]): calmer, and turns about the panel's own axis are not seen.
 * From them:
 * - THE LAG: the box's acceleration in the panel's plane, past [DEAD], integrated into a change of the box's velocity;
 *   the dust's velocity against the box takes the opposite of it ([LAG_GAIN]: about 9 px per cm the phone moves
 *   briskly) and a Folme spring brings it back ([LAG_DAMPING], [LAG_RESPONSE]).
 * - THE SWIRL: the box's turn about the panel's own axis (the gyroscope's z), past [TURN_DEAD]: a change of its pace
 *   turns the dust the other way about the middle of what is seen ([SPIN_GAIN]), on the same kind of spring.
 * - A STIR from both: the current runs faster for a moment ([STIR_SPEED], [STIR_TIME]) and calms over [STIR_S]: a curl
 *   kick, the eddies the move leaves behind.
 * - THE TILT for the parallax, as the app's other parallax reads it (RearDialView: roll from x, pitch from y), from the
 *   followed gravity.
 *
 * THE PANEL'S AXES: the back is seen from behind, so its x (right as he sees it) is the phone's -x and its y (down) the
 * phone's -y; the phone's z points into the back as he sees it, so a turn about +z is clockwise to him. Taken from
 * the panel's shape and the camera's place; NOT CHECKED ON THE PHONE (desk work).
 *
 * Two threads: [accel] and [gyro] on the one the samples come on (the sensors' own from the listener, the scene's
 * through the direct channel: DustSensor), [step] on the scene's; what passes between them is under one small lock.
 * Nothing is allocated after it is made.
 */
class DustMotion {
    private val lock = Any()

    // -- the sensor's side (under lock) --------------------------------------------------------------------------------
    private var started = false
    private var lastNs = 0L
    private var gyroNs = 0L
    /** The gyroscope's last sample, for the trapezoid between two (rad/s). */
    private var wxWas = 0f; private var wyWas = 0f; private var wzWas = 0f
    /** Gravity as followed (m/s², phone axes), and whether a gyroscope is turning it. */
    private var gx = 0f; private var gy = 0f; private var gz = 0f
    private var hasGyro = false
    /** The turn about the panel's axis past its dead zone (rad/s, + clockwise to him), as last given to [step]. */
    private var turnRate = 0f
    /** Not yet taken by [step]: the box's change of velocity (m/s, panel axes), of turning pace (rad/s), the stir. */
    private var pendVX = 0f
    private var pendVY = 0f
    private var pendTurn = 0f
    private var pendStir = 0f
    /** The dust's own state to be put back to rest by the scene's thread at its next [step]. */
    private var pendReset = false
    /** A made-up movement (the debug word) has the samples until this time (the sensor's clock); the real ones wait. */
    private var madeUntilNs = 0L
    /** The tilt as last read (rad, as TiltTracker takes it), whether there is one, and whether to start it afresh. */
    private var rollIn = 0f
    private var pitchIn = 0f
    private var tiltIn = false
    private var pendTiltReset = false

    // -- the scene's side ----------------------------------------------------------------------------------------------
    /** The dust's lag behind the box (px, panel axes, near motes) and its pace. */
    var lagX = 0f; private set
    var lagY = 0f; private set
    private var lagVX = 0f
    private var lagVY = 0f
    /** The dust's swirl against the box (rad, near motes, + clockwise to him) and its pace. */
    var spin = 0f; private set
    private var spinV = 0f
    /** The stir, 0 (calm) to 1. */
    var stir = 0f; private set
    /**
     * The tilt for the parallax, -1..1 each way (TiltTracker), followed HERE, on every picture, not once a sample: the
     * sensors' 50 a second against the pictures' 60 would leave every sixth picture standing still in a tilt and step
     * the rest (several px each), and at 120 hold two or three pictures a sample.
     */
    private val tilt = TiltTracker()
    var tiltX = 0f; private set
    var tiltY = 0f; private set
    /** How fast the tilt moves the near motes now (px/s). */
    private var tiltPace = 0f

    /**
     * A sample of the accelerometer (m/s², the phone's axes) at [tNs] (the sensor's clock). On the thread the samples
     * come on (DustSensor).
     * [made]: a made-up one (the debug word), which the real ones give way to while it lasts.
     */
    fun accel(ax: Float, ay: Float, az: Float, tNs: Long, made: Boolean = false) = synchronized(lock) {
        if (!made && tNs < madeUntilNs) return@synchronized
        if (!made && madeUntilNs != 0L) {
            // A made-up movement is over: the real phone is where it is, its next sample the new rest (no jump).
            madeUntilNs = 0L; started = false; gyroNs = 0L; turnRate = 0f; wxWas = 0f; wyWas = 0f; wzWas = 0f
            tiltIn = false; pendTiltReset = true
        }
        if (!started) {
            // The rest: gravity is what the accelerometer says. The turn's pace is 0 here after a reset and the made-up
            // movement's end, and as it was after a resync (its change in a gap is still a change).
            started = true; lastNs = tNs
            gx = ax; gy = ay; gz = az
            return@synchronized
        }
        val dt = ((tNs - lastNs) / 1e9f).coerceIn(0f, MAX_GAP_S)
        lastNs = tNs
        if (dt <= 0f) return@synchronized
        // Gravity pulled towards what the accelerometer says, slowly while the gyroscope turns it.
        val k = 1f - exp(-dt / (if (hasGyro) GRAVITY_S else GRAVITY_S_ALONE))
        gx += (ax - gx) * k; gy += (ay - gy) * k; gz += (az - gz) * k
        // The box's own acceleration, in the panel's plane (phone x and y both flip): only what is past the dead zone.
        var lx = -(ax - gx); var ly = -(ay - gy)
        val dead = if (hasGyro) DEAD else DEAD_ALONE
        val m = sqrt(lx * lx + ly * ly)
        if (m > dead) { val s = (m - dead) / m; lx *= s; ly *= s } else { lx = 0f; ly = 0f }
        pendVX += lx * dt; pendVY += ly * dt
        pendStir += (m - dead).coerceAtLeast(0f) * dt
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (g > 1f) {
            rollIn = asin((gx / g).coerceIn(-1f, 1f)); pitchIn = -asin((gy / g).coerceIn(-1f, 1f)); tiltIn = true
        }
    }

    /** A sample of the gyroscope (rad/s, the phone's axes) at [tNs]. On the thread the samples come on (DustSensor). */
    fun gyro(wx: Float, wy: Float, wz: Float, tNs: Long, made: Boolean = false) = synchronized(lock) {
        if (!made && tNs < madeUntilNs) return@synchronized
        hasGyro = true
        val dt = if (gyroNs == 0L) 0f else ((tNs - gyroNs) / 1e9f).coerceIn(0f, MAX_GAP_S)
        gyroNs = tNs
        // The turn over the step between two samples: the mean of the two (a trapezoid, not the last one alone).
        val mx = (wx + wxWas) * 0.5f; val my = (wy + wyWas) * 0.5f; val mz = (wz + wzWas) * 0.5f
        wxWas = wx; wyWas = wy; wzWas = wz
        if (!started || dt <= 0f) return@synchronized
        // Gravity is fixed in the world: in the phone's frame it turns the other way (dg/dt = g x w).
        val ngx = gx + (gy * mz - gz * my) * dt
        val ngy = gy + (gz * mx - gx * mz) * dt
        val ngz = gz + (gx * my - gy * mx) * dt
        gx = ngx; gy = ngy; gz = ngz
        // The turn about the panel's own axis, past the dead zone: its change of pace swirls the dust.
        val r = if (abs(wz) > TURN_DEAD) wz - TURN_DEAD * kotlin.math.sign(wz) else 0f
        val change = r - turnRate
        pendTurn += change
        pendStir += abs(change) * STIR_PER_RAD
        turnRate = r
    }

    /** [dt] seconds on: what the sensors gave since is taken, the springs move. On the scene's thread. */
    fun step(dt: Float) {
        var dvx: Float; var dvy: Float; var dTurn: Float; var dStir: Float; var reset: Boolean
        var roll: Float; var pitch: Float; var hasTilt: Boolean; var tiltReset: Boolean
        synchronized(lock) {
            dvx = pendVX; dvy = pendVY; dTurn = pendTurn; dStir = pendStir; reset = pendReset
            roll = rollIn; pitch = pitchIn; hasTilt = tiltIn; tiltReset = pendTiltReset
            pendVX = 0f; pendVY = 0f; pendTurn = 0f; pendStir = 0f; pendReset = false; pendTiltReset = false
        }
        if (reset) { lagX = 0f; lagY = 0f; lagVX = 0f; lagVY = 0f; spin = 0f; spinV = 0f; stir = 0f }
        if (reset || tiltReset) { tilt.reset(); tiltX = 0f; tiltY = 0f; tiltPace = 0f }
        // The tilt, followed on this picture's own time (TiltTracker's smoothing and recentring), from the last reading.
        if (hasTilt && dt > 0f) {
            val wasX = tiltX; val wasY = tiltY
            tilt.update(roll, pitch, (dt * 1000f).roundToLong().coerceAtLeast(1L))
            tiltX = tilt.x; tiltY = tilt.y
            tiltPace = hypot((tiltX - wasX) * TILT_NEAR_X, (tiltY - wasY) * TILT_NEAR_Y) / dt
        } else tiltPace = 0f
        // The box sped up by dv: the dust, left behind, goes the other way against it.
        lagVX = (lagVX - LAG_GAIN * dvx).coerceIn(-LAG_PACE_MAX, LAG_PACE_MAX)
        lagVY = (lagVY - LAG_GAIN * dvy).coerceIn(-LAG_PACE_MAX, LAG_PACE_MAX)
        // The box began to turn clockwise to him: the dust keeps its place in the world, so against the box it turns
        // the other way.
        spinV = (spinV - SPIN_GAIN * dTurn).coerceIn(-SPIN_PACE_MAX, SPIN_PACE_MAX)
        if (dt > 0f) {
            lagVX += (-LAG_K * lagX - LAG_C * lagVX) * dt; lagX += lagVX * dt
            lagVY += (-LAG_K * lagY - LAG_C * lagVY) * dt; lagY += lagVY * dt
            spinV += (-LAG_K * spin - LAG_C * spinV) * dt; spin += spinV * dt
            stir = (stir * exp(-dt / STIR_S) + STIR_GAIN * dStir).coerceAtMost(1f)
        }
    }

    /** The lag as seen: softly held under [LAG_MAX] however hard the phone is shaken. */
    fun seenLagX(): Float = soft(lagX, lagY) * lagX
    fun seenLagY(): Float = soft(lagX, lagY) * lagY
    private fun soft(x: Float, y: Float): Float {
        val m = sqrt(x * x + y * y)
        return if (m < 1e-3f) 1f else LAG_MAX * tanh(m / LAG_MAX) / m
    }
    /** The swirl as seen: softly held under [SPIN_MAX]. */
    fun seenSpin(): Float = SPIN_MAX * tanh(spin / SPIN_MAX)

    /**
     * How fast the phone's movement moves the near motes on the panel now, at most (px/s): the lag's pace, the swirl's
     * at the panel's farthest corner from its middle, the tilt's, and what the stir adds to the current's fast motes.
     */
    val pace: Float
        get() = hypot(lagVX, lagVY) + abs(spinV) * SPIN_REACH + tiltPace + stir * STIR_SPEED * DustField.CURRENT_FAST

    /**
     * It moves the dust fast enough to count as reacting (DustDriver.active: its pictures stepped in steps of 1/120 s,
     * DustPace.steps): by what is seen, not by whether its springs have settled to the last fraction of a pixel
     * ([PACE_FAST]).
     */
    val active: Boolean get() = pace > PACE_FAST

    /**
     * It moves the dust too fast for 60 pictures a second ([PACE_SMOOTH]), with the idle act's breath adding [breath]
     * px/s now (DustDriver): every refresh of the rear then (DustDriver.quick).
     */
    fun quick(breath: Float = 0f): Boolean = pace + breath > PACE_SMOOTH

    /**
     * Fresh: the sensors start again (the face shown again, uncovered): their first sample is the new rest, and the dust
     * is put back to rest at the scene's next [step]. Any thread.
     */
    fun reset() = synchronized(lock) {
        started = false; gyroNs = 0L; hasGyro = false; turnRate = 0f; wxWas = 0f; wyWas = 0f; wzWas = 0f
        pendVX = 0f; pendVY = 0f; pendTurn = 0f; pendStir = 0f; pendReset = true
        tiltIn = false
    }

    /**
     * The samples go on after a gap in which some were lost (the direct channel's reading started again at the newest
     * after the scene's thread stalled, DirectReports.Session): gravity is taken afresh from the next accelerometer
     * sample and the gyroscope's from its next, as at a start, so the phone's turn in the gap, never followed, is not
     * read as a push. The dust's springs, the stir and the turn's pace are kept. Any thread.
     */
    fun resync() = synchronized(lock) {
        if (!started) return@synchronized
        started = false; gyroNs = 0L; wxWas = 0f; wyWas = 0f; wzWas = 0f
    }

    /** A made-up movement has the samples until [untilNs] (the sensor's clock); gravity as followed now, into [out]. */
    fun makeUp(untilNs: Long, out: FloatArray) = synchronized(lock) {
        madeUntilNs = untilNs
        if (started) { out[0] = gx; out[1] = gy; out[2] = gz } else { out[0] = 0f; out[1] = G; out[2] = 0f }
    }

    /**
     * A MADE-UP MOVEMENT, as the two sensors would give it (the debug word, and the tests): from rest with gravity [g0]
     * (phone axes, m/s²), a MOVE of the box along the panel that starts and stops ([pushX], [pushY] m/s² at its
     * briskest, over [pushS] seconds: one sine of acceleration, so it ends at rest, [pushX] x [pushS]² / 2π metres on), a
     * TURN about the panel's axis by [turn] rad (clockwise to him) and a TILT left or right (about the phone's y) by
     * [tilt] rad, each there and back over [turnS] (a sine of pace). Pure.
     */
    class Made(
        private val g0: FloatArray,
        val pushX: Float = 0f, val pushY: Float = 0f, val pushS: Float = 0f,
        val turn: Float = 0f, val tilt: Float = 0f, val turnS: Float = 1f,
    ) {
        /** How long it lasts (s), and the still second and a half after it in which the dust settles. */
        val lastsS: Float get() = maxOf(pushS, if (turn != 0f || tilt != 0f) turnS else 0f)

        /** At [t] s: the accelerometer into [accel] and the gyroscope into [gyro]. */
        fun at(t: Float, accel: FloatArray, gyro: FloatArray) {
            // There and back: the angle 0 -> peak -> 0 over turnS, its pace the derivative.
            val u = (t / turnS).coerceIn(0f, 1f)
            val shape = (0.5f * (1f - kotlin.math.cos(2f * PI.toFloat() * u)))
            val pace = if (t in 0f..turnS) (PI.toFloat() / turnS) * kotlin.math.sin(2f * PI.toFloat() * u) else 0f
            val th = turn * shape; val ph = tilt * shape
            // Gravity turned the other way in the phone's frame: about z by -th, then about y by -ph.
            val c = kotlin.math.cos(th); val s = kotlin.math.sin(th)
            val x1 = g0[0] * c + g0[1] * s; val y1 = -g0[0] * s + g0[1] * c; val z1 = g0[2]
            val cp = kotlin.math.cos(ph); val sp = kotlin.math.sin(ph)
            val push = if (pushS > 0f && t in 0f..pushS) kotlin.math.sin(2f * PI.toFloat() * t / pushS) else 0f
            // The box's acceleration along the panel is the phone's the other way (x and y flip), the reading's minus it.
            accel[0] = x1 * cp - z1 * sp - pushX * push
            accel[1] = y1 - pushY * push
            accel[2] = x1 * sp + z1 * cp
            gyro[0] = 0f; gyro[1] = tilt * pace; gyro[2] = turn * pace
        }
    }

    companion object {
        /**
         * Both sensors' pace: 50 a second, no batching (the main processor is awake anyway while the back is lit). Not
         * lower: at 25 a second the tilt, followed on every picture through TiltTracker's 60 ms smoothing, moves in
         * spurts (its pace picture to picture swings about twice as far as at 50), and the swirl after a turn comes out
         * up to 4 px elsewhere (DustPaceTest). Each sample costs our side well under a microsecond (no allocation); what
         * a lower rate would spare is the sensor thread's wake-ups, a small share of the dust's cost.
         */
        const val RATE_US = 20_000
        /** A gap between two samples longer than this is taken as this (a stall must not throw the dust). */
        const val MAX_GAP_S = 0.1f
        const val G = 9.81f
        /** Gravity's pull towards the accelerometer: slow with the gyroscope turning it, quick without. */
        const val GRAVITY_S = 1.0f
        const val GRAVITY_S_ALONE = 0.35f
        /** The box's acceleration under this (m/s²) is nothing: a resting hand's is 0.02 to 0.1. Wider with no gyroscope. */
        const val DEAD = 0.15f
        const val DEAD_ALONE = 0.6f
        /** The dust's lag per m/s of the box's change of velocity, px/s: about 9 px per cm of a brisk move, near motes. */
        const val LAG_GAIN = 900f
        /** Its spring: Folme damping and response (s); settled within about three seconds. */
        const val LAG_DAMPING = 0.7f
        const val LAG_RESPONSE = 1.8f
        val LAG_K = ((2 * PI / LAG_RESPONSE) * (2 * PI / LAG_RESPONSE)).toFloat()
        val LAG_C = (4 * PI * LAG_DAMPING / LAG_RESPONSE).toFloat()
        /** Never further than this (px, near motes; softly), never faster than [LAG_PACE_MAX] px/s. */
        const val LAG_MAX = 80f
        const val LAG_PACE_MAX = 600f
        /** A turn about the panel's axis under this (rad/s) is nothing (a resting hand, the gyroscope's own noise). */
        const val TURN_DEAD = 0.05f
        /** The swirl per rad/s of the box's change of turning pace: a third of the turn; never past [SPIN_MAX] rad. */
        const val SPIN_GAIN = 0.35f
        const val SPIN_MAX = 0.12f
        const val SPIN_PACE_MAX = 1.5f
        /**
         * The stir: per m/s of the box's change of velocity past the dead zone, and per rad/s of change of its turn; it
         * calms over [STIR_S]. A brisk 12 cm move stirs about 0.4, a wrist's 30 degree wiggle about 0.3.
         */
        const val STIR_GAIN = 0.4f
        const val STIR_PER_RAD = 0.1f
        const val STIR_S = 1.5f
        /** At a full stir the current runs this much faster, and its eddies change this much faster. */
        const val STIR_SPEED = 1.5f
        const val STIR_TIME = 2.0f
        /**
         * The movement's [pace] over this (px/s, near motes) is reacting: half a pixel a picture at 60 on top of the
         * current's own. A hand's small wobble past the dead zones moves the dust by less.
         */
        const val PACE_FAST = 30f
        /**
         * THE MOST THAT IS DRAWN AT 60 (px/s, near motes): the movement's [pace] and the breath's together over this need
         * every refresh of the rear ([quick]). It is how fast the dust went in the 60 pictures a second the face was
         * first drawn with: they took the movement up to [PACE_FAST] on top of the current's fast motes at the top of a
         * breath (DustField.CURRENT_FAST + DustField.GUST_PACE + PACE_FAST, about 112 px/s, 1.9 px a picture). Under
         * it, no mote steps further between two pictures at 60 than it could then; over it, 120 as then.
         */
        val PACE_SMOOTH = PACE_FAST + DustField.GUST_PACE
        /** The farthest the panel goes from the swirl's middle (px): where a swirl moves the dust fastest. */
        val SPIN_REACH = hypot(maxOf(DustField.SPIN_X, DustField.W - DustField.SPIN_X), maxOf(DustField.SPIN_Y, DustField.H - DustField.SPIN_Y))
        /** A near mote's shift per unit of tilt (px): the app's parallax margin, at DustField.PAR_NEAR. */
        const val TILT_NEAR_X = Parallax.MARGIN * DustField.W * DustField.PAR_NEAR
        const val TILT_NEAR_Y = Parallax.MARGIN * DustField.H * DustField.PAR_NEAR
    }
}

/**
 * THE FINGER ON THE BACK, for the dust (the shell's rear touch reader; panel px). The dust only SHOWS it: what a tap or a
 * stroke does is the shell's as before. Down, moves and up on the touch reader's thread; [frame] on the scene's.
 * A finger not heard of for [STALE_MS] is taken as gone (a reader that lost it must not leave a clearing for good).
 */
class DustFinger {
    private val lock = Any()
    private var x = 0f
    private var y = 0f
    private var isDown = false
    private var downAt = 0L
    private var upAt = Long.MIN_VALUE / 4
    private var lastAt = 0L
    private var vx = 0f
    private var vy = 0f

    fun down(x: Float, y: Float, atMs: Long) = synchronized(lock) {
        this.x = x; this.y = y; isDown = true; downAt = atMs; lastAt = atMs; vx = 0f; vy = 0f
    }

    fun move(x: Float, y: Float, atMs: Long) = synchronized(lock) {
        if (!isDown) return@synchronized
        val dt = ((atMs - lastAt) / 1000f).coerceAtLeast(MIN_STEP_S)
        val k = 1f - exp(-dt / SPEED_S)
        vx += ((x - this.x) / dt - vx) * k; vy += ((y - this.y) / dt - vy) * k
        this.x = x; this.y = y; lastAt = atMs
    }

    /** Lifted (or taken by something else: the picker, a page it turned). */
    fun up(atMs: Long) = synchronized(lock) {
        if (!isDown) return@synchronized
        isDown = false; upAt = atMs
    }

    /**
     * Forgotten, as if never down: the show it was drawn in has ended (SceneEngine.playDust). A finger still on a back
     * that went dark lifts unheard (the touch reaches the dust only while it shows), and the next show must not open
     * with a clearing and a glow where it was.
     */
    fun reset() = synchronized(lock) {
        isDown = false; upAt = Long.MIN_VALUE / 4; vx = 0f; vy = 0f
    }

    val down: Boolean get() = synchronized(lock) { isDown }

    /** This frame's finger into [inp]: the push, its speed, the glow. */
    fun frame(nowMs: Long, inp: DustField.Inputs) = synchronized(lock) {
        if (isDown && nowMs - lastAt > STALE_MS) { isDown = false; upAt = nowMs }
        val since = (nowMs - downAt) / 1000f
        inp.fingerX = x; inp.fingerY = y; inp.glowX = x; inp.glowY = y
        // A finger at rest sends nothing: its last speed fades out.
        val idle = ((nowMs - lastAt) / 1000f - IDLE_S).coerceAtLeast(0f)
        val fade = exp(-idle / SPEED_S)
        inp.fingerVX = if (isDown) vx * fade else 0f
        inp.fingerVY = if (isDown) vy * fade else 0f
        inp.push = if (isDown) (since / DustField.PUSH_RAMP_S).coerceIn(0f, 1f) else 0f
        val rise = 1f - (1f - (since / DustField.GLOW_IN_S).coerceIn(0f, 1f)).let { it * it * it }
        inp.glow = if (isDown) rise else rise * exp(-((nowMs - upAt) / 1000f).coerceAtLeast(0f) / DustField.GLOW_OUT_S)
    }

    /** Down, or let go of so lately that the motes are still on their way back. */
    fun active(nowMs: Long): Boolean = synchronized(lock) { isDown || nowMs - upAt < SETTLE_MS }

    companion object {
        /** The finger's speed is smoothed over this, and fades this fast once it stops sending. */
        const val SPEED_S = 0.03f
        const val IDLE_S = 0.04f
        const val MIN_STEP_S = 0.004f
        /** After a lift the motes are back within about this (the spring's response twice). */
        const val SETTLE_MS = 1_600L
        /** A finger down and silent this long is gone (a held one opens the picker long before: SceneTouch.PICKER_HOLD_MS). */
        const val STALE_MS = 8_000L
    }
}
