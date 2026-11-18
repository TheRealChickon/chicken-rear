package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Paint
import dev.kb896.aiusage.data.MascotMood
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The Claude mascot ALIVE on the usage page. The calm is the fluid's and the page's; the life is the mascot's. He is
 * a character, not a sticker that slides.
 *
 * TWO WAYS HE IS DRAWN, at one size and on one spot, so going from one to the other moves nothing:
 * - THE FIGURE ITSELF ([Rig], drawn by [draw]): upstream's nine rectangles (a body, two hands, two
 *   eyes, four legs; WalkRig's geometry), each of which can move: legs that step, a body that
 *   leans, bobs and is squashed, eyes that look and blink, hands that lift. Standing awake he
 *   breathes, blinks, looks about and shifts his weight ([idle]); he WALKS along his meter on his
 *   legs ([walk]); he HOPS to another meter with a crouch, a stretch in the air and a squash as he
 *   lands ([hop]).
 * - UPSTREAM'S DRAWN CYCLES (MascotFrames, MascotPoses), PLAYED, not held on their first drawing:
 *   at work with the dumbbells while the five hours are being used, the white flag near the limit,
 *   asleep and breathing with its Zs when they are spent, confetti when a window starts again.
 *
 * WHAT HE DOES AND WHEN is [Actor]'s: a string of doings, each with an end, with rests between
 * them, so he never stands frozen and never loops without a pause. Everything is eased; nothing
 * jumps from one place to another.
 *
 * Plain numbers but for [draw], so a test holds the rest.
 */
object MascotLive {
    const val W = 107f
    const val H = 86f
    private val LEG_X = floatArrayOf(11f, 32f, 64f, 85f)
    private const val LEG_Y = 60f
    private const val LEG_W = 11f
    private const val LEG_H = 26f

    /** The figure at one moment, in its own units (107 x 86) and degrees. At rest: all nothing, [squash] and [blink] 1. */
    class Rig {
        /** Its height about its feet (under 1 squashed, over 1 stretched); its width goes the other way. */
        var squash = 1f
        /** The body turned about its hips, moved sideways, moved up (negative) or down. */
        var lean = 0f
        var sway = 0f
        var bob = 0f
        /** The eyes moved, and how open they are (1 open, near 0 shut). */
        var eyeX = 0f
        var eyeY = 0f
        var blink = 1f
        /** The hands moved up (negative) or down. */
        var hands = 0f
        /** Each leg turned about its top. */
        val leg = FloatArray(4)

        fun rest() { squash = 1f; lean = 0f; sway = 0f; bob = 0f; eyeX = 0f; eyeY = 0f; blink = 1f; hands = 0f; leg.fill(0f) }

        /** Everything about it in one number, for telling whether it moved (tests). */
        fun signature(): Float = squash * 31f + lean * 7f + sway * 5f + bob * 3f + eyeX * 2f + eyeY * 1.7f + blink * 11f + hands * 1.3f + leg[0] * 0.9f + leg[1] * 0.7f + leg[2] * 0.5f + leg[3] * 0.3f
    }

    private fun smooth(t: Float): Float { val p = t.coerceIn(0f, 1f); return p * p * (3f - 2f * p) }

    /** 0, up to 1 over [a]..[b], held, down to 0 over [c]..[d]: eased both ways. */
    private fun ramp(p: Float, a: Float, b: Float, c: Float, d: Float): Float = when {
        p <= a || p >= d -> 0f
        p < b -> smooth((p - a) / (b - a))
        p <= c -> 1f
        else -> smooth((d - p) / (d - c))
    }

    /**
     * Standing awake, [t] ms into the page: he breathes, blinks (now and then twice), looks to a side
     * (now and then up), and shifts his weight from one side to the other. Three beats of different
     * lengths, so it never comes round the same. [seed]: two of them side by side are not in step.
     */
    fun idle(r: Rig, t: Long, seed: Int) {
        val tt = t + seed * 977L
        r.bob += -0.9f * sin(2f * PI.toFloat() * tt / BREATH_MS)
        blink(r, t, seed)
        val lp = tt.mod(LOOK_MS).toFloat()
        val li = tt / LOOK_MS
        val look = ramp(lp, 1100f, 1300f, 2700f, 2900f)
        r.eyeX += (if (li % 2L == 0L) 6f else -6f) * look
        if (li % 4L == 3L) r.eyeY += -3f * look
        val sp = tt.mod(SHIFT_MS).toFloat()
        val s = ramp(sp, 4300f, 4900f, 6300f, 6900f) * (if ((tt / SHIFT_MS) % 2L == 0L) 1f else -1f)
        r.lean += 2.6f * s
        r.sway += 1.6f * s
    }

