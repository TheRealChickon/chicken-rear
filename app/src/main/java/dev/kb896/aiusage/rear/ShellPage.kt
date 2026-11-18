package dev.kb896.aiusage.rear

import android.graphics.PixelFormat
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

/**
 * A page of the back other than the scene: the Claude usage page, the pinned notes. A shell layer
 * of its own over the scene, opaque, so the scene is not under it but gone while it shows, and
 * back the moment it leaves.
 *
 * NOT TRUSTED, as the scene's layer is not: while it is up no touch reaches Xiaomi's screen
 * under everything. The shell's own reading of the touchscreen brings it in and sends it away
 * (SceneTouch).
 *
 * It slides in from the edge it belongs to ([from]: the usage page from the right, the notes from
 * the bottom) and out to it, on [handler]'s thread; drawn again when [changed] says so and every
 * twenty seconds for the times on it. It leaves by itself after [stayMs] untouched, and at once
 * when the back goes dark: the scene is the face people see.
 */
class ShellPage(
    private val name: String,
    private val from: Edge,
    private val layerStack: () -> Int,
    private val geometry: () -> RearGeometry,
    private val handler: Handler,
    private val paint: (android.graphics.Canvas, Float, Float) -> Unit,
    private val onShown: () -> Unit = {},
    private val stayMs: Long = STAY_MS,
    /** Left alone for its time: asked to go (the navigation decides and hides it: RearNav). Null: it hides itself. */
    private val onAlone: (() -> Unit)? = null,
    /**
     * Its being IN ITS PLACE has changed ([inPlace]), on [handler]'s thread: told BEFORE its layer moves out of its
     * place (what is under it can be drawn first) and after it has come into it. For HiddenFrames.
     */
    private val onPlace: (Boolean) -> Unit = {},
    /** Its slide's steps on the vsync, placed by the frame's own time (FramePacer); null: a 12 ms timer, as before. */
    private val pacer: FramePacer? = null,
) {
    enum class Edge { RIGHT, BOTTOM }

    @Volatile private var layer: SurfaceControl? = null
    private var surface: Surface? = null
    /** 0 off the right edge, 1 in place. Only [handler]'s thread moves it. */
    private var at = 0f
    private var goal = 0f
    /** The time (System.nanoTime) its slide last stepped to; 0: not moving. */
    private var movedAt = 0L
    @Volatile private var touchedAt = 0L

    /** On the back, or on its way in. */
    @Volatile var isUp = false
        private set

    /** Up, in its place and staying there, not under a finger: the whole back is this page (HiddenFrames.inPlace). */
    @Volatile var inPlace = false
        private set

    /** On [handler]'s thread: [inPlace] as it is now, told to [onPlace] when it changed. */
    private fun placedNow() {
        val now = HiddenFrames.inPlace(isUp, at, goal, peeking)
        if (now == inPlace) return
        inPlace = now
        runCatching { onPlace(now) }
    }

    /** In, to stay [forMs] untouched (the page's own time when 0). Already up: drawn again, and its time starts over. [atOnce]: no slide. */
    fun show(forMs: Long = 0L, atOnce: Boolean = false) = handler.post {
        stayFor = if (forMs > 0L) forMs else stayMs
        touchedAt = SystemClock.uptimeMillis()
        if (isUp) { draw(); return@post }
        if (!ensure()) return@post
        isUp = true
        peeking = false
        goal = 1f
        draw()
        runCatching { onShown() }
        handler.removeCallbacks(minute); handler.postDelayed(minute, minOf(MINUTE_MS, stayFor))
        // IN ITS PLACE AT ONCE (a pinned note's card has just grown into exactly this picture: PinBeam), or by its slide.
        if (atOnce) { unstep(); at = 1f; movedAt = 0L; place(); placedNow() } else step()
    }

    fun hide(why: String) = handler.post {
        if (!isUp) return@post
        Log.i(TAG, "$name: away — $why")
        isUp = false
        peeking = false
        goal = 0f
        handler.removeCallbacks(minute)
        // Out of its place before it moves: what is under it is drawn first.
        placedNow()
        step()
    }

    private var peeking = false

    /**
     * UNDER A FINGER (PageDrag): put where the finger has it, [inPart] of its way in (0 off its edge, 1 in place).
     * A page that is not up is drawn and shown for this, and is still not up: [show] then goes on from here, and
     * [unpeek] takes it back. One that is up is moved, and [hide] goes on from here.
     */
    fun peek(inPart: Float) = handler.post {
        if (!ensure()) return@post
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

    /** A finger on it: it stays a while longer. Any thread. */
    fun touched() { touchedAt = SystemClock.uptimeMillis() }

    /** What is on it has changed. Any thread. */
    fun changed() = handler.post { if (drawnWhenChanged(isUp, peeking)) draw() }

    private var stayFor = stayMs

    private val minute: Runnable = object : Runnable {
        override fun run() {
            if (!isUp) return
            if (SystemClock.uptimeMillis() - touchedAt >= stayFor) {
                val ask = onAlone
                if (ask == null) { hide("left alone"); return }
                // Asked once; if it is still up at the next look, asked again.
                runCatching { ask() }
                handler.postDelayed(this, MINUTE_MS)
                return
            }
            draw()
            handler.postDelayed(this, minOf(MINUTE_MS, stayFor))
        }
    }

    private fun ensure(): Boolean {
        if (layer != null) return true
        val rear = geometry()
        return runCatching {
            val sc = ShellLayer.create(
                name, layerStack(), ShellLayer.Z_USAGE, rear.widthPx, rear.heightPx,
                trusted = false, visible = false, alpha = 1f, format = PixelFormat.RGBA_8888, opaque = true,
                corner = ShellLayer.pageCorner(rear),
            )
            layer = sc; surface = Surface(sc)
            true
        }.onFailure { Log.w(TAG, "$name: no layer: ${it.message}") }.getOrDefault(false)
    }

    private fun draw() {
        val s = surface ?: return
        val rear = geometry()
        runCatching {
            val c = runCatching { s.lockHardwareCanvas() }.getOrNull() ?: s.lockCanvas(null)
            try { paint(c, rear.widthPx.toFloat(), rear.heightPx.toFloat()) } finally { s.unlockCanvasAndPost(c) }
        }.onFailure { Log.w(TAG, "$name: not drawn: ${it.message}") }
    }

    /** A step of its slide: on the pacer's vsync it is placed by that frame's time, so every refresh moves it alike. */
    private val stepper = Runnable { step(pacer?.frameNs ?: System.nanoTime()) }

    private fun unstep() { handler.removeCallbacks(stepper); pacer?.cancel(stepper) }

    private fun step(now: Long = System.nanoTime()) {
        val sc = layer ?: return
        if (movedAt == 0L) movedAt = now
        val d = FramePace.share(movedAt, now, SLIDE_MS)
        movedAt = maxOf(movedAt, now)
        at = if (goal > at) minOf(goal, at + d) else maxOf(goal, at - d)
        // Leaving its place is told before the move, arriving in it after.
        if (inPlace) placedNow()
        place()
        if (!inPlace) placedNow()
        if (at != goal) { if (pacer != null) pacer.post(stepper, 0f) else handler.postDelayed(stepper, FRAME_MS) } else movedAt = 0L
    }

    /** The layer where [at] says. Eased: quick off the edge, settling into place. */
    private fun place() {
        val sc = layer ?: return
        val rear = geometry()
        runCatching {
            SurfaceControl.Transaction().use {
                RearShape.place(it, sc, rear, xAt(from, at, rear.widthPx.toFloat()), yAt(from, at, rear.heightPx.toFloat()))
                it.setVisibility(sc, at > 0f).apply()
            }
        }
    }

    fun close() {
        handler.removeCallbacks(minute); unstep()
        isUp = false
        if (inPlace) { inPlace = false; runCatching { onPlace(false) } }
        runCatching { surface?.release() }
        ShellLayer.remove(layer); layer = null; surface = null
    }

    companion object {
        private const val TAG = "AiUsageRear"
        /** How long a page takes from its edge into place, and its easing: the live pages slide by the same (ShellCard). */
        const val SLIDE_MS = 260f
        /** [at] 0 (off its edge) to 1 (in place), eased: quick off the edge, settling into place. */
        fun ease(at: Float): Float { val t = at.coerceIn(0f, 1f); return 1f - (1f - t) * (1f - t) * (1f - t) }
        /** Where a page that comes [from] its edge stands at [at] (0 off it, 1 in place) on a back [w] wide, [h] high. */
        fun xAt(from: Edge, at: Float, w: Float): Float = if (from == Edge.RIGHT) (1f - ease(at)) * w else 0f
        fun yAt(from: Edge, at: Float, h: Float): Float = if (from == Edge.BOTTOM) (1f - ease(at)) * h else 0f
        /**
         * Whether a page is drawn again when what is on it changes: up, or UNDER A FINGER on its way in ([peek] draws it
         * once as the finger takes it, and it is not up yet). A note's picture read while the notes page is pulled up
         * comes onto it under the finger, not only when the finger lets go. Pure.
         */
        fun drawnWhenChanged(isUp: Boolean, peeking: Boolean): Boolean = isUp || peeking
        private const val FRAME_MS = 12L
        private const val MINUTE_MS = 20_000L
        /** Left alone this long, a page gives the back to the scene again. */
        const val STAY_MS = 45_000L
    }
}
