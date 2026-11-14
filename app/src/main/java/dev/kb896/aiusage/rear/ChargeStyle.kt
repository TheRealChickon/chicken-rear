package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.ChargeAnimStyle

/**
 * Where the charging animation stands on the rear and how it moves, for each style (Liquid,
 * Ring, Edge glow, Minimal) and for its two sizes: the FULL one over Xiaomi's launcher (5 s,
 * over a dim scrim) and the COMPACT one down the right edge over an app on the back (2 s, no
 * scrim, nothing over the app's middle).
 *
 * All in the rear's REAL pixels, as ShellCharge draws, and inside the SAFE AREA: right of the
 * camera island (the rear's cutout, 296 px on the 17 Pro Max) to the right edge — x 296..976 on
 * its 976 x 596. Nothing is drawn under the lenses, where it would be cut off by the glass.
 *
 * Pure: no android types, so it is tested on the JVM (ChargeStyleTest). ShellCharge paints.
 */
object ChargeStyle {

    /** A rectangle in the rear's pixels. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centreX: Float get() = (left + right) / 2f
        val centreY: Float get() = (top + bottom) / 2f
    }

    /** How long a show lasts and how its parts are timed. */
    data class Timing(val totalMs: Float, val fillMs: Float, val inMs: Float, val outMs: Float)

    /** Over Xiaomi's launcher: ChargeOverlay's own five seconds — in, three of filling, the figure read, out. */
    val FULL = Timing(ChargeOverlay.TOTAL_MS, ChargeOverlay.FILL_MS, ChargeOverlay.SLIDE_MS, ChargeOverlay.SLIDE_MS)

    /** Over an app on the back: two seconds, so the app is not held up. */
    val COMPACT = Timing(2_000f, 1_200f, 200f, 240f)

    fun timing(compact: Boolean): Timing = if (compact) COMPACT else FULL

    /** How far in from the safe area's edges anything stands. */
    const val MARGIN = 24f

    /** The compact strip's width. */
    const val STRIP_W = 34f

    /** How dark the scrim over Xiaomi's launcher is at its strongest, 0..1. Not over a guest. */
    const val SCRIM = 0.62f

    /** How long an unplug drains the liquid and fades it all away (ChargeOverlay.DRAIN_MS). */
    const val DRAIN_MS = ChargeOverlay.DRAIN_MS

    /** The safe area of a rear [widthPx] x [heightPx] whose camera island takes the left [cutoutPx]. */
    fun safeArea(widthPx: Int, heightPx: Int, cutoutPx: Int): Box =
        Box(cutoutPx.coerceIn(0, widthPx).toFloat(), 0f, widthPx.toFloat(), heightPx.toFloat())

    /**
     * The glass the liquid fills, or the bar / ring's bounds, for [style] in [safe]. COMPACT: a
     * strip down the right edge for every style, the same strip, since at 34 px a ring or a bar
     * would be the same shape anyway.
     */
    fun body(style: ChargeAnimStyle, safe: Box, compact: Boolean): Box {
        if (compact) return Box(safe.right - MARGIN - STRIP_W, safe.top + MARGIN, safe.right - MARGIN, safe.bottom - MARGIN)
        return when (style) {
            // A tall glass on the right of the safe area, the figure to its left.
            ChargeAnimStyle.LIQUID -> {
                val w = (safe.width * ChargeOverlay.WIDTH_FRACTION * 1.3f).coerceAtLeast(STRIP_W)
                Box(safe.right - MARGIN - w, safe.top + MARGIN, safe.right - MARGIN, safe.bottom - MARGIN)
            }
            // A ring in the middle of the safe area, the figure inside it.
            ChargeAnimStyle.RING -> {
                val r = ringRadius(safe)
                Box(safe.centreX - r, safe.centreY - r, safe.centreX + r, safe.centreY + r)
            }
            // The safe area's own edge, inset.
            ChargeAnimStyle.EDGE_GLOW -> Box(safe.left + MARGIN / 2f, safe.top + MARGIN / 2f, safe.right - MARGIN / 2f, safe.bottom - MARGIN / 2f)
            // A thin bar under the figure, in the lower middle.
            ChargeAnimStyle.MINIMAL -> {
                val w = safe.width * 0.56f
                val y = safe.top + safe.height * 0.68f
                Box(safe.centreX - w / 2f, y, safe.centreX + w / 2f, y + 10f)
            }
        }
    }

