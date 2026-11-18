package dev.kb896.aiusage.circle

import android.graphics.Rect

/** [lb] is the box of the line the word sits on: selection highlights and hit tests use the line's height. */
class Word(val text: String, val box: Rect, val line: Int, val block: Int, val lb: Rect = box, val characters: List<Glyph> = emptyList(), val joinsPrevious: Boolean = false) {
    fun glyphAt(x: Float) = characters.indices.minByOrNull { i ->
        val bounds = characters[i].box
        maxOf(bounds.left - x, x - bounds.right, 0f)
    } ?: 0

    fun selection(first: Int = 0, last: Int = characters.lastIndex): SelLogic.W {
        val slice = if (characters.isEmpty() || first == 0 && last >= characters.lastIndex) null else {
            val start = first.coerceIn(0,characters.lastIndex)
            characters.subList(start, last.coerceIn(start,characters.lastIndex) + 1)
        }
        return SelLogic.W(slice?.joinToString("") { it.text } ?: text,
            slice?.minOf { it.box.left } ?: box.left, box.top, slice?.maxOf { it.box.right } ?: box.right,
            box.bottom, lb.top, lb.bottom, line, block, joinsPrevious)
    }
}
class Part(val text: String, val box: Rect, val conf: Float = 1f)
class Line(val text: String, val box: Rect, val parts: List<Part> = emptyList())
class Block(
    val text: String, val box: Rect, val lines: List<Line>,
    val keepL: Int = Int.MIN_VALUE, val keepR: Int = Int.MAX_VALUE,
)
class OcrResult(
    val words: List<Word>, val blocks: List<Block>, val suggestedPacks: List<String> = emptyList(),
    val fastFallback: List<String> = emptyList(),
    val valid: Boolean = true,
) {
    internal val fallbackNotice get() = fastFallback.takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = "Using Fast for ")
}

fun isCjk(c: Char): Boolean {
    val b = Character.UnicodeScript.of(c.code)
    return b == Character.UnicodeScript.HAN || b == Character.UnicodeScript.HIRAGANA ||
        b == Character.UnicodeScript.KATAKANA || b == Character.UnicodeScript.HANGUL
}

/** Joins two neighbouring pieces: no space between CJK characters, a space otherwise. */
fun glue(a: String, b: String): String =
    if (a.isEmpty()) b
    else if (b.isEmpty()) a
    else if (isCjk(a.last()) && isCjk(b.first())) a + b
    // A word divided at the end of a line ("Kunden-" / "dienst") is one word again; "Ein- und Ausgang" stays.
    else if (a.length >= 3 && a.last() == '-' && a[a.length - 2].isLetter() && b.first().isLowerCase() && !Regex("^(und|oder|and|or|bzw)\\b").containsMatchIn(b)) a.dropLast(1) + b
    else "$a $b"

internal object Dbg {
    fun log(message: String) { android.util.Log.d("CircleSearch", message) }
}

/**
 * OCR often returns one visual paragraph as several blocks. Joins consecutive blocks that share a left edge
 * and line height and follow each other at line distance, so a paragraph is translated and sized as one.
 */
fun mergeParagraphs(blocks: List<Block>): List<Block> {
    val out = ArrayList<Block>()
    fun lh(b: Block) = b.lines.maxOf { it.box.height() }   // the tallest line has both ascenders and descenders
    // OCR does not always return blocks top to bottom, so look for the block directly above among all kept ones.
    for (b in blocks.sortedBy { it.box.top }) {
        val h2 = lh(b)
        val at = out.indexOfLast { p ->
            val h = lh(p)
            val gap = b.lines.first().box.top - p.lines.last().box.bottom
            val rtl = b.text.firstOrNull { it.isLetter() }?.let {
                Character.getDirectionality(it) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            } == true
            val edgeDelta = if (rtl) b.box.right - p.box.right else b.box.left - p.box.left
            val sameEdge = kotlin.math.abs(edgeDelta) < h * 0.8f
            val sameSize = kotlin.math.abs(h2 - h) < h * 0.3f
            // The previous line must be reasonably long (ragged right is fine), otherwise it was a paragraph end.
            val full = p.lines.last().box.width() > maxOf(p.box.width(), b.box.width()) * 0.55f
            sameEdge && sameSize && full && gap > -h * 0.3f && gap < h * 0.95f
        }
        if (at >= 0) {
            val p = out[at]
            out[at] = Block(glue(p.text, b.text), android.graphics.Rect(p.box).apply { union(b.box) }, p.lines + b.lines)
        } else out.add(b)
    }
    return out
}
