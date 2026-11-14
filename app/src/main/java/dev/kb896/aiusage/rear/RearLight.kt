package dev.kb896.aiusage.rear

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * How bright the back is, BY THE LIGHT AROUND IT.
 *
 * WHAT WAS FOUND ON THE PHONE (`dumpsys display`): the rear panel is already on
 * Xiaomi's own automatic brightness, by a light sensor of its own beside the rear screen
 * (`tcs3760_sec_back`, type `xiaomi.sensor.back_lux`). That takes it up outdoors (to 600 nits and
 * beyond in the sun). But its FLOOR is 35 nits in darkness (0 lux: 35.2 nits; 4 lux: 40.4), which is very bright in a
 * dark room. So the panel is left to Xiaomi (it keeps the back readable in the sun, and nothing of ours can make it
 * too dark to find again), and this says how much of the panel's light OUR picture gives: little in the dark, all of
 * it in daylight.
 *
 * [share] is the curve: lux to a share of the panel's own light, smooth in the logarithm of the
 * lux (as the eye is). [POINTS] are its points. Then what the back is doing MULTIPLIES it
 * ([state]): in the hand a little under the curve, face down lower, a tap a boost for a while, a
 * double tap's dim a step down, saving battery lower still; the camera open, all the curve gives.
 * A slider in the settings moves all of it up or down ([bias]).
 *
 * The picture is darkened in its numbers, and a screen's light goes by their 2.2th power:
 * [gain] turns a share of light into the factor the picture's numbers are multiplied by.
 *
 * [Steady] keeps a passing hand from being followed, and [Ease] makes every change a slow one.
 * All plain, so a test holds it.
 */
object RearLight {
    /** Lux, and the share of the panel's own light the back gives there (before what it is doing). */
    val POINTS: List<Pair<Float, Float>> = listOf(
        0f to 0.10f,        // a dark room: 3.5 nits of the panel's 35
        5f to 0.14f,        // a screen's glow, a night light
        20f to 0.22f,       // one lamp across the room
        60f to 0.36f,       // a living room in the evening
        200f to 0.62f,      // a lit room
        600f to 0.85f,      // a bright room, a window by day
        1_500f to 1f,       // daylight: all the panel gives (which Xiaomi has raised by then)
    )

    /** With no reading at all: as in a lit room, which is the level the back had before this. */
    const val NO_READING_LUX = 200f

    private fun x(lux: Float) = ln(1f + lux.coerceAtLeast(0f))

    /** The share of the panel's light at [lux]: through [POINTS], eased from each to the next. */
    fun share(lux: Float): Float {
        if (lux.isNaN()) return share(NO_READING_LUX)
        if (lux <= POINTS.first().first) return POINTS.first().second
        if (lux >= POINTS.last().first) return POINTS.last().second
        val i = POINTS.indexOfLast { it.first <= lux }
        val (l0, s0) = POINTS[i]
        val (l1, s1) = POINTS[i + 1]
        val t = (x(lux) - x(l0)) / (x(l1) - x(l0))
        return s0 + (s1 - s0) * (t * t * (3f - 2f * t))
    }

    /** What the back is doing, as far as its light goes. [level]: StayOn.Level's ordinal (1 a dim still while saving, 2 face down, 3 in the hand, 4 the camera). */
    data class Doing(val level: Int, val tapped: Boolean = false, val dimmedByHand: Boolean = false)

    /** What the posture multiplies the curve by for [d]. */
    fun posture(d: Doing): Float = when (d.level) {
        1 -> SAVING
        2 -> FACE_DOWN
        // 0: the rule has the back off and he lit it himself (the front off, a double tap): as in the hand, not at
        // the camera's full strength (the review's 34).
        0, 3 -> IN_HAND
        else -> 1f
    }

    /** What his own hand multiplies it by: a tap, or a double tap's dim. A tap wins: it is asked for now. */
    fun hand(d: Doing): Float = when {
        d.tapped -> TAP_BOOST
        d.dimmedByHand -> HAND_DIM
        else -> 1f
    }

    /** What multiplies the curve for [d]: the posture, and his hand. */
    fun state(d: Doing): Float = posture(d) * hand(d)

    /** The slider: [bias] -1 (dimmer) to 1 (brighter), nothing at 0. Each end is two and a half times. */
    fun bias(bias: Float): Float = BIAS_SPAN.pow(bias.coerceIn(-1f, 1f))

