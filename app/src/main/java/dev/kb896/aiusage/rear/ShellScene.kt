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
 *
 * DUST IN LIGHT ([FaceCatalog.DUST] as the loop) is drawn by the same engine on the same layer as the moon, with no
 * file: its finger comes in by [dustTouch], its two sensors (the accelerometer and the gyroscope, from [sensors]) are on
 * only while it is drawn on a lit back and not held still (DustSensor.Gate, in the engine's loop). Where this GPU cannot
 * draw it, the moon shows in its place and the choice is kept (as for a Xiaomi face that fails).
 */
class ShellScene(
    private val layerStack: () -> Int,
    private val geometry: () -> RearGeometry,
    /** Whether the rear is lit (STATE_ON) now. */
    private val lit: () -> Boolean,
    /** Whether the rear is lit by a reading of this process's under a tenth of a second old: the look at every picture. */
    private val litRecently: () -> Boolean = lit,
    private val onEnded: (why: String) -> Unit,
    /** Xiaomi's faces (MamlHost), for a loop that is a face's key ("xiaomi:<id>", FaceCatalog) and not a clip. */
    private val faces: FaceSource? = null,
    /** Our own loop (the moon's clip): what shows when a Xiaomi face cannot be had or draws nothing. */
    private val moonLoop: () -> String? = { null },
    /** The sensors, for the dust face's two (null: it ignores the phone's movement). */
    private val sensors: () -> android.hardware.SensorManager? = { null },
    /** The back's panel's refresh rate now (Hz, its mode's; 0: not known), for the dust's log line. */
    private val panelHz: () -> Float = { 0f },
) {
    /** Where a Xiaomi face comes from: the shell's MamlHost, with the face's file and his edit. */
    interface FaceSource {
        /** The face [key] loaded, or null (and why said) when it cannot be had. */
        fun open(key: String): MamlHost.Face?
        /** [key] failed after it was loaded (a first frame that stays empty): it is left out from now on. */
        fun failed(key: String, why: String)
        /** The face up now, for the picker's commands and his taps; null with none. */
        fun shown(face: MamlHost.Face?)
    }

    private val state = Any()
    private var request: Request? = null
    private var thread: Thread? = null
    @Volatile private var look = SceneFace.Look()
    @Volatile private var engine: SceneEngine? = null
    @Volatile private var layer: SurfaceControl? = null
    /** A Xiaomi face's layer, at the scene's place (drawn by a canvas, never by the moon's GL). */
    @Volatile private var faceLayer: SurfaceControl? = null
    /** The layer that shows now, for the picker's shape ([shape]) and the light ([dim]). */
    @Volatile private var current: SurfaceControl? = null
    @Volatile private var currentAlpha = 1f
    private val making = Any()
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") private val waker = java.lang.Object()
    @Volatile private var armed = false

    /** 1 for the picture as it is; less for a back left lying face up (RearPosture's dim). Any thread. */
    @Volatile var dim = 1f
        set(value) { field = value; engine?.dim = value; if (current != null && current === faceLayer) placeCurrent() }

    /**
     * THE FACE AS A CARD IN THE PICKER (FacePicker): its layer scaled about its own centre to [Shape.scale], centred at
     * [Shape.cx], [Shape.cy], its corners rounded and its alpha [Shape.alpha] (times its own). Nothing is drawn again for
     * it: SurfaceFlinger moves the picture. Null: the whole back, as always.
     */
    data class Shape(val scale: Float, val cx: Float, val cy: Float, val corner: Float, val alpha: Float) {
        /** Where the scaled layer's top-left corner stands, for a panel [w] x [h] (RearShape.place). */
        fun left(w: Float) = cx - w * scale / 2f
        fun top(h: Float) = cy - h * scale / 2f
    }

    @Volatile var shape: Shape? = null
        private set

    /** The face shaped as [s] now (any thread; one transaction). */
    fun shape(s: Shape?) {
        shape = s
        placeCurrent()
    }

    private fun placeCurrent() {
        val sc = current ?: return
        runCatching { SurfaceControl.Transaction().use { t -> transform(t, sc, currentAlpha); t.apply() } }
    }

    /** The layer's alpha, its shape and the light (a Xiaomi face's gain is its alpha over the black cover). */
    private fun transform(t: SurfaceControl.Transaction, sc: SurfaceControl, alpha: Float) {
        val rear = geometry()
        val s = shape
        val gain = if (sc === faceLayer) dim.coerceIn(0f, 1f) else 1f
        val a = alpha * gain * (s?.alpha ?: 1f)
        // Through RearShape, as every layer of ours that moves: in its place the display's shape over everything
        // rounds it; as the picker's card its own corners, all four.
        if (s == null) RearShape.place(t, sc, rear, 0f, 0f, 1f, 0f)
        else RearShape.place(t, sc, rear, s.left(rear.widthPx.toFloat()), s.top(rear.heightPx.toFloat()), s.scale, s.corner)
        t.setAlpha(sc, a).setVisibility(sc, a > 0f)
    }

    /** The loop held as a still picture (SceneEngine.still). Any thread. */
    @Volatile var still = false
        set(value) { field = value; engine?.still = value }

    /** The clock behind the clip's subject, where the clip carries a matte. Any thread. */
    @Volatile var depth = true
        set(value) { field = value; engine?.depth = value }

    private class Request(val wake: String?, val loop: String?, val at: Long, val waitForLight: Boolean) {
        /** A Xiaomi face that could not be had or drew nothing: the moon in its place ([moonLoop]), not an empty back. */
        @Volatile var faceFailed = false
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

    /**
     * THE MESSAGE ISLAND'S TINT ("Island · Tint", ShellNotices): every engine of this scene reads it at each picture, so the
     * live face itself takes the app's colour under the open page. Nothing is drawn for it while no island is up.
     */
    val veil = NoticeVeil()

    /** The face up now is drawn by the scene's own engine (the moon, the dust), which can take the island's colour itself. */
    val tintsItself: Boolean get() = isUp && engine != null && current != null && current === layer

    private fun engineFor(surface: Surface, dust: Boolean = false): SceneEngine {
        val rear = geometry()
        return SceneEngine(surface, rear.widthPx, rear.heightPx, rear.cutoutPx.toFloat(), gles3 = dust).also {
            it.look = look; it.dim = dim; it.depth = depth; it.still = still
            it.weather = weather; it.clouds = clouds; it.glitch = glitch; it.moonClip = moon
            it.veil = veil
            if (dust) it.dust = SceneEngine.Dust(dustFinger, dustMotion, { dustSensor.start() }, { dustSensor.stop() }, panelHz = panelHz, sensorPoll = { dustSensor.poll() }, sensorSaid = { dustSensor.said() }, sensorAllowed = { dustMoves })
        }
    }

    // -- the dust face's inputs: kept here, they outlive one show ---------------------------------------------------------

    /** The setting "Moves with the phone" (PanelConfig.dustMovesWithPhone); the dust's loop reads it every picture. */
    @Volatile var dustMoves = true

    private val dustFinger = DustFinger()
    private val dustMotion = DustMotion()
    private val dustSensor by lazy { DustSensor(sensors, dustMotion) }

    /** The dust face is the scene now (or about to be). */
    val dustShowing: Boolean get() = isUp && showing() == FaceCatalog.DUST

    /**
     * A finger on the back, for the dust to show ([x], [y] in rear px, [e]'s own clock). Only shows it: the shell does
     * with the touch whatever it did. [seen]: the face is what is seen (a page over it, a picker): otherwise let go.
     */
    fun dustTouch(e: RearTouches.Event, x: Float, y: Float, seen: Boolean) {
        if (!seen) { dustFinger.up(e.atMs); return }
        when (e) {
            is RearTouches.Down -> dustFinger.down(x, y, e.atMs)
            is RearTouches.Move -> dustFinger.move(x, y, e.atMs)
            is RearTouches.Up -> { dustFinger.move(x, y, e.atMs); dustFinger.up(e.atMs) }
        }
    }

    /** The finger the dust shows is let go of (the picker took it): the motes come back. */
    fun dustRelease() = dustFinger.up(SystemClock.uptimeMillis())

    /** Whether the dust's sensors are registered now, and which (for the log and the debug word). */
    val dustSensing: String get() = if (dustSensor.listening) dustSensor.what else "off"

    /**
     * Debug builds: a made-up movement of the phone for the dust (the pop-demo word), as its two sensors would give it
     * (DustMotion.Made), from the gravity it has now; the real samples wait until it is over and a second and a half
     * of stillness after it. On a thread of its own; only while the dust is drawn.
     */
    fun dustMove(made: DustMotion.Made) {
        if (!dustShowing) return
        Thread({
            val start = SystemClock.elapsedRealtimeNanos()
            val endNs = start + ((made.lastsS + DUST_MADE_STILL_S) * 1e9f).toLong()
            val g0 = FloatArray(3); val a = FloatArray(3); val w = FloatArray(3)
            dustMotion.makeUp(endNs, g0)
            val m = DustMotion.Made(g0, made.pushX, made.pushY, made.pushS, made.turn, made.tilt, made.turnS)
            var t = start
            while (t < endNs) {
                m.at((t - start) / 1e9f, a, w)
                dustMotion.gyro(w[0], w[1], w[2], t, made = true)
                dustMotion.accel(a[0], a[1], a[2], t, made = true)
                Thread.sleep(DustMotion.RATE_US / 1000L)
                t = SystemClock.elapsedRealtimeNanos()
            }
        }, "rear-dust-made").apply { isDaemon = true; start() }
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
        // A key, not a file: a Xiaomi face, or the dust (no wake clip before either).
        val keyed = loop?.startsWith("xiaomi:") == true || loop == FaceCatalog.DUST
        val w = if (keyed) null else wake?.takeIf { File(it).canRead() }
        val l = if (keyed) loop else loop?.takeIf { File(it).canRead() }
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
        synchronized(waker) { waker.notifyAll() }
        return true
    }

    /** The Xiaomi face drawing now (MamlHost), if one is. */
    @Volatile var face: MamlHost.Face? = null
        private set

    /** A command to the Xiaomi face up now (the picker's enterEdit / exitEdit, as Xiaomi's face host sends them). */
    fun command(c: String) { face?.command(c) }
    fun number(name: String, v: Double) { face?.number(name, v) }

    /** The face up now is Xiaomi's [key] (or is about to be). */
    fun showing(): String? = synchronized(state) { request?.loop }

    /** Away: faded over [fadeMs] to what is under it. The back going dark, an app arriving, a swipe handed on. */
    fun dismiss(fadeMs: Long, why: String) {
        val r = synchronized(state) { request } ?: return
        if (r.dismissAt != 0L) return
        r.dismissMs = fadeMs.coerceAtLeast(1L)
        r.why = why
        r.dismissAt = SystemClock.uptimeMillis()
        synchronized(waker) { waker.notifyAll() }
    }

    /**
     * Out of the way NOW, on the caller's thread: hidden in one transaction, so the very next
     * touch event reaches what is under it, and then ended as [dismiss] ends it.
     */
    fun aside(why: String) {
        dismiss(1L, why)
        for (sc in listOfNotNull(layer, faceLayer)) runCatching { SurfaceControl.Transaction().use { it.setVisibility(sc, false).setAlpha(sc, 0f).apply() } }
    }

    private fun run() {
        var why = "done"
        val sc = layerOrNull()
        try {
            if (sc == null) { why = "no layer"; return }
            while (true) {
                val r = synchronized(state) { request } ?: break
                why = show(r, sc)
                // THE MOON IN A FAILED FACE'S PLACE (FaceCatalog rule 8): the back stays ours, his choice is kept.
                // (Asked outside the lock: lit() asks the system.)
                val instead = if (r.faceFailed && r.dismissAt == 0L && lit()) moonLoop()?.takeIf { File(it).canRead() } else null
                val next = synchronized(state) {
                    if (request !== r) return@synchronized request
                    if (instead != null && r.dismissAt == 0L) {
                        Log.i(TAG, "scene: ${r.loop} — $why; the moon in its place")
                        moon = instead.endsWith(MOON_CLIP)
                        Request(null, instead, SystemClock.uptimeMillis(), waitForLight = false).also { request = it }
                    } else { request = null; null }
                }
                if (next == null) break
            }
        } catch (_: InterruptedException) {
            why = "stopped"
        } catch (e: Throwable) {
            why = "failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "scene stopped", e)
        } finally {
            engine = null
            current = null
            for (l in listOfNotNull(sc, faceLayer)) runCatching { SurfaceControl.Transaction().use { t -> t.setVisibility(l, false).setAlpha(l, 0f).apply() } }
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
        if (r.loop?.startsWith("xiaomi:") == true) return showFace(r, r.loop, sc)
        val dust = r.loop == FaceCatalog.DUST
        val surface = Surface(sc)
        try {
            val e = engineFor(surface, dust)
            engine = e
            looping = false
            // The curtain: black over Xiaomi's screen until the clip's first frame is up.
            e.black()
            var placed = -1f
            current = sc
            fun place(alpha: Float) {
                if (alpha == placed) return
                currentAlpha = alpha
                SurfaceControl.Transaction().use {
                    transform(it, sc, alpha)
                    // THE OTHER LAYER DOWN IN THE SAME TRANSACTION (Swap): a Xiaomi face before this one keeps its last
                    // picture on its own layer, at the same z and made later, so over the moon until it is hidden.
                    if (placed < 0f) for (other in Swap.hiddenWith(Swap.Layer.MOON, layer = sc, face = faceLayer)) it.setVisibility(other, false).setAlpha(other, 0f)
                    it.apply()
                }
                placed = alpha
            }
            place(1f)
            armed = false
            // Up to 120 pictures a second: the light is not asked of the system at every one (a moon clip did that, and the
            // dust ten times a second), but taken from the shell's last reading while that is under a tenth of a second
            // old (litRecently). Dark by it is read again before the scene ends on it.
            fun litNow(): Boolean = litRecently() || lit()
            val control = object : SceneEngine.Control {
                override var why = ""
                override fun strength(): Float {
                    if (!litNow()) { why = "the back went dark"; return SceneEngine.STOP }
                    if (r.dismissAt != 0L) {
                        val left = 1f - (SystemClock.uptimeMillis() - r.dismissAt).toFloat() / r.dismissMs
                        if (left <= 0f) { why = r.why.ifEmpty { "dismissed" }; return SceneEngine.STOP }
                        place(left)
                    }
                    return 1f
                }
                override fun onLoop() { looping = true }
            }
            val end = e.play(r.wake, r.loop, control)
            if (dust && end.startsWith(SceneEngine.DUST_FAILED)) {
                // THE MOON IN ITS PLACE (as for a Xiaomi face that fails): his choice is kept.
                Log.w(TAG, "scene: $end")
                r.faceFailed = true
                return end
            }
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

    private fun faceLayerOrNull(): SurfaceControl? {
        faceLayer?.let { return it }
        synchronized(making) {
            faceLayer?.let { return it }
            val rear = geometry()
            return runCatching {
                ShellLayer.create(
                    "face", layerStack(), ShellLayer.Z_SCENE, rear.widthPx, rear.heightPx,
                    trusted = false, visible = false, alpha = 0f, format = PixelFormat.RGBA_8888, opaque = true,
                )
            }.onFailure { Log.w(TAG, "face: no layer: ${it.message}") }.getOrNull()?.also { faceLayer = it }
        }
    }

    /**
     * A XIAOMI FACE (MamlHost) as the scene: on its own layer at the scene's place, drawn when its engine asks and at
     * each minute, and nothing between (no clip, no GL). The moon's layer is down meanwhile. A first frame that does
     * not come within [FIRST_FRAME_MS] is a failure (FaceCatalog rule 8): the moon shows instead.
     */
    private fun showFace(r: Request, key: String, moonLayer: SurfaceControl): String {
        val src = faces ?: return "no faces here".also { r.faceFailed = true }
        val fl = faceLayerOrNull() ?: return "no layer for the face".also { r.faceFailed = true }
        val face = src.open(key) ?: return "the face could not be had".also { r.faceFailed = true }
        val surface = Surface(fl)
        try {
            face.drawTo(surface)
            face.resume()
            val until = SystemClock.uptimeMillis() + FIRST_FRAME_MS
            while (!face.drawn && SystemClock.uptimeMillis() < until && r.dismissAt == 0L && lit()) Thread.sleep(WAIT_STEP_MS)
            if (!face.drawn) {
                if (r.dismissAt == 0L && lit()) { src.failed(key, "a first frame that stays empty"); r.faceFailed = true; return "the face drew nothing" }
                return r.why.ifEmpty { "the back went dark" }
            }
            current = fl; currentAlpha = 1f
            SurfaceControl.Transaction().use { t ->
                transform(t, fl, 1f)
                for (other in Swap.hiddenWith(Swap.Layer.FACE, layer = moonLayer, face = fl)) t.setVisibility(other, false).setAlpha(other, 0f)
                t.apply()
            }
            armed = false
            looping = true
            this.face = face
            src.shown(face)
            var nextMinute = MamlFeed.nextMinute(System.currentTimeMillis())
            while (true) {
                if (!lit()) return "the back went dark"
                if (synchronized(state) { request !== r }) return "replaced"
                if (r.dismissAt != 0L) {
                    val left = 1f - (SystemClock.uptimeMillis() - r.dismissAt).toFloat() / r.dismissMs
                    if (left <= 0f) return r.why.ifEmpty { "dismissed" }
                    currentAlpha = left
                    SurfaceControl.Transaction().use { t -> transform(t, fl, left); t.apply() }
                    Thread.sleep(FRAME_MS)
                    continue
                }
                val now = System.currentTimeMillis()
                if (now >= nextMinute) { face.minute(); nextMinute = MamlFeed.nextMinute(now) }
                // Asleep until the minute, a dismissal or a new request; and a look at the light four times a second.
                synchronized(waker) { waker.wait(minOf(LIGHT_LOOK_MS, (nextMinute - now).coerceAtLeast(1L))) }
            }
        } finally {
            this.face = null
            src.shown(null)
            current = null
            // In the picker (shaped) the layer's last picture would stand still where its card was while the row moves
            // on: down at once (the picker's own picture of the card is over that place). Whole, it stays until the next
            // face's or the moon's first picture takes it down (Swap), so a face changed from the settings has no gap.
            if (shape != null) runCatching { SurfaceControl.Transaction().use { it.setVisibility(fl, false).setAlpha(fl, 0f).apply() } }
            runCatching { face.pause() }
            runCatching { face.close() }
            runCatching { surface.release() }
        }
    }

    /**
     * THE MOON AND A XIAOMI FACE ARE TWO LAYERS AT ONE z ([ShellLayer.Z_SCENE]); SurfaceFlinger draws the one made later
     * (the face's) over the other. So whichever shows its first picture hides the other in that same transaction: the
     * moon after a Xiaomi face would otherwise lie under the face's last picture (frozen, and in the picker at its
     * card's size) until the back went dark. Pure, for the test.
     */
    object Swap {
        enum class Layer { MOON, FACE }

        /** The layers to hide as [shown] shows its first picture: always the other one, if it exists. */
        fun <T : Any> hiddenWith(shown: Layer, layer: T?, face: T?): List<T> = when (shown) {
            Layer.MOON -> listOfNotNull(face?.takeIf { it !== layer })
            Layer.FACE -> listOfNotNull(layer?.takeIf { it !== face })
        }
    }

    /** Lets go of the layer, for the shell's end. */
    fun close() {
        runCatching { dustSensor.stop() }
        synchronized(state) { request = null; isUp = false; thread?.interrupt() }
        ShellLayer.remove(layer); layer = null
        ShellLayer.remove(faceLayer); faceLayer = null
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
        /** A Xiaomi face's first frame: it loads in 28 to 50 ms (the probe); this long and it has failed. */
        private const val FIRST_FRAME_MS = 1_500L
        /** How often a Xiaomi face's thread looks at the light while it waits for its minute. */
        private const val LIGHT_LOOK_MS = 250L
        /** A made-up movement (the debug word) keeps the samples this long after it for the dust to settle. */
        private const val DUST_MADE_STILL_S = 1.5f
    }
}
