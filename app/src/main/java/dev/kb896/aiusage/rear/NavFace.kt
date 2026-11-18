package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

object NavFace {
    data class Segment(val length: Int, val color: Int)
    data class Point(val position: Int, val color: Int)

    data class Model(
        /** The notification's key: route updates keep it (PageRow.navigation). */
        val key: String,
        val pkg: String,
        /** Android 16's short critical text, the words of the status bar's chip: Maps' distance to the turn, as far as is known. */
        val short: String = "",
        val title: String = "",
        val text: String = "",
        /** The notification's sub text: Maps' time of arrival. Shown in the pill only where it fits. */
        val eta: String = "",
        /** Progress over its max, 0 to 1; null when the notification gives none (no tracker, nothing muted). */
        val fraction: Float? = null,
        val progressMax: Int? = null,
        /** The route's parts, as the app gave them (Notification.ProgressStyle). A colour with no alpha is "the default" ([ink]). */
        val segments: List<Segment> = emptyList(),
        val points: List<Point> = emptyList(),
        /** ProgressStyle's own word: the part behind the progress is set apart from the part ahead. */
        val styledByProgress: Boolean = true,
        val appIcon: Bitmap? = null,
        val maneuver: Bitmap? = null,
        val tracker: Bitmap? = null,
        val startIcon: Bitmap? = null,
        val endIcon: Bitmap? = null,
        val demo: Boolean = false,
    )

    // The reference's places, measured on it and mapped onto the panel (its column centred on 636).
    const val LEFT = 346f
    const val RIGHT = 926f
    const val ICON = 110f
    const val ICON_TOP = 62f
    const val PILL_H = 52f
    const val PILL_CY = 96f
    const val PILL_TEXT = 30f
    /** The pill's widest (small, as the reference's); a longer sub text is not shown whole (it would be cut). */
    const val PILL_MAX = 300f
    /** The pill's room on either side of its words. */
    const val PILL_PAD = 22f
    const val WORDS = 56f
    const val WORDS_LEAST = 44f
    const val FIRST_BASELINE = 254f
    const val MAX_LINES = 3
    /** The words end over this (the maneuver's top is 400): smaller words before they reach it. */
    const val WORDS_BOTTOM = 396f
    const val BAR_Y = 510f
    const val BAR_H = 18f
    /** Between two segments of the part ahead (Android's own bar has such gaps). */
    const val GAP = 12f
    const val TRACKER = 52f
    const val END_ICON = 40f
    const val MANEUVER_W = 92f
    const val MANEUVER_H = 80f
    /** The maneuver's foot over the bar's middle line. */
    const val MANEUVER_ABOVE = 30f

    /** The ground: dark at the top, greener at the foot, a little light low on the left (the reference's tones). */
    const val TOP = 0xFF0E120F.toInt()
    const val MIDDLE = 0xFF132215.toInt()
    const val BOTTOM = 0xFF1B3418.toInt()
    const val GLOW = 0x5A2D5826
    /** The route behind the tracker: the reference's grey over the green. */
    const val TRAVELLED = 0x26FFFFFF
    /** A segment or point the app left at its default colour, and a plain progress bar's part ahead. */
    const val DEFAULT_INK = 0xFFE3EAE4.toInt()
    const val PILL = 0x14FFFFFF
    const val PILL_INK = 0xFFCBD3CC.toInt()

    /** Between the chip's words and the title: a dot with an en space each side, as wide as the reference's. */
    const val DOT = "\u2002•\u2002"
    /** What goes before the text runs on into it only when it is a distance alone: this short, with a figure in it. */
    const val RUN_ON = 12

    /** The tracker eases from one reported place to the next in this long; it never runs ahead of the app. */
    const val MOTION_MS = 650L
    /**
     * A step of the tracker shorter than this along the bar (panel pixels) is not eased: it is drawn once, in its new
     * place. While driving the route moves a pixel or less per post (a 10 km route is about 17 m a pixel), and easing
     * that would redraw the whole page at the panel's rate for most of every second, for nothing to see.
     */
    const val GLIDE_LEAST_PX = 2f

