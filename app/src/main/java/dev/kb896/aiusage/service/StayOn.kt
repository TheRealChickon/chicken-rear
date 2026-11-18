package dev.kb896.aiusage.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.kb896.aiusage.BuildConfig
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.rear.FaceDownWatch
import dev.kb896.aiusage.rear.LyingOnBack
import dev.kb896.aiusage.rear.ShizukuBridge
import java.io.File

/**
 * Whether the back is to be lit with the scene on it, decided from how the phone is held, and
 * told to the shell (ShizukuBridge.setStay), which does the lighting.
 *
 * The rule: on whenever the phone is in use in the hand, and whenever it
 * lies face down (the back facing up), so that it is seen; off in a pocket, lying on its back,
 * and after a double tap on the back until it is lit again by hand.
 *
 * [level] is the whole rule, pure. What feeds it:
 *  - in use: the FRONT screen is on (FrontScreen: the front display's own state, never
 *    PowerManager.isInteractive, which stays true while the back alone is lit; the screen broadcasts and
 *    the display's changes the service passes on with [sync]);
 *  - face down: the shell's resting decision, shared with its front dim and the table watch;
 *  - lying on its back: the accelerometer (LyingOnBack). Posture is read only while something could come of it: the
 *    front on, or the back being held lit (which keeps the phone awake, so readings keep coming);
 *  - a pocket: the rear proximity sensor (RearProximity), asked only when the front is off;
 *  - switched off by hand: the double tap's latch (ShizukuBridge.manualOff).
 *
 * ASLEEP AND THEN LAID DOWN. With both screens off the phone sleeps, and nothing above runs. So
 * while the rule says off and the front is dark, the significant-motion sensor is armed (a
 * wake-up sensor: it fires once when the phone is moved, asleep or not). A firing holds the
 * processor for [LOOK_MS] and reads the posture in that time: laid face down, the back comes on,
 * and from then the back being lit keeps the phone awake. Armed again no sooner than
 * [MOTION_REARM_MS] after a firing, so a walk with the phone in a pocket is not one long wake.
 *
 * A debug build reads `files/posture-override` in the app's shared folder first ("hand",
 * "facedown", "back", "pocket", "camera", "saver", or nothing for the sensors), so a posture can be tested with the
 * phone lying where it is; the word "night" beside it ("facedown night") is the night whatever the clock says.
 *
 * AT NIGHT (NightRule, "Sleep at night"): with the front off the back has two minutes, the phone being
 * moved lights nothing, and this stops looking altogether.
 *
 * NOTHING RUNS WHILE NOTHING CAN COME OF IT: with the front off and the back off the tick is not posted
 * again ([nextTickMs]). What starts it: the front (the service's [sync]), a setting, a finger on the back
 * (the shell says so), and by day the phone being moved.
 *
 * THE SHELL IS TOLD AGAIN every [CONFIRM_MS] while the back is to be lit: it holds the wake lock, it is
 * never frozen, and a level this app set and then could not take back (frozen by Xiaomi's night mode,
 * killed) would hold the phone awake for ever. Not told for half a minute, the shell lets go by itself.
 */
