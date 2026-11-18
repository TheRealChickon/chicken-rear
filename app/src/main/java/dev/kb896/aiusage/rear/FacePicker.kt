package dev.kb896.aiusage.rear

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

class FacePicker(
    /** How many faces there are (at least one). */
    val count: Int,
    /** The face that was live when the hold began: its card is the live layer itself, the others are pictures. */
    val live: Int,
) {
    init { require(count >= 1 && live in 0 until count) }

    enum class Phase {
        /** The face shrinking into its card. */
        ENTER,
        /** Up: swiped, or at rest. */
        UP,
        /** Shutting: the middle card growing back to the whole back. */
        EXIT,
        /** Over: the face is the whole back again. */
        GONE,
    }

    var phase = Phase.ENTER
        private set

    /** The card the row rests on, or will once it has settled. */
    var index = live
        private set

    /** The dot that is lit (Xiaomi's holders swap at its own thresholds, [dotFor]). */
    var dot = live
        private set

    /** Chosen at the shut, or null for left without choosing. */
    var chosen: Int? = null
        private set

    /** Open progress: 0 = the face is the whole back, 1 = the picker at rest. One spring for every value of the enter. */
    private val open = FolmeSpring(0.95, 0.35, 0.0005)
    /** The row's offset in px, from the [index] card at its place. */
    private val row = FolmeSpring(0.95, 0.35, 0.05)
    /** Per card: its selection progress (alpha, inner stroke), the ring's alpha and its scale progress. */
    private val p = HashMap<Int, FolmeSpring>()
    private val ringA = HashMap<Int, FolmeSpring>()
    private val ringS = HashMap<Int, FolmeSpring>()
    /** The exit with a new face: its live layer in, the card picture over it out (Xiaomi's two springs). */
    private val liveIn = FolmeSpring(1.0, 0.45, 0.0005)
    private val pictureOut = FolmeSpring(1.0, 0.25, 0.0005)
    /** Seconds the exit still waits for the new face's layer to be built (Xiaomi's 150 ms). */
    private var exitWait = 0.0

    private var dragging = false
    /** The finger's own offset, before the ends' rubber band; and where it began for this finger. */
    private var raw = 0.0
    private var rawAtGrab = 0.0
    /** The card the dot is reckoned from: the one the row rested on when the finger came (Xiaomi's middle holder). */
    private var base = live

    init {
        open.x = 0.0; open.target = 1.0
        for (i in 0 until count) cardSprings(i, rest = true)
    }

    private fun cardSprings(i: Int, rest: Boolean) {
        val sel = if (i == index) 1.0 else 0.0
        p.getOrPut(i) { FolmeSpring(0.95, 0.35, 0.0005) }.also { if (rest) { it.x = sel; it.v = 0.0 }; it.target = sel }
        ringA.getOrPut(i) { FolmeSpring(0.95, 0.35, 0.0005) }.also { if (rest) { it.x = sel; it.v = 0.0 }; it.target = sel }
        ringS.getOrPut(i) { FolmeSpring(0.95, 0.35, 0.0005) }.also { if (rest) { it.x = sel; it.v = 0.0 }; it.target = sel }
    }

    /** Whether the picker takes a finger now (not while it shuts: Xiaomi's reads no touch until its springs rest). */
    val takesTouch: Boolean get() = phase == Phase.ENTER || phase == Phase.UP

    /** A finger has landed: a settle stops where it is, and the row is the finger's from there. */
    fun grab() {
        if (!takesTouch) return
        row.v = 0.0; row.target = row.x
        dragging = false
        rawAtGrab = unrubber(row.x)
        raw = rawAtGrab
        if (row.isAtRest()) base = index else base = (index - row.x / STEP).let { Math.round(it).toInt() }.coerceIn(0, count - 1)
    }

    /** The finger [dx] px from where it began to drag (left negative): the row follows it one to one. */
    fun drag(dx: Double) {
        if (!takesTouch) return
        dragging = true
        // One card per release: the row never shows more than one card's way from the card it rests on.
        raw = (rawAtGrab + dx).coerceIn(-STEP, STEP)
        row.x = rubber(raw); row.v = 0.0; row.target = row.x
        settleCards()
    }

    /**
     * The finger is gone at [vx] px/s (left negative). The row moves on by one card if it was flung over
     * [COMMIT_SPEED] the way it was dragged, or dragged over half a card; else back. Either way by a spring that
     * starts at the finger's speed.
     */
    fun release(vx: Double) {
        if (!takesTouch) return
        dragging = false
        val off = row.x
        val dir = sign(off).toInt()   // -1: the row went left (the next card comes), +1: right (the previous)
        val can = dir != 0 && (index - dir) in 0 until count
        val commit = can && (abs(off) > COMMIT_DISTANCE || vx * dir > COMMIT_SPEED)
        if (commit) {
            index -= dir
            // The same place on the screen, now reckoned from the new card.
            row.x = off - dir * STEP
        }
        row.v = vx
        row.target = 0.0
        // The ring goes to the card the row will rest on (at the settle's start, as Xiaomi's does).
        settleCards()
    }

    /** The finger's vertical drift, or anything else, takes the drag away: the row goes back where it rests. */
    fun cancelDrag() {
        if (!takesTouch) return
        dragging = false
        row.target = 0.0
        settleCards()
    }

    /**
     * Shut: [pick] true chooses the middle card (a tap), false leaves the face as it was. [build]: the chosen face's
     * live layer must still be made (a face not live now), so the exit waits [EXIT_WAIT_S] for it, as Xiaomi's does.
     */
    fun shut(pick: Boolean, build: Boolean = pick && index != live) {
        if (phase == Phase.EXIT || phase == Phase.GONE) return
        dragging = false
        row.target = 0.0
        chosen = if (pick) index else null
        // Left without choosing: the old face is what grows back, so the row goes back to it first.
        if (!pick && index != live) {
            row.x = row.x - (index - live) * STEP
            index = live
        }
        settleCards()
        phase = Phase.EXIT
        exitWait = if (build) EXIT_WAIT_S else 0.0
        open.target = 0.0
        if (changesFace) { liveIn.x = 0.0; liveIn.target = 1.0; pictureOut.x = 1.0; pictureOut.target = 0.0 }
    }

    /** The shut chose a face other than the one that was live. */
    val changesFace: Boolean get() = chosen != null && chosen != live

    private fun settleCards() {
        val z = row.x / STEP
        for (i in 0 until count) {
            val sel = when {
                i == index -> 1.0 - min(1.0, abs(z))
                z < 0 && i == index + 1 -> min(1.0, abs(z))
                z > 0 && i == index - 1 -> min(1.0, abs(z))
                else -> 0.0
            }
            p[i]!!.target = sel
            val ring = if (i == index) 1.0 else 0.0
            ringA[i]!!.target = if (i == index) sel else 0.0
            ringS[i]!!.target = ring
        }
    }

    /** Advances everything by [dt] seconds of real time. True while anything still moves. */
    fun step(dt: Double): Boolean {
        if (phase == Phase.GONE) return false
        var h = dt
        if (phase == Phase.EXIT && exitWait > 0.0) {
            val w = min(exitWait, h); exitWait -= w; h -= w
            if (h <= 0.0) { stepCards(dt); return true }
        }
        open.step(h)
        if (!dragging) row.step(h)
        if (changesFace && phase == Phase.EXIT) { liveIn.step(h); pictureOut.step(h) }
        if (!dragging) settleCards()
        stepCards(dt)
        dot = dotFor(base, index - row.x / STEP).coerceIn(0, count - 1)
        if (phase == Phase.ENTER && open.isAtRest()) phase = Phase.UP
        if (!dragging && row.isAtRest()) base = index
        if (phase == Phase.EXIT && open.isAtRest() && (!changesFace || liveIn.isAtRest() && pictureOut.isAtRest())) {
            phase = Phase.GONE; open.x = 0.0
            return false
        }
        return moving()
    }

    private fun stepCards(dt: Double) {
        for (s in p.values) s.step(dt)
        for (s in ringA.values) s.step(dt)
        for (s in ringS.values) s.step(dt)
    }

    /** Anything still moving (a finger down counts as moving: the shell draws every vsync then). */
    fun moving(): Boolean = phase != Phase.GONE && (dragging || exitWait > 0.0 || !open.isAtRest() || !row.isAtRest() ||
        p.values.any { !it.isAtRest() } || ringA.values.any { !it.isAtRest() } || ringS.values.any { !it.isAtRest() } ||
        changesFace && (!liveIn.isAtRest() || !pictureOut.isAtRest()))

    /** One card as it is to be drawn now. */
    data class Card(
        val index: Int,
        /** Centre, in rear pixels. */
        val cx: Double, val cy: Double,
        /** Of the whole 976 x 596 face. */
        val scale: Double,
        val alpha: Double,
        /** Corner radius on the screen, px. */
        val corner: Double,
        val strokeAlpha: Double,
        val ringAlpha: Double,
        val ringScale: Double,
        /** This card is the face that was live: the live layer itself, not a picture. */
        val live: Boolean,
    ) {
        val width get() = W * scale
        val height get() = H * scale
        val left get() = cx - width / 2
        val right get() = cx + width / 2
    }

    data class Dots(val count: Int, val lit: Int, val cx: Double, val cy: Double, val pitch: Double, val alpha: Double)

    data class Frame(
        val cards: List<Card>,
        val dots: Dots,
        /** The exit with a new face: its live layer's alpha, and the alpha of its picture over it. */
        val newFaceAlpha: Double,
        val pictureAlpha: Double,
        /** Open progress, 0 to 1. */
        val open: Double,
    )

    /** What is to be drawn now. Cards out of the panel are left out (the live one is always given, to be placed or hidden). */
    fun frame(): Frame {
        val e = open.x
        val scale = 1.0 + (SCALE - 1.0) * e
        val apart = W + (STEP - W) * e
        val middle = W / 2 + SHIFT * e
        val cards = ArrayList<Card>(4)
        for (i in 0 until count) {
            val cx = middle + (i - index) * apart + row.x
            val half = W * scale / 2
            val onPanel = cx + half > 0 && cx - half < W
            if (!onPanel && i != live) continue
            val sel = p[i]!!.x.coerceIn(0.0, 1.0)
            val closed = phase == Phase.GONE
            cards += Card(
                index = i, cx = cx, cy = H / 2, scale = scale,
                alpha = if (closed) 1.0 else 0.2 + 0.8 * sel,
                corner = if (closed) 0.0 else CORNER * scale,
                strokeAlpha = if (closed) 0.0 else 1.0 - sel,
                ringAlpha = (ringA[i]!!.x * e).coerceIn(0.0, 1.0),
                ringScale = 0.97 + 0.03 * (ringS[i]!!.x * e).coerceIn(0.0, 1.0),
                live = i == live,
            )
        }
        val pitch = if (count <= 1) DOT_PITCH else min(DOT_PITCH, (DOT_SPAN - 2 * DOT_RADIUS) / (count - 1))
        return Frame(
            cards = cards,
            dots = Dots(count, dot, DOT_CX, DOT_CY + DOT_DROP * (1.0 - e), pitch, e.coerceIn(0.0, 1.0)),
            newFaceAlpha = if (changesFace) liveIn.x.coerceIn(0.0, 1.0) else 1.0,
            pictureAlpha = if (changesFace) pictureOut.x.coerceIn(0.0, 1.0) else 0.0,
            open = e,
        )
    }

    /** The row's offset as drawn now, px (for the tests and the log). */
    val offset: Double get() = row.x
    val openProgress: Double get() = open.x

    /** The ends: past the first or last card the row gives by a rubber band, at most a third of a card. */
    private fun atEnd(off: Double) = off > 0 && index == 0 || off < 0 && index == count - 1
    private fun rubber(r: Double): Double = if (atEnd(r)) band(r) else r
    private fun unrubber(shown: Double): Double = if (atEnd(shown)) unband(shown) else shown

    companion object {
        const val W = 976.0
        const val H = 596.0
        /** The face scaled to its card. */
        const val SCALE = 0.595
        /** The middle card's centre: 604, 298 (its left edge 17 px right of the lenses; NOT x 636). */
        const val SHIFT = 116.0
        const val STEP = 620.0
        /** The card's corner, unscaled (61.3 px on the screen at 0.595). */
        const val CORNER = 103.0
        const val STROKE = 6.0
        const val STROKE_COLOR = 0x0FFFFFFF
        const val RING_STROKE = 11.0
        const val RING_RADIUS = 123.0
        const val RING_W = 1031.0
        const val RING_H = 651.0
        const val RING_COLOR = 0x99FFFFFF.toInt()
        const val DOT_RADIUS = 7.0
        const val DOT_PITCH = 37.0
        /** The dots' row is at most this wide; with more than 18 faces the pitch shrinks. */
        const val DOT_SPAN = 652.0
        const val DOT_CX = 636.0
        /** NOT MEASURED: 5 dp bottom margin plus miuix's small padding; to be checked on a reference capture. */
        const val DOT_CY = 553.0
        const val DOT_DROP = 135.0
        const val DOT_ON = 0xFFCCCCCC.toInt()
        const val DOT_OFF = 0xFF4C4C4C.toInt()
        /** A release commits over this speed the way it was dragged (px/s), or over half a card. */
        const val COMMIT_SPEED = 500.0
        const val COMMIT_DISTANCE = 310.0
        const val EXIT_WAIT_S = 0.150
        /** The most the ends' rubber band gives: 620 / 3. */
        val BAND_MAX = STEP / 3.0

        /** 620 · sign · (u − u² + u³/3), u = min(|raw| / 620, 1). */
        fun band(r: Double): Double {
            val u = min(abs(r) / STEP, 1.0)
            return sign(r) * STEP * (u - u * u + u * u * u / 3.0)
        }

        /** The finger offset that [band] turns into [shown] (the band rises on [0, 1], so a bisection finds it). */
        fun unband(shown: Double): Double {
            val y = min(abs(shown), BAND_MAX)
            var lo = 0.0; var hi = STEP
            repeat(60) { val mid = (lo + hi) / 2; if (band(mid) < y) lo = mid else hi = mid }
            return sign(shown) * (lo + hi) / 2
        }

        /**
         * The lit dot, as Xiaomi's holders swap (`C0308x.C`): from the card [base] the row rested on, the next one once
         * the row has gone more than half a card towards it, the previous one already at three tenths.
         */
        fun dotFor(base: Int, position: Double): Int {
            val z = base - position   // Xiaomi's z = offset / 620: negative towards the next card
            return when {
                z < -0.5 -> base + kotlin.math.ceil(-z - 0.5).toInt()
                z > 0.3 -> base - kotlin.math.ceil(z - 0.3).toInt()
                else -> base
            }
        }
    }
}

