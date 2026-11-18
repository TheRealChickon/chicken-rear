package dev.kb896.aiusage.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.util.Log
import android.view.Display
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.StatePayload
import dev.kb896.aiusage.rear.ChargeStyle
import dev.kb896.aiusage.rear.RearActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The engine: polls both accounts on a display-aware cadence (Cadence.kt) and pushes
 * the encoded state to the rear panel. Also owns the respawn watchdog (Task 15 Step
 * 2b, added after the rest of this class was designed and just as load-bearing):
 * the panel is always-on at the user's explicit request (RearActivity holds
 * FLAG_KEEP_SCREEN_ON unconditionally), so it no longer dies to the system's
 * display timeout — but it can still be killed by memory pressure, a crash, or a
 * reboot. A dead activity runs no code and cannot revive itself, so this service
 * checks for that on every tick and re-places it through the one verified path,
 * ShizukuBridge.show(). See ensurePlaced() for the two guards that matter there.
 */
class UsagePollService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var displays: DisplayManager

    /** Watchdog's own memory of the last observed state, so it can tell a genuine
     *  transition from a repeated reading — see ensurePlaced(). */
    private var watchdogState: Watchdog.State? = null

    /** The control centre's brightness, written off the main thread, newest value only. */
    private val brightnessWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val latestBrightness = java.util.concurrent.atomic.AtomicInteger(-1)

    /**
     * HyperOS's rear brightness and its mode, watched: automatic changes the level on its
     * own, and the control centre's cell should move when the screen does — not when it
     * next asks. A change is read through Shizuku and sent on; a burst of them while
     * automatic settles is one read, whatever arrived while it was pending.
     */
    private val brightnessPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val brightnessWatch = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper()),
    ) {
        override fun onChange(selfChange: Boolean) {
            if (!brightnessPending.compareAndSet(false, true)) return
            brightnessWorker.execute {
                brightnessPending.set(false)
                sendRearBrightness()
            }
        }
    }

    /** Silent, Bluetooth, mobile data and Wi-Fi, to the panel and the shell's control centre. On brightnessWorker. */
    private fun sendQuickStates() {
        val audio = getSystemService(android.media.AudioManager::class.java)
        val silent = audio.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL
        val bridge = Graph.of(this).bridge
        val rest = bridge.quickStates()
        val states = booleanArrayOf(silent, rest?.get(0) ?: false, rest?.get(1) ?: false, rest?.get(2) ?: false)
        sendBroadcast(
            Intent(RearActivity.ACTION_QUICK_STATES).setPackage(packageName)
                .putExtra(RearActivity.EXTRA_QUICK_STATES, states)
                .putExtra(RearActivity.EXTRA_QUICK_REACHABLE, rest != null),
        )
        // And to the shell, for its control centre over a guest.
        bridge.setQuickStates(states, rest != null)
    }

    /** The switches, watched as the brightness is: a change anywhere reaches the tiles at once. */
    private val quickPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val quickWatch = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper()),
    ) {
        override fun onChange(selfChange: Boolean) {
            if (!quickPending.compareAndSet(false, true)) return
            brightnessWorker.execute {
                quickPending.set(false)
                sendQuickStates()
            }
        }
    }

    /** The rear brightness and whether it is automatic, to the panel. On brightnessWorker. */
    private fun sendRearBrightness() {
        val bridge = Graph.of(this).bridge
        val value = bridge.rearBrightness() ?: -1
        val auto = bridge.rearAutoBrightness() ?: false
        sendBroadcast(
            Intent(RearActivity.ACTION_REAR_BRIGHTNESS).setPackage(packageName)
                .putExtra(RearActivity.EXTRA_BRIGHTNESS, value)
                .putExtra(RearActivity.EXTRA_AUTO_BRIGHTNESS, auto),
        )
    }

    private val limitNotifier by lazy { LimitNotifier(this) }

    /**
     * The panel's power as this listener last saw it — see onDisplayChanged. Seeded from the
     * display itself as the listener is registered (onCreate), never assumed: see PanelPower.
     */
    private var panelPower = PanelPower(on = false)

    /** When the rear display last went dark, for telling a lock's sleep from an earlier one. */
    @Volatile private var rearWentOffAt = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}

        /**
         * Repaints the panel when it wakes. Deliberately does NOT fetch.
         *
         * This callback fires on every change to a display's properties — brightness
         * ramps, refresh-rate switches between 60 and 120Hz, rotation — not only on
         * power transitions. It previously ran a full safeTick() on each one, so an
         * ordinary brightness ramp produced a burst of requests, observed at up to
         * three per second.
         *
         * Two things fix that, and both are needed. The state check makes this act only
         * on a genuine off -> on transition instead of every property change. And a
         * wake does not need new data anyway: showing the panel what the repository
         * already holds is exactly the "no extra calls, just render what's already in"
         * rule the rest of this app follows. Fresh numbers arrive on the poll loop's
         * own schedule.
         */
        override fun onDisplayChanged(displayId: Int) {
            // The table watch follows the front's own power, not only SCREEN_OFF/ON: with the
            // back lit the phone stays interactive as the front times out, and that broadcast
            // need not come. Its sync is cheap and does nothing when nothing changed.
            if (displayId == android.view.Display.DEFAULT_DISPLAY) { table?.sync(); stayOn?.sync(); runCatching { dev.kb896.aiusage.place.PlaceWatch.front(this@UsagePollService) }; return }
            if (displayId != Panel.DISPLAY_ID) return
            stayOn?.sync()
            // Pocket mode hears every change, and keeps only the power (PocketMode.lit).
            pocket?.sync()
            val change = panelPower.see(panelOn())
            if (change == PanelPower.Change.WOKE || change == PanelPower.Change.WENT_DARK) table?.sync()
            if (change == PanelPower.Change.WENT_DARK) rearWentOffAt = android.os.SystemClock.elapsedRealtime()
            if (change != PanelPower.Change.WOKE) return
            // The back lit while the double tap's latch is set: every automatic wake is declined
            // then (ShizukuBridge.wake), so this is the user — the double tap to wake, the power
            // button. The latch is his to end (ManualOff); the panel clears it on its edge too.
            Graph.of(this@UsagePollService).bridge.clearManualOff("the back lit while switched off by hand")
            // And whenever our own rule had it off: lit by his hand (StayOn.rearWoke), which looks again.
            stayOn?.rearWoke()
            dev.kb896.aiusage.place.PlaceWatch.look(this@UsagePollService, "rear woke")
            // THE BACKSTOP for the dozing flag. Everything else that clears it depends on
            // the panel still being alive to say so, and the one case the supervisors
            // exist for is the panel not being alive. A lit display is proof the sleep is
            // over whoever ended it, so the flag is dropped here before the supervisor
            // runs — otherwise a panel that died while dozing would be left unrecovered,
            // and the user would double-tap the rear screen and find nothing there.
            Graph.of(this@UsagePollService).bridge.setDozing(false)
            // Companion mode: nothing of ours to recover on a rear Xiaomi just lit.
            if (!config().companion) scope.launch { safeSupervise() }
        }
    }

    private var portalApps: PortalAppsLoader? = null

    /** Pocket mode's sensor and calls — see PocketWatch for why they live in this process. */
    private var pocket: PocketWatch? = null
    /** Table mode's face-down watch while the back is dark — see TableWatch, same reason. */
    private var table: TableWatch? = null
    /** Camera mode's watch for a camera in use — see CameraWatch, same reason. */
    private var camera: CameraWatch? = null
    /** Whether the back is to stay lit with the scene on it, from how the phone is held (StayOn). */
    private var stayOn: StayOn? = null
    private var stopConfigWatch: (() -> Unit)? = null

    /** Installs, updates, removals and a language change all alter the pinned apps' icons or names. */
    private val portalAppsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { portalApps?.refresh() }
    }

    /**
     * Replace mode is gone (one UI, and Xiaomi's rear screen always the base). A config that still
     * says Replace is written back as companion, once; the old panel is taken off the back, and Xiaomi's rear
     * app, which Replace mode had moved out of the way, is put back as soon as the shell can be asked.
     */
    private fun leaveReplaceIfStored(app: Graph) {
        val store = ConfigStore(this)
        if (!dev.kb896.aiusage.data.PanelConfig.storedReplace(store.raw())) return
        Log.i(TAG, "replace mode was stored: back to companion, and Xiaomi's rear app restored")
        store.save(store.load())                      // read as companion (PanelConfig.decode), and stored so
        runCatching { app.bridge.hide() }
        Thread({
            // The shell may not be bound yet: asked until it answers, for a minute.
            repeat(30) {
                val restored = runCatching { if (app.bridge.status().ready) app.bridge.setXiaomiRearApp(installed = true) else null }.getOrNull()
                if (restored != null) { Log.i(TAG, "Xiaomi's rear app restored: $restored"); return@Thread }
                try { Thread.sleep(2_000L) } catch (_: InterruptedException) { return@Thread }
            }
            Log.w(TAG, "Xiaomi's rear app not restored: the shell did not answer")
        }, "leave-replace").apply { isDaemon = true; start() }
    }

    override fun onCreate() {
        super.onCreate()
        // The type must be passed here as well as declared in the manifest — they are
        // two separate assertions and the framework checks both. specialUse rather than
        // dataSync: see the manifest for why the six-hour cap on dataSync would take the
        // panel down every afternoon.
        androidx.core.app.ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        // OpenAI's Dots character for ChatGPT's vessel on the back's usage page (rear/dots), handed to the shell;
        // bound only while it is switched on and ChatGPT is signed in (told again at each config write and tick).
        runCatching {
            val bridge = Graph.of(this).bridge
            dev.kb896.aiusage.rear.dots.DotsLink.onBinder { b -> bridge.setDots(b) }
            syncDots()
        }
        // The restart's own lines (RestartLog) and the health check, from the start.
        ShizukuArrival.serviceStarted()
        HealthCheck.start(this)
        val app = Graph.of(this)
        leaveReplaceIfStored(app)
        // Pinned pictures' files that no note has (data/PinImage): swept, off the main thread. The place prompt's waiting
        // picture is kept, and so is one being read right now.
        Thread({
            runCatching {
                dev.kb896.aiusage.data.PinImageFiles.sweep(this, ConfigStore(this).load().pins,
                    setOfNotNull(dev.kb896.aiusage.place.PlaceStore(this).load().draft?.image))
            }
        }, "pin-pictures-sweep").apply { isDaemon = true; start() }
        // The pinned apps' icons and names go to the shell: now, and on every config write.
        val loader = PortalAppsLoader(this) { app.bridge.setPortalApps(it) }.also { portalApps = it }
        stopConfigWatch = ConfigStore(this).listen {
            val c = config()
            loader.refresh()
            pocket?.setSwitch(c.pocketMode)
            table?.setConfig(c.tableMode, c.tableChargingOnly, c.offOnBack, liftWakes(c),
                panelWantsTable = !c.companion && (c.showTableLights || c.video.playOnTable))
            camera?.setSwitch(c.cameraLight)
            stayOn?.sync()
            syncDots()
        }
        ContextCompat.registerReceiver(this, portalAppsReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, portalAppsReceiver,
            IntentFilter(Intent.ACTION_LOCALE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        loader.refresh()
        app.repository.onFetchCompleted = {
            val current = app.repository.state.value
            // Only cache a refresh that actually produced numbers. onFetchCompleted
            // fires whatever the outcome, so caching unconditionally let one throttled
            // cycle overwrite good values with an empty snapshot — destroying exactly
            // the data this cache exists to preserve.
            //
            // The timestamp is still recorded either way: a failed attempt is still an
            // attempt, and must not license an immediate retry on the next restart.
            //
            // An account with no credential is a definitive answer, not a throttled miss:
            // a sign-out must reach the cache, or a restart restores the old numbers and
            // the readout comes back for an account that is gone. So the cache is merged
            // per account — fresh where the refresh said something (numbers, or no
            // credential), the cached status where it was only throttled.
            val cached = cachedState()?.let { runCatching { StatePayload.decode(it).state }.getOrNull() }
            val merged = current.statuses.map { s ->
                if (s.snapshot != null || !s.connected) s
                else cached?.statuses?.firstOrNull { it.account == s.account && it.snapshot != null } ?: s
            }
            if (merged.any { it.snapshot != null || !it.connected }) {
                recordFetch(StatePayload.encode(DashboardState(current.fetchedAt, merged)))
            } else {
                recordFetchTimeOnly()
            }
        }
        // Render the last known values immediately rather than waiting out the
        // remaining interval with a blank panel.
        cachedState()?.let { app.repository.restore(StatePayload.decode(it).state) }
        displays = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // As the back is NOW: a back lit when this service starts is not a back waking up at
        // its first refresh-rate change (PanelPower).
        panelPower = PanelPower(on = panelOn())
        pocket = PocketWatch(this, app.bridge, onPocketChanged = { table?.sync() }).also { it.setSwitch(config().pocketMode); it.sync() }
        table = TableWatch(this, app.bridge).also { w ->
            val c = config()
            w.setConfig(c.tableMode, c.tableChargingOnly, c.offOnBack, liftWakes(c),
                panelWantsTable = !c.companion && (c.showTableLights || c.video.playOnTable))
            // A panel that outlived the last process may still show its old posture.
            w.restateAfterStart()
        }
        camera = CameraWatch(this, app.bridge).also { it.setSwitch(config().cameraLight); it.start() }
        stayOn = StayOn(this, app.bridge, { config() }, cameraInUse = { camera?.inUse == true || app.bridge.cameraOpen }).also { it.start() }
        displays.registerDisplayListener(displayListener, null)
        // RECEIVER_NOT_EXPORTED is required, not optional: on API 34+ a receiver whose
        // filter contains a NON-system action must declare its export state or
        // registerReceiver throws SecurityException and takes the service down at
        // startup. ACTION_STRANDED is ours, so this filter is no longer system-only.
        // NOT_EXPORTED still receives it — RearActivity is a different process but the
        // same UID.
        runCatching {
            contentResolver.registerContentObserver(
                android.provider.Settings.System.getUriFor("sub_display_screen_brightness"), false, brightnessWatch,
            )
            contentResolver.registerContentObserver(
                android.provider.Settings.Secure.getUriFor("sub_display_screen_brightness_mode"), false, brightnessWatch,
            )
            for (key in listOf("bluetooth_on", "wifi_on", "mode_ringer", "mobile_data", "mobile_data0", "mobile_data1", "mobile_data2", "mobile_data3")) {
                contentResolver.registerContentObserver(android.provider.Settings.Global.getUriFor(key), false, quickWatch)
            }
        }
        // The battery for the scene's percent on the back: sticky, so the first reading comes at once.
        app.bridge.chargeDetailFor = { battery -> battery.message(preciseReading(battery), chargeKind) }
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // The system's own (Xiaomi's battery service sends them to whoever listens): exported, as a system broadcast needs.
        runCatching {
            ContextCompat.registerReceiver(this, chargeNews, IntentFilter().apply {
                addAction(ChargePrecise.ACTION_QUICK_CHARGE_TYPE); addAction(ChargePrecise.ACTION_SOC_DECIMAL)
            }, ContextCompat.RECEIVER_EXPORTED)
        }
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            // Only for the table watch's "only while charging" (handled and returned from below).
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            // RearActivity shouting that it has landed on the wrong display. It cannot
            // move itself — moveRootTaskToDisplay needs shell privileges — so it tells
            // us and we go through Shizuku. Handled on the same path as the screen
            // broadcasts: ensureOnRear() first, which is the whole remedy when it works.
            addAction(RearActivity.ACTION_STRANDED)
            // The panel's camera button, pressed on the back of the phone. Same reason
            // it comes here: the Shizuku binding lives in this process and the panel's
            // does not.
            addAction(RearActivity.ACTION_OPEN_CAMERA)
            // Camera mode's double tap on the back: switched off until the camera closes.
            addAction(RearActivity.ACTION_CAMERA_SLEEP)
            // And its transport buttons, for the same reason again.
            addAction(RearActivity.ACTION_MEDIA_COMMAND)
            // The panel telling us it has let its display go, or taken it back.
            addAction(RearActivity.ACTION_DOZING)
            // And whether its lock slider or always-on is up, for the shell's portal sheet.
            addAction(RearActivity.ACTION_GUARDED)
            addAction(RearActivity.ACTION_ASK_CODE)
            addAction(RearActivity.ACTION_CAPTURE)
            // The control centre's brightness: read it, set it — through Shizuku, here.
            addAction(RearActivity.ACTION_GET_REAR_BRIGHTNESS)
            addAction(RearActivity.ACTION_SET_REAR_BRIGHTNESS)
            addAction(RearActivity.ACTION_SET_REAR_AUTO_BRIGHTNESS)
            // And its switches: silent, Bluetooth, mobile data, Wi-Fi.
            addAction(RearActivity.ACTION_GET_QUICK)
            addAction(RearActivity.ACTION_SET_QUICK)
            // The panel's list of notifications, for the shell's over any page.
            addAction(RearActivity.ACTION_NOTICES_LIST)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        // This process may be new while the panel is not: the bridge starts unguarded, and the
        // panel says its guard only when it changes. Asked for again now that ACTION_GUARDED
        // is heard, so a pocket-locked panel stays guarded across a restart of this process.
        // Its hold too: the panel answers with ACTION_DOZING, which is how the bridge — not
        // knowing whether the panel sleeps until it is told — comes to know (and only then
        // tells the shell; see ShizukuBridge.setDozing).
        sendBroadcast(Intent(RearActivity.ACTION_MIRROR_SYNC).setPackage(packageName))

        // Held by this service rather than by NotificationRelay, even though the relay is
        // what authorises it. A listener service is started and stopped by the system on
        // its own schedule — it is rebound after a crash, and it is not running before
        // the user has granted access — whereas this service is the app's one long-lived
        // component and is already the thing that talks to the panel.
        media = MediaWatcher(this).also { it.start() }
        // Earbuds connecting: the card on the front and the back's moment, once per connection (service/EarbudsWatch).
        earbuds = EarbudsWatch(this).also { it.start() }
        // Place the panel NOW, before either loop's first delay.
        //
        // This is the whole recovery story for an app that has been killed: the panel
        // dies with the app (RearActivity is ours, in our own :rear process, and
        // force-stop takes the Shizuku user service with it since Shizuku names that
        // process after our package). Nothing outside the app can bring it back, so
        // starting the app has to. Without this the poll loop waits out
        // initialDelayMs() — up to a full 5 minutes if a fetch completed recently —
        // and the watchdog waits its own 20s, so opening the app to fix a dead panel
        // left it dead for another 20s at best.
        //
        // Uses cached state and makes no network call, so it costs nothing against
        // the rate limit and cannot re-introduce the request bursts.
        //
        // In a new process the bridge is not bound yet and this first pass can only start
        // the bind (the panel's state is unknown, not dead); the real look is taken the
        // moment the service connects.
        // Companion mode places nothing: the shell is bound, given the config, and that is all
        // (companionSync) — Xiaomi's rear screen stays as it is.
        app.bridge.onConnected = { scope.launch { if (config().companion) companionSync() else safeSupervise() } }
        // The shell's process and its guard, asked every few seconds whether they are still there: a shell
        // that died is bound again within that, not at the next poll. A binder ping each, nothing more.
        // Not while there is nothing to see (the front off and the back off, PanelAwake as StayOn feeds it in
        // companion mode): parked then, and asked at once when there is.
        scope.launch {
            while (isActive) {
                delay(SHELL_ALIVE_MS)
                if (config().companion) PanelAwake.awaitAwake()
                runCatching { app.bridge.ensureAlive() }
            }
        }
        // A finger on a back that is not held lit: the stay-on rule looks again (it has stopped looking by then).
        app.bridge.onRearTouch = { stayOn?.sync() }
        app.bridge.onRestingFaceDownChanged = {
            stayOn?.sync()
            table?.restingPostureChanged()
        }
        app.bridge.onRearWoke = { dev.kb896.aiusage.place.PlaceWatch.look(this, "rear woke") }
        // The fences for notes with a place, set again in every new process; Location switched on and off heard (place/PlaceWatch).
        runCatching { dev.kb896.aiusage.place.PlaceWatch.start(this) }
        // The playing card's buttons on the back: the same commands the panel's media page gave.
        app.bridge.onMediaCommand = { action -> runCatching { media?.command(action) } }
        // The usage page was opened on the back: the figures fetched again (the repository's own floor
        // keeps this from being more than one fetch in a minute and a half) and pushed.
        app.bridge.onUsageShown = {
            scope.launch {
                runCatching { app.repository.refresh() }
                app.bridge.update(StatePayload.encode(app.repository.state.value, config(), WeatherPoll.latest))
            }
        }
        scope.launch { if (config().companion) companionSync() else safeSupervise() }

        scope.launch {
            val initial = initialDelayMs()
            if (initial > 0) {
                Log.i(TAG, "recent fetch ${(System.currentTimeMillis() - lastFetchAt()) / 1000}s ago — first poll in ${initial / 1000}s")
                delay(initial)
            }
            while (isActive) {
                // NOTHING IS ASKED FOR WHILE NOBODY CAN SEE IT, however long that lasts.
                //
                // Parked here, not polling a flag: suspended on the panel's own state,
                // this loop is not scheduled at all until the panel comes back, so a
                // phone in a pocket overnight costs exactly zero requests and zero
                // wake-ups. Two hours dark is the same as two minutes dark.
                //
                // AND THE RATE IS UNCHANGED, which is the part worth being sure of. The
                // delay below always runs to completion before the loop returns here, so
                // two calls can never be closer together than FAST_MS no matter how often
                // the panel is woken. Glancing at it ten times a minute asks for nothing
                // extra; glancing at it after two hours asks immediately, because the
                // delay is long finished and this is where the loop has been waiting.
                PanelAwake.awaitAwake()
                safeTick()
                delay(Cadence.FAST_MS)
            }
        }

        val weather = WeatherPoll(
            fetch = { t -> app.weatherApi.current(t.latitude, t.longitude, t.label, System.currentTimeMillis()) },
            fix = deviceFix(this),
            configNow = ::config,
            onReading = {
                // Push at once so the line lands within a second of the fetch rather than
                // at the next usage tick. Calibrate mode is never touched from here.
                if (!app.bridge.calibrating) {
                    app.bridge.update(StatePayload.encode(app.repository.state.value, config(), WeatherPoll.latest))
                }
            },
        )
        // Dispatchers.IO, not the scope's own Default: fetch is a blocking OkHttp
        // execute() that can sit for up to ten seconds, and Default has as many threads
        // as there are cores — the same reason UsageRepository.refresh runs under IO.
        scope.launch(Dispatchers.IO) { weather.run() }

        // Supervising the display is a separate job from fetching data, on its own
        // clock. Coupling them meant a panel dismissed by MIUI's double-tap gesture
        // stayed gone until the next poll — up to five minutes of blank back screen
        // for something that should return in seconds. This loop makes no network
        // calls: it re-places using whatever the repository already holds.
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                // No watchdog in companion mode: there is no panel to keep placed. And the relay is looked at
                // only while there is something to see: parked with the front off and the back off.
                if (!config().companion) safeSupervise() else PanelAwake.awaitAwake()
                RelayWatch.check(this@UsagePollService)
            }
        }
    }

    /**
     * Whether the main screen is off or locked.
     *
     * No longer a reason to refuse a relaunch, and the history matters. RearActivity is
     * necessarily created on the MAIN display before being moved to the rear one, and it
     * used to carry FLAG_TURN_SCREEN_ON — so every respawn while the phone was asleep
     * woke it. Measured: mWakefulness went Dozing -> Awake on a locked relaunch. That is
     * what this guard existed to prevent, and the cost was that a panel which died while
     * the screen was off stayed dead until someone unlocked and tapped the notification.
     *
     * The flag is gone; the rear display is woken explicitly by the privileged service
     * instead, which can target one display where a window flag could not. Re-measured
     * with it removed: Dozing -> Dozing, and the panel still lands on display 1.
     *
     * Kept as a reading because it is still worth LOGGING which case a recovery happened
     * in, and because the keyguard remains the one situation where a stray placement is
     * visible rather than merely wasteful.
     */
    private fun screenOffOrLocked(): Boolean {
        val power = getSystemService(PowerManager::class.java)
        if (power != null && !power.isInteractive) return true
        return getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }

    private fun panelOn(): Boolean =
        displays.getDisplay(Panel.DISPLAY_ID)?.state == Display.STATE_ON

    /**
     * tick(), guarded so one transient failure (a flaky network refresh, a Shizuku
     * hiccup) can't silently kill the polling loop forever. Without this, an
     * uncaught exception either escapes the `while (isActive)` loop in onCreate() —
     * ending polling for the rest of the process's life — or, from the
     * DisplayListener's fire-and-forget launch, an uncaught exception on
     * Dispatchers.Default with no installed handler. Cancellation must still
     * propagate normally, so it is rethrown rather than logged.
     */
    /**
     * Fires on lock and unlock. Both are moments the system re-evaluates which
     * activity shows over the keyguard, and both are when the panel was observed
     * jumping to the front screen — so the corrective check runs immediately rather
     * than waiting out the watchdog interval, which would leave the lock screen
     * covered for up to 20 seconds. Makes no network call.
     */
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val battery = ChargeBattery.read(this@UsagePollService)
            val percent = battery.level
            val status = battery.status
            val state = battery.state
            val now = android.os.SystemClock.uptimeMillis()
            Graph.of(this@UsagePollService).bridge.setBattery(percent,
                state == ChargeState.Is.FILLING || state == ChargeState.Is.FULL)
            sendChargeDetail(battery, preciseReading(battery, now))
            val plugged = battery.plugged ?: return
            val wasPlugged = chargeWasPlugged
            chargeWasPlugged = plugged
            if (!config().companion || !config().chargeAnim) return
            if (!plugged && wasPlugged == true) {
                chargeOut()
            } else if (plugged && wasPlugged != true && (wasPlugged == false || state != ChargeState.Is.FULL)) {
                // A sticky plug edge supplies the status. An existing charge found at startup can stay
                // on the back, but only a new plug-in can wake it or start the short moment at full.
                chargeIn(battery, mayWake = wasPlugged == false)
            } else when (chargePage.next(state, percent, now)) {
                ChargeState.Page.Does.REST -> { Log.i("AiUsageCharge", "charge: full ($percent%, status $status) — the page leaves the back"); chargeRest() }
                ChargeState.Page.Does.SHOW -> chargeIn(battery)
                ChargeState.Page.Does.NOTHING -> Unit
            }
        }
    }

    // -- the charging page's exact level and charger (ChargePrecise) ------------------------------

    private val chargePrecise = ChargePrecise()
    /** Whether the charging page has its place on the back: not at full (ChargeState). */
    private val chargePage = ChargeState.Page()
    private var chargeWasPlugged: Boolean? = null
    @Volatile private var chargeKind = ""

    private fun preciseReading(battery: ChargeBattery.Snapshot, now: Long = android.os.SystemClock.uptimeMillis()): ChargePrecise.Detail {
        if (battery.plugged != true) {
            chargePrecise.reset()
            return battery.detail()
        }
        chargePrecise.filling(battery.state == ChargeState.Is.FILLING, now)
        return chargePrecise.level(battery.level, now)
    }

    private fun sendChargeDetail(battery: ChargeBattery.Snapshot, d: ChargePrecise.Detail) {
        Graph.of(this).bridge.chargeDetail(battery.message(d, chargeKind))
        if (battery.plugged != true) return
        // Said at each whole percent and at most every half minute between: what the page's hundredths are made of.
        val now = android.os.SystemClock.uptimeMillis()
        if (d.level >= 0 && (d.level != chargeSaidLevel || d.holding != chargeSaidHolding || now - chargeSaidAt >= 30_000L)) {
            chargeSaidLevel = d.level; chargeSaidAt = now; chargeSaidHolding = d.holding
            Log.i("AiUsageCharge", "charge: ${d.level}%, " + (if (d.holding) "HOLDING (on the charger, not filling): the figure stands and the fluid rests" else if (d.fraction < 0f) "nothing known past it yet (no hundredths shown)"
                else "hundredths .%02d and %.2f a second (%s)".format((d.fraction * 100).toInt().coerceIn(0, 99), d.ratePerSec * 100f, if (d.xiaomi) "Xiaomi's own figure" else "from the moment the percent stepped")) +
                if (chargeKind.isEmpty()) "" else ", $chargeKind")
        }
    }
    private var chargeSaidHolding = false
    private var chargeSaidLevel = -1
    private var chargeSaidAt = 0L

    /** Xiaomi provides a charger label and hundredths. Neither can supply the whole percent. */
    private val chargeNews = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val battery = ChargeBattery.read(this@UsagePollService)
            val now = android.os.SystemClock.uptimeMillis()
            val detail = preciseReading(battery, now)
            when (intent?.action) {
                ChargePrecise.ACTION_QUICK_CHARGE_TYPE -> {
                    chargeKind = ChargePrecise.kind(intent.getIntExtra(ChargePrecise.EXTRA_QUICK_CHARGE_TYPE, 0), intent.getIntExtra(ChargePrecise.EXTRA_POWER_MAX, 0))
                    sendChargeDetail(battery, detail)
                }
                ChargePrecise.ACTION_SOC_DECIMAL -> {
                    if (battery.state != ChargeState.Is.FILLING) return
                    sendChargeDetail(battery, chargePrecise.xiaomi(
                        intent.getIntExtra(ChargePrecise.EXTRA_SOC_DECIMAL, -1),
                        intent.getIntExtra(ChargePrecise.EXTRA_SOC_DECIMAL_RATE, 0), now,
                    ))
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // The front going on or off, the lock opening: the stay-on rule looks again.
            when (intent?.action) { Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT -> stayOn?.sync() }
            // A place note waiting: one look of ours as the front comes on (place/PlaceWatch keeps its limits).
            if (intent?.action == Intent.ACTION_SCREEN_ON || intent?.action == Intent.ACTION_SCREEN_OFF) {
                runCatching { dev.kb896.aiusage.place.PlaceWatch.front(this@UsagePollService) }
            }
            // The camera is not a placement problem, so it does not go through the
            // supervisor: that path exists to get the panel back where it belongs, and
            // running it here would have a camera tap second-guessing the panel's
            // position at the exact moment something else is about to cover it.
            if (intent?.action == RearActivity.ACTION_OPEN_CAMERA) {
                Graph.of(this@UsagePollService).bridge.openCamera()
                return
            }
            if (intent?.action == RearActivity.ACTION_CAMERA_SLEEP) {
                camera?.darkForSession()
                return
            }
            // THE CHARGER GOING IN LIGHTS THE PANEL.
            //
            // The cell fills over five seconds and is worth seeing, and the rear display
            // dozes on its own to save battery — so without this, plugging the phone in
            // while it slept animated the whole thing onto a dark screen. The panel draws
            // it either way; this is only about there being a screen lit to draw it on.
            //
            // Waking lives HERE rather than in the panel, because the panel cannot: the
            // keyevent has to be aimed at one display and that needs the privileged
            // service, which this process holds the binding for.
            // THE PANEL IS ASLEEP ON PURPOSE — or awake again. Neither supervisor can
            // work this out for itself; see IRearService.setDozing.
            if (intent?.action == RearActivity.ACTION_GET_REAR_BRIGHTNESS) {
                brightnessWorker.execute {
                    sendRearBrightness()
                }
                return
            }
            if (intent?.action == RearActivity.ACTION_NOTICES_LIST) {
                val keys = intent.getStringArrayExtra(RearActivity.EXTRA_NOTIFICATION_KEYS).orEmpty()
                val pkgs = intent.getStringArrayExtra(RearActivity.EXTRA_NOTICES_PKGS).orEmpty()
                val apps = intent.getStringArrayExtra(RearActivity.EXTRA_NOTICES_APPS).orEmpty()
                val titles = intent.getStringArrayExtra(RearActivity.EXTRA_NOTICES_TITLES).orEmpty()
                val texts = intent.getStringArrayExtra(RearActivity.EXTRA_NOTICES_TEXTS).orEmpty()
                BackNotices.replace(this@UsagePollService, keys.indices.map { i ->
                    dev.kb896.aiusage.rear.NoticeShade.Item(
                        keys[i], pkgs.getOrElse(i) { "" }, apps.getOrElse(i) { "" }, titles.getOrElse(i) { "" }, texts.getOrElse(i) { "" },
                    )
                })
                return
            }
            if (intent?.action == RearActivity.ACTION_GET_QUICK) {
                brightnessWorker.execute { sendQuickStates() }
                return
            }
            if (intent?.action == RearActivity.ACTION_SET_QUICK) {
                val which = intent.getStringExtra(RearActivity.EXTRA_QUICK).orEmpty()
                val on = intent.getBooleanExtra(RearActivity.EXTRA_QUICK_ON, false)
                brightnessWorker.execute {
                    if (which == "silent") {
                        // The ringer is the app's to set: notification access brings Do Not
                        // Disturb access with it, which is what silencing needs.
                        val audio = getSystemService(android.media.AudioManager::class.java)
                        runCatching {
                            audio.ringerMode = if (on) android.media.AudioManager.RINGER_MODE_SILENT
                                else android.media.AudioManager.RINGER_MODE_NORMAL
                        }.onFailure { android.util.Log.w("AiUsagePoll", "ringer: ${it.message}") }
                    } else {
                        Graph.of(this@UsagePollService).bridge.setQuick(which, on)
                    }
                    // The watchers report the change; this covers a switch with none to watch.
                    sendQuickStates()
                }
                return
            }
            if (intent?.action == RearActivity.ACTION_SET_REAR_AUTO_BRIGHTNESS) {
                val on = intent.getBooleanExtra(RearActivity.EXTRA_AUTO_BRIGHTNESS, false)
                brightnessWorker.execute {
                    Graph.of(this@UsagePollService).bridge.setRearAutoBrightness(on)
                    // The level automatic picks reaches the panel through brightnessWatch,
                    // the moment HyperOS writes it.
                    sendRearBrightness()
                }
                return
            }
            if (intent?.action == RearActivity.ACTION_SET_REAR_BRIGHTNESS) {
                // A drag sends a stream of these; only the latest is worth writing, so
                // the worker writes whatever is newest when it gets to it.
                latestBrightness.set(intent.getIntExtra(RearActivity.EXTRA_BRIGHTNESS, -1))
                brightnessWorker.execute {
                    val v = latestBrightness.getAndSet(-1)
                    if (v in 1..255) Graph.of(this@UsagePollService).bridge.setRearBrightness(v)
                }
                return
            }
            if (intent?.action == RearActivity.ACTION_CAPTURE) {
                Graph.of(this@UsagePollService).bridge.capture(intent.getBooleanExtra(RearActivity.EXTRA_RECORD, false))
                return
            }
            if (intent?.action == RearActivity.ACTION_ASK_CODE) {
                Graph.of(this@UsagePollService).bridge.askBackCode()
                return
            }
            if (intent?.action == RearActivity.ACTION_GUARDED) {
                Graph.of(this@UsagePollService).bridge.setPanelGuarded(
                    intent.getBooleanExtra(RearActivity.EXTRA_GUARDED, false),
                    intent.getBooleanExtra(RearActivity.EXTRA_CONTROL_CENTER, false),
                )
                return
            }
            if (intent?.action == RearActivity.ACTION_DOZING) {
                val dozing = intent.getBooleanExtra(RearActivity.EXTRA_DOZING, false)
                Graph.of(this@UsagePollService).bridge.setDozing(dozing)
                // And anything in this process that only matters when somebody can see
                // the panel. See PanelAwake — it releases whatever was put off.
                PanelAwake.setDozing(dozing)
                // A new panel says its hold first thing: it is told whether the phone lies on
                // its back, which it hears otherwise only as an edge (TableWatch.restate).
                table?.restate()
                return
            }
            // LOCKING THE FRONT PUTS THE BACK INTO ALWAYS-ON, not out. HyperOS sends the rear
            // display to sleep with the main one on the power button ("Secondary Display
            // gotosleep by power button") and, its own rear always-on being off, straight
            // to dark ("aod is close, and just go to sleep directly"). With the panel's
            // always-on switched on, the back is lit again a moment later and the panel,
            // woken with always-on on, goes into it — the slider up, the page dimmed.
            // Only when the back went dark WITH the front, and only over the panel itself,
            // awake: a guest on top at the lock is not lit at full brightness on a locked
            // phone, and a panel already letting the back go (the double tap, pocket mode)
            // stays asleep — both read NOW, at the lock, before the sleep can change them
            // (LockRelight). And never over the other build's panel (ShizukuBridge.wake).
            // Not returned from — the lock's own supervision below still runs.
            // "Stay on in the hand" the same: the back is on unless a posture rule says off, and the
            // bridge declines this wake where one does (the double tap's latch, on its back, a pocket).
            // Not in companion mode: relit after the lock is Xiaomi's rear relit against its own
            // AOD off (subscreen_aod_auto_off_enable). Xiaomi owns the back's sleep there.
            if (intent?.action == Intent.ACTION_SCREEN_OFF && !config().companion && (config().alwaysOn || config().stayOnInHand)) {
                val lockedAt = android.os.SystemClock.elapsedRealtime()
                val bridge = Graph.of(this@UsagePollService).bridge
                val dozingAtLock = bridge.dozingOrNull()
                val guestAtLock = bridge.guestOnTop()
                scope.launch {
                    delay(RELIGHT_AFTER_LOCK_MS)
                    val together = kotlin.math.abs(rearWentOffAt - lockedAt) <= WENT_DARK_TOGETHER_MS
                    if (LockRelight.relight(together, dozingAtLock, guestAtLock)) {
                        Log.i(TAG, "the front locked and took the back with it — lit again")
                        // Without always-on's own slider, lit on a locked phone it is guarded as any
                        // wake from the dark is: the slider up (RearActivity.lockPanel).
                        if (bridge.wake("the back after the lock") && !config().alwaysOn) {
                            bridge.tellPanel(RearActivity.ACTION_LOCK)
                        }
                    } else if (together) {
                        Log.i(TAG, "the front locked and took the back with it — left dark " +
                            "(asleep on purpose: $dozingAtLock, a guest on top: $guestAtLock)")
                    }
                }
            }
            // The front on or off, a charger in or out: what the table watch watches for, and refuses.
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT,
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> table?.sync()
            }
            // Companion mode: the charging animation over Xiaomi's rear (ShellCharge).
            // The sticky battery broadcast confirms a plug-in and supplies its status, including full.
            if (config().companion && intent?.action == Intent.ACTION_POWER_CONNECTED) return
            if (config().companion && intent?.action == Intent.ACTION_POWER_DISCONNECTED) {
                chargeOut()
                return
            }
            if (intent?.action == Intent.ACTION_POWER_DISCONNECTED) return
            if (intent?.action == Intent.ACTION_POWER_CONNECTED) {
                // Same reasoning as the notification path: only a panel that was dark
                // gets locked. Plugging in a phone you are holding and watching the cell
                // fill should not cost you a swipe.
                if (Graph.of(this@UsagePollService).bridge.wake("the charger")) {
                    sendBroadcast(
                        Intent(RearActivity.ACTION_LOCK).setPackage(packageName),
                    )
                }
                return
            }
            // A transport button on the back of the phone. Also not a placement problem,
            // and also not worth a round trip through the supervisor.
            if (intent?.action == RearActivity.ACTION_MEDIA_COMMAND) {
                media?.command(
                    intent.getStringExtra(RearActivity.EXTRA_MEDIA_COMMAND).orEmpty(),
                )
                return
            }
            // Lock, unlock, a stranded panel: placement work, which companion mode has none of.
            if (config().companion) return
            safeSupervise()
        }
    }

    /**
     * Post-placement check. Corrects, never tears down.
     *
     * This used to call bridge.hide() when the rear panel was not lit 8s after a
     * placement, on the theory that an unlit panel meant a failed move. That theory is
     * obsolete — SurfaceFlinger now tells us directly which display is compositing the
     * activity — and the action was actively destructive: hide() FINISHES the activity,
     * so every placement armed a timer that could kill the panel, and the watchdog then
     * respawned it ~20s later. Measured on device while the app sat in the background:
     * the task was destroyed and recreated on a 28-32s cycle, which is exactly this 8s
     * teardown plus the 20s watchdog. The panel was fighting itself.
     *
     * ensureOnRear() moves a genuinely stranded task back and does nothing when the
     * answer is uncertain, so an unlit-but-fine panel now costs one dumpsys instead of
     * the panel's life.
     */
    private suspend fun verifyPlacement(app: Graph) {
        delay(VERIFY_DELAY_MS)
        if (app.bridge.ensureOnRear()) Log.w(TAG, "post-placement: was on the main display, moved back")
    }

    /** Watchdog-only pass: no fetch, just make sure the panel is still up. Replace mode only. */
    private fun safeSupervise() {
        try {
            val app = Graph.of(this)
            // The backstop: no placement, moving back or stranded handling in companion mode.
            if (config().companion) return
            if (app.bridge.ensureOnRear()) Log.w(TAG, "panel was on the main display — moved back")
            ensurePlaced(app, StatePayload.encode(app.repository.state.value, config(), WeatherPoll.latest))
        } catch (e: Exception) {
            Log.e(TAG, "supervise failed", e)
        }
    }

    /**
     * Companion mode's whole supervision, at the start and on every (re)connect: the shell bound
     * and given the config (IRearService.update applies it, and places nothing), and Xiaomi's
     * rear app let out of a suspension once if Replace mode left it suspended.
     */
    private fun companionSync() {
        try {
            val app = Graph.of(this)
            app.bridge.update(StatePayload.encode(app.repository.state.value, config(), WeatherPoll.latest))
            app.bridge.connect()
            restoreXiaomiOnce(app)
        } catch (e: Exception) {
            Log.e(TAG, "companion sync failed", e)
        }
    }

    /** Whether Xiaomi's rear app has been looked at (and let out, if it had to be) in this process. */
    @Volatile private var xiaomiLooked = false

    /**
     * Companion mode keeps Xiaomi's rear screen as the base, so one suspended by Replace mode
     * (RearUserService.removeXiaomiRearApp) is unsuspended, once, with one line to say so. Blocks
     * on the shell (awaitService): off the main thread. Not bound yet: tried again on connect.
     */
    private fun restoreXiaomiOnce(app: Graph) {
        if (xiaomiLooked) return
        when (dev.kb896.aiusage.ui.Setup.xiaomiRearAppInstalled(this)) {
            // There and free to run, or no such app on this phone: nothing to do.
            true, null -> { xiaomiLooked = true; return }
            false -> Unit
        }
        val done = app.bridge.setXiaomiRearApp(installed = true) ?: return
        xiaomiLooked = true
        val back = dev.kb896.aiusage.ui.Setup.xiaomiRearAppInstalled(this) == true
        Log.i(TAG, "companion mode: Xiaomi's rear app was suspended — restore said $done, running now: $back")
        if (back) android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, "Xiaomi's rear screen is back", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // -- the charging animation, companion mode ---------------------------------------------

    private var chargeStart: Job? = null

    /**
     * The charger went in: the back woken if it is dark and "Also when the back is dark" is on
     * (the bridge's declines still hold: switched off by hand, on its back, in a pocket), then the
     * animation requested, and the gravity sensor streamed to its liquid for five seconds. The shell
     * asks for the level when it shows the page.
     */
    private fun chargeIn(battery: ChargeBattery.Snapshot, mayWake: Boolean = true) {
        val c = config()
        if (!c.chargeAnim) return
        val plug = chargePage.pluggedIn(battery.state)
        if (plug == ChargeState.Page.Plug.NOTHING) return
        val bridge = Graph.of(this).bridge
        chargeStart?.cancel()
        chargeStart = scope.launch(Dispatchers.Main.immediate) {
            val bound = withContext(Dispatchers.IO) { bridge.awaitService(CHARGE_BIND_MS) != null }
            if (!bound) return@launch
            val beforeWake = ChargeBattery.read(this@UsagePollService)
            if (beforeWake.plugged != true) return@launch
            if (mayWake && c.chargeWhenDark && beforeWake.state != ChargeState.Is.FULL) {
                withContext(Dispatchers.IO) { bridge.wake("the charger") }
            }
            // The bind or wake may have taken a moment. Pass only current charger state, never a figure.
            val current = ChargeBattery.read(this@UsagePollService)
            if (current.plugged != true) return@launch
            val full = current.state == ChargeState.Is.FULL
            if (full && !mayWake) return@launch
            if (bridge.showCharge(full, current.fast)) {
                tiltFor(ChargeStyle.FULL.totalMs.toLong())
            }
        }
    }

    /** Full with the charger in: the page leaves the back (it is not drained: nothing came out). */
    private fun chargeRest() {
        stopTilt()
        Graph.of(this).bridge.chargeFull()
    }

    /** The charger came out: the animation, if it is up, drains away. */
    private fun chargeOut() {
        chargeWasPlugged = false
        chargeStart?.cancel()
        chargePage.out()
        chargePrecise.reset()
        chargeKind = ""
        stopTilt()
        Graph.of(this).bridge.endCharge()
    }

    private var tiltListener: android.hardware.SensorEventListener? = null
    private var tiltLastAt = 0L
    private val tiltStop = Runnable { stopTilt() }
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** The gravity sensor to the shell's liquid (IRearService.chargeTilt), about 30 a second, for [ms]. */
    private fun tiltFor(ms: Long) = mainHandler.post {
        stopTilt()
        val sensors = getSystemService(android.hardware.SensorManager::class.java) ?: return@post
        val gravity = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY) ?: return@post
        val bridge = Graph.of(this).bridge
        val l = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - tiltLastAt < TILT_EVERY_MS) return
                tiltLastAt = now
                // The sensor's own x and y: the panel's axes are the sensor's with both in-plane
                // axes flipped twice over (RearDialView.gravityPanelX), which cancels.
                bridge.chargeTilt(e.values[0], e.values[1])
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
        }
        if (sensors.registerListener(l, gravity, android.hardware.SensorManager.SENSOR_DELAY_GAME)) {
            tiltListener = l
            mainHandler.postDelayed(tiltStop, ms)
        }
    }

    private fun stopTilt() {
        mainHandler.removeCallbacks(tiltStop)
        val l = tiltListener ?: return
        tiltListener = null
        runCatching { getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(l) }
    }

    /** A lift off its back wakes the back: in Replace mode always, as before; in companion mode only if asked. */
    private fun liftWakes(c: dev.kb896.aiusage.data.PanelConfig): Boolean = !c.companion || c.wakeOnLift

    private suspend fun safeTick() {
        try {
            tick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "tick failed", e)
        }
    }

    /**
     * Wall-clock time of the last completed fetch, persisted so it survives the process
     * dying. elapsedRealtime would be wrong here: it resets on reboot, and the point is
     * to remember across restarts.
     */
    private fun lastFetchAt(): Long =
        getSharedPreferences("poll", MODE_PRIVATE).getLong("lastFetchAt", 0L)

    /**
     * Persists the fetch time AND the values themselves. Carrying only the timestamp
     * meant a restart knew not to re-fetch but had nothing to show, so the panel sat
     * empty for the remainder of the interval — blank for data we already had.
     *
     * The payload is the same one broadcast to the panel: utilisations and timestamps
     * only, never a credential. See StatePayload.
     */
    private fun recordFetch(state: String) =
        getSharedPreferences("poll", MODE_PRIVATE).edit()
            .putLong("lastFetchAt", System.currentTimeMillis())
            .putString("lastState", state)
            .apply()

    /** Records the attempt without touching the cached values. See onFetchCompleted. */
    private fun recordFetchTimeOnly() =
        getSharedPreferences("poll", MODE_PRIVATE).edit()
            .putLong("lastFetchAt", System.currentTimeMillis()).apply()

    private fun cachedState(): String? =
        getSharedPreferences("poll", MODE_PRIVATE).getString("lastState", null)

    /**
     * How long to wait before the first fetch after the service starts.
     *
     * Every service start used to fetch immediately. That is fine once, but the service
     * restarts far more often than expected — every app update during development, and
     * in normal use whenever MIUI kills and revives it. Each restart then fired a
     * request, producing bursts against the very endpoint that reports rate limits, and
     * that burst pattern is what got the work account 429'd. Rate is not the problem;
     * bunching is.
     *
     * So a restart resumes the existing cadence rather than restarting it: if the last
     * fetch was recent, wait out the remainder instead of fetching again.
     */
    private fun initialDelayMs(): Long {
        val since = System.currentTimeMillis() - lastFetchAt()
        if (since < 0) return 0L // clock moved backwards; do not stall forever
        return (Cadence.FAST_MS - since).coerceIn(0L, Cadence.FAST_MS)
    }

    /**
     * Read fresh on every send, never cached in a field. A settings edit must reach the
     * panel on the next push without this service being restarted to notice it.
     */
    private fun config() = ConfigStore(this).load()

    /** The ":dots" process bound or let go by the switch and ChatGPT's sign-in (rear/dots/DotsLink.wanted). */
    private fun syncDots() = runCatching {
        val signedIn = runCatching { Graph.of(this).store.load(dev.kb896.aiusage.data.Account.CHATGPT) != null }.getOrDefault(false)
        dev.kb896.aiusage.rear.dots.DotsLink.sync(this, dev.kb896.aiusage.rear.dots.DotsLink.wanted(config().usageDots, signedIn))
    }

    private suspend fun tick() {
        val app = Graph.of(this)
        app.repository.refresh()
        syncDots()
        // UsageRepository.refresh() keeps each account's last good snapshot when a
        // fetch degrades (see its KDoc), so this is never a blank/empty payload
        // just because one fetch failed — the panel keeps showing the last real
        // numbers with a non-OK Health, not nothing.
        // Alert BEFORE placement work: a limit warning must not be delayed, or lost
        // entirely, because the panel happened to need re-placing on this tick.
        // Evaluates against the freshly-refreshed state and is a no-op when nothing
        // crossed a band.
        limitNotifier.onState(app.repository.state.value)

        // Re-post the ongoing notification so the shade and the island follow the new
        // reading. startForeground is not repeated — the service is already foreground,
        // and notify() on the same id is the documented way to update it.
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification())

        val payload = StatePayload.encode(app.repository.state.value, config(), WeatherPoll.latest)
        // Companion mode: the config to the shell, nothing placed (and no watchdog reading).
        if (config().companion) {
            app.bridge.update(payload)
            return
        }
        val decision = ensurePlaced(app, payload)
        // A fresh respawn already delivered `payload` via the launch intent extra
        // (RearUserService.show()'s `am start --es state ...`), and calibrate mode
        // must never be touched by this service at all — only an already-alive,
        // non-calibrating panel gets the routine push. Unknown (not bound yet) too: the
        // bridge keeps it and hands it over as the service connects.
        if (decision.state == Watchdog.State.ALIVE || decision.state == Watchdog.State.UNKNOWN) {
            app.bridge.update(payload)
        }
    }

    /**
     * Re-places the panel if it has died. Always-on is a user requirement, so a
     * killed activity must not stay dead until the next manual tap.
     *
     * Goes through ShizukuBridge.show() deliberately: RearUserService.show() performs
     * launch -> poll-task-id -> move -> verify -> wake in one shell-uid process.
     * Hand-rolling that sequence over adb once stranded the activity fullscreen on
     * the MAIN display and required a force-stop to recover.
     *
     * The two guards the brief calls out both live in Watchdog.decide(), which is
     * plain Kotlin and unit tested directly (WatchdogTest):
     *  - a state TRANSITION is logged once, not every failing attempt, so a stuck
     *    "Shizuku dead" / "permission revoked" / "no rear display" condition
     *    doesn't spam the log every tick. The retry itself still happens every
     *    tick — show() is called on every DEAD reading — but only at the normal
     *    Cadence.FAST_MS/SLOW_MS pace; there is no faster inner retry loop, which
     *    is what "not a tight loop" actually requires.
     *  - CALIBRATING always wins over DEAD, so a panel that crashed mid-calibration
     *    is left dead rather than "helpfully" respawned with the normal usage
     *    payload, which would silently exit calibrate mode and reset the view
     *    under the user's hands. See ShizukuBridge.isCalibrating()'s KDoc for the
     *    one thing this cannot see: a calibrate session started via the manual adb
     *    broadcast workaround bypasses ShizukuBridge entirely and is invisible
     *    here — that is a pre-existing gap in how calibrate mode is reached today,
     *    not something this task can close without new infrastructure.
     */
    private fun ensurePlaced(app: Graph, payload: String): Watchdog.Decision {
        val decision = Watchdog.decide(
            watchdogState,
            // Null while this process is not bound to the shell — not dead. See Watchdog.decide.
            app.bridge.panelAlive(),
            app.bridge.isCalibrating(),
            // Re-placing calls show(), and show() lights the display — so this watchdog,
            // doing exactly its job, undid every deliberate sleep. See Watchdog.decide.
            app.bridge.isDozing(),
            // Companion mode: nothing to watch (NOTHING); ensurePlaced is not even called then.
            companion = config().companion,
        )
        watchdogState = decision.state
        if (decision.isNewState) {
            Log.i(TAG, when (decision.state) {
                Watchdog.State.DEAD -> "rear panel not present — re-placing"
                Watchdog.State.CALIBRATING -> "calibrate mode active — watchdog standing down"
                Watchdog.State.DOZING -> "panel asleep on purpose — watchdog standing down"
                Watchdog.State.ALIVE -> "rear panel confirmed alive"
                Watchdog.State.UNKNOWN -> "not bound to the shell yet — asking again once it connects"
                Watchdog.State.NOTHING -> "companion mode — no panel to watch"
            })
        }
        if (decision.shouldRespawn) {
            // NOT while the keyguard is up. Respawning means ShizukuBridge.show(), and
            // show() launches on display 0 before moving to display 1 — the rear panel
            // rejects direct launches, so there is no other route. RearActivity holds
            // FLAG_SHOW_WHEN_LOCKED and setTurnScreenOn(true), so that transient launch
            // does not pass unnoticed on a locked phone: it draws over the keyguard and
            // wakes the main screen. That is the "every time I lock the screen it gets
            // sent to the main screen" report, and the cause is this respawn, not the
            // system moving anything.
            //
            // Deferring costs a dark rear panel until the phone is unlocked, which
            // nobody is looking at anyway — the alternative is hijacking the lock
            // screen every time. USER_PRESENT drives screenReceiver, so the panel is
            // back within a moment of unlocking.
            if (screenOffOrLocked()) {
                // Recover anyway. This used to return, because the respawn woke the
                // phone; it no longer does. A panel that died while the screen was off
                // is precisely the case the user cannot fix themselves — they turn the
                // rear display on by double-tapping it and find nothing there.
                Log.i(TAG, "re-placing the panel while the screen is off")
            }
            // `payload` carries the config, so a freshly launched panel comes up with
            // the user's ring geometry already applied. This used to need a second,
            // delayed push from a separate store — the panel runs in ":rear" and could
            // not see values kept only in this process — and that delay was a visible
            // window in which every respawn showed the compiled-in defaults.
            //
            // light = false: a recovery, not a request. The back is lit afterwards only if it
            // was lit before (PlaceLight) — this runs in pockets, after every app restart.
            app.bridge.show(payload, light = false)
            scope.launch { verifyPlacement(app) }
        }
        return decision
    }

    /**
     * Ongoing foreground notification. Deliberately static/generic text only — no
     * utilisation numbers, no account names, no timestamps rendered here. Nothing
     * about this notification, or any log line in this class, carries anything
     * beyond what's already true from the app being installed.
     */
    private fun notification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Magic Rear", NotificationManager.IMPORTANCE_LOW))
        // Tapping the notification re-places the panel. This is the only recovery path
        // that does not require opening the app, and it exists because the panel can
        // end up in states the watchdog cannot see — a task that still exists while
        // nothing composites it will read as ALIVE forever.
        val tap = PendingIntent.getForegroundService(
            this, 0,
            Intent(this, UsagePollService::class.java).setAction(ACTION_SHOW_PANEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setContentTitle("Magic Rear")
            // The empty state only — IslandNotification.decorate replaces this the
            // moment there are readings. It used to say "tap to show on the rear panel",
            // which was an instruction, and placing the panel by hand stopped being a
            // step once the service started recovering it on its own. Tapping still
            // works and is still the one recovery path that does not need the app
            // opened; it is simply no longer something anyone has to be told.
            .setContentText("Waiting for the first reading")
            // Ours, not a stock system glyph — this one is permanently in the
            // status bar, so it is the app's face more than any other icon.
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NOTIFICATION_TINT)
            .setContentIntent(tap)
            .setOngoing(true)
            // Once the numbers exist, this notification carries them — into the shade
            // and, on HyperOS, into the island. Decorating the notification the service
            // must keep alive anyway costs nothing; a second permanent one would.
            .let { IslandNotification.decorate(it, Graph.of(this).repository.state.value) }
        return builder.build()
    }

    /**
     * The app was swiped out of recents.
     *
     * A foreground service is supposed to survive this, and on stock Android it does.
     * HyperOS is more aggressive: "close all" takes the process, and with it the panel —
     * which is why the setup advice says to lock the app in recents. Asking for the
     * service back immediately is the one thing this side can do about it, and it costs
     * nothing when the system was going to keep us anyway.
     *
     * Not a substitute for the autostart permission. If MIUI has decided to kill the
     * process, this call goes with it; what it recovers is the milder case where the
     * TASK is removed but the process survives.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed — asking for the service back")
        start(this)
        super.onTaskRemoved(rootIntent)
    }

    /** The active media session, or null when notification access has not been granted. */
    private var media: MediaWatcher? = null
    private var earbuds: EarbudsWatch? = null

    override fun onDestroy() {
        Graph.of(this).bridge.onConnected = {}
        Graph.of(this).bridge.onRestingFaceDownChanged = {}
        HealthCheck.stop(this)
        stopTilt()
        stopConfigWatch?.invoke()
        stopConfigWatch = null
        runCatching { unregisterReceiver(portalAppsReceiver) }
        portalApps?.stop()
        displays.unregisterDisplayListener(displayListener)
        pocket?.stop()
        pocket = null
        table?.stop()
        table = null
        camera?.stop()
        stayOn?.stop()
        camera = null
        media?.stop()
        media = null
        earbuds?.stop()
        earbuds = null
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        Graph.of(this).bridge.onRearWoke = { }
        Graph.of(this).bridge.chargeDetailFor = null
        runCatching { dev.kb896.aiusage.place.PlaceWatch.stop(this) }
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(chargeNews) } catch (_: Exception) {}
        runCatching { contentResolver.unregisterContentObserver(brightnessWatch) }
        runCatching { contentResolver.unregisterContentObserver(quickWatch) }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Handles the notification tap: re-place the panel, now, from cached state.
     *
     * Deliberately a re-place rather than a nudge — this is the button you reach for
     * precisely when the panel is NOT there, so it must work from a dead panel, not
     * only from a live one. It calls show() directly instead of going through
     * ensurePlaced(), because the watchdog's Watchdog.decide() will happily answer
     * ALIVE for a task that exists but is composited nowhere, which is the exact state
     * this recovers from.
     *
     * Makes no network call. The payload is whatever the repository already holds, the
     * same rule the rest of the app follows.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A debug build's level probe (MusicLevel.probe) has nothing else to start it.
        if (dev.kb896.aiusage.BuildConfig.DEBUG) MusicLevel.sync(this)
        // The tap re-places the panel, which companion mode has none of: the tap does nothing more.
        if (intent?.action == ACTION_SHOW_PANEL && !config().companion) {
            val app = Graph.of(this)
            // Asked for by a tap, so lit (light defaults to true): unlike the watchdog's.
            app.bridge.show(StatePayload.encode(app.repository.state.value, config(), WeatherPoll.latest))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** The status-bar icon's tint: neutral, as the settings are. */
        private const val NOTIFICATION_TINT = 0xFF8A8F98.toInt()
        /** How long after the lock the back is lit again: past HyperOS's own sleep of it. */
        private const val RELIGHT_AFTER_LOCK_MS = 700L
        /** The back counts as having gone dark with the front within this of the lock. */
        private const val WENT_DARK_TOGETHER_MS = 1_500L
        /**
         * How long to wait before checking whether a placement lit the rear panel.
         * Must exceed MIUI's own rear-display timeout (observed ~5s) so a genuine
         * success is not mistaken for a failure, while keeping a bad placement's time
         * on the user's main screen short.
         */
        private const val VERIFY_DELAY_MS = 8_000L

        /**
         * How often to check the panel is still placed. Short because the user
         * dismisses it by double-tapping the rear panel, and a status display that
         * takes minutes to come back is not one you trust to be there.
         *
         * Cheap to run: no network, just an aliveness check and, if needed, a re-place
         * using cached state.
         */
        /**
         * How long after a placement to send the tuned ring geometry. Long enough for
         * the activity to have registered its receiver, short enough that the default
         * geometry is not visible for long.
         */
        private const val GEOMETRY_PUSH_DELAY_MS = 1_500L

        private const val WATCHDOG_INTERVAL_MS = 20_000L
        private const val SHELL_ALIVE_MS = 3_000L

        /** How often the gravity reading goes to the shell's liquid: about 30 a second. */
        private const val TILT_EVERY_MS = 33L
        /** How long the charger's animation waits for the shell to bind. */
        private const val CHARGE_BIND_MS = 2_500L

        /** Notification tap → re-place the panel. See onStartCommand. */
        const val ACTION_SHOW_PANEL = "dev.kb896.aiusage.SHOW_PANEL"

        private const val TAG = "AiUsagePoll"
        private const val CHANNEL = "usage"
        private const val NOTIFICATION_ID = 1

        fun start(c: Context) = c.startForegroundService(Intent(c, UsagePollService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, UsagePollService::class.java))
    }
}
