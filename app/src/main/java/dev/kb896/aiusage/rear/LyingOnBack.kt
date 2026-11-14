package dev.kb896.aiusage.rear

import kotlin.math.abs
import kotlin.math.max

/**
 * LYING ON ITS BACK: the rear display against the table, the front facing up, the phone AT
 * REST. Read from the raw accelerometer (gravity plus whatever the hand adds), in m/s²:
 *
 * - FLAT: z beyond [z] (+9.81 lying still; 9.0 is about 23° of tilt), x and y within [xy]
 *   (1.5, about 9°).
 * - STILL: every reading within [still] (0.4) of the run's first reading, on every axis. A
 *   phone held flat in the hand drifts and trembles past that within a second or two; one on
 *   a table moves by a few hundredths.
 * - SETTLED: flat and still for [settleMs] (3 s) without a break. A break — tilted, or moved
 *   past [still] — starts the run over from that reading.
 *
 * Settled, it ends — a LIFT — on the first reading that is no longer flat with some slack
 * (z under [liftZ] 8.0, or x or y beyond [liftXy] 2.5), or that moves more than [liftMove]
 * (2.5) from where it settled. A tap on the front of a phone lying there shakes it by well
 * under that; picking it up does not.
 *
 * Pure, times are the caller's clock (a sensor event's timestamp, in ms), so it is tested
 * without a sensor. Used by the poll service's watch (TableWatch), which tells the panel.
 */
class LyingOnBack(
    private val z: Float = Z,
    private val xy: Float = XY,
    private val still: Float = STILL,
    private val settleMs: Long = SETTLE_MS,
    private val liftZ: Float = LIFT_Z,
    private val liftXy: Float = LIFT_XY,
    private val liftMove: Float = LIFT_MOVE,
) {
    /** Settled on its back. */
    var down = false
        private set

    /** When the current flat, still run began, and its first reading; -1 while there is none. */
    private var since = -1L
    private var ax = 0f
    private var ay = 0f
    private var az = 0f

    /** A reading: true when it has just settled on its back, false when just lifted, null for no change. */
    fun reading(x: Float, y: Float, z: Float, now: Long): Boolean? {
        if (down) {
            val flat = z >= liftZ && abs(x) <= liftXy && abs(y) <= liftXy
            if (flat && moved(x, y, z) <= liftMove) return null
            down = false
            since = -1L
            return false
        }
        val flat = z > this.z && abs(x) < xy && abs(y) < xy
        if (!flat) {
            since = -1L
            return null
        }
        if (since < 0 || moved(x, y, z) > still) {
            since = now
            ax = x; ay = y; az = z
            return null
        }
        if (now - since < settleMs) return null
        down = true
        return true
    }

    /** The watch stopped: nothing known, and the next run counts from its own first reading. */
    fun reset() {
        since = -1L
        down = false
    }

    private fun moved(x: Float, y: Float, z: Float): Float = max(abs(x - ax), max(abs(y - ay), abs(z - az)))

    companion object {
        const val Z = 9.0f
        const val XY = 1.5f
        const val STILL = 0.4f
        const val SETTLE_MS = 3_000L
        const val LIFT_Z = 8.0f
        const val LIFT_XY = 2.5f
        const val LIFT_MOVE = 2.5f
    }
}
