package dev.kb896.aiusage.circle

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where translated text may go, as pure geometry (no Android classes; tested in TrLayoutTest). Boxes are
 * IntArray(left, top, right, bottom) in view pixels.
 *
 * The layout preserves lists, paragraph gaps and inline emphasis:
 *  - a bulleted list read as one block came out as two lines with two bullets each: every list item, and every
 *    paragraph after a gap, is its own block ([split]); the bullet itself is kept as drawn, not translated;
 *  - icons read as letters (a clock icon became "9") are not text ([isIcon]);
 *  - bold words inside a paragraph were lost ([boldRanges]);
 *  - text under the app's own bars and a field's hint were translated and drawn over the input bar ([area]).
 */
object TrLayout {
    class P(val text: String, val box: IntArray, val conf: Float = 1f)
    class L(val text: String, val box: IntArray, val parts: List<P> = emptyList())
    /** One block to translate: its lines (markers and icons taken out) and the columns its patch may not paint over. */
    /**
     * [split]: this item was cut off from the lines before it in its recognized block (a marker, a gap, a colour).
     * [xh]: the x-height of its letters from the picture (0 = not known).
     */
    class Item(val lines: List<L>, val keepL: Int = Int.MIN_VALUE, val keepR: Int = Int.MAX_VALUE, val split: Boolean = false, val xh: Int = 0,
               val firstInk: Int? = null, val lastInk: Int? = null) {
        val text: String get() = lines.fold("") { a, l -> glue(a, l.text) }
        val box: IntArray get() = intArrayOf(lines.minOf { it.box[0] }, lines.minOf { it.box[1] }, lines.maxOf { it.box[2] }, lines.maxOf { it.box[3] })
    }

    private fun w(b: IntArray) = b[2] - b[0]
    private fun h(b: IntArray) = b[3] - b[1]

    // ---------------- list markers ----------------

    /** Whether [t] (a line's first part) is a weak list marker ("1.", "a)", "-"): it counts only beside others of its kind. */
    fun weakMarker(t: String): String? = markerKind(t)?.takeIf { !strong(it) }

    /** Glyphs that only ever start a list item. */
    private const val BULLETS = "•●▪◦‣∙·►▸■□➤✓✔◆◇○▶–—"
    private val numbered = Regex("^(\\(?\\d{1,2}[.)]|\\(?[a-zA-Z][.)]|[ivxIVX]{1,4}[.)])$")

    /** What kind of list marker [t] (the first part of a line, or the start of a line's text) is: null = none. */
    fun markerKind(t: String): String? {
        val s = t.trim()
        if (s.isEmpty()) return null
        if (s.length == 1 && s[0] in BULLETS) return if (s[0] == '–' || s[0] == '—') "-" else "•"
        if (s == "-" || s == "*" || s == "+") return s
        if (numbered.matches(s)) return if (s.trimStart('(').first().isDigit()) "1." else "a."
        return null
    }

    /** A strong marker splits wherever it stands; a weak one ("-", "*", "1.", "a)") only when the block has two of its kind. */
    private fun strong(kind: String) = kind == "•"

    // ---------------- icons ----------------

    /**
     * Whether part [i] of [line] is an icon the recognizer read as a character: one character alone at the start
     * or end of the line, set off by a gap, and either drawn too wide for a letter (a round clock read as "9"),
     * not a letter or digit at all (a chevron read as ">"), or read with little confidence.
     */
    fun isIcon(line: L, i: Int): Boolean {
        val ps = line.parts
        if (ps.size < 2 || (i != 0 && i != ps.size - 1)) return false
        val p = ps[i]
        val t = p.text.trim()
        if (t.length != 1 || isCjk(t[0]) || markerKind(t) != null) return false
        val others = ps.filterIndexed { k, _ -> k != i }.filter { o -> o.text.any { it.isLetter() } }
        if (others.isEmpty()) return false
        val hh = others.map { h(it.box) }.sorted()[others.size / 2].coerceAtLeast(1)
        val n = if (i == 0) ps[1] else ps[i - 1]
        val gap = if (i == 0) n.box[0] - p.box[2] else p.box[0] - n.box[2]
        if (gap < hh * 0.3f) return false
        val c = t[0]
        val wide = w(p.box) >= h(p.box) * 0.85f && h(p.box) >= hh * 0.7f && c !in "MWmw@%"
        val sign = !c.isLetterOrDigit() && c !in "&$€£¥#(\"'"
        return wide || sign || p.conf < 0.45f
    }

