package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RuntimeShader
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import dev.kb896.aiusage.rear.EarbudsEdge
import dev.kb896.aiusage.service.EarbudsGate
import kotlin.math.max

/** One earbuds connection: who, when it began (uptimeMillis), and what came in for the back's moment (set from another thread). */
class EarbudsMoment(val name: String?, val address: String?, val at: Long) {
    @Volatile var picture: Bitmap? = null
    @Volatile var level: Int? = null
}

/**
 * THE FRONT'S HALF OF EARBUDS CONNECTING: a fluid Siri-style glow blooming from all rounded edges together, once ([EarbudsEdge]),
 * light over the page as it is. Nothing else: no card, no island, no picture, no level, no words.
 * It never takes focus and never takes a touch: ONE WINDOW, always NOT_FOCUSABLE and NOT_TOUCHABLE ([params]), put
 * there one of two ways:
 *  - by the pin's accessibility service when he has switched it on, as an accessibility overlay: a trusted window,
 *    so touches pass through it at full opacity; or
 *  - as an app overlay ("Display over other apps", his to allow), at 0.8 opacity: Android blocks touches that pass
 *    through another app's overlay that is more opaque than that (untrusted touch occlusion, Android 12 and later),
 *    and a touch he makes meanwhile must reach what he is in.
 * NOT the pin beam's way (a SurfaceControlViewHost attached to the display): that host is a window of its own in
 * the input, the size of the screen, and every touch would land on it and go nowhere.
 * The window is taken away when the edge light is over, and after [LONGEST_MS] whatever happens (a screen gone off
 * mid-way draws no frame that could end it).
 */
object EarbudsOverlay {
    private const val TAG = "AiUsageBuds"
    private var active: EarbudsEdgeView? = null
    /** The most an app overlay may cover and still let touches through (InputManager's default maximum obscuring opacity). */
    const val PASS_THROUGH_ALPHA = 0.8f
    /** Taken away by then all the same. */
    const val LONGEST_MS = EarbudsEdge.RUN_MS + 1_500L

    fun show(context: Context, way: EarbudsGate.Way, onEnded: () -> Unit = {}): Boolean = when (way) {
        EarbudsGate.Way.GLASS_SERVICE -> dev.kb896.aiusage.service.PinGlassService.windowContext()?.let { window(it, way, onEnded) } == true
            || (android.provider.Settings.canDrawOverlays(context) && window(context, EarbudsGate.Way.APP_OVERLAY, onEnded))
        EarbudsGate.Way.APP_OVERLAY -> window(context, way, onEnded)
        EarbudsGate.Way.NONE -> false
    }

    /**
     * The window's parameters for [way]: never focus, never a touch; trusted by the service, see-through enough for
     * touches as an app overlay. The WHOLE screen, bars included (the edge light starts at the bottom edge itself, not over
     * the navigation bar's inset). [refresh] (Hz, 0: none) asks the panel for its top rate while the edge light plays: the
     * app under it may have it at 60 or lower, and the edge light's steps are made for 120.
     */
    fun params(way: EarbudsGate.Way, refresh: Float = 0f): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        if (way == EarbudsGate.Way.GLASS_SERVICE) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        // FLAG_LAYOUT_IN_SCREEN alone still fits the window inside the navigation bar's inset
        fitInsetsTypes = 0
        if (refresh > 0f) preferredRefreshRate = refresh
        alpha = if (way == EarbudsGate.Way.GLASS_SERVICE) 1f else PASS_THROUGH_ALPHA
        title = "Earbuds"
    }

    /** The front display's top refresh rate at its present resolution (0: not known). Through DisplayManager: the app's own context has no display of its own. */
    private fun topRate(context: Context): Float = runCatching {
        val d = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return 0f
        val now = d.mode
        d.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }.maxOf { it.refreshRate }
    }.getOrDefault(0f)

    private fun window(context: Context, way: EarbudsGate.Way, onEnded: () -> Unit): Boolean = runCatching {
        if (active != null) return false
        val wm = context.getSystemService(WindowManager::class.java)
        val handler = android.os.Handler.createAsync(android.os.Looper.getMainLooper())
        lateinit var view: EarbudsEdgeView
        var gone = false
        lateinit var watchdog: Runnable
        val complete = {
            if (!gone) {
                gone = true
                if (active === view) active = null
                handler.removeCallbacks(watchdog)
                onEnded()
            }
        }
        // Drawing posts its end outside traversal. Immediate removal confirms cleanup even when
        // the window never attached, so no detach callback will be delivered.
        val end = {
            if (!gone) {
                runCatching { wm.removeViewImmediate(view) }
                    .onSuccess { complete() }
                    .onFailure { error ->
                        if (view.parent == null && !view.isAttachedToWindow) {
                            complete() // Already removed, including removal before the first attachment.
                        } else {
                            Log.w(TAG, "earbuds: overlay removal failed; retrying", error)
                            handler.removeCallbacks(watchdog)
                            handler.postDelayed(watchdog, 100L)
                        }
                    }
            }
            Unit
        }
        watchdog = Runnable { end() }
        view = EarbudsEdgeView(context, end, complete)
        wm.addView(view, params(way, topRate(context)))
        active = view
        handler.postDelayed(watchdog, LONGEST_MS)
        Log.i(TAG, "earbuds: the edge light is up (${if (way == EarbudsGate.Way.GLASS_SERVICE) "accessibility overlay" else "app overlay"})")
        true
    }.onFailure { Log.w(TAG, "earbuds: no overlay (${way.name.lowercase()}): ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}") }.getOrDefault(false)
}

