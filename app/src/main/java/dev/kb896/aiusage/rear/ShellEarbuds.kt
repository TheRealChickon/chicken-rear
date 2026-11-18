package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import kotlin.math.max
import kotlin.math.min

/**
 * THE BACK'S MOMENT OF EARBUDS CONNECTING (draft B's back, his earbuds as Sony shows them): when the front's event is
 * over (Xiaomi never runs the two screens at once), Xiaomi's light veil blooms over what the back shows, drains to a
 * line round the screen's edge, and the earbuds come into focus in the middle of what is seen with their level under
 * them; after about three seconds it is gone and the back is as it was. LIGHT ONLY: one see-through layer over the
 * pages ([ShellLayer.Z_WAKE], as the pin's beam), no page of the row changes and the navigation is not asked
 * ([RearNav] is untouched: nothing he could swipe to comes or goes). A touch on the back meanwhile is the page's as
 * always. On [handler]'s thread; every frame from the one clock ([EarbudsMotion.back]).
 */
class ShellEarbuds(private val layerStack: () -> Int, private val geometry: () -> RearGeometry, private val handler: Handler) {
    private var layer: SurfaceControl? = null
    private var surface: Surface? = null
    private var at = 0L
    private var picture: Bitmap? = null
    private var level: Int? = null
    private var frames = 0
    private var worst = 0L
    private var lastAt = 0L
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, 500, false) }

    private var mayShow: () -> Boolean = { false }
    private var askedAt = 0L

    @Volatile var isUp = false
        private set

    /** Plays from [startAt] (uptimeMillis of the front's start) + [EarbudsMotion.BACK_AT]. [mayShow] is asked at that moment. */
    fun play(startAt: Long, picture: Bitmap?, level: Int?, mayShow: () -> Boolean) {
        handler.postAtTime({
            val late = EarbudsMotion.backLate(SystemClock.uptimeMillis(), startAt)
            if (late || !mayShow()) {
                Log.i(TAG, if (late) "earbuds: told too late for the back's moment — nothing shown there" else "earbuds: the back is dark, another's, in a call or under a pop-up — nothing shown there")
                picture?.recycle(); return@postAtTime
            }
            // One chain of frames: a moment over one still playing takes its place (two chains drew every frame twice).
            handler.removeCallbacks(frame)
            this.picture?.recycle()
            this.picture = picture; this.level = level; at = startAt; this.mayShow = mayShow; askedAt = 0L
            frames = 0; worst = 0L; lastAt = 0L
            val rear = geometry()
            if (layer == null) runCatching {
                val sc = ShellLayer.create("earbuds", layerStack(), ShellLayer.Z_WAKE, rear.widthPx, rear.heightPx, trusted = false, visible = false, alpha = 1f, format = PixelFormat.RGBA_8888, opaque = false)
                layer = sc; surface = Surface(sc)
            }.onFailure { Log.w(TAG, "earbuds: no layer: ${it.message}"); return@postAtTime }
            isUp = true
            Log.i(TAG, "earbuds: the back's moment begins${if (level == null) " (no level known)" else ""}")
            frame.run()
        }, startAt + EarbudsMotion.BACK_AT)
    }

    private val frame: Runnable = object : Runnable {
        override fun run() {
            val sc = layer ?: return; val s = surface ?: return
            val now = SystemClock.uptimeMillis()
            if (lastAt != 0L) worst = max(worst, now - lastAt)
            lastAt = now; frames++
            val t = (now - at).toFloat()
            if (t >= EarbudsMotion.BACK_END_MS) { stop(); return }
            // Asked again a few times a second: the back gone dark, a call, the notifications pulled over it: the light goes at once.
            if (now - askedAt >= ASK_EVERY_MS) {
                askedAt = now
                if (!mayShow()) { Log.i(TAG, "earbuds: the back is no longer ours to light — the moment ends"); stop(); return }
            }
            val rear = geometry()
            runCatching {
                val c = runCatching { s.lockHardwareCanvas() }.getOrNull() ?: s.lockCanvas(null)
                try { draw(c, rear, EarbudsMotion.back(t), t) } finally { s.unlockCanvasAndPost(c) }
                SurfaceControl.Transaction().use { it.setVisibility(sc, true).apply() }
            }.onFailure { Log.w(TAG, "earbuds: not drawn: ${it.message}"); stop(); return }
            handler.postAtTime(this, now + 16L)
        }
    }

    /** One picture of the moment at [t] ms with [picture] and [level], for a test's look beside the draft (no layer). */
    internal fun render(c: Canvas, rear: RearGeometry, t: Float, picture: Bitmap?, level: Int?) {
        this.picture = picture; this.level = level
        draw(c, rear, EarbudsMotion.back(t), t)
    }

    /** The panel's own units: 976 x 596 here, the lenses over the left 296; scaled to the real panel. */
    private fun draw(c: Canvas, rear: RearGeometry, b: EarbudsMotion.Back, t: Float) {
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val kx = rear.widthPx / 976f; val ky = rear.heightPx / 596f; val k = min(kx, ky)
        val w = rear.widthPx.toFloat(); val h = rear.heightPx.toFloat()
        // what was there steps back (no blur of the layers under ours to be had from here: a dim)
        if (b.dim > 0.002f) c.drawColor(Color.argb((b.dim * 255).toInt(), 0, 0, 0))
        val vertical = LinearGradient(0f, 0.08f * h, 0f, 0.95f * h, EarbudsMotion.APRICOT, EarbudsMotion.BLUE, Shader.TileMode.CLAMP)
        // Xiaomi's veil: blooms from right of the middle to the whole panel, then drains to a line round the edge
        if (b.veil >= 0f) {
            val (amp, edge) = EarbudsMotion.veil(b.veil)
            val radius = 1300f * k * (1f - (1f - min(1f, b.veil / 300f)).let { it * it * it })
            if (amp > 0.01f && radius > 1f) {
                val ox = 700f * kx; val oy = 330f * ky
                val layerId = c.saveLayer(0f, 0f, w, h, null)
                fill.shader = vertical; fill.alpha = (amp * 0.85f * 0.72f * 255).toInt(); c.drawRect(0f, 0f, w, h, fill)
                // lavender at the sides, as Xiaomi's veil has it
                fill.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(EarbudsMotion.LAVENDER, EarbudsMotion.LAVENDER and 0x00FFFFFF, EarbudsMotion.LAVENDER and 0x00FFFFFF, EarbudsMotion.LAVENDER), floatArrayOf(0.30f, 0.52f, 0.72f, 1f), Shader.TileMode.CLAMP)
                fill.alpha = (amp * 0.85f * 0.45f * 255).toInt(); c.drawRect(0f, 0f, w, h, fill)
                fill.shader = RadialGradient(ox, oy, radius, intArrayOf(Color.BLACK, Color.BLACK, 0), floatArrayOf(0f, max(0f, 1f - 450f * k / radius).coerceAtMost(0.999f), 1f), Shader.TileMode.CLAMP)
                fill.alpha = 255; fill.xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_IN); c.drawRect(0f, 0f, w, h, fill)
                fill.xfermode = null; fill.shader = null
                c.restoreToCount(layerId)
            }
            if (edge > 0.01f) edgeLine(c, rear, vertical, edge)
        }
        if (b.edgeHold > 0.01f) edgeLine(c, rear, vertical, b.edgeHold)
        // the earbuds, as Sony shows them, in the middle of what is seen (x 296 to 976: its middle is 636)
        val cx = 636f * kx; val cy = 232f * ky; val ph = 175f * k
        val glow = ((0.06f + 0.14f * EarbudsMotion.breath(t)) * b.breathe + 0.4f * b.flare) * b.picture
        if (glow > 0.01f) {
            fill.shader = RadialGradient(cx, cy, ph * 1.2f, intArrayOf(Color.argb((min(1f, glow) * 255).toInt(), 255, 245, 240), 0), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, ph * 1.2f, fill); fill.shader = null
        }
        picture?.let { pic ->
            if (b.picture > 0.01f) {
                val sc = 2 * ph / max(pic.width, pic.height)
                fill.alpha = (b.picture * (1 - 0.35f * b.focus) * 255).toInt()
                fill.maskFilter = if (b.focus > 0.05f) BlurMaskFilter(b.focus * 24f * k, BlurMaskFilter.Blur.NORMAL) else null
                c.save(); c.translate(cx - pic.width * sc / 2, cy - pic.height * sc / 2); c.scale(sc, sc)
                c.drawBitmap(pic, 0f, 0f, fill); c.restore(); fill.maskFilter = null; fill.alpha = 255
            }
        }
        level?.let { lv ->
            if (b.figures > 0.01f) {
                text.textSize = 54f * k; text.alpha = (b.figures * 255).toInt()
                text.maskFilter = if (b.figuresBlur > 0.05f) BlurMaskFilter(b.figuresBlur * 5f * k, BlurMaskFilter.Blur.NORMAL) else null
                c.drawText("$lv%", cx, 478f * ky + 19f * k, text); text.maskFilter = null
            }
        }
    }

    private fun edgeLine(c: Canvas, rear: RearGeometry, shader: Shader, a: Float) {
        val inset = 6f
        val r = RectF(inset, inset, rear.widthPx - inset, rear.heightPx - inset)
        stroke.shader = shader; stroke.strokeWidth = 12f; stroke.alpha = (min(1f, a) * 255).toInt()
        stroke.maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(r, rear.cornerPx.toFloat(), rear.cornerPx.toFloat(), stroke)
        stroke.maskFilter = null; stroke.shader = null
    }

    fun stop() {
        handler.removeCallbacks(frame)
        if (isUp) Log.i(TAG, "earbuds: the back's moment is over ($frames pictures, the longest gap $worst ms)")
        isUp = false
        layer?.let { sc -> runCatching { SurfaceControl.Transaction().use { it.setVisibility(sc, false).apply() } } }
        picture?.recycle(); picture = null
    }

    fun close() {
        stop()
        runCatching { surface?.release() }
        ShellLayer.remove(layer); layer = null; surface = null
    }

    private companion object {
        const val TAG = "AiUsageRear"
        const val ASK_EVERY_MS = 250L
    }
}
