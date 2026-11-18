package dev.kb896.aiusage.circle

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs

/** The Android side of [TrLayout]: recognized blocks in, the blocks a reader sees out; the app's page and bars from its node tree. */
object TrBlocks {
    private fun ia(r: Rect) = intArrayOf(r.left, r.top, r.right, r.bottom)
    private fun rect(a: IntArray) = Rect(a[0], a[1], a[2], a[3])
    private fun toL(l: Line) = TrLayout.L(l.text, ia(l.box), l.parts.map { TrLayout.P(it.text, ia(it.box), it.conf) })

    /**
     * Every list item and every paragraph its own block; bullets and icons are left out of the text and kept on the
     * screen. With the picture ([src], view size [vw] x [vh]): a bullet the recognizer dropped is found left of its
     * line, lines in another colour (a title over its grey subtitle) are not one block, and a list item's text starts
     * at its first ink, not where the recognizer put its first word.
     */
    fun split(blocks: List<Block>, src: Bitmap? = null, vw: Int = 0, vh: Int = 0): List<Block> {
        val all = blocks.flatMap { block ->
            val lines = block.lines.map { TrLayout.columns(toL(it)) }
            if (lines.any { it.size > 1 }) {
                // Every fragment goes through the same geometry-based paragraph join below.
                lines.flatten().map { listOf(it) }
            } else listOf(lines.flatten())
        }
        // the screen's usual pitch per line height, for blocks too short to tell their own
        val known = all.mapNotNull { ls -> TrLayout.innerPitch(ls)?.let { TrLayout.lineHeight(ls) to it } }
        val items = ArrayList<TrLayout.Item>()
        // Weak list markers ("1.", "a)") at the start of lines: a block's lone one counts as a marker when a line of
        // another block starts with one of its kind in the same column, a line or two above or below.
        val heads = all.flatMapIndexed { bi, ls -> ls.mapNotNull { l -> l.parts.takeIf { it.size >= 2 }?.first()?.let { p -> TrLayout.weakMarker(p.text)?.let { k -> Triple(bi, k, l) } } } }
        for ((bi, ls) in all.withIndex()) {
            if (ls.isEmpty()) continue
            val hh = TrLayout.lineHeight(ls)
            val listed = heads.filter { it.first == bi }.filter { (_, k, l) ->
                heads.any { (bj, kj, lj) -> bj != bi && kj == k && abs(lj.box[0] - l.box[0]) <= hh && abs(lj.box[1] - l.box[1]) <= hh * 3.2f }
            }.map { it.second }.toSet()
            val ref = known.filter { abs(it.first - hh) <= hh * 0.15f }.map { it.second }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
            val pic = src != null && vw > 0 && vh > 0
            val ink = if (pic && ls.size > 1) ls.map { lineInk(src!!, vw, vh, it.box) } else emptyList()
            val bullets = if (pic) ls.map { bulletLeftOf(src!!, vw, vh, it.box, hh) } else emptyList()
            val xh = if (pic && ls.size == 2) ls.map { xHeight(src!!, vw, vh, it.box) } else emptyList()
            for (item0 in TrLayout.split(ls, ref, ink, bullets, xh, listed)) {
                // The column between a marker and its text must be clear of both: the recognizer's box of the first
                // word can start well right of its first letter, and the cut halfway to it went through the "O" of
                // "Open" (a sliver of it was left standing beside the translation).
                val keep = if (pic && item0.keepL != Int.MIN_VALUE) blankLeftOf(src!!, vw, vh, item0.lines[0].box, item0.keepL, hh) ?: item0.keepL else item0.keepL
                val item = if (keep == item0.keepL) item0 else TrLayout.Item(item0.lines, keep, item0.keepR, item0.split, item0.xh)
                // After a bullet the text starts at its first ink (the recognizer's box for a short first word can be
                // far off); the joining below and the patch's left edge go by it.
                val f = item.lines[0]
                val x = if (pic && item.keepL != Int.MIN_VALUE) inkLeft(src!!, vw, vh, rect(f.box), item.keepL)?.takeIf { it < f.box[0] } else null
                val xh = if (pic && item.lines.size == 1) xHeight(src!!, vw, vh, f.box) ?: 0 else 0
                val lines = if (x == null) item.lines else listOf(TrLayout.L(f.text, intArrayOf(x, f.box[1], f.box[2], f.box[3]),
                    f.parts.mapIndexed { i, p -> if (i == 0) TrLayout.P(p.text, intArrayOf(x, p.box[1], p.box[2], p.box[3]), p.conf) else p })) + item.lines.drop(1)
                val firstInk = if (pic) lineInk(src!!, vw, vh, lines.first().box) else null
                val lastInk = if (lines.size == 1) firstInk else if (pic) lineInk(src!!, vw, vh, lines.last().box) else null
                items.add(TrLayout.Item(lines, item.keepL, item.keepR, item.split, xh, firstInk, lastInk))
            }
        }
        val out = ArrayList<Block>()
        for (item in TrLayout.join(items)) {
            val lines = item.lines.map { l -> Line(l.text, rect(l.box), l.parts.map { Part(it.text, rect(it.box), it.conf) }) }
            out.add(Block(item.text, rect(item.box), lines, item.keepL, item.keepR))
        }
        return out
    }

