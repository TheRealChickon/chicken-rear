package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel

class RearTouches(private val maxX: Int, private val maxY: Int) {

    sealed interface Event { val x: Float; val y: Float; val atMs: Long }
    data class Down(override val x: Float, override val y: Float, override val atMs: Long) : Event
    data class Move(override val x: Float, override val y: Float, override val atMs: Long) : Event
    data class Up(override val x: Float, override val y: Float, override val atMs: Long) : Event

    private var slot = 0
    private var primary = -1
    private var landed = false
    private var lifted = false
    private var moved = false
    private var rawX = -1
    private var rawY = -1
    // Each slot's last place (the kernel only re-sends an axis that changed), for a second finger that takes over.
    private val slotX = IntArray(SLOTS) { -1 }
    private val slotY = IntArray(SLOTS) { -1 }
    /** A second finger has landed while the first is down: its slot, or -1. */
    private var second = -1
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var strayed = false

    // Every contact, for [crowded]: down, landed in this frame, where it last came to rest and since when (gone
    // [REST_SLOP] from there, it starts again where it is), and whether it is a resting finger let go (it never crowds
    // the first).
    private val slotDown = BooleanArray(SLOTS)
    private val slotFresh = BooleanArray(SLOTS)
    private val slotStillAt = LongArray(SLOTS)
    private val slotRestX = IntArray(SLOTS) { -1 }
    private val slotRestY = IntArray(SLOTS) { -1 }
    private val slotLetGo = BooleanArray(SLOTS)

    /** The first finger is not alone: another, not a resting one, has been down with it. Until it lifts. */
    var crowded = false
        private set

    /** The line just fed made the first finger [crowded] (once per finger). */
    var crowdBegan = false
        private set

    /** The events this line completes, in order: none for most lines. */
    fun feed(line: String, nowMs: Long): List<Event> {
        crowdBegan = false
        val (type, code, value) = parse(line) ?: return emptyList()
        if (type == EV_SYN && code == SYN_REPORT) return frame(nowMs)
        if (type == EV_SYN && code == SYN_DROPPED) { dropped(); return emptyList() }
        if (type != EV_ABS) return emptyList()
        when (code) {
            ABS_MT_SLOT -> slot = value
            ABS_MT_TRACKING_ID -> if (value == -1) {
                if (slot == primary) lifted = true
                if (slot in 0 until SLOTS) { slotDown[slot] = false; slotFresh[slot] = false; slotLetGo[slot] = false }
            } else {
                if (slot in 0 until SLOTS) { slotDown[slot] = true; slotFresh[slot] = true; slotLetGo[slot] = false }
                if (primary < 0) {
                    primary = slot; landed = true
                } else if (slot != primary) second = slot
            }
            ABS_MT_POSITION_X -> { if (slot in 0 until SLOTS) slotX[slot] = value; if (slot == primary) { rawX = value; moved = true } }
            ABS_MT_POSITION_Y -> { if (slot in 0 until SLOTS) slotY[slot] = value; if (slot == primary) { rawY = value; moved = true } }
        }
        return emptyList()
    }

    private fun frame(nowMs: Long): List<Event> {
        contacts(nowMs)
        if (rawX < 0 || rawY < 0) {
            // Only before the very first position: nothing to report, but never stay stuck.
            if (lifted) { primary = -1; crowded = false }
            landed = false; lifted = false; moved = false
            slotFresh.fill(false)
            return emptyList()
        }
        val x = rawX * Panel.WIDTH / (maxX + 1f)
        val y = rawY * Panel.HEIGHT / (maxY + 1f)
        // A SECOND FINGER WHILE THE FIRST ONLY RESTS (the hand that holds the phone has a finger on this screen: down
        // for longer than any tap or swipe and not moved): the resting one is let go and the new one is THE finger,
        // or no stroke was ever seen while he held the phone (the review's 16). A first finger that is doing
        // something keeps the back to itself, as before.
        val other = second
        second = -1
        if (other in 0 until SLOTS && primary >= 0 && !landed && !lifted && !strayed && nowMs - downAt > REST_MS && slotX[other] >= 0 && slotY[other] >= 0) {
            if (primary in 0 until SLOTS) slotLetGo[primary] = true
            primary = other
            rawX = slotX[other]; rawY = slotY[other]
            val nx = rawX * Panel.WIDTH / (maxX + 1f)
            val ny = rawY * Panel.HEIGHT / (maxY + 1f)
            downAt = nowMs; downX = nx; downY = ny; strayed = false
            moved = false
            newFirst(nowMs)
            // Another finger that landed in this same frame is with the new one.
            crowd()
            slotFresh.fill(false)
            return listOf(Up(x, y, nowMs), Down(nx, ny, nowMs))
        }
        if (landed) { downAt = nowMs; downX = x; downY = y; strayed = false; newFirst(nowMs) }
        else if (moved && (kotlin.math.abs(x - downX) >= REST_SLOP || kotlin.math.abs(y - downY) >= REST_SLOP)) strayed = true
        crowd()
        val e: List<Event> = when {
            landed && lifted -> listOf(Down(x, y, nowMs), Up(x, y, nowMs))
            landed -> listOf(Down(x, y, nowMs))
            lifted -> listOf(Up(x, y, nowMs))
            moved && primary >= 0 -> listOf(Move(x, y, nowMs))
            else -> emptyList()
        }
        if (lifted) { primary = -1; crowded = false }
        landed = false; lifted = false; moved = false
        slotFresh.fill(false)
        return e
    }

