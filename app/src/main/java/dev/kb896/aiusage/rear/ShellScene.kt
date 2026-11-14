package dev.kb896.aiusage.rear

import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import java.io.File

/**
 * The scene on the back: a clip played once as the back wakes, then a clip that repeats under
 * the clock, drawn by SceneEngine onto one opaque layer of the shell's own over Xiaomi's rear
 * screen (ShellLayer). Opaque and black wherever the picture is black, so an OLED pixel there is
 * off.
 *
 * NOT TRUSTED, so it takes every touch (Android's untrusted-touch occlusion, as ShellOverlay's
 * blocker does): while it is up nothing reaches Xiaomi's screen under it, and no tap can change a
 * page nobody sees. It was trusted, and faded at the first touch: a double tap meant to brighten
 * the back landed on Xiaomi's pinned message. The shell reads the raw
 * touchscreen instead (SceneTouch) and calls [aside] for a swipe made on purpose, which hides the
 * layer at once so the swipe can be handed on (RearFinger).
 *
 * THE CURTAIN AHEAD ([arm]). With the back OFF (not dozing: Xiaomi's always-on face is never
 * covered) the layer can be put up black ahead, so the first lit frame is already black and the
 * clip starts from it with no flash of Xiaomi's screen. [disarm] takes it away; a wake that
 * plays nothing must call it.
 *
 * THREADING: [state] guards the request; one thread per show, living only as long; [onEnded]
 * runs on it as it stops, with why.
 */