    /** The height of a line's lowercase letters (the rows most of its ink is in), view pixels; null if unclear. */
    fun xHeight(src: Bitmap, vw: Int, vh: Int, box: IntArray): Int? {
        val s = scaled(src, vw, vh, box)
        val bw = s[2] - s[0]; val bh = s[3] - s[1]
        if (bw < 6 || bh < 6 || bw.toLong() * bh > 600_000) return null
        val px = IntArray(bw * bh); src.getPixels(px, 0, bw, s[0], s[1], bw, bh)
        val bg = commonest(px)
        val n = IntArray(bh) { y -> var c = 0; for (x in 0 until bw) if (dist(px[y * bw + x], bg) > 120) c++; c }
        val top = n.maxOrNull() ?: 0
        if (top < 3) return null
        var best = 0; var run = 0
        for (y in 0 until bh) { if (n[y] >= top * 0.35f) { run++; best = maxOf(best, run) } else run = 0 }
        return if (best < 3) null else (best / (src.height / vh.toFloat())).toInt()
    }

    private fun scaled(src: Bitmap, vw: Int, vh: Int, b: IntArray): IntArray {
        val kx = src.width / vw.toFloat(); val ky = src.height / vh.toFloat()
        val l = (b[0] * kx).toInt().coerceIn(0, src.width - 1); val t = (b[1] * ky).toInt().coerceIn(0, src.height - 1)
        return intArrayOf(l, t, (b[2] * kx).toInt().coerceIn(l + 1, src.width), (b[3] * ky).toInt().coerceIn(t + 1, src.height))
    }

    /** The commonest colour of the first [count] of [px] (to 4 bits a channel): the ground of a strip of text. */
    internal fun commonest(px: IntArray, count: Int = px.size): Int {
        // counted in a plain array (a boxed map per pixel made a translate tap block the UI thread for seconds)
        val n = IntArray(4096)
        for (i in 0 until count) { val c = px[i]; n[(c shr 12 and 0xF00) or (c shr 8 and 0xF0) or (c shr 4 and 0xF)]++ }
        var k = 0
        for (j in 1 until 4096) if (n[j] > n[k]) k = j
        return ((k shr 8) shl 20) or ((k shr 4 and 15) shl 12) or ((k and 15) shl 4) or 0x080808
    }

    /** The colour most of the letters of a line are drawn in (a link word in it does not change it); null if unclear. */
    fun lineInk(src: Bitmap, vw: Int, vh: Int, box: IntArray): Int? {
        val s = scaled(src, vw, vh, box)
        val bw = s[2] - s[0]; val bh = s[3] - s[1]
        if (bw < 6 || bh < 6 || bw.toLong() * bh > 600_000) return null
        val px = IntArray(bw * bh); src.getPixels(px, 0, bw, s[0], s[1], bw, bh)
        val bg = commonest(px)
        var nf = 0
        for (c in px) if (dist(c, bg) > 150) px[nf++] = c       // the far pixels, packed in front (px is not needed after)
        if (nf < 12) return null
        return commonest(px, nf)
    }

