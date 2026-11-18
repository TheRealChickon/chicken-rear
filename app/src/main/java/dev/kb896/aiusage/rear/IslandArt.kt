package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * THE MESSAGE ISLAND DRAWN (MessageIsland's timeline and formula), one picture at a time on any canvas: the shell's layer on
 * the back (ShellNotices), the settings' preview, the tests' bitmaps.
 *
 * What it draws, in this order: the island's layer (the black pill and its soft shadow, the open page's veil, the sweep,
 * Xiaomi's light round the outline), then the app's own icon on its one path from the pill to the page's head, the pill's
 * two lines (who it is from, the message cut to one line) and the page's words (NoticePop's own layout, in white). The
 * pill's words and the page's are two layouts that never turn into each other: each fades on its own (MessageIsland.at).
 *
 * ON A HARDWARE CANVAS the layer is ONE RUNTIME SHADER (MessageIsland.AGSL) over the part that can be anything but clear
 * (MessageIsland.bounds): its uniforms set in place, nothing made per picture. ON A SOFTWARE CANVAS (the tests, the PC's
 * renders) the same formula in Kotlin (MessageIsland.pixel) into a bitmap made once per size.
 *
 * [set] lays one message out (its lines cut and measured once); [draw] draws it at one [MessageIsland.Frame]. One thread.
 */
class IslandArt {
    /** One message laid out, in page units (the 976 x 596 rear). */
    class Words(
        /** The pill's two lines (who, the message), or one ([pillTwo] false: [pillMessage] alone, in the message's type). */
        val pillSender: String, val pillMessage: String, val pillTwo: Boolean,
        /** The page's head line (who; empty: none), its message lines and their size. */
        val pageHead: String, val pageLines: List<String>, val pageSize: Float,
        val headW: Float,
        val layout: MessageIsland.Layout,
        /** The pill's who in the app's colour made bright enough for black, its message in the pale ink (NoticePop's). */
        val senderColour: Int, val inkColour: Int,
        /** The app's colour, sRGB 0..1 (the wash where the face cannot take the colour itself). */
        val accent: FloatArray,
        val icon: Bitmap?,
    )

    var words: Words? = null
        private set
    /** The open page's look (MessageIsland.Look): set with the words. */
    var look: MessageIsland.Look = MessageIsland.Look.SWEEP
        private set

    /** The pill's width for the words laid out (MessageIsland.at takes it); the widest-but-one before any. */
    val pillW: Float get() = words?.layout?.pillW ?: MessageIsland.PILL_MAX_W

    private var typed = false
    private val sender = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val message = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pageWho = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pageWords = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dest = RectF()
    private val box = FloatArray(4)
    private val uni = MessageIsland.uniformArrays()

    // The hardware canvas: the shader, made at its first picture (warm() makes it before anything is shown).
    private var shader: RuntimeShader? = null
    private val shaderPaint = Paint()

    // The software canvas: one bitmap and its pixels per size, the formula's scratch.
    private var soft: Bitmap? = null
    private var softPx: IntArray? = null
    private val px = FloatArray(4)
    private val scratch = FloatArray(6)
    private val src = Rect()
    private val dst = Rect()

    fun prepareHardware() {
        type()
        if (shader == null) {
            val program = RuntimeShader(MessageIsland.AGSL)
            shaderPaint.shader = program
            shader = program
        }
    }

    fun clearMessage() { words = null }

    private fun type() {
        if (typed) return
        typed = true
        // The pop-up's two weights (NoticePop): regular for who, the music, call and timer pages' demibold for the message.
        val regular = Typeface.create(Typeface.DEFAULT, 400, false)
        val demi = Typeface.create(Typeface.DEFAULT, 600, false)
        sender.typeface = regular; pageWho.typeface = regular
        message.typeface = demi; pageWords.typeface = demi
        sender.textSize = MessageIsland.PILL_SENDER
        message.textSize = MessageIsland.PILL_MESSAGE
        pageWho.textSize = NoticePop.HEAD_SIZE
    }

