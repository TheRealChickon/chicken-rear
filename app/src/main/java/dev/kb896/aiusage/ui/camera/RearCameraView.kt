package dev.kb896.aiusage.ui.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.camera.view.PreviewView
import dev.kb896.aiusage.ui.camera.RearCameraLayout as L

/**
 * THE CAMERA PAGE ON THE BACK, laid out as the reference (RearCameraLayout): the live picture over the whole panel,
 * the mode words low with the chosen one in the camera's yellow, a column on the right with the last photo, the
 * zoom chip ("0.7×"), the flash and the shutter. Everything is placed in the back's own pixels and scaled to the view.
 *
 * The picture is a MIRROR: the person in front of the back sees themselves as in a mirror (raise the right hand, it
 * is on the right), as every selfie camera shows it; the photo is saved the right way round.
 *
 * The page is drawn by the window's hardware renderer, the picture included (a TextureView, PreviewView's COMPATIBLE
 * mode): one pre-rotated buffer for the whole page, no layer of its own SurfaceFlinger would have to turn.
 */
class RearCameraView(context: Context) : ViewGroup(context) {
    val preview = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
        // A mirror for the person looking at the back (see above). Touches are read by the page, not by this view.
        scaleX = -1f
    }
    val thumbnail = Thumb(context).apply {
        contentDescription = "Last photo"
        visibility = GONE
    }
    val zoom = Round(context, Round.Kind.TEXT).apply { contentDescription = "Ultra-wide" }
    val flash = Round(context, Round.Kind.FLASH).apply { contentDescription = "Flash off" }
    val shutter = Shutter(context)
    /** One word per mode, each at its own place (RearCameraLayout.modeCx); only the modes there are are shown. */
    private val words: Map<RearCameraPolicy.Mode, TextView> = RearCameraPolicy.Mode.entries.associateWith { mode ->
        label(L.MODES_TEXT).apply {
            text = RearCameraPolicy.word(mode)
            // Regular, as the reference's words (lighter than the chip's figures).
            typeface = Typeface.create(Typeface.DEFAULT, 400, false)
            setPadding(L.MODES_PAD, 0, L.MODES_PAD, 0)
            isClickable = true
            setOnClickListener { onMode(mode) }
            tag = mode
            visibility = GONE
        }
    }
    /** The mode words shown, in their order on the page. */
    val modeWords: List<TextView> get() = shownModes.map { words.getValue(it) }
    /** The recording time, top centre; hidden while not recording. */
    val time = label(L.TIME_TEXT).apply { visibility = GONE }
    /** One line when something failed; hidden otherwise. */
    val note = label(L.NOTE_TEXT).apply { visibility = GONE }
    private val blinkView = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 0f }

    var onBack: () -> Unit = {}
    var onMode: (RearCameraPolicy.Mode) -> Unit = {}
    /** A tap on the picture, in the picture's own (mirrored back) view coordinates. */
    var onFocus: (Float, Float) -> Unit = { _, _ -> }
    /** A tap on the last photo. */
    var onThumbnail: () -> Unit = {}

    private var shownModes: List<RearCameraPolicy.Mode> = emptyList()

    init {
        setBackgroundColor(Color.BLACK)
        addView(preview); addView(blinkView)
        listOf(thumbnail, zoom, flash, shutter).forEach(::addView)
        words.values.forEach(::addView)
        addView(time); addView(note)
        thumbnail.setOnClickListener { onThumbnail() }
        showModes(RearCameraPolicy.Mode.PHOTO, bokeh = false)
    }

    /** The words for the modes there are, [selected] in yellow. */
    fun showModes(selected: RearCameraPolicy.Mode, bokeh: Boolean) {
        shownModes = RearCameraPolicy.modes(bokeh)
        for ((mode, word) in words) {
            word.visibility = if (mode in shownModes) VISIBLE else GONE
            word.isSelected = mode == selected
            word.setTextColor(if (word.isSelected) L.YELLOW else Color.WHITE)
            word.contentDescription = "${word.text} mode${if (word.isSelected) ", chosen" else ""}"
        }
        requestLayout()
    }

    fun controlsEnabled(enabled: Boolean, canReconfigure: Boolean = enabled) {
        shutter.isEnabled = enabled; zoom.isEnabled = canReconfigure; flash.isEnabled = enabled
        words.values.forEach { it.isEnabled = canReconfigure }
        shutter.alpha = if (enabled) 1f else 0.45f
    }

    /** The last photo or film taken here; null: none (the round picture is not shown). */
    fun lastTaken(picture: android.graphics.Bitmap?) {
        thumbnail.picture = picture
        thumbnail.visibility = if (picture == null) GONE else VISIBLE
    }

    /** A line that something failed; empty: none. */
    fun note(text: String) { note.text = text; note.visibility = if (text.isEmpty()) GONE else VISIBLE }

    /** The recording time ("0:42"); null: not recording. */
    fun recording(time: String?) {
        if (time == null) { this.time.visibility = GONE; return }
        // A red dot before the time, as cameras have it.
        this.time.text = android.text.SpannableString("\u25CF  $time").apply {
            setSpan(android.text.style.ForegroundColorSpan(L.RED), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        this.time.visibility = VISIBLE
    }

    /** The shutter's feedback: the picture blinks dark for a moment and the button gives. */
    fun blink() {
        blinkView.animate().cancel(); blinkView.alpha = 0.6f
        blinkView.animate().alpha(0f).setDuration(160).start()
        shutter.animate().cancel(); shutter.scaleX = 0.9f; shutter.scaleY = 0.9f
        shutter.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    // -- the page's own fingers: back the way it came (from anywhere above the words), a tap on the picture to focus ----

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var fingers = 0

    private fun px(x: Float) = x * L.WIDTH / width.coerceAtLeast(1)
    private fun py(y: Float) = y * L.HEIGHT / height.coerceAtLeast(1)

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // Every finger is counted here, whoever ends up with the touch: a button under it, or the page.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = px(e.x); downY = py(e.y); downAt = e.eventTime; fingers = 1 }
            MotionEvent.ACTION_POINTER_DOWN -> fingers = maxOf(fingers, e.pointerCount)
        }
        return super.dispatchTouchEvent(e)
    }

    /** Nothing here scrolls: a button shows its press at once. */
    override fun shouldDelayChildPressedState(): Boolean = false

    /** A stroke back that began on a round button is the page's once it is clearly one (the button's press cancelled). */
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean =
        e.actionMasked == MotionEvent.ACTION_MOVE && RearCameraPolicy.strokeStarted(downX, downY, px(e.x), py(e.y), fingers)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val x = px(e.x); val y = py(e.y)
                when {
                    RearCameraPolicy.leaves(downX, downY, x, y, fingers) -> onBack()
                    RearCameraPolicy.tap(downX, downY, x, y, e.eventTime - downAt, fingers) ->
                        // The picture is mirrored: its own coordinates run the other way across.
                        onFocus(preview.width - (e.x - preview.left), e.y - preview.top)
                }
                fingers = 0
            }
            MotionEvent.ACTION_CANCEL -> fingers = 0
        }
        return true
    }

    // -- layout, in the back's pixels -------------------------------------------------------------------------------

    private fun sx(v: Int) = v * measuredWidth / L.WIDTH
    private fun sy(v: Int) = v * measuredHeight / L.HEIGHT

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        val scale = measuredHeight.toFloat() / L.HEIGHT
        val pad = Math.round(L.MODES_PAD * scale)
        for (word in words.values) {
            word.setTextSize(TypedValue.COMPLEX_UNIT_PX, L.MODES_TEXT * scale)
            word.setPadding(pad, 0, pad, 0)
        }
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, L.TIME_TEXT * scale)
        note.setTextSize(TypedValue.COMPLEX_UNIT_PX, L.NOTE_TEXT * scale)
        zoom.textPx = L.ZOOM_TEXT * scale
        val exact = MeasureSpec.EXACTLY
        val loose = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.AT_MOST)
        fun exactly(v: View, w: Int, h: Int) = v.measure(MeasureSpec.makeMeasureSpec(w, exact), MeasureSpec.makeMeasureSpec(h, exact))
        exactly(preview, measuredWidth, measuredHeight); exactly(blinkView, measuredWidth, measuredHeight)
        fun circle(v: View, c: L.Circle, min: Int = 96) { val b = c.box(min); exactly(v, sx(b.width), sy(b.height)) }
        circle(thumbnail, L.thumbnail, min = 0); circle(zoom, L.zoom); circle(flash, L.flash); circle(shutter, L.shutter, 128)
        for (word in words.values) word.measure(loose, MeasureSpec.makeMeasureSpec(sy(L.MODES_HEIGHT), exact))
        time.measure(loose, MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.AT_MOST))
        note.measure(MeasureSpec.makeMeasureSpec(sx(L.WIDTH - L.SEEN_LEFT - 40), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.AT_MOST))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        preview.layout(0, 0, width, height); blinkView.layout(0, 0, width, height)
        fun at(v: View, cx: Int, cy: Int) {
            val x = sx(cx) - v.measuredWidth / 2; val y = sy(cy) - v.measuredHeight / 2
            v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
        }
        at(thumbnail, L.thumbnail.cx, L.thumbnail.cy); at(zoom, L.zoom.cx, L.zoom.cy)
        at(flash, L.flash.cx, L.flash.cy); at(shutter, L.shutter.cx, L.shutter.cy)
        for ((mode, word) in words) at(word, L.modeCx(mode), L.MODES_CY)
        at(time, L.SEEN_CX, L.TIME_CY); at(note, L.SEEN_CX, L.NOTE_CY)
    }

    private fun label(size: Float) = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        includeFontPadding = false
        setShadowLayer(4f, 0f, 1f, 0x66000000)
        isClickable = false
    }

    /** The last one taken, round: its middle filling the circle (drawn round, not clipped, so it is round on any canvas). */
    class Thumb(context: Context) : View(context) {
        var picture: android.graphics.Bitmap? = null
            set(value) { field = value; shader = value?.let { android.graphics.BitmapShader(it, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP) }; invalidate() }
        private var shader: android.graphics.BitmapShader? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val fit = android.graphics.Matrix()
        init { isClickable = true; isFocusable = true }

        override fun onDraw(canvas: Canvas) {
            val bmp = picture ?: return
            val s = shader ?: return
            val r = minOf(width, height) / 2f
            val scale = 2f * r / minOf(bmp.width, bmp.height)
            fit.setScale(scale, scale)
            fit.postTranslate(width / 2f - bmp.width * scale / 2f, height / 2f - bmp.height * scale / 2f)
            s.setLocalMatrix(fit)
            paint.shader = s
            canvas.drawCircle(width / 2f, height / 2f, r, paint)
        }
    }

    /** The round dark buttons: the zoom chip (its words) and the flash (a bolt; struck through while off). */
    class Round(context: Context, private val kind: Kind) : View(context) {
        enum class Kind { TEXT, FLASH }
        var text = ""; set(value) { field = value; invalidate() }
        var on = false; set(value) { field = value; invalidate() }
        var textPx = L.ZOOM_TEXT; set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bolt = Path()
        private val textFace = Typeface.create(Typeface.DEFAULT, 500, false)
        private val metrics = Paint.FontMetrics()
        init { isClickable = true; isFocusable = true }

        override fun onDraw(canvas: Canvas) {
            val k = height.toFloat() / (2 * maxOf(L.zoom.r, 48))
            val cx = width / 2f; val cy = height / 2f
            val r = (if (kind == Kind.TEXT) L.zoom.r else L.flash.r) * k
            paint.style = Paint.Style.FILL; paint.color = L.DARK
            canvas.drawCircle(cx, cy, r, paint)
            when (kind) {
                Kind.TEXT -> {
                    paint.color = Color.WHITE; paint.textSize = textPx; paint.textAlign = Paint.Align.CENTER
                    paint.typeface = textFace
                    paint.getFontMetrics(metrics)
                    canvas.drawText(text, cx, cy - (metrics.ascent + metrics.descent) / 2f, paint)
                }
                Kind.FLASH -> {
                    // A bolt 34 px tall (in the back's pixels), centred.
                    val u = 34f * k / 24f
                    val ox = cx - 12f * u; val oy = cy - 12f * u
                    bolt.reset()
                    bolt.moveTo(ox + 14f * u, oy + 1f * u); bolt.lineTo(ox + 5f * u, oy + 13.5f * u); bolt.lineTo(ox + 11f * u, oy + 13.5f * u)
                    bolt.lineTo(ox + 10f * u, oy + 23f * u); bolt.lineTo(ox + 19f * u, oy + 10f * u); bolt.lineTo(ox + 13f * u, oy + 10f * u); bolt.close()
                    paint.color = if (on) L.YELLOW else Color.WHITE
                    canvas.drawPath(bolt, paint)
                    if (!on) {
                        // Struck through, with a dark edge so the stroke reads over the bolt.
                        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
                        paint.strokeWidth = 6f * k; paint.color = 0xFF3A3A3A.toInt()
                        canvas.drawLine(cx - 13f * k, cy - 13f * k, cx + 13f * k, cy + 13f * k, paint)
                        paint.strokeWidth = 2.5f * k; paint.color = Color.WHITE
                        canvas.drawLine(cx - 13f * k, cy - 13f * k, cx + 13f * k, cy + 13f * k, paint)
                    }
                }
            }
        }
    }

    /** The shutter: a white ring and a white disc; for video a red disc, and while recording a red square. */
    class Shutter(context: Context) : View(context) {
        var video = false; set(value) { field = value; describe(); invalidate() }
        var recording = false; set(value) { field = value; describe(); invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { isClickable = true; isFocusable = true; describe() }
        private fun describe() { contentDescription = if (recording) "Stop recording" else if (video) "Record video" else "Take photo" }

        override fun onDraw(canvas: Canvas) {
            val k = height.toFloat() / 128f
            val cx = width / 2f; val cy = height / 2f
            val outer = L.shutter.r * k
            val ring = L.SHUTTER_RING * k
            paint.style = Paint.Style.STROKE; paint.strokeWidth = ring; paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, outer - ring / 2f, paint)
            paint.style = Paint.Style.FILL
            val inner = outer - ring - L.SHUTTER_GAP * k
            when {
                recording -> {
                    paint.color = L.RED
                    val h = inner * 0.42f
                    canvas.drawRoundRect(cx - h, cy - h, cx + h, cy + h, 8f * k, 8f * k, paint)
                }
                video -> { paint.color = L.RED; canvas.drawCircle(cx, cy, inner, paint) }
                else -> { paint.color = Color.WHITE; canvas.drawCircle(cx, cy, inner, paint) }
            }
        }
    }
}
