package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import dev.kb896.aiusage.data.WakeAnimStyle
import kotlin.math.sin

/**
 * The wake animation over Xiaomi's rear screen, played once as the back lights up (WakeStyle has
 * the styles' timelines), on a layer of the shell's own as the charge (ShellCharge) is. Xiaomi's
 * launcher keeps running under it and is never delayed: this only draws over its first second.
 *
 * MADE AHEAD, HIDDEN. The layer is made once ([prepare]) and kept at alpha 0, cropped to the safe
 * area, so the first lit frame only has to draw into it and show it: the drawing is posted first,
 * then the alpha, visibility (and for Blur reveal the blur) go in one transaction.
 *
 * NEVER LATE. A wake seen at [play]'s seenAtMs whose first frame cannot be posted within
 * WakeStyle.FIRST_FRAME_MS is skipped, not started late: an animation over a screen that is
 * already showing would read as a glitch. A "Play on the back" preview has no such limit, and
 * waits up to [LIT_WAIT_MS] for a back it has just woken.
 *
 * TRUSTED, so every touch goes through to Xiaomi's screen. Cut short by a charge or a banner
 * (RearShow): faded over [RearShow.CROSS_FADE_MS] ([cut]).
 *
 * THREADING: [state] guards the fields below; one render thread per play, living only as long;
 * [onEnded] runs on it as it stops.
 */
