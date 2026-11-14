package dev.kb896.aiusage.service

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.kb896.aiusage.rear.RearActivity
import dev.kb896.aiusage.rear.ShizukuBridge
import java.util.concurrent.Executors

/**
 * CAMERA MODE's watch ("Light up while the camera is open", PanelConfig.cameraLight): a camera
 * in use on the phone lights the back at full brightness with its ordinary clock page
 * (RearActivity, CameraMode), and never with a viewfinder.
 *
 * CameraManager's AVAILABILITY CALLBACK, which needs no CAMERA permission: a camera id going
 * unavailable is an app holding it open. Debounced by CameraUse, so face unlock's glance and a
 * camera app switching lenses are neither a start nor an end. In the poll service's process for
 * PocketWatch's reason: the panel's :rear process may be frozen while the back is dark.
 *
 * THE CAMERA'S OWN REAR PREVIEW is left alone: before lighting, the shell is asked whether the
 * rear is the camera's or a guest's (ShizukuBridge.cameraOnRear: the focused app on the rear
 * display, its top layer, the camera this app opened there, the portal's guest). Then the panel
 * is told to stand aside, and the keep-alive — told a camera is in use, setCameraInUse — does
 * not cover it. Asked again every [RECHECK_MS] while the camera is in use: the preview can be
 * switched on and off in the middle of a session.
 *
 * The double tap on the lit back (the panel's, ACTION_CAMERA_SLEEP) switches the back off and
 * keeps it off until the camera closes: [darkForSession] — the shell sleeps the rear, and the
 * bridge declines every other wake meanwhile (ShizukuBridge.cameraDark).
 */
class CameraWatch(private val context: Context, private val bridge: ShizukuBridge) {

    private val cameras = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val handler = Handler(Looper.getMainLooper())
    private val use = CameraUse()

    /** The shell's questions and the wake are binder calls and execs: off the main thread, in order. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "camera").apply { isDaemon = true } }

    private var switchOn = false
    private var registered = false
    private var stopped = false

    /** In a session: the rear was the camera's (or a guest's) at the last look. Worker thread only. */
    private var rearTaken: Boolean? = null

    /** Switched off by the double tap for this session. Read on the worker, written on main and the worker. */
    @Volatile private var dark = false

    private val callback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            use.unavailable(cameraId, SystemClock.uptimeMillis())
            schedule()
        }

        override fun onCameraAvailable(cameraId: String) {
            use.available(cameraId, SystemClock.uptimeMillis())
            schedule()
        }
    }

    /**
     * Registered once, whatever the switch says: the keep-alive's "do not cover the camera"
     * holds with the switch off too. The switch only decides whether the panel lights.
     */
    fun start() {
        if (registered || stopped) return
        val cm = cameras ?: run { Log.w(TAG, "no camera service — no camera mode"); return }
        runCatching { cm.registerAvailabilityCallback(callback, handler) }
            .onSuccess { registered = true; Log.i(TAG, "camera: watching for a camera in use") }
            .onFailure { Log.w(TAG, "camera: availability callback refused: ${it.message}") }
    }

    /** The switch, from the config: read once per write. */
    fun setSwitch(on: Boolean) {
        if (on == switchOn) return
        switchOn = on
        // Switched off mid-session: the panel's own switch ends its part (RearActivity.render);
        // the back is no longer held off for it either.
        if (!on && dark) {
            dark = false
            bridge.cameraDark = false
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        handler.removeCallbacks(settle)
        handler.removeCallbacks(recheck)
        if (registered) runCatching { cameras?.unregisterAvailabilityCallback(callback) }
        registered = false
        if (use.inUse) end()
        use.reset()
        worker.shutdown()
    }

    /** A camera is in use on the phone now, as the availability callback last said (settled, CameraUse). */
    val inUse: Boolean get() = use.inUse

    /** The panel's double tap during camera mode: the back off, and kept off until the camera closes. */
    fun darkForSession() {
        if (!use.inUse) return
        dark = true
        bridge.cameraDark = true
        Log.i(TAG, "camera: double tap — the back off until the camera closes")
        worker.execute { bridge.rearSleep() }
    }

    private fun schedule() {
        handler.removeCallbacks(settle)
        val at = use.dueAt()
        if (at >= 0) handler.postAtTime(settle, at)
    }

    private val settle = Runnable {
        when (use.settle(SystemClock.uptimeMillis())) {
            CameraUse.Edge.START -> begin()
            CameraUse.Edge.END -> end()
            null -> schedule()
        }
    }

    /** A camera use has begun: the shell told, and the back lit for it unless it is not the panel's. */
    private fun begin() {
        if (stopped) return
        Log.i(TAG, "camera: in use")
        dark = false
        bridge.cameraDark = false
        worker.execute {
            bridge.setCameraInUse(true)
            rearTaken = null
            look()
        }
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, RECHECK_MS)
    }

    private fun end() {
        Log.i(TAG, "camera: closed")
        handler.removeCallbacks(recheck)
        val wasDark = dark
        dark = false
        bridge.cameraDark = false
        if (wasDark) Log.i(TAG, "camera: closed — the back may light again")
        worker.execute {
            rearTaken = null
            bridge.setCameraInUse(false)
            tellPanel(RearActivity.CAMERA_END)
        }
    }

    /** The rear looked at again while the camera is in use: the preview may have come or gone. */
    private val recheck: Runnable = object : Runnable {
        override fun run() {
            if (stopped || !use.inUse) return
            worker.execute { look() }
            handler.postDelayed(this, RECHECK_MS)
        }
    }

    /** On the worker: light the back for the camera, or stand aside, as the rear is now. */
    private fun look() {
        if (stopped || !use.inUse) return
        if (!switchOn) return
        if (bridge.isPocketed()) {
            if (rearTaken != true) Log.i(TAG, "camera: in use, but the back is in a pocket — left dark")
            rearTaken = true
            return
        }
        // Not known (no service) counts as free: lighting the back is the switch's point, and
        // the panel itself still refuses under a guest it knows about.
        val taken = bridge.cameraOnRear() == true
        if (taken == rearTaken) return
        rearTaken = taken
        if (taken) {
            Log.i(TAG, "camera: the rear is the camera's (its own preview) or a guest's — the panel stands aside")
            tellPanel(RearActivity.CAMERA_ASIDE)
            return
        }
        if (dark) return
        Log.i(TAG, "camera: lighting the back for it")
        tellPanel(RearActivity.CAMERA_LIT)
        bridge.wake("the camera")
    }

    private fun tellPanel(what: String) {
        context.sendBroadcast(
            Intent(RearActivity.ACTION_CAMERA).setPackage(context.packageName)
                .putExtra(RearActivity.EXTRA_CAMERA, what),
        )
    }

    private companion object {
        const val TAG = "AiUsageCamera"
        /** How often the rear is looked at again while a camera is in use (two cheap dumps in the shell). */
        const val RECHECK_MS = 3_000L
    }
}
