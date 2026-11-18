package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import dev.kb896.aiusage.rear.BlurLevels
import dev.kb896.aiusage.rear.PinB
import dev.kb896.aiusage.rear.PinBGl
import dev.kb896.aiusage.rear.PinFace
import dev.kb896.aiusage.rear.PinGl
import dev.kb896.aiusage.rear.XiaomiPin
import dev.kb896.aiusage.service.DropletTime

/**
 * THE FRONT'S HALF OF "PIN ANIMATION: OURS", DRAFT B (rear/PinB, drawn by rear/PinBGl): a see-through picture over the
 * app he is in, every frame on the display's own vsync (Choreographer's frame time, the one clock), on a thread of its
 * own. In whichever window holds it: the pin's activity (the system blurs the window behind) or the glass service's
 * overlay above every window (the page behind is then the glass's picture, frosted here).
 *
 * The content is the notes page with the note on it, as the back will show it (rear/PinFace). Ready before the first
 * frame: the timeline (a few ms) and the content's sharp picture; its blurred levels and the page behind's come from
 * a worker within tens of ms and are taken when they come (the frost is first needed at about 1030 ms).
 *
 * The back is told at [PinB.TELL_AT], the phone's notification sound's attack is heard on the island's first frame
 * ([PinB.CHIME_AT], service/Droplet), and from
 * [PinB.CARD_GONE_MS] only the island is left. A touch ends it wherever it is: its window (an activity, or the overlay's
 * embedded window) takes every touch over the whole screen, so none could reach the app under it.
 */
