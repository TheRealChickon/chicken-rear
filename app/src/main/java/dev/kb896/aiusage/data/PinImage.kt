package dev.kb896.aiusage.data

import kotlin.math.roundToInt

/**
 * A PICTURE PINNED TO THE BACK (a picture shared to "Pin to back" or "Pin to location"): what is pure about it. The
 * reading, the files and the hand-off are [PinImageFiles]'; the back draws it in the note's place (rear/PinFace).
 *
 * The picture is read ONCE as it is shared, scaled to what the notes page can show at the back's own pixels and no
 * larger ([fit]: [MAX_W] x [MAX_H], the page's picture place), and kept as a file of its own in the app's EXTERNAL files
 * folder, `pins/` ([DIR]), which the back's shell process reads as it reads its other files there. The note keeps the
 * file's NAME only ([Pin.image]): `<at>-<w>x<h>.jpg` ([name]), the time it was read and its size, so the back can lay
 * the page out before the picture is decoded ([size]); `.webp` for a picture with see-through parts (JPEG has none).
 *
 * THE FRONT'S COPY: a tap on the picture on the back opens it on the front, full screen (ui/PinViewActivity). The back's
 * copy is far too small for that, so the same single read also keeps a LARGER copy ([fitFront]: its longest side
 * [FRONT_MAX] at most, never larger than the picture is), in the app's PRIVATE files, `pins-front/` ([FRONT_DIR]): the
 * back's shell never reads it. It has the SAME NAME as the back's copy (the name is the note's key; the size in it is the
 * back copy's), so whatever finds one finds the other. A picture no larger than its back copy has no front copy, and a
 * picture pinned before the front copy existed has none either: the front then shows the back's copy.
 *
 * A file goes when its note goes ([gone]: whatever takes a note away, the store's save deletes the files no note has
 * any more, both copies), and files that no note has are swept as the app starts ([orphans], in both folders). Pure.
 */
object PinImage {
    /** The folder in the app's external files folder (the back reads it as `<external files>/pins/<name>`). */
    const val DIR = "pins"

    /** The front's larger copies: a folder of the app's PRIVATE files (Context.filesDir), never the external one. */
    const val FRONT_DIR = "pins-front"
    /**
     * The front's copy at most this long on its longest side: about twice the front screen's width (1200 px), so a
     * picture fills the screen sharply and can be zoomed into a little before it softens, at a megabyte or so a file.
     */
    const val FRONT_MAX = 2400
    /** The front's copy: JPEG at this quality (WebP, lossy, for a picture with see-through parts, as the back's copy). */
    const val FRONT_QUALITY = 92

    /** The notes page's picture place (rear/PinFace's PIC_*), in the back's pixels: a stored picture is never larger. */
    const val MAX_W = 478
    const val MAX_H = 428

    /**
     * Pictures shared at once (SEND_MULTIPLE): one note each, for the first [MOST_AT_ONCE]; the rest are not pinned. Each
     * is read as it is shared (a tenth of a second or so), and nine of them would hold the pin back for a second.
     */
    const val MOST_AT_ONCE = 3

    /** A file younger than this with no note is left by the sweep: a picture being read, or one waiting in the place prompt. */
    const val YOUNG_MS = 10 * 60_000L

    private val NAME = Regex("""(\d{1,19})-(\d{1,5})x(\d{1,5})\.(jpg|webp)""")

    /** The file's name for a picture read at [at] (wall ms) and stored at [w] x [h]; [alpha]: it has see-through parts (WebP). */
    fun name(at: Long, w: Int, h: Int, alpha: Boolean): String = "$at-${w}x$h.${if (alpha) "webp" else "jpg"}"

    /** Whether [name] is one of ours: a plain name of that form, never a path (nothing from outside picks a file). */
    fun valid(name: String?): Boolean = name != null && NAME.matches(name)

    /** The size [name] says the picture is stored at; null for a name that is not one of ours. */
    fun size(name: String?): Pair<Int, Int>? {
        val m = name?.let { NAME.matchEntire(it) } ?: return null
        val w = m.groupValues[2].toInt(); val h = m.groupValues[3].toInt()
        return if (w > 0 && h > 0) w to h else null
    }

    /** "image/jpeg" or "image/webp", for [name]. */
    fun mime(name: String): String = if (name.endsWith(".webp")) "image/webp" else "image/jpeg"

    /**
     * The size a picture of [w] x [h] (as it stands, turned by its own orientation) is stored at: fitted into [maxW] x
     * [maxH] in its own shape, NEVER LARGER than it is (a small picture stays as it is); null for one with no size.
     */
    fun fit(w: Int, h: Int, maxW: Int = MAX_W, maxH: Int = MAX_H): Pair<Int, Int>? {
        if (w <= 0 || h <= 0) return null
        val s = minOf(1.0, maxW.toDouble() / w, maxH.toDouble() / h)
        return (w * s).roundToInt().coerceIn(1, maxOf(1, minOf(w, maxW))) to (h * s).roundToInt().coerceIn(1, maxOf(1, minOf(h, maxH)))
    }

    /**
     * The size the FRONT's copy of a picture of [w] x [h] is kept at: its longest side [FRONT_MAX] at most, in its own
     * shape, never larger than it is; null for one with no size.
     */
    fun fitFront(w: Int, h: Int, most: Int = FRONT_MAX): Pair<Int, Int>? = fit(w, h, most, most)

    /** Whether a front copy of [frontW] x [frontH] is worth keeping beside a back copy of [backW] x [backH]: only when it is larger. */
    fun frontWorth(frontW: Int, frontH: Int, backW: Int, backH: Int): Boolean = frontW > backW || frontH > backH

    /**
     * The sizes a picture of [w] x [h] is made smaller through, on its way to [toW] x [toH]: halves while a half is still
     * at least the size wanted, then the size wanted itself (the last of the list). A filtered scale takes four pixels
     * into one at most, so a picture taken down by more than half in one step loses fine lines in places and keeps
     * them in others; by halves every pixel counts. Empty where it is the size wanted already.
     */
    fun halvings(w: Int, h: Int, toW: Int, toH: Int): List<Pair<Int, Int>> {
        if (w <= 0 || h <= 0 || toW <= 0 || toH <= 0 || (w == toW && h == toH)) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var cw = w; var ch = h
        while (cw / 2 >= toW && ch / 2 >= toH && (cw / 2 > toW || ch / 2 > toH)) { cw /= 2; ch /= 2; out += cw to ch }
        out += toW to toH
        return out
    }

    /** The picture files [before] had that no note of [after] has: they go with their notes. */
    fun gone(before: List<Pin>, after: List<Pin>): Set<String> {
        val kept = after.mapNotNullTo(HashSet()) { it.image }
        return before.mapNotNullTo(LinkedHashSet()) { it.image?.takeIf { n -> n !in kept && valid(n) } }
    }

    /**
     * Which of the files in the folder ([files]: name to last change, wall ms) no note of [pins] has and nothing else
     * keeps ([keep]: the place prompt's waiting picture), and that are older than [youngMs] at [now] (a picture being
     * read right now has no note yet). Only names of ours: anything else in the folder is left alone.
     */
    fun orphans(files: List<Pair<String, Long>>, pins: List<Pin>, keep: Set<String>, now: Long, youngMs: Long = YOUNG_MS): List<String> {
        val held = pins.mapNotNullTo(HashSet()) { it.image } + keep
        return files.filter { (name, changed) -> valid(name) && name !in held && now - changed >= youngMs }.map { it.first }
    }
}
