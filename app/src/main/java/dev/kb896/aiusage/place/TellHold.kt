package dev.kb896.aiusage.place

class TellHold {
    private var until = 0L
    private var owed = false
    private var made = 0

    /** A pin's animation begins at [now] and tells the back within [ms]: a token for its own way out. */
    @Synchronized fun hold(now: Long, ms: Long): Int { until = maxOf(until, now + ms); return ++made }

    /** Whether a write at [now] may be told at once; false: it is held, and owed to the back. */
    @Synchronized fun mayTell(now: Long): Boolean {
        if (now < until) { owed = true; return false }
        return true
    }

    /** The pin has told the back (what is stored, all of it): the hold ends. */
    @Synchronized fun release() { until = 0L; owed = false }

    /** The hold [token] ran out at [now] without its tell: whether something held is still owed to the back. */
    @Synchronized fun ranOut(token: Int, now: Long): Boolean {
        if (token != made || now < until) return false
        val o = owed
        owed = false; until = 0L
        return o
    }
}
