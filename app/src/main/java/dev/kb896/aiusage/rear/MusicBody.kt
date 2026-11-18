package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

object MusicBody {
    const val PAGE_W = 976f
    const val PAGE_H = 596f
    /** What he sees starts here (the lenses cover the left 296 px). */
    const val SEEN_X = 296f
    /** The middle of what he sees: the bass's peak, and the kick's splash. */
    const val DOME_X = 636f
    const val DOME_W = 140f
    /**
     * The surface paused: a calm low body this high over the foot, or on the floor ([floorPx]) where the setting's is
     * higher (from about 3 on: at the default 30 the paused body stands on the floor, 98 px).
     */
    const val REST_PX = 46f
    /**
     * Playing, the base stands this much higher with the song quiet… (REST_PX + LIFT0 + RISE·E in all, 70 to 140 px:
     * a floor over that holds the base still and leaves the song's course to the peaks, the kick and the breath).
     */
    const val LIFT0 = 24f
    /** …and this much more at the song's loudest (E = 1). Over the floor, as before; under it, the floor. */
    const val RISE = 70f
    /** The peaks' height for a band at its top: PEAK0 + PEAK_E·E² (a verse's peaks well under a drop's). */
    const val PEAK0 = 70f
    const val PEAK_E = 180f
    /** A full kick at the middle, on top. */
    const val KICK_PX = 46f
    /** The travelling wave: its height (quiet, and the more with the song), its length, its time to go one length. */
    const val WAVE_PX = 12f
    const val WAVE_E = 4f
    const val WAVE_REST = 3.5f
    const val WAVE_L = 380f
    /** The bands: how many, how far apart their peaks are (from the middle out, mirrored), how wide one is. */
    const val BANDS = 12
    const val BAND_STEP = (PAGE_W - DOME_X) / (BANDS - 1)
    const val BAND_R = 2.6f * BAND_STEP
    /** The surface never above this (its soft ceiling): 55 % of the page. The cover ends at y 257. */
    const val Y_LIMIT = 270f
    /** How far under the ceiling the surface starts to be held back (a drop's peaks are in that knee, not flat at its top). */
    const val SOFT = 40f
    /** The calm body when no level comes. */
    const val E_NOLEVEL = 0.22f
    /** A peak this high (px over the base) is a whole one for its light. */
    const val PEAK_FULL = 120f
    /**
     * Where the base meets the floor it is eased onto it over this many px ([onFloor]), never more than a quarter of it
     * (4 px) over the plain higher of the two. Without it a sinking base stopped dead on the floor (2.15 px a frame
     * squared at about 98 px, two and a half times the hardest the base turns today); with it about a quarter of that
     * hardest turn. Under [REST_PX], so a floor of 0 is never within it.
     */
    const val FLOOR_KNEE = 16f

    const val FLOOR_ROOM = REST_PX + LIFT0 + RISE

    const val FLOOR_AT_DEFAULT = 30f
    /** The default's floor, px over the foot: well over the quiet base (70 px) and the calm paused one (46 px). */
    const val FLOOR_DEFAULT_PX = 98f
    val FLOOR_CURVE: Float = (kotlin.math.ln(FLOOR_DEFAULT_PX / FLOOR_ROOM.toDouble()) / kotlin.math.ln(FLOOR_AT_DEFAULT / 100.0)).toFloat()

    /** The setting's floor (0..100) as px over the foot, by [FLOOR_CURVE]. The glide is in px ([MusicFace.Body]), not in this value. */
    fun floorPx(value: Float): Float = FLOOR_ROOM * (value.coerceIn(0f, 100f) / 100f).pow(FLOOR_CURVE)