    /**
     * The share of the panel's light the back gives: the curve at [lux], times the posture, times the
     * slider; then his hand (a tap, a dim). Never under [FLOOR] (it can still be read, and found),
     * never over all of it. A tap lifts it to at least [TAP_LEAST], so a tap in a dark room is
     * something to see.
     *
     * [panelNits]: what the panel itself gives now (RearPanel), when that is known. The curve was
     * drawn over Xiaomi's 35 nits in the dark, but the panel is not always there: in one night it stood at 1 to 2
     * nits in the same darkness, and a tenth of that is no back at all. So before his hand, the back gives at least
     * [LEAST_NITS] (times the posture and the slider) as far as the panel has them: of a panel at 35 nits that is the
     * curve's own dark end, of one at 2 nits it is all of it.
     */
    fun light(lux: Float, d: Doing, bias: Float = 0f, panelNits: Float = Float.NaN): Float {
        val scale = posture(d) * bias(bias)
        var v = share(lux) * scale
        if (panelNits > 0f) v = maxOf(v, LEAST_NITS * scale / panelNits)
        v = (v.coerceAtMost(1f) * hand(d)).coerceIn(FLOOR, 1f)
        return if (d.tapped) maxOf(v, TAP_LEAST) else v
    }

    /** The factor for the picture's numbers that gives [light] of the light. */
    fun gain(light: Float): Float = light.coerceIn(0f, 1f).pow(1f / GAMMA)

    /** The light a picture multiplied by [gain] gives. */
    fun lightOf(gain: Float): Float = gain.coerceIn(0f, 1f).pow(GAMMA)

    const val GAMMA = 2.2f
    const val IN_HAND = 0.85f
    /**
     * Lying on its front the back is what he looks at: BRIGHT, the whole of what the room's light asks for. It was
     * 0.40, the dim posture, until then.
     */
    const val FACE_DOWN = 1.0f
    const val SAVING = 0.30f
    const val TAP_BOOST = 3f
    const val TAP_LEAST = 0.22f
    const val HAND_DIM = 0.40f
    const val BIAS_SPAN = 2.5f
    const val FLOOR = 0.02f
    /** The curve's dark end in nits: a tenth of the 35 Xiaomi's panel gives in darkness. */
    const val LEAST_NITS = 3.5f

    /**
     * The lux the back goes by: not every reading. A reading is followed only when it is clearly
     * another light (half as much again, or a third less, and at least [LEAST] lux away) and has
     * STAYED so: a brighter one for [UP_MS], a darker one for [DOWN_MS]. A hand passing over the
     * sensor is dark for a moment and is never followed; a lamp switched on is, within a second.
     * The sensor only speaks when its reading changes, so [at] is asked on a clock as well.
     */
    class Steady {
        private var held = Float.NaN
        private var latest = Float.NaN
        private var differsSince = 0L
        private var rising = false

        /** A reading, at [now] (any steady clock, ms). */
        @Synchronized fun feed(lux: Float, now: Long) {
            if (lux.isNaN() || lux < 0f) return
            latest = lux
            if (held.isNaN()) { held = lux; differsSince = 0L; return }
            val other = differs(lux, held)
            val up = lux > held
            if (!other) differsSince = 0L
            else if (differsSince == 0L || up != rising) { differsSince = now; rising = up }
        }

        /** The lux in force at [now]; NaN before the first reading. */
        @Synchronized fun at(now: Long): Float {
            // Before the first reading after a wake: the last one there was, if any.
            if (held.isNaN()) return latest
            if (differsSince != 0L && now - differsSince >= (if (rising) UP_MS else DOWN_MS)) { held = latest; differsSince = 0L }
            return held
        }

        /** The back went dark: what it wakes to may be another room, so the next reading is taken as it comes. */
        @Synchronized fun fresh() { held = Float.NaN; differsSince = 0L }

        companion object {
            const val UP_MS = 800L
            const val DOWN_MS = 2_500L
            const val LEAST = 2f
            fun differs(a: Float, b: Float): Boolean = abs(a - b) >= LEAST && (a > b * 1.5f || a < b * 0.67f)
        }
    }

    /** A value that goes to where it is sent over a time, eased at both ends; sent again on its way, it goes on from where it is. */
    class Ease(private var from: Float) {
        private var to = from
        private var since = 0L
        private var over = 1L

        val target: Float get() = to

        fun send(value: Float, now: Long, ms: Long) {
            if (value == to) return
            from = at(now); to = value; since = now; over = ms.coerceAtLeast(1L)
        }

        /** There at once. */
        fun set(value: Float) { from = value; to = value }

        fun at(now: Long): Float {
            val t = ((now - since).toFloat() / over).coerceIn(0f, 1f)
            return from + (to - from) * (t * t * (3f - 2f * t))
        }

        fun moving(now: Long): Boolean = from != to && now - since < over
    }

    /** A change of the light around takes this long on the back; one he asked for by hand, this. */
    const val EASE_MS = 1_600L
    const val EASE_BY_HAND_MS = 260L
}
