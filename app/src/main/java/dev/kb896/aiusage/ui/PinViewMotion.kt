package dev.kb896.aiusage.ui

import dev.kb896.aiusage.rear.PinBeam
import dev.kb896.aiusage.rear.PinFace
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HOW THE FRONT'S PICTURE MOVES (ui/PinViewer, ui/PinViewActivity), the way a photo moves in Apple's and Xiaomi's own
 * viewers: pure numbers, view pixels in, view pixels out.
 *
 * - IT COMES FROM THE BACK ([origin]): it grows out of the place the back's notes page has it, seen from the front where
 *   the back screen sits behind the glass (the card of the pin's beam, rear/PinBeam: its centre and width on Xiaomi's
 *   1200 x 2608 front, the back's own shape), the picture's own place on that page (PinFace.pictureAt) and its rounded
 *   corners, to the whole screen, fitted ([fit]), on a spring.
 * - EVERY MOVE IS A [Spring] (damping near 1, a response of about a third of a second: no bounce to be seen), and a move
 *   the finger lets go of goes on at the finger's speed.
 * - PINCH: zoomed about the fingers, from under the fit (it gives a little: [PINCH_UNDER]) to [maxZoom] (it gives past
 *   it: [rubber]); let go under the fit it comes back, under [PINCH_CLOSE] it closes. ZOOMED IN, one finger moves it, held at the picture's edges with a
 *   give ([panLimit], [rubber]); let go, it glides on and stops where the glide would ([glide]), within the edges.
 * - DOUBLE TAP: in to [doubleTapZoom] about the tap, or back to the fit.
 * - A STROKE DOWN (at the fit): the picture follows the finger, smaller and its corners rounder as it goes down, the
 *   black behind it lighter ([dragPart]); let go past [dismisses]'s line or quickly down, it goes on down and away with
 *   that speed (a page leaves the way it was sent, never fading); short of it, it springs back.
 */
object PinViewMotion {
    /**
     * A value on a spring of [damping] (1: no overshoot) and [response] seconds towards [target], stepped exactly (no
     * error grows with a long frame), keeping its velocity (units a second) across a change of target.
     */
    class Spring(var value: Float, var target: Float = value, var damping: Float = 1f, var response: Float = 0.35f) {
        var velocity = 0f

        /** Moves on [dt] seconds. */
        fun step(dt: Float) {
            if (dt <= 0f) return
            val w = 2.0 * PI / response.coerceAtLeast(0.01f)
            val z = damping.toDouble().coerceIn(0.05, 1.0)
            val x0 = (value - target).toDouble()
            val v0 = velocity.toDouble()
            val t = dt.toDouble()
            val x: Double; val v: Double
            if (z >= 0.999) {
                val b = v0 + w * x0
                val e = exp(-w * t)
                x = e * (x0 + b * t)
                v = e * (v0 - w * b * t)
            } else {
                val wd = w * sqrt(1 - z * z)
                val c2 = (v0 + z * w * x0) / wd
                val e = exp(-z * w * t)
                val co = cos(wd * t); val si = sin(wd * t)
                x = e * (x0 * co + c2 * si)
                v = e * ((-z * w * x0 + wd * c2) * co + (-z * w * c2 - wd * x0) * si)
            }
            value = (target + x).toFloat()
            velocity = v.toFloat()
        }

        /** Close enough to its target to stand there ([near] in its units, [slow] a second). */
        fun settled(near: Float = 0.5f, slow: Float = 5f): Boolean = abs(value - target) <= near && abs(velocity) <= slow

        /** Put at its target, still. */
        fun land() { value = target; velocity = 0f }

        /** At [v], still, its target there too. */
        fun hold(v: Float) { value = v; target = v; velocity = 0f }
    }

    /** The open: from the back's place to the screen. Near critical, a third of a second and a little. */
    const val OPEN_DAMPING = 0.92f
    const val OPEN_RESPONSE = 0.38f
    /** Back into the back's place: as quick, no overshoot (a picture going into its place must not bounce out of it). */
    const val CLOSE_DAMPING = 1f
    const val CLOSE_RESPONSE = 0.32f
    /** Zoom, pan and a drag let go: the settle. */
    const val SETTLE_DAMPING = 1f
    const val SETTLE_RESPONSE = 0.4f
    /** A glide after a pan let go: a little slower, so it eases out as a decay does. */
    const val GLIDE_RESPONSE = 0.55f
    /** Released under this (the zoom shown, a part of the fit), a pinch closes the picture (back into the back). */
    const val PINCH_CLOSE = 0.85f
    /** How far a pinch may go under the fit while held. */
    const val PINCH_LEAST = 0.5f
    /** Under the fit a pinch takes the picture down this much of the fingers' own way: it gives, and still plainly goes. */
    const val PINCH_UNDER = 0.8f
    /** A stroke down turns into the picture leaving past this part of the screen's width (the pages' 150 of 1000)... */
    const val DISMISS_TRAVEL = 0.15f
    /** ...or quicker than this (screen widths a second: the pages' 0.6 of 1000 a ms), once past [DISMISS_FLING_AFTER]. */
    const val DISMISS_FLING = 0.6f
    const val DISMISS_FLING_AFTER = 0.04f
    /** The picture at the end of a stroke down's way ([dragPart] 1): this much smaller. */
    const val DRAG_SHRINK = 0.28f
    /** And the black behind it this much lighter. */
    const val DRAG_LIGHTEN = 0.85f
    /** A stroke down's way: this part of the screen's height. */
    const val DRAG_WAY = 0.45f
    /** The ease out of a glide (Apple's scroll views' normal deceleration, 0.998 a ms): a velocity carries on this many seconds' worth. */
    const val GLIDE_SECONDS = 0.499f

    /** A picture of [pw] x [ph] fitted in a view of [vw] x [vh], in the middle (left, top, right, bottom). Larger or smaller, as a photo is. */
    fun fit(pw: Int, ph: Int, vw: Float, vh: Float): FloatArray {
        if (pw <= 0 || ph <= 0 || vw <= 0f || vh <= 0f) return floatArrayOf(0f, 0f, vw, vh)
        val s = minOf(vw / pw, vh / ph)
        val w = pw * s; val h = ph * s
        return floatArrayOf((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f)
    }

    /**
     * WHERE THE PICTURE COMES FROM on a front of [vw] x [vh] turned [rotation] (Surface.ROTATION_*: 1 = the phone's top to
     * the left, 3 = to the right, 2 = upside down): the back's page as the pin's card stands on the front (PinBeam's
     * CARD_*: where the back screen sits behind the glass), and in it the picture's own place ([place], PinFace.pictureAt:
     * rear pixels of 976x596; null: the page's picture place). Upright on the screen as it is turned, in the picture's own
     * shape ([pw] x [ph]). Left, top, right, bottom; then the corner radius it has there.
     */
    fun origin(vw: Float, vh: Float, rotation: Int, place: FloatArray?, pw: Int, ph: Int): FloatArray {
        val turned = rotation == 1 || rotation == 3
        val nw = if (turned) vh else vw
        val nh = if (turned) vw else vh
        val cardW = nw * PinBeam.CARD_W
        val k = cardW / 976f
        val cardH = cardW / PinBeam.CARD_SHAPE
        val cardL = nw * PinBeam.CARD_X - cardW / 2f
        val cardT = nh * PinBeam.CARD_Y - cardH / 2f
        val at = place?.takeIf { it.size == 4 } ?: PinFace.picturePlace(pw.coerceAtLeast(1), ph.coerceAtLeast(1))
        val w = (at[2] - at[0]) * k
        val h = (at[3] - at[1]) * k
        // Its own shape, whatever the place's rounding: as wide as the place, as high as the picture is for that.
        val ow = w
        val oh = if (pw > 0 && ph > 0) w * ph / pw else h
        val nx = cardL + (at[0] + at[2]) / 2f * k
        val ny = cardT + (at[1] + at[3]) / 2f * k
        val (cx, cy) = when (rotation) {
            1 -> ny to nw - nx
            3 -> nh - ny to nx
            2 -> nw - nx to nh - ny
            else -> nx to ny
        }
        return floatArrayOf(cx - ow / 2f, cy - oh / 2f, cx + ow / 2f, cy + oh / 2f, PinFace.PIC_CORNER * k)
    }

    /** The give past an edge: [over] beyond it, in a room of [extent], comes out as less and less (Apple's rubber band). */
    fun rubber(over: Float, extent: Float): Float {
        if (extent <= 0f) return 0f
        val a = abs(over)
        val g = (1f - 1f / (a * 0.55f / extent + 1f)) * extent
        return if (over < 0f) -g else g
    }

    /** [rubber] undone: the way past the edge that gives [given] (a picture caught in its give is taken on from there). */
    fun unrubber(given: Float, extent: Float): Float {
        if (extent <= 0f) return 0f
        val g = abs(given).coerceAtMost(extent * 0.999f)
        val a = g / (extent - g) * extent / 0.55f
        return if (given < 0f) -a else a
    }

    /** How far the picture's centre may be from the screen's, each way, at [zoom] times the fit of [fw] x [fh] on [vw] x [vh]. */
    fun panLimit(fw: Float, fh: Float, zoom: Float, vw: Float, vh: Float): Pair<Float, Float> =
        maxOf(0f, (fw * zoom - vw) / 2f) to maxOf(0f, (fh * zoom - vh) / 2f)

    /** [pan] within ±[limit], what is past it given back as a rubber band's give ([extent]: the room it gives in). */
    fun held(pan: Float, limit: Float, extent: Float): Float = when {
        pan > limit -> limit + rubber(pan - limit, extent)
        pan < -limit -> -limit + rubber(pan + limit, extent)
        else -> pan
    }

    /** [held] undone: the finger's own way for a pan shown at [shown] (caught past an edge, it is taken on with no step). */
    fun unheld(shown: Float, limit: Float, extent: Float): Float = when {
        shown > limit -> limit + unrubber(shown - limit, extent)
        shown < -limit -> -limit + unrubber(shown + limit, extent)
        else -> shown
    }

    /** The most it zooms in: three times the fit, or twice the picture's own pixels where that is more ([native] = its pixels over the fit's). */
    fun maxZoom(native: Float): Float = maxOf(3f, native * 2f).coerceAtMost(8f)

    /**
     * A pinch at [zoom] (unheld): what the screen shows while held, giving past the fit and past [most].
     */
    fun pinchHeld(zoom: Float, most: Float): Float = when {
        zoom < 1f -> maxOf(PINCH_LEAST, 1f - (1f - zoom) * PINCH_UNDER)
        zoom > most -> most + rubber(zoom - most, most * 0.5f)
        else -> zoom
    }

    /** [pinchHeld] undone: the fingers' own zoom for a zoom shown at [shown] (a pinch catching it in its give takes it on from there). */
    fun pinchUnheld(shown: Float, most: Float): Float = when {
        shown < 1f -> 1f - (1f - shown.coerceAtLeast(PINCH_LEAST)) / PINCH_UNDER
        shown > most -> most + unrubber(shown - most, most * 0.5f)
        else -> shown
    }

    /**
     * A double tap: in, to fill the screen where that is a real step (a wide picture on a tall screen) and at least
     * twice, never past [most]; already in (past 1.05): back to the fit.
     */
    fun doubleTapZoom(zoom: Float, fw: Float, fh: Float, vw: Float, vh: Float, most: Float): Float {
        if (zoom > 1.05f) return 1f
        val fill = if (fw > 0f && fh > 0f) maxOf(vw / fw, vh / fh) else 1f
        return maxOf(2f, if (fill > 1.2f) fill else 2.5f).coerceAtMost(most)
    }

    /**
     * The pan that keeps the point under [focus] (its offset from the screen's centre) where it is as the zoom goes from
     * [from] to [to], the pan being [pan] before.
     */
    fun panAbout(pan: Float, focus: Float, from: Float, to: Float): Float = if (from <= 0f) pan else focus - (focus - pan) * (to / from)

    /** Where a glide at [velocity] (px a second) from [from] comes to rest, held within ±[limit]. */
    fun glide(from: Float, velocity: Float, limit: Float): Float = (from + velocity * GLIDE_SECONDS).coerceIn(-limit, limit)

    /** How far along a stroke down's way the picture is (0 to 1), at [dy] down on a screen [vh] high. */
    fun dragPart(dy: Float, vh: Float): Float = if (vh <= 0f) 0f else (dy / (vh * DRAG_WAY)).coerceIn(0f, 1f)

    /** The picture's scale at [part] of the way down. */
    fun dragScale(part: Float): Float = 1f - DRAG_SHRINK * part

    /**
     * Whether a stroke down let go with the finger [dy] down from where it took the picture (its own way, not the
     * picture's centre's, which a picture taken high or low goes less or more), going [vy] (px a second; down is more),
     * on a screen [vw] wide, closes it.
     */
    fun dismisses(dy: Float, vy: Float, vw: Float): Boolean {
        if (vw <= 0f || dy <= 0f) return false
        if (dy >= vw * DISMISS_TRAVEL && vy > -DISMISS_FLING * vw * 0.5f) return true
        return dy >= vw * DISMISS_FLING_AFTER && vy >= DISMISS_FLING * vw
    }

    /** [a] to [b] at [t] (not held within 0 and 1: a spring's overshoot carries through). */
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
