package dev.kb896.aiusage.rear

object HiddenFrames {
    /** The opaque layers of ours that can stand over the whole back. */
    enum class Layer { POP, ARRIVAL, NOTES, USAGE, LIVE, CHARGE, SHADE }

    /** The live things whose page is a layer of the row's own (ShellCard); the charging page is ShellCharge's ([Layer.CHARGE]). */
    private fun cardPage(live: RearNav.Live?): Boolean = live == RearNav.Live.NAVIGATION || live == RearNav.Live.TIMER || live == RearNav.Live.CALL || live == RearNav.Live.MUSIC

    /** The layer of the live thing's page: a card's ([Layer.LIVE]), the charging page's, or none. */
    private fun liveLayer(live: RearNav.Live?): Layer? = when {
        cardPage(live) -> Layer.LIVE
        live == RearNav.Live.CHARGE -> Layer.CHARGE
        else -> null
    }

    /** The layer that is the whole back for what is seen now, if it is one that covers everything under it. */
    fun cover(s: RearNav.State): Layer? = when (RearNav.seen(s)) {
        RearNav.Seen.POP -> Layer.POP
        RearNav.Seen.SHADE -> Layer.SHADE
        RearNav.Seen.ARRIVAL -> Layer.ARRIVAL
        RearNav.Seen.NOTES -> Layer.NOTES
        RearNav.Seen.USAGE -> Layer.USAGE
        RearNav.Seen.LIVE -> liveLayer(s.live)
        else -> null
    }

    /**
     * Whether the scene is covered by a page of ours in its place: what is seen is such a page and it stands in its
     * place, or the live page is in the row (under whatever lies over it) and stands in its place.
     */
    fun covered(s: RearNav.State, inPlace: (Layer) -> Boolean): Boolean {
        if (!s.lit || !s.ours) return false
        val c = cover(s)
        if (c != null && inPlace(c)) return true
        val under = if (s.liveShown) liveLayer(s.live) else null
        return under != null && inPlace(under)
    }

    /**
     * The scene held on its frame: as a dim still's posture asks ([dimStill], as before), under the music page whenever
     * it is up (as before), and now under every opaque page in its place ([covered]).
     */
    fun sceneStill(dimStill: Boolean, musicUp: Boolean, s: RearNav.State, inPlace: (Layer) -> Boolean): Boolean =
        dimStill || musicUp || covered(s, inPlace)

    /** The overlay that lies over the live page, if the live page is under one of those that stop its pictures. */
    fun overLive(s: RearNav.State): Layer? {
        if (!s.liveShown || !cardPage(s.live)) return null
        return when (RearNav.seen(s)) {
            RearNav.Seen.USAGE -> Layer.USAGE
            RearNav.Seen.NOTES -> Layer.NOTES
            RearNav.Seen.ARRIVAL -> Layer.ARRIVAL
            else -> null
        }
    }

    /** The live page is drawn whenever any of it can be seen: always, but while usage, the notes or an arrival lie over it in their place. */
    fun liveDrawn(s: RearNav.State, inPlace: (Layer) -> Boolean): Boolean {
        val over = overLive(s) ?: return true
        return !inPlace(over)
    }

    /** A page's layer in its place: up, at its place and staying there, not under a finger. */
    fun inPlace(isUp: Boolean, at: Float, goal: Float, peeking: Boolean): Boolean = isUp && !peeking && at >= 1f && goal >= 1f

    /** While a live page's pictures are not drawn, its frame tick only looks again this often (it is drawn at once when uncovered). */
    const val HIDDEN_LOOK_MS = 250L

    /** The next look of a frame tick whose page is covered: never sooner than its own pace, never sooner than [HIDDEN_LOOK_MS]. */
    fun hiddenDelay(ownDelayMs: Long): Long = maxOf(ownDelayMs, HIDDEN_LOOK_MS)
}

/**
 * THE PERIODIC TRUTH READ OF THE BACK'S FOCUS (RearUserService's stay loop): the event log already says every change of
 * the apps on a display, and each such line is followed by a read at once, and a burst of them by one settled read
 * 250 ms after its first line and one 250 ms after its last (RearTop.Settle). So the loop's own read,
 * which forked `sh`, `dumpsys window` and `grep` (asked by binder first since: RearTop), is every [SLOW_MS] instead of every two seconds; every tick (as before)
 * while the log is not being followed (then the dump itself, every tick: RearTop.Reader's `force`), and for [FAST_FOR_MS]
 * after it was (re)started (a gap in the log is possible then). Pure.
 */
object FocusReread {
    const val SLOW_MS = 10_000L
    const val FAST_FOR_MS = 10_000L

    /**
     * Whether the stay loop reads now: [lastReadAt] the last read of any kind (uptime, 0 for none), [logSince] when the
     * log reader that hears the changes began following (uptime; 0 while none follows).
     */
    fun due(now: Long, lastReadAt: Long, logSince: Long): Boolean = when {
        logSince == 0L -> true
        now - logSince < FAST_FOR_MS -> true
        lastReadAt == 0L -> true
        else -> now - lastReadAt >= SLOW_MS
    }
}
