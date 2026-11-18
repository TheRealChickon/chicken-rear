package dev.kb896.aiusage.rear

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

/**
 * THE APP ON TOP OF THE BACK, ASKED BY BINDER FIRST: IActivityTaskManager.getAllRootTaskInfosOnDisplay, which shell
 * may read (as TaskRead's getAllRootTaskInfos), one call for the one display. The window manager's dump
 * ([RearCover.READ]) is still read whenever that answer says anything new.
 *
 * WHY: every read was a fork of `sh`, `dumpsys window displays` and `grep`, the window manager dumping every display's
 * whole hierarchy under its lock, and it was made at once and again 250 ms later on each line of the event log that says
 * apps changed on ANY display, in both processes that keep a cover: 12 to 16 forks for one app switch on the front,
 * which leaves the back as it was.
 *
 * THE SAME NAME: the dump's answer is the display's mFocusedApp, printed as the component it was started by, in short
 * form ("pkg/.Activity", ActivityRecord.toString). By binder: the root task the display's focused app is in
 * (TaskInfo.isFocused) and its top activity, named the same way: topActivityInfo's package and name (an alias by its
 * own name, as the record prints it), topActivity where that is missing (it names an alias's target).
 *
 * THE DUMP STILL DECIDES EVERY CHANGE ([Reader.read]): when the binder's answer is the one already known, the read is
 * left out and nothing is forked; when it differs, no task is focused, or the system cannot be asked, the dump is read
 * as before and ITS answer is taken. So every change of the cover comes from the same read as before; the reads left
 * out are those where the binder found nothing new (where binder and dump would disagree, the dump's change waits).
 * An unchanged answer is checked against the dump at the first read [AUDIT_MS] or more after the last dump (reads
 * come from the caller: a log line, a tick, a hint), and a caller with no other safety net asks for the dump every time
 * ([Reader.read]'s `force`: the guard while the main shell is gone, the main shell while its log is not followed). A
 * debug build says each disagreement (a line per [SAY_MS] at most), so they can be looked for on the phone.
 */
internal object RearTop {
    private const val TAG = "AiUsageRear"

    /** An answer of the binder's that agrees with what is known is checked against the dump at most this seldom. */
    const val AUDIT_MS = 60_000L
    /** A debug build's line about a disagreement, at most this often (with how many since the last). */
    const val SAY_MS = 30_000L
    /** A debug build's count of the reads, at most this often. */
    const val COUNT_MS = 600_000L

    /** One root task as asked: whether the display's focused app is in it, and its top activity's name (null for none). */
    data class Task(val focused: Boolean, val top: String?)

    /** The app on top from a display's root tasks: the focused one's top activity; null where none is focused or it has none (then the dump decides). */
    fun pick(tasks: List<Task>): String? = tasks.firstOrNull { it.focused }?.top

    /** Whether the dump is read: nothing from the binder, something new from it, or no dump for [AUDIT_MS]. */
    fun dumpNeeded(asked: String?, known: String?, sinceDumpMs: Long): Boolean =
        asked == null || asked != known || sinceDumpMs >= AUDIT_MS

    /** An activity's name as the window manager prints it ("pkg/.Activity"). */
    fun nameOf(pkg: String, cls: String): String = ComponentName(pkg, cls).flattenToShortString()

    private class Api(
        val atm: Any,
        val onDisplay: java.lang.reflect.Method,
        val focused: java.lang.reflect.Field,
        val top: java.lang.reflect.Field,
        val topInfo: java.lang.reflect.Field?,
    )

