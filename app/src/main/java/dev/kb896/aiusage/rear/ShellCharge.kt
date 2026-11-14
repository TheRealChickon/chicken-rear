package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import dev.kb896.aiusage.data.ChargeAnimStyle

/**
 * The charging animation over the rear, drawn by the shell on a layer of its own, as the
 * notification banner is (ShellNotices): over Xiaomi's launcher, which keeps running under it,
 * locked or not. Xiaomi's rear has no charging animation of its own, so this never doubles one.
 *
 * - FULL over Xiaomi's launcher: five seconds over a dim scrim — the liquid (LiquidCellPainter
 *   and its ChargeSurge discharges, on ChargeOverlay's timings), or the Ring, Edge glow or Minimal
 *   style (ChargeStyle) — then Xiaomi's screen as it was.
 * - COMPACT over a guest of ours: two seconds down the right edge, no scrim.
 * - Never over Xiaomi's rear camera: the caller skips it (RearUserService.showCharge).
 *
 * TRUSTED, so touches go through it to Xiaomi's screen or the app: the one touch read is the
 * shell's own touchscreen watch, where any finger down ends it ([end]) without being taken.
 *
 * THE FIRST LIT FRAME: asked for with the rear dark (the app wakes it, then asks), it waits up to
 * [LIT_WAIT_MS] for the rear to light and starts its clock then, so the fill is not spent in the
 * dark; not lit by then, it is dropped rather than shown late. The layer is made once
 * ([prepare]) and kept, hidden, so the first lit frame already has it.
 *
 * Unplugged while up: the liquid drains and everything fades over ChargeOverlay.DRAIN_MS
 * ([drain]). Cut short by a banner (RearShow): faded over [RearShow.CROSS_FADE_MS] ([cut]).
 *
 * THREADING: [state] guards the fields below; one render thread per show, which lives only
 * while it does; [onEnded] runs on it as it stops.
 */
