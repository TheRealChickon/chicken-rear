package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint

/**
 * A message as it arrives on the back: the whole screen, looking as Xiaomi's own "Pinned message"
 * page looks, in the colour of the app it came from.
 *
 * THE PIN PAGE, MEASURED (a capture of Xiaomi's page, 976x596):
 * - the ground: black at the top, deepening through a dark, redder shade of its colour to the
 *   colour itself glowing along the bottom edge ([SHADES], read off the picture every 120 px);
 * - a head line at y 80: a small mark and "Pinned message" in the colour, medium, 40 px;
 * - the message under it, large (90 px, regular), a pale tint of the colour, centred on x 512;
 * - a clock chip, a bin and a handle, which a message that pops up and goes has no use for.
 *
 * HERE: the head line is who it is from, with the app's own icon where the mark is; the message
 * is the message, smaller as it gets longer, never more than four lines. Nothing else: no app
 * name, no time, no buttons.
 *
 * WHERE IT STANDS AND IN WHAT TYPE. Measured on the rear: the pin page's own places (the
 * head at y 80, the message from y 250, centred on x 512 to 607 by its width) put the words left of
 * the middle of what he sees and in the upper part of it, because the lenses cover the panel's left
 * 296 px: the part he sees runs from x 296 to 976, its middle is x 636. And the message was the
 * pin's 90 px regular, thin and grey. Now ([layout]): icon, sender and message are ONE BLOCK, centred
 * in the part he sees, across and up and down; the message in the demibold of the music, call and
 * timer pages (their titles' weight, near their size), the sender in their regular. The ground, the
 * app's colour glowing up from the bottom and the small real icon are as they were.
 *
 * In rear pixels of a 976x596 panel; drawn to scale on any other. [shade], [ink] and [head] are
 * plain arithmetic, so a test holds them to the measured page.
 */
object NoticePop {
    /** Xiaomi's own pin colour: the head line's, from which every shade of its page follows. */
    const val PIN = 0xFFF2A753.toInt()

    private class Shade(val y: Float, val value: Float, val grey: Float, val turn: Float)

    // Down the pin page: how bright (of the head colour's own brightness), how much of the colour's
    // greyness is left (it grows purer as it darkens), and how far its hue has turned (degrees, away from yellow).
    private val SHADES = arrayOf(
        Shade(0f, 0.012f, 0.00f, 24f), Shade(120f, 0.070f, 0.17f, 24f), Shade(240f, 0.169f, 0.28f, 20.3f),
        Shade(360f, 0.326f, 0.44f, 19.2f), Shade(480f, 0.545f, 0.69f, 14.5f), Shade(550f, 0.747f, 1.00f, 7f),
        Shade(596f, 0.875f, 1.14f, 2.4f),
    )

