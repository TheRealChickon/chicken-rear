package dev.kb896.aiusage.rear

import android.graphics.PixelFormat
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

/**
 * The back dimmed by hand: one black layer over everything of ours there, shown at an alpha.
 *
 * A double tap on a bright back dims it, on a dim one brightens it; a tap
 * brightens it for a while. The scene has its own gain in its shader (the posture's level), but the
 * pages over it (music, call, timer, usage, notes, a message) have none, so the dim by hand is this
 * layer over all of them: what moves under it keeps moving.
 *
 * Black at alpha a lets (1 - a) of the light through: the panel is an OLED, so this is the light
 * itself, not a filter over a backlight. The alpha is never above [MAX_ALPHA]: Android stops a
 * touch from reaching an app's window under an untrusted layer more opaque than 0.8, and an app of
 * his on the back must never lose its touch to this.
 *
 * It fades ([FADE_MS]) on [handler], and is made the first time it is wanted.
 */
class RearDim(private val layerStack: () -> Int, private val size: () -> Pair<Int, Int>, private val handler: Handler) {
    private var layer: SurfaceControl? = null
    private var from = 0f
    private var target = 0f
    private var since = 0L
    @Volatile var alpha = 0f
        private set

    private val step = object : Runnable {
        override fun run() {
            val t = ((SystemClock.uptimeMillis() - since).toFloat() / FADE_MS).coerceIn(0f, 1f)
            put(from + (target - from) * (t * t * (3f - 2f * t)))
            if (t < 1f) handler.postDelayed(this, 16L)
        }
    }

    /** To [to] (0 for away), over [FADE_MS]; at once when [now]. Any thread; safe to call again and again. */
    fun set(to: Float, now: Boolean = false) {
        val want = to.coerceIn(0f, MAX_ALPHA)
        handler.post {
            if (want == target && (!now || alpha == want)) return@post
            handler.removeCallbacks(step)
            from = alpha; target = want; since = SystemClock.uptimeMillis()
            if (now) put(want) else step.run()
        }
    }

    private fun put(a: Float) {
        if (a > 0f && layer == null) {
            val (w, h) = size()
            // A BLACK COLOUR LAYER, NO BUFFER (ShellLayer.createColour): the same black at the same alpha over the same
            // w x h, without a 2.3 MB picture read at every refresh while it is up (nearly always while lit indoors).
            // Where that cannot be had, the black buffer of the display's size it always was.
            layer = runCatching {
                ShellLayer.createColour("dim", layerStack(), ShellLayer.Z_DIM, w, h, trusted = false, visible = false, alpha = 0f)
            }.recoverCatching { e ->
                if (e is ShellLayer.Untrusted) throw e
                Log.i(TAG, "dim: no colour layer (${e.message}); a black buffer ${w}x$h")
                bufferLayer(w, h)
            }.onFailure { Log.w(TAG, "dim: no layer: ${it.message}") }.getOrNull()
        }
        val sc = layer
        alpha = a
        if (sc == null) return
        runCatching { SurfaceControl.Transaction().use { it.setAlpha(sc, a).setVisibility(sc, a > 0.002f).apply() } }
            .onFailure { Log.w(TAG, "dim: ${it.message}") }
    }

    /** The way it was made before colour layers: a black picture of the display's size, drawn once. */
    private fun bufferLayer(w: Int, h: Int): SurfaceControl {
        val sc = ShellLayer.create(
            "dim", layerStack(), ShellLayer.Z_DIM, w, h,
            trusted = false, visible = false, alpha = 0f, format = PixelFormat.RGBA_8888,
        )
        // A layer with no buffer shows nothing: one black picture, once.
        try {
            val s = Surface(sc)
            try {
                val c = ShellLayer.lockPanelCanvas(s)
                try { c.drawColor(0xFF000000.toInt()) } finally { s.unlockCanvasAndPost(c) }
            } finally { s.release() }
        } catch (e: Throwable) { ShellLayer.remove(sc); throw e }
        return sc
    }

    fun close() {
        handler.removeCallbacks(step)
        ShellLayer.remove(layer); layer = null; alpha = 0f; target = 0f
    }

    companion object {
        private const val TAG = "AiUsageRear"
        const val FADE_MS = 220f
        const val MAX_ALPHA = 0.75f

        /**
         * The layer's alpha for a back that should give [want] of its light when what is under it
         * already gives [has] (the scene's own gain): nothing when it is as dim as that already.
         */
        fun alphaFor(want: Float, has: Float): Float =
            if (has <= want || has <= 0f) 0f else (1f - want / has).coerceIn(0f, MAX_ALPHA)
    }
}
