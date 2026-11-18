package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel

class ThreeHold(private val maxX: Int, private val maxY: Int) {

    sealed interface Out {
        /** A hold is under way: ask [due] at [atMs] (the reader's clock). Said again whenever that time changes. */
        data class Arm(val atMs: Long) : Out
        /**
         * The hold: [fingers] in it, [spreadMs] from the first of its three landing to the third, held [heldMs] since
         * the third landed; [x], [y]: where the first landed, in panel units; [widest]: the largest TOUCH_MAJOR or
         * TOUCH_MINOR any of its fingers reported (0: the panel sent none), to measure a fingertip by. For the log.
         */
        data class Fire(val fingers: Int, val spreadMs: Long, val heldMs: Long, val x: Float, val y: Float, val widest: Int = 0) : Out
    }

    private var slot = 0
    private val down = BooleanArray(SLOTS)
    private val fresh = BooleanArray(SLOTS)
    private val at = LongArray(SLOTS)
    private val x = IntArray(SLOTS) { -1 }
    private val y = IntArray(SLOTS) { -1 }
    private val landX = IntArray(SLOTS) { -1 }
    private val landY = IntArray(SLOTS) { -1 }
    private val strayed = BooleanArray(SLOTS)
    private val palm = BooleanArray(SLOTS)
    // The slot's last TOUCH_MAJOR and TOUCH_MINOR (the kernel re-sends an axis only when it changes), and the largest
    // either has been during the contact.
    private val major = IntArray(SLOTS)
    private val minor = IntArray(SLOTS)
    private val widest = IntArray(SLOTS)
    /** The fingers of the hold that fired, until each lifts. */
    private val inHold = BooleanArray(SLOTS)
    private var fired = false
    private var armedFor: Long? = null

    /** What this line completes: an [Out.Arm] when a hold's time changes, an [Out.Fire] when it is complete; mostly null. */
    @Synchronized
    fun feed(line: String, nowMs: Long): Out? {
        val (type, code, value) = RearTouches.parse(line) ?: return null
        if (type == EV_SYN && code == SYN_REPORT) return frame(nowMs)
        if (type == EV_SYN && code == SYN_DROPPED) { dropped(); return null }
        if (type != EV_ABS) return null
        when (code) {
            ABS_MT_SLOT -> slot = value
            ABS_MT_TRACKING_ID -> if (slot in 0 until SLOTS) { if (value == -1) lift(slot) else land(slot) }
            ABS_MT_POSITION_X -> if (slot in 0 until SLOTS) x[slot] = value
            ABS_MT_POSITION_Y -> if (slot in 0 until SLOTS) y[slot] = value
            ABS_MT_TOUCH_MAJOR -> if (slot in 0 until SLOTS) major[slot] = value
            ABS_MT_TOUCH_MINOR -> if (slot in 0 until SLOTS) minor[slot] = value
            // Kept per slot, as the kernel re-sends an axis only when it changes.
            ABS_MT_TOOL_TYPE -> if (slot in 0 until SLOTS) palm[slot] = value == MT_TOOL_PALM
        }
        return null
    }

    /** The hold, if it is complete at [nowMs] and has not fired: asked at [Out.Arm.atMs]. Once. */
    @Synchronized
    fun due(nowMs: Long): Out.Fire? {
        val g = group() ?: return null
        return if (nowMs >= g.dueAt) fire(g, nowMs) else null
    }

    /** Contacts down now (any, palms too). */
    @Synchronized
    fun fingersDown(): Int = down.count { it }

    private fun land(s: Int) {
        // A new contact where one is still down: that one's lift was lost. It has lifted (a finger of the hold too).
        if (down[s]) lift(s)
        down[s] = true; fresh[s] = true; strayed[s] = false; inHold[s] = false; widest[s] = 0
    }

    /** SYN_DROPPED: what is down is no longer known. Every contact forgotten (one still down comes back as it lands again). */
    private fun dropped() {
        down.fill(false); fresh.fill(false); strayed.fill(false); inHold.fill(false)
        fired = false
        armedFor = null
    }

    /** Not a fingertip: a palm by the panel's word, or wide enough to fill the size axis. */
    private fun notFinger(s: Int) = palm[s] || widest[s] >= WIDE

