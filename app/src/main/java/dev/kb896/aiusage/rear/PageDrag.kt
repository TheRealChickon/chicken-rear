package dev.kb896.aiusage.rear

import kotlin.math.abs

/**
 * A PAGE FOLLOWS THE FINGER (the review's 11: the turn was made in the middle of the
 * stroke, at 150 units, and could not be taken back; a slow drag did nothing).
 *
 * Which page a stroke turns is RearNav's to say, as ever, and it says so once, WHEN THE FINGER LIFTS. This only
 * reads the finger: its way is decided by its first [SLOP] of travel and kept; if that way turns a page (the
 * caller's [tracks], asked once), every move says how far the finger has gone that way ([Out.At]: the page is
 * put there), and the lift says whether the page turns or goes back ([Out.End]):
 *
 * - far enough ([COMMIT], the old swipe's own measure), however slowly: it turns;
 * - a fling (the last moves faster than [FLING], and at least [FLING_TRAVEL] gone): it turns;
 * - anything short of that: the page goes back where it was, and nothing has happened.
 *
 * A finger whose way turns no page is not this one's: the old reader (SceneTouch) has it, taps and all.
 * Pure: panel units in, as RearTouches gives them. One finger.
 */
class PageDrag {
    enum class Way { LEFT, RIGHT, UP, DOWN }

    sealed interface Out {
        /** The finger's way is known and it turns a page: it is this one's from here (the other reader lets it go). */
        data class Begin(val way: Way, val downX: Float, val downY: Float, val downAt: Long) : Out
        /** How far the finger has gone its way, 0 or more (back past its landing is 0). */
        data class At(val way: Way, val travel: Float) : Out
        /** Lifted: [turns], or back. [at]: when. */
        data class End(val way: Way, val turns: Boolean, val travel: Float, val downAt: Long, val at: Long) : Out
    }

    private var down = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var way: Way? = null
    private var decided = false
    private var travel = 0f
    private var lastTravel = 0f
    private var lastAt = 0L
    private var lastMoveAt = 0L
    private var speed = 0f

    /** This finger is being followed. */
    val following: Boolean get() = down && way != null

    @Synchronized
    fun feed(e: RearTouches.Event, tracks: (way: Way, downX: Float, downY: Float, downAt: Long) -> Boolean): List<Out> = when (e) {
        is RearTouches.Down -> {
            down = true; downX = e.x; downY = e.y; downAt = e.atMs
            way = null; decided = false; travel = 0f; lastTravel = 0f; lastAt = e.atMs; lastMoveAt = e.atMs; speed = 0f
            emptyList()
        }
        is RearTouches.Move -> if (!down) emptyList() else {
            val dx = e.x - downX
            val dy = e.y - downY
            val out = ArrayList<Out>(2)
            if (!decided && maxOf(abs(dx), abs(dy)) >= SLOP) {
                decided = true
                val w = if (abs(dx) >= abs(dy)) (if (dx < 0f) Way.LEFT else Way.RIGHT) else (if (dy < 0f) Way.UP else Way.DOWN)
                if (tracks(w, downX, downY, downAt)) { way = w; out += Out.Begin(w, downX, downY, downAt) }
            }
            val w = way
            if (w != null) {
                travel = along(w, dx, dy)
                // The speed of the last stretch (not of the whole stroke): a slow drag that ends in a flick is a fling.
                if (e.atMs - lastAt >= SPEED_WINDOW_MS) { speed = (travel - lastTravel) / (e.atMs - lastAt); lastTravel = travel; lastAt = e.atMs }
                lastMoveAt = e.atMs
                out += Out.At(w, travel)
            }
            out
        }
        is RearTouches.Up -> {
            val w = way
            val out: List<Out> = if (!down || w == null) emptyList() else {
                val t = along(w, e.x - downX, e.y - downY)
                // The lift comes a report or two after the last move: the speed is the last stretch's. Only a finger
                // that stood still before it lifted (no move for [REST_MS]) has none.
                if (e.atMs - lastMoveAt > REST_MS) speed = 0f
                listOf(Out.End(w, turns(t, speed), t, downAt, e.atMs))
            }
            down = false; way = null; decided = false
            out
        }
    }

    private fun along(w: Way, dx: Float, dy: Float): Float = when (w) {
        Way.LEFT -> -dx; Way.RIGHT -> dx; Way.UP -> -dy; Way.DOWN -> dy
    }.coerceAtLeast(0f)

    companion object {
        /** The first travel that says which way the finger goes (SceneTouch's own). */
        const val SLOP = 16f
        /** Gone this far when it lifts, the page turns: the old swipe's travel. */
        const val COMMIT = 150f
        /** Or let go this fast (panel units a millisecond, ControlCenter's fling), having gone at least [FLING_TRAVEL]. */
        const val FLING = 0.6f
        const val FLING_TRAVEL = 40f
        private const val SPEED_WINDOW_MS = 24L
        private const val REST_MS = 60L

        fun turns(travel: Float, speed: Float): Boolean = travel >= COMMIT || (speed >= FLING && travel >= FLING_TRAVEL)

        /** Where a page stands for a finger that has gone [travel] of [extent] (the back's width or height): the part of its way IN, 0..1. */
        fun inPart(coming: Boolean, travel: Float, extent: Float): Float {
            val gone = (travel / extent).coerceIn(0f, 1f)
            return if (coming) gone else 1f - gone
        }

        /** The slide's own measure for a place: ShellPage.ease's inverse, so a page let go goes on from where the finger left it. */
        fun unease(part: Float): Float = 1f - Math.cbrt((1f - part.coerceIn(0f, 1f)).toDouble()).toFloat()
    }
}
