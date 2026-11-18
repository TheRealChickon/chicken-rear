package dev.kb896.aiusage.circle

import kotlin.math.abs
import kotlin.math.max

/**
 * One scrolling part of a page, followed while it moves (pure; see ScrollFollow).
 *
 * Readings are how far the content has moved up since the anchoring, each stamped with the display frame it belongs
 * to (the app's state after that frame). [at] gives the value for another frame: the last reading carried on with the
 * speed of the last ~30 ms, never more than [maxAheadNs] ahead, nothing carried on once the content has stopped.
 *
 * It also judges the source while it moves. A source is ROUGH when a reading lands far from where the earlier ones
 * said it would be ([jumpPx], or half the reading's own step if that is more) or when no reading came for more than
 * four frames while moving. Rough sources are not drawn (the caller hides their translations until the page rests):
 * a translation that lags and jumps is worse than one that is away for a moment.
 */
class FollowTrack(private val jumpPx: Float, private val frameNs: Long) {
    companion object {
        private const val RING = 16
        const val ROUGH_HITS = 2          // misses in one motion that make it rough
        const val CLEAN_READINGS = 6      // moving readings without a miss that make a motion clean
    }
    private val t = LongArray(RING)
    private val y = IntArray(RING)
    private var n = 0
    private var lastMoveT = Long.MIN_VALUE

    /** Misses in the current motion. */
    var hits = 0; private set
    /** Readings in the current motion that moved and were where they were expected. */
    var clean = 0; private set
    /** Largest miss in the current motion, px. */
    var worst = 0f; private set
    val rough: Boolean get() = hits >= ROUGH_HITS
    val count: Int get() = n
    val last: Int get() = if (n == 0) 0 else y[(n - 1) % RING]

    fun reset() { n = 0; hits = 0; clean = 0; worst = 0f; lastMoveT = Long.MIN_VALUE }
    /** A new motion begins (the page was at rest): the judgement starts again, the position is kept. */
    fun newMotion() { hits = 0; clean = 0; worst = 0f }

    /** True while the readings change (the last change is at most three frames old at [now]). */
    fun moving(now: Long) = lastMoveT != Long.MIN_VALUE && now - lastMoveT <= 3 * frameNs

    /** A reading for the frame [ft]. Returns the miss in px (0 if none was judged). */
    fun add(ft: Long, value: Int): Float {
        var miss = 0f
        if (n > 0) {
            val k1 = (n - 1) % RING
            if (ft <= t[k1]) return 0f                     // same or older frame: nothing new
            val step = value - y[k1]
            if (n >= 2) {
                val stepBefore = y[k1] - y[(n - 2) % RING]
                // Judged: a reading while it moved, and a step out of a short stand-still (a source that stalls and
                // jumps); not the first step out of a real rest (that cannot be foreseen).
                val recently = lastMoveT != Long.MIN_VALUE && t[k1] - lastMoveT <= 250_000_000L
                if (stepBefore != 0 || (step != 0 && recently)) {
                    val e = abs(value - at(ft, Long.MAX_VALUE))
                    val allowed = max(jumpPx, 0.5f * abs(step))
                    if (e > allowed) { hits++; miss = e; if (e > worst) worst = e }
                    else if (step != 0) clean++
                    // A reading that comes long after the last one while the content moves: it was guessed for too long.
                    if (ft - t[k1] > 4 * frameNs && moving(t[k1]) && step != 0) { hits++; miss = max(miss, abs(step).toFloat()) }
                }
            }
            if (step != 0) lastMoveT = ft
        }
        val k = n % RING
        t[k] = ft; y[k] = value; n++
        return miss
    }

    /** Where the content is at the frame [ft] (px moved up since the anchoring). */
    fun at(ft: Long, maxAheadNs: Long = frameNs * 2): Float {
        if (n == 0) return 0f
        val k1 = (n - 1) % RING
        val t1 = t[k1]; val y1 = y[k1]
        if (n == 1 || !moving(t1) || (n >= 2 && y[(n - 2) % RING] == y1)) return y1.toFloat()
        // Speed over the last ~30 ms (one frame alone is cut to whole pixels).
        var v = 0.0
        var j = n - 2
        while (j >= 0 && j > n - RING) {
            val k = j % RING
            val dt = t1 - t[k]
            // Not back past the start of the motion: the reading at rest before it is the base.
            val restHere = j >= 1 && j - 1 > n - RING && y[k] == y[(j - 1) % RING]
            if (dt >= 28_000_000L || j == 0 || j == n - RING + 1 || restHere) { if (dt > 0) v = (y1 - y[k]).toDouble() / dt; break }
            j--
        }
        val ahead = (ft - t1).coerceIn(0L, maxAheadNs)
        return (y1 + v * ahead).toFloat()
    }
}