class ShellCharge(
    private val layerStack: () -> Int,
    private val geometry: () -> RearGeometry,
    /** Whether the rear is lit (STATE_ON) now. */
    private val lit: () -> Boolean,
    /** The show is over, however it ended: for RearShow and the touch watch. */
    private val onEnded: () -> Unit,
) {
    private val state = Any()
    private var level = 0f
    private var fast = false
    private var compact = false
    private var style = ChargeAnimStyle.LIQUID
    private var percent = true
    private var askedAt = 0L
    private var startAt = 0L
    private var drainAt = 0L
    private var cutAt = 0L
    private var cutMs = RearShow.CROSS_FADE_MS
    private var fillNow = 0f
    private var alphaNow = 0f
    private var fillAtDrain = 0f
    private var alphaAtDrain = 0f
    private var thread: Thread? = null
    @Volatile private var layer: SurfaceControl? = null
    private val making = Any()

    @Volatile private var gravityX = 0f
    @Volatile private var gravityY = ChargeOverlay.FULL_G

    /** Showing, or waiting for the rear to light to show: for the touch watch. */
    @Volatile var isUp = false
        private set

    /** The layer made now, hidden, so the first lit frame of a show already carries it. Any thread. */
    fun prepare() {
        runCatching { layerOrNull() }
    }

    private fun layerOrNull(): SurfaceControl? {
        layer?.let { return it }
        synchronized(making) {
            layer?.let { return it }
            val rear = geometry()
            val made = runCatching {
                ShellLayer.create("charge", layerStack(), ShellLayer.Z_CHARGE_PAGE, rear.widthPx, rear.heightPx, trusted = true, visible = false, alpha = 0f, corner = ShellLayer.pageCorner(rear))
            }.onFailure { Log.w(TAG, "charge: no layer: ${it.message}") }.getOrNull() ?: return null
            layer = made
            return made
        }
    }

    /**
     * Show it, filling to [levelPercent], [fastCharger] or not, [compactOverGuest] down the edge.
     * A show already up starts again from here: one charger, one answer.
     */
    fun show(levelPercent: Int, fastCharger: Boolean, compactOverGuest: Boolean, look: ChargeAnimStyle, showPercent: Boolean, slideIn: Boolean = false) {
        synchronized(state) {
            // As a page of the row it comes in from the right edge, as the timer's and the call's do ([slideIn]); else
            // in its place, by its own arrival (the fluid rising).
            leaving = false
            slidIn = slideIn && !compactOverGuest
            if (slidIn) slide.comeFrom(PageSlide.Edge.RIGHT) else slide.inPlace()
            level = (levelPercent.coerceIn(0, 100)) / 100f
            fast = fastCharger
            compact = compactOverGuest
            style = look
            percent = showPercent
            held = !compactOverGuest
            fraction = -1f; label = ""
            askedAt = SystemClock.uptimeMillis()
            startAt = 0L
            drainAt = 0L
            cutAt = 0L
            isUp = true
            if (thread?.isAlive != true) {
                thread = Thread({ run() }, "rear-charge").apply { isDaemon = true; start() }
            }
        }
    }

    /** Which way is down for the liquid, the gravity sensor's x and y. Any thread. */
    fun tilt(x: Float, y: Float) {
        gravityX = x
        gravityY = y
    }

    /** The charger came out: drain and fade. Nothing up, nothing to do. */
    fun drain() = synchronized(state) {
        if (!isUp || drainAt != 0L) return@synchronized
        if (startAt == 0L) { stopLocked("unplugged before the back lit"); return@synchronized }
        drainAt = SystemClock.uptimeMillis()
        fillAtDrain = fillNow
        alphaAtDrain = alphaNow
        Log.i(TAG, "charge: unplugged — draining")
    }

    /**
     * THE PAGE STAYS for as long as the charger is in: it ends when the charger comes out ([drain]) or he swipes it
     * away ([swipedAway]), never by its own clock and never because another show came (those show over it).
     * Only the small one over an app still runs its two seconds.
     */
    @Volatile private var held = false

    /** How bright, as the scene would be now (the always-on rules' dimming on a table): 1 full. Any thread. */
    @Volatile var dim = 1f

    // The level between two whole percents, from a real source (the app's ChargePrecise): [fraction] of a percent
    // at [fractionAt] (uptime), rising by [ratePerSec]; under 0 while it is not known, and then no decimals show.
    @Volatile private var fraction = -1f
    @Volatile private var fractionAt = 0L
    @Volatile private var ratePerSec = 0f
    /** What kind of charger, in words ("Fast charging"), or "" for an ordinary one. */
    @Volatile private var label = ""
    /** On the charger but not filling (a limit, full): the fluid comes to rest and stays so (ChargePrecise.Detail.holding). */
    @Volatile private var holding = false
    // The fluid's own clock: it runs with the time while the battery fills and slows to a stop while it holds.
    private var fluidClock = 0L
    private var fluidSpeed = 1f
    private var fluidAt = 0L

    /** The battery as it is now, while the page is up: the level (no restart of the show) and what is known past it. Any thread. */
    fun detail(levelPercent: Int, fractionNow: Float, atUptime: Long, rate: Float, kind: String, holds: Boolean = false) {
        // A level that is not known (under 0) changes nothing on the page: never a made-up figure on the back.
        synchronized(state) { if (isUp && levelPercent >= 0) level = levelPercent.coerceIn(0, 100) / 100f }
        fraction = fractionNow; fractionAt = atUptime; ratePerSec = rate; label = kind; holding = holds
    }

    // THE PAGE'S PLACE ON THE BACK (PageSlide): it slides as every page does, and is under the finger while one moves it.
    private val slide = PageSlide()
    @Volatile private var leaving = false
    @Volatile private var slidIn = false

    /**
     * Sent away (by hand, or the navigation has another page there): IT SLIDES OUT, to the right edge as the other
     * live pages do, or on to the edge a finger was taking it to; gone until it is shown again. (It used to fade
     * where it stood.) The small one over an app keeps its short fade.
     */
    fun swipedAway() = synchronized(state) {
        if (!isUp || cutAt != 0L || leaving) return@synchronized
        if (compact) {
            held = false
            cutAt = SystemClock.uptimeMillis()
            cutMs = RearShow.CROSS_FADE_MS
        } else {
            leaving = true
            slide.leaveTo(PageSlide.Edge.RIGHT)
        }
        Log.i(TAG, "charge: swiped away")
    }

    /** UNDER A FINGER (PageDrag): [inPart] of its way in, 1 in place and 0 off [towards]. Only the page that is up. */
    fun peek(inPart: Float, towards: PageSlide.Edge) { if (isUp && !compact && !leaving) slide.peek(inPart, towards) }

    /** Let go without turning: back into its place, by its slide. */
    fun unpeek() = slide.unpeek()

    /** A higher show (a banner) cuts it short: faded out over [fadeMs]. Not the page that stays: the other shows over it. */
    fun cut(fadeMs: Long, why: String) = synchronized(state) {
        if (held) return@synchronized
        if (!isUp || cutAt != 0L) return@synchronized
        cutAt = SystemClock.uptimeMillis()
        cutMs = fadeMs.coerceAtLeast(1L)
        Log.i(TAG, "charge: cut short — $why")
    }

    /** Tapped: ends now, with the short fade. */
    fun end(why: String) = cut(RearShow.CROSS_FADE_MS, why)

    /** Under [state]: the render thread stops at its next frame and says so (onEnded). */
    private fun stopLocked(why: String) {
        Log.i(TAG, "charge: $why")
        isUp = false
    }

    /** A snapshot of [state] for one frame. */
    private class Frame(
        val level: Float, val fast: Boolean, val compact: Boolean, val style: ChargeAnimStyle, val percent: Boolean,
        val askedAt: Long, val startAt: Long, val drainAt: Long, val cutAt: Long, val cutMs: Long,
        val fillAtDrain: Float, val alphaAtDrain: Float,
    )

    private fun run() {
        var surface: Surface? = null
        var shownOnce = false
        var why = "done"
        try {
            val sc = layerOrNull() ?: run { why = "no layer"; return }
            val rear = geometry()
            surface = Surface(sc)
            val painter = Painter(rear)
            var placedAlpha = -1f
            var placedX = Float.NaN; var placedY = Float.NaN
            while (!Thread.currentThread().isInterrupted) {
                val now = SystemClock.uptimeMillis()
                val f = synchronized(state) {
                    if (!isUp) null
                    else {
                        // The clock starts on the first lit frame (see the class comment).
                        if (startAt == 0L && lit()) startAt = now
                        Frame(level, fast, compact, style, percent, askedAt, startAt, drainAt, cutAt, cutMs, fillAtDrain, alphaAtDrain)
                    }
                } ?: break
                if (f.startAt == 0L) {
                    if (now - f.askedAt > LIT_WAIT_MS) { why = "the back did not light — not shown"; break }
                    Thread.sleep(WAIT_STEP_MS)
                    continue
                }
                val t = ChargeStyle.timing(f.compact)
                val elapsed = (now - f.startAt).toFloat()
                var fill = ChargeStyle.fill(elapsed, f.level, t)
                var alpha = ChargeStyle.alpha(elapsed, t)
                // The page that stays: in as ever, then there, with no end of its own.
                val stays = held && !f.compact
                if (stays && (elapsed >= t.inMs || slidIn || leaving)) alpha = 1f
                // Sent away: out by its slide, and over when it has reached its edge (or at once on a dark back).
                if (leaving && (slide.gone || !lit())) { why = "swiped away"; break }
                if (f.drainAt != 0L) {
                    val since = (now - f.drainAt).toFloat()
                    if (ChargeStyle.drained(since)) { why = "drained"; break }
                    fill = ChargeStyle.drainFill(f.fillAtDrain, since)
                    alpha = ChargeStyle.drainAlpha(f.alphaAtDrain, since)
                } else if (!stays && ChargeStyle.done(elapsed, t)) break
                if (f.cutAt != 0L) {
                    val left = (1f - (now - f.cutAt).toFloat() / f.cutMs).coerceIn(0f, 1f)
                    if (left <= 0f) { why = "cut short"; break }
                    alpha *= left
                }
                synchronized(state) { fillNow = fill; alphaNow = alpha }
                // Staying, and the back dark: nothing is drawn for nobody.
                if (stays && elapsed > t.inMs && !lit()) { Thread.sleep(DARK_STEP_MS); continue }
                val c = runCatching { surface.lockHardwareCanvas() }.getOrNull() ?: surface.lockCanvas(null)
                try {
                    c.drawColor(0, PorterDuff.Mode.CLEAR)
                    val shownFraction = if (fraction >= 0f) (fraction + ratePerSec * (now - fractionAt) / 1000f).coerceIn(0f, 0.99f) else -1f
                    // Its drift eases to a stop over a second and a half as the battery holds, and up again as it fills.
                    val step = if (fluidAt == 0L) 0L else (now - fluidAt).coerceIn(0L, 100L)
                    fluidAt = now
                    val want = if (holding) 0f else 1f
                    fluidSpeed += (want - fluidSpeed) * (step / REST_MS).coerceIn(0f, 1f)
                    if (fluidClock == 0L) fluidClock = SystemClock.elapsedRealtime()
                    fluidClock += (step * fluidSpeed).toLong()
                    painter.draw(c, f, elapsed, fill, gravityX, gravityY, shownFraction, label, dim, fluidClock)
                } finally { surface.unlockCanvasAndPost(c) }
                // The whole layer's strength is SurfaceFlinger's: the scrim and the drawing fade as one.
                if (alpha != placedAlpha || !shownOnce) {
                    SurfaceControl.Transaction().use { tx -> tx.setAlpha(sc, alpha).setVisibility(sc, alpha > 0f).apply() }
                    placedAlpha = alpha
                    shownOnce = true
                }
                // Where it is on the back: in its place, sliding, or where a finger has it.
                val moving = slide.step(now)
                val x = slide.x(rear.widthPx.toFloat()); val y = slide.y(rear.heightPx.toFloat())
                if (x != placedX || y != placedY) {
                    SurfaceControl.Transaction().use { tx -> ShellLayer.round(tx, sc, rear, moving = x != 0f || y != 0f); tx.setPosition(sc, x, y).apply() }
                    placedX = x; placedY = y
                }
                Thread.sleep(if (moving) SLIDE_FRAME_MS else FRAME_MS)
            }
        } catch (_: InterruptedException) {
            why = "stopped"
        } catch (e: Throwable) {
            why = "failed: ${e.message}"
            Log.w(TAG, "charge stopped: ${e.message}")
        } finally {
            // Hidden, kept: the next show finds it made.
            layer?.let { sc -> runCatching { SurfaceControl.Transaction().use { it.setVisibility(sc, false).setAlpha(sc, 0f).setPosition(sc, 0f, 0f).apply() } } }
            leaving = false
            runCatching { surface?.release() }
            val mine = synchronized(state) {
                if (thread === Thread.currentThread()) { thread = null; isUp = false; true } else false
            }
            if (mine) {
                Log.i(TAG, "charge: over — $why")
                runCatching { onEnded() }
            }
        }
    }

    /**
     * The same drawing for the settings' preview on the front: a full show at [level] (0..1),
     * [elapsedMs] in. Draws onto a canvas the rear's size; returns the show's strength then
     * (what the layer's alpha would be), or null once it is over.
     */
    class PreviewPainter(rear: RearGeometry) {
        private val painter = Painter(rear)

        fun draw(c: Canvas, style: ChargeAnimStyle, level: Float, percent: Boolean, elapsedMs: Float): Float? {
            val t = ChargeStyle.timing(false)
            if (ChargeStyle.done(elapsedMs, t)) return null
            val f = Frame(level, false, false, style, percent, 0L, 0L, 0L, 0L, 0L, 0f, 0f)
            painter.draw(c, f, elapsedMs, ChargeStyle.fill(elapsedMs, level, t), 0f, 9.81f)
            return ChargeStyle.alpha(elapsedMs, t)
        }
    }

    /** Lets go of the layer, for the shell's end. */
    fun close() {
        synchronized(state) { stopLocked("closed"); thread?.interrupt() }
        ShellLayer.remove(layer)
        layer = null
    }

    /** One show's drawing, on the render thread: its own paints and liquid. */
    private class Painter(private val rear: RearGeometry) {
        private val safe = ChargeStyle.safeArea(rear.widthPx, rear.heightPx, rear.cutoutPx)
        private val liquid = LiquidCellPainter().apply {
            glassy = false
            corner = rear.cornerPx.toFloat()
            ground = Color.BLACK
        }
        private val cell = LiquidCellPainter.Cell()
        private val figure = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            color = Color.WHITE
        }
        private val demi = Typeface.create(Typeface.DEFAULT, 600, false)
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val rect = RectF()
        private val path = Path()

        fun draw(c: Canvas, f: Frame, elapsed: Float, fill: Float, gx: Float, gy: Float, fraction: Float = -1f, kind: String = "", dim: Float = 1f, fluidAt: Long = SystemClock.elapsedRealtime()) {
            val tint = if (f.fast) FAST_TINT else TINT
            val t = ChargeStyle.timing(f.compact)
            // The figure grows in and out with the show, as ChargeOverlay's is carried in.
            // (The page that stays keeps its figure once it is in: the old show's fading out is not its.)
            val grow = if (f.percent && !f.compact) (if (elapsed >= t.inMs) 1f else ChargeStyle.alpha(elapsed, t)) else 0f
            val figureText = Math.round(f.level * 100f).toString() + "%"
            if (!f.compact) {
                // THE WHOLE BACK, with the back's own fluid (rear/Fluid, the timer page's water) risen to the
                // battery's level, in the colour of how charged it is (ChargeFluid). The older looks (ring, edge,
                // minimal) are not drawn any more.
                val w = rear.widthPx.toFloat(); val h = rear.heightPx.toFloat(); val u = h / 596f
                c.drawColor(Color.BLACK)
                rect.set(0f, 0f, w, h)
                val water = XiaomiArt.load("timer", TimerFace.ART)[TimerFace.ART_WATER]
                Fluid.draw(c, rect, 0f, h - fill * h, fluidAt, water, u, tint = ChargeFluid.color(f.level))
                if (f.percent) {
                    // The figure where the timer page has its time, and as large: the whole percents, and, where a
                    // real source gives them, the hundredths beside them, smaller, counting as the battery fills.
                    val whole = Math.round(f.level * 100f).toString()
                    val part = if (fraction >= 0f && f.level < 1f) "." + "%02d".format((fraction * 100f).toInt().coerceIn(0, 99)) else ""
                    text.typeface = demi
                    text.textAlign = Paint.Align.LEFT
                    text.alpha = (255 * grow.coerceIn(0f, 1f)).toInt()
                    text.textSize = 157f * u
                    val wWhole = text.measureText(whole)
                    text.textSize = 78f * u
                    val wPart = if (part.isEmpty()) 0f else text.measureText(part)
                    val left = TimerFace.CENTRE * u - (wWhole + wPart) / 2f
                    text.textSize = 157f * u
                    val base = 123.5f * u - text.ascent()
                    c.drawText(whole, left, base, text)
                    if (part.isNotEmpty()) { text.textSize = 78f * u; c.drawText(part, left + wWhole, base, text) }
                    if (kind.isNotEmpty()) {
                        text.textSize = 34f * u; text.textAlign = Paint.Align.CENTER
                        text.alpha = (150 * grow.coerceIn(0f, 1f)).toInt()
                        c.drawText(kind, TimerFace.CENTRE * u, base + 64f * u, text)
                    }
                    text.textAlign = Paint.Align.CENTER
                    text.alpha = 255
                }
                // Dimmed as the scene would be (lying face down on a table): a veil, not a darker fluid.
                if (dim < 0.995f) c.drawColor(Color.argb(Math.round((1f - dim.coerceIn(0f, 1f)) * 255f), 0, 0, 0))
                return
            }
            val body = ChargeStyle.body(f.style, safe, f.compact)
            if (f.compact || f.style == ChargeAnimStyle.LIQUID) {
                liquid.gravityX = gx
                liquid.gravityY = gy
                liquid.draw(
                    c, cell, figure, SystemClock.elapsedRealtime(),
                    body.left, body.top, body.right, body.bottom,
                    fill = fill, tint = tint, surgeAt = elapsed, grow = grow,
                    figureTint = Color.WHITE, figure = figureText,
                )
                return
            }
            when (f.style) {
                ChargeAnimStyle.RING -> {
                    val stroke = ChargeStyle.ringRadius(safe) * 0.16f
                    rect.set(body.left, body.top, body.right, body.bottom)
                    track.strokeWidth = stroke; track.color = Color.argb(60, 255, 255, 255)
                    c.drawArc(rect, 0f, 360f, false, track)
                    glow.strokeWidth = stroke * 2.2f; glow.color = (tint and 0x00FFFFFF) or (0x40 shl 24)
                    c.drawArc(rect, -90f, ChargeStyle.ringSweep(fill), false, glow)
                    lit.strokeWidth = stroke; lit.color = tint
                    c.drawArc(rect, -90f, ChargeStyle.ringSweep(fill), false, lit)
                    if (grow > 0f) drawFigure(c, f, safe, body, figureText, body.width * 0.26f * grow)
                }
                ChargeAnimStyle.EDGE_GLOW -> {
                    val r = (rear.cornerPx - ChargeStyle.MARGIN / 2f).coerceAtLeast(12f)
                    path.reset()
                    path.addRoundRect(body.left, body.top, body.right, body.bottom, r, r, Path.Direction.CW)
                    track.strokeWidth = 4f; track.color = Color.argb(50, 255, 255, 255)
                    c.drawPath(path, track)
                    // Lit from the bottom up to the level, on both sides at once.
                    c.save()
                    c.clipRect(0f, ChargeStyle.fillTop(body, fill), rear.widthPx.toFloat(), rear.heightPx.toFloat())
                    glow.strokeWidth = 22f; glow.color = (tint and 0x00FFFFFF) or (0x48 shl 24)
                    c.drawPath(path, glow)
                    lit.strokeWidth = 6f; lit.color = tint
                    c.drawPath(path, lit)
                    c.restore()
                    if (grow > 0f) drawFigure(c, f, safe, body, figureText, safe.height * 0.26f * grow)
                }
                ChargeAnimStyle.MINIMAL -> {
                    val y = body.centreY
                    track.strokeWidth = body.height; track.color = Color.argb(60, 255, 255, 255)
                    c.drawLine(body.left, y, body.right, y, track)
                    lit.strokeWidth = body.height; lit.color = tint
                    if (fill > 0.005f) c.drawLine(body.left, y, ChargeStyle.barRight(body, fill), y, lit)
                    // Minimal is the figure; with the percent off, only the bar.
                    if (grow > 0f) drawFigure(c, f, safe, body, figureText, safe.height * 0.2f * grow)
                }
                ChargeAnimStyle.LIQUID -> Unit
            }
        }

        private fun drawFigure(c: Canvas, f: Frame, safe: ChargeStyle.Box, body: ChargeStyle.Box, s: String, size: Float) {
            if (size < 1f) return
            val (x, y) = ChargeStyle.figureCentre(f.style, safe, body)
            text.textSize = size
            c.drawText(s, x, y - (text.ascent() + text.descent()) / 2f, text)
        }
    }

    companion object {
        private const val TAG = "AiUsageRear"
        /** How long a show asked for on a dark rear waits for it to light before it is dropped. */
        const val LIT_WAIT_MS = 1_500L
        private const val WAIT_STEP_MS = 30L
        private const val FRAME_MS = 16L
        /** While the page slides or is under a finger: a picture for every frame of the panel. */
        private const val SLIDE_FRAME_MS = 8L
        /** How often the page that stays looks whether the back is lit again, while it is dark. */
        private const val DARK_STEP_MS = 400L
        /** The fluid comes to rest, and stirs again, over about this long. */
        private const val REST_MS = 1_500f
        /** The liquid's colours: a charger, and a fast one. */
        private const val TINT = 0xFF4CD964.toInt()
        private const val FAST_TINT = 0xFF35D0FF.toInt()
    }
}
