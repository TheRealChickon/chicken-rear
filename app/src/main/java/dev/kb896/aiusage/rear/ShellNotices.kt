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
import dev.kb896.aiusage.data.MessageStyle
import dev.kb896.aiusage.data.PanelTheme
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
    /** A page or the pop-up thrown the snoozing way (NoticeThrow): the notification of [key] snoozed for [ms]. */
    private val onSnoozed: (key: String, ms: Long) -> Unit = { _, _ -> },
    /**
     * The pop-up stands IN ITS PLACE over the whole back (true), or does not any more (false): told on its own thread,
     * a little before it starts to go by itself ([POP_LEAD_MS]) and before any move out of its place. For HiddenFrames.
     */
    private val onPopPlace: (Boolean) -> Unit = {},
    /**
     * The pile STANDS SETTLED OVER THE WHOLE BACK (true: down all the way, one page in its place, nothing moving, no
     * finger on it), or does not any more (false): from its sheet (ShellOverlay.onCover), false before any frame that
     * moves and as it ends, and with the first frame after a finger lands on it. For HiddenFrames.
     */
    private val onPileCover: (Boolean) -> Unit = {},
    /** "Empty pull light": Xiaomi's rear-glow colours (true) or white (PanelConfig.emptyPullXiaomi). Read as a pull begins. */
    private val rimXiaomi: () -> Boolean = { false },
    /**
     * The message island's tint of the live face ("Island · Tint": NoticeVeil, read by the scene at each of its pictures);
     * null: the island draws its page over whatever is under it by itself.
     */
    private val veil: NoticeVeil? = null,
) {

    // -- nothing waiting: the rim lights up (EmptyRim, ShellEmptyRim) ---------------------------
    //
    // A pull with nothing waiting brings no page down: the rim's light plays over whatever the back shows, its pool
    // following the finger, the run round the rim from the lift. For the navigation it is the notifications up with
    // nothing waiting (RearNav's shade, a finger on it the shade's own, which does nothing), until it has gone.

    private val rim by lazy { ShellEmptyRim(layerStack, geometry) { rimEnded() } }
    /** Whether the rim's light has the shade ([up] is then its: nothing waiting, no layers of the pages up). Under [lock]. */
    private val empty = EmptyPull()
    private var downX = 0f
    /** Where the pulling finger is now (panel units): the pages come down to it if one arrives while it pulls. Under [lock]. */
    private var pullY = 0f

    private fun rimEnded() {
        synchronized(lock) {
            // A newer play begun in the moment the last one ended keeps the shade (its own end shuts it).
            if (!empty.ended(rim.isPlaying)) return
            up = false
        }
        Log.i(TAG, "notices: nothing waiting — the rim's light is over")
    }

    /**
     * A debug build's look at the rim with no notification touched: as a pull with nothing waiting, by the clock
     * (EmptyRim.DEMO_X, the draft's own 200 ms pull). False when the notifications are up or the panel not known yet.
     */
    fun emptyDemo(): Boolean {
        synchronized(lock) {
            if (up || face == null) return false
            empty.demo(); up = true
        }
        Log.i(TAG, "notices: nothing waiting (a demo) — the rim's light")
        rim.demo(runCatching { rimXiaomi() }.getOrDefault(false))
        return true
    }

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

    /**
     * The pages are up, not the rim's light (which has none): the pile's word ([onPileCover]) counts only with this, so a
     * word not yet taken back as the pages go (their sheet ends a moment later, on [clock]) never holds what the rim's
     * light plays over.
     */
    val pagesUp: Boolean get() = synchronized(lock) { up && !empty.up }

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
        var toPages = false
        val none = synchronized(lock) {
            real = list
            if (!demo) model.items(list)
            // ONE CAME WHILE THE RIM'S LIGHT PLAYED: the light gives the shade back at once. Under a finger still pulling the
            // pages come down to it; after the lift the shade shuts, so its pop-up comes (none comes over the shade).
            when (empty.arrived(model.count)) {
                EmptyPull.Arrival.NONE -> Unit
                EmptyPull.Arrival.SHUT -> {
                    Log.i(TAG, "notices: one came while the rim's light played — the light gives way")
                    rim.stop(); up = false
                }
                EmptyPull.Arrival.PAGES -> {
                    Log.i(TAG, "notices: one came while the rim's light followed the pull — the pages come down to the finger")
                    rim.stop()
                    if (overlay != null) {
                        model.beginPull(downY, pullY, SystemClock.uptimeMillis()); beginPending = true; toPages = true
                    } else up = false
                }
            }
            up && !empty.up && !demo && list.isEmpty()
        }
        if (toPages) { open(); banner.gone() }
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
            if (e is RearTouches.Down) { downY = e.y; downX = e.x }
            // The rim's light is up: a finger on it is nobody's (no page on it), but a new pull plays it again once the light
            // is over. A finger landing while the last one is still taken as pulling: that one's lift never came.
            if (empty.up && e is RearTouches.Down) {
                owns = false
                if (empty.pulling) { rim.release(now); empty.lifted() }
            } else if (e is RearTouches.Down) {
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
                is EdgeGestures.Action.NoticePullStart -> if (!owns && opens() && face != null) {
                    pullY = a.y
                    when (empty.start(model.count, up, rim.lightOver(now))) {
                        // NOTHING WAITING: no page comes down; the rim lights up under the finger.
                        EmptyPull.Start.RIM -> {
                            up = true
                            rim.begin(downX, runCatching { rimXiaomi() }.getOrDefault(false), now)
                            rim.pull(a.y - downY)
                            Log.i(TAG, "notices: nothing waiting — the rim's light follows the pull")
                        }
                        EmptyPull.Start.NOTHING -> Log.i(TAG, "notices: a pull while the rim's light still runs — nobody's")
                        // Something came while the light was up (the list's own change normally comes first): the pages take its place.
                        EmptyPull.Start.PAGES_FROM_RIM -> {
                            rim.stop()
                            if (overlay != null) { model.beginPull(downY, a.y, now); begin = true; beginPending = true } else up = false
                        }
                        EmptyPull.Start.PAGES -> if (overlay != null) {
                            model.beginPull(downY, a.y, now)
                            if (!up) { up = true; begin = true; beginPending = true }
                        }
                    }
                }
                is EdgeGestures.Action.NoticePullMove -> { pullY = a.y; if (empty.pulling) rim.pull(a.y - downY) else model.movePull(a.y, now) }
                is EdgeGestures.Action.NoticePullEnd -> if (empty.pulling) {
                    rim.pull(a.y - downY); rim.release(now); empty.lifted()
                } else { effects += model.endPull(a.y, now); nothing = up && model.count == 0 }
                is EdgeGestures.Action.PullEnd, is EdgeGestures.Action.SlideEnd, EdgeGestures.Action.Cancel -> {
                    // A pull that ends any other way still plays from the lift (a short pull with nothing waiting plays too).
                    if (empty.pulling) { rim.release(now); empty.lifted() }
                    rest += a
                }
                else -> if (!owns) rest += a
            }
            if (empty.pulling && e is RearTouches.Up) { rim.release(now); empty.lifted() }
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
            if (empty.up) {
                // The rim's light: gone at once; it had no pages up.
                empty.shut(); up = false
                rim.stop()
                return
            }
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
            made.onCover = onPileCover
            overlay = made
            return made
        }
    }

    private fun carryOut(effect: NoticeShade.Effect) {
        when (effect) {
            is NoticeShade.Effect.Dismissed, is NoticeShade.Effect.Snoozed -> {
                val snooze = effect is NoticeShade.Effect.Snoozed
                val key = if (effect is NoticeShade.Effect.Snoozed) effect.key else (effect as NoticeShade.Effect.Dismissed).key
                Log.i(TAG, "notices: a page thrown out — ${if (snooze) "snoozed for an hour" else "dismissed"}${if (NoticeThrow.isDemo(key)) " (a demo row)" else ""}")
                if (effect is NoticeShade.Effect.Snoozed) runCatching { onSnoozed(effect.key, effect.ms) }.onFailure { Log.w(TAG, "notices: snooze: ${it.message}") }
                else runCatching { onDismissed(key) }.onFailure { Log.w(TAG, "notices: dismiss: ${it.message}") }
                // The last one thrown: the pile goes up with it, and its label stays the moment it is due on a layer of its own.
                if (synchronized(lock) { model.count == 0 }) throwLabel.show(if (snooze) NoticeThrow.Kind.SNOOZE else NoticeThrow.Kind.DISMISS)
                // The last one gone, there is nothing to stay open for.
                runCatching { clock.schedule({ shutIfNone() }, NoticeShade.FLY_MS, TimeUnit.MILLISECONDS) }
            }
            is NoticeShade.Effect.Refused -> Log.i(TAG, "notices: a page thrown that may not be dismissed or snoozed — it springs back")
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
            if (up && !empty.up && model.count == 0) model.close(SystemClock.uptimeMillis()) else emptyList()
        }
        shut.forEach(::carryOut)
        overlay?.changed()
    }

    private fun endIfShut() {
        val o = synchronized(lock) {
            if (!up || empty.up || !model.isShut(SystemClock.uptimeMillis())) return
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
        private var covers = false

        override fun frame(): Float = synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            val s = model.scene(now)
            scene = s
            moving = model.moving(now)
            val p = model.progress(now)
            // Settled over the whole back, as this frame is drawn (the painter draws nothing without a face); never
            // while a finger is the pile's, the pinned apps' row included.
            covers = face != null && !owns && model.covers(p, moving, s)
            p
        }

        override fun covers(): Boolean = covers

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

    /**
     * [item] as it arrives, in the message [style] chosen (PanelConfig.messageStyle: the full page, or the message island);
     * [faceTints]: what shows under it is a face the scene draws (the moon, the dust), which takes the app's colour itself
     * for "Island · Tint". A pop-up already up keeps its style; the next one comes in its place. Any thread. Returns how
     * long it has on the back from now (ms), for the navigation's end of it.
     */
    fun pop(item: NoticeShade.Item, style: MessageStyle = MessageStyle.FULL, faceTints: Boolean = false): Long = banner.show(item, style, faceTints)

    /** A pop-up's whole time on the back in [style] (in, held, out): what RearShow is told as one is asked for. */
    fun popMs(style: MessageStyle): Long = if (style == MessageStyle.FULL) BANNER_MS else MessageIsland.total()

    /** The pop-up taken away now (a stroke on it: RearNav). */
    fun popGone() = banner.gone()

    /** The pop-up under a finger going sideways: [dx] across, panel units (RearUserService's PageDrag). */
    fun popPeek(dx: Float) = banner.peek(dx * geometry().fitX)

    /** Let go short, or one that may not be thrown (RearNav's POP_GIVES): back to its place on a spring. The ms it has left on the back. */
    fun popBack(): Long = banner.back()

    /**
     * The pop-up THROWN OUT the way the finger went ([dir] -1 left, +1 right; RearNav's POP_THROWN_*): it flies on from
     * where the finger left it, its label rises, and its notification is dismissed or snoozed ([kind]; a made-up one of a
     * debug build only logged by the callers). False when there was none to throw.
     */
    fun popThrown(dir: Float, kind: NoticeThrow.Kind): Boolean {
        val item = banner.throwOut(dir) ?: return false
        throwLabel.show(kind)
        Log.i(TAG, "notices: the pop-up thrown out — ${if (kind == NoticeThrow.Kind.SNOOZE) "snoozed for an hour" else "dismissed"}${if (NoticeThrow.isDemo(item.key)) " (a demo)" else ""}")
        if (kind == NoticeThrow.Kind.SNOOZE) runCatching { onSnoozed(item.key, NoticeThrow.SNOOZE_MS) }.onFailure { Log.w(TAG, "notices: snooze: ${it.message}") }
        else runCatching { onDismissed(item.key) }.onFailure { Log.w(TAG, "notices: dismiss: ${it.message}") }
        return true
    }

    /** The notification the pop-up shows, or null for none up. */
    fun popItem(): NoticeShade.Item? = banner.current()

    fun islandTimeLeft(): Long = banner.islandTimeLeft()

    private val readyArt = java.util.concurrent.atomic.AtomicReference<IslandArt?>()

    /** Compile the program and resolve its fonts as soon as an island style is selected. */
    fun prepareIsland() {
        if (readyArt.get() == null) {
            runCatching { IslandArt().apply { prepareHardware() } }
                .onSuccess { readyArt.compareAndSet(null, it) }
                .onFailure { Log.w(TAG, "Island preparation failed", it) }
        }
    }

    private val banner = Banner()
    private val throwLabel = ThrowLabel()

    /**
     * The label of a throw on a layer of its own (NoticeThrowArt), over whatever the back shows: for the pop-up, which
     * flies away under it, and for the pile's last page, whose pile goes up with it. Trusted, so it takes no touch; at
     * most NoticeThrow.LABEL_MS, then gone.
     */
    private inner class ThrowLabel {
        private val state = Any()
        private var kind: NoticeThrow.Kind? = null
        private var at = 0L
        private var thread: Thread? = null

        fun show(k: NoticeThrow.Kind) {
            if (face == null) return
            synchronized(state) {
                kind = k
                at = SystemClock.uptimeMillis()
                if (thread?.isAlive != true) thread = Thread({ run() }, "notice-throw-label").apply { isDaemon = true; start() }
            }
        }

        private fun run() {
            var sc: SurfaceControl? = null
            var surface: Surface? = null
            try {
                val rear = geometry()
                val h = rear.heightPx.toFloat()
                sc = ShellLayer.create(
                    "notice-throw-label", layerStack(), ShellLayer.Z_SHEET, rear.widthPx, rear.heightPx,
                    trusted = true, visible = false, alpha = 1f, format = android.graphics.PixelFormat.RGBA_8888, opaque = false,
                )
                surface = Surface(sc)
                val art = NoticeThrowArt()
                var visible = false
                while (!Thread.currentThread().isInterrupted) {
                    val (k, t) = synchronized(state) { kind to SystemClock.uptimeMillis() - at }
                    val label = k?.let { NoticeThrow.labelAt(it, t) } ?: break
                    val c = runCatching { surface.lockHardwareCanvas() }.getOrNull() ?: surface.lockCanvas(null)
                    try {
                        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        art.draw(c, h, label)
                    } finally { surface.unlockCanvasAndPost(c) }
                    if (!visible) { SurfaceControl.Transaction().use { it.setVisibility(sc, true).apply() }; visible = true }
                    Thread.sleep(FRAME_MS)
                }
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                Log.w(TAG, "notice throw label stopped: ${e.message}")
            } finally {
                runCatching { surface?.release() }
                ShellLayer.remove(sc)
                synchronized(state) { if (thread === Thread.currentThread()) { thread = null; kind = null } }
            }
        }
    }

    /** Reused snapshot of banner input; the render thread never holds the input lock while drawing. */
    private class IslandInput {
        var item: NoticeShade.Item? = null
        var start = 0L
        var look = MessageIsland.Look.SWEEP
        var x = 0f
        var flown = false
        var moving = false
    }

    /**
     * The banner: in, held, out by itself, on a thread of its own that lives only while it shows.
     * Trusted and so touch-transparent: the app under it goes on being used.
     */
    private inner class Banner {
        private val state = java.lang.Object()
        private var item: NoticeShade.Item? = null
        private var shownAt = 0L
        private var thread: Thread? = null
        /**
         * THE STYLE OF THE ONE UP (PanelConfig.messageStyle, read as it comes): the full page, or the message island and its
         * open page's look. Fixed from its coming until it has gone: a message coming in its place keeps it.
         */
        private var style = MessageStyle.FULL
        private var look = MessageIsland.Look.SWEEP
        /** The island's pill width now (its words'), for where a message coming in its place goes on from. */
        @Volatile private var pillW = MessageIsland.PILL_MAX_W

        // ACROSS (rear px): under a finger going sideways ([dragging], at [dragX]); or let go, from [moveFrom] at
        // [moveAt]: back to its place on a spring ([moveOut] 0), or on out that way (-1, +1) and then gone.
        private var dragging = false
        private var dragX = 0f
        private var peekAt = 0L
        private var moveFrom = 0f
        private var moveAt = -1L
        private var moveOut = 0f

        /** Where it stands across at [now], [w] the back's width. Under [state]. */
        private fun xAt(now: Long, w: Float): Float = when {
            dragging -> dragX
            moveAt < 0L -> 0f
            moveOut == 0f -> popSpringX(moveFrom, (now - moveAt).toFloat() / NoticeThrow.SPRING_MS)
            else -> popThrownX(moveFrom, moveOut, (now - moveAt).toFloat() / NoticeShade.FLY_MS, w)
        }

        fun current(): NoticeShade.Item? = synchronized(state) { item?.takeIf { moveOut == 0f } }

        fun islandTimeLeft(): Long = synchronized(state) {
            if (item == null || style == MessageStyle.FULL || moveOut != 0f) 0L
            else (shownAt + MessageIsland.TOTAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        }

        fun peek(px: Float) {
            synchronized(state) {
                if (item == null || moveOut != 0f) return
                dragging = true; moveAt = -1L; dragX = px; peekAt = SystemClock.uptimeMillis()
                state.notifyAll()
            }
        }

        fun back(): Long = synchronized(state) {
            val now = SystemClock.uptimeMillis()
            if (item == null || moveOut != 0f) return 0L
            moveFrom = xAt(now, geometry().widthPx.toFloat()); moveAt = now; moveOut = 0f; dragging = false
            state.notifyAll()
            if (style != MessageStyle.FULL) {
                // The island: it stayed open under the finger (and is open at least [AFTER_MS] more); one already folding folds on.
                islandHeld(now)
                return (MessageIsland.total() - (now - shownAt)).coerceAtLeast(0L)
            }
            // Its time stood still while the finger had it: it has at least [AFTER_MS] more once let go.
            if (now - shownAt > IN_MS + HOLD_MS - AFTER_MS) shownAt = now - (IN_MS + HOLD_MS - AFTER_MS)
            (IN_MS + HOLD_MS + OUT_MS - (now - shownAt)).coerceAtLeast(0L)
        }

        /** The island under a finger, at [now]: open, it stays open (at least [AFTER_MS] more); folding, it goes on. Under [state]. */
        private fun islandHeld(now: Long) {
            val open = MessageIsland.IN_MS + MessageIsland.HOLD_MS
            val t = now - shownAt
            if (t > open - AFTER_MS && t < open) shownAt = now - (open - AFTER_MS)
        }

        /** The open page's look for a message in [style], over a face that tints itself or not. */
        private fun lookFor(style: MessageStyle, faceTints: Boolean): MessageIsland.Look = when (style) {
            MessageStyle.SWEEP -> MessageIsland.Look.SWEEP
            else -> if (faceTints && veil != null) MessageIsland.Look.TINT_OVER_FACE else MessageIsland.Look.TINT_OWN
        }

        fun throwOut(dir: Float): NoticeShade.Item? = synchronized(state) {
            val it = item ?: return null
            if (moveOut != 0f) return null
            val now = SystemClock.uptimeMillis()
            moveFrom = xAt(now, geometry().widthPx.toFloat()); moveAt = now; moveOut = if (dir < 0f) -1f else 1f; dragging = false
            state.notifyAll()
            it
        }

        fun show(next: NoticeShade.Item, wanted: MessageStyle, faceTints: Boolean): Long {
            // Never silently: a banner with no face is one the user was told to look for.
            if (face == null) { Log.w(TAG, "notices: banner dropped — the panel's font is not loaded"); return popMs(wanted) }
            synchronized(state) {
                val now = SystemClock.uptimeMillis()
                // None up: this one's style (and, for the island, its open page's look over what is under it now).
                if (item != null && style != wanted) gone()
                style = wanted
                look = lookFor(wanted, faceTints)
                if (style != MessageStyle.FULL) {
                    // THE ISLAND: one open (or opening) takes the new words in place; one folding or leaving opens again from
                    // the moment of its way in whose shape is nearest (MessageIsland.rejoin): the shape never jumps.
                    val t = now - shownAt
                    shownAt = when {
                        item == null || moveOut != 0f -> now
                        t < MessageIsland.IN_MS -> shownAt
                        t < MessageIsland.IN_MS + MessageIsland.HOLD_MS -> now - MessageIsland.IN_MS
                        else -> now - MessageIsland.rejoin(t.toFloat(), pillW).toLong()
                    }
                    if (moveOut != 0f) { moveOut = 0f; moveAt = -1L; dragging = false }
                    else if (dragging) { moveFrom = dragX; moveAt = now; dragging = false }
                    item = next
                    state.notifyAll()
                    if (thread?.isAlive != true) thread = Thread({ run() }, "notice-island").apply { isDaemon = true; start() }
                    return (MessageIsland.total() - (now - shownAt)).coerceAtLeast(0L)
                }
                // Already fully in: this one takes its place without going out first.
                shownAt = if (item != null && moveOut == 0f && now - shownAt in IN_MS until IN_MS + HOLD_MS) now - IN_MS else now
                // One thrown out and still flying is gone: the next comes in from the top as any does, in its place.
                if (moveOut != 0f) { moveOut = 0f; moveAt = -1L; dragging = false }
                // One taking the place of a pop-up under a finger is not under it: back to its place on a spring, and the
                // finger's lift throws nothing (RearUserService: the drag was the one before's; RearNav: not aimed at it).
                else if (dragging) { moveFrom = dragX; moveAt = now; dragging = false }
                item = next
                if (thread?.isAlive != true) {
                    thread = Thread({ run() }, "notice-banner").apply { isDaemon = true; start() }
                }
            }
            return BANNER_MS
        }

        fun gone() {
            synchronized(state) {
                if (style != MessageStyle.FULL) veil?.clear()
                item = null
                dragging = false; moveAt = -1L; moveOut = 0f
                thread?.interrupt()
                thread = null
            }
        }

        /** The thread's work, by the style it was started for. */
        private fun run() = if (style == MessageStyle.FULL) runFull() else runIsland()

        /**
         * This thread's pop-up is over. One that came in its place in the moment between its last picture and here (its
         * time fresh, not thrown) is not dropped: a thread of its own goes on with it (never after one that [failed]: no
         * round of threads failing); else nothing is up.
         */
        private fun ended(failed: Boolean) = synchronized(state) {
            if (thread !== Thread.currentThread()) return@synchronized
            val total = if (style == MessageStyle.FULL) IN_MS + HOLD_MS + OUT_MS else MessageIsland.total()
            if (!failed && item != null && moveOut == 0f && SystemClock.uptimeMillis() - shownAt < total) {
                thread = Thread({ run() }, if (style == MessageStyle.FULL) "notice-banner" else "notice-island").apply { isDaemon = true; start() }
            } else { thread = null; item = null; dragging = false; moveAt = -1L; moveOut = 0f }
        }

        private fun runFull() {
            var sc: SurfaceControl? = null
            var surface: Surface? = null
            var failed = false
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
                var across = 0f
                val w = rear.widthPx.toFloat()
                var placed = false
                while (!Thread.currentThread().isInterrupted) {
                    var x = 0f
                    var over = false
                    var moving = false
                    val (now, it, at) = synchronized(state) {
                        val now = SystemClock.uptimeMillis()
                        // A finger that said nothing for this long has gone without its lift: back to its place.
                        if (dragging && now - peekAt > DRAG_STALE_MS) { moveFrom = dragX; moveAt = now; moveOut = 0f; dragging = false }
                        // Under the finger its time stands still (it stays up for at least [AFTER_MS] once let go).
                        if (dragging && now - shownAt > IN_MS + HOLD_MS - AFTER_MS) shownAt = now - (IN_MS + HOLD_MS - AFTER_MS)
                        x = xAt(now, w)
                        over = moveOut != 0f && now - moveAt >= NoticeShade.FLY_MS
                        moving = dragging || (moveAt >= 0L && now - moveAt < (if (moveOut != 0f) NoticeShade.FLY_MS else NoticeThrow.SPRING_MS))
                        if (!dragging && moveOut == 0f && moveAt >= 0L && now - moveAt >= NoticeThrow.SPRING_MS) moveAt = -1L
                        Triple(now, item, shownAt)
                    }
                    val t = now - at
                    // Thrown out and flown off the back: gone, not slid up.
                    if (it == null || over || t >= IN_MS + HOLD_MS + OUT_MS) break
                    val shown = popShown(t)
                    // In its place: all the way in, not aside, nothing moving it, and not about to go by itself.
                    val inPlace = shown >= 1f && x == 0f && !moving && t < IN_MS + HOLD_MS - POP_LEAD_MS
                    if (placed && !inPlace) { placed = false; runCatching { onPopPlace(false) } }
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
                    if (alpha != shown || across != x) {
                        // IT COMES DOWN FROM THE TOP and goes back up: the whole page slides, at full strength, not a
                        // fade in place. Sideways
                        // it follows the finger and is thrown out or springs back the same way (NoticeThrow), round
                        // cornered whenever it is not in its place, never faded.
                        SurfaceControl.Transaction().use { tx ->
                            // RearShape.place: off its place (down or across) the display's corners, in it the mask's.
                            RearShape.place(tx, sc, rear, x, popY(shown, rear.heightPx.toFloat()))
                            tx.setAlpha(sc, 1f).setVisibility(sc, shown > 0f).apply()
                        }
                        alpha = shown
                        across = x
                    }
                    if (!placed && inPlace) { placed = true; runCatching { onPopPlace(true) } }
                    Thread.sleep(if (shown < 1f || moving) FRAME_MS else IDLE_MS)
                }
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                failed = true
                Log.w(TAG, "notice banner stopped: ${e.message}")
            } finally {
                // Not in its place from here: told before its layer goes.
                runCatching { onPopPlace(false) }
                runCatching { surface?.release() }
                ShellLayer.remove(sc)
                ended(failed)
            }
        }

        /**
         * THE MESSAGE ISLAND (MessageIsland, IslandArt; "Island · Tint" and "Island · Sweep" in the settings): a SEE-THROUGH
         * layer of the back's size at the banner's place, so THE LIVE FACE STAYS UNDER IT, moving: the island never says it
         * stands in its place ([onPopPlace]), so nothing under it is held. Trusted (touches go through it, as the full
         * page's). Every moment of it is MessageIsland.at at the picture's own time; a picture is drawn only when it differs
         * from the last (the page held: nothing drawn). On the rear's frame grid while it moves; the face's tint, where the
         * face takes the colour itself, follows the same timeline in the scene's own thread ([veil]). Before anything is
         * shown everything it draws is drawn once with the layer hidden (IslandArt.warm): the shader, the icon's texture and
         * the glyphs are made then, not as it arrives or opens. Sideways under a finger, thrown, sprung back: as the full
         * page. Once it has sunk into the top edge, the layer, its thread and the tint are gone.
         */
        private fun runIsland() {
            var sc: SurfaceControl? = null
            var surface: Surface? = null
            var failed = false
            val art = readyArt.getAndSet(null) ?: IslandArt()
            val f = MessageIsland.Frame()
            val last = MessageIsland.Frame()
            val placement = SurfaceControl.Transaction()
            var said = ""
            // Its moving pictures, for the one line said as it goes: how many, the longest gap, how many came late.
            var pictures = 0; var worstNs = 0L; var late = 0
            try {
                val rear = geometry()
                sc = ShellLayer.create(
                    "notice-island", layerStack(), ShellLayer.Z_BANNER, rear.widthPx, rear.heightPx,
                    trusted = true, visible = false, alpha = 1f, format = android.graphics.PixelFormat.RGBA_8888, opaque = false,
                    corner = ShellLayer.pageCorner(rear),
                )
                surface = Surface(sc)
                val w = rear.widthPx.toFloat(); val h = rear.heightPx.toFloat()
                // The rear's px to the island's page units (the 976 x 596 back): for the tint's place under a finger.
                val toPage = MessageIsland.W / w
                placement.setCrop(sc, android.graphics.Rect(0, 0, rear.widthPx, rear.heightPx))
                var drawn: NoticeShade.Item? = null
                var across = Float.NaN
                var visible = false
                var redraw = false
                var thrownSaid = false
                var lastNs = 0L
                var prevNs = 0L
                val open = MessageIsland.IN_MS + MessageIsland.HOLD_MS
                val input = IslandInput()
                while (!Thread.currentThread().isInterrupted) {
                    val nowNs = System.nanoTime()
                    val now = SystemClock.uptimeMillis()
                    synchronized(state) {
                        if (thread !== Thread.currentThread()) return
                        if (dragging && now - peekAt > DRAG_STALE_MS) { moveFrom = dragX; moveAt = now; moveOut = 0f; dragging = false }
                        if (dragging) islandHeld(now)
                        val thrown = moveOut != 0f
                        input.x = xAt(now, w)
                        input.flown = thrown && now - moveAt >= NoticeShade.FLY_MS
                        input.moving = dragging || (moveAt >= 0L && now - moveAt < (if (thrown) NoticeShade.FLY_MS else NoticeThrow.SPRING_MS))
                        if (!dragging && !thrown && moveAt >= 0L && now - moveAt >= NoticeThrow.SPRING_MS) moveAt = -1L
                        input.item = item
                        input.start = shownAt
                        input.look = look
                        if (item != null && item === drawn && veil != null) {
                            veil.retime(shownAt)
                            if (thrown && !thrownSaid) { thrownSaid = true; veil.end(now) }
                            veil.dx = input.x * toPage
                        }
                    }
                    val it = input.item ?: break
                    val at = input.start
                    val lk = input.look
                    val x = input.x
                    val t = ((nowNs - at * 1_000_000L) / 1e6).toFloat()
                    if (input.flown || t >= MessageIsland.TOTAL_MS) break
                    if (it !== drawn) {
                        val words = shown(it)
                        val icon = icons[words.pkg]
                        art.set(whoOf(words, icon), words.text, words.color, icon, lk)
                        pillW = art.pillW
                        said = if (lk === MessageIsland.Look.TINT_OVER_FACE) "tint, the face takes the colour" else if (lk.sweep) "sweep" else "tint over a page"
                        if (drawn == null) {
                            // Everything drawn once, hidden, then cleared: nothing is made as it is seen.
                            val warmAt = System.nanoTime()
                            val c = ShellLayer.lockPanelCanvas(surface)
                            try { c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR); art.warm(c, w, h, f) } finally { surface.unlockCanvasAndPost(c) }
                            val c2 = ShellLayer.lockPanelCanvas(surface)
                            try { c2.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR) } finally { surface.unlockCanvasAndPost(c2) }
                            val warmMs = (System.nanoTime() - warmAt) / 1_000_000L
                            Log.i(TAG, "island: up ($said), made ready in $warmMs ms before it is shown")
                            // Its clock starts after that: the arrival is not cut short by it.
                            synchronized(state) { if (item === it && shownAt == at) shownAt = SystemClock.uptimeMillis() }
                        }
                        synchronized(state) {
                            if (thread !== Thread.currentThread()) return
                            if (item === it) veil?.begin(words.color, shownAt, art.pillW, tints = lk === MessageIsland.Look.TINT_OVER_FACE)
                        }
                        drawn = it
                        redraw = true
                        thrownSaid = false
                        continue
                    }
                    MessageIsland.at(t, art.pillW, f, sweeps = lk.sweep)
                    if (redraw || !f.same(last)) {
                        val c = ShellLayer.lockPanelCanvas(surface)
                        try {
                            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                            art.draw(c, w, h, f)
                        } finally { surface.unlockCanvasAndPost(c) }
                        last.copyFrom(f)
                        redraw = false
                        val drewNs = System.nanoTime()
                        if (prevNs != 0L) { val gap = drewNs - prevNs; if (gap > worstNs) worstNs = gap; if (gap > LATE_NS) late++ }
                        prevNs = drewNs; pictures++
                    } else prevNs = 0L
                    if (!visible || across != x) {
                        if (!visible || (across == 0f) != (x == 0f)) {
                            ShellOverlay.roundCorners(placement, sc, RearShape.cornerAt(rear, x, 0f))
                        }
                        placement.setPosition(sc, x, 0f).setVisibility(sc, true).apply()
                        visible = true
                        across = x
                    }
                    // On the rear's frame grid while anything of it moves; the page held, a look now and then (a finger, the fold).
                    val animating = input.moving || t < MessageIsland.IN_MS || t >= open || (lk.sweep && t < MessageIsland.SWEEP_TO)
                    if (animating) {
                        val next = FramePace.clockNextNs(lastNs, System.nanoTime(), 0f, REAR_PERIOD_NS)
                        lastNs = next
                        val wait = next - System.nanoTime()
                        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    } else {
                        lastNs = 0L
                        synchronized(state) {
                            if (!dragging && moveAt < 0L && item === it) {
                                state.wait(minOf(IDLE_MS, (open - t).toLong().coerceAtLeast(1L)))
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                failed = true
                Log.w(TAG, "notice island stopped: ${e.message}")
            } finally {
                art.clearMessage()
                if (!failed) readyArt.compareAndSet(null, art)
                placement.close()
                synchronized(state) {
                    if (thread === Thread.currentThread()) veil?.clear()
                }
                if (pictures > 0) Log.i(TAG, "island: gone ($said) — $pictures pictures while it moved, the longest gap ${"%.1f".format(java.util.Locale.ROOT, worstNs / 1e6)} ms, $late over ${LATE_NS / 1_000_000} ms")
                runCatching { surface?.release() }
                ShellLayer.remove(sc)
                ended(failed)
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
        const val IN_MS = 380L
        const val HOLD_MS = 5_000L
        const val OUT_MS = 420L
        /** The pop-up is told as not in its place this much before it goes up by itself: what is under it moves again first. */
        const val POP_LEAD_MS = 150L
        /** A pop-up let go after a finger held it aside stays at least this much longer. */
        private const val AFTER_MS = 1_500L
        /** Held aside with no word from the finger for this long: it is let go. */
        private const val DRAG_STALE_MS = 2_000L

        /** How far in the pop-up is [t] ms after it came: 0 off the top edge, 1 the whole back. */
        fun popShown(t: Long): Float = when {
            t < IN_MS -> { val p = 1f - t.toFloat() / IN_MS; 1f - p * p * p }
            t < IN_MS + HOLD_MS -> 1f
            else -> { val p = ((t - IN_MS - HOLD_MS).toFloat() / OUT_MS).coerceAtMost(1f); 1f - p * p }
        }

        /** IT COMES DOWN FROM THE TOP and goes back up: where its top edge stands when it is [shown] in, on a back [h] high. */
        fun popY(shown: Float, h: Float): Float = -(1f - shown) * h

        /** Thrown out: where it stands across [p] (0 to 1) of its flight ([NoticeShade.FLY_MS]) from [from] the way [dir] (-1, +1), on a back [w] wide. */
        fun popThrownX(from: Float, dir: Float, p: Float, w: Float): Float {
            val q = p.coerceIn(0f, 1f)
            return from + (dir * w - from) * (1f - (1f - q) * (1f - q))
        }

        /** Let go short: where it stands across [p] (any, of [NoticeThrow.SPRING_MS]) on its spring back from [from]. */
        fun popSpringX(from: Float, p: Float): Float = from * (1f - NoticeThrow.spring(p))
        /** A banner's whole time on the rear, in to out: for RearShow. */
        const val BANNER_MS = IN_MS + HOLD_MS + OUT_MS
        private const val FRAME_MS = 16L
        private const val IDLE_MS = 100L
        /** The rear's refresh at its 120 Hz: the island's pictures while it moves. */
        private val REAR_PERIOD_NS = FramePace.periodNsOf(FramePace.REAR_HZ)
        /** A gap between two of the island's moving pictures longer than this (two refreshes and a bit) is a stutter, for its log line. */
        private const val LATE_NS = 20_000_000L

        private const val KEYS = "keys"
        private const val PKGS = "pkgs"
        private const val APPS = "apps"
        private const val TITLES = "titles"
        private const val TEXTS = "texts"
        private const val TIMES = "times"
        private const val ACTIONS = "actions"
        private const val COLORS = "colors"
        private const val CLEARABLE = "clearable"
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
            putBooleanArray(CLEARABLE, items.map { it.clearable }.toBooleanArray())
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
            val clearable = b.getBooleanArray(CLEARABLE)
            return keys.indices.mapNotNull { i ->
                NoticeShade.Item(
                    keys[i], pkgs.getOrNull(i) ?: return@mapNotNull null, apps.getOrNull(i).orEmpty(), titles.getOrNull(i).orEmpty(), texts.getOrNull(i).orEmpty(),
                    time = times?.getOrNull(i) ?: 0L,
                    actions = actions?.getOrNull(i)?.takeIf { it.isNotEmpty() }?.split(SEP).orEmpty(),
                    color = colors?.getOrNull(i) ?: 0,
                    // From an app of before this was said: what it put in the list was clearable (its relay let no other in).
                    clearable = clearable?.getOrNull(i) ?: true,
                )
            }
        }
    }
}