    /**
     * The base's y [y] held on the floor at y [floorY]: the higher of the two (the smaller y), met through a soft knee
     * [FLOOR_KNEE] px wide. Further than that from the floor, exactly the higher one: a base well over the floor is
     * untouched, and with no floor (y [PAGE_H]) every base is (it is never under [REST_PX]).
     */
    fun onFloor(y: Float, floorY: Float): Float {
        val held = minOf(y, floorY)
        val h = (FLOOR_KNEE - kotlin.math.abs(y - floorY)) / FLOOR_KNEE
        return if (h <= 0f) held else held - h * h * FLOOR_KNEE / 4f
    }

    /** One frame's uniforms. [breath] is amplitude times its sine; [bands] 0..1; the colours linear sRGB, 0..1. */
    class Frame {
        /**
         * The base line's y (page units): where the surface stands with no peak, kick, breath or wave. Never under the
         * setting's floor ([onFloor]); the only uniform the floor changes, so the shader needs nothing of it.
         */
        var base = PAGE_H - REST_PX
        var peak = 0f
        var kickAmp = 0f
        var breath = 0f
        var wave = WAVE_REST
        /** The travelling wave's phase, radians (it goes to the right as it grows). */
        var wph = 0f
        val bands = FloatArray(BANDS)
        var limit = Y_LIMIT
        var soft = SOFT
        var g = 0.42f
        var glow = 0.13f * 0.42f
        var tilt = 1f
        var shift = 0f
        val ground = FloatArray(3)
        val colA = FloatArray(3)
        val colB = FloatArray(3)

        fun setColors(ground: Int, a: Int, b: Int) { lin(ground, this.ground); lin(a, colA); lin(b, colB) }

        override fun toString() = "base %.1f peak %.1f kickAmp %.1f breath %.2f wave %.2f wph %.3f G %.3f glow %.3f tilt %.3f shift %.3f".format(base, peak, kickAmp, breath, wave, wph, g, glow, tilt, shift) +
            " bands " + bands.joinToString(",") { "%.2f".format(it) } +
            " ground %s colA %s colB %s".format(hex(ground), hex(colA), hex(colB))

        private fun hex(c: FloatArray) = "#%06X".format(srgb(c[0], c[1], c[2]) and 0xFFFFFF)
    }

    // ---- the formula, in Kotlin (the AGSL below is the same, line for line) ----

    /** One band's bump at [t] (its distance from the band's place over [BAND_R]): smooth, 0 from 1 on. */
    private fun bump(t: Float): Float { val a = 1f - t * t; return if (a > 0f) a * a else 0f }

    /**
     * The peaks' shape at column [x], about 0..1: the bands' bumps mirrored about [DOME_X], summed (over [KN], so
     * bands all at 1 give about 1 inside).
     */
    fun peaks(f: Frame, x: Float): Float {
        val d = kotlin.math.abs(x - DOME_X)
        var p = 0f
        for (i in 0 until BANDS) {
            val c = i * BAND_STEP
            p += f.bands[i] * (bump((d - c) / BAND_R) + (if (i > 0) bump((d + c) / BAND_R) else 0f))
        }
        return p / KN
    }

    /** The bumps' sum with every band at 1, inside the row: what [peaks] divides by. */
    const val KN = 2.77f

    /** The surface's y at column [x] (page units): its half-light line. Never above [Frame.limit]. */
    fun surface(f: Frame, x: Float): Float {
        val dx = x - DOME_X
        val dome = exp(-(dx * dx) / (DOME_W * DOME_W))
        val s = f.base - f.peak * peaks(f, x) - f.kickAmp * (0.25f + 0.75f * dome) - f.breath -
            f.wave * sin(2f * PI.toFloat() * x / WAVE_L - f.wph)
        var d = s - f.limit
        if (d < f.soft) d = f.soft * exp((d - f.soft) / f.soft)
        return f.limit + d
    }

    /** How much of a peak column [x] is, 0..1: the light's lift there. */
    fun peakAt(f: Frame, x: Float): Float = (f.peak * peaks(f, x) / PEAK_FULL).coerceIn(0f, 1f)

