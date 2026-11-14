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
 * A running timer (or stopwatch), as the whole back: Xiaomi's own rear timer page, copied. The
 * rule: no bars or small cards over the moon;
 * anything live is a full page in Xiaomi's rear style, then back to the moon.
 *
 * XIAOMI'S PAGE, READ FROM ITS OWN TEMPLATE (com.xiaomi.subscreencenter, assets/smart_assistant/timer).
 * One unit is the page's width / 336; on this 976x596 panel:
 * - the ground: its dark blue picture, and over it a sheet of lighter blue "water" whose top edge
 *   stands as high as the time that is left (full at the start, gone at the end, 543 px of way)
 *   and drifts sideways, once across in two seconds;
 * - a small hourglass at the top, centred on x 630;
 * - the time left in large white demibold figures, each in a place of its own (157 px figures for
 *   minutes and seconds, 113 px with hours), two round dots between;
 * - two round buttons, 163 px, centred at (470, 453) and (790, 453): pause or go on (a light
 *   disc with a dark glyph), and close (a dim disc with a hairline ring and a cross).
 * - when it has run out: its name in the figures' place and one close button in the middle.
 *
 * The pictures are Xiaomi's own, read from its package on the phone ([ART], XiaomiArt); without
 * them the page is drawn plain. Xiaomi's figures flip over as they change; here they change in
 * place. In rear pixels of a 976x596 panel; to scale on any other.
 */
object TimerFace {
    /**
     * One timer as the page shows it. [endAt]: when it runs out (wall clock), for one that runs;
     * [left]: what was left when it was paused; [total]: its whole length (0 if unknown: the water
     * then stands still). [up]: a stopwatch, counting up from [endAt].
     */
    data class Model(
        val key: String, val pkg: String, val name: String,
        val endAt: Long, val left: Long, val total: Long,
        val paused: Boolean, val over: Boolean, val up: Boolean = false,
        val actions: List<String> = emptyList(),
    )

    enum class Side { MAIN, CLOSE }
    enum class Act { PAUSE, RESUME, CANCEL, DISMISS }

    const val BTN = 162.7f
    const val BTN_CY = 453.1f
    const val MAIN_CX = 469.9f
    const val CLOSE_CX = 790.1f
    const val CENTRE = 629.5f
    private const val U = 976f / 336f

    const val ART_BG = "assets/bg.webp"
    const val ART_WATER = "assets/ripple.webp"
    const val ART_GLASS = "assets/sandglass.webp"
    const val ART_DISC = "assets/btnBg.webp"
    const val ART_RING = "assets/circle.webp"
    const val ART_CROSS = "assets/x.webp"
    val ART = listOf(ART_BG, ART_WATER, ART_GLASS, ART_DISC, ART_RING, ART_CROSS)

    /** Which button a tap at [x], [y] (rear pixels of a 976x596 panel) is on, for a timer that is [over] or not. */
    fun side(x: Float, y: Float, over: Boolean): Side? {
        val r = BTN / 2f + 18f
        fun on(cx: Float) = (x - cx) * (x - cx) + (y - BTN_CY) * (y - BTN_CY) <= r * r
        return when {
            over -> if (on(CENTRE)) Side.CLOSE else null
            on(MAIN_CX) -> Side.MAIN
            on(CLOSE_CX) -> Side.CLOSE
            else -> null
        }
    }

    fun act(side: Side, m: Model): Act = when {
        side == Side.CLOSE && m.over -> Act.DISMISS
        side == Side.CLOSE -> Act.CANCEL
        m.paused -> Act.RESUME
        else -> Act.PAUSE
    }

    /** The seconds the figures show at [now]: what is left (rounded up, as Xiaomi's), or what has run for a stopwatch. */
    fun seconds(m: Model, now: Long): Long = when {
        m.up -> ((if (m.paused) m.left else now - m.endAt) / 1000L).coerceAtLeast(0L)
        m.paused -> (m.left + 999L) / 1000L
        else -> ((m.endAt - now).coerceAtLeast(0L) + 999L) / 1000L
    }

