package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.cos

/**
 * What [PinBeam] moves, drawn: the card in the back screen's shape (its face is the notes page itself, [face]; its
 * back is plain dark glass), turned about its upright axis with depth, its edge lit; and, on the front, the sheet of
 * pinned words that lifts off and flies into it. The same card on both screens: the front's activity and the back's
 * shell layer both draw with this. No colour of its own: neutral glass and white light.
 *
 * Not thread-safe: one instance per drawing thread.
 */
class PinBeamArt {
    private val camera = Camera()
    private val matrix = Matrix()
    private val rect = RectF()
    private val clip = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pic = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val layer = Paint()
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = Typeface.create(Typeface.DEFAULT, 600, false) }

    /**
     * The card, [w] by [h] and centred on ([cx], [cy]), turned [turn] degrees about its upright axis (0 its face,
     * 180 its back). [face] is its face's picture, shown as much as [faceIn] says (the glass alone before that);
     * [glow] lights its edge, which is [edge] of its width thick; [corner] of its height round.
     */
    fun card(c: Canvas, face: Bitmap?, cx: Float, cy: Float, w: Float, h: Float, turn: Float, alpha: Float, faceIn: Float, glow: Float, edge: Float, corner: Float) {
        if (alpha <= 0.004f || w < 2f) return
        val showsFace = cos(Math.toRadians(turn.toDouble())) >= 0.0
        camera.save()
        // The eye three card-widths away: depth that is seen, without the near edge swelling.
        camera.setLocation(0f, 0f, -(3f * w / 72f).coerceAtLeast(8f))
        camera.rotateY(turn)
        camera.getMatrix(matrix)
        camera.restore()
        val count = c.save()
        c.translate(cx, cy)
        c.concat(matrix)
        // Its back is seen mirrored otherwise: turned round so its own left is left.
        if (!showsFace) c.scale(-1f, 1f)
        rect.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
        val r = corner * h
        val whole = if (alpha < 0.996f) { layer.alpha = (alpha * 255).toInt(); c.saveLayer(rect.left - w * 0.2f, rect.top - w * 0.2f, rect.right + w * 0.2f, rect.bottom + w * 0.2f, layer) } else -1
        // The light round it first, under the card: wider and fainter each time.
        if (glow > 0.01f) {
            val t = (edge * w).coerceAtLeast(1.5f)
            for ((wide, a) in GLOW) {
                line.strokeWidth = t * wide
                line.color = ((glow * a * 255).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
                c.drawRoundRect(rect, r, r, line)
            }
        }
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, if (showsFace) GLASS else BACKING, null, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, fill)
        fill.shader = null
        if (showsFace && face != null && faceIn > 0.004f) {
            clip.rewind(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
            val inner = c.save()
            c.clipPath(clip)
            pic.alpha = (faceIn.coerceIn(0f, 1f) * 255).toInt()
            c.drawBitmap(face, null, rect, pic)
            c.restoreToCount(inner)
        }
        // The edge itself: a fine line of light, brighter as it is lit, there at all times so the glass has a rim.
        line.strokeWidth = (edge * w * 0.5f).coerceAtLeast(1.2f)
        line.color = (((0.22f + 0.6f * glow) * (1f - 0.75f * edgeOn(turn))).coerceIn(0f, 1f).times(255).toInt() shl 24) or 0xFFFFFF
        c.drawRoundRect(rect, r, r, line)
        if (whole >= 0) c.restoreToCount(whole)
        c.restoreToCount(count)
    }

    /** How near to edge-on the card is: 1 edge-on, 0 flat to the eye. */
    private fun edgeOn(turn: Float) = 1f - abs(cos(Math.toRadians(turn.toDouble()))).toFloat()

    private var laidFor = ""
    private var laid: StaticLayout? = null

    /**
     * The sheet of pinned words, as it lifts off the front: centred on ([cx], [cy]), [scale] times its size, [lift]
     * (0..1) its height over the page (a shadow under it). [room] is the widest it may be at scale 1.
     */
    fun sheet(c: Canvas, text: String, cx: Float, cy: Float, room: Float, scale: Float, alpha: Float, lift: Float, unit: Float) {
        if (alpha <= 0.004f) return
        val size = 52f * unit
        val key = "$room|$size|$text"
        val lay = laid?.takeIf { laidFor == key } ?: run {
            words.textSize = size
            StaticLayout.Builder.obtain(text, 0, text.length, words, (room - 2 * PAD * unit).toInt().coerceAtLeast(10))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(6).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
                .also { laid = it; laidFor = key }
        }
        var widest = 0f
        for (i in 0 until lay.lineCount) widest = maxOf(widest, lay.getLineWidth(i))
        val w = widest + 2 * PAD * unit
        val h = lay.height + 2 * PAD * unit * 0.8f
        val count = c.save()
        c.translate(cx, cy)
        c.scale(scale, scale)
        rect.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
        val r = 34f * unit
        val whole = if (alpha < 0.996f) { layer.alpha = (alpha * 255).toInt(); c.saveLayer(rect.left - 60f * unit, rect.top - 60f * unit, rect.right + 60f * unit, rect.bottom + 90f * unit, layer) } else -1
        // Its shadow: further below and softer the higher it is.
        for (i in 1..5) {
            fill.color = ((lift * 0.07f * 255).toInt().coerceIn(0, 255) shl 24)
            val grow = i * 5f * unit * lift
            c.drawRoundRect(rect.left - grow, rect.top - grow + 16f * unit * lift, rect.right + grow, rect.bottom + grow + 26f * unit * lift, r + grow, r + grow, fill)
        }
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, SHEET, null, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, fill)
        fill.shader = null
        line.strokeWidth = 1.5f * unit
        line.color = 0x38FFFFFF
        c.drawRoundRect(rect, r, r, line)
        c.translate(-lay.width / 2f, -lay.height / 2f)
        lay.draw(c)
        if (whole >= 0) c.restoreToCount(whole)
        c.restoreToCount(count)
    }

    private var sheetShaderOf: Bitmap? = null
    private var sheetShader: android.graphics.BitmapShader? = null
    private val sheetMatrix = Matrix()

    /**
     * A PICTURE'S PIN: the picture itself lifts off where the sheet of words would (its rounded corners, its shadow, its
     * rim), centred on ([cx], [cy]), [scale] times its size, fitted in its own shape into [room] wide and three quarters
     * of that high (never stretched).
     */
    fun sheetPicture(c: Canvas, picture: Bitmap, cx: Float, cy: Float, room: Float, scale: Float, alpha: Float, lift: Float, unit: Float) {
        if (alpha <= 0.004f || picture.isRecycled || picture.width <= 0 || picture.height <= 0) return
        val k = minOf(room / picture.width, room * 0.75f / picture.height)
        val w = picture.width * k; val h = picture.height * k
        val count = c.save()
        c.translate(cx, cy)
        c.scale(scale, scale)
        rect.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
        val r = 34f * unit
        val whole = if (alpha < 0.996f) { layer.alpha = (alpha * 255).toInt(); c.saveLayer(rect.left - 60f * unit, rect.top - 60f * unit, rect.right + 60f * unit, rect.bottom + 90f * unit, layer) } else -1
        for (i in 1..5) {
            fill.color = ((lift * 0.07f * 255).toInt().coerceIn(0, 255) shl 24)
            val grow = i * 5f * unit * lift
            c.drawRoundRect(rect.left - grow, rect.top - grow + 16f * unit * lift, rect.right + grow, rect.bottom + grow + 26f * unit * lift, r + grow, r + grow, fill)
        }
        if (sheetShaderOf !== picture) { sheetShaderOf = picture; sheetShader = android.graphics.BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        sheetMatrix.setRectToRect(RectF(0f, 0f, picture.width.toFloat(), picture.height.toFloat()), rect, Matrix.ScaleToFit.FILL)
        sheetShader!!.setLocalMatrix(sheetMatrix)
        pic.shader = sheetShader
        c.drawRoundRect(rect, r, r, pic)
        pic.shader = null
        line.strokeWidth = 1.5f * unit
        line.color = 0x38FFFFFF
        c.drawRoundRect(rect, r, r, line)
        if (whole >= 0) c.restoreToCount(whole)
        c.restoreToCount(count)
    }

    companion object {
        private const val PAD = 44f
        /** The light round the card's edge: how many edge-thicknesses wide, how strong. */
        private val GLOW = arrayOf(14f to 0.05f, 9f to 0.08f, 5f to 0.14f, 2.4f to 0.3f)
        private val GLASS = intArrayOf(0xFF1D1D20.toInt(), 0xFF0B0B0D.toInt())
        private val BACKING = intArrayOf(0xFF26262A.toInt(), 0xFF101012.toInt())
        private val SHEET = intArrayOf(0xF22A2A2E.toInt(), 0xF2161618.toInt())
    }
}
