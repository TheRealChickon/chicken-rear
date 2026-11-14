package dev.kb896.aiusage.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import android.util.Log

/**
 * The light around the phone, for the back's brightness (rear/RearLight): read here, because the
 * shell that draws the back has no sensors, and told to it (ShizukuBridge.setAmbient).
 *
 * WHICH SENSOR. The 17 Pro has one beside the rear screen (`tcs3760_sec_back`, type
 * [XIAOMI_BACK], the one Xiaomi's own rear brightness goes by) and the usual one on the front.
 * THE REAR ONE IS USED: it sees what the back sees, also with the phone lying face down, when the
 * front one sees the table. The front one is only its stand-in:
 * - when the rear one is COVERED: it reads next to nothing ([COVERED_LUX]) while the front one, in
 *   the same room, reads clearly more ([covered]): a finger of the hand that holds the phone lies
 *   on it. Then half the front's reading is used (the back is turned away from what lights the front);
 * - on a phone with no rear one.
 * With neither, the shell is told there is no reading and uses a lit room's level.
 *
 * COST. Both are read only while [listen] is on, which is while the back is lit, at a slow rate
 * ([PERIOD_US]); they speak only when the light changes; and the shell is told at most twice a
 * second, and only of a change worth telling ([worthTelling]).
 */
class AmbientLight(context: Context, private val handler: Handler, private val tell: (lux: Float, source: Int) -> Unit) {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rear: Sensor? by lazy { findRear(sensors) }
    private val front: Sensor? by lazy { sensors.getDefaultSensor(Sensor.TYPE_LIGHT) }
    private var listening = false
    private var rearLux = Float.NaN
    private var frontLux = Float.NaN
    private var toldLux = Float.NaN
    private var toldSource = -1
    private var toldAt = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (e.sensor.type == Sensor.TYPE_LIGHT) frontLux = e.values[0] else rearLux = e.values[0]
            say()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** On while the back is lit and its brightness follows the light; off otherwise. Safe to call again and again. On [handler]'s thread. */
    fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        if (on) {
            rearLux = Float.NaN; frontLux = Float.NaN; toldSource = -1
            val r = rear?.let { sensors.registerListener(listener, it, PERIOD_US, handler) } ?: false
            val f = front?.let { sensors.registerListener(listener, it, PERIOD_US, handler) } ?: false
            Log.i(TAG, "light: listening (rear sensor ${if (r) "yes" else "no"}, front sensor ${if (f) "yes" else "no"})")
            if (!r && !f) tell(0f, NONE)
        } else {
            runCatching { sensors.unregisterListener(listener) }
        }
    }

    private fun say() {
        val (lux, source) = pick(rearLux, frontLux)
        val now = SystemClock.uptimeMillis()
        if (source == toldSource && (!worthTelling(lux, toldLux) || now - toldAt < 500L)) return
        toldLux = lux; toldSource = source; toldAt = now
        tell(lux, source)
    }

    companion object {
        private const val TAG = "AiUsageStay"
        const val XIAOMI_BACK = "xiaomi.sensor.back_lux"
        const val REAR = 0
        const val FRONT = 1
        const val NONE = 2
        /** Twice a second is plenty for a room's light. */
        const val PERIOD_US = 500_000
        const val COVERED_LUX = 3f

        private val BACK = Regex("(?i)(back|rear|sub).*(lux|light|als)|(lux|light|als).*(back|rear|sub)")

        /** The light sensor beside the rear screen; the one that does not wake the phone, where there are two. */
        fun findRear(sensors: SensorManager): Sensor? {
            val all = sensors.getSensorList(Sensor.TYPE_ALL)
            val backs = all.filter { it.stringType == XIAOMI_BACK }
                .ifEmpty { all.filter { it.type != Sensor.TYPE_LIGHT && isRear(it.stringType ?: "", it.name ?: "") } }
            return backs.firstOrNull { !it.isWakeUpSensor } ?: backs.firstOrNull()
        }

        /** Whether a sensor is a rear light sensor by its names (and not a raw, factory or colour stream of one). Pure. */
        fun isRear(stringType: String, name: String): Boolean {
            if (stringType == XIAOMI_BACK) return true
            val both = "$stringType $name"
            if (Regex("(?i)raw|strm|factory|cct|flicker|prox|fov").containsMatchIn(both)) return false
            return BACK.containsMatchIn(stringType) || BACK.containsMatchIn(name)
        }

        /** The rear sensor reads next to nothing while the front one, in the same room, reads clearly more: something lies on it. Pure. */
        fun covered(rear: Float, front: Float): Boolean = !rear.isNaN() && !front.isNaN() && rear < COVERED_LUX && front > 20f + 10f * rear

        /** The lux to go by and where it is from, of what the two read (NaN: no reading yet, or no such sensor). Pure. */
        fun pick(rear: Float, front: Float): Pair<Float, Int> = when {
            covered(rear, front) -> front * 0.5f to FRONT
            !rear.isNaN() -> rear to REAR
            !front.isNaN() -> front to FRONT
            else -> 0f to NONE
        }

        /** A change of the light worth telling the shell: a tenth, and half a lux. Pure. */
        fun worthTelling(now: Float, told: Float): Boolean =
            told.isNaN() || (kotlin.math.abs(now - told) >= 0.5f && kotlin.math.abs(now - told) >= 0.1f * maxOf(now, told))
    }
}