    /** Made-up data for the settings' preview, a PC render and the debug line: marked "Demo"; no art of its own. */
    fun demo(appIcon: Bitmap? = null, fraction: Float? = 0.55f, maneuver: Bitmap? = null, tracker: Bitmap? = null) = Model(
        key = "navigation-demo", pkg = "com.google.android.apps.maps",
        short = "2 km", title = "Turn right", text = "toward Example Way", eta = "08:16",
        fraction = fraction?.coerceIn(0f, 1f), progressMax = 100,
        // The reference's colours: yellow (slower traffic) ahead of the tracker, then blue.
        segments = listOf(Segment(45, 0xFF1A43DA.toInt()), Segment(30, 0xFFDE9B35.toInt()), Segment(25, 0xFF1A43DA.toInt())),
        appIcon = appIcon, maneuver = maneuver, tracker = tracker, demo = true,
    )

    /**
     * The words as the page sets them: the short text and the title (a dot between), then the text. A head that is a
     * distance alone ("2 km", Maps' title) runs on into the text after a dot, as the reference's line does; a longer
     * one ("2 km • Turn right") ends its line and the text begins the next. Every word as the app gave it.
     */
    fun words(m: Model): String {
        val title = m.title.trim(); val text = m.text.trim(); val short = m.short.trim()
        // The chip's words are left out where the title or the text already says them.
        val lead = short.takeIf { it.isNotEmpty() && !containsWords(title, it) && !containsWords(text, it) }
        val head = listOfNotNull(lead, title.ifEmpty { null }).joinToString(DOT)
        return when {
            head.isEmpty() -> text
            text.isEmpty() -> head
            head.length <= RUN_ON && head.any { it.isDigit() } && '\n' !in head -> head + DOT + text
            else -> head + "\n" + text
        }
    }

    private fun containsWords(text: String, phrase: String): Boolean {
        var start = text.indexOf(phrase)
        while (start >= 0) {
            val end = start + phrase.length
            if ((start == 0 || !text[start - 1].isLetterOrDigit()) &&
                (end == text.length || !text[end].isLetterOrDigit())) return true
            start = text.indexOf(phrase, start + 1)
        }
        return false
    }

    /**
     * What the pill says, [width] measuring words in its paint: the sub text whole where it fits; else its last part
     * after Maps' own separator ("15 min · 4.2 km · 08:31" gives "08:31"); else nothing. Never shortened by a cut.
     */
    fun pill(eta: String, width: (String) -> Float): String? {
        val whole = eta.trim().replace('\n', ' ')
        if (whole.isEmpty()) return null
        fun fits(s: String) = width(s) + 2f * PILL_PAD <= PILL_MAX
        if (fits(whole)) return whole
        val last = whole.split('·', '•', '|').last().trim()
        return last.takeIf { it.isNotEmpty() && it != whole && fits(it) }
    }

    /**
     * Interpolates only between two places the app reported, over [MOTION_MS]; after the last one it stands. A new
     * route (another key) is not slid to: the tracker is where it is said to be; nor is a step under [GLIDE_LEAST_PX].
     */
    class TrackerMotion {
        private var key: String? = null
        private var from = Float.NaN
        private var target = Float.NaN
        private var began = 0L

        @Synchronized
        fun reset() {
            key = null
            from = Float.NaN
            target = Float.NaN
            began = 0L
        }

        @Synchronized
        fun update(model: Model, now: Long) {
            val next = model.fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: Float.NaN
            if (key == model.key && (target == next || target.isNaN() && next.isNaN())) return
            val previous = valueLocked(now)
            val barWidth = barRight(model) - barLeft(model)
            val glide = kotlin.math.abs(next - previous) * barWidth >= GLIDE_LEAST_PX
            from = if (key == model.key && glide) previous else next
            target = next
            key = model.key
            began = now
        }

