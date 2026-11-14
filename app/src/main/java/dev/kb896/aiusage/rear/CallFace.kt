package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint

/**
 * A call, as the whole back: Xiaomi's own rear call page, copied.
 *
 * XIAOMI'S PAGE, READ FROM ITS OWN TEMPLATE (com.xiaomi.subscreencenter, assets/smart_assistant/phone,
 * a MAML widget). One unit of it is the page's width / 336; on this 976x596 panel:
 * - the ground: nearly black green at the top (#000E04) to a dark green at the bottom (#003717);
 * - who it is: white, demibold, 64 px, centred on x 633 (the middle of the room right of the
 *   lenses), its top at y 145; under it, at half strength and 41 px, what is going on ("Incoming
 *   call") and, once it is answered, the time it has run;
 * - two round buttons, 162 px, their centres at (469, 444) and (787, 444).
 *   RINGING: left the red one (#FA382E) that declines, right the green one (#1DCD3A) that answers,
 *   with a ring of green light spreading from it every second and a half and, under it in pale
 *   green, how it will answer: "Speaker", or "Earphones" with a headset on.
 *   ANSWERED: the red one has gone over to the right and ends the call; on the left the speaker,
 *   grey while off, white with a blue glyph while on.
 * Nothing else: no mute, no keypad, no avatar. Xiaomi's has none, and neither has this.
 *
 * The glyphs are Xiaomi's own pictures, read from its package on the phone ([ART], XiaomiArt); a
 * button whose picture is missing is drawn bare.
 *
 * [side] and [words] are plain, so a test holds them. In rear pixels of a 976x596 panel; to scale
 * on any other.
 */
object CallFace {
    /** One call as the page shows it. [since]: when it was answered (wall clock), 0 while it rings. */
    data class Model(
        val key: String, val pkg: String, val who: String, val what: String,
        val ringing: Boolean, val since: Long, val speaker: Boolean, val headset: Boolean,
        /** The notification's own buttons, by their words: for a call that is not the phone's own dialer's. */
        val actions: List<String> = emptyList(),
    )

    enum class Side { LEFT, RIGHT }

    /** What a button does, by the call's state. */
    enum class Act { DECLINE, ANSWER, SPEAKER, END }

    const val BTN = 161.6f
    const val BTN_Y = 363.1f
    const val LEFT_X = 387.8f
    const val RIGHT_X = 705.9f
    private const val CENTRE = 633.2f
    private const val TITLE_Y = 145.2f
    private const val TITLE_SIZE = 63.9f
    private const val TITLE_W = 560.6f
    private const val SUB_Y = 232.4f
    private const val SUB_SIZE = 40.7f
    private const val ICON = 81.3f
    private const val LABEL_Y = 533.9f
    private const val LABEL_SIZE = 32f

    /** The pictures the page uses, by their paths in Xiaomi's template. */
    const val ART_END = "assets/state/end_call.png"
    const val ART_ANSWER = "assets/state/answer_call.png"
    const val ART_ANSWER_SPEAKER = "assets/state/speaker_call.png"
    const val ART_SPEAKER_OFF = "assets/state/phone.png"
    const val ART_SPEAKER_ON = "assets/state/speaker.png"
    const val ART_RING = "assets/aperture.webp"
    val ART = listOf(ART_END, ART_ANSWER, ART_ANSWER_SPEAKER, ART_SPEAKER_OFF, ART_SPEAKER_ON, ART_RING)

    /** Which button a tap at [x], [y] (rear pixels of a 976x596 panel) is on; null for neither. */
    fun side(x: Float, y: Float): Side? {
        val cy = BTN_Y + BTN / 2f
        val r = BTN / 2f + 18f
        fun on(left: Float) = (x - left - BTN / 2f) * (x - left - BTN / 2f) + (y - cy) * (y - cy) <= r * r
        return when { on(LEFT_X) -> Side.LEFT; on(RIGHT_X) -> Side.RIGHT; else -> null }
    }

    /** What the button on [side] does for a call that is [ringing] or not. */
    fun act(side: Side, ringing: Boolean): Act = when {
        ringing && side == Side.LEFT -> Act.DECLINE
        ringing -> Act.ANSWER
        side == Side.LEFT -> Act.SPEAKER
        else -> Act.END
    }

