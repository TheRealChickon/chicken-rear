package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

/**
 * THE BACK'S HALF OF THE BEAM (PinBeam): a note pinned on the front comes out on the back as the card that left the
 * front, small and still turning, grows to the whole back and is the notes page. A see-through layer over all the
 * pages for that second: what was showing darkens under the card; when the card has landed [onLanded] puts the
 * notes page in its place under it (the same picture, so nothing jumps), the card's lit edge dies away, and the
 * layer is gone. On [handler]'s thread; every frame from the one clock.
 */
class ShellBeam(
    private val layerStack: () -> Int,
    private val geometry: () -> RearGeometry,
    private val handler: Handler,
    /** The notes page with the new note on it, as it will stand (and, for a demo, its mark). */
    private val page: (Canvas, Float, Float, String?) -> Unit,
    /** The card has landed: the notes page goes under it now. False: no page came (the back is another's, a pop-up has it), and the card fades. */
    private val onLanded: (demo: Boolean) -> Boolean,
) {
    private var layer: SurfaceControl? = null
    private var surface: Surface? = null
    private var face: Bitmap? = null
    private val art = PinBeamArt()
    private var began = 0L
    private var landed = false
    private var stays = true
    private var demo: String? = null
    private var frames = 0
    private var worst = 0L
    private var lastAt = 0L

    /** Playing now. */
    @Volatile var isUp = false
        private set

    // ---- the same arrival through OpenGL with Xiaomi's own card model (rear/PinGl): plumbing, behind a debug word ----

    private var glLayer: SurfaceControl? = null
    private var glSurface: Surface? = null
    private var glHost: PinGl.Host? = null
    private var glScene: PinGl.Scene? = null
    private val glKeys = FloatArray(PinKeys.COUNT)

    /** The arrival drawn with Xiaomi's card model and pictures, a demo only (nothing lands, nothing is kept). False: not to be had here. */
    fun playGl(demoText: String) = handler.post {
        val rear = geometry()
        val assets = XiaomiPin.load() ?: run { Log.i(TAG, "beam: no models of Xiaomi's here — the OpenGL arrival is not shown"); return@post }
        runCatching {
            if (glLayer == null) {
                val sc = ShellLayer.create("pin-beam-gl", layerStack(), ShellLayer.Z_WAKE, rear.widthPx, rear.heightPx, trusted = false, visible = false, alpha = 1f, format = PixelFormat.RGBA_8888, opaque = false)
                glLayer = sc; glSurface = Surface(sc)
            }
            val pic = Bitmap.createBitmap(rear.widthPx, rear.heightPx, Bitmap.Config.ARGB_8888).also { page(Canvas(it), rear.widthPx.toFloat(), rear.heightPx.toFloat(), demoText) }
            val host = PinGl.Host(glSurface!!)
            check(host.make()) { "no OpenGL on the layer" }
            glHost = host
            glScene = PinGl.Scene(assets).also { it.init(pic, pic) }
            pic.recycle()
        }.onFailure { Log.w(TAG, "beam: OpenGL arrival not set up: ${it.message}"); closeGl(); return@post }
        began = SystemClock.uptimeMillis(); frames = 0; worst = 0L; lastAt = 0L
        isUp = true
        Log.i(TAG, "beam: a pinned note arrives (a demo, OpenGL, Xiaomi's card)")
        handler.removeCallbacks(glFrame)
        glFrame.run()
    }

    private val glFrame: Runnable = object : Runnable {
        override fun run() {
            val sc = glLayer ?: return; val scene = glScene ?: return; val host = glHost ?: return
            val rear = geometry()
            val now = SystemClock.uptimeMillis()
            if (lastAt != 0L) worst = maxOf(worst, now - lastAt)
            lastAt = now; frames++
            val t = now - began
            val b = PinBeam.back(minOf(t, PinBeam.ARRIVE_MS) + (t - PinBeam.ARRIVE_MS - DEMO_HOLD_MS).coerceAtLeast(0L))
            val out = if (t >= PinBeam.ARRIVE_MS + DEMO_HOLD_MS) b.glow else 1f
            val w = rear.widthPx.toFloat(); val h = rear.heightPx.toFloat()
            val grown = ((b.scale - 0.06f) / 0.94f).coerceIn(0f, 1f)
            java.util.Arrays.fill(glKeys, 0f)
            // The card alone, as the back has it: across the whole back at scale 1 (its model is 1.9 wide for the card's 950).
            glKeys[PinKeys.CARD_ALPHA] = b.alpha * out
            glKeys[PinKeys.CARD_W] = w; glKeys[PinKeys.CARD_H] = h; glKeys[PinKeys.CARD_SCALE] = b.scale
            glKeys[PinKeys.CARD_ROT_X] = 90f; glKeys[PinKeys.CARD_ROT_Y] = b.turn
            glKeys[PinKeys.CARD_X] = SEEN_CX / 976f * w + (w / 2f - SEEN_CX / 976f * w) * grown; glKeys[PinKeys.CARD_Y] = h / 2f
            glKeys[PinKeys.EDGE] = 0.012f; glKeys[PinKeys.FACE_ALPHA] = 1f; glKeys[PinKeys.GLOW] = b.glow
            runCatching { scene.draw(glKeys, rear.widthPx, rear.heightPx, b.shade * out); host.swap() }.onFailure { Log.w(TAG, "beam: OpenGL frame: ${it.message}") }
            if (frames == 1) runCatching { SurfaceControl.Transaction().use { it.setVisibility(sc, true).apply() } }
            if (t < PinBeam.BACK_MS + DEMO_HOLD_MS) handler.postDelayed(this, FRAME_MS)
            else {
                Log.i(TAG, "beam: landed (OpenGL demo); $frames pictures, $worst ms at most between two")
                isUp = false
                closeGl()
            }
        }
    }

    private fun closeGl() {
        handler.removeCallbacks(glFrame)
        runCatching { glHost?.release() }; glHost = null; glScene = null
        runCatching { glSurface?.release() }
        ShellLayer.remove(glLayer); glLayer = null; glSurface = null
    }

    /** The arrival, from its start. [demoText]: a made-up note (nothing of his), held a little longer for a look. */
    fun play(demoText: String? = null) = handler.post {
        val rear = geometry()
        if (layer == null) {
            runCatching {
                val sc = ShellLayer.create("pin-beam", layerStack(), ShellLayer.Z_WAKE, rear.widthPx, rear.heightPx, trusted = false, visible = false, alpha = 1f, format = PixelFormat.RGBA_8888, opaque = false)
                layer = sc; surface = Surface(sc)
            }.onFailure { Log.w(TAG, "beam: no layer: ${it.message}") }
        }
        if (layer == null) { onLanded(demoText != null); return@post }
        demo = demoText
        face = (face?.takeIf { it.width == rear.widthPx && it.height == rear.heightPx } ?: Bitmap.createBitmap(rear.widthPx, rear.heightPx, Bitmap.Config.ARGB_8888)).also {
            page(Canvas(it), rear.widthPx.toFloat(), rear.heightPx.toFloat(), demoText)
        }
        began = SystemClock.uptimeMillis(); landed = false; stays = true; frames = 0; worst = 0L; lastAt = 0L
        isUp = true
        Log.i(TAG, "beam: a pinned note arrives${if (demoText != null) " (a demo)" else ""}")
        handler.removeCallbacks(frame)
        frame.run()
    }

    private val frame: Runnable = object : Runnable {
        override fun run() {
            val s = surface ?: return
            val sc = layer ?: return
            val rear = geometry()
            val now = SystemClock.uptimeMillis()
            if (lastAt != 0L) worst = maxOf(worst, now - lastAt)
            lastAt = now; frames++
            val hold = if (demo != null) DEMO_HOLD_MS else 0L
            val t = now - began
            val b = PinBeam.back(minOf(t, PinBeam.ARRIVE_MS) + (t - PinBeam.ARRIVE_MS - hold).coerceAtLeast(0L))
            if (!landed && t >= PinBeam.ARRIVE_MS) { landed = true; stays = runCatching { onLanded(demo != null) }.getOrDefault(false) }
            // No page under it (a demo, or the back is not ours to show it on): the card goes out with its light.
            val out = if (landed && !stays) b.glow else 1f
            val w = rear.widthPx.toFloat(); val h = rear.heightPx.toFloat()
            runCatching {
                val c = runCatching { s.lockHardwareCanvas() }.getOrNull() ?: s.lockCanvas(null)
                try {
                    c.drawColor(0, PorterDuff.Mode.CLEAR)
                    c.drawColor(((b.shade * out * 255).toInt().coerceIn(0, 255)) shl 24)
                    // Out of the middle of what he sees (the lenses cover the left), to the middle of the panel as it fills it.
                    val grown = ((b.scale - 0.06f) / 0.94f).coerceIn(0f, 1f)
                    val cx = SEEN_CX / 976f * w + (w / 2f - SEEN_CX / 976f * w) * grown
                    art.card(c, face, cx, h / 2f, w * b.scale, h * b.scale, b.turn, b.alpha * out, 1f, b.glow, 0.012f, b.corner)
                } finally { s.unlockCanvasAndPost(c) }
            }.onFailure { Log.w(TAG, "beam: not drawn: ${it.message}") }
            if (frames == 1) runCatching { SurfaceControl.Transaction().use { it.setVisibility(sc, true).apply() } }
            if (t < PinBeam.BACK_MS + hold) handler.postDelayed(this, FRAME_MS)
            else {
                runCatching { SurfaceControl.Transaction().use { it.setVisibility(sc, false).apply() } }
                isUp = false
                Log.i(TAG, "beam: landed${if (stays) ", the notes page under it" else ", no page"}; $frames pictures, $worst ms at most between two")
            }
        }
    }

    /** The back went dark, or is another's: gone at once. */
    fun cut() = handler.post {
        if (!isUp) return@post
        closeGl()
        handler.removeCallbacks(frame)
        isUp = false
        layer?.let { sc -> runCatching { SurfaceControl.Transaction().use { it.setVisibility(sc, false).apply() } } }
    }

    fun close() {
        closeGl()
        handler.removeCallbacks(frame)
        isUp = false
        runCatching { surface?.release() }
        ShellLayer.remove(layer); layer = null; surface = null
        face = null
    }

    companion object {
        private const val TAG = "AiUsageRear"
        private const val FRAME_MS = 8L
        /** The middle of what he sees of the back, across (the lenses cover the left 296 px of 976). */
        const val SEEN_CX = 636f
        /** A demo's card stays whole this long after landing, for a look, before it goes. */
        const val DEMO_HOLD_MS = 2_200L
    }
}
