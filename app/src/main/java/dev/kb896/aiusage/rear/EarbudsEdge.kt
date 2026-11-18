package dev.kb896.aiusage.rear

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/** Siri-style coloured light blooming inward from all edges of the front glass.
 * A warped periodic field varies each lobe's depth and breath; no travelling heads or fixed-width stroke.
 * Pure timeline and premultiplied colour formula; production reuses Frame and scratch arrays every vsync. */
object EarbudsEdge {
    const val IN_MS = 300L
    const val RECEDE_MS = 1_350L
    const val RUN_MS = 1_750L
    // Compact support ends eight reference pixels before the shaded strips, with zero first/second derivatives.
    const val BAND = 216f
    const val MIN_REACH = 52f
    const val MAX_REACH = 208f
    const val PEAK = 0.92f
    const val FALLBACK_RADIUS = 170f

    fun smooth(a: Float, b: Float, t: Float): Float {
        val u = ((t - a) / (b - a)).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    class Frame {
        var time = 0f
        var amp = 0f
        var spread = 0f
    }

    fun at(t: Float, out: Frame = Frame()): Frame {
        out.time = t.coerceIn(0f, RUN_MS.toFloat()) / 1000f
        val bloom = smooth(0f, IN_MS.toFloat(), t)
        val retreat = 1f - smooth(RECEDE_MS.toFloat(), RUN_MS.toFloat(), t)
        out.amp = bloom * retreat
        // Depth retreats along with opacity: the clouds withdraw into the bezel, rather than just fading in place.
        out.spread = 0.18f + 0.82f * bloom * retreat
        return out
    }

    /** Warm up the pipeline invisibly, then start on the second vsync. */
    class Clock {
        private var warmed = false
        private var start = -1L
        val running get() = start >= 0
        fun at(now: Long): Float {
            if (!warmed) { warmed = true; return 0f }
            if (start < 0) start = now
            return (now - start).toFloat()
        }
    }

    /** Current window pixels. Corner order matches RoundedCorner: top left, top right, bottom right, bottom left. */
    class Geometry(val w: Float, val h: Float, tl: Float, tr: Float, br: Float, bl: Float) {
        val k = minOf(w, h) / 1200f
        val tl = radius(tl); val tr = radius(tr); val br = radius(br); val bl = radius(bl)
        val band = BAND * k
        val strip = maxOf(this.tl, this.tr, this.br, this.bl, band)
        private fun radius(value: Float) = (if (value.isFinite() && value > 0f) value else FALLBACK_RADIUS * k)
            .coerceIn(1f, minOf(w, h) / 2f)

        /** Inward rounded-glass distance and a smooth angular perimeter coordinate.
         * The angle extends continuously into the glow, even past a corner's radius where nearest-arc
         * arclength has a singularity. Integer harmonics join seamlessly across its -PI/PI seam. */
        fun sample(x: Float, y: Float, out: FloatArray) {
            val left = x < w / 2f
            val top = y < h / 2f
            val r = if (top) { if (left) tl else tr } else { if (left) bl else br }
            val ex = if (left) x else w - x
            val ey = if (top) y else h - y
            out[0] = if (ex < r && ey < r) {
                val dx = r - ex; val dy = r - ey
                r - sqrt(dx * dx + dy * dy)
            } else minOf(ex, ey)
            out[1] = atan2((y - h * 0.5f) / h, (x - w * 0.5f) / w)
        }
    }

    /** RGBA premultiplied, before sub-level shader dither; scratch has at least two entries. */
    fun light(f: Frame, g: Geometry, x: Float, y: Float, out: FloatArray, scratch: FloatArray) {
        g.sample(x, y, scratch)
        val d = scratch[0] / g.k
        if (d < 0f || d >= BAND || f.amp <= 0f) { out.fill(0f); return }
        shade(f, scratch[1], d, out)
    }

    /** Periodic in s (radians), including its derivatives. Also used to audit the wrap directly. */
    internal fun shade(f: Frame, s: Float, d: Float, out: FloatArray) {
        if (d < 0f || d >= MAX_REACH || f.amp <= 0f) { out.fill(0f); return }
        val t = f.time
        val q = s + 0.10f * sin(2f * s - 0.65f * t) + 0.06f * sin(3f * s + 0.50f * t + 1f)
        val n = 0.50f + 0.30f * sin(4f * q + 1.105f * t) + 0.20f * sin(5f * q - 1.43f * t + 0.8f)
        val reach = (MIN_REACH + (MAX_REACH - MIN_REACH) * n) * f.spread
        val u = (d / reach).coerceIn(0f, 1f)
        // Quartic compact falloff: max |dF/du| = (16f / 9f), zero slope at both bezel and tail.
        val v = 1f - u
        val cloud = v * v * v * (1f + 3f * u)
        val breath = 0.90f + 0.10f * sin(3f * q + 1.20f * t + 0.4f)
        val a = f.amp * PEAK * breath * (0.55f + 0.45f * n) * cloud
        // A warm-to-cool ribbon folds smoothly back through the same palette: no green/yellow rainbow sector.
        val colourAt = (0.50f + 0.50f * sin(s + 0.28f * sin(2f * s - 0.55f * t) - 0.38f * t)) * 5f
        palette(colourAt, out)
        val white = 0.06f * smooth(0f, 160f, d)
        for (i in 0..2) out[i] = (out[i] + (1f - out[i]) * white) * a
        out[3] = a
    }

    /** Orange, pink, magenta, violet, blue, cyan: the named Siri reference retains its violet. */
    private fun palette(v: Float, out: FloatArray) {
        val i = v.toInt().coerceIn(0, 4)
        val u = smooth(0f, 1f, v - i)
        fun channel(a: Float, b: Float) = a + (b - a) * u
        when (i) {
            0 -> { out[0] = 1f; out[1] = channel(0.30f, 0.10f); out[2] = channel(0.025f, 0.38f) }
            1 -> { out[0] = channel(1f, 0.94f); out[1] = channel(0.10f, 0.035f); out[2] = channel(0.38f, 0.68f) }
            2 -> { out[0] = channel(0.94f, 0.48f); out[1] = channel(0.035f, 0.06f); out[2] = channel(0.68f, 1f) }
            3 -> { out[0] = channel(0.48f, 0.045f); out[1] = channel(0.06f, 0.32f); out[2] = 1f }
            else -> { out[0] = channel(0.045f, 0.025f); out[1] = channel(0.32f, 0.80f); out[2] = 1f }
        }
    }

    private fun g(x: Float) = "%.8f".format(Locale.ROOT, x)
    val AGSL = """
uniform float2 uSize;
uniform float4 uCorners;
uniform float4 uFrame;
float3 palette(float v) {
  float u = smoothstep(0.0, 1.0, fract(v));
  if (v < 1.0) return mix(float3(1.0, 0.30, 0.025), float3(1.0, 0.10, 0.38), u);
  if (v < 2.0) return mix(float3(1.0, 0.10, 0.38), float3(0.94, 0.035, 0.68), u);
  if (v < 3.0) return mix(float3(0.94, 0.035, 0.68), float3(0.48, 0.06, 1.0), u);
  if (v < 4.0) return mix(float3(0.48, 0.06, 1.0), float3(0.045, 0.32, 1.0), u);
  return mix(float3(0.045, 0.32, 1.0), float3(0.025, 0.80, 1.0), smoothstep(4.0, 5.0, v));
}
half4 main(float2 p) {
  float w = uSize.x, h = uSize.y;
  bool left = p.x < w * 0.5, top = p.y < h * 0.5;
  float r = top ? (left ? uCorners.x : uCorners.y) : (left ? uCorners.w : uCorners.z);
  float ex = left ? p.x : w - p.x, ey = top ? p.y : h - p.y;
  float d = (ex < r && ey < r) ? r - length(float2(r - ex, r - ey)) : min(ex, ey);
  d /= uFrame.w;
  if (d < 0.0 || d >= ${g(BAND)} || uFrame.y <= 0.0) return half4(0.0);
  float s = atan((p.y - h * 0.5) / h, (p.x - w * 0.5) / w);
  float t = uFrame.x;
  float q = s + 0.10 * sin(2.0 * s - 0.65 * t) + 0.06 * sin(3.0 * s + 0.50 * t + 1.0);
  float n = 0.50 + 0.30 * sin(4.0 * q + 1.105 * t) + 0.20 * sin(5.0 * q - 1.43 * t + 0.8);
  float reach = (${g(MIN_REACH)} + ${g(MAX_REACH - MIN_REACH)} * n) * uFrame.z;
  float u = clamp(d / reach, 0.0, 1.0);
  float v = 1.0 - u;
  float cloud = v * v * v * (1.0 + 3.0 * u);
  float breath = 0.90 + 0.10 * sin(3.0 * q + 1.20 * t + 0.4);
  float a = uFrame.y * ${g(PEAK)} * breath * (0.55 + 0.45 * n) * cloud;
  float colourAt = (0.50 + 0.50 * sin(s + 0.28 * sin(2.0 * s - 0.55 * t) - 0.38 * t)) * 5.0;
  float3 col = palette(colourAt);
  float white = 0.06 * smoothstep(0.0, 160.0, d);
  col += (1.0 - col) * white;
  // Static sub-level dither avoids bands without time-varying grain or strobing.
  float noiseGate = smoothstep(0.0, 0.02, a);
  float nc = (fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715)))) - 0.5) / 255.0 * noiseGate;
  float na = (fract(52.9829189 * fract(dot(p + float2(19.19, 73.73), float2(0.06711056, 0.00583715)))) - 0.5) / 255.0 * noiseGate;
  float alpha = clamp(a + na, 0.0, 1.0);
  return half4(clamp(col * a + nc, float3(0.0), float3(alpha)), alpha);
}
""".trimIndent()
}
