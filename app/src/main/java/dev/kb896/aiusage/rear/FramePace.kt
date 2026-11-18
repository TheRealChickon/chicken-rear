package dev.kb896.aiusage.rear

/**
 * WHEN THE NEXT PICTURE OF A PAGE IS ASKED FOR, AND HOW FAR A SLIDE HAS COME (FramePacer asks it; pure, every time
 * is System.nanoTime's, the base of the Choreographer's frame times and of SystemClock.uptimeMillis).
 *
 * The pages used to ask for their next picture with a fixed sleep (8, 12, 14, 16 or 33 ms after the last one, or
 * after the last one had been drawn). None of those is a whole number of the rear's refreshes (8.33 ms at 120 Hz),
 * so pictures landed one or two refreshes apart by turns: a slide stepped unevenly, the timer's water hitched about
 * once a second, and the call page's "33 ms" came 35 to 39 ms apart on the phone.
 *
 * Now a picture is asked for ON A VSYNC: [every] whole refreshes after the last one (a page that wants one every
 * 16 ms gets every 2nd refresh at 120 Hz, every refresh at 60 Hz; one that wants 33 ms every 4th or 2nd), and a slide
 * on every refresh of the rear, its place taken from the frame's own time ([share]), so it moves by the same step
 * each refresh. Where the vsync cannot be had or is slower than the rear ([vsyncFits]), the pictures go by the clock
 * on the rear's own frame grid ([clockNextNs]): never fewer pictures than before.
 */
object FramePace {
    /**
     * How long a refresh of the rear lasts at its highest rate [hz]. A rear that does not say is taken at [REAR_HZ], its
     * panel's own rate: never slower, so a slide never gets fewer pictures than before for a reading that failed.
     */
    fun periodNsOf(hz: Float): Long = Math.round(1_000_000_000.0 / (if (hz >= 24f && hz <= 240f) hz else REAR_HZ))

    /**
     * Whole refreshes of [periodNs] from one picture to the next, for a page that wants one every [targetMs] (0: every
     * refresh): the nearest whole number, at least one.
     */
    fun every(targetMs: Float, periodNs: Long): Int {
        if (periodNs <= 0L || targetMs <= 0f) return 1
        return maxOf(1, Math.round(targetMs * 1_000_000.0 / periodNs).toInt())
    }

    /** What a page that wants [targetMs] asks for on a rear of [rearPeriodNs]: never faster than the rear refreshes. */
    fun targetOn(targetMs: Float, rearPeriodNs: Long): Float = maxOf(targetMs, rearPeriodNs / 1_000_000f)

    /**
     * When to ask for the vsync of the next picture, [lastNs] the frame time of the last one (0: none yet): half a
     * refresh before the wanted one, so the vsync that follows IS the wanted one, whatever the thread's delays. In the
     * past or with no last picture (or one older than [STALE_NS]): now, and the next vsync is it.
     */
    fun dueNs(lastNs: Long, nowNs: Long, targetMs: Float, periodNs: Long): Long {
        if (lastNs == 0L || nowNs - lastNs > STALE_NS) return nowNs
        val due = lastNs + every(targetMs, periodNs) * periodNs - periodNs / 2
        return maxOf(due, nowNs)
    }

    /**
     * The vsync asked for has not come by this time ([dueNs] + one refresh of it, half one of the rear's and 2 ms):
     * the picture is drawn by the clock. A vsync is due within one refresh of the ask.
     */
    fun lateNs(dueNs: Long, periodNs: Long, rearPeriodNs: Long): Long = dueNs + periodNs + rearPeriodNs / 2 + 2_000_000L

    /**
     * The vsync can pace the rear: it comes at least about as often as the rear refreshes (the vsync is the lead
     * display's, which may be the front, and the front may run slower: then a page on it would show fewer pictures than
     * before). Unknown (0): not refused here; FramePacer measures it before it counts on it.
     */
    fun vsyncFits(periodNs: Long, rearPeriodNs: Long): Boolean =
        periodNs <= 0L || periodNs <= rearPeriodNs + rearPeriodNs / 4 + 500_000L

    /**
     * BY THE CLOCK: the next picture [every] refreshes of the rear after [lastNs], on the grid that started there (no
     * drift: the time asked for, not the time it ran, is the next one's start). Fallen behind by more than a refresh,
     * or no last picture: now (never a burst to catch up).
     */
    fun clockNextNs(lastNs: Long, nowNs: Long, targetMs: Float, rearPeriodNs: Long): Long {
        if (lastNs == 0L || nowNs - lastNs > STALE_NS) return nowNs
        val next = lastNs + every(targetMs, rearPeriodNs) * rearPeriodNs
        return if (next < nowNs - rearPeriodNs) nowNs else next
    }

    /**
     * A vsync asked for at [askNs] came at [frameNs], later than one refresh of [periodNs] can explain (by 1.5 ms): the
     * vsync this thread gets is slower than measured (the lead display gone to a lower rate, or off and the vsync made up).
     */
    fun cameLate(askNs: Long, frameNs: Long, periodNs: Long): Boolean = frameNs - askNs > periodNs + LATE_SLACK_NS

    /**
     * The last [size] vsyncs asked for, late or not ([cameLate], or not come at all): [note] says when [limit] of them
     * were, and the vsync is to be measured again before it is counted on. Pure.
     */
    class LateWindow(private val size: Int = 8, private val limit: Int = 3) {
        private var bits = 0
        /** One more vsync, [late] or on time: true when [limit] of the last [size] were late (and the window starts over). */
        fun note(late: Boolean): Boolean {
            bits = ((bits shl 1) or (if (late) 1 else 0)) and ((1 shl size) - 1)
            if (Integer.bitCount(bits) < limit) return false
            reset(); return true
        }
        fun reset() { bits = 0 }
    }

    /** The refresh between two vsyncs in a row ([firstNs], [secondNs]), if it can be one. */
    fun periodFrom(firstNs: Long, secondNs: Long): Long {
        val d = secondNs - firstNs
        return if (d in MIN_PERIOD_NS..MAX_PERIOD_NS) d else 0L
    }

    /** The share of a slide of [slideMs] made from [fromNs] to [frameNs]: never backwards (a frame time a little before the last step's). */
    fun share(fromNs: Long, frameNs: Long, slideMs: Float): Float =
        if (slideMs <= 0f) 1f else ((frameNs - fromNs).coerceAtLeast(0L) / 1_000_000f) / slideMs

    /** The rear panel's refresh rate (120 Hz on the phone). */
    const val REAR_HZ = 120f
    /** The last picture is this old: the next one starts a new cadence (now), it does not keep the old grid. */
    const val STALE_NS = 1_000_000_000L
    const val LATE_SLACK_NS = 1_500_000L
    /** What a refresh can be: 240 Hz to 4 Hz. */
    const val MIN_PERIOD_NS = 4_000_000L
    const val MAX_PERIOD_NS = 250_000_000L
}
