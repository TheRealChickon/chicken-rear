package dev.kb896.aiusage.rear

import android.util.Log
import dev.kb896.aiusage.IRearGuard
import dev.kb896.aiusage.Panel
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * The guard: a second shell process (through Shizuku, as RearUserService is) whose whole work is
 * RearCover. It keeps Xiaomi's rear screen covered while the shell's main process is not there to:
 * killed, crashed, being restarted. Measured on the phone before there was a guard:
 * the main process killed, Xiaomi's face stood on the back for as long as it took to come back.
 *
 * Its cover is one layer, under every layer of the main process ([ShellLayer.rootZ] one less), and
 * follows the same rule from the same signals: the hint file the main process keeps (a face of
 * ours is wanted), and the app on top of the back, read from the window manager whenever the
 * system's event log says apps have changed and every few seconds besides. It knows nothing else and talks to nobody.
 *
 * A daemon: it outlives the app's process. It does not outlive an update of the app (Shizuku ends
 * it with the old package), nor a restart of the phone.
 */
class RearGuardService : IRearGuard.Stub() {
    private val cover = RearCover(::layerStack) { 1024 to 640 }
    @Volatile private var focus: String? = null
    @Volatile private var ending = false

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
        runCatching { cover.set(RearCover.wanted(java.io.File(HINT).exists(), focus), why) }
    }

    private fun poll() {
        while (!ending) {
            read()
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun read() {
        runCatching { focus = RearCover.focusOf(exec(RearCover.READ), Panel.DISPLAY_ID) }
        sync("read")
    }

    private fun listen() {
        LogWatch.sweep(::exec).let { if (it > 0) Log.i(TAG, "guard: $it stale log watchers ended") }
        while (!ending) {
            val proc = try {
                LogWatch.start("events", "wm_set_resumed_activity:I", "wm_task_moved:I", "wm_on_top_resumed_gained_called:I")
            } catch (e: Exception) { Log.w(TAG, "guard: no logcat (${e.message})"); return }
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!RearCover.changed(line)) continue
                        if (RearCover.launcherResumed(line)) { focus = RearCover.LAUNCHER; sync("Xiaomi's launcher resumed") }
                        read()
                        // Once more when the change has settled, off this thread's reading.
                        Thread({ try { Thread.sleep(250L) } catch (_: InterruptedException) {}; read() }, "guard-read").apply { isDaemon = true; start() }
                    }
                }
            } catch (_: java.io.IOException) {
            } catch (e: Throwable) { Log.w(TAG, "guard: ${e.message}") }
            LogWatch.stop(proc)
            try { Thread.sleep(1_000L) } catch (_: InterruptedException) { return }
        }
    }

    private fun layerStack(): Int {
        val dump = exec("dumpsys display | grep -iE 'mDisplayId=${Panel.DISPLAY_ID}|layerStack' | head -40")
        return Regex("""mDisplayId=${Panel.DISPLAY_ID}\b[\s\S]*?[lL]ayerStack[ =](\d+)""").find(dump)?.groupValues?.get(1)?.toIntOrNull() ?: Panel.DISPLAY_ID
    }

    private fun exec(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
        p.waitFor()
        out.trim()
    } catch (e: Exception) { "" }

    private companion object {
        const val TAG = "AiUsageRear"
        const val HINT = "/data/local/tmp/chicken-rear-cover"
        const val POLL_MS = 2_500L
    }
}
