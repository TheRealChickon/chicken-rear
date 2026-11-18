package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.ChargeAnimStyle
import dev.kb896.aiusage.data.WakeAnimStyle
import dev.kb896.aiusage.rear.ChargeStyle.Box
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The wake animations played once over Xiaomi's rear screen as it lights up (ShellWake): what each
 * style draws at a moment of its short life, and when one is to be played at all.
 *
 * - EDGE_GLOW (the default): a bright run of light once round the safe area's edge, fading as it closes.
 * - LIQUID_SWEEP: a band of liquid rising from the bottom and draining off the top.
 * - BLUR_REVEAL: Xiaomi's screen comes in from blurred and dimmed (SurfaceFlinger's live blur).
 * - RIPPLE: one ring out from the camera island, the safe area's left edge.
 * - NONE: nothing, no layer shown.
 *
 * Each lasts [MIN_MS]..[MAX_MS] and ends fully transparent, so the last thing seen is Xiaomi's
 * screen as it is. Everything stands in the SAFE AREA (ChargeStyle.safeArea, right of the lenses);
 * ShellWake also crops its layer to it.
 *
 * Pure: no android types, so it is tested on the JVM (WakeStyleTest). ShellWake paints.
 */
object WakeStyle {

    const val MIN_MS = 500L
    const val MAX_MS = 900L

    /** How far in from the safe area's edges the edge glow's line runs. */
    const val EDGE_INSET = 14f
    /** The edge glow's line, and its glow round it (each side of the line). */
    const val EDGE_STROKE = 6f
    const val EDGE_GLOW_PX = 10f
    /** How long the edge glow's lit run is, as a part of the way round. */
    const val EDGE_TAIL = 0.28f

    /** The liquid band's height, as a part of the safe area's, and its wave's height in px. */
    const val LIQUID_BAND = 0.42f
    const val LIQUID_WAVE = 14f

    /** The blur at the start of a blur reveal, SurfaceFlinger px, and the dim over it, 0..1. */
    const val BLUR_START_PX = 70
    const val BLUR_DIM = 0.35f

    /** The ripple's ring width. */
    const val RIPPLE_WIDTH = 18f

    /** How long a style plays; 0 for NONE. */
    fun durationMs(style: WakeAnimStyle): Long = when (style) {
        WakeAnimStyle.EDGE_GLOW -> 800L
        WakeAnimStyle.LIQUID_SWEEP -> 900L
        WakeAnimStyle.BLUR_REVEAL -> 600L
        WakeAnimStyle.RIPPLE -> 700L
        WakeAnimStyle.NONE -> 0L
    }

    /** One moment of a style. [alpha]: the whole layer's strength. [drawn]: everything drawn stands in it. */
    sealed class Frame {
        abstract val alpha: Float
        abstract val drawn: Box

        /** The line round [outline] (corner [radius]) lit from [tailAt] to [headAt], parts of the way round from the top-left, clockwise. */
        data class Edge(override val alpha: Float, val outline: Box, val radius: Float, val tailAt: Float, val headAt: Float) : Frame() {
            override val drawn: Box get() = grow(outline, EDGE_STROKE / 2f + EDGE_GLOW_PX)
        }

        /** The liquid between [top] (its wavy surface, waves of [wave] px) and [bottom], across [left]..[right]. */
        data class Liquid(override val alpha: Float, val left: Float, val right: Float, val top: Float, val bottom: Float, val wave: Float, val phase: Float) : Frame() {
            override val drawn: Box get() = Box(left, top - wave, right, bottom)
        }

        /** Xiaomi's screen under [area], blurred by [blurPx] and dimmed by [dim] 0..1. */
        data class Blur(override val alpha: Float, val area: Box, val blurPx: Int, val dim: Float) : Frame() {
            override val drawn: Box get() = area
        }

        /** A ring of [radius] round ([cx], [cy]), [width] wide, clipped to [clip]. */
        data class Ripple(override val alpha: Float, val cx: Float, val cy: Float, val radius: Float, val width: Float, val clip: Box) : Frame() {
            override val drawn: Box get() = intersect(Box(cx - radius - width, cy - radius - width, cx + radius + width, cy + radius + width), clip)
        }
    }

