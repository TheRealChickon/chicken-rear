package dev.kb896.aiusage.rear

import kotlin.math.exp

/**
 * DOES OUR FIRST FRAME LOOK LIKE XIAOMI'S OWN PICTURE OF THE FACE? (SPEC 3.4.9) Both are scaled to [W] x [H], made
 * grey, blurred (σ [BLUR] px) and compared by SSIM. A face under FaceCatalog.FAITHFUL_AT is left out: a partial face is
 * never shown (Gradient without its glass orb is not Gradient).
 *
 * THIS IS A HEURISTIC. A face's live picture is not its preview (another time on the clock, another frame of its
 * motion), so the blur and the small size are there to compare its layout and its masses, not its details; the
 * threshold is a guess from the probe frames and is to be checked on the phone ("faces: … (0.xx)" in the log).
 *
 * Pure: ARGB pixels in.
 */
object Faithful {
    const val W = 122
    const val H = 74
    const val BLUR = 2.0

    /** [a] ([aw] x [ah]) against [b] ([bw] x [bh]), 0 to 1. */
    fun score(a: IntArray, aw: Int, ah: Int, b: IntArray, bw: Int, bh: Int): Double {
        val x = blur(grey(a, aw, ah))
        val y = blur(grey(b, bw, bh))
        return ssim(x, y)
    }

    /** Area-averaged down to [W] x [H], luminance 0 to 1. */
    fun grey(px: IntArray, w: Int, h: Int): DoubleArray {
        val out = DoubleArray(W * H)
        val n = IntArray(W * H)
        for (yy in 0 until h) {
            val ty = (yy.toLong() * H / h).toInt()
            for (xx in 0 until w) {
                val tx = (xx.toLong() * W / w).toInt()
                val p = px[yy * w + xx]
                val a = (p ushr 24 and 255) / 255.0
                val l = (0.2126 * (p shr 16 and 255) + 0.7152 * (p shr 8 and 255) + 0.0722 * (p and 255)) / 255.0
                out[ty * W + tx] += l * a
                n[ty * W + tx]++
            }
        }
        for (i in out.indices) if (n[i] > 0) out[i] /= n[i]
        return out
    }

    private fun kernel(sigma: Double): DoubleArray {
        val r = (sigma * 3).toInt().coerceAtLeast(1)
        val k = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val s = k.sum(); for (i in k.indices) k[i] /= s
        return k
    }

    fun blur(g: DoubleArray, sigma: Double = BLUR): DoubleArray {
        val k = kernel(sigma); val r = k.size / 2
        val t = DoubleArray(g.size)
        for (y in 0 until H) for (x in 0 until W) {
            var s = 0.0
            for (i in k.indices) s += k[i] * g[y * W + (x + i - r).coerceIn(0, W - 1)]
            t[y * W + x] = s
        }
        val o = DoubleArray(g.size)
        for (y in 0 until H) for (x in 0 until W) {
            var s = 0.0
            for (i in k.indices) s += k[i] * t[(y + i - r).coerceIn(0, H - 1) * W + x]
            o[y * W + x] = s
        }
        return o
    }

    /** Mean SSIM over Gaussian windows (σ 1.5), the usual constants for values 0 to 1. */
    fun ssim(x: DoubleArray, y: DoubleArray): Double {
        val c1 = 0.01 * 0.01; val c2 = 0.03 * 0.03
        val mx = blur(x, 1.5); val my = blur(y, 1.5)
        val xx = blur(DoubleArray(x.size) { x[it] * x[it] }, 1.5)
        val yy = blur(DoubleArray(y.size) { y[it] * y[it] }, 1.5)
        val xy = blur(DoubleArray(x.size) { x[it] * y[it] }, 1.5)
        var sum = 0.0
        for (i in x.indices) {
            val vx = xx[i] - mx[i] * mx[i]; val vy = yy[i] - my[i] * my[i]; val cxy = xy[i] - mx[i] * my[i]
            sum += ((2 * mx[i] * my[i] + c1) * (2 * cxy + c2)) / ((mx[i] * mx[i] + my[i] * my[i] + c1) * (vx + vy + c2))
        }
        return (sum / x.size).coerceIn(0.0, 1.0)
    }
}
