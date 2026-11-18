package dev.kb896.aiusage.service

object RestartLog {
    const val TAG = dev.kb896.aiusage.rear.RearCover.RESTART_TAG

    fun say(step: String) {
        runCatching { android.util.Log.i(TAG, "$step (${line(android.os.SystemClock.elapsedRealtime())})") }
    }

    /** "47 s after boot" for [elapsedMs] since the phone started. Pure. */
    fun line(elapsedMs: Long): String = "${elapsedMs / 1000} s after boot"

    /** Whole seconds from [fromMs] to [toMs], never under 0. Pure. */
    fun seconds(fromMs: Long, toMs: Long): Long = ((toMs - fromMs).coerceAtLeast(0L) + 500L) / 1000L
}

/**
 * How long to wait before looking again whether the shell services are bound, after Shizuku's binder came and a bind
 * did not take: 10 s, a minute, five minutes, then every quarter of an hour. NEVER A LOOP UNDER TEN SECONDS AND NEVER
 * A WAKE LOCK: these are delays on the main thread's handler, which do not wake a sleeping phone (a look that falls
 * due while it sleeps is made when it next wakes). While Shizuku itself is not there nothing waits on a clock at all:
 * its binder arriving is the signal (service/ShizukuArrival). Pure.
 */
class Backoff(private val steps: LongArray = STEPS) {
    private var i = 0

    /** The next wait, in ms; the last step repeats. */
    fun next(): Long = steps[minOf(i, steps.lastIndex)].also { i++ }

    /** Back to the first step (a bind took, or Shizuku came again). */
    fun reset() { i = 0 }

    companion object {
        val STEPS = longArrayOf(10_000L, 60_000L, 300_000L, 900_000L)
    }
}
