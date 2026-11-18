package dev.kb896.aiusage.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.rear.FaceDown
import dev.kb896.aiusage.rear.FaceDownWatch
import dev.kb896.aiusage.rear.LyingOnBack
import dev.kb896.aiusage.rear.RearActivity
import dev.kb896.aiusage.rear.ShizukuBridge
import java.util.concurrent.Executors

/**
 * THE TABLE'S WATCH, in the poll service, for the back's posture (RearPosture):
 *
 * - FACE DOWN ("Dim when face down", PanelConfig.tableMode): laid on its face, the panel is told
 *   (RearActivity.ACTION_TABLE) and the back woken for it; the panel puts up the dim. Lifted, the
 *   panel is told that too.
 * - ON ITS BACK ("Off when lying on its back", PanelConfig.offOnBack): settled and still on its
 *   back (LyingOnBack), the bridge declines wakes (ShizukuBridge.lyingOnBack) and the panel is
 *   told ([RearActivity.EXTRA_TABLE_BACK]) and lets the back go. Picked up, the panel is told and
 *   the back woken — the one way it comes back on by itself, the panel's process perhaps frozen.
 *
 * IN THE POLL SERVICE'S PROCESS, for PocketWatch's reason: a dark back stops the panel's
 * activity and its :rear process can be frozen. This watch sends the shell's resting decision
 * to the panel both while it is lit and when it is dark and table wake is enabled.
 *
 * THE WAKE-UP ACCELEROMETER where the phone has one: with both screens off the processor
 * suspends, and only a wake-up sensor's events wake it. Lying on its back uses the batched event timestamps.
 * Face-down orientation starts a short shell probe using fresh samples. Delivery holds a wakelock only
 * until the listener returns; the guest check, the
 * panel's word and the shell's wake run after it, under a short one of our own ([WAKE_HOLD_MS]).
 *
 * THREE CADENCES (TableCadence). LIFT while it lies settled either way: [LIFT_SAMPLING_US] in
 * [LIFT_BATCH_US] batches, so picking it up is heard within about half a second — battery is
 * not the concern there, the back coming on in the hand is. ACTIVE for [ACTIVE_MS] after the
 * watch starts and after a lift: [SAMPLING_US] in [BATCH_US] batches, a lie seen within a couple
 * of seconds of settling. Then IDLE: one reading a second in batches of [IDLE_BATCH_US], and only
 * where the sensor hub can hold that batch (its FIFO); where it cannot, the idle watch reads the
 * ordinary, non-wake-up accelerometer, which never wakes the processor.
 *
* With both screens off and no wake-on-lift action, use the idle sensor cadence. Waking either screen restores the faster cadence.
 *
 * Watching while either switch is on — the front on or off: lying on its back is as much a
 * posture with the front in use. Not in a pocket (pocket mode switched the back off; PocketWatch
 * says when that changes). Face down, two refusals: "Only while charging" with no charger in, and
 * a guest app over the panel.
 */
