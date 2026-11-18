package dev.kb896.aiusage.circle

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.graphics.PathParser
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Root of the overlay window. Back comes from the system: the window sits below HyperOS's gesture layer, so the
 * phone's own edge swipe (with its own arrow) reaches it as a back key or a back callback, like in any app.
 * An edge touch that arrives here at all was not taken by the system (its gesture is switched off there, or the
 * fallback window type covers the gesture layer); such a swipe is recognised here, without drawing anything.
 */
@SuppressLint("ViewConstructor")
class Host(private val svc: CircleOverlay) : FrameLayout(svc) {
    private val d = resources.displayMetrics.density
    private val slop = android.view.ViewConfiguration.get(svc).scaledTouchSlop
    // A touch that begins near the left or right edge is far more often the back gesture than a marking.
    // The system intercepts touches in its back strip. Wait for direction on nearby touches
    // before treating them as selection strokes.
    private val zone = SelLogic.EDGE_ZONE_DP * d
    private var side = 0          // -1 = started at the left edge, 1 = right edge, 0 = not an edge touch (or decided: a mark)
    private var backing = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var fromEdge = 0f
    private var vt: android.view.VelocityTracker? = null
    /** An edge touch that has not shown yet whether it is back or a mark: the overlay draws no stroke for it. */
    val edgeUndecided: Boolean get() = side != 0 && !backing
    private val held = Runnable { if (side != 0 && !backing) { Dbg.log("edge touch ${(fromEdge / d).toInt()} dp from the ${if (side < 0) "left" else "right"} edge: held still, a mark"); side = 0; invalidateAll() } }
    private fun invalidateAll() { for (i in 0 until childCount) getChildAt(i).invalidate() }

    init {
        if (android.os.Build.VERSION.SDK_INT >= 33) addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            val cb = android.window.OnBackInvokedCallback { svc.back("system callback") }
            override fun onViewAttachedToWindow(v: View) { runCatching { findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb) } }
            override fun onViewDetachedFromWindow(v: View) { removeCallbacks(held); runCatching { findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(cb) } }
        })
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) svc.back("key")
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; backing = false; downAt = e.eventTime
                side = if (e.x < zone) -1 else if (e.x > width - zone) 1 else 0
                fromEdge = if (side < 0) e.x else width - e.x
                removeCallbacks(held)
                vt?.recycle(); vt = null
                if (side != 0) { vt = android.view.VelocityTracker.obtain(); vt?.addMovement(e); postDelayed(held, SelLogic.EDGE_HOLD_MS) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (side != 0 && !backing) { side = 0; removeCallbacks(held); invalidateAll() }   // two fingers: zoom
            MotionEvent.ACTION_MOVE -> if (side != 0 && !backing) {
                vt?.addMovement(e)
                when (SelLogic.edgeVerdict((e.x - downX) * -side / d, (e.y - downY) / d)) {
                    SelLogic.Edge.BACK -> { backing = true; removeCallbacks(held); return true }
                    SelLogic.Edge.MARK -> {
                        Dbg.log("edge touch ${(fromEdge / d).toInt()} dp from the ${if (side < 0) "left" else "right"} edge: moved ${((e.y - downY) / d).toInt()} dp down, ${((e.x - downX) * -side / d).toInt()} dp inward: a mark")
                        side = 0; removeCallbacks(held); invalidateAll()
                    }
                    SelLogic.Edge.WAIT -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (side != 0 && !backing) { side = 0; removeCallbacks(held); invalidateAll() }   // a tap
        }
        return backing
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!backing) return false
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_UP -> {
                vt?.computeCurrentVelocity(1000)
                val inward = (e.x - downX) * -side / d; val speed = (vt?.xVelocity ?: 0f) * -side / d
                val go = SelLogic.edgeBackCommits(inward, speed)
                Dbg.log("edge touch ${(fromEdge / d).toInt()} dp from the ${if (side < 0) "left" else "right"} edge: ${inward.toInt()} dp inward, ${speed.toInt()} dp/s: ${if (go) "back" else "too short, nothing"}")
                backing = false; side = 0
                vt?.recycle(); vt = null
                if (go) svc.back("edge swipe")
            }
            MotionEvent.ACTION_CANCEL -> { backing = false; side = 0; vt?.recycle(); vt = null }
        }
        return true
    }
}

