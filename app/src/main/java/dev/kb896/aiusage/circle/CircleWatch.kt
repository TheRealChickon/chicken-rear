package dev.kb896.aiusage.circle

import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import dev.kb896.aiusage.IRearEvents
import dev.kb896.aiusage.rear.LogWatch
import dev.kb896.aiusage.rear.RearTop
import kotlin.concurrent.thread

/** Lives in the shell process only for the lifetime of an enabled app callback. */
internal class CircleWatch(private val events: IRearEvents, private val session: Long, private val launcherUid: Int) : IBinder.DeathRecipient {
    private val loop = HandlerThread("Circle gesture")
    private lateinit var handler: Handler
    private val readers = mutableListOf<Thread>()
    private val processes = mutableSetOf<Process>()
    @Volatile private var stopped = false
    private fun send(action: String, at: Long) {
        if (stopped) return
        val onHome = action == NavHold.ARM && RearTop.ask(android.view.Display.DEFAULT_DISPLAY)
            ?.let(RearTop::pick)?.substringBefore('/') == "com.miui.home"
        runCatching { events.onCircleGesture(session, action, at, onHome) }.onFailure { close() }
    }
    private var gesture = NavHold(::send)
    private var scheduled: Long? = null
    private val timer = Runnable {
        scheduled = null
        gesture.tick(SystemClock.uptimeMillis())
    }

    @Synchronized fun start() {
        loop.start()
        handler = Handler(loop.looper)
        try { events.asBinder().linkToDeath(this, 0) } catch (_: android.os.RemoteException) { close(); return }
        readSource(false)
        readSource(true)
    }

    private fun update(action: (NavHold, Long) -> Unit) {
        val now = SystemClock.uptimeMillis()
        handler.post {
            if (stopped) return@post
            action(gesture, now)
            val at = gesture.armTime()
            if (at != scheduled) {
                handler.removeCallbacks(timer)
                scheduled = at
                if (at != null) handler.postAtTime(timer, at)
            }
        }
    }

    private fun readSource(touch: Boolean) {
        readers += thread(name = if (touch) "Circle front touch" else "Circle launcher") {
            var failures = 0
            while (!stopped) {
                val began = SystemClock.uptimeMillis()
                var process: Process? = null
                try {
                    val p = if (touch) LogWatch.startFrontTouch(FrontTouchDevice.find() ?: "/dev/input/event7") else LogWatch.startNavGesture(launcherUid)
                    process = p
                    synchronized(this) {
                        if (stopped) return@thread
                        processes.add(p)
                    }
                    if (!touch) send(LISTENING, 0)
                    val input = FrontTouches(
                        { slot, x, y -> update { g, t -> g.down(slot, x, y, t) } },
                        { slot, moved, age -> update { g, t -> g.depart(slot, moved, age, t) } },
                    )
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (touch) input.read(line) else update { g, t -> g.launcher(line, t) }
                        }
                    }
                } catch (_: java.io.IOException) {
                    // Reconnect this source independently.
                } finally {
                    synchronized(this) { processes.remove(process) }
                    LogWatch.stop(process)
                }
                if (stopped) return@thread
                update { g, _ ->
                    if (touch) g.readerEnded() else {
                        send(NavHold.CANCEL, 0)
                        send(WAITING, 0)
                        gesture = NavHold(::send)
                    }
                }
                if (SystemClock.uptimeMillis() - began >= 60_000) failures = 0
                try { Thread.sleep(if (++failures <= 3) 2000 else 60_000) }
                catch (_: InterruptedException) { return@thread }
            }
        }
    }

    override fun binderDied() = close()

    @Synchronized fun close() {
        if (stopped) return
        stopped = true
        runCatching { events.asBinder().unlinkToDeath(this, 0) }
        processes.forEach(LogWatch::stop)
        readers.forEach(Thread::interrupt)
        handler.removeCallbacksAndMessages(null)
        loop.quit()
    }

    companion object {
        const val LISTENING = "LISTENING"
        const val WAITING = "WAITING"
    }
}
