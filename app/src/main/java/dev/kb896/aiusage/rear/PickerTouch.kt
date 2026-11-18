package dev.kb896.aiusage.rear

import kotlin.math.abs

/**
 * A finger in the face picker (FacePicker), read by the picker itself: SceneTouch gives it the finger
 * (Mode.PICKER) and makes no tap, swipe or hold of it, the way PageDrag takes a finger.
 *
 * - Sideways past [DRAG_SLOP_PX] (Xiaomi's 22 px), more sideways than up or down: the row follows the finger one to
 *   one ([Out.Drag], from where the drag began, so the row does not jump by the slop), and at the lift the row is
 *   handed the finger's speed ([Out.Release]).
 * - A TAP (down at most SceneTouch.TAP_MS, within SceneTouch.TAP_SLOP): the middle card is chosen ([Out.Choose]).
 *   Never for the finger whose hold opened the picker: lifted in place, that was the hold.
 * - UP OR DOWN ON PURPOSE (SceneTouch.TRAVEL within SceneTouch.SWIPE_MS, 1.5 times more up or down than sideways),
 *   from anywhere, the top edge too: leave without choosing ([Out.Leave]); the old face stays (Ask him, 2).
 *   A vertical drift that is not a stroke only takes the drag away ([Out.Cancel]: Xiaomi's "Cancel
 *   ChangeWidgetGesture: vertical dominant"), and the row springs back.
 * - Left to right is the previous card here and NEVER Xiaomi's Camera (as on Xiaomi).
 * - A SECOND FINGER DOWN WITH IT ([crowded]): none of the above any more.
 *
 * Pure: panel units in (RearTouches), rear pixels out.
 */
class PickerTouch {
    sealed interface Out {
        /** A finger landed: whatever the row was doing stops where it is. */
        data object Grab : Out
        /** The row [dxPx] from where the drag began (left negative). */
        data class Drag(val dxPx: Double) : Out
        /** The finger is gone at [vxPxPerS] (left negative): the row settles, from that speed. */
        data class Release(val vxPxPerS: Double) : Out
        /** A drift up or down took the drag away: the row goes back to its card. */
        data object Cancel : Out
        data object Choose : Out
        data object Leave : Out
    }

    private var down = false
    private var fromHold = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dragging = false
    private var dragFromX = 0f
    /** No more drag for this finger (it went up or down), and no tap. */
    private var vertical = false
    private var left = false
    /** Another finger came down with this one ([crowded]): nothing more of it. */
    private var crowd = false
    private var moved = false
    private val trail = ArrayDeque<Triple<Long, Float, Float>>()

    /**
     * The finger whose hold opened the picker: it is already down at [x], [y] since [atMs]; it may go on to drag, and
     * lifted in place it chooses nothing.
     */
    @Synchronized
    fun holdFinger(x: Float, y: Float, atMs: Long): List<Out> {
        start(x, y, atMs); fromHold = true
        return listOf(Out.Grab)
    }

    private fun start(x: Float, y: Float, atMs: Long) {
        down = true; fromHold = false; downX = x; downY = y; downAt = atMs
        dragging = false; vertical = false; left = false; moved = false; crowd = false
        trail.clear(); trail.addLast(Triple(atMs, x, y))
    }

    /**
     * ANOTHER FINGER IS DOWN WITH THIS ONE (RearTouches.crowded): no drag, no choice and no leave come of it any more.
     * A drag under way is taken away (the row goes back to its card); a row the landing stopped settles on.
     */
    @Synchronized
    fun crowded(): List<Out> {
        if (!down || left || crowd) return emptyList()
        crowd = true
        return if (dragging) { dragging = false; listOf(Out.Cancel) } else listOf(Out.Release(0.0))
    }

    @Synchronized
    fun feed(e: RearTouches.Event): List<Out> = when (e) {
        is RearTouches.Down -> { start(e.x, e.y, e.atMs); listOf(Out.Grab) }
        is RearTouches.Move -> if (!down) emptyList() else move(e)
        is RearTouches.Up -> if (!down) emptyList() else lift(e)
    }

    private fun move(e: RearTouches.Event): List<Out> {
        trail.addLast(Triple(e.atMs, e.x, e.y))
        while (trail.size > 2 && e.atMs - trail.first().first > VELOCITY_WINDOW_MS) trail.removeFirst()
        if (left || crowd) return emptyList()
        val dxU = e.x - downX; val dyU = e.y - downY
        if (maxOf(abs(dxU), abs(dyU)) >= SceneTouch.TAP_SLOP) moved = true
        val dxPx = dxU / UNITS_PER_PX_X; val dyPx = dyU / UNITS_PER_PX_Y
        // Up or down on purpose: leave, from wherever it began, mid-drag too.
        if (e.atMs - downAt <= SceneTouch.SWIPE_MS && abs(dyU) >= SceneTouch.TRAVEL && abs(dyU) > abs(dxU) * 1.5f) {
            left = true
            return listOf(Out.Leave)
        }
        val out = ArrayList<Out>(1)
        if (dragging) {
            // More up or down than sideways, past the slop: no drag any more (the row goes back), maybe a stroke still.
            if (abs(dyPx) >= DRAG_SLOP_PX && abs(dyPx) > abs(dxPx)) { dragging = false; vertical = true; out += Out.Cancel }
            else out += Out.Drag(((e.x - dragFromX) / UNITS_PER_PX_X).toDouble())
        } else if (!vertical) {
            if (abs(dxPx) >= DRAG_SLOP_PX && abs(dxPx) > abs(dyPx)) { dragging = true; dragFromX = e.x; out += Out.Drag(0.0) }
            else if (abs(dyPx) >= DRAG_SLOP_PX && abs(dyPx) > abs(dxPx)) vertical = true
        }
        return out
    }

    private fun lift(e: RearTouches.Event): List<Out> {
        down = false
        if (left || crowd) return emptyList()
        val out = ArrayList<Out>(2)
        if (dragging) {
            trail.addLast(Triple(e.atMs, e.x, e.y))
            dragging = false
            out += Out.Release(velocity())
            return out
        }
        // A finger that did not drag lets a settle it stopped go on from where it is.
        out += Out.Release(0.0)
        val still = !moved && maxOf(abs(e.x - downX), abs(e.y - downY)) < SceneTouch.TAP_SLOP
        if (!fromHold && !vertical && still && e.atMs - downAt <= SceneTouch.TAP_MS) out += Out.Choose
        return out
    }

    /** Sideways speed over the last [VELOCITY_WINDOW_MS], px/s. */
    private fun velocity(): Double {
        val last = trail.last()
        val first = trail.firstOrNull { last.first - it.first <= VELOCITY_WINDOW_MS } ?: return 0.0
        val dt = (last.first - first.first) / 1000.0
        if (dt <= 0.0) return 0.0
        return (last.second - first.second) / UNITS_PER_PX_X / dt
    }

    companion object {
        /** Xiaomi's touch slop on this panel: 22 px. */
        const val DRAG_SLOP_PX = 22.0
        /** Panel units (Panel.WIDTH x HEIGHT, 904 x 572) per rear pixel (976 x 596). */
        const val UNITS_PER_PX_X = 904f / 976f
        const val UNITS_PER_PX_Y = 572f / 596f
        const val VELOCITY_WINDOW_MS = 80L
    }
}
