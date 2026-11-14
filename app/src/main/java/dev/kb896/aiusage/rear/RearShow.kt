package dev.kb896.aiusage.rear

/**
 * One transient show at a time on the rear, over Xiaomi's screen: a notification's banner, the
 * charging animation, a wake animation. Ranked BANNER > CHARGE > WAKE.
 *
 * - Nothing running, or the running one ranked the same or lower: the new one starts. A
 *   different kind running is CUT SHORT: it fades out over [fadeMs] while the new one comes in
 *   (the 120 ms cross-fade), so two shows never stand at full strength over each other.
 * - The same kind again restarts it (a second charger event, the next banner): there is one
 *   answer, and showing it twice in a row would be the same fact twice.
 * - A lower one arriving while a higher one plays is DROPPED, not queued: a charge animation
 *   five seconds late is about nothing that is happening any more.
 * - A show is over at its own end ([request]'s duration) or when whoever asked for it says so ([end]),
 *   whichever comes first; after that the rear is idle and anything may start.
 *
 * Pure: every time is given, so it is tested on the JVM (RearShowTest). Thread-safe: the
 * shell's binder threads, its touch reader and the shows' own threads all ask it.
 */
class RearShow(private val fadeMs: Long = CROSS_FADE_MS) {

    enum class Kind(val rank: Int) { WAKE(0), CHARGE(1), BANNER(2) }

    sealed class Decision {
        /** [kind] starts now. [cut]: what it cut short, which fades out over [fadeMs]; null for none. */
        data class Start(val kind: Kind, val cut: Kind?, val fadeMs: Long) : Decision()
        /** A higher one is playing: this one is not shown at all. [over] is what it lost to. */
        data class Drop(val kind: Kind, val over: Kind) : Decision()
    }

    private var running: Kind? = null
    private var endsAt = 0L

    /** What is playing at [nowMs], or null when the rear is idle. */
    @Synchronized
    fun current(nowMs: Long): Kind? {
        if (running != null && nowMs >= endsAt) running = null
        return running
    }

    /** Whether [kind] is what is playing at [nowMs]. */
    fun playing(kind: Kind, nowMs: Long): Boolean = current(nowMs) == kind

    /** [kind] asks to play from [nowMs] for [durationMs]. See the class comment for the rules. */
    @Synchronized
    fun request(kind: Kind, nowMs: Long, durationMs: Long): Decision {
        val now = current(nowMs)
        if (now != null && now.rank > kind.rank) return Decision.Drop(kind, now)
        running = kind
        endsAt = nowMs + durationMs.coerceAtLeast(0L)
        return Decision.Start(kind, if (now != null && now != kind) now else null, fadeMs)
    }

    /**
     * [kind] is over early (tapped away, unplugged and drained, its layer gone). Only if it is
     * still the one playing: a show that was already cut short does not end its successor.
     */
    @Synchronized
    fun end(kind: Kind, nowMs: Long) {
        if (current(nowMs) == kind) running = null
    }

    /**
     * How strong a show that was cut at [cutAtMs] still is at [nowMs]: 1 down to 0 over [fadeMs].
     * For whoever asked for the show that lost, to fade itself out with.
     */
    fun cutAlpha(cutAtMs: Long, nowMs: Long): Float {
        if (fadeMs <= 0L) return 0f
        return (1f - (nowMs - cutAtMs).toFloat() / fadeMs).coerceIn(0f, 1f)
    }

    /** When the rear last lit up (a wake edge), or null before the first. */
    private var lastWakeAt: Long? = null

    /**
     * The rear lit up at [nowMs]: whether a wake animation may play for it — not when the last
     * wake was under [REWAKE_GUARD_MS] ago (a tap, a dim, a tap: the same look at the back, not a
     * new one). Every wake counts, played or not, so a run of quick wakes plays only the first.
     */
    @Synchronized
    fun wakeEdge(nowMs: Long): Boolean {
        val last = lastWakeAt
        lastWakeAt = nowMs
        return last == null || nowMs - last >= REWAKE_GUARD_MS
    }

    companion object {
        /** The cross-fade when a higher show cuts a lower one short. */
        const val CROSS_FADE_MS = 120L
        /** A wake under this long after the last one plays no wake animation. */
        const val REWAKE_GUARD_MS = 3_000L
    }
}
