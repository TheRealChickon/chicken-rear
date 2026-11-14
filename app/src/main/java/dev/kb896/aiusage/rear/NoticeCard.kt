package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import dev.kb896.aiusage.data.PanelTheme

/**
 * A notification's card, on the list pulled down over the back and as the banner: see "the card"
 * below for how it looks and why. In panel units, [rect] one card's height. Also the round clear
 * button, which keeps the older made-up glass ([pane]).
 */
class NoticeCard(
/** The panel's face; nothing is drawn before there is one. */
private val face: () -> PanelType.Face?,
/** The display's own corner, in panel units: a card up against the top follows it. */
private val corner: () -> Float,
/** An app's icon by package, or null for none. */
private val icon: (String) -> Bitmap?,
) {
    /**
     * The panel's look for a notification: the theme, the text's colour, and the card's body. Under
     * ORIGINAL that is the notification's ground; under GLASS there is no ground to choose (the
     * settings offer none), and the card is clear glass in Glass settings' tint, as every other pane
     * (SheetTint.of).
     */
    class Look(val theme: PanelTheme, val text: Int, val ground: Int, val glassTint: Int)

    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val heading = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val ground = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dest = RectF()
    private val shape = android.graphics.Path()
    /**
     * The face the paints were set to. ONLY WHEN IT CHANGES: PanelType's set clears the weight
     * and puts it back, and a paint then builds its font again — twice a row, every frame, it
     * was what held the list well under the display's rate while it moved.
     */
    private var faced: PanelType.Face? = null
    /** Lines already cut to their room, by text, paint and width: cut once, not every frame. */
    private val fitted = HashMap<String, String>()
    private var sheen: android.graphics.Shader? = null
    private var edge: android.graphics.Shader? = null
    private var gradientFor = 0L
    private var rowGlow: android.graphics.Shader? = null
    private var rowGlowFor = 0L
    private val gradientShift = android.graphics.Matrix()

    private fun line(text: String, paint: TextPaint, room: Float, which: Char): String {
        val key = "$which${room.toInt()}|$text"
        fitted[key]?.let { return it }
        if (fitted.size > 256) fitted.clear()
        return fit(text, paint, room).also { fitted[key] = it }
    }

    /**
     * [square]: the corners that run off the display stay square, as the panel's strip's did.
     * [screenTop]: where the card's top is on the rear, in panel units, for its top-right corner.
     */
    /** [body]: how much of the message line shows, 0 to 1, for it fading in or out. */
    // -- the card ------------------------------------------------------------
    //
    // The first one was poor next to Xiaomi's own pinned-note page: a grey slab, the
    // app's name in capitals, a heavy headline, the icon as big as the card. This one is quiet:
    // near-black glass with a hairline of light round it, the app's real icon small in the first
    // line with the time, no name beside it, then the title, and the message in full on two lines,
    // in the system's own face (MiSans on this phone, the clock's). A notification with buttons
    // has them as pills along its foot, where NoticeShade knows them (actionAt), and one line of
    // message over them.

    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillWord = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var typed = false
    private val clock = java.util.Calendar.getInstance()
    private val wrapped = HashMap<String, Pair<String, String>>()

    private fun type() {
        if (typed) return
        typed = true
        // The pop-up's two weights (the music, call and timer pages'): who it is from in the regular, the message in the demibold.
        small.typeface = Typeface.create(Typeface.DEFAULT, 400, false)
        title.typeface = Typeface.create(Typeface.DEFAULT, 400, false)
        body.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        pillWord.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        small.textSize = 16.5f
        title.textSize = 19f
        body.textSize = 23f
        pillWord.textSize = 16.5f
    }

    /** "19:32" for today's, "12 Nov" for older ones; "" for an unknown time. */
    private fun timeOf(ms: Long): String {
        if (ms <= 0L) return ""
        val now = System.currentTimeMillis()
        clock.timeInMillis = now
        val day = clock.get(java.util.Calendar.DAY_OF_YEAR)
        val year = clock.get(java.util.Calendar.YEAR)
        clock.timeInMillis = ms
        return if (clock.get(java.util.Calendar.DAY_OF_YEAR) == day && clock.get(java.util.Calendar.YEAR) == year) {
            "%02d:%02d".format(clock.get(java.util.Calendar.HOUR_OF_DAY), clock.get(java.util.Calendar.MINUTE))
        } else {
            "${clock.get(java.util.Calendar.DAY_OF_MONTH)} ${MONTHS[clock.get(java.util.Calendar.MONTH)]}"
        }
    }

    /** [text] on two lines of [room]: broken at a space where there is one, the second cut with an ellipsis. */
    private fun twoLines(text: String, room: Float): Pair<String, String> {
        val key = "${room.toInt()}|$text"
        wrapped[key]?.let { return it }
        if (wrapped.size > 128) wrapped.clear()
        val flat = text.replace('\n', ' ').trim()
        val n = body.breakText(flat, true, room, null)
        val out = if (n >= flat.length) flat to "" else {
            val cut = flat.lastIndexOf(' ', n).takeIf { it > n / 2 } ?: n
            flat.substring(0, cut).trimEnd() to cutTo(flat.substring(cut).trimStart(), body, room)
        }
        return out.also { wrapped[key] = it }
    }

    /**
     * Draws one notification as a card filling [rect] (panel units; laid out for a row's 143, and
     * to scale in a lower one, the banner's). [alpha] fades all of it. [square] and [screenTop] are
     * the first row's corner under the display's own. [body] fades the message alone.
     */
    fun draw(canvas: Canvas, rect: RectF, item: NoticeShade.Item, alpha: Float, square: Boolean, screenTop: Float, look: Look, body: Float = 1f) {
        type()
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val r = CARD_RADIUS
        shape.rewind()
        val fitted = GlassCard.innerRadius(corner(), MARGIN, r)
        val away = ((screenTop - MARGIN) / CORNER_EASE).coerceIn(0f, 1f)
        val tr = if (square) 0f else maxOf(r, fitted) + (r - maxOf(r, fitted)) * away
        if (square) shape.addRoundRect(rect, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, r, r), android.graphics.Path.Direction.CW)
        else shape.addRoundRect(rect, floatArrayOf(r, r, tr, tr, r, r, r, r), android.graphics.Path.Direction.CW)
        // THE POP-UP'S LOOK, A ROW HIGH: black, the app's own
        // colour glowing up from the row's foot (NoticePop's shades), the app's real icon large at its left, who it
        // is from in that colour and the message in the pop-up's demibold. No time, no app name. An app with no
        // colour of its own is a quiet grey, as its pop-up is.
        val accent = NoticePop.accentOf(item.color)
        val glowKey = (accent.toLong() shl 32) or rect.height().toLong()
        if (glowKey != rowGlowFor || rowGlow == null) {
            rowGlowFor = glowKey
            rowGlow = android.graphics.LinearGradient(
                0f, 0f, 0f, rect.height(),
                intArrayOf(NoticePop.shade(accent, 0f), NoticePop.shade(accent, 200f), NoticePop.shade(accent, GLOW_FOOT)), floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        gradientShift.setTranslate(rect.left, rect.top)
        rowGlow!!.setLocalMatrix(gradientShift)
        ground.style = Paint.Style.FILL
        ground.color = 0xFFFFFFFF.toInt()
        ground.alpha = a
        ground.shader = rowGlow
        canvas.drawPath(shape, ground)
        // A hairline of light round it, so one row ends where the next begins.
        val size = (rect.width().toLong() shl 32) or rect.height().toLong()
        if (size != gradientFor || edge == null) {
            gradientFor = size
            edge = android.graphics.LinearGradient(
                0f, 0f, 0f, rect.height(),
                (CARD_EDGE_TOP shl 24) or 0xFFFFFF, (CARD_EDGE_BOTTOM shl 24) or 0xFFFFFF,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        edge!!.setLocalMatrix(gradientShift)
        ground.shader = edge
        ground.style = Paint.Style.STROKE
        ground.strokeWidth = 1.2f
        canvas.drawPath(shape, ground)
        ground.shader = null
        ground.style = Paint.Style.FILL

        // Laid out for 143 high; a lower card (the banner) is the same, closer.
        val k = (rect.height() / NoticeShade.ROW_H).coerceAtMost(1f)
        val buttons = minOf(item.actions.size, NoticeShade.ACTIONS).takeIf { k >= 0.99f } ?: 0
        // Nothing of it outside its own shape, whatever a word's width comes to.
        canvas.save()
        canvas.clipPath(shape)
        val bmp = icon(item.pkg)
        // The room for the icon and the words: the whole row, or what is over the buttons.
        val roomTop = rect.top
        val roomBottom = if (buttons > 0) rect.top + NoticeShade.PILL_TOP - 6f else rect.bottom
        val mid = (roomTop + roomBottom) / 2f
        // Which app, by its own icon, as the pop-up says it: large, at the row's left, the first thing seen.
        val logo = (if (buttons > 0) LOGO_OVER_BUTTONS else LOGO) * k
        val left0 = rect.left + NoticeShade.TEXT_X
        if (bmp != null) {
            dest.set(left0, mid - logo / 2f, left0 + logo, mid + logo / 2f)
            iconPaint.alpha = a
            canvas.drawBitmap(bmp, null, dest, iconPaint)
        }
        // The words stand beside the icon; the buttons keep their place from the row's edge, where a finger finds them (NoticeShade.actionAt).
        val textLeft = if (bmp != null) left0 + logo + LOGO_GAP * k else left0
        val left = left0
        val right = rect.right - 24f
        val room = right - textLeft
        // Who it is from: not written when it is only the app's name and the icon already says it; an app with
        // no icon to be had is still named.
        val who = if (bmp == null) item.title.ifBlank { item.app } else item.title.takeUnless { it.equals(item.app, ignoreCase = true) }.orEmpty()
        val showText = item.text.isNotBlank() && body > 0f
        val words = if (showText && buttons == 0) twoLines(item.text, room) else null
        val lines = if (!showText) 0 else if (words == null || words.second.isEmpty()) 1 else 2
        // The block of who and the message, in the middle of its room.
        val whoH = if (who.isNotEmpty()) WHO_STEP * k else 0f
        val blockH = whoH + lines * MESSAGE_STEP * k
        var y = mid - blockH / 2f
        if (who.isNotEmpty()) {
            title.color = NoticePop.head(accent)
            title.alpha = a
            canvas.drawText(line(who, title, room, 't'), textLeft, y + WHO_STEP * k * 0.76f, title)
            y += whoH
        }
        if (showText) {
            this.body.color = NoticePop.ink(accent)
            this.body.alpha = (a * body).toInt()
            if (words == null) {
                canvas.drawText(cutTo(item.text.replace('\n', ' '), this.body, room), textLeft, y + MESSAGE_STEP * k * 0.76f, this.body)
            } else {
                canvas.drawText(words.first, textLeft, y + MESSAGE_STEP * k * 0.76f, this.body)
                if (words.second.isNotEmpty()) canvas.drawText(words.second, textLeft, y + MESSAGE_STEP * k * 1.76f, this.body)
            }
        }
        val ink = look.text
        // Its buttons, where NoticeShade.actionAt finds them.
        for (i in 0 until buttons) {
            val x = left + i * (NoticeShade.PILL_W + NoticeShade.PILL_GAP)
            dest.set(x, rect.top + NoticeShade.PILL_TOP, x + NoticeShade.PILL_W, rect.top + NoticeShade.PILL_TOP + NoticeShade.PILL_H)
            pill.color = ink
            pill.alpha = (a * 0.13f).toInt()
            canvas.drawRoundRect(dest, NoticeShade.PILL_H / 2f, NoticeShade.PILL_H / 2f, pill)
            pillWord.color = ink
            pillWord.alpha = (a * 0.92f).toInt()
            canvas.drawText(fit(item.actions[i], pillWord, NoticeShade.PILL_W - 24f), dest.centerX(), dest.centerY() + 6f, pillWord)
        }
        canvas.restore()
    }

    private fun pane(canvas: Canvas, shape: android.graphics.Path, rect: RectF, alpha: Float, l: Look) {
        val tint = l.glassTint
        ground.shader = null
        ground.style = Paint.Style.FILL
        ground.color = tint
        ground.alpha = (Color.alpha(tint) * alpha).toInt()
        canvas.drawPath(shape, ground)
        // Made once for the shape's size, at its origin, and moved to each.
        val size = (rect.width().toLong() shl 32) or rect.height().toLong()
        if (size != gradientFor || sheen == null) {
            gradientFor = size
            sheen = android.graphics.LinearGradient(
                0f, 0f, rect.width() * 0.45f, rect.height(),
                (SHEEN_ALPHA shl 24) or 0xFFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP,
            )
            edge = android.graphics.LinearGradient(
                0f, 0f, 0f, rect.height(),
                (EDGE_TOP_ALPHA shl 24) or 0xFFFFFF, (EDGE_BOTTOM_ALPHA shl 24) or 0xFFFFFF,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        gradientShift.setTranslate(rect.left, rect.top)
        sheen!!.setLocalMatrix(gradientShift)
        edge!!.setLocalMatrix(gradientShift)
        ground.color = 0xFFFFFFFF.toInt()
        ground.alpha = (255 * alpha).toInt()
        ground.shader = sheen
        canvas.drawPath(shape, ground)
        ground.shader = edge
        ground.style = Paint.Style.STROKE
        ground.strokeWidth = EDGE_WIDTH
        canvas.drawPath(shape, ground)
        ground.shader = null
        ground.style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glow: android.graphics.Shader? = null
    private var light: android.graphics.Shader? = null
    private var glowFor = 0f
    private val glowShift = android.graphics.Matrix()

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f
    }

    /**
     * The clear button: a circle of the cards' own glass (or, under ORIGINAL, their ground) with
     * an X in the text's colour, no words. Its own card-sized shaders would be made again for a
     * circle every frame, so it keeps a NoticeCard of its own.
     */
    fun drawClear(canvas: Canvas, c: NoticeShade.Clear, look: Look, bin: Boolean = false) {
        val r = c.size / 2f
        // A soft bright halo round it, fading out, so it reads over the rows and the blur alike.
        val glowR = r + CLEAR_GLOW
        if (glowFor != c.size) {
            glowFor = c.size
            glow = android.graphics.RadialGradient(
                0f, 0f, glowR,
                intArrayOf((CLEAR_GLOW_ALPHA shl 24) or 0xFFFFFF, (CLEAR_GLOW_ALPHA shl 24) or 0xFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, r / glowR, 1f), android.graphics.Shader.TileMode.CLAMP,
            )
            light = android.graphics.RadialGradient(
                0f, -r * 0.45f, r * 1.3f,
                (CLEAR_LIGHT_ALPHA shl 24) or 0xFFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP,
            )
        }
        glowShift.setTranslate(c.cx, c.cy)
        glow!!.setLocalMatrix(glowShift)
        light!!.setLocalMatrix(glowShift)
        glowPaint.shader = glow
        glowPaint.alpha = (255 * c.alpha).toInt()
        canvas.drawCircle(c.cx, c.cy, glowR, glowPaint)
        dest.set(c.cx - r, c.cy - r, c.cx + r, c.cy + r)
        shape.rewind()
        shape.addOval(dest, android.graphics.Path.Direction.CW)
        if (look.theme.isGlass) pane(canvas, shape, dest, c.alpha, look)
        else {
            ground.shader = null
            ground.style = Paint.Style.FILL
            ground.color = look.ground or (0xFF shl 24)
            ground.alpha = (255 * c.alpha).toInt()
            canvas.drawPath(shape, ground)
        }
        // Lit from within, brightest under its top and fading down.
        glowPaint.shader = light
        canvas.drawPath(shape, glowPaint)
        glowPaint.shader = null
        val arm = r * 0.32f
        crossPaint.color = look.text
        crossPaint.alpha = (255 * c.alpha).toInt()
        if (bin) {
            // A bin, for a screenshot's delete (ShotPolaroid): its lid and handle, its body, two ribs.
            val top = c.cy - arm * 0.7f
            canvas.drawLine(c.cx - arm, top, c.cx + arm, top, crossPaint)
            canvas.drawLine(c.cx - arm * 0.35f, top - arm * 0.3f, c.cx + arm * 0.35f, top - arm * 0.3f, crossPaint)
            val l = c.cx - arm * 0.72f; val rr = c.cx + arm * 0.72f; val b = c.cy + arm
            canvas.drawLine(l, top, l + arm * 0.1f, b, crossPaint)
            canvas.drawLine(rr, top, rr - arm * 0.1f, b, crossPaint)
            canvas.drawLine(l + arm * 0.1f, b, rr - arm * 0.1f, b, crossPaint)
            canvas.drawLine(c.cx - arm * 0.22f, top + arm * 0.35f, c.cx - arm * 0.22f, b - arm * 0.3f, crossPaint)
            canvas.drawLine(c.cx + arm * 0.22f, top + arm * 0.35f, c.cx + arm * 0.22f, b - arm * 0.3f, crossPaint)
        } else {
            canvas.drawLine(c.cx - arm, c.cy - arm, c.cx + arm, c.cy + arm, crossPaint)
            canvas.drawLine(c.cx - arm, c.cy + arm, c.cx + arm, c.cy - arm, crossPaint)
        }
    }

    /** One line cut to [width] by measuring it, with an ellipsis: for where the cut must hold. */
    private fun cutTo(text: String, paint: TextPaint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        val n = paint.breakText(text, true, width - paint.measureText("…"), null)
        return text.substring(0, n.coerceAtLeast(0)).trimEnd() + "…"
    }

    /** One line, cut to [width]. */
    private fun fit(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text.replace('\n', ' '), paint, width, TextUtils.TruncateAt.END).toString()

    companion object {
        /** RearDialView's GLASS_NOTICE_RADIUS and GLASS_NOTICE_ALPHA, GlassCard.MIN: the panel's strip's. */
        const val RADIUS = 22f
        /** The notification card's own: rounder than the panel's strip, as the back's own corners are. */
        const val CARD_RADIUS = 30f
        private const val CARD_TINT = 0xD60C0C0E.toInt()
        private const val CARD_EDGE_TOP = 0x3D
        private const val CARD_EDGE_BOTTOM = 0x12
        /** The app's icon in a card's first line. */
        private const val ICON = 26f
        /** The app's icon in a row of the list, with no buttons under it and with; the room between it and the words. */
        const val LOGO = 62f
        const val LOGO_OVER_BUTTONS = 44f
        private const val LOGO_GAP = 20f
        /** From who it is from to the message, and from one line of the message to the next. */
        private const val WHO_STEP = 27f
        private const val MESSAGE_STEP = 30f
        /** How far down the pop-up's page (0..596) the row's foot glows: not to its full brightness, three rows stand together. */
        private const val GLOW_FOOT = 430f
        private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        const val GLASS_ALPHA = 0xB0
        const val MARGIN = 14f
        /** The panel's own notification's height (RearDialView.NOTICE_H): a quarter of it. */
        const val HEIGHT = NoticeShade.ROW_H
        /** The made-up glass: its sheen at the top left, its edge lit at the top and fading down. */
        private const val SHEEN_ALPHA = 0x2E
        private const val EDGE_TOP_ALPHA = 0x66
        private const val EDGE_BOTTOM_ALPHA = 0x14
        private const val EDGE_WIDTH = 1.5f
        /** The clear button's halo: how far past its edge, and how bright at it; its light within. */
        private const val CLEAR_GLOW = 18f
        private const val CLEAR_GLOW_ALPHA = 0x40
        private const val CLEAR_LIGHT_ALPHA = 0x55
        /** Over how far down from the top a card's top-right corner turns from the display's into its own. */
        private const val CORNER_EASE = 60f
    }
}