    /**
     * A line without its list marker and the icons at its ends. Returns the line (null if nothing is left), the
     * marker's kind, and how far left/right the translation may paint (between the marker or icon and the text).
     */
    class Clean(val line: L?, val kind: String?, val keepL: Int, val keepR: Int)

    fun clean(line: L, markers: Boolean = true): Clean {
        if (line.parts.isEmpty()) return Clean(line, markerKind(line.text.trim().substringBefore(' ')), Int.MIN_VALUE, Int.MAX_VALUE)
        var ps = line.parts
        var keepL = Int.MIN_VALUE; var keepR = Int.MAX_VALUE
        var kind: String? = null
        fun at(list: List<P>) = L(line.text, line.box, list)
        // an icon before the marker or the text
        if (ps.size >= 2 && isIcon(at(ps), 0)) { keepL = (ps[0].box[2] + ps[1].box[0]) / 2; ps = ps.drop(1) }
        if (markers && ps.size >= 2) {
            val k = markerKind(ps[0].text)
            val t = ps[0].text
            // between the marker and the text, but close to the marker: the recognizer's box of a short first word
            // can start well right of its letters
            if (k != null) { kind = k; keepL = min((ps[0].box[2] + ps[1].box[0]) / 2, ps[0].box[2] + h(line.box) / 3); ps = ps.drop(1) }
            else if (t.length >= 3 && t[0] in BULLETS && t[0] != '–' && t[0] != '—' && t[1].isLetterOrDigit()) {
                // a bullet glued to its word ("•Corners"): the glyph is about one letter wide
                kind = "•"
                val b = ps[0].box
                val cut = b[0] + w(b) / t.length
                keepL = cut
                ps = listOf(P(t.substring(1), intArrayOf(cut + 1, b[1], b[2], b[3]), ps[0].conf)) + ps.drop(1)
            }
        }
        if (ps.size >= 2 && isIcon(at(ps), ps.size - 1)) { keepR = (ps[ps.size - 2].box[2] + ps.last().box[0]) / 2; ps = ps.dropLast(1) }
        if (ps === line.parts) return Clean(line, kind, keepL, keepR)
        if (ps.isEmpty()) return Clean(null, kind, keepL, keepR)
        val text = ps.fold("") { a, p -> glue(a, p.text) }
        val box = intArrayOf(ps.minOf { it.box[0] }, line.box[1], ps.maxOf { it.box[2] }, line.box[3])
        return Clean(L(text, box, ps), kind, keepL, keepR)
    }

    // ---------------- blocks ----------------

    /** Separate text on either side of a wide gutter when OCR returned one line across both columns. */
    fun columns(line: L): List<L> {
        val parts = line.parts.sortedBy { it.box[0] }
        if (parts.size < 2) return listOf(line)
        val gaps = parts.zipWithNext { a, b -> b.box[0] - a.box[2] }
        val normal = gaps.filter { it in 1..h(line.box) }.sorted()
        val gutter = max(h(line.box) * 1.6f, (normal.getOrNull(normal.size / 2) ?: 0) * 3.5f)
        val cuts = gaps.indices.filter { i ->
            gaps[i] > gutter && parts[i].text.any { it.isLetter() } && parts[i + 1].text.any { it.isLetter() }
        }
        if (cuts.isEmpty()) return listOf(line)
        return (listOf(0) + cuts.map { it + 1 } + parts.size).zipWithNext { a, b ->
            val words = parts.subList(a, b)
            val box = intArrayOf(words.first().box[0], words.minOf { it.box[1] },
                words.maxOf { it.box[2] }, words.maxOf { it.box[3] })
            L(words.fold("") { text, word -> glue(text, word.text) }, box, words)
        }
    }

    /** Distance from one line's top to the next (tops: nearly every line has capitals or ascenders, fewer have descenders). */
    fun pitches(lines: List<L>): List<Int> = (1 until lines.size).map { lines[it].box[1] - lines[it - 1].box[1] }

    /** The usual pitch inside a block: its smallest that is not an overlap (null with fewer than three lines). */
    fun innerPitch(lines: List<L>): Int? {
        val ps = pitches(lines)
        if (ps.size < 2) return null
        val hh = lineHeight(lines)
        return ps.filter { it >= hh * 0.8f }.minOrNull()
    }

    /** The same from the lines' bottoms (null with fewer than three lines). */
    private fun innerPitchBottom(lines: List<L>): Int? {
        if (lines.size < 3) return null
        val hh = lineHeight(lines)
        return (1 until lines.size).map { lines[it].box[3] - lines[it - 1].box[3] }.filter { it >= hh * 0.8f }.minOrNull()
    }

