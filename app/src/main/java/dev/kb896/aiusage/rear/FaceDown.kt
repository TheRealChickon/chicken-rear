package dev.kb896.aiusage.rear

import kotlin.math.abs

/** One resting posture for the table, Always on, and the shell's front dim. Times are monotonic milliseconds. */
class FaceDown {
    var down = false
        private set

    private data class Sample(val x: Float, val y: Float, val z: Float, val at: Long) {
        fun distanceSquared(other: Sample): Float {
            val dx = x - other.x
            val dy = y - other.y
            val dz = z - other.z
            return dx * dx + dy * dy + dz * dz
        }
    }

    private val window = ArrayDeque<Sample>()
    private var eligibleSince: Long? = null
    private var canEnterAt = 0L
    private var resting: Sample? = null

    /** A true lift or front interaction always wins over the dwell and cooldown. */
    fun conditions(near: Boolean, frontActive: Boolean, now: Long): Boolean? {
        if (near && !frontActive) return null
        window.clear()
        eligibleSince = null
        if (!down) return null
        down = false
        resting = null
        canEnterAt = now + REENTRY_MS
        return false
    }

    fun reading(x: Float, y: Float, z: Float, now: Long, near: Boolean, frontActive: Boolean): Boolean? {
        if (!near || frontActive) return conditions(near, frontActive, now)
        val sample = Sample(x, y, z, now)
        if (down) {
            val atRest = resting ?: return null
            val withinLiftLimits = z <= -6.5f && abs(x) <= 3.5f && abs(y) <= 3.5f &&
                sample.distanceSquared(atRest) <= 2.5f * 2.5f
            return if (withinLiftLimits) null else conditions(near = false, frontActive = false, now = now)
        }
        if (!flat(x, y, z) || now < canEnterAt) {
            window.clear()
            eligibleSince = null
            return null
        }
        val previous = window.lastOrNull()
        if (previous != null && now - previous.at !in 1..MAX_GAP_MS) {
            window.clear()
            eligibleSince = null
        }
        val began = eligibleSince ?: now.also { eligibleSince = it }
        window.addLast(sample)
        while (window.size > 1 && now - window[1].at >= STILL_MS) window.removeFirst()
        if (variance() > MAX_VARIANCE) {
            window.clear()
            window.addLast(sample)
            eligibleSince = now
            return null
        }
        if (now - began < ENTER_MS || now - window.first().at < STILL_MS) return null
        resting = sample
        window.clear()
        down = true
        return true
    }

    /** The sum of the three axis variances, in (m/s²)². */
    private fun variance(): Double {
        val first = window.first()
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var squares = 0.0
        for (s in window) {
            x += (s.x - first.x).toDouble()
            y += (s.y - first.y).toDouble()
            z += (s.z - first.z).toDouble()
            squares += s.distanceSquared(first)
        }
        val n = window.size.toDouble()
        return squares / n - (x * x + y * y + z * z) / (n * n)
    }

    companion object {
        const val STILL_MS = 1_500L
        const val ENTER_MS = 2_000L
        const val REENTRY_MS = 3_000L
        private const val MAX_GAP_MS = 350L
        private const val MAX_VARIANCE = 0.015

        fun flat(x: Float, y: Float, z: Float): Boolean =
            z in -11f..-8.6f && abs(x) < 2.2f && abs(y) < 2.2f

        /** Require a real near reading from the front sensor. Binary sensors report zero or their range. */
        fun near(value: Float, maximumRange: Float): Boolean =
            maximumRange.isFinite() && maximumRange > 0f && value >= 0f && value < maximumRange && value <= 2f
    }
}
