package dev.kb896.aiusage.circle

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.graphics.PathParser
import kotlin.math.hypot

/**
 * The floating control for scroll translation shows the target language and a close button.
 * Tap the label to switch languages or drag the control out of the way.
 */
@SuppressLint("ViewConstructor")
class PillView(private val svc: CircleOverlay) : View(svc) {
    lateinit var params: WindowManager.LayoutParams
    var target = svc.target()
        private set
    private var status: String? = null
    private var busy = false

    private val d = resources.displayMetrics.density
    private val tl = TrLook(resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val medium = Typeface.create(Typeface.create("google-sans", Typeface.NORMAL), 500, false)
    private val regular = Typeface.create("google-sans", Typeface.NORMAL)
    private val gPaths = listOf(
        0xFFEA4335.toInt() to "M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z",
        0xFF4285F4.toInt() to "M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z",
        0xFFFBBC05.toInt() to "M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z",
        0xFF34A853.toInt() to "M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z",
    ).map { it.first to PathParser.createPathFromPathData(it.second) }
    private val slop = ViewConfiguration.get(svc).scaledTouchSlop
    private val pill = RectF()
    private val closeBtn = RectF()

    /** Room round the pill inside its window, for the shadow. Touches there are not kept. */
    val margin = (10 * d).toInt()
    val wantW = (TrLook.PILL_W * d).toInt() + 2 * margin
    val wantH = (TrLook.PILL_H * d).toInt() + 2 * margin
    /** Where the window's bottom edge rests above the screen's bottom edge. */
    val restY = (TrLook.PILL_BOTTOM * d).toInt() - margin

    fun setLangs(src: String, tgt: String) { target = tgt; invalidate() }
    fun setStatus(s: String?) { status = s; invalidate() }
    fun setBusy(b: Boolean) { busy = b; invalidate() }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(wantW, wantH) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        pill.set(margin.toFloat(), margin.toFloat(), (w - margin).toFloat(), (h - margin).toFloat())
        val cd = 36 * d
        closeBtn.set(pill.right - 15.6f * d - cd, pill.centerY() - cd / 2, pill.right - 15.6f * d, pill.centerY() + cd / 2)
    }

    /** Comes up from below the screen edge, a little past its place and back . */
    fun rise() {
        val wm = svc.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TrLook.PILL_IN_MS; interpolator = android.view.animation.OvershootInterpolator(1.1f)
            addUpdateListener {
                if (!isAttachedToWindow || dragging) return@addUpdateListener
                val f = it.animatedValue as Float
                params.y = (-wantH + (restY + wantH) * f).toInt()
                runCatching { wm.updateViewLayout(this@PillView, params) }
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        p.color = tl.capsule
        p.setShadowLayer(7 * d, 0f, 2 * d, 0x38000000)
        c.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, p)
        p.clearShadowLayer()
        // G, 26 dp, 20 dp from the pill's left end
        val gs = 26 * d
        c.save(); c.translate(pill.left + 20 * d, pill.centerY() - gs / 2); c.scale(gs / 48f, gs / 48f)
        for ((col, path) in gPaths) { p.color = col; c.drawPath(path, p) }
        c.restore()
        // two lines, 62 dp from the left: baselines 29.3 and 48.5 dp below the pill's top
        val tx = pill.left + 62 * d
        val room = closeBtn.left - 8 * d - tx
        text.typeface = medium; text.textSize = 16 * d; text.color = tl.title
        c.drawText("Translating", tx, pill.top + 29.3f * d, text)
        text.typeface = regular; text.textSize = 14 * d; text.color = tl.sub
        val second = status ?: "to ${Tr.name(target)}"
        c.drawText(TextUtils.ellipsize(second, text, room, TextUtils.TruncateAt.END).toString(), tx, pill.top + 48.5f * d, text)
        // close
        p.color = tl.closeBg
        c.drawOval(closeBtn, p)
        p.color = tl.title; p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * d; p.strokeCap = Paint.Cap.ROUND
        val q = 4.7f * d
        c.drawLine(closeBtn.centerX() - q, closeBtn.centerY() - q, closeBtn.centerX() + q, closeBtn.centerY() + q, p)
        c.drawLine(closeBtn.centerX() + q, closeBtn.centerY() - q, closeBtn.centerX() - q, closeBtn.centerY() + q, p)
        p.style = Paint.Style.FILL
    }

    private var downX = 0f; private var downY = 0f; private var startY = 0; private var dragging = false; private var inside = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> { svc.userTouch(); return false }
            MotionEvent.ACTION_DOWN -> {
                inside = pill.contains(e.x, e.y)
                if (!inside) { svc.userTouch(); return false }
                downX = e.rawX; downY = e.rawY; startY = params.y; dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (inside) {
                if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) dragging = true
                if (dragging) {
                    params.y = (startY - (e.rawY - downY).toInt()).coerceAtLeast(-margin)
                    (svc.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(this, params)
                }
            }
            MotionEvent.ACTION_UP -> if (inside && !dragging) {
                if (e.x > closeBtn.left - 8 * d) { Dbg.log("scroll and translate off (close button)"); svc.dismiss() }
                else if (e.x > pill.left + 56 * d) svc.toggleTarget()
            }
        }
        return true
    }
}