    /** Only the blink of [idle]: his eyes shut for an eighth of a second every three seconds or so. */
    fun blink(r: Rig, t: Long, seed: Int) {
        val tt = t + seed * 977L
        val cycle = BLINK_MS + (seed % 3) * 350L
        val p = tt.mod(cycle)
        if (p >= cycle - 130L || ((tt / cycle) % 3L == 2L && p in (cycle - 430L) until (cycle - 300L))) r.blink = 0.12f
    }

    /**
     * Walking, [ms] into the walk: his legs step in two pairs, he bobs with each step, leans and
     * looks the way he goes. [env] 0..1: how much of a walk it is (it eases in and out with his
     * pace, so his legs come to rest as he stops); [dir] +1 to the right, -1 to the left;
     * [stride]: one full step, ms (shorter when he hurries).
     */
    fun walk(r: Rig, ms: Long, env: Float, dir: Float, stride: Float = STRIDE_MS) {
        val a = sin(2f * PI.toFloat() * ms / stride)
        val swing = 17f * a * env
        r.leg[0] += swing; r.leg[2] += swing
        r.leg[1] -= swing; r.leg[3] -= swing
        r.bob += -1.6f * abs(a) * env
        r.lean += 3.5f * dir * env
        r.eyeX += 4f * dir * env
    }

    /** Where a leap is: [along] 0..1 of the way across, [height] 0..1 of its top. */
    class Leap(val along: Float, val height: Float)

    /**
     * A hop, [t] 0..1 of it: he crouches (squashed, a fifth of the time), leaves the ground stretched
     * with his hands up and his legs tucked, comes down, is squashed by the landing and stands up
     * again a touch too far before he is still.
     */
    fun hop(r: Rig, t: Float): Leap {
        val p = t.coerceIn(0f, 1f)
        return when {
            p < CROUCH -> {
                val k = smooth(p / CROUCH)
                r.squash = 1f - 0.24f * k
                r.hands += 3f * k
                Leap(0f, 0f)
            }
            p < LAND -> {
                val q = (p - CROUCH) / (LAND - CROUCH)
                val arc = sin(PI.toFloat() * q)
                r.squash = 1f + 0.10f * arc - 0.24f * (1f - smooth(q * 6f))
                r.hands += -8f * arc
                r.leg[0] += -12f * arc; r.leg[1] += -6f * arc; r.leg[2] += 6f * arc; r.leg[3] += 12f * arc
                Leap(smooth(q), 4f * q * (1f - q))
            }
            p < SETTLE -> {
                val k = (p - LAND) / (SETTLE - LAND)
                r.squash = 1f - 0.26f * sin(PI.toFloat() * k)
                r.hands += 4f * sin(PI.toFloat() * k)
                Leap(1f, 0f)
            }
            else -> {
                val k = (p - SETTLE) / (1f - SETTLE)
                r.squash = 1f + 0.05f * sin(PI.toFloat() * k)
                Leap(1f, 0f)
            }
        }
    }

    private const val CROUCH = 0.20f
    private const val LAND = 0.72f
    private const val SETTLE = 0.90f
    const val BREATH_MS = 3_600f
    const val BLINK_MS = 3_100L
    const val LOOK_MS = 5_200L
    const val SHIFT_MS = 7_900L
    const val STRIDE_MS = 380f

    /**
     * Draws the figure: the middle of its feet at [cx], [feet] (canvas pixels), [unit] pixels to one
     * of its units. [paint] is used for every part.
     */
    fun draw(c: Canvas, r: Rig, cx: Float, feet: Float, unit: Float, paint: Paint, bodyColor: Int, eyeColor: Int) {
        paint.style = Paint.Style.FILL
        c.save()
        c.translate(cx, feet)
        // Squashed about its feet: lower and wider, or taller and thinner.
        c.scale((1f + (1f - r.squash) * 0.6f) * unit, r.squash * unit)
        c.translate(-W / 2f, -H)
        paint.color = bodyColor
        for (i in 0 until 4) {
            c.save()
            c.rotate(r.leg[i], LEG_X[i] + LEG_W / 2f, LEG_Y)
            c.drawRect(LEG_X[i], LEG_Y, LEG_X[i] + LEG_W, LEG_Y + LEG_H, paint)
            c.restore()
        }
        c.translate(r.sway, r.bob)
        c.rotate(r.lean, 53f, 65f)
        c.drawRect(11f, 0f, 96f, 65f, paint)
        c.drawRect(85f, 21f + r.hands, 107f, 44f + r.hands, paint)
        c.drawRect(0f, 21f + r.hands, 22f, 44f + r.hands, paint)
        paint.color = eyeColor
        val open = 11f * r.blink.coerceIn(0.05f, 1f)
        val ey = 11f + r.eyeY + (11f - open) / 2f
        c.drawRect(75f + r.eyeX, ey, 86f + r.eyeX, ey + open, paint)
        c.drawRect(21f + r.eyeX, ey, 32f + r.eyeX, ey + open, paint)
        c.restore()
    }

