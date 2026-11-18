package dev.kb896.aiusage.rear

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object EarbudsMotion {
    /** The back begins as the front edge light leaves; one shared timing point. */
    const val BACK_AT = EarbudsEdge.RUN_MS
    /** The back's part ends. */
    const val BACK_END_MS = BACK_AT + 3_000L
    /** Connection gate includes a stalled front window and its removal watchdog. */
    const val WHOLE_MS = BACK_END_MS + 1_500L + 150L
    /** Retry the level before the front ends; artwork never holds up the handoff. */
    const val LEVEL_AGAIN_MS = BACK_AT - 400L
    /** Told later than this past its moment, the back shows nothing (it would come in half-way, the veil already gone). */
    const val BACK_LATE_MS = 250L

    /** Rebase the rear timeline to the actual front removal, including a slow first window or shader frame. */
    fun rearStartAt(frontEndedAt: Long): Long = frontEndedAt - BACK_AT

    /** Whether the back's moment, begun on the front at [startAt], is too late to begin at [now] (both uptimeMillis). */
    fun backLate(now: Long, startAt: Long): Boolean = now > startAt + BACK_AT + BACK_LATE_MS

    data class Back(
        val blur: Float, val dim: Float,
        /** Ms into the veil (-1: none). */
        val veil: Float,
        val picture: Float, val focus: Float, val figures: Float, val figuresBlur: Float,
        val breathe: Float, val edgeHold: Float, val flare: Float,
    )

    /** A spring's step response, damping ratio [z], response [period] in s (Folme's and SwiftUI's), [t] ms since it was let go; 0 before. */
    fun spring(t: Float, z: Float, period: Float): Float {
        if (t <= 0f) return 0f
        val s = t / 1000f; val w = 2f * PI.toFloat() / period
        if (z >= 1f) return 1f - exp(-w * s) * (1f + w * s)
        val wd = w * sqrt(1f - z * z)
        return 1f - exp(-z * w * s) * (cos(wd * s) + z * w / wd * sin(wd * s))
    }

    private fun cl(x: Float) = min(1f, max(0f, x))
    private fun sm(a: Float, b: Float, t: Float): Float { val x = cl((t - a) / (b - a)); return x * x * (3 - 2 * x) }
    private fun eo3(x: Float) = 1f - (1f - cl(x)).pow(3)

    /** The back at [t] ms since the connection was heard (nothing before [BACK_AT]). */
    fun back(t: Float): Back {
        val rs = t - BACK_AT
        val out = spring(rs - 2750, 0.95f, 0.4f)
        return Back(
            blur = 58f * spring(rs, 0.95f, 0.3f) * (1f - out), dim = 0.45f * sm(0f, 300f, rs) * (1f - out),
            veil = if (rs in 0f..1000f) rs else -1f,
            picture = sm(250f, 450f, rs) * (1f - sm(2630f, 2900f, rs)), focus = 1f - eo3((rs - 300) / 380f),
            figures = sm(450f, 700f, rs) * (1f - sm(2630f, 2850f, rs)), figuresBlur = 1f - eo3((rs - 450) / 380f),
            breathe = sm(600f, 850f, rs) * (1f - sm(2630f, 2850f, rs)),
            edgeHold = 0.32f * sm(700f, 950f, rs) * (1f - sm(2200f, 3000f, rs)),
            flare = if (rs > 300) exp(-(rs - 300) / 300f) * 0.6f else 0f,
        )
    }

    /** How strong Xiaomi's veil is at [ms] into it, and how strong its line round the edge (pin_show.pag, measured). */
    fun veil(ms: Float): Pair<Float, Float> {
        if (ms < 0f) return 0f to 0f
        val amp = if (ms < 417f) sm(0f, 300f, ms) else 1f - sm(417f, 717f, ms)
        val edge = sm(280f, 650f, ms) * (1f - sm(650f, 950f, ms)) * 0.82f
        return amp to edge
    }

    /** The music's beat the storyboard breathes with is made up there; on the phone the breath is a slow even one (no level is read). */
    fun breath(t: Float): Float = 0.5f + 0.5f * sin(2f * PI.toFloat() * t / 1600f)

    /** Xiaomi's light colours (pin_show.pag): apricot, lavender, blue. Kept as Xiaomi has them (a faithful copy). */
    const val APRICOT = 0xFFF7BE98.toInt()
    const val LAVENDER = 0xFFAA9DE1.toInt()
    const val BLUE = 0xFF4B83FC.toInt()
}
