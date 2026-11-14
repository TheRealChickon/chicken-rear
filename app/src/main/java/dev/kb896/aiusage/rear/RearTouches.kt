package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel

/**
 * The rear touchscreen's first finger, from `getevent <device>` lines, as down, move and
 * up in panel pixels — the shell service cannot otherwise see a guest app's touches
 * (see EdgeGestures).
 *
 * Multitouch protocol B, as [BandTap] reads it: a tracking id starts a contact, -1 ends
 * it, positions arrive between SYN_REPORTs. Events are emitted on the SYN that closes a
 * frame, so a down carries its first position rather than none; a contact that lands and
 * lifts within one frame (a very light tap) is a down and then an up.
 *
 * Other fingers are ignored until the first lifts; a finger still down then is not
 * promoted, the next landing is the new first. The last position is kept across lifts, as
 * the kernel only re-sends an axis that changed, so a landing at the same X or Y as the
 * last contact arrives without it.
 */
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

    /** The events this line completes, in order: none for most lines. */
    fun feed(line: String, nowMs: Long): List<Event> {
        val t = line.trim().split(WHITESPACE).takeLast(3)
        if (t.size < 3) return emptyList()
        val type = t[0].toIntOrNull(16) ?: return emptyList()
        val code = t[1].toIntOrNull(16) ?: return emptyList()
        val value = t[2].toLongOrNull(16)?.toInt() ?: return emptyList()
        if (type == EV_SYN && code == SYN_REPORT) return frame(nowMs)
        if (type != EV_ABS) return emptyList()
        when (code) {
            ABS_MT_SLOT -> slot = value
            ABS_MT_TRACKING_ID -> if (value == -1) {
                if (slot == primary) lifted = true
            } else if (primary < 0) {
                primary = slot; landed = true
            } else if (slot != primary) second = slot
            ABS_MT_POSITION_X -> { if (slot in 0 until SLOTS) slotX[slot] = value; if (slot == primary) { rawX = value; moved = true } }
            ABS_MT_POSITION_Y -> { if (slot in 0 until SLOTS) slotY[slot] = value; if (slot == primary) { rawY = value; moved = true } }
        }
        return emptyList()
    }

    private fun frame(nowMs: Long): List<Event> {
        if (rawX < 0 || rawY < 0) {
            // Only before the very first position: nothing to report, but never stay stuck.
            if (lifted) primary = -1
            landed = false; lifted = false; moved = false
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
            primary = other
            rawX = slotX[other]; rawY = slotY[other]
            val nx = rawX * Panel.WIDTH / (maxX + 1f)
            val ny = rawY * Panel.HEIGHT / (maxY + 1f)
            downAt = nowMs; downX = nx; downY = ny; strayed = false
            moved = false
            return listOf(Up(x, y, nowMs), Down(nx, ny, nowMs))
        }
        if (landed) { downAt = nowMs; downX = x; downY = y; strayed = false }
        else if (moved && (kotlin.math.abs(x - downX) >= REST_SLOP || kotlin.math.abs(y - downY) >= REST_SLOP)) strayed = true
        val e: List<Event> = when {
            landed && lifted -> listOf(Down(x, y, nowMs), Up(x, y, nowMs))
            landed -> listOf(Down(x, y, nowMs))
            lifted -> listOf(Up(x, y, nowMs))
            moved && primary >= 0 -> listOf(Move(x, y, nowMs))
            else -> emptyList()
        }
        if (lifted) primary = -1
        landed = false; lifted = false; moved = false
        return e
    }

    private companion object {
        const val SLOTS = 10
        /** A finger down this long without moving [REST_SLOP] is resting: no tap (SceneTouch.TAP_MS) or swipe (SWIPE_MS) can come of it. */
        const val REST_MS = 700L
        const val REST_SLOP = 16f
        const val EV_SYN = 0x00
        const val SYN_REPORT = 0x00
        const val EV_ABS = 0x03
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        val WHITESPACE = Regex("""\s+""")
    }
}
