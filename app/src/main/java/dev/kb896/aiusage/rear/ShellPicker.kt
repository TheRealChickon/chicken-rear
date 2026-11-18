package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceControl

/**
 * THE FACE PICKER ON THE BACK (FacePicker's model, PickerTouch's finger): one shell layer at ShellLayer.Z_PICKER,
 * directly over the face, with the cards that are pictures (Xiaomi's own picture of each face), the ring, the inner
 * strokes and the dots. The face that was live when the hold began is its own layer under this one (ShellScene.shape
 * scales and rounds it each frame, nothing is drawn again for it), seen through this layer's clear middle: the cards
 * never overlap.
 *
 * Drawn every vsync while anything moves or a finger is down, and nothing at rest. Its own thread. Trusted: it stops no
 * touch (the cover under everything is what keeps every touch from Xiaomi's screen).
 *
 * IT NEVER STAYS UP BY ITSELF (PickerWatch): left alone it leaves; its exit is gone by a deadline; a vsync that does not
 * come (the Choreographer's is the front display's) is stood in for by a timer; a frame that throws drops it.
 */
class ShellPicker(
    private val layerStack: () -> Int,
    private val geometry: () -> RearGeometry,
    private val scene: ShellScene,
    /** As its exit starts: [chosen] the face picked, or null for left without choosing. On the picker's thread. */
    private val onShut: (chosen: FaceCatalog.Face?, faces: List<FaceCatalog.Face>) -> Unit,
    /** The exit has played out and the picker is gone. On the picker's thread. */
    private val onGone: () -> Unit,
) {
    private val thread = HandlerThread("rear-picker", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private var choreographer: Choreographer? = null

    private var layer: SurfaceControl? = null
    private var surface: Surface? = null
    @Volatile private var model: FacePicker? = null
    private var faces: List<FaceCatalog.Face> = emptyList()
    private val touch = PickerTouch()
    private var lastFrameNanos = 0L
    private var ticking = false
    private val watch = PickerWatch()
    private val watchCheck = Runnable { watchDue() }
    /** The face that was chosen is being built (Xiaomi's 150 ms wait is in the model). */
    private var shutSaid = false

    /** Up: from the hold until its exit has played out. Any thread. */
    @Volatile var isUp = false
        private set

    // -- pictures: at most the middle card and its neighbours decoded, the next ahead, off the drawing thread ----------

    private val decoder = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "rear-picker-cards").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val pictures = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 4
    }
    private val decoding = HashSet<String>()

    private fun picture(path: String?): Bitmap? {
        if (path == null) return null
        synchronized(pictures) { pictures[path]?.let { return it } ; if (!decoding.add(path)) return null }
        decoder.execute {
            val bm = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            synchronized(pictures) { decoding.remove(path); if (bm != null) pictures[path] = bm }
            if (bm == null) Log.w(TAG, "picker: Xiaomi's picture $path could not be read")
            handler.post { if (isUp) kick() }
        }
        return null
    }

    // -- in and out -------------------------------------------------------------------------------------------------

    /**
     * Up, on [list] (the faces offered, in order) at [live] (the face showing), opened by the finger that is down at
     * [x], [y] (panel units) since [downAt]. Any thread.
     */
    fun open(list: List<FaceCatalog.Face>, live: Int, x: Float, y: Float, downAt: Long) {
        if (list.isEmpty()) return
        isUp = true
        handler.post {
            if (!ensure()) { isUp = false; onGone(); return@post }
            faces = list
            model = FacePicker(list.size, live.coerceIn(0, list.size - 1))
            shutSaid = false; selectedSaid = true
            for (o in touch.holdFinger(x, y, downAt)) apply(o)
            Log.i(TAG, "picker: up on ${list[live.coerceIn(0, list.size - 1)].title}, ${list.size} faces")
            frames = 0; drawSum = 0L; gapMax = 0L
            watch.opened(SystemClock.uptimeMillis()); rewatch()
            scene.command(MamlFeed.ENTER_EDIT)
            kick()
        }
    }

    /** A finger on the back while the picker is up. Any thread. */
    fun feed(e: RearTouches.Event) {
        handler.post {
            val m = model ?: return@post
            if (!m.takesTouch) return@post
            if (e !is RearTouches.Move) { watch.touched(SystemClock.uptimeMillis()); rewatch() }
            for (o in touch.feed(e)) apply(o)
            kick()
        }
    }

    /** A second finger down with the picker's (PickerTouch.crowded): its finger means nothing more. Any thread. */
    fun crowded() {
        handler.post {
            val m = model ?: return@post
            if (!m.takesTouch) return@post
            for (o in touch.crowded()) apply(o)
            kick()
        }
    }

    private fun apply(o: PickerTouch.Out) {
        val m = model ?: return
        when (o) {
            PickerTouch.Out.Grab -> m.grab()
            is PickerTouch.Out.Drag -> m.drag(o.dxPx)
            is PickerTouch.Out.Release -> m.release(o.vxPxPerS)
            PickerTouch.Out.Cancel -> m.cancelDrag()
            PickerTouch.Out.Choose -> shut(pick = true)
            PickerTouch.Out.Leave -> shut(pick = false)
        }
    }

    private fun shut(pick: Boolean) {
        val m = model ?: return
        val chosen = if (pick) faces.getOrNull(m.index) else null
        // A new face's layer has to be built (the moon's clip started, or a Xiaomi face loaded): Xiaomi's 150 ms.
        m.shut(pick, build = chosen != null && m.index != m.live)
        watch.shut(SystemClock.uptimeMillis()); rewatch()
        if (!shutSaid) { shutSaid = true; scene.command(MamlFeed.EXIT_EDIT); runCatching { onShut(if (m.changesFace) chosen else null, faces) } }
        Log.i(TAG, if (m.changesFace) "picker: chosen ${chosen?.title}" else "picker: left, ${faces[m.live].title} stays")
    }

    /** Shut without choosing, as a stroke up would (the debug word, a test). Any thread. */
    fun leave() = handler.post { if (model?.takesTouch == true) { shut(pick = false); kick() } }

    /**
     * DROPPED AT ONCE (the back went dark, an app or something live took it, a note was pinned): the old face at full
     * size, no motion. Any thread.
     */
    fun drop(why: String) {
        if (!isUp) return
        handler.post {
            if (model == null) return@post
            Log.i(TAG, "picker: dropped — $why")
            if (!shutSaid) scene.command(MamlFeed.EXIT_EDIT)
            gone()
        }
    }

    /** What the watch says is due now: left alone, leave; the exit overdue, gone. On the picker's thread. */
    private fun watchDue() {
        if (model == null) return
        when (watch.due(SystemClock.uptimeMillis())) {
            PickerWatch.Due.LEAVE -> { Log.i(TAG, "picker: left alone ${PickerWatch.ALONE_MS / 1000} s — it leaves"); shut(pick = false); kick() }
            PickerWatch.Due.GONE -> { Log.w(TAG, "picker: its exit did not end within ${PickerWatch.EXIT_DEADLINE_MS} ms — gone"); if (!shutSaid) scene.command(MamlFeed.EXIT_EDIT); gone() }
            PickerWatch.Due.NOTHING -> rewatch()
        }
    }

    private fun rewatch() {
        handler.removeCallbacks(watchCheck)
        val at = watch.nextAt() ?: return
        handler.postAtTime(watchCheck, at)
    }

    private fun gone() {
        model = null
        ticking = false
        handler.removeCallbacks(frameFallback)
        watch.gone(); handler.removeCallbacks(watchCheck)
        choreographer?.removeFrameCallback(frameCallback)
        lastFrameNanos = 0L
        scene.shape(null)
        layer?.let { sc -> runCatching { SurfaceControl.Transaction().use { it.setVisibility(sc, false).setAlpha(sc, 0f).apply() } } }
        surface?.let { s -> runCatching { val c = s.lockHardwareCanvas(); c.drawColor(0, PorterDuff.Mode.CLEAR); s.unlockCanvasAndPost(c) } }
        synchronized(pictures) { pictures.clear() }
        if (frames > 0) Log.i(TAG, "picker: $frames pictures, drawn in ${drawSum / frames / 1000f} ms on average, longest gap ${gapMax / 1000f} ms")
        isUp = false
        runCatching { onGone() }
    }

    // -- frames -------------------------------------------------------------------------------------------------------

    private val frameCallback: Choreographer.FrameCallback = Choreographer.FrameCallback { nanos -> handler.removeCallbacks(frameFallback); safeFrame(nanos) }
    /** The vsync did not come in time (the front off?): the frame by the clock, and the vsync's asked for no more. */
    private val frameFallback: Runnable = Runnable { if (ticking) { choreographer?.removeFrameCallback(frameCallback); safeFrame(System.nanoTime()) } }

    private fun kick() {
        if (ticking) return
        ticking = true
        val c = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        c.postFrameCallback(frameCallback)
        handler.postDelayed(frameFallback, PickerWatch.FRAME_FALLBACK_MS)
    }

    /** A frame that throws drops the picker (the old face whole), never leaves it up with nothing to move it on. */
    private fun safeFrame(nanos: Long) {
        try { frame(nanos) } catch (t: Throwable) {
            Log.w(TAG, "picker: a frame failed — dropped", t)
            ticking = false
            if (model != null) { if (!shutSaid) scene.command(MamlFeed.EXIT_EDIT); gone() }
        }
    }

    private var selectedSaid = true
    private var frames = 0
    private var drawSum = 0L
    private var gapMax = 0L

    private fun frame(nanos: Long) {
        ticking = false
        val m = model ?: return
        val dt = if (lastFrameNanos == 0L) 1.0 / 120.0 else ((nanos - lastFrameNanos) / 1e9).coerceIn(0.0, 0.1)
        if (lastFrameNanos != 0L) gapMax = maxOf(gapMax, (nanos - lastFrameNanos) / 1000L)
        lastFrameNanos = nanos
        val moving = m.step(dt)
        // Xiaomi's face host tells the live face whether it is the middle card ("selected").
        val sel = m.index == m.live
        if (sel != selectedSaid) { selectedSaid = sel; scene.number(MamlFeed.SELECTED, if (sel) 1.0 else 0.0) }
        val t0 = SystemClock.elapsedRealtimeNanos()
        draw(m)
        drawSum += (SystemClock.elapsedRealtimeNanos() - t0) / 1000L; frames++
        if (m.phase == FacePicker.Phase.GONE) { gone(); return }
        if (moving) kick() else lastFrameNanos = 0L
    }

    private val art = PickerArt()

    private fun draw(m: FacePicker) {
        val f = m.frame()
        val rear = geometry()
        val sx = rear.widthPx / FacePicker.W.toFloat(); val sy = rear.heightPx / FacePicker.H.toFloat()
        // The live face's layer: placed as its card, or (a new face chosen) the new face's layer as the middle card.
        art.liveCard(m, f)?.let { card -> scene.shape(art.shapeOf(m, f, card, sx, sy)) }
        val s = surface ?: return
        val sc = layer ?: return
        val c = runCatching { s.lockHardwareCanvas() }.getOrNull() ?: return
        try {
            c.drawColor(0, PorterDuff.Mode.CLEAR)
            art.draw(c, m, f, sx, sy, { i -> picture(faces.getOrNull(i)?.card) })
        } finally { s.unlockCanvasAndPost(c) }
        runCatching { SurfaceControl.Transaction().use { it.setAlpha(sc, 1f).setVisibility(sc, true).apply() } }
    }

    private fun ensure(): Boolean {
        if (layer != null) return true
        val rear = geometry()
        return runCatching {
            val sc = ShellLayer.create("picker", layerStack(), ShellLayer.Z_PICKER, rear.widthPx, rear.heightPx,
                trusted = true, visible = false, alpha = 0f, format = PixelFormat.RGBA_8888, opaque = false)
            layer = sc; surface = Surface(sc)
            true
        }.onFailure { Log.w(TAG, "picker: no layer — ${it.message}") }.getOrDefault(false)
    }

    fun close() {
        handler.post {
            model = null
            runCatching { surface?.release() }
            ShellLayer.remove(layer); layer = null; surface = null
            thread.quitSafely()
        }
        decoder.shutdown()
    }

    private companion object { const val TAG = "AiUsageRear" }
}
