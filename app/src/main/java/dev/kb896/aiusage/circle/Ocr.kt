package dev.kb896.aiusage.circle

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** [lb] is the box of the line the word sits on: selection highlights and hit tests use the line's height. */
class Word(val text: String, val box: Rect, val line: Int, val block: Int, val lb: Rect = box)
class Line(val text: String, val box: Rect)
class Block(val text: String, val box: Rect, val lines: List<Line>)
class OcrResult(val words: List<Word>, val blocks: List<Block>)

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

object Ocr {
    // One bundled recognizer per script ML Kit offers. Each also reads Latin, but only its own script well.
    private val names = listOf("latin", "chinese", "japanese", "korean", "devanagari")
    private val clients by lazy {
        listOf(
            TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions.Builder().build()),
        )
    }
    /** Recognizers that contributed text at the last full probe; the frames in between only run these. */
    @Volatile private var active = setOf(0, 1)
    private var sinceProbe = 0

    fun warm() { clients }

    private class Cand(val block: Block, val words: List<Word>, val q: Float, val src: Int)

    /** How much text in a recognizer's own script a block holds: that is what makes its reading trustworthy. */
    private fun quality(text: String, src: Int): Float {
        var latin = 0; var han = 0; var kana = 0; var hangul = 0; var deva = 0
        for (c in text) when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.LATIN -> latin++
            Character.UnicodeScript.HAN -> han++
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
            Character.UnicodeScript.HANGUL -> hangul++
            Character.UnicodeScript.DEVANAGARI -> deva++
            else -> {}
        }
        return when (src) {
            0 -> latin.toFloat()
            1 -> if (han >= 2) han * 3f else 0f
            // Japanese is Han plus kana; without kana the Chinese reading of the same block wins the tie.
            // Japanese needs kana; Han alone is left to the Chinese recognizer.
            2 -> if (kana >= 2) (kana + han) * 3f + 1 else 0f
            3 -> if (hangul >= 2) hangul * 3f else 0f
            else -> if (deva >= 4) deva * 3f else 0f
        }
    }

    /**
     * Runs OCR on [bmp]; boxes are scaled by [kx]/[ky] into view coordinates. Callback on the main thread.
     * All recognizers run in parallel and every block of text is taken from the recognizer that read it in its
     * own script, so a screen can mix scripts. With [probe] false only the recognizers that found something
     * last time run (a full probe still happens every few frames), which keeps scroll-translate fast.
     */
    fun run(bmp: Bitmap, kx: Float, ky: Float, probe: Boolean = true, late: ((OcrResult) -> Unit)? = null, cb: (OcrResult) -> Unit) {
        val img = InputImage.fromBitmap(bmp, 0)
        val full = probe || sinceProbe++ >= 4
        if (full) sinceProbe = 0
        val first = if (full) clients.indices.toSet() else active
        // With [late], the recognizers that are switched off read the picture too, without anyone waiting for them.
        // If one of them finds real text in its own script, [late] gets the reading of all of them together.
        val others = if (late != null) clients.indices.toSet() - first else emptySet()
        val res = arrayOfNulls<com.google.mlkit.vision.text.Text>(clients.size)
        var left = first.size
        var leftLate = others.size
        var use = first
        var fullNow = full
        var deliver: (OcrResult) -> Unit = {}
        var done = {}
        val doneLate = {
            if (--leftLate == 0 && left == 0) {
                val found = others.filter { i -> res[i]?.let { t -> convert(t, kx, ky, i).sumOf { it.q.toDouble() } >= 24 } == true }
                if (found.isNotEmpty()) {
                    Dbg.log("ocr: text in " + found.joinToString { names[it] } + " on this screen")
                    use = clients.indices.toSet(); fullNow = true; left = 1
                    deliver = { r -> mainHandler.post { late?.invoke(r) } }
                    done()
                }
            }
        }
        done = {
            if (--left == 0) {
                val cands = ArrayList<Cand>()
                for (i in use) res[i]?.let { cands.addAll(convert(it, kx, ky, i)) }
                cands.sortByDescending { it.q }
                val kept = ArrayList<Cand>()
                for (c in cands) {
                    if (c.q <= 0f) continue
                    val a = c.block.box
                    val clash = kept.any { k ->
                        val r = Rect(a)
                        r.intersect(k.block.box) && r.width().toLong() * r.height() * 10 > minOf(a.width().toLong() * a.height(), k.block.box.width().toLong() * k.block.box.height()) * 3
                    }
                    if (!clash) kept.add(c)
                }
                // reading order: top to bottom, and blocks on one row left to right
                val order = SelLogic.readingOrder(kept.map { intArrayOf(it.block.box.left, it.block.box.top, it.block.box.right, it.block.box.bottom) })
                val sorted = order.map { kept[it] }
                kept.clear(); kept.addAll(sorted)
                if (fullNow) {
                    // A script stays switched on only if it contributed real text, not one doubtful block.
                    val now = kept.groupBy { it.src }.filter { (src, list) -> src <= 1 || list.sumOf { it.q.toDouble() } >= 24 }.keys + 0 + 1
                    if (now != active) Dbg.log("ocr scripts in use: " + now.sorted().joinToString { names[it] })
                    active = now
                }
                val words = ArrayList<Word>(); val blocks = ArrayList<Block>()
                var line = 0
                for (c in kept) {
                    val base = c.words.minOfOrNull { it.line } ?: 0
                    for (w in c.words) words.add(Word(w.text, w.box, line + w.line - base, blocks.size, w.lb))
                    line += c.block.lines.size
                    blocks.add(c.block)
                }
                val out = OcrResult(words, blocks)
                deliver(out)
                // the late readers may have finished before the first ones
                if (leftLate == 0 && others.isNotEmpty() && use === first) { leftLate = 1; doneLate() }
            }
        }
        deliver = { r -> mainHandler.post { cb(r) } }
        // Collecting and merging the results stays off the main thread: it used to land in the middle of the
        // opening animation. Only the finished result is handed over there.
        for (i in first) clients[i].process(img)
            .addOnSuccessListener(merge) { res[i] = it; done() }
            .addOnFailureListener(merge) { done() }
        for (i in others) clients[i].process(img)
            .addOnSuccessListener(merge) { res[i] = it; doneLate() }
            .addOnFailureListener(merge) { doneLate() }
    }
    private val merge = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Reads one part of the picture again, enlarged [f] times, with the Latin recognizer. [rect] is in bitmap
     * pixels; the words come back in view coordinates. Used for selections of very small text. Callback on main.
     */
    fun reread(bmp: Bitmap, rect: Rect, kx: Float, ky: Float, f: Float, cb: (List<Word>) -> Unit) {
        merge.execute {
            val out = runCatching {
                val r = Rect(rect)
                if (bmp.isRecycled || !r.intersect(0, 0, bmp.width, bmp.height) || r.width() < 8 || r.height() < 8) return@runCatching emptyList<Word>()
                // never more than about 6 million pixels after enlarging
                val ff = minOf(f, kotlin.math.sqrt(6_000_000f / (r.width() * r.height()))).coerceAtLeast(1f)
                val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
                val big = Bitmap.createScaledBitmap(crop, (r.width() * ff).toInt().coerceAtLeast(1), (r.height() * ff).toInt().coerceAtLeast(1), true)
                try {
                    val t = com.google.android.gms.tasks.Tasks.await(clients[0].process(InputImage.fromBitmap(big, 0)), 4, java.util.concurrent.TimeUnit.SECONDS)
                    val dx = (r.left * kx).toInt(); val dy = (r.top * ky).toInt()
                    fun mv(q: Rect) = Rect(q.left + dx, q.top + dy, q.right + dx, q.bottom + dy)
                    convert(t, kx / ff, ky / ff, 0).flatMap { c -> c.words.map { Word(it.text, mv(it.box), it.line, 0, mv(it.lb)) } }
                } finally {
                    if (big !== crop && big !== bmp) big.recycle()
                    if (crop !== bmp) crop.recycle()
                }
            }.getOrElse { Dbg.log("re-read failed: $it"); emptyList() }
            mainHandler.post { cb(out) }
        }
    }

    private fun convert(t: com.google.mlkit.vision.text.Text, kx: Float, ky: Float, src: Int): List<Cand> {
        fun sc(r: Rect) = Rect((r.left * kx).toInt(), (r.top * ky).toInt(), (r.right * kx).toInt(), (r.bottom * ky).toInt())
        // Recognizers sometimes read icons or Latin letters as CJK characters. Real CJK text comes in runs,
        // so only stray ones are dropped: one or two in a line, or a small minority of it.
        fun stray(line: String): Boolean {
            val cjk = line.count { isCjk(it) }
            return cjk in 1..2 || (cjk > 0 && cjk * 5 < line.count { it.isLetter() })
        }
        fun clean(s: String, strip: Boolean): String =
            if (!strip || s.none { isCjk(it) }) s else s.filter { !isCjk(it) }.replace(Regex("\\s+"), " ").trim()
        val out = ArrayList<Cand>()
        for (b in t.textBlocks) {
            val bb = b.boundingBox ?: continue
            val lines = ArrayList<Line>()
            val words = ArrayList<Word>()
            var joined = ""
            for (l in b.lines) {
                val lb = l.boundingBox ?: continue
                val minor = stray(l.text)
                val lt = clean(l.text, minor)
                if (lt.isEmpty()) continue
                // A tilted line has a tall upright box around it; there each word keeps its own box instead.
                val tallest = l.elements.maxOfOrNull { it.boundingBox?.height() ?: 0 } ?: 0
                val upright = tallest > 0 && lb.height() <= tallest * 1.6f
                for (e in l.elements) {
                    val eb = e.boundingBox ?: continue
                    val et = clean(e.text, minor)
                    if (et.isEmpty()) continue
                    // Chinese, Japanese and Korean come as whole phrases; their characters are separate selectable
                    // units, as words are in other scripts.
                    val syms = if (et.any { isCjk(it) } && !minor) e.symbols.filter { it.boundingBox != null && it.text.isNotBlank() } else emptyList()
                    if (syms.size > 1) {
                        for (sy in syms) { val sb = sc(sy.boundingBox!!); words.add(Word(sy.text, sb, lines.size, 0, if (upright) Rect(sb.left, sc(lb).top, sb.right, sc(lb).bottom) else sb)) }
                    } else words.add(Word(et, sc(eb), lines.size, 0, if (upright) Rect(sc(eb).left, sc(lb).top, sc(eb).right, sc(lb).bottom) else sc(eb)))
                }
                lines.add(Line(lt, sc(lb)))
                joined = glue(joined, lt)
            }
            if (lines.isNotEmpty()) out.add(Cand(Block(joined, sc(bb), lines), words, quality(joined, src), src))
        }
        return out
    }
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
            val sameEdge = kotlin.math.abs(b.box.left - p.box.left) < h * 0.6f
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