    /**
     * One message: [from] (who; empty when the icon says it), [text] (the message; empty: the head's words are the message,
     * as on the full page), the app's [colour] and [icon]; [look] the open page's. Measured and cut once here.
     */
    fun set(from: String, text: String, colour: Int, icon: Bitmap?, look: MessageIsland.Look) {
        type()
        this.look = look
        val accent = NoticePop.accentOf(colour)
        val who = from.replace('\n', ' ').trim()
        val said = text.replace('\n', ' ').trim()
        val iconW = if (icon != null) MessageIsland.PILL_ICON + MessageIsland.PILL_GAP else 0f
        // THE PILL: who over one line of the message, each cut to the pill's room; one line when there is only one to say.
        val room = MessageIsland.PILL_TEXT_MAX + (MessageIsland.PILL_ICON + MessageIsland.PILL_GAP - iconW)
        val two = who.isNotEmpty() && said.isNotEmpty()
        val pillSender = if (two) NoticePop.cut(who, room) { sender.measureText(it) } else ""
        val pillMessage = NoticePop.cut(said.ifEmpty { who }, room) { message.measureText(it) }
        // THE PAGE, as the full page lays its block out (NoticePop.draw): the message as large as it stays whole at.
        val headOnly = said.isEmpty()
        val pageText = said.ifEmpty { who }
        val (size, lines) = NoticePop.fit(pageText, NoticePop.SIZES, NoticePop.ROOM) { s, t -> pageWords.textSize = s; pageWords.measureText(t) }
        pageWords.textSize = size
        val head = if (headOnly) "" else NoticePop.cut(who, NoticePop.ROOM - NoticePop.HEAD_ICON - NoticePop.HEAD_GAP) { pageWho.measureText(it) }
        val hasHead = icon != null || head.isNotEmpty()
        val at = NoticePop.layout(lines.size, size, hasHead)
        val headW = (if (icon != null) NoticePop.HEAD_ICON else 0f) + (if (icon != null && head.isNotEmpty()) NoticePop.HEAD_GAP else 0f) + pageWho.measureText(head)
        var widest = 0f
        for (l in lines) widest = max(widest, pageWords.measureText(l))
        val layout = MessageIsland.layout(
            if (two) sender.measureText(pillSender) else 0f, message.measureText(pillMessage), icon != null,
            headW, widest, at.headCy, at.firstBase, at.step, at.top, at.bottom,
        )
        words = Words(
            pillSender, pillMessage, two, head, lines, size, headW, layout,
            NoticePop.head(accent), NoticePop.ink(accent),
            floatArrayOf(Color.red(accent) / 255f, Color.green(accent) / 255f, Color.blue(accent) / 255f), icon,
        )
    }

    /**
     * ONE PICTURE: the island at [f] on [c], [w] x [h] canvas px for the 976 x 596 back. Draws over what is there (the
     * caller clears a layer of its own first). Nothing is made here but the software path's bitmap at its first use.
     */
    fun draw(c: Canvas, w: Float, h: Float, f: MessageIsland.Frame) {
        val wd = words ?: return
        if (!f.shown) return
        val u = h / MessageIsland.H
        val l = wd.layout
        // The layer: the pill's black, the page's veil, the sweep and Xiaomi's light.
        MessageIsland.bounds(f, box)
        val x0 = floor(box[0] * u).coerceIn(0f, w); val y0 = floor(box[1] * u).coerceIn(0f, h)
        val x1 = ceil(box[2] * u).coerceIn(0f, w); val y1 = ceil(box[3] * u).coerceIn(0f, h)
        if (x1 > x0 && y1 > y0) {
            if (c.isHardwareAccelerated) gpu(c, x0, y0, x1, y1, u, f, l, wd.accent) else cpu(c, w.toInt(), h.toInt(), x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt(), u, f, l, wd.accent)
        }
        // The words and the icon, in page units.
        val save = c.save()
        c.scale(u, u)
        val p = f.p
        // ONE ICON, ONE PATH: from its place in the pill (scaled with it) to the page's head (render.py's content()).
        val scale = if (p == 0f) f.compact else 1f
        val icon = wd.icon
        if (icon != null && f.alpha > 0.002f) {
            val ix = MessageIsland.CX + (l.pillIconX - MessageIsland.CX) * scale
            val x = ix + (l.pageIconX - ix) * p
            val cy = f.pillCy + (l.headCy - f.pillCy) * p
            val s = MessageIsland.PILL_ICON * scale + (NoticePop.HEAD_ICON - MessageIsland.PILL_ICON * scale) * p
            dest.set(x, cy - s / 2f, x + s, cy + s / 2f)
            iconPaint.alpha = (255f * f.alpha).toInt().coerceIn(0, 255)
            c.drawBitmap(icon, null, dest, iconPaint)
        }
        // The pill's lines, scaled about its middle as it condenses.
        val pillA = f.alpha * f.pillInk
        if (pillA > 0.002f) {
            val inner = c.save()
            c.scale(scale, scale, MessageIsland.CX, f.pillCy)
            if (wd.pillTwo) {
                sender.color = wd.senderColour; sender.alpha = (255f * pillA).toInt().coerceIn(0, 255)
                c.drawText(wd.pillSender, l.pillTextX, f.pillCy + MessageIsland.PILL_SENDER_BASE, sender)
            }
            message.color = wd.inkColour; message.alpha = (255f * pillA).toInt().coerceIn(0, 255)
            c.drawText(wd.pillMessage, l.pillTextX, f.pillCy + if (wd.pillTwo) MessageIsland.PILL_MESSAGE_BASE else MessageIsland.PILL_ONE_BASE, message)
            c.restoreToCount(inner)
        }
        // The page's words: white, NoticePop's block, only once it is open; settling up by at most 4 units as they come.
        val pageA = f.alpha * f.pageInk
        if (pageA > 0.002f) {
            val a = (255f * pageA).toInt().coerceIn(0, 255)
            val settle = 4f * (1f - f.pageInk)
            if (wd.pageHead.isNotEmpty()) {
                pageWho.color = Color.WHITE; pageWho.alpha = a
                val tx = MessageIsland.CX - wd.headW / 2f + (if (icon != null) NoticePop.HEAD_ICON + NoticePop.HEAD_GAP else 0f)
                c.drawText(wd.pageHead, tx, l.headCy + NoticePop.HEAD_SIZE * 0.36f + settle, pageWho)
            }
            pageWords.textSize = wd.pageSize
            pageWords.color = Color.WHITE; pageWords.alpha = a
            var base = l.firstBase + settle
            for (index in wd.pageLines.indices) {
                c.drawText(wd.pageLines[index], MessageIsland.CX, base, pageWords)
                base += l.step
            }
        }
        c.restoreToCount(save)
    }

