package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.data.PinOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A value on a spring: it goes to [target] with the speed it has (units a second), so a finger's own speed carries on
 * into it when it is let go. Pure. A step is cut into pieces of at most 4 ms and never over 50 ms of time in all, so a
 * late frame neither throws it nor makes it leap.
 */
class Spring(value: Float = 0f) {
    var value = value
    var speed = 0f
    var target = value

    fun snap(v: Float) { value = v; target = v; speed = 0f }

    /** On by [ms]: stiffness [k] (1/s^2), damping ratio [zeta] (under 1 overshoots a little). */
    fun step(ms: Float, k: Float, zeta: Float) {
        var left = (ms / 1000f).coerceIn(0f, 0.05f)
        val c = 2f * zeta * sqrt(k)
        while (left > 0f) {
            val dt = minOf(left, 0.004f)
            speed += (-k * (value - target) - c * speed) * dt
            value += speed * dt
            left -= dt
        }
    }

    fun settled(eps: Float): Boolean = abs(value - target) < eps && abs(speed) < eps * 10f
}

class NoteCarousel {
    enum class Phase { OFF, OPEN, LEAVING, GIVE }

    data class Placed(val id: Long, val cx: Float, val cy: Float, val scale: Float, val held: Boolean, val dim: Float)

    var phase = Phase.OFF
        private set
    var ids: List<Long> = emptyList()
        private set
    var held = 0L
        private set
    val heldAt: Int get() = ids.indexOf(held)
    val active: Boolean get() = phase != Phase.OFF
    /** In the carousel, not on its way back to the page: it takes a finger and a tap. */
    val open: Boolean get() = phase == Phase.OPEN
    var carrying = false
        private set

    private val opening = Spring()
    private val camera = Spring()
    private class Card { val x = Spring(); val dip = Spring() }
    private val cards = HashMap<Long, Card>()

    private var grabX = 0f
    private var grabSlot = 0f
    private var spacingPx = SPACING
    private var p = 0f
    private var lastP = 0f
    private var lastAt = 0L
    private var speed = 0f
    private var carriedFrom: List<Long> = emptyList()
    private var lastStep = 0L

    /** The page lifts into the carousel: [order] as it is, [heldId] in front. */
    @Synchronized
    fun enter(order: List<Long>, heldId: Long) {
        if (heldId !in order) return
        ids = order; held = heldId; phase = Phase.OPEN; carrying = false
        cards.clear()
        for ((i, id) in order.withIndex()) cards[id] = Card().also { it.x.snap(i.toFloat()) }
        camera.snap(heldAt.toFloat())
        opening.snap(0f); opening.target = 1f
        lastStep = 0L
    }

    /** Held with nothing to put in order (one note): the page gives a little, and back. */
    @Synchronized
    fun give(heldId: Long) {
        if (phase == Phase.OPEN || phase == Phase.LEAVING) return
        ids = listOf(heldId); held = heldId; phase = Phase.GIVE; carrying = false
        cards.clear(); cards[heldId] = Card()
        camera.snap(0f)
        opening.snap(0f); opening.target = GIVE_DEPTH
        lastStep = 0L
    }

    /**
     * Back to the page, the held note growing into it where it now is. A carry under way is dropped where it is, and
     * the order it made is answered: nobody has told it yet (only a lift or a swap does), so the caller tells it.
     * Null when there is nothing new to tell.
     */
    @Synchronized
    fun leave(): List<Long>? {
        if (phase == Phase.OFF || phase == Phase.LEAVING) return null
        val untold = untold()
        if (carrying) settleCarry()
        phase = Phase.LEAVING
        opening.target = 0f
        camera.target = heldAt.toFloat()
        return untold
    }

    /** Off at once (the back dark, the list changed under it). Answers as [leave] does: the order a carry had made, untold. */
    @Synchronized
    fun reset(): List<Long>? {
        val untold = untold()
        phase = Phase.OFF; carrying = false; cards.clear(); ids = emptyList(); lastStep = 0L
        return untold
    }

    /** The order a carry under way has made since [grab], if it is not the one it began with: no one has told it. */
    private fun untold(): List<Long>? = if (carrying && ids != carriedFrom) ids else null

