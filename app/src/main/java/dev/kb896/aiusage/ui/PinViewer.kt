package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import dev.kb896.aiusage.ui.PinViewMotion.Spring
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A PINNED PICTURE ON THE FRONT, FULL SCREEN (ui/PinViewActivity): black, the picture fitted, nothing else but its
 * caption, small, where it has one. It moves as a photo does in Apple's and Xiaomi's own viewers (the numbers are
 * [PinViewMotion]'s):
 *
 * - it OPENS out of the back's place ([begin]): from where the back's page has it, seen from the front, growing to the
 *   screen on a spring while the black comes up behind it;
 * - PINCH to zoom about the fingers, ONE FINGER to move it once it is zoomed in, gliding on when let go and held at its
 *   edges; DOUBLE TAP in about the tap, or back out; a single tap shows or hides the caption;
 * - A STROKE DOWN at the fit: it follows the finger, smaller and rounder, the black lighter; let go far or quickly enough
 *   it goes on down and away at the finger's speed ([closeAway]); else it springs back;
 * - BACK ([backProgress], [closeBack]): it follows the back gesture a little, and closes INTO THE BACK'S PLACE, the way
 *   it came; a pinch let go far under the fit does the same.
 * When it is gone, [onClosed] (the screen goes, with no window animation of its own).
 *
 * The picture is drawn as it is (sRGB, filtered, read at mipmap levels when smaller than its pixels), rounded only where
 * it moves (at the back's place, and on its way down).
 */
class PinViewer(
    context: Context,
    private val picture: Bitmap,
    caption: String,
    /** The picture's place on the back's page (rear pixels of 976x596, PinFace.pictureAt), where it comes from; null: the page's picture place. */
    private val place: FloatArray?,
    private val onClosed: () -> Unit,
) : View(context) {

    enum class Phase { WAITING, OPENING, OPEN, CLOSING_BACK, CLOSING_AWAY, CLOSED }

    var phase = Phase.WAITING
        private set

    private val open = Spring(0f, 0f, PinViewMotion.OPEN_DAMPING, PinViewMotion.OPEN_RESPONSE)
    private val zoom = Spring(1f, 1f, PinViewMotion.SETTLE_DAMPING, PinViewMotion.SETTLE_RESPONSE)
    private val panX = Spring(0f, 0f, PinViewMotion.SETTLE_DAMPING, PinViewMotion.SETTLE_RESPONSE)
    private val panY = Spring(0f, 0f, PinViewMotion.SETTLE_DAMPING, PinViewMotion.SETTLE_RESPONSE)
    /**
     * A stroke down: THE FINGER'S OWN WAY (not the picture's centre's). The picture is drawn that far moved, and smaller
     * about the point the finger took ([grabX], [grabY]; [full]), so that point stays under the finger all the way.
     */
    private val strokeX = Spring(0f, 0f, PinViewMotion.SETTLE_DAMPING, PinViewMotion.SETTLE_RESPONSE)
    private val strokeY = Spring(0f, 0f, PinViewMotion.SETTLE_DAMPING, PinViewMotion.SETTLE_RESPONSE)
    private val back = Spring(0f, 0f, PinViewMotion.SETTLE_DAMPING, 0.3f)
    private val captionShown = Spring(1f, 1f, 1f, 0.25f)
    private val springs = listOf(open, zoom, panX, panY, strokeX, strokeY, back, captionShown)

    /** How far the open has come (0 at the back's place, 1 on the screen): for the screen and a test. */
    val opened: Float get() = open.value
    /** The zoom (1 = fitted), for a test. */
    val zoomNow: Float get() = zoom.value
    /** The picture shown, for a test. */
    internal val shownPicture: Bitmap get() = picture
    /** Where the picture is drawn now (view pixels), for a test. */
    internal val shownPlace: RectF get() = RectF().also { drawn(it) }
    /** The most it zooms in here, for a test. */
    internal val zoomMost: Float get() = most

    private val density = resources.displayMetrics.density
    private var fit = floatArrayOf(0f, 0f, 0f, 0f)
    private var from = floatArrayOf(0f, 0f, 0f, 0f, 0f)
    private var most = 3f
    private var beginWhenLaidOut = false

    private val shot = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shader = BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val matrix = Matrix()
    private val rect = RectF()
    private val src = RectF(0f, 0f, picture.width.toFloat(), picture.height.toFloat())
    private val black = Paint().apply { color = 0xFF000000.toInt() }
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
    }
    private val caption = caption.trim()
    private var captionLayout: StaticLayout? = null

    init {
        // Read at mipmap levels where it is drawn smaller than its pixels: a 2400 px picture on a 1200 px screen stays fine.
        runCatching { picture.setHasMipMap(true) }
        shot.shader = shader
        contentDescription = this.caption.ifEmpty { "Pinned picture" }
        isClickable = true
    }

    // ---- the open and the closes -----------------------------------------------------------------------------------

    /** The open, from the back's place (at once if laid out, else as soon as it is). */
    fun begin() {
        if (width == 0 || height == 0) { beginWhenLaidOut = true; return }
        beginWhenLaidOut = false
        open.hold(0f)
        open.damping = PinViewMotion.OPEN_DAMPING; open.response = PinViewMotion.OPEN_RESPONSE
        open.target = 1f
        phase = Phase.OPENING
        run()
    }

    /** Closed INTO THE BACK'S PLACE, from wherever it is: Back, or a pinch let go far under the fit. */
    fun closeBack() {
        if (phase == Phase.CLOSED || phase == Phase.CLOSING_BACK || phase == Phase.CLOSING_AWAY) return
        if (phase == Phase.WAITING) { done(); return }
        phase = Phase.CLOSING_BACK
        open.damping = PinViewMotion.CLOSE_DAMPING; open.response = PinViewMotion.CLOSE_RESPONSE
        open.target = 0f
        // What the finger left (a drag, a back gesture's lean) closes with it: it is all one picture going home.
        run()
    }

    /** A stroke down let go: on down and away at the finger's speed, the black going as it goes. */
    private fun closeAway(vx: Float, vy: Float) {
        phase = Phase.CLOSING_AWAY
        awayFrom = strokeY.value
        strokeY.damping = 1f; strokeY.response = 0.32f
        strokeY.velocity = maxOf(vy, 0f)
        strokeY.target = height * 1.2f
        strokeX.velocity = vx
        strokeX.target = strokeX.value + vx * 0.12f
        run()
    }

    /** Where a stroke down was let go: the black goes from there on, with no step at the lift. */
    private var awayFrom = 0f

    private fun done() {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        onClosed()
    }

    // ---- the back gesture --------------------------------------------------------------------------------------------

    /** The back gesture under way, [progress] 0 to 1: the picture leans away a little, following it. */
    fun backProgress(progress: Float) {
        if (phase != Phase.OPEN && phase != Phase.OPENING) return
        back.hold(progress.coerceIn(0f, 1f))
        invalidate()
    }

    /** The back gesture called off: the lean springs back. */
    fun backCancelled() { back.target = 0f; run() }

    // ---- frames ----------------------------------------------------------------------------------------------------

    private var lastFrame = 0L
    private var ticking = false
    private val tick = Choreographer.FrameCallback { now ->
        ticking = false
        val dt = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        lastFrame = now
        step(dt)
        invalidate()
        if (moving()) run() else lastFrame = 0L
    }

    private fun run() {
        if (ticking) return
        ticking = true
        Choreographer.getInstance().postFrameCallback(tick)
    }

    /**
     * Whether anything still moves. A spring the finger holds stands at its own target, still ([Spring.hold]), so it is
     * not moving and stepping it leaves it where the finger put it; a spring near enough its target is landed there.
     */
    private fun moving(): Boolean = phase != Phase.CLOSED &&
        (phase == Phase.OPENING || phase == Phase.CLOSING_BACK || phase == Phase.CLOSING_AWAY || springs.any { it.value != it.target || it.velocity != 0f })

    /** Near enough: a thousandth of the way for the parts (open, zoom, lean, caption), half a pixel for a move. */
    private fun near(s: Spring): Float = if (s === open || s === zoom || s === back || s === captionShown) 0.001f else 0.5f

    private fun step(dt: Float) {
        for (s in springs) { s.step(dt); if (s.settled(near(s), near(s) * 30f)) s.land() }
        when (phase) {
            Phase.OPENING -> if (open.value == open.target && open.velocity == 0f) phase = Phase.OPEN
            Phase.CLOSING_BACK -> if (open.value <= 0.004f && abs(open.velocity) < 0.2f) { open.land(); done() }
            Phase.CLOSING_AWAY -> if (shownTop() >= height || (strokeY.value == strokeY.target && strokeY.velocity == 0f)) done()
            else -> Unit
        }
    }

    // ---- layout and drawing ----------------------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit = PinViewMotion.fit(picture.width, picture.height, w.toFloat(), h.toFloat())
        from = PinViewMotion.origin(w.toFloat(), h.toFloat(), display?.rotation ?: 0, place, picture.width, picture.height)
        val fw = fit[2] - fit[0]
        most = PinViewMotion.maxZoom(if (fw > 0f) picture.width / fw else 1f)
        captionLayout = if (caption.isEmpty()) null else StaticLayout.Builder
            .obtain(caption, 0, caption.length, words, (w - 48f * density).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
        // Turned while zoomed: back to the fit, as photos do.
        if (oldw != 0 && (oldw != w || oldh != h)) { zoom.hold(1f); panX.hold(0f); panY.hold(0f) }
        if (beginWhenLaidOut) begin()
    }

    /**
     * The picture's rect as it stands, before the open's share: the fit, zoomed, moved, leaning. A stroke down moves it
     * the finger's way and shrinks it about the point the finger took: that point is where the finger is.
     */
    private fun full(out: RectF) {
        val k = PinViewMotion.dragScale(PinViewMotion.dragPart(strokeY.value, height.toFloat()))
        val s = zoom.value * k * (1f - BACK_LEAN * back.value)
        val fw = (fit[2] - fit[0]) * s; val fh = (fit[3] - fit[1]) * s
        val cx = width / 2f + panX.value + strokeX.value + grabX * (1f - k)
        val cy = height / 2f + panY.value + strokeY.value + grabY * (1f - k)
        out.set(cx - fw / 2f, cy - fh / 2f, cx + fw / 2f, cy + fh / 2f)
    }

    /** Where it is drawn: [full], the open's share of the way from the back's place. */
    private fun drawn(out: RectF) {
        full(out)
        val p = open.value
        out.set(PinViewMotion.lerp(from[0], out.left, p), PinViewMotion.lerp(from[1], out.top, p),
            PinViewMotion.lerp(from[2], out.right, p), PinViewMotion.lerp(from[3], out.bottom, p))
    }

    private fun shownTop(): Float { drawn(rect); return rect.top }

    override fun onDraw(c: Canvas) {
        if (phase == Phase.WAITING || phase == Phase.CLOSED || width == 0) return
        val p = open.value
        val h = height.toFloat()
        val part = PinViewMotion.dragPart(strokeY.value, h)
        val away = if (phase == Phase.CLOSING_AWAY) ((strokeY.value - awayFrom) / (strokeY.target - awayFrom).coerceAtLeast(1f)).coerceIn(0f, 1f) else 0f
        // Lighter as it goes: down, away, leaning with Back, pinched under the fit (all ways out).
        val under = ((1f - zoom.value) / (1f - PinViewMotion.PINCH_LEAST)).coerceIn(0f, 1f)
        val light = p.coerceIn(0f, 1f) * (1f - PinViewMotion.DRAG_LIGHTEN * part) * (1f - away) * (1f - 0.35f * back.value) * (1f - 0.6f * under)
        black.alpha = (255f * light).toInt().coerceIn(0, 255)
        c.drawRect(0f, 0f, width.toFloat(), h, black)

        drawn(rect)
        val lean = maxOf(part, back.value)
        val corner = PinViewMotion.lerp(from[4], MOVING_CORNER * density * lean, p.coerceIn(0f, 1f)).coerceAtLeast(0f)
        matrix.setRectToRect(src, rect, Matrix.ScaleToFit.FILL)
        shader.setLocalMatrix(matrix)
        if (corner < 0.5f) c.drawRect(rect, shot) else c.drawRoundRect(rect, corner, corner, shot)

        val layout = captionLayout ?: return
        val still = (1f - (zoom.value - 1f) * 4f).coerceIn(0f, 1f) * (1f - part)
        val a = light * captionShown.value * still * if (phase == Phase.OPEN || phase == Phase.OPENING) 1f else 0f
        if (a <= 0.01f) return
        words.alpha = (230f * a).toInt()
        val inset = rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemGestures())?.bottom ?: 0
        c.save()
        c.translate(24f * density, h - inset - 28f * density - layout.height)
        layout.draw(c)
        c.restore()
    }

    // ---- the finger ------------------------------------------------------------------------------------------------

    private enum class Hold { NONE, PAN, DRAG, PINCH, DONE }
    private var hold = Hold.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    /** A pan held past its edges: the finger's own way, before the give. */
    private var rawPanX = 0f
    private var rawPanY = 0f
    /**
     * Where the finger took the picture, from its centre: a stroke down shrinks the picture about that point ([full]).
     * It stays through the stroke's way home, and a finger catching the picture on that way keeps it (no jump).
     */
    private var grabX = 0f
    private var grabY = 0f
    /** The stroke's way when the finger landed: caught on its way home, the finger takes it on from there. */
    private var strokeFromX = 0f
    private var strokeFromY = 0f
    private var rawZoom = 1f
    private var tracker: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var fx = 0f
        private var fy = 0f
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            if (phase != Phase.OPEN && phase != Phase.OPENING) return false
            // A stroke down under way gives way to the pinch: the picture goes back to its place under the fingers.
            if (hold == Hold.DRAG) { strokeX.target = 0f; strokeY.target = 0f }
            hold = Hold.PINCH
            // Caught under the fit or past the most: the fingers take it on from there, the give as it was (no step).
            rawZoom = PinViewMotion.pinchUnheld(zoom.value, most)
            zoom.hold(zoom.value); panX.hold(panX.value); panY.hold(panY.value)
            fx = d.focusX; fy = d.focusY
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val before = zoom.value
            rawZoom *= d.scaleFactor
            val after = PinViewMotion.pinchHeld(rawZoom, most)
            // About the fingers, and with them as they move together.
            val ox = d.focusX - width / 2f; val oy = d.focusY - height / 2f
            panX.hold(PinViewMotion.panAbout(panX.value, ox, before, after) + (d.focusX - fx))
            panY.hold(PinViewMotion.panAbout(panY.value, oy, before, after) + (d.focusY - fy))
            fx = d.focusX; fy = d.focusY
            zoom.hold(after)
            invalidate()
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            hold = Hold.DONE
            if (zoom.value < PinViewMotion.PINCH_CLOSE) { closeBack(); return }
            settleTo(zoom.value.coerceIn(1f, most), 0f, 0f)
        }
    })

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (phase != Phase.OPEN && phase != Phase.OPENING) return false
            val fw = fit[2] - fit[0]; val fh = fit[3] - fit[1]
            val to = PinViewMotion.doubleTapZoom(zoom.value, fw, fh, width.toFloat(), height.toFloat(), most)
            hold = Hold.DONE
            if (to == 1f) settleTo(1f, 0f, 0f, aboutFinger = false)
            else {
                val (lx, ly) = PinViewMotion.panLimit(fw, fh, to, width.toFloat(), height.toFloat())
                // Zoom and pan on one spring each, alike: the point tapped stays under the finger all the way.
                panX.response = PinViewMotion.SETTLE_RESPONSE; panY.response = PinViewMotion.SETTLE_RESPONSE
                zoom.target = to
                panX.target = PinViewMotion.panAbout(panX.value, e.x - width / 2f, zoom.value, to).coerceIn(-lx, lx)
                panY.target = PinViewMotion.panAbout(panY.value, e.y - height / 2f, zoom.value, to).coerceIn(-ly, ly)
                run()
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (caption.isEmpty() || phase != Phase.OPEN) return false
            captionShown.target = if (captionShown.target > 0.5f) 0f else 1f
            run()
            return true
        }
    })

    /** Zoom to [to] (from where it is), the pan within the edges there; [vx], [vy]: the finger's speed carried on. */
    private fun settleTo(to: Float, vx: Float, vy: Float, aboutFinger: Boolean = true) {
        val fw = fit[2] - fit[0]; val fh = fit[3] - fit[1]
        val (lx, ly) = PinViewMotion.panLimit(fw, fh, to, width.toFloat(), height.toFloat())
        zoom.target = to
        panX.response = PinViewMotion.SETTLE_RESPONSE; panY.response = PinViewMotion.SETTLE_RESPONSE
        // (The ratio first: at the same zoom it is exactly 1, and a pan within the edges stays exactly where it is.)
        val px = if (aboutFinger && zoom.value != 0f) panX.value * (to / zoom.value) else 0f
        val py = if (aboutFinger && zoom.value != 0f) panY.value * (to / zoom.value) else 0f
        panX.target = if (to <= 1f) 0f else px.coerceIn(-lx, lx)
        panY.target = if (to <= 1f) 0f else py.coerceIn(-ly, ly)
        panX.velocity = vx; panY.velocity = vy
        run()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (phase == Phase.CLOSED || phase == Phase.CLOSING_BACK || phase == Phase.CLOSING_AWAY || phase == Phase.WAITING) return true
        val t = tracker ?: VelocityTracker.obtain().also { tracker = it }
        t.addMovement(e)
        // The landing first: a double tap's second landing then sets its own zoom over it (the detectors below).
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            hold = Hold.NONE
            downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
            // Caught as it moves, ALL of it (the zoom too, or the pan would stop while the zoom went on): it stays under
            // the finger, and the lift puts it where it belongs ([lifted]).
            zoom.hold(zoom.value); panX.hold(panX.value); panY.hold(panY.value); strokeX.hold(strokeX.value); strokeY.hold(strokeY.value)
            val w = width.toFloat(); val h = height.toFloat()
            val (lx, ly) = PinViewMotion.panLimit(fit[2] - fit[0], fit[3] - fit[1], zoom.value, w, h)
            // Caught past an edge: the finger takes it on from there, the give as it was (no step).
            rawPanX = PinViewMotion.unheld(panX.value, lx, w); rawPanY = PinViewMotion.unheld(panY.value, ly, h)
            strokeFromX = strokeX.value; strokeFromY = strokeY.value
            // Taken at rest: about the point under the finger. Caught on its way home: about the point it had (no jump).
            if (strokeFromX == 0f && strokeFromY == 0f) { full(rect); grabX = e.x - rect.centerX(); grabY = e.y - rect.centerY() }
        }
        pinch.onTouchEvent(e)
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (hold == Hold.DRAG) { strokeX.target = 0f; strokeY.target = 0f; run() }
            MotionEvent.ACTION_MOVE -> if (!pinch.isInProgress && e.pointerCount == 1) moved(e)
            MotionEvent.ACTION_UP -> { t.computeCurrentVelocity(1000); lifted(t.xVelocity, t.yVelocity); end() }
            MotionEvent.ACTION_CANCEL -> { lifted(0f, 0f); end() }
        }
        return true
    }

    private fun end() {
        tracker?.recycle(); tracker = null
        run()
    }

    private fun moved(e: MotionEvent) {
        val dx = e.x - lastX; val dy = e.y - lastY
        lastX = e.x; lastY = e.y
        if (hold == Hold.NONE) {
            if (hypot(e.x - downX, e.y - downY) < slop) return
            hold = if (zoom.value > 1.01f) Hold.PAN else if (phase == Phase.OPEN) Hold.DRAG else Hold.NONE
            if (hold == Hold.NONE) return
        }
        val w = width.toFloat(); val h = height.toFloat()
        when (hold) {
            Hold.PAN -> {
                val (lx, ly) = PinViewMotion.panLimit(fit[2] - fit[0], fit[3] - fit[1], zoom.value, w, h)
                rawPanX += dx; rawPanY += dy
                panX.hold(PinViewMotion.held(rawPanX, lx, w)); panY.hold(PinViewMotion.held(rawPanY, ly, h))
            }
            Hold.DRAG -> {
                // The finger's own way: the picture goes with it, smaller about the point it took ([full]), that point under it.
                strokeX.hold(strokeFromX + e.x - downX); strokeY.hold(strokeFromY + e.y - downY)
            }
            else -> return
        }
        invalidate()
    }

    /**
     * The finger off: what it moved goes on at its speed (a glide, the way down and away, the way home), and WHATEVER IT
     * CAUGHT goes where it belongs: the stroke home, the zoom within its ends, the pan within the edges at that zoom.
     */
    private fun lifted(vx: Float, vy: Float) {
        val was = hold
        hold = Hold.NONE
        // Let go far or quickly enough (the finger's own way and speed): down and away.
        if (was == Hold.DRAG && PinViewMotion.dismisses(strokeY.value, vy, width.toFloat())) { closeAway(vx, vy); return }
        strokeX.response = PinViewMotion.SETTLE_RESPONSE; strokeY.response = PinViewMotion.SETTLE_RESPONSE
        if (was == Hold.DRAG) { strokeX.velocity = vx; strokeY.velocity = vy }
        strokeX.target = 0f; strokeY.target = 0f
        when (was) {
            Hold.PAN -> {
                val to = zoom.value.coerceIn(1f, most)
                // Caught past the most and moved: to the most, the pan within the edges there, at the finger's speed.
                if (to != zoom.value) { settleTo(to, vx, vy); return }
                val (lx, ly) = PinViewMotion.panLimit(fit[2] - fit[0], fit[3] - fit[1], to, width.toFloat(), height.toFloat())
                // Past an edge: back to it with the finger's speed; within: on as a glide would go.
                for ((s, v, l) in listOf(Triple(panX, vx, lx), Triple(panY, vy, ly))) {
                    s.velocity = v
                    s.response = if (abs(s.value) > l) PinViewMotion.SETTLE_RESPONSE else PinViewMotion.GLIDE_RESPONSE
                    s.target = if (abs(s.value) > l) s.value.coerceIn(-l, l) else PinViewMotion.glide(s.value, v, l)
                }
            }
            // A tap, a press, a stroke home: zoom and pan from where the finger caught them to where they belong (where
            // they already are, nothing moves).
            Hold.NONE, Hold.DRAG -> settleTo(zoom.value.coerceIn(1f, most), 0f, 0f)
            // The pinch's end or the double tap has sent them where they go.
            Hold.PINCH, Hold.DONE -> Unit
        }
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(tick)
        ticking = false
        super.onDetachedFromWindow()
    }

    private companion object {
        /** The back gesture at its end: the picture this much smaller. */
        const val BACK_LEAN = 0.12f
        /** The corners of a picture on its way (a stroke down, the back gesture), dp: rounded as the display is. */
        const val MOVING_CORNER = 28f
    }
}
