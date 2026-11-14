package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Handler
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

/**
 * One layer of the shell's over everything on the back, black or a picture, as strong as it is told: what a pin that
 * goes through Xiaomi's own (PinVia) hides the change-over behind. Black as our page gives way to Xiaomi's arrival;
 * a picture of our notes page as that arrival gives way to ours. On [handler]'s thread.
 */
class ShellVeil(private val layerStack: () -> Int, private val geometry: () -> RearGeometry, private val handler: Handler) {
    private var layer: SurfaceControl? = null
    private var surface: Surface? = null
    private var shows = PinVia.Veil.NONE
    private var alpha = -1f

    /** [kind] at [a]; [picture] draws the notes page when [kind] is that. Called every frame; cheap when nothing changed. */
    fun put(kind: PinVia.Veil, a: Float, picture: (Canvas, Float, Float) -> Unit) {
        if (kind == PinVia.Veil.NONE || a <= 0.002f) { hide(); return }
        val rear = geometry()
        if (layer == null) runCatching {
            val sc = ShellLayer.create("pin-veil", layerStack(), ShellLayer.Z_FRAME, rear.widthPx, rear.heightPx, trusted = false, visible = false, alpha = 0f, format = PixelFormat.RGBA_8888, opaque = false)
            layer = sc; surface = Surface(sc)
        }.onFailure { Log.w(TAG, "veil: no layer: ${it.message}") }
        val sc = layer ?: return
        if (kind != shows) {
            shows = kind
            runCatching {
                val s = surface!!
                val c = runCatching { s.lockHardwareCanvas() }.getOrNull() ?: s.lockCanvas(null)
                try { c.drawColor(0xFF000000.toInt()); if (kind == PinVia.Veil.NOTES) picture(c, rear.widthPx.toFloat(), rear.heightPx.toFloat()) } finally { s.unlockCanvasAndPost(c) }
            }.onFailure { Log.w(TAG, "veil: not drawn: ${it.message}") }
        }
        if (a != alpha) { alpha = a; runCatching { SurfaceControl.Transaction().use { it.setAlpha(sc, a).setVisibility(sc, true).apply() } } }
    }

    fun hide() {
        val sc = layer ?: return
        if (alpha == 0f && shows == PinVia.Veil.NONE) return
        alpha = 0f; shows = PinVia.Veil.NONE
        runCatching { SurfaceControl.Transaction().use { it.setAlpha(sc, 0f).setVisibility(sc, false).apply() } }
    }

    fun close() {
        runCatching { surface?.release() }
        ShellLayer.remove(layer); layer = null; surface = null; alpha = -1f; shows = PinVia.Veil.NONE
    }

    private companion object { const val TAG = "AiUsageRear" }
}
