package dev.kb896.aiusage.rear

import android.graphics.PixelFormat
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

/**
 * A card over the scene: a clear shell layer the size of the back with something drawn on a part
 * of it, over the scene and under the pages' place in time (the
 * shell takes it away while a page is up). It comes and goes by its light. Trusted: it stops no
 * touch itself, and none reaches Xiaomi's screen anyway (the cover is under everything); its taps
 * are read from the raw touchscreen like every other (SceneTouch).
 *
 * [opaque]: not a card but the whole back, in the scene's stead (what is playing: MusicFace), at
 * its own depth [z]; not trusted then, as the scene is not.
 *
 * [slide]: a page in the row of pages (home | the live page | usage): it comes in from the RIGHT edge and leaves
 * to it, by the very slide the notes and the usage page have (ShellPage.SLIDE_MS, ShellPage.ease), and does not
 * fade. (It faded in where it stood.)
 */
class ShellCard(
    private val name: String,
    private val layerStack: () -> Int,
    private val geometry: () -> RearGeometry,
    private val handler: Handler,
    private val z: Int = ShellLayer.Z_CARD,
    private val opaque: Boolean = false,
    private val slide: Boolean = false,
    private val paint: (android.graphics.Canvas, Float, Float) -> Unit,
) {
    private var layer: SurfaceControl? = null
    private var surface: Surface? = null
    private var at = 0f
    private var goal = 0f
    private var movedAt = 0L

    @Volatile var isUp = false
        private set

    /**
     * Up (drawn again when [redraw], or when it was not up). Any thread. [moving] false: in its place at once, no
     * slide and no fade (it is under something that is leaving, or the back has just lit: RearNav's rule that what
     * is under a page is already there).
     */
    fun show(redraw: Boolean = false, moving: Boolean = true) {
        val was = isUp
        isUp = true
        handler.post {
            if (!ensure()) { isUp = false; return@post }
            if (redraw || !was) draw()
            // Under a finger it stays where the finger has it; a look at what is live does not move it.
            if (peeking && was) return@post
            peeking = false
            goal = 1f
            // In its place at once only as it COMES up: a card that is up (and may be on its way in) is not jumped by a later look.
            if (!moving && !was) at = 1f
            step()
        }
    }

    private var peeking = false

    /** UNDER A FINGER (PageDrag), as ShellPage.peek: put [inPart] of its way in from the right edge; its being up or not does not change. */
    fun peek(inPart: Float) = handler.post {
        if (!slide || !ensure()) return@post
        if (!isUp && !peeking) draw()
        peeking = true
        handler.removeCallbacks(stepper)
        movedAt = 0L
        at = PageDrag.unease(inPart)
        place()
    }

    /** Let go without turning: back to where it belongs, by its slide. */
    fun unpeek() = handler.post {
        if (!peeking) return@post
        peeking = false
        goal = if (isUp) 1f else 0f
        step()
    }

    /**
     * Its picture drawn again, IF IT IS UP: for the pages' frame ticks, which must never bring a card up (a tick
     * that had looked a moment before a hide used to call show, and undid the hide).
     */
    fun redraw() { if (isUp) handler.post { if (isUp) draw() } }

    /** Away: by its slide or fade, or at once when not [moving]. */
    fun hide(moving: Boolean = true) {
        if (!isUp) return
        isUp = false
        handler.post { peeking = false; goal = 0f; if (!moving) at = 0f; step() }
    }

    private fun ensure(): Boolean {
        if (layer != null) return true
        val rear = geometry()
        return runCatching {
            val sc = ShellLayer.create(name, layerStack(), z, rear.widthPx, rear.heightPx, trusted = !opaque, visible = false, alpha = 0f, format = PixelFormat.RGBA_8888, opaque = false,
                // A whole page (not a small card over the scene) has the display's corners, as every page has.
                corner = if (opaque || slide) ShellLayer.pageCorner(rear) else 0f)
            layer = sc; surface = Surface(sc)
            true
        }.onFailure { Log.w(TAG, "$name: no layer: ${it.message}") }.getOrDefault(false)
    }

    // How long its pictures take and how far apart they come, said in the log every [REPORT] of them:
    // the measure of whether a moving page is smooth on the phone itself.
    private var frames = 0
    private var drawSum = 0L
    private var drawMax = 0L
    private var gapMax = 0L
    private var late = 0
    private var lastAt = 0L

    private fun draw() {
        val s = surface ?: return
        val rear = geometry()
        val t0 = SystemClock.elapsedRealtimeNanos()
        runCatching {
            val c = runCatching { s.lockHardwareCanvas() }.getOrNull() ?: s.lockCanvas(null)
            try { paint(c, rear.widthPx.toFloat(), rear.heightPx.toFloat()) } finally { s.unlockCanvasAndPost(c) }
        }.onFailure { Log.w(TAG, "$name: not drawn: ${it.message}") }
        val t1 = SystemClock.elapsedRealtimeNanos()
        val took = (t1 - t0) / 1_000L
        val gap = if (lastAt == 0L || t0 - lastAt > 500_000_000L) 0L else (t0 - lastAt) / 1_000L
        lastAt = t0
        frames++; drawSum += took; drawMax = maxOf(drawMax, took); gapMax = maxOf(gapMax, gap)
        if (gap > LATE_US) late++
        if (frames >= REPORT) {
            Log.i(TAG, "$name: $frames pictures, drawn in ${drawSum / frames / 1000f} ms on average, ${drawMax / 1000f} ms at most; longest wait between two ${gapMax / 1000f} ms, $late over ${LATE_US / 1000} ms")
            frames = 0; drawSum = 0L; drawMax = 0L; gapMax = 0L; late = 0
        }
    }

    private val stepper = Runnable { step() }

    private fun step() {
        val sc = layer ?: return
        val now = SystemClock.uptimeMillis()
        if (movedAt == 0L) movedAt = now
        val d = (now - movedAt).toFloat() / (if (slide) ShellPage.SLIDE_MS else FADE_MS)
        movedAt = now
        at = if (goal > at) minOf(goal, at + d) else maxOf(goal, at - d)
        place()
        handler.removeCallbacks(stepper)
        if (at != goal) handler.postDelayed(stepper, 14L) else movedAt = 0L
    }

    private fun place() {
        val sc = layer ?: return
        runCatching {
            SurfaceControl.Transaction().use {
                if (slide) {
                    val rear = geometry()
                    ShellLayer.round(it, sc, rear, moving = at < 1f)
                    it.setPosition(sc, (1f - ShellPage.ease(at)) * rear.widthPx, 0f).setAlpha(sc, 1f).setVisibility(sc, at > 0f).apply()
                }
                else it.setAlpha(sc, at).setVisibility(sc, at > 0f).apply()
            }
        }
    }

    fun close() {
        handler.removeCallbacks(stepper)
        isUp = false
        runCatching { surface?.release() }
        ShellLayer.remove(layer); layer = null; surface = null
    }

    private companion object {
        const val TAG = "AiUsageRear"
        const val FADE_MS = 240f
        const val REPORT = 150
        /** A wait between two pictures longer than this shows as a stutter (the pages ask for one every 33 ms). */
        const val LATE_US = 50_000L
    }
}
