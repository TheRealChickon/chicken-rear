package dev.kb896.aiusage.circle

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.PathParser
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A translated block drawn over the place of the original text. */
/** What to paint over one original text line: a flat colour, or a small bitmap stretched over the box. */
class Fill(val box: Rect, val color: Int, val bmp: Bitmap?)
/** [clip]: the part of the screen the block belongs to (the page between the app's bars), fixed while the page moves;
 *  [room]: the block's own place (its bubble, down to the next block), which moves with it. Nothing is drawn outside both. */
/** [clip]: the part of the screen the block belongs to (the page between the app's bars), fixed while the page moves;
 *  [room]: the block's own place (its bubble, down to the next block), which moves with it. Nothing is drawn outside both. */
class Patch(val fills: List<Fill>, val layout: StaticLayout, val x: Float, val y: Float, val clip: Rect? = null, val room: Rect? = null) {
    /** Rows of the screen this patch covers (what it erases and what it writes). */
    val top: Int = minOf(fills.minOfOrNull { it.box.top } ?: y.toInt(), y.toInt())
    val bottom: Int = maxOf(fills.maxOfOrNull { it.box.bottom } ?: 0, (y + layout.height).toInt())
    val text: String get() = layout.text.toString()
    fun moved(up: Int) = Patch(fills.map { Fill(Rect(it.box).apply { offset(0, -up) }, it.color, it.bmp) }, layout, x, y - up, clip, room?.let { Rect(it).apply { offset(0, -up) } })
}

