package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * THE FACE PICKER'S PICTURE (ShellPicker's layer at ShellLayer.Z_PICKER) and where the live face's own layer goes as
 * its card (ShellScene.Shape, placed through RearShape.place): one painter, used by the shell and by the corners'
 * test (RearCornersTest), so what is checked is what is drawn.
 *
 * The cards that are pictures are cut round by their own radius (FacePicker.Card.corner, all four corners); the live
 * card is not drawn here: it is the face's layer under this one, seen through this picture's clear middle.
 */
class PickerArt {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val clip = Path()

    /** The exit with a new face chosen: the new face's layer is the middle card. */
    fun exitWithNew(m: FacePicker): Boolean = m.phase == FacePicker.Phase.EXIT && m.changesFace

    /** The card the face's layer is (the live one, or with a new face chosen the middle one); null with none on the panel. */
    fun liveCard(m: FacePicker, f: FacePicker.Frame): FacePicker.Card? =
        if (exitWithNew(m)) f.cards.firstOrNull { it.index == m.index } else f.cards.firstOrNull { it.live }

    /**
     * The face's layer as [card] in a frame scaled [sx], [sy] from the picker's units to the rear's pixels: null when it
     * is the whole back again (closed, nothing new coming).
     */
    fun shapeOf(m: FacePicker, f: FacePicker.Frame, card: FacePicker.Card, sx: Float, sy: Float): ShellScene.Shape? {
        val withNew = exitWithNew(m)
        if (f.open <= 0.0005 && !withNew) return null
        val a = if (withNew) f.newFaceAlpha else card.alpha
        return ShellScene.Shape(card.scale.toFloat(), (card.cx * sx).toFloat(), (card.cy * sy).toFloat(), (card.corner / card.scale).toFloat(), a.toFloat())
    }

    /**
     * The picker's own picture into [c] (cleared by the caller): every card that is a picture, the strokes, the ring and
     * the dots, in the picker's units scaled [sx], [sy]. [picture] is a card's picture by its face's index (null: black).
     * [skip]: a card whose picture is left out (the test's "without it"); its ring and stroke are still drawn.
     */
    fun draw(c: Canvas, m: FacePicker, f: FacePicker.Frame, sx: Float, sy: Float, picture: (Int) -> Bitmap?, skip: Int = -1) {
        val withNew = exitWithNew(m)
        c.save(); c.scale(sx, sy)
        for (card in f.cards) {
            rect.set(card.left.toFloat(), (card.cy - card.height / 2).toFloat(), card.right.toFloat(), (card.cy + card.height / 2).toFloat())
            val r = card.corner.toFloat()
            val isLiveLayer = if (withNew) card.index == m.index else card.live
            // A picture where the card is not the live layer; over the new face's layer, its picture fading out.
            val pictureAlpha = when {
                withNew && card.index == m.index -> f.pictureAlpha
                isLiveLayer -> 0.0
                else -> card.alpha
            }
            if (pictureAlpha > 0.003 && card.index != skip) {
                val bm = picture(card.index)
                c.save()
                clip.rewind(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
                c.clipPath(clip)
                paint.alpha = (pictureAlpha * 255).toInt().coerceIn(0, 255)
                if (bm != null) c.drawBitmap(bm, null, rect, paint)
                else { paint.color = 0xFF000000.toInt(); paint.alpha = (pictureAlpha * 255).toInt(); c.drawRect(rect, paint) }
                c.restore()
                paint.alpha = 255
            }
            // The inner stroke: #0FFFFFFF, 6 px of the unscaled face.
            if (card.strokeAlpha > 0.003 && f.open > 0.0) {
                val w = (FacePicker.STROKE * card.scale).toFloat()
                stroke.strokeWidth = w
                stroke.color = FacePicker.STROKE_COLOR
                stroke.alpha = ((FacePicker.STROKE_COLOR ushr 24) * card.strokeAlpha).toInt().coerceIn(0, 255)
                val inset = RectF(rect); inset.inset(w / 2, w / 2)
                c.drawRoundRect(inset, (r - w / 2).coerceAtLeast(0f), (r - w / 2).coerceAtLeast(0f), stroke)
            }
            // The ring: 1031 x 651 of the unscaled face, centred on the card, stroke 11, radius 123, #99FFFFFF.
            if (card.ringAlpha > 0.003) {
                val k = card.scale * card.ringScale
                val w = (FacePicker.RING_W * k).toFloat(); val h = (FacePicker.RING_H * k).toFloat()
                val ring = RectF((card.cx - w / 2).toFloat(), (card.cy - h / 2).toFloat(), (card.cx + w / 2).toFloat(), (card.cy + h / 2).toFloat())
                stroke.strokeWidth = (FacePicker.RING_STROKE * k).toFloat()
                stroke.color = FacePicker.RING_COLOR
                stroke.alpha = ((FacePicker.RING_COLOR ushr 24) * card.ringAlpha).toInt().coerceIn(0, 255)
                val rr = (FacePicker.RING_RADIUS * k).toFloat()
                c.drawRoundRect(ring, rr, rr, stroke)
            }
        }
        // The dots.
        val d = f.dots
        if (d.count > 0 && d.alpha > 0.003) {
            val x0 = d.cx - (d.count - 1) * d.pitch / 2
            for (i in 0 until d.count) {
                paint.color = if (i == d.lit) FacePicker.DOT_ON else FacePicker.DOT_OFF
                paint.alpha = (255 * d.alpha).toInt().coerceIn(0, 255)
                c.drawCircle((x0 + i * d.pitch).toFloat(), d.cy.toFloat(), FacePicker.DOT_RADIUS.toFloat(), paint)
            }
            paint.alpha = 255
        }
        c.restore()
    }
}
