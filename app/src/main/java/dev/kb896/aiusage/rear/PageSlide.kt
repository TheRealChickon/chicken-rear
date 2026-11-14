package dev.kb896.aiusage.rear

/**
 * THE ONE WAY A PAGE OF THE BACK MOVES: it slides, from an edge into its place and out to an edge, in
 * [ShellPage.SLIDE_MS] with [ShellPage.ease]; under a finger it is where the finger has it, and let go short it
 * slides back. No page comes or goes by a fade.
 *
 * 1 = in its place, 0 = off the [edge] it leaves to. Pure but for the clock it is given; any thread.
 */
class PageSlide {
    enum class Edge { RIGHT, LEFT, TOP, BOTTOM }

    @get:Synchronized var at = 1f; private set
    @get:Synchronized var goal = 1f; private set
    @get:Synchronized var edge = Edge.RIGHT; private set
    /** Under a finger: it does not move by itself. */
    @get:Synchronized var held = false; private set
    private var movedAt = 0L

    /** In its place at once (it is under something that is leaving, or the back has just lit). */
    @Synchronized fun inPlace() { at = 1f; goal = 1f; held = false; movedAt = 0L }

    /** From off [from] into its place. */
    @Synchronized fun comeFrom(from: Edge) { edge = from; at = 0f; goal = 1f; held = false; movedAt = 0L }

    /** Out, to [to]; from under a finger or from part of the way it goes on to the edge it was already moving to. */
    @Synchronized fun leaveTo(to: Edge) { if (at >= 1f) edge = to; goal = 0f; held = false; movedAt = 0L }

    /** Under a finger: [inPart] of its way in (0 off [towards], 1 in place), as the finger has it (PageDrag.inPart). */
    @Synchronized fun peek(inPart: Float, towards: Edge) { held = true; edge = towards; at = PageDrag.unease(inPart); movedAt = 0L }

    /** Let go without turning: back into its place, by its slide. */
    @Synchronized fun unpeek() { if (!held) return; held = false; goal = 1f; movedAt = 0L }

    /** Moved on to [now] (uptime); true while it has further to go. */
    @Synchronized fun step(now: Long): Boolean {
        if (held) return true
        if (at == goal) { movedAt = 0L; return false }
        if (movedAt == 0L) movedAt = now
        val d = (now - movedAt).toFloat() / ShellPage.SLIDE_MS
        movedAt = now
        at = if (goal > at) minOf(goal, at + d) else maxOf(goal, at - d)
        return at != goal
    }

    /** Out and arrived at its edge: nothing of it is on the back any more. */
    @get:Synchronized val gone: Boolean get() = !held && goal == 0f && at <= 0f

    /** Where the page's corner is, for a back [w] by [h]: eased as every page is. */
    @Synchronized fun x(w: Float): Float = (1f - ShellPage.ease(at)).let { off -> when (edge) { Edge.RIGHT -> off * w; Edge.LEFT -> -off * w; else -> 0f } }
    @Synchronized fun y(h: Float): Float = (1f - ShellPage.ease(at)).let { off -> when (edge) { Edge.BOTTOM -> off * h; Edge.TOP -> -off * h; else -> 0f } }
}
