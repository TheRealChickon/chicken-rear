package dev.kb896.aiusage.rear

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.Log
import dev.kb896.aiusage.circle.CircleWatch
import dev.kb896.aiusage.IRearEvents
import dev.kb896.aiusage.IRearService
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.service.ChargeBattery
import dev.kb896.aiusage.service.ChargeState
import rikka.shizuku.Shizuku

class ShizukuBridge(private val context: Context) {

    data class Status(val running: Boolean, val granted: Boolean, val rearDisplayId: Int) {
        val ready get() = running && granted && rearDisplayId >= 0
    }

    @Volatile private var service: IRearService? = null
    /** A placement waiting for the service: the payload, and whether it was asked to light the back. */
    private var pending: Pair<String, Boolean>? = null

    /**
     * Supervision wants an answer from the service and there was no binder to bind with yet:
     * bound as soon as Shizuku's binder arrives (see [armBinderListener]).
     */
    @Volatile private var bindWanted = false

    /**
     * The last payload handed to the shell (show or update), sent or not. A new shell process
     * starts with none — its portal settings (frame colour, grid, DPI, confined apps) at their
     * defaults until the next payload — so a (re)connect sends it first.
     */
    @Volatile private var lastPayload: String? = null
    private var binderListenerArmed = false

    /**
     * May the panel go on the rear display right now? Set by Graph to the onboarding's
     * answer (Setup.panelAllowed); the default says yes so the bridge is usable alone.
     * Checked on every placement and re-placement, so a step coming undone is honoured
     * within one watchdog tick — the panel comes down instead of being fought for.
     */
    var gate: (Status) -> Boolean = { true }

    /**
     * Companion mode (PanelConfig.rearMode): Xiaomi's rear screen is the base and no panel of
     * ours is ever placed, moved back or re-placed over it. Set by Graph from the config; the
     * default says no, so the bridge alone behaves as it always did.
     */
    var companion: () -> Boolean = { false }

    /**
     * Flush the queued payload as soon as Shizuku's binder shows up.
     *
     * Sticky, so it fires immediately if the binder is already there rather than
     * waiting for the next delivery — otherwise a race between the queue and the
     * binder's arrival strands the payload until something else calls show().
     * Registered at most once; Shizuku keeps listeners for the process's lifetime.
     */
    private fun armBinderListener() {
        if (binderListenerArmed) return
        binderListenerArmed = true
        try {
            Shizuku.addBinderReceivedListenerSticky {
                pending?.let { (p, light) -> pending = null; show(p, light) }
                if (bindWanted) { bindWanted = false; bind() }
            }
        } catch (e: Exception) {
            binderListenerArmed = false
            Log.e(TAG, "could not register the Shizuku binder listener", e)
        }
    }