    fun lineHeight(lines: List<L>): Int = lines.map { h(it.box) }.sorted()[lines.size / 2].coerceAtLeast(1)

    /**
     * Splits the lines of one recognized block into the blocks a reader sees: a new block at every list marker, at a
     * line pitch clearly larger than the block's own (a paragraph gap), and at a line that starts left of the lines
     * before it (the next paragraph after an indented list item). [refPitch] is the screen's usual pitch for text of
     * this height (0 = unknown), for blocks too short to tell their own. From the picture, when known: [ink] = each
     * line's letter colour (a line in another colour starts another block: a title over its grey subtitle), [bullets]
     * = for a line with a bullet drawn left of it that the recognizer did not read, the column between the two.
     */
    fun split(lines: List<L>, refPitch: Int = 0, ink: List<Int?> = emptyList(), bullets: List<Int?> = emptyList(), xh: List<Int?> = emptyList(),
              listed: Set<String> = emptySet()): List<Item> {
        if (lines.isEmpty()) return emptyList()
        val first = lines.mapIndexed { i, l -> clean(l).let { c -> val b = bullets.getOrNull(i); if (c.kind == null && b != null) Clean(c.line, "•", b, c.keepR) else c } }
        // [listed]: weak markers that stand in a column with markers of their kind in the blocks next to this one (a
        // numbered list whose last item the recognizer gave as a block of its own: its "3." alone was taken for text)
        val kinds = (first.mapNotNull { it.kind } + first.mapNotNull { it.kind }.filter { it in listed }).groupingBy { it }.eachCount()
        // a weak marker that stands alone ("- or so it seemed" in a wrapped sentence) is text
        val cleaned = first.mapIndexed { i, c -> val k = c.kind; if (k != null && !strong(k) && (kinds[k] ?: 0) < 2) clean(lines[i], markers = false) else c }
        val hh = lineHeight(lines)
        val inner = innerPitch(lines) ?: refPitch.takeIf { it > 0 }
        // A paragraph gap moves a line's top AND its bottom down. One line whose box top alone came out a few pixels
        // higher or lower (letters without ascenders, the recognizer's jitter) made one step from the tops look like a
        // gap (78/69/84 px, the true pitch 77): the last line of a paragraph was cut off and translated on its own.
        val innerB = if (innerPitch(lines) != null) innerPitchBottom(lines) else inner
        val out = ArrayList<Item>()
        var cur = ArrayList<L>()
        var keepL = Int.MIN_VALUE; var keepR = Int.MAX_VALUE
        var prev: L? = null
        fun flush() { if (cur.isNotEmpty()) out.add(Item(cur, keepL, keepR, out.isNotEmpty())); cur = ArrayList(); keepL = Int.MIN_VALUE; keepR = Int.MAX_VALUE }
        for ((i, c) in cleaned.withIndex()) {
            val orig = lines[i]
            val l = c.line
            if (l == null) { prev = orig; continue }
            var cut = cur.isEmpty()
            val k = c.kind
            if (k != null && (strong(k) || (kinds[k] ?: 0) >= 2)) cut = true
            val a = ink.getOrNull(i); val b = ink.getOrNull(i - 1)
            if (a != null && b != null && inkDist(a, b) > 90) cut = true
            val p = prev
            if (!cut && p != null) {
                val pitch = orig.box[1] - p.box[1]
                val gap = orig.box[1] - p.box[3]
                // With no pitch to compare with, the letters' x-height says it (boxes of lines without descenders
                // are short, which made an ordinary line gap look like a paragraph's): a line pitch is up to about
                // three x-heights even at 1.6 spacing, a paragraph gap adds more than one
                val x = xh.filterNotNull().sorted().let { if (it.size == xh.size && it.isNotEmpty()) it[it.size / 2] else null }
                val pitchB = orig.box[3] - p.box[3]
                if (inner != null) {
                    if (pitch > inner * 1.12f && pitch - inner > hh * 0.2f && (innerB == null || (pitchB > innerB * 1.12f && pitchB - innerB > hh * 0.2f))) cut = true
                }
                else if (x != null) { if (pitch > x * 3.5f) cut = true }
                else if (gap > hh * 0.9f) cut = true
                // centred lines share a centre, not a left edge
                val centred = abs((orig.box[0] + orig.box[2]) - (p.box[0] + p.box[2])) < hh && abs(orig.box[0] - p.box[0]) > hh * 0.8f
                if (!centred && cur.size >= 2) {
                    val left = cur.drop(1).minOf { it.box[0] }
                    if (l.box[0] < left - hh * 0.8f) cut = true
                }
                if (!centred && cur.isNotEmpty()) {
                    // first-line indent: the line before ended short, this one starts further right
                    val widest = cur.maxOf { w(it.box) }
                    val left = cur.minOf { it.box[0] }
                    if (l.box[0] > left + hh * 0.8f && w(p.box) < widest * 0.85f && cur.size >= 2) cut = true
                }
            }
            if (cut) { flush(); keepL = c.keepL; keepR = c.keepR }
            else { keepL = max(keepL, c.keepL); keepR = min(keepR, c.keepR) }
            cur.add(l)
            prev = orig
        }
        flush()
        return out
    }

