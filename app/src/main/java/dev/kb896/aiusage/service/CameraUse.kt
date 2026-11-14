package dev.kb896.aiusage.service

/**
 * Whether a camera is IN USE, from CameraManager's availability callbacks (CameraWatch):
 * a camera id reported unavailable is one some app has open. No CAMERA permission is needed
 * to be told.
 *
 * DEBOUNCED both ways. A use starts only once a camera has been held for [startMs]: face
 * unlock and a QR scan in passing open a camera for a moment, and are not somebody taking a
 * photo. It ends only once every camera has been free for [endMs]: a camera app switching
 * from photo to video, or from one lens to another, closes one id and opens the next, and
 * that gap is not the camera closing.
 *
 * Pure: the watch feeds it the callbacks and asks [settle] when [dueAt] says. Times are the
 * caller's clock.
 */
class CameraUse(
    private val startMs: Long = START_MS,
    private val endMs: Long = END_MS,
) {
    enum class Edge { START, END }

    private val busy = mutableSetOf<String>()

    /** The debounced answer: a camera is in use. */
    var inUse = false
        private set

    /** When the raw answer (some id held) last changed; -1 before any change. */
    private var changedAt = -1L

    /** Some camera is held right now, undebounced. */
    val held: Boolean get() = busy.isNotEmpty()

    fun unavailable(id: String, now: Long) = update(now) { busy.add(id) }

    fun available(id: String, now: Long) = update(now) { busy.remove(id) }

    private inline fun update(now: Long, change: () -> Unit) {
        val before = held
        change()
        if (held != before) changedAt = now
    }

    /** When [settle] has something to decide; -1 when the raw answer agrees with [inUse]. */
    fun dueAt(): Long =
        if (held == inUse || changedAt < 0) -1L else changedAt + if (held) startMs else endMs

    /** The debounced edge at [now], if one is due: START or END, once each. */
    fun settle(now: Long): Edge? {
        val at = dueAt()
        if (at < 0 || now < at) return null
        inUse = held
        return if (inUse) Edge.START else Edge.END
    }

    /** The watch stopped: nothing is known, and nothing is in use. */
    fun reset() {
        busy.clear()
        inUse = false
        changedAt = -1L
    }

    companion object {
        /** Longer than face unlock's look, shorter than raising a phone to frame a shot. */
        const val START_MS = 1_200L
        /** Longer than a camera app's switch between modes or lenses. */
        const val END_MS = 2_500L
    }
}