    /**
     * One account's mascot: what he is doing at each moment, and where. A string of DOINGS, each
     * with its end; when one is over the next is chosen by what his account has earned
     * (MascotMood, the app's choice):
     * - awake, nothing much going on ([MascotMood.WALK] and any other): he stands, alive ([idle]),
     *   for a few seconds, then WALKS along his meter to another place in it, and stands again.
     * - at work ([MascotMood.MUSCLE]): two rounds with the dumbbells (upstream's cycle), then he
     *   stands and gets his breath, and again.
     * - near the limit ([MascotMood.FLAG]): he waves the white flag, then paces a few hurried steps,
     *   and again.
     * - asleep, a ghost: upstream's own slow cycles, on and on (they are slow; a rest in a sleep is
     *   nothing). Dead: he lies still.
     * And WHATEVER HE IS DOING: when another meter comes to matter more (the one nearest its limit)
     * he HOPS over to it, once; when a window starts again he throws confetti, once; as the page
     * opens he lands on his meter and is moving within the second.
     *
     * [seed]: which account's (two side by side are not in step). Not for two threads at once.
     */
    class Actor(private val seed: Int = 0) {
        enum class Doing { LAND, STAND, WALK, CYCLE, HOP, CHEER }

        /**
         * What to draw: the meter he is on ([vessel]) or is leaving for [to] ([along] 0..1 of the way,
         * [height] 0..1 of the leap's top; [along] is 1 and [height] 0 when he is not in the air);
         * [spot] 0..1 across the meter; and either the [rig] or, when [clip] is not null, that
         * drawn cycle's [frame]. One object, written anew at every call.
         */
        class Pose {
            var vessel = 0
            var to = 0
            var along = 1f
            var height = 0f
            var spot = 0.5f
            var clip: String? = null
            var frame = 0
            var dead = false
            var doing = Doing.STAND
            val rig = Rig()
        }

        private val pose = Pose()
        private var doing = Doing.LAND
        private var startedAt = 0L
        private var lasts = 1L
        private var clip: String? = null
        private var spot = 0.5f
        private var spotFrom = 0.5f
        private var spotTo = 0.5f
        private var stride = STRIDE_MS
        private var at = -1
        private var from = 0
        private var last: List<Float> = emptyList()
        private var n = 0
        private var cheered = false

        /** The meter that matters for [used] (the five hours first, then the week's levels): the one nearest its limit. */
        private fun matters(used: List<Float>): Int {
            if (used.size < 2) return 0
            // The five hours, unless the week is clearly nearer its limit: he does not flit over a hair's difference.
            return if (used.drop(1).max() > used[0] + 0.08f) 1 else 0
        }

        /** A number 0..1 that is the same for the same place in his string of doings. */
        private fun chance(k: Int): Float {
            val x = sin((seed * 131 + n * 71 + k) * 12.9898) * 43758.5453
            return (x - Math.floor(x)).toFloat()
        }

        private fun start(what: Doing, at: Long, ms: Long, cycle: String? = null) {
            doing = what; startedAt = at; lasts = ms.coerceAtLeast(1L); clip = cycle; n++
        }

        private fun cycle(name: String): MascotFrames.Clip? = MascotFrames.CLIPS.firstOrNull { it.name == name }
        private fun rounds(name: String, times: Int): Long = cycle(name)?.let { it.frames.size * it.frameMs * times } ?: 1_000L

        private fun stroll(at: Long, hurried: Boolean) {
            spotFrom = spot
            // To the other half of the meter, so a walk is a walk and not a shuffle on the spot.
            val far = 0.32f * chance(1)
            spotTo = if (hurried) (if (spot < 0.5f) spot + 0.22f + 0.1f * chance(1) else spot - 0.22f - 0.1f * chance(1))
            else if (spot < 0.5f) 0.60f + far else 0.40f - far
            stride = if (hurried) STRIDE_MS * 0.62f else STRIDE_MS
            start(Doing.WALK, at, ((if (hurried) 500f else 900f) + (if (hurried) 1_100f else 1_700f) * abs(spotTo - spotFrom)).toLong())
        }

        /** The mood's own drawn cycle that plays on and on, or null. */
        private fun endless(mood: String): String? = when (mood) {
            MascotMood.SLEEP, MascotMood.GHOST -> mood
            MascotMood.DEAD -> MascotMood.STAND
            else -> null
        }