/**
 * The results sheet of one search level: a grab handle, a pill showing what was searched, and a web view with
 * Google's results. Drag the top part up for full height, down to close (the service then returns to the
 * previous search level, or to the idle frozen screen).
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class Sheet(private val svc: CircleOverlay, private val onMoved: (Float?) -> Unit, private val onClosed: () -> Unit) : FrameLayout(svc) {
    private val d = resources.displayMetrics.density
    private val bg = 0xFF1F2023.toInt()
    private val headH = (108 * d).toInt()
    private var fullH = 0
    private var halfH = 0
    private var query = ""
    private var thumb: Bitmap? = null
    private var loading = false
    private var ownLoad = false

    val web: WebView
    private val head: View
    // The search pill is a real text field while it is being edited: a tap on it brings up the keyboard with the
    // current text and the cursor at its end; the search key searches for what is in it, changed or not.
    private val edit: android.widget.EditText
    private var editing = false
    private var imageUrl: String? = null        // results page of the picture being searched, if any
    private var headTap = false

    init {
        setBackgroundColor(bg)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height + (40 * d).toInt(), 28 * d) }
        }
        clipToOutline = true
        elevation = 12 * d

        // A dark-themed context makes pages see prefers-color-scheme: dark.
        web = WebView(ContextThemeWrapper(svc, android.R.style.Theme_DeviceDefault))
        web.setBackgroundColor(bg)
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme
                return s != "http" && s != "https"   // no jumping out to other apps from inside the sheet
            }
            override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) { loading = true; head.invalidate() }
            override fun onPageCommitVisible(v: WebView, url: String?) { trim(v, url) }
            override fun onPageFinished(v: WebView, url: String?) { if (ownLoad) { ownLoad = false; v.clearHistory() }; loading = false; head.invalidate(); trim(v, url); Dbg.log("sheet page loaded") }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: android.webkit.WebResourceError) { if (r.isForMainFrame) Dbg.log("sheet page error ${e.errorCode}") }
            // On a results page the sheet's own pill already shows the query: hide the page's logo row and search box.
            private fun trim(v: WebView, url: String?) {
                if (url == null || !url.contains("google.") || !url.contains("/search")) return
                v.evaluateJavascript("(function(){if(document.getElementById('cs-trim'))return;var s=document.createElement('style');s.id='cs-trim';s.textContent='header{display:none!important}';(document.head||document.documentElement).appendChild(s);})()", null)
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { topMargin = headH })

        head = Head()
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, headH))

        edit = android.widget.EditText(ContextThemeWrapper(svc, android.R.style.Theme_DeviceDefault))
        edit.background = null
        edit.setSingleLine(true)
        edit.setTextColor(0xFFE3E3E3.toInt()); edit.setHintTextColor(0xFF9AA0A6.toInt())
        edit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 17 * d)
        edit.typeface = Typeface.create("google-sans", Typeface.NORMAL)
        edit.setPadding(0, 0, 0, 0); edit.gravity = Gravity.CENTER_VERTICAL
        edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        edit.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        edit.hint = "Search"
        edit.visibility = View.GONE
        edit.setOnEditorActionListener { _, id, ev ->
            if (id == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || id == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) { submit(edit.text.toString()); true } else false
        }
        addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, (52 * d).toInt()).apply { topMargin = (36 * d).toInt(); leftMargin = (68 * d).toInt(); rightMargin = (36 * d).toInt() })
    }

    private fun imm() = svc.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
    private fun inPill(e: MotionEvent) = e.y > 34 * d && e.y < 90 * d && e.x > 16 * d && e.x < width - 16 * d

    /** Tap on the pill: the text in it becomes editable, cursor at the end, keyboard up. */
    fun startEdit() {
        if (editing) { imm()?.showSoftInput(edit, 0); return }
        editing = true
        // after a picture search the pill shows a label, not a query: typing starts empty and is added to the picture
        val current = if (thumb != null) typedForImage else query
        edit.setText(current); edit.setSelection(edit.text.length)
        (edit.layoutParams as LayoutParams).leftMargin = ((if (thumb != null) 112 else 68) * d).toInt()
        edit.hint = if (thumb != null) "Add to your search" else "Search"
        edit.visibility = View.VISIBLE
        edit.requestLayout()
        edit.requestFocus()
        edit.post { imm()?.showSoftInput(edit, 0) }
        // the collapsed strip would sit under the keyboard: come up to half height
        if (translationY > half() + 1f) springTo(half())
        head.invalidate()
        Dbg.log("search bar: editing (${current.length} chars in it${if (thumb != null) ", picture search" else ""})")
    }

    /** Leaves editing without searching; true if it was editing. */
    fun stopEdit(): Boolean {
        if (!editing) return false
        editing = false
        imm()?.hideSoftInputFromWindow(edit.windowToken, 0)
        edit.clearFocus(); edit.visibility = View.GONE
        head.invalidate()
        return true
    }
    private var typedForImage = ""

    /** One search made in this sheet, with what the pill showed and what was selected on the screen for it. */
    class Entry(val query: String, val url: String, val thumb: Bitmap?, val imageUrl: String?, val typed: String, val lastQuery: String?, val tag: Any?)
    private val history = ArrayList<Entry>()
    /** Gives the selection the next search belongs to, so going back can show it again. */
    var tagProvider: (() -> Any?)? = null
    val searches: Int get() = history.size
    val isEditing: Boolean get() = editing
    fun canPageBack(): Boolean = web.canGoBack()

    private fun push(url: String) {
        val e = Entry(query, url, thumb, imageUrl, typedForImage, lastQuery, tagProvider?.invoke())
        if (history.lastOrNull()?.url == url) history[history.size - 1] = e else history.add(e)
        if (history.size > 40) history.removeAt(0)
    }

    /** Back to the search made before the current one: pill and page as they were. Null if there is none. */
    fun previousSearch(): Entry? {
        if (history.size < 2) return null
        history.removeAt(history.size - 1)
        val e = history.last()
        stopEdit()
        query = e.query; thumb = e.thumb; imageUrl = e.imageUrl; typedForImage = e.typed; lastQuery = e.lastQuery
        loading = true; head.invalidate()
        ownLoad = true; web.loadUrl(e.url)
        return e
    }

    /** The search key: searches for what is in the bar, also when it is the same text as before. */
    private fun submit(text: String) {
        val t = text.trim()
        stopEdit()
        val img = imageUrl
        if (thumb != null && img != null) {
            typedForImage = t
            query = if (t.isEmpty()) "Image search" else t
            Dbg.log("search bar: picture search with ${t.length} chars of text added")
            ownLoad = true; loading = true; head.invalidate()
            val u = SelLogic.withQuery(img, t)
            push(u); web.loadUrl(u)
        } else {
            Dbg.log("search bar: search for ${t.length} chars typed")
            lastQuery = null
            searchText(t)
        }
    }

    // ---- position ----
    // translationY is the sheet's position: 0 = full height, half() = half open, fullH = closed (below the screen).
    // The view is taller than fullH by [ext], so its body continues below the screen when it is pulled above full.
    private val ext = (240 * d).toInt()

    /** Size and place the sheet for a parent of [h] pixels, then let it spring in to half height. */
    fun attach(parent: FrameLayout, h: Int) {
        fullH = h - (96 * d).toInt()
        halfH = (h * 0.47f).toInt()
        web.layoutParams = (web.layoutParams as LayoutParams).apply { height = fullH - headH }
        parent.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, fullH + ext, Gravity.TOP).apply { topMargin = h - fullH })
        translationY = fullH.toFloat()
        springTo(half(), -(fullH - half()) * 3f)
    }

    val topOnScreen: Float get() = (parent as? View)?.let { it.height - fullH + translationY } ?: 0f

    private fun half() = (fullH - halfH).toFloat()
    private fun atFull() = translationY < 1.5f
    private fun setPos(ty: Float) { translationY = ty; onMoved(topOnScreen) }

    // A damped spring carries the sheet to its resting place, starting with the speed the finger let go at.
    private val stiffness = 380f
    private val damping = 0.86f
    private var vel = 0f
    private var target = 0f
    private var springOn = false
    private var lastNs = 0L
    private var onRest: (() -> Unit)? = null
    private val choreo = android.view.Choreographer.getInstance()
    private val tick = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!springOn) return
            val dt = if (lastNs == 0L) 1f / 120f else ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.034f)
            lastNs = now
            var x = translationY
            val closing = target > fullH
            val c = 2f * (if (closing) 1f else damping) * kotlin.math.sqrt(stiffness)
            val n = kotlin.math.ceil(dt / 0.001f).toInt()
            val h = dt / n
            repeat(n) { vel += (-stiffness * (x - target) - c * vel) * h; x += vel * h }
            if (closing) { if (x >= fullH) { x = fullH.toFloat(); springOn = false } }
            else if (abs(x - target) < 0.5f && abs(vel) < 20f) { x = target; springOn = false }
            setPos(x)
            if (springOn) choreo.postFrameCallback(this)
            else { vel = 0f; val r = onRest; onRest = null; r?.invoke() }
        }
    }

    private fun springTo(t: Float, v0: Float = 0f, then: (() -> Unit)? = null) {
        target = t; vel = v0; onRest = then
        if (!springOn) { springOn = true; lastNs = 0L; choreo.postFrameCallback(tick) }
    }

    private fun stopSpring() { springOn = false; onRest = null; choreo.removeFrameCallback(tick); vel = 0f }

    /** Past full height the sheet follows the finger less and less (rubber band). */
    private fun rubber(over: Float): Float { val dim = halfH.toFloat(); return (1f - 1f / (over * 0.55f / dim + 1f)) * dim }
    private fun unrubber(y: Float): Float { val dim = halfH.toFloat(); return y * dim / (0.55f * (dim - y).coerceAtLeast(1f)) }

    /** Shows what is about to be searched (query in the pill, loading line) before the request goes out. */
    fun announce(q: String) { if (q == lastQuery) return; query = q; thumb = null; loading = q.isNotBlank(); head.invalidate() }
    private var lastQuery: String? = null

    fun searchText(q: String) {
        if (q == lastQuery && thumb == null) return   // same text again (for example a handle put back): keep the page
        lastQuery = q
        val shown = SelLogic.limitWords(q)
        query = shown; thumb = null; imageUrl = null; typedForImage = ""; head.invalidate()
        if (editing) { edit.setText(shown); edit.setSelection(edit.text.length) }
        ownLoad = true
        val url = SelLogic.searchUrl(q, java.util.Locale.getDefault().language)
        if (q.isNotBlank()) push(url)
        web.loadUrl(url)
    }

    fun showImage(crop: Bitmap) { stopEdit(); imageUrl = null; typedForImage = ""; lastQuery = null; query = "Searching this image…"; thumb = crop; loading = true; head.invalidate(); ownLoad = true; web.loadUrl("about:blank") }
    fun imageResults(url: String) { query = "Image search"; imageUrl = url; typedForImage = ""; head.invalidate(); push(url); ownLoad = true; web.loadUrl(url) }
    fun imageFailed(msg: String) { query = msg; loading = false; head.invalidate() }

    /** Slides the sheet off the bottom; it leaves at speed instead of slowing down at the edge. */
    fun close(velocity: Float = 0f) { if (leaving) return; leaving = true; onClosing?.invoke(); springTo(fullH * 1.3f, max(velocity, 0f)) { onMoved(null); onClosed() } }
    /** Called the moment a release decides that the sheet goes away, before it has slid off. */
    var onClosing: (() -> Unit)? = null
    private var leaving = false

    /** Back inside the results: one page back if the user followed a link in the sheet. */
    fun goBack(): Boolean { if (!web.canGoBack()) return false; web.goBack(); return true }

    // ---- dragging ----
    // The handle and the search pill move the sheet from the first pixel. On the page a vertical drag moves the
    // sheet until it is at full height; from there the page scrolls, and pulling down at the page's top moves the
    // sheet again. Both hand-overs happen inside one gesture, without lifting the finger.
    private enum class G { IDLE, UNDECIDED, SHEET, WEB }
    private var g = G.IDLE
    private val slop = android.view.ViewConfiguration.get(svc).scaledTouchSlop
    private val maxFling = android.view.ViewConfiguration.get(svc).scaledMaximumFlingVelocity.toFloat()
    private var vt: android.view.VelocityTracker? = null
    private var downX = 0f; private var downY = 0f; private var lastRawY = 0f
    private var anchorRaw = 0f; private var anchorY = 0f   // finger and (un-rubbered) sheet position at the grab
    private var onHeadDown = false
    private var webVertical = false
    private var pid = 0

    private fun grab(rawY: Float) {
        g = G.SHEET; anchorRaw = rawY
        val ty = translationY
        anchorY = if (ty < 0f) -unrubber(-ty) else ty
    }

    private fun toWeb(e: MotionEvent, action: Int) {
        val m = MotionEvent.obtain(e); m.action = action
        super.dispatchTouchEvent(m); m.recycle()
    }

    /** Velocity has to be measured in screen coordinates: the sheet itself moves under the finger. */
    private fun track(e: MotionEvent) {
        val m = MotionEvent.obtain(e); m.setLocation(e.rawX, e.rawY); vt?.addMovement(m); m.recycle()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(e0: MotionEvent): Boolean {
        // Only the finger that touched first counts: a second finger must not move the sheet or take it over.
        if (e0.actionMasked == MotionEvent.ACTION_DOWN) pid = e0.getPointerId(0)
        val pi = e0.findPointerIndex(pid)
        if (e0.pointerCount > 1 || pi != 0) {
            if (g == G.SHEET) {
                val lifted = e0.actionMasked == MotionEvent.ACTION_POINTER_UP && e0.actionIndex == pi
                if (lifted || pi < 0 || e0.actionMasked == MotionEvent.ACTION_UP || e0.actionMasked == MotionEvent.ACTION_CANCEL) { release(); g = G.IDLE; vt?.recycle(); vt = null }
                else if (e0.actionMasked == MotionEvent.ACTION_MOVE && pi >= 0) setPos((anchorY + (e0.getRawY(pi) - anchorRaw)).let { if (it < 0f) -rubber(-it) else min(it, fullH.toFloat()) })
                return true
            }
            return if (g == G.IDLE) true else super.dispatchTouchEvent(e0)
        }
        val e = e0
        val a = e.actionMasked
        if (a == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = android.view.VelocityTracker.obtain() }
        track(e)
        when (a) {
            MotionEvent.ACTION_DOWN -> {
                stopSpring()                                   // a moving sheet is caught where it is
                downX = e.rawX; downY = e.rawY; onHeadDown = e.y < headH; webVertical = false
                headTap = onHeadDown && inPill(e)
                if (editing && headTap) { g = G.WEB; super.dispatchTouchEvent(e) }       // the text field: cursor, selection
                else if (onHeadDown) grab(e.rawY) else { if (editing) stopEdit(); g = G.UNDECIDED; super.dispatchTouchEvent(e) }
            }
            MotionEvent.ACTION_MOVE -> when (g) {
                G.UNDECIDED -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        if (!atFull() || (dy > 0 && web.scrollY <= 0)) { toWeb(e, MotionEvent.ACTION_CANCEL); grab(e.rawY) }
                        else { g = G.WEB; webVertical = true; super.dispatchTouchEvent(e) }
                    } else {
                        if (abs(dx) > slop) g = G.WEB
                        super.dispatchTouchEvent(e)
                    }
                }
                G.SHEET -> {
                    val raw = anchorY + (e.rawY - anchorRaw)
                    if (raw < 0f && !onHeadDown) {
                        // Full height reached with the finger still going up: the page takes over and scrolls.
                        setPos(0f); g = G.WEB; webVertical = true; toWeb(e, MotionEvent.ACTION_DOWN)
                    } else setPos(if (raw < 0f) -rubber(-raw) else min(raw, fullH.toFloat()))
                }
                G.WEB -> {
                    // The page is back at its top and the finger keeps pulling down: the sheet takes over.
                    if (webVertical && atFull() && web.scrollY <= 0 && e.rawY > lastRawY + 0.5f) { toWeb(e, MotionEvent.ACTION_CANCEL); grab(e.rawY) }
                    else super.dispatchTouchEvent(e)
                }
                G.IDLE -> {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (g) {
                    G.SHEET -> {
                        release()
                        // a tap (not a drag) on the pill: edit the text in it
                        if (a == MotionEvent.ACTION_UP && headTap && abs(e.rawY - downY) < slop && abs(e.rawX - downX) < slop) startEdit()
                    }
                    G.UNDECIDED, G.WEB -> super.dispatchTouchEvent(e)
                    G.IDLE -> {}
                }
                g = G.IDLE; vt?.recycle(); vt = null
            }
            else -> if (g == G.WEB || g == G.UNDECIDED) super.dispatchTouchEvent(e)
        }
        lastRawY = e.rawY
        return true
    }

    /** Collapsed position: only the handle and the search pill show above the bottom of the screen. */
    private fun peek() = (fullH - headH).toFloat()

    private fun release() {
        vt?.computeCurrentVelocity(1000, maxFling)
        val v = vt?.yVelocity ?: 0f
        val pos = translationY
        if (pos < 0f) { springTo(0f); return }
        when (val t = SelLogic.sheetRest(pos, v, peek(), fullH.toFloat())) {
            null -> { Dbg.log("sheet released at ${(pos / d).toInt()} dp, speed ${(v / d).toInt()} dp/s -> goes away"); close(v) }
            else -> {
                Dbg.log("sheet released at ${(pos / d).toInt()} dp, speed ${(v / d).toInt()} dp/s -> rests at ${if (t == 0f) "full height" else "${(t / d).toInt()} dp"}")
                springTo(t, v)
            }
        }
    }

    fun destroy() { stopEdit(); stopSpring(); (parent as? FrameLayout)?.removeView(this); web.stopLoading(); web.destroy() }

    /** Handle, search pill and loading line; also the drag area. */
    private inner class Head : View(svc) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val g = listOf(
            0xFFEA4335.toInt() to "M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z",
            0xFF4285F4.toInt() to "M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z",
            0xFFFBBC05.toInt() to "M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z",
            0xFF34A853.toInt() to "M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z",
        ).map { it.first to PathParser.createPathFromPathData(it.second) }
        private var sweep = 0f
        private val spin = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { sweep = it.animatedValue as Float; if (loading) invalidate() }
            start()
        }

        override fun onDetachedFromWindow() { spin.cancel(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            p.shader = null
            p.color = 0xFF8E918F.toInt()
            r.set(w / 2 - 16 * d, 12 * d, w / 2 + 16 * d, 16 * d)
            c.drawRoundRect(r, 2 * d, 2 * d, p)
            // search pill
            r.set(16 * d, 36 * d, w - 16 * d, 88 * d)
            p.color = 0xFF2B2D31.toInt()
            c.drawRoundRect(r, 26 * d, 26 * d, p)
            val gs = 24 * d
            c.save(); c.translate(r.left + 16 * d, r.centerY() - gs / 2); c.scale(gs / 48f, gs / 48f)
            for ((col, path) in g) { p.color = col; c.drawPath(path, p) }
            c.restore()
            var tx = r.left + 52 * d
            thumb?.let { b ->
                val s = 34 * d
                val dst = RectF(tx, r.centerY() - s / 2, tx + s, r.centerY() + s / 2)
                c.save(); val clip = android.graphics.Path().apply { addRoundRect(dst, 8 * d, 8 * d, android.graphics.Path.Direction.CW) }
                c.clipPath(clip); c.drawBitmap(b, null, dst, p); c.restore()
                tx += s + 10 * d
            }
            p.color = 0xFFE3E3E3.toInt(); p.textSize = 17 * d; p.typeface = Typeface.create("google-sans", Typeface.NORMAL)
            if (!editing) {
                val shown = TextUtils.ellipsize(query.replace('\n', ' ').ifBlank { "Search" }, android.text.TextPaint(p), r.right - tx - 20 * d, TextUtils.TruncateAt.END).toString()
                c.drawText(shown, tx, r.centerY() - (p.ascent() + p.descent()) / 2, p)
            }
            // loading line under the pill, in Google's colours
            if (loading) {
                val x0 = r.left + 26 * d; val x1 = r.right - 26 * d; val len = (x1 - x0) * 0.35f
                val sx = x0 + (x1 - x0 - len) * sweep
                p.shader = android.graphics.LinearGradient(sx, 0f, sx + len, 0f,
                    intArrayOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt(), 0xFF34A853.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
                c.drawRoundRect(sx, r.bottom - 2.5f * d, sx + len, r.bottom, d, d, p)
                p.shader = null
            }
        }

        // Touches fall through to the sheet, which handles dragging for its whole surface.
    }
}