    private val api: Api? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as IBinder
            val info = Class.forName("android.app.TaskInfo")
            Api(
                atm = Class.forName("android.app.IActivityTaskManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!,
                onDisplay = Class.forName("android.app.IActivityTaskManager").getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType),
                focused = info.getField("isFocused"),
                top = info.getField("topActivity"),
                topInfo = runCatching { info.getField("topActivityInfo") }.getOrNull(),
            )
        }.onFailure { Log.w(TAG, "top: not asked by binder on this firmware (${(it.cause ?: it).message}); the window manager's dump is read as before") }.getOrNull()
    }

    @Volatile private var refusedLogged = false
    /** Refused for good (a SecurityException): not asked again in this process, the dump is read as before. */
    @Volatile private var refused = false

    /** Display [display]'s root tasks, asked; null where they cannot be. */
    fun ask(display: Int): List<Task>? {
        if (refused) return null
        val a = api ?: return null
        return try {
            (a.onDisplay.invoke(a.atm, display) as List<*>).filterNotNull().map { info ->
                val ai = a.topInfo?.get(info) as? ActivityInfo
                val pkg = ai?.applicationInfo?.packageName ?: ai?.packageName
                val name = if (pkg != null && ai?.name != null) nameOf(pkg, ai.name) else (a.top.get(info) as? ComponentName)?.flattenToShortString()
                Task(a.focused.getBoolean(info), name)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); null
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            // Not given to the shell: it will not be, so no invoke and no exception at every read from now on.
            if (cause is SecurityException) refused = true
            if (!refusedLogged) { refusedLogged = true; Log.w(TAG, "top: refused by binder (${cause.javaClass.simpleName}: ${cause.message}); the window manager's dump is read as before") }
            null
        }
    }

    /**
     * One process's reads of the app on top: [exec] runs a shell line and gives its output, [who] names the process in
     * the log; [asker] and [clock] are the system's but for a test. Any thread.
     */
    class Reader(
        private val exec: (String) -> String,
        private val who: String,
        private val asker: (Int) -> List<Task>? = ::ask,
        private val clock: () -> Long = SystemClock::uptimeMillis,
    ) {
        @Volatile private var dumpedAt = Long.MIN_VALUE
        @Volatile private var saidAt = 0L
        @Volatile private var unsaid = 0
        // A debug build's count of the reads, said every [COUNT_MS] at most: how many the dump still made, and why.
        @Volatile private var countSince = Long.MIN_VALUE
        private val counts = IntArray(5)

        /**
         * The app on top of [display] now, given [known], what the last read gave (or the event log said). [force]: the
         * dump is read whatever the binder says (and the binder is not asked), as every read was before: for a caller
         * whose read is the only safety net at that moment.
         */
        fun read(display: Int, known: String?, force: Boolean = false): String? {
            if (force) { val now = clock(); count(4, now); return dump(display, null, now) }
            val asked = asker(display)?.let(::pick)
            val now = clock()
            if (dumpedAt != Long.MIN_VALUE && !dumpNeeded(asked, known, now - dumpedAt)) { count(0, now); return known }
            count(if (asked == null) 1 else if (asked != known) 2 else 3, now)
            return dump(display, asked, now)
        }

        private fun dump(display: Int, asked: String?, now: Long): String? {
            dumpedAt = now
            val dumped = RearCover.focusOf(exec(RearCover.READ), display)
            if (asked != null && asked != dumped) disagree(asked, dumped, now)
            return dumped
        }

        /** [kind]: 0 by binder alone, 1 the dump for nothing asked, 2 for something new, 3 the minute's check, 4 forced. */
        private fun count(kind: Int, now: Long) {
            if (!dev.kb896.aiusage.BuildConfig.DEBUG) return
            synchronized(counts) {
                counts[kind]++
                if (countSince == Long.MIN_VALUE) countSince = now
                if (now - countSince < COUNT_MS) return
                Log.i(TAG, "top ($who): ${counts.sum()} reads in ${(now - countSince) / 60_000L} min, ${counts[0]} by binder alone; " +
                    "the dump for ${counts[1]} not asked, ${counts[2]} new, ${counts[3]} the minute's check, ${counts[4]} forced")
                counts.fill(0)
                countSince = now
            }
        }

        private fun disagree(asked: String, dumped: String?, now: Long) {
            if (!dev.kb896.aiusage.BuildConfig.DEBUG) return
            unsaid++
            if (saidAt != 0L && now - saidAt < SAY_MS) return
            Log.i(TAG, "top ($who): the binder said $asked, the window manager ${dumped ?: "nothing"}; the window manager's is taken" +
                if (unsaid > 1) " ($unsaid such since the last line)" else "")
            saidAt = now
            unsaid = 0
        }
    }

    /**
     * ONE READ ONCE THE CHANGE HAS SETTLED, for a whole process: each line of the event log is read at once, and was read
     * again [afterMs] later, one delayed read per line (and in the guard a new thread per line). Now one is pending at a
     * time: the first line's (at its own time), then, if lines came after it, the last of those's, again at its own time.
     * The delayed reads between them are left out, so they are not the same reads: a focus that holds only between the
     * first line's delayed read and the last line's, and at none of the lines' own reads at once (those are all still
     * made), is not seen any more; the cover no longer steps away for so short a moment, and a Camera's exit inside it
     * is not seen there. Pure; any thread.
     */
    class Settle(private val afterMs: Long) {
        private var pending = false
        private var dueAt = 0L
        private var lastLine = Long.MIN_VALUE

        /**
         * A line at [now]: in how many ms the settled read is made, or -1 when one is already pending. One pending that is
         * [LOST_MS] past its time is taken as lost (its clock gone) and made again.
         */
        @Synchronized
        fun line(now: Long): Long {
            lastLine = now
            if (pending && now - dueAt <= LOST_MS) return -1L
            pending = true
            dueAt = now + afterMs
            return afterMs
        }

        /** The settled read was made at [now]: in how many ms the next is made (a line came after the last one's), or -1 for none. */
        @Synchronized
        fun ran(now: Long): Long {
            val due = lastLine + afterMs
            if (due > now) { dueAt = due; return due - now }
            pending = false
            return -1L
        }

        /** The read could not be scheduled: none is pending. */
        @Synchronized
        fun dropped() { pending = false }

        companion object {
            const val LOST_MS = 2_000L
        }
    }
}