        /** What comes after the doing that ended at [at]. */
        private fun next(at: Long, mood: String) {
            val was = doing
            val on = endless(mood)
            when {
                on != null -> start(Doing.CYCLE, at, 3_600_000L, on)
                mood == MascotMood.CONFETTI && !cheered -> { cheered = true; start(Doing.CHEER, at, rounds(MascotMood.CONFETTI, 2), MascotMood.CONFETTI) }
                mood == MascotMood.MUSCLE -> if (was == Doing.CYCLE) start(Doing.STAND, at, 2_400L + (1_400f * chance(2)).toLong()) else start(Doing.CYCLE, at, rounds(MascotMood.MUSCLE, 2), MascotMood.MUSCLE)
                mood == MascotMood.FLAG -> if (was == Doing.CYCLE) stroll(at, hurried = true) else start(Doing.CYCLE, at, rounds(MascotMood.FLAG, 5), MascotMood.FLAG)
                // Awake: a walk after every stand, a stand after everything else. The first stand is short: he is walking within a second and a half of the page opening.
                was == Doing.STAND -> stroll(at, hurried = false)
                was == Doing.LAND -> start(Doing.STAND, at, FIRST_STAND_MS)
                else -> start(Doing.STAND, at, 2_600L + (2_800f * chance(3)).toLong())
            }
        }

        /**
         * At [now], for [used] (each limit's level, the five hours first) and [mood]; [opened]: when the
         * page came up (the same clock, ms). A drop in any of [used] is a window starting again.
         */
        fun at(used: List<Float>, mood: String, now: Long, opened: Long, vessels: List<Float>? = null): Pose {
            // [vessels]: each meter's highest level, in the meters' order, where the page has them so (one meter
            // holding the week and the models, with no five hours beside it): the one that matters is among those.
            val want = matters(vessels ?: used)
            if (at < 0) {
                at = want; from = want; spot = 0.30f + 0.40f * chance(0); cheered = false
                start(Doing.LAND, opened, LAND_MS)
            }
            // A window started again: confetti, once, wherever he is.
            val again = last.size == used.size && last.indices.any { last[it] - used[it] > RESET_DROP }
            last = used
            if (again && doing != Doing.HOP) start(Doing.CHEER, now, rounds(MascotMood.CONFETTI, 2), MascotMood.CONFETTI)
            // Another meter matters more: over to it, once he has his feet on the ground.
            if (want != at && doing != Doing.HOP && doing != Doing.LAND && doing != Doing.CHEER) { from = at; at = want; start(Doing.HOP, now, HOP_MS) }
            // What his account has earned has changed under him: the cycle he is in is not his any more.
            val on = endless(mood)
            if (doing == Doing.CYCLE && clip != (on ?: mood)) next(now, mood)
            else if (on != null && (doing == Doing.STAND || doing == Doing.WALK)) next(now, mood)
            var guard = 0
            while (now >= startedAt + lasts && guard++ < 6) next(startedAt + lasts, mood)
            // Far behind (the page was not drawn for a long while): he starts from now, standing.
            if (now >= startedAt + lasts) start(Doing.STAND, now, 2_000L)

            val t = (now - startedAt).coerceAtLeast(0L)
            val p = (t.toFloat() / lasts).coerceIn(0f, 1f)
            val life = now - opened
            val r = pose.rig
            r.rest()
            pose.vessel = at; pose.to = at; pose.along = 1f; pose.height = 0f
            pose.clip = null; pose.frame = 0; pose.dead = false; pose.doing = doing
            when (doing) {
                Doing.LAND -> { pose.height = hop(r, p).height * LAND_HEIGHT; blink(r, life, seed) }
                Doing.STAND -> idle(r, life, seed)
                Doing.WALK -> {
                    spot = spotFrom + (spotTo - spotFrom) * smooth(p)
                    walk(r, t, ramp(p, 0f, 0.18f, 0.82f, 1f), if (spotTo >= spotFrom) 1f else -1f, stride)
                    blink(r, life, seed)
                }
                Doing.HOP -> {
                    val leap = hop(r, p)
                    pose.vessel = from; pose.along = leap.along; pose.height = leap.height
                }
                Doing.CYCLE, Doing.CHEER -> {
                    val c = cycle(clip.orEmpty())
                    pose.clip = clip
                    pose.frame = if (c == null) 0 else ((t / c.frameMs) % c.frames.size).toInt()
                    pose.dead = mood == MascotMood.DEAD && doing == Doing.CYCLE
                    if (pose.dead) pose.frame = 0
                }
            }
            pose.spot = spot
            return pose
        }

        /** The page went away: the next opening starts afresh. */
        fun reset() { at = -1; last = emptyList(); n = 0 }

        companion object {
            const val LAND_MS = 800L
            const val FIRST_STAND_MS = 600L
            const val HOP_MS = 900L
            /** His landing as the page opens is a small jump: this much of a hop's height. */
            const val LAND_HEIGHT = 0.45f
            /** A level falling by this much at once is a window starting again. */
            const val RESET_DROP = 0.25f
        }
    }
}