    /** The ring's radius: most of the shorter side of the safe area. */
    fun ringRadius(safe: Box): Float = minOf(safe.width, safe.height) / 2f * 0.62f

    /**
     * The liquid's surface (LIQUID, the compact strip) or the edge glow's lit top (EDGE_GLOW),
     * at [fill] 0..1: the body's bottom when empty, its top when full.
     */
    fun fillTop(body: Box, fill: Float): Float = body.bottom - body.height * fill.coerceIn(0f, 1f)

    /** The ring's swept angle at [fill], degrees, from the top clockwise. */
    fun ringSweep(fill: Float): Float = 360f * fill.coerceIn(0f, 1f)

    /** The minimal bar's lit right end at [fill]. */
    fun barRight(body: Box, fill: Float): Float = body.left + body.width * fill.coerceIn(0f, 1f)

    /** Where the figure's centre stands: left of the glass (LIQUID), in the ring, over the bar, mid-safe-area (EDGE_GLOW). */
    fun figureCentre(style: ChargeAnimStyle, safe: Box, body: Box): Pair<Float, Float> = when (style) {
        ChargeAnimStyle.LIQUID -> ((safe.left + body.left) / 2f) to safe.centreY
        ChargeAnimStyle.RING -> body.centreX to body.centreY
        ChargeAnimStyle.MINIMAL -> body.centreX to (body.top - safe.height * 0.16f)
        ChargeAnimStyle.EDGE_GLOW -> safe.centreX to safe.centreY
    }

    /** How full it is, 0..1, on the way to [level], [elapsedMs] in. */
    fun fill(elapsedMs: Float, level: Float, t: Timing): Float {
        if (elapsedMs <= 0f) return 0f
        val p = (elapsedMs / t.fillMs).coerceIn(0f, 1f)
        return level.coerceIn(0f, 1f) * Tween.easeInOut(p)
    }

    /** How strongly it is shown, 0..1: in, held, out. */
    fun alpha(elapsedMs: Float, t: Timing): Float = when {
        elapsedMs <= 0f -> 0f
        elapsedMs < t.inMs -> Tween.easeInOut(elapsedMs / t.inMs)
        elapsedMs < t.totalMs - t.outMs -> 1f
        elapsedMs < t.totalMs -> 1f - Tween.easeInOut((elapsedMs - (t.totalMs - t.outMs)) / t.outMs)
        else -> 0f
    }

    fun done(elapsedMs: Float, t: Timing): Boolean = elapsedMs >= t.totalMs

    /** The liquid as the charger comes out: ChargeOverlay.drain. */
    fun drainFill(fillAtUnplug: Float, sinceUnplugMs: Float): Float = ChargeOverlay.drain(fillAtUnplug, sinceUnplugMs)

    /** Everything's strength as it drains: from [alphaAtUnplug] to nothing over [DRAIN_MS]. */
    fun drainAlpha(alphaAtUnplug: Float, sinceUnplugMs: Float): Float {
        if (sinceUnplugMs <= 0f) return alphaAtUnplug.coerceIn(0f, 1f)
        val p = (sinceUnplugMs / DRAIN_MS).coerceIn(0f, 1f)
        return alphaAtUnplug.coerceIn(0f, 1f) * (1f - p)
    }

    fun drained(sinceUnplugMs: Float): Boolean = sinceUnplugMs >= DRAIN_MS
}
