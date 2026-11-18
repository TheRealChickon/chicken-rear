package dev.kb896.aiusage.circle

import android.graphics.Rect
import kotlin.math.roundToInt

data class Glyph(val text: String, val box: Rect)
internal data class Reading(val glyphs: List<Glyph>, val confidence: Float) {
    val text get() = glyphs.joinToString("") { it.text }.trim()
}
internal data class TextRegion(val box: Rect, val reading: Reading)

internal object PaddleLayout {
    /** CTC blank is class zero. Collapse only consecutive repeats; a blank separates doubled letters. */
    fun decode(values: FloatArray, labels: List<String>, steps: Int, crop: Rect,
               contentWidth: Int, tensorWidth: Int, rtl: Boolean, ink: BooleanArray? = null): Reading {
        require(values.size == steps * labels.size)
        data class Peak(val token: Int, val first: Int, var last: Int, var probability: Float)
        val peaks = ArrayList<Peak>()
        var previous = -1
        for (step in 0 until steps) {
            val start = step * labels.size
            var best = 0
            for (i in 1 until labels.size) if (values[start + i] > values[start + best]) best = i
            if (best != 0 && step * tensorWidth < contentWidth * steps) {
                val score = values[start + best]
                if (best == previous) {
                    peaks.last().last = step
                    peaks.last().probability = maxOf(score, peaks.last().probability)
                } else peaks += Peak(best, step, step, score)
            }
            previous = best
        }
        if (peaks.isEmpty()) return Reading(emptyList(), 0f)
        val unit = crop.width().toFloat() * tensorWidth / (steps * contentWidth)
        fun x(step: Float) = (crop.left + step * unit).roundToInt().coerceIn(crop.left, crop.right)
        // Boundaries bisect the blank interval between two emissions. Padding is outside the source crop.
        val visual = peaks.mapIndexed { i, p ->
            val left = if (i == 0) crop.left else x((peaks[i-1].last + 1 + p.first) / 2f)
            val right = if (i == peaks.lastIndex) crop.right else x((p.last + 1 + peaks[i+1].first) / 2f)
            Glyph(labels[p.token], Rect(left, crop.top, right, crop.bottom))
        }
        // Paddle's Arabic decoder reverses the visual sequence while retaining LTR number/Latin runs.
        val logical = if (rtl && visual.any { it.text.any { c -> Character.UnicodeScript.of(c.code) == Character.UnicodeScript.ARABIC } }) {
            val runs = mutableListOf<List<Glyph>>()
            var latin = mutableListOf<Glyph>()
            for (g in visual) {
                if (g.text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in " :*./%+-" }) latin.add(g)
                else {
                    if (latin.isNotEmpty()) { runs.add(latin); latin = mutableListOf() }
                    runs.add(listOf(g))
                }
            }
            if (latin.isNotEmpty()) runs.add(latin)
            runs.asReversed().flatten()
        } else visual
        // An out-of-script model can read just the Latin suffix with 99% confidence. Penalize
        // ink columns farther than half the normalized line height from any non-space emission.
        val coverage = if (ink == null) 1f else {
            val covered = BooleanArray(contentWidth)
            for (p in peaks) if (labels[p.token].isNotBlank()) {
                val start = ((p.first + .5f) * tensorWidth / steps - 24).toInt().coerceAtLeast(0)
                val end = ((p.last + .5f) * tensorWidth / steps + 24).toInt().coerceAtMost(contentWidth)
                for (x in start until end) covered[x] = true
            }
            var all = 0
            var accounted = 0
            for (x in ink.indices) if (ink[x]) { all++; if (covered[x]) accounted++ }
            if (all == 0) 1f else accounted.toFloat()/all
        }
        return Reading(logical, peaks.map { it.probability }.average().toFloat() * coverage)
    }

    /** DB sometimes separates words on one baseline. Stop at a column gutter or a new row. */
    private fun joinLines(regions: List<TextRegion>): List<TextRegion> {
        val order = SelLogic.readingOrder(regions.map { with(it.box) { intArrayOf(left,top,right,bottom) } })
        val joined = mutableListOf<TextRegion>()
        for (index in order) {
            val next = regions[index]
            val previous = joined.lastOrNull()
            if (previous != null) {
                val a = previous.box
                val b = next.box
                val h = minOf(a.height(), b.height())
                val gap = b.left - a.right
                val overlap = minOf(a.bottom,b.bottom) - maxOf(a.top,b.top)
                if (overlap >= .7f*h && gap >= -.2f*h && gap < .78f*h &&
                    maxOf(a.height(),b.height()) < h*1.45f) {
                    val bounds = Rect(a).apply { union(b) }
                    val rtl = previous.reading.text.firstOrNull { it.isLetter() }?.let {
                        Character.getDirectionality(it) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                    } == true
                    val first = if (rtl) next else previous
                    val second = if (rtl) previous else next
                    val space = if (glue(first.reading.text,second.reading.text).length >
                        first.reading.text.length + second.reading.text.length) listOf(Glyph(" ",Rect())) else emptyList()
                    joined[joined.lastIndex] = TextRegion(bounds, Reading(first.reading.glyphs + space + second.reading.glyphs,
                        minOf(first.reading.confidence,second.reading.confidence)))
                    continue
                }
            }
            joined.add(next)
        }
        return joined
    }

    /** Read a column to its end before entering the next one. Full-width headings and footers
     * are separated at horizontal whitespace before looking for gutters within the remaining band. */
    private fun orderParagraphs(blocks: List<Block>): List<Block> {
        if (blocks.size <= 1) return blocks
        val byX = blocks.sortedBy { it.box.left }
        val minGutter = blocks.minOf { it.lines.first().box.height() }.coerceAtLeast(1)
        var right = byX.first().box.right
        for (i in 1 until byX.size) {
            val next = byX[i].box
            if (next.left - right >= minGutter) {
                val leftColumn = byX.take(i)
                val rightColumn = byX.drop(i)
                val overlap = minOf(leftColumn.maxOf { it.box.bottom }, rightColumn.maxOf { it.box.bottom }) -
                    maxOf(leftColumn.minOf { it.box.top }, rightColumn.minOf { it.box.top })
                if (overlap >= minGutter / 2) {
                    val rtl = blocks.count { b -> b.text.firstOrNull(Char::isLetter)?.directionality ==
                        CharDirectionality.RIGHT_TO_LEFT_ARABIC } * 2 > blocks.size
                    return if (rtl) orderParagraphs(rightColumn) + orderParagraphs(leftColumn)
                        else orderParagraphs(leftColumn) + orderParagraphs(rightColumn)
                }
            }
            right = maxOf(right,next.right)
        }
        val byY = blocks.sortedBy { it.box.top }
        if (byX.last().box.left < byX.minOf { it.box.right }) return byY
        var bottom = byY.first().box.bottom
        for (i in 1 until byY.size) {
            if (byY[i].box.top > bottom)
                return orderParagraphs(byY.subList(0,i)) + orderParagraphs(byY.subList(i,byY.size))
            bottom = maxOf(bottom,byY[i].box.bottom)
        }
        val order = SelLogic.readingOrder(blocks.map { intArrayOf(it.box.left,it.box.top,it.box.right,it.box.bottom) })
        return order.map { blocks[it] }
    }

    fun result(regions: List<TextRegion>, kx: Float, ky: Float, dx: Float = 0f, dy: Float = 0f,
               offers: List<String> = emptyList()): OcrResult {
        fun scaled(r: Rect) = Rect((r.left*kx+dx).roundToInt(), (r.top*ky+dy).roundToInt(),
            (r.right*kx+dx).roundToInt(), (r.bottom*ky+dy).roundToInt())
        val lines = joinLines(regions.filter { it.reading.text.isNotBlank() }).associateBy { region ->
            val parts = mutableListOf<Part>()
            val token = StringBuilder()
            val box = Rect()
            fun flushPart() {
                if (token.isEmpty()) return
                parts.add(Part(token.toString(), scaled(box), region.reading.confidence))
                token.clear()
                box.setEmpty()
            }
            for (glyph in region.reading.glyphs) {
                if (glyph.text.isBlank()) flushPart() else {
                    token.append(glyph.text)
                    box.union(glyph.box)
                }
            }
            flushPart()
            Line(region.reading.text, scaled(region.box), parts)
        }
        val blocks = mergeParagraphs(lines.keys.map { Block(it.text, Rect(it.box), listOf(it)) })
        val sorted = orderParagraphs(blocks)
        val words = mutableListOf<Word>()
        var lineId = 0
        for ((blockId, block) in sorted.withIndex()) for (line in block.lines) {
            val glyphs = lines.getValue(line).reading.glyphs
            val buffer = ArrayList<Glyph>()
            val thai = if (line.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.THAI })
                android.icu.text.BreakIterator.getWordInstance(java.util.Locale.forLanguageTag("th")).apply {
                    setText(glyphs.joinToString("") { it.text })
                } else null
            var offset = 0
            var joinsPrevious = false
            var afterSpace = true
            fun flush() {
                if (buffer.isEmpty()) return
                val chars = buffer.map { Glyph(it.text, scaled(it.box)) }
                val box = Rect().apply { chars.forEach { union(it.box) } }
                words.add(Word(chars.joinToString("") { it.text }, box, lineId, blockId, line.box, chars, joinsPrevious))
                buffer.clear()
            }
            for (g in glyphs) {
                val cjk = g.text.codePoints().anyMatch { Character.UnicodeScript.of(it) in cjkScripts }
                if (g.text.isBlank() || cjk || thai?.isBoundary(offset) == true) flush()
                if (g.text.isNotBlank()) {
                    if (buffer.isEmpty()) joinsPrevious = !afterSpace
                    buffer.add(g)
                }
                if (cjk) flush()
                offset += g.text.length
                afterSpace = g.text.isBlank()
            }
            flush()
            lineId++
        }
        return OcrResult(words, sorted, offers)
    }

    private val cjkScripts = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
}