    private fun inkDist(a: Int, b: Int) = abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))

    /**
     * The other way round: the recognizer often returns the last line of a wrapped list item (a wrapped continuation)
     * as a block of its own, because the item's block began further left at its bullet. An item that does not start
     * with a marker or icon is joined to the one just above it when its first line starts at that one's text edge, at
     * that one's line pitch, in its size, and the line above ran on to the right (it was not a paragraph's end).
     */
    fun join(items: List<Item>): List<Item> {
        val out = ArrayList<Item>()
        for (it in items.sortedWith(compareBy({ it.box[1] }, { it.box[0] }))) {
            val at = if (it.keepL != Int.MIN_VALUE || it.split) -1 else out.indices.filter { i ->
                val p = out[i]
                val pl = p.lines.last(); val f = it.lines.first()
                val hh = lineHeight(p.lines)
                // its own pitch; for one line the letters' x-height (as in [split]), else a guess from the box
                val own = innerPitch(p.lines) ?: pitches(p.lines).minOrNull()
                val limit = if (own != null) own * 1.12f + 1 else if (p.xh > 0) p.xh * 3.5f else hh * 1.85f
                val step = f.box[1] - pl.box[1]
                val textLeft = p.lines.minOf { l -> l.box[0] }
                val widest = max(p.lines.maxOf { l -> w(l.box) }, it.lines.maxOf { l -> w(l.box) })
                val sharedWidth = min(pl.box[2], f.box[2]) - max(pl.box[0], f.box[0])
                val punctuation = pl.text.trimEnd().lastOrNull()
                // A sentence may end at the right edge of a wrapped line within the same paragraph.
                val fullLine = w(pl.box) > widest * 0.85f &&
                    (p.lines.size > 1 || (p.keepL == Int.MIN_VALUE && w(f.box) > widest * 0.82f && step <= hh * 1.55f))
                val runsOn = punctuation == null || punctuation !in ".!?。！？" || fullLine
                val sameInk = p.lastInk == null || it.firstInk == null || inkDist(p.lastInk, it.firstInk) <= 90
                // Tall capitals, umlauts and descenders can make a short wrap look much taller than its neighbours.
                val tailAtPitch = own != null && it.lines.size == 1 && f.text.trim().none(Char::isWhitespace) &&
                    punctuation != null && punctuation !in ".!?。！？" &&
                    abs((f.box[3] - pl.box[3]) - own) <= hh * 0.45f &&
                    h(f.box) in (hh * 0.6f).toInt()..(hh * 1.5f).toInt()
                val sameHeight = abs(lineHeight(it.lines) - hh) < hh * 0.3f || tailAtPitch
                abs(f.box[0] - textLeft) < hh * 0.6f && sameHeight &&
                    step > hh * 0.8f && step <= limit && sharedWidth >= min(w(pl.box), w(f.box)) * 0.7f &&
                    w(pl.box) > widest * 0.55f && runsOn && sameInk
            }.maxByOrNull { out[it].lines.last().box[1] } ?: -1
            if (at >= 0) { val p = out[at]; out[at] = Item(p.lines + it.lines, p.keepL, min(p.keepR, it.keepR), p.split, p.xh, p.firstInk, it.lastInk) } else out.add(it)
        }
        return out
    }

    // ---------------- bold runs ----------------

    /**
     * Where the bold words of the original ([words]: each word and whether it is bold) are in [translated]. A bold
     * run at the start that ends in ':' (a label: "Note: ...") is the translation up to its first ':'; any other bold
     * run is found where the translation has the same words (names, numbers, code). Runs that cannot be placed are
     * left out: a wrong word in bold is worse than none.
     */
    fun boldRanges(words: List<Pair<String, Boolean>>, translated: String): List<IntRange> {
        if (words.none { it.second } || words.all { it.second }) return emptyList()
        val runs = ArrayList<Pair<Int, String>>()   // index of the first word, text
        var i = 0
        while (i < words.size) {
            if (!words[i].second) { i++; continue }
            var j = i
            while (j + 1 < words.size && words[j + 1].second) j++
            runs.add(i to words.subList(i, j + 1).fold("") { a, w -> glue(a, w.first) })
            i = j + 1
        }
        val out = ArrayList<IntRange>()
        for ((first, run) in runs) {
            val label = run.trimEnd().lastOrNull()
            if (first == 0 && (label == ':' || label == '：')) {
                val at = translated.indexOfFirst { it == ':' || it == '：' }
                if (at in 1..max(run.length * 3, 12) && at < translated.length * 0.6f) { out.add(0..at); continue }
            }
            val needle = run.trim().trimEnd(':', '：', ',', '.')
            if (needle.length < 2) continue
            var from = 0
            while (true) {
                val at = translated.indexOf(needle, from, ignoreCase = true)
                if (at < 0) break
                val end = at + needle.length
                val okL = at == 0 || !translated[at - 1].isLetterOrDigit()
                val okR = end >= translated.length || !translated[end].isLetterOrDigit()
                if (okL && okR && out.none { it.first < end && at <= it.last }) { out.add(at until end); break }
                from = at + 1
            }
        }
        return out.sortedBy { it.first }
    }

    // ---------------- the page and the app's own bars ----------------

    /** One accessibility node, in pre-order: [depth] in the tree, [scroll] scrollable, [edit] an input field. */
    class N(val box: IntArray, val depth: Int, val scroll: Boolean = false, val edit: Boolean = false)

    /**
     * [page]: the part of the main scrolling list that is not under a bar of the app's own drawn after it (an input
     * bar at the bottom, a toolbar at the top). [list]: the whole list (text inside it but outside [page] is under a
     * bar or in the gesture strip: not page text). [fields]: input fields (their hint or typed text is not page text).
     */
    class Area(val page: IntArray, val list: IntArray?, val fields: List<IntArray>)

    fun area(nodes: List<N>, sw: Int, sh: Int, top0: Int, bottom0: Int): Area {
        val fields = nodes.filter { it.edit && w(it.box) > 0 && h(it.box) > 0 }.map { it.box }
        var s = -1
        var best = 0L
        for ((i, n) in nodes.withIndex()) {
            if (!n.scroll) continue
            val a = max(0, w(n.box)).toLong() * max(0, h(n.box))
            if (a > best && a >= sw.toLong() * sh / 5) { best = a; s = i }
        }
        if (s < 0) return Area(intArrayOf(0, top0, sw, bottom0), null, fields)
        val sb = nodes[s].box
        var end = s + 1
        while (end < nodes.size && nodes[end].depth > nodes[s].depth) end++
        var top = max(sb[1], top0); var bottom = min(sb[3], bottom0)
        val cy = (sb[1] + sb[3]) / 2
        for (j in end until nodes.size) {
            val b = nodes[j].box
            val l = max(b[0], sb[0]); val t = max(b[1], sb[1]); val r = min(b[2], sb[2]); val bt = min(b[3], sb[3])
            if (r <= l || bt <= t) continue
            if (r - l < w(sb) * 0.5f || bt - t > h(sb) * 0.35f) continue
            if ((t + bt) / 2 > cy) bottom = min(bottom, t) else top = max(top, bt)
        }
        if (bottom <= top) { top = max(sb[1], top0); bottom = min(sb[3], bottom0) }
        return Area(intArrayOf(sb[0], top, sb[2], bottom), sb, fields)
    }

    /** What happens to a block at [b] on this screen: null = not page text (drop it), else the rectangle it is drawn in. */
    fun clipFor(a: Area, b: IntArray, sw: Int, sh: Int): IntArray? {
        val area = w(b).toLong() * h(b)
        for (f in a.fields) {
            val l = max(b[0], f[0]); val t = max(b[1], f[1]); val r = min(b[2], f[2]); val bt = min(b[3], f[3])
            if (r > l && bt > t && (r - l).toLong() * (bt - t) * 10 >= area * 3) return null
        }
        val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2
        val p = a.page
        val list = a.list
        if (list != null && cx in list[0]..list[2] && cy in list[1]..list[3]) {
            if (cy !in p[1]..p[3]) return null
            return p.copyOf()
        }
        if (list == null && cy !in p[1]..p[3]) return null
        // Outside the list (a toolbar's title, a bar's button): drawn in its own strip, never over the page.
        return when {
            cy < p[1] -> intArrayOf(0, 0, sw, p[1])
            cy > p[3] -> intArrayOf(0, p[3], sw, sh)
            else -> intArrayOf(0, 0, sw, sh)
        }
    }
}