    /**
     * A bullet drawn left of the line [box] that the recognizer did not read: one small round blob of ink, about a
     * third of the line high, in the middle of its height, with nothing else between it and the text. Returns the
     * column between the blob and the text (what a translation may not paint over), or null.
     */
    fun bulletLeftOf(src: Bitmap, vw: Int, vh: Int, box: IntArray, lh: Int): Int? {
        val from = box[0] - (lh * 1.6f).toInt()
        if (from < 0) return null
        val s = scaled(src, vw, vh, intArrayOf(from, box[1], box[0] - 1, box[3]))
        val bw = s[2] - s[0]; val bh = s[3] - s[1]
        if (bw < 6 || bh < 6) return null
        val px = IntArray(bw * bh); src.getPixels(px, 0, bw, s[0], s[1], bw, bh)
        val bg = commonest(px)
        var x0 = bw; var x1 = -1; var y0 = bh; var y1 = -1; var n = 0
        for (y in 0 until bh) for (x in 0 until bw) if (dist(px[y * bw + x], bg) > 120) { n++; x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y) }
        if (n == 0) return null
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        val k = src.height / vh.toFloat()
        val lhp = lh * k
        // small, round, filled, about the middle of the line, and clear of the text on its right
        if (w < lhp * 0.1f || h < lhp * 0.1f || w > lhp * 0.45f || h > lhp * 0.45f) return null
        if (abs(w - h) > maxOf(w, h) * 0.4f || n < w * h * 0.5f) return null
        val cy = (y0 + y1) / 2f
        if (cy < bh * 0.3f || cy > bh * 0.75f) return null
        if (bw - 1 - x1 < lhp * 0.12f) return null
        val right = (s[0] + x1 + 1) / (src.width / vw.toFloat())
        return ((right + box[0]) / 2f).toInt()
    }

    /**
     * [x] (view coordinates) as a column between a marker and the text in the rows of [box]: x itself when it is clear
     * of ink (faint edges of letters included); when it falls inside a letter, the middle of the clear gap left of that
     * letter (down to the marker's ink), at most [lh] further left. Null when no clear gap is found.
     */
    fun blankLeftOf(src: Bitmap, vw: Int, vh: Int, box: IntArray, x: Int, lh: Int): Int? {
        val from = x - lh
        if (from < 0) return null
        val s = scaled(src, vw, vh, intArrayOf(from, box[1], x + 1, box[3]))
        val bw = s[2] - s[0]; val bh = s[3] - s[1]
        if (bw < 4 || bh < 4) return null
        val px = IntArray(bw * bh); src.getPixels(px, 0, bw, s[0], s[1], bw, bh)
        val bg = commonest(px)
        fun ink(c: Int): Boolean { var n = 0; for (y in 0 until bh) if (dist(px[y * bw + c], bg) > 60 && ++n >= 2) return true; return false }
        var c = bw - 1
        if (!ink(c)) return x
        while (c >= 0 && ink(c)) c--                     // through the letter to its left edge
        if (c < 0) return null
        val right = c
        while (c >= 0 && !ink(c)) c--                    // through the gap to the marker's ink
        if (right - c < 2) return null
        val k = src.width / vw.toFloat()
        return ((s[0] + (c + 1 + right) / 2f) / k).toInt()
    }

    /**
     * The first ink beside a line that is not its own: from [from] towards [to] (view x, either way) in the middle rows
     * of [top]..[bottom], the first column of ink that comes after a clear gap of at least [gap] view px (ink nearer
     * than that is the line's own last glyph, which the recognizer's box can stop short of). [ground]: the colour
     * around the text. Null when the row is clear up to [to].
     */
    fun inkBeside(src: Bitmap, vw: Int, vh: Int, top: Int, bottom: Int, from: Int, to: Int, ground: Int, gap: Int): Int? {
        if (abs(to - from) < 2) return null
        val dir = if (to > from) 1 else -1
        val s = scaled(src, vw, vh, intArrayOf(minOf(from, to), top, maxOf(from, to), bottom))
        val bw = s[2] - s[0]; val bh = s[3] - s[1]
        if (bw < 2 || bh < 5 || bw.toLong() * bh > 2_000_000) return null
        val px = IntArray(bw * bh); src.getPixels(px, 0, bw, s[0], s[1], bw, bh)
        val kx = src.width / vw.toFloat()
        val g = maxOf(1, (gap * kx).toInt())
        var run = 0
        for (k in 0 until bw) {
            val x = if (dir > 0) k else bw - 1 - k
            var n = 0; var y = bh / 5
            while (y < bh - bh / 5) { if (dist(px[y * bw + x], ground) > 110 && ++n >= 2) break; y++ }
            if (n >= 2) { if (run >= g) return ((s[0] + x + (if (dir > 0) 0 else 1)) / kx).toInt(); run = 0 } else run++
        }
        return null
    }

    /** The first column of ink right of [from] in the rows of [box] (view coordinates), or null. */
    fun inkLeft(src: Bitmap, vw: Int, vh: Int, box: Rect, from: Int): Int? {
        val s = scaled(src, vw, vh, intArrayOf(from + 1, box.top, box.right, box.bottom))
        val bw = s[2] - s[0]; val bh = s[3] - s[1]
        if (bw < 4 || bh < 4) return null
        val px = IntArray(bw * bh); src.getPixels(px, 0, bw, s[0], s[1], bw, bh)
        val bg = commonest(px)
        for (x in 0 until bw) { var c = 0; for (y in 0 until bh) if (dist(px[y * bw + x], bg) > 120) c++; if (c >= 2) return ((s[0] + x) / (src.width / vw.toFloat())).toInt() }
        return null
    }

    /**
     * The app window's page and bars ([TrLayout.area]) in view coordinates ([ox], [oy] = where the view sits on the
     * screen), or null when no window can be read. [top0]/[bottom0]: the status bar's bottom and the navigation
     * strip's top.
     */
    fun area(svc: AccessibilityService, sw: Int, sh: Int, ox: Int, oy: Int, top0: Int, bottom0: Int): TrLayout.Area? = runCatching {
        val t0 = android.os.SystemClock.uptimeMillis()
        val wins = svc.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.sortedByDescending { it.layer }
        var nodes: List<TrLayout.N> = emptyList()
        for (w in wins) {
            val root = w.root ?: continue
            val list = ArrayList<TrLayout.N>()
            collect(root, 0, list, ox, oy, sw.toLong() * sh / 5)
            if (list.size >= 3) { nodes = list; break }
        }
        if (nodes.isEmpty()) return@runCatching null
        val a = TrLayout.area(nodes, sw, sh, top0, bottom0)
        Dbg.log("page area: ${nodes.size} nodes read in ${android.os.SystemClock.uptimeMillis() - t0} ms, page ${a.page.joinToString(",")}" +
            (a.list?.let { ", list ${it.joinToString(",")}" } ?: ", no list") + ", ${a.fields.size} field(s)")
        a
    }.getOrElse { Dbg.log("page area: $it"); null }

    private fun navHeight(svc: AccessibilityService): Int {
        val id = svc.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id != 0) svc.resources.getDimensionPixelSize(id) else 0
    }

    /** No page known (a frozen picture of our own overlay, or nothing could be read): the whole screen. */
    val NONE = TrLayout.Area(intArrayOf(0, 0, 0, 0), null, emptyList())

    /** The page of the app in front, in screen coordinates, as it is now (before the overlay covers it). Any thread. */
    fun screenArea(svc: AccessibilityService, statusBar: Int): TrLayout.Area? {
        val b = svc.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        return area(svc, b.width(), b.height(), 0, 0, statusBar, b.height() - navHeight(svc))
    }

    /** Drawing bounds for each page-text block in view coordinates. A frozen shot can supply its saved page. */
    fun clips(svc: AccessibilityService, xy: IntArray, vw: Int, vh: Int, blocks: List<Block>, statusBar: Int, shot: TrLayout.Area? = null): Map<Block, Rect> {
        val a = when {
            shot === NONE -> null
            shot != null -> if (xy[0] == 0 && xy[1] == 0) shot else TrLayout.Area(off(shot.page, xy), shot.list?.let { off(it, xy) }, shot.fields.map { off(it, xy) })
            else -> area(svc, vw, vh, xy[0], xy[1], statusBar, vh - navHeight(svc))
        }
        val out = LinkedHashMap<Block, Rect>()
        var dropped = 0
        for (b in blocks) {
            val c = if (a == null) intArrayOf(0, 0, vw, vh) else TrLayout.clipFor(a, ia(b.box), vw, vh)
            if (c == null) dropped++ else out[b] = rect(c)
        }
        if (dropped > 0) Dbg.log("$dropped block(s) under the app's bars or in its fields left alone")
        return out
    }

    private fun off(r: IntArray, xy: IntArray) = intArrayOf(r[0] - xy[0], r[1] - xy[1], r[2] - xy[0], r[3] - xy[1])

    private fun collect(n: AccessibilityNodeInfo, depth: Int, out: ArrayList<TrLayout.N>, ox: Int, oy: Int, big: Long) {
        if (out.size >= 1500 || depth > 60 || !n.isVisibleToUser) return
        val r = Rect(); n.getBoundsInScreen(r); r.offset(-ox, -oy)
        out.add(TrLayout.N(ia(r), depth, n.isScrollable, n.isEditable))
        // The inside of a big scrolling list (most of a chat's nodes, each child one call into the app) says nothing
        // about where the page ends: only the list itself and what is drawn after it (its bars) are needed.
        if (n.isScrollable && r.width().toLong() * r.height() >= big) return
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, depth + 1, out, ox, oy, big) }
    }

    // ---------------- read from the picture ----------------

    /** A chat bubble can differ from the page by only a few colour levels. */
    private const val EDGE = 12

    private fun dist(c: Int, bg: Int) = abs((c shr 16 and 255) - (bg shr 16 and 255)) + abs((c shr 8 and 255) - (bg shr 8 and 255)) + abs((c and 255) - (bg and 255))

    /**
     * The plain area of colour [bg] the block stands in (a chat bubble, a card, the page), in view coordinates: from
     * the block's edges outwards along the empty rows above, between and below its lines, and down from its last line.
     */
    fun container(src: Bitmap, vw: Int, vh: Int, b: Block, bg: Int, d: Float): Rect {
        val kx = src.width / vw.toFloat(); val ky = src.height / vh.toFloat()
        val g = (2 * d).toInt().coerceAtLeast(2)
        val rows = ArrayList<Int>()
        rows.add(b.lines.first().box.top - g); rows.add(b.lines.last().box.bottom + g)
        for (i in 1 until b.lines.size) { val a = b.lines[i - 1].box.bottom; val c = b.lines[i].box.top; if (c - a >= 3) rows.add((a + c) / 2) }
        val row = IntArray(src.width)
        fun walk(yv: Int, xv: Int, dir: Int): Int {
            val y = (yv * ky).toInt()
            if (y !in 0 until src.height) return if (dir > 0) vw else 0
            src.getPixels(row, 0, src.width, 0, y, src.width, 1)
            var x = (xv * kx).toInt().coerceIn(0, src.width - 1)
            var off = 0
            while (x + dir in 0 until src.width) {
                x += dir
                if (dist(row[x], bg) > EDGE) { if (++off >= 2) { x -= dir * 2; break } } else off = 0
            }
            return (x / kx).toInt()
        }
        fun med(l: List<Int>) = l.sorted()[l.size / 2]
        val right = med(rows.map { walk(it, b.box.right + g, 1) }).coerceAtLeast(b.box.right)
        val left = med(rows.map { walk(it, b.box.left - g, -1) }).coerceAtMost(b.box.left)
        var bottom = b.box.bottom
        val col = IntArray(src.height)
        for (xv in listOf(b.box.left + g, b.box.centerX(), b.box.right - g)) {
            val x = (xv * kx).toInt().coerceIn(0, src.width - 1)
            src.getPixels(col, 0, 1, x, 0, 1, src.height)
            var y = ((b.box.bottom + g) * ky).toInt().coerceIn(0, src.height - 1)
            var off = 0
            while (y + 1 < src.height) { y++; if (dist(col[y], bg) > EDGE) { if (++off >= 2) { y -= 2; break } } else off = 0 }
            bottom = maxOf(bottom, (y / ky).toInt())
        }
        return Rect(left, b.box.top, right, bottom)
    }

    /** The row the letters of [box] stand on (view coordinates), from the ink of each row; null if unclear. */
    fun baseline(src: Bitmap, vw: Int, vh: Int, box: Rect, bg: Int): Int? {
        val kx = src.width / vw.toFloat(); val ky = src.height / vh.toFloat()
        val l = (box.left * kx).toInt().coerceIn(0, src.width - 1); val r = (box.right * kx).toInt().coerceIn(l + 1, src.width)
        val t = (box.top * ky).toInt().coerceIn(0, src.height - 1); val bt = (box.bottom * ky).toInt().coerceIn(t + 1, src.height)
        val bw = r - l; val bh = bt - t
        if (bw < 6 || bh < 6) return null
        val px = IntArray(bw * bh)
        src.getPixels(px, 0, bw, l, t, bw, bh)
        val n = IntArray(bh)
        for (y in 0 until bh) for (x in 0 until bw) if (dist(px[y * bw + x], bg) > 100) n[y]++
        val top = n.maxOrNull() ?: 0
        if (top < 3) return null
        var last = -1
        for (y in 0 until bh) if (n[y] >= top * 0.35f) last = y
        return if (last < 0) null else ((t + last + 1) / ky).toInt()
    }

    /** Each word of [b] (its recognized parts) with whether it is bold next to the rest of the block, and its colour if not the block's. */
    class StyledWord(val text: String, val bold: Boolean, val color: Int?)

    fun styleWords(src: Bitmap, vw: Int, vh: Int, b: Block, bg: Int, fg: Int): List<StyledWord>? {
        val kx = src.width / vw.toFloat(); val ky = src.height / vh.toFloat()
        val parts = b.lines.flatMap { it.parts }
        if (parts.size < 3) return null
        val dfg = dist(fg, bg)
        if (dfg < 80) return null
        val stems = ArrayList<Float?>(); val cols = ArrayList<Int?>()
        for (p in parts) {
            val l = (p.box.left * kx).toInt().coerceIn(0, src.width - 1); val r = (p.box.right * kx).toInt().coerceIn(l + 1, src.width)
            val t = (p.box.top * ky).toInt().coerceIn(0, src.height - 1); val bt = (p.box.bottom * ky).toInt().coerceIn(t + 1, src.height)
            val bw = r - l; val bh = bt - t
            if (bw < 4 || bh < 6 || bw * bh > 400_000) { stems.add(null); cols.add(null); continue }
            val px = IntArray(bw * bh)
            src.getPixels(px, 0, bw, l, t, bw, bh)
            val mask = BooleanArray(px.size) { dist(px[it], bg) * 2 >= dfg }
            stems.add(Look.measure(mask, bw, bh)?.stem)
            var sr = 0L; var sg = 0L; var sb = 0L; var cnt = 0
            for (c in px) if (dist(c, bg) * 10 >= dfg * 7) { sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255; cnt++ }
            cols.add(if (cnt < 6) null else android.graphics.Color.rgb((sr / cnt).toInt(), (sg / cnt).toInt(), (sb / cnt).toInt()))
        }
        val known = stems.filterNotNull().sorted()
        if (known.size < 3) return null
        val med = known[known.size / 2]
        var prevBold = false
        return parts.mapIndexed { i, p ->
            val s = stems[i]
            val bold = if (s == null) prevBold else s >= med * 1.3f
            prevBold = bold
            // a link's colour: clearly another hue, not a grey
            val c = cols[i]?.takeIf { dist(it, fg) > 90 && dist(it, bg) > 90 && maxOf(it shr 16 and 255, it shr 8 and 255, it and 255) - minOf(it shr 16 and 255, it shr 8 and 255, it and 255) >= 48 }
            StyledWord(p.text, bold, c)
        }.let { ws -> if (ws.count { it.bold } * 2 > ws.size) ws.map { StyledWord(it.text, false, it.color) } else ws }
    }
}