    /** How far the water has sunk, 0 (full) to 1 (gone). */
    fun sunk(m: Model, now: Long): Float {
        if (m.total <= 0L || m.up) return 0.5f
        val left = if (m.paused) m.left else (m.endAt - now).coerceAtLeast(0L)
        return (1f - left.toFloat() / m.total).coerceIn(0f, 1f)
    }

    /** Which of a notification's buttons ([words]) does [act], by what it says; -1 for none (a clock other than Xiaomi's). */
    fun actionFor(act: Act, words: List<String>): Int {
        val want = when (act) {
            Act.PAUSE -> listOf("pause")
            Act.RESUME -> listOf("resume", "continue", "start")
            Act.CANCEL -> listOf("stop", "cancel", "reset", "delete")
            Act.DISMISS -> listOf("dismiss", "stop", "close")
        }
        return words.indexOfFirst { w -> want.any { w.contains(it, ignoreCase = true) } }
    }

    private val ground = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pic = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val figure = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private var typed = false

    /** Draws the page over the whole canvas (opaque). [pressed] and [sincePress]: a button just pressed. */
    @Synchronized
    fun draw(c: Canvas, w: Float, h: Float, m: Model, now: Long, art: Map<String, Bitmap>, pressed: Side? = null, sincePress: Long = Long.MAX_VALUE) {
        val u = h / 596f
        if (!typed) { typed = true; figure.typeface = Typeface.create(Typeface.DEFAULT, 600, false) }

        // The ground, and the water over it.
        val bg = art[ART_BG]
        if (bg != null) { rect.set(0f, 0f, w, h); c.drawBitmap(bg, null, rect, pic.apply { alpha = 255 }) } else {
            ground.shader = LinearGradient(0f, 0f, 0f, h, 0xFF000A22.toInt(), 0xFF00247A.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, ground)
        }
        // The water (rear/Fluid, the renderer every fluid page shares): its surface as far down as the time has run.
        if (!m.over && art[ART_WATER] != null) {
            rect.set(0f, 0f, w, h)
            Fluid.draw(c, rect, 0f, (sunk(m, now) * 187f * U + 29f) * u, now, art[ART_WATER], u, moving = !m.paused)
        }

        figure.color = Color.WHITE
        if (m.over) {
            figure.textSize = 30f * U * u
            c.drawText(cut(m.name.ifBlank { "Timer" }, 193f * U * u, figure), CENTRE * u, 57.5f * U * u - figure.ascent(), figure)
            close(c, CENTRE, u, art, press(Side.CLOSE, pressed, sincePress))
            return
        }

        // The hourglass, and the figures each in its place.
        art[ART_GLASS]?.let { glass ->
            val s = 22f * U * u
            rect.set(CENTRE * u - s / 2f, 24.5f * U * u, CENTRE * u + s / 2f, 24.5f * U * u + s)
            c.drawBitmap(glass, null, rect, pic.apply { alpha = 255 })
        }
        val secs = seconds(m, now)
        val hours = secs >= 3600L
        val places = if (hours) HOURS else SHORT
        val left = (CENTRE - (if (hours) 182f else 152f) * U / 2f) * u
        val top = (if (hours) 50.5f else 42.5f) * U * u
        figure.textSize = (if (hours) 39f else 54f) * U * u
        val digits = if (hours) "%02d%02d%02d".format((secs / 3600) % 100, secs / 60 % 60, secs % 60) else "%02d%02d".format((secs / 60) % 100, secs % 60)
        for (i in digits.indices) c.drawText(digits, i, i + 1, left + places[i] * U * u, top - figure.ascent(), figure)
        fill.color = Color.WHITE
        val dot = (if (hours) 3f else 4f) * U * u
        for (x in if (hours) floatArrayOf(58.5f, 123.5f) else floatArrayOf(76f)) for (y in if (hours) floatArrayOf(19f, 33f) else floatArrayOf(26f, 46f)) {
            c.drawCircle(left + x * U * u, top + y * U * u, dot, fill)
        }

        // Pause or go on: a light disc, a dark glyph. Close: a dim disc, its ring, a cross.
        val k = press(Side.MAIN, pressed, sincePress)
        val r = BTN / 2f * u * k
        val cx = MAIN_CX * u; val cy = BTN_CY * u
        val disc = art[ART_DISC]
        if (disc != null) { rect.set(cx - r, cy - r, cx + r, cy + r); c.drawBitmap(disc, null, rect, pic.apply { alpha = 230 }) } else { fill.color = 0xE6FFFFFF.toInt(); c.drawCircle(cx, cy, r, fill) }
        fill.color = 0xE6101828.toInt()
        if (m.paused) {
            val p = android.graphics.Path()
            p.moveTo(cx - r * 0.17f, cy - r * 0.27f); p.lineTo(cx + r * 0.27f, cy); p.lineTo(cx - r * 0.17f, cy + r * 0.27f); p.close()
            fill.strokeJoin = Paint.Join.ROUND; fill.style = Paint.Style.FILL_AND_STROKE; fill.strokeWidth = r * 0.07f
            c.drawPath(p, fill)
            fill.style = Paint.Style.FILL
        } else for (side in intArrayOf(-1, 1)) {
            rect.set(cx + side * r * 0.2f - r * 0.07f, cy - r * 0.26f, cx + side * r * 0.2f + r * 0.07f, cy + r * 0.26f)
            c.drawRoundRect(rect, r * 0.05f, r * 0.05f, fill)
        }
        close(c, CLOSE_CX, u, art, press(Side.CLOSE, pressed, sincePress))
    }

