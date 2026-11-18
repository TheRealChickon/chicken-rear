package dev.kb896.aiusage.circle

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Everything about selecting that is pure geometry and text: which word a finger is on, what a stroke means,
 * what text a selection stands for, and the zoom arithmetic. No Android classes, so it runs in plain unit tests
 * (`./gradlew testDebugUnitTest`). All coordinates are in the screenshot's own space ("content"), never in
 * zoomed screen pixels.
 */
object SelLogic {
    /** A word: its own box (l,t,r,b), the top and bottom of the line it sits on (lt, lb), line and block number. */
    class W(val text: String, val l: Int, val t: Int, val r: Int, val b: Int, val lt: Int, val lb: Int, val line: Int, val block: Int) {
        val cx get() = (l + r) / 2f
        val cy get() = (lt + lb) / 2f
        val h get() = lb - lt
    }

    class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w get() = r - l
        val h get() = b - t
    }

    /** What a finished stroke means. */
    sealed class Pick {
        object None : Pick()
        /** Words a..b; [around] is set when a loop was drawn that could also have meant the picture inside it. */
        class Text(val a: Int, val b: Int, val around: Box? = null) : Pick()
        class Image(val box: Box) : Pick()
    }

    /**
     * OCR reads round icons as letters: a magnifier becomes "Q", a circle "O" or "0". Such a single character at
     * the start of a line (or alone on it) is an icon and is never selected as text.
     */
    fun iconish(ws: List<W>, i: Int): Boolean {
        val w = ws[i]
        if (w.text.length != 1 || w.text[0] !in "QOo0CGcD@©°•") return false
        // Icons sit in front of their label ("Q Search"); a letter at the end of a line is text ("Vitamin C").
        return ws.getOrNull(i - 1)?.takeIf { it.line == w.line } == null
    }

    private fun gap(lo: Float, hi: Float, v: Float) = max(0f, max(lo - v, v - hi))

    /**
     * The word under the point, or -1. A word counts over the whole height of its line (glyph boxes of short
     * letters are much lower than the line), plus [pad] around it. Of several candidates the closest wins, so in
     * small, tight text the neighbour's padding never steals the tap.
     */
    fun wordAt(ws: List<W>, x: Float, y: Float, pad: Float, skipIcons: Boolean = true): Int {
        var best = -1; var bd = Float.MAX_VALUE
        for ((i, w) in ws.withIndex()) {
            val dx = gap(w.l.toFloat(), w.r.toFloat(), x); val dy = gap(w.lt.toFloat(), w.lb.toFloat(), y)
            if (dx > pad || dy > pad) continue
            if (skipIcons && iconish(ws, i)) continue
            val dd = dx * dx + dy * dy
            if (dd < bd) { bd = dd; best = i }
        }
        return best
    }

    /** The word closest to the point, lines counting more than columns (for dragging a handle or a selection). */
    fun nearestWord(ws: List<W>, x: Float, y: Float): Int {
        var best = -1; var bd = Float.MAX_VALUE
        for ((i, w) in ws.withIndex()) {
            val dx = gap(w.l.toFloat(), w.r.toFloat(), x); val dy = gap(w.lt.toFloat(), w.lb.toFloat(), y) * 3
            val dd = dx * dx + dy * dy
            if (dd < bd) { bd = dd; best = i }
        }
        return best
    }

    /**
     * Decides what a stroke means. [pts] is x0,y0,x1,y1,... in content space; [finger] is the size of a fingertip
     * margin in content pixels (it shrinks when the picture is zoomed in).
     *  - nothing readable under it                  -> picture search of the stroke's box
     *  - an open stroke that starts and ends on text -> the text from the first to the last of those two words
     *    (dragging across words and lines, as on a desktop), also when it runs just under the line
     *  - a tight scribble back and forth            -> the words under it
     *  - a closed loop or box around text           -> the text, and the picture is offered as well
     *  - a large area in which text is under 5 %    -> the picture
     */
    fun classify(ws: List<W>, pts: FloatArray, finger: Float): Pick {
        val n = pts.size / 2
        if (n < 2) return Pick.None
        var l = pts[0]; var t = pts[1]; var r = l; var b = t; var len = 0f
        for (k in 1 until n) {
            val x = pts[2 * k]; val y = pts[2 * k + 1]
            l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            len += hypot(x - pts[2 * k - 2], y - pts[2 * k - 1])
        }
        val bx = Box(l, t, r, b)
        val gapEnds = hypot(pts[0] - pts[2 * n - 2], pts[1] - pts[2 * n - 1])
        val hit = HashSet<Int>()
        for (k in 0 until n) { val i = wordAt(ws, pts[2 * k], pts[2 * k + 1], finger * 0.3f); if (i >= 0) hit.add(i) }
        val perimeter = 2 * (bx.w + bx.h)
        val diag = hypot(bx.w, bx.h)
        val scribble = len > perimeter * 1.7f
        val closed = gapEnds < diag * 0.35f && len > perimeter * 0.55f
        // What a loop encloses is judged by the loop itself, not by the rectangle around it: a word belongs to it
        // when most of the word lies inside the drawn line. (The rectangle took in words in its corners that a
        // round loop never went around, and dropped words the loop cut through the middle of.)
        // Only a line that really goes around something is a loop: a stroke along a line and back again also ends
        // where it began, but encloses next to nothing.
        var lasso = closed && !scribble && abs(polygonArea(pts)) >= bx.w * bx.h * 0.35f
        val inside = ArrayList<Int>(); var area = 0f
        fun collect(byLoop: Boolean) {
            inside.clear(); area = 0f
            for ((i, w) in ws.withIndex()) {
                if (iconish(ws, i)) continue
                val isIn = if (byLoop) coveredBy(pts, w) >= 0.5f else w.cx >= l && w.cx <= r && w.cy >= t && w.cy <= b
                if (isIn) { inside.add(i); area += (w.r - w.l) * (w.b - w.t).toFloat() }
            }
        }
        collect(lasso)
        // a loop that holds no whole word (drawn very tight, or tiny) is read by its box and by what it touches
        if (lasso && inside.none { ws[it].text.any { c -> c.isLetterOrDigit() } }) { lasso = false; collect(false) }
        val all = (inside + (if (lasso) emptyList() else hit)).distinct().filter { ws[it].text.any { c -> c.isLetterOrDigit() } }
        if (all.isEmpty()) return Pick.Image(bx)

        val medH = all.map { ws[it].h }.sorted()[all.size / 2].toFloat()
        val lo = all.min(); val hi = all.max()
        val big = bx.h > medH * 3.2f && bx.w > medH * 3.2f
        val coverage = if (bx.w * bx.h > 0f) area / (bx.w * bx.h) else 1f
        if (big && coverage < 0.05f) return Pick.Image(bx)
        if (!scribble && closed && big) return if (inside.isEmpty()) Pick.Image(bx) else Pick.Text(lo, hi, bx)
        if (!scribble && !closed) {
            val reach = max(finger * 0.3f, medH * 0.8f)
            val s = wordAt(ws, pts[0], pts[1], reach); val e = wordAt(ws, pts[2 * n - 2], pts[2 * n - 1], reach)
            if (s >= 0 && e >= 0) return Pick.Text(min(s, e), max(s, e))
            if (hit.isNotEmpty()) return Pick.Text(hit.min(), hit.max())
        }
        if (!lasso && hit.isNotEmpty() && bx.h < medH * 1.8f) return Pick.Text(hit.min(), hit.max())
        // A scribble marks what it runs over, not everything in the rectangle around it (in small text that
        // rectangle reaches lines the finger never touched).
        if (scribble && hit.isNotEmpty()) return Pick.Text(hit.min(), hit.max())
        return Pick.Text(lo, hi)
    }

    /**
     * Is the point inside the closed line through [pts] (x0,y0,x1,y1,...)? Counted by winding, so a loop drawn
     * twice around, or overlapping itself at the end, still has an inside.
     */
    fun inPolygon(pts: FloatArray, x: Float, y: Float): Boolean {
        val n = pts.size / 2
        if (n < 3) return false
        var wn = 0
        var j = n - 1
        for (i in 0 until n) {
            val xi = pts[2 * i]; val yi = pts[2 * i + 1]; val xj = pts[2 * j]; val yj = pts[2 * j + 1]
            // edge j -> i
            val side = (xi - xj) * (y - yj) - (x - xj) * (yi - yj)
            if (yj <= y) { if (yi > y && side > 0f) wn++ } else if (yi <= y && side < 0f) wn--
            j = i
        }
        return wn != 0
    }

    /** Signed area enclosed by the closed line through [pts]. */
    fun polygonArea(pts: FloatArray): Float {
        val n = pts.size / 2
        var a = 0.0
        var j = n - 1
        for (i in 0 until n) { a += (pts[2 * j] * pts[2 * i + 1] - pts[2 * i] * pts[2 * j + 1]).toDouble(); j = i }
        return (a / 2).toFloat()
    }

    /** The share of a word's own box (nine sample points) that lies inside the closed line through [pts]. */
    fun coveredBy(pts: FloatArray, w: W): Float {
        var inN = 0
        for (fx in floatArrayOf(0.15f, 0.5f, 0.85f)) for (fy in floatArrayOf(0.25f, 0.5f, 0.75f))
            if (inPolygon(pts, w.l + (w.r - w.l) * fx, w.t + (w.b - w.t) * fy)) inN++
        return inN / 9f
    }

    class StrokeLook(val coreWidth: Float, val rimWidth: Float, val haloHalf: FloatArray, val haloBlur: FloatArray, val haloAlpha: IntArray, val oldCoreAlpha: Int, val recentMs: Int)

    const val STROKE_CORE_DP = 5.6f

    fun strokeLook(d: Float) = StrokeLook(STROKE_CORE_DP * d, (STROKE_CORE_DP + 1.7f) * d,
        floatArrayOf(44.9f * d, 27.6f * d), floatArrayOf(8.1f * d, 10.6f * d), intArrayOf(33, 123), 215, 600)

    private fun phi(z: Double): Double {                      // normal distribution function (Abramowitz-Stegun 26.2.17)
        val t = 1.0 / (1.0 + 0.2316419 * abs(z))
        val y = 1.0 - 0.3989422804 * kotlin.math.exp(-z * z / 2) * t * (0.31938153 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        return if (z >= 0) y else 1 - y
    }

    /** Strength of the halo at a distance (pixels) from the centre line of a long straight stroke, 0..1. */
    fun haloAt(look: StrokeLook, dist: Float): Float {
        var through = 1.0
        for (k in look.haloHalf.indices) {
            val sigma = look.haloBlur[k] * 0.57735 + 0.5        // how Android turns a blur radius into a sigma
            val cov = phi((look.haloHalf[k] - dist) / sigma) - phi((-look.haloHalf[k] - dist) / sigma)
            through *= 1.0 - look.haloAlpha[k] / 255.0 * cov
        }
        return (1.0 - through).toFloat()
    }

    /** The core's width on screen. The stroke is painted in screen pixels, so the zoom must not enter into it. */
    fun strokeCoreOnScreen(d: Float, @Suppress("UNUSED_PARAMETER") zoom: Float): Float = strokeLook(d).coreWidth

    /** Content position to screen position under a zoom (the one mapping the view and the stroke capture share). */
    fun toScreen(c: Float, zoom: Float, offset: Float) = c * zoom + offset

    /** The words that lie inside a box (for "Select text" on a picture region), as a first..last pair or null. */
    fun wordsIn(ws: List<W>, box: Box): Pair<Int, Int>? {
        val inside = ws.indices.filter { i -> val w = ws[i]; !iconish(ws, i) && w.cx >= box.l && w.cx <= box.r && w.cy >= box.t && w.cy <= box.b && w.text.any { it.isLetterOrDigit() } }
        return if (inside.isEmpty()) null else inside.min() to inside.max()
    }

    private fun cjk(c: Char): Boolean {
        val s = Character.UnicodeScript.of(c.code)
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL
    }

    /** The selection exactly as it reads on screen (what "Copy" puts on the clipboard). */
    fun text(ws: List<W>, a: Int, b: Int): String {
        if (a < 0 || b < a || b >= ws.size) return ""
        val sb = StringBuilder()
        for (i in a..b) {
            val w = ws[i]
            if (i > a) {
                val p = ws[i - 1]
                if (p.block != w.block) sb.append('\n')
                else if (!(sb.isNotEmpty() && cjk(sb.last()) && w.text.isNotEmpty() && cjk(w.text.first()))) sb.append(' ')
            }
            sb.append(w.text)
        }
        return sb.toString()
    }

    /**
     * The selection as a search query: a word broken at a line end with a hyphen is put together again, and
     * punctuation around the whole selection is dropped ("Himmel," searches for Himmel).
     */
    fun query(ws: List<W>, a: Int, b: Int): String {
        if (a < 0 || b < a || b >= ws.size) return ""
        val sb = StringBuilder()
        for (i in a..b) {
            val w = ws[i]
            if (i > a) {
                val p = ws[i - 1]
                val broken = p.line != w.line && p.block == w.block && p.text.length > 2 && p.text.endsWith("-") &&
                    p.text[p.text.length - 2].isLetter() && w.text.isNotEmpty() && w.text[0].isLowerCase()
                if (broken) sb.setLength(sb.length - 1)
                else if (!(sb.isNotEmpty() && cjk(sb.last()) && w.text.isNotEmpty() && cjk(w.text.first()))) sb.append(' ')
            }
            sb.append(w.text)
        }
        val s = sb.toString().trim { !it.isLetterOrDigit() }
        return if (s.isEmpty()) sb.toString().trim() else s
    }

    /**
     * Reading order for OCR blocks given as (left, top, right, bottom): top to bottom, and blocks that sit on the
     * same row left to right. (Sorting by the top edge alone put a right-hand block first whenever it began a
     * pixel higher, which made selections jump across the screen.) Returns the indices in order.
     */
    fun readingOrder(boxes: List<IntArray>): List<Int> {
        val idx = boxes.indices.sortedBy { boxes[it][1] }.toMutableList()
        fun sameRow(a: IntArray, b: IntArray): Boolean {
            val ov = min(a[3], b[3]) - max(a[1], b[1])
            return ov > min(a[3] - a[1], b[3] - b[1]) * 0.5f
        }
        var changed = true; var guard = 0
        while (changed && guard++ < boxes.size + 2) {
            changed = false
            for (k in 0 until idx.size - 1) {
                val a = boxes[idx[k]]; val b = boxes[idx[k + 1]]
                if (sameRow(a, b) && b[0] < a[0]) { val tmp = idx[k]; idx[k] = idx[k + 1]; idx[k + 1] = tmp; changed = true }
            }
        }
        return idx
    }

    /** Lines lower than this (screenshot pixels) are read again from an enlarged crop when they are selected. */
    const val TINY_LINE = 18

    /**
     * Very small text (lines under about 16 px) is where the recognizer makes mistakes; read again from an enlarged
     * crop it gets more of it right . [sel] are the
     * selected words of the first reading, [again] everything the second reading found in the crop. Returns the
     * second reading of the selected places in reading order, or null when it does not plausibly cover them.
     */
    fun reread(sel: List<W>, again: List<W>): String? {
        if (sel.isEmpty() || again.isEmpty()) return null
        val picked = again.filter { w ->
            sel.any { s0 -> val padX = (s0.r - s0.l) * 0.15f + 2f; w.cx >= s0.l - padX && w.cx <= s0.r + padX && w.cy >= s0.lt - 2f && w.cy <= s0.lb + 2f }
        }
        if (picked.isEmpty()) return null
        // reading order: by the selected line each word sits on, then left to right
        fun lineOf(w: W) = sel.filter { w.cy >= it.lt - 2f && w.cy <= it.lb + 2f }.minByOrNull { abs(it.cx - w.cx) }?.line ?: Int.MAX_VALUE
        val ordered = picked.sortedWith(compareBy({ lineOf(it) }, { it.l }))
        val text = ordered.joinToString(" ") { it.text }.trim()
        val n0 = sel.sumOf { it.text.count { c -> c.isLetterOrDigit() } }
        val n1 = text.count { it.isLetterOrDigit() }
        // a second reading that lost or invented a lot is not trusted
        if (n1 == 0 || n1 * 10 < n0 * 6 || n1 * 10 > n0 * 16) return null
        return text
    }

    /** Punctuation around a query is dropped ("Himmel," searches for Himmel). */
    fun cleanQuery(t: String): String { val s = t.trim { !it.isLetterOrDigit() }; return if (s.isEmpty()) t.trim() else s }

    // ---------- touches that begin at the left or right edge ----------
    /** A touch within this distance of the left or right edge waits before it may become a mark (HyperOS's own back strip is the first 21 dp of it). */
    const val EDGE_ZONE_DP = 40f
    /** Resting this long without moving makes it a mark (a press on a word, the start of a slow stroke). */
    const val EDGE_HOLD_MS = 220L
    enum class Edge { WAIT, BACK, MARK }

    /**
     * What an edge touch has become after moving [inward] dp towards the middle of the screen and [down] dp up or
     * down (sign does not matter). Mostly sideways and inward is back, always; mostly up or down, or outward, is a mark.
     */
    fun edgeVerdict(inward: Float, down: Float): Edge {
        val v = kotlin.math.abs(down)
        if (inward >= 8f && inward >= v) return Edge.BACK
        if (v >= 12f && v > inward) return Edge.MARK
        if (inward <= -8f) return Edge.MARK
        return Edge.WAIT
    }

    /** A back swipe counts when the finger went 32 dp inward, or was still moving inward fast when it lifted. */
    fun edgeBackCommits(inward: Float, speed: Float) = inward >= 32f || (inward >= 12f && speed >= 400f)

    // ---------- where a released results sheet goes ----------
    /**
     * [pos] = how far the sheet's top is below full height when the finger lifts, [v] its speed downwards (px/s),
     * [peek] = the lowest place it may rest, [off] = fully off the screen. Returns where it comes to rest, or null:
     * it goes away. It stays where it is let go (carried on by 0.17 s of its speed); in the top sixth it snaps to
     * full height; in the bottom sixth, or below, it goes away without stopping.
     */
    fun sheetRest(pos: Float, v: Float, peek: Float, off: Float): Float? {
        val zone = peek * 0.17f
        val proj = pos + v * 0.17f
        if (proj >= peek - zone) return null
        return if (proj < zone) 0f else proj
    }

    // ---------- back ----------
    enum class Back { NONE, LEAVE_BAR, CLOSE_CARD, PAGE_BACK, PREVIOUS_SEARCH, PREVIOUS_LEVEL, CLOSE }

    /**
     * What one back gesture does. It never does more than one of these, and it never reaches the app underneath:
     *  - while the overlay is still being prepared, or is already closing: nothing (the gesture is swallowed)
     *  - the search bar is being edited: leave the bar (the keyboard goes)
     *  - a translation card is open: close the card
     *  - a link was followed inside the results: one page back
     *  - more than one search was made on this level: the search before this one
     *  - a search was stacked on top of another (hold on the results): back to the level below
     *  - otherwise: close Circle Search; the app that was in front is still there
     */
    fun backStep(preparing: Boolean, closing: Boolean, translating: Boolean, editing: Boolean, card: Boolean, pageCanGoBack: Boolean, searches: Int, levels: Int): Back = when {
        preparing || closing -> Back.NONE
        translating -> Back.CLOSE
        editing -> Back.LEAVE_BAR
        card -> Back.CLOSE_CARD
        pageCanGoBack -> Back.PAGE_BACK
        searches > 1 -> Back.PREVIOUS_SEARCH
        levels > 1 -> Back.PREVIOUS_LEVEL
        else -> Back.CLOSE
    }

    // ---------- the search bar ----------
    private fun enc(q: String) = java.net.URLEncoder.encode(q, "UTF-8")

    /** Google reads about 32 words of a query; a whole paragraph only makes the request look odd. */
    fun limitWords(q: String): String {
        val words = q.trim().split(Regex("\\s+"))
        return if (words.size > 32) words.take(32).joinToString(" ") else q.trim()
    }

    /** The results page for a typed or selected text; an empty bar gives Google's start page. */
    fun searchUrl(q: String, lang: String): String =
        if (q.isBlank()) "https://www.google.com/?hl=$lang" else "https://www.google.com/search?q=" + enc(limitWords(q)) + "&hl=$lang"

    /**
     * Text typed into the bar while a picture is being searched is added to that picture's results page as its
     * `q` parameter (picture plus words); an empty text takes the words away again. Everything else in the
     * address stays as Google returned it.
     */
    fun withQuery(url: String, q: String): String {
        val hash = url.indexOf('#').let { if (it >= 0) url.substring(it) else "" }
        val noHash = if (hash.isEmpty()) url else url.substring(0, url.length - hash.length)
        val qm = noHash.indexOf('?')
        val base = if (qm >= 0) noHash.substring(0, qm) else noHash
        val params = if (qm >= 0) noHash.substring(qm + 1).split('&').filter { it.isNotEmpty() && !it.startsWith("q=") && it != "q" } else emptyList()
        val all = if (q.isBlank()) params else params + ("q=" + enc(limitWords(q)))
        return base + (if (all.isEmpty()) "" else "?" + all.joinToString("&")) + hash
    }

    // ---------- zoom ----------
    const val ZOOM_MAX = 5f

    /** While pinching, the zoom may go a little past its limits, with growing resistance. */
    fun rubberZoom(z: Float): Float = when {
        z < 1f -> 1f - (1f - z) * 0.45f
        z > ZOOM_MAX -> ZOOM_MAX + (z - ZOOM_MAX) * 0.25f
        else -> z
    }

    /** The raw pinch value that [rubberZoom] turns into z. */
    fun unrubberZoom(z: Float): Float = when {
        z < 1f -> 1f - (1f - z) / 0.45f
        z > ZOOM_MAX -> ZOOM_MAX + (z - ZOOM_MAX) / 0.25f
        else -> z
    }

    /** New offset so that the content point under the focus (fx, fy) stays under it when zoom goes z0 -> z1. */
    fun zoomAt(z0: Float, ox: Float, oy: Float, fx: Float, fy: Float, z1: Float): FloatArray {
        val cx = (fx - ox) / z0; val cy = (fy - oy) / z0
        return floatArrayOf(fx - cx * z1, fy - cy * z1)
    }

    /** The offset limited so the zoomed picture always covers the view (w x h). */
    fun clampOffset(z: Float, ox: Float, oy: Float, w: Float, h: Float): FloatArray {
        if (z <= 1f) return floatArrayOf(0f, 0f)
        return floatArrayOf(ox.coerceIn(w - w * z, 0f), oy.coerceIn(h - h * z, 0f))
    }

    /** Where a zoom settles when the fingers lift: back to fit below 1.08, never above the maximum. */
    fun settleZoom(z: Float): Float = if (z < 1.08f) 1f else min(z, ZOOM_MAX)

    /**
     * A picture region being adjusted: [mode] 0 moves it, otherwise a bit mask of the borders that follow the
     * finger (1 left, 2 top, 4 right, 8 bottom). It stays inside the picture and never gets smaller than [minSize].
     */
    fun adjust(box: Box, mode: Int, dx: Float, dy: Float, w: Float, h: Float, minSize: Float): Box {
        if (mode == 0) {
            val nx = (box.l + dx).coerceIn(0f, max(0f, w - box.w)); val ny = (box.t + dy).coerceIn(0f, max(0f, h - box.h))
            return Box(nx, ny, nx + box.w, ny + box.h)
        }
        var l = box.l; var t = box.t; var r = box.r; var b = box.b
        // a region smaller than the minimum (made at a higher zoom, or squeezed against an edge) keeps its own size as the minimum
        val mw = min(minSize, min(box.w, w)).coerceAtLeast(0f); val mh = min(minSize, min(box.h, h)).coerceAtLeast(0f)
        if (mode and 1 != 0) l = (l + dx).coerceIn(0f, max(0f, r - mw))
        if (mode and 4 != 0) r = (r + dx).coerceIn(min(l + mw, w), max(w, min(l + mw, w)))
        if (mode and 2 != 0) t = (t + dy).coerceIn(0f, max(0f, b - mh))
        if (mode and 8 != 0) b = (b + dy).coerceIn(min(t + mh, h), max(h, min(t + mh, h)))
        return Box(l, t, r, b)
    }

    /** Which part of a region a touch grabs: -1 nothing, 0 the inside (move), else the border mask of [adjust]. */
    fun grab(box: Box, x: Float, y: Float, reach: Float): Int {
        if (x < box.l - reach || x > box.r + reach || y < box.t - reach || y > box.b + reach) return -1
        var m = 0
        val edgeX = min(reach, box.w / 3); val edgeY = min(reach, box.h / 3)
        if (abs(x - box.l) <= edgeX || x < box.l) m = m or 1 else if (abs(x - box.r) <= edgeX || x > box.r) m = m or 4
        if (abs(y - box.t) <= edgeY || y < box.t) m = m or 2 else if (abs(y - box.b) <= edgeY || y > box.b) m = m or 8
        return m
    }
}