    private fun lift(s: Int) {
        down[s] = false; fresh[s] = false
        if (inHold[s]) {
            inHold[s] = false
            // Every finger of the hold that fired has lifted: the next hold may fire.
            if (inHold.none { it }) fired = false
        }
    }

    private fun frame(nowMs: Long): Out? {
        for (s in 0 until SLOTS) {
            if (!down[s]) continue
            widest[s] = maxOf(widest[s], major[s], minor[s])
            if (fresh[s]) { fresh[s] = false; at[s] = nowMs; landX[s] = x[s]; landY[s] = y[s]; continue }
            if (landX[s] < 0) landX[s] = x[s]
            if (landY[s] < 0) landY[s] = y[s]
            if (strayed[s] || x[s] < 0 || y[s] < 0 || landX[s] < 0 || landY[s] < 0) continue
            val dx = (x[s] - landX[s]) * Panel.WIDTH / (maxX + 1f)
            val dy = (y[s] - landY[s]) * Panel.HEIGHT / (maxY + 1f)
            if (dx * dx + dy * dy > SLOP * SLOP) strayed[s] = true
        }
        val g = group()
        if (g != null && nowMs >= g.dueAt) return fire(g, nowMs)
        val due = g?.dueAt
        if (due == armedFor) return null
        armedFor = due
        return due?.let { Out.Arm(it) }
    }

    /** Three of the fingers that may still be a hold, landed together: the one that is complete soonest. */
    private class Group(val first: Int, val third: Int, val dueAt: Long)

    private fun group(): Group? {
        if (fired) return null
        val c = (0 until SLOTS).filter { down[it] && !fresh[it] && !notFinger(it) && !strayed[it] }.sortedBy { at[it] }
        var best: Group? = null
        // Sorted by landing, three in a row are the closest three that start with the first of them.
        for (i in 0..c.size - 3) {
            val first = c[i]; val third = c[i + 2]
            if (at[third] - at[first] > LAND_MS) continue
            val g = Group(first, third, at[third] + HOLD_MS)
            if (best == null || g.dueAt < best.dueAt) best = g
        }
        return best
    }

    private fun fire(g: Group, nowMs: Long): Out.Fire {
        fired = true
        armedFor = null
        // The hold's fingers: every one down that is not a palm and landed with or after the first of its three.
        var n = 0
        var wide = 0
        for (s in 0 until SLOTS) {
            inHold[s] = down[s] && !notFinger(s) && at[s] >= at[g.first]
            if (inHold[s]) { n++; wide = maxOf(wide, widest[s]) }
        }
        return Out.Fire(
            fingers = n,
            spreadMs = at[g.third] - at[g.first],
            heldMs = nowMs - at[g.third],
            x = landX[g.first] * Panel.WIDTH / (maxX + 1f),
            y = landY[g.first] * Panel.HEIGHT / (maxY + 1f),
            widest = wide,
        )
    }

    companion object {
        /** The third of the three lands within this of the first of them. */
        const val LAND_MS = 350L
        /** Each stays this near where it landed, in panel units (about 2.2 mm). */
        const val SLOP = 40f
        /** And they are held so this long after the third landed. */
        const val HOLD_MS = 500L
        /**
         * A contact this wide (TOUCH_MAJOR or TOUCH_MINOR) is taken for a palm, not a fingertip: the top of the rear
         * panel's size axis (0..255). Chosen without measuring, as the limit least likely to drop a real fingertip,
         * whatever unit the panel counts in; a tighter one waits for real fingertips and palms on the phone
         * ([Out.Fire.widest] in the log).
         */
        const val WIDE = 255

        private const val SLOTS = 10
        private const val EV_SYN = 0x00
        private const val SYN_REPORT = 0x00
        private const val SYN_DROPPED = 0x03
        private const val EV_ABS = 0x03
        private const val ABS_MT_SLOT = 0x2f
        private const val ABS_MT_TOOL_TYPE = 0x37
        private const val ABS_MT_TOUCH_MAJOR = 0x30
        private const val ABS_MT_TOUCH_MINOR = 0x31
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
        private const val MT_TOOL_PALM = 2
    }
}