class ShellScene(
    private val layerStack: () -> Int,
    private val geometry: () -> RearGeometry,
    /** Whether the rear is lit (STATE_ON) now. */
    private val lit: () -> Boolean,
    private val onEnded: (why: String) -> Unit,
) {
    private val state = Any()
    private var request: Request? = null
    private var thread: Thread? = null
    @Volatile private var look = SceneFace.Look()
    @Volatile private var engine: SceneEngine? = null
    @Volatile private var layer: SurfaceControl? = null
    private val making = Any()
    @Volatile private var armed = false

    /** 1 for the picture as it is; less for a back left lying face up (RearPosture's dim). Any thread. */
    @Volatile var dim = 1f
        set(value) { field = value; engine?.dim = value }

    /** The loop held as a still picture (SceneEngine.still). Any thread. */
    @Volatile var still = false
        set(value) { field = value; engine?.still = value }

    /** The clock behind the clip's subject, where the clip carries a matte. Any thread. */
    @Volatile var depth = true
        set(value) { field = value; engine?.depth = value }

    private class Request(val wake: String?, val loop: String?, val at: Long, val waitForLight: Boolean) {
        @Volatile var dismissAt = 0L
        @Volatile var dismissMs = FADE_OUT_MS
        @Volatile var why = ""
    }

    /** Playing, or waiting for the back to light. */
    @Volatile var isUp = false
        private set

    /** When the show now up began (uptime); 0 with none. */
    @Volatile var startedAt = 0L
        private set

    /** The loop is what is showing (the wake clip is over, or there was none). */
    @Volatile var looping = false
        private set

    /** The layer made now, hidden, so a wake only has to draw. Any thread. */
    fun prepare() { runCatching { layerOrNull() } }

    /** The clock and battery as they are to be drawn from now on. Any thread. */
    fun look(next: SceneFace.Look) { look = next; engine?.look = next }

    /** The weather for the moon face's clouds (SceneEffects); null when it is not known. */
    @Volatile var weather: dev.kb896.aiusage.data.Condition? = null
        set(value) { field = value; engine?.weather = value }
    /** The clouds stirred by the weather, and the glitch: each wanted or not (PanelConfig.sceneWeather, sceneGlitch). */
    @Volatile var clouds = true
        set(value) { field = value; engine?.clouds = value }
    @Volatile var glitch = true
        set(value) { field = value; engine?.glitch = value }
    /** The clip up is the moon's: the effects' places are that picture's. */
    @Volatile private var moon = false

    private fun layerOrNull(): SurfaceControl? {
        layer?.let { return it }
        synchronized(making) {
            layer?.let { return it }
            val rear = geometry()
            return runCatching {
                ShellLayer.create(
                    "scene", layerStack(), ShellLayer.Z_SCENE, rear.widthPx, rear.heightPx,
                    trusted = false, visible = false, alpha = 0f, format = PixelFormat.RGBA_8888, opaque = true,
                )
            }.onFailure { Log.w(TAG, "scene: no layer: ${it.message}") }.getOrNull()?.also { layer = it }
        }
    }

    private fun engineFor(surface: Surface): SceneEngine {
        val rear = geometry()
        return SceneEngine(surface, rear.widthPx, rear.heightPx, rear.cutoutPx.toFloat()).also {
            it.look = look; it.dim = dim; it.depth = depth; it.still = still
            it.weather = weather; it.clouds = clouds; it.glitch = glitch; it.moonClip = moon
        }
    }

    /**
     * The layer up now, black, with the back OFF, so the wake that follows starts from black.
     * Nothing while a show is up. Blocking for a few milliseconds: not on a binder thread.
     */
    fun arm() {
        synchronized(state) { if (isUp || armed) return }
        val sc = layerOrNull() ?: return
        runCatching {
            val s = Surface(sc)
            try { engineFor(s).apply { black(); close() } } finally { s.release() }
            SurfaceControl.Transaction().use { it.setAlpha(sc, 1f).setVisibility(sc, true).apply() }
            armed = true
        }.onFailure { Log.w(TAG, "scene: could not arm: ${it.message}") }
    }

    /** The curtain put up by [arm] taken away, unless a show has since taken it over. */
    fun disarm() {
        if (!armed) return
        armed = false
        if (isUp) return
        layer?.let { sc -> runCatching { SurfaceControl.Transaction().use { it.setVisibility(sc, false).setAlpha(sc, 0f).apply() } } }
    }

    /**
     * Show the scene: [wake] once (null for none), then [loop] under the clock until dismissed
     * (null: it ends with the wake clip). Paths of readable files. [waitForLight]: one that may
     * wait for a back that is being woken for it.
     */
    fun play(wake: String?, loop: String?, seenAtMs: Long, waitForLight: Boolean): Boolean {
        val w = wake?.takeIf { File(it).canRead() }
        val l = loop?.takeIf { File(it).canRead() }
        if (wake != null && w == null) Log.w(TAG, "scene: $wake cannot be read")
        if (loop != null && l == null) Log.w(TAG, "scene: $loop cannot be read")
        if (w == null && l == null) return false
        moon = l?.endsWith(MOON_CLIP) == true
        engine?.moonClip = moon
        synchronized(state) {
            request?.let { it.why = "replaced"; it.dismissMs = 1L; if (it.dismissAt == 0L) it.dismissAt = SystemClock.uptimeMillis() }
            request = Request(w, l, seenAtMs, waitForLight)
            isUp = true
            startedAt = seenAtMs
            if (thread?.isAlive != true) thread = Thread({ run() }, "rear-scene").apply { isDaemon = true; start() }
        }
        return true
    }

    /** Away: faded over [fadeMs] to what is under it. The back going dark, an app arriving, a swipe handed on. */
    fun dismiss(fadeMs: Long, why: String) {
        val r = synchronized(state) { request } ?: return
        if (r.dismissAt != 0L) return
        r.dismissMs = fadeMs.coerceAtLeast(1L)
        r.why = why
        r.dismissAt = SystemClock.uptimeMillis()
    }

    /**
     * Out of the way NOW, on the caller's thread: hidden in one transaction, so the very next
     * touch event reaches what is under it, and then ended as [dismiss] ends it.
     */
    fun aside(why: String) {
        dismiss(1L, why)
        layer?.let { sc -> runCatching { SurfaceControl.Transaction().use { it.setVisibility(sc, false).setAlpha(sc, 0f).apply() } } }
    }

    private fun run() {
        var why = "done"
        val sc = layerOrNull()
        try {
            if (sc == null) { why = "no layer"; return }
            while (true) {
                val r = synchronized(state) { request } ?: break
                why = show(r, sc)
                val next = synchronized(state) { if (request === r) { request = null; null } else request }
                if (next == null) break
            }
        } catch (_: InterruptedException) {
            why = "stopped"
        } catch (e: Throwable) {
            why = "failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "scene stopped", e)
        } finally {
            engine = null
            sc?.let { runCatching { SurfaceControl.Transaction().use { t -> t.setVisibility(it, false).setAlpha(it, 0f).apply() } } }
            armed = false
            val mine = synchronized(state) {
                if (thread === Thread.currentThread()) { thread = null; isUp = false; looping = false; startedAt = 0L; request = null; true } else false
            }
            if (mine) {
                Log.i(TAG, "scene: over — $why")
                runCatching { onEnded(why) }
            }
        }
    }

    /** One request, start to end; why it ended. */
    private fun show(r: Request, sc: SurfaceControl): String {
        while (!lit()) {
            if (!r.waitForLight || SystemClock.uptimeMillis() - r.at > LIT_WAIT_MS) return "the back is dark — not played"
            if (r.dismissAt != 0L) return r.why
            Thread.sleep(WAIT_STEP_MS)
        }
        val surface = Surface(sc)
        try {
            val e = engineFor(surface)
            engine = e
            looping = false
            // The curtain: black over Xiaomi's screen until the clip's first frame is up.
            e.black()
            var placed = -1f
            fun place(alpha: Float) {
                if (alpha == placed) return
                SurfaceControl.Transaction().use { it.setAlpha(sc, alpha).setVisibility(sc, alpha > 0f).apply() }
                placed = alpha
            }
            place(1f)
            armed = false
            val control = object : SceneEngine.Control {
                override var why = ""
                override fun strength(): Float? {
                    if (!lit()) { why = "the back went dark"; return null }
                    if (r.dismissAt != 0L) {
                        val left = 1f - (SystemClock.uptimeMillis() - r.dismissAt).toFloat() / r.dismissMs
                        if (left <= 0f) { why = r.why.ifEmpty { "dismissed" }; return null }
                        place(left)
                    }
                    return 1f
                }
                override fun onLoop() { looping = true }
            }
            val end = e.play(r.wake, r.loop, control)
            if (end == "played") {
                // A wake clip with nothing after it ends on black: that fades to Xiaomi's screen.
                val from = SystemClock.uptimeMillis()
                while (lit()) {
                    val left = 1f - (SystemClock.uptimeMillis() - from).toFloat() / FADE_OUT_MS
                    if (left <= 0f) break
                    place(left)
                    Thread.sleep(FRAME_MS)
                }
            }
            return end
        } finally {
            engine = null
            runCatching { surface.release() }
        }
    }

    /** Lets go of the layer, for the shell's end. */
    fun close() {
        synchronized(state) { request = null; isUp = false; thread?.interrupt() }
        ShellLayer.remove(layer); layer = null
    }

    companion object {
        /** The one face: David on the moon. Its Earth is where SceneEngine's shader looks for clouds. */
        const val MOON_CLIP = "david-moon.loop.mp4"
        private const val TAG = "AiUsageRear"
        const val FADE_OUT_MS = 200L
        /** A touch takes it away faster than it leaves by itself. */
        const val TOUCH_FADE_MS = 120L
        /** And it comes back gently. */
        const val LIT_WAIT_MS = 1_500L
        private const val WAIT_STEP_MS = 20L
        private const val FRAME_MS = 16L
    }
}
