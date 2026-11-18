package dev.kb896.aiusage.service

import dev.kb896.aiusage.rear.NoticeThrow

object PhoneThrow {
    /** What the phone says of a notification now. [category]: Notification.CATEGORY_*'s string; [media]: a media session. */
    data class Facts(val ongoing: Boolean, val clearable: Boolean, val category: String?, val media: Boolean)

    interface Listener {
        /** The notification of [key] as it is on the phone now; null when it is not there any more. */
        fun find(key: String): Facts?
        fun cancel(key: String)
        fun snooze(key: String, ms: Long)
    }

    enum class Result {
        DONE,
        /** Not on the phone any more: nothing to do (the back's list lets it go all the same). */
        GONE,
        /** Not to be thrown (ongoing, not clearable, a call…; or a made-up one): left as it is. */
        REFUSED,
    }

    fun dismiss(l: Listener, key: String): Result = act(l, key) { l.cancel(key) }

    fun snooze(l: Listener, key: String, ms: Long = NoticeThrow.SNOOZE_MS): Result = act(l, key) { l.snooze(key, duration(ms)) }

    /** The snooze asked for, kept to sense: the hour when nothing sensible is said, never under a minute or over a day. */
    fun duration(ms: Long): Long = if (ms <= 0L) NoticeThrow.SNOOZE_MS else ms.coerceIn(60_000L, 24L * 60L * 60_000L)

    private inline fun act(l: Listener, key: String, go: () -> Unit): Result {
        if (key.isEmpty() || NoticeThrow.isDemo(key)) return Result.REFUSED
        val f = l.find(key) ?: return Result.GONE
        if (!NoticeThrow.throwable(f.ongoing, f.clearable, f.category, f.media)) return Result.REFUSED
        go()
        return Result.DONE
    }
}