    /** How much of the body's colour is added to the ground at [y], with the surface [s] and the peak [pk] there. */
    fun alpha(f: Frame, y: Float, s: Float, pk: Float): Float {
        val dy = y - s
        val edge = smoothstep(-18f, 20f, dy)
        // Lit from inside, the more the lower on the page (as Xiaomi's card): by the height alone, so a row under the
        // valleys is evenly lit whatever the peaks over it do.
        val q = ((y - Y_LIMIT) / (PAGE_H - Y_LIMIT)).coerceIn(0f, 1f)
        // The exponent held at 30 (e^-30 is nothing): far under the surface it ran to -88, where an approximate exp (Skia's
        // on a CPU) gives garbage, a dark band at the foot in the test's renderer.
        val rim = (0.16f + 0.34f * pk) * exp(-minOf(((dy - 3f) * (dy - 3f)) / 64f, 30f))
        val lit = (edge * f.g * (0.6f + 0.4f * q.pow(0.7f) + rim)).coerceIn(0f, 1f)
        val hz = f.glow * (1f + 0.6f * pk) * exp(-max(0f, -dy - 18f) / 42f)
        return 1f - (1f - lit) * (1f - hz)
    }

    /** Where between the two colours column [x] is (0 = A, 1 = B). */
    fun mixAt(f: Frame, x: Float): Float {
        val uu = (x - SEEN_X) / (PAGE_W - SEEN_X)
        return 0.5f + 0.5f * sin(3.92699f * (uu - 0.5f) * f.tilt + f.shift)
    }

    /** The linear colour at ([x], [y]) into [out] (before the dither). */
    fun linear(f: Frame, x: Float, y: Float, out: FloatArray) {
        val a = alpha(f, y, surface(f, x), peakAt(f, x))
        val w = mixAt(f, x)
        for (i in 0..2) out[i] = f.ground[i] + a * (f.colA[i] + (f.colB[i] - f.colA[i]) * w)
    }

    /** The light the body adds at ([x], [y]): linear luminance over the ground's. */
    fun light(f: Frame, x: Float, y: Float): Float = light(f, x, y, surface(f, x), peakAt(f, x))

    /** [light] with the column's surface [s] and peak [pk] already worked out. */
    fun light(f: Frame, x: Float, y: Float, s: Float, pk: Float): Float {
        val a = alpha(f, y, s, pk)
        val w = mixAt(f, x)
        var l = 0f
        for (i in 0..2) l += LUMA[i] * (f.colA[i] + (f.colB[i] - f.colA[i]) * w)
        return a * l
    }

    /** The body's highest point above the page's foot over what he sees (x 296..976, every 2 px). */
    fun height(f: Frame): Float {
        var top = PAGE_H
        var x = SEEN_X
        while (x <= PAGE_W) { top = minOf(top, surface(f, x)); x += 2f }
        return PAGE_H - top
    }

    /** The body's lowest point above the foot over what he sees: the deepest valley. */
    fun lowest(f: Frame): Float {
        var bottom = 0f
        var x = SEEN_X
        while (x <= PAGE_W) { bottom = maxOf(bottom, surface(f, x)); x += 2f }
        return PAGE_H - bottom
    }

