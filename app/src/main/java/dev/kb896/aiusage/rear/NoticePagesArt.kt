package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.abs

/**
 * What NoticePages shows, drawn: each notification by the pop-up's own painter (NoticePop), in the rear's own
 * pixels as the pop-up is, so a page here and its pop-up are the same picture; the pile comes down from the top
 * with the pull, as the pop-up comes. Over the pages, along the right edge of what he sees, the line of the apps'
 * real logos, one per waiting notification, the one on top at full strength and a little larger, the others dimmed.
 * Nothing else: no time, no counter, no close button. A notification's buttons are plain words at the page's foot
 * (where NoticePages.actionAt finds them), no pill round them.
 *
 * One per render thread (its paints are its own).
 */
class NoticePagesArt {
    private val bitmap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val word = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    }
    private val box = RectF()
    private val label = NoticeThrowArt()

    /**
     * Onto [canvas], which is in panel units as ShellOverlay hands it. [progress] is how far down the pile is.
     * [words] false leaves the buttons' words out (something else stands at the foot). [shown] is a notification as
     * it may be shown now (its words hidden while the back is locked), [icon] an app's own icon.
     */
    fun draw(
        canvas: Canvas, rear: RearGeometry, s: NoticePages.Scene, progress: Float, words: Boolean,
        icon: (String) -> Bitmap?, shown: (NoticeShade.Item) -> NoticeShade.Item = { it },
    ) {
        val w = rear.widthPx.toFloat()
        val h = rear.heightPx.toFloat()
        val u = h / 596f
        val left = rear.cutoutUnits
        canvas.save()
        // The rear's own pixels from here, as the pop-up is drawn: the same measures give the same picture.
        canvas.scale(1f / rear.fitX, 1f / rear.fitY)
        canvas.translate(0f, -(1f - progress) * h)
        // Nothing waiting: the one line, set as a pop-up's message is (its face, its size, the middle of what he
        // sees), on the pop-up's ground with no app's colour and kept dark: black down to a dark grey.
        // A PAGE THAT IS NOT IN ITS PLACE HAS THE DISPLAY'S CORNERS (PageCorners): the pile on its way down or up, the
        // logos' page going up and away, a page thrown out sideways. In its place its corners are the display's own.
        val corner = PageCorners.radius(rear, w)
        val pulled = progress < 0.999f
        if (s.empty) {
            if (pulled) canvas.saveLayerAlpha(0f, 0f, w, h, 255)
            NoticePop.draw(canvas, w, h, "", NONE, NONE_GROUND, null)
            if (pulled) { PageCorners.clear(canvas, w, h, corner); canvas.restore() }
        }
        for (p in s.pages) {
            canvas.save()
            canvas.translate(p.dx * rear.fitX, p.dy * rear.fitY)
            val aside = pulled || p.dx != 0f || p.dy != 0f
            val layered = p.alpha < 0.996f || aside
            if (layered) canvas.saveLayerAlpha(0f, 0f, w, h, (p.alpha * 255).toInt().coerceIn(0, 255))
            val raw = p.item
            if (raw == null) {
                // The logos' page: the pop-up's ground with no app's colour, and the apps' own logos, nothing else.
                NoticePop.draw(canvas, w, h, "", "", 0, null)
                val apps = NoticePages.apps(s.line)
                bitmap.alpha = 255
                for ((k, b) in NoticePages.logoBoxes(apps.size, left).withIndex()) {
                    val logo = icon(apps[k].pkg) ?: continue
                    // Square in the rear's pixels (the two fits differ by four per cent), about the place a finger finds it.
                    val half = b[2] / 2f * rear.fitY
                    box.set(b[0] * rear.fitX - half, b[1] * rear.fitY - half, b[0] * rear.fitX + half, b[1] * rear.fitY + half)
                    canvas.drawBitmap(logo, null, box, bitmap)
                }
            } else {
                val item = shown(raw)
                val logo = icon(item.pkg)
                NoticePop.draw(canvas, w, h, who(item, logo), item.text, item.color, logo)
                val n = if (words) minOf(item.actions.size, NoticePages.ACTIONS) else 0
                if (n > 0) {
                    word.textSize = WORD_SIZE * u
                    word.color = NoticePop.ink(NoticePop.accentOf(item.color))
                    word.setShadowLayer(8f * u, 0f, 1f * u, 0x66000000)
                    for (i in 0 until n) {
                        val text = TextUtils.ellipsize(item.actions[i], word, (NoticePages.WORD_W - 24f) * rear.fitX, TextUtils.TruncateAt.END).toString()
                        canvas.drawText(text, NoticePages.wordX(i, n, left) * rear.fitX, NoticePages.WORD_Y * rear.fitY + WORD_SIZE * 0.36f * u, word)
                    }
                }
            }
            if (layered) { if (aside) PageCorners.clear(canvas, w, h, corner); canvas.restore() }
            canvas.restore()
        }
        // The logos along the edge, in the notifications' order.
        val n = s.line.size
        val lineAlpha = (s.focus + 1f).coerceIn(0f, 1f)
        if (n > 0 && lineAlpha > 0.01f) {
            val pitch = (LINE_ICON + LINE_GAP) * u
            val room = LINE_ROOM * u
            // All of them centred where they fit; more than fit, the line runs with the page on top.
            val half = (room / pitch - 1f) / 2f
            val mid = if (n * pitch <= room) (n - 1) / 2f else s.focus.coerceIn(half, n - 1 - half)
            for (k in 0 until n) {
                val cy = NoticePop.MIDDLE * u + (k - mid) * pitch
                val edge = ((room / 2f + pitch / 2f - abs(cy - NoticePop.MIDDLE * u)) / (pitch / 2f)).coerceIn(0f, 1f)
                if (edge <= 0f) continue
                val logo = icon(s.line[k].pkg) ?: continue
                val near = (1f - abs(k - s.focus)).coerceIn(0f, 1f)
                val size = (LINE_ICON + (LINE_ICON_TOP - LINE_ICON) * near) * u
                box.set(LINE_X * u - size / 2f, cy - size / 2f, LINE_X * u + size / 2f, cy + size / 2f)
                bitmap.alpha = (255 * lineAlpha * edge * (LINE_DIM + (1f - LINE_DIM) * near)).toInt().coerceIn(0, 255)
                canvas.drawBitmap(logo, null, box, bitmap)
            }
        }
        // A page just thrown out: "Dismissed" or "Snoozed 1 h" rising over the one that came up (NoticeThrow).
        s.label?.let { label.draw(canvas, h, it) }
        canvas.restore()
    }

    companion object {
        /** Who a page says it is from: the sender; the app's name only where there is no icon to say which app. */
        fun who(item: NoticeShade.Item, icon: Bitmap?): String =
            if (icon == null) item.title.ifBlank { item.app } else item.title.takeUnless { it.equals(item.app, ignoreCase = true) }.orEmpty()

        /** The one line with nothing waiting. */
        const val NONE = "No notifications"
        /** Its page's "colour": a dark grey, so the ground runs from black to dark and no further. */
        const val NONE_GROUND = 0xFF4A4A4A.toInt()
        /** A button's word, in the pop-up's pixels (of a 596 high panel). */
        const val WORD_SIZE = 32f
        // The line of logos: along the right edge of what he sees, clear of the widest message (NoticePop.ROOM ends
        // at x 936) and of the corners; each logo this size, the one on top a little larger, the others dimmed.
        const val LINE_X = 950f
        const val LINE_ICON = 26f
        const val LINE_ICON_TOP = 34f
        const val LINE_GAP = 16f
        const val LINE_ROOM = 400f
        const val LINE_DIM = 0.34f
    }
}