class ShellWake(
    private val layerStack: () -> Int,
    private val geometry: () -> RearGeometry,
    /** Whether the rear is lit (STATE_ON) now. */
    private val lit: () -> Boolean,
    /** The play is over, however it ended: for RearShow. */
    private val onEnded: () -> Unit,
) {
    private val state = Any()
    private var style = WakeAnimStyle.EDGE_GLOW
    private var seenAt = 0L
    private var preview = false
    private var cutAt = 0L
    private var cutMs = RearShow.CROSS_FADE_MS
    private var thread: Thread? = null
    @Volatile private var layer: SurfaceControl? = null
    private val making = Any()

    /** Playing, or (a preview) waiting for the back to light. */
    @Volatile var isUp = false
        private set

    /** The layer made now, hidden, so the first lit frame only draws. Any thread. */
    fun prepare() {
        runCatching { layerOrNull() }
    }

    private fun layerOrNull(): SurfaceControl? {
        layer?.let { return it }
        synchronized(making) {
            layer?.let { return it }
            val rear = geometry()
            val made = runCatching {
                ShellLayer.create("wake", layerStack(), ShellLayer.Z_WAKE, rear.widthPx, rear.heightPx, trusted = true, visible = false, alpha = 0f)
            }.onFailure { Log.w(TAG, "wake: no layer: ${it.message}") }.getOrNull() ?: return null
            // Nothing of it, not even the blur, under the lenses.
            val safe = ChargeStyle.safeArea(rear.widthPx, rear.heightPx, rear.cutoutPx)
            runCatching {
                SurfaceControl.Transaction().use { t ->
                    t.setCrop(made, Rect(safe.left.toInt(), safe.top.toInt(), safe.right.toInt(), safe.bottom.toInt())).apply()
                }
            }.onFailure { Log.w(TAG, "wake: no crop: ${it.message}") }
            layer = made
            return made
        }
    }

    /**
     * Play [look], for a wake seen at [seenAtMs] (uptime). [asPreview]: a "Play on the back", which
     * may wait for the back to light and is never skipped for being late. One already playing
     * starts again from here.
     */
    fun play(look: WakeAnimStyle, seenAtMs: Long, asPreview: Boolean) {
        if (look == WakeAnimStyle.NONE) return
        synchronized(state) {
            style = look
            seenAt = seenAtMs
            preview = asPreview
            cutAt = 0L
            isUp = true
            if (thread?.isAlive != true) {
                thread = Thread({ run() }, "rear-wake").apply { isDaemon = true; start() }
            }
        }
    }

    /** A higher show (the charge, a banner) cuts it short: faded out over [fadeMs]. */
    fun cut(fadeMs: Long, why: String) = synchronized(state) {
        if (!isUp || cutAt != 0L) return@synchronized
        cutAt = SystemClock.uptimeMillis()
        cutMs = fadeMs.coerceAtLeast(1L)
        Log.i(TAG, "wake: cut short — $why")
    }

    private class Frame(val style: WakeAnimStyle, val seenAt: Long, val preview: Boolean, val cutAt: Long, val cutMs: Long)

    private fun run() {
        var surface: Surface? = null
        var why = "done"
        var startAt = 0L
        var startedFor = -1L
        try {
            val sc = layerOrNull() ?: run { why = "no layer"; return }
            val rear = geometry()
            val safe = ChargeStyle.safeArea(rear.widthPx, rear.heightPx, rear.cutoutPx)
            surface = Surface(sc)
            val painter = Painter()
            var placedAlpha = -1f
            var placedBlur = -1
            while (!Thread.currentThread().isInterrupted) {
                val now = SystemClock.uptimeMillis()
                val f = synchronized(state) {
                    if (!isUp) null else Frame(style, seenAt, preview, cutAt, cutMs)
                } ?: break
                // play() again while playing: from the start.
                if (f.seenAt != startedFor) { startedFor = f.seenAt; startAt = 0L }
                if (startAt == 0L) {
                    if (f.preview) {
                        if (!lit()) {
                            if (now - f.seenAt > LIT_WAIT_MS) { why = "the back did not light — not played"; break }
                            Thread.sleep(WAIT_STEP_MS)
                            continue
                        }
                    } else if (now - f.seenAt > WakeStyle.FIRST_FRAME_MS) {
                        why = "the first frame was missed (${now - f.seenAt} ms) — skipped"
                        break
                    }
                    startAt = now
                }
                val elapsed = (now - startAt).toFloat()
                val frame = WakeStyle.frame(f.style, elapsed, safe, rear.cornerPx.toFloat()) ?: break
                var alpha = frame.alpha
                if (f.cutAt != 0L) {
                    val left = (1f - (now - f.cutAt).toFloat() / f.cutMs).coerceIn(0f, 1f)
                    if (left <= 0f) { why = "cut short"; break }
                    alpha *= left
                }
                val c = runCatching { surface.lockHardwareCanvas() }.getOrNull() ?: surface.lockCanvas(null)
                try {
                    c.drawColor(0, PorterDuff.Mode.CLEAR)
                    painter.draw(c, frame)
                } finally { surface.unlockCanvasAndPost(c) }
                val blur = (frame as? WakeStyle.Frame.Blur)?.blurPx ?: 0
                if (alpha != placedAlpha || blur != placedBlur) {
                    SurfaceControl.Transaction().use { tx ->
                        tx.setAlpha(sc, alpha).setVisibility(sc, alpha > 0f)
                        if (blur != placedBlur) ShellOverlay.blurUnder(tx, sc, blur)
                        tx.apply()
                    }
                    placedAlpha = alpha
                    placedBlur = blur
                }
                Thread.sleep(FRAME_MS)
            }
        } catch (_: InterruptedException) {
            why = "stopped"
        } catch (e: Throwable) {
            why = "failed: ${e.message}"
            Log.w(TAG, "wake stopped: ${e.message}")
        } finally {
            // Hidden, no blur, kept: the next wake finds it made.
            layer?.let { sc ->
                runCatching {
                    SurfaceControl.Transaction().use { t ->
                        t.setVisibility(sc, false).setAlpha(sc, 0f)
                        ShellOverlay.blurUnder(t, sc, 0)
                        t.apply()
                    }
                }
            }
            runCatching { surface?.release() }
            val mine = synchronized(state) {
                if (thread === Thread.currentThread()) { thread = null; isUp = false; true } else false
            }
            if (mine) {
                Log.i(TAG, "wake: over — $why")
                runCatching { onEnded() }
            }
        }
    }

    /** Lets go of the layer, for the shell's end. */
    fun close() {
        synchronized(state) { isUp = false; thread?.interrupt() }
        ShellLayer.remove(layer)
        layer = null
    }

    /** One play's drawing, on the render thread. */
    private class Painter {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val path = Path()
        private val point = FloatArray(2)
        private var shaderFor = Float.NaN

        fun draw(c: Canvas, f: WakeStyle.Frame) {
            when (f) {
                is WakeStyle.Frame.Edge -> edge(c, f)
                is WakeStyle.Frame.Liquid -> liquid(c, f)
                is WakeStyle.Frame.Blur -> c.drawColor(Color.argb(Math.round(f.dim * 255).coerceIn(0, 255), 0, 0, 0))
                is WakeStyle.Frame.Ripple -> {
                    glow.strokeWidth = f.width * 2.4f; glow.color = (TINT and 0x00FFFFFF) or (0x38 shl 24)
                    c.drawCircle(f.cx, f.cy, f.radius, glow)
                    line.strokeWidth = f.width / 3f; line.color = TINT
                    c.drawCircle(f.cx, f.cy, f.radius, line)
                }
            }
        }

        private fun edge(c: Canvas, f: WakeStyle.Frame.Edge) {
            if (f.headAt <= f.tailAt) return
            val o = f.outline
            // Square on its left, where the safe area meets the lens strip; round on the glass's side.
            val shape = ElectricBorder.Shape.Rounded(o.left, o.top, o.right, o.bottom, f.radius, leftR = 0f)
            path.reset()
            val steps = ((f.headAt - f.tailAt) * shape.length / ElectricBorder.STEP).toInt().coerceAtLeast(2)
            for (i in 0..steps) {
                shape.at(f.tailAt + (f.headAt - f.tailAt) * i / steps, point, 0)
                if (i == 0) path.moveTo(point[0], point[1]) else path.lineTo(point[0], point[1])
            }
            glow.strokeWidth = WakeStyle.EDGE_STROKE + WakeStyle.EDGE_GLOW_PX * 2f; glow.color = (TINT and 0x00FFFFFF) or (0x44 shl 24)
            c.drawPath(path, glow)
            line.strokeWidth = WakeStyle.EDGE_STROKE; line.color = TINT
            c.drawPath(path, line)
        }

        private fun liquid(c: Canvas, f: WakeStyle.Frame.Liquid) {
            if (f.bottom <= f.top) return
            val wavelength = 160f
            path.reset()
            path.moveTo(f.left, f.bottom)
            var x = f.left
            while (true) {
                path.lineTo(x, f.top + f.wave * sin(f.phase + x / wavelength * 2f * Math.PI.toFloat()))
                if (x >= f.right) break
                x = minOf(f.right, x + 8f)
            }
            path.lineTo(f.right, f.bottom)
            path.close()
            // The colour deepens downwards; made again only when the band has moved.
            if (shaderFor != f.top) {
                fill.shader = LinearGradient(0f, f.top - f.wave, 0f, f.bottom, LIQUID_TOP, LIQUID_BOTTOM, Shader.TileMode.CLAMP)
                shaderFor = f.top
            }
            c.drawPath(path, fill)
        }
    }

    companion object {
        private const val TAG = "AiUsageRear"
        /** A preview, asked for with the back dark: how long it waits for the back to light. */
        const val LIT_WAIT_MS = 1_500L
        private const val WAIT_STEP_MS = 30L
        private const val FRAME_MS = 16L
        private const val TINT = 0xFF8FD8FF.toInt()
        private const val LIQUID_TOP = 0xCC6FE3FF.toInt()
        private const val LIQUID_BOTTOM = 0xEE1E6BFF.toInt()
    }
}
