package dev.kb896.aiusage.circle

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader

object StrokePainter {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    private val blurs = HashMap<Float, BlurMaskFilter>()
    private fun blur(r: Float) = blurs.getOrPut(r) { BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL) }

    /** The halo's colours over the screen: the same places as the colour wash (blue left, red upper right, green below). */
    fun colours(w: Int, h: Int): Shader {
        val bw = 48; val bh = (48f * h / w.coerceAtLeast(1)).toInt().coerceAtLeast(8)
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val q = Paint()
        c.drawColor(0xFF8C6CF2.toInt())
        fun spot(cx: Float, cy: Float, r: Float, color: Int) {
            q.shader = RadialGradient(cx, cy, r, intArrayOf(color, (color and 0xFFFFFF) or (0x99 shl 24), color and 0xFFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), q)
        }
        spot(bw * 0.10f, bh * 0.05f, bw * 0.95f, 0xFF3F7BFF.toInt())
        spot(bw * 0.95f, bh * 0.08f, bw * 0.90f, 0xFFFF3B2F.toInt())
        spot(bw * 1.05f, bh * 0.62f, bw * 0.70f, 0xFFF2A91C.toInt())
        spot(bw * 0.55f, bh * 1.02f, bw * 1.05f, 0xFF16C784.toInt())
        spot(bw * -0.05f, bh * 0.80f, bw * 0.75f, 0xFF12A7D6.toInt())
        return BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(w / bw.toFloat(), h / bh.toFloat()) })
        }
    }

    /**
     * [whole] is the stroke so far, [recent] its newest part (drawn at full strength; null if there is none).
     * Returns true when the halo was painted, which is what the caller's "stroke without glow" check counts.
     */
    fun paint(c: Canvas, whole: Path, recent: Path?, look: SelLogic.StrokeLook, colours: Shader): Boolean {
        p.shader = colours
        var halo = false
        for (k in look.haloHalf.indices) {
            if (look.haloAlpha[k] <= 0) continue
            p.maskFilter = blur(look.haloBlur[k])
            p.alpha = look.haloAlpha[k]; p.strokeWidth = look.haloHalf[k] * 2
            c.drawPath(whole, p); halo = true
        }
        p.maskFilter = null
        p.alpha = 255; p.strokeWidth = look.rimWidth
        c.drawPath(whole, p)
        p.shader = null; p.color = Color.WHITE
        p.alpha = look.oldCoreAlpha; p.strokeWidth = look.coreWidth
        c.drawPath(whole, p)
        if (recent != null) { p.alpha = 255; c.drawPath(recent, p) }
        p.alpha = 255
        return halo
    }
}
