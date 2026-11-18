package dev.kb896.aiusage.rear

import android.util.Log
import dev.kb896.aiusage.IRearGuard
import dev.kb896.aiusage.Panel
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.system.exitProcess

class RearGuardService : IRearGuard.Stub() {
    private val cover = RearCover(::layerStack, "guard") { 1024 to 640 }
    @Volatile private var focus: String? = null
    @Volatile private var ending = false
    // Before init: its threads use them at once.
    /** The reads of the back's top app (by binder, the window manager's dump for whatever is new: RearTop). */
    private val topReader = RearTop.Reader(::exec, "guard")
    /** The read once a change has settled: one pending at a time, on one thread (it was a new thread per line). */
    private val settle = RearTop.Settle(250L)
    private val settleClock = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "guard-read").apply { isDaemon = true } }
    private val settled: Runnable = object : Runnable {
        override fun run() {
            read()
            val next = settle.ran(android.os.SystemClock.uptimeMillis())
            if (next >= 0L) schedule(this, next)
        }
    }

    init {
        ShellLayer.rootZ = Int.MAX_VALUE - 1
        Thread({ poll() }, "guard-poll").apply { isDaemon = true; start() }
        Thread({ listen() }, "guard-focus").apply { isDaemon = true; start() }
        Log.i(TAG, "guard: up")
    }

    override fun alive() = true

    override fun destroy() {
        ending = true
        runCatching { cover.close() }
        runCatching { ShellLayer.removeAll() }
        exitProcess(0)
    }

    private fun sync(why: String) {
        if (ending) return
        runCatching { val hint = RearCover.Hint.read(); cover.set(RearCover.wanted(hint.face, focus, hint.allow), why) }
    }

    /**
     * Every [POLL_MS]: the hint file looked at (a file read, no fork) and the process that wrote it. The top app is read
     * (RearTop: by binder, the window manager's dump for anything new) only when that process is not the main shell any more, or when the
     * file has changed: the main shell writes it each time its own cover goes up or away, so the guard's cover follows
     * the main one's within the same few seconds it did before, without a fork every tick while nothing changes. While
     * the main shell is not there the guard alone keeps the back covered: each tick's read is the dump itself, as before.
     */
    private fun poll() {
        var seen: Pair<String?, Long>? = null
        var said: Boolean? = null
        while (!ending) {
            val f = File(RearCover.HINT_FILE)
            val now = runCatching { (if (f.exists()) f.readText() else null) to f.lastModified() }.getOrDefault(null to 0L)
            val writer = RearCover.Hint.writerOf(now.first)
            val alive = writer != null && RearCover.isMainShell(runCatching { File("/proc/$writer/cmdline").readText() }.getOrNull())
            if (alive != said) {
                said = alive
                Log.i(TAG, if (alive) "guard: the main shell is up (pid $writer) — reading on the log and the hint only" else "guard: no main shell — reading every ${POLL_MS} ms")
            }
            if (RearCover.guardReads(alive, now != seen)) read(force = !alive)
            seen = now
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { return }
        }
    }

    /** [force]: the window manager's dump whatever the binder says (RearTop.Reader). */
    private fun read(force: Boolean = false) {
        runCatching { focus = topReader.read(Panel.DISPLAY_ID, focus, force) }
        sync("read")
    }

    private fun schedule(r: Runnable, afterMs: Long) {
        try { settleClock.schedule(r, afterMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) { settle.dropped() }
    }

    private fun listen() {
        LogWatch.sweep(::exec).let { if (it > 0) Log.i(TAG, "guard: $it stale log watchers ended") }
        while (!ending) {
            val proc = try {
                LogWatch.start("events", "wm_set_resumed_activity:I", "wm_task_moved:I", "wm_on_top_resumed_gained_called:I")
            } catch (e: Exception) { Log.w(TAG, "guard: no logcat (${e.message})"); return }
            // Lines may have gone by while no reader was there (before this one started: the sweep, the last one's end
            // and the second's wait): read once. Before, the poll's next tick caught them; while the main shell lives it
            // does not read any more unless the hint changes.
            read()
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!RearCover.changed(line)) continue
                        if (RearCover.launcherResumed(line)) { focus = RearCover.LAUNCHER; sync("Xiaomi's launcher resumed") }
                        read()
                        // Once more when the change has settled, off this thread's reading.
                        settle.line(android.os.SystemClock.uptimeMillis()).let { if (it >= 0L) schedule(settled, it) }
                    }
                }
            } catch (_: java.io.IOException) {
            } catch (e: Throwable) { Log.w(TAG, "guard: ${e.message}") }
            LogWatch.stop(proc)
            try { Thread.sleep(1_000L) } catch (_: InterruptedException) { return }
        }
    }

    private fun layerStack(): Int = RearStack.of(::exec)

    private fun exec(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
        p.waitFor()
        out.trim()
    } catch (e: Exception) { "" }

    private companion object {
        const val TAG = "AiUsageRear"
        const val POLL_MS = 2_500L
    }
}
