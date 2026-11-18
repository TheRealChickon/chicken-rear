package dev.kb896.aiusage.circle

import kotlin.math.abs
import kotlin.math.roundToLong

/** Reduce a getevent report to a down, the first movement beyond six pixels, or a release.
 * Coordinates persist per slot when the input driver omits an unchanged axis. */
internal class FrontTouches(
    private val down: (slot: Int, x: Int, y: Int) -> Unit,
    private val leave: (slot: Int, moved: Boolean, ageMs: Long?) -> Unit,
) {
    private class Slot {
        var x = -1
        var y = -1
        var ox = -1
        var oy = -1
        var began: Double? = null
        var active = false
        var fresh = false
        var moved = false
        var release = false
        fun begin(t: Double?) {
            active = true
            fresh = true
            moved = false
            release = false
            ox = -1
            oy = -1
            began = t
        }
    }

    private val slots = Array(10) { Slot() }
    private var slot = 0
    private var dropped = false
    private var recovering = false

    fun read(input: String) {
        val line = input.trimEnd()
        val valueAt = line.lastIndexOf(' ')
        if (valueAt < 0) return
        val prefix = line.substring(0, valueAt).trimEnd()
        val key = prefix.substringAfterLast(' ')
        val value = line.substring(valueAt + 1)
        fun stamp() = line.substringBefore(']', "").substringAfter('[', "").trim().toDoubleOrNull()
        fun age(s: Slot): Long? = s.began?.let { began -> stamp()?.let { ((it - began) * 1000).roundToLong() } }
        val current = slots.getOrNull(slot)
        if (dropped) {
            if (key == "SYN_REPORT") dropped = false
            return
        }
        when (key) {
            "ABS_MT_SLOT" -> slot = value.toIntOrNull(16) ?: -1
            "ABS_MT_TRACKING_ID" -> if (current != null) {
                if (value == "ffffffff") {
                    current.release = current.active
                    current.active = false
                } else {
                    if (current.active) leave(slot, false, age(current))
                    current.begin(stamp())
                }
            }
            "ABS_MT_POSITION_X", "ABS_MT_POSITION_Y" -> if (current != null) {
                val coord = value.toIntOrNull(16) ?: return
                if (key.endsWith("_X")) current.x = coord else current.y = coord
                if (recovering && !current.active) current.begin(stamp())
            }
            "BTN_TOUCH", "SYN_DROPPED" -> {
                recovering = key == "SYN_DROPPED"
                dropped = recovering
                for ((i, s) in slots.withIndex()) {
                    if (s.active && (dropped || !s.fresh)) {
                        leave(i, false, age(s))
                        s.active = false
                    }
                    if (dropped) slots[i] = Slot()
                }
            }
            "SYN_REPORT" -> for ((i, s) in slots.withIndex()) {
                if (s.fresh) {
                    down(i, if (s.x < 0) -1 else s.x / 100, if (s.y < 0) -1 else s.y / 100)
                    s.fresh = false
                }
                if (s.active && !s.moved) {
                    if (s.ox < 0) s.ox = s.x
                    if (s.oy < 0) s.oy = s.y
                    if (abs(s.x - s.ox) > 600 || abs(s.y - s.oy) > 600) {
                        s.moved = true
                        leave(i, true, age(s))
                    }
                }
                if (s.release) {
                    leave(i, false, age(s))
                    s.release = false
                }
            }
        }
    }
}