    /**
     * The notes as the app has them ([given]) in the carousel's order while it is up: a payload that comes during a carry
     * or a swap is from before it and must not put the old order back under his finger. A note pinned since keeps its
     * place in front, one binned since is not there (PinOrder.apply). Off or only giving: [given] as it is.
     */
    @Synchronized
    fun ordered(given: List<Pin>): List<Pin> =
        if (phase == Phase.OPEN || phase == Phase.LEAVING) PinOrder.applyPins(given, ids) else given

    /** A finger that landed at [x] (rear px of a canvas [w] wide) takes the held note along from where it is. */
    @Synchronized
    fun grab(x: Float, at: Long, w: Float) {
        if (phase != Phase.OPEN) return
        val card = cards[held] ?: return
        carrying = true
        spacingPx = SPACING * w / PANEL_W
        grabX = x; grabSlot = card.x.value; p = grabSlot; lastP = p; lastAt = at; speed = 0f
        carriedFrom = ids
    }

    /**
     * The carrying finger at [x]: the held note is where it has it, the row's camera after it. Answers the places the
     * held note has come to, one for each note it passed, in that order (each one soft tick, each RearNav's
     * NoteCarried); empty when it passed none.
     */
    @Synchronized
    fun drag(x: Float, at: Long): List<Int> {
        if (!carrying) return emptyList()
        val raw = grabSlot + (x - grabX) / spacingPx
        val hi = (ids.size - 1).toFloat()
        // Past either end it goes on a little and harder the further, as a page does at its end.
        p = when { raw < 0f -> -rubber(-raw); raw > hi -> hi + rubber(raw - hi); else -> raw }
        if (at > lastAt) {
            val v = (p - lastP) / (at - lastAt) * 1000f
            speed = if (at - lastAt > 80L) v else speed * 0.35f + v * 0.65f
            lastP = p; lastAt = at
        }
        val card = cards[held] ?: return emptyList()
        card.x.value = p; card.x.target = p; card.x.speed = speed
        camera.target = p
        val want = p.roundToInt().coerceIn(0, ids.size - 1)
        val passed = ArrayList<Int>(2)
        while (heldAt != want) {
            val from = heldAt
            ids = PinOrder.move(ids, from, from + if (want > from) 1 else -1)
            passed += heldAt
        }
        return passed
    }

    /** Let go at [x]: the note springs into its place with the finger's speed. Answers whether the order is not what it was at [grab]. */
    @Synchronized
    fun drop(x: Float, at: Long): Boolean {
        if (!carrying) return false
        drag(x, at)
        settleCarry()
        return ids != carriedFrom
    }

    private fun settleCarry() {
        carrying = false
        // No speed at all if the finger stood still before it lifted.
        cards[held]?.let { it.x.target = heldAt.toFloat(); it.x.speed = speed }
        // The row's camera keeps the speed it has from following: it comes after the note as it did.
        camera.target = heldAt.toFloat()
    }

    /** Where the held note is along the row, in places (for the tests and the log). */
    val heldPlace: Float @Synchronized get() = cards[held]?.x?.value ?: -1f

    /** A tap on the note [id]: it and the held one in each other's places, both moving there. Whether it was done. */
    @Synchronized
    fun swapWith(id: Long): Boolean {
        if (phase != Phase.OPEN || carrying || id == held) return false
        val i = heldAt; val j = ids.indexOf(id)
        if (i < 0 || j < 0) return false
        ids = PinOrder.swap(ids, i, j)
        // The other one goes under as they cross: a dip that springs back.
        cards[id]?.dip?.speed = DIP_KICK
        camera.target = j.toFloat()
        return true
    }

    /** On to [now] (uptime ms). Whether it still moves (and so is to be drawn again a frame later). */
    @Synchronized
    fun step(now: Long): Boolean {
        if (phase == Phase.OFF) return false
        val ms = if (lastStep == 0L) FRAME_MS else (now - lastStep).toFloat()
        lastStep = now
        opening.step(ms, K_OPEN, Z_OPEN)
        if (phase == Phase.GIVE && opening.target > 0f && opening.value >= opening.target * 0.92f) opening.target = 0f
        if (!carrying) camera.target = heldAt.toFloat()
        camera.step(ms, K_ROW, Z_ROW)
        var still = opening.settled(0.002f) && camera.settled(0.002f)
        for ((i, id) in ids.withIndex()) {
            val c = cards[id] ?: continue
            if (!(carrying && id == held)) { c.x.target = i.toFloat(); c.x.step(ms, K_ROW, Z_ROW) }
            c.dip.step(ms, K_DIP, Z_DIP)
            if (!c.x.settled(0.002f) || !c.dip.settled(0.002f)) still = false
        }
        if (carrying) still = false
        // At rest: exactly where it rests, so the last picture is the layout itself and not a hair off it.
        if (still) { opening.snap(opening.target); camera.snap(camera.target); for (c in cards.values) { c.x.snap(c.x.target); c.dip.snap(c.dip.target) } }
        if (still && (phase == Phase.LEAVING || phase == Phase.GIVE) && opening.target == 0f) {
            phase = Phase.OFF; cards.clear()
            return false
        }
        return !still
    }