    /**
     * Every contact as its frame closes: where and since when it has lain still. Landed, or gone [REST_SLOP] from where
     * it lay (on either axis), it starts again there and then.
     */
    private fun contacts(nowMs: Long) {
        for (s in 0 until SLOTS) {
            if (!slotDown[s]) continue
            if (slotFresh[s]) { slotStillAt[s] = nowMs; slotRestX[s] = slotX[s]; slotRestY[s] = slotY[s]; continue }
            if (slotRestX[s] < 0) slotRestX[s] = slotX[s]
            if (slotRestY[s] < 0) slotRestY[s] = slotY[s]
            if (slotX[s] < 0 || slotY[s] < 0 || slotRestX[s] < 0 || slotRestY[s] < 0) continue
            val dx = kotlin.math.abs(slotX[s] - slotRestX[s]) * Panel.WIDTH / (maxX + 1f)
            val dy = kotlin.math.abs(slotY[s] - slotRestY[s]) * Panel.HEIGHT / (maxY + 1f)
            if (dx >= REST_SLOP || dy >= REST_SLOP) { slotStillAt[s] = nowMs; slotRestX[s] = slotX[s]; slotRestY[s] = slotY[s] }
        }
    }

    /**
     * A new first finger (landed, or taken over): not crowded yet, and every other contact that has lain still for
     * longer than [REST_MS] is a resting finger, let go: the hand that holds the phone, though it drifted as it settled.
     */
    private fun newFirst(nowMs: Long) {
        crowded = false
        for (s in 0 until SLOTS) {
            if (s == primary || !slotDown[s] || slotFresh[s]) continue
            if (nowMs - slotStillAt[s] > REST_MS) slotLetGo[s] = true
        }
    }

    /**
     * SYN_DROPPED: the kernel lost events, lifts among them perhaps. Every contact but the first is forgotten (one
     * still down is seen again when it next lands), so a lost lift never crowds the fingers after it.
     */
    private fun dropped() {
        for (s in 0 until SLOTS) {
            if (s == primary) continue
            slotDown[s] = false; slotFresh[s] = false; slotLetGo[s] = false
        }
        second = -1
    }

    /** Another contact down with the first, not let go: [crowded], once. */
    private fun crowd() {
        if (crowded || primary < 0) return
        for (s in 0 until SLOTS) {
            if (s != primary && slotDown[s] && !slotLetGo[s]) { crowded = true; crowdBegan = true; return }
        }
    }

    companion object {
        private const val SLOTS = 10
        /** A finger down this long without moving [REST_SLOP] is resting: no tap (SceneTouch.TAP_MS) or swipe (SWIPE_MS) can come of it. */
        const val REST_MS = 700L
        private const val REST_SLOP = 16f
        private const val EV_SYN = 0x00
        private const val SYN_REPORT = 0x00
        private const val SYN_DROPPED = 0x03
        private const val EV_ABS = 0x03
        private const val ABS_MT_SLOT = 0x2f
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
        private val WHITESPACE = Regex("""\s+""")

        /** One `getevent` line as type, code and value (hex; a device prefix allowed); null for anything else. */
        fun parse(line: String): Triple<Int, Int, Int>? {
            val t = line.trim().split(WHITESPACE).takeLast(3)
            if (t.size < 3) return null
            val type = t[0].toIntOrNull(16) ?: return null
            val code = t[1].toIntOrNull(16) ?: return null
            val value = t[2].toLongOrNull(16)?.toInt() ?: return null
            return Triple(type, code, value)
        }

        /**
         * Where in one line's [events] the crowding applies ([crowdBegan]): after its last Down (the finger that has
         * just landed is the one crowded, and a takeover's new finger is), before anything else (a Move, the Up of a
         * finger lifting in the same frame). [events].size: after them all.
         */
        fun crowdIndex(events: List<Event>): Int = events.indexOfLast { it is Down } + 1
    }
}