@SuppressLint("ViewConstructor")
class OverlayView internal constructor(
    private val svc: CircleOverlay, val depth: Int, internal val ocrModel: OcrModel,
) : View(svc) {
    enum class Mode { FROZEN, PASS }

    var mode = Mode.FROZEN
    private val announcedFallbacks = mutableSetOf<String>()
    var shot: Bitmap? = null
    var soft: Bitmap? = null
    var ocr: OcrResult? = null
        private set

    private val d = resources.displayMetrics.density
    private val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val surface = if (dark) 0xFF1F1F1F.toInt() else Color.WHITE
    private val onSurface = if (dark) 0xFFE3E3E3.toInt() else 0xFF1F1F1F.toInt()
    private val onSurfaceDim = if (dark) 0xFFC4C7C5.toInt() else 0xFF444746.toInt()
    private val accent = if (dark) 0xFFA8C7FA.toInt() else 0xFF0B57D0.toInt()
    private val slop = ViewConfiguration.get(svc).scaledTouchSlop

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 12 * d; maskFilter = BlurMaskFilter(6 * d, BlurMaskFilter.Blur.NORMAL)
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("google-sans", Typeface.NORMAL) }
    private val medium = Typeface.create(Typeface.create("google-sans", Typeface.NORMAL), 500, false)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface; setShadowLayer(10 * d, 0f, 3 * d, 0x55000000) }

    private val gPaths = listOf(
        0xFFEA4335.toInt() to "M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z",
        0xFF4285F4.toInt() to "M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z",
        0xFFFBBC05.toInt() to "M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z",
        0xFF34A853.toInt() to "M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z",
    ).map { it.first to PathParser.createPathFromPathData(it.second) }
    private val translateIcon = PathParser.createPathFromPathData(
        "M12.87 15.07l-2.54-2.51.03-.03c1.74-1.94 2.98-4.17 3.71-6.53H17V4h-7V2H8v2H1v1.99h11.17C11.5 7.92 10.44 9.75 9 11.35 8.07 10.32 7.3 9.19 6.69 8h-2c.73 1.63 1.73 3.17 2.98 4.56l-5.09 5.02L4 19l5-5 3.11 3.11.76-2.04zM18.5 10h-2L12 22h2l1.12-3h4.75L21 22h2l-4.5-12zm-2.62 7l1.62-4.33L19.12 17h-3.24z"
    )

    private val micIcon = PathParser.createPathFromPathData(
        "M12 14c1.66 0 2.99-1.34 2.99-3L15 5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z")
    private val noteIcon = PathParser.createPathFromPathData(
        "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z")
    private val capsule = RectF()
    private val musicBtn = RectF()

    // ---- state ----
    /** Top edge of the results sheet in view coordinates while one is open over this level, else null. */
    private var sheetTop: Float? = null
    private var panY = 0f          // the frozen screen is shifted up by this much so the selection stays above the sheet
    private var calm = 0f          // 0 = idle look (colour wash, bottom bar), 1 = something is selected (plain dim)
    private var calmAnim: ValueAnimator? = null
    private var drift = 0f
    private val driftAnim = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 9000; repeatCount = ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { drift = it.animatedValue as Float; if (mode == Mode.FROZEN && calm < 1f) invalidate() }
    }
    private var openAt = 0L            // 0 = not started (window staged, invisible), -1 = starts with the next frame
    private var openMs = 0f
    var onFirstFrame: (() -> Unit)? = null
    private val washT = floatArrayOf(37f, 45f, 53f, 61f, 71f, 86f, 103f, 136f, 186f, 245f, 328f, 428f, 595f, 1031f)
    private val washA = floatArrayOf(0f, 0.29f, 0.39f, 0.44f, 0.50f, 0.57f, 0.61f, 0.69f, 0.76f, 0.83f, 0.886f, 0.93f, 0.957f, 1f)

    /** Starts the opening animation; its clock begins with the first frame drawn from now. */
    fun start() {
        if (openAt != 0L) return
        openAt = -1L
        ocr?.let(::showOcrFallback)
        invalidate()
    }

    private fun key(t: Float, ts: FloatArray, vs: FloatArray): Float {
        if (t <= ts[0]) return vs[0]
        for (i in 1 until ts.size) if (t < ts[i]) return vs[i - 1] + (vs[i] - vs[i - 1]) * (t - ts[i - 1]) / (ts[i] - ts[i - 1])
        return vs[vs.size - 1]
    }

    /** Displacement of a damped spring from its target, [t] seconds after starting at [x0] with speed [v0]. */
    private fun spring(t: Float, x0: Float, v0: Float, w: Float, zeta: Float): Float {
        val wd = w * kotlin.math.sqrt(1f - zeta * zeta)
        return (kotlin.math.exp(-zeta * w * t) * (x0 * kotlin.math.cos(wd * t) + (v0 + zeta * w * x0) / wd * kotlin.math.sin(wd * t)))
    }
    private val pts = ArrayList<PointF>()
    private val ptT = ArrayList<Long>()      // when each stroke point was drawn (for the fading trail)
    private val tail = Path()
    private var ignoreGesture = false         // a second finger came down: not a stroke
    // The idle capsule can be dragged up and down; released near the top or bottom it snaps there.
    private var capOff = 0f                   // 0 = home position at the bottom, negative = moved up
    private val capSpring = Spring(420f, 0.82f) { capOff = it; invalidate() }
    private var capDown = false
    private var capDragging = false
    private var capDownY = 0f
    private var capStart = 0f
    private var capVt: android.view.VelocityTracker? = null
    private var toSheet: Sheet? = null        // this gesture began just above the results sheet and moves it
    private var wholeWords: List<SelLogic.W> = emptyList()
    private var firstGlyph = 0
    private var lastGlyph = Int.MAX_VALUE

    private fun resetGlyphs() {
        firstGlyph = 0
        lastGlyph = Int.MAX_VALUE
        ws = wholeWords
    }

    private fun trimSelection() {
        val words = ocr?.words ?: return
        if (selA !in words.indices || selB !in words.indices) return
        val bounds = wholeWords.toMutableList()
        bounds[selA] = words[selA].selection(firstGlyph, if (selA == selB) lastGlyph else words[selA].characters.lastIndex)
        if (selB != selA) bounds[selB] = words[selB].selection(last = lastGlyph)
        ws = bounds
    }

    private fun moveHandle(x: Float, y: Float) {
        val words = ocr?.words ?: return
        val i = SelLogic.nearestWord(wholeWords,x,y)
        if (i < 0) return
        val glyph = words[i].glyphAt(x)
        if (dragHandle == 1) {
            if (i < selB || i == selB && glyph <= lastGlyph) { selA = i; firstGlyph = glyph }
            else {
                selA = selB; firstGlyph = lastGlyph.coerceAtMost(words[selB].characters.lastIndex.coerceAtLeast(0))
                selB = i; lastGlyph = glyph; dragHandle = 2
            }
        } else {
            if (i > selA || i == selA && glyph >= firstGlyph) { selB = i; lastGlyph = glyph }
            else {
                selB = selA; lastGlyph = firstGlyph
                selA = i; firstGlyph = glyph; dragHandle = 1
            }
        }
        reread = null
        trimSelection()
        invalidate()
    }

    private var ws: List<SelLogic.W> = emptyList()   // the OCR words in the form the selection logic works on
    // zoom: content appears on screen at content * zoom + (zox, zoy)
    private var zoom = 1f
    private var zox = 0f
    private var zoy = 0f
    private var zoomAnim: ValueAnimator? = null
    private var pinching = false
    private var pinchSpan0 = 1f
    private var pinchZoom0 = 1f
    private var pinchFx = 0f
    private var pinchFy = 0f
    // long press on a word, then drag: live text selection
    private var lpWord = -1
    private var lpAnchor = -1
    private var textDrag = false
    private var strokeMoved = false
    private var travel = 0f
    private var lastSX = 0f
    private var lastSY = 0f
    // a picture region being moved or resized
    private var regionGrab = -1
    private var regionStart: RectF? = null
    private var downCX = 0f
    private var downCY = 0f
    private val stroke = Path()
    private var drawing = false
    private var pendingClassify = false
    private var wantTranslate = false
    private var selA = -1
    private var selB = -1
    private var region: RectF? = null
    private var dragHandle = 0 // 1 = start, 2 = end
    private var downX = 0f
    private var downY = 0f
    private var pressed: (() -> Unit)? = null
    private var toast: String? = null
    private var status: String? = null
    private var card: Card? = null
    private var patches = ArrayList<Patch>()

    // ---- translation laid over the frozen screen  ----
    private val tl = TrLook(dark)
    /** Translate is on while the screen is still frozen. */
    var trOn = false
        private set
    private var trT0 = 0L                 // clock of the bar's change-over (see trPhase)
    private var trSrc = "und"
    private var trTgt = "en"
    private var leaveT0 = 0L              // "Scroll and translate" was pressed: the bar drops away
    private val chipCap = RectF()
    private val srcChip = RectF()
    private val tgtChip = RectF()
    private val swipeIcon = TrLook.swipeUp()
    private val arrowIcon = PathParser.createPathFromPathData(TrLook.ARROW)
    val patchCount: Int get() = patches.size

    /** 0 = search bar, [TrLook.CHANGE_MS] = "Scroll and translate" bar; runs forwards when switched on, backwards when off. */
    private fun trPhase(): Float {
        if (trT0 == 0L) return 0f
        val e = (android.os.SystemClock.uptimeMillis() - trT0).toFloat()
        return if (trOn) e.coerceAtMost(TrLook.CHANGE_MS) else (TrLook.CHANGE_MS - e).coerceAtLeast(0f)
    }
    private fun setTr(on: Boolean) {
        if (trOn == on) return
        val ph = trPhase()
        trOn = on
        trT0 = android.os.SystemClock.uptimeMillis() - (if (on) ph else TrLook.CHANGE_MS - ph).toLong()
        invalidate()
    }
    fun setLangs(src: String, tgt: String) { trSrc = src; trTgt = tgt; invalidate() }

    /** Translate switched off on the frozen screen: the original is shown again, the search bar comes back. */
    fun stopTranslate() {
        if (!trOn) return
        setTr(false); svc.stopInPlace(this); clearPatches()
    }

    /** "Scroll and translate": the bar drops away, then the screen is live again with the translation kept on it. */
    private fun scrollAndTranslate() {
        if (leaveT0 != 0L || !trOn) return
        leaveT0 = android.os.SystemClock.uptimeMillis()
        zoomAnim?.cancel(); zoom = 1f; zox = 0f; zoy = 0f
        invalidate()
        postDelayed({ if (isAttachedToWindow && mode == Mode.FROZEN) svc.enterScroll(this) }, TrLook.LEAVE_MS.toLong())
    }

    private class Item(val label: String, val rect: RectF, val run: () -> Unit)
    private var items: List<Item> = emptyList()
    private val itemsBar = RectF()

    private class Card(val src: String, var tgt: String, val original: String, var layout: StaticLayout?, val rect: RectF = RectF())

    private val barRect = RectF()
    private val trBtn = RectF()
    private val closeBtn = RectF()

    /** The status bar stays live above the overlay; this repaints its frozen copy without the icons. */
    var statusStrip: Bitmap? = null
    var statusH = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        driftAnim.start()
    }

    override fun onDetachedFromWindow() {
        driftAnim.cancel(); zoomAnim?.cancel(); panAnim?.cancel(); calmAnim?.cancel(); capSpring.stop(); edgeAnim?.cancel(); glowAnim?.cancel()
        removeCallbacks(longPress); capVt?.recycle(); capVt = null
        super.onDetachedFromWindow()
    }

    private fun setCalm(on: Boolean) {
        // Selecting something works on the original text: clear the translation first.
        if (on && trOn) stopTranslate()
        val to = if (on) 1f else 0f
        if (calm == to && calmAnim == null) return
        calmAnim?.cancel()
        calmAnim = ValueAnimator.ofFloat(calm, to).apply {
            duration = 220
            addUpdateListener { calm = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Called by the service when a results sheet opens, moves or closes over this level. */
    fun setSheet(top: Float?) {
        val moving = sheetTop != null && top != null
        sheetTop = top
        updatePan(follow = moving); if (moving) panTarget = panY
        invalidate()
    }

    /** Back inside the overlay: closes the translation card if one is open; false means "nothing to step back from". */
    fun stepBack(): Boolean {
        if (card == null) return false
        card = null; svc.setSheetVisible(this, true); layoutItems(); invalidate(); return true
    }

    private var panAnim: ValueAnimator? = null

    /** [follow] = the sheet is moving right now: track it directly instead of animating towards it. */
    private fun updatePan(follow: Boolean = false) {
        val st = sheetTop
        var want = 0f
        if (st != null) {
            var bottom = region?.let { pY(it.bottom) } ?: 0f
            if (selA >= 0 && selB < ws.size) for (i in selA..selB) bottom = max(bottom, pY(ws[i].lb.toFloat()))
            // never push the picture further than the sheet is tall (a zoomed selection can lie far below)
            want = max(0f, bottom + 56 * d - st).coerceAtMost(max(0f, height - st))
        }
        if (follow && panAnim?.isRunning != true) { panY = want; if (selA >= 0) layoutItems(); return }
        if (want == panTarget) return
        panTarget = want
        panAnim?.cancel()
        panAnim = ValueAnimator.ofFloat(panY, want).apply {
            duration = 240; interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { panY = it.animatedValue as Float; if (it.animatedFraction >= 1f && selA >= 0) layoutItems(); invalidate() }
            start()
        }
    }
    private var panTarget = 0f

    /** The sheet was swiped away at the first level: back to the idle frozen screen. */
    fun resetToIdle() { selA = -1; selB = -1; items = emptyList(); card = null; region = null; sheetTop = null; systemGestureExclusionRects = emptyList(); setCalm(false); updatePan(); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        glowShader = null
        // The capsule holds the search pill and two round buttons.
        val side = 24 * d
        val ch = 68 * d
        val bottom = h - 49 * d
        capsule.set(side, bottom - ch, w - side, bottom)
        val inset = 9 * d; val bd = ch - 2 * inset; val gap = 6 * d
        trBtn.set(capsule.right - inset - bd, capsule.top + inset, capsule.right - inset, capsule.bottom - inset)
        musicBtn.set(trBtn.left - gap - bd, trBtn.top, trBtn.left - gap, trBtn.bottom)
        barRect.set(capsule.left + inset, trBtn.top, musicBtn.left - gap, trBtn.bottom)
        closeBtn.set(22 * d, 52 * d, 22 * d + 40 * d, 92 * d)
    }

    fun setOcr(r: OcrResult) {
        ocr = r
        showOcrFallback(r)
        if (selB >= r.words.size || lpWord >= r.words.size) { selA = -1; selB = -1; lpWord = -1; lpAnchor = -1; textDrag = false; dragHandle = 0; items = emptyList() }
        wholeWords = r.words.map { it.selection() }
        resetGlyphs()
        if (wantTranslate) { wantTranslate = false; setStatus(null); startTranslate() }
        if (pendingClassify) { pendingClassify = false; classify() }
        invalidate()
    }

    internal fun showOcrFallback(result: OcrResult) {
        if (openAt == 0L || !isShown) return
        if (announcedFallbacks.addAll(result.fastFallback)) {
            android.widget.Toast.makeText(context, result.fallbackNotice, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun setStatus(s: String?) { status = s; invalidate() }

    // ---------- patches (translation in place) ----------

    fun clearPatches() { patches = ArrayList(); pending = null; lists.clear(); listOf.clear(); invalidate() }

    // A newly read screen replaces the translations in one go: they are collected first and swapped in together,
    // so the screen never stands empty for a frame between the old set and the new one.
    private var pending: ArrayList<Patch>? = null
    fun beginPatches() { pending = ArrayList() }
    /** A new set is still being collected (not yet swapped in). */
    val collectingPatches: Boolean get() = pending != null
    fun commitPatches() {
        val p = pending ?: return
        pending = null; patches = p
        kickGlow()
        invalidate()
    }

    /**
     * The page has moved: every translation is asked where its text went. [where] gets the rows a patch covers and
     * answers how far up it moved (0 = it did not move), or null if its text is no longer there. Returns how many stayed.
     */
    fun shiftPatches(retainWhenNone: Boolean = false, where: (top: Int, bottom: Int) -> Int?): Int {
        val out = ArrayList<Patch>()
        for (p in patches) { val up = where(p.top, p.bottom) ?: continue; out.add(if (up == 0) p else p.moved(up)) }
        if (out.isNotEmpty() || !retainWhenNone) { patches = out; invalidate() }
        return out.size
    }
    fun discardPendingPatches() { pending = null }

    val hasPendingPatches: Boolean get() = pending?.isNotEmpty() == true

    fun addPatch(b: Block, translated: String, src: Bitmap) {
        (pending ?: patches).add(make(b, translated, src))
        if (pending == null) invalidate()
    }

    /** One translated paragraph, within the measured footprint of the original. */
    private fun make(b: Block, translated: String, src: Bitmap): Patch {
        val w = width
        val pad = (3 * d).toInt()
        // Erase each original line with its own reconstructed background.
        val fills = ArrayList<Fill>()
        var fg = 0; var weight = 400; var ground = 0
        val ref = b.lines.sortedBy { it.box.height() }[b.lines.size / 2]
        for (l in b.lines) {
            val s = sampleLine(src, l.box, pad, if (l === ref) l.text else null)
            fills.add(s.fill)
            if (l === ref) { fg = s.fg; weight = s.weight; ground = s.ground }
        }
        val lh = ref.box.height().toFloat()
        val base = Typeface.DEFAULT
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; typeface = Typeface.create(base, weight, false) }
        val start = planned[b] ?: estimateSize(b, src)
        var size = start
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            val spacing = b.lines.filter { it.text.length >= 5 && it.text.none(::isCjk) }.map { line ->
                val bounds = Rect()
                p.textSize = start
                p.getTextBounds(line.text, 0, line.text.length, bounds)
                Look.letterSpacing(line.box.width().toFloat(), bounds.width().toFloat(), start, line.text.length - 1)
            }.sorted()
            if (spacing.isNotEmpty()) p.letterSpacing = spacing[spacing.size / 2]
        }
        // The block's own line pitch (top to top), else the screen's for text of this size, else the font's.
        // The recognizer's line boxes follow ascenders and descenders ("again." starts at its x-height): the step from
        // the first line's baseline to the last one's, as the picture shows them, is what the app laid out; the
        // lines' centres when the picture does not tell.
        val bg0 = fills.firstOrNull()?.takeIf { it.bmp == null }?.color
        val bl = if (b.lines.size > 1 && bg0 != null) TrBlocks.baseline(src, w, height, b.lines.first().box, bg0)?.let { a -> TrBlocks.baseline(src, w, height, b.lines.last().box, bg0)?.let { z -> z - a } } else null
        val tops = if (b.lines.size > 1) listOf((bl?.toFloat()?.takeIf { it > 0f } ?: (b.lines.last().box.exactCenterY() - b.lines.first().box.exactCenterY())) / (b.lines.size - 1)) else emptyList()
        val pitch0 = if (tops.isNotEmpty()) tops[0] else start * (pitchRatio.takeIf { it > 0f } ?: 1.32f)
        val single = b.lines.size == 1
        val cx = b.box.exactCenterX()
        // Centred when the block sits on the screen's centre line, or when its lines share a centre but not a left edge.
        // Centred lines share a centre and stand in from the block's edges by the same amount on both sides; lines
        // that share a right edge but not a left one are right-aligned.
        // (never a list item: its bullet stands at its left, wherever the line happens to end)
        val centered = if (b.keepL != Int.MIN_VALUE) false else if (single) abs(cx - w / 2f) < 14 * d && b.box.width() < w * 0.8f
        else b.lines.all { abs(it.box.exactCenterX() - cx) < w * 0.025f } &&
            b.lines.any { it.box.left - b.box.left > w * 0.03f && abs((it.box.left - b.box.left) - (b.box.right - it.box.right)) < w * 0.02f }
        val inCol = !centered && b in rightCol
        val rightAligned = inCol || !single && !centered && b.lines.all { b.box.right - it.box.right < w * 0.012f } && b.lines.any { it.box.left - b.box.left > w * 0.04f }
        // Where the block may draw: its part of the screen (the page between the app's bars), its own plain area (a chat
        // bubble, a card), never over a bullet or icon beside it, and down only to the next block.
        val clip = clips[b] ?: Rect(0, 0, w, height)
        val bg = fills.firstOrNull()?.takeIf { it.bmp == null }?.color
        val cont = if (bg != null) TrBlocks.container(src, w, height, b, bg, d) else Rect(0, b.box.top, w, height)
        cont.left = max(cont.left, max(clip.left, if (b.keepL == Int.MIN_VALUE) 0 else b.keepL))
        cont.right = min(cont.right, min(clip.right, if (b.keepR == Int.MAX_VALUE) w else b.keepR))
        // Ink beside a line that is not part of it (words the recognizer did not read between two emoji, a settings
        // row's value) is not erased: the translation may not run over it. A line of its own ends its place there;
        // the lines of a paragraph are kept short of it one by one (indents in build()), and so is their erasing.
        val ground0 = bg ?: ground
        val gapPx = max((lh * 0.2f).toInt(), (3 * d).toInt())
        val beside = b.lines.map { l -> TrBlocks.inkBeside(src, w, height, l.box.top, l.box.bottom, l.box.right, cont.right, ground0, gapPx) }
        for ((i, x) in beside.withIndex()) if (x != null) fills.getOrNull(i)?.takeIf { it.bmp == null }?.let { f -> f.box.right = max(b.lines[i].box.right, min(f.box.right, x - 1)) }
        if (single) beside[0]?.let { cont.right = max(b.box.right, min(cont.right, it - gapPx / 2)) }
        // A value in a right-aligned column may grow to the left up to its row's label.
        if (inCol) TrBlocks.inkBeside(src, w, height, b.box.top, b.box.bottom, b.box.left, cont.left, ground0, gapPx)?.let { cont.left = min(b.box.left, max(cont.left, it + gapPx)) }
        // A list's items start at one left edge (the recognizer's box of one of them can take in part of its bullet).
        val left0 = listOf[b]?.left?.takeIf { it > cont.left } ?: b.box.left
        val padX = max(2, (1.5f * d).toInt())
        val wrapW = when {
            centered -> (2 * min(cx - cont.left, cont.right - cx)).toInt()
            rightAligned -> b.box.right - cont.left
            else -> cont.right - left0
        }.coerceIn(1, max(1, b.box.width() + padX))
        val lineLimit = b.lines.size
        // Bold words and coloured links inside the block keep their look where the translation has them too.
        // (on a gradient or picture too, against the ground's average colour: a bold label on a chat's shaded ground)
        val styled = if (!single || b.lines.first().parts.size >= 3) TrBlocks.styleWords(src, w, height, b, bg ?: ground, fg) else null
        val text: CharSequence = if (styled == null) translated else android.text.SpannableString(translated).also { sp ->
            if (weight < 600) for (r in TrLayout.boldRanges(styled.map { it.text to it.bold }, translated))
                sp.setSpan(android.text.style.TypefaceSpan(Typeface.create(base, max(700, weight + 300).coerceAtMost(900), false)), r.first, r.last + 1, 0)
            for (col in styled.mapNotNull { it.color }.distinct()) for (r in TrLayout.boldRanges(styled.map { it.text to (it.color == col) }, translated))
                sp.setSpan(android.text.style.ForegroundColorSpan(col), r.first, r.last + 1, 0)
        }
        // The first line stands on the original first line: same baseline.
        val firstText = b.lines.first().text
        val base0 = bg?.let { TrBlocks.baseline(src, w, height, b.lines.first().box, it) }?.takeIf { it > b.lines.first().box.top + lh * 0.4f }
            ?: (b.lines.first().box.bottom - if (firstText.any { it in "gjpqyQ,;()[]{}|" }) (start * 0.21f).toInt() else 0)
        val align = if (centered) Layout.Alignment.ALIGN_CENTER else if (rightAligned) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
        fun build(lw: Int, lines: Int, cut: Boolean): StaticLayout {
            // exactly the original pitch at the original size; closer together as the text is made smaller
            val add = pitch0 * size / start - p.getFontMetrics(null)
            val bld = StaticLayout.Builder.obtain(text, 0, text.length, p, max(lw, 1))
                .setIncludePad(false).setMaxLines(lines).setUseLineSpacingFromFallbacks(false)
                .setLineSpacing(add, 1f)
                .setEllipsize(if (cut) TextUtils.TruncateAt.END else null)
                .setAlignment(align)
            // each line of the translation that stands on an original line with foreign ink right of it ends before it
            if (!single && align == Layout.Alignment.ALIGN_NORMAL && beside.any { it != null }) {
                val ind = IntArray(b.lines.size + 1) { i -> beside.getOrNull(i)?.let { x -> val room = x - gapPx / 2 - left0; if (room >= size * 2.5f && room < lw) lw - room else 0 } ?: 0 }
                if (ind.any { it > 0 }) bld.setIndents(null, ind)
            }
            return bld.build()
        }
        val sizeScale = Look.fitScale { scale ->
            size = start * scale
            p.textSize = size
            // A trailing word can otherwise wrap even when a one-line label only misses by a rounding pixel.
            val trial = build(wrapW, lineLimit + 1, false)
            trial.lineCount <= lineLimit && trial.getLineEnd(trial.lineCount - 1) == text.length &&
                (0 until trial.lineCount).all { trial.getLineMax(it) <= wrapW + 1f }
        }
        size = start * sizeScale
        p.textSize = size
        val layout = build(wrapW, lineLimit, true)
        val x = if (centered) cx - wrapW / 2f else if (rightAligned) b.box.right - wrapW.toFloat() else left0.toFloat()
        val y = base0 - layout.getLineBaseline(0).toFloat()
        val inkBottom = y + layout.getLineBaseline(layout.lineCount - 1) + p.descent()
        val sidePad = max(pad, (3 * d).toInt())
        val room = Rect(max(cont.left, b.box.left - sidePad), b.box.top - pad,
            min(cont.right, b.box.right + sidePad), min(cont.bottom, max(b.box.bottom + pad, inkBottom.toInt())))

        return Patch(fills, layout, x, y, clip, room)
    }

    private val planned = HashMap<Block, Float>()

    /** Compare the original glyphs against the same text drawn in the system font at a reference size. */
    private fun estimateSize(b: Block, src: Bitmap?): Float {
        val sizePaint = Paint().apply { typeface = Typeface.DEFAULT; textSize = 100f }
        val tb = Rect()
        sizePaint.getTextBounds("x", 0, 1, tb)
        val referenceX = tb.height().toFloat()
        val sizes = b.lines.map { l ->
            sizePaint.getTextBounds(l.text, 0, l.text.length, tb)
            val measured = if (src != null) inkHeight(src, l.box) else 0
            val glyph = (if (measured > l.box.height() * 0.5f) measured else l.box.height()).toFloat()
            val lower = l.text.count { it in 'a'..'z' }
            val xHeight = if (src != null && lower >= 4 && lower * 2 >= l.text.count { it.isLetter() })
                TrBlocks.xHeight(src, width, height, intArrayOf(l.box.left, l.box.top, l.box.right, l.box.bottom)) ?: 0 else 0
            Look.fontSize(glyph, max(tb.height(), 35).toFloat(), xHeight.toFloat(), referenceX)
        }.sorted()
        return sizes[sizes.size / 2].coerceAtLeast(1f)
    }

    /** Rows of [box] (view coordinates) that hold ink in [src], counted from the first to the last such row; 0 if unclear. */
    private fun inkHeight(src: Bitmap, box: Rect): Int {
        val kx = src.width / width.toFloat(); val ky = src.height / height.toFloat()
        val l = (box.left * kx).toInt().coerceIn(0, src.width - 2); val t = (box.top * ky).toInt().coerceIn(0, src.height - 2)
        val r = (box.right * kx).toInt().coerceIn(l + 1, src.width); val bt = (box.bottom * ky).toInt().coerceIn(t + 1, src.height)
        val bw = r - l; val bh = bt - t
        if (bw < 8 || bh < 6 || bw * bh > 600_000) return 0
        val px = IntArray(bw * bh)
        src.getPixels(px, 0, bw, l, t, bw, bh)
        fun q(c: Int) = (c shr 12 and 0xF00) or (c shr 8 and 0xF0) or (c shr 4 and 0xF)
        val hist = IntArray(4096)
        var i = 0
        while (i < px.size) { hist[q(px[i])]++; i += 3 }
        var key = 0
        for (k in 1 until 4096) if (hist[k] > hist[key]) key = k
        if (hist[key] * 3 * 100 < px.size * 45) return 0                 // no plain background: the box is all there is to go by
        val br = (key shr 8) * 17; val bg = (key shr 4 and 15) * 17; val bb = (key and 15) * 17
        var first = -1; var last = -1
        for (y in 0 until bh) {
            var n = 0
            var x = 0
            while (x < bw) { val c = px[y * bw + x]; if (abs(Color.red(c) - br) + abs(Color.green(c) - bg) + abs(Color.blue(c) - bb) > 110) n++; x += 2 }
            if (n * 2 >= max(2, bw / 80)) { if (first < 0) first = y; last = y }
        }
        return if (first < 0) 0 else ((last - first + 1) / ky).toInt()
    }

    /** What [sizePlan] works out for one screen of blocks; [applySizes] puts it in force (main thread). */
    class SizePlan(val planned: Map<Block, Float>, val clips: Map<Block, Rect>,
                   val pitchRatio: Float, val lists: List<List<Block>>, val rightCol: Set<Block> = emptySet())

    /** Measurements are per block, so intentional size differences survive the translation. */
    fun sizePlan(blocks: List<Block>, src: Bitmap? = null, clipsFor: Map<Block, Rect> = emptyMap()): SizePlan {
        val planned = blocks.associateWith { estimateSize(it, src) }
        // line pitch per size on this screen, for blocks of one line that wrap when translated
        val ratios = blocks.filter { it.lines.size > 1 }.mapNotNull { b ->
            planned[b]?.let { (b.lines.last().box.exactCenterY() - b.lines.first().box.exactCenterY()) / (b.lines.size - 1) / it }
        }.sorted()
        // The items of one list (a bullet left of each, one left edge, lines of one height, one under the other) are
        // Use a shared size to absorb measurement variation across list items.
        val lists = ArrayList<List<Block>>()
        var run = ArrayList<Block>()
        fun lineH(b: Block) = b.lines.map { it.box.height() }.sorted()[b.lines.size / 2]
        for (b in blocks.filter { it.keepL != Int.MIN_VALUE }.sortedBy { it.box.top }) {
            val last = run.lastOrNull()
            // (heights compared loosely: one item's box takes in descenders, another's has none; 38 against 51 px)
            val same = last != null && abs(b.box.left - last.box.left) <= 8 * d && b.box.top - last.box.bottom in -4..(lineH(last) * 1.3f).toInt() &&
                lineH(b).toFloat() / lineH(last) in 0.7f..1.43f
            if (!same) { if (run.size > 1) lists.add(run); run = ArrayList() }
            run.add(b)
        }
        if (run.size > 1) lists.add(run)
        // Values in one right-aligned column (a settings page: the label at the left, the value at the right): lines of
        // their own whose right edges meet within a few px, away from the page's left side, with left edges that differ.
        // Each is set from its right edge (make): placed at their old left edges they ended all over the place.
        val vw = width
        val rightCol = HashSet<Block>()
        val cands = blocks.filter { b ->
            b.keepL == Int.MIN_VALUE && b.box.right > vw * 0.6f &&
                (b.lines.size == 1 && b.box.left > vw * 0.25f ||
                    b.lines.size > 1 && b.lines.all { b.box.right - it.box.right < vw * 0.012f } &&
                    b.lines.any { it.box.left - b.box.left > vw * 0.04f })
        }.sortedBy { it.box.right }
        var c0 = 0
        while (c0 < cands.size) {
            var c1 = c0
            while (c1 + 1 < cands.size && cands[c1 + 1].box.right - cands[c1].box.right <= vw * 0.012f) c1++
            val col = cands.subList(c0, c1 + 1)
            if (col.size >= 2 && col.maxOf { it.box.left } - col.minOf { it.box.left } > vw * 0.04f) rightCol.addAll(col)
            c0 = c1 + 1
        }
        return SizePlan(planned, HashMap(clipsFor), if (ratios.isEmpty()) 0f else ratios[ratios.size / 2], lists, rightCol)
    }

    fun applySizes(p: SizePlan) {
        planned.clear(); planned.putAll(p.planned)
        clips.clear(); clips.putAll(p.clips)
        pitchRatio = p.pitchRatio
        lists.clear(); listOf.clear()
        for (m in p.lists) { val l = ListSet(m); lists.add(l); for (b in m) listOf[b] = l }
        rightCol.clear(); rightCol.addAll(p.rightCol)
    }
    private val clips = HashMap<Block, Rect>()
    private val rightCol = HashSet<Block>()
    private var pitchRatio = 0f

    /** One list's items: held until all of them are translated (or the model is done), then all set in the size the longest needs. */
    private class ListSet(val members: List<Block>) {
        val left = members.map { it.box.left }.sorted()[members.size / 2]
    }
    private val lists = ArrayList<ListSet>()
    private val listOf = HashMap<Block, ListSet>()

    /** [ground]: the background's colour (its average where it is not plain). */
    class LineSample(val fill: Fill, val fg: Int, val bold: Boolean, val weight: Int = 400, val family: Int = 0, val ground: Int = 0)
    /**
     * Looks at the screenshot pixels of one text line (plus [pad] around it) and returns what to paint over it.
     * A plain background becomes a flat colour. Anything else (gradient, photo, wallpaper) is rebuilt by
     * stretching the pixels just left and right of the line across it, row by row, so no box shows.
     * With [text] given it also estimates the text colour and whether the text is bold.
     */
    private fun sampleLine(src: Bitmap, vb: Rect, pad: Int, text: String?): LineSample {
        val kx = src.width / width.toFloat(); val ky = src.height / height.toFloat()
        // Sample (and, on non-plain backgrounds, repaint) half a line height beyond both ends: OCR boxes often stop a glyph short.
        val hp = pad + max((4 * d).toInt(), vb.height() / 2)
        val l = ((vb.left - hp) * kx).toInt().coerceIn(0, src.width - 2); val t = ((vb.top - pad) * ky).toInt().coerceIn(0, src.height - 2)
        val r = ((vb.right + hp) * kx).toInt().coerceIn(l + 1, src.width); val b = ((vb.bottom + pad) * ky).toInt().coerceIn(t + 1, src.height)
        val bw = r - l; val bh = b - t
        val px = IntArray(bw * bh)
        src.getPixels(px, 0, bw, l, t, bw, bh)
        fun q(c: Int) = (c shr 12 and 0xF00) or (c shr 8 and 0xF0) or (c shr 4 and 0xF)
        val hist = IntArray(4096)
        var i = 0; var n = 0
        while (i < px.size) { hist[q(px[i])]++; n++; i += 2 }
        var top = 0
        for (k in 1 until 4096) if (hist[k] > hist[top]) top = k
        // Neighbouring bins count too, so soft noise or a faint gradient still reads as plain.
        var share = 0
        for (dr in -1..1) for (dg in -1..1) for (db in -1..1) {
            val rr = (top shr 8) + dr; val gg = (top shr 4 and 15) + dg; val bb = (top and 15) + db
            if (rr in 0..15 && gg in 0..15 && bb in 0..15) share += hist[(rr shl 8) or (gg shl 4) or bb]
        }
        // A dominant colour can still be part of a gradient. The surrounding pixels must agree as well.
        val flat = share * 100 >= n * 58 && Look.evenBackground(px, bw, bh)
        var br: Int; var bgc: Int; var bb: Int
        val fill: Fill
        val out = Rect(vb).apply { inset(-pad - (4 * d).toInt(), -pad) }
        if (flat) {
            var ar = 0L; var ag = 0L; var ab = 0L; var an = 0
            i = 0
            while (i < px.size) { val c = px[i]; if (q(c) == top) { ar += Color.red(c); ag += Color.green(c); ab += Color.blue(c); an++ }; i += 2 }
            br = (ar / an).toInt(); bgc = (ag / an).toInt(); bb = (ab / an).toInt()
            // OCR boxes sometimes stop short of the first or last glyphs. On a plain background, keep erasing
            // sideways while the line still has ink, and stop at the first clear gap wider than a word space.
            val reach = (bh * 5).coerceAtMost(src.width)
            val gapMax = (bh * 0.75f).toInt()
            fun extend(dir: Int): Int {
                val x0 = if (dir > 0) r else (l - reach).coerceAtLeast(0)
                val wdt = if (dir > 0) min(reach, src.width - r) else l - x0
                if (wdt <= 1) return 0
                val strip = IntArray(wdt * bh)
                src.getPixels(strip, 0, wdt, x0, t, wdt, bh)
                var last = 0; var k = 1
                val near = (bh * 0.3f).toInt()
                while (k <= wdt) {
                    if (last == 0 && k > near) return 0   // nothing touching the box edge: the box was complete
                    val col = if (dir > 0) k - 1 else wdt - k
                    var ink = false; var yy = bh / 5
                    while (yy < bh - bh / 5) {
                        val c = strip[yy * wdt + col]
                        if (abs(Color.red(c) - br) + abs(Color.green(c) - bgc) + abs(Color.blue(c) - bb) > 70) { ink = true; break }
                        yy += 2
                    }
                    if (ink) last = k else if (k - last > gapMax) break
                    k++
                }
                // Ink that runs on for more than a few characters is a picture or a rule, not stray glyphs: leave it.
                return if (last >= wdt - 1 || last > bh * 3) 0 else last
            }
            // The whole sampled strip is plain apart from the text, so all of it can be repainted.
            out.left = vb.left - hp; out.right = vb.right + hp
            val er = extend(1); val el = extend(-1)
            if (er > 0) out.right += (er / kx).toInt() + pad
            if (el > 0) out.left -= (el / kx).toInt() + pad
            fill = Fill(out, Color.rgb(br, bgc, bb), null)
        } else {
            // Rebuild the background as a coarse grid: each cell takes the median colour of its pixels, which is
            // background as long as text covers less than half of the cell; a second median down each column
            // removes rows that run along a glyph's horizontal stroke. Drawn stretched and filtered, the grid
            // follows gradients and pictures without showing a box.
            val cols = (bw / max(bh, 1) * 2).coerceIn(4, 14); val rows = max(2, bh / 3)
            val grid = Array(3) { IntArray(rows * cols) }
            val sm = IntArray(64); val sg2 = IntArray(64); val sb2 = IntArray(64)
            // The ground just above and just below the line (the pad around the recognizer's box holds no letters), per
            // column of cells: inside the line a pixel counts as ground only when it is close to what these say there.
            // A plain median let bold or dense letters (more than half of a cell's pixels) pass for ground, and the
            // erased original came back as light smears between the translated words.
            val edge = max(2, (pad * ky).toInt()).coerceAtMost(max(1, bh / 3))
            val above = IntArray(cols); val under = IntArray(cols)
            fun med(cx: Int, ya: Int, yb: Int): Int {
                val x0 = cx * bw / cols; val x1 = max(x0 + 1, (cx + 1) * bw / cols)
                val step = max(1, (x1 - x0) / 20)
                var c = 0
                for (yy in ya until yb) { var xx = x0; while (xx < x1 && c < 64) { val v = px[yy * bw + xx]; sm[c] = Color.red(v); sg2[c] = Color.green(v); sb2[c] = Color.blue(v); c++; xx += step } }
                if (c == 0) return 0
                java.util.Arrays.sort(sm, 0, c); java.util.Arrays.sort(sg2, 0, c); java.util.Arrays.sort(sb2, 0, c)
                return Color.rgb(sm[c / 2], sg2[c / 2], sb2[c / 2])
            }
            for (cx in 0 until cols) { above[cx] = med(cx, 0, edge); under[cx] = med(cx, bh - edge, bh) }
            for (ry in 0 until rows) {
                val y0 = (ry * bh / rows).coerceAtMost(bh - 1); val y1 = min(bh, y0 + 3)
                val f = ((y0 + y1) / 2f / bh).coerceIn(0f, 1f)
                for (cx in 0 until cols) {
                    val a0 = above[cx]; val u0 = under[cx]
                    val er = (Color.red(a0) + (Color.red(u0) - Color.red(a0)) * f).toInt()
                    val eg = (Color.green(a0) + (Color.green(u0) - Color.green(a0)) * f).toInt()
                    val eb = (Color.blue(a0) + (Color.blue(u0) - Color.blue(a0)) * f).toInt()
                    val tol = 36 + abs(Color.red(a0) - Color.red(u0)) + abs(Color.green(a0) - Color.green(u0)) + abs(Color.blue(a0) - Color.blue(u0))
                    val x0 = cx * bw / cols; val x1 = max(x0 + 1, (cx + 1) * bw / cols)
                    val step = max(1, (x1 - x0) / 20)
                    var c = 0; var seen = 0
                    for (yy in y0 until y1) { var xx = x0; while (xx < x1 && seen < 64) {
                        val v = px[yy * bw + xx]; seen++
                        if (abs(Color.red(v) - er) + abs(Color.green(v) - eg) + abs(Color.blue(v) - eb) <= tol) { sm[c] = Color.red(v); sg2[c] = Color.green(v); sb2[c] = Color.blue(v); c++ }
                        xx += step } }
                    if (c * 4 < seen || c == 0) { grid[0][ry * cols + cx] = er; grid[1][ry * cols + cx] = eg; grid[2][ry * cols + cx] = eb; continue }
                    java.util.Arrays.sort(sm, 0, c); java.util.Arrays.sort(sg2, 0, c); java.util.Arrays.sort(sb2, 0, c)
                    grid[0][ry * cols + cx] = sm[c / 2]; grid[1][ry * cols + cx] = sg2[c / 2]; grid[2][ry * cols + cx] = sb2[c / 2]
                }
            }
            val bm = IntArray(rows * cols)
            var sr = 0L; var sg = 0L; var sb = 0L
            val win = IntArray(7)
            for (ry in 0 until rows) for (cx in 0 until cols) {
                var rgb = 0
                for (ch in 0..2) {
                    var c = 0
                    for (k in -3..3) { val i2 = ry + k; if (i2 in 0 until rows) win[c++] = grid[ch][i2 * cols + cx] }
                    java.util.Arrays.sort(win, 0, c)
                    rgb = (rgb shl 8) or win[c / 2]
                }
                bm[ry * cols + cx] = rgb or (0xFF shl 24)
                sr += rgb shr 16 and 255; sg += rgb shr 8 and 255; sb += rgb and 255
            }
            br = (sr / (rows * cols)).toInt(); bgc = (sg / (rows * cols)).toInt(); bb = (sb / (rows * cols)).toInt()
            out.left = vb.left - hp; out.right = vb.right + hp
            fill = Fill(out, 0, Bitmap.createBitmap(bm, cols, rows, Bitmap.Config.ARGB_8888))
        }
        if (text == null) return LineSample(fill, 0, false, ground = Color.rgb(br, bgc, bb))

        // Restrict colour and stroke measurement to the glyph box. Estimate the ground locally over gradients.
        val x0 = ((vb.left * kx).toInt() - l).coerceIn(0, bw - 1)
        val x1 = ((vb.right * kx).toInt() - l).coerceIn(x0 + 1, bw)
        val y0 = ((vb.top * ky).toInt() - t).coerceIn(0, bh - 1)
        val y1 = ((vb.bottom * ky).toInt() - t).coerceIn(y0 + 1, bh)
        val lw = x1 - x0
        val lh2 = y1 - y0
        val groundColor = Color.rgb(br, bgc, bb)
        val glyphPixels = IntArray(lw * lh2)
        val dist = IntArray(glyphPixels.size)
        var k = 0
        for (yy in y0 until y1) for (xx in x0 until x1) {
            val c = px[yy * bw + xx]
            val local = fill.bmp?.let { it.getPixel(xx * it.width / bw, yy * it.height / bh) } ?: groundColor
            glyphPixels[k] = c
            dist[k++] = Look.colorDistance(c, local)
        }
        val fg = Look.inkColor(glyphPixels, dist) ?: if (br + bgc + bb > 380) Color.BLACK else Color.WHITE
        val maxD = Look.colorDistance(fg, groundColor)
        val ink = dist.count { it >= maxOf(18, maxD / 2) }
        var bold = false
        var weight = 400
        val th = lh2
        if (maxD >= 24 && th in 8..220 && text.length >= 3) {
            val mask = BooleanArray(lw * lh2)
            for (j in mask.indices) mask[j] = dist[j] >= maxOf(18, maxD / 2)
            val seen = Look.measure(mask, lw, lh2)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            fun drawn(tf: Typeface): Look.Ink? {
                tp.typeface = tf; tp.textSize = 100f
                val r0 = Rect(); tp.getTextBounds(text, 0, text.length, r0)
                tp.textSize = 100f * th / max(r0.height(), 1)
                tp.getTextBounds(text, 0, text.length, r0)
                val w0 = r0.width().coerceIn(1, 4000); val h0 = r0.height().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w0, h0, Bitmap.Config.ALPHA_8)
                Canvas(bmp).drawText(text, -r0.left.toFloat(), -r0.top.toFloat(), tp)
                val buf = java.nio.ByteBuffer.allocate(bmp.rowBytes * h0)
                bmp.copyPixelsToBuffer(buf)
                val m = BooleanArray(w0 * h0)
                for (yy in 0 until h0) for (xx in 0 until w0) m[yy * w0 + xx] = buf.get(yy * bmp.rowBytes + xx).toInt() and 0xFF > 127
                bmp.recycle()
                return Look.measure(m, w0, h0)
            }
            if (seen != null) {
                val sans = Look.WEIGHTS.map { drawn(Typeface.create(Typeface.DEFAULT, it, false)) }
                weight = Look.weight(seen.rel, sans.map { it?.rel ?: 0f })
                bold = weight >= 600

            }
        }
        return LineSample(fill, fg, bold, weight, ground = groundColor)
    }

    // ---------- drawing ----------

    override fun onDraw(c: Canvas) {
        if (mode == Mode.FROZEN) drawFrozen(c)
        else {
            drawPatches(c)
            drawEdgeGlow(c)   // on top, so translated text near the edge does not cut notches into it
            afterDraw?.let { cb -> afterDraw = null; runCatching { viewTreeObserver.registerFrameCommitCallback { cb() } }.onFailure { cb() } }
        }
    }

    /** Called once, when the next frame drawn in scroll mode is on the screen. */
    var afterDraw: (() -> Unit)? = null
    /** The translation alone, for the window that stands in while this view changes windows. */
    fun drawTranslation(c: Canvas) { drawPatches(c) }

    /** Translations follow the page while it moves (set in scroll mode, see ScrollFollow). */
    var follow: ScrollFollow.Out? = null
    /** The current set, as the follower knows it (compared by identity), and where its original text is. */
    val patchSet: Any get() = patches
    fun patchBoxes(): List<Rect> = patches.map { p ->
        val r = Rect(); for (f in p.fills) r.union(f.box)
        if (r.isEmpty) r.set(p.x.toInt(), p.top, (p.x + p.layout.width).toInt(), p.bottom)
        r
    }

    private fun drawPatches(c: Canvas) {
        val fo = follow
        if (fo != null && fo.live && fo.list === patches && fo.groups.size <= patches.size && fo.moved()) { drawFollowed(c, fo); return }
        // All erasing first, then all text, so one block's fill never covers its neighbour's words.
        for (p in patches) {
            c.save(); p.clip?.let { c.clipRect(it) }; p.room?.let { c.clipRect(it) }
            for (f in p.fills) {
                if (f.bmp != null) c.drawBitmap(f.bmp, null, f.box, bmpPaint)
                else { fill.color = f.color; c.drawRect(f.box, fill) }
            }
            c.restore()
        }
        for (p in patches) { c.save(); p.clip?.let { c.clipRect(it) }; p.room?.let { c.clipRect(it) }; c.translate(p.x, p.y); p.layout.draw(c); c.restore() }
    }

    /**
     * The page is moving and the follower knows by how much: each translation is drawn moved with its scrolling
     * part and cut to it. Unmatched patches keep their last position until a fresh frame replaces them.
     */
    private fun drawFollowed(c: Canvas, fo: ScrollFollow.Out) {
        for (pass in 0..1) {
            for (i in 0 until patches.size) {
                // A translation that came after anchoring keeps its position until the next reading.
                val g = fo.groups.getOrElse(i) { -1 }
                val p = patches[i]
                c.save()
                // The page between the app's bars stays put; the block's own room moves with the content.
                p.clip?.let { c.clipRect(it) }
                if (g >= 0 && fo.dy[g] != 0f) {
                    c.clipRect(fo.clip[g])
                    c.translate(0f, -fo.dy[g])
                }
                p.room?.let { c.clipRect(it) }
                if (pass == 0) {
                    for (j in 0 until p.fills.size) {
                        val f = p.fills[j]
                        if (f.bmp != null) c.drawBitmap(f.bmp, null, f.box, bmpPaint)
                        else { fill.color = f.color; c.drawRect(f.box, fill) }
                    }
                } else { c.translate(p.x, p.y); p.layout.draw(c) }
                c.restore()
            }
        }
    }

    private val tmp = RectF()
    // ---- edge light in scroll-and-translate mode ----
    // A soft band of colour follows the screen edges and moves when the page is translated again.
    private var edgeIn = 0f
    private var edgeAnim: ValueAnimator? = null
    private var glowPhase = 0f
    private var glowAnim: ValueAnimator? = null
    private var glowBmp: Bitmap? = null
    private var glowBmpPhase = Float.NaN
    private var glowIdx = IntArray(0)        // cells of the small bitmap that lie in the band
    private var glowA = IntArray(0)          // their alpha
    private var glowT = FloatArray(0)        // their place round the screen, 0..1
    private var glowPx = IntArray(0)
    private val glowCols = intArrayOf(0xEA4335, 0xFBBC05, 0x34A853, 0x22B8CF, 0x4285F4, 0x9B72F2, 0xEA4335)

    /** The colours move on by a third of the way round and settle (called when the page is translated again). */
    fun kickGlow() {
        if (mode != Mode.PASS) return
        glowAnim?.cancel()
        glowAnim = ValueAnimator.ofFloat(glowPhase, glowPhase + 0.33f).apply {
            duration = 1400; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { glowPhase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun drawEdgeGlow(c: Canvas) {
        if (edgeAnim == null) edgeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450; addUpdateListener { edgeIn = it.animatedValue as Float; invalidate() }; start()
        }
        val w = width; val h = height
        if (w == 0 || h == 0) return
        val k = 8                                           // the band is soft: an eighth of the resolution is plenty
        val bw = (w + k - 1) / k; val bh = (h + k - 1) / k
        var bmp = glowBmp
        if (bmp == null || bmp.width != bw || bmp.height != bh) {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); glowBmp = bmp; glowBmpPhase = Float.NaN
            val rad = (if (android.os.Build.VERSION.SDK_INT >= 31)
                (rootWindowInsets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f).let { if (it > 0) it else 34 * d } else 34 * d)
            val idx = ArrayList<Int>(); val al = ArrayList<Int>(); val tt = ArrayList<Float>()
            val reach = TrLook.GLOW_REACH_DP * d; val fall = TrLook.GLOW_FALL_DP * d
            for (y in 0 until bh) for (x in 0 until bw) {
                val px = (x + 0.5f) * k; val py = (y + 0.5f) * k
                // distance to the screen's (rounded) edge, inside the screen
                val qx = abs(px - w / 2f) - (w / 2f - rad); val qy = abs(py - h / 2f) - (h / 2f - rad)
                val dist = -(hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - rad)
                if (dist > reach) continue
                val a = TrLook.GLOW_EDGE * kotlin.math.exp(-max(dist, 0f) / fall) * (1f - (dist / reach).coerceIn(0f, 1f)).let { kotlin.math.sqrt(it) }
                idx.add(y * bw + x); al.add((a * 255).toInt().coerceIn(0, 255))
                tt.add((kotlin.math.atan2(py - h / 2f, px - w / 2f) / (2 * Math.PI).toFloat() + 1f) % 1f)
            }
            glowIdx = idx.toIntArray(); glowA = al.toIntArray(); glowT = tt.toFloatArray(); glowPx = IntArray(bw * bh)
        }
        if (glowBmpPhase != glowPhase) {
            glowBmpPhase = glowPhase
            val n = glowCols.size - 1
            for (i in glowIdx.indices) {
                val t = ((glowT[i] + glowPhase) % 1f) * n
                val a0 = t.toInt().coerceIn(0, n - 1); val f = t - a0
                val c0 = glowCols[a0]; val c1 = glowCols[a0 + 1]
                val r = ((c0 shr 16 and 255) + ((c1 shr 16 and 255) - (c0 shr 16 and 255)) * f).toInt()
                val g = ((c0 shr 8 and 255) + ((c1 shr 8 and 255) - (c0 shr 8 and 255)) * f).toInt()
                val b = ((c0 and 255) + ((c1 and 255) - (c0 and 255)) * f).toInt()
                glowPx[glowIdx[i]] = (glowA[i] shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(glowPx, 0, bw, 0, 0, bw, bh)
        }
        tmp.set(0f, 0f, (bw * k).toFloat(), (bh * k).toFloat())
        washPaint.alpha = (edgeIn * 255).toInt()
        c.drawBitmap(bmp, null, tmp, washPaint)
    }
    private val m = Matrix()

    private var wash: Bitmap? = null
    private val washPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun blob(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Float) {
        if (alpha <= 0.01f) return
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        fill.shader = android.graphics.RadialGradient(cx, cy, r,
            intArrayOf((a shl 24) or (color and 0xFFFFFF), ((a * 0.55f).toInt() shl 24) or (color and 0xFFFFFF), color and 0xFFFFFF),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null
    }

    private fun drawFrozen(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val idle = 1f - calm

        if (pts.isNotEmpty() && (parent as? Host)?.edgeUndecided != true) strokeWanted++
        // Clock of the opening animation (frame time, so the motion is even).
        if (openAt == -1L) {
            openAt = drawingTime
            onFirstFrame?.let { cb -> onFirstFrame = null; runCatching { viewTreeObserver.registerFrameCommitCallback { cb() } } }
        }
        openMs = if (openAt > 0L) (drawingTime - openAt).toFloat() else 0f
        if (openAt != 0L && openMs < 1100f) postInvalidateOnAnimation()
        val enter = ((openMs - 40f) / 220f).coerceIn(0f, 1f)        // header fade

        // ---- frozen screen and everything that belongs to it, shifted up while a sheet covers the selection ----
        c.save(); c.translate(zox, zoy - panY); c.scale(zoom, zoom)
        if (zoom < 1f) c.drawColor(Color.BLACK)
        shot?.let { tmp.set(0f, 0f, w, h); c.drawBitmap(it, null, tmp, bmpPaint) }
        statusStrip?.let { if (statusH > 0) { tmp.set(0f, 0f, w, statusH.toFloat()); c.drawBitmap(it, null, tmp, bmpPaint) } }
        // Translate on the frozen screen: the translation is part of the picture (it zooms and pans with it).
        if (trOn) drawPatches(c)
        c.restore()
        // "Scroll and translate" pressed: everything that belongs to the overlay leaves in LEAVE_MS.
        val leave = if (leaveT0 == 0L) 0f else ((android.os.SystemClock.uptimeMillis() - leaveT0) / TrLook.LEAVE_MS).coerceIn(0f, 1f)
        val stay = 1f - leave
        val ph = trPhase()
        if (leaveT0 != 0L || (ph > 0f && ph < TrLook.CHANGE_MS)) postInvalidateOnAnimation()

        // Dim: 10 % while idle and there from the first frame, darker once something is selected .
        if (region != null) {
            // A picture is selected: it keeps its brightness, everything round it goes dark (Google's: 0.26-0.43 of
            // the brightness before, the inside untouched).
            val f = regionRect(tmp); regionPath.rewind(); regionPath.addRoundRect(tmp.left, tmp.top - panY, tmp.right, tmp.bottom - panY, REGION_R * d, REGION_R * d, Path.Direction.CW)
            c.save(); c.clipOutPath(regionPath)
            c.drawColor(Color.argb(((0.10f * idle + (0.5f + 0.15f * f) * calm) * stay * 255).toInt(), 0, 0, 0))
            c.restore()
        } else
        c.drawColor(Color.argb(((0.10f * idle + 0.5f * calm) * stay * 255).toInt(), 0, 0, 0))

        // Colour wash: blue top-left, red top-right, amber on the right edge, green from the bottom. It fades in
        // fast and then slowly to full strength, drifts slowly, and fades out when something is selected.
        val au = idle * stay * 0.66f * key(openMs, washT, washA)
        if (au > 0.01f) {
            // The wash is painted once into a small bitmap; each frame only stretches it, slightly shifted, over
            // the screen.
            val bmp = wash ?: Bitmap.createBitmap(max(1, width / 12), max(1, height / 12), Bitmap.Config.ARGB_8888).also { b ->
                val cc = Canvas(b); val bw = b.width.toFloat(); val bh = b.height.toFloat()
                fun spot(cx: Float, cy: Float, r: Float, color: Int, a: Float) {
                    val al = (a * 255).toInt()
                    fill.shader = android.graphics.RadialGradient(cx, cy, r,
                        intArrayOf((al shl 24) or (color and 0xFFFFFF), ((al * 0.55f).toInt() shl 24) or (color and 0xFFFFFF), color and 0xFFFFFF),
                        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                    cc.drawRect(0f, 0f, bw, bh, fill)
                    fill.shader = null
                }
                // Position the colour stops relative to the cached bitmap.
                spot(bw * 0.156f, bh * -0.139f, bw * 0.798f, 0xFF3072FF.toInt(), 1.00f)
                spot(bw * 0.774f, bh * -0.117f, bw * 0.765f, 0xFFFC2917.toInt(), 1.00f)
                spot(bw * 1.300f, bh * 0.704f, bw * 1.071f, 0xFF8F8308.toInt(), 0.65f)
                spot(bw * 0.499f, bh * 1.221f, bw * 1.348f, 0xFF06FF4E.toInt(), 1.00f)
                spot(bw * -0.300f, bh * 0.832f, bw * 1.442f, 0xFF0079E1.toInt(), 0.46f)
                wash = b
            }
            val dx = kotlin.math.sin(drift) * w * 0.035f; val dy = kotlin.math.cos(drift) * h * 0.018f
            tmp.set(-w * 0.05f + dx, -h * 0.03f + dy, w * 1.05f + dx, h * 1.03f + dy)
            washPaint.alpha = (au.coerceIn(0f, 1f) * 255).toInt()
            c.drawBitmap(bmp, null, tmp, washPaint)
        }

        c.save(); c.translate(0f, -panY)
        c.save(); c.translate(zox, zoy); c.scale(zoom, zoom)
        val k = 1f / zoom                      // keeps line widths the same on screen at any zoom
        drawHighlights(c)
        c.restore()
        drawRegion(c)
        drawStroke(c)
        drawHandles(c)
        if (items.isNotEmpty()) {
            fill.color = 0xFF1E1F22.toInt(); fill.setShadowLayer(8 * d, 0f, 2 * d, 0x66000000)
            c.drawRoundRect(itemsBar, itemsBar.height() / 2, itemsBar.height() / 2, fill)
            fill.clearShadowLayer()
            text.color = 0xFFE3E3E3.toInt(); text.textSize = itemText; text.typeface = Typeface.create("google-sans", Typeface.NORMAL)
            for (it in items) c.drawText(it.label, it.rect.left + itemPad, it.rect.centerY() - (text.ascent() + text.descent()) / 2, text)
        }
        c.restore()

        // ---- header: close button and wordmark ----
        val ha = (enter * stay * 255).toInt()
        fill.color = Color.argb((0xB0 * enter * stay).toInt(), 0x14, 0x18, 0x22)
        c.drawOval(closeBtn, fill)
        line.shader = null; line.color = Color.argb(ha, 255, 255, 255); line.strokeWidth = 2 * d
        val q = 6 * d
        c.drawLine(closeBtn.centerX() - q, closeBtn.centerY() - q, closeBtn.centerX() + q, closeBtn.centerY() + q, line)
        c.drawLine(closeBtn.centerX() + q, closeBtn.centerY() - q, closeBtn.centerX() - q, closeBtn.centerY() + q, line)
        text.color = Color.argb(ha, 255, 255, 255); text.textSize = 26 * d; text.typeface = Typeface.create("google-sans", Typeface.NORMAL)
        val gw = text.measureText("Google")
        c.drawText("Google", w / 2 - gw / 2, closeBtn.centerY() - (text.ascent() + text.descent()) / 2, text)

        // ---- bottom: translation card, or the capsule (search pill, song search, translate) while idle ----
        val cd = card
        if (cd != null) drawCard(c, cd)
        else if (idle > 0.01f && sheetTop == null) {
            // The capsule grows out of the navigation handle: first frame a flat pill on the handle, then the
            // whole capsule on two springs (see the measurements at the top).
            val ts = openMs / 1000f
            val flat = openMs < 4f
            val sc = if (openMs > 900f) 1f else 1f + spring(ts, -0.398f, 1.781f, 15.5f, 0.80f)
            val dy = if (openMs > 900f) 0f else spring(ts, 46.9f, -424.5f, 21.5f, 0.72f) * d
            if (flat) {
                fill.color = Color.BLACK
                val fw = capsule.width() * 0.245f; val fb = h - 1f
                c.drawRoundRect(w / 2 - fw, fb - 9 * d, w / 2 + fw, fb, 4.5f * d, 4.5f * d, fill)
            }
            c.save()
            // "Scroll and translate" pressed: the bar drops out of the screen, gathering speed.
            c.translate(0f, dy + capOff + leave * leave * (h - capsule.top - capOff + 12 * d))
            c.scale(sc, sc, w / 2, capsule.bottom)
            val layer = if (idle < 0.995f) c.saveLayerAlpha(capsule.left - 4 * d, capsule.top - 4 * d, capsule.right + 4 * d, capsule.bottom + 4 * d, (idle * 255).toInt()) else c.save()
            if (flat) c.clipRect(0f, 0f, 0f, 0f)
            // Change-over between the search bar and the "Scroll and translate" bar (timing: TrLook).
            val out = (ph / TrLook.FADE_OUT_MS).coerceIn(0f, 1f)                 // what is in the search field leaves
            val musicOut = (ph / TrLook.MUSIC_OUT_MS).coerceIn(0f, 1f)           // the song button leaves
            val widen = ((ph - TrLook.WIDEN_FROM_MS) / TrLook.WIDEN_MS).coerceIn(0f, 1f)   // the field grows over its place
            val labelIn = ((ph - TrLook.LABEL_FROM_MS) / TrLook.LABEL_MS).coerceIn(0f, 1f)
            fill.color = mix(tl.idleCapsule, tl.capsule, widen)
            c.drawRoundRect(capsule, capsule.height() / 2, capsule.height() / 2, fill)
            tmp.set(barRect.left, barRect.top, barRect.right + (musicBtn.right - barRect.right) * widen, barRect.bottom)
            fill.color = mix(tl.idleField, tl.pill, widen)
            c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill)
            val fieldRight = tmp.right
            fun icon(path: Path, r: RectF, size: Float, color: Int, a: Float = 1f) {
                c.save(); c.translate(r.centerX() - size / 2, r.centerY() - size / 2); c.scale(size / 24f, size / 24f)
                fill.color = color; fill.alpha = (Color.alpha(color) * a).toInt(); c.drawPath(path, fill); fill.alpha = 255; c.restore()
            }
            if (musicOut < 1f) {
                fill.color = tl.idleField; fill.alpha = ((1f - musicOut) * 255).toInt(); c.drawOval(musicBtn, fill); fill.alpha = 255
                icon(noteIcon, musicBtn, 23 * d, tl.idleIcon, 1f - musicOut)
            }
            if (depth == 0) {
                // The translate button is lit from the moment it is pressed.
                fill.color = if (trOn) tl.on else tl.idleField
                c.drawOval(trBtn, fill)
                icon(translateIcon, trBtn, 23 * d, if (trOn) tl.onInk else tl.idleIcon)
            }
            if (out < 1f) {
                val a = 1f - out
                val gs = 26 * d
                c.save(); c.translate(barRect.left + 13 * d, barRect.centerY() - gs / 2); c.scale(gs / 48f, gs / 48f)
                for ((col, p) in gPaths) { fill.color = col; fill.alpha = (a * 255).toInt(); c.drawPath(p, fill) }
                fill.alpha = 255
                c.restore()
                tmp.set(barRect.right - 46 * d, barRect.top, barRect.right - 6 * d, barRect.bottom)
                icon(micIcon, tmp, 25 * d, tl.idleIcon, a)
                text.color = tl.idleHint; text.alpha = (Color.alpha(tl.idleHint) * a).toInt()
                text.textSize = 16.5f * d; text.typeface = Typeface.create("google-sans", Typeface.NORMAL)
                val hint = status ?: "Ask about screen"
                val maxW = barRect.width() - 100 * d
                while (text.measureText(hint) > maxW && text.textSize > 12 * d) text.textSize -= d / 2
                c.drawText(TextUtils.ellipsize(hint, text, maxW, TextUtils.TruncateAt.END).toString(),
                    barRect.left + 52 * d, barRect.centerY() - (text.ascent() + text.descent()) / 2, text)
                text.alpha = 255
            }
            if (labelIn > 0f) {
                // Hand-with-arrow and "Scroll and translate", centred as one group in the pale pill.
                val kx = capsule.width() / (356.5f * d)
                val label = "Scroll and translate"
                text.typeface = medium; text.textSize = 16 * d
                text.textSize = 16 * d * (TrLook.LABEL_W * d * kx) / text.measureText(label)
                val tw = text.measureText(label)
                val box = 24 * d * kx                                          // the icon's ink is 21 dp of its 24 dp box
                val group = TrLook.LABEL_ICON * d * kx + TrLook.LABEL_GAP * d * kx + tw
                val gx = (barRect.left + fieldRight) / 2 - group / 2
                tmp.set(gx - d * kx, barRect.centerY() - box / 2, gx - d * kx + box, barRect.centerY() + box / 2)
                icon(swipeIcon, tmp, box, tl.label, labelIn)
                text.color = tl.label; text.alpha = (labelIn * 255).toInt()
                c.drawText(label, gx + group - tw, barRect.centerY() - (text.ascent() + text.descent()) / 2, text)
                text.alpha = 255
            }
            c.restoreToCount(layer)
            c.restore()
        }

        // ---- language chips while the frozen screen is translated ----
        val chipsIn = ((ph - TrLook.LABEL_FROM_MS) / TrLook.LABEL_MS).coerceIn(0f, 1f) * stay * idle
        if (chipsIn > 0.01f && sheetTop == null && cd == null) drawChips(c, chipsIn) else chipCap.setEmpty()

        toast?.let { t ->
            text.textSize = 14 * d; text.typeface = medium
            val tw = text.measureText(t) + 36 * d
            val cy = (card?.rect?.top ?: sheetTop ?: (capsule.top + capOff).coerceAtLeast(200 * d)) - 40 * d
            tmp.set(w / 2 - tw / 2, cy - 20 * d, w / 2 + tw / 2, cy + 20 * d)
            fill.color = 0xFFE3E3E3.toInt()
            c.drawRoundRect(tmp, 20 * d, 20 * d, fill)
            text.color = 0xFF1F1F1F.toInt()
            c.drawText(t, tmp.left + 18 * d, cy - (text.ascent() + text.descent()) / 2, text)
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        fun ch(sh: Int) = ((a ushr sh and 255) + ((b ushr sh and 255) - (a ushr sh and 255)) * t).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Source and target language in one capsule under the header while translation is active. */
    private fun drawChips(c: Canvas, a: Float) {
        text.typeface = medium; text.textSize = 15 * d
        val sName = if (trSrc == "und") "Detect" else Tr.name(trSrc)
        val tName = Tr.name(trTgt)
        val sw = text.measureText(sName); val tw = text.measureText(tName)
        val ch = TrLook.CHIP_H * d; val inset = TrLook.CHIP_INSET * d; val pad = TrLook.CHIP_PAD * d; val gap = TrLook.CHIP_ARROW_GAP * d
        val total = inset * 2 + sw + tw + pad * 4 + gap
        val top = closeBtn.bottom + TrLook.CHIPS_BELOW_CLOSE * d
        chipCap.set(width / 2f - total / 2, top, width / 2f + total / 2, top + ch + inset * 2)
        srcChip.set(chipCap.left + inset, top + inset, chipCap.left + inset + sw + pad * 2, top + inset + ch)
        tgtChip.set(chipCap.right - inset - tw - pad * 2, top + inset, chipCap.right - inset, top + inset + ch)
        val layer = c.saveLayerAlpha(chipCap.left - 14 * d, chipCap.top - 12 * d, chipCap.right + 14 * d, chipCap.bottom + 18 * d, (a * 255).toInt())
        fill.color = tl.capsule; fill.setShadowLayer(8 * d, 0f, 2 * d, 0x40000000)
        c.drawRoundRect(chipCap, chipCap.height() / 2, chipCap.height() / 2, fill)
        fill.clearShadowLayer()
        fill.color = tl.pill
        c.drawRoundRect(srcChip, ch / 2, ch / 2, fill)
        c.drawRoundRect(tgtChip, ch / 2, ch / 2, fill)
        text.color = tl.chipInk
        val base = -(text.ascent() + text.descent()) / 2
        c.drawText(sName, srcChip.centerX() - sw / 2, srcChip.centerY() + base, text)
        c.drawText(tName, tgtChip.centerX() - tw / 2, tgtChip.centerY() + base, text)
        val asz = 18 * d
        c.save(); c.translate((srcChip.right + tgtChip.left) / 2 - asz / 2, srcChip.centerY() - asz / 2); c.scale(asz / 24f, asz / 24f)
        fill.color = tl.chipInk; c.drawPath(arrowIcon, fill); c.restore()
        c.restoreToCount(layer)
    }

    private val REGION_R = 10f
    private val REGION_MS = 260f
    private var regionT0 = 0L
    private val regionPath = Path()
    private val bracketPath = Path()
    private var regionGlow: Bitmap? = null
    private var regionGlowW = 0; private var regionGlowH = 0
    private val regionGlowM get() = 64 * d                 // how far the light reaches round the frame
    private val regionEase = android.view.animation.PathInterpolator(0f, 0f, 0.2f, 1f)

    /** The selected rectangle on the screen ("page" y, before the pan), at its current size in the growing animation. Returns the animation's progress. */
    private fun regionRect(out: RectF): Float {
        val r = region ?: run { out.setEmpty(); return 0f }
        val f = if (regionT0 == 0L) 1f else ((android.os.SystemClock.uptimeMillis() - regionT0) / REGION_MS).coerceIn(0f, 1f)
        val s = 0.55f + 0.45f * regionEase.getInterpolation(f)
        val l = pX(r.left); val t = pY(r.top); val rr = pX(r.right); val b = pY(r.bottom)
        val cx = (l + rr) / 2; val cy = (t + b) / 2
        out.set(cx - (rr - l) / 2 * s, cy - (b - t) / 2 * s, cx + (rr - l) / 2 * s, cy + (b - t) / 2 * s)
        if (f < 1f) postInvalidateOnAnimation()
        return f
    }

    private fun drawRegion(c: Canvas) {
        if (region == null) return
        val box = RectF(); regionRect(box)
        val full = RectF(pX(region!!.left), pY(region!!.top), pX(region!!.right), pY(region!!.bottom))
        // the light: painted once per size into a half-size bitmap, then only stretched
        val gw = (full.width() + 2 * regionGlowM).toInt(); val gh = (full.height() + 2 * regionGlowM).toInt()
        if (gw > 8 && gh > 8) {
            var bmp = regionGlow
            if (bmp == null || kotlin.math.abs(regionGlowW - gw) > 2 || kotlin.math.abs(regionGlowH - gh) > 2) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(max(1, gw / 2), max(1, gh / 2), Bitmap.Config.ARGB_8888)
                val gc = Canvas(bmp); gc.scale(0.5f, 0.5f)
                val gr = RectF(regionGlowM, regionGlowM, regionGlowM + full.width(), regionGlowM + full.height())
                val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
                // right yellow, below green, left blue, towards the top right red (a sweep starts at the right and runs clockwise)
                gp.shader = android.graphics.SweepGradient(gr.centerX(), gr.centerY(),
                    intArrayOf(0xFFFBBC05.toInt(), 0xFF34A853.toInt(), 0xFF2FA4A0.toInt(), 0xFF4285F4.toInt(), 0xFF7B5FD9.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt()),
                    floatArrayOf(0f, 0.25f, 0.37f, 0.5f, 0.68f, 0.84f, 1f))
                val rad = (REGION_R + 2) * d
                // two soft bands that add up to the measured fall-off: a narrow strong one and a wide weak one
                gp.strokeWidth = 16 * d; gp.maskFilter = BlurMaskFilter(11 * d, BlurMaskFilter.Blur.NORMAL); gp.alpha = 120
                gc.drawRoundRect(gr, rad, rad, gp)
                gp.strokeWidth = 44 * d; gp.maskFilter = BlurMaskFilter(24 * d, BlurMaskFilter.Blur.NORMAL); gp.alpha = 80
                gc.drawRoundRect(gr, rad, rad, gp)
                regionGlow = bmp; regionGlowW = gw; regionGlowH = gh
            }
            val k = box.width() / full.width().coerceAtLeast(1f)
            regionPath.rewind(); regionPath.addRoundRect(box, REGION_R * d, REGION_R * d, Path.Direction.CW)
            c.save(); c.clipOutPath(regionPath)
            tmp.set(box.left - regionGlowM * k, box.top - regionGlowM * k, box.right + regionGlowM * k, box.bottom + regionGlowM * k)
            washPaint.alpha = 255
            c.drawBitmap(bmp!!, null, tmp, washPaint)
            c.restore()
        }
        // the brackets
        val o = 4.85f * d                                     // centre of the bracket's line outside the rectangle
        val l = box.left - o; val t = box.top - o; val r = box.right + o; val b = box.bottom + o
        val reach = min(29.7f * d - 2.85f * d, min(r - l, b - t) * 0.42f)
        val rad = min((REGION_R + 4.85f) * d, reach)
        line.shader = null; line.color = Color.WHITE; line.strokeWidth = 5.7f * d
        bracketPath.rewind()
        bracketPath.moveTo(l, t + reach); bracketPath.lineTo(l, t + rad); bracketPath.arcTo(l, t, l + 2 * rad, t + 2 * rad, 180f, 90f, false); bracketPath.lineTo(l + reach, t)
        bracketPath.moveTo(r - reach, t); bracketPath.lineTo(r - rad, t); bracketPath.arcTo(r - 2 * rad, t, r, t + 2 * rad, 270f, 90f, false); bracketPath.lineTo(r, t + reach)
        bracketPath.moveTo(r, b - reach); bracketPath.lineTo(r, b - rad); bracketPath.arcTo(r - 2 * rad, b - 2 * rad, r, b, 0f, 90f, false); bracketPath.lineTo(r - reach, b)
        bracketPath.moveTo(l + reach, b); bracketPath.lineTo(l + rad, b); bracketPath.arcTo(l, b - 2 * rad, l + 2 * rad, b, 90f, 90f, false); bracketPath.lineTo(l, b - reach)
        c.drawPath(bracketPath, line)
    }

    private val sPath = Path()
    private val look = SelLogic.strokeLook(resources.displayMetrics.density)
    private var glowShader: Shader? = null
    private var strokeFrames = 0      // frames in which the stroke's core was painted
    private var glowFrames = 0        // ... and its glow
    private var strokeWanted = 0      // frames of the frozen screen drawn while a stroke existed

    /**
     * The stroke: a soft coloured glow around the whole line and a white core, from the first point on.
     * It is painted in screen pixels (the points are mapped through the zoom, the canvas is not scaled), because
     * a scaled canvas also scales the blur: zoomed in, the glow was spread so thin that only the white line was
     * left. The newest part of the core is brighter than the rest; the glow is the same everywhere.
     */
    private fun drawStroke(c: Canvas) {
        if (pts.isEmpty()) return
        // A touch that began at the screen's edge and has not shown yet whether it is the back gesture: no mark is drawn.
        if ((parent as? Host)?.edgeUndecided == true) return
        sPath.rewind()
        val x0 = pX(pts[0].x); val y0 = pY(pts[0].y)
        sPath.moveTo(x0, y0)
        if (pts.size == 1) sPath.lineTo(x0 + 0.5f, y0)          // a finger that has not moved yet: a glowing dot
        for (q in 1 until pts.size) {
            val ax = pX(pts[q - 1].x); val ay = pY(pts[q - 1].y); val bx = pX(pts[q].x); val by = pY(pts[q].y)
            sPath.quadTo(ax, ay, (ax + bx) / 2, (ay + by) / 2)
        }
        if (pts.size > 1) sPath.lineTo(pX(pts.last().x), pY(pts.last().y))
        val shader = glowShader ?: StrokePainter.colours(width, height).also { glowShader = it }
        // the newest part of the core at full strength
        val now = android.os.SystemClock.uptimeMillis()
        var from = pts.size - 1
        while (from > 0 && now - ptT[from - 1] < look.recentMs) from--
        var recent: Path? = null
        if (drawing && from < pts.size - 1) {
            tail.rewind(); tail.moveTo(pX(pts[from].x), pY(pts[from].y))
            for (q in from + 1 until pts.size) { val ax = pX(pts[q - 1].x); val ay = pY(pts[q - 1].y); val bx = pX(pts[q].x); val by = pY(pts[q].y); tail.quadTo(ax, ay, (ax + bx) / 2, (ay + by) / 2) }
            recent = tail
        } else if (drawing && pts.size == 1) recent = sPath
        if (StrokePainter.paint(c, sPath, recent, look, shader)) glowFrames++
        strokeFrames++
        if (drawing) postInvalidateOnAnimation()      // the newest part keeps ageing while the finger rests
    }

    /** Called when a stroke ends: says in the log whether every frame of it was painted with its glow. */
    private fun strokeDone() {
        if (strokeMoved && strokeFrames == 0 && strokeWanted > 0) { android.util.Log.e(CircleOverlay.TAG, "STROKE NEVER PAINTED: $strokeWanted frames wanted"); Dbg.log("STROKE NEVER PAINTED: $strokeWanted frames wanted") }
        if (strokeFrames > 0) {
            if (glowFrames < strokeFrames || strokeFrames < strokeWanted) { android.util.Log.e(CircleOverlay.TAG, "STROKE WITHOUT GLOW: $glowFrames of $strokeFrames frames"); Dbg.log("STROKE WITHOUT GLOW: $glowFrames of $strokeFrames frames") }
            else Dbg.log("stroke: $strokeFrames frames, glow on all of them, zoom ${"%.2f".format(zoom)}")
        }
        strokeFrames = 0; glowFrames = 0; strokeWanted = 0
    }

    private val hr get() = 10 * d      // radius of a selection handle

    /** Highlight of the selected words, drawn in content space: one band per line, as tall as the line. */
    private fun drawHighlights(c: Canvas) {
        if (selA < 0 || selB >= ws.size) return
        fill.color = 0x664285F4
        val k = 1f / zoom
        var i = selA
        while (i <= selB) {
            val first = ws[i]
            var j = i
            while (j + 1 <= selB && ws[j + 1].line == first.line) j++
            val left = (i..j).minOf { ws[it].l }
            val right = (i..j).maxOf { ws[it].r }
            c.drawRoundRect(left - 2 * d * k, first.lt.toFloat(), right + 2 * d * k, first.lb.toFloat(), 4 * d * k, 4 * d * k, fill)
            i = j + 1
        }
    }

    /** The two handles: teardrops whose tips touch the bottom corners of the selection; same size at any zoom. */
    private fun drawHandles(c: Canvas) {
        if (selA < 0 || selB >= ws.size) return
        fill.color = 0xFF4285F4.toInt()
        val a = ws[selA]; val b = ws[selB]
        val ax = pX(a.startX.toFloat()); val ay = pY(a.lb.toFloat())
        c.drawCircle(ax - hr, ay + hr, hr, fill); c.drawRect(ax - hr, ay, ax, ay + hr, fill)
        val bx = pX(b.endX.toFloat()); val by = pY(b.lb.toFloat())
        c.drawCircle(bx + hr, by + hr, hr, fill); c.drawRect(bx, by, bx + hr, by + hr, fill)
    }

    private fun drawCard(c: Canvas, cd: Card) {
        val side = 12 * d
        val lay = cd.layout
        val bodyH = min(lay?.height?.toFloat() ?: 24 * d, height * 0.4f)
        val hgt = 52 * d + bodyH + 60 * d
        cd.rect.set(side, height - 28 * d - hgt, width - side, height - 28 * d)
        c.drawRoundRect(cd.rect, 28 * d, 28 * d, shadow)
        text.typeface = medium; text.textSize = 14 * d; text.color = accent
        c.drawText("${Tr.name(cd.src)}  →  ${Tr.name(cd.tgt)}  ⇄", cd.rect.left + 22 * d, cd.rect.top + 34 * d, text)
        c.save()
        c.clipRect(cd.rect.left, cd.rect.top + 52 * d, cd.rect.right, cd.rect.top + 52 * d + bodyH)
        c.translate(cd.rect.left + 22 * d, cd.rect.top + 52 * d)
        if (lay != null) lay.draw(c) else { text.color = onSurfaceDim; text.textSize = 16 * d; c.drawText("Translating…", 0f, 18 * d, text) }
        c.restore()
        text.typeface = medium; text.textSize = 14 * d; text.color = accent
        c.drawText("Copy translation", cd.rect.left + 22 * d, cd.rect.bottom - 24 * d, text)
    }

    // ---------- selection logic ----------
    // The geometry and the text rules are in SelLogic (unit tested); this part only applies them.

    /** A selection as it was when a search was made, so that going back to that search shows it again. */
    class Snap(val a: Int, val b: Int, val region: RectF?, val first: Int, val last: Int)
    fun snapshot() = Snap(selA, selB, region?.let { RectF(it) }, firstGlyph, lastGlyph)
    fun restore(sn: Snap) {
        if (sn.b >= ws.size) return
        selA = sn.a; selB = sn.b; region = sn.region?.let { RectF(it) }; card = null; reread = null
        firstGlyph = sn.first; lastGlyph = sn.last; trimSelection()
        layoutItems(); setCalm(true); updatePan(); invalidate()
    }
    val hasCard: Boolean get() = card != null

    /** Where the selected words (or the picture region) are on the screen right now. */
    fun selectionOnScreen(): Rect? {
        val r = RectF()
        if (selA >= 0 && selB < ws.size) {
            val f = ws[selA]; r.set(pX(f.l.toFloat()), pY(f.lt.toFloat()), pX(f.r.toFloat()), pY(f.lb.toFloat()))
            for (i in selA..selB) { val w = ws[i]; r.union(pX(w.l.toFloat()), pY(w.lt.toFloat()), pX(w.r.toFloat()), pY(w.lb.toFloat())) }
        } else region?.let { r.set(pX(it.left), pY(it.top), pX(it.right), pY(it.bottom)) } ?: return null
        r.offset(0f, -panY)
        return Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
    }

    private fun selectedText(): String = reread?.takeIf { rereadA == selA && rereadB == selB } ?: SelLogic.text(ws, selA, selB)

    // the second, enlarged reading of a selection of very small text (see SelLogic.reread)
    private var reread: String? = null
    private var rereadA = -1
    private var rereadB = -1

    /** Searches for the selection; if its text is tiny, reads that part again enlarged and searches for the better reading. */
    private fun searchSelection() {
        reread = null
        val a = selA; val b = selB
        if (a < 0 || b >= ws.size) return
        svc.searchText(this, SelLogic.query(ws, a, b))
        if (firstGlyph > 0 || lastGlyph < (ocr?.words?.getOrNull(b)?.characters?.lastIndex ?: 0)) return
        val bmp = soft ?: return
        val ky = bmp.height / height.toFloat(); val kx = bmp.width / width.toFloat()
        val sel = ws.subList(a, b + 1)
        val hs = sel.map { it.h * ky }.sorted()
        if (hs[hs.size / 2] >= SelLogic.TINY_LINE || sel.size > 60 || sel.any { w -> w.text.any { isCjk(it) } }) return
        val h = hs[hs.size / 2]
        val box = Rect(((sel.minOf { it.l } * kx) - h * 2).toInt(), ((sel.minOf { it.lt } * ky) - h).toInt(), ((sel.maxOf { it.r } * kx) + h * 2).toInt(), ((sel.maxOf { it.lb } * ky) + h).toInt())
        val t0 = android.os.SystemClock.uptimeMillis()
        svc.ocr.reread(bmp, box, 1f / kx, 1f / ky, 3f, ocrModel) { words ->
            if (selA != a || selB != b || b >= ws.size) return@reread
            val again = words.map { SelLogic.W(it.text, it.box.left, it.box.top, it.box.right, it.box.bottom, it.lb.top, it.lb.bottom, it.line, 0) }
            val better = SelLogic.reread(ws.subList(a, b + 1), again)
            val first = SelLogic.text(ws, a, b)
            Dbg.log("tiny text (${h.toInt()} px lines): read again enlarged in ${android.os.SystemClock.uptimeMillis() - t0} ms, ${if (better == null) "kept the first reading" else if (better == first) "same reading" else "reading changed"}")
            if (better != null && better != first) { reread = better; rereadA = a; rereadB = b; svc.searchText(this, SelLogic.cleanQuery(better)) }
        }
    }

    /** Selects words a..b. With [image] set, the stroke could also mean "search this picture": keep the region and offer both. */
    private fun setSelection(a: Int, b: Int, image: RectF? = null) {
        if (ws.isEmpty()) return
        resetGlyphs()
        if (card != null) svc.setSheetVisible(this, true)
        selA = min(a, b).coerceIn(0, ws.size - 1); selB = max(a, b).coerceIn(0, ws.size - 1)
        region = image; card = null
        layoutItems()
        setCalm(true)
        invalidate()
        // Like the original: marking text searches for it straight away, in the sheet inside the overlay.
        searchSelection()
        updatePan()
    }

    private fun clearSelection() {
        if (card != null) svc.setSheetVisible(this, true)
        selA = -1; selB = -1; items = emptyList(); card = null; region = null
        setCalm(false)
        svc.closeSheet(this)
        systemGestureExclusionRects = emptyList()
        invalidate()
    }

    /** The little toolbar next to the selection. It lives in screen space: it keeps its size while the picture is zoomed. */
    private fun layoutItems() {
        if (dragHandle != 0 || textDrag || regionGrab >= 0) return
        val defs = ArrayList<Pair<String, () -> Unit>>()
        val img = region
        val sel = RectF()
        if (selA >= 0 && selB < ws.size) {
            defs.add("Copy" to { svc.copy(selectedText()); flash("Copied") })
            if (selA > 0 || selB < ws.size - 1 || firstGlyph > 0 || lastGlyph < (ocr?.words?.getOrNull(selB)?.characters?.lastIndex ?: 0)) defs.add("Select all" to { setSelection(0, ws.size - 1) })
            defs.add("Translate" to { translateSelection() })
            defs.add("Pin to back" to {
                val to = svc.pinToBack(this, selectedText())
                flash(if (to == null) "Rear screen pin is not available" else "Pinned to back")
            })
            // A big loop around text may have meant the picture: offer that too.
            if (img != null) defs.add("Search image" to { val r = RectF(img); selA = -1; selB = -1; items = emptyList(); searchRegion(r, grow = false) })
            val first = ws[selA]
            sel.set(pX(first.l.toFloat()), pY(first.lt.toFloat()), pX(first.r.toFloat()), pY(first.lb.toFloat()))
            for (i in selA..selB) { val w = ws[i]; sel.union(pX(w.l.toFloat()), pY(w.lt.toFloat()), pX(w.r.toFloat()), pY(w.lb.toFloat())) }
            if (img != null) sel.union(pX(img.left), pY(img.top), pX(img.right), pY(img.bottom))
            // the handles hang at the edges of the selection: keep the system's edge gestures off them
            val a = ws[selA]; val b = ws[selB]
            val q = (30 * d).toInt()
            systemGestureExclusionRects = listOf(
                Rect((pX(a.startX.toFloat()) - hr).toInt() - q, (pY(a.lb.toFloat()) - panY).toInt() - q / 2, (pX(a.startX.toFloat())).toInt() + q, (pY(a.lb.toFloat()) - panY + 2 * hr).toInt() + q),
                Rect((pX(b.endX.toFloat())).toInt() - q, (pY(b.lb.toFloat()) - panY).toInt() - q / 2, (pX(b.endX.toFloat()) + hr).toInt() + q, (pY(b.lb.toFloat()) - panY + 2 * hr).toInt() + q))
        } else if (img != null) {
            // A picture region with readable text in it: the text can be selected instead ("Select text").
            val inside = SelLogic.wordsIn(wholeWords, SelLogic.Box(img.left, img.top, img.right, img.bottom))
            if (inside != null) defs.add("Select text" to { setSelection(inside.first, inside.second, RectF(img)) })
            sel.set(pX(img.left), pY(img.top), pX(img.right), pY(img.bottom))
            systemGestureExclusionRects = emptyList()
        }
        if (defs.isEmpty()) { items = emptyList(); return }
        text.typeface = Typeface.create("google-sans", Typeface.NORMAL)
        // five entries do not fit a narrow screen at full size: shrink a little rather than run off the edge
        var size = 15 * d; var pad = 26 * d
        var widths: List<Float>
        while (true) {
            text.textSize = size
            widths = defs.map { text.measureText(it.first) + pad }
            if (widths.sum() + 12 * d <= width - 16 * d || size <= 11.5f * d) break
            size -= 0.5f * d; pad = max(14 * d, pad - 1.5f * d)
        }
        itemText = size
        val total = widths.sum() + 12 * d
        val bh = 46 * d
        var top = sel.top - 12 * d - bh
        if (top - panY < 104 * d) top = sel.bottom + 34 * d
        if (top - panY > height - bh - 60 * d) top = max(104 * d + panY, sel.top + 12 * d)      // selection fills the screen: sit inside it
        val left = (sel.left - 30 * d).coerceIn(8 * d, max(8 * d, width - total - 8 * d))
        itemsBar.set(left, top, left + total, top + bh)
        itemPad = pad / 2
        var x = left + 6 * d
        items = defs.mapIndexed { i, df -> Item(df.first, RectF(x, top, x + widths[i], top + bh), df.second).also { x += widths[i] } }
    }

    private var itemPad = 14f
    private var itemText = 15f

    /** Decides what a finished stroke means (rules and tests: SelLogic.classify). */
    private fun classify() {
        val arr = FloatArray(pts.size * 2) { k -> if (k % 2 == 0) pts[k / 2].x else pts[k / 2].y }
        stroke.reset(); pts.clear(); ptT.clear()
        strokeFrames = 0; glowFrames = 0; strokeWanted = 0
        when (val p = SelLogic.classify(wholeWords, arr, 24 * d / zoom)) {
            is SelLogic.Pick.None -> { if (selA < 0 && region == null) clearSelection(); invalidate() }
            is SelLogic.Pick.Image -> searchRegion(RectF(p.box.l, p.box.t, p.box.r, p.box.b))
            is SelLogic.Pick.Text -> setSelection(p.a, p.b, p.around?.let { bx ->
                RectF(bx.l, bx.t, bx.r, bx.b).apply { inset(-6 * d / zoom, -6 * d / zoom); intersect(0f, 0f, width.toFloat(), height.toFloat()) }
            })
        }
    }

    private fun searchRegion(r: RectF, grow: Boolean = true) {
        if (grow) r.inset(-6 * d / zoom, -6 * d / zoom)
        r.intersect(0f, 0f, width.toFloat(), height.toFloat())
        if (r.width() < 24 * d / zoom || r.height() < 24 * d / zoom) { if (selA < 0 && region == null) clearSelection(); invalidate(); return }
        selA = -1; selB = -1; card = null
        // a new selection grows into place; one whose border is being dragged just follows the finger
        if (grow) regionT0 = android.os.SystemClock.uptimeMillis()
        region = r
        layoutItems()
        setCalm(true)
        invalidate()
        svc.searchImage(this, r)
        updatePan()
    }

    private fun translateSelection() {
        val source = ocr ?: return
        val selected = selectedText()
        if (selected.isBlank() || selA !in ws.indices || selB !in ws.indices) return
        items = emptyList()
        val ids = ws.subList(minOf(selA, selB), maxOf(selA, selB) + 1).map { it.block }.distinct()
        val text = ids.mapNotNull { source.blocks.getOrNull(it)?.text }.joinToString("\n\n")
        if (text.isBlank()) return
        Tr.detect(text) { code ->
            if (selected == selectedText()) showCard(text, if (code == "und") "en" else code)
        }
    }

    private fun showCard(t: String, src: String) {
        android.util.Log.i(CircleOverlay.TAG, "selection language $src (${t.length} chars), target pref ${svc.target()}")
        run {
            val pref = svc.target()
            val tgt = if (src == pref) Tr.other(pref) else pref
            val cd = Card(src, tgt, t, null)
            card = cd
            svc.setSheetVisible(this, false)
            invalidate()
            fillCard(cd)
        }
    }

    private fun fillCard(cd: Card) {
        cd.layout = null; invalidate()
        Tr.translate(cd.original, cd.src, cd.tgt) { r ->
            if (card !== cd) return@translate
            val p = TextPaint(text).apply { color = onSurface; textSize = 18 * d; typeface = Typeface.create("google-sans", Typeface.NORMAL) }
            val s = r ?: "Could not translate this text."
            cd.layout = StaticLayout.Builder.obtain(s, 0, s.length, p, (width - 68 * d).toInt()).setLineSpacing(2 * d, 1f).build()
            cardText = r
            invalidate()
        }
    }

    private var cardText: String? = null

    private fun flash(s: String) {
        toast = s; invalidate()
        postDelayed({ if (toast == s) { toast = null; invalidate() } }, 1400)
    }

    // ---------- input ----------

    // ---------- input ----------
    // Spaces: "content" is the screenshot's own space (words, selection, stroke, region). On screen it appears at
    // content * zoom + (zox, zoy), and the whole thing is moved up by panY while a sheet would cover the selection.
    private fun cX(sx: Float) = (sx - zox) / zoom
    private fun cY(sy: Float) = (sy + panY - zoy) / zoom
    private fun pX(cx: Float) = SelLogic.toScreen(cx, zoom, zox)
    private fun pY(cy: Float) = SelLogic.toScreen(cy, zoom, zoy)          // "page" y: screen y + panY

    private val longPress = Runnable {
        // Finger rested on a word: text selection starts there and follows the finger, word by word.
        if (drawing && lpWord >= 0 && lpWord < ws.size && !strokeMoved) {
            drawing = false; stroke.reset(); pts.clear(); ptT.clear()
            strokeFrames = 0; glowFrames = 0; strokeWanted = 0
            resetGlyphs()
            textDrag = true; lpAnchor = lpWord
            selA = lpWord; selB = lpWord; region = null; card = null; items = emptyList()
            setCalm(true)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    private fun dropGesture() {
        removeCallbacks(longPress)
        if (capDragging) releaseCapsule()
        if (regionGrab >= 0) { regionStart?.let { region = RectF(it) }; regionGrab = -1 }
        if (textDrag || dragHandle != 0) {
            // the selection keeps the range it had reached: bring the toolbar, the search and the pan in line with it
            textDrag = false; dragHandle = 0; lpAnchor = -1
            layoutItems(); searchSelection(); updatePan()
        }
        capVt?.recycle(); capVt = null
        strokeFrames = 0; glowFrames = 0; strokeWanted = 0
        drawing = false; pressed = null; capDown = false; pendingClassify = false
        stroke.reset(); pts.clear(); ptT.clear()
    }

    // ---- two fingers: zoom and move the frozen screen ----
    private fun twoPointers(e: MotionEvent, skip: Int): IntArray? {
        var a = -1; var b = -1
        for (k in 0 until e.pointerCount) { if (k == skip) continue; if (a < 0) a = k else if (b < 0) b = k }
        return if (b >= 0) intArrayOf(a, b) else null
    }

    private fun beginPinch(e: MotionEvent, skip: Int = -1) {
        val t = twoPointers(e, skip) ?: return
        zoomAnim?.cancel()
        pinching = true
        pinchSpan0 = hypot(e.getX(t[0]) - e.getX(t[1]), e.getY(t[0]) - e.getY(t[1])).coerceAtLeast(40f)
        pinchZoom0 = SelLogic.unrubberZoom(zoom)
        pinchFx = (e.getX(t[0]) + e.getX(t[1])) / 2; pinchFy = (e.getY(t[0]) + e.getY(t[1])) / 2
    }

    private fun movePinch(e: MotionEvent) {
        val t = twoPointers(e, -1) ?: return
        val span = hypot(e.getX(t[0]) - e.getX(t[1]), e.getY(t[0]) - e.getY(t[1])).coerceAtLeast(40f)
        val fx = (e.getX(t[0]) + e.getX(t[1])) / 2; val fy = (e.getY(t[0]) + e.getY(t[1])) / 2
        // move with the fingers, then zoom about the point between them
        zox += fx - pinchFx; zoy += fy - pinchFy
        val z1 = SelLogic.rubberZoom(pinchZoom0 * span / pinchSpan0)
        val o = SelLogic.zoomAt(zoom, zox, zoy, fx, fy + panY, z1)
        zoom = z1; zox = o[0]; zoy = o[1]
        pinchFx = fx; pinchFy = fy
        fitOffsets()
        layoutItems(); invalidate()
    }

    /** Keeps the picture over the whole view; smaller than the view (pinched in past fit) it stays centred. */
    private fun fitOffsets() {
        val w = width.toFloat(); val h = height.toFloat()
        if (zoom < 1f) { zox = (w - w * zoom) / 2; zoy = (h - h * zoom) / 2 }
        else { val o = SelLogic.clampOffset(zoom, zox, zoy, w, h); zox = o[0]; zoy = o[1] }
    }

    /** Fingers lifted: back to fit if it was pinched in, back to the maximum if it was stretched past it. */
    private fun endPinch() {
        pinching = false; ignoreGesture = true
        val zt = SelLogic.settleZoom(zoom)
        if (zt == zoom) { fitOffsets(); updatePan(); invalidate(); return }
        val z0 = zoom; val fx = pinchFx; val fy = pinchFy + panY
        val cx = (fx - zox) / z0; val cy = (fy - zoy) / z0
        zoomAnim?.cancel()
        zoomAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                zoom = z0 + (zt - z0) * f
                zox = fx - cx * zoom; zoy = fy - cy * zoom
                fitOffsets(); layoutItems(); invalidate()
                if (f >= 1f) updatePan()
            }
            start()
        }
        Dbg.log("zoom released at ${"%.2f".format(z0)} -> ${"%.2f".format(zt)}")
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode != Mode.FROZEN) return false
        val act = e.actionMasked
        // A touch that began just above the results sheet moves the sheet (its edge is easy to miss with a finger).
        if (act == MotionEvent.ACTION_DOWN) { pinching = false; toSheet = null }
        toSheet?.let { sh ->
            if (svc.sheetOf(this) !== sh) { toSheet = null; ignoreGesture = true; return true }   // closed meanwhile
            val m = MotionEvent.obtain(e); m.offsetLocation(0f, -sh.topOnScreen); sh.dispatchTouchEvent(m); m.recycle()
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) toSheet = null
            return true
        }
        // ---- two fingers ----
        if (act == MotionEvent.ACTION_POINTER_DOWN) {
            if (!pinching && card == null) { dropGesture(); beginPinch(e) } else if (pinching) beginPinch(e)
            else { pressed = null; ignoreGesture = true }     // a card is open: two fingers do nothing, and press nothing
            invalidate(); return true
        }
        if (pinching) {
            when (act) {
                MotionEvent.ACTION_MOVE -> movePinch(e)
                MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount - 1 >= 2) beginPinch(e, e.actionIndex) else endPinch()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endPinch()
            }
            return true
        }
        if (ignoreGesture && act != MotionEvent.ACTION_DOWN) return true

        // one finger: screen position, and the same point in the screenshot
        val x = e.x; val ry = e.y; val py = e.y + panY
        val cx = cX(x); val cy = cY(ry)
        when (act) {
            MotionEvent.ACTION_DOWN -> {
                zoomAnim?.let { if (it.isRunning) it.end() }
                downX = x; downY = ry; pressed = null; dragHandle = 0; drawing = false; ignoreGesture = false
                capDown = false; capDragging = false; textDrag = false; regionGrab = -1; strokeMoved = false; travel = 0f
                if (pendingClassify) { pendingClassify = false; stroke.reset(); pts.clear(); ptT.clear() }
                lastSX = x; lastSY = ry
                removeCallbacks(longPress)
                val cd = card
                // selection handles first: the nearer of the two, so one short word can be grown either way
                if (cd == null && selA >= 0 && selB < ws.size) {
                    val a = ws[selA]; val b = ws[selB]
                    val da = hypot(x - (pX(a.startX.toFloat()) - hr), py - (pY(a.lb.toFloat()) + hr))
                    val db = hypot(x - (pX(b.endX.toFloat()) + hr), py - (pY(b.lb.toFloat()) + hr))
                    val reach = 28 * d
                    if (min(da, db) < reach) { dragHandle = if (da < db) 1 else 2; items = emptyList(); invalidate(); return true }
                }
                if (cd == null) items.firstOrNull { it.rect.contains(x, py) }?.let { pressed = it.run; return true }
                val st = sheetTop
                if (st != null && cd == null && ry >= st - 30 * d && ry < st + 2 * d) {
                    svc.sheetOf(this)?.let { sh ->
                        toSheet = sh
                        val m = MotionEvent.obtain(e); m.offsetLocation(0f, -sh.topOnScreen); sh.dispatchTouchEvent(m); m.recycle()
                        return true
                    }
                }
                if (cd != null && cd.rect.contains(x, ry)) {
                    pressed = if (ry > cd.rect.bottom - 56 * d) ({ cardText?.let { svc.copy(it); flash("Copied") }; Unit })
                    else if (ry < cd.rect.top + 52 * d) ({ cd.tgt = Tr.other(cd.tgt); svc.setTarget(cd.tgt); fillCard(cd) })
                    else ({})
                    return true
                }
                if (cd != null) { pressed = { card = null; svc.setSheetVisible(this, true); layoutItems(); invalidate() }; return true }
                if (closeBtn.contains(x, ry)) { pressed = { svc.dismiss() }; return true }
                if (leaveT0 != 0L) return true                         // the bar is on its way out
                // language chips: the target switches between English and German; the source is always detected
                if (trOn && !chipCap.isEmpty && chipCap.contains(x, ry)) { pressed = if (tgtChip.contains(x, ry)) ({ svc.toggleTarget() }) else ({}); return true }
                if (sheetTop == null && calm < 0.5f) {
                    val ky = ry - capOff          // the capsule may have been moved up
                    if (capsule.contains(x, ky) || (ky > capsule.top - 10 * d && ky < capsule.bottom + 14 * d && x > capsule.left - 12 * d && x < capsule.right + 12 * d)) {
                        // Anywhere on the capsule: a tap presses what is under the finger, a vertical drag moves the capsule.
                        capSpring.stop()
                        capDown = true; capDownY = ry; capStart = capOff
                        capVt?.recycle(); capVt = android.view.VelocityTracker.obtain(); capVt?.addMovement(e)
                        pressed = if (depth == 0 && trBtn.contains(x, ky)) ({ if (trOn) { Dbg.log("translate off (button)"); stopTranslate() } else startTranslate() })
                        else if (trOn) (if (x < trBtn.left - 3 * d) ({ scrollAndTranslate() }) else ({}))
                        else if (musicBtn.contains(x, ky)) ({ svc.songSearch() })
                        else if (barRect.contains(x, ky)) (if (x > barRect.right - 50 * d) ({ svc.voiceSearch() }) else ({ setCalm(true); svc.askText(this) }))
                        else ({})
                        return true
                    }
                }
                items.firstOrNull { it.rect.contains(x, py) }?.let { pressed = it.run; return true }
                // a picture region: its borders can be dragged, and the whole region moved
                val rg = region
                if (rg != null && selA < 0) {
                    val g = SelLogic.grab(SelLogic.Box(rg.left, rg.top, rg.right, rg.bottom), cx, cy, 24 * d / zoom)
                    if (g >= 0) { regionGrab = g; regionStart = RectF(rg); downCX = cx; downCY = cy; items = emptyList(); return true }
                }
                drawing = true
                pts.clear(); ptT.clear(); stroke.reset(); stroke.moveTo(cx, cy); pts.add(PointF(cx, cy)); ptT.add(android.os.SystemClock.uptimeMillis())
                lpWord = SelLogic.wordAt(wholeWords, cx, cy, 6 * d / zoom)
                if (lpWord >= 0) postDelayed(longPress, 340)
                invalidate()                                   // the glowing dot under the finger, at once
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(x - lastSX, ry - lastSY) >= 2 * d) { travel += hypot(x - lastSX, ry - lastSY); lastSX = x; lastSY = ry }
                if (hypot(x - downX, ry - downY) > slop || travel > slop * 2) { strokeMoved = true; removeCallbacks(longPress) }
                if (capDown) {
                    capVt?.addMovement(e)
                    if (!capDragging && abs(ry - capDownY) > slop) { capDragging = true; pressed = null; capDownY = ry; capStart = capOff }
                    if (capDragging) {
                        val top = capTopOff()
                        var o2 = capStart + (ry - capDownY)
                        // past either end it follows the finger less and less
                        if (o2 > 0f) o2 = o2 / (1f + o2 / (60 * d)) else if (o2 < top) o2 = top - (top - o2) / (1f + (top - o2) / (60 * d))
                        capOff = o2; capSpring.value = o2; invalidate()
                    }
                } else if (dragHandle != 0) {
                    // the handle hangs below its line, so the finger points at the line above it
                    moveHandle(cx, cY(ry - 22 * d))
                } else if (textDrag) {
                    val i = SelLogic.nearestWord(ws, cx, cy)
                    if (i >= 0 && lpAnchor >= 0) { selA = min(lpAnchor, i); selB = max(lpAnchor, i); invalidate() }
                } else if (regionGrab >= 0) {
                    regionStart?.let { r0 ->
                        val b = SelLogic.adjust(SelLogic.Box(r0.left, r0.top, r0.right, r0.bottom), regionGrab, (x - downX) / zoom, (ry - downY) / zoom, width.toFloat(), height.toFloat(), 40 * d / zoom)
                        region = RectF(b.l, b.t, b.r, b.b); invalidate()
                    }
                } else if (drawing) {
                    for (k in 0 until e.historySize) add(cX(e.getHistoricalX(k)), cY(e.getHistoricalY(k)))
                    add(cx, cy)
                    if (pts.size == 6 && (selA >= 0 || region != null || card != null)) { selA = -1; selB = -1; items = emptyList(); card = null; region = null }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (capDragging) releaseCapsule()
                else if (pressed != null) { if (hypot(x - downX, ry - downY) < slop * 2) pressed?.invoke(); pressed = null }
                else if (dragHandle != 0 || textDrag) {
                    dragHandle = 0; textDrag = false; lpAnchor = -1
                    layoutItems(); invalidate(); searchSelection(); updatePan()
                }
                else if (regionGrab >= 0) {
                    regionGrab = -1
                    val r = region
                    if (r != null && regionStart?.let { abs(it.left - r.left) + abs(it.top - r.top) + abs(it.right - r.right) + abs(it.bottom - r.bottom) > 6 * d / zoom } == true) searchRegion(RectF(r), grow = false)
                    else layoutItems()
                    invalidate()
                }
                else if (drawing) {
                    drawing = false
                    strokeDone()
                    if (!strokeMoved) { stroke.reset(); pts.clear(); ptT.clear(); tap(cx, cy) }
                    else if (ocr == null) pendingClassify = true
                    else classify()
                }
                capDown = false
            }
            MotionEvent.ACTION_CANCEL -> { dropGesture(); invalidate() }
        }
        return true
    }

    /** How far up the capsule can go: to just below the header. */
    private fun capTopOff() = -(capsule.top - 112 * d).coerceAtLeast(0f)

    /** Capsule let go: near the top or the bottom it snaps there, in between it stays where it was released. */
    private fun releaseCapsule() {
        capDragging = false; capDown = false
        capVt?.computeCurrentVelocity(1000)
        val v = capVt?.yVelocity ?: 0f
        capVt?.recycle(); capVt = null
        val top = capTopOff()
        val zone = -top * 0.22f
        val proj = (capOff + v * 0.16f).coerceIn(top, 0f)       // where the movement would carry it
        val t = if (proj > -zone) 0f else if (proj < top + zone) top else proj
        Dbg.log("capsule released at ${(capOff / d).toInt()} dp, speed ${(v / d).toInt()} dp/s -> rests at ${(t / d).toInt()} dp")
        capSpring.value = capOff
        capSpring.to(t, v)
    }

    /** Adds a stroke point (content space); the minimum step is two screen dp whatever the zoom. */
    private fun add(x: Float, y: Float) {
        val l = pts.lastOrNull() ?: run { stroke.moveTo(x, y); pts.add(PointF(x, y)); ptT.add(android.os.SystemClock.uptimeMillis()); return }
        if (hypot(x - l.x, y - l.y) < 2 * d / zoom) return
        stroke.quadTo(l.x, l.y, (x + l.x) / 2, (y + l.y) / 2)
        pts.add(PointF(x, y)); ptT.add(android.os.SystemClock.uptimeMillis())
    }

    private fun tap(cx: Float, cy: Float) {
        val i = SelLogic.wordAt(wholeWords, cx, cy, 6 * d / zoom)
        if (i >= 0) { setSelection(i, i); return }
        if (selA >= 0 || card != null || region != null) { clearSelection(); return }
        if (ocr == null) return
        val s = width * 0.26f / zoom
        searchRegion(RectF(cx - s, cy - s, cx + s, cy + s))
    }

    /** The translate button: the frozen screen is translated in place. Scrolling is a second step (the bar offers it). */
    private fun startTranslate() {
        if (ocr == null) { wantTranslate = true; setStatus("Reading the screen…"); return }
        setTr(true)
        svc.translateInPlace(this)
    }
}