/**
 * Folme's spring (`spring(damping, response)`): mass 1, stiffness k = (2π / response)², damping c = 2 · damping · √k.
 * Integrated EXACTLY over each step (the closed form of the damped oscillator towards a fixed target), in substeps of
 * at most 1/60 s; it carries its velocity, so a finger's speed goes into it. [rest]: how near, in its own unit, is
 * at rest (and ten times that per second of speed).
 */
class FolmeSpring(val damping: Double, val response: Double, private val rest: Double) {
    val k = (2 * Math.PI / response).let { it * it }
    val c = 2 * damping * sqrt(k)
    var x = 0.0
    var v = 0.0
    var target = 0.0

    fun isAtRest(): Boolean = abs(x - target) < rest && abs(v) < rest * 10

    fun step(dt: Double) {
        if (dt <= 0.0) return
        if (isAtRest()) { x = target; v = 0.0; return }
        var left = dt
        while (left > 1e-9) {
            val h = min(left, 1.0 / 60.0)
            advance(h)
            left -= h
        }
        if (isAtRest()) { x = target; v = 0.0 }
    }

    private fun advance(t: Double) {
        val w = sqrt(k)
        val z = damping
        val x0 = x - target
        val v0 = v
        val nx: Double
        val nv: Double
        if (z < 1.0) {
            val wd = w * sqrt(1 - z * z)
            val a = exp(-z * w * t)
            val b = (v0 + z * w * x0) / wd
            nx = a * (x0 * cos(wd * t) + b * sin(wd * t))
            nv = a * ((-z * w) * (x0 * cos(wd * t) + b * sin(wd * t)) + (-x0 * wd * sin(wd * t) + b * wd * cos(wd * t)))
        } else if (z == 1.0) {
            val a = exp(-w * t)
            val b = v0 + w * x0
            nx = (x0 + b * t) * a
            nv = (b - w * (x0 + b * t)) * a
        } else {
            val s = sqrt(z * z - 1)
            val r1 = -w * (z - s); val r2 = -w * (z + s)
            val c2 = (v0 - r1 * x0) / (r2 - r1); val c1 = x0 - c2
            nx = c1 * exp(r1 * t) + c2 * exp(r2 * t)
            nv = c1 * r1 * exp(r1 * t) + c2 * r2 * exp(r2 * t)
        }
        x = target + nx
        v = nv
    }
}
