package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel

/**
 * The App Portal's pull: the control centre's, mirrored to the bottom edge. A finger that
 * lands within [EDGE] of the bottom, right of the lens column, pulls the portal's sheet up
 * over [TRAVEL] (see EdgeGestures, which reads it off the rear touchscreen, and
 * SheetMotion, which follows it from there). Up is negative dy, as MotionEvent has it.
 */
object PortalPull {

    /** The control centre's edge width, for the same reason: a finger on the back lands short. */
    const val EDGE = ControlCenter.EDGE
    /** And its travel: a pull this long opens the sheet all the way. */
    const val TRAVEL = ControlCenter.TRAVEL

    /**
     * A finger at [x], [y] (panel units) starts a pull: in the bottom band, right of [left] —
     * the lens column's edge in panel units, given, never assumed (RearGeometry.cutoutUnits:
     * left of 296 on a Pro Max, whose lenses keep their pixels).
     */
    fun startsPull(x: Float, y: Float, left: Float): Boolean = y >= Panel.HEIGHT - EDGE && x >= left

    // -- companion mode: the pull from the top edge ----------------------------------------
    //
    // Over Xiaomi's rear screen the bottom band is Xiaomi's (its 90 px SubScreenGestureHome
    // strip), and so is the right edge (its 62 px SubScreenGestureBack strip); the camera
    // island is on the left. So in companion mode the pinned apps come down from the top edge,
    // between the island and Xiaomi's back strip, in the rear's own pixels (976 wide on a
    // 17 Pro Max): x from [TOP_LEFT_PX] to [TOP_RIGHT_PX]. The raw touch is read, not taken,
    // so Xiaomi's launcher sees the finger too until the pull commits: past [TOP_COMMIT] down,
    // more down than sideways. Then the sheet comes up with its blocker, which takes the
    // touches from there on.

    /** The top pull's left edge, rear pixels: right of the camera island. */
    const val TOP_LEFT_PX = 296f
    /** And its right edge: left of Xiaomi's back strip (976 - 62). */
    const val TOP_RIGHT_PX = 914f
    /** How far down, in panel units, before it is a pull and the blocker takes over. */
    const val TOP_COMMIT = 60f

    /**
     * A finger at [x], [y] (panel units, as RearTouches gives them) lands where the top pull
     * starts: within [EDGE] of the top, and between [TOP_LEFT_PX] and [TOP_RIGHT_PX] once
     * scaled to the rear's pixels by [fitX] (RearGeometry.fitX).
     */
    fun startsTopPull(x: Float, y: Float, fitX: Float): Boolean =
        y <= EDGE && (x * fitX) in TOP_LEFT_PX..TOP_RIGHT_PX

    /** From a landing at [downX], [downY] to [x], [y]: far enough down, and more down than across, to commit. */
    fun topCommits(downX: Float, downY: Float, x: Float, y: Float): Boolean {
        val down = y - downY
        return down >= TOP_COMMIT && down > kotlin.math.abs(x - downX)
    }

    /** Gone sideways, or back up, before it committed: not a pull, it stays Xiaomi's. */
    fun topAbandoned(downX: Float, downY: Float, x: Float, y: Float, slop: Float): Boolean {
        val across = kotlin.math.abs(x - downX)
        return (across >= slop && across >= y - downY) || downY - y >= slop
    }

    /**
     * Where the sheet's motion is told the finger is, for a pull down from the top: mirrored,
     * so a finger [y] - [downY] down from its landing moves the sheet as a finger that far UP
     * from the bottom edge does (SheetMotion follows a pull up from the bottom).
     */
    fun mirrored(y: Float, downY: Float): Float = Panel.HEIGHT - (y - downY)

    /**
     * Whether companion mode wants the rear's raw touches read: a guest on top, our sheet or a
     * shell pane up, or the rear lit with More apps on (the top pull) or with [doubleTaps] (a
     * wake of ours that the double tap's latch must hold back: ManualOff.byDoubleTap). Otherwise
     * nothing of ours is on the rear and no getevent runs.
     */
    fun companionTouchWanted(
        guestOnTop: Boolean, shellUp: Boolean, rearLit: Boolean, moreApps: Boolean, doubleTaps: Boolean = false,
    ): Boolean = guestOnTop || shellUp || (rearLit && (moreApps || doubleTaps))
}
