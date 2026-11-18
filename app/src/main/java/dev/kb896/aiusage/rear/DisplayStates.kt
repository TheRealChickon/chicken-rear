package dev.kb896.aiusage.rear

import java.util.concurrent.ConcurrentHashMap

/**
 * A display's state (Display.getState) as this process last read it, and when: ONE READING SHARED. Every read of the
 * shell's goes through [fresh], which keeps what it found; a look that may be a little old ([recent]) takes the kept
 * one when it is younger than it allows, and reads (and keeps) only when it is not.
 *
 * WHY: the back's light was read about 16 times a second from five places while the dust drew (the light's own clock,
 * the dust's look ten times a second, the charging page every picture, the stay loop, the keep-alive), and a moon clip
 * read it every picture, up to 120 times a second. Each is a binder call whenever the framework's cached DisplayInfo
 * has been invalidated, which any change of any display does.
 *
 * A value given is never older than the age asked for (its time is taken before the read, so it errs old). Pure but for
 * [read] and [clock], both given; any thread.
 */
class DisplayStates(private val read: (Int) -> Int?, private val clock: () -> Long) {
    private class Seen(val state: Int?, val at: Long)

    private val seen = ConcurrentHashMap<Int, Seen>()

    /** [display]'s state now, read, and kept for [recent]. */
    fun fresh(display: Int): Int? {
        val at = clock()
        val state = read(display)
        val now = Seen(state, at)
        // The newer of two reads made at once is kept.
        seen.merge(display, now) { old, new -> if (new.at >= old.at) new else old }
        return state
    }

    /** [display]'s state as last read if that was under [maxAgeMs] ago, else [fresh]. */
    fun recent(display: Int, maxAgeMs: Long): Int? {
        val s = seen[display]
        if (s != null && clock() - s.at < maxAgeMs) return s.state
        return fresh(display)
    }
}