        @Synchronized
        fun value(now: Long): Float? = valueLocked(now).takeIf { it.isFinite() }

        @Synchronized
        fun position(model: Model, now: Long): Float {
            val reported = model.fraction ?: return Float.NaN
            // The next notification may have arrived while the page thread was taking its snapshot.
            return if (key == model.key && target == reported) valueLocked(now) else reported
        }

        private fun valueLocked(now: Long): Float {
            if (from == target || !target.isFinite() || now - began >= MOTION_MS) return target
            val t = ((now - began).toFloat() / MOTION_MS).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)
            return from + (target - from) * eased
        }

        @Synchronized
        fun moving(now: Long): Boolean = from.isFinite() && target.isFinite() && from != target && now - began < MOTION_MS
    }

    /** One stretch of the bar, in fractions of its length, and its colour. */
    data class Piece(val from: Float, val to: Float, val color: Int)

    /**
     * The bar as drawn, behind and ahead of [at] (the tracker's place, 0 to 1, or null for none): pure, for the tests.
     * Empty when there is nothing to draw (no max, or neither segments nor progress). With the app's word that it is
     * styled by progress, the part behind [at] is one muted stretch and the segments are cut at [at]; otherwise every
     * segment in its own colour.
     */
    fun pieces(m: Model, at: Float?): List<Piece> {
        val segments = m.segments.filter { it.length > 0 }
        val max = (m.progressMax?.takeIf { it > 0 } ?: segments.sumOf { it.length }.takeIf { it > 0 }) ?: return emptyList()
        val p = at?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        if (segments.isEmpty() && p == null) return emptyList()
        // A plain progress bar is one segment in the default colour (as Android draws a ProgressStyle with none).
        val route = if (segments.isEmpty()) listOf(Piece(0f, 1f, 0)) else {
            var start = 0L
            segments.mapNotNull { s ->
                val a = start; start += s.length
                val f = (a.toFloat() / max).coerceIn(0f, 1f); val t = (start.toFloat() / max).coerceIn(0f, 1f)
                if (t > f) Piece(f, t, s.color) else null
            }
        }
        if (!m.styledByProgress || p == null) return route
        val out = ArrayList<Piece>()
        if (p > 0f) out += Piece(0f, p, TRAVELLED)
        for (r in route) if (r.to > p) out += Piece(maxOf(r.from, p), r.to, r.color)
        return out
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val white = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    private var ground: Pair<Shader, Shader>? = null

    private val medium by lazy { Typeface.create(Typeface.DEFAULT, 500, false) }
    private val demibold by lazy { Typeface.create(Typeface.DEFAULT, 600, false) }
    private var preparedFor: Model? = null
    private var laidFor: String? = null
    private var laid: StaticLayout? = null
    private var pillText: String? = null
    private var pillWidth = 0f
    private var barPieces: Array<Piece> = emptyArray()
    private var pointPositions: FloatArray = floatArrayOf()
    private var pointColors: IntArray = intArrayOf()

    // Whether a maneuver bitmap is dark ink, worked out once per bitmap.
    private var inkFor: Bitmap? = null
    private var inkDark = false

    private fun barLeft(m: Model) = LEFT + if (m.startIcon != null) END_ICON + 16f else 0f
    private fun barRight(m: Model) = RIGHT - if (m.endIcon != null) END_ICON + 16f else 0f

    private fun prepare(m: Model) {
        if (preparedFor === m) return
        val previous = preparedFor
        if (previous == null || previous.short != m.short || previous.title != m.title || previous.text != m.text) {
            val text = words(m)
            if (text != laidFor) {
                laid = if (text.isEmpty()) null else layout(text)
                laidFor = text
            }
        }
        if (previous == null || previous.eta != m.eta) {
            small.typeface = medium
            small.textSize = PILL_TEXT
            pillText = pill(m.eta) { small.measureText(it) }
            pillWidth = pillText?.let { small.measureText(it) + 2f * PILL_PAD } ?: 0f
        }
        if (previous == null || previous.progressMax != m.progressMax || previous.segments != m.segments ||
            previous.points != m.points || (previous.fraction == null) != (m.fraction == null)
        ) {
            barPieces = pieces(m, null).toTypedArray()
            if (barPieces.isEmpty() && m.fraction != null && m.progressMax != null) {
                barPieces = arrayOf(Piece(0f, 1f, 0))
            }
            val max = m.progressMax ?: m.segments.sumOf { it.length }
            val points = m.points.filter { it.position > 0 && it.position < max }
            pointPositions = FloatArray(points.size) { points[it].position.toFloat() / max }
            pointColors = IntArray(points.size) { ink(points[it].color) }
        }
        preparedFor = m
    }

    fun draw(c: Canvas, w: Float, h: Float, m: Model, fraction: Float? = m.fraction) =
        drawFrame(c, w, h, m, fraction ?: Float.NaN)

    /** Shared painter. Animated frames reuse text, colors and route geometry prepared from the latest model. */
    @Synchronized
    fun drawFrame(c: Canvas, w: Float, h: Float, m: Model, fraction: Float) {
        prepare(m)
        val saved = c.save()
        c.scale(w / 976f, h / 596f)
        val (linear, glow) = ground ?: Pair<Shader, Shader>(
            LinearGradient(0f, 0f, 0f, 596f, intArrayOf(TOP, MIDDLE, BOTTOM), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP),
            RadialGradient(470f, 640f, 620f, GLOW, GLOW and 0x00FFFFFF, Shader.TileMode.CLAMP),
        ).also { ground = it }
        fill.shader = linear; c.drawRect(0f, 0f, 976f, 596f, fill)
        fill.shader = glow; c.drawRect(0f, 0f, 976f, 596f, fill)
        fill.shader = null

        // The app's own icon, top left; "Demo" beside it on made-up data.
        bitmap(c, m.appIcon, LEFT, ICON_TOP, ICON, ICON)
        small.typeface = medium
        if (m.demo) {
            small.textSize = 28f; small.color = 0x99FFFFFF.toInt()
            c.drawText("Demo", if (m.appIcon != null) LEFT + ICON + 22f else LEFT, ICON_TOP + ICON / 2f + 10f, small)
        }

        // The time of arrival in its pill, top right ([pill]).
        small.textSize = PILL_TEXT; small.color = PILL_INK
        pillText?.let { eta ->
            val pw = pillWidth
            fill.color = PILL
            rect.set(RIGHT - pw, PILL_CY - PILL_H / 2f, RIGHT, PILL_CY + PILL_H / 2f)
            c.drawRoundRect(rect, PILL_H / 2f, PILL_H / 2f, fill)
            c.drawText(eta, RIGHT - pw + PILL_PAD, PILL_CY + PILL_TEXT * 0.36f, small)
        }

        // The words: up to three lines, smaller down to WORDS_LEAST before the last is cut.
        laid?.let { block ->
            c.save()
            c.translate(LEFT, FIRST_BASELINE - block.getLineBaseline(0))
            block.draw(c)
            c.restore()
        }

        // The maneuver over the bar's right end: the app's own picture; dark ink is shown light on this ground.
        m.maneuver?.let { arrow ->
            if (arrow !== inkFor) { inkFor = arrow; inkDark = darkInk(arrow) }
            image.colorFilter = if (inkDark) white else null
            bitmap(c, arrow, RIGHT - MANEUVER_W, BAR_Y - MANEUVER_ABOVE - MANEUVER_H, MANEUVER_W, MANEUVER_H)
            image.colorFilter = null
        }

        if (barPieces.isNotEmpty()) {
            val left = barLeft(m)
            val width = barRight(m) - left
            val progress = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else Float.NaN
            val travelled = if (m.styledByProgress && progress.isFinite()) progress else 0f
            if (travelled > 0f) {
                barPiece(c, left, width * travelled, TRAVELLED, gapBefore = false, gapAfter = travelled < 1f)
            }
            for (i in barPieces.indices) {
                val piece = barPieces[i]
                if (piece.to <= travelled) continue
                val start = maxOf(piece.from, travelled)
                barPiece(c, left + width * start, width * (piece.to - start), ink(piece.color),
                    gapBefore = start > 0f, gapAfter = i < barPieces.lastIndex)
            }
            for (i in pointPositions.indices) {
                fill.color = pointColors[i]
                c.drawCircle(left + width * pointPositions[i], BAR_Y, BAR_H / 2f + 2f, fill)
            }
            bitmap(c, m.startIcon, LEFT, BAR_Y - END_ICON / 2f, END_ICON, END_ICON)
            bitmap(c, m.endIcon, RIGHT - END_ICON, BAR_Y - END_ICON / 2f, END_ICON, END_ICON)
            if (progress.isFinite()) bitmap(c, m.tracker, left + width * progress - TRACKER / 2f, BAR_Y - TRACKER / 2f, TRACKER, TRACKER)
        }
        c.restoreToCount(saved)
    }

    private fun barPiece(c: Canvas, left: Float, width: Float, color: Int, gapBefore: Boolean, gapAfter: Boolean) {
        // A fixed gap at both ends used to erase short, supplied traffic segments.
        val inset = minOf(GAP / 2f, width / 4f)
        fill.color = color
        rect.set(left + if (gapBefore) inset else 0f, BAR_Y - BAR_H / 2f,
            left + width - if (gapAfter) inset else 0f, BAR_Y + BAR_H / 2f)
        c.drawRoundRect(rect, BAR_H / 2f, BAR_H / 2f, fill)
    }

    /** A colour with no alpha is ProgressStyle's "default" (Notification.COLOR_DEFAULT): the neutral ink here, never invisible. */
    fun ink(color: Int): Int = if (Color.alpha(color) == 0) DEFAULT_INK else color

    private fun layout(text: String): StaticLayout {
        words.typeface = demibold
        words.color = Color.WHITE
        words.textSize = WORDS
        fun fits(b: StaticLayout) = b.lineCount <= MAX_LINES && FIRST_BASELINE - b.getLineBaseline(0) + b.height <= WORDS_BOTTOM
        var block = build(text, Int.MAX_VALUE)
        while (!fits(block) && words.textSize > WORDS_LEAST) {
            words.textSize -= 2f
            block = build(text, Int.MAX_VALUE)
        }
        if (block.lineCount > MAX_LINES) block = build(text, MAX_LINES)
        return block
    }

    private fun build(text: String, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, words, (RIGHT - LEFT).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()

    /** Whether [b] is dark ink (the average light of its solid pixels under 40%): such an arrow would vanish on the ground. */
    fun darkInk(b: Bitmap): Boolean = runCatching {
        if (b.isRecycled || b.width <= 0 || b.height <= 0) return false
        val step = maxOf(1, maxOf(b.width, b.height) / 48)
        var sum = 0.0; var n = 0
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val px = b.getPixel(x, y)
                if (Color.alpha(px) > 128) { sum += (0.2126 * Color.red(px) + 0.7152 * Color.green(px) + 0.0722 * Color.blue(px)) / 255.0; n++ }
                x += step
            }
            y += step
        }
        n > 0 && sum / n < 0.4
    }.getOrDefault(false)

    private fun bitmap(c: Canvas, bitmap: Bitmap?, x: Float, y: Float, w: Float, h: Float) {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val k = minOf(w / bitmap.width, h / bitmap.height)
        val dw = bitmap.width * k; val dh = bitmap.height * k
        rect.set(x + (w - dw) / 2f, y + (h - dh) / 2f, x + (w + dw) / 2f, y + (h + dh) / 2f)
        c.drawBitmap(bitmap, null, rect, image)
    }
}