    /**
     * Best-effort "is the panel currently meant to be in calibrate mode" flag — see
     * isCalibrating() below for what it can and cannot see.
     */
    @Volatile var calibrating = false
        private set

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, RearUserService::class.java.name))
            .daemon(false).processNameSuffix("rear").debuggable(false).version(3)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) IRearService.Stub.asInterface(binder) else null
            if (service != null) { wasBound = true; runCatching { binder?.linkToDeath(shellDeath, 0) }; dev.kb896.aiusage.service.RestartLog.say("shell service bound"); boundNow() }
            portalApps?.let { b -> runCatching { service?.setPortalApps(b) } }
            // The settings the shell runs on, before anything is placed or shown with them: a held
            // banner below is drawn by a shell that knows it is in companion mode, with notifications
            // on (both oneway, so they land in this order).
            lastPayload?.let { p -> runCatching { service?.update(p) } }
            // A banner that arrived while unbound comes up now, if it is still fresh (PendingPop).
            notices?.let { b ->
                val pop = pendingPop.take(android.os.SystemClock.elapsedRealtime())
                // Woken first, as the relay would have, had there been a shell to ask then.
                if (pop.isNotEmpty() && pendingPopWake) wake("a notification held while unbound")
                pendingPopWake = false
                runCatching { service?.setNotices(b, pop) }
            }
            runCatching { service?.setPanelGuarded(panelGuarded); service?.setPanelControlCenter(panelControlCenter) }
            // A new shell process starts awake (dozing false): a panel asleep on purpose
            // would otherwise be read and woken as if it were not. But only what this process
            // KNOWS: a new app process has not heard from the panel yet, and pushing its
            // default false overwrote the shell's true while the back slept. Not known, the
            // panel is asked to say it again (it answers ACTION_MIRROR_SYNC with ACTION_DOZING),
            // and the answer comes through setDozing below, to the shell as well.
            dozing.let { d ->
                if (d != null) runCatching { service?.setDozing(d) }
                else context.sendBroadcast(android.content.Intent(RearActivity.ACTION_MIRROR_SYNC).setPackage(context.packageName))
            }
            runCatching { service?.setEvents(events) }
            dev.kb896.aiusage.circle.CircleOption.refresh(context, reconnect = true)
            quickStates?.let { (states, reachable) -> runCatching { service?.setQuickStates(states, reachable) } }
            // A new shell process thinks no camera is in use: told again if one is.
            if (cameraInUse) runCatching { service?.setCameraInUse(true) }
            // And knows no battery: the scene's percent.
            battery?.let { (level, charging) -> runCatching { service?.setBattery(level, charging) } }
            val current = ChargeBattery.read(context)
            current.plugged?.let { plugged ->
                val active = plugged && current.state != ChargeState.Is.FULL
                runCatching { service?.restoreCharge(plugged, active, current.fast) }
            }
            ambient?.let { (lux, source) -> runCatching { service?.setAmbient(lux, source) } }
            dots?.let { d -> runCatching { service?.setDots(d) } }
            stay?.let { (level, why) -> runCatching { service?.setStay(level, why) } }
            media?.let { m -> runCatching { service?.setMedia(m) } }
            live?.let { l -> runCatching { service?.setLive(l) } }
            navigation?.let { n -> runCatching { service?.setLive(n) } }
            pending?.let { (p, light) -> pending = null; show(p, light) }
            // Now there is somebody to ask whether the panel is there: supervision, again.
            if (service != null) onConnected()
        }
        override fun onServiceDisconnected(name: ComponentName?) = onShellGone("disconnected")
    }

    /**
     * The shell's process is gone (killed, crashed): bound again at once, and asked for again until
     * it is back. While it is away the guard keeps the back covered; nothing else of ours is on it.
     *
     * Heard three ways, because the first alone was not enough on this phone (the process
     * killed, and no onServiceDisconnected came): the connection's own word, the binder's death, and
     * [ensureAlive], which the poll service asks every few seconds.
     */
    private fun onShellGone(why: String) {
        if (service == null && goneAt != 0L && android.os.SystemClock.elapsedRealtime() - goneAt < 1_000L) return
        goneAt = android.os.SystemClock.elapsedRealtime()
        service = null
        dev.kb896.aiusage.circle.CircleOption.refresh(context)
        Log.w(TAG, "the shell service is gone ($why) — binding it again")
        runCatching { dev.kb896.aiusage.service.HealthCheck.cue(context, dev.kb896.aiusage.service.Health.Cue.SHELL_GONE, "the shell gone") }
        for (after in longArrayOf(0L, 500L, 2_000L, 6_000L)) mainHandler.postDelayed({ if (service == null) bindWhenReachable() }, after)
    }
    @Volatile private var goneAt = 0L
    private val shellDeath = IBinder.DeathRecipient { onShellGone("its process died") }

    /** Asked every few seconds: a shell that no longer answers is bound again; so is a guard. Any thread. */
    fun ensureAlive() {
        // Without Shizuku there is nothing to bind with: its binder arriving binds both (onShizukuArrived).
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        val s = service
        if (s != null && !runCatching { s.asBinder().pingBinder() }.getOrDefault(false)) onShellGone("no answer")
        else if (s == null && wasBound && android.os.SystemClock.elapsedRealtime() - goneAt > 8_000L) onShellGone("still away")
        val g = guard
        if (g == null || !runCatching { g.pingBinder() }.getOrDefault(false)) { guard = null; mainHandler.post { bindGuard() } }
    }
    @Volatile private var wasBound = false

    // -- the guard: a second shell process that only keeps Xiaomi's rear screen covered (RearGuardService) --

    private val guardArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, RearGuardService::class.java.name))
            .daemon(true).processNameSuffix("guard").debuggable(false).version(9)
    }
    @Volatile private var guard: IBinder? = null
    private val guardConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            guard = binder?.takeIf { it.pingBinder() }
            if (guard != null) { dev.kb896.aiusage.service.RestartLog.say("guard bound"); boundNow() }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            guard = null
            for (after in longArrayOf(0L, 1_000L, 5_000L)) mainHandler.postDelayed({ bindGuard() }, after)
        }
    }

    private fun bindGuard() {
        if (guard != null) return
        runCatching { Shizuku.bindUserService(guardArgs, guardConnection) }.onFailure { Log.e(TAG, "guard: bind failed", it) }
    }

    // -- after a restart: both shell services bound each time Shizuku's binder arrives (service/ShizukuArrival) --

    private val bindBackoff = dev.kb896.aiusage.service.Backoff()
    private val bindCheck = Runnable { checkBound() }

    /** Whether the main shell service and the guard both answer now. */
    fun shellBound(): Boolean = runCatching { service?.asBinder()?.pingBinder() == true }.getOrDefault(false)
    fun guardBound(): Boolean = runCatching { guard?.pingBinder() == true }.getOrDefault(false)

    /**
     * Shizuku's binder has arrived (main thread): both shell services bound, now, and looked at again after
     * [dev.kb896.aiusage.service.Backoff]'s waits until both are. Not allowed in Shizuku: nothing to bind, said once.
     */
    fun onShizukuArrived() {
        dev.kb896.aiusage.circle.CircleOption.refresh(context)
        mainHandler.removeCallbacks(bindCheck)
        bindBackoff.reset()
        if (!status().granted) { dev.kb896.aiusage.service.RestartLog.say("Shizuku is there, but this app is not allowed in it: nothing bound"); return }
        bind()
        mainHandler.postDelayed(bindCheck, bindBackoff.next())
    }

    /** Shizuku's binder is gone: no more looks until it comes again. */
    fun onShizukuGone() {
        dev.kb896.aiusage.circle.CircleOption.refresh(context)
        mainHandler.removeCallbacks(bindCheck)
        bindBackoff.reset()
    }

    private fun checkBound() {
        if (shellBound() && guardBound()) return
        if (!status().granted) return
        val wait = bindBackoff.next()
        dev.kb896.aiusage.service.RestartLog.say("not bound yet (shell ${if (shellBound()) "yes" else "no"}, guard ${if (guardBound()) "yes" else "no"}) — binding again, next look in ${wait / 1000} s")
        if (!shellBound()) service = null
        if (!guardBound()) guard = null
        bind()
        mainHandler.postDelayed(bindCheck, wait)
        runCatching { dev.kb896.aiusage.service.HealthCheck.cue(context, dev.kb896.aiusage.service.Health.Cue.BIND_RETRY, "still not bound") }
    }

    private fun boundNow() {
        if (shellBound() && guardBound()) { mainHandler.removeCallbacks(bindCheck); bindBackoff.reset() }
        runCatching { dev.kb896.aiusage.service.HealthCheck.cue(context, dev.kb896.aiusage.service.Health.Cue.BOUND, "bound") }
    }

    fun status(): Status {
        val running = try { Shizuku.pingBinder() } catch (_: Exception) { false }
        val granted = try {
            running && !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
        return Status(running, granted, rearDisplayId())
    }

    /**
     * [Panel.DISPLAY_ID] is the single source of truth for which display the rear
     * panel is — never derive it. We only confirm that display actually exists, so
     * an absent panel still reports -1 and the front UI can say so.
     *
     * Deliberately NOT "first display that isn't the default": that heuristic is
     * correct only by topology coincidence and would silently select a Chromecast,
     * an HDMI output, or a virtual display if one were ever enumerated.
     */
    fun rearDisplayId(): Int {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return if (dm.getDisplay(Panel.DISPLAY_ID) != null) Panel.DISPLAY_ID else -1
    }

    fun requestPermission(@Suppress("UNUSED_PARAMETER") activity: Activity, requestCode: Int = 4117) {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { bind(); return }
        Shizuku.requestPermission(requestCode)
    }

    fun bind() {
        bindGuard()
        if (service != null) return
        try { Shizuku.bindUserService(args, connection) } catch (e: Exception) { Log.e(TAG, "bind failed", e) }
    }

    /**
     * Place the panel. [light]: somebody ASKED for it — the "Show on the back" button, the
     * notification's tap, the onboarding finishing — so the back is lit whatever it was. The
     * watchdog's recoveries pass false, and the back is then left as they find it (PlaceLight):
     * a new app process recovering a panel must not light a back asleep in a pocket.
     */
    fun show(payload: String, light: Boolean = true) {
        // Asked for by hand: the double tap's latch is the user's to end, and this ends it.
        if (light) clearManualOff("shown on the back")
        // Companion mode has no panel: Xiaomi's rear screen stays, and nothing is placed over
        // it. Nothing calls this then (UsagePollService skips placement); this is the backstop.
        if (companion()) {
            pending = null
            Log.i(TAG, "companion mode — Xiaomi's rear screen stays, no panel placed")
            return
        }
        calibrating = CalibrateMode.isCalibrate(payload)
        val s = status()
        if (!gate(s)) {
            // Not placed, and not queued either: the onboarding is not done. A panel that
            // is up from before the step came undone is taken down, so the two rear-screen
            // apps stop trading the display every tick.
            pending = null
            if (panelAlive() == true) {
                Log.i(TAG, "onboarding incomplete — taking the panel down")
                hide()
            } else {
                Log.i(TAG, "onboarding incomplete — not placing the panel")
            }
            return
        }
        lastPayload = payload
        if (!s.ready) {
            // Hold the payload instead of dropping it. This is the normal state for the
            // first seconds after the app is killed and restarted: Shizuku has to
            // re-deliver its binder to the fresh process, so status().ready is false
            // even though everything is installed and granted. Dropping the request
            // here meant the panel could not return until the next 20s watchdog tick
            // happened to land after the binder arrived — measured at ~30s. Queued
            // plus the listener below, it goes back the moment Shizuku is reachable.
            Log.w(TAG, "not ready: $s — queuing")
            pending = payload to light
            armBinderListener()
            return
        }
        val svc = service ?: run { pending = payload to light; bind(); return }
        try { svc.place(payload, s.rearDisplayId, light) } catch (e: Exception) { Log.e(TAG, "show failed", e); service = null }
    }

    /**
     * Run when the service connects (main thread). Set by UsagePollService to supervise again:
     * an unbound bridge cannot say whether the panel is there ([panelAlive] is null), so the
     * watchdog leaves it alone until this.
     */
    @Volatile var onConnected: () -> Unit = {}

    /**
     * Have the shell service bound: now if Shizuku is reachable, else the moment its binder
     * arrives. Companion mode's way in — nothing is placed there, which is what used to bind it.
     */
    fun connect() = bindWhenReachable()

    /** Bind now if Shizuku is reachable, or the moment its binder arrives. */
    private fun bindWhenReachable() {
        if (status().ready) bind() else { bindWanted = true; armBinderListener() }
    }

    fun update(payload: String) {
        calibrating = CalibrateMode.isCalibrate(payload)
        lastPayload = payload
        // To the panel from here, not relayed by the shell: its receiver takes only this app.
        // The shell is still told — it keeps the payload for its own re-placing and frame.
        toPanel(RearActivity.ACTION_STATE, payload)
        try { service?.update(payload) } catch (e: Exception) { Log.e(TAG, "update failed", e); service = null }
    }

    fun hide() {
        toPanel(RearActivity.ACTION_FINISH)
        try { service?.hide() } catch (e: Exception) { Log.e(TAG, "hide failed", e) }
    }

    /**
     * Hide or restore HyperOS's rear-screen app. Null when the user service is not bound
     * yet — a bind is started, and the caller should say "try again in a moment" rather
     * than "failed".
     */
    /**
     * The user service, bound if it was not — waiting up to [timeoutMs] for Shizuku to
     * start the process and hand over the binder. Blocks: call it off the main thread.
     * Null when Shizuku never answered, which with the permission granted means it is
     * not running.
     */
    fun awaitService(timeoutMs: Long = 6_000): IRearService? {
        service?.let { return it }
        bind()
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (service == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        return service
    }

    fun setXiaomiRearApp(installed: Boolean): Boolean? {
        // Nothing else binds the service while the onboarding is incomplete — placement,
        // which used to do it as a side effect, is gated on that very onboarding — so
        // the row binds and waits rather than asking the user to tap twice.
        val svc = awaitService() ?: return null
        return try {
            if (installed) svc.restoreXiaomiRearApp() else svc.removeXiaomiRearApp()
        } catch (e: Exception) {
            Log.e(TAG, "xiaomi rear app change failed", e); service = null; false
        }
    }

    /**
     * Notification access for this app's own [component] through the shell (IRearService.
     * allowListener). True: allowed now. False: the shell ran it and it did not take. Null: no
     * service to ask (Shizuku down or not allowed). Blocks: off the main thread.
     */
    fun allowListener(component: String): Boolean? {
        val svc = awaitService() ?: return null
        return try { svc.allowListener(component) } catch (e: Exception) {
            Log.e(TAG, "allow_listener failed", e); service = null; null
        }
    }

    /** Whether Xiaomi's own rear notifications show their text; null when the service does not answer. Blocks. */
    fun xiaomiNoticeText(): Boolean? {
        val svc = awaitService() ?: return null
        return try { svc.xiaomiNoticeText() } catch (e: Exception) {
            Log.e(TAG, "xiaomi notice text read failed", e); service = null; null
        }
    }

    /** Switch Xiaomi's rear notification text; true when it now reads as asked. Blocks. */
    fun setXiaomiNoticeText(on: Boolean): Boolean {
        val svc = awaitService() ?: return false
        return try { svc.setXiaomiNoticeText(on) } catch (e: Exception) {
            Log.e(TAG, "xiaomi notice text write failed", e); service = null; false
        }
    }

    /** The rear-display report for a bug report; null when the service does not answer. */
    fun rearReport(): String? {
        val svc = awaitService() ?: return null
        return try {
            svc.rearReport()
        } catch (e: Exception) {
            Log.e(TAG, "rear report failed", e); service = null; null
        }
    }

    /** The rear display's brightness, 1 to 255; null when the service does not answer. */
    fun rearBrightness(): Int? {
        val svc = awaitService() ?: return null
        return try {
            svc.rearBrightness().takeIf { it in 1..255 }
        } catch (e: Exception) {
            Log.e(TAG, "rear brightness read failed", e); service = null; null
        }
    }

    /** Set the rear display's brightness, 1 to 255; false when the service does not answer. */
    fun setRearBrightness(value: Int): Boolean {
        val svc = awaitService() ?: return false
        return try {
            svc.setRearBrightness(value); true
        } catch (e: Exception) {
            Log.e(TAG, "rear brightness write failed", e); service = null; false
        }
    }

    /** Whether the rear brightness is automatic; null when the service does not answer. */
    fun rearAutoBrightness(): Boolean? {
        val svc = awaitService() ?: return null
        return try { svc.rearAutoBrightness() } catch (e: Exception) {
            Log.e(TAG, "rear auto brightness read failed", e); service = null; null
        }
    }

    fun setRearAutoBrightness(on: Boolean): Boolean {
        val svc = awaitService() ?: return false
        return try { svc.setRearAutoBrightness(on); true } catch (e: Exception) {
            Log.e(TAG, "rear auto brightness write failed", e); service = null; false
        }
    }

    /** Bluetooth, mobile data and Wi-Fi, on or off; null when the service does not answer. */
    fun quickStates(): BooleanArray? {
        val svc = awaitService() ?: return null
        return try {
            svc.quickStates().split(",").map { it.trim() == "1" }.takeIf { it.size == 3 }?.toBooleanArray()
        } catch (e: Exception) {
            Log.e(TAG, "quick states failed", e); service = null; null
        }
    }

    fun setQuick(which: String, on: Boolean): Boolean {
        val svc = awaitService() ?: return false
        return try { svc.setQuick(which, on); true } catch (e: Exception) {
            Log.e(TAG, "quick $which failed", e); service = null; false
        }
    }

    /**
     * Whether the panel is there (IRearService.isAlive); NULL when there is no service to ask.
     *
     * Not bound used to read as DEAD, and that is the state of every new app process — HyperOS
     * restarting the app, an update, a swipe from recents — so each of those re-placed a panel
     * that was most likely fine, and the placement lit the back in a pocket. Unknown is not
     * dead: a bind is started here, and [onConnected] runs supervision again with a real answer.
     * A call that fails is unknown too — the service has gone, and is bound again.
     */
    fun panelAlive(): Boolean? {
        val svc = service ?: run { bindWhenReachable(); return null }
        return try { svc.isAlive() } catch (e: Exception) {
            Log.e(TAG, "isAlive failed", e); service = null; bindWhenReachable(); null
        }
    }

    /**
     * Pulls the panel back to the rear display if it has been hoisted onto the main
     * one. Cheap and silent when nothing is wrong — see IRearService.ensureOnRear.
     */
    fun ensureOnRear(): Boolean {
        val svc = service ?: return false
        val id = rearDisplayId()
        if (id < 0) return false
        // Nothing of ours to move back in companion mode: what is on the rear is Xiaomi's.
        if (companion()) return false
        if (!gate(status())) return false
        return try { svc.ensureOnRear(id) } catch (e: Exception) {
            Log.e(TAG, "ensureOnRear failed", e); service = null; false
        }
    }

    /**
     * Whether the last payload THIS bridge instance was asked to show or update was
     * a calibrate payload — the watchdog's guard against re-placing a panel that is
     * mid-calibration (Task 15 Step 2b).
     *
     * DEAD CODE TODAY, on purpose, not by oversight: calibrate mode was reachable ONLY
     * via a manual `adb shell am broadcast ... --es state '{"mode":"calibrate"}'`, straight
     * to RearActivity's receiver — which no longer takes broadcasts from outside the app
     * (2026-10-30), so not even that now; the in-app Advanced geometry fields cover it.
     * Nothing in the app calls show()/update() with a calibrate
     * payload yet, so this field is always false in practice and the guard can
     * never fire — until Task 17 (or whatever adds an in-app calibrate control)
     * wires it through this bridge, which is the natural integration (it already
     * shares Graph with UsagePollService, exactly like the "Show on back" button
     * does today), not a change made "just to activate this guard."
     *
     * This is intent tracking, not a live query, and that distinction matters here:
     * RearActivity runs in its own process (:rear), so once it dies there is
     * nothing left to ask "are you calibrating?" — this field is deliberately kept
     * in THIS process instead, specifically so it survives that death. That means
     * it is NOT equivalent to "RearActivity is currently rendering calibrate mode
     * right now" (which would trivially go false the instant the activity dies) —
     * it is "the last thing we told it to show was calibrate," which is exactly
     * what lets Watchdog.decide() still choose CALIBRATING (not DEAD) for a task
     * that crashed mid-calibration, so a crash there is left dead rather than
     * silently resurrected into the normal dial view. Once a calibrate control
     * exists and uses this bridge, that crash-recovery behaviour is real, not
     * merely documented.
     */
    /**
     * Ask the privileged service to put the system camera on the rear display.
     *
     * Silent on failure by design. This is triggered by a tap on the back of a phone
     * nobody is looking at a screen for — there is nowhere to show an error, and the
     * camera not appearing IS the message.
     */
    fun openCamera() {
        val display = rearDisplayId()
        if (display < 0) return
        runCatching { service?.openCamera(display) }
            .onFailure { Log.w(TAG, "camera launch failed: ${it.message}") }
    }

    /** The pinned apps' names and icons, kept to hand on whenever the service (re)connects. */
    @Volatile private var portalApps: android.os.Bundle? = null

    /**
     * Hands [apps] to the shell service, now or — not connected — when it connects. False
     * when the call failed (too big for the binder's room just then, the service gone):
     * the caller sends again later, since an equal load would otherwise never be re-sent.
     */
    fun setPortalApps(apps: android.os.Bundle): Boolean {
        portalApps = apps
        if (service == null) { bind(); return true }
        return runCatching { service?.setPortalApps(apps) }.onFailure {
            Log.w(TAG, "portal apps: ${it.message}")
            if (it is android.os.DeadObjectException) service = null
        }.isSuccess
    }

    /** Blocks while waiting for the service: call off the main thread. */
    fun sendFocusedToBack(): String {
        val display = rearDisplayId()
        if (display < 0) return ""
        val svc = awaitService() ?: return ""
        return runCatching { svc.sendFocusedToBack(display).orEmpty() }
            .onFailure {
                Log.w(TAG, "send to back failed: ${it.message}")
                if (it is android.os.DeadObjectException) service = null
            }.getOrDefault("")
    }

    /**
     * Open [pkg] on the back as the portal's guest, for a command from outside (remote control).
     * The shell's own launch, the one a tap in the sheet ends in — and so with the shell's own
     * checks of the name (RearUserService.launchGuestNow). WHO may ask and WHICH apps is decided
     * before this is called (RemoteGate, RemotePolicy): the shell's launch has no lock of its own,
     * the sheet's taps were its only door. Blocks for seconds: off the main thread.
     */
    fun launchGuest(pkg: String): Boolean {
        val display = rearDisplayId()
        if (display < 0) return false
        val svc = awaitService() ?: return false
        return runCatching { svc.launchGuest(pkg, display) }
            .onFailure {
                Log.w(TAG, "launch on the back failed: ${it.message}")
                if (it is android.os.DeadObjectException) service = null
            }.getOrDefault(false)
    }

    /** "close", "front", "panel" or "resume" for the guest (IRearService.guestAction). Blocking, as [launchGuest]. */
    fun guestAction(which: String): Boolean {
        val svc = awaitService() ?: return false
        return runCatching { svc.guestAction(which) }
            .onFailure {
                Log.w(TAG, "guest $which failed: ${it.message}")
                if (it is android.os.DeadObjectException) service = null
            }.isSuccess
    }

    /**
     * The back's lock as the shell last said it (BackLock, through IRearEvents.onBackLock);
     * null until it has said. Kept for what asks before opening something on the back from
     * outside, where the sheet's own lock is not in the way.
     */
    @Volatile var backLocked: Boolean? = null
        private set

    /** A word to the panel from inside the app, with what it is about: its receiver takes nobody else. */
    fun tellPanel(action: String, extras: android.content.Intent.() -> Unit = {}) {
        context.sendBroadcast(android.content.Intent(action).setPackage(context.packageName).apply(extras))
    }

    /**
     * Light the rear panel, if it has gone to sleep.
     *
     * The display dozes on its own to save battery, and a panel nobody woke is a panel
     * that missed whatever it was about to show — a notification arrives, the strip
     * animates, and the whole five seconds happen on a dark screen. So the things worth
     * looking at ask for the light first.
     *
     * Aimed at ONE display, which is the entire reason this goes through the privileged
     * service rather than a window flag. RearActivity used to carry FLAG_TURN_SCREEN_ON
     * and it woke the phone's main screen every time the panel was re-placed, because the
     * activity is created on display 0 before being moved. A keyevent can be pointed
     * somewhere; a flag on a window cannot.
     *
     * Silent on failure, like openCamera: there is nowhere to report it, and a rear panel
     * that stays dark is its own message.
     */
    /**
     * Light the rear display, for something that just happened on it.
     *
     * [reason] is only for the log, and it is there because this is the kind of thing
     * that fails silently: the panel is usually lit already, so a wake that never
     * happened looks exactly like one that did. When a charge or a notice turns out to
     * have animated in the dark, the question is whether this was reached at all.
     *
     * Costs nothing when the display is already on, which is most of the time.
     */
    fun wake(reason: String = "", byHand: Boolean = false): Boolean {
        // AT NIGHT WITH THE FRONT OFF nothing of ours lights the back (NightRule): not a notification, not the
        // charger, not being picked up. A lit back keeps the whole phone awake and Xiaomi's night mode out.
        // [byHand]: he asked for it himself, just now.
        if (!byHand && dev.kb896.aiusage.service.NightRule.declinesWake(nightNow(), dev.kb896.aiusage.service.FrontScreen.on(context))) {
            if (reason.isNotEmpty()) Log.i(TAG, "wake($reason) declined — night, the front off")
            return false
        }
        // In a pocket (pocket mode switched the back off for it): a notification, the
        // charger, always-on after the lock — none of them lights the back inside it. What
        // arrived meanwhile is in the panel's list when the pocket is left, the slider up.
        if (pocketed) {
            if (reason.isNotEmpty()) Log.i(TAG, "wake($reason) declined — the back is in a pocket")
            return false
        }
        // Switched off by the double tap (ManualOff): off until the user lights it himself.
        if (manualOff.on) {
            if (reason.isNotEmpty()) Log.i(TAG, "wake($reason) declined — the back was switched off by hand")
            return false
        }
        // Lying on its back (RearPosture): off, and lit again when picked up (TableWatch).
        if (lyingOnBack) {
            if (reason.isNotEmpty()) Log.i(TAG, "wake($reason) declined — the phone lies on its back")
            return false
        }
        // Switched off by camera mode's double tap: off until the camera closes (CameraWatch).
        if (cameraDark) {
            if (reason.isNotEmpty()) Log.i(TAG, "wake($reason) declined — the back was switched off for the camera")
            return false
        }
        val display = rearDisplayId()
        if (display < 0) return false
        val svc = service
        if (svc == null) {
            Log.w(TAG, "wake($reason) with no service — display stays as it was")
            return false
        }
        // COMPANION MODE'S POCKET: pocket mode's watch is off there (it acts on Xiaomi's screen),
        // so [pocketed] is never set; a dark back is read fresh instead, and not lit in a pocket.
        if (companion() && darkNow(display) && pocketProbe.covered() == true) {
            if (reason.isNotEmpty()) Log.i(TAG, "wake($reason) declined — the rear sensor is covered (a pocket)")
            return false
        }
        // READ THE STATE BEFORE THE KEYEVENT, because afterwards there is nothing to
        // read. This is the only moment anyone can tell the difference between a panel
        // somebody is looking at and a panel that is about to light up in a pocket, and
        // that difference is the whole basis for locking. A fifth of a second later, when
        // the notification reaches the panel, both look identical.
        val wasDark = darkNow(display)
        // The other build (release vs dev) has the back: this install's notification,
        // charger or always-on is not a reason to light it. The shell checks, as it wakes.
        runCatching { svc.wakeOwn(display) }
            .onSuccess { woke ->
                if (!woke) {
                    Log.i(TAG, "wake($reason) skipped — the other build's panel has the back, or it is in a pocket")
                    return false
                }
                if (reason.isNotEmpty()) {
                    Log.i(TAG, "woke rear display for $reason (was dark: $wasDark)")
                }
            }
            .onFailure { Log.w(TAG, "wake($reason) failed: ${it.message}"); return false }
        // Somebody has decided the panel should be lit, so it is no longer asleep on
        // purpose. Cleared on this side too, not just in the service, or the watchdog here
        // would go on standing down over a panel that is wide awake.
        dozing = false
        return wasDark
    }

    /**
     * Whether the rear display is currently showing nothing.
     *
     * Anything that is not STATE_ON counts. The states this device actually reports are
     * ON, OFF and the two DOZE flavours, and a dozing panel is as unwatched as a dark
     * one — MIUI drops this display to DOZE_SUSPEND to save battery while the window
     * carries on believing it is visible. Treating only OFF as dark would miss the case
     * the user reported.
     *
     * An unknown display is NOT dark. Failing that way round means an unreadable state
     * leaves the panel usable rather than locking somebody out of their own phone.
     */
    private fun darkNow(displayId: Int): Boolean {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val state = dm.getDisplay(displayId)?.state ?: return false
        return state != android.view.Display.STATE_ON
    }

    fun isCalibrating(): Boolean = calibrating

    /**
     * The panel is asleep on purpose, or is not any more.
     *
     * Held HERE as well as forwarded, for the same reason `calibrating` is: the watchdog
     * in this process has to be able to ask, and asking across the binder would fail
     * exactly when the service is not bound — which is one of the states the watchdog
     * exists to notice.
     */
    fun setDozing(value: Boolean) {
        dozing = value
        runCatching { service?.setDozing(value) }
            .onFailure { Log.w(TAG, "setDozing($value) failed: ${it.message}") }
    }

    /**
     * Whether the App Portal's guest is in front of the panel on the back (IRearService.guestOnTop);
     * null with no service to ask. Never binds: asked at the lock, where not knowing is an answer.
     */
    fun guestOnTop(): Boolean? {
        val svc = service ?: return null
        return runCatching { svc.guestOnTop() }.onFailure { Log.w(TAG, "guestOnTop failed: ${it.message}") }.getOrNull()
    }

    /** Asleep on purpose as far as this process knows; not known counts as not. */
    fun isDozing(): Boolean = dozing == true

    /** The panel's last word on its hold: null until this process has heard one. */
    fun dozingOrNull(): Boolean? = dozing

    /**
     * The panel's lock slider / pocket lock / always-on is up, or not. Kept, like [dozing],
     * and handed on again whenever the service (re)connects: a new shell process starts
     * unguarded. Never binds — a panel that says this is already being served.
     */
    fun setPanelGuarded(value: Boolean, controlCenter: Boolean = false) {
        panelGuarded = value
        panelControlCenter = controlCenter
        runCatching { service?.setPanelGuarded(value); service?.setPanelControlCenter(controlCenter) }
            .onFailure { Log.w(TAG, "setPanelGuarded($value) failed: ${it.message}") }
    }

    @Volatile private var panelGuarded = false

    /** The panel's screenshot or recording tile: the shell captures the back (IRearService.capture). */
    fun capture(record: Boolean) {
        runCatching { service?.capture(record) }.onFailure { Log.w(TAG, "capture failed: ${it.message}") }
    }

    /** The panel asked for the back's code: the shell's keypad over it (IRearService.askBackCode). */
    fun askBackCode() {
        runCatching { service?.askBackCode() }.onFailure { Log.w(TAG, "askBackCode failed: ${it.message}") }
    }
    @Volatile private var panelControlCenter = false

    /**
     * Pocket mode switched the back off for a pocket, and has not brought it back: [wake]
     * declines. Here as well as in the shell so the decline costs no binder call, and is
     * logged where the wake was asked for. Lost with this process — the shell's own holds.
     */
    @Volatile private var pocketed = false

    /** In a pocket as far as this process knows. */
    fun isPocketed(): Boolean = pocketed

    /** Companion mode's one reading of the rear sensor before a wake from the dark ([wake]). */
    private val pocketProbe by lazy { dev.kb896.aiusage.service.RearProximity.Probe(context) }

    /**
     * CAMERA MODE's double tap switched the back off, and the camera has not closed yet: [wake]
     * declines — a notification, the charger, the table — so the back stays off until it does.
     * Set and cleared by CameraWatch only.
     */
    @Volatile var cameraDark = false

    /** The double tap's latch (ManualOff), read fresh at every wake: the panel's process writes it. */
    val manualOff by lazy { ManualOff.of(context) }

    /**
     * The user lit the back himself ("Show on the back", the rear lighting up while it was set):
     * the latch off, and the panel told. True if it was set.
     */
    fun clearManualOff(why: String): Boolean {
        if (!manualOff.set(false)) return false
        Log.i(TAG, "manual off: cleared — $why")
        tellPanel(RearActivity.ACTION_MANUAL_OFF) { putExtra(RearActivity.EXTRA_MANUAL_OFF, false) }
        return true
    }

    /**
     * Lying on its back, settled (TableWatch, LyingOnBack): [wake] declines, and pocket mode's way
     * out does not light the back. Lost with this process; the watch finds it again.
     */
    @Volatile var lyingOnBack = false

    /** A camera in use, as last told to the shell — and told again when it reconnects. */
    @Volatile private var cameraInUse = false

    /** IRearService.setCameraInUse: the keep-alive covers nothing on the rear while it is. */
    fun setCameraInUse(inUse: Boolean) {
        cameraInUse = inUse
        runCatching { service?.setCameraInUse(inUse) }
            .onFailure { Log.w(TAG, "setCameraInUse($inUse) failed: ${it.message}") }
    }

    /**
     * Whether the rear is the camera's or a guest's now (IRearService.cameraOnRear); null with
     * no service to ask. Blocking (execs in the shell): off the main thread.
     */
    fun cameraOnRear(): Boolean? {
        val svc = service ?: return null
        val display = rearDisplayId()
        if (display < 0) return null
        return runCatching { svc.cameraOnRear(display) }
            .onFailure { Log.w(TAG, "cameraOnRear failed: ${it.message}") }.getOrNull()
    }

    /** The charger went in. Oneway; the caller has already waited for the shell. */
    fun showCharge(full: Boolean, fast: Boolean): Boolean {
        val svc = service ?: return false
        return runCatching { svc.showCharge(full, fast); true }
            .onFailure { Log.w(TAG, "showCharge failed: ${it.message}"); if (it is android.os.DeadObjectException) service = null }
            .getOrDefault(false)
    }

    /** Main-thread enrichment from the battery watcher while it is running. */
    internal var chargeDetailFor: ((ChargeBattery.Snapshot) -> android.os.Bundle)? = null

    /** The battery as it is, for the charging page while it is up. Oneway; never binds. */
    fun chargeDetail(b: android.os.Bundle) {
        runCatching { service?.chargeDetail(b) }
    }

    /** Which way is down for the charging liquid, the gravity sensor's x and y. Oneway; never binds. */
    fun chargeTilt(x: Float, y: Float) {
        runCatching { service?.chargeTilt(x, y) }
    }

    /** The battery is full, the charger still in: the charging page leaves the back. Never binds. */
    fun chargeFull() {
        runCatching { service?.chargeFull() }.onFailure { Log.w(TAG, "chargeFull failed: ${it.message}") }
    }

    /** The charger came out: the animation drains away if it is up. Never binds. */
    fun endCharge() {
        runCatching { service?.endCharge() }.onFailure { Log.w(TAG, "endCharge failed: ${it.message}") }
    }

    /**
     * "Play on the back" (IRearService.playWake): [preview] on the back now, waking it first if it
     * is dark — through [wake], so its declines hold (switched off by hand, lying on its back, a
     * pocket). Blocking, off the main thread. False if it was not asked, or the back stayed dark.
     */
    /** A camera on the phone is open, as the shell last said (IRearEvents.onCameraOpen). */
    @Volatile var cameraOpen = false
        private set

    /** The stay level last asked for (IRearService.setStay), kept for a shell that connects later. */
    @Volatile private var stay: Pair<Int, String>? = null

    /** IRearService.setStay: whether the back is to stay lit with the scene on it. */
    fun setStay(level: Int, why: String) {
        stay = level to why
        runCatching { service?.setStay(level, why) }
    }

    /** The light around the phone as last read (IRearService.setAmbient), kept for a shell that connects later. */
    @Volatile private var ambient: Pair<Float, Int>? = null

    /** IRearService.setAmbient: the lux the back's brightness goes by, and which sensor read it (service/AmbientLight). */
    fun setAmbient(lux: Float, source: Int) {
        ambient = lux to source
        runCatching { service?.setAmbient(lux, source) }
    }

    /** The ":dots" process's binder (rear/dots/DotsLink), kept for a shell that connects later. */
    @Volatile private var dots: IBinder? = null

    /** IRearService.setDots: OpenAI's Dots character for the usage page, or null. */
    fun setDots(b: IBinder?) {
        dots = b
        runCatching { service?.setDots(b) }
    }

    /** The battery as the app last read it, kept for a shell that connects later. */
    @Volatile private var battery: Pair<Int, Boolean>? = null

    /** IRearService.setBattery: the scene's percent on the back. Only a change is sent. */
    fun setBattery(level: Int, charging: Boolean) {
        val next = level to charging
        if (battery == next) return
        battery = next
        runCatching { service?.setBattery(level, charging) }
    }

    /**
     * "Play on the back" for the scene: "wake" (the wake clip, then the loop), "loop", or "stop".
     * The back is woken for it when dark, through [wake] so its declines hold. Blocking, off the
     * main thread. False if it could not be asked.
     */
    fun playScene(what: String): Boolean {
        val svc = awaitService(CHARGE_BIND_MS) ?: return false
        runCatching { svc.playScene(what) }
            .onFailure { Log.w(TAG, "playScene failed: ${it.message}"); if (it is android.os.DeadObjectException) service = null; return false }
        if (what == "stop") return true
        val display = rearDisplayId()
        if (display >= 0 && darkNow(display)) wake("a preview")
        return true
    }

    /** The rear display as it is now, a small JPEG, or null. Blocking, off the main thread. */
    fun rearShot(): ByteArray? {
        val svc = awaitService(CHARGE_BIND_MS) ?: return null
        return runCatching { svc.rearShot() }.onFailure { Log.w(TAG, "rearShot failed: ${it.message}") }.getOrNull()
    }

    fun playWake(preview: WakeStyle.Preview): Boolean {
        val svc = awaitService(CHARGE_BIND_MS) ?: return false
        // Asked first, so the wake below is the one it plays on.
        runCatching { svc.playWake(WakeStyle.encode(preview)) }
            .onFailure { Log.w(TAG, "playWake failed: ${it.message}"); if (it is android.os.DeadObjectException) service = null; return false }
        val display = rearDisplayId()
        if (display < 0 || !darkNow(display)) return true
        wake("a preview")
        // The back takes a moment to report lit after the key.
        val until = android.os.SystemClock.uptimeMillis() + PREVIEW_LIT_MS
        while (darkNow(display)) {
            if (android.os.SystemClock.uptimeMillis() >= until) return false
            try { Thread.sleep(50L) } catch (_: InterruptedException) { return false }
        }
        return true
    }

    /** Camera mode's double tap: the back off now (IRearService.rearSleep). Blocking: off the main thread. */
    fun rearSleep(): Boolean {
        val svc = service ?: return false
        val display = rearDisplayId()
        if (display < 0) return false
        return runCatching { svc.rearSleep(display) }
            .onFailure { Log.w(TAG, "rearSleep failed: ${it.message}") }.getOrDefault(false)
    }

    /**
     * Switch the back off for a pocket (IRearService.pocketSleep); false if it was not —
     * no service, or the shell refused. Blocking (an exec in the shell): off the main thread.
     */
    fun pocketSleep(): Boolean {
        val display = rearDisplayId()
        val svc = service ?: return false
        if (display < 0) return false
        val done = runCatching { svc.pocketSleep(display) }
            .onFailure { Log.w(TAG, "pocketSleep failed: ${it.message}") }.getOrDefault(false)
        if (done) pocketed = true
        return done
    }

    /**
     * Out of the pocket, the back switched on again when [light]; or the pocket called off,
     * the back left as it is. Wakes work again either way — here at once, in the shell when
     * the call lands. Blocking, like [pocketSleep].
     */
    /** Since the last touch on the back, or null: unbound, an older shell, or no touch read yet. */
    fun restingFaceDown(client: Int, watch: Boolean): Boolean =
        runCatching { service?.restingFaceDown(client, watch) }.getOrNull() == true

    var onRestingFaceDownChanged: () -> Unit = {}

    fun msSinceRearTouch(): Long? =
        runCatching { service?.msSinceRearTouch() }.getOrNull()?.takeIf { it >= 0 }

    /** When this app last lit the back itself (elapsed real time): a rear display coming on right after is not his hand (StayOn.rearWoke). */
    @Volatile var ownWakeAt = 0L
        private set

    fun pocketEnd(light: Boolean) {
        pocketed = false
        val display = rearDisplayId()
        // Out of the pocket into a rule that keeps the back off: the pocket ends, unlit. AT NIGHT WITH THE FRONT OFF too
        // (the review's 33): the pocket's way out lit the back, and the app then took that light for his hand.
        val night = dev.kb896.aiusage.service.NightRule.declinesWake(nightNow(), dev.kb896.aiusage.service.FrontScreen.on(context))
        val lit = light && !manualOff.on && !lyingOnBack && !night
        if (light && !lit) Log.i(TAG, "pocketEnd: left dark — ${if (night) "night, the front off" else "switched off by hand, or lying on its back"}")
        if (lit) ownWakeAt = android.os.SystemClock.elapsedRealtime()
        runCatching { service?.pocketEnd(display, lit) }
            .onFailure { Log.w(TAG, "pocketEnd($light) failed: ${it.message}") }
    }

    /**
     * Null until the panel (or a wake from this process) has said: a new process does not
     * know, and guessing "awake" is what overwrote the shell's correct "asleep" on connect.
     */
    @Volatile private var dozing: Boolean? = null

    /**
     * The control centre's tiles — silent, Bluetooth, mobile data, Wi-Fi — and whether the
     * last three can be switched, for the one the shell holds over a guest. Kept, and handed
     * on again whenever the service (re)connects. Never binds.
     */
    fun setQuickStates(states: BooleanArray, reachable: Boolean) {
        quickStates = states.copyOf() to reachable
        runCatching { service?.setQuickStates(states, reachable) }
            .onFailure { Log.w(TAG, "setQuickStates failed: ${it.message}") }
    }

    @Volatile private var quickStates: Pair<BooleanArray, Boolean>? = null

    /**
     * An app's DPI nudged on the back: [dpi] for the package, 0 for its default. Set by
     * Graph, which saves it in the config and pushes the payload back; run on the main
     * thread, where the settings screen writes the config too, so the two never interleave.
     */
    var onPortalDpi: (pkg: String, dpi: Int) -> Unit = { _, _ -> }

    /** "Keep right of the cameras" switched on the back. Set by Graph, as [onPortalDpi]. */
    var onPortalConfined: (pkg: String, on: Boolean) -> Unit = { _, _ -> }

    /** An app's width set on the back, 0 for the whole. Set by Graph, as [onPortalDpi]. */
    var onPortalWidth: (pkg: String, width: Int) -> Unit = { _, _ -> }

    /** A button of the playing card pressed on the back (UsagePollService hands it to the media watcher). */
    var onMediaCommand: (action: String) -> Unit = { }

    /** The live card's notification (a timer, a call), kept for a shell that connects later. */
    @Volatile private var live: android.os.Bundle? = null
    /** The route being followed (NavigationNotification's packet, kept apart: a call or a timer does not replace it), likewise. */
    @Volatile private var navigation: android.os.Bundle? = null

    /** IRearService.health: the shell's half of the health check; null where no shell is bound. Blocks: off the main thread. */
    fun health(again: Boolean): android.os.Bundle? = runCatching { service?.health(again) }.getOrNull()

    /** IRearService.pinVia: a pin of ours has just been handed to Xiaomi's own. False where no shell is bound to hear it. */
    fun pinVia(b: android.os.Bundle): Boolean = runCatching { service?.pinVia(b); service != null }.getOrDefault(false)

    /** IRearService.earbuds: earbuds have connected; the back plays its moment if it may. False where no shell is bound. */
    fun earbuds(b: android.os.Bundle): Boolean = runCatching { service?.earbuds(b); service != null }.getOrDefault(false)

    fun setLive(b: android.os.Bundle) {
        if (b.getString("kind") == dev.kb896.aiusage.service.NavigationNotification.KIND) navigation = b else live = b
        runCatching { service?.setLive(b) }
    }

    /** IRearService.setMusicSound: what the sound does now (service/MusicSound's packet). Not kept: stale in a moment. */
    fun setMusicSound(v: FloatArray) { runCatching { service?.setMusicSound(v) } }

    /** What is playing, for the back's card; kept for a shell that connects later. */
    @Volatile private var media: android.os.Bundle? = null

    fun setMedia(b: android.os.Bundle) {
        media = b
        runCatching { service?.setMedia(b) }
    }

    /** A pinned note's bin was pressed on the back: taken off the list, and the payload pushed back (Graph). */
    var onPinRemoved: (at: Long) -> Unit = { }

    /** The pinned notes were put in order on the back: their names, the first first (Graph keeps it: PinOrder.apply). */
    var onPinsOrdered: (order: List<Long>) -> Unit = { }
    /** "Not now" on a note standing at its place on the back (IRearEvents.onArrivalLater): it waits again (Graph). */
    var onArrivalLater: (at: Long) -> Unit = { }

    /** The usage page was swiped in on the back: the poll service fetches and pushes (UsagePollService). */
    var onUsageShown: () -> Unit = { }

    /** A finger on the back while the back was not held lit (IRearEvents.onRearTouch): the stay-on rule looks again. */
    var onRearTouch: () -> Unit = { }
    var onRearWoke: () -> Unit = { }

    /** Night as "Sleep at night" means it (NightRule.now), set by the service that has the settings. */
    var nightNow: () -> Boolean = { false }

    /** A press and hold on the back picked another face: saved, and the payload pushed back (Graph). */
    var onFacePicked: (file: String) -> Unit = { }

    /** The face picker on the back chose a face (FaceCatalog key), the shell's choice number [seq]: saved and sent back (Graph). */
    var onFaceChosen: (key: String, seq: Long) -> Unit = { _, _ -> }

    /** The faces on the phone changed: the settings read the list again (faces/faces.json). */
    var onFacesChanged: () -> Unit = { }
    /** Listeners of [onFacesChanged] besides the Graph's (the settings' page, while it is open). */
    val facesListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** What the shell service tells this process (IRearEvents); handed over on every (re)connect. */
    @Volatile private var notices: android.os.Bundle? = null

    /**
     * The notifications over a guest, now or when it connects. [pop]: a key that has just arrived,
     * for its banner. [wakeIfHeld]: the banner was to wake a dark back, so if it has to wait for a
     * connection, the back is woken as it is handed over — the wake asked for at the post could
     * not be done then, with no shell to do it.
     */
    fun setNotices(list: android.os.Bundle, pop: String, wakeIfHeld: Boolean = false) {
        notices = list
        val now = android.os.SystemClock.elapsedRealtime()
        // Not bound: the list goes across on connect, and the banner with it if it is still
        // fresh then (PendingPop) — it used to be lost, every app restart.
        if (service == null) { holdPop(pop, now, wakeIfHeld); bindWhenReachable(); return }
        runCatching { service?.setNotices(list, pop) }
            .onSuccess { if (pop.isNotEmpty()) { pendingPop.clear(); pendingPopWake = false } }
            .onFailure {
                Log.w(TAG, "notices: ${it.message}")
                holdPop(pop, now, wakeIfHeld)
                if (it is android.os.DeadObjectException) { service = null; bindWhenReachable() }
            }
    }

    private fun holdPop(pop: String, now: Long, wake: Boolean) {
        if (pop.isEmpty()) return
        pendingPop.hold(pop, now)
        pendingPopWake = wake
    }

    /** The held banner ([pendingPop]) wants the back woken when it is handed over. */
    @Volatile private var pendingPopWake = false

    /** A banner that could not be handed over yet: see [setNotices]. */
    private val pendingPop = dev.kb896.aiusage.service.PendingPop()

    private var circleSession = 0L
    @Volatile internal var circleListening = false
        private set

    internal fun syncCircle(on: Boolean, launcherUid: Int, reconnect: Boolean) {
        if (!reconnect && on == (circleSession != 0L)) return
        circleListening = false
        circleSession = if (on) android.os.SystemClock.elapsedRealtimeNanos() else 0
        runCatching { service?.setCircleSession(circleSession, launcherUid) }
            .onFailure { circleSession = 0L }
    }

    private val events = object : IRearEvents.Stub() {
        override fun onChargeReading() {
            val uid = android.os.Binder.getCallingUid()
            if (uid != android.os.Process.SHELL_UID && uid != android.os.Process.ROOT_UID) return
            mainHandler.post {
                val battery = ChargeBattery.read(context)
                chargeDetail(chargeDetailFor?.invoke(battery) ?: battery.message())
            }
        }

        override fun onCircleGesture(session: Long, action: String?, uptime: Long, onHome: Boolean) {
            if (android.os.Binder.getCallingUid() != android.os.Process.SHELL_UID || action == null) return
            mainHandler.post {
                if (session == 0L || session != circleSession || !shellBound()) return@post
                when (action) {
                    CircleWatch.LISTENING, CircleWatch.WAITING -> {
                        circleListening = action == CircleWatch.LISTENING
                        dev.kb896.aiusage.circle.CircleOption.refresh(context)
                    }
                    else -> dev.kb896.aiusage.service.PinGlassService.circleGesture(action, uptime, onHome)
                }
            }
        }

        // A row swiped away over a guest: gone from the panel's list too, and from the front if
        // they are kept the same (PanelConfig.matchFrontNotifications), as the panel's own swipe is.
        override fun onNoticeDismissed(key: String?) {
            if (key.isNullOrEmpty()) return
            dev.kb896.aiusage.service.BackNotices.removed(context, key)
            context.sendBroadcast(
                android.content.Intent(RearActivity.ACTION_NOTIFICATION_REMOVED).setPackage(context.packageName)
                    .putExtra(RearActivity.EXTRA_NOTIFICATION_KEY, key),
            )
            // Swiped away on the back is dismissed: on the phone too, in companion mode (there is one list, his).
            val c = dev.kb896.aiusage.data.ConfigStore(context).load()
            if (!key.startsWith(RearDialView.KEYLESS) && (c.matchFrontNotifications || c.companion)) context.sendBroadcast(
                android.content.Intent(RearActivity.ACTION_DISMISS_NOTIFICATION).setPackage(context.packageName)
                    .putExtra(RearActivity.EXTRA_NOTIFICATION_KEY, key),
            )
        }

        // Thrown the snoozing way on the back (NoticeThrow): off the back's list as a dismissal is, and snoozed on the
        // phone for the hour by the relay; Android brings it back by itself after, and the relay shows it again then.
        override fun onNoticeSnoozed(key: String?, ms: Long) {
            if (key.isNullOrEmpty() || key.length > 1024 || dev.kb896.aiusage.rear.NoticeThrow.isDemo(key) || key.startsWith(RearDialView.KEYLESS)) return
            dev.kb896.aiusage.service.BackNotices.removed(context, key)
            context.sendBroadcast(
                android.content.Intent(RearActivity.ACTION_NOTIFICATION_REMOVED).setPackage(context.packageName)
                    .putExtra(RearActivity.EXTRA_NOTIFICATION_KEY, key),
            )
            context.sendBroadcast(
                android.content.Intent(RearActivity.ACTION_SNOOZE_NOTIFICATION).setPackage(context.packageName)
                    .putExtra(RearActivity.EXTRA_NOTIFICATION_KEY, key)
                    .putExtra(RearActivity.EXTRA_SNOOZE_MS, dev.kb896.aiusage.service.PhoneThrow.duration(ms)),
            )
        }

        override fun onPortalDpi(pkg: String?, dpi: Int) {
            if (pkg.isNullOrEmpty() || pkg.length > 255) return
            mainHandler.post { this@ShizukuBridge.onPortalDpi(pkg, dpi) }
        }

        override fun onPortalWidth(pkg: String?, width: Int) {
            if (pkg.isNullOrEmpty() || pkg.length > 255) return
            mainHandler.post { this@ShizukuBridge.onPortalWidth(pkg, width) }
        }

        // The shell's control centre over a guest: its tiles go the way the panel's do, as
        // the broadcasts UsagePollService already serves (RearActivity.ACTION_SET_QUICK,
        // ACTION_GET_QUICK), which switch them and send the states back to both.
        override fun onQuick(which: String?, on: Boolean) {
            if (which !in QUICK) return
            context.sendBroadcast(
                android.content.Intent(RearActivity.ACTION_SET_QUICK).setPackage(context.packageName)
                    .putExtra(RearActivity.EXTRA_QUICK, which).putExtra(RearActivity.EXTRA_QUICK_ON, on),
            )
        }

        override fun onControlCenterOpening() {
            context.sendBroadcast(android.content.Intent(RearActivity.ACTION_GET_QUICK).setPackage(context.packageName))
        }

        override fun onPortalOpened() {
            mainHandler.post { this@ShizukuBridge.onPortalOpened() }
        }

        // The shell's two words to the panel, passed on: the panel's receiver hears only this
        // app (RearActivity), and the shell is uid 2000.
        override fun onPanelPoke() = toPanel(RearActivity.ACTION_PORTAL_POKE)

        override fun onPanelStepBack() = toPanel(RearActivity.ACTION_PORTAL_STEP_BACK)

        override fun onPanelCloseControlCenter() = toPanel(RearActivity.ACTION_CLOSE_CONTROL_CENTER)

        // A screenshot or recording of the back, made by the shell in this app's files: into the
        // gallery, off the binder thread (CaptureStore).
        override fun onCaptured(file: android.os.ParcelFileDescriptor?, video: Boolean) {
            if (file == null) return
            CaptureStore.save(context, file, video)
        }

        // A screenshot of the back: claimed from the shell first (ShotKeeper), so that one the shell saved itself while
        // this app was frozen is not saved twice. No shell to ask (unbound, or it died): this app saves it.
        override fun onShot(file: android.os.ParcelFileDescriptor?, name: String?) {
            if (file == null) return
            val shot = name?.takeIf { BackShots.isName(it) }
            val claim = if (shot == null) ShotKeeper.Claim.SAVE
            else runCatching { service?.claimShot(shot) }.getOrNull()?.let { ShotKeeper.Claim.of(it) } ?: ShotKeeper.Claim.SAVE
            when (claim) {
                ShotKeeper.Claim.SAVE -> CaptureStore.save(context, file, video = false, name = shot)
                ShotKeeper.Claim.SCAN -> { runCatching { file.close() }; CaptureStore.scanShellShot(context, shot!!) }
                ShotKeeper.Claim.NOTHING -> runCatching { file.close() }
            }
        }

        override fun onDeleteLastShot() {
            CaptureStore.deleteLastShot(context)
        }

        // Companion mode's double tap off: the latch the panel would have set (ManualOff).
        override fun onCameraOpen(open: Boolean) {
            if (cameraOpen != open) Log.i(TAG, if (open) "camera: open" else "camera: closed")
            cameraOpen = open
        }

        override fun onLiveAction(key: String?, index: Int) {
            if (key.isNullOrEmpty()) return
            context.sendBroadcast(
                android.content.Intent(RearActivity.ACTION_NOTIFICATION_ACT).setPackage(context.packageName)
                    .putExtra(RearActivity.EXTRA_NOTIFICATION_KEY, key)
                    .putExtra(RearActivity.EXTRA_NOTIFICATION_ACTION, index)
                    .putExtra(RearActivity.EXTRA_NOTIFICATION_LIVE, true),
            )
        }

        override fun onMediaCommand(action: String?) {
            if (action in setOf(RearActivity.MEDIA_PLAY_PAUSE, RearActivity.MEDIA_NEXT, RearActivity.MEDIA_PREVIOUS)) mainHandler.post { this@ShizukuBridge.onMediaCommand(action!!) }
        }

        override fun onPinRemoved(at: Long) { mainHandler.post { this@ShizukuBridge.onPinRemoved(at) } }

        override fun onPinsOrdered(order: LongArray?) { val o = order?.toList() ?: return; mainHandler.post { this@ShizukuBridge.onPinsOrdered(o) } }
        override fun onArrivalLater(at: Long) { mainHandler.post { this@ShizukuBridge.onArrivalLater(at) } }

        override fun onUsageShown() { mainHandler.post { this@ShizukuBridge.onUsageShown() } }

        override fun onRearWoke() { mainHandler.post { this@ShizukuBridge.onRearWoke() } }

        override fun onRestingFaceDownChanged() { mainHandler.post { this@ShizukuBridge.onRestingFaceDownChanged() } }
        override fun onRearTouch() { mainHandler.post { this@ShizukuBridge.onRearTouch() } }

        override fun onWokenByHand() {
            mainHandler.post {
                // His own hand: "switched off by hand" is over, and the rule looks again.
                clearManualOff("a double tap on the dark back")
                this@ShizukuBridge.onRearTouch()
            }
        }

        override fun onFacePicked(file: String?) {
            if (file.isNullOrEmpty() || file.length > 255 || file.contains('/')) return
            mainHandler.post { this@ShizukuBridge.onFacePicked(file) }
        }

        override fun onFaceChosen(key: String?, seq: Long) {
            if (key.isNullOrEmpty() || key.length > 80 || !(FaceCatalog.ours(key) || Regex("xiaomi:[0-9a-fA-F-]{8,64}").matches(key))) return
            mainHandler.post { this@ShizukuBridge.onFaceChosen(key, seq) }
        }

        override fun onFacesChanged() { mainHandler.post { this@ShizukuBridge.onFacesChanged() } }

        override fun onNoticeAction(key: String?, index: Int) {
            if (key.isNullOrEmpty()) return
            context.sendBroadcast(
                android.content.Intent(RearActivity.ACTION_NOTIFICATION_ACT).setPackage(context.packageName)
                    .putExtra(RearActivity.EXTRA_NOTIFICATION_KEY, key)
                    .putExtra(RearActivity.EXTRA_NOTIFICATION_ACTION, index),
            )
        }

        override fun onSwitchedOffByHand() {
            if (manualOff.set(true)) Log.i(TAG, "manual off: set — the back went dark right after a double tap")
        }

        override fun onRecording(on: Boolean) {
            context.sendBroadcast(
                android.content.Intent(RearActivity.ACTION_RECORDING).setPackage(context.packageName)
                    .putExtra(RearActivity.EXTRA_RECORDING, on),
            )
        }

        override fun onBackLock(locked: Boolean) {
            backLocked = locked
            context.sendBroadcast(
                android.content.Intent(RearActivity.ACTION_BACK_LOCK).setPackage(context.packageName)
                    .putExtra(RearActivity.EXTRA_BACK_LOCKED, locked),
            )
        }

        override fun onPortalConfined(pkg: String?, on: Boolean) {
            if (pkg.isNullOrEmpty() || pkg.length > 255) return
            mainHandler.post { this@ShizukuBridge.onPortalConfined(pkg, on) }
        }

        override fun onPanelCovered(covered: Boolean) {
            context.sendBroadcast(
                android.content.Intent(RearActivity.ACTION_PORTAL_COVERED).setPackage(context.packageName)
                    .putExtra(RearActivity.EXTRA_PORTAL_COVERED, covered),
            )
        }
    }

    /** A broadcast to the panel, from inside the app — the only sender its receiver takes. */
    private fun toPanel(action: String, state: String? = null) {
        val intent = android.content.Intent(action).setPackage(context.packageName)
        if (state != null) intent.putExtra(RearActivity.EXTRA_STATE, state)
        context.sendBroadcast(intent)
    }

    /** The portal sheet was opened on the back (IRearEvents.onPortalOpened). Set by Graph; run on the main thread. */
    var onPortalOpened: () -> Unit = {}

    private companion object {
        const val TAG = "AiUsageBridge"
        /** How long the charger's animation waits for the shell to bind: past that, the moment has gone. */
        const val CHARGE_BIND_MS = 2_500L
        /** How long a preview's wake may take to show as lit. */
        private const val PREVIEW_LIT_MS = 1_000L
        /** The tiles the shell may ask to switch (ControlCenterPainter.QUICK_NAMES); nothing else. */
        val QUICK = ControlCenterPainter.QUICK_NAMES.toSet()
    }
}
