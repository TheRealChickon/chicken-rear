package dev.kb896.aiusage.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The proximity sensor on the BACK of the phone: which one it is, and when it reads covered.
 * Shared by pocket mode's watch (PocketWatch) and the one-shot reading companion mode takes
 * before a wake ([Probe]).
 *
 * Never the front sensor: falling back to it made the panel read "in a pocket" whenever the
 * phone was held or lay on its face. Matched loosely after the 17 Pro's own name, in case
 * another model spells it differently; the wake-up one first.
 */
object RearProximity {

    /** The 17 Pro's own name for it (sic). */
    const val XIAOMI_TYPE = "xiaomi.sensor.proxmity_back"

    private val REAR = Regex("(?i)prox.*(back|rear|sub)|(back|rear|sub).*prox")

    /** Whether a [stringType] / [name] pair is a rear proximity sensor by name. Pure. */
    fun isRear(stringType: String, name: String): Boolean =
        stringType == XIAOMI_TYPE || REAR.containsMatchIn(stringType) || REAR.containsMatchIn(name)

    /** A reading of [value] on a sensor of [maximumRange] is covered: under half its range. Pure. */
    fun covered(value: Float, maximumRange: Float): Boolean = value < maximumRange.coerceAtLeast(1f) * 0.5f

    /** The rear one, the wake-up variant first; null if the phone has none. */
    fun find(sensors: SensorManager): Sensor? {
        val all = sensors.getSensorList(Sensor.TYPE_ALL)
        val backs = all.filter { it.stringType == XIAOMI_TYPE }
            .ifEmpty { all.filter { isRear(it.stringType ?: "", it.name ?: "") } }
        return backs.firstOrNull { it.isWakeUpSensor } ?: backs.firstOrNull()
    }

    /**
     * COMPANION MODE'S POCKET: one fresh reading, asked for just before a wake from the dark.
     *
     * Pocket mode's watch (PocketWatch) reads the sensor only while its switch is on, and
     * companion mode starts with it off, because switching the back off in a pocket acts on
     * Xiaomi's screen. Declining OUR wakes in a pocket acts on nothing of Xiaomi's, so it is
     * kept regardless (design section 5): a notification or the charger never lights the back
     * in a pocket. No sensor is held between wakes; an on-change sensor hands a new listener its
     * current reading at once.
     */
    class Probe(context: Context) {
        private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val sensor: Sensor? by lazy { find(sensors) }
        private val handler by lazy { Handler(HandlerThread("pocket-probe").apply { isDaemon = true; start() }.looper) }
        private val lock = Any()

        /**
         * True covered, false not, null not known (no sensor, or no reading in [timeoutMs]).
         * Blocking for up to [timeoutMs]; its events come on a thread of its own, so the main
         * thread may ask.
         */
        fun covered(timeoutMs: Long = TIMEOUT_MS): Boolean? = synchronized(lock) {
            val s = sensor ?: return null
            val latch = CountDownLatch(1)
            var reading: Boolean? = null
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    if (latch.count == 0L) return
                    reading = covered(e.values[0], e.sensor.maximumRange)
                    latch.countDown()
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            return try {
                if (!sensors.registerListener(listener, s, SensorManager.SENSOR_DELAY_FASTEST, handler)) return null
                if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) reading
                else { Log.i(TAG, "pocket probe: no reading in $timeoutMs ms"); null }
            } catch (_: InterruptedException) {
                null
            } finally {
                runCatching { sensors.unregisterListener(listener) }
            }
        }
    }

    private const val TAG = "AiUsagePocket"
    /** Long enough for an on-change sensor's first event; short enough to hold a wake no longer than that. */
    const val TIMEOUT_MS = 250L
}
