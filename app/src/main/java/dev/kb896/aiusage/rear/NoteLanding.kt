package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.Pin

class NoteLanding {
    /** The landing note's Pin.at; 0 for none. */
    @Volatile var at = 0L
        private set
    private var since = 0L
    private var seen = false

    /**
     * A payload's fresh note ([note], the newest, new since the last payload) at [now]: it lands when it is for a place,
     * here or not (a plain note is always listed). Whether a landing began (the caller then looks again at [MOST_MS]).
     */
    @Synchronized fun fresh(note: Pin?, now: Long): Boolean {
        if (note == null || !note.placed) return false
        at = note.at; since = now; seen = false
        return true
    }

    /**
     * The notes page is [up] at [now] (it has just come or gone, or the caller looks at [MOST_MS]): whether the landing
     * is over by it. Then [at] is 0 and the notes page lists by the rule alone.
     */
    @Synchronized fun page(up: Boolean, now: Long): Boolean {
        if (at == 0L) return false
        if (up) { seen = true; return false }
        if (!seen && now - since < MOST_MS) return false
        at = 0L; seen = false
        return true
    }

    /** No landing any more (a made-up list's turn, the shell going). */
    @Synchronized fun clear() { at = 0L; seen = false }

    companion object {
        /**
         * A landing nobody saw is over this long after its payload: past Xiaomi's arrival on the back at its latest
         * (PinVia.ENTER_LATEST) and our own beam's (PinBeam.ARRIVE_AT), with room to spare. One that is up then lasts
         * until its page goes down.
         */
        const val MOST_MS = 12_000L
    }
}
