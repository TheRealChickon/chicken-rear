package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * A PAGE SEEN ASIDE OVER BLACK HAS THE BACK SCREEN'S OWN CORNERS. The page stays as it is drawn; what lies outside
 * the display's corner shape is black, as the ground is.
 */
object PageCorners {
    private val path = Path()
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }

    /** Black over what of a page [w] x [h] at the canvas's origin lies outside its four corners of radius [r]. One thread. */
    fun cut(c: Canvas, w: Float, h: Float, r: Float) {
        if (r <= 0f) return
        path.rewind()
        path.fillType = Path.FillType.EVEN_ODD
        // A little over the page's own edges, so no line of it is left along them.
        path.addRect(-1f, -1f, w + 1f, h + 1f, Path.Direction.CW)
        path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        c.drawPath(path, black)
    }

    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }

    /**
     * The same for a page that lies over OTHER PAGES in one picture (the notifications' pile): what is outside its
     * corners is taken out of the layer it was drawn into (the caller's saveLayer), so what is under it shows there.
     */
    fun clear(c: Canvas, w: Float, h: Float, r: Float) {
        if (r <= 0f) return
        path.rewind()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(-1f, -1f, w + 1f, h + 1f, Path.Direction.CW)
        path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        c.drawPath(path, clear)
    }

    /** The display's corner for a page drawn [w] wide: [geometry]'s, in the page's own pixels. */
    fun radius(geometry: RearGeometry, w: Float): Float = geometry.cornerPx * w / geometry.widthPx.coerceAtLeast(1)
}