class TableWatch(private val context: Context, private val bridge: ShizukuBridge) {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var faceDown = false
    private var faceDemand = false
    private var nextFaceRequestAt = 0L
    private var panelWantsTable = false
    private val probeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiUsage:resting-probe")
            .apply { setReferenceCounted(false) }
    }
    private val back = LyingOnBack()

    /** The guest check and the shell's wake are binder calls: off the main thread, in order. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "table").apply { isDaemon = true } }

    private val wakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiUsage:table")
            .apply { setReferenceCounted(false) }
    }

    /**
     * The wake-up accelerometer first; else the ordinary one, then gravity (then only while the
     * phone is awake anyway). The raw accelerometer before gravity: lying on its back needs the
     * hand's tremor in the readings (LyingOnBack's stillness), which gravity filters out.
     */
    private val sensor: Sensor? by lazy {
        (sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)).also {
            if (it == null) Log.w(TAG, "no accelerometer — no table watch while the back is dark")
            else Log.i(TAG, "the table watch's sensor: ${it.name}, wake-up ${it.isWakeUpSensor}")
        }
    }

    /** The IDLE cadence's: the same sensor where its FIFO holds a whole idle batch, else the non-wake-up one. */
    private val idleSensor: Sensor? by lazy {
        val s = sensor ?: return@lazy null
        val plain = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        TableCadence.idleSensor(s, plain, IDLE_SAMPLING_US, IDLE_BATCH_US, { it.isWakeUpSensor }, { it.fifoMaxEventCount }).also {
            Log.i(TAG, "the table watch's idle sensor: ${it.name}, wake-up ${it.isWakeUpSensor}, fifo ${it.fifoMaxEventCount}")
        }
    }

    /** The cadence registered now, and until when ACTIVE lasts (elapsedRealtime: it counts the suspend too). */
    private var cadence: TableCadence.Mode? = null
    /** IDLE was chosen for a settled lie (both screens dark), not for nothing happening: for the log alone. */
    private var slowWhileSettled = false
    private var activeUntil = 0L

    private var switchOn = false
    private var chargingOnly = false
    private var backOn = false
    /**
     * A lift off its back wakes the back: PanelConfig.wakeOnLift, or always in Replace mode,
     * where the panel let go when it was laid down. Off in companion mode by default — there it
     * lit Xiaomi's rear on every pick-up — and "on its back" then only declines wakes.
     */
    private var liftWakes = true
    private var watching = false
    private var stopped = false

    /** The panel has been told it lies there, for this lie. */
    private var told = false

    /** The switches, from the config: read once per write. */
    fun setConfig(on: Boolean, chargingOnly: Boolean, backOn: Boolean, liftWakes: Boolean = true, panelWantsTable: Boolean = false) {
        val liftChanged = liftWakes != this.liftWakes
        this.liftWakes = liftWakes
        val panelChanged = panelWantsTable != this.panelWantsTable
        this.panelWantsTable = panelWantsTable
        if (on == switchOn && chargingOnly == this.chargingOnly && backOn == this.backOn && !panelChanged) {
            // Only whether a lift wakes the back: that is the cadence's question alone.
            if (liftChanged && watching) recadence()
            return
        }
        switchOn = on
        this.chargingOnly = chargingOnly
        if (this.backOn && !backOn && back.down) {
            // Switched off while it lies there: the back is free again (the panel's own switch relights it).
            back.reset()
            onBack(false, wake = false)
        }
        this.backOn = backOn
        sync()
    }

    /** Something that changes what to watch, or what is refused: the front on or off, the charger, the pocket. */
    fun sync() {
        if (stopped) return
        dark = screensDark()
        val want = (wantsFace() || backOn) && sensor != null && !bridge.isPocketed()
        watch(want)
        if (!switchOn && told) {
            // Switched off on the table: the panel's own switch has ended it (RearActivity.render).
            told = false
        }
        // Lying there already, and a refusal gone (the charger in): now.
        if (!wantsFace()) requestFace(false)
        if (watching && faceDown && !told) deliver()
        // The front on or off (the service's display listener and screen broadcasts come here): the lift's cadence.
        if (watching) recadence()
    }

    /**
     * What this watch knows of lying on its back, said to the panel again. The panel hears only
     * edges, so it can hold a word nobody will take back: a panel new since the lie (its :rear
     * process placed again; asked on its ACTION_DOZING, which every new panel says), or a "down"
     * from this process's predecessor, killed before it heard the lift.
     */
    fun restate() {
        if (stopped) return
        val down = bridge.lyingOnBack
        val face = faceDown
        worker.execute {
            tellPanel(down = false, onBack = down)
            tellPanel(down = face)
        }
    }

    /**
     * A new process: the panel may still hold the old one's "on its back". Said once the watch
     * has had time to settle on a lie of its own ([RESTATE_AFTER_START_MS]), so a phone still on
     * its back is not told "picked up" for the moment before it finds that out again.
     */
    fun restateAfterStart() {
        handler.postDelayed({ restate() }, RESTATE_AFTER_START_MS)
    }

    fun stop() {
        if (stopped) return
        watch(false)
        if (probeLock.isHeld) probeLock.release()
        stopped = true
        worker.shutdown()
    }

    /** On its back, settled ([down]) or picked up: the bridge, the panel, and on a lift the back woken. */
    private fun onBack(down: Boolean, wake: Boolean = true) {
        if (bridge.lyingOnBack == down) return
        bridge.lyingOnBack = down
        Log.i(TAG, if (down) "table: lying on its back — the back off" else "table: off its back${if (wake && liftWakes) " — waking the back" else ""}")
        if (down) {
            worker.execute { tellPanel(down = false, onBack = true) }
            return
        }
        wakeLock.acquire(WAKE_HOLD_MS)
        worker.execute {
            try {
                tellPanel(down = false, onBack = false)
                // Declined by the bridge itself where another rule keeps it off (the double tap's latch, a pocket).
                if (wake && liftWakes) bridge.wake("picked up")
            } finally {
                wakeLock.release()
            }
        }
    }

    private fun watch(on: Boolean) {
        if (on == watching) return
        val s = sensor ?: return
        watching = on
        if (on) {
            activeUntil = android.os.SystemClock.elapsedRealtime() + ACTIVE_MS
            register(TableCadence.Mode.ACTIVE)
            dark = screensDark()
            runCatching { displays?.registerDisplayListener(screens, handler) }
            Log.i(TAG, "table: watching")
        } else {
            sensors.unregisterListener(listener)
            runCatching { displays?.unregisterDisplayListener(screens) }
            cadence = null
            slowWhileSettled = false
            // Not a lift: the switch went, or a pocket. Nothing known until it reads again.
            requestFace(false)
            told = false
            // A pocket or the switches: the back is no longer held off for lying on its back.
            back.reset()
            onBack(false, wake = false)
            Log.i(TAG, "table: not watching")
        }
    }

    /** Registered at [mode]'s cadence, in place of whichever was. What a settled posture knows is kept (its lift is still news). */
    private fun register(mode: TableCadence.Mode) {
        val s = (if (mode == TableCadence.Mode.IDLE) idleSensor else sensor) ?: return
        sensors.unregisterListener(listener)
        // A flat run is not carried across the gap; a settled lie is.
        if (!back.down) back.reset()
        cadence = mode
        when (mode) {
            TableCadence.Mode.LIFT -> sensors.registerListener(listener, s, LIFT_SAMPLING_US, LIFT_BATCH_US, handler)
            TableCadence.Mode.ACTIVE -> sensors.registerListener(listener, s, SAMPLING_US, BATCH_US, handler)
            TableCadence.Mode.IDLE -> sensors.registerListener(listener, s, IDLE_SAMPLING_US, IDLE_BATCH_US, handler)
        }
    }

    /** The cadence for now, registered if it is not the one already (posted: never from inside the listener's own call). */
    private fun recadence() {
        if (wanted() == cadence) return
        handler.post {
            if (!watching) return@post
            val now = wanted()
            if (now == cadence) return@post
            val settled = faceDown || back.down
            if (now == TableCadence.Mode.IDLE) {
                Log.i(TAG, if (settled) "table: lying still, both screens dark — the slow watch" else "table: nothing for a while — the slow watch")
            } else if (now == TableCadence.Mode.LIFT && slowWhileSettled) {
                Log.i(TAG, "table: lying still, a screen on — listening for the lift")
            }
            slowWhileSettled = now == TableCadence.Mode.IDLE && settled
            register(now)
        }
    }

    /** TableCadence.mode for now; the screens as last read ([dark]), so a sensor batch makes no display call. */
    private fun wanted(): TableCadence.Mode {
        // The lit panel uses the quicker cadence even before a lie has settled.
        if (panelWantsTable && !dark) return TableCadence.Mode.LIFT
        return TableCadence.mode(
            android.os.SystemClock.elapsedRealtime(), activeUntil, settled = faceDown || back.down,
            liftWakes = liftWakes, screensDark = dark,
        )
    }

    /** Both screens dark, as last read: when the watch starts, at every display change and at every [sync]. */
    private var dark = false

    private val displays: DisplayManager? by lazy { context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager }

    /** Both screens dark: the front (FrontScreen's own rule, dozing counts as dark) and the back. Where it cannot be read: not dark. */
    private fun screensDark(): Boolean = runCatching {
        val dm = displays ?: return@runCatching false
        val front = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching false
        // No rear display at all: nothing lit there.
        val rear = dm.getDisplay(Panel.DISPLAY_ID)
        !FrontScreen.isOn(front.state) && (rear == null || !FrontScreen.isOn(rear.state))
    }.getOrDefault(false)

    /**
     * The screens going on and off, while watching: the lift's cadence follows them (LIFT back the moment one comes on).
     * Every change of a display comes here (refresh rates too); what it does is cheap and changes nothing then.
     */
    private val screens = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (!watching || (displayId != Display.DEFAULT_DISPLAY && displayId != Panel.DISPLAY_ID)) return
            dark = screensDark()
            if (!wantsFace()) requestFace(false)
            recadence()
        }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
            val at = e.timestamp / 1_000_000L
            val look = wantsFace() && (faceDown || FaceDown.flat(x, y, z))
            val now = android.os.SystemClock.uptimeMillis()
            if (look != faceDemand || (look && now >= nextFaceRequestAt)) requestFace(look)
            if (backOn) when (back.reading(x, y, z, at)) {
                true -> onBack(true)
                false -> {
                    onBack(false)
                    activeUntil = android.os.SystemClock.elapsedRealtime() + ACTIVE_MS
                }
                null -> Unit
            }
            recadence()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun wantsFace(): Boolean = switchOn || (panelWantsTable && !dark)

    private fun requestFace(watch: Boolean) {
        if (!watch && !faceDemand) return
        if (watch && !faceDemand && dark) probeLock.acquire(5_000L)
        faceDemand = watch
        nextFaceRequestAt = android.os.SystemClock.uptimeMillis() + 5_000L
        updateFace(bridge.restingFaceDown(FaceDownWatch.TABLE, watch))
    }

    fun restingPostureChanged() {
        if (!stopped && faceDemand) requestFace(wantsFace())
    }

    private fun updateFace(down: Boolean) {
        if (faceDown == down) return
        faceDown = down
        worker.execute { tellPanel(down = down) }
        if (down) {
            deliver()
        } else {
            told = false
            activeUntil = android.os.SystemClock.elapsedRealtime() + ACTIVE_MS
        }
        recadence()
    }

    /** Wake the back for a confirmed lie if table wake is enabled. */
    private fun deliver() {
        if (stopped || told || !switchOn) return
        if (chargingOnly && !plugged()) return
        if (bridge.isPocketed()) {
            Log.i(TAG, "table: face down in a pocket's off — left dark")
            return
        }
        told = true
        wakeLock.acquire(WAKE_HOLD_MS)
        worker.execute {
            try {
                if (bridge.guestOnTop() == true) {
                    Log.i(TAG, "table: face down under a guest app — left as it is")
                    handler.post { told = false }
                    return@execute
                }
                if (!faceDown) return@execute
                Log.i(TAG, "table: face down — waking the back")
                bridge.wake("the table")
            } finally {
                wakeLock.release()
            }
        }
    }

    /** [down]: face down; [onBack]: on its back (null: not about that). */
    private fun tellPanel(down: Boolean, onBack: Boolean? = null) {
        context.sendBroadcast(
            Intent(RearActivity.ACTION_TABLE).setPackage(context.packageName)
                .putExtra(RearActivity.EXTRA_TABLE_DOWN, down)
                .apply { if (onBack != null) putExtra(RearActivity.EXTRA_TABLE_BACK, onBack) },
        )
    }

    /** A charger in: EXTRA_PLUGGED, as the panel reads it. */
    private fun plugged(): Boolean =
        (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private companion object {
        const val TAG = "AiUsageTable"
        /** ACTIVE: five readings a second, the settle rule needs a handful inside its second. */
        const val SAMPLING_US = 200_000
        /**
         * Batched up to this long where the sensor hub can, so the processor is woken every
         * couple of seconds rather than five times a second; the back lights at most this much
         * after the second of settling.
         */
        const val BATCH_US = 2_000_000
        /** LIFT: ten readings a second, in batches of half a second — picked up, heard at once. */
        const val LIFT_SAMPLING_US = 100_000
        const val LIFT_BATCH_US = 500_000
        /** How long ACTIVE lasts after the watch starts or a lift. */
        const val ACTIVE_MS = 3 * 60_000L
        /** IDLE: one reading a second — the settle rule's second is two of them. */
        const val IDLE_SAMPLING_US = 1_000_000
        /** IDLE: batches of half a minute — the processor woken about twice a minute, the back lit that late at most. */
        const val IDLE_BATCH_US = 30_000_000
        /** Long enough for the guest check, the panel's word and the shell's wake; released as they end. */
        const val WAKE_HOLD_MS = 3_000L
        /** LyingOnBack's settle (3 s) plus an ACTIVE batch (2 s), and room to spare. */
        const val RESTATE_AFTER_START_MS = 8_000L
    }
}

