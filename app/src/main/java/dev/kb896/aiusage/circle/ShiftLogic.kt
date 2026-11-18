package dev.kb896.aiusage.circle

import kotlin.math.abs

/**
 * How far a page moved between two pictures of the screen, found from the pictures alone. No Android classes
 * (tested in ShiftLogicTest). Used when a scrolled page comes to rest: translations that are already on the
 * screen are moved along with their text at once, instead of waiting for the screen to be read again.
 *
 * A picture is reduced to one small vector per pixel row: the brightness of [K] narrow vertical strips.
 * Rows that look like the row above them (empty background, flat colour) say nothing about movement and
 * are left out.
 */
object ShiftLogic {
    const val K = 12
    /** Mean difference per strip (of 255) up to which two rows count as the same row. */
    private const val SAME = 9

    class Rows(val h: Int, val f: ByteArray) {
        /** Rows that differ from the row above: text, edges of pictures. */
        val info = BooleanArray(h)
        var informative = 0
        init {
            for (r in 1 until h) {
                var d = 0
                for (k in 0 until K) d += abs((f[r * K + k].toInt() and 255) - (f[(r - 1) * K + k].toInt() and 255))
                if (d > 2 * K) { info[r] = true; informative++ }
            }
        }
    }

    private fun dist(a: Rows, ra: Int, b: Rows, rb: Int): Int {
        var d = 0
        val ia = ra * K; val ib = rb * K
        for (k in 0 until K) d += abs((a.f[ia + k].toInt() and 255) - (b.f[ib + k].toInt() and 255))
        return d
    }

    /**
     * Share of the informative rows of [new] between [top] and [bottom] that are the same as the row [dy] further
     * down in [old] (0..1); -1 if that part has no informative rows or lies outside [old].
     */
    fun match(old: Rows, new: Rows, top: Int, bottom: Int, dy: Int, step: Int = 1): Float {
        var n = 0; var ok = 0
        var r = top.coerceAtLeast(0)
        val end = bottom.coerceAtMost(new.h)
        while (r < end) {
            val o = r + dy
            if (o < 0 || o >= old.h) return -1f
            if (new.info[r] || old.info[o]) { n++; if (dist(new, r, old, o) <= SAME * K) ok++ }
            r += step
        }
        return if (n == 0) -1f else ok.toFloat() / n
    }

    /**
     * The part of [new] that moved by [dy]: from the first to the last row that is the same as the row [dy] further
     * down in [old] (a few stray rows at either end are not counted). Fixed bars above and below the scrolling part
     * lie outside it. Null if hardly anything matches.
     */
    fun region(old: Rows, new: Rows, dy: Int): IntRange? {
        val rows = ArrayList<Int>()
        for (r in maxOf(0, -dy) until minOf(new.h, new.h - dy)) if (new.info[r] && dist(new, r, old, r + dy) <= SAME * K) rows.add(r)
        if (rows.size < 12) return null
        return rows[rows.size * 2 / 100]..rows[rows.size - 1 - rows.size * 2 / 100]
    }

    /**
     * The movement of the page's content: a row [r] of [new] shows what row [r] + result showed in [old]
     * (positive = the page was scrolled down, its content moved up). Fixed parts (bars) do not count.
     * Null when no single movement explains a good part of the picture.
     */
    fun shift(old: Rows, new: Rows, minOverlap: Float = 0.12f): Int? {
        if (old.h != new.h || new.informative < 24) return null
        val h = new.h
        // anchors: informative rows of the new picture, spread evenly
        val want = 72
        val anchors = ArrayList<Int>()
        val every = (new.informative / want).coerceAtLeast(1)
        var seen = 0
        for (r in 0 until h) if (new.info[r]) { if (seen % every == 0) anchors.add(r); seen++ }
        val votes = HashMap<Int, Int>()
        for (a in anchors) for (r in 0 until h) if (old.info[r] && dist(new, a, old, r) <= SAME * K / 2) { val dy = r - a; votes[dy] = (votes[dy] ?: 0) + 1 }
        val maxDy = (h * (1f - minOverlap)).toInt()
        // the strongest candidates (neighbouring values count together), then a full count for each
        val cands = votes.entries.filter { abs(it.key) <= maxDy }
            .sortedByDescending { it.value + (votes[it.key - 1] ?: 0) + (votes[it.key + 1] ?: 0) }.take(6).map { it.key }
        var best: Int? = null; var bestRows = 0; var bestOf = 0
        for (dy in cands) {
            var n = 0; var of = 0
            val from = maxOf(0, -dy); val to = minOf(h, h - dy)
            var r = from
            while (r < to) { if (new.info[r]) { of++; if (dist(new, r, old, r + dy) <= SAME * K) n++ }; r += 2 }
            if (n > bestRows) { bestRows = n; best = dy; bestOf = of }
        }
        // Of the rows both pictures share at that movement, two in five must really be the same rows (a fixed bar
        // alone is far below that), and there must be enough of them to mean something.
        return if (best != null && bestRows >= 12 && bestRows * 5 >= bestOf * 2) best else null
    }
}
