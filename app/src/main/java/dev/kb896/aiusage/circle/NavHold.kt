package dev.kb896.aiusage.circle

import kotlin.math.abs

/** One gesture, identified jointly by the launcher's nav-bar line and a front touchscreen slot.
 * Deadlines use uptime on both sides of Binder. Touch ages use the kernel event timestamps. */
internal class NavHold(private val emit: (String, Long) -> Unit) {
    private data class Finger(val at: Long, val x: Int, val y: Int, var moved: Boolean = false, var lifted: Boolean = false)
    private val fingers = arrayOfNulls<Finger>(10)
    private var start: Long? = null
    private var previous: Long? = null
    private var x = -1
    private var y = -1
    private var hot = false
    private var drag = false
    private var travel = 0
    private var bound = -1
    private var quality = 0
    private var alreadyUp = false
    private var left = false
    private var second = false
    private var judged = false
    private var due: Long? = null

    private fun matches(f: Finger): Boolean {
        if (minOf(x, y, f.x, f.y) < 0) return false
        fun near(px: Int, py: Int) = abs(px - x) <= 40 && abs(py - y) <= 40
        return near(f.x, f.y) || near(f.y, 1199 - f.x) || near(2607 - f.y, f.x)
    }

    private fun bind(slot: Int, rank: Int) {
        val finger = fingers[slot]!!
        bound = slot
        quality = rank
        alreadyUp = finger.lifted
        left = finger.lifted || finger.moved
    }

    fun down(slot: Int, px: Int, py: Int, now: Long) {
        val f = Finger(now, px, py)
        fingers[slot] = f
        val since = start?.let { now - it } ?: return
        if (!judged && since < 250) {
            val rank = if (matches(f)) 2 else 1
            if (rank > quality || (alreadyUp && rank == quality && (rank == 2 || since < 60))) {
                bind(slot, rank)
                return
            }
        }
        if (bound == slot) {
            // The old contact ended. The new contact in the same slot is still down.
            if (!left && due?.let { now < it } == true) cancel()
            left = true
            bound = -1
        } else if (!judged && bound >= 0) second = true
    }

    fun depart(slot: Int, swipe: Boolean, age: Long?, now: Long) {
        val f = fingers[slot] ?: return
        if (swipe) f.moved = true else f.lifted = true
        if (bound != slot || left) return
        left = true
        val end = due ?: return
        val at = age?.let { f.at + it } ?: now
        if (at < end || (swipe && at < end + 180)) cancel()
        due = null
    }

    fun readerEnded() {
        for (i in fingers.indices) if (fingers[i]?.lifted != true) fingers[i] = null
        if (bound >= 0 && fingers[bound] == null) bound = -1
    }

    fun launcher(line: String, now: Long) {
        when {
            "Starting long press detection" in line -> {
                if (due?.let { now < it } == true) cancel()
                due = null
                previous = start
                start = now
                val pos = POSITION.find(line)
                x = pos?.groupValues?.get(1)?.toIntOrNull() ?: -1
                y = pos?.groupValues?.get(2)?.toIntOrNull() ?: -1
                hot = "in_hot_zone: false" !in line
                travel = 0
                drag = false
                second = false
                judged = false
                left = false
                alreadyUp = false
                bound = -1
                quality = 0
                val recent = fingers.indices.filter { fingers[it] != null }
                val position = recent.filter { now - fingers[it]!!.at < 1000 && matches(fingers[it]!!) }
                    .maxByOrNull { fingers[it]!!.at }
                val time = recent.filter { now - fingers[it]!!.at < 250 }.maxByOrNull { fingers[it]!!.at }
                if (position != null) bind(position, 2) else if (time != null) bind(time, 1)
                emit(PREPARE, 0)
            }
            start == null -> return
            "Long press condition met" in line -> {
                if (judged || drag || travel > 60) return
                val old = previous
                val fast = armTime()
                if (old != null && now - old in 250..400 &&
                    (now - start!! < 150 || (fast != null && now < fast + 20))) return
                judged = true
                if (!left) arm(now + 100)
            }
            else -> {
                val up = "Some(Up)" in line || "Some(Cancel)" in line || "nav_long_press_ready=false" in line
                if ("Started(Drag)" in line) drag = true
                if ("action=Move" in line) Y.find(line)?.let { travel = y - (it.groupValues[1].toIntOrNull() ?: y) }
                if (up || drag || travel > 60) {
                    if (due?.let { now < it } == true) cancel()
                    // An explicit release supersedes a later blind launcher mark.
                    if (up && !judged) left = true
                }
            }
        }
    }

    fun armTime(): Long? {
        if (start == null || judged || !hot || second || quality != 2 || left || drag || travel > 60 || bound < 0) return null
        return fingers[bound]?.at?.plus(230)
    }

    fun tick(now: Long) {
        val at = armTime() ?: return
        if (now >= at) arm(maxOf(at + 70, now + 40))
    }

    private fun arm(end: Long) {
        judged = true
        due = end
        emit(ARM, end)
    }

    private fun cancel() {
        due = null
        emit(CANCEL, 0)
    }

    companion object {
        const val PREPARE = "PREPARE"
        const val ARM = "ARM"
        const val CANCEL = "CANCEL"
        private val POSITION = Regex("""detection at \((\d+)(?:\.\d+)?,\s*(\d+)(?:\.\d+)?\)""")
        private val Y = Regex(""", y=(\d+)""")
    }
}
