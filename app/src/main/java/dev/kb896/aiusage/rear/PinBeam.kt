package dev.kb896.aiusage.rear

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A NOTE PINNED TO THE BACK IS BEAMED THERE: one movement across both screens. The model is Xiaomi's own pin
 * animation (`com.xiaomi.subscreencenter`, `PinReceiveActivity`: an overlay on the front drawn by its
 * `libbackpinengine`), which our own "Pin to back" had replaced with a toast.
 *
 * THE MOTION IS XIAOMI'S, read from its keyframes on the phone (its `PinAnimationState`'s four states; the numbers
 * are in PinKeys), on a front screen of 1200 x 2608:
 *  - [LIFT_AT]: what is behind blurs, and the pinned words lift off as a sheet (larger than life, settling a little);
 *  - [CARD_AT]: a card in the back screen's own shape comes down from the top edge, turning from its back to its
 *    face, to the upper part of the screen, where the back screen sits behind the glass; the sheet flies up into it
 *    and from 0.85 s on is its face, its edge lighting up; the card gives a little as the sheet lands;
 *  - [LEAVE_AT]: the card turns away about its upright axis, shrinks and goes into the top edge, gone 0.56 s later.
 * THE BACK TAKES OVER WHILE THE FRONT'S CARD IS STILL TURNING ([ARRIVE_AT], as it begins to fade): the same card, seen
 * from the other side at the very angle the front has it at ([back]'s turn is the front's, mirrored), comes out
 * small, finishes the turn, grows to the whole back and IS the notes page ([ARRIVE_MS]); its lit edge then dies away.
 *
 * Pure: time in, places out. Places on the front are parts of the screen's width and height.
 */
object PinBeam {
    const val LIFT_AT = 0L
    const val CARD_AT = 200L
    const val LEAVE_AT = 1_500L
    /** The back's arrival begins: the front's card starts to fade here. */
    const val ARRIVE_AT = LEAVE_AT + 380L
    /**
     * The pin's sound IS TO BE HEARD here (its attack; service/PinChime asks for it a little before): when Xiaomi's
     * animation has finished (its third state is 550 ms long) it posts its "Pinned" notification, whose sound is the
     * chime. Xiaomi's own real moment (the notification's way through the system comes on top) is to be measured from
     * the recording of the original, and this set to it.
     */
    const val CHIME_AT = LEAVE_AT + 550L
    /** Nothing of ours is on the front after this. */
    const val FRONT_MS = LEAVE_AT + 700L
    /** On the back: from the card's coming to its being the notes page. */
    const val ARRIVE_MS = 700L
    /** On the back: the lit edge dying away after that. */
    const val SETTLE_MS = 420L
    const val BACK_MS = ARRIVE_MS + SETTLE_MS

    // Xiaomi's places on its 1200 x 2608 front, as parts of the screen.
    const val MID_X = 0.5f
    const val CARD_X = 610f / 1200f
    const val CARD_Y = 382f / 2608f
    const val TOP_Y = 72f / 2608f
    /** The card's width at scale 1, as a part of the screen's width (950 of 1200), and its shape (the back's own). */
    const val CARD_W = 950f / 1200f
    const val CARD_SHAPE = 976f / 596f
    private const val LIFT_Y0 = 0.5f - 100f / 2608f
    private const val LIFT_Y1 = 0.5f + 100f / 2608f

    data class Front(
        /** What is behind: 0 as it is, 1 fully blurred and dimmed. */
        val blur: Float,
        /** The sheet of pinned words. */
        val noteAlpha: Float, val noteScale: Float, val noteX: Float, val noteY: Float, val lift: Float,
        /** The card: [cardScale] of [CARD_W], turned [cardTurn] degrees about its upright axis (0 = its face to him). */
        val cardAlpha: Float, val cardScale: Float, val cardTurn: Float, val cardX: Float, val cardY: Float,
        /** How much of the card's face is the note yet, how lit its edge is, and how thick (a part of its width). */
        val face: Float, val glow: Float, val edge: Float,
    )

    data class Back(
        /** What is on the back under the card: 0 as it is, 1 black. */
        val shade: Float,
        val alpha: Float,
        /** 1 = the whole back. */
        val scale: Float,
        val turn: Float,
        val glow: Float,
        /** The card's corners, a part of its height; 0 once it is the page. */
        val corner: Float,
    )

    // ---- easings (miuix's, by their names in Xiaomi's code) ----