class PinBView(
    context: Context,
    private val text: String,
    private val origin: RectF?,
    private val run: PinRun,
    private val light: XiaomiPin.Light,
    /** How blurred the window behind is to be (a radius in pixels): the activity's to set; nothing in the overlay. */
    private val blur: (Int) -> Unit,
    /** A touch ends it. Both callers say true: a window of ours over the whole screen takes the touch either way. */
    private val touchEnds: Boolean,
    /** OpenGL could not be had here: the caller plays the plain drawing instead. */
    private val onFailed: () -> Unit,
    private val end: () -> Unit,
    /** A debug build's slow motion (its clock divided by this); 1 for every real pin. */
    private val slow: Float = 1f,
    /** A picture's pin: the picture (and its file's name), where the words would be on the notes page it carries. */
    private val picture: Bitmap? = null,
    private val image: String? = null,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var gl: Handler? = null
    @Volatile private var running = false
    @Volatile private var over = false
    private val at = IntArray(2)

    /** The page behind, as it was when he pinned (the glass service's picture). In memory only; whoever set it lets it go. */
    @Volatile var behind: Bitmap? = null
        set(value) { field = value; if (value != null) gl?.post { pageFrom(value) } }

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!touchEnds) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) stop("a touch")
        return true
    }

    private fun stop(why: String) {
        if (over) return
        over = true
        running = false
        Log.i(TAG, "beam B: ended early ($why)")
        main.post { end() }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        if (running || over) return
        running = true
        getLocationOnScreen(at)
        val unit = width / PinB.SCREEN_W
        // The camera hole, where the screen says it is (its punch hole is the island's middle and the card's way in and out).
        val cut = runCatching { display?.cutout?.boundingRects }.getOrNull().orEmpty()
            .filter { it.top < height / 6 }.minByOrNull { kotlin.math.abs(it.centerX() - width / 2) }
        val place = PinB.Place(
            screenH = height / unit,
            holeX = cut?.let { (it.exactCenterX() - at[0]) / unit } ?: PinB.HOLE_X,
            holeY = cut?.let { (it.exactCenterY() - at[1]) / unit } ?: PinB.HOLE_Y,
            holeR = cut?.let { minOf(it.width(), it.height()) / 2f / unit } ?: PinB.HOLE_R,
            fromX = origin?.let { (it.centerX() - at[0]) / unit },
            fromY = origin?.let { (it.centerY() - at[1]) / unit },
            fromW = origin?.let { (it.width() / unit).coerceIn(300f, PinB.LIFT_W) },
        )
        val holeKnown = cut != null
        val t = HandlerThread("pin-b", android.os.Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        thread = t
        val h = Handler(t.looper)
        gl = h
        h.post { play(Surface(st), width, height, unit, place, holeKnown) }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        running = false
        val t = thread ?: return true
        // Its OpenGL let go on its own thread before the texture goes.
        gl?.post { releaseGl() }
        t.quitSafely()
        runCatching { t.join(500) }
        thread = null; gl = null
        return true
    }

    // ---- on the pin-b thread ----

    private var host: PinGl.Host? = null
    private var front: PinBGl.Front? = null
    private var output: Surface? = null
    private val work = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "pin-b-levels").apply { isDaemon = true } }
    private var pageAt = 0L

    private fun play(surface: Surface, w: Int, h: Int, unit: Float, place: PinB.Place, holeKnown: Boolean) {
        output = surface
        val began = SystemClock.uptimeMillis()
        val timeline: PinB.Timeline
        try {
            timeline = PinB.Timeline(place)
            val ho = PinGl.Host(surface)
            check(ho.make()) { "no OpenGL on the surface" }
            host = ho
            // The content: the notes page with this note (a picture's with its picture), the picture the back will end on.
            val pic = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888).also {
                PinFace.draw(Canvas(it), 976f, 596f, text, PinFace.stamp(System.currentTimeMillis(), System.currentTimeMillis()), 0, 1, false, image = image, picture = picture)
            }
            val bytes = PinBGl.rgba(pic); pic.recycle()
            val photo = PinBGl.Levels(976, 596).also { it.putAll(bytes) }
            val f = PinBGl.Front(w, h, unit, light.env, light.light, photo)
            front = f
            // Its blur levels in the draft's measure (its picture was 1171 px wide): off this thread, taken when made.
            work.execute {
                val lv = BlurLevels.levels(bytes, 976, 596, 976f / 1171f)
                gl?.post { if (front === f && host?.current() == true) runCatching { photo.putLevels(lv) } }
            }
            behind?.let { pageFrom(it) }
            Log.i(TAG, "beam B: ready in ${SystemClock.uptimeMillis() - began} ms (${if (PinBGl.floatTargets()) "with" else "without"} its bloom; the hole ${if (holeKnown) "from the screen" else "not known"})")
        } catch (e: Throwable) {
            Log.w(TAG, "beam B: OpenGL failed: ${e.message.orEmpty().take(160)} — the plain drawing")
            releaseGl()
            running = false
            main.post { if (!over) { over = true; onFailed() } }
            return
        }
        var began0 = 0L
        var told = false; var blurred = -1
        // THE DROP ON THE ISLAND'S FRAME (service/Droplet): asked for [CHIME_AHEAD_MS] before, for the frame the island
        // first shows on, as the display says it puts that frame on the glass; the island's real first frame is logged.
        var chimeSet = false; var islandSeen = false; var islandDue = 0L; var latency = 0L; var period = 0L
        val gaps = ArrayList<Float>(600)
        var last = 0L
        val chor = Choreographer.getInstance()
        val tick = object : Choreographer.FrameCallback {
            override fun doFrame(nanos: Long) {
                if (!running) { releaseGl(); return }
                if (began0 == 0L) { began0 = nanos; run.beganAt = SystemClock.uptimeMillis(); run.armChime(); Log.i(TAG, "beam B: the front begins${if (origin != null) " (from where the words are)" else ""}") }
                if (last != 0L) gaps += (nanos - last) / 1e6f
                last = nanos
                val t = (nanos - began0) / 1e6f / slow
                val f = timeline.at(t)
                val radius = (f.blur * 0.7f).toInt()
                if (radius != blurred) { blurred = radius; main.post { runCatching { blur(radius) } } }
                // The page behind (where its picture is): in over 120 ms as it comes, out as the frost clears.
                val pageOn = if (pageAt == 0L) 0f else (((SystemClock.uptimeMillis() - pageAt) / 120f).coerceIn(0f, 1f) * PinB.seg(f.blur, 0f, 12f, 0f, 1f))
                try {
                    val ho = host!!
                    ho.current()
                    front!!.draw(f, place.screenH, place.holeX, place.holeY, place.holeR, holeKnown, pageOn)
                    ho.swap()
                    // Slowed (a debug build's look): which moment each picture is, to read captures by.
                    if (slow > 1f) Log.i(TAG, "beam B: a picture at ${t.toInt()} ms")
                } catch (e: Throwable) {
                    Log.w(TAG, "beam B: a frame failed: ${e.message.orEmpty().take(120)}")
                    stop("OpenGL"); releaseGl(); return
                }
                if (!told && t >= PinB.TELL_AT) { told = true; main.post { run.tellBack() } }
                if (!chimeSet && t >= PinB.CHIME_AT - CHIME_AHEAD_MS) {
                    chimeSet = true
                    // The next frame's own data: its expected time on the glass and the vsync period.
                    chor.postVsyncCallback { data ->
                        val ft = data.frameTimeNanos
                        val lines = data.frameTimelines
                        val p = if (lines.size >= 2) (lines[1].expectedPresentationTimeNanos - lines[0].expectedPresentationTimeNanos) else 0L
                        period = if (p in 4_000_000L..34_000_000L) p else medianGap(gaps)
                        latency = DropletTime.presentOf(ft, data.preferredFrameTimeline.expectedPresentationTimeNanos, period, HAND_ON) - ft
                        islandDue = DropletTime.firstFrameAfter(ft, period, began0, PinB.CHIME_AT, slow)
                        run.chimeAt(islandDue + latency, "the island's frame ${"%.1f".format((islandDue - ft) / 1e6)} ms after the frame it was asked on, on the glass ${"%.1f".format(latency / 1e6)} ms after it is drawn")
                    }
                }
                if (!islandSeen && f.islandOp > 0f) {
                    islandSeen = true
                    if (latency != 0L) run.islandShown(nanos + latency)
                    Log.i(TAG, "beam B: the island's first frame ${if (islandDue == 0L) "(the drop was not placed)" else if (nanos == islandDue) "as foreseen" else "${"%+.1f".format((nanos - islandDue) / 1e6)} ms from the one foreseen (frames dropped or the clock adjusted)"}")
                }
                if (t >= PinB.FRONT_MS) {
                    Log.i(TAG, "beam B: the front is done; ${stats(gaps)}")
                    running = false; over = true
                    releaseGl()
                    main.post { end() }
                    return
                }
                chor.postFrameCallback(this)
            }
        }
        chor.postFrameCallback(tick)
    }

    /** The page behind's levels, a quarter of its size (it is only ever seen frosted), off this thread. */
    private fun pageFrom(picture: Bitmap) {
        val f = front ?: return
        if (f.page != null) return
        work.execute {
            val small = runCatching {
                val soft = if (picture.config == Bitmap.Config.HARDWARE) picture.copy(Bitmap.Config.ARGB_8888, false) else picture
                val sw = maxOf(8, soft.width / 4); val sh = maxOf(8, soft.height / 4)
                Bitmap.createScaledBitmap(soft, sw, sh, true).also { if (soft !== picture) soft.recycle() }
            }.getOrNull() ?: return@execute
            val bytes = PinBGl.rgba(small)
            // The radii are in the front's 1200-wide units; the picture is the whole screen, a quarter as wide.
            val lv = BlurLevels.levels(bytes, small.width, small.height, small.width / PinB.SCREEN_W)
            val sw = small.width; val sh = small.height
            small.recycle()
            gl?.post {
                if (front !== f || f.page != null || host?.current() != true) return@post
                runCatching { f.page = PinBGl.Levels(sw, sh).also { it.putLevels(lv) }; pageAt = SystemClock.uptimeMillis() }
                Log.i(TAG, "beam B: the page behind is under the frost")
            }
        }
    }

    private fun releaseGl() {
        runCatching { if (host?.current() == true) front?.release() }
        front = null
        runCatching { host?.release() }
        host = null
        runCatching { output?.release() }
        output = null
        work.shutdown()
    }

    private companion object {
        const val TAG = "AiUsagePin"
        /** The drop is placed this long before the island: far more than its track's buffer (40 ms) and one frame. */
        const val CHIME_AHEAD_MS = 450L
        /** A TextureView's picture is composed into its window's next frame: one period more on its way to the glass. */
        const val HAND_ON = 1

        fun medianGap(gaps: List<Float>): Long = if (gaps.isEmpty()) 8_333_333L else (gaps.sorted()[gaps.size / 2] * 1e6f).toLong().coerceIn(4_000_000L, 34_000_000L)

        /** How smooth it was: the pictures, the median and the worst gap, and how many were over one 120 Hz frame and a half or a 60 Hz frame. */
        fun stats(gaps: List<Float>): String {
            if (gaps.isEmpty()) return "no pictures"
            val s = gaps.sorted()
            return "${gaps.size + 1} pictures, median ${"%.1f".format(s[s.size / 2])} ms, worst ${"%.1f".format(s.last())} ms, ${gaps.count { it > 12f }} over 12 ms, ${gaps.count { it > 16.7f }} over 16.7 ms"
        }
    }
}