    /** Where each card is, on a canvas [w] x [h]: the farthest first, the held note last (in front). */
    @Synchronized
    fun placed(w: Float, h: Float): List<Placed> {
        if (phase == Phase.OFF) return emptyList()
        val sx = w / PANEL_W; val sy = h / PANEL_H
        val o = opening.value.coerceIn(0f, 1.06f)
        val spacing = lerp(w, SPACING * sx, o)
        val centreX = lerp(w / 2f, CENTRE_X * sx, o)
        val centreY = lerp(h / 2f, CENTRE_Y * sy, o)
        val out = ArrayList<Placed>(ids.size)
        for (id in ids) {
            val c = cards[id] ?: continue
            val d = c.x.value - camera.value
            val isHeld = id == held
            // Further from the middle, smaller; the held note at its own size whatever it passes.
            val near = SIDE * (1f - 0.2f * (abs(d) - 1f).coerceIn(0f, 2f))
            val dip = c.dip.value
            val scale = (if (isHeld) lerp(1f, HELD, o) else lerp(1f, near, o)) * (1f - 0.18f * abs(dip).coerceAtMost(1f))
            val cx = centreX + d * spacing
            val cy = centreY - (if (isHeld) RAISE * sy * o else 0f) + dip * DIP * sy * o
            // Off the canvas altogether: not drawn.
            if (cx + w * scale / 2f < 0f || cx - w * scale / 2f > w) continue
            out += Placed(id, cx, cy, scale, isHeld, if (isHeld) 0f else DIM * o.coerceAtMost(1f))
        }
        // Back to front: the farthest from the middle and the deepest dip first, the held note last.
        return out.sortedWith(compareBy<Placed>({ it.held }, { -abs(it.cx - centreX) - abs(it.cy - centreY) }))
    }

    /** The note a tap at [x], [y] lands on (the one in front where they overlap), or null for the ground. */
    @Synchronized
    fun hit(x: Float, y: Float, w: Float, h: Float): Long? =
        placed(w, h).asReversed().firstOrNull { abs(x - it.cx) <= w * it.scale / 2f && abs(y - it.cy) <= h * it.scale / 2f }?.id

    /** How far the carousel is open, 0 the page, 1 the carousel (for the tests and the log). */
    val openness: Float @Synchronized get() = opening.value

    private fun rubber(over: Float): Float = RUBBER * over / (over + RUBBER)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        const val PANEL_W = 976f
        const val PANEL_H = 596f
        /** The middle of what he sees (NoticePop.CENTRE): the lenses cover the left 296 px. */
        const val CENTRE_X = 636f
        const val CENTRE_Y = 298f
        /** The held note's card: 0.41 of the page (400 x 244 px), raised a little. */
        const val HELD = 0.41f
        const val RAISE = 12f
        /** Its neighbours': 0.30, one place apart [SPACING]: 140 px of each is seen beside the held one. */
        const val SIDE = 0.30f
        const val SPACING = 250f
        /** The neighbours a little darker than the one in front (not a fade: nothing comes or goes by it). */
        const val DIM = 0.32f
        /** A swap's dip: how far the other note goes under, as a part of [DIP] px, and its kick. */
        const val DIP = 40f
        const val DIP_KICK = 9f
        /** The give with one note: this part of the way to a card, and back. */
        const val GIVE_DEPTH = 0.12f
        /** Past an end: at most this many places further. */
        const val RUBBER = 0.35f
        const val FRAME_MS = 16f
        const val K_OPEN = 260f
        const val Z_OPEN = 0.84f
        const val K_ROW = 420f
        const val Z_ROW = 0.78f
        const val K_DIP = 300f
        const val Z_DIP = 0.62f
    }
}