    fun linear(t: Float) = t
    fun cubicOut(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
    fun cubicInOut(t: Float) = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
    fun quartInOut(t: Float) = if (t < 0.5f) 8f * t * t * t * t else 1f - (-2f * t + 2f).let { it * it * it * it } / 2f
    fun sineOut(t: Float) = sin(t * PI / 2).toFloat()
    fun sineInOut(t: Float) = (-(cos(PI * t) - 1.0) / 2.0).toFloat()

    /** A spring of [damping] (1 = no overshoot) and [response] seconds, [ms] after it was let go: 0 towards 1. */
    fun spring(damping: Float, response: Float, ms: Float): Float {
        if (ms <= 0f) return 0f
        val t = ms / 1000.0
        val w = 2 * PI / response
        val z = damping.toDouble()
        val x = if (z < 1.0) {
            val wd = w * sqrt(1 - z * z)
            1 - exp(-z * w * t) * (cos(wd * t) + z * w / wd * sin(wd * t))
        } else 1 - exp(-w * t) * (1 + w * t)
        return x.toFloat()
    }

    /** One stretch of a value's way: from where it is at [at], to [to]. [ms] 0: a spring ([damping], [response]). */
    internal class Seg(val at: Long, val to: Float, val ms: Long, val ease: (Float) -> Float = ::linear, val damping: Float = 0f, val response: Float = 0f)

    internal fun spr(at: Long, to: Float, damping: Float, response: Float) = Seg(at, to, 0L, damping = damping, response = response)

    internal class Track(val from: Float, vararg val segs: Seg) {
        fun at(t: Float, upTo: Int = segs.size): Float {
            var v = from
            for (i in 0 until upTo) {
                val s = segs[i]
                if (t < s.at) break
                val began = at(s.at.toFloat(), i)
                val d = t - s.at
                val part = if (s.ms == 0L) spring(s.damping, s.response, d) else s.ease((d / s.ms).coerceIn(0f, 1f))
                v = began + (s.to - began) * part
            }
            return v
        }
    }

    // Xiaomi's states: 1 at LIFT_AT (its springs 0.95 / 0.3), 2 at CARD_AT (0.95 / 0.4), 3 at LEAVE_AT (0.95 / 0.35).
    private val blur = Track(0f, spr(LIFT_AT, 1f, 0.95f, 0.3f), spr(LEAVE_AT + 100, 0f, 0.95f, 0.35f))
    private val noteAlpha = Track(0f, spr(LIFT_AT, 1f, 1f, 0.25f), Seg(CARD_AT + 850, 0f, 200, ::cubicOut))
    private val noteScale = Track(1.2f, spr(LIFT_AT, 1.05f, 0.95f, 0.3f), Seg(CARD_AT + 850, 0.65f, 200, ::cubicInOut))
    private val noteX = Track(MID_X, Seg(CARD_AT, CARD_X, 930, ::cubicInOut))
    private val noteY = Track(LIFT_Y0, Seg(LIFT_AT, LIFT_Y1, 300, ::cubicOut), Seg(CARD_AT, CARD_Y, 930, ::cubicInOut))
    private val lift = Track(0f, spr(LIFT_AT, 1f, 0.95f, 0.3f), spr(CARD_AT, 0f, 0.95f, 0.4f))
    private val cardAlpha = Track(0f, spr(CARD_AT, 1f, 0.95f, 0.4f), Seg(ARRIVE_AT, 0f, 180, ::sineOut))
    private val cardScale = Track(
        0.06f, Seg(CARD_AT, 0.68f, 900, ::quartInOut), Seg(CARD_AT + 930, 0.6f, 150, ::sineOut), Seg(CARD_AT + 1110, 0.68f, 400, ::cubicOut),
        Seg(LEAVE_AT, 0.06f, 630, ::quartInOut),
    )
    private val cardTurn = Track(-180f, Seg(CARD_AT, 0f, 1000, ::cubicOut), Seg(CARD_AT + 1000, 10f, 1300, ::linear), Seg(LEAVE_AT, 180f, 680, ::sineInOut))
    private val cardX = Track(MID_X, Seg(CARD_AT, CARD_X, 880, ::cubicOut), Seg(LEAVE_AT, MID_X, 630, ::sineOut))
    private val cardY = Track(TOP_Y, Seg(CARD_AT, CARD_Y, 880, ::cubicOut), Seg(LEAVE_AT, TOP_Y, 680, ::quartInOut))
    private val face = Track(0f, spr(CARD_AT + 850, 1f, 0.9f, 0.45f))
    private val glow = Track(0f, spr(CARD_AT + 850, 1f, 0.9f, 0.65f))
    private val edge = Track(0.01f, Seg(LEAVE_AT, 0.04f, 630, ::quartInOut))

    /**
     * Where the caller says where the pinned words are on the screen, the sheet lifts off FROM THERE: 0 at the words'
     * own place and size, 1 on its way as [front] has it, joined within the lift ([JOIN_MS]).
     */
    fun joined(ms: Long): Float = cubicOut((ms.toFloat() / JOIN_MS).coerceIn(0f, 1f))
    const val JOIN_MS = 300L

    /** The front, [ms] after he pinned. */
    fun front(ms: Long): Front {
        val t = ms.coerceAtLeast(0L).toFloat()
        return Front(
            blur.at(t).coerceIn(0f, 1f),
            noteAlpha.at(t).coerceIn(0f, 1f), noteScale.at(t), noteX.at(t), noteY.at(t), lift.at(t).coerceIn(0f, 1f),
            cardAlpha.at(t).coerceIn(0f, 1f), cardScale.at(t), cardTurn.at(t), cardX.at(t), cardY.at(t),
            face.at(t).coerceIn(0f, 1f), glow.at(t).coerceIn(0f, 1f), edge.at(t),
        )
    }

    private val backScale = Track(0.06f, Seg(0, 1f, ARRIVE_MS, ::quartInOut))
    private val backAlpha = Track(0f, Seg(0, 1f, 180, ::sineOut))
    private val backShade = Track(0f, Seg(0, 0.72f, 320, ::sineOut))
    private val backGlow = Track(1f, Seg(ARRIVE_MS, 0f, SETTLE_MS, ::sineOut))

    /**
     * The back, [ms] after its arrival began (which is [ARRIVE_AT] after he pinned). ITS TURN IS THE FRONT'S, seen from
     * behind: the card that is `a` degrees from showing the front its back is `a` degrees from showing the back its face.
     */
    fun back(ms: Long): Back {
        val t = ms.coerceAtLeast(0L).toFloat()
        val scale = backScale.at(t)
        return Back(
            backShade.at(t).coerceIn(0f, 1f), backAlpha.at(t).coerceIn(0f, 1f), scale,
            -(180f - cardTurn.at(ARRIVE_AT + t)).coerceIn(0f, 180f),
            backGlow.at(t).coerceIn(0f, 1f),
            0.09f * (1f - ((scale - 0.6f) / 0.4f).coerceIn(0f, 1f)),
        )
    }
}
