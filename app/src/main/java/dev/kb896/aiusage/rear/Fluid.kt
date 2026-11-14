package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * THE FLUID of the back's live pages: the water of Xiaomi's own rear timer page (its picture,
 * `assets/ripple.webp` of the timer template, read on the phone: XiaomiArt), standing at a level
 * and drifting sideways, once across in two seconds. One renderer for every page that has it (the
 * timer's water, the usage page's meters, charging), so they move alike and a fix to one is a fix
 * to all.
 *
 * The picture is drawn at the size the timer page draws it (twice a 976 panel's width, a panel
 * and a bit high), placed so that its surface line stands at the height asked for, and cut to the
 * shape it is poured into. In another colour it is the same water: its light and shade kept, its
 * blue replaced. Without the picture (Xiaomi's package not readable) it is a plain fill.
 */
object Fluid {
    /** Where the water's surface line is in Xiaomi's picture, from its top, as a part of its drawn height. */
    private const val SURFACE = 0.258f
    /** The picture's drawn size on a 976x596 panel (the timer page's: two panels wide, the panel and its shadow high). */
    private const val W = 1952f
    private const val H = 764f
    const val DRIFT_MS = 2_000L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val plain = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dest = RectF()
    private val shape = Path()
    private val tints = HashMap<Int, ColorMatrixColorFilter>()

    /** The water's own blue in Xiaomi's picture, for the plain fill and as "no other colour". */
    const val BLUE = 0xFF0A78F5.toInt()

    /**
     * Pours it into [into] (rounded by [radius]) on a canvas whose unit is [u] rear pixels: its
     * surface at [surfaceY], everything below it water. [tint]: its colour, or null for the
     * picture's own blue. [moving]: drifting, or held still (a paused timer). [alpha] 0..255.
     * [driftMs]: how long once across takes (the timer's two seconds; a calm page asks for far longer).
     */
    @Synchronized
    fun draw(c: Canvas, into: RectF, radius: Float, surfaceY: Float, now: Long, water: Bitmap?, u: Float, tint: Int? = null, moving: Boolean = true, alpha: Int = 255, driftMs: Long = DRIFT_MS) {
        if (surfaceY >= into.bottom + 60f * u) return
        c.save()
        shape.rewind()
        shape.addRoundRect(into, radius, radius, Path.Direction.CW)
        c.clipPath(shape)
        if (water != null) {
            val w = W * u; val h = H * u
            // Half its width is one panel: it slides by that much in [DRIFT_MS] and starts over unseen, as the timer page's does.
            val x = into.left - w / 2f + (if (moving) (w / 2f / driftMs) * (now % driftMs) else w / 4f)
            dest.set(x, surfaceY - SURFACE * h, x + w, surfaceY - SURFACE * h + h)
            paint.alpha = alpha
            paint.colorFilter = tint?.let { t -> tints.getOrPut(t) { filter(t) } }
            c.drawBitmap(water, null, dest, paint)
            // Deeper than the picture reaches: more of the same.
            if (dest.bottom < into.bottom) {
                plain.color = tint ?: BLUE; plain.alpha = alpha
                c.drawRect(into.left, dest.bottom - 1f, into.right, into.bottom, plain)
            }
        } else {
            plain.color = tint ?: BLUE; plain.alpha = alpha
            c.drawRect(into.left, surfaceY, into.right, into.bottom, plain)
        }
        c.restore()
    }

    /** The picture's light and shade in [tint] instead of its blue. */
    private fun filter(tint: Int): ColorMatrixColorFilter {
        // Its brightness (the water's body comes to about 0.42 of white), brought up to the tint's full strength.
        val k = 2.3f
        val r = Color.red(tint) / 255f * k; val g = Color.green(tint) / 255f * k; val b = Color.blue(tint) / 255f * k
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        return ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            r * lr, r * lg, r * lb, 0f, 0f,
            g * lr, g * lg, g * lb, 0f, 0f,
            b * lr, b * lg, b * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
    }
}
