package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import dev.kb896.aiusage.rear.PinBeam
import dev.kb896.aiusage.rear.PinBeamArt
import dev.kb896.aiusage.rear.PinFace
import dev.kb896.aiusage.service.PinChime

/**
 * ONE PIN, ONCE: what has to happen whatever draws it and however it ends. The back is told as the card leaves the
 * front (or at once should the pin's screen go before that), and the pin's sound comes when the card has gone. Made by
 * [PinActivity] as the pin begins; the screen that plays it (the activity's own window, or the glass service's
 * overlay over an app that is itself an overlay) calls these at their times.
 */
class PinRun(private val context: Context, private val tell: Runnable, private val demo: Boolean, private val soundAsked: Boolean) {
    private var told = false
    private var chimed = false
    @Volatile var beganAt = 0L

    fun tellBack() {
        if (told) return
        told = true
        tell.run()
        Log.i(TAG, "beam: the back told, ${if (beganAt == 0L) "before a frame was drawn" else "${SystemClock.uptimeMillis() - beganAt} ms after the front began"}")
    }

    fun chime() {
        if (chimed) return
        chimed = true
        PinChime.play(context, demo, soundAsked)
    }

    // ---- THE DROP ON A FRAME (draft B: service/Droplet; the plain drawing keeps chime() above) ----
    private var armed = false
    @Volatile private var shot: dev.kb896.aiusage.service.Droplet.Shot? = null

    /** As the pin's first frame is drawn: the sound decoded and a track of its own running silence, or nothing for a silent pin. */
    @Synchronized fun armChime() {
        if (armed || chimed) return
        armed = true
        shot = dev.kb896.aiusage.service.Droplet.arm(context, demo, soundAsked)
    }

    /** Its attack heard at [presentNanos] (System.nanoTime): the island's first frame on the glass. */
    @Synchronized fun chimeAt(presentNanos: Long, how: String) {
        if (chimed) return
        if (!armed) { chime(); return }
        chimed = true
        shot?.at(presentNanos, how)
    }

    /** When the island's first frame really was on the glass, for the drop's own log line. */
    fun islandShown(presentNanos: Long) { shot?.islandShown = presentNanos }

    @Synchronized private fun chimeNow() {
        if (chimed) return
        if (!armed) { chime(); return }
        chimed = true
        shot?.now()
    }

    /** The pin's screen is going: whatever was still to come, comes now. */
    fun finish() { tellBack(); chimeNow() }

    private companion object { const val TAG = "AiUsagePin" }
}

/**
 * The front's half of the beam (the plain drawing: rear/PinBeamArt), every frame from the one clock, in whichever
 * window holds it. [origin]: where the pinned words are on the screen, if the caller said (a rectangle in screen
 * pixels): the sheet then lifts off from there and joins its way within the lift; without, from the middle as before.
 */
class PinBeamView(
    context: Context,
    private val text: String,
    private val origin: RectF?,
    private val run: PinRun,
    /** How blurred what is behind is to be (a radius in pixels): the window's own to set. */
    private val blur: (Int) -> Unit,
    /** A touch ends it (the activity's window); false where touches go through to what is under (the overlay). */
    private val touchEnds: Boolean,
    /** A picture's pin: the picture lifts off where the words would (PinBeamArt.sheetPicture), and is on the card's face. */
    private val picture: Bitmap? = null,
    private val image: String? = null,
    private val end: () -> Unit,
) : View(context) {
    private val art = PinBeamArt()
    private var began = 0L
    private var face: Bitmap? = null
    private var blurred = -1
    private var frames = 0; private var worst = 0L; private var last = 0L
    private var over = false
    private val at = IntArray(2)

    /**
     * The page behind, as it was when he pinned (the glass service's picture): where this view has no window whose
     * blur the system makes (the overlay above all windows), it is drawn here, blurred as far as the animation says.
     * In memory only; whoever set it lets it go.
     */
    @Volatile var behind: Bitmap? = null
    private val page by lazy { android.graphics.RenderNode("page-behind") }
    private var pageOf: Bitmap? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!touchEnds) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) stop()
        return true
    }

    private fun stop() { if (over) return; over = true; end() }

    override fun onDraw(c: Canvas) {
        if (over) return
        val now = SystemClock.uptimeMillis()
        if (began == 0L) {
            began = now; run.beganAt = now
            Log.i(TAG, "beam: the front begins${if (origin != null) " (from where the words are)" else ""}")
            // The card's face is the back's own notes page with this note on it, as it will be there.
            face = runCatching {
                Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888).also {
                    PinFace.draw(Canvas(it), 976f, 596f, text, PinFace.stamp(System.currentTimeMillis(), System.currentTimeMillis()), 0, 1, false, image = image, picture = picture)
                }
            }.getOrNull()
        }
        val t = now - began
        val f = PinBeam.front(t)
        val w = width.toFloat(); val h = height.toFloat()
        // What is behind: blurred where the phone can, and dimmed.
        val radius = (f.blur * 70f).toInt()
        if (radius != blurred) { blurred = radius; runCatching { blur(radius) } }
        behind?.takeIf { !it.isRecycled && c.isHardwareAccelerated && radius > 0 }?.let { picture ->
            if (pageOf !== picture) {
                pageOf = picture
                page.setPosition(0, 0, width, height)
                val rc = page.beginRecording()
                rc.drawBitmap(picture, null, android.graphics.Rect(0, 0, width, height), null)
                page.endRecording()
            }
            page.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius.toFloat(), radius.toFloat(), android.graphics.Shader.TileMode.CLAMP))
            // In over the sharp page as the blur comes, so its arrival a few frames in is not a jump.
            page.alpha = f.blur.coerceIn(0f, 1f)
            c.drawRenderNode(page)
        }
        c.drawColor(((f.blur * 0.5f * 255).toInt().coerceIn(0, 255)) shl 24)
        val unit = w / 1200f
        val room = w * 0.64f
        // From where the words are (the caller's rectangle, in screen pixels; this view may not start at the screen's corner).
        var x = f.noteX * w; var y = f.noteY * h; var scale = f.noteScale
        origin?.let { o ->
            getLocationOnScreen(at)
            val k = PinBeam.joined(t)
            val ox = o.centerX() - at[0]; val oy = o.centerY() - at[1]
            val os = (o.width() / room).coerceIn(0.35f, 1.2f)
            x = ox + (x - ox) * k; y = oy + (y - oy) * k; scale = os + (scale - os) * k
        }
        if (picture != null && !picture.isRecycled) art.sheetPicture(c, picture, x, y, room, scale, f.noteAlpha, f.lift, unit)
        else art.sheet(c, text, x, y, room, scale, f.noteAlpha, f.lift, unit)
        val cw = PinBeam.CARD_W * w * f.cardScale
        art.card(c, face, f.cardX * w, f.cardY * h, cw, cw / PinBeam.CARD_SHAPE, f.cardTurn, f.cardAlpha, f.face, f.glow, f.edge, 0.09f)
        if (t >= PinBeam.ARRIVE_AT - PinActivity.TELL_LEAD_MS) run.tellBack()
        if (t >= PinBeam.CHIME_AT - PinChime.LEAD_MS) run.chime()
        frames++; if (last != 0L) worst = maxOf(worst, now - last); last = now
        if (t >= PinBeam.FRONT_MS) { Log.i(TAG, "beam: the front is done; $frames pictures, $worst ms at most between two"); stop() } else postInvalidateOnAnimation()
    }

    private companion object { const val TAG = "AiUsagePin" }
}
