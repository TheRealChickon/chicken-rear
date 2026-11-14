package dev.kb896.aiusage.rear

/**
 * THE TABLE: gravity says the phone lies flat on its face, rear display up — gravity along
 * -z beyond [z], x and y within [xy], for [settleMs] without a break. Any reading outside
 * that is a lift, at once.
 *
 * One rule for both watches: the panel's own, while the back is lit (RearActivity), and the
 * poll service's wake-up one, while it is dark (TableWatch). Pure, and times are the
 * caller's clock (a sensor event's timestamp, in ms), so it is tested without a sensor.
 */
class FaceDown(
    private val z: Float = Z,
    private val xy: Float = XY,
    private val settleMs: Long = SETTLE_MS,
) {
    /** Settled on its face. */
    var down = false
        private set

    /** When the current flat run began; -1 while it is not flat. */
    private var flatSince = -1L

    /** A reading: true when it has just settled face down, false when just lifted, null for no change. */
    fun reading(x: Float, y: Float, z: Float, now: Long): Boolean? {
        val flat = z < -this.z && kotlin.math.abs(x) < xy && kotlin.math.abs(y) < xy
        if (!flat) {
            flatSince = -1L
            if (!down) return null
            down = false
            return false
        }
        if (flatSince < 0) flatSince = now
        if (down || now - flatSince < settleMs) return null
        down = true
        return true
    }

    /**
     * Told by the other watch that it lies on its face already: the next reading off the
     * table is a lift at once, and flat ones are no news — no second settling.
     */
    fun assumeDown() {
        down = true
    }

    /** The watch stopped: nothing known, and the next run counts from its own first reading. */
    fun reset() {
        flatSince = -1L
        down = false
    }

    companion object {
        const val Z = 8.6f
        const val XY = 2.2f
        const val SETTLE_MS = 1_000L
    }
}
