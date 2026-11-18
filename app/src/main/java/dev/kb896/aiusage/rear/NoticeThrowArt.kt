package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint

/**
 * The label a thrown notification leaves for a moment (NoticeThrow.labelAt): "Dismissed" or "Snoozed 1 h", one line in
 * the pop-up's demibold, plain white (no accent colour, no pill, no icon), centred on x 636 of the back, the middle of
 * what he sees past the lenses. Drawn by the pile's painter (NoticePagesArt) and by the pop-up's label layer
 * (ShellNotices), so the two are one picture.
 *
 * One per render thread (its paint is its own).
 */
class NoticeThrowArt {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        color = 0xFFFFFFFF.toInt()
    }

    /** Onto [canvas] in the back's own pixels, [h] high (the pop-up's measures are of a 596 high back). */
    fun draw(canvas: Canvas, h: Float, label: NoticeThrow.Label) {
        if (label.alpha <= 0.004f) return
        val u = h / 596f
        paint.textSize = NoticeThrow.LABEL_SIZE * u
        paint.alpha = (255 * label.alpha).toInt().coerceIn(0, 255)
        // A soft shadow so it reads over a page's colour as over black; dark, not a colour.
        paint.setShadowLayer(10f * u, 0f, 1f * u, ((0x88 * label.alpha).toInt().coerceIn(0, 255)) shl 24)
        canvas.drawText(label.words, NoticeThrow.LABEL_X * u, label.y * u, paint)
    }
}
