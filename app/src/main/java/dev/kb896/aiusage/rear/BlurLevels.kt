package dev.kb896.aiusage.rear

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object BlurLevels {
    val RADII = floatArrayOf(0f, 4f, 8f, 16f, 32f, 64f, 128f)
    /** Sigma, in working pixels, above which the picture is first made smaller. */
    private const val WORK = 6f

    /**
     * All levels of [rgba] ([w] x [h], 4 bytes a pixel). [pxPerRadius]: the picture's pixels per unit of radius
     * (1 when the radii are in the picture's own pixels; 0.25 for a picture a quarter the size of the units).
     */
    fun levels(rgba: ByteArray, w: Int, h: Int, pxPerRadius: Float = 1f): List<ByteArray> =
        RADII.map { r -> if (r <= 0f) rgba.copyOf() else blurred(rgba, w, h, r / 2f * pxPerRadius) }

    /** [rgba] blurred by a Gaussian of [sigma] pixels. */
    fun blurred(rgba: ByteArray, w: Int, h: Int, sigma: Float): ByteArray {
        if (sigma < 0.3f) return rgba.copyOf()
        var d = 1
        while (sigma / d > WORK && w / (d * 2) >= 4 && h / (d * 2) >= 4) d *= 2
        val sw = max(1, w / d); val sh = max(1, h / d)
        val small = if (d == 1) toFloats(rgba) else shrink(rgba, w, h, sw, sh)
        for (box in boxes(sigma / d)) { boxH(small, sw, sh, box); boxV(small, sw, sh, box) }
        return if (d == 1) toBytes(small) else grow(small, sw, sh, w, h)
    }

    /** The three box widths' half-sizes for a Gaussian of [sigma] (the usual three-box fit). */
    fun boxes(sigma: Float): IntArray {
        val n = 3
        val wIdeal = sqrt(12f * sigma * sigma / n + 1f)
        var wl = floor(wIdeal).toInt(); if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12f * sigma * sigma - n * wl * wl - 4f * n * wl - 3f * n) / (-4f * wl - 4f)
        val mm = mIdeal.roundToInt()
        return IntArray(n) { i -> ((if (i < mm) wl else wu) - 1) / 2 }
    }

    private fun toFloats(b: ByteArray) = FloatArray(b.size) { (b[it].toInt() and 0xFF).toFloat() }
    private fun toBytes(f: FloatArray) = ByteArray(f.size) { f[it].roundToInt().coerceIn(0, 255).toByte() }

    /** The mean of each d x d block. */
    private fun shrink(b: ByteArray, w: Int, h: Int, sw: Int, sh: Int): FloatArray {
        val out = FloatArray(sw * sh * 4)
        val dx = w.toFloat() / sw; val dy = h.toFloat() / sh
        for (y in 0 until sh) {
            val y0 = (y * dy).toInt(); val y1 = min(h, ((y + 1) * dy).toInt().coerceAtLeast(y0 + 1))
            for (x in 0 until sw) {
                val x0 = (x * dx).toInt(); val x1 = min(w, ((x + 1) * dx).toInt().coerceAtLeast(x0 + 1))
                var r = 0f; var g = 0f; var bl = 0f; var a = 0f; var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    val i = (yy * w + xx) * 4
                    r += b[i].toInt() and 0xFF; g += b[i + 1].toInt() and 0xFF; bl += b[i + 2].toInt() and 0xFF; a += b[i + 3].toInt() and 0xFF; n++
                }
                val o = (y * sw + x) * 4
                out[o] = r / n; out[o + 1] = g / n; out[o + 2] = bl / n; out[o + 3] = a / n
            }
        }
        return out
    }

    /** Back to [w] x [h], by a straight line between the small picture's pixel centres. */
    private fun grow(s: FloatArray, sw: Int, sh: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 4)
        val kx = sw.toFloat() / w; val ky = sh.toFloat() / h
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * ky - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(sh - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * kx - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(sw - 1, x0 + 1); val tx = fx - x0
                val a = (y0 * sw + x0) * 4; val b = (y0 * sw + x1) * 4; val c = (y1 * sw + x0) * 4; val dd = (y1 * sw + x1) * 4
                val o = (y * w + x) * 4
                for (k in 0 until 4) {
                    val top = s[a + k] + (s[b + k] - s[a + k]) * tx
                    val bot = s[c + k] + (s[dd + k] - s[c + k]) * tx
                    out[o + k] = (top + (bot - top) * ty).roundToInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    /** A box of half-size [r] along each row, the edge pixels carried outwards. */
    private fun boxH(p: FloatArray, w: Int, h: Int, r: Int) {
        if (r <= 0) return
        val row = FloatArray(w * 4)
        val n = 2f * r + 1f
        for (y in 0 until h) {
            val base = y * w * 4
            for (k in 0 until 4) {
                var sum = 0f
                for (i in -r..r) sum += p[base + i.coerceIn(0, w - 1) * 4 + k]
                for (x in 0 until w) {
                    row[x * 4 + k] = sum / n
                    sum += p[base + (x + r + 1).coerceAtMost(w - 1) * 4 + k] - p[base + (x - r).coerceAtLeast(0) * 4 + k]
                }
            }
            System.arraycopy(row, 0, p, base, w * 4)
        }
    }

    private fun boxV(p: FloatArray, w: Int, h: Int, r: Int) {
        if (r <= 0) return
        val col = FloatArray(h)
        val n = 2f * r + 1f
        for (x in 0 until w) for (k in 0 until 4) {
            var sum = 0f
            for (i in -r..r) sum += p[(i.coerceIn(0, h - 1) * w + x) * 4 + k]
            for (y in 0 until h) {
                col[y] = sum / n
                sum += p[((y + r + 1).coerceAtMost(h - 1) * w + x) * 4 + k] - p[((y - r).coerceAtLeast(0) * w + x) * 4 + k]
            }
            for (y in 0 until h) p[(y * w + x) * 4 + k] = col[y]
        }
    }
}
