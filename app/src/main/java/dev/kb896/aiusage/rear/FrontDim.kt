package dev.kb896.aiusage.rear

import android.graphics.PixelFormat
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
 * The shell's shared FaceDownWatch clears this layer on a lift even if the app is frozen.
 */
class FrontDim(private val handler: Handler) {
    private var layer: SurfaceControl? = null
    private var from = 0f
    private var target = 0f
    private var since = 0L
    private var alpha = 0f
    private var fadeMs = IN_MS

    /** On, or going on. */
    @Volatile var isOn = false
        private set
    private val step = object : Runnable {
        override fun run() {
            val t = ((SystemClock.uptimeMillis() - since).toFloat() / fadeMs).coerceIn(0f, 1f)
            put(from + (target - from) * (t * t * (3f - 2f * t)))
            if (t < 1f) handler.postDelayed(this, 16L) else if (target == 0f) close()
        }
    }

    /** On or off, eased ([IN_MS] in, [OUT_MS] out: quicker when the phone is lifted). Called on the layer handler. */
    fun set(on: Boolean, why: String) {
        if (on == isOn) return
        isOn = on
        Log.i(TAG, "front dim: ${if (on) "on, the front at ${(LEFT * 100).toInt()}% of its light" else "off"} — $why")
        handler.removeCallbacks(step)
        from = alpha; target = if (on) ALPHA else 0f; since = SystemClock.uptimeMillis(); fadeMs = if (on) IN_MS else OUT_MS
        step.run()
    }

    private fun put(a: Float) {
        if (a > 0f && layer == null) {
            // ONE SQUARE OF THE FRONT'S LONGEST SIDE covers it either way up (it was 4096 x 4096: a 64 MiB picture filled
            // by the CPU at every face down). Best of all A BLACK COLOUR LAYER, NO BUFFER (ShellLayer.createColour), which
            // costs no memory whatever its size: there an unreadable size is the old square, cropped, not drawn.
            val side = frontSide()
            layer = runCatching {
                ShellLayer.createColour("front-dim", FRONT_STACK, ShellLayer.Z_DIM, side ?: SIDE, side ?: SIDE, trusted = true, visible = false, alpha = 0f)
            }.recoverCatching { e ->
                // Not trusted is never shown, whatever it is drawn with (fail closed); and never a 4096 x 4096 buffer again.
                if (e is ShellLayer.Untrusted || side == null) throw e
                Log.i(TAG, "front dim: no colour layer (${e.message}); a black buffer ${side}x$side")
                bufferLayer(side)
            }.onFailure { Log.w(TAG, "front dim: no layer: ${it.message}${if (side == null) " (the front's size could not be read)" else ""}") }.getOrNull()
        }
        val sc = layer
        alpha = a
        if (sc == null) return
        runCatching { SurfaceControl.Transaction().use { it.setAlpha(sc, a).setVisibility(sc, a > 0.002f).apply() } }
            .onFailure { Log.w(TAG, "front dim: ${it.message}") }
    }

    /** The way it was made before colour layers: a black picture, [side] square, drawn once. */
    private fun bufferLayer(side: Int): SurfaceControl {
        val sc = ShellLayer.create("front-dim", FRONT_STACK, ShellLayer.Z_DIM, side, side, trusted = true, visible = false, alpha = 0f, format = PixelFormat.RGBA_8888)
        try {
            val s = Surface(sc)
            try {
                val c = s.lockCanvas(null)
                try { c.drawColor(0xFF000000.toInt()) } finally { s.unlockCanvasAndPost(c) }
            } finally { s.release() }
        } catch (e: Throwable) { ShellLayer.remove(sc); throw e }
        return sc
    }

    /** Gone, with its layer (also as the shell ends: nothing of this may outlive it). */
    fun close() {
        handler.removeCallbacks(step)
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
        /** The front display (its id and its layer stack). */
        private const val FRONT_STACK = 0
        /** Only the crop of a colour layer (no memory) when the front's size cannot be read; never a buffer's size. */
        private const val SIDE = 4096

        /**
         * The side of a square that covers a display of [width] x [height] either way up and is no larger than it needs
         * to be: its longest side. Null for a size that is not one. Pure.
         */
        fun coverSide(width: Int, height: Int): Int? = if (width > 0 && height > 0) maxOf(width, height) else null

        /**
         * The front display's logical size as the display manager has it (IDisplayManager.getDisplayInfo, straight to the
         * service as RearPanel does: no listener is registered in this process), as [coverSide]; null where it cannot be read.
         */
        private fun frontSide(): Int? = runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "display")
            val dm = Class.forName("android.hardware.display.IDisplayManager\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            val info = Class.forName("android.hardware.display.IDisplayManager")
                .getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(dm, FRONT_STACK) ?: return@runCatching null
            coverSide(info.javaClass.getField("logicalWidth").getInt(info), info.javaClass.getField("logicalHeight").getInt(info))
        }.onFailure { Log.w(TAG, "front dim: the front's size: ${it.message}") }.getOrNull()

        /** StayOn.Level.DIM's ordinal: lying face down, by the app's rule (the "face down" switch on, no finger on it just now). */
        const val FACE_DOWN_LEVEL = 2

        /**
         * Whether the front is to be dimmed: the app's rule says lying face down ([stay]), it said so within
         * [STALE_MS] ([saidAgoMs]), no call is on (he may turn the phone over in a call and must find its screen), and
         * the shell has independently confirmed the shared resting posture. At night the rule has the back off (another level), the
         * Camera is another level: neither is this one. Pure.
         */
        fun wanted(stay: Int, saidAgoMs: Long, call: Boolean, resting: Boolean): Boolean =
            stay == FACE_DOWN_LEVEL && !call && resting && saidAgoMs in 0 until STALE_MS

    }
}