    /** The ground's colour at [y] (0..596) for a page of [accent]. */
    fun shade(accent: Int, y: Float): Int {
        val hsv = FloatArray(3); Color.colorToHSV(accent, hsv)
        val i = (1 until SHADES.size).firstOrNull { y <= SHADES[it].y } ?: (SHADES.size - 1)
        val a = SHADES[i - 1]; val b = SHADES[i]
        val t = ((y - a.y) / (b.y - a.y)).coerceIn(0f, 1f)
        fun mix(p: Float, q: Float) = p + (q - p) * t
        // An app with no colour of its own (a grey): grey all the way down. Deepened as a colour is, it turned a
        // dark red in the middle of the page (seen in the list's rows, where three stand together).
        if (hsv[1] < 0.12f) return Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * mix(a.grey, b.grey).coerceIn(0f, 1f), (hsv[2] * mix(a.value, b.value)).coerceIn(0f, 1f)))
        val grey = (1f - hsv[1]) * mix(a.grey, b.grey)
        return Color.HSVToColor(floatArrayOf(turned(hsv[0], mix(a.turn, b.turn)), (1f - grey).coerceIn(0f, 1f), (hsv[2] * mix(a.value, b.value)).coerceIn(0f, 1f)))
    }

    /** The ground's colours down the page, and where each stands (for a gradient). */
    fun shades(accent: Int): IntArray = IntArray(SHADES.size) { shade(accent, SHADES[it].y) }
    fun stops(): FloatArray = FloatArray(SHADES.size) { SHADES[it].y / 596f }

    /** A hue turned [by] degrees away from yellow, as a colour deepens: orange towards red, green towards teal. Less for reds, which would go pink. */
    private fun turned(hue: Float, by: Float): Float {
        val fromYellow = ((hue - 60f + 540f) % 360f) - 180f           // -180..180, 0 at yellow
        val room = if (hue < 20f || hue > 300f) 0.4f else 1f
        val out = hue + (if (fromYellow >= 0f) by else -by) * room
        return (out + 360f) % 360f
    }

    /** The head line's colour: the app's own, made bright enough to read on black. */
    fun head(accent: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(accent, hsv)
        // A grey or black "colour" (many apps have none of their own) stays a quiet light grey.
        if (hsv[1] < 0.12f) return Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 0.86f))
        return Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceIn(0.45f, 0.80f), 0.95f))
    }

    /** The message's colour: nearly white, with a little of the app's colour in it (the pin's cream). */
    fun ink(accent: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(accent, hsv)
        if (hsv[1] < 0.12f) return 0xFFE8E8E8.toInt()
        val fromYellow = ((hsv[0] - 60f + 540f) % 360f) - 180f
        val hue = hsv[0] - fromYellow.coerceIn(-11f, 11f)                // a step towards yellow, as the pin's cream is
        return Color.HSVToColor(floatArrayOf((hue + 360f) % 360f, 0.185f, 0.91f))
    }

    /** The colour a page is made from: the app's, or the pin's own warm one when the app gave none. */
    fun accentOf(color: Int): Int = if (color == 0 || Color.alpha(color) == 0) 0xFF9A9A9A.toInt() else color or (0xFF shl 24)

    private val ground = Paint()
    /** A page as last laid out: its ground, the size its words came to, their lines, its head line. */
    private class Kept(val key: String, val size: Float, val broken: List<String>, val head: String, val ground: LinearGradient)
    private val kept = ArrayDeque<Kept>()
    private const val KEEP = 8
    private val who = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dest = RectF()
    private var typed = false

    /**
     * [text] broken into lines no wider than [room], at most [most] of them, the last cut with an ellipsis.
     * A WORD WIDER THAN THE ROOM (a link, a number, text with no spaces at all) is broken across lines, not cut to
     * one (the review's 46: a link was one cut line with three lines of room unused).
     */
    internal fun lines(text: String, most: Int, room: Float, measure: (String) -> Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in text.replace('\n', ' ').trim().split(' ').filter { it.isNotEmpty() }) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (measure(next) <= room) { line = next; continue }
            if (line.isNotEmpty()) out += line
            line = word
            // Too wide by itself: as much of it as fits on each line, the rest on the next.
            while (measure(line) > room && line.length > 1 && out.size < most + 1) {
                var n = line.length - 1
                while (n > 1 && measure(line.substring(0, n)) > room) n--
                // Never half a character; and should one character alone be wider than the room, it is taken whole.
                n = whole(line, n).takeIf { it > 0 } ?: one(line, n + 1)
                if (n >= line.length) break
                out += line.substring(0, n)
                line = line.substring(n)
            }
        }
        if (line.isNotEmpty()) out += line
        if (out.size <= most) return out.map { cut(it, room, measure) }
        val kept = out.take(most).toMutableList()
        kept[most - 1] = cut(kept[most - 1] + " " + out.drop(most).joinToString(" "), room, measure)
        return kept.map { cut(it, room, measure) }
    }

    internal fun cut(s: String, room: Float, measure: (String) -> Float): String {
        if (measure(s) <= room) return s
        var n = s.length
        while (n > 1 && measure(s.substring(0, n).trimEnd() + "…") > room) n--
        return s.substring(0, whole(s, n).takeIf { it > 0 } ?: one(s, n)).trimEnd() + "…"
    }

    /** The end of the first whole character of [s] at or after [n]: one character at least is always kept. */
    private fun one(s: String, n: Int): Int = generateSequence(n.coerceAtLeast(1)) { it + 1 }.first { it >= s.length || whole(s, it) == it }

    /**
     * [n] characters of [s], or fewer so that NO CHARACTER IS CUT IN HALF: not between the two halves of an emoji
     * (a lone half draws as a box), and not leaving a joiner or a variation mark hanging at the end.
     */
    internal fun whole(s: String, n: Int): Int {
        var k = n.coerceIn(0, s.length)
        fun inside(k: Int) = k > 0 && k < s.length &&
            (Character.isHighSurrogate(s[k - 1]) || s[k - 1] == '\u200D' || s[k] == '\u200D' || s[k] == '\uFE0F')
        while (inside(k)) k--
        return k
    }

    /** Whether [broken] is all of [text]: nothing was cut away (a text that itself ends in "…" is whole). */
    internal fun whole(text: String, broken: List<String>): Boolean {
        fun bare(t: String) = t.filterNot { it.isWhitespace() }
        return bare(broken.joinToString("")) == bare(text)
    }

    /**
     * The largest of [sizes] at which [text] is whole in as many lines as that size is given (one at the first, one
     * more at each smaller one), and its lines there; the smallest, cut, if it is whole at none. WHETHER IT FITS IS
     * WHETHER ANYTHING WAS CUT, not whether the last line ends in "…": "Call me…" was drawn a third smaller than
     * "Call me" (the review's 46).
     */
    internal fun fit(text: String, sizes: FloatArray, room: Float, measure: (Float, String) -> Float): Pair<Float, List<String>> {
        var size = sizes.last(); var broken = emptyList<String>()
        for ((i, s) in sizes.withIndex()) {
            broken = lines(text, i + 1, room) { measure(s, it) }
            size = s
            if (whole(text, broken)) break
        }
        return size to broken
    }

    /**
     * Draws the page on a canvas [w] by [h]: its ground over everything (opaque), who it is from,
     * the message. [icon] is the app's, or null for none.
     */
    @Synchronized
    fun draw(c: Canvas, w: Float, h: Float, from: String, message: String, color: Int, icon: Bitmap?) {
        val accent = accentOf(color)
        val u = h / 596f
        if (!typed) {
            typed = true
            // The music, call and timer pages' two weights: their titles' demibold for the message, their regular for who.
            who.typeface = Typeface.create(Typeface.DEFAULT, 400, false)
            words.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        }
        // WHAT IS THE SAME FROM ONE PICTURE TO THE NEXT IS KEPT (the review's 44): a page that is pulled, turned or
        // thrown out is drawn every frame, and its ground and the breaking of its words into lines (a measuring of
        // every word at up to four sizes) were made again each time. The picture is the same; only not made twice.
        val key = "$w|$h|$accent|${icon != null}|$from\u0000$message"
        val made = kept.firstOrNull { it.key == key } ?: run {
            // With no message the head's words are the message; with no words at all the icon stands alone.
            val headOnly = message.isBlank()
            val text = message.ifBlank { from }
            val (size, broken) = fit(text, SIZES, ROOM * u) { s, t -> words.textSize = s * u; words.measureText(t) }
            who.textSize = HEAD_SIZE * u
            Kept(key, size, broken, if (headOnly) "" else cutHead(from, (ROOM - HEAD_ICON - HEAD_GAP) * u), LinearGradient(0f, 0f, 0f, h, shades(accent), stops(), Shader.TileMode.CLAMP))
                .also { kept.addFirst(it); while (kept.size > KEEP) kept.removeLast() }
        }
        ground.shader = made.ground
        c.drawRect(0f, 0f, w, h, ground)
        val size = made.size
        val broken = made.broken
        val head = made.head
        words.textSize = size * u
        who.textSize = HEAD_SIZE * u
        val hasHead = icon != null || head.isNotEmpty()
        val at = layout(broken.size, size, hasHead)
        val cx = at.cx * u

        // Who it is from, with the app's icon where the pin has its mark: the two together centred.
        val headW = (if (icon != null) HEAD_ICON * u else 0f) + (if (icon != null && head.isNotEmpty()) HEAD_GAP * u else 0f) + who.measureText(head)
        var x = cx - headW / 2f
        if (icon != null) {
            dest.set(x, (at.headCy - HEAD_ICON / 2f) * u, x + HEAD_ICON * u, (at.headCy + HEAD_ICON / 2f) * u)
            c.drawBitmap(icon, null, dest, iconPaint)
            x += (HEAD_ICON + HEAD_GAP) * u
        }
        who.color = head(accent)
        if (head.isNotEmpty()) c.drawText(head, x, (at.headCy + HEAD_SIZE * 0.36f) * u, who)

        // The message, each line centred.
        words.color = ink(accent)
        var base = at.firstBase
        for (line in broken) {
            c.drawText(line, cx, base * u, words)
            base += at.step
        }
    }

    /** Where the block of icon, sender and message stands, in rear pixels of the 976x596 panel. */
    class Layout(val cx: Float, val headCy: Float, val firstBase: Float, val step: Float, val top: Float, val bottom: Float)

    /**
     * The block for a message of [lines] lines at [size], with or without a head line ([head]): centred on the
     * middle of the part of the back he sees ([CENTRE] across, [MIDDLE] down). Its height is the head line (the
     * icon's height), the room under it, and the message from its first line's capitals to its last line's tails.
     */
    fun layout(lines: Int, size: Float, head: Boolean): Layout {
        val wordsH = if (lines <= 0) 0f else size * CAP + (lines - 1) * size * LEADING + size * TAIL
        val headH = if (head) HEAD_ICON + (if (lines > 0) HEAD_BELOW else 0f) else 0f
        val top = MIDDLE - (headH + wordsH) / 2f
        return Layout(CENTRE, top + HEAD_ICON / 2f, top + headH + size * CAP, size * LEADING, top, top + headH + wordsH)
    }

    private fun cutHead(s: String, room: Float): String = cut(s.replace('\n', ' ').trim(), room) { who.measureText(it) }

    private const val HEAD_SIZE = 38f
    const val HEAD_ICON = 46f
    private const val HEAD_GAP = 14f
    /** Between the head line and the message's capitals. */
    private const val HEAD_BELOW = 30f
    /** The part of the back he sees: right of the lenses (296) to the panel's edge (976), top to bottom. */
    const val SEEN_LEFT = 296f
    const val CENTRE = (SEEN_LEFT + 976f) / 2f
    const val MIDDLE = 298f
    /** The message's size by how many lines it runs to: about the call page's name and the music page's title for one. */
    val SIZES = floatArrayOf(66f, 58f, 50f, 44f)
    /** A line's capitals and tails as parts of its size, and from one line to the next. */
    private const val CAP = 0.72f
    private const val TAIL = 0.21f
    private const val LEADING = 1.2f
    /** The widest a line may be: clear of the lenses on the left and of the rounded corner on the right, the same each side. */
    const val ROOM = 600f
}
