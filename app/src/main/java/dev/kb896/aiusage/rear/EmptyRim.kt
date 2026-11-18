package dev.kb896.aiusage.rear

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object EmptyRim {
    // ---- the draft's timeline (s) -----------------------------------------------------------------------------------
    /** The draft's pull. Here: the finger's; the lift is at this point of the draft's time. */
    const val PULL_S = 0.20
    /** The run round the rim: natural frequency (Hz), damping ratio (about 3 % over). */
    const val SPRING_F = 0.98
    const val SPRING_Z = 0.74
    /** What is under the light, at the deepest (a multiplier in linear light; about 80 % to the eye). */
    const val DIM_TO = 0.62
    /** The pool lets go of the rim with this time constant once the heads leave it. */
    const val POOL_TAU_S = 0.11
    /** What is under comes back on a critically damped spring at this rate, from 50 ms before the heads meet. */
    const val MOON_BACK_HZ = 1.15
    /** The fade of the swell where the heads meet ends with the rest of the light ([lightEnd]); it starts here (after [tMeet]). */
    const val SWELL_GONE_FROM = 0.35
    /** The draft's shutter: half a 60 Hz frame. */
    const val SHUTTER_S = 0.5 / 60.0
    /** Where they meet, the rear's x on its bottom edge: under the middle of what he sees (the lenses cover the left 296 px). */
    const val MEET_X = 636.0
    /** The draft's pull came down at this x of the top edge (its right half). A demo plays there. */
    const val DEMO_X = 780.0
    /** How far a finger pulls (panel units) for the pool to be full: half the notifications' own travel, their point of opening. */
    const val PULL_FULL = ControlCenter.TRAVEL * 0.5f

    /** The run's table: time since the split, fraction of the way (the spring), as the draft built it. */
    private val TT = DoubleArray(4001) { it * 1.6 / 4000 }
    private val FF = DoubleArray(TT.size) { spring(TT[it]) }
    private val PEAK = FF.indices.maxByOrNull { FF[it] }!!
    private val MONO = DoubleArray(PEAK + 1).also { m -> var hi = Double.NEGATIVE_INFINITY; for (i in m.indices) { hi = max(hi, FF[i]); m[i] = hi } }

    /** From the split until the heads are 98.5 % of the way: about 550 ms. */
    val T_ARRIVE: Double = interp(0.985, MONO, TT)
    /** When they meet, in the draft's time: about 750 ms. */
    val T_MEET: Double = PULL_S + T_ARRIVE
    /** The light is over (the draft's settle): 1350 ms. */
    val LIGHT_END: Double = T_MEET + 0.60
    /** What is under it is back (to under one level in 255) and the layer goes: 1.5 s. */
    const val END_S = 1.5
    /** A pull held longer than this (ms from the finger landing) plays on as if lifted ([Play.at]). */
    const val HOLD_MAX_MS = 8000.0

    /** When the run's front passes [f] (0 at the pull, 1 at the meeting) along its own path, in the draft's time. */
    fun passTime(f: Double): Double = PULL_S + interp(f.coerceIn(0.0, MONO.last()), MONO, TT)

    /** [passTime] sampled for the shader: entry i at f = i / ([PASS_N] - 1). */
    const val PASS_N = 65
    val PASS: FloatArray = FloatArray(PASS_N) { passTime(it / (PASS_N - 1.0)).toFloat() }

    fun spring(t: Double, fhz: Double = SPRING_F, z: Double = SPRING_Z): Double {
        if (t <= 0) return 0.0
        val w = 2 * PI * fhz
        val wd = w * sqrt(1 - z * z)
        return 1 - exp(-z * w * t) * (cos(wd * t) + z / sqrt(1 - z * z) * sin(wd * t))
    }

    /** How fast the run goes, fraction of the way a second. */
    fun springSpeed(t: Double, fhz: Double = SPRING_F, z: Double = SPRING_Z): Double {
        if (t <= 0) return 0.0
        val w = 2 * PI * fhz
        val wd = w * sqrt(1 - z * z)
        return w * w / wd * exp(-z * w * t) * sin(wd * t)
    }

    fun crit(t: Double, fhz: Double): Double {
        if (t <= 0) return 0.0
        val w = 2 * PI * fhz
        return 1 - exp(-w * t) * (1 + w * t)
    }

    fun smooth(a: Double, b: Double, v: Double): Double {
        val t = ((v - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    fun easeOutCubic(v: Double): Double { val c = v.coerceIn(0.0, 1.0); return 1 - (1 - c).pow(3) }

    private fun interp(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        if (x <= xs[0]) return ys[0]
        if (x >= xs[xs.size - 1]) return ys[xs.size - 1]
        var lo = 0; var hi = xs.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (xs[m] <= x) lo = m else hi = m }
        val span = xs[hi] - xs[lo]
        return if (span <= 0) ys[lo] else ys[lo] + (ys[hi] - ys[lo]) * (x - xs[lo]) / span
    }

    /** The pool's depth, 0 to 1, for a finger that has pulled [dy] panel units down: the draft's ease over its travel. */
    fun pullOf(dy: Float): Double = easeOutCubic((dy / PULL_FULL).toDouble())

    // ---- the rim's path -------------------------------------------------------------------------------------------

    /**
     * The display's rounded rectangle, [w] x [h] with corners of [r], as one path clockwise from the top edge's left
     * end (x [r], y 0), and the pull at [pullX] on the top edge. Both runs leave the pull and reach [sMeet] together.
     */
    class Path(val w: Double, val h: Double, val r: Double, pullX: Double) {
        val lx = w - 2 * r
        val ly = h - 2 * r
        val ar = PI * r / 2
        val p = 2 * lx + 2 * ly + 4 * ar
        /** The pull's place on the path: on the straight top edge, never in a corner. */
        val pullX = pullX.coerceIn(r, w - r)
        val s0 = this.pullX - r
        val sMeet = lx + ar + ly + ar + (w - r - MEET_X)
        /** Clockwise: the right corner, the right edge, along the bottom. Anticlockwise: along the top, down the left, along the bottom. */
        val lRight = sMeet - s0
        val lLeft = p - lRight

        /** The point [s] along the path. */
        fun xy(s: Double): DoubleArray {
            var t = ((s % p) + p) % p
            if (t < lx) return doubleArrayOf(r + t, 0.0)
            t -= lx
            if (t < ar) { val a = t / r; return doubleArrayOf(w - r + r * sin(a), r - r * cos(a)) }
            t -= ar
            if (t < ly) return doubleArrayOf(w, r + t)
            t -= ly
            if (t < ar) { val a = t / r; return doubleArrayOf(w - r + r * cos(a), h - r + r * sin(a)) }
            t -= ar
            if (t < lx) return doubleArrayOf(w - r - t, h)
            t -= lx
            if (t < ar) { val a = t / r; return doubleArrayOf(r - r * sin(a), h - r + r * cos(a)) }
            t -= ar
            if (t < ly) return doubleArrayOf(0.0, h - r - t)
            t -= ly
            val a = t / r
            return doubleArrayOf(r - r * cos(a), r - r * sin(a))
        }
    }

    // ---- one frame ------------------------------------------------------------------------------------------------

    /** What the shader is given for one picture: the draft's light_at and dim_at at one instant, as numbers. */
    class Frame(
        /** The pool under the finger: strength, its spread along the rim (px), how far it leaks in (px). */
        val poolAmp: Double, val poolSpread: Double, val poolLeak: Double,
        /** The run has begun (the finger let go). */
        val running: Boolean,
        /** The draft's time (s): the run's front and its trail are measured from it. */
        val t: Double,
        /** How far round the runs are (the spring), and the trail behind them (fading out). */
        val run: Double, val fadeAll: Double,
        /** The heads: strength, where each is on the path, their points, and how far each travels in one shutter (px). */
        val heads: Double, val sRight: Double, val sLeft: Double,
        val xyRight: DoubleArray, val xyLeft: DoubleArray, val blurRight: Double, val blurLeft: Double,
        /** The swell where they meet: strength, and its point. */
        val swell: Double, val meet: DoubleArray,
        /** What is under it: its multiplier (linear light), and how far its edge is darkened toward the rim. */
        val dim: Double, val envelope: Double,
    ) {
        /** Nothing of the light is left (what is under may still be coming back). */
        val dark: Boolean get() = poolAmp < 1e-4 && (!running || (fadeAll <= 0.0 && heads <= 0.0 && swell < 1e-4))
    }

    /** One instant: [t] the draft's time, [depth] the pool's depth (the finger's; 1 once the draft's pull is done), [running] after the lift. */
    fun frame(path: Path, t: Double, depth: Double, running: Boolean): Frame {
        // 1. The pull: light pools on the rim under the finger and leaks in; it lets go once the heads leave.
        val leave = if (running) exp(-max(0.0, t - PULL_S) / POOL_TAU_S) else 1.0
        val amp = depth * leave
        // 2. The run: two heads on one spring, the rim lit behind them, fading.
        val since = t - PULL_S
        val f = if (running) spring(since) else 0.0
        val fadeAll = if (running) 1 - smooth(T_MEET + 0.02, T_MEET + 0.52, t) else 0.0
        val heads = if (running) easeOutCubic(since / 0.08) * (1 - smooth(T_MEET - 0.10, T_MEET + 0.12, t)) else 0.0
        val sR = path.s0 + f * path.lRight
        val sL = path.s0 - f * path.lLeft
        val v = if (running) springSpeed(since) else 0.0
        // 3. Where they meet: a soft swell, then everything settles out.
        val k = t - (T_MEET - 0.06)
        val swell = if (running && k > 0) 0.45 * (1 - exp(-k / 0.07)) * exp(-k / 0.26) * (1 - smooth(T_MEET + SWELL_GONE_FROM, LIGHT_END, t)) else 0.0
        // What is under: down with the pull, back on its spring from 50 ms before the meeting.
        val down = 1 - (1 - DIM_TO) * depth
        val back = if (running) crit(t - (T_MEET - 0.05), MOON_BACK_HZ) else 0.0
        val dim = down + (1 - down) * back
        val envelope = depth * (1 - smooth(T_MEET + 0.02, LIGHT_END, t))
        return Frame(
            amp, 60 + 70 * depth, 20 + 34 * depth, running, t, f, fadeAll,
            heads, sR, sL, path.xy(sR), path.xy(sL), abs(v * path.lRight * SHUTTER_S), abs(v * path.lLeft * SHUTTER_S),
            swell, path.xy(path.sMeet), dim, envelope,
        )
    }

    private fun abs(v: Double) = if (v < 0) -v else v

    class Play(val path: Path, private val landedMs: Double = Double.NaN) {
        private var finger = 0.0
        private var releasedAt = Double.NaN
        private var depthAtLift = 0.0

        val released: Boolean get() = !releasedAt.isNaN()

        /** The finger has pulled to a depth of [depth] (0 to 1, [pullOf]); after the lift it changes nothing. */
        fun pull(depth: Double) { if (!released) finger = depth.coerceIn(0.0, 1.0) }

        /** The lift at [nowMs]: the heads leave the pool now. A second one changes nothing. */
        fun release(nowMs: Double) { if (!released) { releasedAt = nowMs; depthAtLift = finger } }

        /** The draft's time at [nowMs]: the pull's end ([PULL_S]) at the lift; before it, the pull's own moment. */
        fun time(nowMs: Double): Double = if (!released) PULL_S * finger else PULL_S + max(0.0, nowMs - releasedAt) / 1000.0

        /**
         * The pool's depth at [nowMs]: the finger's while it pulls; after a short pull it runs on to full over the
         * draft's remaining pull time (at a full pull, nothing to run).
         */
        fun depth(nowMs: Double): Double {
            if (!released) return finger
            val rest = PULL_S * 1000.0 * (1 - depthAtLift)
            if (rest <= 0.5) return 1.0
            val p = ((nowMs - releasedAt) / rest).coerceIn(0.0, 1.0)
            return depthAtLift + (1 - depthAtLift) * easeOutCubic(p)
        }

        /** The light itself is over at [nowMs] (only what is under may still be coming back). */
        fun lightOver(nowMs: Double): Boolean = released && time(nowMs) >= LIGHT_END

        /**
         * The frame at [nowMs], or null once it is over (nothing is drawn after that). A finger held longer than
         * [HOLD_MAX_MS] from [landedMs] is taken as lifted: a lift that never reached the shell must not leave the pool and
         * the dim up for good.
         */
        fun at(nowMs: Double): Frame? {
            if (!released && !landedMs.isNaN() && nowMs - landedMs > HOLD_MAX_MS) release(nowMs)
            val t = time(nowMs)
            if (released && t >= END_S) return null
            return frame(path, t, depth(nowMs), released)
        }
    }

    // ---- colour ---------------------------------------------------------------------------------------------------

    /** The light's colour: linear RGB at the top, down the sides, at the bottom (the shader blends them by height). */
    class Colours(val top: FloatArray, val side: FloatArray, val bottom: FloatArray)

    /** NEUTRAL (the default): a hair warm of paper white, the same everywhere. No accent colour. */
    val WHITE = floatArrayOf(1.0f, 0.975f, 0.945f).let { Colours(it, it, it) }

    val XIAOMI = Colours(lin(0xF7BE98), lin(0xB4A0DA), lin(0x4B83FC))

    fun colours(xiaomi: Boolean): Colours = if (xiaomi) XIAOMI else WHITE

    fun lin(rgb: Int): FloatArray = FloatArray(3) { i ->
        val c = ((rgb shr (16 - 8 * i)) and 0xFF) / 255.0
        (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
    }

    /** The colour at height [y] of [h], as the shader has it (the draft's init): blended by height, its largest channel 1. */
    fun colourAt(c: Colours, y: Double, h: Double): DoubleArray {
        val v = y / h
        val a = smooth(0.0, 0.55, v); val b = smooth(0.45, 1.0, v)
        val out = DoubleArray(3) { i -> (c.top[i] * (1 - a) + c.side[i] * a) * (1 - b) + c.bottom[i] * b }
        val m = out.maxOrNull()!!.coerceAtLeast(1e-6)
        return DoubleArray(3) { out[it] / m }
    }

    // ---- the shader -----------------------------------------------------------------------------------------------

    /**
     * The tone of what is under at which the compositor's sum is made exact besides black (sRGB 0.55, linear 0.262):
     * chosen on the draft's own frames over the moon (rim band: +1.6 levels in 255 on average, against +7 when exact
     * over white instead).
     */
    const val ANCHOR_SRGB = 0.55
    private val ANCHOR_LIN = ((ANCHOR_SRGB + 0.055) / 1.055).pow(2.4)


    /**
     * The whole picture, one pass, one triangle over the back. The light is the draft's light_at, closed form per pixel;
     * its output is PREMULTIPLIED and ADDED by the compositor to what is under: rgb = the light as it is over black,
     * alpha = how much of what is under goes (the dim, and the edge darkened under the light), chosen so the sum is the
     * draft's linear-light sum exactly over black and over white and close between. Inside the display's own corners
     * only. No texture; under 0.3 ms on the phone's GPU (it logs its own time).
     */
    val FRAGMENT: String = """
        #version 300 es
        precision highp float;
        uniform vec2 uSize;
        uniform float uR;
        uniform vec4 uPath;      // lx, ly, ar, p
        uniform vec4 uRoute;     // s0, lRight, lLeft, sMeet
        uniform vec4 uPool;      // strength, spread, leak, pull x
        uniform vec4 uRun;       // running, run, fadeAll, t
        uniform float uPass[$PASS_N];
        uniform vec4 uHead;      // strength, s right, s left, -
        uniform vec4 uHeadXY;    // right x, y, left x, y
        uniform vec2 uHeadBlur;  // right, left: px travelled in one shutter
        uniform vec4 uSwell;     // strength, x, y, -
        uniform vec2 uDim;       // what is under: its multiplier, the edge envelope
        uniform vec3 uTop;
        uniform vec3 uSide;
        uniform vec3 uBottom;
        out vec4 fragColor;

        float sq(float a) { return a * a; }
        float wrapS(float v, float p) { return mod(v + 0.5 * p, p) - 0.5 * p; }
        float passTime(float f) {
            float x = clamp(f, 0.0, 1.0) * ${PASS_N - 1}.0;
            int i = int(min(floor(x), ${PASS_N - 2}.0));
            return mix(uPass[i], uPass[i + 1], x - float(i));
        }
        vec3 shoulder(vec3 c) { return mix(c, 0.8 + 0.2 * (1.0 - exp(-(c - 0.8) / 0.2)), step(0.8, c)); }
        vec3 srgb(vec3 c) {
            c = clamp(c, 0.0, 1.0);
            return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
        }

        void main() {
            float W = uSize.x, H = uSize.y, R = uR;
            float LX = uPath.x, LY = uPath.y, AR = uPath.z, P = uPath.w;
            vec2 q = vec2(gl_FragCoord.x, H - gl_FragCoord.y);
            float x = q.x, y = q.y;

            // Where this pixel is: its distance in from the rim, and its place along the rim.
            float dt = y, db = H - y, dl = x, dr = W - x;
            float d = min(min(dt, db), min(dl, dr));
            float s = x - R;
            if (d == dr) s = LX + AR + (y - R);
            if (d == db) s = LX + AR + LY + AR + (W - R - x);
            if (d == dl) s = 2.0 * LX + LY + 3.0 * AR + (H - R - y);
            vec2 v;
            if (x > W - R && y < R) { v = q - vec2(W - R, R); d = R - length(v); s = LX + R * clamp(atan(v.x, -v.y), 0.0, 1.5707963); }
            else if (x > W - R && y > H - R) { v = q - vec2(W - R, H - R); d = R - length(v); s = LX + AR + LY + R * clamp(atan(v.y, v.x), 0.0, 1.5707963); }
            else if (x < R && y > H - R) { v = q - vec2(R, H - R); d = R - length(v); s = 2.0 * LX + 2.0 * AR + LY + R * clamp(atan(-v.x, v.y), 0.0, 1.5707963); }
            else if (x < R && y < R) { v = q - vec2(R, R); d = R - length(v); s = 2.0 * LX + 2.0 * LY + 3.0 * AR + R * clamp(atan(-v.y, -v.x), 0.0, 1.5707963); }
            s = mod(s, P);
            float mask = clamp(d + 0.5, 0.0, 1.0);
            d = max(d, 0.0);

            // Across the rim: the head's thin hot line with its halo, and what it leaves behind, gentler.
            float core = exp(-sq((d - 2.5) / 2.2));
            float halo = exp(-d / 12.0), wide = exp(-d / 30.0), bloom = exp(-d / 70.0);
            float prof = 1.25 * core + 0.50 * halo + 0.10 * wide + 0.03 * bloom;
            float soft = 0.62 * core + 0.22 * halo + 0.02 * wide + 0.004 * bloom;

            float light = 0.0;
            // 1. The pool under the finger, and its leak in.
            if (uPool.x > 1e-3) {
                float a0 = wrapS(s - uRoute.x, P);
                light += uPool.x * 1.35 * exp(-sq(a0 / uPool.y)) * prof;
                float dx = x - uPool.w;
                light += uPool.x * 0.50 * exp(-sq(dx / (uPool.y * 1.3)) - sq(y / uPool.z));
            }
            if (uRun.x > 0.5) {
                // 2. The run: the rim lit behind both fronts, fading.
                float ds = mod(s - uRoute.x, P);
                bool right = ds <= uRoute.y;
                float f = right ? ds / uRoute.y : (P - ds) / uRoute.z;
                float plen = right ? uRoute.y : uRoute.z;
                float ahead = (uRun.y - f) * plen;
                float blur = 0.5 * (right ? uHeadBlur.x : uHeadBlur.y);
                float lit = smoothstep(-6.0 - blur, 24.0 + blur, ahead);
                float since = max(uRun.w - passTime(f), 0.0);
                light += uRun.z * lit * (0.12 + 0.62 * exp(-since / 0.24)) * soft;
                // The two heads: a short leading edge, a longer tail, a small point bloom; each widened by its own travel.
                if (uHead.x > 1e-3) {
                    for (int k = 0; k < 2; k++) {
                        float sh = k == 0 ? uHead.y : uHead.z;
                        float sgn = k == 0 ? 1.0 : -1.0;
                        float tr = k == 0 ? uHeadBlur.x : uHeadBlur.y;
                        vec2 hxy = k == 0 ? uHeadXY.xy : uHeadXY.zw;
                        float sa = sqrt(225.0 + tr * tr / 6.0), sb = sqrt(2116.0 + tr * tr / 6.0);
                        float delta = wrapS(s - sh, P);
                        float sig = delta * sgn > 0.0 ? sa : sb;
                        light += uHead.x * 1.05 * (61.0 / (sa + sb)) * exp(-sq(delta / sig)) * prof;
                        float sp = sqrt(900.0 + tr * tr / 6.0);
                        float rr = length(q - hxy);
                        light += uHead.x * (0.20 * (30.0 / sp) * exp(-sq(rr / sp)) + 0.05 * exp(-rr / 80.0));
                    }
                }
                // 3. Where they meet: a soft swell.
                if (uSwell.x > 1e-4) {
                    float rr = length(q - uSwell.yz);
                    light += uSwell.x * (0.50 * exp(-sq(rr / 44.0)) + 0.07 * exp(-rr / 110.0));
                    float along = abs(wrapS(s - uRoute.w, P));
                    light += uSwell.x * 0.9 * exp(-sq(along / 70.0)) * soft;
                }
            }

            // The colour by height (white: the same everywhere).
            float hv = y / H;
            vec3 col = mix(mix(uTop, uSide, smoothstep(0.0, 0.55, hv)), uBottom, smoothstep(0.45, 1.0, hv));
            col /= max(max(col.r, col.g), max(col.b, 1e-6));
            vec3 L = light * col;
            // What is under: dimmed, and darker toward the rim while the light is up.
            float m = uDim.x * (1.0 - 0.38 * uDim.y * exp(-d / 60.0));
            // Added by the compositor (out = rgb + under * (1 - alpha), in sRGB): exact over black (rgb), and exact over
            // a mid tone ($ANCHOR_SRGB in sRGB, the Earth's and the ground's own), what is under dimmed plus the light (its own
            // shoulder only: what is under, at rest, is never changed). Between and above, the line through the two.
            vec3 Ls = shoulder(L);
            vec3 lo = srgb(Ls);
            vec3 mid = srgb(${ANCHOR_LIN} * m + Ls);
            vec3 slope = clamp((mid - lo) / $ANCHOR_SRGB, 0.0, 1.0);
            float keep = (slope.r + slope.g + slope.b) / 3.0;
            float n = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
            vec3 rgb = lo + step(0.5 / 255.0, max(lo.r, max(lo.g, lo.b))) * n / 255.0;
            fragColor = vec4(clamp(rgb, 0.0, 1.0) * mask, 1.0 - keep * mask);
        }
    """.trimIndent()

    const val VERTEX: String = """#version 300 es
in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    /** The uniforms of [FRAGMENT], in the order [fill] writes them, and each one's number of floats. */
    val UNIFORM_NAMES = arrayOf("uSize", "uR", "uPath", "uRoute", "uPool", "uRun", "uPass", "uHead", "uHeadXY", "uHeadBlur", "uSwell", "uDim", "uTop", "uSide", "uBottom")
    private val UNIFORM_SIZES = intArrayOf(2, 1, 4, 4, 4, 4, PASS_N, 4, 4, 2, 4, 2, 3, 3, 3)

    /** One array per uniform ([UNIFORM_NAMES]), made once per play and written by [fill] every frame: nothing made per frame. */
    fun uniformArrays(): Array<FloatArray> = Array(UNIFORM_NAMES.size) { FloatArray(UNIFORM_SIZES[it]) }

    // (Fixed arity, not vararg: a vararg call would make an array each time.)
    private fun FloatArray.put(a: Double) { this[0] = a.toFloat() }
    private fun FloatArray.put(a: Double, b: Double) { this[0] = a.toFloat(); this[1] = b.toFloat() }
    private fun FloatArray.put(a: Double, b: Double, c: Double, d: Double) { this[0] = a.toFloat(); this[1] = b.toFloat(); this[2] = c.toFloat(); this[3] = d.toFloat() }

    /** The uniforms of [FRAGMENT] for one frame, written into [out] (from [uniformArrays]). */
    fun fill(path: Path, f: Frame, c: Colours, out: Array<FloatArray>) {
        out[0].put(path.w, path.h)
        out[1].put(path.r)
        out[2].put(path.lx, path.ly, path.ar, path.p)
        out[3].put(path.s0, path.lRight, path.lLeft, path.sMeet)
        out[4].put(f.poolAmp, f.poolSpread, f.poolLeak, path.pullX)
        out[5].put(if (f.running) 1.0 else 0.0, f.run, f.fadeAll, f.t)
        PASS.copyInto(out[6])
        out[7].put(f.heads, f.sRight, f.sLeft, 0.0)
        out[8].put(f.xyRight[0], f.xyRight[1], f.xyLeft[0], f.xyLeft[1])
        out[9].put(f.blurRight, f.blurLeft)
        out[10].put(f.swell, f.meet[0], f.meet[1], 0.0)
        out[11].put(f.dim, f.envelope)
        c.top.copyInto(out[12]); c.side.copyInto(out[13]); c.bottom.copyInto(out[14])
    }

    /** The uniforms of [FRAGMENT] for one frame, by name (the tests and the PC's render of it; the phone uses [fill]). */
    fun uniforms(path: Path, f: Frame, c: Colours): LinkedHashMap<String, FloatArray> {
        val a = uniformArrays().also { fill(path, f, c, it) }
        return LinkedHashMap<String, FloatArray>().apply { UNIFORM_NAMES.forEachIndexed { i, n -> put(n, a[i]) } }
    }

    /** How much of what is under stays where there is no light at all, as the shader gives it (its line through black and the mid tone). For the tests. */
    fun keepWithoutLight(dim: Double): Double {
        val c = (ANCHOR_LIN * dim).coerceIn(0.0, 1.0)
        return ((if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055) / ANCHOR_SRGB).coerceIn(0.0, 1.0)
    }

}