/** TableWatch's cadence rules, pure so they are tested without a sensor. */
internal object TableCadence {
    enum class Mode { LIFT, ACTIVE, IDLE }

    fun stillActive(now: Long, activeUntil: Long): Boolean = now < activeUntil

    /**
     * LIFT while a lie is settled (face down or on its back) and its lift is to be heard at once ([liftAtOnce]); a
     * settled lie whose lift is not (both screens dark, and a lift that wakes nothing) is IDLE; not settled, ACTIVE
     * until its time, then IDLE. The defaults are a lift that wakes the back: LIFT whenever settled, as before.
     */
    fun mode(now: Long, activeUntil: Long, settled: Boolean, liftWakes: Boolean = true, screensDark: Boolean = false): Mode = when {
        settled -> if (liftAtOnce(liftWakes, screensDark)) Mode.LIFT else Mode.IDLE
        stillActive(now, activeUntil) -> Mode.ACTIVE
        else -> Mode.IDLE
    }

    /**
     * A lift heard within half a second: where it wakes the back ([liftWakes]: Replace mode, or "wake on lift"), or
     * where a screen is on (somebody may be looking, and the processor is awake anyway). Both screens dark and a lift
     * that wakes nothing (companion mode's default): the lift can wait for an IDLE batch.
     */
    fun liftAtOnce(liftWakes: Boolean, screensDark: Boolean): Boolean = liftWakes || !screensDark

    /** Events a FIFO must hold for one whole idle batch. */
    fun eventsPerBatch(samplingUs: Int, batchUs: Int): Int = (batchUs / samplingUs).coerceAtLeast(1)

    /**
     * The idle watch's sensor: [wakeUp] itself where it is a wake-up sensor whose FIFO holds a
     * whole batch (so it wakes the processor once a batch); else [plain], the non-wake-up one,
     * which wakes nothing; else [wakeUp] as it is (no better choice there).
     */
    fun <S : Any> idleSensor(wakeUp: S, plain: S?, samplingUs: Int, batchUs: Int, isWakeUp: (S) -> Boolean, fifo: (S) -> Int): S {
        if (!isWakeUp(wakeUp)) return wakeUp
        if (fifo(wakeUp) >= eventsPerBatch(samplingUs, batchUs)) return wakeUp
        return plain?.takeIf { !isWakeUp(it) } ?: wakeUp
    }
}
