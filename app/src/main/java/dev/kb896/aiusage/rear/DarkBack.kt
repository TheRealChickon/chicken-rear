package dev.kb896.aiusage.rear

/**
 * A double tap on the back while it is DARK wakes it. At first that rested on Xiaomi's own gesture lighting the rear,
 * which this app only heard of afterwards; a double tap our own reader saw on a dark back toggled the dim instead,
 * where nobody could see it. Now the reader's double tap on a dark back is the wake: the app is told
 * (IRearEvents.onWokenByHand), ends the hand-off latch and looks at its rule again, and the back is lit if the rule
 * has it lit (in the hand, face down; not in a pocket or lying on its back, and at night for its two minutes).
 *
 * WHAT THIS COVERS, AND WHAT IT DOES NOT. The shell reads the rear touchscreen only while the back is lit or
 * something of ours is up (PortalPull.companionTouchWanted): on a back that has been dark for more than a moment
 * no reader runs, and it is XIAOMI'S OWN DOUBLE TAP that lights the panel, as it always did. The app hears of
 * that as the rear display coming on while its own rule had it off, and takes it as his hand (StayOn.rearWoke,
 * UsagePollService's display listener): the hand-off latch ends, the night's two minutes start. So this object
 * decides only the double tap our reader does see on a dark back (just after it went dark, or with a pane of
 * ours still up): the wake, where it used to toggle a dim nobody could see. A debug build notes every finger it
 * sees on a dark back in `files/stay-notes.txt`.
 */
object DarkBack {
    enum class Act { WAKE, DIM }

    /** What a double tap means: on a dark back the wake, on a lit one the dim and its undoing. */
    fun doubleTap(rearLit: Boolean): Act = if (rearLit) Act.DIM else Act.WAKE
}
