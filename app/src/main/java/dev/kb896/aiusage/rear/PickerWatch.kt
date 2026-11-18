package dev.kb896.aiusage.rear

/**
 * WHAT KEEPS THE FACE PICKER FROM STAYING UP FOR EVER (ShellPicker asks it; pure, the caller gives the clock).
 *
 * - LEFT ALONE: up and no finger landing or lifting for [ALONE_MS], it leaves without choosing, the old face kept, as
 *   the usage page and the notes leave when left alone. A hand resting on the back while the front is in use opens it
 *   (a hold in place); that hand's further touches must not go on to drive it, and a resting finger is no reason to stay.
 *   Only a finger LANDING or LIFTING counts: a finger lying on the back sends small moves all the time. Ours: Xiaomi's
 *   own was not seen to time out (it was never recorded).
 * - THE EXIT OVERDUE: from the shut, gone by [EXIT_DEADLINE_MS] whatever the frames did (the exit's springs rest in
 *   well under a second). The picker's frames come from the Choreographer, whose vsync is the front display's: if they
 *   stop (the front off, the back lit), or a frame threw, the picker would hold every finger of the back.
 * - A FRAME LATE: each frame asked for is run by a plain timer after [FRAME_FALLBACK_MS] if the vsync has not come.
 */
class PickerWatch {
    enum class Due { NOTHING, LEAVE, GONE }

    private var up = false
    private var shutAt = -1L
    private var lastTouch = 0L

    /** Opened at [now] by a finger that is down (its hold). */
    fun opened(now: Long) { up = true; shutAt = -1L; lastTouch = now }

    /** A finger landed or lifted at [now] (moves do not count). */
    fun touched(now: Long) { if (up && shutAt < 0L) lastTouch = now }

    /** Its exit began at [now] (chosen or not). */
    fun shut(now: Long) { if (up && shutAt < 0L) shutAt = now }

    /** Gone (its exit played out, or dropped). */
    fun gone() { up = false; shutAt = -1L }

    /** What is due at [now]. */
    fun due(now: Long): Due = when {
        !up -> Due.NOTHING
        shutAt >= 0L -> if (now - shutAt >= EXIT_DEADLINE_MS) Due.GONE else Due.NOTHING
        now - lastTouch >= ALONE_MS -> Due.LEAVE
        else -> Due.NOTHING
    }

    /** When to ask [due] next, or null with nothing to watch. */
    fun nextAt(): Long? = when {
        !up -> null
        shutAt >= 0L -> shutAt + EXIT_DEADLINE_MS
        else -> lastTouch + ALONE_MS
    }

    companion object {
        const val ALONE_MS = 10_000L
        const val EXIT_DEADLINE_MS = 2_000L
        const val FRAME_FALLBACK_MS = 50L
    }
}