    private fun press(side: Side, pressed: Side?, since: Long): Float {
        if (pressed != side) return 1f
        val p = (since.coerceAtLeast(0L).toFloat() / PRESS_MS).coerceIn(0f, 1f)
        return 1f - 0.1f * kotlin.math.sin(p * Math.PI).toFloat()
    }

    private fun close(c: Canvas, centre: Float, u: Float, art: Map<String, Bitmap>, k: Float) {
        val cx = centre * u; val cy = BTN_CY * u; val r = BTN / 2f * u * k
        rect.set(cx - r, cy - r, cx + r, cy + r)
        val disc = art[ART_DISC]
        if (disc != null) c.drawBitmap(disc, null, rect, pic.apply { alpha = 66 }) else { fill.color = 0x42FFFFFF; c.drawCircle(cx, cy, r, fill) }
        art[ART_RING]?.let { c.drawBitmap(it, null, rect, pic.apply { alpha = 255 }) }
        val x = 15f * U * u * k
        rect.set(cx - x, cy - x, cx + x, cy + x)
        val cross = art[ART_CROSS]
        if (cross != null) c.drawBitmap(cross, null, rect, pic.apply { alpha = 255 }) else {
            fill.color = Color.WHITE; fill.strokeWidth = 6f * u; fill.strokeCap = Paint.Cap.ROUND
            c.drawLine(cx - x * 0.5f, cy - x * 0.5f, cx + x * 0.5f, cy + x * 0.5f, fill); c.drawLine(cx + x * 0.5f, cy - x * 0.5f, cx - x * 0.5f, cy + x * 0.5f, fill)
        }
    }

    private fun cut(s: String, room: Float, p: TextPaint): String {
        val one = s.replace('\n', ' ').trim()
        if (p.measureText(one) <= room) return one
        var n = one.length
        while (n > 1 && p.measureText(one.substring(0, n).trimEnd() + "…") > room) n--
        return one.substring(0, NoticePop.whole(one, n)).trimEnd() + "…"
    }

    const val PRESS_MS = 260f
    // The figures' places across their row, in Xiaomi's units: minutes and seconds; and with hours.
    private val SHORT = floatArrayOf(17f, 51f, 101f, 135f)
    private val HOURS = floatArrayOf(13f, 39f, 78f, 104f, 143f, 169f)
}
