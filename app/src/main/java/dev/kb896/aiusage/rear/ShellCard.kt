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
    /**
     * Its being IN ITS PLACE has changed ([inPlace]), on [handler]'s thread: told BEFORE its layer moves out of its
     * place (what is under it can be drawn first) and after it has come into it. For HiddenFrames.
     */
    private val onPlace: (Boolean) -> Unit = {},
    /** Its slide's or fade's steps on the vsync, placed by the frame's own time (FramePacer); null: a 14 ms timer, as before. */
    private val pacer: FramePacer? = null,
    private val paint: (android.graphics.Canvas, Float, Float) -> Unit,
) {
    private var layer: SurfaceControl? = null
    private var surface: Surface? = null
    private var at = 0f
    private var goal = 0f
    /** The time (System.nanoTime) its slide or fade last stepped to; 0: not moving. */
    private var movedAt = 0L

    @Volatile var isUp = false
        private set

    /** Up, in its place and staying there, not under a finger: the whole back is this card (HiddenFrames.inPlace). */
    @Volatile var inPlace = false
        private set

    /** On [handler]'s thread: [inPlace] as it is now, told to [onPlace] when it changed. */
    private fun placedNow() {
        val now = HiddenFrames.inPlace(isUp, at, goal, peeking)
        if (now == inPlace) return
        inPlace = now
        runCatching { onPlace(now) }
    }

    /** How many times it has gone from up to away: a page whose picture moves can tell its first frame back by it. */
    @Volatile var hides = 0
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
        // Under the finger it is not in its place: told before it moves, so what is under it is drawn first.
        placedNow()
        unstep()
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

    /** Its picture drawn again NOW, if it is up: ON [handler]'S THREAD ONLY (what was under a page drawn before that page moves). */
    fun drawNow() { if (isUp) draw() }

    /** Away: by its slide or fade, or at once when not [moving]. */
    fun hide(moving: Boolean = true) {
        if (!isUp) return
        isUp = false
        hides++
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
            val c = ShellLayer.lockPanelCanvas(s)
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

    /** A step of its slide or fade: on the pacer's vsync it is placed by that frame's time, so every refresh moves it alike. */
    private val stepper = Runnable { step(pacer?.frameNs ?: System.nanoTime()) }

    private fun unstep() { handler.removeCallbacks(stepper); pacer?.cancel(stepper) }

    private fun step(now: Long = System.nanoTime()) {
        val sc = layer ?: return
        if (movedAt == 0L) movedAt = now
        val d = FramePace.share(movedAt, now, if (slide) ShellPage.SLIDE_MS else FADE_MS)
        movedAt = maxOf(movedAt, now)
        at = if (goal > at) minOf(goal, at + d) else maxOf(goal, at - d)
        // Leaving its place is told before the move, arriving in it after.
        if (inPlace) placedNow()
        place()
        if (!inPlace) placedNow()
        unstep()
        if (at != goal) { if (pacer != null) pacer.post(stepper, 0f) else handler.postDelayed(stepper, 14L) } else movedAt = 0L
    }

    private fun place() {
        val sc = layer ?: return
        runCatching {
            SurfaceControl.Transaction().use {
                if (slide) {
                    val rear = geometry()
                    RearShape.place(it, sc, rear, ShellPage.xAt(ShellPage.Edge.RIGHT, at, rear.widthPx.toFloat()), 0f)
                    it.setAlpha(sc, 1f).setVisibility(sc, at > 0f).apply()
                }
                else it.setAlpha(sc, at).setVisibility(sc, at > 0f).apply()
            }
        }
    }

    fun close() {
        unstep()
        if (isUp) hides++
        isUp = false
        if (inPlace) { inPlace = false; runCatching { onPlace(false) } }
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
