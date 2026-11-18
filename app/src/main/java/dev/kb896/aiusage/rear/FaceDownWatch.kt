package dev.kb896.aiusage.rear

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import dev.kb896.aiusage.circle.FrontTouchDevice
import dev.kb896.aiusage.circle.FrontTouches
import dev.kb896.aiusage.service.FrontScreen
import dev.kb896.aiusage.service.RearProximity
import kotlin.concurrent.thread

/** Shell-owned so lifting takes effect even while HyperOS has frozen the app. All decisions run on [handler]. */
internal class FaceDownWatch(
    private val handler: Handler,
    private val sensors: () -> SensorManager?,
    private val frontState: () -> Int?,
    private val changed: () -> Unit,
) : SensorEventListener {
    private val posture = FaceDown()
    @Volatile var down = false
        private set

    private var manager: SensorManager? = null
    private var proximity: Sensor? = null
    private var accelerometer: Sensor? = null
    private var proximityOn = false
    private var near = false
    private var screenOn = false
    private var touch: TouchReader? = null
    private var touchReady = false
    private var fingers = 0
    private var frontUsedAt = -FRONT_QUIET_MS
    private var retryTouchAt = 0L

    private val until = LongArray(2)
    private val expire = Runnable { updateListening() }

    fun request(client: Int, on: Boolean) {
        if (client !in until.indices) return
        handler.post {
            val leaseMs = if (client == TABLE) TABLE_LEASE_MS else STAY_LEASE_MS
            until[client] = if (on) SystemClock.elapsedRealtime() + leaseMs else 0L
            updateListening()
        }
    }

    private fun updateListening() {
        val now = SystemClock.elapsedRealtime()
        handler.removeCallbacks(expire)
        until.filter { it > now }.minOrNull()?.let { handler.postDelayed(expire, it - now) }
        if (until.none { it > now }) {
            manager?.unregisterListener(this)
            accelerometer = null
            proximityOn = false
            near = false
            stopTouch()
            publish(posture.conditions(near = false, frontActive = false, now = now))
        } else if (accelerometer == null) {
            val m = runCatching { sensors() }.getOrNull() ?: return
            val s = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            manager = m
            proximity = sequenceOf(false, true).mapNotNull { m.getDefaultSensor(Sensor.TYPE_PROXIMITY, it) }
                .firstOrNull { !RearProximity.isRear(it.stringType.orEmpty(), it.name.orEmpty()) }
            if (runCatching { m.registerListener(this, s, 50_000, handler) }.getOrDefault(false)) accelerometer = s
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (until.none { it > now }) {
            updateListening()
            return
        }
        if (event.sensor == proximity && proximityOn) {
            near = FaceDown.near(event.values[0], event.sensor.maximumRange)
            publish(posture.conditions(near, frontActive = false, now = now))
            return
        }
        if (event.sensor != accelerometer) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val state = frontState()
        val lit = state != null && FrontScreen.isOn(state)
        if (lit && !screenOn) frontUsedAt = now
        screenOn = lit
        if (lit) {
            if (touch == null && now >= retryTouchAt) touch = TouchReader().also { it.start() }
        } else if (touch != null) {
            if (fingers != 0) frontUsedAt = now
            stopTouch()
        }
        val active = state == null || now - frontUsedAt < FRONT_QUIET_MS || (lit && (!touchReady || fingers != 0))
        publish(posture.reading(x, y, z, now, near, active))
        listenProximity(down || FaceDown.flat(x, y, z))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun listenProximity(on: Boolean) {
        if (on == proximityOn) return
        val m = manager ?: return
        val p = proximity ?: return
        if (on) {
            proximityOn = runCatching { m.registerListener(this, p, SensorManager.SENSOR_DELAY_NORMAL, handler) }.getOrDefault(false)
        } else {
            m.unregisterListener(this, p)
            proximityOn = false
            near = false
        }
    }

    private fun publish(edge: Boolean?) {
        if (edge == null) return
        down = edge
        changed()
    }

    private fun stopTouch() {
        touch?.close()
        touch = null
        fingers = 0
        touchReady = false
        screenOn = false
    }

    /** Read only contact edges from the same front input device used by Circle to Search. */
    private inner class TouchReader {
        private var process: Process? = null
        private var closed = false

        fun start() {
            thread(name = "face-down front touch", isDaemon = true) {
                var child: Process? = null
                try {
                    val path = FrontTouchDevice.find() ?: return@thread
                    synchronized(this) {
                        if (closed) return@thread
                        child = LogWatch.startFrontTouch(path)
                        process = child
                    }
                    handler.post {
                        if (touch === this) {
                            touchReady = true
                            frontUsedAt = SystemClock.elapsedRealtime()
                        }
                    }
                    val input = FrontTouches(
                        down = { slot, _, _ -> contact(slot, true) },
                        leave = { slot, moved, _ -> if (!moved) contact(slot, false) },
                    )
                    child?.inputStream?.bufferedReader()?.useLines { lines -> lines.forEach(input::read) }
                } catch (_: Exception) {
                    // Without a readable touch source we cannot qualify a lit front as idle.
                } finally {
                    LogWatch.stop(child)
                    handler.post {
                        if (touch === this) {
                            stopTouch()
                            retryTouchAt = SystemClock.elapsedRealtime() + 5_000L
                            publish(posture.conditions(near, frontActive = true, now = SystemClock.elapsedRealtime()))
                        }
                    }
                }
            }
        }

        private fun contact(slot: Int, pressed: Boolean) {
            handler.post {
                if (touch !== this) return@post
                val bit = 1 shl slot
                fingers = if (pressed) fingers or bit else fingers and bit.inv()
                frontUsedAt = SystemClock.elapsedRealtime()
                publish(posture.conditions(near, frontActive = true, now = frontUsedAt))
            }
        }

        @Synchronized fun close() {
            closed = true
            LogWatch.stop(process)
        }
    }

    companion object {
        const val STAY = 0
        const val TABLE = 1
        private const val FRONT_QUIET_MS = 3_000L
        // The table watch can batch its idle acceleration for 30 s. Always on renews every 2 s.
        private const val TABLE_LEASE_MS = 40_000L
        private const val STAY_LEASE_MS = 14_000L
    }
}
