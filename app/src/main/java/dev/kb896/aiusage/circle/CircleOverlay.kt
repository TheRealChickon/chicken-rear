package dev.kb896.aiusage.circle

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

class CircleOverlay(private val service: AccessibilityService) : android.content.ContextWrapper(service) {
    companion object {
        const val G = "com.google.android.googlequicksearchbox"
        const val TAG = "CircleSearch"
    }

    internal val ocr = PaddleOcr(service.applicationContext)
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("cs", Context.MODE_PRIVATE) }

    /** One search level: a frozen screen and, once something was searched on it, its results sheet. */
    private class Level(val view: OverlayView, val shot: Shot) { var sheet: Sheet? = null }
    private var host: Host? = null
    private val levels = ArrayList<Level>()
    /** The overlay view of the top level (the only one in scroll-translate mode). */
    private var view: OverlayView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var pill: PillView? = null

    /** A screenshot with what is derived from it off the main thread (ready well before a hold completes). */
    class Shot(val hw: Bitmap) {
        @Volatile var page: TrLayout.Area? = null
        @Volatile var soft: Bitmap? = null
        /** Two pixel rows that, stretched over the status bar, repaint it without its icons (see [cleanStatusBar]). */
        @Volatile var strip: Bitmap? = null
        @Volatile var lightBar = false
        var statusShown = true
        fun recycle() { hw.recycle(); soft?.recycle(); strip?.recycle() }
    }
    private val prep = Executors.newSingleThreadExecutor()
    private val layoutExec = Executors.newSingleThreadExecutor()
    private var follower: ScrollFollow? = null
    private var prepared: Shot? = null
    private var preparedAt = 0L
    private var captureGeneration = 0
    private var shooting = false
    private var pendingShow = false
    private var lastShotAt = 0L
    private var showAskedAt = 0L
    private var keepAlive: android.view.View? = null

    private var hostWm: WindowManager? = null
    private var hostIsApp = false
    private val appWm: WindowManager? by lazy {
        runCatching {
            val disp = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createWindowContext(disp, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null).getSystemService(WindowManager::class.java)
        }.getOrNull()
    }
    /** Height of the status bar as the system lays it out (with a camera cutout it is taller than the plain dimension). */
    private val sbHeight by lazy {
        val dimen = resources.getIdentifier("status_bar_height", "dimen", "android").let { if (it != 0) resources.getDimensionPixelSize(it) else 0 }
        val inset = runCatching { wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top }.getOrDefault(0)
        maxOf(dimen, inset)
    }
    private val topRefresh by lazy { runCatching { getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).supportedModes.maxOf { it.refreshRate } }.getOrDefault(0f) }

    // Staging: at the launcher's long-press mark the window is built and drawn with alpha 0; when the extra
    // hold is over it only has to be switched to alpha 1, which shows on the next frame.
    private var staged = false
    private var armAt = 0L
    private var armedOcrModel = OcrModel.FAST
    private var revealedAt = 0L
    private val fire = Runnable { armedFire() }

    // scroll-translate state
    private var frame = 0   // id of the screen content the current patches belong to
    private var lastTouch = 0L
    private var lastSig: IntArray? = null
    private var shownSig: IntArray? = null
    private var explicitTarget = false
    private var sessionTarget: String? = null
    private var lastSrc = "und"
    private var lastTgt = "en"

    private val closer = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            // Home / recents / screen off closes the frozen overlay; scroll-translate keeps running.
            if (view?.mode == OverlayView.Mode.FROZEN) dismiss()
            else if (i?.action == Intent.ACTION_SCREEN_OFF) dismiss()
        }
    }

    private var closed = false

    fun start() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        setEvents(0)
        // Keep the bound service responsive to gesture callbacks while this option is active.
        runCatching {
            wm.addView(android.view.View(this).also { keepAlive = it }, WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
                .apply { gravity = Gravity.TOP or Gravity.START; title = "CircleSearchKeepAlive" })
        }
        registerReceiver(closer, IntentFilter().apply {
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS); addAction(Intent.ACTION_SCREEN_OFF)
        }, Context.RECEIVER_NOT_EXPORTED)
        Tr.onStatus = { s -> view?.setStatus(s); pill?.setStatus(s) }
        Tr.warm(this)
    }

    fun close() {
        closed = true
        main.removeCallbacks(fire)
        pendingShow = false
        host?.animate()?.cancel()
        removeWindows()
        setEvents(0)
        keepAlive?.let { runCatching { wm.removeViewImmediate(it) } }
        keepAlive = null
        runCatching { unregisterReceiver(closer) }
        Tr.cancelPending()
        Tr.onStatus = null
        discardPrepared()
        ocr.close()
        prep.shutdown()
        bg.shutdown()
        layoutExec.shutdown()
        follower?.close()
        follower = null
    }

    private fun setEvents(types: Int, listWindows: Boolean = types != 0) {
        service.serviceInfo = service.serviceInfo?.apply {
            eventTypes = types
            val retrieve = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            flags = if (listWindows) flags or retrieve else flags and retrieve.inv()
        } ?: return
    }

    fun target(): String = prefs.getString("target", "en") ?: "en"
    fun setTarget(t: String) { prefs.edit().putString("target", t).apply() }

    // ---------- trigger + screenshot ----------

    private fun shoot(cb: (Bitmap?) -> Unit) {
        if (closed) return
        service.takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                val b = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                r.hardwareBuffer.close()
                lastShotAt = SystemClock.uptimeMillis()
                if (closed) b?.recycle() else cb(b)
            }
            override fun onFailure(code: Int) { if (!closed) cb(null) }
        })
    }

    /** Finger went down on the nav bar: take the screenshot now so it is ready if this becomes a long-press. */
    fun prepare() {
        if (view?.mode == OverlayView.Mode.PASS) return
        captureGeneration++
        shooting = false
        pendingShow = false
        discardPrepared()
        capture(0)
    }

    private fun discardPrepared() {
        prepared?.let { shot -> prep.execute { shot.recycle() } }
        prepared = null
    }

    private fun capture(attempt: Int) {
        if (closed) return
        finishClosing()
        if (shooting) return
        if (view?.mode == OverlayView.Mode.FROZEN && !canNest()) return
        val passView = view?.takeIf { it.mode == OverlayView.Mode.PASS }
        if (passView != null && !pendingShow) return
        shooting = true
        val generation = captureGeneration
        val t0 = SystemClock.uptimeMillis()
        val ins = keepAlive?.rootWindowInsets
        val statusShown = ins?.isVisible(android.view.WindowInsets.Type.statusBars()) ?: true
        val receive: (Bitmap?) -> Unit = receive@{ b ->
            if (generation != captureGeneration || closed) {
                b?.recycle()
                return@receive
            }
            shooting = false
            if (b != null && (passView == null || view === passView && pendingShow)) {
                val shot = Shot(b).apply { this.statusShown = statusShown }
                prepared = shot
                preparedAt = SystemClock.uptimeMillis()
                Log.i(TAG, "screenshot ready ${preparedAt - t0} ms after capture")
                prep.execute {
                    shot.soft = runCatching { b.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                    if (statusShown) cleanStatusBar(shot)
                }
                if (pendingShow) { pendingShow = false; open(shot) }
                else main.postDelayed({
                    if (prepared === shot) {
                        discardPrepared()
                    }
                }, 3000)
            } else {
                b?.recycle()
                if (pendingShow && attempt < 4) main.postDelayed({
                    if (generation == captureGeneration && pendingShow) capture(attempt + 1)
                }, 380)
                else pendingShow = false
            }
        }
        if (passView == null) shoot(receive)
        else if (android.os.Build.VERSION.SDK_INT >= 34) captureWindow { receive(it.getOrNull()) }
        else receive(null)
    }

    /**
     * The status bar stays live above the overlay, so its frozen copy in the screenshot has to go, or a changed
     * clock or icon would show doubled. The rows just above and below the icons are stretched over the bar's
     * height, which rebuilds a plain or gradient background exactly and a picture closely enough.
     */
    private fun cleanStatusBar(s: Shot) {
        val soft = s.soft ?: return
        val h = sbHeight
        if (h < 12 || h >= soft.height / 4) return
        val w = soft.width
        val rows = IntArray(w * 2)
        soft.getPixels(rows, 0, w, 0, 1, w, 1)
        soft.getPixels(rows, w, w, 0, h - 1, w, 1)
        var lum = 0L
        var i = 0
        while (i < rows.size) { val c = rows[i]; lum += (c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255); i += 7 }
        s.lightBar = lum / ((rows.size + 6) / 7) > 1450          // bright background: the icons on it are dark
        s.strip = Bitmap.createBitmap(rows, w, 2, Bitmap.Config.ARGB_8888)
    }

    private fun show() {
        finishClosing()
        if (view?.mode == OverlayView.Mode.FROZEN && !canNest() && !staged) return
        showAskedAt = SystemClock.uptimeMillis()
        if (staged) { main.removeCallbacks(fire); reveal(); return }
        val p = prepared
        if (p != null && SystemClock.uptimeMillis() - preparedAt < 2500) open(p)
        else { pendingShow = true; capture(0) }
    }

    private var holdOnHome = false
    internal val wantsAnchor get() = holdOnHome && hostIsApp && !staged && view?.mode == OverlayView.Mode.FROZEN

    private fun anchorHome() {
        if (wantsAnchor && AnchorActivity.current == null) runCatching {
            startActivity(Intent(this, AnchorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        }
    }

    internal fun arm(at: Long, onHome: Boolean, model: OcrModel) {
        finishClosing()
        if (view?.mode == OverlayView.Mode.FROZEN && !canNest()) return
        main.removeCallbacks(fire)
        armAt = at
        armedOcrModel = model
        holdOnHome = onHome
        val p = prepared
        if (host == null && view == null && p != null && SystemClock.uptimeMillis() - preparedAt < 2500) open(p, stage = true)
        main.postAtTime(fire, armAt)      // at the planned moment, however long the staging above took
    }

    private fun armedFire() {
        showAskedAt = SystemClock.uptimeMillis()
        Log.i(TAG, "hold complete (${showAskedAt - armAt} ms after the planned time), staged=$staged")
        buzz()
        if (staged) reveal() else show()
    }

    /** The finger lifted or moved away before the hold was complete (or, rarely, a few ms after). */
    fun cancelArm() {
        main.removeCallbacks(fire)
        pendingShow = false
        if (staged) { Dbg.log("hold cancelled"); staged = false; removeWindows(); setEvents(0) }
        else if (revealedAt != 0L && SystemClock.uptimeMillis() - revealedAt < 250 && levels.size == 1 && levels[0].sheet == null) {
            Dbg.log("hold cancelled just after opening"); removeWindows(); setEvents(0)
        }
    }

    private fun buzz() {
        runCatching {
            val vib = getSystemService(android.os.Vibrator::class.java) ?: return
            val fx = android.os.VibrationEffect.createOneShot(35, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            if (android.os.Build.VERSION.SDK_INT >= 33) vib.vibrate(fx, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ACCESSIBILITY))
            else vib.vibrate(fx)
        }
    }

    /** A new hold while results are showing searches inside those results: it stacks a level on top. */
    private fun canNest() = !staged && levels.isNotEmpty() && levels.last().sheet != null && levels.size < 8

    private fun windowParams(app: Boolean, s: Shot?, hidden: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (hidden) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        // An app that had hidden the status bar (video, game) keeps it hidden under the overlay.
        if (app && s != null && !s.statusShown) flags = flags or WindowManager.LayoutParams.FLAG_FULLSCREEN
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (app) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags, PixelFormat.TRANSLUCENT,
        )
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        p.fitInsetsTypes = 0
        p.windowAnimations = 0
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        if (topRefresh > 0f) p.preferredRefreshRate = topRefresh   // animations and drags at the panel's full rate
        if (hidden) p.alpha = 0f
        p.title = "CircleSearch"
        return p
    }

    private fun open(s: Shot, stage: Boolean = false) {
        prepared = null
        if (view?.mode == OverlayView.Mode.FROZEN && canNest() && host != null) { addLevel(s); return }
        removeWindows()
        explicitTarget = false; sessionTarget = null
        val app = android.provider.Settings.canDrawOverlays(this) && appWm != null
        val mgr = if (app) appWm!! else wm
        val h = Host(this)
        if (app) {
            var ui = 0
            if (s.lightBar) ui = ui or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            @Suppress("DEPRECATION") run { h.systemUiVisibility = ui }
        }
        val p = windowParams(app, s, hidden = stage)
        try { mgr.addView(h, p) } catch (e: Exception) {
            Log.w(TAG, "overlay window refused ($e)"); Dbg.log("overlay window refused: $e")
            return
        }
        host = h; lp = p; hostWm = mgr; hostIsApp = app; staged = stage
        addLevel(s)
        if (!stage) anchorHome()
        if (stage) Log.i(TAG, "overlay staged ${armAt - SystemClock.uptimeMillis()} ms before the hold ends (app window: $app)")
        else { revealedAt = SystemClock.uptimeMillis(); levels.lastOrNull()?.view?.let { watchFirstFrame(it); it.start() }; Log.i(TAG, "overlay shown ${SystemClock.uptimeMillis() - showAskedAt} ms after trigger (app window: $app)") }
    }

    private fun watchFirstFrame(v: OverlayView) {
        val asked = showAskedAt
        v.onFirstFrame = { Log.i(TAG, "first animation frame committed ${SystemClock.uptimeMillis() - asked} ms after trigger") }
    }

    /** Switches the staged window on. */
    private fun reveal() {
        val h = host ?: return; val p = lp ?: return
        staged = false
        p.alpha = 1f
        p.flags = p.flags and (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).inv()
        levels.lastOrNull()?.view?.let { watchFirstFrame(it); it.start() }
        runCatching { hostWm?.updateViewLayout(h, p) }
        anchorHome()
        revealedAt = SystemClock.uptimeMillis()
        Log.i(TAG, "overlay shown ${revealedAt - showAskedAt} ms after trigger (staged, app window: $hostIsApp)")
    }

    private fun addLevel(s: Shot) {
        val h = host ?: return
        val b = s.hw
        val v = OverlayView(this, levels.size, armedOcrModel)
        v.shot = b
        // The status bar is only repainted where it stays live above the overlay.
        if (hostIsApp) v.statusStrip = s.strip
        v.statusH = sbHeight
        levels.lastOrNull()?.let { below -> main.postDelayed({ if (levels.lastOrNull()?.view !== below.view) { below.view.visibility = android.view.View.GONE; below.sheet?.visibility = android.view.View.GONE } }, 120) }
        h.addView(v, android.widget.FrameLayout.LayoutParams(-1, -1))
        levels.add(Level(v, s))
        Dbg.log("level ${levels.size - 1} opened")
        view = v
        v.requestFocus()
        if (levels.size > 1) v.start()
        // The software copy was started when the screenshot arrived; this runs once it is there.
        prep.execute {
            val soft = s.soft ?: runCatching { b.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()?.also { s.soft = it } ?: return@execute
            main.post {
                if (levels.none { it.view === v }) return@post
                v.soft = soft
                if (hostIsApp && v.statusStrip == null) { v.statusStrip = s.strip; v.invalidate() }
                val bounds = wm.currentWindowMetrics.bounds
                val vw = if (v.width > 0) v.width else bounds.width(); val vh = if (v.height > 0) v.height else bounds.height()
                val t0 = SystemClock.uptimeMillis()
                ocr.run(soft, vw / soft.width.toFloat(), vh / soft.height.toFloat(), v.ocrModel) { r ->
                    Dbg.log("ocr ready: ${r.words.size} words in ${SystemClock.uptimeMillis() - t0} ms")
                    if (levels.any { it.view === v }) {
                        v.setOcr(r)
                        if (view === v) OcrPrompt.suggest(service, r.suggestedPacks) {
                            if (view === v && !soft.isRecycled) ocr.run(soft, vw / soft.width.toFloat(), vh / soft.height.toFloat(), v.ocrModel) { fresh ->
                                if (view === v) { v.setOcr(fresh); if (v.trOn) translateFrame(v, fresh, soft) }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Drops the top level and shows the one below it again, results sheet included. */
    private fun popLevel(why: String = "sheet swiped down") {
        if (levels.size < 2) return
        val top = levels.removeAt(levels.size - 1)
        Dbg.log("$why: back to level ${levels.size - 1}")
        top.sheet?.destroy()
        host?.removeView(top.view)
        val below = levels.last()
        below.view.visibility = android.view.View.VISIBLE
        below.sheet?.visibility = android.view.View.VISIBLE
        view = below.view
    }

    private fun levelOf(v: OverlayView) = levels.firstOrNull { it.view === v }
    fun sheetOf(v: OverlayView): Sheet? = levelOf(v)?.sheet?.takeIf { it.visibility == android.view.View.VISIBLE }

    private fun sheetFor(lv: Level): Sheet? {
        lv.sheet?.let { return it }
        val h = host ?: return null
        presetConsent()
        val sh = Sheet(this, onMoved = { top -> lv.view.setSheet(top) }, onClosed = {
            // Swiped down and gone: a stacked search shows the search under it again; the results of the first
            // level were the last thing open, so Circle Search is over (the app underneath is left as it is).
            if (levels.size > 1 && levels.last() === lv) popLevel()
            else if (lv.sheet?.searches == 0) { lv.sheet?.destroy(); lv.sheet = null; lv.view.resetToIdle() }   // only the empty search bar was open
            else if (levels.lastOrNull() === lv && !closing) dismiss()
        })
        // At the first level the whole overlay leaves together with the sheet, from the moment of the release:
        // no frozen screen is left standing for a moment after the sheet has gone.
        sh.onClosing = {
            if (levels.size == 1 && levels.last() === lv && sh.searches > 0) { Dbg.log("results swiped down at the first level (${sh.searches} searches): closing into the app"); dismiss() }
        }
        lv.sheet = sh
        sh.tagProvider = { lv.view.snapshot() }
        sh.attach(h, h.height)
        return sh
    }

    private var pendingSearch: Runnable? = null

    /**
     * Opens the sheet at once, but waits a moment before asking Google: selections often change several times
     * in a second (stroke, then handle drags), and a burst of searches gets Google's "unusual traffic" page.
     */
    fun searchText(v: OverlayView, q: String) {
        val lv = levelOf(v) ?: return
        val sh = sheetFor(lv) ?: return
        pendingSearch?.let { main.removeCallbacks(it) }
        sh.announce(q)
        val r = Runnable {
            if (levelOf(v) !== lv || lv.sheet !== sh) return@Runnable
            Dbg.log("search text (${q.length} chars) at level ${levels.indexOf(lv)}")
            sh.searchText(q)
        }
        pendingSearch = r
        main.postDelayed(r, 450)
    }

    /** Tap on the empty search bar of the idle overlay: the sheet opens with the bar ready for typing. */
    fun askText(v: OverlayView) {
        val lv = levelOf(v) ?: return
        val sh = sheetFor(lv) ?: return
        pendingSearch?.let { main.removeCallbacks(it) }
        sh.announce("")
        sh.startEdit()
    }

    fun closeSheet(v: OverlayView) {
        val lv = levelOf(v) ?: return
        lv.sheet?.destroy(); lv.sheet = null
        v.setSheet(null)
    }

    fun setSheetVisible(v: OverlayView, on: Boolean) {
        levelOf(v)?.sheet?.visibility = if (on) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    /** Google shows a cookie-consent wall to new browsers in Europe. Pre-answer it with "reject all". */
    private fun presetConsent() {
        val cm = android.webkit.CookieManager.getInstance()
        if (cm.getCookie("https://www.google.com")?.contains("SOCS=") == true) return
        cm.setCookie("https://www.google.com", "SOCS=CAESHAgBEhJnd3NfMjAyMzA4MTAtMF9SQzIaAmRlIAEaBgiAo_CmBg; Domain=.google.com; Path=/; Secure; Max-Age=33696000")
        cm.flush()
    }

    private fun removeWindows() {
        for (lv in levels) runCatching { lv.sheet?.destroy() }
        levels.clear()
        host?.let { runCatching { (hostWm ?: wm).removeViewImmediate(it) } }
        pill?.let { runCatching { wm.removeViewImmediate(it) } }
        AnchorActivity.current?.leave()
        host = null; view = null; pill = null; lp = null; hostWm = null; staged = false
        watching = false; frame++
        follower?.drop()
        shownRows = null
        main.removeCallbacks(watch)
    }

    /**
     * Back (the phone's own gesture, arriving as a key or a back callback). One gesture is one step, decided by
     * SelLogic.backStep (tested as a table): through the searches made, newest first, and at the first one Circle
     * Search closes. The app that was in front is never touched by it.
     */
    fun back(how: String) {
        val v = view
        val top = levels.lastOrNull(); val sh = top?.sheet
        // Translation on the frozen screen is one step of its own: back takes it off again.
        if (v != null && v.mode == OverlayView.Mode.FROZEN && v.trOn && !staged && !closing && sh == null) { Dbg.log("back ($how): translate off"); v.stopTranslate(); return }
        val step = SelLogic.backStep(staged, closing, v != null && v.mode != OverlayView.Mode.FROZEN, sh?.isEditing == true, v?.hasCard == true,
            sh?.canPageBack() == true, sh?.searches ?: 0, levels.size)
        Dbg.log("back ($how): ${step.name.lowercase().replace('_', ' ')}${if (sh != null) " (${sh.searches} searches, ${levels.size} level${if (levels.size == 1) "" else "s"})" else ""}")
        when (step) {
            SelLogic.Back.NONE -> {}
            SelLogic.Back.LEAVE_BAR -> sh?.stopEdit()
            SelLogic.Back.CLOSE_CARD -> v?.stepBack()
            SelLogic.Back.PAGE_BACK -> sh?.goBack()
            SelLogic.Back.PREVIOUS_SEARCH -> { val e = sh?.previousSearch(); pendingSearch?.let { main.removeCallbacks(it) }; (e?.tag as? OverlayView.Snap)?.let { v?.restore(it) } }
            SelLogic.Back.PREVIOUS_LEVEL -> popLevel("back ($how)")
            SelLogic.Back.CLOSE -> dismiss()
        }
    }

    private var closing = false

    fun dismiss() {
        main.removeCallbacks(fire)
        if (host == null && pill == null) return
        if (staged) { removeWindows(); setEvents(0); return }
        if (closing) return
        val h = host
        // A short fade instead of vanishing; scroll-translate mode (nothing but text on screen) just goes.
        if (h != null && view?.mode == OverlayView.Mode.FROZEN) {
            closing = true
            h.animate().alpha(0f).setDuration(130).withEndAction {
                if (host !== h) { closing = false; return@withEndAction }
                val p = lp
                if (p != null && hostIsApp) {
                    p.alpha = 0f; p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    runCatching { hostWm?.updateViewLayout(h, p) }
                    main.postDelayed({ if (host === h && closing) { closing = false; removeWindows(); setEvents(0) } }, 320)
                } else { closing = false; removeWindows(); setEvents(0) }
            }.start()
            return
        }
        closing = false
        removeWindows()
        setEvents(0)
    }

    /** A new hold while the last overlay is still in its closing moment: finish that first. */
    private fun finishClosing() {
        if (!closing) return
        closing = false
        host?.animate()?.cancel()
        removeWindows(); setEvents(0)
    }

    fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Circle Search", text))
    }

    fun pinToBack(v: OverlayView, text: String): String? {
        if (text.isBlank()) return null
        val rect = v.selectionOnScreen()
        val send = { Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .putExtra("dev.chicken.extra.FROM_OVERLAY", true).also { i -> rect?.let {
                i.putExtra("dev.chicken.extra.SELECTION_RECT", it)
                i.putExtra("dev.chicken.rear.extra.TEXT_RECT", intArrayOf(it.left, it.top, it.right, it.bottom))
            } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION) }
        return runCatching {
            startActivity(send().setClass(this, dev.kb896.aiusage.ui.PinActivity::class.java))
            packageName
        }.getOrNull()
    }

    fun voiceSearch() {
        try { startActivity(Intent(Intent.ACTION_VOICE_COMMAND).setPackage(G).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); main.postDelayed({ dismiss() }, 250) }
        catch (e: Exception) { view?.setStatus("Voice search is not available") }
    }

    fun songSearch() {
        try { startActivity(Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH").setPackage(G).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); main.postDelayed({ dismiss() }, 250) }
        catch (e: Exception) { view?.setStatus("Song search is not available") }
    }

    /**
     * Picture search for the region [r] (view coordinates) of a level's frozen screen. The crop is uploaded to
     * Google Lens' web endpoint and the results open in the sheet; if that does not work, the Lens app gets the
     * crop instead (which leaves the overlay).
     */
    fun searchImage(v: OverlayView, r: RectF) {
        val lv = levelOf(v) ?: return
        bg.execute {
            var soft = v.soft
            var n = 0
            while (soft == null && n++ < 40) { Thread.sleep(15); soft = v.soft }
            val s = soft ?: return@execute
            val kx = s.width / v.width.toFloat(); val ky = s.height / v.height.toFloat()
            val x = (r.left * kx).toInt().coerceIn(0, s.width - 2); val y = (r.top * ky).toInt().coerceIn(0, s.height - 2)
            val w = (r.width() * kx).toInt().coerceIn(1, s.width - x); val h = (r.height() * ky).toInt().coerceIn(1, s.height - y)
            val crop = Bitmap.createBitmap(s, x, y, w, h)
            val dir = File(cacheDir, "lens").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, "crop_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, 93, it) }
            main.post { pendingSearch?.let { main.removeCallbacks(it) }; if (levelOf(v) === lv) sheetFor(lv)?.showImage(crop) }
            val url = runCatching { lensUpload(f.readBytes()) }.onFailure { Log.w(TAG, "lens upload failed: $it") }.getOrNull()
            main.post {
                if (levelOf(v) !== lv) return@post
                if (url != null) lv.sheet?.imageResults(url) else lensApp(v, f)
            }
        }
    }

    /** POSTs the crop to lens.google.com and returns the results URL it redirects to. */
    private fun lensUpload(jpeg: ByteArray): String? {
        val cm = android.webkit.CookieManager.getInstance()
        val boundary = "----circlesearch${System.nanoTime()}"
        val lang = java.util.Locale.getDefault().language
        val c = java.net.URL("https://lens.google.com/v3/upload?hl=$lang&st=${System.currentTimeMillis()}&ep=gsbubb").openConnection() as java.net.HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 8000; c.readTimeout = 15000
            c.setRequestProperty("User-Agent", android.webkit.WebSettings.getDefaultUserAgent(this))
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            cm.getCookie("https://lens.google.com")?.let { c.setRequestProperty("Cookie", it) }
            c.outputStream.use { o ->
                o.write("--$boundary\r\nContent-Disposition: form-data; name=\"encoded_image\"; filename=\"crop.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                o.write(jpeg)
                o.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = c.responseCode
            c.headerFields["Set-Cookie"]?.forEach { cm.setCookie("https://lens.google.com", it) }
            val loc = c.getHeaderField("Location")
            Dbg.log("lens upload: HTTP $code, redirect ${loc != null}")
            return loc?.takeIf { code in 300..399 && android.net.Uri.parse(it).scheme == "https" }
        } finally { c.disconnect() }
    }

    private fun lensApp(v: OverlayView, f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.pins", f)
        val i = Intent(Intent.ACTION_SEND)
            .setClassName(G, "com.google.android.apps.search.lens.LensShareEntryPointActivity")
            .setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("crop", uri)
        try { startActivity(i) } catch (e: Exception) { Log.e(TAG, "lens start failed", e); levelOf(v)?.sheet?.imageFailed("Google Lens is not available"); return }
        main.postDelayed({ dismiss() }, 380)
    }

    private var watching = false
    private var lastScrollAt = 0L
    private var unstableSince = 0L
    private var translating = false
    private var looking: OverlayView? = null
    private var adoptNext = false
    private var shownFromWindow = false
    private var windowMode = false
    private var windowFails = 0
    private var windowRetryAt = 0L

    /** Translate pressed on the frozen screen: the translation is laid over the screenshot, nothing else changes. */
    fun translateInPlace(v: OverlayView) {
        val o = v.ocr ?: return
        val s = v.soft ?: return
        if (v.mode != OverlayView.Mode.FROZEN || staged) return
        setEvents(0, listWindows = true)
        Dbg.log("translate on (frozen screen, ${o.blocks.size} blocks)")
        translateFrame(v, o, s)
    }

    /** Translate switched off on the frozen screen: whatever is still on its way is dropped. */
    fun stopInPlace(v: OverlayView) {
        if (view !== v) return
        frame++
        Tr.cancelPending()
        setEvents(0)
    }

    /**
     * "Scroll and translate": the frozen overlay becomes a touch-through layer that only draws translations, and
     * the floating "Translating" pill appears. The translation that is on the screen stays there.
     */
    fun enterScroll(v: OverlayView) {
        val p = lp ?: return
        val o = v.ocr ?: return
        val s = v.soft ?: return
        if (staged) return
        AnchorActivity.current?.leave()
        v.mode = OverlayView.Mode.PASS
        val old = host ?: return
        if (hostIsApp) {
            // Touches may only pass through a trusted window: move the view into an accessibility overlay.
            val nh = Host(this)
            val np = windowParams(app = false, s = null, hidden = false)
            np.flags = np.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            old.removeView(v)
            v.statusStrip = null
            // The old window keeps showing the same translation until the new one has put its first frame on the
            // screen; otherwise the original text shows through for a frame or two in between.
            val oldWm = hostWm
            old.addView(object : android.view.View(this) { override fun onDraw(c: android.graphics.Canvas) { v.drawTranslation(c) } },
                android.widget.FrameLayout.LayoutParams(-1, -1))
            nh.addView(v, android.widget.FrameLayout.LayoutParams(-1, -1))
            wm.addView(nh, np)
            var gone = false
            val drop = Runnable { if (!gone) { gone = true; runCatching { oldWm?.removeViewImmediate(old) } } }
            v.afterDraw = { main.postDelayed(drop, 34) }
            main.postDelayed(drop, 400)
            host = nh; lp = np; hostWm = wm; hostIsApp = false
        } else {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            wm.updateViewLayout(old, p)
        }
        v.invalidate()

        val pv = PillView(this)
        val pp = WindowManager.LayoutParams(
            pv.wantW, pv.wantH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        pp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        pp.y = -pv.wantH          // below the screen edge; it rises into place
        pp.windowAnimations = 0
        pp.title = "CircleSearchPill"
        pv.params = pp
        wm.addView(pv, pp)
        pill = pv
        pv.rise()

        setEvents(AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        windowMode = android.os.Build.VERSION.SDK_INT >= 34
        windowFails = 0; windowRetryAt = 0L
        lastSig = null; unstableSince = 0L; lastScrollAt = 0L; lastTouch = 0L; translating = false
        shownSig = if (v.patchCount > 0) signature(s) else null
        adoptNext = true
        watching = true
        if (windowMode) {
            val tracking = follower ?: ScrollFollow(service, prefs).also { follower = it }
            tracking.onChange = { view?.invalidate() }
            tracking.onMotion = { Dbg.log(it) }
            v.follow = tracking.out
        }
        Dbg.log("scroll and translate on (window capture: $windowMode, ${v.patchCount} translations kept)")
        if (v.patchCount > 0) {
            pv.setLangs(lastSrc, lastTgt)
            anchorSet(v)
        } else if (!v.collectingPatches && !windowMode) translateFrame(v, o, s)
        soon(50)
    }

    private fun translateFrame(v: OverlayView, o: OcrResult, s: Bitmap, readAt: Long? = null) {
        val g = ++frame
        Tr.cancelPending()
        v.beginPatches()
        val vw = v.width
        val vh = v.height
        val xy = IntArray(2).also { v.getLocationOnScreen(it) }
        val shot = levels.firstOrNull { it.view === v }?.shot
        val statusBar = if (shot?.statusShown != false) sbHeight else 0
        val frozen = v.mode == OverlayView.Mode.FROZEN
        layoutExec.execute {
            val blocks = TrBlocks.split(o.blocks, s, vw, vh)
            val page = if (frozen) shot?.page ?: TrBlocks.screenArea(service, statusBar)?.also { shot?.page = it } else null
            val clips = TrBlocks.clips(service, xy, vw, vh, blocks, statusBar, page)
            val visible = blocks.filter { it in clips }
            val plan = v.sizePlan(visible, s, clips)
            val rows = rowsOf(s)
            main.post {
                if (g != frame || view !== v) {
                    if (readAt != null) s.recycle()
                    return@post
                }
                v.applySizes(plan)
                translateBlocks(v, s, g, visible, rows, readAt)
            }
        }
    }

    private fun translateBlocks(v: OverlayView, source: Bitmap, generation: Int, blocks: List<Block>,
                                rows: ShiftLogic.Rows?, readAt: Long?) {
        pill?.setBusy(true)
        val startTime = SystemClock.uptimeMillis()
        val replaceLive = v.mode == OverlayView.Mode.PASS && v.patchCount > 0
        var unavailable = false
        var first = true
        fun current() = generation == frame && view === v
        fun stale() = v.mode == OverlayView.Mode.PASS && lastScrollAt > (readAt ?: startTime)
        fun publish() {
            if (v.collectingPatches) {
                v.commitPatches()
                shownRows = rows
            }
            if (v.mode == OverlayView.Mode.PASS) anchorSet(v)
        }
        val publishAvailable = Runnable {
            if (current() && !stale() && v.hasPendingPatches) publish()
        }
        if (replaceLive) main.postDelayed(publishAvailable, 900)
        Tr.screen(blocks, sessionTarget ?: target(), explicitTarget || sessionTarget != null,
            active = ::current,
            start = { dom, eff ->
                if (current()) {
                    if (sessionTarget == null) sessionTarget = eff
                    lastSrc = if (dom == eff) "und" else dom
                    lastTgt = eff
                    pill?.setLangs(lastSrc, lastTgt)
                    v.setLangs(lastSrc, lastTgt)
                }
            },
            each = { block, translated ->
                if (current() && !stale()) {
                    if (first) {
                        first = false
                        Dbg.log("first translated text ${SystemClock.uptimeMillis() - startTime} ms after layout")
                    }
                    v.addPatch(block, translated, source)
                }
            },
            ready = { if (current() && !replaceLive && !stale()) publish() },
            failed = { unavailable = true },
            done = {
                main.removeCallbacks(publishAvailable)
                if (readAt != null) source.recycle()
                if (current()) {
                    val stale = stale()
                    if (stale || replaceLive && unavailable && v.collectingPatches && !v.hasPendingPatches) {
                        v.discardPendingPatches()
                        shownSig = null
                        if (stale) soon(190) else {
                            Dbg.log("translation unavailable; previous patches retained")
                            soon(1800)
                        }
                    } else publish()
                    pill?.setBusy(false)
                    Dbg.log("translation completed (${v.patchCount} patches)")
                }
            })
    }

    // ---- translations follow the page when it has moved ----
    private var shownRows: ShiftLogic.Rows? = null     // the picture the translations on screen were made from
    private fun rowsOf(b: Bitmap): ShiftLogic.Rows? {
        if (b.isRecycled || b.width < 96 || b.height < 96) return null
        val h = b.height; val k = ShiftLogic.K; val sw = 8
        val f = ByteArray(h * k); val px = IntArray(sw * h)
        for (i in 0 until k) {
            val x = (((i + 0.5f) * b.width / k).toInt() - sw / 2).coerceIn(0, b.width - sw)
            b.getPixels(px, 0, sw, x, 0, sw, h)
            for (r in 0 until h) {
                var s = 0
                for (j in 0 until sw) { val c = px[r * sw + j]; s += (c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255) }
                f[r * k + i] = (s / (10 * sw)).toByte()
            }
        }
        return ShiftLogic.Rows(h, f)
    }

    /**
     * The page has come to rest somewhere else. Translations whose text is still on the screen are moved to where
     * it is now and shown at once; reading the screen again then only has to add what is new. Returns how many stayed.
     */
    private fun follow(v: OverlayView, bmp: Bitmap): Int {
        val old = shownRows
        val had = v.patchCount
        val live = follower?.out?.takeIf { it.list === v.patchSet }?.dy?.maxByOrNull { abs(it) } ?: 0f
        if (old == null || had == 0) return 0
        val t0 = SystemClock.uptimeMillis()
        val new = rowsOf(bmp)
        if (new == null || new.h != old.h) return 0
        val dy = ShiftLogic.shift(old, new)
        if (dy == null || dy == 0) return 0
        // The part of the screen that moved. A translation that would reach out of it (under a fixed bar, past the
        // bottom of a list) is dropped: it comes back with the next reading, cut the way its text is cut now.
        val moved = ShiftLogic.region(old, new, dy)
        val ky = bmp.height / v.height.toFloat()
        // A capture taken near the start of a fling can arrive after the tracked nodes have moved farther.
        // Keep the live anchor until the image catches up, so the patches do not jump back to older positions.
        if (abs(live * ky) > abs(dy) + 8 * resources.displayMetrics.density * ky) return 0
        val slack = (5 * resources.displayMetrics.density * ky).toInt()      // a translation's box is a little taller than its text
        val kept = v.shiftPatches(retainWhenNone = true) { top, bottom ->
            var up: Int? = null
            for (cand in listOf(dy, 0)) {
                val nt = (top * ky).toInt() - cand; val nb = (bottom * ky).toInt() - cand
                if (nt < 0 || nb > new.h) continue                      // has left the screen (or would only be half on it)
                if (cand != 0 && (moved == null || nt < moved.first - slack || nb > moved.last + slack)) continue
                if (ShiftLogic.match(old, new, nt, nb, cand) >= 0.8f) { up = (cand / ky).toInt(); break }
            }
            up
        }
        if (kept == 0) return 0
        follower?.drop()  // The verified offsets and the new patch positions take effect together.
        shownRows = new
        shownSig = null
        Dbg.log("page moved $dy px: $kept of $had translations moved with it (${SystemClock.uptimeMillis() - t0} ms)${if (live != 0f) ", followed live to ${live.toInt()} px" else ""}")
        if (kept > 0 && view === v) anchorSet(v)
        return kept
    }

    private fun anchorSet(v: OverlayView) {
        val tracker = follower ?: return
        if (v.mode != OverlayView.Mode.PASS || view !== v || v.patchCount == 0) tracker.drop()
        else tracker.anchor(v.patchSet, v.patchBoxes()) { Dbg.log("follow: $it") }
    }

    fun toggleTarget() {
        val t = Tr.other(sessionTarget ?: pill?.target ?: target())
        setTarget(t); explicitTarget = true; sessionTarget = t
        shownSig = null
        frame++
        Tr.cancelPending()
        Dbg.log("target switched to $t")
        val v = view
        if (v != null && v.mode == OverlayView.Mode.FROZEN && v.trOn) translateInPlace(v) else soon(60)
    }

    private fun soon(ms: Long) { if (watching) { main.removeCallbacks(watch); main.postDelayed(watch, ms) } }

    /** A touch is a reason to look sooner, never evidence that the displayed pixels changed. */
    fun userTouch() {
        if (view?.mode != OverlayView.Mode.PASS) return
        lastTouch = SystemClock.uptimeMillis()
        if (windowMode) follower?.wake(700)
        soon(160)
    }

    fun kick(scroll: Boolean) {
        if (view?.mode != OverlayView.Mode.PASS) return
        if (scroll) {
            lastScrollAt = SystemClock.uptimeMillis()
            if (windowMode) follower?.scrolled()
        }
        // Keep the periodic look during noisy event streams. Its pixel comparison decides what changed.
        if (watching && !main.hasCallbacks(watch) && looking == null && !translating) soon(190)
    }

    /** Capture the front application's surface. Both Circle Search windows stay on screen. */
    @androidx.annotation.RequiresApi(34)
    private fun captureWindow(cb: (Result<Bitmap>) -> Unit) {
        val win = runCatching {
            val apps = service.windowsOnAllDisplays[Display.DEFAULT_DISPLAY].orEmpty()
                .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive } ?: apps.firstOrNull()
        }.getOrNull()
        if (win == null) { cb(Result.failure(IllegalStateException("no front application window"))); return }
        val bounds = Rect()
        win.getBoundsInScreen(bounds)
        val dm = wm.currentWindowMetrics.bounds
        runCatching {
            service.takeScreenshotOfWindow(win.id, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    val hb = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    r.hardwareBuffer.close()
                    lastShotAt = SystemClock.uptimeMillis()
                    if (closed) { hb?.recycle(); return }
                    if (hb == null) { cb(Result.failure(IllegalStateException("empty window capture"))); return }
                    bg.execute {
                        val result = runCatching {
                            val copy = try { hb.copy(Bitmap.Config.ARGB_8888, false) } finally { hb.recycle() }
                            if (copy.width == dm.width() && copy.height == dm.height() && bounds.left == 0 && bounds.top == 0) copy
                            else Bitmap.createBitmap(dm.width(), dm.height(), Bitmap.Config.ARGB_8888).also {
                                android.graphics.Canvas(it).drawBitmap(copy, null, bounds, null)
                                copy.recycle()
                            }
                        }
                        main.post { cb(result) }
                    }
                }
                override fun onFailure(code: Int) {
                    cb(Result.failure(IllegalStateException(if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) "interval" else "window capture error $code")))
                }
            })
        }.onFailure { cb(Result.failure(it)) }
    }

    private fun look(v: OverlayView, cb: (Bitmap?) -> Unit) {
        if (windowMode && android.os.Build.VERSION.SDK_INT >= 34) {
            captureWindow { result ->
                if (view === v && watching) {
                    if (result.isSuccess) windowFails = 0
                    else if (result.exceptionOrNull()?.message != "interval" && ++windowFails >= 3) {
                        // Whole-display captures would include the translations. Keep retrying the clean source
                        // while they are visible and use the display fallback only on a screen with no patches.
                        windowRetryAt = SystemClock.uptimeMillis() + 4000
                        windowMode = false
                        Dbg.log("window capture unavailable (${result.exceptionOrNull()?.message}); translations retained")
                    }
                }
                cb(result.getOrNull())
            }
            return
        }
        val pillBox = pill?.let { pv ->
            val xy = IntArray(2)
            pv.getLocationOnScreen(xy)
            Rect(xy[0], xy[1], xy[0] + pv.width, xy[1] + pv.height)
        }
        shoot { b ->
            if (b == null) { cb(null); return@shoot }
            bg.execute {
                val soft = b.copy(Bitmap.Config.ARGB_8888, true)
                b.recycle()
                if (pillBox != null && pillBox.intersect(0, 0, soft.width, soft.height)) {
                    val c = soft.getPixel(pillBox.left, (pillBox.top - 4).coerceAtLeast(0))
                    android.graphics.Canvas(soft).drawRect(pillBox, android.graphics.Paint().apply { color = c })
                }
                main.post { cb(soft) }
            }
        }
    }

    private val watch = object : Runnable {
        override fun run() {
            val v = view ?: return
            if (!watching || v.mode != OverlayView.Mode.PASS) return
            val now = SystemClock.uptimeMillis()
            if (windowRetryAt != 0L && now >= windowRetryAt) {
                windowMode = true; windowRetryAt = 0L; windowFails = 0; lastSig = null
            }
            if (looking != null || translating || shooting || (!windowMode && (v.patchCount > 0 || v.trOn && v.collectingPatches))) {
                soon(200); return
            }
            val delay = 345 - (now - lastShotAt)
            if (delay > 0) { soon(delay); return }

            looking = v
            val clean = windowMode
            val sampleStarted = now
            look(v) { bmp ->
                looking = null
                if (view !== v || !watching) { bmp?.recycle(); return@look }
                if (bmp == null) { soon(380); return@look }
                if (!clean && v.patchCount > 0) { bmp.recycle(); soon(380); return@look }

                val t = SystemClock.uptimeMillis()
                val sig = signature(bmp)
                val previous = lastSig
                val rested = previous != null && same(previous, sig)
                val unchanged = (adoptNext || clean == shownFromWindow) && shownSig?.let { same(it, sig) } == true
                lastSig = sig
                if (unchanged) {
                    if (adoptNext) Dbg.log("live page matches frozen translation")
                    adoptNext = false
                    shownFromWindow = clean
                    shownSig = sig
                    unstableSince = 0L
                    bmp.recycle(); soon(380); return@look
                }
                // Reposition verified matches as soon as a capture shows a scroll. Keep the current set
                // when there is no reliable offset, then replace it after the next OCR and translation.
                val tracking = follower?.out?.let { it.live && it.list === v.patchSet && it.moved() } == true
                if (lastScrollAt > sampleStarted) { bmp.recycle(); soon(190); return@look }
                if (!tracking || t - lastScrollAt >= 260) follow(v, bmp)
                if (unstableSince == 0L) unstableSince = t
                val scrollStopped = t - lastScrollAt in 260..1500
                if (!adoptNext && !rested && !scrollStopped && t - unstableSince <= 2500) {
                    bmp.recycle(); soon(345); return@look
                }

                adoptNext = false; unstableSince = 0L
                translating = true
                val gen = ++frame
                val readAt = t
                Dbg.log("reading changed content (${if (clean) "window" else "screen"} capture)")
                ocr.run(bmp, v.width / bmp.width.toFloat(), v.height / bmp.height.toFloat(), v.ocrModel) { r ->
                    if (view !== v || !watching) { bmp.recycle(); return@run }
                    translating = false
                    if (r.valid && lastScrollAt <= readAt && frame == gen) {
                        shownSig = sig
                        shownFromWindow = clean
                        v.showOcrFallback(r)
                        translateFrame(v, r, bmp, readAt)
                        OcrPrompt.suggest(service, r.suggestedPacks) { shownSig = null }
                    } else bmp.recycle()
                    soon(345)
                }
            }
        }
    }

    private val sigW = 144
    private val sigH = 312

    private fun signature(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, sigW, sigH, true)
        val px = IntArray(sigW * sigH)
        s.getPixels(px, 0, sigW, 0, 0, sigW, sigH)
        if (s !== b) s.recycle()
        return px
    }

    private fun same(a: IntArray, b: IntArray): Boolean =
        Look.sameFrame(a, b, sigW, sigH, top = 16, bottom = 10, right = 5)

    fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (view?.mode != OverlayView.Mode.PASS) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (e.packageName != packageName) kick(true)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (e.packageName != packageName && SystemClock.uptimeMillis() - lastTouch < 2500) kick(false)
            // Our own overlay windows come and go; only other apps' window changes matter.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (e.packageName != packageName) kick(false)
        }
    }
}
