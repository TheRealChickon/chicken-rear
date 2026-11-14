package dev.kb896.aiusage.rear

/**
 * What is live on the phone, turned into RearNav's inputs: [look] is given what is live now (one mark per kind,
 * null for not live; a mark that differs from the last is something NEW of that kind) and says what to tell the
 * navigation: [RearNav.Input.LiveComes] for what is new (the highest kind first, so a call that takes a timer's
 * place is one change of page and not a page out and another in), then [RearNav.Input.LiveEnds] for what is gone.
 *
 * A GAP IS NOT AN END: a clock takes its notification down and re-posts it (a pause, going on), a player stops for
 * a moment between two tracks. What he sent away is still that thing for [FORGET_MS] of nothing (so it does not
 * come back as "new" a second later); what is showing stays for [GRACE_MS] (so its page does not slide out and in
 * again). An end HE MADE (the page's X, a demo taken down) is at once: [look] with `atOnce`.
 *
 * Pure: the time is handed in. Not thread-safe.
 */
class LiveWatch {
    private val known = arrayOfNulls<String>(RearNav.Live.values().size)
    private val goneAt = LongArray(known.size)

    /** What to tell the navigation, in order, and when to look again by itself (0 for no need). */
    class Told(val inputs: List<RearNav.Input>, val againAt: Long)

    /**
     * [marks] by [RearNav.Live.ordinal]; [state] is the navigation's state before these inputs; [urgent] says
     * whether a new thing of a kind comes up over usage, the notes and the shade (a call; a timer run out).
     */
    fun look(now: Long, marks: Array<String?>, state: RearNav.State, atOnce: Boolean = false, urgent: (RearNav.Live) -> Boolean): Told {
        val out = ArrayList<RearNav.Input>()
        var again = 0L
        for (kind in RearNav.Live.values().reversed()) {
            val k = kind.ordinal
            val mark = marks[k] ?: continue
            goneAt[k] = 0L
            if (mark != known[k]) { known[k] = mark; out += RearNav.Input.LiveComes(kind, urgent(kind)) }
        }
        for (kind in RearNav.Live.values()) {
            val k = kind.ordinal
            if (marks[k] != null || known[k] == null) continue
            // Still it for a while, should it be back as it was: long for one he sent away, a moment for any other.
            val patience = if (atOnce) 0L else if (state.sent and (1 shl k) != 0) FORGET_MS else GRACE_MS
            if (patience > 0L) {
                if (goneAt[k] == 0L) goneAt[k] = now
                if (now - goneAt[k] < patience) { again = if (again == 0L) goneAt[k] + patience else minOf(again, goneAt[k] + patience); continue }
            }
            known[k] = null; goneAt[k] = 0L
            out += RearNav.Input.LiveEnds(kind)
        }
        return Told(out, again)
    }

    companion object {
        const val FORGET_MS = 4_000L
        /** A thing whose page shows is gone only after this much of nothing (a notification re-posted is not an end). */
        const val GRACE_MS = 700L
    }
}
