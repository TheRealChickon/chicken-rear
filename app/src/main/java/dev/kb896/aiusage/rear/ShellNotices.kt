package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import dev.kb896.aiusage.data.GlassStyle
import dev.kb896.aiusage.data.PanelTheme
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The notifications over a guest or the panel, whichever page it is on, drawn and driven by the
 * shell service, as ShellControlCenter is the control centre: [touch] takes the top edge's right half and every finger while it is down,
 * and the sheet is a ShellOverlay with the blocker (no blur: the pages are opaque). What is on it is NoticePages:
 * each notification a whole page drawn as its pop-up is, and a line of the apps' logos (before that a NoticeShade's
 * rows of NoticeCards). The list is the app's (NotificationRelay through BackNotices, whole, with every change); a
 * page thrown out sideways goes back by [onDismissed].
 *
 * [pop] is the other half: one notification as it arrives, a banner over the top of the rear that
 * slides in and leaves by itself, on a trusted layer of its own so touches go through it to the app.
 *
 * THREADING as ShellControlCenter's: [lock] guards the model, [touch] runs on the reader thread,
 * the overlay's render thread reads under [lock] once a frame, [clock] runs begin() and end().
 */
class ShellNotices(
    private val layerStack: () -> Int,
    private val geometry: () -> RearGeometry,
    private val onDismissed: (String) -> Unit,
    private val onProgress: (Float) -> Unit,
    /** The back's lock is on and their words are not to show (BackLock, PanelConfig.lockedNoticeText). */
    private val redact: () -> Boolean = { false },
    /** A row tapped while its words are hidden: ask for the code. */
    private val onLockedTap: () -> Unit = {},
    /** Button [index] of the notification of [key] pressed on the back. */
    private val onAction: (key: String, index: Int) -> Unit = { _, _ -> },
    /** The pinned apps for the dock along the list's foot (companion mode); null for no dock. */
    private val dock: () -> PortalAppsSnapshot? = { null },
    /** A dock icon tapped: its package. The list has been shut. */
    private val onDockTap: (String) -> Unit = {},
    /** The pages have come up (a pull began), or are gone: for the navigation (RearNav). May be called under this one's lock: it must only post. */
    private val onUpChanged: (Boolean) -> Unit = {},
) {

    // -- the dock: the pinned apps along the foot of the list ---------------------------------
    //
    // Over Xiaomi's rear screen the pull down from the top is the one edge left to this app, and
    // the notifications have it; so the apps picked for the back ride on their list, as one row
    // that stays put while the rows above it scroll. A tap on one shuts the list and opens it.

    /** The dock's icon boxes for [n] apps, in panel units: left edges, the row's top, the icon's size. */
    private fun dockBoxes(n: Int): Triple<FloatArray, Float, Float> {
        val left = geometry().cutoutUnits + 10f
        val right = NoticeShade.RIGHT
        val shown = n.coerceAtMost(DOCK_MAX)
        val gap = DOCK_GAP
        val size = minOf(DOCK_ICON, (right - left - DOCK_PAD * 2 - gap * (shown - 1).coerceAtLeast(0)) / shown.coerceAtLeast(1))
        val span = size * shown + gap * (shown - 1).coerceAtLeast(0)
        val x0 = (left + right - span) / 2f
        return Triple(FloatArray(shown) { x0 + it * (size + gap) }, dev.kb896.aiusage.Panel.HEIGHT - DOCK_BOTTOM - size, size)
    }

    /** Which dock icon is at [x], [y], or -1. */
    private fun dockAt(x: Float, y: Float): Int {
        val apps = dock() ?: return -1
        if (apps.size == 0) return -1
        val (lefts, top, size) = dockBoxes(apps.size)
        if (y < top - DOCK_SLOP || y > top + size + DOCK_SLOP) return -1
        for (i in lefts.indices) if (x >= lefts[i] - DOCK_GAP / 2f && x <= lefts[i] + size + DOCK_GAP / 2f) return i
        return -1
    }

    private var dockTapped: String? = null
    private var dockFinger = -1
    private var dockDownX = 0f
    private var dockDownY = 0f
    private var dockDownAt = 0L


    /** [item] as the back may show it now: whose it is, and "New notification" while [redact]. */
    private fun shown(item: NoticeShade.Item): NoticeShade.Item =
        if (redact()) item.copy(title = LOCKED_NOTICE, text = "", actions = emptyList()) else item
    private val lock = Any()
    private val model by lazy { NoticePages(geometry().cutoutUnits) }
    private var owns = false
    private var downY = 0f
    @Volatile private var up = false
        set(value) { if (field != value) { field = value; runCatching { onUpChanged(value) } } }
    private var beginPending = false
    @Volatile private var overlay: ShellOverlay? = null
    private val making = Any()
    @Volatile private var face: PanelType.Face? = null
    @Volatile private var look = NoticeCard.Look(PanelTheme.GLASS, 0xFFFFFFFF.toInt(), 0xFF1D1F21.toInt(), SheetTint.of(true, null))
    private var shutCheck: ScheduledFuture<*>? = null
    @Volatile private var icons: Map<String, Bitmap> = emptyMap()
    private val iconCache = HashMap<String, Bitmap>()

    private val clock = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "notices-clock").apply { isDaemon = true }
    }

    /** Up, or on its way: for the touch reader to read with no lock. */
    val isUp: Boolean get() = up

    /** The panel's face; nothing opens before it is known. The first one makes the sheet. */
    fun face(f: PanelType.Face) {
        face = f
        overlayOrNull()?.changed()
    }

    /** The notifications' look as it is: for a button drawn as theirs elsewhere (ShotPolaroid's bin). */
    fun lookNow(): NoticeCard.Look = look

    /** The panel's theme, Glass settings' style, and a notification's colours (PanelConfig.notificationTint, notificationGround). */
    fun theme(theme: PanelTheme, style: GlassStyle?, text: Int, ground: Int) {
        look = NoticeCard.Look(theme, text, ground, SheetTint.of(theme.isGlass, style))
        overlay?.changed()
    }


    /** The whole list, newest first, as [encode] wrote it. Any thread. */
    fun items(bundle: Bundle?): List<NoticeShade.Item> {
        val list = decode(bundle)
        synchronized(iconCache) {
            for (item in list) if (item.pkg !in iconCache) {
                bundle?.getByteArray(ICON + item.pkg)?.let { bytes ->
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()?.let { iconCache[item.pkg] = it }
                }
            }
            icons = HashMap(iconCache)
        }
        val none = synchronized(lock) { real = list; if (!demo) model.items(list); up && !demo && list.isEmpty() }
        overlay?.changed()
        // Open while the last of them goes on the phone: the line that says so does not stay for good.
        if (none) runCatching { clock.schedule({ shutIfNone() }, EMPTY_MS, TimeUnit.MILLISECONDS) }
        return list
    }

    /** The list as the app last gave it, kept while a debug build's made-up list stands in its place ([demo]). */
    private var real: List<NoticeShade.Item> = emptyList()
    private var demo = false

    /**
     * A debug build's made-up list in the real one's place, for a look at the list with no notification of his
     * on it; null puts his own back. His list goes on being kept meanwhile, and nothing of it is lost.
     */
    fun demo(list: List<NoticeShade.Item>?, logosFirst: Boolean = false) {
        synchronized(lock) {
            demo = list != null
            model.logosFirst = list != null && logosFirst
            model.items(list ?: real)
        }
        overlay?.changed()
    }

    /** An app's own icon for a made-up notification of it (a debug build's demo), where its real ones have given none. */
    fun lend(pkg: String, icon: Bitmap) {
        synchronized(iconCache) {
            if (pkg in iconCache) return
            iconCache[pkg] = icon
            icons = HashMap(iconCache)
        }
    }

    /** See ShellControlCenter.touch: the same, for the top edge's other half. */
    fun touch(e: RearTouches.Event, actions: List<EdgeGestures.Action>, opens: () -> Boolean): List<EdgeGestures.Action> {
        val effects = ArrayList<NoticeShade.Effect>()
        val rest = ArrayList<EdgeGestures.Action>()
        var begin = false
        var heal = false
        var nothing = false
        synchronized(lock) {
            val now = e.atMs
            if (e is RearTouches.Down) {
                downY = e.y
                if (up && !beginPending && overlay?.up != true) {
                    Log.w(TAG, "notices: their layers are gone — shut")
                    up = false; model.reset(); stopTimers()
                }
                owns = up && model.shown(now)
                dockFinger = if (owns && model.progress(now) > 0.9f) dockAt(e.x, e.y) else -1
                if (dockFinger >= 0) { dockDownX = e.x; dockDownY = e.y; dockDownAt = now }
                else if (owns) model.fingerDown(e.x, e.y, now)
                // Shut, with its layers still up and no check left to come: whatever led here,
                // the blocker must not stay over the rear. The next finger takes it away.
                if (up && !beginPending && model.isShut(now)) heal = true
            }
            for (a in actions) when (a) {
                is EdgeGestures.Action.NoticePullStart -> if (!owns && opens() && overlay != null && face != null) {
                    model.beginPull(downY, a.y, now)
                    if (!up) { up = true; begin = true; beginPending = true }
                }
                is EdgeGestures.Action.NoticePullMove -> model.movePull(a.y, now)
                is EdgeGestures.Action.NoticePullEnd -> { effects += model.endPull(a.y, now); nothing = up && model.count == 0 }
                is EdgeGestures.Action.PullEnd, is EdgeGestures.Action.SlideEnd, EdgeGestures.Action.Cancel -> rest += a
                else -> if (!owns) rest += a
            }
            if (owns && dockFinger >= 0) {
                if (e is RearTouches.Up) {
                    val still = kotlin.math.abs(e.x - dockDownX) < EdgeGestures.SLOP && kotlin.math.abs(e.y - dockDownY) < EdgeGestures.SLOP
                    if (still && now - dockDownAt <= EdgeGestures.TAP_MS) {
                        dockTapped = dock()?.packages?.getOrNull(dockFinger)
                        if (dockTapped != null) effects += model.close(now)
                    }
                    dockFinger = -1
                    owns = false
                }
            } else if (owns) when (e) {
                is RearTouches.Move -> model.fingerMove(e.x, e.y, now)
                is RearTouches.Up -> { effects += model.fingerUp(e.x, e.y, now); owns = false }
                else -> Unit
            }
        }
        dockTapped?.let { pkg ->
            dockTapped = null
            runCatching { onDockTap(pkg) }.onFailure { Log.w(TAG, "notices: dock: ${it.message}") }
        }
        if (begin) { open(); banner.gone() }
        // Nothing waiting: the one line says so, and the back is the moon again by itself.
        if (nothing) runCatching { clock.schedule({ shutIfNone() }, EMPTY_MS, TimeUnit.MILLISECONDS) }
        if (heal) {
            Log.w(TAG, "notices: shut, with the blocker still up — taken away")
            runCatching { clock.execute { endIfShut() } }
        }
        if (synchronized(lock) { up }) overlay?.changed()
        effects.forEach(::carryOut)
        return rest
    }

    /** Shut at once, no settle (the rear going dark, the guest going), the banner with it. */
    /**
     * The back's lock came on or went: what is up drawn again now, its words hidden or shown — a
     * list left on screen as the phone locked kept showing them, since nothing else asked for a
     * frame (the owner, 2026-11-09). A banner up as it locks goes.
     */
    fun lockChanged() {
        overlay?.changed()
        if (redact()) banner.gone()
    }

    fun closeNow(why: String, sync: Boolean = false) {
        banner.gone()
        val o = synchronized(lock) {
            if (!up) return
            Log.i(TAG, "notices: shut — $why")
            up = false
            model.reset()
            stopTimers()
            overlay
        } ?: return
        if (sync) runCatching { o.end() } else runCatching { clock.execute { runCatching { o.end() } } }
    }

    private fun open() {
        val o = overlay
        if (o == null) { synchronized(lock) { up = false; beginPending = false; model.reset() }; return }
        val queued = runCatching {
            clock.execute {
                val ok = synchronized(lock) { up } && runCatching { o.begin() }.getOrElse {
                    Log.w(TAG, "notices: begin threw: ${it.message}"); false
                }
                synchronized(lock) {
                    beginPending = false
                    if (!ok) {
                        if (up) Log.i(TAG, "notices: could not come up — the pull does nothing")
                        up = false; model.reset(); stopTimers()
                    }
                }
                if (!ok) runCatching { o.end() }
            }
        }.isSuccess
        if (!queued) synchronized(lock) { up = false; beginPending = false; model.reset() }
    }

    private fun overlayOrNull(): ShellOverlay? {
        overlay?.let { return it }
        synchronized(making) {
            overlay?.let { return it }
            val made = runCatching { ShellOverlay(layerStack(), geometry(), "notices", "notices", Drawing(), blurs = false, z = ShellLayer.Z_PANES) }
                .onFailure { Log.w(TAG, "notices: no sheet: ${it.message}") }.getOrNull() ?: return null
            made.onProgress = onProgress
            overlay = made
            return made
        }
    }

    private fun carryOut(effect: NoticeShade.Effect) {
        when (effect) {
            is NoticeShade.Effect.Dismissed -> {
                runCatching { onDismissed(effect.key) }.onFailure { Log.w(TAG, "notices: dismiss: ${it.message}") }
                // The last one gone, there is nothing to stay open for.
                runCatching { clock.schedule({ shutIfNone() }, NoticeShade.FLY_MS, TimeUnit.MILLISECONDS) }
            }
            is NoticeShade.Effect.Tapped -> if (redact()) runCatching { onLockedTap() }
            is NoticeShade.Effect.Action -> if (redact()) runCatching { onLockedTap() } else runCatching { onAction(effect.key, effect.index) }
            is NoticeShade.Effect.ClearedAll -> {
                for (key in effect.keys) {
                    runCatching { onDismissed(key) }.onFailure { Log.w(TAG, "notices: dismiss: ${it.message}") }
                }
                // Shut once the last has risen away.
                runCatching { clock.schedule({ shutIfNone() }, (effect.doneAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS) }
            }
            is NoticeShade.Effect.ShutAt -> synchronized(lock) {
                shutCheck?.cancel(false)
                val delay = (effect.atMs - SystemClock.uptimeMillis()).coerceAtLeast(0L) + SHUT_GRACE_MS
                shutCheck = runCatching { clock.schedule({ endIfShut() }, delay, TimeUnit.MILLISECONDS) }.getOrNull()
            }
        }
    }

    /** Up with none left to show: shut, by its settle. */
    private fun shutIfNone() {
        val shut = synchronized(lock) {
            if (up && model.count == 0) model.close(SystemClock.uptimeMillis()) else emptyList()
        }
        shut.forEach(::carryOut)
        overlay?.changed()
    }

    private fun endIfShut() {
        val o = synchronized(lock) {
            if (!up || !model.isShut(SystemClock.uptimeMillis())) return
            up = false
            model.reset()
            stopTimers()
            overlay
        } ?: return
        runCatching { o.end() }.onFailure { Log.w(TAG, "notices: end failed: ${it.message}") }
    }

    private fun stopTimers() {
        shutCheck?.cancel(false); shutCheck = null
    }

    private fun whoOf(item: NoticeShade.Item, icon: Bitmap?): String = NoticePagesArt.who(item, icon)

    /** THE PAGES (NoticePages), drawn by NoticePagesArt; the pinned apps' icons at the foot where there are any. */
    private inner class Drawing : ShellOverlay.Content {
        private var scene: NoticePages.Scene? = null
        private var moving = false

        override fun frame(): Float = synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            scene = model.scene(now)
            moving = model.moving(now)
            model.progress(now)
        }

        override fun painter(): ShellOverlay.Painter {
            val art = NoticePagesArt()
            return ShellOverlay.Painter { canvas, progress ->
                if (face == null) return@Painter false
                val s = scene ?: return@Painter false
                // No words of buttons over the dock: its icons stand there (and what is not drawn is not pressed: NoticePages.words).
                val words = (dock()?.size ?: 0) == 0
                model.words = words
                art.draw(canvas, geometry(), s, progress, words = words, icon = { icons[it] }, shown = ::shown)
                drawDock(canvas, ((progress - 0.3f) / 0.7f).coerceIn(0f, 1f))
                moving
            }
        }
    }

    private val dockIcon = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dockRect = RectF()

    /** The pinned apps' row at the foot of the pages (companion mode, More apps): their own icons, nothing under them. */
    private fun drawDock(canvas: Canvas, arrive: Float) {
        val apps = dock() ?: return
        if (apps.size == 0 || arrive <= 0.01f) return
        val (lefts, top, size) = dockBoxes(apps.size)
        val a = (255 * arrive).toInt().coerceIn(0, 255)
        // No plate under them: the pages have no cards or boxes; the apps' own icons alone.
        dockIcon.alpha = a
        for (i in lefts.indices) {
            val icon = apps.icon(i) ?: continue
            dockRect.set(lefts[i], top, lefts[i] + size, top + size)
            canvas.drawBitmap(icon, null, dockRect, dockIcon)
        }
    }

    /** [item] as it arrives: the banner over the top of the app. Any thread. */
    fun pop(item: NoticeShade.Item) = banner.show(item)

    /** The pop-up taken away now (a stroke on it: RearNav). */
    fun popGone() = banner.gone()

    private val banner = Banner()

    /**
     * The banner: in, held, out by itself, on a thread of its own that lives only while it shows.
     * Trusted and so touch-transparent: the app under it goes on being used.
     */
    private inner class Banner {
        private val state = Any()
        private var item: NoticeShade.Item? = null
        private var shownAt = 0L
        private var thread: Thread? = null

        fun show(next: NoticeShade.Item) {
            // Never silently: a banner with no face is one the user was told to look for.
            if (face == null) { Log.w(TAG, "notices: banner dropped — the panel's font is not loaded"); return }
            synchronized(state) {
                val now = SystemClock.uptimeMillis()
                // Already fully in: this one takes its place without going out first.
                shownAt = if (item != null && now - shownAt in IN_MS until IN_MS + HOLD_MS) now - IN_MS else now
                item = next
                if (thread?.isAlive != true) {
                    thread = Thread({ run() }, "notice-banner").apply { isDaemon = true; start() }
                }
            }
        }

        fun gone() {
            synchronized(state) {
                item = null
                thread?.interrupt()
                thread = null
            }
        }

        private fun run() {
            var sc: SurfaceControl? = null
            var surface: Surface? = null
            try {
                val rear = geometry()
                // THE WHOLE BACK, as Xiaomi's pinned message has it (NoticePop): opaque, black at the top and
                // the app's colour glowing up from the bottom, who it is from and the message. It comes and
                // goes by its light alone; nothing slides.
                sc = ShellLayer.create(
                    "notice-banner", layerStack(), ShellLayer.Z_BANNER, rear.widthPx, rear.heightPx,
                    trusted = true, visible = false, alpha = 0f, format = android.graphics.PixelFormat.RGBA_8888, opaque = true,
                    corner = ShellLayer.pageCorner(rear),
                )
                surface = Surface(sc)
                var drawn: NoticeShade.Item? = null
                var alpha = -1f
                while (!Thread.currentThread().isInterrupted) {
                    val (now, it, at) = synchronized(state) { Triple(SystemClock.uptimeMillis(), item, shownAt) }
                    val t = now - at
                    if (it == null || t >= IN_MS + HOLD_MS + OUT_MS) break
                    val shown = when {
                        t < IN_MS -> { val p = 1f - t.toFloat() / IN_MS; 1f - p * p * p }
                        t < IN_MS + HOLD_MS -> 1f
                        else -> { val p = (t - IN_MS - HOLD_MS).toFloat() / OUT_MS; 1f - p * p }
                    }
                    if (it !== drawn) {
                        val item = shown(it)
                        val c = runCatching { surface.lockHardwareCanvas() }.getOrNull() ?: surface.lockCanvas(null)
                        try {
                            // Which app is said by its icon; its name is written only when there is no icon to say it.
                            val icon = icons[item.pkg]
                            NoticePop.draw(c, rear.widthPx.toFloat(), rear.heightPx.toFloat(), whoOf(item, icon), item.text, item.color, icon)
                        } finally { surface.unlockCanvasAndPost(c) }
                        drawn = it
                    }
                    if (alpha != shown) {
                        // IT COMES DOWN FROM THE TOP and goes back up: the whole page slides, at full strength, not a
                        // fade in place.
                        SurfaceControl.Transaction().use { tx ->
                            ShellLayer.round(tx, sc, rear, moving = shown < 1f)
                            tx.setPosition(sc, 0f, -(1f - shown) * rear.heightPx).setAlpha(sc, 1f).setVisibility(sc, shown > 0f).apply()
                        }
                        alpha = shown
                    }
                    Thread.sleep(if (shown < 1f) FRAME_MS else IDLE_MS)
                }
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                Log.w(TAG, "notice banner stopped: ${e.message}")
            } finally {
                runCatching { surface?.release() }
                ShellLayer.remove(sc)
                synchronized(state) { if (thread === Thread.currentThread()) { thread = null; item = null } }
            }
        }
    }

    companion object {
        /** With nothing waiting, how long the line that says so stays before the back is the moon again. */
        private const val EMPTY_MS = 1_300L
        private const val DOCK_MAX = 6
        private const val DOCK_ICON = 68f
        private const val DOCK_GAP = 20f
        private const val DOCK_PAD = 12f
        private const val DOCK_BOTTOM = 26f
        private const val DOCK_SLOP = 10f
        /** A notification's headline while the back is locked. */
        private const val LOCKED_NOTICE = dev.kb896.aiusage.service.NoticeFilter.LOCKED_TITLE
        private const val TAG = "AiUsageRear"
        private const val SHUT_GRACE_MS = 32L
        private val BANNER_H = NoticeShade.ROW_H
        /** The blur under the banner's card, SurfaceFlinger px: enough to quiet text behind it. */
        private const val BANNER_BLUR_PX = 30
        private const val IN_MS = 380L
        private const val HOLD_MS = 5_000L
        private const val OUT_MS = 420L
        /** A banner's whole time on the rear, in to out: for RearShow. */
        const val BANNER_MS = IN_MS + HOLD_MS + OUT_MS
        private const val FRAME_MS = 16L
        private const val IDLE_MS = 100L

        private const val KEYS = "keys"
        private const val PKGS = "pkgs"
        private const val APPS = "apps"
        private const val TITLES = "titles"
        private const val TEXTS = "texts"
        private const val TIMES = "times"
        private const val ACTIONS = "actions"
        private const val COLORS = "colors"
        /** Between one notification's buttons' words. */
        private const val SEP = "\u001F"
        private const val ICON = "icon:"

        /** The list for the binder, each app's icon once. */
        fun encode(items: List<NoticeShade.Item>, icons: Map<String, ByteArray>): Bundle = Bundle().apply {
            putStringArray(KEYS, items.map { it.key }.toTypedArray())
            putStringArray(PKGS, items.map { it.pkg }.toTypedArray())
            putStringArray(APPS, items.map { it.app }.toTypedArray())
            putStringArray(TITLES, items.map { it.title }.toTypedArray())
            putStringArray(TEXTS, items.map { it.text }.toTypedArray())
            putLongArray(TIMES, items.map { it.time }.toLongArray())
            putStringArray(ACTIONS, items.map { it.actions.joinToString(SEP) }.toTypedArray())
            putIntArray(COLORS, items.map { it.color }.toIntArray())
            for (pkg in items.map { it.pkg }.distinct()) icons[pkg]?.let { putByteArray(ICON + pkg, it) }
        }

        fun decode(b: Bundle?): List<NoticeShade.Item> {
            val keys = b?.getStringArray(KEYS) ?: return emptyList()
            val pkgs = b.getStringArray(PKGS) ?: return emptyList()
            val apps = b.getStringArray(APPS) ?: return emptyList()
            val titles = b.getStringArray(TITLES) ?: return emptyList()
            val texts = b.getStringArray(TEXTS) ?: return emptyList()
            val times = b.getLongArray(TIMES)
            val actions = b.getStringArray(ACTIONS)
            val colors = b.getIntArray(COLORS)
            return keys.indices.mapNotNull { i ->
                NoticeShade.Item(
                    keys[i], pkgs.getOrNull(i) ?: return@mapNotNull null, apps.getOrNull(i).orEmpty(), titles.getOrNull(i).orEmpty(), texts.getOrNull(i).orEmpty(),
                    time = times?.getOrNull(i) ?: 0L,
                    actions = actions?.getOrNull(i)?.takeIf { it.isNotEmpty() }?.split(SEP).orEmpty(),
                    color = colors?.getOrNull(i) ?: 0,
                )
            }
        }
    }
}
