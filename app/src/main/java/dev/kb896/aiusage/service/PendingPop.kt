package dev.kb896.aiusage.service

/**
 * A notification's banner that arrived while the shell was not bound: kept, and handed over
 * when the shell connects, if it is still fresh.
 *
 * Before this, ShizukuBridge.setNotices returned after bind() when there was no service, and
 * on connect only the list went across, with an empty pop key: every app restart (HyperOS
 * kills it, an update) opened a window in which a notification was not shown as a banner on
 * the back at all. A banner [MAX_AGE_MS] late is still about something that just happened;
 * one older than that is not, and is dropped. One at a time: the newest wins.
 *
 * Pure (times are given), so it is tested on the JVM (PendingPopTest).
 */
class PendingPop(private val maxAgeMs: Long = MAX_AGE_MS) {

    private var key: String? = null
    private var at = 0L

    /** [key] could not be shown at [nowMs]: kept, in place of any older one. An empty key is no pop. */
    @Synchronized
    fun hold(key: String, nowMs: Long) {
        if (key.isEmpty()) return
        this.key = key
        at = nowMs
    }

    /** It was shown after all (the call went through): nothing left to hand over. */
    @Synchronized
    fun clear() {
        key = null
    }

    /** The key to pop now, or "" when there is none or it is too old. Taken either way: once only. */
    @Synchronized
    fun take(nowMs: Long): String {
        val k = key ?: return ""
        key = null
        return if (nowMs - at in 0..maxAgeMs) k else ""
    }

    companion object {
        /** How late a banner may still come up: five seconds. */
        const val MAX_AGE_MS = 5_000L
    }
}
