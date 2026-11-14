package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint

/**
 * The pinned notes on the back: OUR page for them, looking as Xiaomi's "Pinned message" page looks
 * (measured: NoticePop), since Xiaomi's own is under the cover now and never seen. Its warm ground, the mark and
 * "Pinned message" at the head, the time it was pinned in a chip, the note large in cream, the bin bottom right. With
 * more than one note: which of how many, where the handle is, and a swipe sideways goes from one to the next.
 *
 * In rear pixels of a 976x596 panel; to scale on any other.
 */
object PinFace {
    /** The bin: its centre and radius, for the finger as for the brush. */
    const val BIN_X = 876f
    const val BIN_Y = 496f
    const val BIN_R = 56f

    /** Whether a tap at [x], [y] (rear pixels of a 976x596 panel) is on the bin, generously. */
    fun onBin(x: Float, y: Float): Boolean = (x - BIN_X) * (x - BIN_X) + (y - BIN_Y) * (y - BIN_Y) <= (BIN_R + 14f) * (BIN_R + 14f)

    /** "09:41" for one pinned today, "12 Nov" for an older one. */
    fun stamp(at: Long, now: Long): String {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = now
        val day = c.get(java.util.Calendar.DAY_OF_YEAR); val year = c.get(java.util.Calendar.YEAR)
        c.timeInMillis = at
        return if (c.get(java.util.Calendar.DAY_OF_YEAR) == day && c.get(java.util.Calendar.YEAR) == year) {
            "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        } else "${c.get(java.util.Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(java.util.Calendar.MONTH)]}"
    }

    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val ground = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val head = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()
    private var typed = false

    /**
     * Draws note [index] of [count] ([text], pinned at [stamp]) over the whole canvas. With
     * [count] 0: the page says there is nothing pinned, and how to pin. [placed]: a note for a
     * place (service/PlaceNotes), which has a map pin in its chip, before the time.
     */
    @Synchronized
    fun draw(c: Canvas, w: Float, h: Float, text: String, stamp: String, index: Int, count: Int, placed: Boolean = false) {
        val u = h / 596f
        if (!typed) {
            typed = true
            head.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
            words.typeface = Typeface.create(Typeface.DEFAULT, 400, false)
            small.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        }
        val accent = NoticePop.PIN
        ground.shader = LinearGradient(0f, 0f, 0f, h, NoticePop.shades(accent), NoticePop.stops(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, ground)
        val gold = NoticePop.head(accent)
        val cream = NoticePop.ink(accent)

        // The head: the mark (a bookmark with two lines on it) and "Pinned message", centred on x 512.
        head.textSize = 40f * u; head.color = gold
        val title = "Pinned message"
        val tw = head.measureText(title)
        // With nothing pinned the page is three lines and no note: all of them about the middle of what he sees
        // (x 636, NoticePop.CENTRE). With a note it keeps Xiaomi's own places (x 512), the page it is a copy of.
        val mid = if (count <= 0) NoticePop.CENTRE else 512f
        var x = mid * u - (36f * u + 15f * u + tw) / 2f
        path.rewind()
        val mx = x; val my = 59f * u; val mw = 36f * u; val mh = 43f * u; val r = 7f * u
        path.addRoundRect(mx, my, mx + mw, my + mh * 0.82f, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
        path.moveTo(mx, my + mh * 0.8f); path.lineTo(mx, my + mh); path.lineTo(mx + mw / 2f, my + mh * 0.8f); path.lineTo(mx + mw, my + mh); path.lineTo(mx + mw, my + mh * 0.8f); path.close()
        fill.color = gold
        c.drawPath(path, fill)
        stroke.color = NoticePop.shade(accent, 120f); stroke.strokeWidth = 4.2f * u
        c.drawLine(mx + 8f * u, my + 12f * u, mx + mw - 8f * u, my + 12f * u, stroke)
        c.drawLine(mx + 8f * u, my + 21f * u, mx + mw - 16f * u, my + 21f * u, stroke)
        c.drawText(title, x + 36f * u + 15f * u, (80f + 40f * 0.36f) * u, head)

        if (count <= 0) {
            // Set as a pop-up's message is (its demibold, near its size), with its one line of help under it at a
            // size that reads at arm's length (they were 46 px regular and 24 px, about x 512: left of the middle).
            words.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            words.color = cream; words.textSize = 62f * u
            c.drawText("Nothing pinned", mid * u, 296f * u, words)
            words.typeface = Typeface.create(Typeface.DEFAULT, 400, false)
            small.color = gold; small.alpha = 200; small.textSize = 32f * u
            c.drawText("Select text, then Pin to back", mid * u, 352f * u, small)
            small.alpha = 255
            return
        }

        // The time it was pinned, in its chip, top right.
        small.textSize = 34f * u
        val sw = small.measureText(stamp)
        val mark = if (placed) PLACE_W * u else 0f
        rect.set(920f * u - sw - 44f * u - mark, 54f * u, 920f * u, 108f * u)
        fill.color = 0xFF2C2120.toInt()
        c.drawRoundRect(rect, 27f * u, 27f * u, fill)
        small.color = 0xFF988C8A.toInt()
        c.drawText(stamp, rect.centerX() + mark / 2f, 93f * u, small)
        if (placed) {
            // A map pin: a drop with a hole in it, in the chip's own grey.
            val px = rect.left + 22f * u + PLACE_W * u / 2f - 6f * u; val py = 76f * u; val pr = 10f * u
            path.rewind()
            path.addCircle(px, py, pr, Path.Direction.CW)
            path.moveTo(px - pr * 0.86f, py + pr * 0.5f); path.lineTo(px, py + pr * 2.1f); path.lineTo(px + pr * 0.86f, py + pr * 0.5f); path.close()
            fill.color = 0xFF988C8A.toInt()
            c.drawPath(path, fill)
            fill.color = 0xFF2C2120.toInt()
            c.drawCircle(px, py, pr * 0.42f, fill)
        }

        // The note: the pin's size for a short one, smaller as it runs to more lines.
        words.color = cream
        val (size, broken) = NoticePop.fit(text, SIZES, ROOM * u) { s, t -> words.textSize = s * u; words.measureText(t) }
        words.textSize = size * u
        val widest = broken.maxOfOrNull { words.measureText(it) } ?: 0f
        val cx = maxOf(512f * u, 322f * u + widest / 2f)
        var base = if (broken.size == 1) 250f else 250f - (SIZES[0] - size) * 0.55f
        for (line in broken) { c.drawText(line, cx, base * u, words); base += size * 1.2f }

        // The bin, bottom right.
        fill.color = 0x38FFFFFF
        c.drawCircle(BIN_X * u, BIN_Y * u, BIN_R * u, fill)
        stroke.color = 0xFFFFFFFF.toInt(); stroke.strokeWidth = 4.6f * u
        val bx = BIN_X * u; val by = BIN_Y * u; val s = u
        c.drawLine(bx - 21f * s, by - 15f * s, bx + 21f * s, by - 15f * s, stroke)
        path.rewind()
        path.moveTo(bx - 8f * s, by - 15f * s); path.lineTo(bx - 6f * s, by - 23f * s); path.lineTo(bx + 6f * s, by - 23f * s); path.lineTo(bx + 8f * s, by - 15f * s)
        path.moveTo(bx - 16f * s, by - 15f * s); path.lineTo(bx - 13f * s, by + 22f * s); path.lineTo(bx + 13f * s, by + 22f * s); path.lineTo(bx + 16f * s, by - 15f * s)
        c.drawPath(path, stroke)
        c.drawLine(bx - 5f * s, by - 4f * s, bx - 5f * s, by + 12f * s, stroke)
        c.drawLine(bx + 5f * s, by - 4f * s, bx + 5f * s, by + 12f * s, stroke)

        // Which of how many, where the pin page has its handle; the handle itself for one alone.
        if (count > 1) {
            small.textSize = 22f * u; small.color = cream; small.alpha = 200
            c.drawText("${index + 1} / $count", 636f * u, 584f * u, small)
            small.alpha = 255
        } else {
            fill.color = cream; fill.alpha = 200
            rect.set(604f * u, 576f * u, 668f * u, 581f * u)
            c.drawRoundRect(rect, 3f * u, 3f * u, fill)
            fill.alpha = 255
        }
    }

    private val SIZES = floatArrayOf(90f, 70f, 56f, 46f)
    private const val ROOM = 470f
    /** The room a place's mark takes in the chip. */
    private const val PLACE_W = 30f
}
