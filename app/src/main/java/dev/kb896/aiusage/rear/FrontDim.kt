package dev.kb896.aiusage.rear

import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

/**
 * LYING ON ITS FRONT, THE FRONT IS DIMMED A LOT AND THE BACK IS BRIGHT.
 * The back's part is its light's (RearLight: face down is no longer the dim posture). This is the front's: one black
 * layer of the shell's over the whole front display, [ALPHA] strong, so the front gives [LEFT] of its light. HIS
 * BRIGHTNESS SETTING IS NOT TOUCHED and the front is never slept or locked: when the layer goes, the front is exactly
 * as he had it. A TRUSTED layer, so a touch under it is never held back (the back's own dim layers are untrusted on
 * purpose, and may not be darker than 0.75 for that reason).
 *
 * IT MUST NEVER BE LEFT ON A PHONE HE HAS PICKED UP. So, besides the rule ([wanted]: the app says "lying face down",
 * no call, the rule said so within [STALE_MS]), THE SHELL WATCHES THE PHONE'S OWN TILT while the layer is on: two
 * readings that are no longer face down take it off at once, whatever the app says or however long it is silent
 * (HyperOS freezes the app). Where that sensor is not to be had here, only the rule and its staleness hold, and it is said.
 */
class FrontDim(private val handler: Handler, private val sensors: () -> SensorManager?) {
    private var layer: SurfaceControl? = null
    private var from = 0f
    private var target = 0f
    private var since = 0L
    private var alpha = 0f
    private var fadeMs = IN_MS

    /** On, or going on. */
    @Volatile var isOn = false
        private set
    /** The phone's own tilt says it is no longer on its front (while the layer is on); cleared when the rule next says face down afresh. */
    @Volatile var lifted = false
        private set
    private var upright = 0
    private var listening = false

    private val tilt = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            // Lying on its front the screen looks down: gravity along z is about -9.8. Past LIFT it is on its way up.
            if (liftedBy(e.values[2])) { if (++upright >= 2 && !lifted) { lifted = true; Log.i(TAG, "front dim: the phone is lifted (its own tilt) — off"); set(false, "lifted") } } else upright = 0
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val step = object : Runnable {
        override fun run() {
            val t = ((SystemClock.uptimeMillis() - since).toFloat() / fadeMs).coerceIn(0f, 1f)
            put(from + (target - from) * (t * t * (3f - 2f * t)))
            if (t < 1f) handler.postDelayed(this, 16L) else if (target == 0f) close()
        }
    }

    /** On or off, eased ([IN_MS] in, [OUT_MS] out: quicker, he is picking it up). Any thread; safe to call again and again. */
    fun set(on: Boolean, why: String) = handler.post {
        if (on == isOn) return@post
        isOn = on
        Log.i(TAG, "front dim: ${if (on) "on, the front at ${(LEFT * 100).toInt()}% of its light" else "off"} — $why")
        handler.removeCallbacks(step)
        from = alpha; target = if (on) ALPHA else 0f; since = SystemClock.uptimeMillis(); fadeMs = if (on) IN_MS else OUT_MS
        listen(on)
        step.run()
    }

    /** The rule says face down afresh (it had stopped saying so): the tilt's word is forgotten. */
    fun fresh() { lifted = false; upright = 0 }

    private fun listen(on: Boolean) {
        if (on == listening) return
        val m = runCatching { sensors() }.getOrNull()
        val s = m?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (m == null || s == null) { if (on) Log.i(TAG, "front dim: no tilt to be had here — the rule alone takes it off"); return }
        listening = on
        upright = 0
        runCatching { if (on) m.registerListener(tilt, s, 100_000, handler) else m.unregisterListener(tilt) }
            .onFailure { listening = false; Log.w(TAG, "front dim: tilt: ${it.message}") }
    }

    private fun put(a: Float) {
        if (a > 0f && layer == null) {
            layer = runCatching {
                val sc = ShellLayer.create("front-dim", FRONT_STACK, ShellLayer.Z_DIM, SIDE, SIDE, trusted = true, visible = false, alpha = 0f, format = PixelFormat.RGBA_8888)
                val s = Surface(sc)
                try {
                    val c = s.lockCanvas(null)
                    try { c.drawColor(0xFF000000.toInt()) } finally { s.unlockCanvasAndPost(c) }
                } finally { s.release() }
                sc
            }.onFailure { Log.w(TAG, "front dim: no layer: ${it.message}") }.getOrNull()
        }
        val sc = layer
        alpha = a
        if (sc == null) return
        runCatching { SurfaceControl.Transaction().use { it.setAlpha(sc, a).setVisibility(sc, a > 0.002f).apply() } }
            .onFailure { Log.w(TAG, "front dim: ${it.message}") }
    }

    /** Gone, with its layer (also as the shell ends: nothing of this may outlive it). */
    fun close() {
        handler.removeCallbacks(step)
        listen(false)
        ShellLayer.remove(layer); layer = null; alpha = 0f; target = 0f; isOn = false
    }

    companion object {
        private const val TAG = "AiUsageRear"
        /** How black the layer is, and what that leaves of the front's light. */
        const val ALPHA = 0.88f
        const val LEFT = 1f - ALPHA
        const val IN_MS = 600f
        const val OUT_MS = 180f
        /** The app has not said "face down" again for this long (it says its level every 8 s): the front is not kept dim on its word. */
        const val STALE_MS = 12_000L
        /** Gravity along z (m/s²) above which the phone is no longer lying on its front. */
        const val LIFT = -6.5f
        /** The front display's layer stack, and a square that covers it either way up. */
        private const val FRONT_STACK = 0
        private const val SIDE = 4096

        /** StayOn.Level.DIM's ordinal: lying face down, by the app's rule (the "face down" switch on, no finger on it just now). */
        const val FACE_DOWN_LEVEL = 2

        /**
         * Whether the front is to be dimmed: the app's rule says lying face down ([stay]), it said so within
         * [STALE_MS] ([saidAgoMs]), no call is on (he may turn the phone over in a call and must find its screen), and
         * the phone's own tilt has not said it is lifted. At night the rule has the back off (another level), the
         * Camera is another level: neither is this one. Pure.
         */
        fun wanted(stay: Int, saidAgoMs: Long, call: Boolean, lifted: Boolean): Boolean =
            stay == FACE_DOWN_LEVEL && !call && !lifted && saidAgoMs in 0 until STALE_MS

        /** Whether gravity along z of [z] is a phone no longer on its front. Pure. */
        fun liftedBy(z: Float): Boolean = z > LIFT
    }
}
