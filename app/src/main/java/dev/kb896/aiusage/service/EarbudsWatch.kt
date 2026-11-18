package dev.kb896.aiusage.service

import dev.kb896.aiusage.rear.EarbudsMotion
import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * WATCHES FOR EARBUDS CONNECTING and plays the animation once per connection ([EarbudsGate], [EarbudsConnections]).
 *
 * The connection is heard as an audio output appearing (AudioDeviceCallback: no permission, and it is the moment the
 * sound can go to them). The battery is the level the earbuds report to Android over Bluetooth
 * (BluetoothDevice's battery level and its broadcast): that needs "Nearby devices" (BLUETOOTH_CONNECT), his to
 * give; without it, or where the earbuds report none, no figure is shown. Android gives ONE level for the pair:
 * the left, right and case levels Sony's own app shows come over Sony's own protocol, which is Sound Connect's.
 */
class EarbudsWatch(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val connections = EarbudsConnections()
    /** The level each address last reported (the broadcast), for a connection that has not said it yet at the moment the figure comes up. */
    private val levels = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var started = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            val now = SystemClock.uptimeMillis()
            for (d in added) if (d.isSink && EarbudsGate.isEarbuds(d.type)) {
                val key = EarbudsGate.key(d.address, d.productName?.toString()) ?: continue
                if (connections.added(key, d.id, now)) arrived(d, key)
            }
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            val now = SystemClock.uptimeMillis()
            for (d in removed) if (EarbudsGate.isEarbuds(d.type)) {
                val key = EarbudsGate.key(d.address, d.productName?.toString()) ?: continue
                connections.removed(key, d.id, now)
            }
        }
    }

    private val batteryNews = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val device = runCatching { i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java) }.getOrNull() ?: return
            val level = EarbudsGate.level(i.getIntExtra(EXTRA_BATTERY_LEVEL, -1)) ?: return
            levels[device.address] = level
        }
    }

    fun start() {
        if (started) return
        started = true
        // What is already there is not new: no animation for earbuds that were connected before the app started.
        for (d in audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if (EarbudsGate.isEarbuds(d.type)) {
            EarbudsGate.key(d.address, d.productName?.toString())?.let { connections.seed(it, d.id) }
        }
        audio.registerAudioDeviceCallback(callback, main)
        if (btAllowed(context)) runCatching {
            ContextCompat.registerReceiver(context, batteryNews, IntentFilter(ACTION_BATTERY_LEVEL_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { audio.unregisterAudioDeviceCallback(callback) }
        runCatching { context.unregisterReceiver(batteryNews) }
    }

    private fun arrived(d: AudioDeviceInfo, key: String) {
        val name = d.productName?.toString()
        val config = runCatching { dev.kb896.aiusage.data.ConfigStore(context).load() }.getOrNull()
        val way = EarbudsGate.way(PinGlassService.running(), android.provider.Settings.canDrawOverlays(context))
        val decision = EarbudsGate.decide(
            switchOn = config?.earbudsAnim ?: true, way = way, inCall = EarbudsGate.inCall(audio.mode),
            frontOn = FrontScreen.on(context), newConnection = true,
            // The system's own earbuds card is not known to an app (no broadcast, no window of ours to see); ours plays.
            xiaomiShowsItsOwn = false,
        )
        Log.i(TAG, "earbuds: ${name ?: "a headset"} connected — ${decision.why}")
        if (!decision.plays) return
        play(context, d.address, name, way, levels[d.address])
    }

    companion object {
        private const val TAG = "AiUsageBuds"
        /** BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED and its extra (hidden in the SDK, sent to any app holding BLUETOOTH_CONNECT). */
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

        fun btAllowed(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        /** The level the earbuds at [address] report, through BluetoothDevice.getBatteryLevel (hidden), else [heard]. */
        fun level(context: Context, address: String?, heard: Int? = null): Int? {
            if (address.isNullOrBlank() || !btAllowed(context)) return null
            val direct = runCatching {
                val dev = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
                dev.javaClass.getMethod("getBatteryLevel").invoke(dev) as Int
            }.getOrNull()?.let { EarbudsGate.level(it) }
            return direct ?: heard
        }

        /** The earbuds connected now (for "Try it" in the settings): the first such output, or null. */
        fun connectedNow(context: Context): AudioDeviceInfo? =
            context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { EarbudsGate.isEarbuds(it.type) && it.type != 7 }
                ?: context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { EarbudsGate.isEarbuds(it.type) }

        /** uptimeMillis of the animation playing now, for [EarbudsGate.busy] (0: none yet). */
        private val lastStart = java.util.concurrent.atomic.AtomicLong(0L)
        private val frontActive = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Plays the animation for the earbuds at [address] called [name], on the front and (from its moment) on the
         * back. False: one is already playing, so this one is not put over it. On the main thread.
         */
        fun play(context: Context, address: String?, name: String?, way: EarbudsGate.Way, heard: Int? = null): Boolean {
            val app = context.applicationContext
            val at = SystemClock.uptimeMillis()
            val last = lastStart.get()
            if (frontActive.get() || EarbudsGate.busy(at, last) || !lastStart.compareAndSet(last, at)) { Log.i(TAG, "earbuds: one is already playing — not shown over it"); return false }
            frontActive.set(true)
            val moment = dev.kb896.aiusage.ui.EarbudsMoment(name = name, address = address, at = at)
            val main = Handler(Looper.getMainLooper())
            // The rear starts only after the front window is removed, including a slow shader warm-up.
            var shown = false
            var told = false
            main.post {
                shown = dev.kb896.aiusage.ui.EarbudsOverlay.show(app, way) {
                    if (shown && !told) {
                        told = true
                        val rearAt = EarbudsMotion.rearStartAt(SystemClock.uptimeMillis())
                        lastStart.set(rearAt)
                        frontActive.set(false)
                        Thread({ tellBack(app, moment, rearAt) }, "earbuds-back").start()
                    }
                }
                if (!shown) { Log.i(TAG, "earbuds: the edge light could not be put on the front"); lastStart.compareAndSet(at, 0L); frontActive.set(false) }
            }
            // The level first (quick), then the picture, off the main thread, both for the back's moment (the front's
            // edge light shows neither); the light starts at once.
            Thread({
                moment.level = level(app, address, heard)
                val id = SonyArt.imageId(name)
                moment.picture = id?.let { SonyArt.picture(app, it) }
            }, "earbuds-art").start()
            // The level asked once more if it had not come (a pair often reports it a moment after connecting), in
            // time for the back.
            main.postAtTime({
                if (shown && moment.level == null) Thread({ moment.level = level(app, address, heard) }, "earbuds-level").start()
            }, at + EarbudsMotion.LEVEL_AGAIN_MS)
            return true
        }

        /** The back's moment: told once, with the start time on the shared clock; the shell decides then whether the back may show it. */
        private fun tellBack(context: Context, m: dev.kb896.aiusage.ui.EarbudsMoment, rearAt: Long) {
            val b = android.os.Bundle().apply {
                putLong("at", rearAt)
                putInt("level", m.level ?: -1)
                putString("name", m.name)
                m.picture?.let { putByteArray("picture", small(it)) }
            }
            val told = runCatching { Graph.of(context).bridge.earbuds(b) }.getOrDefault(false)
            Log.i(TAG, if (told) "earbuds: the back told" else "earbuds: no shell to tell the back")
        }

        /** The picture, down to 360 px across, as PNG bytes (a binder call carries well under its limit). */
        private fun small(b: android.graphics.Bitmap): ByteArray {
            val k = (360f / maxOf(b.width, b.height)).coerceAtMost(1f)
            val s = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
            return java.io.ByteArrayOutputStream().also { s.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}