class StayOn(
    private val context: Context, private val bridge: ShizukuBridge, private val config: () -> PanelConfig,
    /** A camera is in use on the phone (CameraWatch). */
    private val cameraInUse: () -> Boolean = { false },
) : SensorEventListener {

    /**
     * How the back is to be, by its ordinal what IRearService.setStay takes: off; a dim still
     * picture (in the hand in battery saver); dim and moving (lying face down); on, a little dim
     * (in use); full (the camera is open).
     */
    enum class Level { OFF, STILL, DIM, ON, FULL }

    private var lastHomeWord = ""

    data class Facts(
        val enabled: Boolean,
        val inUse: Boolean,
        val faceDown: Boolean,
        val onBack: Boolean,
        val pocketed: Boolean,
        val manualOff: Boolean,
        val recentTouch: Boolean,
        val dimFaceDown: Boolean,
        val camera: Boolean = false,
        /** Android's battery saver is on. */
        val saver: Boolean = false,
        /** The battery's percent; under 0 when not known. */
        val battery: Int = 100,
        val charging: Boolean = false,
        /** How long it has lain face down untouched. */
        val faceDownForMs: Long = 0L,
        /** How long since the front came on or the back was last touched, in use. */
        val quietForMs: Long = 0L,
        /** Home detection is on and the phone is on the Wi-Fi marked as home (HomeNet). */
        val home: Boolean = false,
        /** About to sleep: night, or Do Not Disturb (HomeNet.bedtime). */
        val bedtime: Boolean = false,
        /** "Sleep at night" is on and it is night or Do Not Disturb (NightRule.night). */
        val night: Boolean = false,
        /** With the front off: how long since it went off or the back was last touched. */
        val frontOffForMs: Long = 0L,
    )

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val thread = HandlerThread("stay-on").apply { isDaemon = true; start() }
    private val handler = Handler(thread.looper)
    private val probe by lazy { RearProximity.Probe(context) }
    private val ambient = AmbientLight(context, handler) { lux, source -> bridge.setAmbient(lux, source) }
    private val onBack = LyingOnBack()
    private var listening = false
    private var pocketed = false
    private var pocketAskedAt = 0L
    private var last: Pair<Level, String>? = null
    private var faceDownSince = 0L
    private var inUseSince = 0L
    /** When the front went off (elapsedRealtime: the night's two minutes pass asleep too); 0 while it is on. */
    private var frontOffSince = 0L
    private var ticked = false
    private var confirmedAt = 0L
    private var interactiveWord = ""
    private val motion: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private var motionArmed = false
    private var motionAt = 0L
    private var lookUntil = 0L
    private val lookLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChickenRear:posture").apply { setReferenceCounted(false) }
    private val onMotion = object : android.hardware.TriggerEventListener() {
        override fun onTrigger(event: android.hardware.TriggerEvent?) {
            // On the sensor's thread, the processor awake only until this returns: held, then looked at.
            runCatching { lookLock.acquire(LOOK_MS + 1_000L) }
            handler.post {
                motionArmed = false
                motionAt = SystemClock.elapsedRealtime()
                lookUntil = SystemClock.uptimeMillis() + LOOK_MS
                pocketAskedAt = 0L
                sync()
            }
        }
    }
    @Volatile private var stopped = false

    fun start() {
        // The battery and Do Not Disturb from their broadcasts, on this rule's own thread (before its first tick); the
        // sticky battery comes back at once.
        handler.post { listenNews() }
        handler.post(tick)
    }

    private fun listenNews() {
        if (stopped || newsOn) return
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context, news,
                android.content.IntentFilter().apply {
                    addAction(android.content.Intent.ACTION_BATTERY_CHANGED)
                    addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                },
                null, handler, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )?.let { battery = it }
            newsOn = true
        }.onFailure { Log.w(TAG, "stay: no battery / Do Not Disturb broadcasts (${it.message}): read each tick") }
    }

    private val news = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                android.content.Intent.ACTION_BATTERY_CHANGED -> battery = intent
                android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> dndReadAt = 0L
            }
        }
    }
    private var newsOn = false
    private var battery: android.content.Intent? = null
    private var dnd = false
    /** When Do Not Disturb was last read (uptimeMillis); 0 for never, or changed since. */
    private var dndReadAt = 0L

    private fun batteryNow(): android.content.Intent? =
        battery?.takeIf { newsOn } ?: context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))

    private fun dndNow(now: Long): Boolean {
        if (newsOn && dndReadAt != 0L && now - dndReadAt < DND_FRESH_MS) return dnd
        dnd = runCatching { context.getSystemService(android.app.NotificationManager::class.java)?.currentInterruptionFilter?.let { it != android.app.NotificationManager.INTERRUPTION_FILTER_ALL && it != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN } }.getOrNull() == true
        dndReadAt = now
        return dnd
    }

    /**
     * The rear display came on. While this rule has the back OFF nothing of ours lights it (the shell wakes it only
     * for a level above off, and at night every other wake of ours is declined), so that is HIS HAND: Xiaomi's own
     * double tap on the dark back. It counts as a finger on the back: the night's two minutes start with it, and a
     * back that lay face down past its time is lit again. A wake of our own (the level already above off) is not one.
     */
    fun rearWoke() {
        handler.post {
            // Not a light this app has just made itself (the pocket's way out): that is no hand.
            val own = SystemClock.elapsedRealtime() - bridge.ownWakeAt < OWN_WAKE_MS
            if ((last?.first ?: Level.OFF) == Level.OFF && !own) { wokenByHandAt = SystemClock.elapsedRealtime(); Log.i(TAG, "stay: the back was lit by hand") }
            sync()
        }
    }
    private var wokenByHandAt = 0L
    private val OWN_WAKE_MS = 2_000L

    /** Something changed (a setting, the front screen, the double tap's latch): look again now. */
    fun sync() { handler.removeCallbacks(tick); handler.post(tick) }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        handler.post {
            ambient.listen(false)
            listen(false)
            bridge.restingFaceDown(FaceDownWatch.STAY, false)
            if (motionArmed && motion != null) sensors.cancelTriggerSensor(onMotion, motion)
            runCatching { if (lookLock.isHeld) lookLock.release() }
            if (newsOn) { newsOn = false; runCatching { context.unregisterReceiver(news) } }
            bridge.setStay(0, "the service stopped")
        }
        thread.quitSafely()
    }

    private var forcedWas: String? = null
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (stopped) return
            val c = config()
            val enabled = c.companion && c.stayOn && c.sceneOn && (c.sceneLoop.isNotEmpty() || c.sceneWake.isNotEmpty())
            val inUse = FrontScreen.on(context)
            noteInteractive(inUse)
            val words = NightRule.debugWords(context)
            val forcedWord = words.firstOrNull { it != "night" }
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val dnd = dndNow(SystemClock.uptimeMillis())
            val night = "night" in words || NightRule.night(c.sleepAtNight, hour, dnd)
            // "camera" and "saver" are the phone in the hand with that also true.
            val forced = if (forcedWord == "camera" || forcedWord == "saver") "hand" else forcedWord
            // A posture forced for a look at the back, new this tick: a pocket the shell still has (the phone lies on its
            // back, the sensor covered) is called off, or the back it switched off would stay off under the forced posture.
            if (forced != forcedWas) {
                forcedWas = forced
                if (forced != null && forced != "pocket" && forced != "back") runCatching { bridge.pocketEnd(true) }
            }
            val wasOn = (last?.first ?: Level.OFF) != Level.OFF
            // At night with the front off the phone being moved is not looked at.
            if (night && !inUse) lookUntil = 0L
            val looking = SystemClock.uptimeMillis() < lookUntil
            // Readings are wanted while something could come of them.
            val watch = enabled && forced == null && (inUse || wasOn || looking)
            listen(watch)
            val resting = bridge.restingFaceDown(FaceDownWatch.STAY, watch)
            val down = forced?.let { it == "facedown" } ?: resting
            val back = forced?.let { it == "back" } ?: onBack.down
            val now = SystemClock.uptimeMillis()
            // A pocket is only a question with the front off: in the hand, fingers cover the back.
            if (forced != null) pocketed = forced == "pocket"
            else if (!enabled || inUse) pocketed = false
            else if (now - pocketAskedAt > POCKET_ASK_MS) {
                pocketAskedAt = now
                probe.covered()?.let { pocketed = it }
            }
            val using = inUse || forced == "hand"
            val touchedAgo = (if (down || using || night) bridge.msSinceRearTouch() else null) ?: -1L
            // Since when it lies there, or is in use, untouched: a finger on the back starts both again.
            if (!down) faceDownSince = 0L else if (faceDownSince == 0L) faceDownSince = now
            if (!using) inUseSince = 0L else if (inUseSince == 0L) inUseSince = now
            val sinceWoken = if (wokenByHandAt != 0L) SystemClock.elapsedRealtime() - wokenByHandAt else Long.MAX_VALUE
            val sinceTouch = minOf(if (touchedAgo >= 0) touchedAgo else Long.MAX_VALUE, sinceWoken)
            // Since when the front is off. A process that starts with it off does not know: taken as long ago,
            // so a new process at night lights nothing.
            val rt = SystemClock.elapsedRealtime()
            if (using) frontOffSince = 0L else if (frontOffSince == 0L) frontOffSince = if (ticked) rt else rt - NightRule.ON_MS
            ticked = true
            val battery = batteryNow()
            val pct = ChargeLevel.percent(
                battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1,
                battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1,
            ) ?: -1
            // The Wi-Fi is read only for home detection (it was three binder calls a tick, twice, for a log line too).
            val wifi = if (c.homeDetect) HomeNet.current(context) else null
            val facts = Facts(
                enabled = enabled, inUse = using, faceDown = down, onBack = back, pocketed = pocketed,
                manualOff = bridge.manualOff.on, recentTouch = sinceTouch in 0 until TOUCH_MS, dimFaceDown = c.stayDimFaceDown,
                camera = cameraInUse() || forcedWord == "camera", saver = power.isPowerSaveMode || forcedWord == "saver",
                battery = pct, charging = (battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
                faceDownForMs = if (down) minOf(now - faceDownSince, sinceTouch) else 0L,
                quietForMs = if (using) minOf(now - inUseSince, sinceTouch) else 0L,
                home = forcedWord == "home" || forcedWord == "homenight" || HomeNet.atHome(c, wifi),
                bedtime = forcedWord == "homenight" || (forcedWord != "home" && HomeNet.bedtime(hour, dnd)),
                night = night,
                frontOffForMs = if (using) 0L else minOf(rt - frontOffSince, sinceTouch),
            )
            // Said once per change, without the network itself: whether a Wi-Fi can be told apart here at all.
            val homeWord = "detection ${if (c.homeDetect) "on" else "off"}, a home Wi-Fi ${if (c.homeNet.isEmpty()) "not set" else "set"}, on Wi-Fi ${if (!c.homeDetect) "not read" else if (wifi != null) "yes" else "no"}, at home ${facts.home}, night ${facts.bedtime}"
            if (homeWord != lastHomeWord) { lastHomeWord = homeWord; Log.i(TAG, "home: $homeWord") }
            val (level, why) = level(facts)
            // The light around the phone is read while the back is lit and goes by it, and at no other time.
            // (Lit by his own hand while this rule has it off, it is lit all the same: read then too.)
            val rearOn = level == Level.OFF && runCatching {
                (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(bridge.rearDisplayId())?.state == android.view.Display.STATE_ON
            }.getOrDefault(false)
            ambient.listen(c.rearAdaptive && (level != Level.OFF || rearOn))
            if (last != level to why) {
                last = level to why
                confirmedAt = now
                Log.i(TAG, "stay: ${level.name.lowercase()} — $why")
                bridge.setStay(level.ordinal, why)
            } else if (level != Level.OFF && now - confirmedAt >= CONFIRM_MS) {
                // Still so: said again, or the shell lets the back go (it does after half a minute of silence).
                confirmedAt = now
                bridge.setStay(level.ordinal, why)
            }
            // What is only ever SEEN (the usage figures, the weather, the shell's ping) waits while there is nothing
            // to see: the front off and the back off. Companion mode has no panel of its own to say so.
            if (c.companion) PanelAwake.setDozing(nothingToSee(using, level))
            // Off with the front dark: the next thing that can change it is the phone being moved. Not at night.
            val sleeping = enabled && !inUse && level == Level.OFF && !looking
            if (sleeping && NightRule.watchesMotion(night) && !motionArmed && motion != null && rt - motionAt > MOTION_REARM_MS) {
                motionArmed = sensors.requestTriggerSensor(onMotion, motion)
            } else if (motionArmed && motion != null && ((night && !inUse) || (!sleeping && (inUse || level != Level.OFF)))) {
                sensors.cancelTriggerSensor(onMotion, motion)
                motionArmed = false
            }
            nextTickMs(looking, enabled, using, level, listening)?.let { handler.postDelayed(this, it) }
        }
    }

    /**
     * The front off while the phone still calls itself interactive: said once per change, and kept in a debug
     * build's `files/stay-notes.txt`, because the log on this phone holds two minutes. It is the answer to
     * whether "in use" taken from isInteractive would have held itself for ever.
     */
    private fun noteInteractive(frontOn: Boolean) {
        val interactive = power.isInteractive
        val word = "front ${if (frontOn) "on" else "off"}, interactive $interactive"
        if (word == interactiveWord) return
        val first = interactiveWord.isEmpty()
        interactiveWord = word
        if (frontOn == interactive && first) return
        Log.i(TAG, "screens: $word")
        if (!BuildConfig.DEBUG) return
        runCatching {
            val file = File(context.getExternalFilesDir(null) ?: return, "stay-notes.txt")
            if (file.length() > 16_000L) return
            file.appendText("${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $word, back ${last?.first?.name?.lowercase() ?: "?"}\n")
        }
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        if (on) {
            val s = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, handler)
        } else {
            sensors.unregisterListener(this)
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.uptimeMillis()
        val b = onBack.reading(e.values[0], e.values[1], e.values[2], now)
        if (b != null) sync()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "AiUsageStay"
        private const val TICK_MS = 2_000L
        /** While the back is to be lit the shell is told so again this often (it lets go after StaySilence.SILENT_MS, front off). */
        const val CONFIRM_MS = 8_000L

        /**
         * When to look again; null for not at all, until something happens. While it is watched after being moved:
         * quickly. In use, or the back lit: every two seconds. The front off and the back off: nothing can come of
         * looking (the 15-second idle look this had registered the rear's wake-up proximity sensor 348 times in one
         * hour and a half of a night), except once more while the accelerometer is still registered, so that it is
         * let go.
         */
        fun nextTickMs(looking: Boolean, enabled: Boolean, inUse: Boolean, level: Level, listening: Boolean): Long? = when {
            looking -> LOOK_TICK_MS
            (enabled && (inUse || level != Level.OFF)) || listening -> TICK_MS
            else -> null
        }
        private const val POCKET_ASK_MS = 10_000L
        /** Do Not Disturb is read again at least this often, broadcast or not. */
        private const val DND_FRESH_MS = 60_000L
        /** After the phone is moved while asleep: how long the posture is watched, and how often. */
        private const val LOOK_MS = 6_000L
        private const val LOOK_TICK_MS = 500L
        private const val MOTION_REARM_MS = 30_000L
        /** A finger on the back lying face down brings it to full for this long (RearPosture.TOUCH_MS). */
        const val TOUCH_MS = 30_000L

        /** At home (home detection), by day: lit this long after the front comes on or the back is touched, then off. */
        const val HOME_ON_MS = 2 * 60_000L

        /** In battery saver, in the hand: the still picture stays this long after the front comes on or the back is touched. */
        const val SAVER_STILL_MS = 60_000L

        /**
         * How long it stays lit lying face down, untouched: half an hour, less as the battery
         * runs down, and half an hour whatever the level while it charges.
         */
        fun faceDownLimitMs(battery: Int, charging: Boolean): Long = when {
            charging || battery >= 50 || battery < 0 -> 30 * 60_000L
            battery >= 30 -> 15 * 60_000L
            else -> 5 * 60_000L
        }

        /** Nothing of ours can be seen: the front off and the back off. What is only ever seen waits then (PanelAwake). */
        fun nothingToSee(inUse: Boolean, level: Level): Boolean = !inUse && level == Level.OFF

        /** The rule, and the reason for the log and the settings. */
        fun level(f: Facts): Pair<Level, String> {
            // Saving: Android's battery saver, or under 15% off the charger.
            val saving = f.saver || (!f.charging && f.battery in 0..14)
            return when {
                !f.enabled -> Level.OFF to "always on is switched off"
                f.manualOff -> Level.OFF to "switched off by hand"
                f.onBack -> Level.OFF to "lying on its back"
                f.pocketed && !f.inUse -> Level.OFF to "in a pocket"
                // AT NIGHT, the front off: two minutes after it went off or a finger was on the back, whatever the
                // posture, and nothing but a finger or the front starts them again (NightRule).
                NightRule.timeUp(f.night, f.inUse, f.frontOffForMs) -> Level.OFF to "night, the front off for ${NightRule.ON_MS / 60_000} minutes"
                // Taking a picture: everything it has, for whoever is in front of the lens.
                f.camera && (f.inUse || f.faceDown) -> Level.FULL to "the camera is open"
                f.faceDown -> when {
                    saving -> Level.OFF to "lying face down, saving battery"
                    f.faceDownForMs >= faceDownLimitMs(f.battery, f.charging) ->
                        Level.OFF to "lying face down for ${faceDownLimitMs(f.battery, f.charging) / 60_000} minutes"
                    f.dimFaceDown && !f.recentTouch -> Level.DIM to "lying face down"
                    else -> Level.ON to "lying face down, in use"
                }
                f.inUse -> when {
                    !saving -> Level.ON to (if (f.home && f.bedtime) "in use, at home at night" else if (f.home) "in use, at home" else "in use")
                    f.quietForMs < SAVER_STILL_MS -> Level.STILL to "in use, saving battery"
                    else -> Level.OFF to "in use, saving battery"
                }
                else -> Level.OFF to "not in use"
            }
        }
    }
}
