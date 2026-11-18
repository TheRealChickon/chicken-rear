package dev.kb896.aiusage.circle

/**
 * The look of a line of text, read from its pixels: how heavy the strokes are and how the ink is spread along
 * the line. No Android classes (tested in LookTest). OverlayView draws the same words in candidate typefaces,
 * measures those drawings with the same functions and takes the candidate that comes closest.
 */
object Look {
    fun colorDistance(a: Int, b: Int): Int =
        kotlin.math.abs((a shr 16 and 255) - (b shr 16 and 255)) +
            kotlin.math.abs((a shr 8 and 255) - (b shr 8 and 255)) +
            kotlin.math.abs((a and 255) - (b and 255))

    /**
     * A small changed label should still count on an otherwise static screen. Measure changed texels
     * per tile, including shifted tiles so a short word can straddle a tile boundary.
     */
    fun sameFrame(a: IntArray, b: IntArray, w: Int, h: Int, top: Int, bottom: Int, right: Int): Boolean {
        val tile = 8
        val row = (w + tile - 1) / tile + 1
        val count = IntArray(row * ((h + tile - 1) / tile + 1) * 2)
        val offset = count.size / 2
        for (y in top until h - bottom) for (x in 0 until w - right) {
            val i = y * w + x
            if (colorDistance(a[i], b[i]) <= 32) continue
            if (++count[y / tile * row + x / tile] >= 5 ||
                ++count[offset + (y + tile / 2) / tile * row + (x + tile / 2) / tile] >= 5) return false
        }
        return true
    }

    /** Compare short runs of pixels around the text so a gentle gradient still keeps its colour variation. */
    fun evenBackground(pixels: IntArray, w: Int, h: Int): Boolean {
        val maxChannelSpread = 7
        val low = IntArray(3) { 255 }
        val high = IntArray(3)
        val edge = IntArray(9)
        fun sample(x: Int, y: Int, dx: Int, dy: Int) {
            for (c in 0..2) {
                for (i in edge.indices) {
                    val px = (x + (i - 4) * dx).coerceIn(0, w - 1)
                    val py = (y + (i - 4) * dy).coerceIn(0, h - 1)
                    edge[i] = pixels[py * w + px] shr (c * 8) and 255
                }
                edge.sort()
                low[c] = minOf(low[c], edge[4])
                high[c] = maxOf(high[c], edge[4])
            }
        }
        for (i in 0..4) {
            val x = i * (w - 1) / 4
            val y = i * (h - 1) / 4
            sample(x, 0, 1, 0)
            sample(x, h - 1, 1, 0)
            sample(0, y, 0, 1)
            sample(w - 1, y, 0, 1)
        }
        return low.indices.all { high[it] - low[it] <= maxChannelSpread }
    }

