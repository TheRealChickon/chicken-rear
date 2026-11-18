package dev.kb896.aiusage.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import rikka.shizuku.Shizuku

object ShizukuArrival {
    @Volatile private var installed = false
    /** Since when Shizuku has been awaited (elapsed realtime); 0 while it is there. */
    @Volatile private var waitingSince = 0L
    /** When Shizuku last came or went, or the process started (elapsed realtime). */
    @Volatile private var changedAt = SystemClock.elapsedRealtime()

    /** How long ago Shizuku last came or went (or the process started): a restart's grace (Health.GRACE_MS). */
    fun sinceChangeMs(): Long = SystemClock.elapsedRealtime() - changedAt
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val up = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        RestartLog.say("process started, Shizuku ${if (up) "already there" else "not there yet"}")
        if (!up) waitingSince = SystemClock.elapsedRealtime()
        runCatching {
            Shizuku.addBinderReceivedListenerSticky({ arrived(app) }, main)
            Shizuku.addBinderDeadListener({ gone(app) }, main)
        }.onFailure { RestartLog.say("could not listen for Shizuku: ${it.message}") }
    }

    /** Said by the poll service as it starts: whether it now waits for Shizuku. */
    fun serviceStarted() {
        val up = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        RestartLog.say("service started")
        if (!up) {
            if (waitingSince == 0L) waitingSince = SystemClock.elapsedRealtime()
            RestartLog.say("waiting for Shizuku")
        }
    }

    private fun arrived(app: Context) {
        val now = SystemClock.elapsedRealtime()
        changedAt = now
        val since = waitingSince
        waitingSince = 0L
        RestartLog.say(if (since == 0L) "binder arrived (it was there as the process started)" else "binder arrived after ${RestartLog.seconds(since, now)} s")
        // The service first: it is what keeps the process and does everything else. From here the binder's arrival
        // (Shizuku's server starts the process and allows it to start a service for a while) or a running service.
        runCatching { UsagePollService.start(app) }.onFailure { RestartLog.say("service not started: ${it.javaClass.simpleName} ${it.message}") }
        runCatching { Graph.of(app).bridge.onShizukuArrived() }.onFailure { RestartLog.say("bind not started: ${it.message}") }
        HealthCheck.cue(app, Health.Cue.SHIZUKU_CAME, "Shizuku arrived")
    }

    private fun gone(app: Context) {
        waitingSince = SystemClock.elapsedRealtime()
        changedAt = waitingSince
        RestartLog.say("Shizuku gone — waiting for it to come back")
        runCatching { Graph.of(app).bridge.onShizukuGone() }
        HealthCheck.cue(app, Health.Cue.SHIZUKU_WENT, "Shizuku gone")
    }
}
