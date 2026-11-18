package dev.kb896.aiusage.circle

/**
 * The look of a line of text, read from its pixels: how heavy the strokes are and how the ink is spread along
 * the line. No Android classes (tested in LookTest). OverlayView draws the same words in candidate typefaces,
 * measures those drawings with the same functions and takes the candidate that comes closest.
 */
object Look {
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
