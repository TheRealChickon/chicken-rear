package dev.kb896.aiusage.rear

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object PinB {
    /** The back is told this early (its arrival is due at [REAR_AT]; a binder call and a payload read on the way). */
    const val TELL_AT = 2_090L
    /** The notification sound IS HEARD here, with the island (PinChime asks a little before). */
    const val CHIME_AT = 2_100L
    /** The back's arrival begins (Xiaomi: about 2160 after the front's first change; the front is done by then). */
    const val REAR_AT = 2_160L
    /** The card has gone from the front: only the island is left (a touch ends it, as before). */
    const val CARD_GONE_MS = 2_180L
    /** The island's way: grows at [ISLAND_AT], begins to collapse at [ISLAND_END_AT], gone by [FRONT_MS]. */
    const val ISLAND_AT = 2_100L
    const val ISLAND_END_AT = 4_300L
    const val FRONT_MS = 4_420L
    /** On the back: from its start to being the notes page (pin_show.pag's 60 frames at 60 per second). */
    const val BACK_MS = 1_000L
    const val VEIL_FRAMES = 60

    // ---- the draft's places, in Xiaomi's 1200 x 2608 front pixels ----
    const val SCREEN_W = 1200f
    const val CARD_X = 600f
    const val CARD_Y = 400f
    /** 0.68 of Xiaomi's 950-wide card. */
    const val CARD_W = 646f
    /** The back screen's corner, scaled to the card. */
    const val CARD_CORNER = 69f
    const val ASPECT = 976f / 596f
    const val HOLE_X = 600f
    const val HOLE_Y = 72f
    const val HOLE_R = 19f
    /** The content where it lifts off from when nobody says: as wide as the draft's picture on the page. */
    const val LIFT_W = 1104f
    const val LIFT_CORNER = 24f
    /** The light's delay inside the card's state (read 850 in Xiaomi's code, fitted 830 on its recording). */
    const val LIGHT_DELAY = 830L
    private const val UNTIL = 2_400

    /** Where it plays: the screen's height and the camera hole in the 1200-wide units; where the content starts. */
    data class Place(
        val screenH: Float = 2608f,
        val holeX: Float = HOLE_X, val holeY: Float = HOLE_Y, val holeR: Float = HOLE_R,
        /** The content's middle and width as it lifts off; null: the middle of the screen, [LIFT_W]. */
        val fromX: Float? = null, val fromY: Float? = null, val fromW: Float? = null,
    )

    /** One frame of the front. All places in the 1200-wide units, angles in degrees. */
    data class Front(
        val blur: Float, val dim: Float,
        val cardX: Float, val cardY: Float, val cardW: Float, val cardH: Float, val cardCorner: Float,
        val rotY: Float, val thick: Float, val cardOp: Float,
        val glow: Float, val ftOp: Float, val ftSigma: Float, val edgeTh: Float,
        val sprOp: Float, val sprX: Float, val sprY: Float, val sprW: Float, val sprCorner: Float,
        val islandW: Float, val islandH: Float, val islandOp: Float, val islandContent: Float,
    )

    /** One frame of the back, [BACK_MS] long from its start. */
    data class Back(
        /** What was there blurs out: 0..100 (a radius in the panel's pixels). */
        val pageBlur: Float,
        /** The content over it, and how blurred (0..100). */
        val photoA: Float, val photoBlur: Float,
        /** Which of pin_show.pag's frames lies over everything; -1 none. */
        val veil: Int,
    )

    // ---------------------------------------------------------------------------------------------- the engine

    object Ease {
        fun linear(t: Float) = t
        fun cubicOut(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
        fun cubicIn(t: Float) = t * t * t
        fun cubicInOut(t: Float) = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
        fun quartOut(t: Float) = 1f - (1f - t).let { it * it * it * it }
        fun quartInOut(t: Float) = if (t < 0.5f) 8f * t * t * t * t else 1f - (-2f * t + 2f).let { it * it * it * it } / 2f
        fun sineOut(t: Float) = sin(t * PI / 2).toFloat()
        fun sineInOut(t: Float) = (-(cos(PI * t) - 1.0) / 2.0).toFloat()
    }

    /** How a value goes to its target: Folme's spring(damping, response s), or a timed ease; each after [delay] ms. */
    sealed class How(val delay: Long) {
        class Spring(val damping: Float, val response: Float, delay: Long = 0) : How(delay)
        class Tween(val ease: (Float) -> Float, val ms: Long, delay: Long = 0) : How(delay)
    }

    class Move(val to: Float, val how: How? = null)

    /** A state that begins at [at]: each named value's moves, one after the other, [def] where a move has no way of its own. */
    class State(val at: Long, val def: How, val moves: Map<String, List<Move>>)

    private class Cur(var x: Float) {
        var v = 0f
        var active: Go? = null
        var next: Go? = null
        var chain = ArrayDeque<Move>()
    }

    private class Go(val to: Float, val how: How, val t0: Long) { var from = 0f }

    /**
     * Xiaomi's way of playing values, as the draft's `simulate` (keys.js) plays them, step for step: a spring keeps its
     * speed when it gets a new target (8 steps a ms), a timed ease starts from where the value is and ends at rest,
     * moves of one state are played one after the other (after a spring nothing follows: a spring does not end), a
     * new state cuts what is playing. [rest] holds every value's start; the result is each value at every whole ms.
     */
    fun simulate(rest: Map<String, Float>, states: List<State>, untilMs: Int): Map<String, FloatArray> {
        val cur = rest.mapValues { Cur(it.value) }
        val table = rest.keys.associateWith { FloatArray(untilMs + 1) }
        val sub = 8
        for (t in 0..untilMs) {
            for (s in states) if (t.toLong() == s.at) for ((n, list) in s.moves) {
                val c = cur[n] ?: continue
                val first = list.first()
                val how = first.how ?: s.def
                c.next = Go(first.to, how, t + how.delay)
                c.chain = ArrayDeque(list.drop(1).map { Move(it.to, it.how ?: s.def) })
            }
            for ((n, c) in cur) {
                c.next?.let { nx -> if (t >= nx.t0) { c.active = nx.also { it.from = c.x }; c.next = null } }
                val g = c.active
                if (g != null) when (val how = g.how) {
                    is How.Spring -> {
                        val w = (2 * PI / how.response).toFloat()
                        val dt = 0.001f / sub
                        repeat(sub) {
                            val acc = w * w * (g.to - c.x) - 2f * how.damping * w * c.v
                            c.v += acc * dt; c.x += c.v * dt
                        }
                    }
                    is How.Tween -> {
                        val u = min(1f, (t - g.t0).toFloat() / how.ms)
                        val nx = g.from + (g.to - g.from) * how.ease(u)
                        c.v = (nx - c.x) * 1000f; c.x = nx
                        if (u >= 1f) {
                            c.active = null; c.v = 0f
                            if (c.chain.isNotEmpty() && c.next == null) {
                                val q = c.chain.removeFirst()
                                val qh = q.how!!
                                c.next = Go(q.to, qh, t + qh.delay)
                            }
                        }
                    }
                }
                table.getValue(n)[t] = c.x
            }
        }
        return table
    }

    /** [table]'s value [n] at [ms], between whole ms by a straight line, held at its ends. */
    fun valueAt(table: Map<String, FloatArray>, n: String, ms: Float): Float {
        val a = table.getValue(n)
        val max = a.size - 1
        val t = ms.coerceIn(0f, max.toFloat())
        val i = floor(t).toInt(); val f = t - i; val j = min(max, i + 1)
        return a[i] * (1 - f) + a[j] * f
    }

    // ---------------------------------------------------------------------------------------------- draft B

    private fun sp(d: Float, r: Float, delay: Long = 0) = How.Spring(d, r, delay)
    private fun tw(e: (Float) -> Float, ms: Long, delay: Long = 0) = How.Tween(e, ms, delay)
    private fun m(to: Float, how: How? = null) = listOf(Move(to, how))

    /** Draft B's four states for [place] (drafts.js statesB). */
    fun states(place: Place): Pair<Map<String, Float>, List<State>> {
        val fromX = place.fromX ?: SCREEN_W / 2f
        val fromY = place.fromY ?: place.screenH / 2f
        val fromW = place.fromW ?: LIFT_W
        val rest = linkedMapOf(
            "blur" to 0f, "dim" to 0f,
            // The words have no picture on the page: the content comes in (Xiaomi's text sheet), it is not there at rest.
            "uiOp" to 0f, "uiW" to fromW, "uiX" to fromX, "uiY" to fromY, "uiCorner" to LIFT_CORNER,
            "cardOp" to 0f, "cW" to 0.06f * 950f, "rotY" to -180f, "cX" to place.holeX, "cY" to place.holeY,
            "edgeTh" to 0.01f, "ftOp" to 0f, "ftSigma" to 50f, "glow" to 0f,
        )
        val s1 = State(0L, sp(0.95f, 0.3f), mapOf(
            "blur" to m(100f), "dim" to m(0.12f), "uiOp" to m(1f, sp(1f, 0.25f)), "uiW" to m(700f),
            "uiY" to m(fromY + 80f, tw(Ease::cubicOut, 300)), "uiCorner" to m(44f),
        ))
        val ld = LIGHT_DELAY
        val s2 = State(200L, sp(0.95f, 0.4f), mapOf(
            "uiOp" to m(0f, tw(Ease::cubicOut, 200, ld)), "uiW" to m(600f, tw(Ease::cubicInOut, 930)),
            "uiX" to m(CARD_X, tw(Ease::cubicInOut, 930)), "uiY" to m(CARD_Y, tw(Ease::cubicInOut, 930)), "uiCorner" to m(CARD_CORNER),
            "cardOp" to m(1f),
            "cW" to listOf(Move(CARD_W, tw(Ease::quartInOut, 900)), Move(CARD_W * 0.6f / 0.68f, tw(Ease::sineOut, 150, 30)), Move(CARD_W, tw(Ease::cubicOut, 400, 30))),
            "rotY" to listOf(Move(0f, tw(Ease::cubicOut, 1000)), Move(10f, tw(Ease::linear, 1300))),
            "cX" to m(CARD_X, tw(Ease::cubicOut, 880)), "cY" to m(CARD_Y, tw(Ease::cubicOut, 880)),
            "ftOp" to m(1f, sp(0.9f, 0.45f, ld)), "ftSigma" to m(0f, sp(0.9f, 0.45f, ld)), "glow" to m(1f, sp(0.9f, 0.65f, ld)),
        ))
        val s3 = State(1_500L, sp(0.95f, 0.35f), mapOf(
            "blur" to m(0f, sp(0.95f, 0.25f, 100)), "dim" to m(0f, sp(0.95f, 0.25f, 100)), "uiOp" to m(0f),
            "cardOp" to m(0f, tw(Ease::sineOut, 180, 380)), "cW" to m(0.06f * 950f, tw(Ease::quartInOut, 630)),
            "rotY" to m(180f, tw(Ease::sineInOut, 680)), "cX" to m(place.holeX, tw(Ease::sineOut, 630)), "cY" to m(place.holeY, tw(Ease::quartInOut, 680)),
            "edgeTh" to m(0.04f, tw(Ease::quartInOut, 630)), "glow" to m(1f, tw(Ease::sineInOut, 830)),
        ))
        return rest to listOf(s1, s2, s3)
    }

    /** The front's values for one pin, made once ([place]), read every frame ([at]). */
    class Timeline(val place: Place = Place()) {
        private val table: Map<String, FloatArray> = states(place).let { (rest, s) -> simulate(rest, s, UNTIL) }
        private fun v(n: String, t: Float) = valueAt(table, n, t)

        fun at(ms: Float): Front {
            val t = ms.coerceAtLeast(0f)
            val cw = v("cW", t)
            val grow = when {
                t < ISLAND_AT -> 0f
                t < ISLAND_END_AT -> springTo(t, ISLAND_AT.toFloat(), 0f, 1f, 0.9f, 0.3f)
                else -> 1f - ((t - ISLAND_END_AT) / 110f).coerceIn(0f, 1f)
            }
            val base = 2f * place.holeR + 2f
            val islandOp = when {
                t < ISLAND_AT -> 0f
                t < ISLAND_END_AT -> ((t - ISLAND_AT) / 60f).coerceIn(0f, 1f)
                else -> 1f - Ease.cubicIn(((t - ISLAND_END_AT - 30f) / 90f).coerceIn(0f, 1f))
            }
            val content = if (t < ISLAND_END_AT) seg(t, 2_250f, 2_380f, 0f, 1f, Ease::cubicOut) else 1f - ((t - ISLAND_END_AT) / 60f).coerceIn(0f, 1f)
            return Front(
                blur = v("blur", t).coerceIn(0f, 100f), dim = v("dim", t).coerceIn(0f, 1f),
                cardX = v("cX", t), cardY = v("cY", t), cardW = cw, cardH = cw / ASPECT, cardCorner = cw * (CARD_CORNER / CARD_W),
                rotY = v("rotY", t), thick = maxOf(2f, 0.017f * cw), cardOp = v("cardOp", t).coerceIn(0f, 1f),
                glow = v("glow", t).coerceIn(0f, 1.2f), ftOp = v("ftOp", t).coerceIn(0f, 1.2f), ftSigma = v("ftSigma", t).coerceIn(0f, 60f), edgeTh = v("edgeTh", t),
                sprOp = v("uiOp", t).coerceIn(0f, 1f), sprX = v("uiX", t), sprY = v("uiY", t), sprW = v("uiW", t), sprCorner = v("uiCorner", t).coerceAtLeast(0f),
                // Xiaomi's 236 x 90, and never smaller than round a larger camera hole (an emulator's).
                islandW = base + (maxOf(236f, base + 146f) - base) * grow, islandH = base + (maxOf(90f, base) - base) * grow, islandOp = islandOp.coerceIn(0f, 1f), islandContent = content.coerceIn(0f, 1f),
            )
        }
    }

    /** The back, [ms] after its own start (drafts.js rearAt, B). */
    fun back(ms: Float): Back {
        val r = ms
        return Back(
            pageBlur = 100f * seg(r, 0f, 330f, 0f, 1f, Ease::sineOut),
            photoA = seg(r, 100f, 500f, 0f, 1f, Ease::cubicOut),
            photoBlur = if (r < 300f) 100f else seg(r, 300f, 600f, 100f, 0f, Ease::quartOut),
            veil = if (r >= 0f && r < BACK_MS) floor(r * 0.06f).toInt().coerceIn(0, VEIL_FRAMES - 1) else -1,
        )
    }

    /** A timed ease from [a] at [t0] to [b] at [t1]. */
    fun seg(t: Float, t0: Float, t1: Float, a: Float, b: Float, e: (Float) -> Float = Ease::linear): Float =
        if (t <= t0) a else if (t >= t1) b else a + (b - a) * e((t - t0) / (t1 - t0))

    /** A spring from rest at [a] to [b], let go at [t0] (closed form). */
    fun springTo(t: Float, t0: Float, a: Float, b: Float, zeta: Float, resp: Float): Float {
        if (t <= t0) return a
        val s = (t - t0) / 1000.0
        val w = 2 * PI / resp
        val y = if (zeta >= 1f) 1 - (1 + w * s) * exp(-w * s) else {
            val wd = w * sqrt(1.0 - zeta * zeta)
            1 - exp(-zeta * w * s) * (cos(wd * s) + (zeta * w / wd) * sin(wd * s))
        }
        return (a + (b - a) * y).toFloat()
    }
}