    /**
     * [style] at [elapsedMs] in, over [safe] on a rear whose corners are [cornerPx] round. Null when
     * there is nothing to draw: NONE, before the start, or at and past its end.
     */
    fun frame(style: WakeAnimStyle, elapsedMs: Float, safe: Box, cornerPx: Float): Frame? {
        val total = durationMs(style).toFloat()
        if (total <= 0f || elapsedMs < 0f || elapsedMs >= total) return null
        val p = elapsedMs / total
        return when (style) {
            WakeAnimStyle.EDGE_GLOW -> {
                val inset = EDGE_INSET + EDGE_STROKE / 2f + EDGE_GLOW_PX
                val outline = Box(safe.left + inset, safe.top + inset, safe.right - inset, safe.bottom - inset)
                val head = Tween.easeInOut(min(1f, p / 0.8f)) * (1f + EDGE_TAIL)
                Frame.Edge(fadeOut(p, 0.7f), outline, max(0f, cornerPx - inset), (head - EDGE_TAIL).coerceIn(0f, 1f), head.coerceIn(0f, 1f))
            }
            WakeAnimStyle.LIQUID_SWEEP -> {
                // The band's top runs from the bottom to above the top; its bottom follows a band behind.
                val band = safe.height * LIQUID_BAND
                val travel = safe.height + band + LIQUID_WAVE
                val top = safe.bottom - travel * Tween.easeInOut(p)
                val clippedTop = max(top, safe.top + LIQUID_WAVE)
                val bottom = (top + band).coerceIn(clippedTop, safe.bottom)
                Frame.Liquid(fadeOut(p, 0.75f), safe.left, safe.right, clippedTop, bottom, LIQUID_WAVE, p * 2f * Math.PI.toFloat())
            }
            WakeAnimStyle.BLUR_REVEAL -> {
                val left = 1f - Tween.easeInOut(p)
                Frame.Blur(if (p < 0.85f) 1f else fadeOut(p, 0.85f), safe, Math.round(BLUR_START_PX * left), BLUR_DIM * left)
            }
            WakeAnimStyle.RIPPLE -> {
                val (cx, cy) = rippleOrigin(safe)
                val reach = hypot(safe.right - cx, max(cy - safe.top, safe.bottom - cy))
                Frame.Ripple(fadeOut(p, 0.4f), cx, cy, reach * Tween.easeInOut(p), RIPPLE_WIDTH, safe)
            }
            WakeAnimStyle.NONE -> null
        }
    }

    /** The ripple starts at the camera island: the safe area's left edge, at its middle. */
    fun rippleOrigin(safe: Box): Pair<Float, Float> = safe.left to safe.centreY

    /** 1 up to [from] (a part of the whole), then down to 0 at the end. */
    private fun fadeOut(p: Float, from: Float): Float =
        if (p <= from) 1f else (1f - Tween.easeInOut((p - from) / (1f - from))).coerceIn(0f, 1f)

    private fun grow(b: Box, by: Float) = Box(b.left - by, b.top - by, b.right + by, b.bottom + by)

    private fun intersect(a: Box, b: Box): Box {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        return Box(l, t, max(l, min(a.right, b.right)), max(t, min(a.bottom, b.bottom)))
    }

    // -- when one plays -------------------------------------------------------------------------

    /** android.view.Display's states, as this needs them. */
    enum class Light { OFF, DOZE, ON, OTHER }

    /** Display.STATE_* to [Light]: ON 2, OFF 1, DOZE 3, DOZE_SUSPEND 4, ON_SUSPEND 6 (dark, as dozing). */
    fun light(displayState: Int?): Light? = when (displayState) {
        null -> null
        2 -> Light.ON
        1 -> Light.OFF
        3, 4, 6 -> Light.DOZE
        else -> Light.OTHER
    }

    /** The rear lighting up: OFF or DOZE (AOD) to ON. Nothing on a first reading, or ON to anything. */
    fun isWakeEdge(was: Light?, now: Light?): Boolean =
        now == Light.ON && (was == Light.OFF || was == Light.DOZE)

    /**
     * Why a wake edge is not to play a style, or null to play it: off, NONE, not in companion
     * mode, an app of ours or the camera on top. (A charge or banner up, and the re-wake guard,
     * are RearShow's.)
     */
    fun skipWhy(companion: Boolean, on: Boolean, style: WakeAnimStyle, guestOnTop: Boolean, cameraOnRear: Boolean): String? = when {
        !companion -> "not companion mode"
        !on -> "animations are off"
        style == WakeAnimStyle.NONE -> "the style is None"
        cameraOnRear -> "Xiaomi's rear camera has the back"
        guestOnTop -> "an app is on the back"
        else -> null
    }

    /** How late the first frame may be after the wake is seen; later, it is skipped, not started late. */
    const val FIRST_FRAME_MS = 120L

    /** A "Play on the back" (IRearService.playWake): a wake style, or a charge style with a fresh battery reading. */
    sealed class Preview {
        data class Wake(val style: WakeAnimStyle) : Preview()
        data class Charge(val style: ChargeAnimStyle) : Preview()
    }

    /** "EDGE_GLOW" or "charge:LIQUID", as [encode] writes them; null for anything else. */
    fun parsePreview(s: String?): Preview? {
        if (s == null) return null
        val parts = s.split(':')
        if (parts.size == 1) return WakeAnimStyle.entries.firstOrNull { it.name == parts[0] }?.let { Preview.Wake(it) }
        if (parts.size != 2 || parts[0] != "charge") return null
        val style = ChargeAnimStyle.entries.firstOrNull { it.name == parts[1] } ?: return null
        return Preview.Charge(style)
    }

    fun encode(p: Preview): String = when (p) {
        is Preview.Wake -> p.style.name
        is Preview.Charge -> "charge:${p.style.name}"
    }
}