    private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun lin(argb: Int, out: FloatArray) {
        fun l(c: Int): Float { val v = c / 255f; return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f) }
        out[0] = l((argb shr 16) and 0xFF); out[1] = l((argb shr 8) and 0xFF); out[2] = l(argb and 0xFF)
    }

    fun srgb(r: Float, g: Float, b: Float): Int {
        fun e(v: Float): Int { val u = v.coerceIn(0f, 1f); return Math.round(255f * (if (u <= 0.0031308f) 12.92f * u else 1.055f * u.pow(1f / 2.4f) - 0.055f)).coerceIn(0, 255) }
        return (0xFF shl 24) or (e(r) shl 16) or (e(g) shl 8) or e(b)
    }

    /** The same formula as AGSL, for the shell's hardware canvas. The bands in three float4s (b0 = bands 0 to 3, …). */
    const val AGSL = """
uniform float u, base, peak, kickAmp, breath, wave, wph, limit, soft, G, glow, tilt, shift;
uniform float4 b0, b1, b2;
uniform float3 ground, colA, colB;
// Linear to sRGB, as MusicBody.srgb (the page is drawn to an sRGB surface; fromLinearSrgb is not in every AGSL).
float enc(float v) { return v <= 0.0031308 ? 12.92 * v : 1.055 * pow(v, 1.0 / 2.4) - 0.055; }
float bump(float t) { float a = 1.0 - t * t; return a > 0.0 ? a * a : 0.0; }
// Band i's two mirrored bumps (band 0, in the middle, has one).
float band(float d, float i, float h) {
  float c = i * 30.909091;
  return h * (bump((d - c) / 80.363636) + (i > 0.5 ? bump((d + c) / 80.363636) : 0.0));
}
half4 main(float2 p) {
  float x = p.x / u;
  float y = p.y / u;
  float uu = (x - 296.0) / 680.0;
  float dd = abs(x - 636.0);
  float pk = band(dd, 0.0, b0.x) + band(dd, 1.0, b0.y) + band(dd, 2.0, b0.z) + band(dd, 3.0, b0.w)
           + band(dd, 4.0, b1.x) + band(dd, 5.0, b1.y) + band(dd, 6.0, b1.z) + band(dd, 7.0, b1.w)
           + band(dd, 8.0, b2.x) + band(dd, 9.0, b2.y) + band(dd, 10.0, b2.z) + band(dd, 11.0, b2.w);
  pk = pk / 2.77;
  float dome = exp(-((x - 636.0) * (x - 636.0)) / 19600.0);
  float s = base - peak * pk - kickAmp * (0.25 + 0.75 * dome) - breath - wave * sin(6.2831853 * x / 380.0 - wph);
  float d = s - limit;
  if (d < soft) { d = soft * exp((d - soft) / soft); }
  s = limit + d;
  float pa = clamp(peak * pk / 120.0, 0.0, 1.0);
  float dy = y - s;
  float edge = smoothstep(-18.0, 20.0, dy);
  float q = clamp((y - 270.0) / 326.0, 0.0, 1.0);
  float rim = (0.16 + 0.34 * pa) * exp(-min(((dy - 3.0) * (dy - 3.0)) / 64.0, 30.0));
  float lit = clamp(edge * G * (0.6 + 0.4 * pow(q, 0.7) + rim), 0.0, 1.0);
  float hz = glow * (1.0 + 0.6 * pa) * exp(-max(0.0, -dy - 18.0) / 42.0);
  float a = 1.0 - (1.0 - lit) * (1.0 - hz);
  float w = 0.5 + 0.5 * sin(3.92699 * (uu - 0.5) * tilt + shift);
  float3 o = clamp(ground + a * mix(colA, colB, w), 0.0, 1.0);
  float n = (fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0;
  // Kept inside 0..1 after the dither: a colour over 1 is not a valid opaque one (a renderer took it as a dark red).
  return half4(half3(clamp(float3(enc(o.r), enc(o.g), enc(o.b)) + n, 0.0, 1.0)), 1.0);
}
"""

    // ---- the drawing ----

    @Volatile private var shaderFailed = false
    private var shader: RuntimeShader? = null
    // Made at the first picture, not with the object: the arithmetic above runs in plain JVM tests.
    private val paint by lazy { Paint() }
    private var said = ""

    private fun shader(): RuntimeShader? {
        if (shaderFailed) return null
        shader?.let { return it }
        return try {
            RuntimeShader(AGSL).also { shader = it; paint.shader = it }
        } catch (e: Throwable) {
            shaderFailed = true
            android.util.Log.e("AiUsageRear", "music body: the shader does not compile — bitmap path: ${e.message?.take(300)}")
            null
        }
    }

    private var bmp: Bitmap? = null
    private val bmpPixels = IntArray(FALLBACK_W * FALLBACK_H)
    private val bmpPaint by lazy { Paint(Paint.FILTER_BITMAP_FLAG) }
    private val bmpRect by lazy { RectF() }
    private val px = FloatArray(3)
    private val colS = FloatArray(FALLBACK_W)
    private val colPk = FloatArray(FALLBACK_W)
    private val colW = FloatArray(FALLBACK_W)

    /** The body over the whole canvas ([w] x [h]; opaque): the shader on a hardware canvas, else a small bitmap stretched. */
    fun draw(c: Canvas, w: Float, h: Float, f: Frame) {
        val sh = if (c.isHardwareAccelerated) shader() else null
        if (sh != null) {
            uniforms(sh, f, h / PAGE_H)
            paint.shader = sh
            c.drawPaint(paint)
            once("music body: drawn by the shader (hardware canvas)")
            return
        }
        drawBitmap(c, w, h, f)
    }

    /** Sets [f]'s uniforms on a shader made from [AGSL], at [u] canvas px to a page unit. */
    fun uniforms(sh: RuntimeShader, f: Frame, u: Float) {
            sh.setFloatUniform("u", u)
            sh.setFloatUniform("base", f.base)
            sh.setFloatUniform("peak", f.peak)
            sh.setFloatUniform("kickAmp", f.kickAmp)
            sh.setFloatUniform("breath", f.breath)
            sh.setFloatUniform("wave", f.wave)
            sh.setFloatUniform("wph", f.wph)
            val b = f.bands
            sh.setFloatUniform("b0", b[0], b[1], b[2], b[3])
            sh.setFloatUniform("b1", b[4], b[5], b[6], b[7])
            sh.setFloatUniform("b2", b[8], b[9], b[10], b[11])
            sh.setFloatUniform("limit", f.limit)
            sh.setFloatUniform("soft", f.soft)
            sh.setFloatUniform("G", f.g)
            sh.setFloatUniform("glow", f.glow)
            sh.setFloatUniform("tilt", f.tilt)
            sh.setFloatUniform("shift", f.shift)
            sh.setFloatUniform("ground", f.ground[0], f.ground[1], f.ground[2])
            sh.setFloatUniform("colA", f.colA[0], f.colA[1], f.colA[2])
            sh.setFloatUniform("colB", f.colB[0], f.colB[1], f.colB[2])
    }

    private fun drawBitmap(c: Canvas, w: Float, h: Float, f: Frame) {
        val b = bmp ?: Bitmap.createBitmap(FALLBACK_W, FALLBACK_H, Bitmap.Config.ARGB_8888).also { bmp = it }
        for (i in 0 until FALLBACK_W) {
            val x = (i + 0.5f) * PAGE_W / FALLBACK_W
            colS[i] = surface(f, x); colPk[i] = peakAt(f, x); colW[i] = mixAt(f, x)
        }
        for (j in 0 until FALLBACK_H) {
            val y = (j + 0.5f) * PAGE_H / FALLBACK_H
            for (i in 0 until FALLBACK_W) {
                val a = alpha(f, y, colS[i], colPk[i]); val w = colW[i]
                for (k in 0..2) px[k] = f.ground[k] + a * (f.colA[k] + (f.colB[k] - f.colA[k]) * w)
                bmpPixels[j * FALLBACK_W + i] = srgb(px[0], px[1], px[2])
            }
        }
        b.setPixels(bmpPixels, 0, FALLBACK_W, 0, 0, FALLBACK_W, FALLBACK_H)
        bmpRect.set(0f, 0f, w, h)
        c.drawBitmap(b, null, bmpRect, bmpPaint)
        once("music body: drawn by the bitmap (software canvas)")
    }

    private fun once(what: String) { if (said != what) { said = what; runCatching { android.util.Log.i("AiUsageRear", what) } } }

    private const val FALLBACK_W = 122
    private const val FALLBACK_H = 75
}