    /** The line under the name: what is going on while it rings, the time it has run after. */
    fun words(m: Model, now: Long): String {
        if (m.ringing || m.since <= 0L) return m.what.ifBlank { if (m.ringing) "Incoming call" else "" }
        val s = ((now - m.since) / 1000L).coerceAtLeast(0L)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    /**
     * Which of a notification's buttons ([words]) does [act], by what it says; -1 for none. For a
     * call an app other than the phone's own dialer carries (the dialer takes Xiaomi's own commands).
     */
    fun actionFor(act: Act, words: List<String>): Int {
        val want = when (act) {
            Act.ANSWER -> listOf("answer", "accept", "annehmen")
            Act.DECLINE -> listOf("decline", "reject", "dismiss", "ablehnen")
            Act.END -> listOf("hang", "end", "auflegen", "beenden")
            Act.SPEAKER -> listOf("speaker", "lautsprecher")
        }
        return words.indexOfFirst { w -> want.any { w.contains(it, ignoreCase = true) } }
    }

    private val ground = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pic = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private var typed = false

    /**
     * Draws the page over the whole canvas (opaque). [sinceChange]: ms since it went from ringing
     * to answered (the red button's way across); [pressed] and [sincePress]: a button just pressed.
     */
    @Synchronized
    fun draw(
        c: Canvas, w: Float, h: Float, m: Model, now: Long, art: Map<String, Bitmap>,
        sinceChange: Long = Long.MAX_VALUE, pressed: Side? = null, sincePress: Long = Long.MAX_VALUE, ringAt: Long = now,
    ) {
        val u = h / 596f
        if (!typed) {
            typed = true
            title.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            sub.typeface = Typeface.create(Typeface.DEFAULT, 400, false)
        }
        ground.shader = LinearGradient(0f, 0f, 0f, h, 0xFF000E04.toInt(), 0xFF003717.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, ground)

        // Who, and what is going on or how long it has been.
        title.color = Color.WHITE; title.textSize = TITLE_SIZE * u
        c.drawText(cut(m.who, TITLE_W * u, title), CENTRE * u, TITLE_Y * u - title.ascent(), title)
        sub.color = 0x80FFFFFF.toInt(); sub.textSize = SUB_SIZE * u
        val line = words(m, now)
        if (line.isNotEmpty()) c.drawText(cut(line, 494f * u, sub), CENTRE * u, SUB_Y * u - sub.ascent(), sub)

        // Answered: over some 350 ms the red button crosses to the right, the green one goes, the speaker comes.
        val t = if (m.ringing) 0f else (sinceChange.coerceAtLeast(0L).toFloat() / CHANGE_MS).coerceIn(0f, 1f)
        val k = 1f - (1f - t) * (1f - t) * (1f - t)
        fun press(side: Side): Float {
            if (pressed != side) return 1f
            val p = (sincePress.coerceAtLeast(0L).toFloat() / PRESS_MS).coerceIn(0f, 1f)
            return 1f - 0.1f * kotlin.math.sin(p * Math.PI).toFloat()
        }

        if (k < 1f) {
            // The ring of light from the answer button: out to four and a half times its size, fading in its last second.
            val p = (((now - ringAt) % RING_MS + RING_MS) % RING_MS).toFloat() / RING_MS
            val grow = 1f + 3.5f * (1f - (1f - p) * (1f - p))
            val light = 0.3f * (1f - ((p - 1f / 3f) / (2f / 3f)).coerceIn(0f, 1f)) * (1f - k)
            art[ART_RING]?.let { button(c, RIGHT_X, u, grow, light, null, it, full = true) }
            val answer = art[if (m.headset) ART_ANSWER else ART_ANSWER_SPEAKER]
            button(c, RIGHT_X, u, press(Side.RIGHT) * (1f - 0.4f * k), 1f - k, 0xFF1DCD3A.toInt(), answer)
            sub.color = 0xFF83FFB5.toInt(); sub.alpha = Math.round(255f * (1f - k)); sub.textSize = LABEL_SIZE * u
            c.drawText(if (m.headset) "Earphones" else "Speaker", (RIGHT_X + BTN / 2f) * u, LABEL_Y * u - sub.ascent(), sub)
            sub.alpha = 255
        }
        if (k > 0f) {
            val on = m.speaker
            button(c, LEFT_X, u, press(Side.LEFT) * (0.6f + 0.4f * k), k, if (on) 0xE6FFFFFF.toInt() else 0x4D808080, art[if (on) ART_SPEAKER_ON else ART_SPEAKER_OFF])
        }
        // The red one: on the left while it rings, on the right once answered.
        val redX = LEFT_X + (RIGHT_X - LEFT_X) * k
        button(c, redX, u, press(if (m.ringing) Side.LEFT else Side.RIGHT), 1f, 0xFFFA382E.toInt(), art[ART_END])
    }

    /** One round button with its corner at [left] (rear pixels), [scale]d about its middle, at [alpha]. */
    private fun button(c: Canvas, left: Float, u: Float, scale: Float, alpha: Float, color: Int?, glyph: Bitmap?, full: Boolean = false) {
        if (alpha <= 0.003f) return
        val cx = (left + BTN / 2f) * u; val cy = (BTN_Y + BTN / 2f) * u
        val r = BTN / 2f * u * scale
        if (color != null) {
            fill.color = color
            fill.alpha = Math.round(Color.alpha(color) * alpha)
            c.drawCircle(cx, cy, r, fill)
        }
        if (glyph != null) {
            val half = if (full) r else ICON / 2f * u * scale
            rect.set(cx - half, cy - half, cx + half, cy + half)
            pic.alpha = Math.round(255f * alpha)
            c.drawBitmap(glyph, null, rect, pic)
        }
    }

    private fun cut(s: String, room: Float, p: TextPaint): String {
        val one = s.replace('\n', ' ').trim()
        if (p.measureText(one) <= room) return one
        var n = one.length
        while (n > 1 && p.measureText(one.substring(0, n).trimEnd() + "…") > room) n--
        return one.substring(0, NoticePop.whole(one, n)).trimEnd() + "…"
    }

    const val CHANGE_MS = 350f
    const val PRESS_MS = 260f
    const val RING_MS = 1_500L
}