    /**
     * Everything the island will draw, drawn once on [c] while its layer is still hidden: the shader is compiled, the
     * icon's texture and the words' glyphs are made, so the first pictures seen, and the opening, have nothing to make.
     */
    fun warm(c: Canvas, w: Float, h: Float, scratchFrame: MessageIsland.Frame) {
        MessageIsland.at(1000f, pillW, scratchFrame, sweeps = look.sweep)
        draw(c, w, h, scratchFrame)
        MessageIsland.at(MessageIsland.IN_MS + 200f, pillW, scratchFrame, sweeps = look.sweep)
        draw(c, w, h, scratchFrame)
        MessageIsland.at(MessageIsland.SWEEP_FROM + 900f, pillW, scratchFrame, sweeps = look.sweep)
        draw(c, w, h, scratchFrame)
    }

    private fun gpu(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float, f: MessageIsland.Frame, l: MessageIsland.Layout, wash: FloatArray) {
        if (shader == null) prepareHardware()
        val sh = checkNotNull(shader)
        MessageIsland.uniforms(f, look, l, wash, u, uni)
        sh.setFloatUniform("u", uni[0][0])
        sh.setFloatUniform("shape", uni[1][0], uni[1][1], uni[1][2], uni[1][3])
        sh.setFloatUniform("shapeR", uni[2][0], uni[2][1], uni[2][2], uni[2][3])
        sh.setFloatUniform("lit", uni[3][0], uni[3][1])
        sh.setFloatUniform("veil", uni[4][0], uni[4][1], uni[4][2], uni[4][3])
        sh.setFloatUniform("washC", uni[5][0], uni[5][1], uni[5][2])
        sh.setFloatUniform("box", uni[6][0], uni[6][1], uni[6][2], uni[6][3])
        sh.setFloatUniform("sweepS", uni[7][0], uni[7][1])
        c.drawRect(x0, y0, x1, y1, shaderPaint)
    }

    private fun cpu(c: Canvas, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, u: Float, f: MessageIsland.Frame, l: MessageIsland.Layout, wash: FloatArray) {
        if (w <= 0 || h <= 0) return
        val bmp = soft?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { soft = it; softPx = IntArray(w * h) }
        val pixels = softPx!!
        val bw = x1 - x0
        for (y in y0 until y1) for (x in x0 until x1) {
            MessageIsland.pixel((x + 0.5f) / u, (y + 0.5f) / u, u, f, look, l, wash, px, scratch)
            val a = px[3]
            pixels[(y - y0) * bw + (x - x0)] = if (a <= 0f) 0 else {
                // Premultiplied out of the formula; the bitmap is given its straight colour.
                fun ch(v: Float) = ((v / a).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                ((a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (ch(px[0]) shl 16) or (ch(px[1]) shl 8) or ch(px[2])
            }
        }
        bmp.eraseColor(0)
        bmp.setPixels(pixels, 0, bw, x0, y0, bw, y1 - y0)
        src.set(x0, y0, x1, y1); dst.set(x0, y0, x1, y1)
        c.drawBitmap(bmp, src, dst, null)
    }
}