    /** Use the most frequent high-contrast glyph cores, ignoring isolated highlights and antialiasing. */
    fun inkColor(pixels: IntArray, distances: IntArray): Int? {
        val contrast = IntArray(766)
        var inkCount = 0
        for (d in distances) if (d >= 18) { contrast[d]++; inkCount++ }
        if (inkCount < 4) return null
        var count = 0
        var peak = 765
        while (peak > 18 && count + contrast[peak] < maxOf(4, inkCount / 8)) count += contrast[peak--]
        val threshold = maxOf(18, (peak * 0.78f).toInt())
        val bins = IntArray(4096)
        fun bin(c: Int) = (c shr 12 and 0xf00) or (c shr 8 and 0xf0) or (c shr 4 and 0xf)
        for (i in pixels.indices) if (distances[i] >= threshold) bins[bin(pixels[i])]++
        val best = bins.indices.maxByOrNull { bins[it] } ?: return null
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (i in pixels.indices) {
            val c = pixels[i]
            if (distances[i] < threshold || bin(c) != best) continue
            r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255; n++
        }
        if (n == 0) return null
        return (0xff shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** Blend measured glyph height with a reliable lowercase x-height estimate. */
    fun fontSize(glyph: Float, referenceGlyph: Float, xHeight: Float, referenceX: Float): Float {
        val size = glyph * 100f / referenceGlyph.coerceAtLeast(1f)
        if (xHeight <= 0f || referenceX <= 0f) return size
        val fromX = xHeight * 100f / referenceX
        return if (fromX in size * 0.85f..size * 1.15f) (size + fromX) / 2f else size
    }

    /** The largest size that fits, retaining the original size whenever possible. */
    fun fitScale(fits: (Float) -> Boolean): Float {
        if (fits(1f)) return 1f
        var lo = 0.60f
        var hi = 1f
        if (!fits(lo)) return lo
        repeat(8) {
            val mid = (lo + hi) / 2f
            if (fits(mid)) lo = mid else hi = mid
        }
        return lo
    }

    fun letterSpacing(inkWidth: Float, expectedWidth: Float, size: Float, gaps: Int): Float =
        if (gaps < 4 || size <= 0f) 0f else ((inkWidth - expectedWidth) / (size * gaps)).coerceIn(-0.035f, 0.06f)

    /** Font weights tried, lightest first. */
    val WEIGHTS = listOf(300, 400, 500, 600, 700, 800)
    const val BINS = 96

    /** [aspect] = width of the inked part divided by its height: the same words are wider in some typefaces than in others. */
    class Ink(val stem: Float, val profile: FloatArray, val aspect: Float, val height: Int = 1) {
        /** Stroke width as a share of the height of the inked part: comparable between a line on the screen and a drawing of it. */
        val rel: Float get() = stem / height
    }

    /**
     * [mask] = ink or not, [w] x [h], rows first. The stroke width is taken from the horizontal runs of ink in the
     * middle of the line: upright strokes give the short runs, bowls and bars the long ones, so the mean of the
     * runs between the 20th and the 50th percentile is the stroke. The profile is the share of ink per column,
     * brought to [BINS] columns between the first and the last column that has ink.
     */
    fun measure(mask: BooleanArray, w: Int, h: Int): Ink? {
        if (w < 4 || h < 6 || mask.size < w * h) return null
        val runs = ArrayList<Int>()
        for (y in h * 15 / 100 until h * 85 / 100) {
            var run = 0
            for (x in 0 until w) {
                if (mask[y * w + x]) run++ else { if (run > 0) runs.add(run); run = 0 }
            }
            if (run > 0) runs.add(run)
        }
        if (runs.size < 8) return null
        runs.sort()
        val a = runs.size * 20 / 100; val b = (runs.size * 50 / 100).coerceAtLeast(a + 1)
        var s = 0f
        for (i in a until b) s += runs[i]
        val stem = s / (b - a)
        val col = IntArray(w)
        var first = -1; var last = -1
        for (x in 0 until w) {
            var n = 0
            for (y in 0 until h) if (mask[y * w + x]) n++
            col[x] = n
            if (n > 0) { if (first < 0) first = x; last = x }
        }
        if (first < 0 || last - first < 8) return null
        val prof = FloatArray(BINS)
        val span = (last - first + 1).toFloat()
        for (i in 0 until BINS) {
            val x0 = first + (i * span / BINS).toInt(); val x1 = (first + ((i + 1) * span / BINS).toInt()).coerceIn(x0 + 1, last + 1)
            var n = 0
            for (x in x0 until x1) n += col[x]
            prof[i] = n.toFloat() / ((x1 - x0) * h)
        }
        var top = -1; var bot = -1
        for (y in 0 until h) { var any = false; for (x in first..last) if (mask[y * w + x]) { any = true; break }; if (any) { if (top < 0) top = y; bot = y } }
        return Ink(stem, prof, span / (bot - top + 1).coerceAtLeast(1), (bot - top + 1).coerceAtLeast(1))
    }

    /**
     * The weight whose drawn stroke width ([drawn], one per entry of [WEIGHTS], 0 = could not be drawn) is nearest to
     * the stroke width [seen] on the screen, between two weights where the stroke lies between theirs.
     */
    fun weight(seen: Float, drawn: List<Float>): Int {
        val pts = WEIGHTS.indices.filter { drawn.getOrElse(it) { 0f } > 0f }
        if (pts.isEmpty()) return 400
        return distinct(weightRaw(seen, drawn, pts), drawn)
    }

    /** A typeface that has no such weight draws it like regular: then the answer is regular. */
    private fun distinct(w: Int, drawn: List<Float>): Int {
        val reg = WEIGHTS.indexOf(400); val i = WEIGHTS.indexOf(w)
        if (i < 0 || reg < 0 || drawn.getOrElse(reg) { 0f } <= 0f) return w
        return if (kotlin.math.abs(drawn[i] - drawn[reg]) <= drawn[reg] * 0.03f) 400 else w
    }

    private fun weightRaw(seen: Float, drawn: List<Float>, pts: List<Int>): Int {
        if (seen <= drawn[pts.first()]) return WEIGHTS[pts.first()]
        // Thinner than regular but not as thin as the lightest: regular. Most text is regular, and a wrong "light" shows.
        val reg = WEIGHTS.indexOf(400)
        if (reg in pts && seen < drawn[reg]) return 400
        if (seen >= drawn[pts.last()]) return WEIGHTS[pts.last()]
        for (k in 1 until pts.size) {
            val lo = pts[k - 1]; val hi = pts[k]
            if (seen <= drawn[hi]) {
                val f = if (drawn[hi] > drawn[lo]) (seen - drawn[lo]) / (drawn[hi] - drawn[lo]) else 0f
                val wgt = WEIGHTS[lo] + (WEIGHTS[hi] - WEIGHTS[lo]) * f
                return ((wgt + 50f) / 100f).toInt() * 100
            }
        }
        return 400
    }

    fun correlation(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var ma = 0f; var mb = 0f
        for (i in 0 until n) { ma += a[i]; mb += b[i] }
        ma /= n; mb /= n
        var sab = 0f; var saa = 0f; var sbb = 0f
        for (i in 0 until n) { val x = a[i] - ma; val y = b[i] - mb; sab += x * y; saa += x * x; sbb += y * y }
        return if (saa <= 0f || sbb <= 0f) 0f else sab / kotlin.math.sqrt(saa * sbb)
    }

    /**
     * Which of the drawn candidates ([cands], index 0 = the default sans) is the typeface of the line on the screen.
     * A wrong kind is far more visible than a missed one, so another kind is only taken when all of this holds:
     * its ink is spread along the line almost exactly like the line's ([minFit]), clearly better than the default's
     * ([margin]), and the words are as wide as that kind draws them ([maxWidthOff]). Measured on drawn lines
     * (LookBench): the right kind fits with 0.85-0.99 and is 0-5 % off in width; a wrong one rarely reaches 0.75 and
     * is 7-35 % off. When the recognizer misread a word nothing fits well, and the default stays.
     */
    fun family(seen: Ink, cands: List<Ink?>, minFit: Float = 0.80f, margin: Float = 0.15f, maxWidthOff: Float = 0.06f): Int {
        val c = cands.map { if (it == null) -1f else correlation(seen.profile, it.profile) }
        var best = 0
        for (i in 1 until c.size) if (c[i] > c[best]) best = i
        if (best == 0 || c[best] < minFit || c[best] < c[0] + margin) return 0
        val b = cands[best] ?: return 0
        return if (kotlin.math.abs(b.aspect - seen.aspect) / seen.aspect <= maxWidthOff) best else 0
    }
}