/** One precompiled shader, four non-overlapping strips, and reused uniforms. Only the edge band is shaded.
 * The clock is continuous through rotation; current window size and independently reported corners are read again. */
class EarbudsEdgeView(context: Context, private val end: () -> Unit, private val detached: () -> Unit = {}) : View(context) {
    private val shader = runCatching { RuntimeShader(EarbudsEdge.AGSL) }
        .onFailure { Log.w("AiUsageBuds", "earbuds: no edge shader: ${it.javaClass.simpleName}") }.getOrNull()
    private val paint = Paint().apply { shader = this@EarbudsEdgeView.shader }
    private val clock = EarbudsEdge.Clock()
    private val frame = EarbudsEdge.Frame()
    private val uniforms = Uniforms()
    private var geometry: EarbudsEdge.Geometry? = null
    private var frames = 0
    private var worst = 0L
    private var lastAt = -1L
    private var finished = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGeometry()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateGeometry()
    }

    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        updateGeometry()
        return insets
    }

    private fun updateGeometry() {
        if (width <= 0 || height <= 0) return
        // Display reports positions in its current rotation. Missing/invalid values use the measured front fallback.
        fun corner(position: Int): Float = runCatching { display?.getRoundedCorner(position)?.radius?.toFloat() ?: 0f }.getOrDefault(0f)
        geometry = EarbudsEdge.Geometry(width.toFloat(), height.toFloat(),
            corner(android.view.RoundedCorner.POSITION_TOP_LEFT), corner(android.view.RoundedCorner.POSITION_TOP_RIGHT),
            corner(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT), corner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT))
        shader?.let { uniforms.geometry(it, geometry!!) }
    }

    override fun onDraw(canvas: Canvas) {
        if (finished) return
        val now = AnimationUtils.currentAnimationTimeMillis()
        val sh = shader
        val g = geometry
        if (sh == null || g == null) { finish(); return }
        val t = clock.at(now)
        if (clock.running) {
            if (lastAt >= 0) worst = max(worst, now - lastAt)
            lastAt = now; frames++
        }
        if (t >= EarbudsEdge.RUN_MS) { finish(); return }
        EarbudsEdge.at(t, frame)
        uniforms.frame(sh, frame, g.k)
        drawBand(canvas, g, paint)
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        finished = true
        super.onDetachedFromWindow()
        detached()
    }

    private fun finish() {
        if (finished) return
        finished = true
        Log.i("AiUsageBuds", "earbuds: the edge light is over ($frames pictures, the longest gap $worst ms)")
        post { end() }
    }

    /** Arrays select the non-vararg uniform overload: no per-frame Kotlin arrays or frame objects. */
    class Uniforms {
        private val size = FloatArray(2)
        private val corners = FloatArray(4)
        private val values = FloatArray(4)
        fun geometry(sh: RuntimeShader, g: EarbudsEdge.Geometry) {
            size[0] = g.w; size[1] = g.h
            corners[0] = g.tl; corners[1] = g.tr; corners[2] = g.br; corners[3] = g.bl
            sh.setFloatUniform("uSize", size); sh.setFloatUniform("uCorners", corners)
        }
        fun frame(sh: RuntimeShader, f: EarbudsEdge.Frame, k: Float) {
            values[0] = f.time; values[1] = f.amp; values[2] = f.spread; values[3] = k
            sh.setFloatUniform("uFrame", values)
        }
    }

    companion object {
        /** No stroke, path mask, offscreen blur or full-screen fragment pass. Corner squares fit inside these strips. */
        fun drawBand(canvas: Canvas, g: EarbudsEdge.Geometry, paint: Paint) {
            // Integer strip boundaries avoid partial pixel coverage at the joins after resolution changes.
            val b = kotlin.math.ceil(g.strip)
            canvas.drawRect(0f, 0f, g.w, b, paint)
            canvas.drawRect(0f, g.h - b, g.w, g.h, paint)
            canvas.drawRect(0f, b, b, g.h - b, paint)
            canvas.drawRect(g.w - b, b, g.w, g.h - b, paint)
        }
    }
}
