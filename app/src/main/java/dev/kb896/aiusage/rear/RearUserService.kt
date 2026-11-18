package dev.kb896.aiusage.rear

import android.content.Context
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import dev.kb896.aiusage.BuildConfig
import dev.kb896.aiusage.IRearEvents
import dev.kb896.aiusage.IRearService
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.StatePayload
import dev.kb896.aiusage.rear.SurfaceDump.Where
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

class RearUserService : IRearService.Stub {

    @Suppress("unused") constructor() { shapeTheBack(); autostart.start(); searchOffline.start() }
    /**
     * Shizuku 13+ hands a Context of our package here: the shell sheet reads the panel's
     * font through its Resources (see ShellFont).
     */
    @Suppress("unused") constructor(context: Context) { this.context = context; watchFrontLock(); shapeTheBack(); autostart.start(); searchOffline.start() }

    /**
     * LAST in either constructor, once every field is set and the context is here (the rear's size is read through
     * it): the display's shape over everything of ours (RearShape), kept by its own thread from now on. The back's
     * stack read afresh as the pages read it (rearLayerStack), not the guests' one read (coverStack); the back's size
     * as the pages have it. (Asked from the init block, a layer made by the cover's thread before this object was whole
     * met fields not yet set, and the mask waited for the next layer to be made.)
     */
    private fun shapeTheBack() = RearShape.shapeTheBack({ rearLayerStack() }, { geometry })

    private var context: Context? = null

    /** Volatile: the App Portal's binder calls and the keep-alive read it off the lock. */
    @Volatile private var placedDisplayId = -1

    /** Last payload shown, so the keep-alive can re-place without the app's help. */
    @Volatile private var lastPayload: String = "{}"

    /**
     * COMPANION MODE (PanelConfig.rearMode, from the last payload): Xiaomi's rear screen is the
     * base and stays. No panel is placed, moved back or re-placed over it; the keep-alive only
     * watches a guest of ours; ending a guest only drops it; Xiaomi's app is never suspended.
     * True until a payload says otherwise, so a new shell never fights Xiaomi's screen before
     * it has heard which mode it is in.
     */
    @Volatile private var companion = true

    /** More apps on the back (PanelConfig.moreApps), from the last payload: the top pull's watch. */
    @Volatile private var moreApps = true
    /** Volatile: hide() stops it off [placing], and [adoptPlaced] may start one meanwhile. */
    @Volatile private var keepAlive: Thread? = null

    /**
     * The panel was hidden on purpose (hide(), the onboarding undone, the "panel hidden"
     * toggle) and no show() has come since. hide() only forgets the display and sends an
     * async FINISH, so for a moment the panel's task is still listed on the rear — and an
     * update() or isAlive() racing it adopted it again ([adoptPlaced]), and the keep-alive then
     * re-placed it. Set first thing in hide(), cleared in show().
     */
    @Volatile private var hiddenOnPurpose = false

    /** Consecutive failed attempts to move the panel off the main display. */
    private var strandStrikes = 0

    /** When the panel was last rebuilt from scratch, for the cooldown below. */
    private var lastReplaceAt = 0L

    /**
     * A thread whose body cannot kill the process: anything it throws, an Error too, is logged
     * and that thread's work ends. An uncaught throwable in any thread kills app_process, and
     * the sheet, the frame and the guest's watch with it.
     */
    private inline fun guarded(crossinline body: () -> Unit): Thread = Thread {
        try { body() } catch (e: Throwable) { Log.e(TAG, "a shell thread (${Thread.currentThread().name}) failed", e) }
    }

    /** An executor's thread, as [guarded]: a task that throws ends that thread, never the process. */
    private fun guardedThread(name: String, r: Runnable): Thread =
        Thread({
            try { r.run() } catch (e: Throwable) { Log.e(TAG, "a shell worker ($name) failed", e) }
        }, name).apply { isDaemon = true }

    init {
        // FIRST: the back covered, before a single setting is known, if a face of ours was what it showed
        // when this process last ran. A restart (an update, a crash) must not show Xiaomi's screen meanwhile.
        // (A missing or old hint file means cover too: RearCover.Hint.)
        if (RearCover.Hint.read(COVER_HINT_FILE).face) Thread({
            repeat(40) {
                if (runCatching { cover.set(true, "from the last run"); cover.isUp }.getOrDefault(false)) return@Thread
                try { Thread.sleep(50L) } catch (_: InterruptedException) { return@Thread }
            }
        }, "rear-cover-first").apply { isDaemon = true; start() }
        // The last chance: whatever still escapes takes our layers off the rear, at once and in
        // order, before the process dies (a dying process takes them too, but not as tidily).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { Log.e(TAG, "uncaught in ${t.name} — our layers off the rear before the end", e) }
            runCatching { ShellLayer.removeAll() }
            previous?.uncaughtException(t, e)
        }
        // A picture's note on every notes page of this process (the page, its turn and carousel, an arrival, the landing,
        // the veil over Xiaomi's pin) takes its picture from here: in memory, or its place empty while it is read (NotePictures).
        PinFace.pictures = { name -> notePictures.get(name) }
    }

    override fun destroy() {
        // Going anyway with the process, the token dying with it; said first, so it is seen to.
        letGoOfBack("the service is going")
        // The sheet's blocker must not outlive us: an untrusted layer left up takes every
        // touch on the rear. On this thread, since the process exits right after.
        closeSheetNow("the service is going", sync = true)
        stopTouchWatch()
        // A private display's first, while its layer is still there to be let go of in order.
        closePrivate("the service is going")
        // Every layer of ours off the rear now, in one transaction, before the exit.
        ShellLayer.removeAll()
        // The guest's density must not outlive the service that set it. Closed first, so
        // nothing re-applies one between this restore and the exit; and nothing read means
        // nothing was ever set, so no first read just to find that out.
        synchronized(densityLock) {
            densityClosed = true
            if (densityRead) {
                rereadNotBefore = 0L
                applyDensity(null, "the service is going")
                // One more go if it did not take. Past that there is nothing more to do here:
                // the marker setting is still written, so the next start finds the override
                // listed as ours and restores it (see readDensity).
                if (currentDensity != baselineDensity || !densityKnown) applyDensity(null, "the service is going (retry)")
            }
        }
        // DPI changes not yet handed on go now, or they are lost with us.
        flushDpi(all = true)
        flushWidth(all = true)
        exitProcess(0)
    }

    /**
     * Whether the panel is actually THERE — composited on the rear display, not merely
     * recorded as a task.
     *
     * This used to be `taskId() > 0`, and that is the hole the panel kept falling into:
     * observed on device with RearActivity's window reporting mDisplayId=1 while
     * SurfaceFlinger composited it on NEITHER display, and the rear panel showed MIUI's
     * SubScreenLauncher. The task existed the whole time, so the watchdog read ALIVE
     * and never respawned — the panel stayed silently gone until someone noticed.
     *
     * Task existence answers "did something get created", which is not the question.
     * The question is "is the user looking at it", and composition is the only signal
     * on this device that answers it honestly.
     *
     * Only a DEFINITE false counts as dead. `null` means the layer is composited
     * nowhere, which is genuinely ambiguous: it is what a silently-dead panel looks
     * like, but it is ALSO what a blanked display looks like, because SurfaceFlinger
     * stops listing HWC layers for a display that is not being composed. Treating
     * unknown as dead made every screen-off look like a death and respawned the panel
     * through `am start` on display 0 — which is the takeover this whole area exists to
     * prevent. Ambiguity has to resolve to "leave it alone".
     */
    override fun isAlive(): Boolean {
        if (taskId() <= 0) return false
        // A guest app in front of the panel is the panel kept behind ON PURPOSE. Read as
        // DISPLACED it is "dead" to the app's own watchdog, which then re-places the panel
        // over the guest within twenty seconds. Whether the guest is still really there is
        // the keep-alive's to check, every tick (GuestGate), and it re-places the panel
        // itself when the guest goes.
        if (guest != null) return true
        // NOWHERE counts as alive: an asleep panel is not a broken one, and treating it
        // as dead is what once turned every screen-off into a relaunch. DISPLACED does
        // not — the task existing is exactly what makes that failure invisible.
        return when (panelWhere()) {
            // There, with its task: this service may not be the one that placed it (see
            // [adoptPlaced]).
            Where.REAR, Where.NOWHERE -> { adoptPlaced(Panel.DISPLAY_ID, "isAlive"); true }
            Where.MAIN, Where.DISPLACED -> false
        }
    }

    /**
     * The panel found on the rear by a service that did not put it there — the app was
     * reinstalled, or this process restarted, with the panel left up. The app then only
     * update()s it (it reads alive), so [show] never runs, [placedDisplayId] stays -1,
     * and with it [touchWanted]: no touch watch, no pull, until the app restarts.
     * Measured on device after a reinstall. So whatever finds the panel placed adopts it
     * here: the display recorded, the touch watch started, and the keep-alive [show]
     * would have started. Once only — a panel already recorded is left alone, and
     * [hide] still clears it. Never a panel hidden on purpose ([hiddenOnPurpose]): its task
     * lingers a moment after hide(), and adopting it brought it back.
     */
    private fun adoptPlaced(displayId: Int, why: String) {
        // Nothing to adopt in companion mode: a panel task left from Replace mode is not kept up.
        if (companion) return
        if (displayId < 0 || placedDisplayId >= 0 || hiddenOnPurpose) return
        synchronized(placing) {
            if (placedDisplayId >= 0 || hiddenOnPurpose) return
            placedDisplayId = displayId
            Log.i(TAG, "the panel is already on display $displayId ($why) — adopting it")
            startKeepAlive(displayId)
            // hide() sets the flag before it tears down, off this lock: one that came in since
            // the look above is undone here, or — later than this look — by hide() itself.
            if (hiddenOnPurpose) {
                placedDisplayId = -1
                keepAlive?.interrupt()
                keepAlive = null
                return
            }
        }
        syncTouchWatch()
    }

    override fun hide() {
        // First: nothing may adopt the panel back from here (adoptPlaced).
        hiddenOnPurpose = true
        // The panel's ACTION_FINISH is the app's to send (ShizukuBridge.hide): the panel's
        // receiver takes nothing from outside the app — once exported for this shell uid,
        // any app on the phone could feed it fake state or take it down.
        placedDisplayId = -1
        // Nothing of ours on the rear any more: no touches read, no sheet over it.
        syncTouchWatch()
        closeSheetNow("the panel was hidden")
        // A hidden panel is a panel nobody wants back: the keep-alive would otherwise read
        // the empty rear display as "taken" and re-place it within a tick.
        keepAlive?.interrupt()
        keepAlive = null
        // And with the keep-alive gone nothing would ever see a guest leave, so its frame
        // and its watcher would run on for good. The guest app itself is left where it is.
        // Always under the lock, so a launch in flight finishes first and is then undone,
        // rather than placing its guest after this looked and found none.
        synchronized(placing) { if (guest != null) dropGuest("the panel was hidden") }
        // dropGuest resets it when there was a guest; this covers there being none.
        syncDensity("the panel was hidden")
    }

    /**
     * Oneway (IRearService): the app calls it from its main thread, and this runs execs (the
     * broadcast, perhaps `am stack list`, perhaps `wm density`) that took that thread with it.
     */
    override fun update(payload: String) {
        // Kept like show()'s, so a later show(lastPayload) — endGuest's — does not bring
        // back an older config (its portalDpi with it).
        lastPayload = payload
        styleFrame(payload)
        // The panel gets the payload from the app itself (ShizukuBridge.update) — see hide().
        // Only update()d, a panel this service did not place would never be recorded
        // ([adoptPlaced]). One `am stack list`, and only until it is.
        if (placedDisplayId < 0 && !companion) {
            val list = exec("am stack list")
            val task = StackList.parseTaskId(list, PKG, OwnPanel.ACTIVITY)
            if (task > 0 && GuestTask.byId(list, task)?.display == Panel.DISPLAY_ID) adoptPlaced(Panel.DISPLAY_ID, "update")
        }
    }

    /**
     * Whether the panel is asleep on purpose.
     *
     * Volatile because the keep-alive thread reads it and a binder thread writes it.
     */
    @Volatile private var dozing = false

    override fun setDozing(dozing: Boolean) {
        this.dozing = dozing
        // The back going to sleep locks it again (BackLock): the next look asks for the code.
        if (dozing) synchronized(lockGuard) { rearLock.dark() }
        // The panel's own sleep timer can run out while it sits behind a guest; that is
        // not the rear going dark, and the guest on top keeps its frame. A rear that really
        // is dark is seen by the keep-alive (nothing on top) and hides it there.
        val guestOnTop = guest?.onTop == true
        if (dozing && !guestOnTop) chromeHide() else if (!dozing && guestOnTop) chromeShow()
        // A dark rear: the strip's colour is not read.
        strip.dark = dozing && !guestOnTop
        // A sheet is never left up over a rear going to sleep: its blocker would outlive it.
        if (dozing) closeSheetNow("the panel is asleep")
        // Letting go on purpose: the guest hold goes before anything else can see it held.
        if (dozing) syncGuestHoldNow()
        // Said by every new panel as it starts: one that has not heard it is covered is told.
        else if (panelCovered) tellPanelCovered()
        syncTouchWatch()
        Log.i(TAG, if (dozing) "standing down — the panel is asleep on purpose" else "resuming")
    }

    /**
     * The guest is in front of the panel on the rear, as last seen (the keep-alive and every
     * guest change keep [Guest.onTop]); not one hidden on the front. No command run: the app
     * asks this at the lock, on its main thread, to decide always-on's relight (LockRelight).
     */
    override fun guestOnTop(): Boolean = guest?.let { it.onTop && !it.hiddenOnFront } == true

    /**
     * [wake], unless the other build's panel is on top of [displayId] — then nothing, and
     * false. For what the app process lights on its own (a notification, the charger,
     * always-on after the lock): those are this install's, and the back is not.
     */
    override fun wakeOwn(displayId: Int): Boolean {
        // In a pocket: what the app lights on its own — a notification, the charger, always-on
        // after the lock — would light the back inside it. The app declines these too
        // (ShizukuBridge.wake); this is for an app process new since the pocket began.
        if (pocketed) {
            Log.i(TAG, "pocket: the back is in a pocket — not lighting it")
            return false
        }
        if (otherBuildOnTop(displayId)) return false
        wake(displayId)
        return true
    }

    override fun wake(displayId: Int) {
        // An explicit wake is somebody deciding the panel should be lit, which ends the
        // sleep by definition. Not gated on `dozing` — gating it here would make the
        // notification that wakes a dozing panel the one thing that cannot. The pocket's
        // too: asked for ("Show on the back"), the back is lit, pocket or not, and the app's
        // pocket watch switches it off again if it is still covered.
        dozing = false
        pocketed = false
        syncTouchWatch()
        // Required, for a placement that is to end lit. The activity may be created on
        // display 0, where the screen is already on, so FLAG_TURN_SCREEN_ON has nothing
        // to act on; without this the task is placed and focused but the panel stays
        // state=OFF. Which placements are to end lit is PlaceLight's to say, not this.
        exec("input -d $displayId keyevent KEYCODE_WAKEUP")
    }

    /**
     * The back is switched off for a pocket (pocket mode, PocketWatch in the app): [wakeOwn]
     * lights nothing until [pocketEnd], or until an explicit [wake] — somebody asking for the
     * back — ends it. Volatile: binder threads write it and read it.
     */
    @Volatile private var pocketed = false

    /**
     * Switch the rear off now, for a pocket. NOT `input -d <rear> keyevent KEYCODE_SLEEP`:
     * measured on the 17 Pro (SDK 37, OS4), that sleeps power group 0 — the FRONT — whatever
     * display it names. `cmd power sleep --group-id` sleeps one power group alone, at once,
     * and it stays off: "Sleeping power group (groupId=1, uid=2000, reason=application)",
     * the front untouched. Before this the panel only let go of the display, and the back
     * stayed lit in the pocket for HyperOS's ten-second rear timeout.
     *
     * Not over the other build's panel: the back is that install's.
     */
    override fun pocketSleep(displayId: Int): Boolean {
        if (displayId < 0 || otherBuildOnTop(displayId)) return false
        pocketed = true
        // Off before the sleep below, not after, so nothing of ours is holding the back up.
        syncGuestHoldNow()
        // A sheet is not left up over a back going dark — setDozing says the same when the
        // panel's own word arrives, but that is a hop through two processes behind this.
        closeSheetNow("the back is in a pocket")
        val run = execChecked("cmd power sleep --group-id ${rearPowerGroup(displayId)}")
        if (run !is Run.Ok) {
            pocketed = false
            Log.w(TAG, "pocket: could not switch the back off ($run)")
            return false
        }
        Log.i(TAG, "pocket: the back is covered — switched off")
        return true
    }

    override fun pocketEnd(displayId: Int, light: Boolean) {
        pocketed = false
        if (!light || displayId < 0) {
            Log.i(TAG, "pocket: called off — the back stays as it is")
            return
        }
        // Asked for, by the pocket coming off it: the ordinary wake, the rear alone.
        Log.i(TAG, "pocket: out of the pocket — switching the back on")
        wake(displayId)
    }

    /**
     * CAMERA MODE: is the rear the camera's (or a guest's) right now? The guest on top, the
     * camera this service opened there ([cameraUp]), or the camera app's own rear preview —
     * com.android.camera focused on the rear display (the check [sendCameraBack] makes), or its
     * window on top of the rear's layers. The app then leaves the back to it.
     */
    override fun cameraOnRear(displayId: Int): Boolean {
        if (displayId < 0) return false
        if (guestOnTop() || cameraUp() || (displayId == rearDisplay() && RearCameraRoute.isOurs(rearFocus))) return true
        val focused = FocusDump.focusedPackage(exec("dumpsys window displays | grep -E 'mDisplayId=|mFocusedApp='"), displayId)
        if (focused == CAMERA_PACKAGE) return true
        return rearTopPackage() == CAMERA_PACKAGE
    }

    /** The package on top of the rear's layers (SurfaceDump.topPackage), the camera and the panel named. */
    private fun rearTopPackage(): String? {
        val rear = rearPhysicalId()
        return SurfaceDump.topPackage(
            exec("dumpsys SurfaceFlinger"),
            isRear = { id -> if (rear != null) id == rear else id.endsWith(REAR_PHYSICAL_ID_SUFFIX) },
            known = listOf(CAMERA_PACKAGE, PKG),
        )
    }

    /**
     * A camera in use on the phone, as the app last said (CameraWatch), and since when. While
     * it is, the keep-alive also asks whether the camera is focused on the rear ([cameraHasRear]).
     * Bounded like [cameraUp]: an app process that died mid-session cannot stop the keep-alive
     * for good.
     */
    @Volatile private var cameraInUse = false
    @Volatile private var cameraInUseAt = 0L

    override fun setCameraInUse(inUse: Boolean) {
        if (inUse == cameraInUse) return
        cameraInUse = inUse
        cameraInUseAt = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, if (inUse) "camera: in use — the keep-alive leaves the camera's own rear preview alone" else "camera: no longer in use")
    }

    /**
     * Something not ours has the lit rear, and it is the camera's own: the camera this service
     * opened there ([cameraUp]), com.android.camera on top of the rear's layers, or — while a
     * camera is in use on the phone — com.android.camera focused on the rear display. The
     * keep-alive does not re-place the panel over it. A camera in use is NOT by itself the
     * camera having the rear: HyperOS's own rear launcher can sit there during a front camera
     * session, and the panel goes back over that like over anything else. [cameraInUse] only
     * decides whether the extra focus dump is worth taking.
     */
    private fun cameraHasRear(displayId: Int): Boolean {
        if (cameraUp() || (companion && CameraExit.isCamera(rearFocus))) return true
        if (rearTopPackage() == CAMERA_PACKAGE) return true
        if (!(cameraInUse && android.os.SystemClock.elapsedRealtime() - cameraInUseAt < BAND_WATCH_MS)) return false
        return FocusDump.focusedPackage(exec("dumpsys window displays | grep -E 'mDisplayId=|mFocusedApp='"), displayId) == CAMERA_PACKAGE
    }
    @Volatile private var cameraHadRear = false

    /**
     * Camera mode's double tap: the rear off now, as the pocket switches it off ([pocketSleep])
     * but without its after-effects — wakes go on working; the app itself declines its own
     * until the camera closes (ShizukuBridge.cameraDark).
     */
    override fun allowListener(component: String?): Boolean {
        val full = ListenerGrant.normalised(component.orEmpty(), PKG)
        val cmd = full?.let { ListenerGrant.command(it, PKG) }
        if (full == null || cmd == null) {
            Log.w(TAG, "allow_listener refused for \"$component\": not this app's own listener")
            return false
        }
        val run = execChecked(cmd)
        val allowed = ListenerGrant.allowed(exec("settings get secure enabled_notification_listeners"), full)
        Log.i(TAG, "allow_listener $full: $run, allowed now: $allowed")
        return allowed
    }

    override fun xiaomiNoticeText(): Boolean =
        exec("settings get secure $XIAOMI_NOTICE_DETAIL").trim() == "1"

    override fun setXiaomiNoticeText(on: Boolean): Boolean {
        val run = execChecked("settings put secure $XIAOMI_NOTICE_DETAIL ${if (on) 1 else 0}")
        Log.i(TAG, "xiaomi rear notification text ${if (on) "on" else "off"}: $run")
        return run is Run.Ok && xiaomiNoticeText() == on
    }

    override fun rearSleep(displayId: Int): Boolean {
        if (displayId < 0 || otherBuildOnTop(displayId)) return false
        closeSheetNow("the back is switched off for the camera")
        val run = execChecked("cmd power sleep --group-id ${rearPowerGroup(displayId)}")
        if (run !is Run.Ok) {
            Log.w(TAG, "camera: could not switch the back off ($run)")
            return false
        }
        Log.i(TAG, "camera: double tap — the back switched off until the camera closes")
        return true
    }

    /**
     * The rear display's power group, for `cmd power sleep --group-id`. The rear is the only
     * display outside the default group on this phone, and it is group 1 — seen in the power
     * manager's own log for it ("goToSleepInternal[groupId =1]") — so 1 it is, not read from
     * `dumpsys display` per sleep: a pocket wants the back off now, not after a dump. The
     * default display is the one display that cannot be the answer: that is the front.
     */
    private fun rearPowerGroup(@Suppress("UNUSED_PARAMETER") displayId: Int): Int = REAR_POWER_GROUP

    /**
     * Whether [displayId] is lit right now. Read before a placement, since afterwards
     * nothing can tell a back that was lit from one the placement lit. Null when it cannot
     * be read (no Context — Shizuku before 13 — or no such display), which PlaceLight takes
     * as dark.
     */
    private fun rearLit(displayId: Int): Boolean? = displayStates.fresh(displayId)?.let { it == android.view.Display.STATE_ON }

    /**
     * Every read of a display's state in this process, kept with its time (DisplayStates): the scene's and the charging
     * page's look at every picture takes the last one while it is under [LIGHT_LOOK_MS] old.
     */
    private val displayStates by lazy {
        DisplayStates({ id ->
            runCatching { context?.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(id)?.state }.getOrNull()
        }, android.os.SystemClock::uptimeMillis)
    }

    /** Whether the rear is lit by a reading under [LIGHT_LOOK_MS] old (read now if there is none): for a look at every picture. */
    private fun rearLitRecently(): Boolean = displayStates.recent(rearDisplay(), LIGHT_LOOK_MS) == android.view.Display.STATE_ON

    /** show() and ensureOnRear() both place; two at once moved the same task twice and the second threw. */
    private val placing = Any()

    /** A recovery: see [place]. The app's watchdog (an older app), the keep-alive and endGuest come here. */
    override fun show(payload: String, displayId: Int) = place(payload, displayId, false)

    /**
     * Place the panel, and end with the rear lit only as [PlaceLight] says: when [light] was
     * asked for, or when it was lit already and the panel is not letting it go.
     *
     * It used to end with an unconditional wake, and every recovery lit the back: a new app
     * process (unbound, so the panel read as dead), an update, a swipe from recents, the
     * keep-alive's "no panel and no task" — measured as a back lit in a pocket after HyperOS
     * restarted the app.
     */
    override fun place(payload: String, displayId: Int, light: Boolean) = synchronized(placing) {
        if (displayId < 0) return@synchronized
        // Companion mode places nothing: Xiaomi's rear screen stays. The payload's own word, so
        // the switch to Replace mode (its payload, then this) is not refused on the old one.
        if (companionIn(payload)) {
            Log.i(TAG, "companion mode — not placing a panel over Xiaomi's rear screen")
            styleFrame(payload)
            return@synchronized
        }
        // THE OTHER BUILD HAS THE BACK: a recovery does not take it. Release and dev can both
        // be installed, each with a shell of its own, and each re-placed itself over the other
        // every few seconds. Only a placement somebody asked for in THIS install takes the
        // back over ("Show on the back") — that is how the user switches between them.
        if (!light && otherBuildOnTop(displayId)) return@synchronized
        // FIRST, before anything is started or moved: once the panel's start has gone
        // through, a lit rear and a rear it lit look the same.
        val lightIt = PlaceLight.wake(light, rearLit(displayId), dozing)
        RearKeyboard.showOn(displayId)
        hiddenOnPurpose = false
        lastPayload = payload
        styleFrame(payload)
        startKeepAlive(displayId)
        // A guest on a private display is up over the panel, which is where it should be and
        // reads as anything but placed: our layers cover it in what the rear composes. A recovery
        // re-placing it there changes nothing anyone sees but its own state (the service that lost
        // track of one did, 2026-10-30). Somebody asking for the panel ([light]) goes on below,
        // and that does hide the guest.
        if (!light && guest?.let { it.onPrivate && it.onTop } == true) return@synchronized
        // The cheap path — already placed, just push new numbers — must ALSO require
        // that the panel is genuinely composited. Checking only "same display and the
        // task exists" is how a silently-dead panel became unrecoverable: the task
        // outlives composition, so every subsequent show() took this branch and
        // broadcast an update to a window nobody was drawing, instead of relaunching.
        if (placedDisplayId == displayId && taskId() > 0 && panelWhere() == Where.REAR) {
            // The payload still goes through — the panel should be correct whenever it is
            // next seen — but it is NOT lit for it. A status update is not a reason to
            // wake a display somebody deliberately let go, nor one that went dark by itself.
            update(payload)
            if (lightIt) wake(displayId)
            syncTouchWatch()
            return
        }
        // About to come up over the guest, if there is one: it is no longer on top.
        val g = guest
        if (g != null) {
            if (markOnTop(g.task, false)) syncSheetGuest()
            chromeHide()
        }
        // Straight onto the rear display first: the manifest's miui.rear.policy makes
        // HyperOS allow it (ActivityStarterImpl logs "allow app"), so the panel never
        // touches the front screen. HyperOS still aborts the start while the keyguard
        // is locked, for every app outside its own list — then no task appears, and the
        // old way is taken: start on the front screen, move the task across.
        exec("am start --display $displayId -n $PKG/$ACTIVITY --es state ${shellQuote(payload)}")
        var task = pollTaskId()
        // A start HyperOS aborts still leaves a task record for a moment, so one look
        // is not proof; the second, a beat later, is — an aborted start has no task
        // and nothing composited on the rear by then.
        if (task > 0 && panelWhere() == Where.REAR) {
            Thread.sleep(DIRECT_START_SETTLE_MS)
            task = taskId()
        }
        if (task > 0 && panelWhere() == Where.REAR) {
            Log.d(TAG, "task=$task started on display $displayId directly")
        } else {
            if (task <= 0) {
                Log.i(TAG, "start on display $displayId was refused (keyguard?) — starting on the front and moving")
                exec("am start -n $PKG/$ACTIVITY --es state ${shellQuote(payload)}")
                task = pollTaskId()
                if (task <= 0) { Log.e(TAG, "RearActivity task never appeared"); syncDensity("the panel never came up"); return }
            }
            val moved = moveToDisplay(task, displayId)
            Log.d(TAG, "move task=$task -> display=$displayId : $moved")
        }

        // A command that did not complain still proves only that it was accepted, not
        // that the panel is on screen. Confirm with the window manager.
        // Composition-level check first: it is the only one that has never lied.
        // A failed move is now corrected by moving again rather than by tearing the
        // panel down — the user asked for recovery, not eviction, and a second move
        // usually lands when the first did not.
        // Never hide() from here. Both branches used to, and they were the last
        // teardown paths left after verifyPlacement's and the activity's own eviction
        // guard were removed. They fired in the normal case, not an exceptional one:
        // immediately after `am start` the layer is often not composited yet, so
        // panelWhere() answers NOWHERE rather than MAIN, which fell through to a
        // `dumpsys window displays` read (since removed) — the one that has lied in every
        // stranding incident in this project and also simply lags a fresh placement.
        // A false reading there destroyed the panel we had just created, and the
        // watchdog then rebuilt it, which is the panel dying for no reason.
        //
        // A second move is the whole remedy. If it does not take, leaving the activity
        // alone is strictly better than killing it: ensureOnRear() runs every keep-alive
        // tick and will move it later, whereas a finished activity has to be relaunched
        // through display 0.
        if (panelWhere() == Where.MAIN) {
            Log.w(TAG, "compositing on the MAIN display — re-issuing the move")
            moveToDisplay(task, displayId)
            if (lightIt) wake(displayId)
        }
        syncDensity("the panel is placed")
        placedDisplayId = displayId
        // Lit only as PlaceLight said; the touch watch either way (wake starts it too).
        if (lightIt) wake(displayId) else Log.i(TAG, "placed on display $displayId — the rear left dark, as it was")
        syncTouchWatch()
    }

    /**
     * Launch the system camera onto the rear display.
     *
     * The component and flags are MIUI's own, read off a live trace of opening the rear
     * camera by hand: com.xiaomi.subscreencenter starts com.android.camera/.Camera with
     * these five flags and no extras, and the camera app then brings up its own
     * companion UI on the front by itself. Nothing here has to arrange that half.
     *
     * Launching straight at the rear display works for the camera where it does not for
     * this app's own panel — which needs the start-then-move dance above — because the
     * restriction is on the caller, not the display. From shell uid it is simply allowed.
     *
     * The panel is not dismissed first: the camera stacks on top of it, and closing the
     * camera reveals it again. That is measured behaviour, not an assumption.
     */
    override fun openCamera(displayId: Int): Boolean = openCamera(displayId, fromMoon = false)

    private fun openCamera(displayId: Int, fromMoon: Boolean): Boolean {
        if (displayId < 0) return false
        // The back is the camera's from here: a sheet up would sit over it with its blocker.
        closeSheetNow("the camera is opening")
        // Which would come up on the rear under a private display's layer.
        guest?.takeIf { it.onPrivate }?.let { if (markOnTop(it.task, false)) guestBehind(it.pkg) }
        if (fromMoon && openOwnCamera(displayId)) return true
        val out = exec("am start --display $displayId -f $CAMERA_FLAGS -n $CAMERA_COMPONENT")
        val ok = !out.contains("Error", ignoreCase = true)
        if (!ok) Log.w(TAG, "camera launch refused: ${out.take(200)}")
        if (ok) {
            cameraLaunched = true
            cameraWasOnTop = false
            cameraLaunchedAt = android.os.SystemClock.elapsedRealtime()
            watchBandTaps(displayId)
            // Its fingers read even with the panel asleep under it: the pulls close it (closeCameraFromBack).
            syncTouchWatch()
        }
        return ok
    }

    /** PanelConfig.cameraOwnRear, from the last payload. */
    @Volatile private var cameraOwnRear = true

    private fun openOwnCamera(displayId: Int): Boolean {
        val granted = runCatching {
            context?.packageManager?.checkPermission(android.Manifest.permission.CAMERA, PKG) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val locked = runCatching {
            context?.getSystemService(android.app.KeyguardManager::class.java)?.let { it.isKeyguardLocked || it.isDeviceLocked }
        }.getOrNull() ?: keyguardShowing()
        val (route, why) = RearCameraRoute.route(cameraOwnRear, granted, locked, displayId)
        if (route != RearCameraRoute.Route.OURS) {
            Log.i(TAG, "camera: Xiaomi's Camera — $why")
            return false
        }
        val asked = android.os.SystemClock.uptimeMillis()
        val out = exec("am start $NO_SLIDE --display $displayId -n ${RearCameraRoute.COMPONENT}")
        if (!RearCameraRoute.accepted(out)) {
            Log.w(TAG, "camera: our camera page was refused (${out.take(160)}) — Xiaomi's Camera")
            return false
        }
        while (android.os.SystemClock.uptimeMillis() - asked < RearCameraRoute.LAUNCH_WAIT_MS) {
            try { Thread.sleep(RearCameraRoute.LAUNCH_LOOK_MS) } catch (_: InterruptedException) { return false }
            val top = RearCover.focusOf(exec(RearCover.READ), displayId)
            if (RearCameraRoute.arrived(top)) {
                Log.i(TAG, "camera: our camera page is on the back (${android.os.SystemClock.uptimeMillis() - asked} ms)")
                if (top != rearFocus) holdHandler.post { runCatching { readRearFocus() } }
                return true
            }
        }
        Log.w(TAG, "camera: our camera page did not come up on the back within ${RearCameraRoute.LAUNCH_WAIT_MS} ms — Xiaomi's Camera")
        return false
    }

    /**
     * Closing the camera from the back: a tap in its empty grey band.
     *
     * The camera's rear-screen layout has no way out of its own, and nothing can be drawn
     * over it — HyperOS refuses windows on the rear display to apps outside a short
     * whitelist. So the tap is read from the rear touchscreen itself, which shell may read,
     * for as long as the camera we opened is up (see [BandTap]); the camera ignores taps
     * there. The answer is Back to the rear display, the camera's own exit, which takes its
     * front-screen half with it and finishes a recording properly — sent only while the
     * camera has focus there, so it never reaches anything else.
     */
    @Volatile private var bandTaps: Process? = null

    private fun watchBandTaps(displayId: Int) {
        stopBandTaps()
        val device = BandTap.rearDevice(exec("getevent -pl"))
        if (device == null) {
            Log.w(TAG, "camera close: no rear touchscreen in getevent — the camera closes from the front only")
            return
        }
        val (path, maxX, maxY) = device
        // The zone is the display's own left inset, the lens column, as the system reports
        // it for this model (RearGeometry); the 17 Pro's when it does not say.
        val band = BandTap.band(geometry)
        val proc = try {
            ProcessBuilder("sh", "-c", "exec getevent $path").redirectErrorStream(true).start()
        } catch (e: Exception) {
            Log.w(TAG, "camera close: getevent failed: ${e.message}")
            return
        }
        bandTaps = proc
        val since = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "camera close: watching $path for a tap in the left ${"%.1f".format(band * 100)}%")
        guarded {
            val tap = BandTap(maxX, maxY, band)
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - since > BAND_WATCH_MS) break
                        // NOT OVER AFTER ONE TAP. A Back may only take down something inside
                        // the camera — its pocket warning, a recording it stops first — and
                        // the camera stays; the watch used to end there, and every tap after
                        // it went nowhere. It ends when the panel is back in front.
                        if (tap.feed(line, now) && !sendCameraBack(displayId)) break
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (bandTaps === proc) stopBandTaps()
            }
        }.apply { isDaemon = true; name = "camera-band-taps"; start() }
        // getevent blocks while nobody touches the screen, so the time limit needs its own clock.
        guarded {
            try { Thread.sleep(BAND_WATCH_MS) } catch (_: InterruptedException) { return@guarded }
            if (bandTaps === proc) stopBandTaps()
        }.apply { isDaemon = true; name = "camera-band-taps-limit"; start() }
    }

    /**
     * The camera we opened closed from the back by a pull or a swipe: Back to it, and its task gone
     * if Back is not enough ([sendCameraBack], the band tap's own way). Off the touch reader.
     */
    private fun closeCameraFromBack(why: String) {
        Log.i(TAG, "camera close: $why")
        runCatching { pokeWorker.execute { sendCameraBack(rearDisplay()) } }
    }

    private fun stopBandTaps() {
        bandTaps?.let { runCatching { it.destroy() } }
        bandTaps = null
    }

    /**
     * A tap in the zone: Back to the camera if it has the rear display's focus. False when
     * the watch is over — the panel has the focus again, so the camera is gone. Anything
     * else in focus, a system warning say, gets no Back and the watch goes on.
     */
    private fun sendCameraBack(displayId: Int): Boolean {
        val focused = FocusDump.focusedPackage(exec("dumpsys window displays | grep -E 'mDisplayId=|mFocusedApp='"), displayId)
        if (focused != CAMERA_PACKAGE) {
            val over = focused == PKG
            Log.i(TAG, "camera close: display $displayId has $focused in focus, not the camera — no Back" +
                if (over) "; the panel is back, the watch ends" else "; still watching")
            // The camera is over: released if it was seen up, else only forgotten — either
            // way the back is no longer the camera's (cameraUp).
            if (over) { if (cameraWasOnTop) closeCamera() else cameraLaunched = false }
            return !over
        }
        Log.i(TAG, "camera close: a tap in the zone — Back to display $displayId")
        exec("input -d $displayId keyevent KEYCODE_BACK")
        // Seen in front, so the panel coming back next releases its process — see below.
        if (cameraLaunched) cameraWasOnTop = true
        // AND IF BACK WAS NOT ENOUGH, THE TASK GOES. The camera decides what Back does: its
        // pocket warning takes it, so does a recording it stops first, and something it
        // shows that we cannot see might take every one. A tap here means "close the
        // camera", so if it still has the display a moment later its task is removed —
        // the same as swiping it away in recents: the activity is paused, stopped and
        // destroyed, a recording finishes the way it does when Home is pressed, and the
        // front-screen half goes with it (measured: both tasks gone, the panel in focus).
        guarded {
            try { Thread.sleep(BACK_SETTLE_MS) } catch (_: InterruptedException) { return@guarded }
            val dump = exec("dumpsys window displays | grep -E 'mDisplayId=|mFocusedApp='")
            if (FocusDump.focusedPackage(dump, displayId) != CAMERA_PACKAGE) return@guarded
            val task = FocusDump.focusedTask(dump, displayId) ?: return@guarded
            Log.i(TAG, "camera close: still up after Back — removing its task $task")
            exec("am stack remove $task")
        }.apply { isDaemon = true; name = "camera-close-settle"; start() }
        return true
    }

    /**
     * The camera we started, and whether we have yet seen it in front of the panel.
     *
     * NOTHING EVER CLOSED IT. The camera was launched onto the rear display and left
     * there: dismissing it takes its UI away and hands the display back, but the process
     * stays resident with its task still stacked under the panel. The hardware is
     * released — the camera service logs the DISCONNECT — so this is not a sensor left
     * running, but it is an application kept alive for nothing and a task that can be
     * resumed by accident.
     *
     * The pair of flags is what makes closing it safe. Seeing the panel on top is not on
     * its own evidence the camera is finished with; the panel is on top for the whole
     * time before the camera has come up too. Only a panel that is on top AFTER the
     * camera was seen in front of it means the user has closed it.
     */
    @Volatile private var cameraLaunched = false
    @Volatile private var cameraWasOnTop = false
    /**
     * When [cameraLaunched] was set (elapsedRealtime ms: the camera's clocks run on through
     * deep sleep, which uptime does not): a launch never seen in front is forgotten after
     * [CAMERA_UNSEEN_MS].
     */
    @Volatile private var cameraLaunchedAt = 0L

    /**
     * The panel in front, and the camera we launched never seen ahead of it for
     * [CAMERA_UNSEEN_MS]: opened and closed between two ticks, or closed by the strip's own
     * Back. The flags would otherwise hold [cameraUp] — no sheet — forever.
     *
     * NOT SEEN IS NOT GONE. Opened and Backed out of inside one tick (1.3 s, seen
     * 2026-10-30: Xiaomi's edge Back, which the camera answers by moving its own task back)
     * left both its tasks alive — the front-screen half waiting on the front the next time
     * the phone was turned over — and this only forgot it. So a camera with tasks still
     * stacked is closed as a seen one is (closeCamera); with none, there is nothing to do.
     */
    private fun forgetUnseenCamera() {
        if (!cameraLaunched || cameraWasOnTop) return
        if (android.os.SystemClock.elapsedRealtime() - cameraLaunchedAt < CAMERA_UNSEEN_MS) return
        if (StackList.taskIdsOf(exec("am stack list"), CAMERA_PACKAGE).isNotEmpty()) {
            Log.i(TAG, "camera: never seen in front, but its tasks are still there — closing it")
            closeCamera()
            return
        }
        Log.i(TAG, "camera: launched but never seen in front, and the panel is — forgotten")
        cameraLaunched = false
        stopBandTaps()
    }

    /**
     * The camera was closed on the back: both its halves go, then its process.
     *
     * THE FRONT HALF STAYED. The camera opened on the back brings up a companion on the
     * front screen (MainScreenSelfieActivity, a task of its own), and closing the camera on
     * the back does not always take it along: measured on OS4.0.5.0, when the camera leaves
     * the rear by moving its own task back rather than through our Back, the companion
     * stays focused on the front, and that kept the process foreground, so the `am kill`
     * below declined and the camera was waiting on the front the next time the phone was
     * turned over. Its tasks are removed first — the same as swiping them away in recents,
     * which ends a recording the way Home does (see sendCameraBack) — and with no task left
     * nothing is foreground any more.
     *
     * `am kill` rather than `am force-stop` after that, still: kill declines to touch a
     * process the system considers foreground, so a recording still being written out keeps
     * its process. Getting this wrong costs somebody their video; getting it too gently
     * costs one resident process until the next time.
     */
    private fun closeCamera() {
        stopBandTaps()
        cameraLaunched = false
        cameraWasOnTop = false
        val tasks = StackList.taskIdsOf(exec("am stack list"), CAMERA_PACKAGE)
        Log.i(TAG, "camera dismissed and the panel is back — removing its tasks $tasks and releasing it")
        for (task in tasks) exec("am stack remove $task")
        exec("am kill $CAMERA_PACKAGE")
    }

    /**
     * Pulls a stranded panel back onto the rear display. See the AIDL doc for why this
     * cannot live inside show().
     *
     * Deliberately does nothing unless the panel is definitely on the MAIN display. An
     * unknown answer (panel asleep, layer not drawn, unparseable dump) must not move a
     * task: this runs every watchdog tick, and a check that acts on uncertainty would
     * shove a correctly-placed panel around the display topology every 20 seconds.
     *
     * DISPLACED is not this function's problem either, and that is not an oversight: the
     * task is already on the rear display, merely behind something, and moving a task to
     * the display it is already on does not raise it. That case needs a full placement —
     * see the keep-alive.
     */
    override fun ensureOnRear(displayId: Int): Boolean = synchronized(placing) {
        if (displayId < 0) return@synchronized false
        // Run on USER_PRESENT too: a guest hidden behind the keyguard is on the front now.
        unhideIfExposed("the front was unlocked")
        val where = panelWhere()
        if (where != Where.MAIN) {
            strandStrikes = 0
            if (where == Where.REAR) adoptPlaced(displayId, "ensureOnRear")
            return false
        }
        val task = taskId()
        if (task <= 0) return false
        strandStrikes++
        Log.w(TAG, "task $task compositing on the MAIN display (strike $strandStrikes) — moving it back")
        // A recovery like any other (see [place]): the lock is what hoists the panel onto the
        // front, and the back went dark with that lock — moved back, it stays dark.
        val lightIt = PlaceLight.wake(false, rearLit(displayId), dozing)
        moveToDisplay(task, displayId)
        if (lightIt) wake(displayId)
        adoptPlaced(displayId, "ensureOnRear")

        // Last resort. If repeated moves will not get it off the main display, the
        // panel is sitting on top of whatever the user is actually doing, and no
        // amount of patience improves that. Tearing it down is the lesser harm — the
        // keep-alive re-places it afterwards, and a panel that flickers is better than
        // one parked over the foreground app.
        if (strandStrikes >= MAX_STRAND_STRIKES) {
            Log.e(TAG, "still on the main display after $strandStrikes moves — finishing")
            strandStrikes = 0
            hide()
        }
        return true
    }

    /**
     * Whether SurfaceFlinger is actually COMPOSITING RearActivity on the rear panel —
     * see [SurfaceDump] for why composition is the signal, and what each answer means.
     */
    private fun panelWhere(): Where {
        val rear = rearPhysicalId()
        return SurfaceDump.where(exec("dumpsys SurfaceFlinger"), PKG) { id ->
            if (rear != null) id == rear else id.endsWith(REAR_PHYSICAL_ID_SUFFIX)
        }
    }

    /**
     * The rear display as it really is (RearGeometry): its size, lens column, corner and
     * density, the one source the shell takes any of them from. Read the first time anything
     * needs it and kept — it belongs to the panel, so it never changes: display 1 as the
     * window manager has it first (the frame task bounds and layers are in), `dumpsys
     * display`'s device line for what that does not say and for the density (the Display's
     * is the override a guest may have set), the 17 Pro's for the rest. Logged with what
     * each said, so a Pro Max's first run says what it read. Kept only once a source has said
     * its size (RearGeometry.known): with neither read, the 17 Pro's stands in and the rear is
     * asked again after [GEOMETRY_RETRY_MS] — the frame and the strip, made once, keep the
     * geometry they were made with.
     */
    private val geometry: RearGeometry
        get() {
            knownGeometry?.let { return it }
            val now = android.os.SystemClock.uptimeMillis()
            fallbackGeometry?.takeIf { now < geometryRetryAt }?.let { return it }
            synchronized(geometryLock) {
                knownGeometry?.let { return it }
                fallbackGeometry?.takeIf { now < geometryRetryAt }?.let { return it }
                val display = runCatching {
                    context?.getSystemService(android.hardware.display.DisplayManager::class.java)
                        ?.getDisplay(Panel.DISPLAY_ID)?.let(RearGeometry::reading)
                }.onFailure { Log.w(TAG, "rear geometry: display ${Panel.DISPLAY_ID} unreadable: ${it.message}") }.getOrNull()
                val dump = runCatching {
                    rearPhysicalId()?.let { DisplayDump.device(exec("dumpsys display | grep 'DisplayDeviceInfo'"), it) }
                }.getOrNull()
                val g = RearGeometry.resolve(display, dump)
                val known = RearGeometry.known(display, dump)
                if (known || fallbackGeometry == null) {
                    Log.i(TAG, "rear geometry: ${g.widthPx}x${g.heightPx}, lenses to x ${g.cutoutPx}, corner ${g.cornerPx}, " +
                        "${g.dpi} dpi (fit ${"%.4f".format(g.fitX)} x ${"%.4f".format(g.fitY)}) — display said $display, dumpsys $dump" +
                        if (known) "" else " — neither said its size: the 17 Pro's for now, asked again later")
                }
                if (known) knownGeometry = g
                else { fallbackGeometry = g; geometryRetryAt = now + GEOMETRY_RETRY_MS }
                return g
            }
        }
    @Volatile private var knownGeometry: RearGeometry? = null
    /** The 17 Pro's stand-in while neither source says the rear's size, until [geometryRetryAt]. */
    @Volatile private var fallbackGeometry: RearGeometry? = null
    @Volatile private var geometryRetryAt = 0L
    private val geometryLock = Any()

    /** The rear display's physical id, once read. It belongs to the panel, so it never changes. */
    @Volatile private var knownRearId: String? = null
    private var warnedNoRearId = false

    /**
     * The physical id SurfaceFlinger knows the rear display by, read from the system.
     *
     * It was a constant — the tail of the 17 Pro's rear panel id — and on a Pro Max,
     * whose rear display is another panel, nothing matched it: the panel on the back read
     * as on the MAIN display, every tick tried to move it to the display it was already
     * on, the system refused, and three refusals finished it as stranded. See [SurfaceDump].
     *
     * Null when `dumpsys display` does not say, and then [panelWhere] falls back to the
     * 17 Pro's suffix, which is what it always did. Asked again next time rather than
     * remembered as missing: a dump that came back empty once is not a phone without one.
     */
    private fun rearPhysicalId(): String? {
        knownRearId?.let { return it }
        val dump = exec("dumpsys display | grep -E 'mDisplayId=|mPrimaryDisplayDevice='")
        val id = DisplayDump.physicalId(dump, Panel.DISPLAY_ID)
        if (id != null) {
            Log.i(TAG, "rear display ${Panel.DISPLAY_ID} is physical display $id")
            knownRearId = id
        } else if (!warnedNoRearId) {
            warnedNoRearId = true
            Log.w(TAG, "no physical id for display ${Panel.DISPLAY_ID} in dumpsys display — matching the 17 Pro's")
        }
        return id
    }

    private fun pollTaskId(): Int {
        repeat(20) {
            val id = taskId()
            if (id > 0) return id
            Thread.sleep(50)
        }
        return -1
    }

    private fun taskId(): Int = StackList.parseTaskId(exec("am stack list"), PKG, OwnPanel.ACTIVITY)

    /**
     * The other build's panel (release vs dev, see [OwnPanel]) is on top of [displayId]: the
     * back is that install's. Logged on the change only — a recovery asks every tick.
     */
    private fun otherBuildOnTop(displayId: Int): Boolean {
        val other = StackList.otherBuildOnTop(exec("am stack list"), PKG, displayId)
        if (other != otherBuildSeen) {
            otherBuildSeen = other
            Log.i(TAG, if (other) "the other build's panel has display $displayId — standing down until asked"
                else "the other build's panel no longer has display $displayId")
        }
        return other
    }
    @Volatile private var otherBuildSeen = false

    /** Single-quote for `sh -c`, escaping embedded single quotes. */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * The facts a bug report about the rear display needs, from the shell: the firmware,
     * every package that could own the rear screen and its state, the launcher
     * components registered for it, the rear-display settings, and what the window and
     * power managers say about display 1. Bounded by `head` everywhere; it is pasted
     * into a chat, not a file.
     */
    override fun rearReport(): String = exec(REPORT)

    override fun rearBrightness(): Int =
        exec("settings get system sub_display_screen_brightness").trim().toIntOrNull() ?: -1

    override fun rearAutoBrightness(): Boolean =
        exec("settings get secure sub_display_screen_brightness_mode").trim() == "1"

    override fun setRearAutoBrightness(on: Boolean) {
        exec("settings put secure sub_display_screen_brightness_mode ${if (on) 1 else 0}")
    }

    override fun quickStates(): String {
        val bt = exec("settings get global bluetooth_on").trim().let { it != "0" && it.isNotEmpty() && it != "null" }
        val wifi = exec("settings get global wifi_on").trim().let { it != "0" && it.isNotEmpty() && it != "null" }
        // Per SIM (mobile_data0, mobile_data1 …): on if any is.
        val data = exec("settings list global | grep -E '^mobile_data[0-9]+=1' | head -1").isNotBlank()
        fun b(v: Boolean) = if (v) "1" else "0"
        return "${b(bt)},${b(data)},${b(wifi)}"
    }

    /**
     * The App Portal's guest: the app we put on the rear, its task, whether it was last on
     * top. Null when there is none. Replaced whole under [placing]; only [Guest.onTop] is
     * changed off the lock, and only by compare-and-set against the guest it was read
     * from, so a reading that predates a launch can never write the old guest back.
     * [Guest.placed] is false until [guestPlaced]: while a launch waits to see whether the
     * task survives its move, the keep-alive leaves it and its frame alone.
     * [Guest.landed]: a launch has moved it onto the rear — from then on it is the sheet's
     * guest, buttons and slider ([Guest.sheetOnTop], PortalAction.sheetGuest), without waiting
     * the two seconds for [Guest.placed].
     * [Guest.hiddenOnFront]: the sheet's Panel moved it to display 0 behind the keyguard
     * (see [onSheetButton]); it is still the guest, behind, and Resume brings it back.
     * [privateDisplay]: the display it is on when it is kept right of the cameras
     * (PrivateDisplay), -1 when on the rear itself. [onTop] is then our own word: the layer that
     * shows it up or not ([syncPrivate]), the panel under it on the rear all along.
     */
    private data class Guest(
        val pkg: String, val task: Int, val onTop: Boolean, val placed: Boolean = false,
        val hiddenOnFront: Boolean = false, val landed: Boolean = false,
        val privateDisplay: Int = -1,
        /** On that display only for this open, having closed itself behind the lock (LockRetry); the config says otherwise. */
        val lockRetried: Boolean = false,
        /** That display over the whole rear (PrivateDisplay.open: the keyboard), not right of the cameras. */
        val wide: Boolean = false,
    ) {
        val onPrivate: Boolean get() = privateDisplay >= 0

        /** Right of the cameras, on a display of its own: the frame round it and the camera strip beside it. */
        val keptRight: Boolean get() = onPrivate && !wide

        /** As the sheet shows it: null none, true on top (Panel), false behind (Resume). */
        val sheetOnTop: Boolean? get() = PortalAction.sheetGuest(landed, placed, onTop)
    }
    private val guestRef = AtomicReference<Guest?>(null)
    private var guest: Guest?
        get() = guestRef.get()
        set(value) {
            guestRef.set(value)
            syncGuestHold()
            syncNoRestrict()
            syncPrivate()
        }
    private val frame by lazy { PortalFrame(rearLayerStack(), geometry) }
    /** The Back chevron over a guest on a private display, whose own Xiaomi cannot show (BackChevron). */
    private val backChevron by lazy { BackChevron(rearLayerStack(), geometry) }
    /** The camera strip beside a guest on a private display (CameraStrip), in the guest's edge colour. */
    private val strip by lazy { CameraStrip(rearLayerStack(), geometry, keyboard = { keyboardTop() }) { stripColor() } }

    /**
     * The guest's chrome up: its frame, and — on a private display — the camera strip beside
     * it (else the strip goes). In place of a bare frame.show() everywhere.
     */
    private fun chromeShow() {
        // A guest the back's lock covers shows nothing of itself, its frame neither.
        if (lockVeiled) { chromeHide(); return }
        frame.follow(if (guest?.keptRight == true) privateApp else null)
        strip.follow(privateApp, privateArea())
        frame.show()
        if (guest?.keptRight == true) strip.show(null) else strip.hide()
    }

    /** The guest's chrome down: frame and strip fade out. */
    private fun chromeHide() {
        frame.hide()
        strip.hide()
    }

    /** [onTop] on whatever guest has [task] now; true if this call changed it. */
    private fun markOnTop(task: Int, onTop: Boolean): Boolean {
        while (true) {
            val g = guestRef.get() ?: return false
            if (g.task != task || g.onTop == onTop) return false
            if (guestRef.compareAndSet(g, g.copy(onTop = onTop))) {
                syncGuestHold()
                syncPrivate()
                return true
            }
        }
    }

    /**
     * The pinned apps' names and icons, as the main app last sent them. Stored as received
     * (WebP bytes); ShellSheet.apps decodes them all at once on the binder thread that hands
     * them over, so drawing never decodes, and the snapshot keeps each decoded icon.
     * Replaced whole, never edited, so a reader holding one sees a consistent list.
     */
    @Volatile var portalApps: PortalAppsSnapshot = PortalAppsSnapshot.EMPTY
        private set

    override fun setPortalApps(apps: android.os.Bundle?) {
        portalApps = PortalAppsSnapshot.fromBundle(apps)
        shellSheet?.apps(portalApps)
        repage()
        // The cover's allowlist is these apps (RearCover.meant), the guard's through the hint file. Not before the
        // cover has been decided once: a new process gets these first, before its settings, and must not say "0".
        if (coverHintWritten) runCatching { syncCover("the apps on the back changed") }
    }

    /** The portal grid's size (PanelConfig.portalGrid), from the last payload. */
    @Volatile private var portalGrid = dev.kb896.aiusage.data.PortalGrid.R3C4
    /** The apps as a dome, not the grid (PanelConfig.portalDome), from the last payload. */
    @Volatile private var portalDome = false
    /** Where the dome is turned to, and going. Under [sheetLock]. */
    private val dome = DomeMotion()
    /** This finger is turning the dome. Under [sheetLock]. */
    private var domeHeld = false

    /** The apps' names under their icons on the back (PanelConfig.portalAppNames), from the last payload. */
    @Volatile private var portalNames = true
    /**
     * Apps kept right of the cameras (PanelConfig.portalConfined, with the switches made on the
     * back laid over it, [confinedEdits]); every other guest is fullscreen. Written under [dpiLock].
     */
    @Volatile private var portalConfined: Set<String> = emptySet()
    private val confinedEdits = ConfinedEdits()

    /**
     * The pinned apps or the grid changed: the pages follow, an open sheet's with them. On
     * [sheetClock]: this comes from show() and update() under [placing], and the reader may
     * hold the sheet's lock for a while (the sheet's first making loads the font).
     */
    private fun repage() {
        runCatching {
            sheetClock.execute {
                synchronized(sheetLock) {
                    sheetLayout().let { pager.stride = it.pageStride; pager.pages = it.pages }
                    if (sheetUp) shellSheet?.page(pager.position(android.os.SystemClock.uptimeMillis()))
                }
            }
        }
    }

    /** The rear display's layer stack, from dumpsys display, once per process (RearStack); 1 on the 17 Pro, as measured. */
    private fun rearLayerStack(): Int = RearStack.of(::exec)

    /**
     * The frame follows the config's colour, and the guest's density the config's DPI: a
     * new payload may carry a new value for the app that is the guest right now.
     */
    private fun styleFrame(payload: String) {
        if (CalibrateMode.isCalibrate(payload)) return
        try {
            val decoded = StatePayload.decode(payload)
            val config = decoded.config
            usageState = decoded.state
            usageNames = config.accounts.map { it.label }
            usageDemo = config.demoMode
            usageFiveHours = config.usageFiveHours
            usageDot = config.chatGptDot
            usage.changed()
            onPins(config.pins)
            frame.style(config.portalFrameColor)
            portalFrameColor = config.portalFrameColor
            lockedNoticeText = config.lockedNoticeText
            noticeTextWhenLocked = config.noticeTextWhenLocked
            chargeOn = config.chargeAnim
            chargeLook = config.chargeStyle
            chargePercent = config.chargePercent
            wakeOn = config.wakeAnim
            wakeLook = config.wakeStyle
            sceneOn = config.sceneOn
            // The moon face's glitch, and its clouds by the weather (a reading no older than two hours).
            scene.glitch = config.sceneGlitch
            if (adaptive != config.rearAdaptive || lightBias != config.rearLightBias) {
                adaptive = config.rearAdaptive; lightBias = config.rearLightBias
                applySceneLook(byHand = true)
            }
            scene.clouds = config.sceneWeather
            val sky = decoded.weather?.takeIf { it.isFresh(System.currentTimeMillis()) }?.condition
            if (sky != scene.weather) Log.i(TAG, "scene: the clouds go by the weather, now ${sky ?: "not known"}")
            scene.weather = sky
            if (scene.dustMoves != config.dustMovesWithPhone) Log.i(TAG, "dust: moves with the phone ${if (config.dustMovesWithPhone) "on" else "off"} (settings)")
            scene.dustMoves = config.dustMovesWithPhone
            sceneWake = config.sceneWake
            val loopWas = faceLoopPath()
            sceneLoop = config.sceneLoop
            facesStart()
            faceFromPayload(config.face, config.faceSeq)
            // Another loop picked while the scene is up: the back shows the new one now, not at the next wake.
            val loopNow = faceLoopPath()
            if (loopWas != loopNow && scene.isUp && scene.looping) {
                if (loopNow == null) scene.dismiss(ShellScene.FADE_OUT_MS, "no loop is chosen any more")
                else runCatching { popWaiter.execute { scene.play(null, loopNow, android.os.SystemClock.uptimeMillis(), waitForLight = false) } }
            }
            sceneLook = sceneLook.copy(
                clock = config.sceneClock, style = config.sceneClockStyle, date = config.sceneDate,
                battery = config.sceneBattery, hour24 = config.clock12h != true,
            )
            scene.look(sceneLook)
            scene.depth = config.sceneDepth
            // The settings may be what a waiting stay lacked (which clips): looked at now.
            if (stay > 0) runCatching { holdHandler.removeCallbacks(stayTick); holdHandler.post(stayTick) }
            cameraOwnRear = config.cameraOwnRear
            shellSheet?.frameColor(config.portalFrameColor)
            frame.allow(config.portalBorder)
            val shownPkg = guest?.pkg
            val dpiWas = shownPkg?.let { portalDpi[it] }
            val widthWas = shownPkg?.let { portalWidth[it] }
            setPortalDpi { dpiEdits.overlay(config.portalDpi, android.os.SystemClock.uptimeMillis()) }
            val widths = synchronized(dpiLock) { widthEdits.overlay(config.portalWidth, android.os.SystemClock.uptimeMillis()) }
            if (widths != portalWidth) setPortalWidth { widths }
            // The guest's DPI or width set on the front: it restarts with it now (sizes read at start).
            if (shownPkg != null && !sheetUp && (portalDpi[shownPkg] != dpiWas || portalWidth[shownPkg] != widthWas)) {
                restartGuest(shownPkg, "its size changed on the front")
            }
            sheetLook = config.theme to config.glassStyle
            controlCenter.theme(config.theme, config.glassStyle, config.background)
            // Companion mode: plain white words on a dark card unless a colour was chosen for them; the
            // panel's own accent (a purple) is not carried over to Xiaomi's screen.
            if (config.companion) notices.theme(config.theme, config.glassStyle, config.notificationColor ?: 0xFFFFFFFF.toInt(), config.notificationBg ?: 0xFF1C1C1F.toInt())
            else notices.theme(config.theme, config.glassStyle, config.notificationTint, config.notificationGround)
            // Cards put up from outside (remote control) have the banner and the list too, with
            // the front's notifications mirrored or not: they are asked for one by one.
            noticesOn = config.showNotifications || config.remoteCards
            noticesPop = config.noticesOverApps
            emptyPullXiaomi = config.emptyPullXiaomi
            if (messageStyle != config.messageStyle && config.messageStyle != dev.kb896.aiusage.data.MessageStyle.FULL) notices.prepareIsland()
            messageStyle = config.messageStyle
            // What three fingers held on the back do (ThreeFingersAct).
            threeFingers = config.threeFingers
            // "Fluid size": the music page's body glides to it; a page that stands still (paused) is drawn until it is on it
            // (musicFrame), and a covered one is on it at once.
            if (musicBody.floor != config.musicFloor.coerceIn(0, 100)) {
                musicBody.floor = config.musicFloor
                musicFloorAt = android.os.SystemClock.uptimeMillis()
                if (media != null) runCatching { musicHandler.post { if (musicFace.isUp) musicFrame() } }
            }
            // "Navigation on the back": off, a route has no page (it is not live to the navigation); on, it is again.
            if (navigationOn != config.navigationOn) {
                navigationOn = config.navigationOn
                syncMediaCard()
            }
            val wasCompanion = companion
            companion = config.companion
            moreApps = config.moreApps
            // The phone's own search kept off the internet (SearchOffline): applied as this process hears it is wanted.
            searchOffline.wanted(config.searchOffline)
            // A wake of the app's own can light a back switched off by hand: the double tap is read for its latch.
            latchWanted = (noticesOn && config.noticeWake) || (config.chargeAnim && config.chargeWhenDark) || (config.stayOn && config.sceneOn)
            if (wasCompanion != companion) Log.i(TAG, if (companion) "companion mode — Xiaomi's rear screen is the base" else "replace mode — the panel has the rear")
            syncCompanionListening()
            syncRearFocusWatch()
            // The banner's face loaded ahead, off this binder thread: the first banner on a new
            // shell, or with More apps off (no touch watch to make the sheet), has it in hand.
            if (companion && noticesOn && !noticeFaceReady()) runCatching { popWaiter.execute { ensureNoticeFace() } }
            // The charge layer made ahead, hidden, so the first lit frame of a show carries it.
            if (companion && chargeOn) charge.prepare()
            // Likewise the wake's, so a wake's first frame only draws.
            if (companion && wakeOn && wakeLook != dev.kb896.aiusage.data.WakeAnimStyle.NONE) wakeShow.prepare()
            // And the scene's two, so a wake's clip starts from a curtain already made.
            if (companion && sceneWanted()) runCatching { popWaiter.execute { scene.prepare() } }
            if (!companion || !sceneWanted()) { scene.disarm(); if (scene.isUp) scene.dismiss(ShellScene.FADE_OUT_MS, "switched off") }
            // The cover as these settings have it, with the back's focus read fresh (off this thread: it asks the window manager).
            runCatching { holdHandler.post { runCatching { readRearFocus() }; syncCover("settings") } }
            shellSheet?.theme(config.theme, config.glassStyle)
            // Handed on with every payload (the sheet ignores what it has already): a sheet
            // being made between this write and its publication picks it up there.
            val regrid = portalGrid != config.portalGrid
            portalGrid = config.portalGrid
            shellSheet?.grid(config.portalGrid)
            if (regrid) repage()
            portalNames = config.portalAppNames
            shellSheet?.names(config.portalAppNames)
            portalDome = config.portalDome
            shellSheet?.dome(config.portalDome)
            // An app kept right of the cameras, or let back, while it is the guest: relaunched
            // in its new place — on the mode worker, not this binder thread (update() is the app's).
            val confined = synchronized(dpiLock) { confinedEdits.overlay(config.portalConfined, android.os.SystemClock.uptimeMillis()) }
            if (portalConfined != confined) {
                // Switched on the front: off is the whole rear even for one seen letterboxed.
                (portalConfined - confined).forEach(::wantedWhole)
                wholeAnyway.removeAll(confined - portalConfined)
                portalConfined = confined
                syncSheetKeepRight()
                relaunchLater("its camera area setting changed")
            }
            restyleSheetFace(config.theme to config.font)
        } catch (e: Exception) {
            Log.w(TAG, "portal frame: no style from the payload: ${e.message}")
            return
        }
        syncDensity("a new payload")
    }

    /**
     * DPI PER APP: display 1's density follows the guest; one on a private display has that
     * display's instead ([syncPrivateDensity]), and the rear stays as it is. A guest has its own
     * (config.portalDpi, see [RearDensity]) for as long as it IS the guest — on top, behind
     * the panel, still being placed — so Panel / Resume never relayout it twice, and it
     * lands laid out once at its own DPI because that is set before its move. The display's
     * own density (the [baselineDensity]) comes back only when the guest ends (dropGuest, so
     * endGuest; hide(); destroy()) or when a third app has the rear (the keep-alive's USUAL
     * branch; the panel re-placed over it is a live guest's again, so that takes the guest's
     * back). Each change relayouts everything on display 1 — the panel declares
     * configChanges density, so it is untouched — so `wm density` runs only when the wanted
     * value differs from [currentDensity], and the keep-alive's per-tick call costs a
     * comparison, never a dump.
     *
     * The baseline is what the FIRST read found, not "no override": display 1 may carry an
     * override that is not ours (the panel was measured at 450, 380 after one), and that
     * one is restored with `wm density <baseline>`, never wiped by `reset`.
     *
     * Its own lock, [densityLock], not [placing]: the keep-alive's guest branches run off
     * [placing] on purpose — it is held through a launch's settle and survive waits, seconds
     * at a time — and taking it here would stall them behind any launch. [densityLock] is a
     * leaf: taken inside [placing] (launch, show, end), never the other way round. Each call
     * reads the guest afresh under it, so whichever call runs last applies the latest
     * state; two threads can never leave an older value set after a newer one.
     *
     * Shizuku killing the service outright is out of our hands: the override then survives
     * on display 1. For that, a marker `<applied>,<baseline or ->` goes into the global
     * setting [DENSITY_MARKER] BEFORE each override of ours and is deleted once the
     * baseline is back; the next service's first read calls the override it finds ours
     * only if the marker lists it ([DensityDump.start]), and then restores the marker's
     * baseline instead of keeping ours as the display's own. A change from one of ours to
     * another lists both (`300+310,380`) until it has taken.
     *
     * A command that was refused (non-zero exit, an error printed) is known not to have
     * happened, and the marker is put back. One that was interrupted or never started may
     * or may not have happened: the marker is left as written — it covers both outcomes —
     * and [densityKnown] goes false, so the next call reads `wm density` again before it
     * trusts [currentDensity].
     */
    private val densityLock = Any()
    /** Whether display 1's density has been read yet (once per service). Under [densityLock]. */
    private var densityRead = false
    /** False after a command whose outcome is unknown: [currentDensity] is re-read first. Under [densityLock]. */
    private var densityKnown = true
    /** Display 1's own override as first found (null = none): what comes back. Under [densityLock]. */
    private var baselineDensity: Int? = null
    /** The override on display 1 now, as far as this service knows. Under [densityLock]. */
    private var currentDensity: Int? = null
    /** The last change refused, so a refusal is logged once, not every tick. Under [densityLock]. */
    private var refusedDensity: String? = null
    /** Set by destroy(): nothing may apply a density after its restore. Under [densityLock]. */
    private var densityClosed = false
    /** No re-read after an unknown outcome before this uptime (ms), so a failing exec is not retried every tick. Under [densityLock]. */
    private var rereadNotBefore = 0L
    /** Display 1's physical density, for the log. */
    private var physicalDensity = DensityDump.FALLBACK
    /**
     * The config's per-app DPI, from the latest payload, with the changes made on the back
     * laid over it until a payload carries them ([dpiEdits]). Written under [dpiLock].
     */
    @Volatile private var portalDpi: Map<String, Int> = emptyMap()

    /**
     * How wide each app kept right of the cameras is (config.portalWidth, see [GuestWidth]), with
     * the widths set on the back laid over it until a payload carries them ([widthEdits]).
     * Written under [dpiLock].
     */
    @Volatile private var portalWidth: Map<String, Int> = emptyMap()
    /** The portal frame's colour, for the sheet's width preview. */
    @Volatile private var portalFrameColor = 0xFFB14CFF.toInt()
    /** Display 1's own density as the first read found it (its override, else its physical); null before. */
    @Volatile private var densityDefault: Int? = null

    /**
     * Display 1 at the density the guest calls for: its own for any live guest, else the
     * baseline. [thirdOnTop]: another app has the rear, which is shown at the baseline.
     */
    private fun syncDensity(why: String, thirdOnTop: Boolean = false) = synchronized(densityLock) {
        if (densityClosed) return@synchronized
        val g = guest
        applyDensity(if (thirdOnTop) null else RearDensity.target(g?.pkg, g != null && !g.onPrivate, portalDpi), why)
        syncPrivateDensity(g)
    }

    /** [g], if it is on a private display, at its density there: a comparison unless it changed. */
    private fun syncPrivateDensity(g: Guest?) {
        if (g == null || !g.onPrivate) return
        val changed = privateDisplay?.takeIf { it.displayId == g.privateDisplay }?.density(RearDensity.ofPrivate(g.pkg, portalDpi, geometry))
        // It relayouts in its own time: another density can letterbox it otherwise.
        if (changed == true) appRectSoon("its density changed")
    }

    /**
     * Under [densityLock]. [guestDpi] null = the baseline. Nothing is changed until the
     * first read has worked: without it the baseline is unknown, and guessing it is how an
     * override that is not ours gets wiped. A command that did not take leaves
     * [currentDensity] as it was, so the next call retries.
     */
    private fun applyDensity(guestDpi: Int?, why: String) {
        if (!readDensity()) return
        if (!densityKnown) {
            if (android.os.SystemClock.elapsedRealtime() < rereadNotBefore) return
            val d = (execChecked("wm density -d ${Panel.DISPLAY_ID}") as? Run.Ok)?.let { DensityDump.parse(it.out) }
            if (d?.physical == null) {
                rereadNotBefore = android.os.SystemClock.elapsedRealtime() + REREAD_BACKOFF_MS
                return
            }
            currentDensity = d.override
            densityKnown = true
            Log.i(TAG, "density: display ${Panel.DISPLAY_ID} re-read after an unfinished change — ${d.override ?: "no override"}")
        }
        val wanted = guestDpi ?: baselineDensity
        val cur = currentDensity
        if (wanted == cur) return
        val ours = wanted != null && wanted != baselineDensity
        val curOurs = cur != null && cur != baselineDensity
        // The marker first: a service killed between the two finds an override the marker
        // lists either way. From one of ours to another it lists both, until this takes.
        if (ours) {
            val put = execChecked("settings put global $DENSITY_MARKER ${DensityDump.marker(wanted!!, baselineDensity, from = if (curOurs) cur else null)}")
            if (put !is Run.Ok) { densityFailed(put, wanted, why, "the marker"); return }
        }
        val run = execChecked(
            if (wanted != null) "wm density $wanted -d ${Panel.DISPLAY_ID}"
            else "wm density reset -d ${Panel.DISPLAY_ID}"
        )
        if (run !is Run.Ok) {
            densityFailed(run, wanted, why, "the change")
            // Refused, so known not to have happened: the marker back to what is really
            // there. Unknown, it stays as written — it covers both outcomes.
            if (run is Run.Refused && ours) execChecked(
                if (curOurs) "settings put global $DENSITY_MARKER ${DensityDump.marker(cur!!, baselineDensity)}"
                else "settings delete global $DENSITY_MARKER"
            )
            return
        }
        currentDensity = wanted
        refusedDensity = null
        // After the change, never before: killed in between, the marker still covers it.
        if (!ours) execChecked("settings delete global $DENSITY_MARKER")
        else if (curOurs) execChecked("settings put global $DENSITY_MARKER ${DensityDump.marker(wanted!!, baselineDensity)}")
        Log.i(TAG, "density: display ${Panel.DISPLAY_ID} at " +
            (if (ours) "$wanted dpi" else "its own ${wanted ?: physicalDensity} dpi") + " — $why")
        // A guest that is placed on the rear keeps its shape through the relayout. One still
        // landing is shaped after its move. One on a private display was not touched by this.
        guest?.takeIf { it.placed && !it.hiddenOnFront && !it.onPrivate }?.let { g ->
            shapeGuest(g.task, g.pkg, "the density changed")
        }
    }

    /** A step that did not take: logged once per target; an unknown outcome is re-read next time. */
    private fun densityFailed(run: Run, wanted: Int?, why: String, step: String) {
        if (run is Run.Unknown) densityKnown = false
        val key = wanted?.toString() ?: "reset"
        if (refusedDensity == key) return
        refusedDensity = key
        val detail = when (run) {
            is Run.Refused -> "refused: ${run.out.take(200)}"
            else -> "did not finish (interrupted or never started) — re-read next time"
        }
        Log.w(TAG, "density: $key — $step $detail — $why")
    }

    /** The first read: display 1's override and our marker, once. Under [densityLock]. */
    private fun readDensity(): Boolean {
        if (densityRead) return true
        val dump = execChecked("wm density -d ${Panel.DISPLAY_ID}")
        val d = (dump as? Run.Ok)?.let { DensityDump.parse(it.out) }
        val marker = if (d?.physical != null) execChecked("settings get global $DENSITY_MARKER") as? Run.Ok else null
        if (d?.physical == null || marker == null) {
            if (refusedDensity != "read") {
                refusedDensity = "read"
                Log.w(TAG, "density: display ${Panel.DISPLAY_ID} unreadable — leaving it alone: $dump")
            }
            return false
        }
        val start = DensityDump.start(d.override, marker.out)
        densityRead = true
        densityKnown = true
        refusedDensity = null
        physicalDensity = d.physical
        baselineDensity = start.baseline
        densityDefault = start.baseline ?: d.physical
        shellSheet?.dpiDefault(sliderDefault())
        currentDensity = d.override
        if (!start.ours && DensityDump.parseMarker(marker.out) != null) execChecked("settings delete global $DENSITY_MARKER")
        Log.i(TAG, "density: display ${Panel.DISPLAY_ID} is ${d.physical} dpi" +
            (d.override?.let { if (start.ours) ", still at our $it from a service that died — restoring" else ", its own override $it kept" } ?: ""))
        return true
    }

    /**
     * The back blurred and dimmed while one app gives way to another there (GuestCurtain),
     * round [work]: up as it starts, clear again once it is done, whatever came of it.
     */
    private inline fun <T> curtained(work: () -> T): T {
        val c = runCatching { curtain }.getOrNull()
        runCatching { c?.rise() }
        try {
            return work()
        } finally {
            runCatching { c?.fall() }
        }
    }

    private val curtain by lazy { GuestCurtain(rearLayerStack(), geometry) }

    override fun launchGuest(pkg: String?, displayId: Int): Boolean {
        // Nothing to launch, nothing to cover.
        if (displayId < 0 || pkg == null || placedDisplayId < 0) return launchGuestNow(pkg, displayId)
        return curtained { launchGuestNow(pkg, displayId) }
    }

    private fun launchGuestNow(pkg: String?, displayId: Int): Boolean = synchronized(placing) {
        if (displayId < 0 || pkg == null || !PACKAGE_NAME.matches(pkg) || !GuestTask.sendable(pkg, PKG)) {
            return@synchronized false
        }
        // No panel placed means no keep-alive running, and nothing would ever see the
        // guest leave: its frame and watcher would outlive it. In companion mode there is never
        // a panel: the guest-only keep-alive is started for it instead.
        if (!guestWatched(displayId)) { Log.w(TAG, "portal: no panel placed — not launching $pkg"); return@synchronized false }
        // Resolved before anything moves: an app with no launcher activity changes nothing.
        val component = launcherOf(pkg)
        if (component == null) {
            Log.w(TAG, "portal: no launcher activity for $pkg"); return@synchronized false
        }
        // A Back before this launch says nothing about it: an app's next launch may reuse the
        // task a Back closed or sent behind, and a trampoline dying in its survive wait would
        // then read as closed by the user, not retried.
        backedTask = -1
        // The switch under the slider turned off is somebody asking for the whole rear: it is tried, and not taken back.
        val askedWholeRear = wholeRearAsked
        wholeRearAsked = false
        val wantPrivate = GuestShape.onPrivateDisplay(pkg, portalConfined) || pkg in letterboxed ||
            guest?.let { it.pkg == pkg && it.lockRetried } == true
        var privateRefused = false
        // The same app, in the other place than it is: it ends there, and starts here. Its picture
        // taken first, while there is one, and carried through to the reveal: taken after, it would
        // be of the panel, shown for the length of the start. Whatever does not use it, this lets go.
        // Restarted for a new size: ended where it is, process and all, and started again.
        val restart = restartFor == pkg
        // Read once: the survive wait lets go of [placing], and another open of the same app in it is not a restart.
        restartFor = null
        val carried = guest?.takeIf { it.pkg == pkg && (it.keptRight != wantPrivate || restart) }?.let { g ->
            (if (!dozing) coverRear(displayId) else null).also { endForRelaunch(g, kill = restart) }
        }
        try {
        // Every app on a display of its own, right of the cameras or over the whole rear: on the
        // rear itself HyperOS gives it no keyboard (PrivateDisplay.open). The rear itself is what
        // is left when there is none to be had, as it always was; and the same app again, already
        // there, stays there.
        val again = guest?.takeIf { it.pkg == pkg }
        if (again != null && again.onPrivate) return@synchronized openAgainOnPrivate(again, component, displayId)
        if (again == null) {
            freshen(pkg)
            val pd = openPrivate(pkg, wide = !wantPrivate)
            if (pd != null) return@synchronized launchOnPrivate(pkg, component, displayId, pd, carried).also { if (it) startedHere(pkg) }
            Log.w(TAG, "portal: no private display — $pkg on the rear itself instead")
            if (wantPrivate) privateRefused = true
        }
        // One guest at a time: the old one goes to the front, alive — never killed. Not yet:
        // it stays on the rear, live, while the new one starts and settles on the front, and
        // leaves under the cover, as the new one comes in.
        val old = guest?.takeIf { it.pkg != pkg }
        // A new app coming in — over the panel, or in place of another: covered and revealed.
        // The same app again moves nothing on the rear.
        val arriving = guest?.pkg != pkg
        repeat(2) { attempt ->
            val first = attempt == 0
            // Asked for on the front by name: with the old guest still on the rear, where an
            // unspecified start lands is not ours to guess. The same app again is left to
            // land where its task is.
            val start = startSettled(component, pkg, front = arriving) { closed ->
                LockRetry.applies(closed, wantPrivate, privateRefused, retried = !first || askedWholeRear) { keyguardShowing() }
            }
            // Nothing on the rear has changed yet, and the cover is not up: one smooth open from here.
            if (start == Started.ClosedBehindLock) {
                Log.i(TAG, "portal: $pkg closed itself behind the lock — on a private display instead")
                val pd = openPrivate(pkg, wide = false)
                if (pd != null) return@synchronized launchOnPrivate(pkg, component, displayId, pd, carried, lockRetried = true).also { if (it) startedHere(pkg) }
            }
            val task = (start as? Started.Task)?.id
            if (task == null) {
                // Nothing on the rear has changed yet: an old guest is still the guest.
                if (first && old != null && guest == old) {
                    Log.w(TAG, "portal: $pkg never started — ${old.pkg} stays")
                    return@synchronized false
                }
                endGuest("never started"); return@synchronized false
            }
            // The rear as it is, over everything (GuestCover), before anything on it moves:
            // the old guest's leaving, the density's change and the new app's first frames
            // happen under it. Not on a rear asleep, nor for the same app again. The frame
            // (one guest to the next) is left out of the picture, and the live one stays up
            // over it the whole way; where it could not be left out, it is in the picture,
            // and the live one fades out as it goes and back in after the reveal.
            val cover = if (first && arriving && !dozing) carried ?: coverRear(displayId) else null
            if (cover != null && !cover.frameLeftOut) frame.hide()
            if (first && old != null) {
                moveToDisplay(old.task, 0); shapeForFront(old.task)
                if (old.onPrivate) closePrivate("${old.pkg} went to the front")
            }
            guest = Guest(pkg, task, onTop = false)
            // Before the move, so it lands laid out once at its own DPI (see syncDensity).
            syncDensity("$pkg is landing")
            moveToDisplay(task, displayId)
            // After the move: what a launch reused may be in multi-window from anywhere.
            shapeGuest(task, pkg, "it landed")
            wake(displayId)
            val movedAt = android.os.SystemClock.uptimeMillis()
            // Uncovered, the frame comes up as the app lands, not after the survive wait;
            // show() is idempotent, so guestPlaced() below does not start a second one.
            // Uncovered, as it draws.
            if (cover == null) { awaitDrawn(pkg, task, displayId); chromeShow() }
            // Landed: the sheet's guest from now, so a pull during the survive wait has its
            // buttons from the first frame (they used to come in when it was placed).
            guest = Guest(pkg, task, onTop = true, landed = true)
            syncSheetGuest()
            // Covered: revealed once it has drawn, and the frame faded in after it (still up
            // from the guest before, it stays) — never before the app.
            if (cover != null) {
                // Before the reveal: the strip of a private guest before it, gone.
                reveal(cover, pkg, displayId, { stripFor() }) { drawnOn(pkg, task, displayId) }
                if (guest?.let { it.task == task && it.onTop } == true) chromeShow()
            }
            // A task gone within two seconds of the move was a trampoline the move
            // relaunched (Shazam, measured); once more from the start, then give up.
            if (!surviveWait(movedAt, task)) return@synchronized true
            val entry = GuestTask.byId(exec("am stack list"), task)
            if (entry != null && entry.display == displayId) {
                guest = Guest(pkg, task, onTop = true, landed = true)
                Log.i(TAG, "portal: $pkg is the guest, task $task")
                guestPlaced(displayId)
                return@synchronized true
            }
            // Closed by a Back in the wait (at its root): over, not a failed move to retry.
            if (PortalAction.vanished(closedByUser = backedTask == task) == PortalAction.Vanished.OVER) {
                Log.i(TAG, "portal: $pkg's task $task closed on a Back in its survive wait — not relaunched")
                if (dozing) dropGuest("its task closed on Back") else endGuest("its task closed on Back")
                return@synchronized false
            }
            chromeHide()
            // Not landed after all: no buttons for a task that is gone.
            guest = Guest(pkg, task, onTop = false)
            syncSheetGuest()
            Log.w(TAG, "portal: $pkg's task $task did not survive the move (try ${attempt + 1})")
        }
        endGuest("did not stay on the rear")
        false
        } finally {
            // Safe on one its reveal has already taken off.
            carried?.remove()
        }
    }

    /**
     * The wait, from [since], to see a just-started guest's [task] last ([GUEST_SURVIVE_MS]): a
     * trampoline the start relaunched dies inside it. WITHOUT HOLDING [placing] — the caller's —
     * which it gives up for the wait (Object.wait) and takes back after: held, a Close, a Front or
     * the next app asked for in those two seconds waited them out behind it, and read as the
     * rear being slow to close an app just opened (2026-11-09). False when somebody else changed
     * the guest meanwhile (closed it, sent it to the front, opened another): the launch is then
     * over, theirs to have decided, and nothing of it is undone. Under [placing].
     */
    private fun surviveWait(since: Long, task: Int): Boolean {
        val lock = placing as java.lang.Object
        while (true) {
            val left = GUEST_SURVIVE_MS - (android.os.SystemClock.uptimeMillis() - since)
            if (left <= 0) break
            lock.wait(left)
        }
        val g = guest
        if (g == null || g.task != task) {
            Log.i(TAG, "portal: task $task changed hands in its survive wait — ${g?.pkg ?: "no guest"} now; the launch is over")
            return false
        }
        return true
    }

    /** The rear's layer stack for the guests' covers, read once. */
    private val coverStack by lazy { rearLayerStack() }

    /**
     * The rear (display [displayId]) as it shows now, on a cover over it (GuestCover); null
     * when there can be none, and the guest arrives uncovered, as it always did.
     */
    private fun coverRear(displayId: Int): GuestCover? {
        // A guest on a private display up: what shows is our layers, which the rear's capture has
        // nothing of. Its own picture, or none: never the panel's in its place.
        val g = guest
        if (g != null && g.onPrivate && g.onTop) {
            val cover = privatePicture()?.let { GuestCover.ofPicture(coverStack, geometry, it) }
            Log.i(TAG, if (cover != null) "portal: the rear covered with ${g.pkg}'s own picture" else "portal: no picture of ${g.pkg} — no cover")
            return cover
        }
        val at = android.os.SystemClock.uptimeMillis()
        return GuestCover.up(coverStack, geometry, displayId, frame.currentLayer) { screencapRear() }
            ?.also {
                Log.i(TAG, "portal: the rear covered in ${android.os.SystemClock.uptimeMillis() - at} ms" +
                    if (it.frameLeftOut) ", the frame left out" else "")
            }
    }

    /** The launcher activity of [pkg], or null: fit to go into a shell command. */
    private fun launcherOf(pkg: String): String? =
        exec("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg")
            .lines().lastOrNull()?.trim()?.takeIf { COMPONENT.matches(it) }

    // -- a guest on a private display -------------------------------------------------------
    //
    // See PrivateDisplay for why and what it is. Launched there rather than started on the front
    // and moved: no trick to get a task onto the rear, no density to set on anything else, and the
    // panel stays where it is, on the rear, under our layers. Everything else about a guest —
    // its frame, the sheet and its slider, the control centre, the hold, the freezer's list — is
    // as for one on the rear, off the same Guest. What differs, each in its place below: Panel and
    // Resume only hide and show a layer (HIDE_LAYERS, SHOW_LAYERS); the keep-alive asks after it
    // by its task on its display (privateTick); Back is a key on its display; and the fingers go
    // to it from the service's one touch reader (forwardOpen).

    /** The display of the guest kept right of the cameras; null with none. Under [placing], any thread may read it. */
    @Volatile private var privateDisplay: PrivateDisplay? = null

    /**
     * Where the private guest's app stands on the rear (GuestShape.appOnRear); null before it is
     * read, the whole area. What the frame runs round and where the strip's colour is read.
     */
    @Volatile private var privateApp: GuestShape.Bounds? = null

    /**
     * [privateApp] read again (TaskRead, one binder call, never on the touch path), and the frame
     * following if it moved. As it draws, as it comes back, after a density change, and on the
     * keep-alive's tick for what nobody tells us: an app rotating, another of its activities. Any
     * thread but the reader.
     */
    private fun syncAppRect(why: String) {
        val g = guestRef.get()?.takeIf { it.onPrivate } ?: return
        val seen = TaskRead.of(g.task) as? TaskRead.Seen ?: return
        // In the display's rows, its hidden top among them: the rear's are that many fewer.
        val hidden = privateDisplay?.takeIf { it.displayId == g.privateDisplay }?.hidden ?: 0
        val onDisplay = seen.bounds?.let { GuestShape.Bounds(it.left, it.top - hidden, it.right, it.bottom - hidden) }
        val rect = GuestShape.appOnRear(geometry, onDisplay, privateArea())
        if (rect == privateApp) return
        privateApp = rect
        if (g.wide && g.pkg !in wholeAnyway && GuestShape.letterboxedUnderLenses(geometry, rect) && letterboxed.add(g.pkg)) {
            Log.i(TAG, "portal: ${g.pkg} letterboxed under the lenses (x ${rect.left}–${rect.right}) — right of the cameras instead")
            relaunchLater("letterboxed under the lenses")
        }
        // Over the whole rear the frame keeps to the glass right of the lenses, as for an app on
        // the rear itself: the app's own rectangle there runs under them.
        frame.follow(if (g.wide) null else rect)
        strip.follow(rect, privateArea())
        strip.poke()
        Log.i(TAG, "portal: ${g.pkg} stands at x ${rect.left}–${rect.right}, y ${rect.top}–${rect.bottom} — $why")
    }

    /** [syncAppRect] a moment from now, on the hold's thread: the app has not relaid out yet. */
    private fun appRectSoon(why: String) {
        runCatching {
            holdHandler.postDelayed({
                try { syncAppRect(why) } catch (e: Throwable) { Log.w(TAG, "portal: the app's rectangle not read", e) }
            }, APP_RECT_SOON_MS)
        }
    }

    /**
     * What the rear shows of a private guest, as a whole-rear picture: the strip in its colour, the
     * bars included, and the app's layer right of it (RearCapture.layer, the strip's capture, all of it). The frame is
     * not in it: live, over any cover. Null: no capture, or secure content, which comes back black
     * and is not what was there. A software bitmap, so that nothing of it depends on the layer going
     * on existing; the caller's. Before the task ends or the display goes: it is all there is of it.
     */
    private fun privatePicture(): android.graphics.Bitmap? {
        val pd = privateDisplay ?: return null
        val rear = geometry
        val area = pd.area
        return runCatching {
            val shot = ShellLayer.lease(listOf(pd.layer)) { live ->
                live.firstOrNull()?.let { RearCapture.layer(it, android.graphics.Rect(0, pd.hidden, area.width, pd.hidden + area.height)) }
            } ?: return@runCatching null
            try {
                if (shot.secure) return@runCatching null
                val hw = android.graphics.Bitmap.wrapHardwareBuffer(shot.buffer, shot.space) ?: return@runCatching null
                val app = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                hw.recycle()
                val out = android.graphics.Bitmap.createBitmap(rear.widthPx, rear.heightPx, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(out)
                c.drawBitmap(app, area.left.toFloat(), area.top.toFloat(), null)
                app.recycle()
                val paint = android.graphics.Paint().apply { color = strip.color }
                if (!pd.wide) for (b in StripColor.around(rear, privateApp ?: area, strip.keyboardTop)) {
                    c.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
                }
                out
            } finally {
                runCatching { shot.buffer.close() }
            }
        }.onFailure { Log.w(TAG, "portal: no picture of the app: ${(it.cause ?: it).message}") }.getOrNull()
    }

    /**
     * Before a cover slides off the panel as a private guest leaves: the panel has only just been
     * told it is no longer covered (through the app, a broadcast), and has to have drawn again to be
     * live under the picture. Not for what was never covered.
     */
    private fun awaitPanelLive(g: Guest) {
        if (g.onPrivate) Thread.sleep(PANEL_LIVE_MS)
    }

    /** A new display for [pkg] at its density, over the whole rear if [wide], not yet anyone's; null where it cannot be had. Under [placing]. */
    private fun openPrivate(pkg: String, wide: Boolean): PrivateDisplay? {
        val ctx = context ?: run { Log.w(TAG, "private display: no Context from this Shizuku"); return null }
        val pd = PrivateDisplay.open(
            ctx, coverStack, geometry, RearDensity.ofPrivate(pkg, portalDpi, geometry), wide,
            width = if (wide) null else portalWidth[pkg], hidden = statusBarPx,
        ) { id ->
            exec("input -d $id keyevent KEYCODE_WAKEUP")
        } ?: return null
        // Said once more for the firmware that takes it: a portrait-locked app fills the display
        // then. It did not take on OS4.0.5.0 (the spike): such an app letterboxes, on black.
        Log.i(TAG, "private display ${pd.displayId}: ignore orientation: " +
            exec("wm set-ignore-orientation-request -d ${pd.displayId} true; wm get-ignore-orientation-request -d ${pd.displayId}"))
        return pd
    }

    /**
     * The status bar's height as apps read it (android:dimen/status_bar_height, the front's): the
     * rows each private display hides at its top (PrivateDisplay.open, HIDDEN TOP). 0 if unreadable.
     *
     * Asked of the overlay service, as the apps' resources resolve it: this process's own system
     * resources have no overlays, and gave AOSP's 78 px where HyperOS's overlay makes it 150 —
     * TikTok's tabs still 72 px down (2026-11-08).
     */
    private val statusBarPx: Int by lazy {
        val said = exec("cmd overlay lookup android android:dimen/status_bar_height")
        val m = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(px|dp|dip)""").find(said)
        val px = m?.destructured?.let { (n, unit) ->
            val v = n.toFloat()
            if (unit == "px") v else v * android.content.res.Resources.getSystem().displayMetrics.density
        } ?: runCatching {
            val r = android.content.res.Resources.getSystem()
            r.getIdentifier("status_bar_height", "dimen", "android").takeIf { it != 0 }?.let { r.getDimensionPixelSize(it).toFloat() }
        }.getOrNull() ?: 0f
        Log.i(TAG, "private displays: the top ${px.toInt()} px hidden (status_bar_height: ${said.trim().take(60)})")
        Math.round(px).coerceIn(0, geometry.heightPx / 2)
    }

    /** Where the private guest's display stands on the rear; right of the lenses with none. */
    private fun privateArea(): GuestShape.Bounds = privateDisplay?.area ?: GuestShape.appArea(geometry)

    /** The display gone, and with it whatever is still on it. Move what should live off first. Any thread. */
    private fun closePrivate(why: String) {
        val pd = privateDisplay ?: return
        privateDisplay = null
        privateApp = null
        Log.i(TAG, "private display ${pd.displayId}: closing — $why")
        pd.release()
    }

    /**
     * The layer and the panel under it follow the guest: shown while it is on top, and the panel
     * told it is covered (RearActivity.setCovered). Called with every change of the guest, under
     * no lock of its own: [PrivateDisplay.show] is one transaction, and the word to the app goes
     * on a worker. Any thread.
     */
    private fun syncPrivate() {
        val g = guestRef.get()
        val shown = g != null && g.onPrivate && g.onTop && !lockVeiled
        val area = privateDisplay?.takeIf { it.displayId == g?.privateDisplay }?.area
        touchForward?.appX = area?.left ?: if (g?.wide == true) 0 else geometry.cutoutPx
        touchForward?.appW = area?.width ?: 0
        privateDisplay?.takeIf { it.displayId == g?.privateDisplay }?.show(shown)
        if (shown && !panelCovered) privateLit = true // new on top: the hold's next look says if not
        if (!shown) privateAppHolds = false
        if (shown != panelCovered) {
            panelCovered = shown
            tellPanelCovered()
        }
    }

    @Volatile private var panelCovered = false

    /** What [panelCovered] says, to the panel through the app, in order, off the caller's thread. */
    private fun tellPanelCovered() {
        runCatching {
            pokeWorker.execute {
                val ev = events ?: return@execute
                runCatching { ev.onPanelCovered(panelCovered) }.onFailure { Log.w(TAG, "portal: the panel not told it is covered: ${it.message}") }
            }
        }
    }

    /**
     * [pkg] onto [pd], started there — `am start --display`, no front in it — and shown under a
     * cover of the rear as a launch on the rear is. As there, whatever is on the rear goes on
     * until the new one has started and settled, on its display, out of sight; then the cover, the
     * old one leaving, the layer shown. A task gone within two seconds is tried once more; a start
     * that gave no task is tried once through the front (HyperOS has been seen to abort starts
     * under the keyguard, [show]). [pd] is this function's until it is the guest's. Under [placing].
     */
    private fun launchOnPrivate(
        pkg: String, component: String, displayId: Int, pd: PrivateDisplay, carried: GuestCover? = null, lockRetried: Boolean = false,
    ): Boolean {
        val old = guest
        var landed = false
        repeat(2) { attempt ->
            val task = startOnPrivate(component, pkg, pd.displayId, throughFront = attempt > 0)
            if (task == null) {
                if (attempt == 0) { Log.i(TAG, "portal: $pkg gave no task on display ${pd.displayId} — through the front"); return@repeat }
                if (!landed) {
                    // Nothing on the rear has changed: what was there stays.
                    pd.release()
                    Log.w(TAG, "portal: $pkg never started on a private display")
                    if (lockRetried && old == null) endGuest("never started")
                } else endGuest("never started")
                return false
            }
            val cover = if (!landed && !dozing) carried ?: coverRear(displayId) else null
            if (cover != null && !cover.frameLeftOut) frame.hide()
            if (!landed) {
                landed = true
                if (old != null) {
                    moveToDisplay(old.task, 0); shapeForFront(old.task)
                    if (old.onPrivate) closePrivate("${old.pkg} went to the front")
                }
                privateDisplay = pd
            }
            // On top from here: the layer shows, under the cover, and the panel stands down.
            guest = Guest(pkg, task, onTop = true, landed = true, privateDisplay = pd.displayId, lockRetried = lockRetried, wide = pd.wide)
            syncDensity("$pkg is landing")
            wake(displayId)
            val startedAt = android.os.SystemClock.uptimeMillis()
            val area = pd.area
            if (cover == null) { awaitDrawn(pkg, task, pd.displayId, area); syncAppRect("it drew"); chromeShow() }
            syncSheetGuest()
            if (cover != null) {
                reveal(cover, pkg, displayId, { stripFor() }) { drawnOn(pkg, task, pd.displayId, area) }
                if (guest?.let { it.task == task && it.onTop } == true) chromeShow()
            }
            if (!surviveWait(startedAt, task)) return true
            if (GuestTask.byId(exec("am stack list"), task)?.display == pd.displayId) {
                Log.i(TAG, "portal: $pkg is the guest on private display ${pd.displayId}, task $task")
                guestPlaced(displayId)
                return true
            }
            if (PortalAction.vanished(closedByUser = backedTask == task) == PortalAction.Vanished.OVER) {
                Log.i(TAG, "portal: $pkg's task $task closed on a Back in its survive wait — not relaunched")
                if (dozing) dropGuest("its task closed on Back") else endGuest("its task closed on Back")
                return false
            }
            chromeHide()
            guest = Guest(pkg, task, onTop = false, privateDisplay = pd.displayId, lockRetried = lockRetried, wide = pd.wide)
            syncSheetGuest()
            Log.w(TAG, "portal: $pkg's task $task did not survive on display ${pd.displayId}, read on ${GuestTask.byId(exec("am stack list"), task)?.display} (try ${attempt + 1})")
        }
        // Two starts that gave a task and did not last, or one of each: the guest's to end.
        if (landed) endGuest(if (lockRetried) "never started" else "did not stay on its display") else pd.release()
        return false
    }

    /**
     * [component] started on [display], and its task once its top activity has held still, as
     * [startSettled]; null when none shows soon ([PRIVATE_NO_TASK_MS]; started through the front,
     * GUEST_SETTLE_LIMIT_MS). One found elsewhere (the app open on the front, a start refused its
     * display) is moved there.
     */
    private fun startOnPrivate(component: String, pkg: String, display: Int, throughFront: Boolean): Int? {
        RearKeyboard.showOn(display)
        exec(if (throughFront) "am start --display 0 -n $component" else "am start $NO_SLIDE --display $display -n $component")
        val settle = GuestTask.Settle()
        val began = android.os.SystemClock.uptimeMillis()
        var seen: GuestTask.Entry? = null
        while (true) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - began >= GUEST_SETTLE_LIMIT_MS) break
            val mine = GuestTask.entries(exec("am stack list")).filter { it.pkg == pkg }
            seen = mine.firstOrNull { it.display == display } ?: mine.firstOrNull()
            if (seen == null && !throughFront && now - began >= PRIVATE_NO_TASK_MS) return null
            // On its own display already: nothing is moved, so nothing waits for it to hold
            // still. A trampoline's next activity opens on the display of the one that started
            // it, and the reveal waits for the app's own drawn frame anyway. The settle (a whole
            // second of the same top) was most of an open's wait (2026-11-09). One seen elsewhere
            // is moved, and a move does want the app it moves to have settled.
            if (seen != null && seen.display == display) break
            if (seen != null && settle.feed(seen.top, now)) break
            Thread.sleep(START_POLL_MS)
        }
        val entry = seen ?: return null
        if (entry.display != display) {
            Log.i(TAG, "portal: $pkg's task ${entry.task} is on display ${entry.display} — moving it to its own")
            if (!moveToDisplay(entry.task, display)) return null
        }
        return entry.task
    }

    /** [g], already on its private display, asked for again: to the front of it, and on top. Under [placing]. */
    private fun openAgainOnPrivate(g: Guest, component: String, displayId: Int): Boolean {
        exec("am start $NO_SLIDE --display ${g.privateDisplay} -n $component")
        if (!g.onTop) guestOnTopAgain(g, "opened again")
        wake(displayId)
        return true
    }

    /**
     * "Keep right of the cameras" changed for the guest: it ends where it is, and the launch that
     * follows starts it in its new place. Its task goes as a swipe from recents takes it (`am
     * stack remove`, as the camera's: the activity destroyed, a warm process left), not moved:
     * a move to the other display has to go through the front (seen there by anyone looking), and
     * it is `am start` on these that has been proven. The app's own saved state is what it comes
     * back with. Under [placing].
     */
    private fun endForRelaunch(g: Guest, kill: Boolean = false) {
        Log.i(TAG, "portal: ${g.pkg} relaunching, " + when {
            kill -> "process and all, for its new size"
            g.keptRight -> "from right of the cameras to the whole rear"
            else -> "from the whole rear to right of the cameras"
        })
        exec("am stack remove ${g.task}")
        for (i in 0 until RELAUNCH_POLLS) {
            if (GuestTask.byId(exec("am stack list"), g.task) == null) break
            Thread.sleep(RELAUNCH_POLL_MS)
        }
        if (kill) endProcess(g.pkg)
        dropGuest("relaunching in its new place")
    }

    // -- sizes read at start --------------------------------------------------------------
    //
    // Some apps take their size once, as their process starts, from the display it starts for:
    // TikTok scales its whole design to that width (ByteDance's screen adaptation). Started on
    // the front, it kept the front's 1220 px on the back, its bar a quarter of the height at any
    // DPI; started on the back, it fitted (the owner, 2026-11-08). So an app is started on the
    // back by us, fresh: one whose process we did not start here is ended first ([freshen]), and
    // a new DPI or width restarts it ([restartGuest]). Ended as Recents ends an app, never
    // force-stopped (that would stop its notifications until it is opened again).

    /** The process each app had when we last started it on the back. Under [placing]. */
    private val startedPids = HashMap<String, Int>()

    /** The app being restarted for a new size ([restartGuest]): its launch ends it first. Under [placing]. */
    private var restartFor: String? = null

    /** Restart the guest for its size, once the sheet is down: set on the back. Under [sheetLock]. */
    private var restartAfterSheet: String? = null

    private fun pidOf(pkg: String): Int? = exec("pidof $pkg").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()

    /** [pkg]'s process, started on the back now: remembered, so it is not ended next time. Under [placing]. */
    private fun startedHere(pkg: String) {
        pidOf(pkg)?.let { startedPids[pkg] = it } ?: startedPids.remove(pkg)
    }

    /**
     * [pkg]'s process ended if it is alive and not one we started on the back: its tasks removed
     * (out of Recents, as a swipe there), then whatever is left in the background. Under [placing].
     */
    private fun freshen(pkg: String) {
        val pid = pidOf(pkg) ?: return
        if (startedPids[pkg] == pid) return
        Log.i(TAG, "portal: $pkg is running from the front (pid $pid) — ended, to start on the back with the back's size")
        GuestTask.entries(exec("am stack list")).filter { it.pkg == pkg }.forEach { exec("am stack remove ${it.task}") }
        endProcess(pkg)
    }

    /**
     * [pkg] ended, services and all (`am stop-app`), and waited for. Not `am kill`: that only ends
     * an idle background process, and TikTok's push services kept its alive, so it came back warm
     * with its old size (2026-11-08). Not `am force-stop` either: stop-app leaves its alarms and
     * jobs, so it is not left silent. Under [placing].
     */
    private fun endProcess(pkg: String) {
        startedPids.remove(pkg)
        exec("am stop-app $pkg")
        for (i in 0 until RELAUNCH_POLLS) {
            if (pidOf(pkg) == null) return
            Thread.sleep(RELAUNCH_POLL_MS)
        }
        Log.w(TAG, "portal: $pkg still running after stop-app — it keeps the size it started with")
    }

    /**
     * [pkg], just closed, ended (`am stop-app`) on the worker, not waited for: unless it has been
     * opened again since, on the back, by then — that one is not ended. Any thread.
     */
    private fun stopAfterClose(pkg: String) {
        runCatching {
            modeWorker.execute {
                synchronized(placing) {
                    if (guest?.pkg == pkg) { Log.i(TAG, "portal: $pkg opened again before its stop — left running"); return@synchronized }
                    startedPids.remove(pkg)
                    exec("am stop-app $pkg")
                }
            }
        }.onFailure { Log.w(TAG, "portal: stop after close not queued: ${it.message}") }
    }

    /** The guest [pkg] restarted fresh on the back, for a new DPI or width. Off the caller's thread. Any thread. */
    private fun restartGuest(pkg: String, why: String) {
        runCatching {
            modeWorker.execute {
                synchronized(placing) {
                    guest?.takeIf { it.pkg == pkg && it.placed } ?: return@synchronized
                    Log.i(TAG, "portal: $pkg restarting — $why")
                    restartFor = pkg
                    try { launchGuest(pkg, rearDisplay()) } finally { restartFor = null }
                }
            }
        }.onFailure { Log.w(TAG, "portal: restart not queued: ${it.message}") }
    }

    /**
     * The guest moved to where the config says, if that is not where it is. A launch's lock, so
     * nothing races it. Under [placing].
     */
    private fun relaunchIfMoved(why: String) {
        val g = guest?.takeIf { it.placed } ?: return
        if (g.keptRight == keepsRight(g)) return
        Log.i(TAG, "portal: ${g.pkg} — $why")
        launchGuest(g.pkg, rearDisplay())
    }

    /** Whether [g] is to be right of the cameras: the config says so, or this open does (LockRetry). */
    private fun keepsRight(g: Guest): Boolean = g.lockRetried || g.pkg in letterboxed || GuestShape.onPrivateDisplay(g.pkg, portalConfined)

    /**
     * Apps seen letterboxed under the lenses over the whole rear (GuestShape.letterboxedUnderLenses):
     * right of the cameras from then on, in this process — until somebody switches "Keep right
     * of the cameras" off for one, which the sheet shows on for them ([wantedWhole]).
     */
    private val letterboxed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Apps somebody put on the whole rear by switching "Keep right of the cameras" off: the
     * letterbox check got them wrong, or they would rather see it, so it leaves them there.
     */
    private val wholeAnyway: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** [pkg] switched off: on the whole rear, and kept there. */
    private fun wantedWhole(pkg: String) {
        letterboxed.remove(pkg)
        wholeAnyway.add(pkg)
    }

    /** The next launch is somebody having turned the switch off for the whole rear. Under [placing]. */
    private var wholeRearAsked = false

    /** [relaunchIfMoved] off the caller's thread. */
    private fun relaunchLater(why: String) {
        runCatching { modeWorker.execute { synchronized(placing) { relaunchIfMoved(why) } } }
            .onFailure { Log.w(TAG, "portal: relaunch not queued: ${it.message}") }
    }

    /**
     * Whether a finger now is the private guest's: it is up, the rear is lit, the sheet and the
     * control centre are shut. Read at every frame, on the reader, off every lock ("never block
     * the reader"), after EdgeGestures has had the same frame: a pull claimed by it has just made
     * this false (TouchForward then cancels).
     */
    private fun forwardOpen(): Boolean {
        val g = guestRef.get() ?: return false
        return g.onPrivate && g.onTop && privateLit && !pocketed && !sheetUp && !controlCenter.isUp && !notices.isUp && !lockVeiled && !pillOwns && !binOwns
    }

    /**
     * The guest's app keeps its private display's screen on, as the keep-alive's tick last read it
     * (a video playing): the hold follows it (GuestHold). A dump, so on the tick alone, every six
     * seconds and only over a lit back: never on a touch.
     */
    @Volatile private var privateAppHolds = false

    private fun readPrivateAppHold(g: Guest) {
        val holds = WakeLockDump.keepsScreenOn(exec("dumpsys power | sed -n '/^Wake Locks:/,/^\$/p'"), g.privateDisplay)
        if (holds != privateAppHolds) Log.i(TAG, "portal: ${g.pkg} ${if (holds) "keeps its screen on — the back held with it" else "no longer keeps its screen on"}")
        privateAppHolds = holds
        // Each look that hears it is a touch to the hold, so it hears every tick.
        if (holds) syncGuestHold()
    }

    /** The rear as the hold last read it, for [forwardOpen]: dark, a finger waking it is nobody's. */
    @Volatile private var privateLit = true

    /** The touch reader's forwarder, for [syncPrivate] to say where the guest's display starts. */
    @Volatile private var touchForward: TouchForward? = null

    /** Off the reader. */
    private fun forwardOut(out: List<TouchForward.Out>) {
        if (out.isNotEmpty()) privateDisplay?.forward(out)
    }

    /**
     * One more thing kept for what is on top of the rear, one tick: a private guest asked after by
     * its own things (GuestGate.tickPrivate). True when that was all, false for the usual recovery
     * below. From the keep-alive, off [placing] as it is for a guest on the rear.
     */
    private fun privateTick(g: Guest, displayId: Int): Boolean {
        val list = GuestTask.entries(exec("am stack list"))
        val entry = list.firstOrNull { it.task == g.task }
        val rearTask = list.firstOrNull { it.display == displayId }
        val lit = rearLit(displayId) == true
        lockTick(g, lit)
        val tick = GuestGate.tickPrivate(
            alive = entry != null,
            onDisplay = entry?.display == g.privateDisplay,
            layer = privateDisplay?.displayId == g.privateDisplay,
            onTop = g.onTop,
            rearLit = lit,
            // Listed but not shown is nothing over the panel: a task the panel has been put over.
            rearTask = rearTask?.takeIf { it.visible != false }?.pkg,
            ours = PKG,
            // Xiaomi's launcher under our layer is the rear's home in companion mode, not an app hidden.
            base = if (companion) Panel.XIAOMI_REAR_APP else null,
        )
        if (!g.placed && tick != GuestGate.Tick.GUEST_GONE) return true
        when (tick) {
            GuestGate.Tick.GUEST_ON_TOP -> {
                syncDensity("${g.pkg} is on top")
                if (lit) {
                    syncAppRect("the keep-alive saw it")
                    if (guest?.let { it.task == g.task && it.onTop } == true) chromeShow()
                    readPrivateAppHold(g)
                } else {
                    privateAppHolds = false
                    // Nothing to see: no frame drawing to it, no sheet left up. The layer and the
                    // app stay as they are, and come back with the light (the hold's look).
                    chromeHide()
                    closeSheetNow("the rear is dark")
                }
                return true
            }
            GuestGate.Tick.GUEST_BEHIND -> {
                syncDensity("${g.pkg} is behind")
                chromeHide()
                if (rearTask?.pkg == PKG) { if (cameraLaunched && cameraWasOnTop) closeCamera() else forgetUnseenCamera() }
                if (!lit) closeSheetNow("the rear is dark")
                // No task on the rear at all: the panel is gone. Recovered as it would be.
                return rearTask != null
            }
            GuestGate.Tick.GUEST_GONE -> {
                synchronized(placing) {
                    val now = guest?.takeIf { it.task == g.task && it.onPrivate } ?: return true
                    // Read again under the lock: a launch may have just made them.
                    val at = GuestTask.byId(exec("am stack list"), now.task)?.display
                    if (at != now.privateDisplay || privateDisplay?.displayId != now.privateDisplay) {
                        // Where it was read, for the day it reads wrong: `am stack list` is the only word on it.
                        val read = "its task read on display $at, not ${now.privateDisplay}"
                        if (rearLit(displayId) == true) endGuest(read) else dropGuest("$read, the rear dark")
                    }
                }
                return true
            }
            GuestGate.Tick.USUAL -> {
                // Companion mode: something of somebody else's has the lit rear (Xiaomi's camera,
                // say). The guest goes, and nothing is placed: that is what shows.
                if (GuestGate.onUsual(companion) == GuestGate.Usual.DROP_GUEST) {
                    synchronized(placing) {
                        if (guest?.task == g.task) dropGuest("${rearTask?.pkg} has the rear")
                    }
                    return true
                }
                // What it was hiding has the rear: the guest behind, Resume on the sheet, and the
                // panel re-placed over that as for any other guest.
                Log.i(TAG, "portal: ${rearTask?.pkg} has the rear — ${g.pkg}'s layer down")
                if (markOnTop(g.task, false)) syncSheetGuest()
                chromeHide()
                return dozing
            }
            GuestGate.Tick.GUEST_EXPOSED -> return true // never for one on a private display
        }
    }

    /**
     * One thread for the relaunches asked for off [placing], a payload's camera-area change on
     * update()'s binder thread: each run under [placing], never holding up the thread that asked.
     */
    private val modeWorker by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> guardedThread("portal-mode", r) }
    }

    /**
     * What a full-screen guest's shaping is tried for: its task and the density it is shown at.
     * Given up on one of these ([driftTries]), it is tried again only when one of them changes.
     */
    private data class PlaceKey(val task: Int, val dpi: Int?)

    private fun placeKey(task: Int, pkg: String) = PlaceKey(task, RearDensity.target(pkg, true, portalDpi))

    /** The keep-alive's reshapes of a guest whose bounds drifted: [PLACE_TRIES] ticks running, then left. */
    private val driftTries = GuestShape.GiveUp<PlaceKey>(PLACE_TRIES)

    /**
     * [task] ([pkg]'s, on the rear) given the whole display: TaskShaper's transaction, else `am
     * task resize`, which only a multi-window task takes. One transaction; from anywhere.
     */
    private fun shapeGuest(task: Int, pkg: String, why: String) {
        val b = GuestShape.full(geometry)
        val how = when {
            TaskShaper.apply(task, GuestShape.FULLSCREEN, null) -> "transaction"
            resizeFallback(task, b.left, b.top, b.right, b.bottom) -> "am task resize"
            else -> { Log.w(TAG, "portal: $pkg (task $task) not shaped — the task shaper refused it — $why"); return }
        }
        Log.i(TAG, "portal: $pkg (task $task) on the whole rear, by $how — $why")
    }

    private val noRestrictWorker by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> guardedThread("portal-no-restrict", r) }
    }

    /**
     * The guest on the list of apps HyperOS's freezer leaves alone, for as long as it is on the
     * back, and what we added off it again: see [NoRestrictList]. Reads the list afresh each
     * time, so it is only ever called "has anything changed", from the guest changing and from
     * the service starting, which cleans up after one that died with a guest up. Our marker is
     * written before an app goes on the list and cleared after it comes off, so a death at any
     * point leaves something the next look can clean.
     */
    private fun syncNoRestrict() {
        runCatching {
            noRestrictWorker.execute {
                val raw = exec("settings get system $NO_RESTRICT_SETTING").trim()
                val ours = NoRestrictList.parse(exec("settings get global $NO_RESTRICT_MARKER"))
                val step = NoRestrictList.step(raw, ours, guest?.pkg?.takeIf { PACKAGE_NAME.matches(it) })
                val union = (ours + step.ours).distinct()
                if (union != ours) exec("settings put global $NO_RESTRICT_MARKER ${NoRestrictList.marker(union)}")
                step.list?.let {
                    exec("settings put system $NO_RESTRICT_SETTING ${shellQuote(it)}")
                    Log.i(TAG, "portal: the freezer's list: ${step.ours.ifEmpty { "nothing of ours" }} on it now, of what we added")
                }
                if (step.ours != union) {
                    if (step.ours.isEmpty()) exec("settings delete global $NO_RESTRICT_MARKER")
                    else exec("settings put global $NO_RESTRICT_MARKER ${NoRestrictList.marker(step.ours)}")
                }
            }
        }.onFailure { Log.w(TAG, "portal: the freezer's list not looked at: ${it.message}") }
    }

    /** Until [pkg]'s [task] has drawn on [displayId] within [size], or GuestReveal.MAX_WAIT_MS. */
    private fun awaitDrawn(pkg: String, task: Int, displayId: Int, size: GuestShape.Bounds = GuestShape.full(geometry)) {
        val until = android.os.SystemClock.uptimeMillis() + GuestReveal.MAX_WAIT_MS
        while (android.os.SystemClock.uptimeMillis() < until) {
            if (drawnOn(pkg, task, displayId, size)) return
            Thread.sleep(DRAWN_POLL_MS)
        }
    }

    /**
     * [task], leaving the rear for the front, made an ordinary fullscreen task again: left in
     * multi-window by anything it would keep the rear's bounds there, and the app's next launch
     * would reuse it so. Else, sized to the front's whole screen.
     */
    private fun shapeForFront(task: Int) {
        if (TaskShaper.apply(task, GuestShape.FULLSCREEN, null) || TaskShaper.available) return
        val size = runCatching {
            android.graphics.Point().also { p ->
                @Suppress("DEPRECATION")
                context?.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(0)?.getRealSize(p)
            }
        }.getOrNull()?.takeIf { it.x > 0 && it.y > 0 } ?: return
        resizeFallback(task, 0, 0, size.x, size.y)
    }

    @Volatile private var resizeFallbackLogged = false

    /**
     * `am task resize` [task] to these bounds, only where there is no TaskShaper on this
     * firmware at all (logged once): where there is one and it refused, a resize would be
     * refused too (no such task) or undo nothing it could not. Only a multi-window task takes
     * it. True when it was sent.
     */
    private fun resizeFallback(task: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        if (TaskShaper.available) return false
        if (!resizeFallbackLogged) {
            resizeFallbackLogged = true
            Log.i(TAG, "portal: no task shaper on this firmware — guests shaped with am task resize")
        }
        exec("am task resize $task $left $top $right $bottom")
        return true
    }

    /**
     * The camera strip as a guest arrives, before its reveal so it is there when the cover slides:
     * up in the guest's edge colour when it is on a private display, else down.
     */
    private fun stripFor() {
        syncAppRect("it drew")
        strip.follow(privateApp, privateArea())
        if (guest?.keptRight == true) strip.show(stripColor()) else strip.hide()
    }

    /**
     * The colour of a private guest's left edge (StripColor.sample) for the camera strip: a capture
     * of the app's own layer (RearCapture.layer, the spike's way), no window manager in it and
     * nothing of ours to leave out of it, its pixels in sRGB, one colour picked (StripColor) — or
     * black: secure content (it comes back black), a busy edge, a capture that failed. Null with no
     * such guest: the strip keeps what it has.
     */
    private fun stripColor(): Int? {
        val pd = privateDisplay?.takeIf { !it.wide } ?: return null
        return runCatching {
            val at = StripColor.sample(geometry, privateApp ?: pd.area, pd.area.left)
            val crop = android.graphics.Rect(at.left, at.top + pd.hidden, at.right, at.bottom + pd.hidden)
            // A quarter of the rows: the edge's colour does not need every one, and fewer cost less.
            // Its layer held for the capture (ShellLayer.lease): a close may let it go at any moment.
            val shot = ShellLayer.lease(listOf(pd.layer)) { live ->
                live.firstOrNull()?.let { RearCapture.layer(it, crop, STRIP_SAMPLE_SCALE) }
            } ?: return@runCatching BLACK
            try {
                if (shot.secure) return@runCatching BLACK
                val hw = android.graphics.Bitmap.wrapHardwareBuffer(shot.buffer, shot.space) ?: return@runCatching BLACK
                val bmp = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                hw.recycle()
                val w = minOf(StripColor.SAMPLE_W, bmp.width)
                val srgb = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
                val px = IntArray(w * bmp.height) { i ->
                    bmp.getColor(i % w, i / w).convert(srgb).toArgb()
                }
                bmp.recycle()
                StripColor.pick(px)
            } finally {
                runCatching { shot.buffer.close() }
            }
        }.getOrDefault(BLACK)
    }

    /**
     * Where a keyboard starts on a guest kept right of the cameras, for the strip to leave it be
     * (CameraStrip): a thin column of a bar beside a narrow app off its layer (StripColor.keyboardProbe),
     * read as StripColor.keyboardTop. -1 with none, or no bar to look in; null when unread.
     */
    @Volatile private var keyboardLogs = 0

    private fun keyboardTop(): Int? {
        val pd = privateDisplay?.takeIf { !it.wide } ?: return null
        val probe = StripColor.keyboardProbe(geometry, privateApp ?: pd.area, pd.area) ?: return -1
        return runCatching {
            val crop = android.graphics.Rect(probe.left, probe.top + pd.hidden, probe.right, probe.bottom + pd.hidden)
            val shot = ShellLayer.lease(listOf(pd.layer)) { live ->
                live.firstOrNull()?.let { RearCapture.layer(it, crop, KEYBOARD_SCALE) }
            } ?: return@runCatching null
            try {
                if (shot.secure) return@runCatching null
                val hw = android.graphics.Bitmap.wrapHardwareBuffer(shot.buffer, shot.space) ?: return@runCatching null
                val bmp = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                hw.recycle()
                val column = IntArray(bmp.height) { bmp.getPixel(0, it) }
                val scale = bmp.height / probe.height.toFloat()
                bmp.recycle()
                if (BuildConfig.DEBUG && keyboardLogs++ % 25 == 0) Log.i(TAG, "keyboard probe $probe, ${column.size} rows at $scale: " +
                    column.toList().chunked(column.size / 8 + 1).joinToString(" ") { c -> Integer.toHexString(c.last()) })
                StripColor.keyboardTop(column, scale) ?: -1
            } finally {
                runCatching { shot.buffer.close() }
            }
        }.onFailure { Log.w(TAG, "keyboard probe failed: ${(it.cause ?: it).message}") }.getOrNull()
    }

    /** The rear by the `screencap` command, for a cover where the in-process capture is missing; null if it cannot be had. */
    private fun screencapRear(): android.graphics.Bitmap? {
        val id = rearPhysicalId() ?: return null // without -d it would be the FRONT screen
        val p = ProcessBuilder("screencap", "-p", "-d", id).start()
        return try {
            p.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        } finally {
            runCatching { p.waitFor() }
            p.destroy()
        }
    }

    /**
     * [pkg]'s [task] has an app window drawn on [displayId] within [size] — the whole rear, or a
     * private display's (GuestDrawn).
     */
    private fun drawnOn(pkg: String, task: Int, displayId: Int, size: GuestShape.Bounds = GuestShape.full(geometry)) =
        task > 0 && GuestDrawn.drawn(exec("dumpsys window $pkg"), task, displayId, size.width, size.height)

    /**
     * [cover] off the rear once [drawn] says [what] — the app now on top — has drawn there,
     * asked as fast as it answers, or GuestReveal.MAX_WAIT_MS has gone by without a sign of
     * it: slid left and faded on GuestReveal's curve, a transaction a frame at [displayId]'s
     * rate, then removed. On the caller's thread, under [placing].
     */
    private fun reveal(cover: GuestCover, what: String, displayId: Int, onDrawn: () -> Unit = {}, drawn: () -> Boolean) {
        val coveredAt = android.os.SystemClock.uptimeMillis()
        val r = GuestReveal(coveredAt)
        // Blurred and dimmed over all of it (GuestCurtain): the cover is a still picture, and
        // without this the rear looked frozen until it went. Every change made under a cover
        // comes through here — a launch, the tile, Panel, Front, Close, Resume.
        runCatching { curtain.rise() }
        try {
            var seen = false
            while (r.waiting(android.os.SystemClock.uptimeMillis())) {
                if (drawn()) { seen = true; break }
                Thread.sleep(DRAWN_POLL_MS)
            }
            // Whatever goes with the new top before it is seen (the camera strip), still covered.
            runCatching { onDrawn() }.onFailure { Log.w(TAG, "portal: before the reveal: $it", it) }
            // The slide from now, after that, so it never starts part-way.
            val now = android.os.SystemClock.uptimeMillis()
            r.drawn(now)
            if (seen) Log.i(TAG, "portal: $what drew on the rear ${now - coveredAt} ms after the change — revealing")
            else Log.w(TAG, "portal: no sign of $what drawing on the rear in ${GuestReveal.MAX_WAIT_MS} ms — revealing anyway")
            val frameMs = frameMsOf(displayId)
            while (true) {
                val f = r.at(android.os.SystemClock.uptimeMillis(), geometry.widthPx.toFloat())
                cover.place(f.dx, f.alpha)
                if (r.phase == GuestReveal.Phase.DONE) break
                Thread.sleep(frameMs)
            }
        } finally {
            // Interrupted or not, a cover is never left over the rear.
            cover.remove()
            runCatching { curtain.fall() }
        }
    }

    /**
     * A change of what is on top of the rear, made under a cover (GuestCover) of the rear as
     * it shows now and revealed as a launch is: [change] runs underneath, and if it changed
     * something (true), the cover slides away once [drawn] says [what] — the new top — has
     * drawn; if it did not, the cover simply goes, since the rear is as it was. No cover on
     * a rear asleep, or where there can be none: [change] alone. The sheet is down already
     * (a choice runs once it has ended), so it is not in the picture; the frame is left out
     * where it can be, and fades as the change has it. Under [placing].
     */
    private fun covered(what: String, drawn: () -> Boolean, onDrawn: () -> Unit = {}, change: () -> Boolean): Boolean {
        val display = rearDisplay()
        val cover = if (!dozing) coverRear(display) else null
        val changed = try { change() } catch (e: Throwable) { cover?.remove(); throw e }
        if (cover != null) {
            if (changed) reveal(cover, what, display, onDrawn, drawn) else cover.remove()
        } else if (changed) onDrawn()
        return changed
    }

    /** Our panel is what the rear composes on top (over [g]), and has drawn there at its size. */
    private fun panelShown(g: Guest): Boolean =
        rearTop(g) == PKG && drawnOn(PKG, taskId(), rearDisplay())

    /** One frame of [displayId] in ms, from its refresh rate; 60 Hz's when it does not say. */
    private fun frameMsOf(displayId: Int): Long = runCatching {
        val hz = context?.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(displayId)?.refreshRate
        if (hz != null && hz >= 24f) Math.round(1000f / hz).toLong().coerceIn(4L, 42L) else 16L
    }.getOrDefault(16L)

    /**
     * Started on the front — by name when [front], else wherever `am start` puts it — and its
     * task once the top activity has held still, or none. [retries]: asked once, as soon as its task
     * is seen to have gone (LockRetry.closedItself), whether that is the end of waiting for it.
     */
    private fun startSettled(component: String, pkg: String, front: Boolean, retries: (Boolean) -> Boolean): Started {
        val out = exec(if (front) "am start --display 0 -n $component" else "am start $NO_SLIDE -n $component")
        val took = !out.contains("Error", ignoreCase = true)
        val settle = GuestTask.Settle()
        val began = android.os.SystemClock.uptimeMillis()
        val until = began + GUEST_SETTLE_LIMIT_MS
        var saw = false
        var lastSeen = began
        var asked = false
        while (android.os.SystemClock.uptimeMillis() < until) {
            val entry = GuestTask.taskOf(exec("am stack list"), pkg)
            val now = android.os.SystemClock.uptimeMillis()
            if (entry != null) {
                saw = true
                lastSeen = now
                if (settle.feed(entry.top, now)) return Started.Task(entry.task)
            } else if (!asked && LockRetry.closedItself(took, saw, now - lastSeen)) {
                asked = true
                if (retries(true)) return Started.ClosedBehindLock
            }
            Thread.sleep(150)
        }
        return GuestTask.taskOf(exec("am stack list"), pkg)?.let { Started.Task(it.task) } ?: Started.Nothing
    }

    /** What a start on the front came to. */
    private sealed interface Started {
        data class Task(val id: Int) : Started
        data object ClosedBehindLock : Started
        data object Nothing : Started
    }

    override fun sendFocusedToBack(displayId: Int): String {
        if (displayId < 0 || placedDisplayId < 0) return sendFocusedToBackNow(displayId)
        return curtained { sendFocusedToBackNow(displayId) }
    }

    private fun sendFocusedToBackNow(displayId: Int): String = synchronized(placing) {
        if (displayId < 0) return@synchronized ""
        // As in launchGuest: without a placed panel no keep-alive would watch the guest.
        if (!guestWatched(displayId)) { Log.w(TAG, "portal: no panel placed — not sending to back"); return@synchronized "" }
        val dump = exec("dumpsys window displays | grep -E 'mDisplayId=|mFocusedApp='")
        val pkg = FocusDump.focusedPackage(dump, 0) ?: return@synchronized ""
        val task = FocusDump.focusedTask(dump, 0) ?: return@synchronized ""
        if (!PACKAGE_NAME.matches(pkg) || !GuestTask.sendable(pkg, PKG)) return@synchronized ""
        // The old guest to the front made an ordinary fullscreen task again, as launchGuest and
        // Front send it: left in multi-window it keeps the rear's bounds there.
        guest?.let { old ->
            if (old.task != task) {
                moveToDisplay(old.task, 0); shapeForFront(old.task)
                if (old.onPrivate) closePrivate("${old.pkg} went to the front")
            }
        }
        if (sendToPrivate(pkg, task, displayId, wide = !GuestShape.onPrivateDisplay(pkg, portalConfined))) return@synchronized pkg
        guest = Guest(pkg, task, onTop = true)
        syncDensity("$pkg is landing")
        if (!moveToDisplay(task, displayId)) { endGuest("move refused"); return@synchronized "" }
        shapeGuest(task, pkg, "sent to the back")
        wake(displayId)
        guestPlaced(displayId)
        pkg
    }

    /**
     * The focused app's [task], kept right of the cameras, moved to a new private display: not a
     * launch, since it is what the app is showing now that is being sent. False, with no display
     * left over, when it would not go: the tile then puts it on the whole rear, which is where it
     * has always gone. Under [placing].
     */
    private fun sendToPrivate(pkg: String, task: Int, displayId: Int, wide: Boolean): Boolean {
        val pd = openPrivate(pkg, wide) ?: return false
        if (!moveToDisplay(task, pd.displayId) || GuestTask.byId(exec("am stack list"), task)?.display != pd.displayId) {
            Log.w(TAG, "portal: $pkg's task $task would not go to display ${pd.displayId} — the whole rear instead")
            // Off it first, wherever it read: the display takes what is on it when it goes.
            moveToDisplay(task, 0)
            pd.release()
            return false
        }
        privateDisplay = pd
        guest = Guest(pkg, task, onTop = true, privateDisplay = pd.displayId, wide = pd.wide)
        syncDensity("$pkg is landing")
        wake(displayId)
        guestPlaced(displayId)
        appRectSoon("it was sent to the back")
        return true
    }

    /** A guest is on top: the frame, the watcher, and the sheet told. Under [placing]. */
    private fun guestPlaced(displayId: Int) {
        guest = guest?.copy(placed = true)
        syncDensity("${guest?.pkg} is the guest") // already set as it landed; a no-op then
        chromeShow() // already up from the launch's move; a no-op while running
        syncTouchWatch()
        syncSheetGuest()
    }

    /** No guest any more: frame, watcher and the sheet's buttons off. Under [placing]. */
    private fun dropGuest(why: String) {
        Log.i(TAG, "portal: guest over — $why")
        guest = null
        chromeHide()
        closePrivate(why)
        syncDensity("the guest is over")
        // Over the panel the watch goes on (it is for the panel too); dozing, it stops.
        syncTouchWatch()
        syncSheetGuest()
    }

    /**
     * [dropGuest], and the panel back in front. Under [placing]. In companion mode only the
     * drop: Xiaomi's rear screen is simply what shows again (GuestGate.endRestoresPanel).
     */
    private fun endGuest(why: String) {
        dropGuest(why)
        if (GuestGate.endRestoresPanel(companion, placedDisplayId >= 0)) show(lastPayload, placedDisplayId)
    }

    /**
     * Whether a guest put up now would be watched: a placed panel's keep-alive, or in companion
     * mode the guest-only keep-alive, started here (it watches the guest, never a panel).
     */
    private fun guestWatched(displayId: Int): Boolean {
        if (placedDisplayId >= 0) return true
        if (!companion) return false
        startKeepAlive(displayId)
        return true
    }

    /** The payload's mode, or this service's when it says none (a calibrate payload, an unreadable one). */
    private fun companionIn(payload: String): Boolean =
        if (CalibrateMode.isCalibrate(payload)) companion
        else runCatching { StatePayload.decode(payload).config.companion }.getOrDefault(companion)

    override fun guestAction(which: String?) {
        when (which) {
            // The panel stays over a live guest; the sheet then offers Resume.
            "panel" -> {
                val g = guest ?: return
                markOnTop(g.task, false)
                chromeHide()
                syncSheetGuest()
            }
            // RearActivity has gone behind (moveTaskToBack); the guest is on top again. One
            // the shell sheet hid on the front has to be brought back for that to be true.
            "resume" -> {
                val g = guest ?: return
                if (g.hiddenOnFront) { synchronized(placing) { unhide("the panel's Resume") }; return }
                markOnTop(g.task, true)
                chromeShow()
                syncSheetGuest()
                wake(if (placedDisplayId >= 0) placedDisplayId else Panel.DISPLAY_ID)
            }
            // Read under the lock: a launch may have replaced the guest since the panel asked.
            "front" -> synchronized(placing) {
                val g = guest ?: return
                moveToDisplay(g.task, 0)
                shapeForFront(g.task)
                endGuest("sent to the front")
            }
            // Out of Recents (its task removed) and ended, services and all ([endProcess]). Not
            // force-stop: that killed it
            // but left it in Recents, where it looked still open on the front (the owner,
            // 2026-11-08), and a force-stopped app gets no notifications or alarms until it is
            // opened again.
            "close" -> synchronized(placing) {
                val g = guest ?: return
                exec("am stack remove ${g.task}")
                // The panel back first, the app ended after it, off this thread: ended first, the
                // panel waited for a big app's process to die (TikTok's, with its services), and
                // the Close read as slow (2026-11-09). Its task is already gone, so nothing shows.
                startedPids.remove(g.pkg)
                endGuest("closed")
                stopAfterClose(g.pkg)
            }
            else -> Log.w(TAG, "portal: unknown guest action '$which'")
        }
    }

    // -- the portal sheet, in the shell ---------------------------------------------------
    //
    // See specs/2026-10-28-app-portal-shell-sheet-design.md. The sheet is a layer of ours
    // (ShellSheet) and its input is the raw rear touchscreen (getevent → RearTouches →
    // EdgeGestures), so the first swipe counts over the panel and over a guest, locked or
    // not, and nothing is ever started to show it.
    //
    // THE TOUCH WATCH runs while the rear is lit and ours: the panel placed and not asleep
    // on purpose, or a guest on top (the panel's own sleep timer runs out behind a guest;
    // the guest is still lit). One getevent at a time, started and stopped by
    // [syncTouchWatch] — from show/wake/setDozing/hide, guest changes, and every keep-alive
    // tick as the safety net for a getevent that died.
    //
    // THE SHEET: [sheetLock] guards the gestures, [motion] and the flags below. The reader
    // thread feeds touches; [sheetClock], one thread, runs begin()/end() (end() may block
    // for its join, never on the reader), the settle's frames and the pages' — so
    // begin and end are applied in the order they were asked for.

    private val touchWatch = AtomicReference<Process?>(null)
    private val touchLock = Any()
    @Volatile private var rearTouch: Triple<String, Int, Int>? = null
    /** No rear touchscreen found: not asked again before this uptime. */
    @Volatile private var rearTouchRetryAt = 0L
    private var warnedNoRearTouch = false

    private fun touchWanted() =
        if (companion) PortalPull.companionTouchWanted(
            guestOnTop = guest?.onTop == true || cameraUp(),
            shellUp = sheetUp || notices.isUp || controlCenter.isUp || charge.isUp || scene.isUp,
            rearLit = rearLit(rearDisplay()) == true,
            // The top pull is the notifications' too now, so they keep the watch running as More apps does.
            moreApps = moreApps || noticesOn || sceneWanted(),
            doubleTaps = latchWanted,
        )
        else placedDisplayId >= 0 && (!dozing || guest?.onTop == true || cameraUp())

    /** The watch running if and only if [touchWanted]. Any thread. */
    private fun syncTouchWatch() = synchronized(touchLock) {
        val running = touchWatch.get()?.isAlive == true
        when {
            touchWanted() && !running -> startTouchWatch()
            !touchWanted() && running -> stopTouchWatch()
        }
    }

    /** Under [touchLock]. */
    private fun startTouchWatch() {
        stopTouchWatch()
        val device = rearTouch ?: run {
            if (android.os.SystemClock.uptimeMillis() < rearTouchRetryAt) return
            BandTap.rearDevice(exec("getevent -pl")).also { d ->
                if (d != null) rearTouch = d
                else {
                    rearTouchRetryAt = android.os.SystemClock.uptimeMillis() + TOUCH_RETRY_MS
                    if (!warnedNoRearTouch) {
                        warnedNoRearTouch = true
                        Log.w(TAG, "portal: no rear touchscreen in getevent — no sheet, no Back")
                    }
                }
            }
        } ?: return
        val (path, maxX, maxY) = device
        val proc = try {
            ProcessBuilder("sh", "-c", "exec getevent $path").redirectErrorStream(true).start()
        } catch (e: Exception) { Log.w(TAG, "portal: getevent failed: ${e.message}"); return }
        touchWatch.set(proc)
        // Its pull bands start where this rear's lenses end, in panel units.
        val gestures = EdgeGestures(geometry.cutoutUnits, geometry.fitX)
        // Companion mode's top pull, over Xiaomi's rear screen with no guest of ours on top.
        gestures.topPortal = { companion && moreApps && !noticesOn && guest?.onTop != true }
        // With notifications on, the pull down from the top is theirs (the pinned apps ride on their list).
        gestures.topNotices = { companion && noticesOn && guest?.onTop != true }
        // On the dome a finger goes any way; on the strips over and under it, it moves the sheet.
        // Only on the open sheet at rest with its apps whole — not scrolled up to the sliders,
        // whose drags are theirs and the sheet's: with the dome's part taken from where it
        // stands unscrolled, a finger on the sliders was the dome's, and neither moved them nor
        // brought the sheet back down (the owner, 2026-11-10). Asked as a finger's drag starts,
        // on the reader's thread under the sheet's lock, as every action is.
        gestures.roams = { x, y ->
            portalDome && !sheetLocked && runCatching {
                val at = motion.settled(android.os.SystemClock.uptimeMillis())
                at != null && motion.heading && PortalSheet.scroll(at) < 0.5f && PortalDome.turns(sheetLayout()).contains(x, y)
            }.getOrDefault(false)
        }
        synchronized(sheetLock) {
            gestures.sheet = sheetUp
            this.gestures = gestures
        }
        guarded {
            // The font and the layer stack, off every binder thread and before the first pull.
            sheetOrNull()
            // And the capture, once, so the first cover is taken in this process, not by screencap.
            RearCapture.warm(rearDisplay())
            val touches = RearTouches(maxX, maxY)
            // Every finger, for three held together (ThreeHold): the same lines.
            val three = ThreeHold(maxX, maxY)
            threeHold = three
            // The same lines, every finger, for a guest on a private display. After the first
            // finger's gestures, so that a pull claimed on this very frame is not forwarded.
            val rear = geometry
            val forward = TouchForward(
                maxX, maxY, rear.widthPx, rear.heightPx, if (guest?.wide == true) 0 else rear.cutoutPx,
                backLeft = EdgeGestures.BACK_LEFT * rear.fitX, slop = EdgeGestures.SLOP * rear.fitX,
            )
            touchForward = forward
            val startedAt = android.os.SystemClock.uptimeMillis()
            var events = 0
            // One line at a time from either source: the touchscreen, or in a debug build the
            // demo's made-up finger (DemoTouchFeed), which shares this watch's state.
            val feedLock = Any()
            fun take(line: String, madeUp: Boolean) = synchronized(feedLock) {
                val now = android.os.SystemClock.uptimeMillis()
                // A made-up finger's line: the stroke it is part of never throws out a notification of his (madeUpFinger).
                if (madeUp) madeUpLineAt = now
                val got = touches.feed(line, now)
                // A second finger down with the first (RearTouches.crowded): no single-finger gesture of it, from right
                // after its landing (a Down in this same frame) and before anything else of it.
                val crowdAt = if (touches.crowdBegan) RearTouches.crowdIndex(got) else -1
                got.forEachIndexed { i, e ->
                    if (i == crowdAt) fingersCrowded()
                    events++
                    if (e is RearTouches.Down) fingerDownAt = e.atMs
                    onRearTouch(e, gestures)
                }
                if (crowdAt == got.size) fingersCrowded()
                when (val held = three.feed(line, now)) {
                    is ThreeHold.Out.Arm -> runCatching { holdHandler.removeCallbacks(threeCheck); holdHandler.postDelayed(threeCheck, held.atMs - now + THREE_CHECK_LATE_MS) }
                    is ThreeHold.Out.Fire -> onThreeFingers(held)
                    null -> Unit
                }
                forwardOut(forward.feed(line, now, ::forwardOpen))
            }
            if (BuildConfig.DEBUG) DemoTouchFeed.attach(proc) { take(it, madeUp = true) }
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) take(line, madeUp = false)
                }
                forwardOut(forward.cancel(android.os.SystemClock.uptimeMillis()))
            } catch (_: java.io.IOException) {
                // A finger down as the reader goes is left open on the app otherwise.
                forwardOut(forward.cancel(android.os.SystemClock.uptimeMillis()))
                // The stream closing under us is how stopTouchWatch() ends this.
            } catch (e: Throwable) {
                Log.w(TAG, "portal: touch watch stopped", e)
            } finally {
                runCatching { proc.destroy() }
                val lived = android.os.SystemClock.uptimeMillis() - startedAt
                // A watch that ended at once, or read nothing, may have had a stale device
                // path (input devices renumber): the next start asks getevent again.
                if (lived < TOUCH_SHORT_LIFE_MS || events == 0) rearTouch = null
                // Died on its own (stopTouchWatch had not taken it): a sheet up has no reader
                // left to close it — its blocker would hold every touch — so it goes now,
                // and the watch comes back. Not at once after a quick death, so a getevent
                // that cannot run is not respawned in a loop: the keep-alive tick does it.
                if (touchWatch.compareAndSet(proc, null)) {
                    Log.w(TAG, "portal: the touch watch died after ${lived} ms, $events events")
                    closeSheetNow("the touch watch died")
                    if (lived >= TOUCH_SHORT_LIFE_MS) syncTouchWatch()
                }
            }
        }.apply { isDaemon = true; name = "portal-touches"; start() }
    }

    /** No touches read; a sheet up goes with it, since nothing could close it any more. */
    private fun stopTouchWatch() {
        val proc = touchWatch.getAndSet(null) ?: return
        runCatching { proc.destroy() }
        closeSheetNow("the touch watch stopped")
    }

    private val sheetLock = Any()
    /** The watch's classifier; one per getevent, none before the first. Under [sheetLock]. */
    private var gestures: EdgeGestures? = null
    private val motion = SheetMotion()
    /** From an opening's begin until its end is asked for. Written under [sheetLock]; [forwardOpen] reads it off it. */
    @Volatile private var sheetUp = false
    /** begin() asked for and not yet run on [sheetClock]. Under [sheetLock]. */
    private var beginPending = false
    /** This finger is moving the sheet (a pull or a drag on it). Under [sheetLock]. */
    private var driving = false
    /** Where the current finger landed: a drag is measured from there. Under [sheetLock]. */
    private var touchDownY = 0f
    /** And across: a sideways drag is measured from there. Under [sheetLock]. */
    private var touchDownX = 0f
    /** The grid's pages: which shows, and their slide. Under [sheetLock]. */
    private val pager by lazy { SheetPager(sheetLayout().pageStride) }
    /** What this finger's sideways drag moves (PortalSheet.swipe), decided as it starts. Under [sheetLock]. */
    private var slide = PortalSheet.Swipe.NONE
    /** A slider drag's app, the DPI its thumb started at, and where it is now. Under [sheetLock]. */
    private var sliderPkg: String? = null
    private var sliderFrom = 0
    private var sliderAt = 0
    /** The width slider's drag, as the DPI's: whose, from where, and to where (null: the whole). Under [sheetLock]. */
    private var widthPkg: String? = null
    private var widthFrom: Int? = null
    private var widthAt: Int? = null
    /**
     * And when: a drag is timed from there too. Grabbed at the pull's own time, its first step
     * took "1 ms" and every pull read as a flick. Under [sheetLock].
     */
    private var touchDownAt = 0L
    /** Run once the sheet is down: the tapped app's launch, a button's choice. Under [sheetLock]. */
    private var afterShut: (() -> Unit)? = null
    /**
     * [afterShut] is a button's choice that holds the frame's veil (PortalFrame.holdVeil):
     * the choice lets it go when it has run, and a choice dropped unrun lets it go here
     * ([dropChoice], [sheetDown]) — never left holding. Under [sheetLock].
     */
    private var veilHeld = false

    /** No choice to run after all; a veil it held let go. Under [sheetLock]. */
    private fun dropChoice() {
        afterShut = null
        if (veilHeld) { veilHeld = false; frame.releaseVeil() }
        releaseCurtain()
    }

    /** The curtain was raised for a launch chosen on the sheet, and is not yet let fall. */
    @Volatile private var curtainHeld = false

    /** That curtain let fall, once: the launch done, or never to come. Any thread. */
    private fun releaseCurtain() {
        val held = synchronized(curtainLock) { curtainHeld.also { curtainHeld = false } }
        if (held) runCatching { curtain.fall() }
    }
    private val curtainLock = Any()
    private var anim: java.util.concurrent.ScheduledFuture<*>? = null
    /** The button (or the slider's part, PortalSheetPainter.PRESS_*) shown pressed, or -1. Under [sheetLock]. */
    private var pressed = -1
    /** The guest as the sheet shows it: null none (no buttons), true on top (Panel), false behind (Resume); with either, the DPI slider. */
    @Volatile private var sheetGuestOnTop: Boolean? = null
    @Volatile private var sheetLook: Pair<dev.kb896.aiusage.data.PanelTheme, dev.kb896.aiusage.data.GlassStyle?>? = null

    private val sheetClock: java.util.concurrent.ScheduledExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "portal-sheet-clock").apply { isDaemon = true }
        }
    }

    @Volatile private var shellSheet: ShellSheet? = null
    /** The panel's theme and chosen font as the sheet's face was last built for (under [sheetMaking]). */
    @Volatile private var sheetType: Pair<dev.kb896.aiusage.data.PanelTheme, dev.kb896.aiusage.data.PanelFont?> =
        dev.kb896.aiusage.data.PanelTheme.GLASS to null
    /** The font did not load: no new try before this uptime. */
    @Volatile private var sheetFontRetryAt = 0L
    private val sheetMaking = Any()

    /** The sheet, made once the panel's font is in hand; null if it cannot be (tried again after a minute). */
    private fun sheetOrNull(): ShellSheet? {
        shellSheet?.let { return it }
        if (android.os.SystemClock.uptimeMillis() < sheetFontRetryAt) return null
        synchronized(sheetMaking) {
            shellSheet?.let { return it }
            if (android.os.SystemClock.uptimeMillis() < sheetFontRetryAt) return null
            val face = sheetFace(sheetType)
            if (face == null) { sheetFontRetryAt = android.os.SystemClock.uptimeMillis() + SHEET_FONT_RETRY_MS; return null }
            val s = ShellSheet(rearLayerStack(), geometry, face)
            controlCenter.face(face)
            notices.face(face)
            // The portal frame fades under the sheet, as far as it is open.
            s.onProgress = { frame.sheetOpen(it); strip.sheetOpen(it) }
            sheetLook?.let { (theme, style) -> s.theme(theme, style) }
            s.apps(portalApps)
            s.dpiValues(portalDpi)
            s.widthValues(portalWidth)
            s.frameColor(portalFrameColor)
            s.dpiDefault(sliderDefault())
            s.keepRight(guest?.let { keepsRight(it) } == true)
            s.grid(portalGrid)
            s.names(portalNames)
            s.dome(portalDome)
            shellSheet = s
            // Again, now that it is published: a setPortalDpi between the first and here told
            // no sheet. The lock makes this one see that write.
            synchronized(dpiLock) { s.dpiValues(portalDpi); s.widthValues(portalWidth) }
            // And the grid and the names: a payload between the first and here told no sheet.
            s.grid(portalGrid)
            s.names(portalNames)
            s.dome(portalDome)
            s.domeAt(dome.pose(android.os.SystemClock.uptimeMillis()))
            s.dpiDefault(sliderDefault())
            syncSheetGuest()
            return s
        }
    }

    /**
     * The face loaded for the banner and the control centre alone, with no sheet made (under
     * [sheetMaking]). Companion mode's banner must not wait for the touch watch to make the sheet:
     * with More apps off, or a new shell holding a banner, there is no touch watch yet, and a
     * banner with no face is dropped (ShellNotices.Banner).
     */
    @Volatile private var noticeFaceLoaded = false

    /** The banner can be drawn: the sheet made, or the face loaded on its own. */
    private fun noticeFaceReady(): Boolean = shellSheet != null || noticeFaceLoaded

    /**
     * The face for the banner, loaded now if it is not yet: true once it is in hand. Blocking (an
     * exec and a font read): on [popWaiter], never a binder thread. Retried after a minute, as
     * [sheetOrNull], if the font does not load.
     */
    private fun ensureNoticeFace(): Boolean {
        if (noticeFaceReady()) return true
        if (android.os.SystemClock.uptimeMillis() < sheetFontRetryAt) return false
        synchronized(sheetMaking) {
            if (noticeFaceReady()) return true
            if (android.os.SystemClock.uptimeMillis() < sheetFontRetryAt) return false
            val face = sheetFace(sheetType)
            if (face == null) { sheetFontRetryAt = android.os.SystemClock.uptimeMillis() + SHEET_FONT_RETRY_MS; return false }
            controlCenter.face(face)
            notices.face(face)
            noticeFaceLoaded = true
            return true
        }
    }

    /** The panel's face for [type] (theme, chosen font) — see ShellFont.face. */
    private fun sheetFace(type: Pair<dev.kb896.aiusage.data.PanelTheme, dev.kb896.aiusage.data.PanelFont?>) =
        ShellFont.face(context, type.first, type.second) { exec("pm path $PKG") }

    /**
     * The panel's theme or font changed: the sheet's face follows. A sheet not made yet
     * takes it when it is; one whose new face does not build keeps its old one, and the
     * next payload tries again.
     */
    private fun restyleSheetFace(type: Pair<dev.kb896.aiusage.data.PanelTheme, dev.kb896.aiusage.data.PanelFont?>) {
        if (type == sheetType) return
        synchronized(sheetMaking) {
            if (type == sheetType) return
            val s = shellSheet
            // No sheet, but the banner's face loaded on its own: it follows the new type now.
            if (s == null && noticeFaceLoaded) {
                val face = sheetFace(type) ?: return
                sheetType = type
                controlCenter.face(face)
                notices.face(face)
                return
            }
            if (s == null) { sheetType = type; return }
            val face = sheetFace(type) ?: return
            sheetType = type
            s.face(face)
            controlCenter.face(face)
            notices.face(face)
        }
    }

    private fun syncSheetGuest() {
        // A locked sheet offers no guest: no Resume, no sliders, until the code is given.
        val g = guest?.takeIf { it.sheetOnTop != null && !sheetLocked }
        val was = sheetGuestOnTop
        sheetGuestOnTop = g?.sheetOnTop
        shellSheet?.guest(sheetGuestOnTop, g?.pkg)
        shellSheet?.guestIcon(g?.pkg?.let { guestIconOf(it) })
        shellSheet?.dpiDefault(sliderDefault())
        syncSheetKeepRight()
        // An open sheet's stops follow: the slider's scroll comes with a guest and goes with it.
        // On the clock, with the settle frames it may start, not on whichever thread this is.
        if (was != sheetGuestOnTop) runCatching { sheetClock.execute { restop() } }
    }

    /** The sheet's "Keep right of the cameras" as the guest's now is. */
    private fun syncSheetKeepRight() {
        // Not offered for one here only for this open: with the front locked it runs nowhere
        // else, and the switch could only make it fail (the owner, 2026-10-30).
        shellSheet?.keepRight(guest?.let { keepsRight(it) } == true)
    }

    /** What the DPI slider stands at for a guest with none of its own: its private display's, or the rear's as read. */
    private fun sliderDefault(): Int? =
        if (guest?.onPrivate == true) RearDensity.privateDefault(geometry) else densityDefault

    /** An open sheet's stops, its pages and its buttons as the guest now has them. On [sheetClock]. */
    private fun restop() = runCatching {
        synchronized(sheetLock) {
            if (!sheetUp) return@synchronized
            val now = android.os.SystemClock.uptimeMillis()
            motion.setDetents(PortalSheet.detents(sheetGuestOnTop), now)
            sheetLayout().let { pager.stride = it.pageStride; pager.pages = it.pages }
            afterMotion(now)
        }
    }.onFailure { Log.w(TAG, "portal sheet: restop failed: ${it.message}") }

    /** The last guest whose icon was looked up off the pinned apps, and what came of it (null while loading or none). */
    @Volatile private var guestIconCache: Pair<String, android.graphics.Bitmap?>? = null

    /**
     * [pkg]'s icon for the sheet's Resume: a pinned app's as the main app sent it; else
     * PackageManager's (it answers uid 2000 through our package's Context), loaded once
     * per guest on a thread of its own — null until it lands, then handed to the sheet.
     */
    private fun guestIconOf(pkg: String): android.graphics.Bitmap? {
        val apps = portalApps
        apps.packages.indexOf(pkg).takeIf { it >= 0 }?.let { i -> apps.icon(i)?.let { return it } }
        guestIconCache?.let { (p, icon) -> if (p == pkg) return icon }
        guestIconCache = pkg to null
        guarded {
            val icon = runCatching { context?.packageManager?.getApplicationIcon(pkg)?.toBitmap(GUEST_ICON_PX, GUEST_ICON_PX) }
                .onFailure { Log.w(TAG, "portal sheet: no icon for $pkg: ${it.message}") }
                .getOrNull()
            if (guestIconCache?.first != pkg) return@guarded
            guestIconCache = pkg to icon
            if (icon != null && guest?.takeIf { it.sheetOnTop != null }?.pkg == pkg) shellSheet?.guestIcon(icon)
        }.apply { isDaemon = true; name = "portal-guest-icon"; start() }
        return null
    }

    /** When the last finger landed, and when a made-up finger's line (a debug build's DemoTouchFeed) last came: uptime ms, 0 for never. */
    @Volatile private var fingerDownAt = 0L
    @Volatile private var madeUpLineAt = 0L

    /**
     * The finger of the last stroke was MADE UP (rear-touch.py, the regression: DemoTouchFeed), wholly or in part: a line
     * of the made-up feed came since it landed. Its throws never reach the phone (NoticeThrow.toPhone).
     */
    private fun madeUpFinger(): Boolean = NoticeThrow.madeUp(madeUpLineAt, fingerDownAt)

    /** One touch, on the reader thread. */
    /** When the rear touchscreen last reported anything (elapsedRealtime); 0 before the first. */
    @Volatile private var lastRearTouchAt = 0L

    override fun msSinceRearTouch(): Long =
        lastRearTouchAt.let { if (it == 0L) -1L else android.os.SystemClock.elapsedRealtime() - it }

    private fun onRearTouch(e: RearTouches.Event, from: EdgeGestures) {
        lastRearTouchAt = android.os.SystemClock.elapsedRealtime()
        // While the back is not held lit the app is not looking (StayOn stops its tick): told of a finger, not
        // more than once in a few seconds, so that a tap on a dark back can light it.
        if (stay == 0 && lastRearTouchAt - touchToldAt > TOUCH_TELL_MS) {
            touchToldAt = lastRearTouchAt
            if (WakeStyle.light(rearState(rearDisplay())) != WakeStyle.Light.ON) noteDarkTouch("a finger")
            runCatching { events?.onRearTouch() }
        }
        guestHoldTouch(e)
        // And the scene, which takes every touch while it is up (SceneTouch): a tap brightens it,
        // a swipe on purpose is handed to Xiaomi's screen, a swipe down there brings it back.
        sceneTouch(e, from)
        // Used over the panel, the list keeps it awake as the sheet does.
        if (e is RearTouches.Down && notices.isUp) runCatching { events?.onPanelPoke() }
        // A finger on the list or a message, on a back dimmed by hand: bright for the usual time, as a tap is.
        if (e is RearTouches.Down && notices.isUp && dimmedByHand) brightFor("a finger on the notifications")
        var back = false
        synchronized(sheetLock) {
            if (from !== gestures) return
            // The screenshot's bin, and the recording's pill: nothing under them sees their finger.
            if (binTouch(e)) return
            if (pillTouch(e)) return
            // A pattern lock's dots take their finger before anything reads it as a pull.
            if (patternTouch(e)) return
            // The control centre over a guest takes its own first: its top pull, and every
            // finger while it is down (ShellControlCenter.touch).
            // The keypad up over them: its fingers are its own, not the panes' waiting under it.
            val actions = if (sheetUp && sheetLocked) from.feed(e)
            else notices.touch(e, controlCenter.touch(e, from.feed(e), ::controlCenterOpens), ::noticesOpens)
            // Xiaomi's chevron is under a private guest's picture, so ours (BackChevron).
            backChevron.touch(e) { from.onBackBand && forwardOpen() }
            for (a in actions) if (sheetAction(a, e.atMs)) back = true
            if (e is RearTouches.Down) {
                // Something may change on the guest: its edge is read again soon.
                strip.poke()
                touchDownX = e.x
                // The top pull's y comes mirrored to the bottom edge (PortalPull.mirrored).
                touchDownY = if (from.onPortalTop) Panel.HEIGHT.toFloat() else e.y
                touchDownAt = e.atMs
            }
        }
        if (back) sendBackOnce()
    }

    private val sceneFingers = SceneTouch()
    /** Until when (uptime) a tap holds the scene at full brightness; 0 for not. */
    @Volatile private var sceneBrightUntil = 0L

    /** A face of ours is what the back is for: companion mode with the scene on. */
    private fun faceWanted() = companion && sceneWanted() && android.os.SystemClock.uptimeMillis() >= uncoverUntil

    /**
     * A DEBUG BUILD'S LOOK AT XIAOMI'S OWN REAR SCREEN, FOR SECONDS ("uncover|seconds" in pop-demo, twenty at most): no
     * face of ours is wanted until then, so the cover, the scene and the pages step away as they do for an app on the
     * back (the guard follows the hint file within its poll). It ends by itself; "cover" ends it at once. For
     * recording Xiaomi's own pin animation as the reference; nothing else may use it: that screen is never to be seen.
     */
    @Volatile private var uncoverUntil = 0L
    private val coverAgain = Runnable {
        if (uncoverUntil == 0L) return@Runnable
        uncoverUntil = 0L
        syncCover("debug: Xiaomi's screen covered again")
    }

    private fun sceneTouchMode(e: RearTouches.Event, from: EdgeGestures): SceneTouch.Mode = when {
        !faceWanted() || !cover.isUp -> SceneTouch.Mode.NONE
        // The picker's finger is the picker's (PickerTouch); none of the readers here makes anything of it.
        pickerHasFinger() -> SceneTouch.Mode.PICKER
        notices.isUp || guest?.onTop == true -> SceneTouch.Mode.NONE
        usage.isUp -> SceneTouch.Mode.USAGE
        arrivalPage.isUp -> SceneTouch.Mode.ARRIVAL
        pinsPage.isUp -> SceneTouch.Mode.NOTES
        // Under music, a call or a timer the charging page is not what shows: their buttons are theirs.
        chargeOnTop() -> SceneTouch.Mode.CHARGE
        else -> SceneTouch.Mode.SCENE
    }

    /** The charging page is what the back shows: up, and none of the pages that lie over it. */
    private fun chargeOnTop() = charge.isUp && nav.let { it.row == RearNav.Row.LIVE && it.live == RearNav.Live.CHARGE && !it.notes }

    // -- the cover: Xiaomi's rear screen is never what the back shows (RearCover) ---------------------
    //
    // Up whenever a face of ours is wanted and the back's focus is Xiaomi's (or nobody's): under the
    // scene and the pages, so whatever of ours is not drawn at some moment, the back is black. Away
    // for Xiaomi's Camera on the back (including its rear preview), our pages, and allowed apps.
    // The focus is heard the moment it changes (the system logs it: [onRearFocusLine]) and read
    // again every tick of the stay loop in case a line was missed.

    private val cover by lazy { RearCover(::rearLayerStack, "shell") { geometry.widthPx to geometry.heightPx } }
    /** The window that has the back's focus, as last heard or read; null for none or not known yet. */
    @Volatile private var rearFocus: String? = null

    /** The cover as the rule has it, now. Any thread. */
    private fun syncCover(why: String) {
        val allow = coverAllow()
        val want = RearCover.wanted(faceWanted(), rearFocus, allow) && guest?.onTop != true
        val was = cover.isUp
        runCatching { cover.set(want, why) }
        // The dim layer lies only over a face of ours.
        if (want != was) applySceneLook()
        // Kept across a restart of this process: the next one covers the back before it knows anything else.
        // The guard reads the same allowlist from it (RearCover.Hint): written again when the apps on the back change,
        // and with what is on top in the guest's task while we step away for the guest (RearCover.hintAllow).
        val hintAllow = RearCover.hintAllow(allow, guest?.onTop == true, rearFocus)
        if (want != was || !coverHintWritten || hintAllow != coverHintAllow) runCatching {
            coverHintWritten = true; coverHintAllow = hintAllow
            java.io.File(COVER_HINT_FILE).writeText(RearCover.Hint(faceWanted(), hintAllow).text())
        }
        if (!want && was) {
            // Something meant to be seen has the back: nothing of our face stays over it.
            if (scene.isUp) scene.aside(why) else scene.disarm()
            if (CameraExit.isCamera(rearFocus)) wakeShow.cut(1L, why)
            navStep(RearNav.Input.OthersTake, why)
        } else if (want && !was) {
            navStep(RearNav.Input.OthersLeave, why)
            // Ours again: the scene at once, not at the stay loop's next tick.
            runCatching { holdHandler.removeCallbacks(stayTick); holdHandler.post(stayTick) }
        }
    }
    @Volatile private var coverHintWritten = false
    @Volatile private var coverHintAllow: Set<String> = emptySet()

    /** The apps he put on the back (the pinned apps, as the app last sent them) and the guest on it now, whichever way it came. */
    private fun coverAllow(): Set<String> = (portalApps.packages + listOfNotNull(guest?.pkg)).toSet()

    /** One of the event log's lines about apps coming to the top (RearCover.changed), as it is logged. */
    private fun onRearFocusLine(line: String) {
        // Xiaomi's launcher resumed: it is only ever on the back, so the cover is up before anything is asked.
        if (RearCover.launcherResumed(line) && rearFocus != RearCover.LAUNCHER) {
            val was = rearFocus
            rearFocus = RearCover.LAUNCHER
            syncCover("Xiaomi's launcher resumed")
            // The Camera's exit is seen here too: the read that follows finds the focus already changed and says nothing
            // (so the camera counted as "up" for half an hour).
            if (CameraExit.left(was, RearCover.LAUNCHER)) cameraLeftBack(RearCover.LAUNCHER)
        }
        // And the truth read, now and once more when the change has settled (one settled read pending at a time: RearTop.Settle).
        runCatching {
            holdHandler.post { runCatching { readRearFocus() } }
            val after = focusSettle.line(android.os.SystemClock.uptimeMillis())
            if (after >= 0L && !holdHandler.postDelayed(focusSettled, after)) focusSettle.dropped()
        }
    }

    /** The reads of the back's top app (by binder, the window manager's dump for whatever is new: RearTop). */
    private val topReader by lazy { RearTop.Reader(::exec, "shell") }
    private val focusSettle = RearTop.Settle(250L)
    private val focusSettled: Runnable = object : Runnable {
        override fun run() {
            runCatching { readRearFocus() }
            val next = focusSettle.ran(android.os.SystemClock.uptimeMillis())
            if (next >= 0L && !holdHandler.postDelayed(this, next)) focusSettle.dropped()
        }
    }

    /** When the back's focus was last read from the window manager (uptime), by any caller: for the stay loop's own read ([FocusReread]). */
    @Volatile private var focusReadAt = 0L
    /** When the log reader that hears the apps change on a display began following (uptime); 0 while none follows. */
    @Volatile private var focusLogSince = 0L

    /** The back's focus read from the window manager: the truth, a moment later than the log's line. [force]: the dump whatever the binder says (RearTop.Reader). */
    private fun readRearFocus(force: Boolean = false) {
        focusReadAt = android.os.SystemClock.uptimeMillis()
        val title = topReader.read(Panel.DISPLAY_ID, rearFocus, force)
        if (title != rearFocus) {
            val was = rearFocus
            rearFocus = title
            syncCover("on top: $title")
            if (CameraExit.left(was, title)) cameraLeftBack(title)
        }
    }

    /**
     * XIAOMI'S CAMERA HAS LEFT THE BACK, whoever closed it and however (its own Back gesture, the front, the band
     * tap): the back is ours again AND LIT, on the moon. The camera we started is
     * forgotten at once (in companion mode nothing ever did that: it counted as "up" for half an hour, which kept
     * the charging page, the notifications and the wake picture off the back). And the back is woken now if
     * something put it to sleep on the way out, not at the stay loop's next patience.
     */
    private fun cameraLeftBack(now: String?) {
        Log.i(TAG, "camera: left the back (on top now: ${now ?: "nothing"}) — the back is ours again, lit")
        cameraLaunched = false
        cameraWasOnTop = false
        stopBandTaps()
        runCatching {
            holdHandler.post {
                stayWokeAt = 0L
                holdHandler.removeCallbacks(stayTick); stayTick.run()
            }
            // And once more when the way out has settled (the Camera's other half leaving the front can take the back down again).
            holdHandler.postDelayed({ stayWokeAt = 0L; holdHandler.removeCallbacks(stayTick); stayTick.run() }, CAMERA_EXIT_SETTLE_MS)
        }
        syncTouchWatch()
    }

    // -- the pages: Claude usage, and the pinned notes (ShellPage, UsageFace, PinFace) ----------------

    /** The accounts' figures as the last payload had them, and the names the user gave the accounts. */
    @Volatile private var usageState = dev.kb896.aiusage.data.DashboardState.EMPTY
    @Volatile private var usageNames: List<String?> = emptyList()
    @Volatile private var usageDemo = false
    /** The switch "Five-hour limits" (off: each account its week alone), and which of OpenAI's Dots stands in ChatGPT's vessel. */
    @Volatile private var usageFiveHours = false
    @Volatile private var usageDot = "blue_beret"
    /** OpenAI's Dots character, drawn by the ChatGPT app's renderer in the app's ":dots" process (setDots). */
    private val usageDots by lazy { dev.kb896.aiusage.rear.dots.DotsClient(musicHandler, { usageDot }) }

    override fun setDots(dots: android.os.IBinder?) {
        Log.i(TAG, "usage: OpenAI's Dots character ${if (dots == null) "not to be had" else "to be had"}")
        usageDots.attach(dots)
    }
    /** The page as last drawn had nobody signed in: its one button is live. */
    @Volatile private var usageEmpty = false

    /** When the usage page last came up (uptime): its fluid rises and its rings sweep from then. */
    @Volatile private var usageShownAt = 0L
    private var usageDemoModel: UsageFace.Model? = null

    private val usage: ShellPage by lazy {
        ShellPage(
            "usage", ShellPage.Edge.RIGHT, ::rearLayerStack, { geometry }, musicHandler, pacer = pagePacer,
            paint = { c, w, h ->
                val now = System.currentTimeMillis()
                val real = UsageFace.model(usageState, now, { a -> usageNames.getOrNull(a.ordinal) }, fiveHours = usageFiveHours)
                // With nobody signed in: made-up figures, marked as such, when the settings' demo mode is on
                // (or, in a debug build, a file named usage-demo is in the app's files folder).
                val shown = if (real.columns.isEmpty() && (usageDemo || BuildConfig.DEBUG && java.io.File(USAGE_DEMO_FILE).exists())) usageDemoModel?.takeIf { d -> d.columns.any { c -> c.split } == usageFiveHours } ?: UsageFace.demo(now, usageFiveHours).also { usageDemoModel = it } else real
                usageEmpty = shown.columns.isEmpty()
                UsageFace.draw(c, w, h, shown, now, android.os.SystemClock.uptimeMillis() - usageShownAt, timerArt[TimerFace.ART_WATER], ::usageAppIcon, usageDots)
            },
            // Looked at: the app is asked for fresh figures (it keeps its own floor between fetches), and the page moves.
            onShown = {
                usageAppIcons.clear()
                usageAppMissing.clear()
                usageShownAt = android.os.SystemClock.uptimeMillis()
                runCatching { events?.onUsageShown() }
                pagePacer.post(usageTick, USAGE_FRAME_MS.toFloat())
            },
            onAlone = { navStep(RearNav.Input.UsageAlone, "left alone") },
            onPlace = { coverMoved() },
        )
    }

    /**
     * The page moves (its fluid, and its mascots, who walk and hop: MascotLive): a new picture every
     * [USAGE_FRAME_MS] on the vsync (every 2nd refresh at 120 Hz: FramePacer), for as long as it is up. How long the pictures take is said
     * in the log every 300 of them, so a slow page is seen in numbers and not guessed at.
     */
    private val usageTick: Runnable = object : Runnable {
        private var count = 0
        private var lastAt = 0L
        private var spent = 0L
        private var worst = 0L
        override fun run() {
            if (!usage.isUp) { lastAt = 0L; return }
            val began = android.os.SystemClock.uptimeMillis()
            // From one picture to the next: the page draws on this same thread, so a slow picture shows here.
            if (lastAt != 0L && began - lastAt < 1_000L) { val gap = began - lastAt; spent += gap; worst = maxOf(worst, gap); count++ }
            lastAt = began
            if (count >= 300) { Log.i(TAG, "usage: $count pictures, ${spent / count} ms from one to the next, $worst ms at most"); count = 0; spent = 0L; worst = 0L }
            usage.changed()
            pagePacer.post(this, USAGE_FRAME_MS.toFloat())
        }
    }

    /** The notes pinned to the back, newest first (PanelConfig.pins), and which one the page shows. */
    @Volatile private var pins: List<dev.kb896.aiusage.data.Pin> = emptyList()
    /**
     * THE PINNED PICTURES (a picture's note: Pin.image), read from the app's files off the drawing threads, a few at a
     * time in memory; as one comes, the pages that may show it draw again (a frame before it shows its place empty).
     */
    private val notePictures by lazy {
        NotePictures(PINS_DIR, ready = { musicHandler.post { pinsPage.changed(); if (arrived.isNotEmpty()) arrivalPage.changed() } })
    }
    /**
     * ONE NOTE TURNING TO THE NEXT on the notes page (RearNav's NOTE_OLDER / NOTE_NEWER, and the give at an end): the
     * note that was showing goes out to one side as the other comes in from the other, under the finger while it is
     * down ([peek]) and by the pages' own slide after ([go], [back]). On the pages' thread; the page's painter reads it.
     */
    private inner class NoteTurn(private val page: () -> ShellPage = { pinsPage }) {
        @Volatile var from = -1; @Volatile var to = -1
        /** +1: the older note comes in from the right; -1: the newer from the left. */
        @Volatile var dir = 0
        /** 0 the old note in place, 1 the new one in place (before easing). */
        @Volatile var at = 0f
        private var goal = 0f
        private var movedAt = 0L
        val active get() = from >= 0

        fun peek(fromNote: Int, toNote: Int, way: Int, part: Float) = musicHandler.post {
            pagePacer.cancel(stepper)
            from = fromNote; to = toNote; dir = way; movedAt = 0L
            at = PageDrag.unease(part)
            page().changed()
        }

        /** On from where it is to the new note (or, with none that way, a little way and back). */
        fun go(fromNote: Int, toNote: Int, way: Int) = musicHandler.post {
            if (from != fromNote || to != toNote || dir != way) { from = fromNote; to = toNote; dir = way; at = 0f }
            goal = if (toNote >= 0) 1f else if (at < NoteTurnArt.GIVE_PEAK) NoteTurnArt.GIVE_PEAK else 0f
            movedAt = 0L
            step()
        }

        fun back() = musicHandler.post { if (active) { goal = 0f; movedAt = 0L; step() } }

        fun end() = musicHandler.post { pagePacer.cancel(stepper); from = -1; to = -1; at = 0f }

        /** On the vsync, placed by that frame's own time (FramePacer): every refresh turns it alike. */
        private val stepper = Runnable { step(pagePacer.frameNs) }

        private fun step(now: Long = System.nanoTime()) {
            pagePacer.cancel(stepper)
            if (!active) return
            if (movedAt == 0L) movedAt = now
            val d = FramePace.share(movedAt, now, ShellPage.SLIDE_MS)
            movedAt = maxOf(movedAt, now)
            at = if (goal > at) minOf(goal, at + d) else maxOf(goal, at - d)
            if (at == goal) {
                // With none that way it has given as far as it does: now back.
                if (to < 0 && goal > 0f) goal = 0f else { from = -1; to = -1; at = 0f; page().changed(); return }
            }
            page().changed()
            pagePacer.post(stepper, 0f)
        }
    }
    private val noteTurn = NoteTurn()
    /** The newest note's time as last seen; -1 before the first payload (so that one is not news). */
    private var pinSeen = -1L

    private val pinsPage: ShellPage by lazy {
        ShellPage(
            // On the pages' own thread, as usage is: holdHandler waits on a shell command every two seconds (the stay loop's look at the back's focus), and its slide stood still meanwhile.
            "notes", ShellPage.Edge.BOTTOM, ::rearLayerStack, { geometry }, musicHandler, pacer = pagePacer,
            paint = { c, w, h ->
                val list = pins
                // Being put in order (or on its way into it and back): the carousel (NoteCarousel), every frame.
                if (carousel.active) { arrangeArt.draw(c, w, h, carousel, list, PageCorners.radius(geometry, w)); return@ShellPage }
                fun note(at: Int) {
                    val i = at.coerceIn(0, (list.size - 1).coerceAtLeast(0))
                    val note = list.getOrNull(i)
                    PinFace.note(c, w, h, note, i, list.size)
                }
                val turn = noteTurn
                val from = turn.from; val to = turn.to
                if (from < 0) {
                    val i = nav.pin
                    note(i)
                    // The neighbours' pictures read ahead (after this one's), so a turn has its picture from its first frame.
                    notePictures.warm(listOf(list.getOrNull(i - 1)?.image, list.getOrNull(i + 1)?.image))
                } else {
                    // One note going out as the other comes in beside it; with none that way, this one a little aside.
                    NoteTurnArt.draw(c, w, h, PageCorners.radius(geometry, w), from, to, turn.dir, turn.at, ::note)
                }
            },
            onAlone = { navStep(RearNav.Input.NotesAlone, "left alone") },
            onPlace = { coverMoved() },
        )
    }

    // ---- PIN TO LOCATION: A NOTE WHOSE PLACE HE REACHED STANDS ON THE BACK (dev.kb896.aiusage.place, SPEC section 5) ----
    //
    // A STATE IN THE PAYLOAD, not news: whenever the notes carry an arrivedAt the page stands (RearNav's arrivals), also
    // after a restart of this process (then without its slide), until he puts it away: the bin (done), a stroke down
    // (not now), told to the app and told again with every payload that still carries it (ArrivalPuts).

    /** The notes standing as arrivals, newest arrival first, less what he has put away. */
    @Volatile private var arrived: List<dev.kb896.aiusage.data.Pin> = emptyList()
    private val arrivalPuts = ArrivalPuts()
    private val arrivalTurn = NoteTurn { arrivalPage }
    /** The arrivals whose page has come in once (by arrivedAt); null before the first payload, whose ones are not news. */
    private var arrivalsPlayed: MutableSet<Long>? = null
    /** A debug build's made-up arrivals ("arrive|…"), his own aside meanwhile. */
    @Volatile private var demoArrivalsUp = false

    private val arrivalPage: ShellPage by lazy {
        ShellPage(
            "arrival", ShellPage.Edge.BOTTOM, ::rearLayerStack, { geometry }, musicHandler, pacer = pagePacer,
            paint = { c, w, h ->
                val list = arrived
                fun note(at: Int) {
                    val i = at.coerceIn(0, (list.size - 1).coerceAtLeast(0))
                    val n = list.getOrNull(i)
                    // It may stand for hours on an OLED: after 10 minutes it moves by 2 px now and then.
                    val (dx, dy) = PinFace.drift(System.currentTimeMillis() - (n?.arrivedAt ?: System.currentTimeMillis()))
                    c.save(); c.translate(dx, dy)
                    PinFace.draw(c, w, h, n?.text.orEmpty(), n?.let { PinFace.stamp(it.arrivedAt, System.currentTimeMillis()) }.orEmpty(), i, list.size, true,
                        place = n?.place, hollow = n?.watched != true, arrival = true, image = n?.image, picture = notePictures.get(n?.image))
                    if (demoArrivalsUp) demoMark(c, h)
                    c.restore()
                }
                val turn = arrivalTurn
                if (turn.from < 0) note(nav.arrival)
                // The same turn as the notes page's, each note off its place with the display's corners (NoteTurnArt).
                else NoteTurnArt.draw(c, w, h, PageCorners.radius(geometry, w), turn.from, turn.to, turn.dir, turn.at, ::note)
            },
            // NO TIMEOUT: it stands until he dismisses it (drawn again every twenty seconds, for its drift).
            stayMs = Long.MAX_VALUE / 4,
            onPlace = { coverMoved() },
        )
    }

    /** The arrivals as a payload has them (or the demo's): the navigation told how many stand, and what he put away told again. */
    private fun onArrivals(next: List<dev.kb896.aiusage.data.Pin>) {
        val first = arrivalsPlayed == null
        val shown = arrivalPuts.visible(next)
        if (first) arrivalsPlayed = shown.map { it.arrivedAt }.toMutableSet()
        arrived = shown
        if (!demoArrivalsUp) for (put in arrivalPuts.untaken(next)) runCatching {
            Log.i(TAG, "arrival: told again that he put one away (${if (put.bin) "the bin" else "not now"}) — the app had not taken it")
            if (put.bin) events?.onPinRemoved(put.at) else events?.onArrivalLater(put.at)
        }
        if (shown.size != nav.arrivals) { arrivalTurn.end(); navStep(RearNav.Input.ArrivalsAre(shown.size), "the notes standing at their place") }
        arrivalPage.changed()
    }

    /** The arrival page up or down by the navigation; it comes in by its slide once per arrival, after that at once. */
    private fun applyArrival(s: RearNav.State, anim: RearNav.Anim, reason: String) {
        val want = s.lit && s.ours && s.arrivalUp
        if (want == arrivalAsked) return
        arrivalAsked = want
        if (!want) { arrivalPage.hide(reason); return }
        val played = arrivalsPlayed ?: mutableSetOf<Long>().also { arrivalsPlayed = it }
        val stamp = arrived.firstOrNull()?.arrivedAt ?: 0L
        val news = stamp != 0L && played.add(stamp)
        if (news) Log.i(TAG, "arrival: a note's place reached — its page comes in")
        arrivalPage.show(atOnce = !news)
    }
    @Volatile private var arrivalAsked = false

    /** He put the arrival showing away by its bin: done, the note goes (the app takes it off its list). */
    private fun arrivalBin() {
        val at = nav.arrival
        val gone = arrived.getOrNull(at) ?: return
        sceneFingers.forget()
        Log.i(TAG, "arrival: its bin — done")
        notePictures.forget(gone.image)
        if (!demoArrivalsUp) arrivalPuts.bin(gone)
        arrived = arrived.filter { it.at != gone.at }
        arrivalTurn.end()
        navStep(RearNav.Input.ArrivalRemoved(at), "its bin")
        arrivalPage.changed()
        if (!demoArrivalsUp) runCatching { events?.onPinRemoved(gone.at) }
    }

    /** A stroke down on the arrival: not now, every note standing waits for his next visit there. */
    private fun arrivalLater(standing: List<dev.kb896.aiusage.data.Pin>) {
        Log.i(TAG, "arrival: swiped down — not now")
        arrivalTurn.end()
        if (demoArrivalsUp) return
        for (p in standing) { arrivalPuts.later(p); runCatching { events?.onArrivalLater(p.at) } }
        arrived = emptyList()
    }

    /**
     * The notes as a payload has them: a note newer than any seen is shown on the back for a moment, as its confirmation.
     *
     * THE NOTES PAGE LISTS A NOTE FOR A PLACE ONLY WHILE THE PHONE IS THERE (dev.kb896.aiusage.place.Here: the app sends
     * each note's Pin.here, and [pins] is Here.onBack of the payload's list). The arrivals are taken from the whole
     * list (a note standing at its place stands also after he has left), and so is what is fresh (a place note coming
     * back onto the page as he reaches it is not a new pin). A fresh note for a place is listed while its pin lands
     * ([landing]), whatever is learnt of its place meanwhile. Every note keeps its own name (Pin.at), so the bin, the
     * paging and the order told to the app act on the notes listed; the app puts a told order on its whole list with
     * the hidden notes in their places (PinOrder.apply). A note for a place coming or going while he reads another
     * leaves him on his ([listPins]).
     */
    private fun onPins(given: List<dev.kb896.aiusage.data.Pin>) {
        // The order he gave them on the back, until the app's list has it (keptOrder).
        var next = keptOrder(given)
        // A debug build's made-up notes are on the page for a look ("pins|…"): his own are kept aside and come back after.
        if (!demoArrivalsUp) onArrivals(next) else ownArrived = next
        if (demoPinsUp) { ownPins = dev.kb896.aiusage.place.Here.onBack(next, landing.at); return }
        // Being put in order: the carousel's order over a payload from before his last move (NoteCarousel.ordered).
        next = carousel.ordered(next)
        val newestNote = next.maxByOrNull { it.freshAt }
        val newest = newestNote?.freshAt ?: 0L
        val fresh = pinSeen >= 0L && newest > pinSeen
        pinSeen = maxOf(newest, 0L)
        // A note just pinned for a place lands on the notes page whatever is known of its place meanwhile (NoteLanding).
        if (fresh && landing.fresh(newestNote, android.os.SystemClock.uptimeMillis())) {
            Log.i(TAG, "notes: a note for a place is landing — on the notes page until its landing is over${if (newestNote?.here == true) "" else " (its place not here)"}")
            musicHandler.removeCallbacks(landingLook); musicHandler.postDelayed(landingLook, NoteLanding.MOST_MS)
        }
        val all = next.size
        next = dev.kb896.aiusage.place.Here.onBack(next, landing.at)
        if (all - next.size != pinsAway) { pinsAway = all - next.size; Log.i(TAG, "notes: $pinsAway note(s) for a place not here kept off the notes page") }
        listPins(next, fresh, "the pinned notes")
        // A picture just pinned: read now, so the landing, the veil and the page have it when they come.
        if (fresh) notePictures.warm(listOf(newestNote?.image))
        if (fresh && (viaAt != 0L || android.os.SystemClock.uptimeMillis() - viaStartedAt < PinVia.TIDY_AT)) {
            // This pin goes through Xiaomi's own (pinVia): its arrival is Xiaomi's, and our notes page comes at its end.
            pinsPage.changed()
        } else if (fresh && faceWanted() && cover.isUp && rearLit(rearDisplay()) == true) {
            // BEAMED IN (PinBeam): the card that has just left the front comes out here and becomes the notes page.
            beamMade = true
            beamWithPicture(pins.firstOrNull()?.image, android.os.SystemClock.uptimeMillis())
        } else pinsPage.changed()
    }

    /**
     * The beam's card is the notes page drawn once as it starts (ShellBeam): a picture's note starts it when its picture
     * is in memory (read off this thread, a few ms), [BEAM_PICTURE_WAIT_MS] at most after [since]; no frame waits meanwhile.
     */
    private fun beamWithPicture(image: String?, since: Long) {
        if (image == null || notePictures.has(image) || android.os.SystemClock.uptimeMillis() - since >= BEAM_PICTURE_WAIT_MS) { pinBeam.play(); return }
        notePictures.warm(listOf(image))
        musicHandler.postDelayed({ beamWithPicture(image, since) }, 8L)
    }

    /** A note just pinned for a place, on the notes page while its pin lands ([onPins]); its end looked at on the pages' thread. */
    private val landing = NoteLanding()
    /** How many notes for a place not here the last payload kept off the notes page (for the log, when it changes). */
    private var pinsAway = 0
    private val landingLook = Runnable { landingOver(nav.notes, "its time is up") }

    /**
     * The notes page [up] or not, now: if that ends the landing (NoteLanding.page), the landing note follows the rule
     * from here: off the notes page unless he has reached its place meanwhile (the app's payload says so by then).
     * Only the landing note goes, so a bin or an order made on the back meanwhile is kept as it is. On the pages' thread.
     */
    private fun landingOver(up: Boolean, why: String) {
        val was = landing.at
        if (!landing.page(up, android.os.SystemClock.uptimeMillis())) return
        musicHandler.removeCallbacks(landingLook)
        if (demoPinsUp) { ownPins = dev.kb896.aiusage.place.Here.onBack(ownPins); return }
        val next = dev.kb896.aiusage.place.Here.onBack(pins)
        Log.i(TAG, "notes: the pin has landed ($why)${if (next.any { it.at == was }) " — its place is here, it stays" else " — its place is not here, off the notes page"}")
        if (next == pins) return
        pinsAway += pins.size - next.size
        listPins(next, fresh = false, why = "a note for a place not here has landed")
        pinsPage.changed()
    }

    /**
     * [next] is what the notes page lists from now: the table told how many there are and where the note he is reading
     * has gone (RearNav.pinsAre: he stays on it, by its own Pin.at). A note for a place comes onto the page or leaves it
     * at its own place in his order, not in front, while he may be reading another. [fresh]: [next] brings a note just
     * pinned, in front.
     */
    private fun listPins(next: List<dev.kb896.aiusage.data.Pin>, fresh: Boolean, why: String) {
        val s = nav
        val shown = if (s.notes) pins.getOrNull(s.pin)?.at else null
        pins = next
        RearNav.pinsAre(s, shown, next.map { it.at }, fresh)?.let { noteTurn.end(); navStep(it, why) }
    }

    private var ownArrived: List<dev.kb896.aiusage.data.Pin> = emptyList()
    private val demoArrivalsEnd = Runnable {
        if (!demoArrivalsUp) return@Runnable
        demoArrivalsUp = false
        arrivalTurn.end()
        arrived = emptyList()
        onArrivals(ownArrived); ownArrived = emptyList()
        Log.i(TAG, "arrival: demo arrivals ended")
    }

    // MADE-UP NOTES FOR A LOOK AT THE NOTES PAGE (a debug build's "pins|one||two||three"; "nopins" or a minute ends it):
    // his own notes are put aside, never shown, changed or removed meanwhile, and are back after.
    @Volatile private var demoPinsUp = false
    private var ownPins: List<dev.kb896.aiusage.data.Pin> = emptyList()
    private val demoPinsEnd = Runnable {
        if (!demoPinsUp) return@Runnable
        arrangeDrop("the made-up notes are going")
        demoPinsUp = false
        noteTurn.end()
        pins = ownPins; ownPins = emptyList()
        navStep(RearNav.Input.PinsAre(pins.size), "the made-up notes are gone")
        pinsPage.changed()
        Log.i(TAG, "notes: the made-up notes are gone, his own are back")
    }


    private val carousel = NoteCarousel()
    private val arrangeArt by lazy { NoteArrangeArt() }
    /** The arrange reader's finger: down, where it landed (panel units), and whether its way is decided. */
    private var arrangeDown = false
    private var arrangeDownX = 0f
    private var arrangeDownY = 0f
    private var arrangeDecided = false
    /** The order last told to the app, held over its payloads until one agrees: it may be frozen (HyperOS) and late. */
    @Volatile private var toldOrder: List<Long>? = null
    @Volatile private var toldOrderAt = 0L

    /** Drawn every frame while it moves or a finger carries a note; once more as it ends, so the page is the page again. */
    private val arrangeTick: Runnable = object : Runnable {
        override fun run() {
            val moving = carousel.step(android.os.SystemClock.uptimeMillis())
            pinsPage.changed()
            if (moving || carousel.carrying) pagePacer.post(this, ARRANGE_FRAME_MS.toFloat())
            else if (!carousel.active) {
                // The note the page comes back to has its picture from the first frame: its card held it (the shell's few in memory may not).
                pins.getOrNull(nav.pin)?.image?.let { n -> notePictures.keep(n, arrangeArt.pictureOf(n)) }
                arrangeArt.clear(); pinsPage.changed()
            }
        }
    }

    private fun arrangeKick() { musicHandler.post { pagePacer.cancel(arrangeTick); arrangeTick.run() } }

    /** The carousel made what the navigation says ([applyNav]): up as arranging begins, back to the page as it ends. */
    private fun arrangeFollow(s: RearNav.State, why: String) {
        if (s.arrange) {
            val list = pins
            val held = list.getOrNull(s.pin) ?: return
            noteTurn.end()
            carousel.enter(list.map { it.at }, held.at)
            haptic("lifted")
            Log.i(TAG, "notes: note ${s.pin + 1} of ${list.size} lifted to be put in order${if (demoPinsUp) " (made-up notes)" else ""}")
        } else {
            // On a dark back at once; when the list changed under it at once too (its cards are not the notes now).
            val untold = if (!s.lit || carousel.ids.toSet() != pins.map { it.at }.toSet()) carousel.reset() else carousel.leave()
            Log.i(TAG, "notes: put in order — done (${why.ifEmpty { "it ended" }})")
            // Ended under a carrying finger (a pop-up, dark, an urgent call…): the order it made is kept, so it is told.
            untold?.let { tellOrder("ended in the middle of a carry, at ${it.indexOf(carousel.held) + 1} of ${it.size}", it) }
        }
        arrangeKick()
    }

    /** Arranging ends at once (the made-up notes come or go under it); a carry of his own notes is told (tellOrder). */
    private fun arrangeDrop(why: String) {
        if (!nav.arrange && !carousel.active) return
        carousel.reset()?.let { tellOrder("ended in the middle of a carry", it) }
        if (nav.arrange) navStep(RearNav.Input.ArrangeDone, why)
        arrangeKick()
    }

    /** Held on the notes: lifted (two or more), or the page gives a little (one). */
    private fun noteHold() {
        val step = navStep(RearNav.Input.NoteHold, "press and hold")
        if (step.does == RearNav.Does.GIVE) {
            pins.getOrNull(nav.pin)?.let { carousel.give(it.at) }
            arrangeKick()
            Log.i(TAG, "notes: held with one note — nothing to put in order, the page gives")
        }
    }

    /** Every touch, before the other readers: while arranging, a finger that goes sideways carries the held note. */
    private fun arrangeFinger(e: RearTouches.Event) {
        val fitX = geometry.fitX
        when (e) {
            is RearTouches.Down -> { arrangeDown = true; arrangeDownX = e.x; arrangeDownY = e.y; arrangeDecided = false }
            is RearTouches.Move -> {
                if (!arrangeDown) return
                if (!arrangeDecided && nav.arrange && carousel.open) {
                    val dx = e.x - arrangeDownX; val dy = e.y - arrangeDownY
                    if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) >= PageDrag.SLOP) {
                        arrangeDecided = true
                        // The same rule as PageDrag's: more across than up or down is sideways. Down is the page's own way out.
                        if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
                            sceneFingers.take()
                            carousel.grab(arrangeDownX * fitX, e.atMs, geometry.widthPx.toFloat())
                        }
                    }
                }
                if (carousel.carrying) { for (slot in carousel.drag(e.x * fitX, e.atMs)) noteCarried(slot); arrangeKick() }
            }
            is RearTouches.Up -> {
                arrangeDown = false
                if (carousel.carrying) {
                    // The places it passed on the way to the lift, then dropped there.
                    for (slot in carousel.drag(e.x * fitX, e.atMs)) noteCarried(slot)
                    if (carousel.drop(e.x * fitX, e.atMs)) tellOrder("dropped at ${carousel.heldAt + 1} of ${carousel.ids.size}")
                    arrangeKick()
                }
            }
        }
    }

    /** The held note has come to [slot], past one note: the order is the carousel's, the table told, one soft tick. */
    private fun noteCarried(slot: Int) {
        pins = dev.kb896.aiusage.data.PinOrder.applyPins(pins, carousel.ids)
        navStep(RearNav.Input.NoteCarried(slot), "carried")
        haptic("passed")
    }

    /** A tap while arranging: on another note the two swap places; on the held one or the ground it ends. */
    private fun arrangeTap(px: Float, py: Float) {
        sceneFingers.forget()
        if (!nav.arrange || !carousel.open) return
        val hit = carousel.hit(px, py, geometry.widthPx.toFloat(), geometry.heightPx.toFloat())
        if (hit == null || hit == carousel.held) { navStep(RearNav.Input.ArrangeDone, if (hit == null) "a tap on the ground" else "a tap on the held note"); return }
        val with = carousel.ids.indexOf(hit)
        if (!carousel.swapWith(hit)) return
        pins = dev.kb896.aiusage.data.PinOrder.applyPins(pins, carousel.ids)
        navStep(RearNav.Input.NoteSwapped(with), "a tap on another note")
        haptic("swapped")
        tellOrder("swapped into ${with + 1} of ${carousel.ids.size}")
        arrangeKick()
    }

    /**
     * The order as it stands told to the app, which keeps it (PanelConfig.pins). [arranged] is the carousel's (a payload
     * may have come since the last note passed): [pins] is put in it first, and the whole list is told. Made-up notes
     * tell nothing.
     */
    private fun tellOrder(why: String, arranged: List<Long> = carousel.ids) {
        if (demoPinsUp) { Log.i(TAG, "notes: the made-up notes put in order ($why) — nothing of his is told"); return }
        pins = dev.kb896.aiusage.data.PinOrder.applyPins(pins, arranged)
        val order = pins.map { it.at }
        toldOrder = order; toldOrderAt = android.os.SystemClock.uptimeMillis()
        Log.i(TAG, "notes: put in order ($why) — told to the app")
        runCatching { events?.onPinsOrdered(order.toLongArray()) }.onFailure { Log.w(TAG, "notes: the order was not told — ${it.message}") }
    }

    /**
     * A payload's notes in the order told last, until the app's own list has it (then that is forgotten): an app frozen
     * by HyperOS may hear it late, and a payload from before it would put the notes back meanwhile. Told again with it.
     */
    private fun keptOrder(given: List<dev.kb896.aiusage.data.Pin>): List<dev.kb896.aiusage.data.Pin> {
        val told = toldOrder ?: return given
        if (android.os.SystemClock.uptimeMillis() - toldOrderAt > ORDER_HOLD_MS) { toldOrder = null; return given }
        val kept = dev.kb896.aiusage.data.PinOrder.applyPins(given, told)
        if (kept == given) { toldOrder = null; Log.i(TAG, "notes: the app keeps the order"); return given }
        runCatching { events?.onPinsOrdered(told.toLongArray()) }
        return kept
    }

    /**
     * ONE LIGHT TICK under his finger (the note lifted, each note it passes, a swap). From the shell, which may vibrate:
     * the app may be frozen. Xiaomi's tick (`prebaked 2`, VibrationEffect.EFFECT_TICK) by the vibrator's own shell
     * command, on a thread of its own so a slow one never holds a frame.
     */
    private val hapticWorker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "notes-haptic").apply { isDaemon = true } } }
    @Volatile private var hapticSaid = false
    private fun haptic(why: String) {
        runCatching {
            hapticWorker.execute {
                val out = exec("cmd vibrator_manager synced prebaked 2")
                if (!hapticSaid || out.isNotEmpty()) { hapticSaid = true; Log.i(TAG, "notes: tick ($why)${if (out.isEmpty()) "" else " — the vibrator says: ${out.take(120)}"}") }
            }
        }
    }

    // ---- A PIN THROUGH XIAOMI'S OWN (PinVia): its real arrival on the back for its second, ending on our notes page ----

    private val veil by lazy { ShellVeil(::rearLayerStack, { geometry }, musicHandler) }
    /** When Xiaomi's pin was started for the pin now under way (uptime); 0 for none. */
    @Volatile private var viaAt = 0L
    @Volatile private var viaText = ""
    /** The pin under way is a picture's (its file's name): Xiaomi was handed the picture, and its card is found by kind. */
    @Volatile private var viaImage: String? = null
    /** When the last pin went to Xiaomi's own, shown on the back or not: its note arriving in our list is not for our own beam. */
    @Volatile private var viaStartedAt = 0L
    @Volatile private var viaXiaomi = false
    private var viaBright = false
    /** A debug build's other way (PinVia.FULL_OURS_AT): Xiaomi's arrival to its end before ours. */
    private var viaFull = false
    private var viaRefused = false

    private val earbudsMoment by lazy { ShellEarbuds(::rearLayerStack, { geometry }, musicHandler) }

    /**
     * EARBUDS HAVE CONNECTED (the app's EarbudsWatch): the back's moment, light only (ShellEarbuds), from the moment the
     * front's event is over. Shown only on a lit back of ours with no call and no pop-up or notifications over it,
     * asked at that moment, not now.
     */
    override fun earbuds(b: android.os.Bundle?) {
        val at = b?.getLong("at") ?: return
        val level = b.getInt("level", -1).takeIf { it in 0..100 }
        val picture = b.getByteArray("picture")?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        Log.i(TAG, "earbuds: connected — the back's moment at +${EarbudsMotion.BACK_AT} ms${if (picture == null) ", no picture of them" else ""}")
        earbudsMoment.play(at, picture, level) { faceWanted() && cover.isUp && rearLit(rearDisplay()) == true && call == null && !notices.isUp }
    }

    override fun pinVia(b: android.os.Bundle?) {
        val at = b?.getLong("at") ?: return
        val text = b.getString("text").orEmpty()
        val image = b.getString("image")?.takeIf { dev.kb896.aiusage.data.PinImage.valid(it) }
        if (image != null) notePictures.warm(listOf(image))
        // Shown only on a lit back of ours with nothing of his over it that must stay (a call): otherwise Xiaomi's
        // arrival plays under our cover unseen, and the note is on our notes page when he looks.
        val show = faceWanted() && cover.isUp && rearLit(rearDisplay()) == true && call == null && !notices.isUp
        Log.i(TAG, "pin: through Xiaomi's own${if (show) "" else " — the back is dark, another's or in a call: not shown there"}")
        viaText = text; viaImage = image; viaXiaomi = false; viaRefused = false; viaBright = false
        viaFull = dev.kb896.aiusage.BuildConfig.DEBUG && b.getBoolean("full")
        viaStartedAt = at
        // After its time, what THIS pin added to Xiaomi's own list is taken out of it again (known by when it was made).
        val wall = System.currentTimeMillis() - (android.os.SystemClock.uptimeMillis() - at)
        popWaiter.execute {
            runCatching { Thread.sleep((at + PinVia.TIDY_AT - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)) }
            val after = PinVia.notes(exec(PinVia.LIST))?.first ?: run { Log.i(TAG, "pin: Xiaomi's own list was not read — nothing taken out of it"); return@execute }
            // A picture's card has no words of ours: it is the one picture card made as this pin was (PinVia.tidyPicture).
            val gone = if (image != null) PinVia.tidyPicture(wall, after) else PinVia.tidy(wall, after, text)
            for (id in gone) exec(PinVia.delete(id))
            // Its kind for a picture is not yet seen on the phone: when none was found, the kinds made in those seconds (no words).
            if (image != null && gone.isEmpty()) Log.i(TAG, "pin: no picture card of this pin's in Xiaomi's list — kinds made in its seconds: ${PinVia.kindsNear(wall, after)}")
            Log.i(TAG, "pin: Xiaomi's own list has ${after.size - gone.size} notes (${gone.size} of this pin's${if (image != null) " (a picture)" else ""} taken out again, by its own function)")
        }
        if (!show) return
        // Xiaomi's pin says each stage of its animation in the main log: that log is followed for this pin's time only.
        followPinLog()
        viaAt = at; viaHanded = at; viaHeard = false; viaChecked = false; viaEnter = 0L; viaGaveUp = false
        // The guard's cover (under ours) steps aside by itself when our hint says "0"; ours stays up until the switch.
        // (Deleting the file no longer does: a missing hint means cover, RearCover.Hint.)
        runCatching { java.io.File(COVER_HINT_FILE).writeText(RearCover.Hint(false, emptySet()).text()) }
        musicHandler.removeCallbacks(viaFrame)
        musicHandler.postAtTime(viaFrame, at + PinVia.CHECK_AT)
    }

    /**
     * XIAOMI'S PIN SAYS EACH STAGE OF ITS FRONT ANIMATION IN THE LOG (PinVia.heard), and its arrival on the back follows
     * its first stage by a fixed time. From our handing the words over to that first stage it varied, and with a fixed
     * time from the hand-over the black lifted on Xiaomi's own face (the fish, its clock) in one run of three. The pin
     * under way is counted from the first stage heard.
     */
    private fun onXiaomiPinLine(what: PinVia.Heard, now: Long) = musicHandler.post {
        val at = viaAt
        if (at == 0L || viaRefused) return@post
        if (what == PinVia.Heard.ENTER) {
            // THE BACK'S ARRIVAL BEGINS: what follows on the back is counted from here. Only this pin's (not one of an
            // hour ago in the log's tail), and not once it was given up waiting for.
            if (viaEnter != 0L || viaGaveUp || now - at !in (PinVia.FRONT_GONE_MS - 400L)..PinVia.ENTER_LATEST) return@post
            viaEnter = now
            Log.i(TAG, "pin: Xiaomi's arrival on the back begins, ${now - at - PinVia.FRONT_GONE_MS} ms after its front was over${if (viaXiaomi) "" else " (ours has not stepped aside yet)"}")
            return@post
        }
        if (viaXiaomi || viaHeard) return@post
        val zero = PinVia.zero(what, now)
        // Only what belongs to this pin (heard within three seconds of handing it over), and never back in time past it.
        if (zero < viaHanded - 200L || zero > viaHanded + 3_000L) return@post
        // The first of its stages heard sets the count; the others say the same.
        viaHeard = true
        Log.i(TAG, "pin: Xiaomi's animation heard (${what.name.lowercase()}): its first stage began ${zero + PinVia.FRONT_FIRST_MS - viaHanded} ms after the words were handed over")
        viaAt = zero
        // The frame that waits for the dip waits for the new time (the look at its window is at its old time, or done).
        if (viaChecked) { musicHandler.removeCallbacks(viaFrame); musicHandler.postAtTime(viaFrame, zero + PinVia.DIP_AT) }
    }
    // XIAOMI'S PIN LINES, HEARD ONLY WHILE A PIN PLAYS: its two tags are in the main log, which the permanent reader
    // (system and event logs only) does not follow any more. One short reader of the main log for those two tags, made
    // through LogWatch (it cannot outlive this process), from the moment a pin is handed over until [PIN_LOG_MS] after
    // the last one: the front's stages come 0.14 to 0.23 s after the hand-over, its arrival on the back about 2.4 s later.

    private val pinLogLock = Any()
    private var pinLog: Process? = null
    @Volatile private var pinLogUntil = 0L

    private fun followPinLog() {
        val now = android.os.SystemClock.uptimeMillis()
        pinLogUntil = now + PIN_LOG_MS
        synchronized(pinLogLock) {
            if (pinLog == null) {
                val proc = try { LogWatch.start("main", PinVia.LOG_TAG + ":W", PinVia.REAR_TAG + ":W") }
                catch (e: Exception) { Log.w(TAG, "pin: Xiaomi's lines not followed: no logcat (${e.message})"); null }
                if (proc != null) {
                    pinLog = proc
                    Thread({
                        try {
                            proc.inputStream.bufferedReader().useLines { lines ->
                                for (line in lines) PinVia.heard(line)?.let { onXiaomiPinLine(it, android.os.SystemClock.uptimeMillis()) }
                            }
                        } catch (_: java.io.IOException) {
                        } catch (e: Throwable) { Log.w(TAG, "pin: Xiaomi's lines: ${e.message}") }
                        LogWatch.stop(proc)
                        synchronized(pinLogLock) { if (pinLog === proc) pinLog = null }
                    }, "pin-log").apply { isDaemon = true; start() }
                }
            }
        }
        runCatching { holdHandler.removeCallbacks(pinLogEnds); holdHandler.postDelayed(pinLogEnds, PIN_LOG_MS) }
    }

    private val pinLogEnds: Runnable = object : Runnable {
        override fun run() {
            val left = pinLogUntil - android.os.SystemClock.uptimeMillis()
            if (left > 0L) { holdHandler.postDelayed(this, left); return }
            val proc = synchronized(pinLogLock) { pinLog.also { pinLog = null } }
            LogWatch.stop(proc)
        }
    }

    /** When Xiaomi said its arrival on the back began (uptime); 0 while it has not. */
    private var viaEnter = 0L
    /** It was not said in its time: ours is back, and saying it now changes nothing. */
    private var viaGaveUp = false
    private var viaHanded = 0L
    private var viaHeard = false
    private var viaChecked = false

    private val viaFrame: Runnable = object : Runnable {
        override fun run() {
            val at = viaAt
            if (at == 0L) return
            val now = android.os.SystemClock.uptimeMillis()
            val t = now - at
            if (!viaXiaomi && !viaRefused && !viaChecked) {
                viaChecked = true
                // HAS XIAOMI'S PIN REALLY BEGUN? Its animation's own window is on the front while it plays. If not (its
                // list is full, it refused, the front is sideways), nothing of Xiaomi's is let through: our own arrival.
                // (Seen on the phone: one new window, "PinAnimation", from its start to its end.)
                if (!viaHeard && !exec("dumpsys window windows | grep -c 'PinAnimation}'").trim().let { (it.toIntOrNull() ?: 0) > 0 }) {
                    viaRefused = true
                    Log.i(TAG, "pin: Xiaomi's own pin is not playing — our own arrival instead")
                } else Log.i(TAG, "pin: Xiaomi's own pin is playing on the front")
                musicHandler.postAtTime(this, viaAt + PinVia.DIP_AT)
                return
            }
            if (viaRefused) {
                viaAt = 0L
                runCatching { syncCover("Xiaomi's pin did not play") }
                if (faceWanted() && cover.isUp && rearLit(rearDisplay()) == true) { beamMade = true; beamWithPicture(pins.firstOrNull()?.image, now) }
                return
            }
            if (viaEnter == 0L && !viaGaveUp && t >= PinVia.ENTER_LATEST) {
                viaGaveUp = true
                Log.i(TAG, "pin: Xiaomi's arrival on the back was not heard — our notes page, from black")
            }
            val enter = if (viaEnter != 0L) viaEnter - at else null
            val plan = PinVia.at(t, enter, viaFull)
            // THE BACK AT ITS TAPPED LEVEL FOR THE ARRIVAL, as after a tap: at the level of a dark room Xiaomi's coloured
            // glass was hardly to be seen, and one level holds from the glass to our page.
            if (!viaBright) { viaBright = true; brightFor("a pin arrives") }
            // The switch under the veil, only while it is whole: ours aside for Xiaomi's arrival, and back again.
            if (plan.xiaomi && !viaXiaomi) {
                viaXiaomi = true
                // (Ours is back by itself well before this; the time is only for a frame loop that died.)
                uncoverUntil = now + VIA_UNCOVER_MOST_MS
                holdHandler.removeCallbacks(coverAgain); holdHandler.postDelayed(coverAgain, VIA_UNCOVER_MOST_MS)
                runCatching { syncCover("a pin through Xiaomi's own: its arrival") }
            } else if (!plan.xiaomi && viaXiaomi) {
                viaXiaomi = false
                holdHandler.removeCallbacks(coverAgain)
                uncoverUntil = 0L
                runCatching { syncCover("the pin has arrived: ours again") }
                noteTurn.end()
                pinLanding = true
                try { navStep(RearNav.Input.NotePinned, "a note was pinned") } finally { pinLanding = false }
                if (nav.notes) pinsPage.show(PIN_CONFIRM_MS, atOnce = true)
                chargeTriedAt = 0L
            }
            veil.put(plan.veil, plan.alpha) { c, w, h ->
                val note = pins.firstOrNull()
                PinFace.note(c, w, h, note, 0, pins.size.coerceAtLeast(1), text = viaText, image = viaImage)
                // This layer lies over the dim layer (seen: the picture stood brighter than the page it gives way to):
                // the back's level is put into the picture, so the real page under it is the same picture.
                c.drawColor(android.graphics.Color.argb((handDim.alpha.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0))
            }
            if (!PinVia.over(t, enter, viaFull)) musicHandler.postDelayed(this, 8L)
            else { viaAt = 0L; veil.hide(); Log.i(TAG, "pin: arrived by Xiaomi's own, our notes page on the back") }
        }
    }

    @Volatile private var beamMade = false
    /** The notes page is being put under a card that has just landed: in its place at once, no slide ([applyNav]). */
    @Volatile private var pinLanding = false

    private val pinBeam: ShellBeam by lazy {
        ShellBeam(
            ::rearLayerStack, { geometry }, musicHandler,
            page = { c, w, h, demoText ->
                if (demoText != null) { PinFace.draw(c, w, h, demoText, "now", 0, 1, false); demoMark(c, h) }
                else {
                    val note = pins.firstOrNull()
                    PinFace.note(c, w, h, note, 0, pins.size)
                }
            },
            onLanded = { demo ->
                if (demo) false else {
                    noteTurn.end()
                    pinLanding = true
                    try { navStep(RearNav.Input.NotePinned, "a note was pinned") } finally { pinLanding = false }
                    // Up already or just come: its time as a confirmation starts now.
                    if (nav.notes) { pinsPage.show(PIN_CONFIRM_MS, atOnce = true); true } else false
                }
            },
        )
    }

    // -- THE NAVIGATION: one state, RearNav's table (home | the live page | usage, the notes, the shade, a pop-up) ----
    //
    // [nav] is which page the back shows; nothing else decides it. Every stroke, every change of what is live, the
    // light, another's app on the back, a page left alone, a pop-up: each is an input to RearNav.next ([navStep]),
    // and [applyNav] then makes the layers what the state says. The live page is IN ITS PLACE under usage, the notes
    // and the shade (it used to be taken away while they were up and brought back 320 ms after they had gone: the
    // moon in between, then the timer "popping up").

    @Volatile private var nav = RearNav.State()
    private val navLock = Any()
    private val rowStrokes = PageRow.Strokes()
    private val liveWatch = LiveWatch()
    /** What was live as last looked at, for the log. */
    private var liveSaid: PageRow.Token? = null
    /** How often playing has started after not playing: the next start is something new (PageRow.music). */
    @Volatile private var musicStarts = 0L
    /** When playing last went to not playing (uptime); 0 before any. */
    @Volatile private var musicStoppedAt = 0L
    /** The charger is in and its page was asked for ([showCharge]); until it comes out. Its level and kind for the page. */
    @Volatile private var chargeWanted = false
    @Volatile private var chargePlugs = 0L
    @Volatile private var chargeWantedLevel = 0
    @Volatile private var chargeWantedFast = false
    private var chargeTriedAt = 0L

    /** One input to the navigation: the state moves by the table, the layers follow, and it is said in the log. */
    private fun navStep(input: RearNav.Input, why: String = ""): RearNav.Step {
        val was: RearNav.State
        val step: RearNav.Step
        synchronized(navLock) { was = nav; step = RearNav.next(was, input); nav = step.state }
        if (step.state != was || step.does != RearNav.Does.NOTHING && step.does != RearNav.Does.TAP && step.does != RearNav.Does.DIM && step.does != RearNav.Does.SHADE_OWN) {
            Log.i(TAG, "nav: ${navName(input)}${if (why.isEmpty()) "" else " ($why)"}: ${navName(was)} -> ${navName(step.state)}${if (step.anim != RearNav.Anim.NONE) ", ${step.anim.name.lowercase()}" else ""}${if (step.does != RearNav.Does.NOTHING) ", ${step.does.name.lowercase()}" else ""}")
        }
        if (step.state != was) applyNav(was, step, why)
        return step
    }

    private fun navName(s: RearNav.State): String {
        val live = s.live?.name?.lowercase()
        val row = when (s.row) { RearNav.Row.HOME -> if (s.away) "moon ($live sent away)" else "moon"; RearNav.Row.LIVE -> "$live page"; RearNav.Row.USAGE -> if (s.liveShown) "usage over $live" else "usage" }
        return buildString {
            if (!s.lit) append("dark ")
            if (!s.ours) append("another's ")
            if (s.pop) append("pop-up over ")
            if (s.picker) append("picker over ")
            if (s.shade) append("notifications over ")
            if (s.arrange) append("arranging (holding note ${s.pin + 1} of ${s.pins}) ")
            if (s.notes) append(if (s.pin > 0) "notes (note ${s.pin + 1} of ${s.pins}) over " else "notes over ")
            append(row)
        }
    }

    private fun navName(i: RearNav.Input): String = when (i) {
        is RearNav.Input.Stroke -> "stroke ${i.way.name.lowercase()}${if (i.from != RearNav.From.MIDDLE) " from the ${i.from.name.lowercase().replace('_', ' ')}" else ""}${if (i.sliding) ", landed while a page was sliding" else ""}"
        is RearNav.Input.LiveComes -> "${i.kind.name.lowercase()} is new${if (i.urgent) " (urgent)" else ""}"
        is RearNav.Input.LiveEnds -> "${i.kind.name.lowercase()} ended"
        is RearNav.Input.LiveUpdates -> "${i.kind.name.lowercase()} goes on"
        is RearNav.Input.PopArrives -> if (i.fixed) "a pop-up (one not to be thrown out)" else "a pop-up"
        else -> i.toString().lowercase()
    }

    /** The layers made what [step]'s state says. What is under a page that leaves is in its place already. */
    private fun applyNav(was: RearNav.State, step: RearNav.Step, why: String) {
        val reason = why.ifEmpty { step.anim.name.lowercase().replace('_', ' ') }
        // The picker taken away by something else than its own end (dark, an app, something live, a note): dropped at
        // once, the old face whole under whatever comes. Its own end (PICKER_OUT) plays its exit itself.
        if (was.picker && !step.state.picker && step.anim != RearNav.Anim.PICKER_OUT) picker.drop(reason)
        // The live page first: when usage or the notes go, it is under them before they move.
        applyLive(step.anim)
        // And what is under the pages as the new state has it: the scene moving again and the live page drawn the moment
        // what covers them is sent away, before it moves (HiddenFrames; the pages' own calls are on their thread, after this).
        syncHidden()
        // The notes lifted into their carousel, or back to the page (NoteCarousel).
        if (was.arrange != step.state.arrange) arrangeFollow(step.state, why)
        // The pages by the state AS IT IS NOW, one caller at a time: steps come from several threads (the touch
        // reader, the timers, the app's calls), and whichever gets here last leaves the layers as the latest state has them.
        synchronized(pagesAsked) {
            val s = nav
            val wantUsage = s.row == RearNav.Row.USAGE
            if (wantUsage != pagesAsked[0]) { pagesAsked[0] = wantUsage; if (wantUsage) usage.show() else usage.hide(reason) }
            if (s.notes != pagesAsked[1]) {
                pagesAsked[1] = s.notes
                if (s.notes) pinsPage.show(if (pinLanding) PIN_CONFIRM_MS else 0L, atOnce = pinLanding) else pinsPage.hide(reason)
                // A note for a place not here that has landed leaves the list as its page goes (NoteLanding).
                if (landing.at != 0L) { val up = s.notes; musicHandler.post { landingOver(up, reason) } }
            }
            applyArrival(s, step.anim, reason)
        }
        // The shade is ShellNotices' own (it tells the navigation when it is up and gone); told to go when the table shuts it.
        if (was.shade && !step.state.shade && notices.isUp) runCatching { notices.closeNow(reason) }
        // A pop-up a stroke took away (the table's POP_OUT before its time).
        if (was.pop && !step.state.pop && step.anim == RearNav.Anim.POP_OUT) runCatching { holdHandler.removeCallbacks(popEnds); notices.popGone() }
        // The moon is what shows and the scene is not on it (the scene does not come back under the charging page
        // (sceneMayReturn); one already up is held under it (HiddenFrames)): at once, not at the stay loop's next look.
        if (RearNav.seen(step.state) == RearNav.Seen.MOON && !scene.isUp) runCatching { holdHandler.removeCallbacks(stayTick); holdHandler.post(stayTick) }
        syncTouchWatch()
    }
    /** What the usage page and the notes were last told: up or not. Under its own lock. */
    private val pagesAsked = BooleanArray(2)
    /** When a live page last came up (uptime): a tap sooner than its slide and a tap's own length after is not for its buttons. */
    @Volatile private var liveUpAt = 0L

    /**
     * The live page's layers by the navigation's state: the one live thing's page up while it is in the row and not
     * sent away (seen, or under usage, the notes, the shade), on a lit back that is ours with the scene on it; every
     * other one down. [anim] says how it comes or goes: by its slide for RearNav's LIVE_IN and LIVE_OUT, else at once.
     */
    private fun applyLive(anim: RearNav.Anim = RearNav.Anim.NONE, redraw: Boolean = false) {
        val s = nav
        // A LIVE PAGE NEEDS NO SCENE UNDER IT (it is opaque): with the charger in the scene does not run under the
        // charging page, and "scene up" kept the timer's, music's and call's pages off the back altogether.
        val room = s.lit && s.ours && faceWanted() && cover.isUp
        // What the navigation has as the live page, seen or under something; and of that, what can be shown now.
        val has = if (s.liveShown) s.live else null
        val show = if (room) has else null
        val slideIn = anim == RearNav.Anim.LIVE_IN
        val slideOut = anim == RearNav.Anim.LIVE_OUT
        fun card(face: ShellCard, kind: RearNav.Live, frame: () -> Unit) {
            if (show == kind) {
                val was = face.isUp
                // Its buttons are not for a finger that was already on its way down as it came (liveUpAt).
                if (!was) liveUpAt = android.os.SystemClock.uptimeMillis()
                face.show(redraw, moving = slideIn && !was)
                if (!was || redraw) frame()
            } else if (face.isUp) face.hide(moving = slideOut)
        }
        if (show == RearNav.Live.NAVIGATION) {
            val route = navigation
            if (!navigationFace.isUp) {
                liveUpAt = android.os.SystemClock.uptimeMillis()
                navigationFace.show(moving = slideIn)
                navigationFrame(draw = false)
            } else if (route != null && route != navigationDrawn) {
                if (liveSeen()) {
                    if (!musicHandler.hasCallbacks(navigationTick)) musicHandler.post(navigationTick)
                } else liveHeld = true
            }
        } else if (navigationFace.isUp) {
            pagePacer.cancel(navigationTick)
            navigationFace.hide(moving = slideOut)
        }
        card(timerFace, RearNav.Live.TIMER) { timerFrame() }
        card(callFace, RearNav.Live.CALL) { callFrame() }
        card(musicFace, RearNav.Live.MUSIC) { musicFrame() }
        if (musicFace.isUp != musicWasUp) { musicWasUp = musicFace.isUp; applySceneLook() }
        // The charging page is ShellCharge's own layer: raised when it is the live page, taken down when it is not.
        // It stays up through a dark back (it draws nothing then), as it did: only the navigation takes it down.
        if (has == RearNav.Live.CHARGE) {
            val now = android.os.SystemClock.uptimeMillis()
            if (room && !charge.isUp && now - chargeTriedAt >= CHARGE_RETRY_MS) { chargeTriedAt = now; raiseCharge(slideIn) }
            // Xiaomi's own screen is being let through (a pin by its own, or the debug look): the charging page is a
            // layer of its own and steps aside for it too; it is raised again when ours is back.
            else if (uncoverUntil != 0L && charge.isUp) charge.swipedAway()
        } else if (charge.isUp && !chargePreviewing) {
            if (chargeWanted || chargeRests) charge.swipedAway() else charge.drain()
        }
    }

    /** What is live now, for the log: a call, navigation, a timer, then playing or recently paused music. */
    private fun liveToken(now: Long = android.os.SystemClock.uptimeMillis()): PageRow.Token? {
        val m = media
        val playing = m != null && (m.playing || now - maxOf(mediaAliveAt, mediaTouchedAt) < MEDIA_LINGER_MS)
        return PageRow.top(
            call?.let { PageRow.call(it.key, it.ringing) },
            // By its app, not by its notification: Xiaomi's Clock has two for one timer (its own and the rear's), and
            // whichever spoke last must not make the timer a "new" one.
            timer?.let { PageRow.timer(it.pkg, it.total, it.over) },
            if (playing) PageRow.music(musicStarts) else null,
            navigation = navigationLive()?.let { PageRow.navigation(it.key) },
        )
    }

    /** One mark per kind of live thing, null for not live; a mark that changes is something new (LiveWatch). */
    private fun liveMarks(now: Long): Array<String?> {
        val m = media
        val playing = m != null && (m.playing || now - maxOf(mediaAliveAt, mediaTouchedAt) < MEDIA_LINGER_MS)
        fun mark(t: PageRow.Token?) = t?.let { "${it.key}#${it.phase}" }
        val marks = arrayOfNulls<String>(RearNav.Live.values().size)
        marks[RearNav.Live.NAVIGATION.ordinal] = mark(navigationLive()?.let { PageRow.navigation(it.key) })
        marks[RearNav.Live.CALL.ordinal] = mark(call?.let { PageRow.call(it.key, it.ringing) })
        marks[RearNav.Live.TIMER.ordinal] = mark(timer?.let { PageRow.timer(it.pkg, it.total, it.over) })
        marks[RearNav.Live.MUSIC.ordinal] = if (playing) mark(PageRow.music(musicStarts)) else null
        marks[RearNav.Live.CHARGE.ordinal] = if (chargeWanted && companion && chargeOn) "charger#$chargePlugs" else null
        return marks
    }

    private val liveLookAgain = Runnable { syncMediaCard() }

    /**
     * A stroke on purpose, on the moon or a page of ours: the navigation's table says what it is ([navStep]); here
     * only what the table asks for besides the pages (the Camera, the next note).
     */
    private fun strokeGo(o: SceneTouch.Out.Swipe) {
        strokeGo(when (o.way) {
            SceneTouch.Way.LEFT -> RearNav.Way.LEFT; SceneTouch.Way.RIGHT -> RearNav.Way.RIGHT
            SceneTouch.Way.UP -> RearNav.Way.UP; SceneTouch.Way.DOWN -> RearNav.Way.DOWN
        }, o.downAt, o.at)
    }

    private fun strokeGo(way: RearNav.Way, downAt: Long, at: Long): RearNav.Step {
        // One stroke, one page: a finger that landed before the last move's page had settled moves nothing (PageRow.Strokes).
        val sliding = !rowStrokes.mayMove(downAt)
        val was = nav
        // On a pop-up: meant for it only if its finger landed once it had been there a moment (NoticeThrow.aimed).
        val aimed = !was.pop || NoticeThrow.aimed(popAt, downAt)
        val step = navStep(RearNav.Input.Stroke(way, RearNav.From.MIDDLE, sliding, aimed))
        if (!aimed && was.pop) Log.i(TAG, "notices: a stroke not meant for the pop-up — its finger was on its way before it came; nothing done")
        else if (sliding && RearNav.next(was, RearNav.Input.Stroke(way)).let { it.state != was || it.does != RearNav.Does.NOTHING }) {
            Log.i(TAG, "row: a stroke ignored — its finger landed before the last page had settled")
        }
        // Whatever this stroke did (a page, or the Camera left of home), the next one waits for it to settle.
        if (step.state != was || step.does == RearNav.Does.OPEN_CAMERA) rowStrokes.moved(at)
        // The pop-up thrown out sideways: on from where the finger left it, the way it went; its notification dismissed
        // or snoozed for the hour (ShellNotices, NoticeThrow). One that may not be thrown: back to its place.
        when (step.anim) {
            RearNav.Anim.POP_THROWN_LEFT, RearNav.Anim.POP_THROWN_RIGHT -> runCatching {
                holdHandler.removeCallbacks(popEnds)
                val kind = if (step.does == RearNav.Does.SNOOZE) NoticeThrow.Kind.SNOOZE else NoticeThrow.Kind.DISMISS
                if (!notices.popThrown(if (step.anim == RearNav.Anim.POP_THROWN_LEFT) -1f else 1f, kind)) notices.popGone()
            }
            RearNav.Anim.POP_GIVES -> { Log.i(TAG, "notices: the pop-up may not be dismissed or snoozed — it springs back"); popBack() }
            else -> Unit
        }
        when (step.does) {
            RearNav.Does.OPEN_CAMERA -> runCatching { popWaiter.execute { Log.i(TAG, "camera: swiped open"); openCamera(rearDisplay(), fromMoon = true) } }
            RearNav.Does.ARRIVAL_LATER -> arrivalLater(arrived)
            else -> Unit
        }
        // Along the notes: the one that showed goes out as the next comes in (from wherever the finger had it).
        when (step.anim) {
            RearNav.Anim.NOTE_OLDER -> { noteTurn.go(was.pin, step.state.pin, +1); Log.i(TAG, "notes: on to an older one (${step.state.pin + 1} of ${step.state.pins})") }
            RearNav.Anim.NOTE_NEWER -> { noteTurn.go(was.pin, step.state.pin, -1); Log.i(TAG, "notes: back to a newer one (${step.state.pin + 1} of ${step.state.pins})") }
            RearNav.Anim.NOTE_END_OLDER -> { noteTurn.go(was.pin, -1, +1); Log.i(TAG, "notes: no older one — the page gives and stays") }
            RearNav.Anim.NOTE_END_NEWER -> { noteTurn.go(was.pin, -1, -1); Log.i(TAG, "notes: no newer one — the page gives and stays") }
            RearNav.Anim.ARRIVAL_OLDER -> arrivalTurn.go(was.arrival, step.state.arrival, +1)
            RearNav.Anim.ARRIVAL_NEWER -> arrivalTurn.go(was.arrival, step.state.arrival, -1)
            RearNav.Anim.ARRIVAL_END_OLDER -> arrivalTurn.go(was.arrival, -1, +1)
            RearNav.Anim.ARRIVAL_END_NEWER -> arrivalTurn.go(was.arrival, -1, -1)
            else -> Unit
        }
        // The old words, kept for whoever reads the log by them.
        when {
            step.state.row == RearNav.Row.USAGE && was.row != RearNav.Row.USAGE -> Log.i(TAG, "usage: swiped in")
            step.state.notes && !was.notes -> Log.i(TAG, "notes: swiped in")
            step.anim == RearNav.Anim.LIVE_IN -> Log.i(TAG, "row: the ${step.state.live?.name?.lowercase()} page brought back")
            step.anim == RearNav.Anim.LIVE_OUT -> Log.i(TAG, "row: the ${was.live?.name?.lowercase()} page sent away")
        }
        return step
    }

    // -- A PAGE FOLLOWS THE FINGER (PageDrag) --------------------------------------------------------------------
    //
    // A stroke that turns a page is not decided in its middle any more: the page it would turn is put where the finger
    // is ([dragAt], the layers' peek), and the table is asked at the lift, as for any stroke, if the finger went far
    // enough or was flung; let go short, the page goes back and nothing has happened. Which page that is, is asked of
    // the table once, as the finger's way is known ([dragTracks]): what the table would do with that stroke NOW.

    private val pageDrag = PageDrag()
    /** [pop]: for the pop-up under the finger, which one it is ([popSerial]); -1 for any other page. */
    private class Dragged(val anim: RearNav.Anim, val coming: Boolean, val extent: Float, val peek: (Float) -> Unit, val unpeek: () -> Unit, val pop: Long = -1L)
    @Volatile private var dragged: Dragged? = null

    private fun navWay(w: PageDrag.Way) = when (w) {
        PageDrag.Way.LEFT -> RearNav.Way.LEFT; PageDrag.Way.RIGHT -> RearNav.Way.RIGHT; PageDrag.Way.UP -> RearNav.Way.UP; PageDrag.Way.DOWN -> RearNav.Way.DOWN
    }

    private fun liveCard(kind: RearNav.Live?): ShellCard? = when (kind) {
        RearNav.Live.NAVIGATION -> navigationFace
        RearNav.Live.TIMER -> timerFace; RearNav.Live.CALL -> callFace; RearNav.Live.MUSIC -> musicFace
        else -> null
    }

    /** The edge a page under a finger going [way] is taken to. */
    private fun edgeOf(way: PageDrag.Way) = when (way) {
        PageDrag.Way.RIGHT -> PageSlide.Edge.RIGHT; PageDrag.Way.LEFT -> PageSlide.Edge.LEFT; PageDrag.Way.UP -> PageSlide.Edge.TOP; PageDrag.Way.DOWN -> PageSlide.Edge.BOTTOM
    }

    /** Whether a finger going [way] from its landing turns a page that can follow it; if so, which, kept for its moves. */
    private fun dragTracks(way: PageDrag.Way, downX: Float, downY: Float, downAt: Long, e: RearTouches.Event, from: EdgeGestures): Boolean {
        dragged = null
        if (sceneTouchMode(e, from) == SceneTouch.Mode.NONE) return false
        val s = nav
        if (!s.lit || !s.ours || s.shade) return false
        // THE POP-UP FOLLOWS A FINGER GOING SIDEWAYS (NoticeThrow): thrown out at the lift (dismissed or snoozed, by the
        // table), or back to its place; one that may not be thrown only gives. Up and down take it away as before.
        if (s.pop) return popTracks(s, way, downAt)
        // Not while the last page still slides, not the notifications' own pull.
        if (!rowStrokes.mayMove(downAt)) return false
        if (way == PageDrag.Way.DOWN && from.topNotices() && PortalPull.startsTopPull(downX, downY, geometry.fitX)) return false
        val step = RearNav.next(s, RearNav.Input.Stroke(navWay(way)))
        val w = dev.kb896.aiusage.Panel.WIDTH.toFloat(); val h = dev.kb896.aiusage.Panel.HEIGHT.toFloat()
        dragged = when (step.anim) {
            RearNav.Anim.USAGE_IN -> Dragged(step.anim, true, w, { usage.peek(it) }, { usage.unpeek() })
            RearNav.Anim.USAGE_OUT -> Dragged(step.anim, false, w, { usage.peek(it) }, { usage.unpeek() })
            RearNav.Anim.NOTES_IN -> Dragged(step.anim, true, h, { pinsPage.peek(it) }, { pinsPage.unpeek() })
            RearNav.Anim.NOTES_OUT -> Dragged(step.anim, false, h, { pinsPage.peek(it) }, { pinsPage.unpeek() })
            RearNav.Anim.NOTE_OLDER -> Dragged(step.anim, true, w, { noteTurn.peek(s.pin, step.state.pin, +1, it) }, { noteTurn.back() })
            RearNav.Anim.NOTE_NEWER -> Dragged(step.anim, true, w, { noteTurn.peek(s.pin, step.state.pin, -1, it) }, { noteTurn.back() })
            RearNav.Anim.NOTE_END_OLDER -> Dragged(step.anim, true, w, { noteTurn.peek(s.pin, -1, +1, it) }, { noteTurn.back() })
            RearNav.Anim.NOTE_END_NEWER -> Dragged(step.anim, true, w, { noteTurn.peek(s.pin, -1, -1, it) }, { noteTurn.back() })
            RearNav.Anim.ARRIVAL_OUT -> Dragged(step.anim, false, h, { arrivalPage.peek(it) }, { arrivalPage.unpeek() })
            RearNav.Anim.ARRIVAL_OLDER -> Dragged(step.anim, true, w, { arrivalTurn.peek(s.arrival, step.state.arrival, +1, it) }, { arrivalTurn.back() })
            RearNav.Anim.ARRIVAL_NEWER -> Dragged(step.anim, true, w, { arrivalTurn.peek(s.arrival, step.state.arrival, -1, it) }, { arrivalTurn.back() })
            RearNav.Anim.ARRIVAL_END_OLDER -> Dragged(step.anim, true, w, { arrivalTurn.peek(s.arrival, -1, +1, it) }, { arrivalTurn.back() })
            RearNav.Anim.ARRIVAL_END_NEWER -> Dragged(step.anim, true, w, { arrivalTurn.peek(s.arrival, -1, -1, it) }, { arrivalTurn.back() })
            RearNav.Anim.LIVE_IN -> liveCard(step.state.live)?.let { c -> Dragged(step.anim, true, w, { c.peek(it) }, { c.unpeek() }) }
            RearNav.Anim.LIVE_OUT -> liveCard(s.live)?.let { c -> Dragged(step.anim, false, w, { c.peek(it) }, { c.unpeek() }) }
                // The charging page (its own layer) follows the finger out too, the way the finger goes: to the right, up or down.
                ?: if (s.live == RearNav.Live.CHARGE && chargeOnTop()) edgeOf(way).let { edge ->
                    Dragged(step.anim, false, if (way == PageDrag.Way.UP || way == PageDrag.Way.DOWN) h else w, { charge.peek(it, edge) }, { charge.unpeek() })
                } else null
            else -> null
        }
        return dragged != null
    }

    /**
     * A finger going [way] on the pop-up: followed if the table throws it out that way, or has it give. Not a finger
     * that was on its way before this pop-up came ([downAt], NoticeThrow.aimed): the other reader has it, and the table
     * does nothing with its stroke.
     */
    private fun popTracks(s: RearNav.State, way: PageDrag.Way, downAt: Long): Boolean {
        if (way != PageDrag.Way.LEFT && way != PageDrag.Way.RIGHT) return false
        val serial = popSerial
        if (!NoticeThrow.aimed(popAt, downAt)) return false
        val step = RearNav.next(s, RearNav.Input.Stroke(navWay(way)))
        val give = step.anim == RearNav.Anim.POP_GIVES
        if (!give && step.anim != RearNav.Anim.POP_THROWN_LEFT && step.anim != RearNav.Anim.POP_THROWN_RIGHT) return false
        val w = dev.kb896.aiusage.Panel.WIDTH.toFloat()
        val dir = if (way == PageDrag.Way.LEFT) -1f else 1f
        // Its time stands still under the finger (the banner's own); the navigation's end of it waits, at most this long.
        runCatching { holdHandler.removeCallbacks(popEnds); holdHandler.postDelayed(popEnds, POP_HELD_MOST_MS) }
        dragged = Dragged(step.anim, false, w, { part ->
            val dx = (1f - part) * w * dir
            notices.popPeek(if (give) NoticeThrow.give(dx) else dx)
        }, { popBack() }, pop = serial)
        return true
    }

    /** The pop-up a drag was following is gone, or another has taken its place: the finger is no longer on it. */
    private fun popLost(d: Dragged) = d.pop >= 0L && NoticeThrow.popLift(d.pop, popSerial, nav.pop, 0f, 0L, 0L) == NoticeThrow.Lift.NOTHING

    private fun dragAt(travel: Float) {
        val d = dragged ?: return
        if (popLost(d)) return
        d.peek(PageDrag.inPart(d.coming, travel, d.extent))
    }

    private fun dragEnd(o: PageDrag.Out.End) {
        val d = dragged ?: return
        dragged = null
        // The pop-up under the finger (NoticeThrow.popLift): gone or replaced, the lift does nothing at all (sent on as a
        // stroke it would turn the page under, usage or the Camera, or throw out one he has not seen); thrown only by a
        // stroke meant as one, 150 units within a swipe's time, not PageDrag's fling of 40.
        val turns = if (d.pop < 0L) o.turns else when (NoticeThrow.popLift(d.pop, popSerial, nav.pop, o.travel, o.downAt, o.at)) {
            NoticeThrow.Lift.NOTHING -> {
                Log.i(TAG, "notices: the pop-up the finger had is gone or another took its place — nothing thrown")
                return
            }
            NoticeThrow.Lift.BACK -> false
            NoticeThrow.Lift.THROW -> true
        }
        if (!turns) {
            Log.i(TAG, "drag: let go short (${o.travel.toInt()} units) — the page goes back")
            d.unpeek()
            return
        }
        // The table decides now, as for any stroke. Should it no longer turn the page that followed (something
        // live came or went under the finger), that page is put back where it belongs.
        val step = strokeGo(navWay(o.way), o.downAt, o.at)
        if (step.anim != d.anim) d.unpeek()
    }

    private fun sceneTouch(e: RearTouches.Event, from: EdgeGestures) {
        if (usage.isUp) usage.touched()
        if (pinsPage.isUp) pinsPage.touched()
        if (arrivalPage.isUp) arrivalPage.touched()
        // Arranging the notes: a finger sideways carries the held one (before the other readers, who then let it go).
        arrangeFinger(e)
        if (e is RearTouches.Down) {
            fingerDownX = e.x; fingerDownY = e.y; pickerDownAt = e.atMs
            // A tap on a pinned picture waiting to open it on the front: another finger has come, so it opens nothing (PictureTap).
            pictureTap.landed()
            // The back's light read as the finger lands, ahead of anything queued: the table decides its stroke by it.
            if (lightPolling) runCatching { holdHandler.removeCallbacks(lightOnce); holdHandler.postAtFrontOfQueue(lightOnce) }
        }
        // THE PICKER READS ITS OWN FINGER (PickerTouch): nothing here makes a tap, a swipe, a hold or a page drag of it.
        // (A pop-up over it has the whole back, as everywhere: a stroke takes the pop-up away and the picker is there again.)
        if (pickerHasFinger()) {
            sceneFingers.feed(e) { SceneTouch.Mode.PICKER }
            if (nav.picker) picker.feed(e)
            return
        }
        faceTouch(e)
        val rear = geometry
        // NO BACK TAB AND NO BACK GESTURE ON OUR PAGES. A page's strokes are the row's own, wherever on the back they
        // start. A finger whose way turns a page is followed (PageDrag), and is not the other reader's from then on.
        for (d in pageDrag.feed(e) { way, x, y, at -> dragTracks(way, x, y, at, e, from) }) when (d) {
            is PageDrag.Out.Begin -> sceneFingers.take()
            is PageDrag.Out.At -> dragAt(d.travel)
            is PageDrag.Out.End -> dragEnd(d)
        }
        for (o in sceneFingers.feed(e) { sceneTouchMode(e, from) }) when (o) {
            // Under a pop-up a tap presses nothing (the page's buttons are not what he sees): the back bright, no more.
            is SceneTouch.Out.Tap -> if (navStep(RearNav.Input.Tap).does == RearNav.Does.BRIGHT) onTap() else when (o.on) {
                SceneTouch.Mode.SCENE -> {
                    val px = o.x * rear.fitX; val py = o.y * rear.fitY
                    val now = android.os.SystemClock.uptimeMillis()
                    val m = timer
                    val ringing = call
                    // A page's button takes its tap, and such a tap is not the first of a double tap; any other
                    // tap is the back bright for a while (and what a double tap decides by: [onDoubleTap]).
                    var button = false
                    // A page that has only just come in: the finger was on its way before he saw the buttons.
                    if (now - liveUpAt < LIVE_TAP_AFTER_MS) Log.i(TAG, "tap: the page had just come, no button")
                    else if (callFace.isUp && ringing != null) {
                        CallFace.side(px, py)?.let { side -> button = true; callPress(side, ringing) }
                    } else if (timerFace.isUp && m != null) {
                        TimerFace.side(px, py, m.over)?.let { side -> button = true; timerPress(side, m) }
                    } else if (musicFace.isUp) {
                        MusicFace.button(px, py, MusicLayout.of(media?.art))?.let { b ->
                            button = true
                            Log.i(TAG, "media: ${b.command}")
                            if (b != MusicFace.Button.PLAY_PAUSE) { musicPressed = b; musicPressedAt = now }
                            runCatching { events?.onMediaCommand(b.command) }
                        }
                        mediaTouchedAt = now
                        musicFrame()
                    }
                    if (button) sceneFingers.forget() else onTap()
                }
                // The usage page with nobody signed in has one button: it opens the app's sign-in on the front
                // screen. The shell starts it (an app in the background may not); the approval is the user's.
                SceneTouch.Mode.USAGE -> if (!(usageEmpty && UsageFace.onSignIn(o.x * rear.fitX, o.y * rear.fitY))) onTap() else runCatching {
                    sceneFingers.forget()
                    popWaiter.execute {
                        Log.i(TAG, "usage: sign-in asked for on the back")
                        val out = exec("am start --display 0 --activity-single-top --activity-clear-top -n ${BuildConfig.APPLICATION_ID}/dev.kb896.aiusage.ui.MainActivity --ez sign_in true")
                        if (out.contains("Error", ignoreCase = true)) Log.w(TAG, "usage: sign-in not opened: ${out.take(160)}")
                    }
                }
                // The bin: the note showing is taken off the back, and the app keeps the list.
                // Arranging: a tap on another note swaps the two, on the held one or the ground ends it; no bin then.
                // A tap on the note's picture: the back bright as for any tap, and the picture opened on the front once no
                // second tap has come (PictureTap).
                SceneTouch.Mode.NOTES -> if (nav.arrange || carousel.active) arrangeTap(o.x * rear.fitX, o.y * rear.fitY) else if (!PinFace.onBin(o.x * rear.fitX, o.y * rear.fitY)) { onTap(); pictureTapped(o) } else nav.pin.let { at -> pins.getOrNull(at)?.let { gone ->
                    sceneFingers.forget()
                    Log.i(TAG, "notes: one removed on the back")
                    pins = pins.filter { it.at != gone.at }
                    // Its picture, if it had one, let go here (the app deletes its file as it takes the note off its list).
                    notePictures.forget(gone.image)
                    // The note he was on is gone: the next older one shows in its place (the last, if it was the oldest).
                    noteTurn.end()
                    navStep(RearNav.Input.PinRemoved(at), "its bin")
                    pinsPage.changed()
                    // A made-up note's bin takes the made-up note: nothing of his is told to go.
                    if (!demoPinsUp) runCatching { events?.onPinRemoved(gone.at) }
                } }
                SceneTouch.Mode.CHARGE -> onTap()
                // A standing arrival: its bin is done; any other tap is the back bright, as everywhere.
                SceneTouch.Mode.ARRIVAL -> if (PinFace.onBin(o.x * rear.fitX, o.y * rear.fitY)) arrivalBin() else { onTap(); pictureTapped(o) }
                else -> Unit
            }
            is SceneTouch.Out.DoubleTap -> onDoubleTap(o)
            // Down from the top edge's pull zone is the notifications' own pull (EdgeGestures, ShellNotices); every other
            // stroke that begins there is a stroke like any (it used to be dropped, with every tap there).
            is SceneTouch.Out.Swipe -> if (o.on != SceneTouch.Mode.NONE && !(o.way == SceneTouch.Way.DOWN && from.topNotices() && PortalPull.startsTopPull(o.fromX, o.fromY, rear.fitX))) strokeGo(o)
            // HELD ON THE FACE: the picker (Xiaomi's, copied). Asked only while the face is what shows, so a finger held
            // on a live page's button is never taken from it.
            SceneTouch.Out.FirstDown -> runCatching {
                val x = fingerDownX; val y = fingerDownY; val at = pickerDownAt
                holdHandler.postDelayed({
                    if (RearNav.seen(nav) == RearNav.Seen.MOON && sceneFingers.heldAlone(android.os.SystemClock.uptimeMillis())) openPicker(x, y, at)
                }, SceneTouch.PICKER_HOLD_MS + 20L)
            }
            // Held on the notes page: lifted to be put in order (on the pages' thread: the hold thread waits on shell commands).
            SceneTouch.Out.NotesDown -> runCatching {
                musicHandler.postDelayed({
                    if (sceneFingers.heldOnNotes(android.os.SystemClock.uptimeMillis())) noteHold()
                }, SceneTouch.NOTE_HOLD_MS + 20L)
            }
            SceneTouch.Out.SecondDown -> runCatching {
                holdHandler.postDelayed({
                    if (sceneFingers.held(android.os.SystemClock.uptimeMillis())) {
                        Log.i(TAG, "scene: double tap and hold — the back off")
                        backOffByHand()
                    }
                }, SceneTouch.HOLD_MS + 20L)
            }
        }
    }

    // -- A TAP ON A PINNED PICTURE OPENS IT ON THE FRONT (PictureTap; ui/PinViewActivity) -------------------------------

    private val pictureTap = PictureTap()

    /** What [on] (the notes page or an arrival's) shows now, for [pictureTap]. */
    private fun pictureUnder(on: SceneTouch.Mode): PictureTap.Under {
        val notes = on == SceneTouch.Mode.NOTES
        val note = if (notes) pins.getOrNull(nav.pin) else arrived.getOrNull(nav.arrival)
        return PictureTap.Under(
            note = note,
            place = note?.image?.let { PinFace.pictureAt(note.text, it) },
            up = if (notes) pinsPage.isUp else arrivalPage.isUp,
            moving = if (notes) noteTurn.active || carousel.active || nav.arrange else arrivalTurn.active,
            lit = nav.lit,
            inCall = call != null,
            madeUp = if (notes) demoPinsUp else demoArrivalsUp,
        )
    }

    /**
     * A tap (no bin's) on the notes page or an arrival: on the note's picture, the open waits out the double tap's window
     * (on the pages' thread, as the notes' hold: the hold thread waits on shell commands, and the open would wait with it).
     */
    private fun pictureTapped(o: SceneTouch.Out.Tap) {
        val rear = geometry
        if (pictureTap.tapped(o.x * rear.fitX, o.y * rear.fitY, o.on, pictureUnder(o.on), android.os.SystemClock.uptimeMillis()))
            runCatching { musicHandler.removeCallbacks(pictureDue); musicHandler.postDelayed(pictureDue, PictureTap.WAIT_MS) }
    }

    /** The double tap's window is over: the picture opened on the front by the shell (an app frozen by HyperOS cannot). */
    private val pictureDue = Runnable {
        val p = pictureTap.due(android.os.SystemClock.uptimeMillis(), ::pictureUnder) ?: return@Runnable
        Log.i(TAG, "notes: a pinned picture tapped on ${if (p.on == SceneTouch.Mode.ARRIVAL) "an arrival" else "the notes page"} — opened on the front")
        Thread({
            val out = exec(PictureTap.command(BuildConfig.APPLICATION_ID, p.at))
            if (out.contains("Error", ignoreCase = true) || out.contains("Exception")) Log.w(TAG, "notes: the picture was not opened on the front: ${out.take(160)}")
        }, "picture-open").apply { isDaemon = true; start() }
    }

    /**
     * THE BACK OFF BY HAND (a double tap and hold, or three fingers held when that is their choice): the rear group
     * asleep, and the dark edge right after it sets the latch (ManualOff.byDoubleTap, by [lastDoubleTapAt]), so nothing
     * of ours lights it again until he wakes it himself. On [holdHandler] (it waits on a shell command).
     */
    private fun backOffByHand() {
        lastDoubleTapAt = android.os.SystemClock.elapsedRealtime()
        execChecked("cmd power sleep --group-id ${rearPowerGroup(rearDisplay())}")
    }


    /** The touch watch's ThreeHold (one per getevent), for [threeCheck]. */
    @Volatile private var threeHold: ThreeHold? = null
    /** The choice, from the settings (styleFrame). */
    @Volatile private var threeFingers = dev.kb896.aiusage.data.ThreeFingers.SCREENSHOT
    /** A hold under way is complete at the time it said (ThreeHold.Out.Arm): asked then, as getevent is quiet while fingers lie still. */
    private val threeCheck = Runnable { threeHold?.due(android.os.SystemClock.uptimeMillis())?.let { onThreeFingers(it) } }

    /**
     * Another finger is down with the first: what the first was becoming goes. The scene's reader makes nothing more of it
     * and forgets a tap before it; a page following it goes back (a pop-up too, never thrown); a Xiaomi face is told
     * its touch is cancelled; the picker's finger means nothing more. On the reader thread, before the frame's moves.
     */
    private fun fingersCrowded() {
        sceneFingers.crowded()
        pictureTap.landed()
        if (pageDrag.crowded()) dragged?.let { d ->
            dragged = null
            if (!popLost(d)) d.unpeek()
        }
        if (faceFingerDown) { faceFingerDown = false; runCatching { scene.face?.touch(android.view.MotionEvent.ACTION_CANCEL, 0f, 0f) } }
        if (pickerHasFinger()) runCatching { picker.crowded() }
        // The screenshot's polaroid and the recording's pill keep the finger they own (nothing under them sees it), but
        // its lift neither bins, flicks nor stops anything; a card it dragged goes back.
        synchronized(sheetLock) {
            if (binOwns && !binCrowded) {
                binCrowded = true
                if (shotDragging) runCatching { shotPolaroid.release(0f) }
            }
            if (pillOwns) pillCrowded = true
        }
        Log.i(TAG, "fingers: more than one down — no tap, stroke, page drag or hold of the face and pages, and nothing of the polaroid or the pill, comes of this touch")
    }

    /**
     * THREE FINGERS HELD (ThreeHold has fired): what the settings say, only on a lit back that is ours. One log line.
     * The screenshot is taken before anything of ours is drawn for it (the polaroid comes after, from the file); the
     * control centre is closed first only if it is up. Any thread.
     */
    private fun onThreeFingers(f: ThreeHold.Out.Fire) {
        val s = nav
        val ours = faceWanted() && cover.isUp && s.lit && s.ours
        val did = ThreeFingersAct.run(threeFingers, ours, object : ThreeFingersAct.Does {
            override fun screenshot() { runCatching { captureWorker.execute { this@RearUserService.screenshot("three fingers", clearFirst = controlCenter.isUp) } } }
            override fun recording() { runCatching { captureWorker.execute { toggleRecording("three fingers") } } }
            override fun backOff() { runCatching { holdHandler.post { backOffByHand() } } }
        })
        // The widest finger's size goes in too: the panel reports no palms, and a size limit is to be measured from these.
        Log.i(TAG, "three fingers: ${f.fingers} held ${f.heldMs} ms, landed within ${f.spreadMs} ms at (${f.x.toInt()}, ${f.y.toInt()}), widest ${f.widest} — $did")
    }


    /** Where the finger that is down landed (panel units, the reader's clock), for the picker it may open. */
    @Volatile private var fingerDownX = 0f
    @Volatile private var fingerDownY = 0f
    @Volatile private var pickerDownAt = 0L

    private val mamlHost by lazy { MamlHost(context) }
    private val faceScan by lazy { FaceScan(indexFile = java.io.File("${MamlHost.DIR}/index.json")) }
    private val facesThread by lazy {
        android.os.Handler(android.os.HandlerThread("rear-faces", android.os.Process.THREAD_PRIORITY_LOWEST).apply { isDaemon = true; start() }.looper)
    }
    private val faceSchedule = FaceScan.Schedule()
    private val faceWatches = ArrayList<android.os.FileObserver>()
    @Volatile private var facesStarted = false

    /** The faces, as the last scan found them; ours alone (the moon, then the dust) until then. */
    @Volatile private var faces: List<FaceCatalog.Face> = listOf(
        FaceCatalog.Face(FaceCatalog.MOON, FaceCatalog.Source.OURS, mapOf("en_US" to "Moon"), null, null, null, 0L, null, null, false, false, null),
        FaceCatalog.dust(),
    )
    /** The chosen face (PanelConfig.face) and which choice it is (faceSeq): the shell's copy, kept in [FACE_FILE]. */
    @Volatile private var faceKey: String = FaceCatalog.MOON
    @Volatile private var faceSeq = 0L
    /** By sha1: faces that failed in this process (FaceCatalog rule 8), with why. */
    private val faceFailed = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** By sha1: how like Xiaomi's picture of it each face's first frame was (rule 9), kept in [FAITHFUL_FILE]. */
    private val faceFaithful = java.util.concurrent.ConcurrentHashMap<String, Double>()
    /** The face a native crash took down last time (MamlHost.CRASHING), until the scan knows its sha1. */
    @Volatile private var faceCrashed: String? = null
    private var facesSaidAt = ""

    /** The chosen face if it is a Xiaomi one that can be shown now; null for ours (the moon, the dust). */
    private fun xiaomiFaceKey(): String? = faceKey.takeIf { k -> !FaceCatalog.ours(k) && faces.any { it.key == k && it.offered } }

    /** The face the scene shows for the choice: the Xiaomi face's key, the dust, else the moon. */
    private fun shownFaceKey(): String = xiaomiFaceKey() ?: if (faceKey == FaceCatalog.DUST) FaceCatalog.DUST else FaceCatalog.MOON

    /**
     * The scene's loop: the Xiaomi face's key, the dust's key (drawn by the scene's GL, DustField), else the moon's clip.
     * And its wake clip: none for a Xiaomi face or the dust.
     */
    private fun faceLoopPath(): String? = xiaomiFaceKey() ?: if (faceKey == FaceCatalog.DUST) FaceCatalog.DUST else clipPath(sceneLoop)
    private fun faceWakePath(): String? = if (shownFaceKey() != FaceCatalog.MOON) null else clipPath(sceneWake)
    private fun faceTitle(key: String): String = faces.firstOrNull { it.key == key }?.title ?: FaceCatalog.title(emptyMap(), key)

    private val faceSource = object : ShellScene.FaceSource {
        override fun open(key: String): MamlHost.Face? {
            val f = faces.firstOrNull { it.key == key && it.offered } ?: run { Log.i(TAG, "face: $key is not offered — the moon"); return null }
            val content = f.content ?: return null
            val rear = geometry
            val night = (context?.resources?.configuration?.uiMode ?: 0) and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            return runCatching { mamlHost.open(key, content, f.editConfig, rear.widthPx, rear.heightPx, night, sceneLook.level) }
                .onFailure { failed(key, it.message ?: "it did not load") }.getOrNull()
        }
        override fun failed(key: String, why: String) {
            val f = faces.firstOrNull { it.key == key }
            Log.w(TAG, "face: ${f?.title ?: key} failed — $why; left out until the shell starts again")
            f?.sha1?.let { faceFailed[it.lowercase()] = why }
            // The moon while it is out: the next scene request finds it not offered.
            scanFaces("a face failed", sha1 = false) {
                if (faceKey == key) runCatching { popWaiter.execute { if (scene.isUp && scene.showing() == key) scene.play(null, faceLoopPath(), android.os.SystemClock.uptimeMillis(), waitForLight = false) } }
            }
        }
        override fun shown(face: MamlHost.Face?) { Unit }
    }

    /** The first payload: the faces are looked at, watched and polled from now on. */
    private fun facesStart() {
        if (facesStarted) return
        facesStarted = true
        runCatching { java.io.File(MamlHost.DIR).mkdirs() }
        faceCrashed = MamlHost.crashedLastTime()?.also { Log.w(TAG, "face: $it took the last shell process down — the moon, and that face left out") }
        // The shell's own copy of the choice: right before the app speaks (HyperOS may have the app frozen).
        runCatching {
            java.io.File(FACE_FILE).takeIf { it.isFile }?.readText()?.trim()?.split(' ')?.let { p ->
                val k = p.getOrNull(0); val n = p.getOrNull(1)?.toLongOrNull() ?: 0L
                if (k != null && (FaceCatalog.ours(k) || k.startsWith("xiaomi:"))) { faceKey = k; faceSeq = n }
            }
        }
        runCatching {
            java.io.File(FAITHFUL_FILE).takeIf { it.isFile }?.readLines()?.forEach { l ->
                val (sha, score) = l.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1)?.toDoubleOrNull()) }
                if (sha.isNotEmpty() && score != null) faceFaithful[sha] = score
            }
        }
        // Watches on what changes with his list and his downloads (FUSE: not proven to see the theme manager's own
        // writes, so the poll below is the proven way in).
        for (dir in FaceScan.Paths().watched) runCatching {
            val o = object : android.os.FileObserver(java.io.File(dir), CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    val now = android.os.SystemClock.uptimeMillis()
                    faceSchedule.watched(now)
                    facesThread.removeCallbacks(faceDebounce)
                    facesThread.postDelayed(faceDebounce, FaceScan.Schedule.DEBOUNCE_MS + 50L)
                    if (BuildConfig.DEBUG) Log.i(TAG, "faces: the watch on $dir saw $path change")
                }
            }
            o.startWatching(); faceWatches += o
        }.onFailure { Log.w(TAG, "faces: no watch on $dir — ${it.message}") }
        scanFaces("the shell started", sha1 = true)
    }

    private val faceDebounce = Runnable {
        if (faceSchedule.debounced(android.os.SystemClock.uptimeMillis())) scanFaces("a watched folder changed (inotify)", sha1 = true)
    }

    /** The back lit: a scan, and the poll of the download folders every five minutes while it stays lit. */
    private fun facesLit() {
        if (!facesStarted) return
        scanFaces("the back lit", sha1 = true)
        facesThread.removeCallbacks(facePoll)
        facesThread.postDelayed(facePoll, FaceScan.Schedule.POLL_MS)
        facesThread.post { faceSchedule.polled(android.os.SystemClock.uptimeMillis(), FaceScan.Paths().polled.map { java.io.File(it).lastModified() }) }
    }

    private val facePoll: Runnable = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            // Dark: nothing of ours ticks (the night rule). The next light-up scans and starts this again.
            if (faceSchedule.nextPoll(now, rearLit(rearDisplay()) == true) == null) return
            if (faceSchedule.polled(now, FaceScan.Paths().polled.map { java.io.File(it).lastModified() })) scanFaces("the download folder changed (the 5-minute look)", sha1 = true)
            facesThread.postDelayed(this, FaceScan.Schedule.POLL_MS)
        }
    }

    /** A scan on the faces' thread; [then] after it, on that thread. */
    private fun scanFaces(why: String, sha1: Boolean, then: (() -> Unit)? = null) {
        runCatching {
            facesThread.post {
                val list = runCatching {
                    synchronized(faceScan) {
                        faceScan.scan(mamlHost.abilities, faceFailed.toMap(), faceFaithful.toMap(), moonCard(), sha1, dustCard = dustCard())
                    }
                }.onFailure { Log.w(TAG, "faces: the scan failed — ${it.message}") }.getOrNull() ?: return@post
                // The face a crash took down: out for this process.
                faceCrashed?.let { k -> list.firstOrNull { it.key == k }?.sha1?.let { faceFailed[it.lowercase()] = FaceCatalog.CRASHED; faceCrashed = null; scanFaces("a face crashed the last process", false); return@post } }
                facesFound(list, why)
                then?.invoke()
                if (sha1) measureFaces(list)
            }
        }
    }

    /** The list as found: said when it changed, written for the app, and the chosen face checked. On the faces' thread. */
    private fun facesFound(list: List<FaceCatalog.Face>, why: String) {
        val was = faces
        faces = list
        val said = list.joinToString("|") { "${it.key}:${it.leftOut}" }
        if (said != facesSaidAt) {
            facesSaidAt = said
            for (f in list) if (!f.offered && !f.pending) Log.i(TAG, "faces: left out ${f.title} (${f.id8}): ${f.leftOut}")
            Log.i(TAG, "faces: ${list.count { it.offered }} offered, ${list.count { !it.offered }} left out ($why)")
            writeFacesForApp(list)
            runCatching { events?.onFacesChanged() }
        }
        // His choice against the list (FaceCatalog.afterScan): gone from the phone or a crash takes it back to the moon;
        // out for now (failed in this process) shows the moon and keeps his choice for the next shell process.
        val chosen = faceKey
        val title = list.firstOrNull { it.key == chosen }?.title ?: was.firstOrNull { it.key == chosen }?.title ?: chosen
        when (FaceCatalog.afterScan(chosen, list, was)) {
            FaceCatalog.AfterScan.BACK_TO_MOON -> {
                Log.i(TAG, "face: $title is gone from the phone (or took the shell down), back to the moon")
                chooseFace(FaceCatalog.MOON, "gone")
            }
            // Offered again (or for the first time since this process began): the back shows it now.
            FaceCatalog.AfterScan.SHOW_IT -> if (scene.isUp && scene.showing() != chosen)
                runCatching { popWaiter.execute { scene.play(null, faceLoopPath(), android.os.SystemClock.uptimeMillis(), waitForLight = false) } }
            FaceCatalog.AfterScan.SHOW_MOON -> if (scene.isUp && scene.showing() == chosen) {
                Log.i(TAG, "face: $title is out for now — the moon shows, the choice is kept")
                runCatching { popWaiter.execute { if (scene.showing() == chosen) scene.play(null, faceLoopPath(), android.os.SystemClock.uptimeMillis(), waitForLight = false) } }
            }
            FaceCatalog.AfterScan.KEEP -> Unit
        }
    }

    /**
     * The list for the app (it cannot read the downloads): faces/faces.json (keys, titles, group, source, offered or why
     * not) and each card picture as faces/<key>.jpg, Xiaomi's own picture made 488 x 298. Only what changed is written.
     */
    private fun writeFacesForApp(list: List<FaceCatalog.Face>) = runCatching {
        val dir = java.io.File(FACES_APP_DIR).apply { mkdirs() }
        val arr = kotlinx.serialization.json.buildJsonArray {
            for (f in list) add(kotlinx.serialization.json.buildJsonObject {
                put("key", kotlinx.serialization.json.JsonPrimitive(f.key))
                put("title", kotlinx.serialization.json.JsonPrimitive(f.title))
                put("group", kotlinx.serialization.json.JsonPrimitive(f.group ?: ""))
                put("source", kotlinx.serialization.json.JsonPrimitive(f.source.name.lowercase()))
                put("offered", kotlinx.serialization.json.JsonPrimitive(f.offered))
                put("pending", kotlinx.serialization.json.JsonPrimitive(f.pending))
                put("reason", kotlinx.serialization.json.JsonPrimitive(f.leftOut ?: ""))
            })
        }
        java.io.File(dir, "faces.json.tmp").apply { writeText(arr.toString()); renameTo(java.io.File(dir, "faces.json")) }
        for (f in list) {
            val src = f.card?.let { java.io.File(it) }?.takeIf { it.isFile } ?: continue
            val out = java.io.File(dir, "${f.key.replace(':', '_')}.jpg")
            if (out.isFile && out.lastModified() >= src.lastModified() && out.length() > 0) continue
            val bm = android.graphics.BitmapFactory.decodeFile(src.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) ?: continue
            val scaled = if (bm.width == 488 && bm.height == 298) bm else android.graphics.Bitmap.createScaledBitmap(bm, 488, 298, true)
            java.io.FileOutputStream(out).use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
            if (scaled !== bm) scaled.recycle(); bm.recycle()
        }
    }.onFailure { Log.w(TAG, "faces: the list was not written for the app — ${it.message}") }

    /**
     * OUR OWN CARD: a frame of our own clip at its start (MediaMetadataRetriever), made once and kept. Our footage, not a
     * drawing. On the faces' thread.
     */
    private fun moonCard(): String? {
        val out = java.io.File("${MamlHost.DIR}/moon.png")
        val clip = clipPath(sceneLoop)?.let { java.io.File(it) }?.takeIf { it.isFile } ?: return out.takeIf { it.isFile }?.path
        if (out.isFile && out.lastModified() >= clip.lastModified()) return out.path
        return runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(clip.path)
                val bm = r.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@runCatching null
                // A loop with depth is the picture over its matte: the top half is the picture.
                val pic = if (bm.height >= bm.width) android.graphics.Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height / 2) else bm
                java.io.FileOutputStream(out).use { pic.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                out.path
            } finally { r.release() }
        }.onFailure { Log.w(TAG, "faces: no picture of the moon — ${it.message}") }.getOrNull()
    }

    /**
     * THE DUST'S CARD: the face's first picture drawn by its own formulas (DustField.render, the twin of its shaders),
     * 976 x 596, made once per look (DustField.CARD_VERSION) and kept. The cards of earlier looks are deleted once the
     * new one is written (nothing reads them). On the faces' thread.
     */
    private fun dustCard(): String? {
        val out = java.io.File("${MamlHost.DIR}/dust-${DustField.CARD_VERSION}.png")
        if (out.isFile && out.length() > 0) return out.path
        return runCatching {
            val bm = android.graphics.Bitmap.createBitmap(DustField.card(), DustField.W.toInt(), DustField.H.toInt(), android.graphics.Bitmap.Config.ARGB_8888)
            java.io.FileOutputStream(out).use { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bm.recycle()
            out.parentFile?.listFiles { f -> f.isFile && f.name != out.name && DUST_CARD_NAME.matches(f.name) }?.forEach { it.delete() }
            out.path
        }.onFailure { Log.w(TAG, "faces: no picture of the dust — ${it.message}") }.getOrNull()
    }

    /**
     * RULE 9 (FaceCatalog): each face that passes the others is drawn once offscreen and compared with Xiaomi's own
     * picture of it (Faithful), once per sha1 for good (kept in [FAITHFUL_FILE]). This loads Xiaomi's engine in this
     * process, once per new face on the phone. On the faces' thread, one at a time, while the back is lit.
     */
    private fun measureFaces(list: List<FaceCatalog.Face>) {
        val todo = list.filter { it.pending && it.leftOut == "not drawn yet" && it.sha1 != null && it.content != null && it.card != null }
        if (todo.isEmpty() || rearLit(rearDisplay()) != true) return
        var any = false
        for (f in todo) {
            val sha = f.sha1!!.lowercase()
            if (faceFaithful.containsKey(sha) || faceFailed.containsKey(sha)) continue
            val score = runCatching {
                val rear = geometry
                val face = mamlHost.open("${f.key} (offscreen)", f.content!!, f.editConfig, rear.widthPx, rear.heightPx, false, sceneLook.level)
                try {
                    face.resume()
                    Thread.sleep(MEASURE_SETTLE_MS)
                    val ours = face.snapshot(rear.widthPx, rear.heightPx)
                    val ref = android.graphics.BitmapFactory.decodeFile(f.card) ?: error("Xiaomi's picture could not be read")
                    val a = IntArray(ours.width * ours.height).also { ours.getPixels(it, 0, ours.width, 0, 0, ours.width, ours.height) }
                    val b = IntArray(ref.width * ref.height).also { ref.getPixels(it, 0, ref.width, 0, 0, ref.width, ref.height) }
                    Faithful.score(a, ours.width, ours.height, b, ref.width, ref.height).also { ours.recycle(); ref.recycle() }
                } finally { runCatching { java.io.File(MamlHost.CRASHING).delete() }; face.close() }
            }.onFailure { faceFailed[sha] = it.message ?: "it did not draw" }.getOrNull()
            if (score != null) {
                faceFaithful[sha] = score
                Log.i(TAG, "faces: ${f.title} (${f.id8}) drawn offscreen and compared with Xiaomi's picture: %.2f".format(java.util.Locale.ROOT, score))
                runCatching { java.io.File(FAITHFUL_FILE).appendText("$sha $score\n") }
            }
            any = true
        }
        if (any) scanFaces("faces were drawn and compared", sha1 = false)
    }

    private fun faceHistory(from: String, to: String, why: String) = runCatching {
        val f = java.io.File(FACE_LOG)
        val line = "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())} $from -> $to ($why)"
        val kept = (f.takeIf { it.isFile }?.readLines().orEmpty() + line).takeLast(FACE_LOG_LINES)
        f.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    /** The choice from the app (the settings' Faces, or its copy of ours): followed unless older than ours. */
    private fun faceFromPayload(key: String, seq: Long) {
        if (seq < faceSeq || seq == faceSeq && key == faceKey) return
        if (key != faceKey) Log.i(TAG, "face: ${faceTitle(key)} from the settings")
        faceSeq = seq
        setFace(key, "the settings")
    }

    /** [key] made the face by a tap here (the picker, a debug word): numbered, kept, told to the app, shown. */
    private fun chooseFace(key: String, why: String) {
        faceSeq = FaceCatalog.nextSeq(faceSeq, System.currentTimeMillis())
        Log.i(TAG, "face: chosen ${faceTitle(key)} ($key) — $why")
        setFace(key, why)
        runCatching { events?.onFaceChosen(key, faceSeq) }.onFailure { Log.w(TAG, "face: the app was not told — ${it.message}") }
    }

    private fun setFace(key: String, why: String) {
        val was = faceLoopPath()
        if (key != faceKey) faceHistory(faceKey, key, why)
        faceKey = key
        runCatching { java.io.File(FACE_FILE).writeText("$key $faceSeq") }
        val now = faceLoopPath()
        if (was != now && scene.isUp) runCatching { popWaiter.execute { scene.play(null, now, android.os.SystemClock.uptimeMillis(), waitForLight = false) } }
    }

    private val picker: ShellPicker by lazy {
        ShellPicker(::rearLayerStack, { geometry }, scene,
            onShut = { chosen, _ ->
                // The table first (its exit is the picker's own, PICKER_OUT), then the face, so the new one is built
                // while the exit waits its 150 ms.
                runCatching { holdHandler.post { navStep(RearNav.Input.PickerShut(chosen != null), if (chosen != null) "chosen" else "left") } }
                if (chosen != null) chooseFace(chosen.key, "the picker") else Log.i(TAG, "face: picker left, ${faceTitle(faceKey)} stays")
            },
            // Gone: if the table still has it up (it could not be shown at all), its end is said now, or the back would
            // keep giving every finger to a picker that is not there.
            onGone = { runCatching { holdHandler.post { if (nav.picker) navStep(RearNav.Input.PickerShut(false), "the picker is gone"); syncTouchWatch() } } },
        )
    }

    /**
     * The finger is the picker's: it is up, or its exit is still playing, and no pop-up lies over it (a pop-up has the
     * whole back, also over an exit that plays under it since its shut came after the pop-up).
     */
    private fun pickerHasFinger(): Boolean { val s = nav; return !s.pop && (s.picker || picker.isUp) }

    /** A hold on the face: the picker, on the faces offered, at the face that shows. */
    private fun openPicker(x: Float, y: Float, downAt: Long) {
        val step = navStep(RearNav.Input.Hold, "held ${SceneTouch.PICKER_HOLD_MS} ms")
        if (step.anim != RearNav.Anim.PICKER_IN) return
        // The face had the finger as a possible tap: it is the picker's now (and the dust's clearing closes).
        if (faceFingerDown) { faceFingerDown = false; scene.face?.touch(android.view.MotionEvent.ACTION_CANCEL, 0f, 0f) }
        scene.dustRelease()
        val offered = FaceCatalog.offered(faces)
        val showing = shownFaceKey()
        val at = offered.indexOfFirst { it.key == showing }.coerceAtLeast(0)
        picker.open(offered, at, x, y, downAt)
        // The list fresh for next time (a download meanwhile): from the index, no hashing while it is up.
        scanFaces("the picker opened", sha1 = false)
    }

    /**
     * HIS TAP ON A XIAOMI FACE (SPEC 4.2): given to the face as a tap, so Charlie's next pet animation comes as on Xiaomi.
     * Down as it lands, up as it lifts; a finger that becomes anything else gets a cancel. The tap still brightens the back.
     */
    private fun faceTouch(e: RearTouches.Event) {
        // THE DUST SHOWS THE FINGER (DustFinger): what the touch does is decided below and elsewhere, as before.
        if (scene.dustShowing) scene.dustTouch(e, e.x * geometry.fitX, e.y * geometry.fitY, RearNav.seen(nav) == RearNav.Seen.MOON)
        val face = scene.face ?: return
        if (RearNav.seen(nav) != RearNav.Seen.MOON) { if (faceFingerDown) { faceFingerDown = false; face.touch(android.view.MotionEvent.ACTION_CANCEL, 0f, 0f) }; return }
        val px = e.x * geometry.fitX; val py = e.y * geometry.fitY
        when (e) {
            is RearTouches.Down -> { faceFingerDown = true; face.touch(android.view.MotionEvent.ACTION_DOWN, px, py) }
            is RearTouches.Move -> if (faceFingerDown && (kotlin.math.abs(e.x - fingerDownX) > SceneTouch.TAP_SLOP || kotlin.math.abs(e.y - fingerDownY) > SceneTouch.TAP_SLOP || e.atMs - pickerDownAt > SceneTouch.TAP_MS)) {
                faceFingerDown = false; face.touch(android.view.MotionEvent.ACTION_CANCEL, px, py)
            }
            is RearTouches.Up -> if (faceFingerDown) {
                faceFingerDown = false
                face.touch(if (e.atMs - pickerDownAt <= SceneTouch.TAP_MS) android.view.MotionEvent.ACTION_UP else android.view.MotionEvent.ACTION_CANCEL, px, py)
            }
        }
    }
    @Volatile private var faceFingerDown = false

    /** The tap's full brightness over: back to what the posture asks for. */
    private val sceneBrightEnd = Runnable {
        if (android.os.SystemClock.uptimeMillis() >= sceneBrightUntil) {
            sceneBrightUntil = 0L
            applySceneLook()
        }
    }

    /**
     * THE BACK DIMMED BY HAND.
     * A double tap on a bright back dims it to [DIM_GAIN] (the level it has lying face down), on every page of
     * ours, and what moves keeps moving; a tap brightens it for [SCENE_BRIGHT_MS]; a double tap on a dim back
     * (dimmed by hand or by its posture) brightens it. At first a double tap held the picture still
     * instead: that is gone (it read as the page not being animated), and nothing holds the picture still by hand.
     * The dim ends with the back going dark, so a back that wakes is never dim from a double tap he has forgotten.
     */
    @Volatile private var dimmedByHand = false
    /** Whether the back was dim as the last tap landed: a double tap decides by how it was BEFORE its first tap brightened it. */
    @Volatile private var dimBeforeTap = false
    private val handDim by lazy { RearDim(::rearLayerStack, { geometry.widthPx to geometry.heightPx }, holdHandler) }

    /** The back is dim now: by hand or by its posture, and no tap holds it bright. */
    private fun dimNow(): Boolean =
        android.os.SystemClock.uptimeMillis() >= sceneBrightUntil && (dimmedByHand || stay in 1..2)

    /** Full brightness for the usual time, then back to what the hand and the posture ask for. */
    private fun brightFor(why: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now >= sceneBrightUntil && dimNow()) Log.i(TAG, "dim: bright for ${SCENE_BRIGHT_MS / 1000} s ($why)")
        sceneBrightUntil = now + SCENE_BRIGHT_MS
        applySceneLook(byHand = true)
        runCatching { holdHandler.removeCallbacks(sceneBrightEnd); holdHandler.postDelayed(sceneBrightEnd, SCENE_BRIGHT_MS + 50L) }
    }

    /** A tap that was no button's, on the scene or a page. */
    private fun onTap() {
        dimBeforeTap = dimNow()
        brightFor("a tap")
    }

    /** A double tap, on the scene or a page: dim a bright back, brighten a dim one. */
    private fun onDoubleTap(o: SceneTouch.Out.DoubleTap) {
        val how = "second tap ${o.gapMs} ms after the first, ${o.apart.toInt()} units from it, down ${o.pressMs} ms, on ${o.on}"
        // On a dark back it is the wake (DarkBack), not a dim nobody would see.
        val lit = WakeStyle.light(rearState(rearDisplay())) == WakeStyle.Light.ON
        if (DarkBack.doubleTap(lit) == DarkBack.Act.WAKE) {
            Log.i(TAG, "wake: a double tap on the dark back ($how)")
            noteDarkTouch("a double tap")
            dimBeforeTap = false
            runCatching { events?.onWokenByHand() }
            return
        }
        if (dimBeforeTap) {
            dimmedByHand = false
            Log.i(TAG, "dim: a double tap on a dim back — bright ($how)")
            brightFor("a double tap")
        } else {
            dimmedByHand = true
            sceneBrightUntil = 0L
            runCatching { holdHandler.removeCallbacks(sceneBrightEnd) }
            Log.i(TAG, "dim: a double tap — dimmed ($how)")
            applySceneLook(byHand = true)
        }
        dimBeforeTap = false
    }

    /** The dim by hand is over: said in the log with [why]. */
    private fun dimEnds(why: String) {
        if (!dimmedByHand) return
        dimmedByHand = false
        Log.i(TAG, "dim: over ($why)")
        applySceneLook()
    }

    // -- how bright the back is: by the light around it (RearLight) ----------------------------------
    //
    // ONE GAIN FOR THE WHOLE BACK, the moon and every page alike, from the light around it (the app's sensor
    // readings: setAmbient), what the back is doing (the posture's level, a tap, a dim by hand) and the
    // settings' slider. It is put on as the dim layer over everything of ours (RearDim), and what that layer
    // may not do (it is never more opaque than RearDim.MAX_ALPHA) in the scene's own shader. Every change is
    // eased (RearLight.Ease): the light's over a second and a half, his own hand's in a quarter.
    // The PANEL's own brightness is Xiaomi's automatic one and is not touched: see RearLight.

    private val lightSteady = RearLight.Steady()
    // A new process starts from the dark end and comes up: started from the old in-hand level it showed the
    // moon bright for a second before settling (seen in a dark room after an install).
    private val gainEase = RearLight.Ease(RearLight.gain(RearLight.FLOOR))
    private val lightLock = Any()
    /** PanelConfig.rearAdaptive and rearLightBias, as the last payload had them. */
    @Volatile private var adaptive = true
    @Volatile private var lightBias = 0f
    /** Which sensor the last reading was from (IRearService.setAmbient): 0 the rear one, 1 the front one, 2 none. */
    @Volatile private var lightSource = 2
    private var lightSaid = ""
    /** What the panel itself gives now, in nits (RearPanel), read as the light is looked at; NaN: not known. */
    @Volatile private var panelNits = Float.NaN
    /** Its reading, kept two seconds (RearPanel.Kept), read again at once when the light around has changed. */
    private val panelKept = RearPanel.Kept()

    override fun setAmbient(lux: Float, source: Int) {
        lightSource = source
        if (source != 2) lightSteady.feed(lux, android.os.SystemClock.uptimeMillis())
        panelKept.forget()
        applySceneLook()
    }

    /** The back's brightness from everything that has a say; [byHand]: he asked for it just now (a tap, a double tap), so it comes quickly. */
    private fun applySceneLook(byHand: Boolean = false) = synchronized(lightLock) {
        val now = android.os.SystemClock.uptimeMillis()
        val bright = now < sceneBrightUntil
        val lux = lightSteady.at(now)
        if (adaptive && stay > 0) panelNits = panelKept.nits(rearDisplay(), now)
        val panel = panelNits
        val target = if (adaptive) RearLight.gain(RearLight.light(lux, RearLight.Doing(stay, bright, dimmedByHand && !bright), lightBias, panel))
        else {
            // Not by the light: the fixed levels, as before.
            val g = if (bright) 1f else STAY_GAIN[stay]
            if (dimmedByHand && !bright) minOf(g, DIM_GAIN) else g
        }
        if (target != gainEase.target) {
            gainEase.send(target, now, if (byHand) RearLight.EASE_BY_HAND_MS else RearLight.EASE_MS)
            // Said once per change: the pair to check it by.
            val word = "${if (lux.isNaN()) "no reading" else "%.1f lux".format(lux)} (${arrayOf("rear sensor", "front sensor", "no sensor")[lightSource.coerceIn(0, 2)]}) -> " +
                "${"%.1f".format(RearLight.lightOf(target) * 100f)}% of the panel's ${if (panel.isNaN()) "light" else "%.1f nits = %.2f nits".format(panel, panel * RearLight.lightOf(target))} (picture x${"%.2f".format(target)}), ${STAY_NAME[stay]}" +
                (if (bright) ", tapped" else if (dimmedByHand) ", dimmed by hand" else "") + (if (adaptive) "" else ", fixed levels")
            if (word != lightSaid && stay > 0) { lightSaid = word; Log.i(TAG, "light: $word") }
        }
        // Still only as a dim still's posture asks (and under the music page, and under every opaque page of ours in its
        // place, where it is not seen: HiddenFrames): never by hand.
        scene.still = stillNow(now)
        putGain(now)
        if (gainEase.moving(now)) runCatching { holdHandler.removeCallbacks(gainTick); holdHandler.postDelayed(gainTick, 16L) }
    }

    /**
     * Whether the scene is held on its frame at [now]: a dim still's posture without a tap, the music page up, or an
     * opaque page of ours in its place over it (HiddenFrames, from the navigation's state and the layers' own places).
     */
    private fun stillNow(now: Long): Boolean =
        HiddenFrames.sceneStill(stay == STAY_STILL && now >= sceneBrightUntil && !(nav.pop && islandOverFace), musicFace.isUp, nav, ::layerInPlace)

    /** Whether [layer] stands in its place now: for the live page, the card of the navigation's live thing. */
    private fun layerInPlace(layer: HiddenFrames.Layer): Boolean = when (layer) {
        HiddenFrames.Layer.POP -> popInPlace
        HiddenFrames.Layer.ARRIVAL -> arrivalPage.inPlace
        HiddenFrames.Layer.NOTES -> pinsPage.inPlace
        HiddenFrames.Layer.USAGE -> usage.inPlace
        HiddenFrames.Layer.LIVE -> when (nav.live) {
            RearNav.Live.NAVIGATION -> navigationFace.inPlace
            RearNav.Live.TIMER -> timerFace.inPlace
            RearNav.Live.CALL -> callFace.inPlace
            RearNav.Live.MUSIC -> musicFace.inPlace
            else -> false
        }
        HiddenFrames.Layer.CHARGE -> chargeInPlace
        HiddenFrames.Layer.SHADE -> pileInPlace && notices.pagesUp
    }

    /** The pop-up stands over the whole back in its place (ShellNotices' word). */
    @Volatile private var popInPlace = false
    @Volatile private var islandOverFace = false
    /** The whole charging page stands in its place (ShellCharge's word; read here so its lazy page is not made for it). */
    @Volatile private var chargeInPlace = false
    /** The notifications' pile stands settled over the whole back (ShellNotices' word, from its sheet: ShellOverlay.onCover). */
    @Volatile private var pileInPlace = false

    /** A page of ours came into its place or is leaving it (its own thread, before it moves): what is under it follows. */
    private fun coverMoved() = syncHidden()

    /**
     * NO PICTURES FOR NOBODY (HiddenFrames): the scene held while a page of ours covers it in its place, and moving
     * again the moment that ends; the live page's pictures stopped while usage, the notes or an arrival lie over it in
     * their place, and the live page DRAWN AT ONCE when that ends, before what lay over it moves (on the pages' thread
     * this is called on, or first in that thread's queue). Any thread.
     */
    private fun syncHidden() {
        synchronized(lightLock) { scene.still = stillNow(android.os.SystemClock.uptimeMillis()) }
        if (liveHeld && HiddenFrames.liveDrawn(nav, ::layerInPlace)) {
            if (android.os.Looper.myLooper() == musicHandler.looper) drawLiveNow() else runCatching { musicHandler.post { drawLiveNow() } }
        }
    }

    /** A live page's frame tick found it covered and drew nothing: it is drawn the moment it is not ([drawLiveNow]). */
    @Volatile private var liveHeld = false

    /** Whether the live page's pictures are to be drawn now: not while usage, the notes or an arrival lie over it in their place. */
    private val liveLayerInPlace: (HiddenFrames.Layer) -> Boolean = ::layerInPlace
    private fun liveSeen(): Boolean = HiddenFrames.liveDrawn(nav, liveLayerInPlace)

    /** On the pages' thread: the live page that was not drawn under a page drawn NOW, and its frames going on. */
    private fun drawLiveNow() {
        if (!liveHeld || !liveSeen()) return
        liveHeld = false
        if (navigationFace.isUp) navigationFrame()
        if (timerFace.isUp) timerFrame(sync = true)
        if (callFace.isUp) callFrame(sync = true)
        if (musicFace.isUp) musicFrame(sync = true)
    }

    /** The gain as it is at [now], put on the back: the dim layer over all of ours, and the rest in the scene itself. */
    private fun putGain(now: Long) {
        val g = gainEase.at(now).coerceIn(0.05f, 1f)
        // Only over a face of ours: never over an app of his on the back.
        // And over Xiaomi's arrival of a pin that goes through its own (PinVia): the back keeps its level all the way
        // (at Xiaomi's own full light it stood out, and our page after it was a step darker).
        val veil = if (cover.isUp || viaXiaomi) (1f - g).coerceIn(0f, RearDim.MAX_ALPHA) else 0f
        runCatching { handDim.set(veil, now = true) }
        scene.dim = (g / (1f - veil)).coerceIn(0f, 1f)
        // The charging page dims as the scene does.
        charge.dim = scene.dim
    }

    private val gainTick: Runnable = object : Runnable {
        override fun run() = synchronized(lightLock) {
            val now = android.os.SystemClock.uptimeMillis()
            putGain(now)
            if (gainEase.moving(now)) { holdHandler.postDelayed(this, 16L) }
        }
    }

    /** While the back is lit: twice a second the light's reading is looked at again (a reading that has stayed is then followed). */
    private val lightTick: Runnable = object : Runnable {
        override fun run() {
            // Also while the rule has it off and he lit it himself: the light around it is followed as long as it is lit.
            if (stay <= 0 && rearLight != WakeStyle.Light.ON) return
            applySceneLook()
            holdHandler.postDelayed(this, 500L)
        }
    }

    // -- what is playing: the whole back, as Xiaomi's own music page is (MusicFace) --------------------

    /** [art]: the track's cover; [icon]: the player's own icon, shown in its place when a track has none. */
    private class Media(val title: String, val artist: String, val playing: Boolean, val art: android.graphics.Bitmap?, val icon: android.graphics.Bitmap? = null) {
        /** Taken as the cover arrives, never in a frame (Celebi on the cover costs some milliseconds). */
        val colors: MusicFace.Palette = MusicFace.colorsOf(art ?: icon)
    }
    @Volatile private var media: Media? = null
    /** When it last played, or was last touched (uptime): it stays a short while after a pause. */
    @Volatile private var mediaAliveAt = 0L
    @Volatile private var mediaTouchedAt = 0L
    /** When it last went from playing to paused or back (uptime), for the middle button's change. */
    @Volatile private var musicFlippedAt = 0L
    @Volatile private var musicPressed: MusicFace.Button? = null
    @Volatile private var musicPressedAt = 0L
    /** Its floor ("Fluid size") is the default's until the first payload says his. */
    private val musicBody = MusicFace.Body { Log.i(TAG, it) }.apply { floor = dev.kb896.aiusage.data.PanelConfig.MUSIC_FLOOR }
    private val musicFade = MusicFace.Fade()
    /** When the last payload changed "Fluid size" (uptime): bounds the frames a page standing still is drawn for it. */
    @Volatile private var musicFloorAt = 0L

    /** Not sent any more (setMusicSound has taken its place). */
    override fun setMusicLevel(level: Float) {}

    override fun setMusicSound(v: FloatArray?) { if (v != null) musicBody.hear(v, android.os.SystemClock.uptimeMillis()) }

    /** Its own thread: the glows move thirty times a second, and nothing else of the shell's may hold them up. */
    private val musicHandler by lazy { android.os.Handler(android.os.HandlerThread("rear-music").apply { isDaemon = true; start() }.looper) }

    /**
     * THE PAGES' PICTURES ON THE VSYNC (FramePacer): every page on [musicHandler]'s thread asks it for its next picture
     * (usage, the notes and their turn and carousel, an arrival, music, timer, call, and the slides of them all), in
     * place of a fixed 8 / 12 / 14 / 16 / 33 ms sleep that fell one or two refreshes apart by turns.
     */
    private val pagePacer: FramePacer by lazy { FramePacer(musicHandler, "rear-music", ::rearPeakHz) }

    /** The rear's highest refresh rate (FramePacer: never its rate of the moment); 0 when it does not say. */
    private fun rearPeakHz(): Float = runCatching {
        val d = context?.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(rearDisplay())
        d?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f
    }.getOrDefault(0f)

    private val musicFace: ShellCard by lazy {
        ShellCard("music", ::rearLayerStack, { geometry }, musicHandler, z = ShellLayer.Z_MUSIC, opaque = true, slide = true, onPlace = { coverMoved() }, pacer = pagePacer) { c, w, h ->
            val m = media ?: return@ShellCard
            val now = android.os.SystemClock.uptimeMillis()
            // The colours eased from the last cover's to this one's, the body where its one clock has it: asked every frame.
            // The page's hides tell the body its first frame back after being away (it shows the present then).
            MusicFace.draw(c, w, h, m.title, m.artist, m.playing, m.art, musicFade.at(m.colors, now), musicBody.step(now, m.playing, musicFace.hides), now - musicFlippedAt, musicPressed, now - musicPressedAt, m.icon)
        }
    }

    /** One more picture of the music page, and the next one for as long as something on it moves. */
    private val musicTick: Runnable = Runnable { musicFrame() }
    /** [sync]: drawn on this thread now, not posted (only ever on the pages' thread: [drawLiveNow]). */
    private fun musicFrame(sync: Boolean = false) {
        pagePacer.cancel(musicTick)
        if (!musicFace.isUp) { musicFrameAt = 0L; return }
        val began = android.os.SystemClock.uptimeMillis()
        // COVERED in its place by usage, the notes or an arrival: no picture for nobody, only a look now and then
        // (it is drawn at once as what covers it starts to leave: drawLiveNow). The gaps are not counted.
        if (!liveSeen()) {
            liveHeld = true
            musicFrameAt = 0L
            // Not seen: a new "Fluid size" is stood on at once (a glide is for a change he watches), so the page is on it
            // the moment it is drawn again; nothing of the floor is left to look for.
            musicBody.floorUnseen()
            val goesOn = media?.playing == true || began - musicFlippedAt < MUSIC_REST_MS || began - musicPressedAt < MusicFace.PRESS_MS + 60
            if (goesOn) musicHandler.postAtTime(musicTick, began + HiddenFrames.hiddenDelay(MUSIC_PAGE_FRAME_MS))
            return
        }
        // How evenly it is drawn, said every few hundred pictures, so its smoothness can be checked.
        if (musicFrameAt != 0L) {
            val gap = began - musicFrameAt
            musicGapSum += gap; musicGapMax = maxOf(musicGapMax, gap); musicFrames++
            if (musicFrames >= 300) {
                Log.i(TAG, "music: $musicFrames pictures, ${musicGapSum / musicFrames} ms from one to the next, $musicGapMax ms at most")
                musicFrames = 0; musicGapSum = 0L; musicGapMax = 0L
            }
        }
        musicFrameAt = began
        if (sync) musicFace.drawNow() else musicFace.redraw()
        val now = android.os.SystemClock.uptimeMillis()
        // EVERY FRAME while it plays (the glows breathe), and for a few seconds after a pause (they sink to rest), and
        // until the body is on a new "Fluid size" (however late its frames came; bounded by the change's time, so a body
        // that is not stepped never keeps the thread going).
        val moving = media?.playing == true || now - musicFlippedAt < MUSIC_REST_MS || now - musicPressedAt < MusicFace.PRESS_MS + 60 ||
            (musicBody.floorGliding && now - musicFloorAt < MUSIC_FLOOR_MS)
        if (moving) pagePacer.post(musicTick, MUSIC_PAGE_FRAME_MS.toFloat()) else musicFrameAt = 0L
    }
    private var musicFrameAt = 0L
    private var musicFrames = 0
    private var musicGapSum = 0L
    private var musicGapMax = 0L

    override fun setMedia(b: android.os.Bundle?) {
        val active = b?.getBoolean("active") == true
        mediaIsDemo = false
        musicBody.endDemo()
        val was = media
        val next = if (!active || b == null) null else Media(
            b.getString("title").orEmpty(), b.getString("artist").orEmpty(), b.getBoolean("playing"),
            b.getByteArray("art")?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } ?: was?.art?.takeIf { _ -> b.getString("title") == was.title },
            b.getByteArray("icon")?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } ?: was?.icon,
        )
        if (next != null && was != null && next.playing != was.playing) musicFlippedAt = android.os.SystemClock.uptimeMillis()
        val nowUp = android.os.SystemClock.uptimeMillis()
        // Playing after not playing is something new (a page he sent away comes again) ONLY AFTER A REAL STOP: a
        // player is "not playing" for a moment at every track change, skip and seek.
        if (was?.playing == true && next?.playing != true) musicStoppedAt = nowUp
        if (next?.playing == true && was?.playing != true && (musicStoppedAt == 0L || nowUp - musicStoppedAt >= MUSIC_STOP_MS)) musicStarts++
        media = next
        // Its page stays 20 s from the moment it stopped (a pause on the phone or a headset), not from the last word while playing.
        if (next?.playing == true || was?.playing == true) mediaAliveAt = nowUp
        syncMediaCard(redraw = true)
    }

    // -- a timer or a stopwatch: the whole back, as Xiaomi's own timer page is (TimerFace) -------------

    @Volatile private var timer: TimerFace.Model? = null
    /** The timer whose X he pressed, and when: its own last words are not a timer again. */
    @Volatile private var timerClosed: TimerFace.Model? = null
    @Volatile private var timerClosedAt = 0L
    @Volatile private var timerPressed: TimerFace.Side? = null
    @Volatile private var timerPressedAt = 0L
    private val timerArt: Map<String, android.graphics.Bitmap> by lazy { XiaomiArt.load("timer", TimerFace.ART) }

    private val timerFace: ShellCard by lazy {
        ShellCard("timer", ::rearLayerStack, { geometry }, musicHandler, z = ShellLayer.Z_LIVE, opaque = true, slide = true, onPlace = { coverMoved() }, pacer = pagePacer) { c, w, h ->
            val m = timer ?: return@ShellCard
            TimerFace.draw(c, w, h, m, System.currentTimeMillis(), timerArt, timerPressed, android.os.SystemClock.uptimeMillis() - timerPressedAt)
            if (m.pkg == CALL_DEMO) demoMark(c, h)
        }
    }

    /** One more picture of the timer page, and the next for as long as it runs or a press shows. */
    private val timerTick: Runnable = Runnable { timerFrame() }
    /** [sync]: drawn on this thread now, not posted (only ever on the pages' thread: [drawLiveNow]). */
    private fun timerFrame(sync: Boolean = false) {
        pagePacer.cancel(timerTick)
        val m = timer
        if (!timerFace.isUp || m == null) return
        val pressing = android.os.SystemClock.uptimeMillis() - timerPressedAt < TimerFace.PRESS_MS + 60
        val goesOn = pressing || (!m.paused && !m.over)
        // Covered in its place (usage, the notes, an arrival over it): no picture, a look now and then (HiddenFrames).
        if (!liveSeen()) { liveHeld = true; if (goesOn) musicHandler.postDelayed(timerTick, HiddenFrames.hiddenDelay(MUSIC_FRAME_MS)); return }
        if (sync) timerFace.drawNow() else timerFace.redraw()
        // Running, its water drifts: a new picture every frame of ours (once a second it only stepped, and looked like lag),
        // on the vsync (every 4th refresh at 120 Hz: FramePacer).
        if (goesOn) pagePacer.post(timerTick, MUSIC_FRAME_MS.toFloat())
    }

    /**
     * A button of the timer page. Xiaomi's Clock takes the commands its own rear page sends it
     * (TimerService: action.timer_pause, _continue, _cancel, _dismiss); any other clock, its
     * notification's own button of that meaning. A made-up timer (the debug file) does nothing.
     */
    private fun timerPress(side: TimerFace.Side, m: TimerFace.Model) {
        val act = TimerFace.act(side, m)
        timerPressed = side; timerPressedAt = android.os.SystemClock.uptimeMillis()
        // Shown at once; the Clock's own word follows.
        if (act == TimerFace.Act.PAUSE) timer = m.copy(paused = true, left = (m.endAt - System.currentTimeMillis()).coerceAtLeast(0L))
        if (act == TimerFace.Act.RESUME) timer = m.copy(paused = false, endAt = System.currentTimeMillis() + m.left)
        timerFrame()
        Log.i(TAG, "timer: ${act.name.lowercase()} pressed${if (m.pkg == CALL_DEMO) " (a demo's)" else ""}")
        // THE X ENDS IT, ALWAYS, AND ITS PAGE GOES AT ONCE: running, paused or run out, the Clock's own or a demo's.
        // The page does not wait for the Clock to take its notification down, and what the Clock posts of that same
        // timer on its way out is not taken for a timer again ([timerClosed]).
        if (act == TimerFace.Act.CANCEL || act == TimerFace.Act.DISMISS) {
            timerClosed = m; timerClosedAt = android.os.SystemClock.uptimeMillis()
            timer = null
            syncMediaCard(atOnce = true)
        }
        if (m.pkg == CALL_DEMO) return
        runCatching {
            popWaiter.execute {
                val action = when (act) { TimerFace.Act.PAUSE -> "action.timer_pause"; TimerFace.Act.RESUME -> "action.timer_continue"; TimerFace.Act.CANCEL -> "action.timer_cancel"; TimerFace.Act.DISMISS -> "action.timer_dismiss" }
                // The Clock's own command, unless the health look found it gone from the Clock's code: its notification's button then.
                if (m.pkg == XIAOMI_CLOCK && !ShellHealth.clockTakes(action)) Log.i(TAG, "timer: the Clock has no $action any more — its notification's button")
                if (m.pkg == XIAOMI_CLOCK && ShellHealth.clockTakes(action)) {
                    val out = exec("am broadcast -a $action -p $XIAOMI_CLOCK")
                    if (!out.contains("Broadcast completed")) Log.w(TAG, "timer: the Clock was not told: ${out.take(120)}")
                } else {
                    val i = TimerFace.actionFor(act, m.actions)
                    if (i >= 0) runCatching { events?.onLiveAction(m.key, i) } else Log.i(TAG, "timer: this clock offers no button for that")
                }
            }
        }
    }

    // -- a route being followed: the whole back (NavFace), from the app's NavigationNotification packet ----------------

    /** The route the app last told of; null for none. */
    @Volatile private var navigationAsTold: NavFace.Model? = null
    /** What the page shows: the route, or a demo's (the debug file), which never stands in for his own route. */
    @Volatile private var navigation: NavFace.Model? = null
    /** The last drawn, for a picture drawn again while the page slides out after the route has ended. */
    @Volatile private var navigationDrawn: NavFace.Model? = null
    /** The settings' "Navigation on the back". */
    @Volatile private var navigationOn = true
    private val navigationMotion = NavFace.TrackerMotion()
    /** The route's pictures as last decoded: the same arrow sent again with the next step is not decoded again. */
    private val navigationImages = dev.kb896.aiusage.service.NavigationNotification.Images()

    /** The route as live to the navigation (RearNav.Live.NAVIGATION): none while the switch is off (a test's demo shows all the same). */
    private fun navigationLive(): NavFace.Model? = navigation?.takeIf { navigationOn || it.demo }

    private val navigationFace: ShellCard by lazy {
        ShellCard("navigation", ::rearLayerStack, { geometry }, musicHandler, z = ShellLayer.Z_LIVE, opaque = true, slide = true, onPlace = { coverMoved() }, pacer = pagePacer) { c, w, h ->
            val route = navigation
            val model = route ?: navigationDrawn ?: return@ShellCard
            val position = if (route == null) model.fraction ?: Float.NaN else
                navigationMotion.position(model, android.os.SystemClock.uptimeMillis())
            NavFace.drawFrame(c, w, h, model, position)
            navigationDrawn = model
        }
    }
    private val navigationTick = Runnable { navigationFrame() }

    /** Called under liveLooking. RearNav still decides whether the new model has a page. */
    private fun updateNavigation(next: NavFace.Model?) {
        if (next == navigation) return
        if (next == null) navigationMotion.reset() else navigationMotion.update(next, android.os.SystemClock.uptimeMillis())
        navigation = next
    }

    /** Draw on the page thread, then ask for the next frame only while a reported step is easing. */
    private fun navigationFrame(draw: Boolean = true) {
        pagePacer.cancel(navigationTick)
        if (!navigationFace.isUp) return
        val route = navigation ?: return
        if (!liveSeen()) { liveHeld = true; return }
        // Decide before painting so that a draw which crosses the deadline cannot leave the tracker short of its target.
        val moving = (route.tracker != null || route.styledByProgress) &&
            navigationMotion.moving(android.os.SystemClock.uptimeMillis())
        if (draw) navigationFace.drawNow()
        if (moving) pagePacer.post(navigationTick, 0f)
    }

    private fun realLiveDuringNavigationDemo(now: Long): Boolean =
        call?.let { it.pkg != CALL_DEMO } == true || timer?.let { it.pkg != CALL_DEMO } == true ||
            (!mediaIsDemo && media?.let { it.playing || now - maxOf(mediaAliveAt, mediaTouchedAt) < MEDIA_LINGER_MS } == true)

    private val navigationDemoEnds = Runnable {
        synchronized(liveLooking) {
            if (navigation?.demo == true) {
                updateNavigation(navigationAsTold)
                syncMediaCard(atOnce = true)
            }
        }
    }

    // -- a call: the whole back, as Xiaomi's own call page is (CallFace) --------------------------------

    @Volatile private var call: CallFace.Model? = null
    /** When it went from ringing to answered, and a button's press (uptime), for the page's motions. */
    @Volatile private var callChangedAt = 0L
    @Volatile private var callPressed: CallFace.Side? = null
    @Volatile private var callPressedAt = 0L
    @Volatile private var callShownAt = 0L
    private val callArt: Map<String, android.graphics.Bitmap> by lazy { XiaomiArt.load("phone", CallFace.ART) }

    private val callFace: ShellCard by lazy {
        ShellCard("call", ::rearLayerStack, { geometry }, musicHandler, z = ShellLayer.Z_LIVE, opaque = true, slide = true, onPlace = { coverMoved() }, pacer = pagePacer) { c, w, h ->
            val m = call ?: return@ShellCard
            val up = android.os.SystemClock.uptimeMillis()
            CallFace.draw(c, w, h, m, System.currentTimeMillis(), callArt, up - callChangedAt, callPressed, up - callPressedAt, ringAt = System.currentTimeMillis() - (up - callShownAt))
            if (m.pkg == CALL_DEMO) demoMark(c, h)
        }
    }

    /** One more picture of the call page: at once again while it rings or something on it moves, else at the next second for its time. */
    private val callTick: Runnable = Runnable { callFrame() }
    /** [sync]: drawn on this thread now, not posted (only ever on the pages' thread: [drawLiveNow]). */
    private fun callFrame(sync: Boolean = false) {
        pagePacer.cancel(callTick)
        val m = call
        if (!callFace.isUp || m == null) return
        val seen = liveSeen()
        if (!seen) liveHeld = true
        else if (sync) callFace.drawNow() else callFace.redraw()
        val up = android.os.SystemClock.uptimeMillis()
        val moving = m.ringing || up - callChangedAt < CallFace.CHANGE_MS + 60 || up - callPressedAt < CallFace.PRESS_MS + 60
        val next = if (moving) MUSIC_FRAME_MS else 1_000L - System.currentTimeMillis() % 1_000L + 20L
        // Covered in its place: no picture, a look now and then, never sooner than its own pace (HiddenFrames).
        // Seen and moving (ringing, a change, a press): on the vsync (FramePacer); else at the next second, by the clock.
        if (seen && moving) pagePacer.post(callTick, MUSIC_FRAME_MS.toFloat())
        else musicHandler.postDelayed(callTick, if (seen) next else HiddenFrames.hiddenDelay(next))
    }

    /**
     * A button of the call page. The phone's own dialer takes the commands Xiaomi's rear page sends it
     * (com.android.incallui's FocusBackScreenReceiver: answer, which goes to the speaker unless a headset
     * is on, as Xiaomi's does; reject; hang up; speaker). Any other app's call: its notification's own
     * button of that meaning, and for answering or ending with none, the phone's call keys. A made-up
     * call (the debug file) does nothing here, ever: a real call must not be touched by a test.
     */
    private fun callPress(side: CallFace.Side, m: CallFace.Model) {
        val act = CallFace.act(side, m.ringing)
        callPressed = side; callPressedAt = android.os.SystemClock.uptimeMillis()
        // The speaker shows its new state at once; the app says what it really is a moment later.
        if (act == CallFace.Act.SPEAKER) call = m.copy(speaker = !m.speaker)
        callFrame()
        Log.i(TAG, "call: ${act.name.lowercase()} pressed${if (m.pkg == CALL_DEMO) " (a demo's)" else ""}")
        if (m.pkg == CALL_DEMO) {
            // No button on the back does nothing: on a demo they do to the demo what they say.
            when (act) {
                CallFace.Act.ANSWER -> { call = m.copy(ringing = false, since = System.currentTimeMillis()); callChangedAt = android.os.SystemClock.uptimeMillis(); syncMediaCard(redraw = true) }
                CallFace.Act.DECLINE, CallFace.Act.END -> { call = null; syncMediaCard(atOnce = true) }
                CallFace.Act.SPEAKER -> Unit
            }
            return
        }
        runCatching {
            popWaiter.execute {
                val command = when (act) {
                    CallFace.Act.ANSWER -> "ACTION_REAR_ANSWER_INCOMING_CALL"
                    CallFace.Act.DECLINE -> "ACTION_REAR_REJECT_INCOMING_CALL"
                    CallFace.Act.END -> "ACTION_REAR_HANG_UP_CALL"
                    CallFace.Act.SPEAKER -> "ACTION_REAR_SPEAKER"
                }
                // The dialer's own command, unless the health look found it gone from the dialer's code: then the way any
                // other app's call takes (its notification's button, else the phone's call keys).
                if (m.pkg == DIALER && !ShellHealth.dialerTakes(command)) Log.i(TAG, "call: the dialer has no $command any more — the plain way")
                if (m.pkg == DIALER && ShellHealth.dialerTakes(command)) {
                    val action = command
                    val out = exec("am broadcast -a $DIALER.$action -p $DIALER")
                    if (!out.contains("Broadcast completed")) Log.w(TAG, "call: the dialer was not told: ${out.take(120)}")
                } else {
                    val i = CallFace.actionFor(act, m.actions)
                    when {
                        i >= 0 -> runCatching { events?.onLiveAction(m.key, i) }
                        act == CallFace.Act.ANSWER -> exec("input keyevent KEYCODE_CALL")
                        act == CallFace.Act.END || act == CallFace.Act.DECLINE -> exec("input keyevent KEYCODE_ENDCALL")
                        else -> Log.i(TAG, "call: this app offers no button for the speaker")
                    }
                }
            }
        }
    }

    /** What the app last said is live (his real timer or call), kept so a debug demo that hid it can put it back ([liveBack]). */
    @Volatile private var liveAsTold: android.os.Bundle? = null
    private val liveBack = Runnable { liveAsTold?.let { Log.i(TAG, "live: his own back after a demo"); setLive(it) } }
    /**
     * A DEMO'S LIVE PAGE NEVER LINGERS: [DEMO_LIVE_MS] after it was set it
     * is taken down here, whoever set it and whatever became of them, and his own is back. Its page says "Demo".
     */
    private val demoLiveEnds = Runnable {
        var was = false
        if (timer?.pkg == CALL_DEMO) { timer = null; was = true }
        if (call?.pkg == CALL_DEMO) { call = null; was = true }
        if (mediaIsDemo) { media = null; mediaIsDemo = false; was = true }
        if (was) { Log.i(TAG, "live: a demo taken down after its time"); syncMediaCard(atOnce = true); liveBack.run() }
    }
    @Volatile private var mediaIsDemo = false
    private val demoPaint by lazy { android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textAlign = android.graphics.Paint.Align.RIGHT; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false) } }
    /** "Demo" in the corner of a made-up live page, so it is never taken for a timer or a call of his own. */
    private fun demoMark(c: android.graphics.Canvas, h: Float) {
        val u = h / 596f
        demoPaint.textSize = 30f * u
        c.drawText("Demo", 940f * u, 46f * u, demoPaint)
    }
    private fun demoLiveFor() = runCatching { holdHandler.removeCallbacks(demoLiveEnds); holdHandler.postDelayed(demoLiveEnds, DEMO_LIVE_MS) }

    override fun setLive(b: android.os.Bundle?) {
        // A route (its own packet: a call or a timer does not replace it). His own route takes a demo's place at once.
        if (b?.getString("kind") == dev.kb896.aiusage.service.NavigationNotification.KIND) {
            synchronized(liveLooking) {
                val next = dev.kb896.aiusage.service.NavigationNotification.fromBundle(b, navigationImages)
                if ((next == null) != (navigationAsTold == null)) Log.i(TAG, if (next != null) "navigation: a route is followed (${next.pkg})" else "navigation: the route has ended")
                navigationAsTold = next
                if (next != null || navigation?.demo != true) {
                    updateNavigation(next)
                    syncMediaCard()
                }
            }
            return
        }
        liveAsTold = b
        val key = b?.getString("key")
        if (b == null || key.isNullOrEmpty()) {
            // "sure": the phone itself says the call is over (the app's CallHold), so its page need not wait for a notification to come back.
            val sure = b?.getBoolean("sure") == true && call != null
            timer = null; call = null; syncMediaCard(atOnce = sure); return
        }
        if (b.getBoolean("call")) {
            // A call is a page of its own, never a card.
            val was = call
            val running = b.getBoolean("running")
            val next = CallFace.Model(
                key, b.getString("pkg").orEmpty(), b.getString("title").orEmpty(), b.getString("text").orEmpty(),
                ringing = !running, since = if (running) b.getLong("base") else 0L,
                speaker = b.getBoolean("speaker"), headset = b.getBoolean("headset"),
                actions = b.getStringArray("actions")?.toList().orEmpty(),
            )
            if (was?.ringing == true && !next.ringing) callChangedAt = android.os.SystemClock.uptimeMillis()
            if (was == null) callShownAt = android.os.SystemClock.uptimeMillis()
            timer = null; call = next
            syncMediaCard(redraw = true)
            syncFrontDim("a call")
            return
        }
        call = null
        // A timer or a stopwatch is a page of its own too.
        val was = timer
        val end = b.getLong("end"); val left = b.getLong("left")
        // A clock that does not say how long the timer is in all: what was left when it was first seen.
        // The timer he has just closed with the X, said once more by its clock on the way out: not a timer again.
        timerClosed?.let { c ->
            val same = c.pkg == b.getString("pkg").orEmpty() && (if (b.getBoolean("paused")) kotlin.math.abs(left - c.left) < 1_500L || c.paused.not() else kotlin.math.abs(end - c.endAt) < 1_500L)
            if (same && android.os.SystemClock.uptimeMillis() - timerClosedAt < TIMER_CLOSED_MS) { Log.i(TAG, "timer: the one he closed, said again by its clock — not shown"); timer = null; syncMediaCard(); return }
            timerClosed = null
        }
        val total = b.getLong("total").takeIf { it > 0L } ?: was?.total?.takeIf { it > 0L && was.key == key } ?: (if (b.getBoolean("paused")) left else end - System.currentTimeMillis()).coerceAtLeast(0L)
        timer = TimerFace.Model(
            key, b.getString("pkg").orEmpty(), b.getString("title").orEmpty(), end, left, total,
            paused = b.getBoolean("paused"), over = b.getBoolean("over"), up = b.getBoolean("up"),
            actions = b.getStringArray("actions")?.toList().orEmpty(),
        )
        syncMediaCard(redraw = true)
    }

    /**
     * WHAT IS LIVE, LOOKED AT AGAIN (the name is from when it was one card): what is new or gone is told to the
     * navigation (LiveWatch -> RearNav), which decides whether a page comes, and the live page's layers are made
     * what the navigation's state says. Called at every change of a call, a timer, what is playing, the charger, the
     * light and the scene.
     */
    private fun syncMediaCard(redraw: Boolean = false, atOnce: Boolean = false): Unit = synchronized(liveLooking) {
        val now = android.os.SystemClock.uptimeMillis()
        if (navigation?.demo == true && realLiveDuringNavigationDemo(now)) updateNavigation(navigationAsTold)
        // The light and whose the back is, as they are: an edge missed (the first look after this process started) is made up here.
        val lit = rearLight == WakeStyle.Light.ON
        if (lit != nav.lit) navStep(if (lit) RearNav.Input.Lit else RearNav.Input.Dark, "as found")
        val ours = cover.isUp
        if (ours != nav.ours) navStep(if (ours) RearNav.Input.OthersLeave else RearNav.Input.OthersTake, "as found")
        val token = liveToken(now)
        val told = liveWatch.look(now, liveMarks(now), nav, atOnce) { kind -> kind == RearNav.Live.CALL || (kind == RearNav.Live.TIMER && timer?.over == true) }
        if (token != liveSaid) {
            // Said at each change: this is what decides whether a page he sent away comes back by itself.
            Log.i(TAG, "row: live is now ${token?.let { if (it.kind == PageRow.Kind.NAVIGATION) "navigation" else "${it.kind.name.lowercase()} (${it.key.takeLast(24)}, ${it.phase})" } ?: "nothing"}${if (nav.away) ", one sent away" else ""}")
            liveSaid = token
        }
        for (input in told.inputs) navStep(input)
        if (told.againAt > 0L) runCatching { holdHandler.removeCallbacks(liveLookAgain); holdHandler.postDelayed(liveLookAgain, (told.againAt - now).coerceAtLeast(50L) + 50L) }
        applyLive(redraw = redraw)
    }
    private var musicWasUp = false
    /** One look at what is live at a time: it is asked for from the app's calls, the touch reader, the timers and the stay loop. */
    private val liveLooking = Any()

    // -- the guest hold: the back kept lit over a guest ----------------------------------
    //
    // See GuestHold for why and when, DisplayWakeLock for what is held, and the reading of
    // what it can and cannot do against a sleep. Here only the plumbing:
    //
    // ONE THREAD, ONE TIMER. [holdHandler] does the looking, its one delayed [holdCheck] is
    // the only timer there is, and it is replaced at every look; GuestHold asks for none when
    // there is nothing to hold, so a back nobody touches has nothing running for it.
    //
    // THE TOUCH PATH TAKES NO LOCK and is not delayed: [guestHoldTouch] reads volatiles, and
    // while held it has nothing to do at all — the timer, when it comes, reads
    // [lastRearTouchAt] and moves itself out. Otherwise it posts one look ([holdQueued], so a
    // finger's moves are one post), and that is where the display is read (a binder call) and
    // the lock taken. [holdLock] is a leaf: nothing is called under it but the display's
    // state and the power manager, and the anything-may-call [syncGuestHold] only posts, so it
    // is safe from under [placing] and everywhere else.
    //
    // WHAT PUTS THE BACK DOWN GOES FIRST: a sleep on purpose and a pocket let go on their own
    // thread before they go on ([syncGuestHoldNow]); a double tap is caught at its second
    // landing. The back going dark any other way (the power button, HyperOS's own sleep) is
    // seen by a display listener while held; the keep-alive tick is the net under that, for a
    // listener that hears nothing in this process. A display with no Context here reads as
    // dark, so before Shizuku 13 there is no hold, and the rear keeps HyperOS's own timeout.

    private val holdLock = Any()
    private val guestHold = GuestHold()
    private val backLock = DisplayWakeLock("AiUsage:guest-hold")
    private val backGuard = BackGuard()
    /**
     * A private guest's display held awake for as long as the back is, one lock in step with the
     * other. It has a power group of its own and so its own screen timeout (it may be shorter than
     * the hold's thirty seconds), and no input of its own but what we inject. Only for that long:
     * a lit display of any group keeps the phone out of suspend, so never for its whole life.
     */
    private val privateLock = DisplayWakeLock("AiUsage:private-display")
    private var privateLockHeld = false
    /** The last the hold's look saw of a private guest and the rear's light: chrome goes with an edge of that. Under [holdLock]. */
    private var privateLight: Pair<Int, Boolean>? = null
    @Volatile private var holdListening = false
    private val doubleTaps = GuestHold.DoubleTaps()
    /** Mirrors [GuestHold.held] for the touch path, which takes no lock. */
    @Volatile private var holdTaken = false
    @Volatile private var lastDoubleTapAt = 0L
    private val holdQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val holdHandler by lazy {
        android.os.Handler(android.os.HandlerThread("portal-hold").apply { isDaemon = true; start() }.looper)
    }
    /** The one timer. Its own Runnable, so a look's replacing it never takes a queued look. */
    private val holdCheck = Runnable { syncGuestHoldNow() }
    private val holdLook = Runnable {
        holdQueued.set(false)
        syncGuestHoldNow()
    }
    private val holdDisplays by lazy { context?.getSystemService(android.hardware.display.DisplayManager::class.java) }
    /** While there is something to look after: [syncHoldListening]. Any change posts a look, which reads the state; a refresh rate's costs one read. */
    private val holdDisplayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != rearDisplay()) return
            syncGuestHold()
            // A wake under a guest is skipped (WakeStyle.skipWhy), but read, so the edge is not lost.
            if (companion) rearLightChanged()
            chargeLitChanged()
        }
    }

    /** One touch, on the reader thread: see above. */
    private fun guestHoldTouch(e: RearTouches.Event) {
        when (e) {
            is RearTouches.Down -> if (doubleTaps.down(e.x, e.y, e.atMs)) {
                lastDoubleTapAt = android.os.SystemClock.elapsedRealtime()
                if (holdTaken) { syncGuestHold(); return }
            }
            is RearTouches.Up -> doubleTaps.up(e.x, e.y, e.atMs)
            is RearTouches.Move -> Unit
        }
        if (holdTaken || guest?.onTop != true || dozing || pocketed) return
        syncGuestHold()
    }

    /** A look, soon, on [holdHandler]. Any thread, under any lock. */
    private fun syncGuestHold() {
        if (holdQueued.compareAndSet(false, true)) holdHandler.post(holdLook)
    }

    /**
     * The look: what [GuestHold] says, done here and now. On [holdHandler], or a binder thread
     * that wants the lock gone before it goes on. Nothing it throws gets out: on the handler's
     * thread that would end the process, the sheet and the frame with it.
     */
    private fun syncGuestHoldNow() {
        try {
            synchronized(holdLock) {
                val onTop = guestOnTop()
                val display = rearDisplay()
                val now = android.os.SystemClock.elapsedRealtime()
                // No guest on top: no need to ask the display.
                val lit = if (onTop) rearLit(display) else null
                val g = guest
                val onPrivate = onTop && g?.onPrivate == true
                if (onTop) privateLit = lit != false
                val s = guestHold.step(
                    guestOnTop = onTop,
                    rearLit = lit,
                    dozing = dozing,
                    pocketed = pocketed,
                    // Its opening counts as a touch: held from the moment it is up (GuestHold.openedAt).
                    lastTouchAt = maxOf(lastRearTouchAt, guestHold.openedAt(onTop, lit, now)),
                    // With the guard on a double tap is the app's: no sleep to get out of the way of.
                    // On a private display likewise: the panel under it has the focus, and its flag.
                    doubleTapAt = if (backGuard.on || onPrivate) 0L else lastDoubleTapAt,
                    now = now,
                    appHolding = onPrivate && privateAppHolds,
                )
                when (s.act) {
                    GuestHold.Act.TAKE -> if (backLock.acquire(display)) {
                        guestHold.taken()
                        holdTaken = true
                        Log.i(TAG, "portal: holding the back lit, ${GuestHold.HOLD_MS / 1000} s past the last touch")
                    }
                    GuestHold.Act.LET_GO -> {
                        // Ran out: HyperOS's own ten seconds from here, bright and then dim, as
                        // the panel's let-go gets by ON_AFTER_RELEASE. Before the release, while
                        // the group is still up: after it the expired timeout cuts it to dark.
                        if (s.tail) backLock.poke(display)
                        letGoLocked(s.why ?: "")
                    }
                    GuestHold.Act.NONE -> Unit
                }
                // A double tap is the app's while it is on top of a lit back. Dark, the guard
                // goes: it takes the rear's touch double tap with it, and that is what wakes it.
                // Never for one on a private display: the panel's focus is its guard (PrivateDisplay).
                backGuard.set(GuestHold.guards(onTop && !onPrivate, lit))
                syncPrivateLock(if (onPrivate) g else null)
                if (onPrivate) privateLightEdge(g!!, lit)
                syncHoldListening()
                holdHandler.removeCallbacks(holdCheck)
                if (s.recheckInMs >= 0L) holdHandler.postDelayed(holdCheck, s.recheckInMs)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "portal: the guest hold's look failed", e)
        }
    }

    /** Under [holdLock]. [g]: the guest on a private display on top, if any. */
    private fun syncPrivateLock(g: Guest?) {
        val want = holdTaken && g != null
        if (want == privateLockHeld) return
        if (want) privateLockHeld = privateLock.acquire(g!!.privateDisplay)
        else { privateLock.release(); privateLockHeld = false }
    }

    /**
     * The rear going dark under a guest on a private display, or lit again, as the display listener
     * has it (nothing covers it to go by, as for one on the rear): the chrome follows, the layer and
     * the app stay as they are. Woken if its own display slept meanwhile. Under [holdLock], whose
     * calls here take only the chrome's own locks.
     */
    private fun privateLightEdge(g: Guest, lit: Boolean?) {
        if (lit == null) return
        val was = privateLight
        privateLight = g.task to lit
        if (!g.placed || was == null || was.first != g.task || was.second == lit) return
        if (lit) {
            chromeShow()
            privateDisplay?.ensureAwake()
        } else chromeHide()
    }

    /** Under [holdLock]. */
    private fun letGoLocked(why: String) {
        backLock.release()
        guestHold.released()
        holdTaken = false
        syncHoldListening()
        Log.i(TAG, "portal: let go of the back — $why")
    }

    /**
     * Under [holdLock]. The display is watched while a guest is on top, dark too: the guard has
     * to be gone the moment it goes dark, or the rear's touch double tap cannot wake it (seen
     * 2026-10-30), and back the moment it lights, or the first double tap after sleeps it.
     */
    private fun syncHoldListening() {
        val want = holdTaken || backGuard.on || guest?.onTop == true
        if (want == holdListening) return
        holdListening = want
        runCatching {
            if (want) holdDisplays?.registerDisplayListener(holdDisplayListener, holdHandler)
            else holdDisplays?.unregisterDisplayListener(holdDisplayListener)
        }
        syncLightPoll()
    }

    /**
     * Companion mode watches the rear's light on its own: the top pull's touch watch runs while
     * the rear is lit with More apps on ([touchWanted]), and Xiaomi lights and darkens it by
     * itself (raise to wake, its 10 s timeout), with no panel of ours to say so. One listener,
     * on [holdHandler]; a change costs one display read. Gone outside companion mode.
     */
    private val companionDisplayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != rearDisplay()) return
            syncTouchWatch()
            rearLightChanged()
            // The charging page that stays waits on a dark back for exactly this word (it does not poll any more).
            chargeLitChanged()
        }
    }
    private val companionListenLock = Any()
    @Volatile private var companionListening = false

    private fun syncCompanionListening() = synchronized(companionListenLock) {
        // The top pull's touch watch (More apps) and the wake animation's OFF/DOZE -> ON edge.
        // And the double tap's latch: the lit-to-dark edge, read after a double tap (ManualOff.byDoubleTap).
        val want = companion && (moreApps || wakeOn || latchWanted || noticesOn || sceneWanted())
        if (want == companionListening) return@synchronized
        companionListening = want
        runCatching {
            if (want) holdDisplays?.registerDisplayListener(companionDisplayListener, holdHandler)
            else holdDisplays?.unregisterDisplayListener(companionDisplayListener)
        }
        resetRearLight(want)
        syncTouchWatch()
        syncLightPoll()
    }


    /** The light as this clock last read it; null before its first read. On [holdHandler]. */
    private var polledLight: WakeStyle.Light? = null
    /** Whether the clock runs. On [holdHandler]. */
    private var lightPolling = false
    private val lightPoll: Runnable = object : Runnable {
        override fun run() {
            if (!lightPolling) return
            readLightNow()
            runCatching { holdHandler.postDelayed(this, LIGHT_POLL_MS) }
        }
    }
    /** One read, now (a finger has landed). */
    private val lightOnce = Runnable { if (lightPolling) readLightNow() }

    /** The clock on while a listener is wanted, off when none is. Any thread; done on [holdHandler]. */
    private fun syncLightPoll() {
        runCatching {
            holdHandler.post {
                val want = companionListening || holdListening
                if (want == lightPolling) return@post
                lightPolling = want
                holdHandler.removeCallbacks(lightPoll)
                polledLight = null
                // After [resetRearLight]'s own post, so the first read finds the baseline it set and says no edge.
                if (want) holdHandler.post(lightPoll)
            }
        }
    }

    /** On [holdHandler]. The rear's light as it is; a change since the last read is said as a listener would have. */
    private fun readLightNow() {
        try {
            val display = rearDisplay()
            val light = WakeStyle.light(rearState(display))
            if (light == polledLight) return
            val first = polledLight == null
            polledLight = light
            // As holdDisplayListener: the guest hold's look (it reads the display itself).
            if (holdListening) syncGuestHold()
            // As companionDisplayListener: the top pull's touch watch, then the light's edge.
            if (companionListening) syncTouchWatch()
            if (companion && (companionListening || holdListening) && light != rearLight) {
                if (!first) Log.i(TAG, "light: the back is ${light?.name?.lowercase() ?: "unknown"} — read; no display event came")
                rearLightChanged()
            }
            chargeLitChanged()
        } catch (e: Throwable) {
            Log.w(TAG, "light: reading the back's light failed", e)
        }
    }

    /** Unconditionally, for the end of the service. */
    private fun letGoOfBack(why: String) {
        try {
            synchronized(holdLock) {
                backGuard.set(false)
                syncPrivateLock(null)
                syncHoldListening()
                if (!holdTaken) return
                holdHandler.removeCallbacks(holdCheck)
                letGoLocked(why)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "portal: letting go of the back failed", e)
        }
    }

    /**
     * The last task a Back was invoked on since the launch began (PortalAction.vanished): its
     * leaving is the user's doing. Reset as each launch starts.
     */
    @Volatile private var backedTask = -1

    /** Backs, one at a time and in order, off the touch reader and every lock it takes. */
    private val backWorker by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> guardedThread("portal-back", r) }
    }

    /**
     * A right-edge swipe over a guest on top (the sheet down): the guest's Back, ONCE, as a
     * system back navigation (SystemBack) — a BACK key never reaches an app on the rear, and
     * Xiaomi's strip's arrives and does nothing (spec "Back on the rear"). Only the guest's:
     * when the focused window is another task's (the front's app) nothing is invoked. One
     * line logged per Back.
     *
     * ON A PRIVATE DISPLAY a key is the way, first: that skip of MIUI's is for displayId 1, and the
     * display's own focus (OWN_FOCUS) takes it to the app's window where the system navigation asks
     * after the globally focused one, which there is the panel's or the front's. Only if the key
     * cannot be sent, the system navigation, which then most likely says it is not the guest's.
     */
    private fun sendBackOnce() {
        val g = guest ?: return
        runCatching {
            backWorker.execute {
                // Still the guest it was swiped for, on top.
                val now = guest?.takeIf { it.task == g.task && it.onTop } ?: return@execute
                val pd = privateDisplay?.takeIf { now.onPrivate && it.displayId == now.privateDisplay }
                // Before the key: if it closes the app, this is all there will be of it.
                val picture = if (pd != null && !dozing) privatePicture() else null
                if (pd?.back() == true) {
                    Log.i(TAG, "portal: Back for ${now.pkg} (task ${now.task}): a key on display ${now.privateDisplay}")
                    backedTask = now.task
                    afterPrivateBack(now, picture)
                    return@execute
                }
                picture?.recycle()
                val o = SystemBack.invoke(now.task, geometry)
                val what = when {
                    o.error != null -> "failed — ${o.error}"
                    o.step == PortalAction.BackStep.INVOKE -> "the guest's callback invoked"
                    o.step == PortalAction.BackStep.NOT_THE_GUEST -> "focus is on task ${o.focusedTask}, not the guest's (the front?) — skipped"
                    o.step == PortalAction.BackStep.NO_CALLBACK -> "the guest's, with no callback — nothing invoked"
                    else -> "no back navigation given — nothing to do"
                }
                Log.i(TAG, "portal: Back ${o.type} for ${now.pkg} (task ${now.task}): $what, ${o.ms} ms")
                if (o.step == PortalAction.BackStep.INVOKE) {
                    // A Back on it may close it: a launch's survive wait must not relaunch it.
                    backedTask = now.task
                    afterBack(now, o.typeCode)
                }
            }
        }.onFailure { Log.w(TAG, "portal: Back not queued: ${it.message}") }
    }

    /**
     * After a Back invoked on [g]: at its root the app closes its task, and the rear falls
     * back to our panel — the guest is over, its frame and camera strip round nothing. That
     * used to wait for the keep-alive's next tick (two seconds, seen on the phone). Now the
     * task is looked for at PortalAction.BACK_CHECKS_MS (`am stack list`), and once it has
     * left the rear the guest ends at once, as the keep-alive would end it. A Back the
     * navigation says closes the task (PortalAction.backMayClose) fades the chrome out as it
     * is invoked, in step with the system's close. A task that stays is not proof the guest is
     * on top: at its root a launcher activity's Back moves the task behind, alive on the rear
     * under our panel. So what the rear shows on top is read (PortalAction.afterBack): the
     * guest's own window, and the chrome is back in; our panel, and the guest is behind it, as
     * Panel leaves it — no frame or strip over the panel, Resume on the sheet, no control
     * centre of ours over it.
     * On a thread of its own, so the next Back does not wait for the checks.
     */
    private fun afterBack(g: Guest, type: Int) {
        val closing = PortalAction.backMayClose(type)
        if (closing) chromeHide()
        guarded {
            val began = android.os.SystemClock.uptimeMillis()
            for (at in PortalAction.BACK_CHECKS_MS) {
                val wait = began + at - android.os.SystemClock.uptimeMillis()
                if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) { return@guarded }
                if (guest?.task != g.task) return@guarded // replaced or ended meanwhile
                if (!guestStillOnRear(g)) {
                    synchronized(placing) {
                        // Checked again under the lock: a launch since may have moved things.
                        val cur = guest
                        if (cur?.task == g.task && !guestStillOnRear(cur)) {
                            val ms = android.os.SystemClock.uptimeMillis() - began
                            if (dozing) dropGuest("its task closed on Back, $ms ms after it")
                            else endGuest("its task closed on Back, $ms ms after it")
                        }
                    }
                    return@guarded
                }
            }
            // Still there: a Back within the app, or one that moved its task behind the panel.
            val after = if (g.onPrivate) PortalAction.afterPrivateBack(GuestTask.byId(exec("am stack list"), g.task)?.visible)
                else PortalAction.afterBack(rearTop(g), g.pkg, PKG)
            when (after) {
                PortalAction.AfterBack.ON_TOP -> if (closing && guest?.let { it.task == g.task && it.onTop } == true) chromeShow()
                PortalAction.AfterBack.BEHIND -> synchronized(placing) {
                    // Under the lock: a launch or a button since may have changed the guest.
                    if (guest?.task == g.task && markOnTop(g.task, false)) {
                        Log.i(TAG, "portal: ${g.pkg} went behind the panel on Back — the guest still, behind")
                        guestBehind(g.pkg)
                    }
                }
                // Unread or another app's: the keep-alive's next tick decides; the chrome, if the
                // Back faded it, stays down meanwhile rather than come up over the wrong thing.
                PortalAction.AfterBack.UNKNOWN -> Unit
            }
        }.apply { isDaemon = true; name = "portal-back-check"; start() }
    }

    /**
     * After a Back key on a private guest, with its [picture] from just before it (null: none).
     * Most Backs are the app's own, and nothing happens here. One that closes it or puts its task
     * behind asks for it not to be visible at once, while what it draws goes on for a moment: so
     * that is watched for (TaskRead, a read, for [BACK_WATCH_MS] and no more), and as it is seen the
     * picture goes up over the layer, the guest ends or goes behind under it, and the picture slides
     * off the panel, as the app does on the rear. Without a picture the layer just goes. Where the
     * system cannot be asked, the checks every guest gets ([afterBack]).
     */
    private fun afterPrivateBack(g: Guest, picture: android.graphics.Bitmap?) {
        guarded {
            var used = false
            try {
                val began = android.os.SystemClock.uptimeMillis()
                var read: TaskRead.Read
                while (true) {
                    if (guest?.task != g.task) return@guarded
                    read = TaskRead.of(g.task)
                    if (read == TaskRead.Unread) { afterBack(g, -1); return@guarded }
                    if (read == TaskRead.Gone || (read as? TaskRead.Seen)?.visibleRequested == false) break
                    if (android.os.SystemClock.uptimeMillis() - began >= BACK_WATCH_MS) return@guarded
                    Thread.sleep(BACK_WATCH_POLL_MS)
                }
                used = true
                val cover = picture?.let { GuestCover.ofPicture(coverStack, geometry, it) }
                try {
                    // Going, and not yet gone: a moment to tell closing from moved behind.
                    val until = android.os.SystemClock.uptimeMillis() + BACK_GONE_MS
                    while (read != TaskRead.Gone && android.os.SystemClock.uptimeMillis() < until) {
                        Thread.sleep(BACK_WATCH_POLL_MS)
                        read = TaskRead.of(g.task)
                    }
                    synchronized(placing) {
                        if (guest?.task != g.task) return@guarded
                        if (read == TaskRead.Gone) {
                            val ms = android.os.SystemClock.uptimeMillis() - began
                            if (dozing) dropGuest("its task closed on Back, $ms ms after it")
                            else endGuest("its task closed on Back, $ms ms after it")
                        } else if (markOnTop(g.task, false)) {
                            Log.i(TAG, "portal: ${g.pkg} went behind on Back — the guest still, behind")
                            guestBehind(g.pkg)
                        }
                        if (cover != null) reveal(cover, "the panel", rearDisplay(), { awaitPanelLive(g) }) { panelShown(g) }
                    }
                } finally {
                    cover?.remove()
                }
            } finally {
                if (!used) picture?.recycle()
            }
        }.apply { isDaemon = true; name = "portal-private-back"; start() }
    }

    /** [g]'s task still on the rear — or on the front behind the keyguard, as Panel hides it. */
    private fun guestStillOnRear(g: Guest): Boolean {
        val at = GuestTask.byId(exec("am stack list"), g.task)?.display
        if (g.onPrivate) return at == g.privateDisplay
        return at == rearDisplay() || (at == 0 && g.hiddenOnFront)
    }

    private fun rearDisplay() = placedDisplayId.takeIf { it >= 0 } ?: Panel.DISPLAY_ID

    /** Under [sheetLock]. True: send Back to the guest. */
    private fun sheetAction(a: EdgeGestures.Action, atMs: Long): Boolean {
        when (a) {
            // Nothing to fetch as the finger lands: the sheet blurs the live rear under it.
            is EdgeGestures.Action.SheetDown -> {
                if (!sheetAlive()) return false
                // Not the end of a pending launch or button: a touch that lifts again lets the
                // shut carry on (tap → resume) and the choice runs. Only a drag drops it.
                pokePanel()
                stopAnim()
                motion.hold(atMs)
                pushPosition(atMs)
                // The pages keep sliding under a finger that has not moved yet, and the dome turning.
                if (pager.settling || dome.settling) startAnim()
                setPressed(motion.settled(atMs)?.let { at -> pressOf(sheetLayout(), a.x, a.y, at) } ?: -1)
            }
            is EdgeGestures.Action.PullStart -> if (sheetUp) {
                if (!sheetAlive()) return false
                // Caught and dragged: a pending launch or button waits for no shut that is coming.
                dropChoice()
                setPressed(-1)
                motion.grab(touchDownY, touchDownAt)
                driving = true
                shellSheet?.position(motion.drag(a.y, atMs))
            // Only a pull from the bottom band opens the sheet: a drag that began on a sheet
            // since gone (the rear dozed, the layers died) opens nothing.
            } else if (!a.onSheet) openSheet(a.y, atMs)
            is EdgeGestures.Action.PullMove -> if (driving && sheetAlive()) shellSheet?.position(motion.drag(a.y, atMs))
            is EdgeGestures.Action.PullEnd -> if (driving) {
                driving = false
                if (sheetAlive()) { motion.release(a.y, atMs); afterMotion(atMs) }
            }
            is EdgeGestures.Action.SlideStart -> if (sheetAlive()) slideStart(a.x, atMs)
            is EdgeGestures.Action.SlideMove -> if (sheetAlive()) slideMove(a.x, atMs)
            is EdgeGestures.Action.SlideEnd -> if (sheetAlive()) slideEnd(a.x, atMs)
            // The dome, turned any way: only on the open sheet at rest, its apps showing whole.
            is EdgeGestures.Action.RoamStart -> if (sheetAlive()) {
                val at = motion.settled(atMs)
                setPressed(-1)
                motion.resume(atMs)
                domeHeld = at != null && motion.heading && !sheetLocked && PortalSheet.scroll(at) < 0.5f
                if (domeHeld) {
                    dropChoice()
                    stopAnim()
                    dome.grab(touchDownX, touchDownY, touchDownAt)
                    shellSheet?.domeAt(dome.drag(a.x, a.y, atMs))
                } else afterMotion(atMs)
            }
            is EdgeGestures.Action.RoamMove -> if (domeHeld && sheetAlive()) shellSheet?.domeAt(dome.drag(a.x, a.y, atMs))
            is EdgeGestures.Action.RoamEnd -> if (domeHeld) {
                domeHeld = false
                if (sheetAlive()) { dome.release(a.x, a.y, atMs); if (dome.settling) startAnim() }
            }
            is EdgeGestures.Action.Tap -> {
                setPressed(-1)
                if (sheetAlive()) tap(a.x, a.y, atMs)
            }
            EdgeGestures.Action.Cancel -> {
                setPressed(-1)
                if (sheetAlive()) steady(atMs)
            }
            EdgeGestures.Action.Back -> {
                // Over the camera we opened. In companion mode nothing of ours covers it, so Xiaomi's own Back gesture
                // (its dark tab at the edge) reaches it and closes it: a Back of ours on top of that arrived AFTER the
                // camera had gone, at whatever had the back by then. Only with a panel of ours (not companion) is ours sent.
                if (cameraUp() && !sheetUp && guest?.onTop != true) {
                    if (companion) { Log.i(TAG, "camera close: a swipe in from the edge — left to Xiaomi's own Back"); return false }
                    closeCameraFromBack("a swipe in from the edge"); return false
                }
                return PortalAction.wantsBack(guest?.onTop == true, sheetUp)
            }
            // The control centre's, taken by it before they get here (ShellControlCenter.touch).
            is EdgeGestures.Action.NoticePullStart, is EdgeGestures.Action.NoticePullMove, is EdgeGestures.Action.NoticePullEnd,
            is EdgeGestures.Action.TopPullStart, is EdgeGestures.Action.TopPullMove, is EdgeGestures.Action.TopPullEnd -> Unit
        }
        return false
    }

    /**
     * The pull that opens the sheet: no keyguard check, nothing started — it is our own
     * layer. Not over the camera we opened ([cameraUp]: the back is the camera's then). Under
     * [sheetLock].
     */
    private fun openSheet(y: Float, atMs: Long) {
        // Over the camera we opened, the pull is the way out of it, as it is out of everything
        // else here — not nothing: with Xiaomi's edge swipe not taking it on some phones, the band
        // tap was the only way back to the clock, and nobody could know of it (a user, 2026-11-09).
        if (cameraUp()) { closeCameraFromBack("the portal's pull"); return }
        // The panel's control centre down: this pull is the swipe up closing it, which Xiaomi's
        // bottom window kept from the panel. It closes; no sheet.
        if (panelControlCenter && guest?.sheetOnTop != true) {
            runCatching { events?.onPanelCloseControlCenter() }
            return
        }
        if (!pullOpens()) { Log.i(TAG, "portal sheet: the panel is guarded (lock slider / always-on / control centre) — no sheet over it"); return }
        // Over a guest: its control centre or notifications down take the pull, not the sheet.
        if (controlCenter.isUp || notices.isUp) return
        val s = sheetOrNull() ?: return
        sheetLocked = backLockedSaid()
        refreshLockKind()
        clearLockInput()
        if (sheetLocked) pushLock(s) else s.lock(null)
        syncSheetGuest()
        motion.reset()
        motion.setDetents(PortalSheet.detents(sheetGuestOnTop), atMs)
        motion.grab(touchDownY, touchDownAt)
        motion.drag(y, atMs)
        pager.reset()
        sheetLayout().let { pager.stride = it.pageStride; pager.pages = it.pages }
        slide = PortalSheet.Swipe.NONE
        sheetUp = true
        beginPending = true
        driving = true
        gestures?.sheet = true
        dropChoice()
        pressed = -1
        s.page(0f)
        s.sliderDrag(null)
        s.position(motion.position(atMs))
        pokePanel()
        // The slider's thumb stands at the display's own density for an app without one:
        // read, if it has not been yet (a panel this service adopted, say), off this thread.
        if (sheetGuestOnTop != null && densityDefault == null) {
            runCatching { densityWorker.execute { syncDensity("the DPI slider wants the display's own") } }
        }
        beginOnClock(s)
    }

    /** The sheet brought up on [sheetClock], now that it is set to rise. Under [sheetLock]. */
    private fun beginOnClock(s: ShellSheet) {
        sheetClock.execute {
            val p = synchronized(sheetLock) { if (!sheetUp || !beginPending) return@execute; motion.position(android.os.SystemClock.uptimeMillis()) }
            val ok = runCatching { s.begin(p) }.getOrElse { Log.w(TAG, "portal sheet: begin threw: ${it.message}"); false }
            synchronized(sheetLock) {
                beginPending = false
                if (!ok && sheetUp) {
                    Log.i(TAG, "portal sheet: could not come up — the pull does nothing")
                    sheetDown()
                } else if (sheetUp) {
                    // Let go while it was coming up: the settle already began.
                    afterMotion(android.os.SystemClock.uptimeMillis())
                }
            }
        }
    }

    /**
     * The panel's lock slider, pocket lock or always-on is up (RearActivity says so through
     * the app process: [setPanelGuarded]). Over the panel a pull then opens nothing.
     */
    @Volatile private var panelGuarded = false

    /** The panel's control centre is down (IRearService.setPanelControlCenter). */
    @Volatile private var panelControlCenter = false

    // -- screenshots and recordings of the back --------------------------------------------
    //
    // The control centre's two tiles (ControlCenterLayout.SHOT, RECORD), on the panel's and on the
    // one over a guest, and three fingers held on the back (ThreeHold, as the settings choose). Made
    // here, by the shell — screencap and screenrecord of the rear's own display (its physical id, as
    // the cover's capture) — in the shell's own space, and handed to the app, which puts them in the
    // gallery (CaptureStore). The control centre goes first, so it is not in the picture; the
    // recording's pill is left out of it (RecordPill).
    //
    // A SCREENSHOT goes into DCIM/Screenshots beside his others, named as the phone names them
    // (BackShots). It is never lost to a frozen app (ShotKeeper): the app claims it first; not
    // claimed within ShotKeeper.CLAIM_MS, or not handed over at all, the shell writes it there itself
    // and has the media store index it ([shellSaveShot]). That writing, and the bin of a shot the
    // shell wrote, run on [shotSaver], never on [captureWorker]: the media store's commands take
    // seconds, and a tile or a hold meanwhile must not wait to take its picture. One thread for both,
    // so a bin always comes after the copy it deletes.

    private val captureWorker by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> guardedThread("back-capture", r) }
    }
    /** The shell's own saving of a screenshot, and its bin ([shellSaveShot], [binLastShot]): in order, off [captureWorker]. */
    private val shotSaver by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> guardedThread("back-shot-saver", r) }
    }
    /** The recording's screenrecord, and where it writes; 0 with none running. */
    @Volatile private var recordPid = 0
    @Volatile private var recordPath: String? = null
    private val recordPill by lazy { RecordPill(rearLayerStack(), geometry) }
    private val shotPolaroid by lazy { ShotPolaroid(rearLayerStack(), geometry) { runCatching { notices.lookNow() }.getOrNull() } }
    /** Who saves each screenshot: the app (claimed in time) or this shell. */
    private val shots = ShotKeeper()
    /** The last screenshot's name (BackShots.name), for the polaroid's bin. */
    @Volatile private var lastShotName: String? = null
    /** A finger on the polaroid, owned from its landing: on its bin, or dragging the card. Under [sheetLock]. */
    @Volatile private var binOwns = false
    /** That finger is one of several (fingersCrowded): its lift does nothing. Under [sheetLock]. */
    private var binCrowded = false
    private var shotDragging = false
    private var shotDownX = 0f
    private var shotLastX = 0f
    private var shotLastAt = 0L
    private var shotSpeed = 0f

    /**
     * A finger on the screenshot's polaroid (ShotPolaroid): its bin tapped bins the screenshot; the
     * card dragged right and let go goes away (the screenshot kept). True when it was the polaroid's.
     * Under [sheetLock].
     */
    private fun binTouch(e: RearTouches.Event): Boolean {
        if (!binOwns) {
            if (e !is RearTouches.Down) return false
            shotDragging = when {
                shotPolaroid.binAt(e.x, e.y) -> false
                shotPolaroid.cardAt(e.x, e.y) -> true
                else -> return false
            }
        }
        when (e) {
            is RearTouches.Down -> {
                binOwns = true
                binCrowded = false
                shotDownX = e.x; shotLastX = e.x; shotLastAt = e.atMs; shotSpeed = 0f
            }
            is RearTouches.Move -> if (shotDragging && !binCrowded) {
                val ms = (e.atMs - shotLastAt).coerceAtLeast(1L)
                shotSpeed = 0.6f * ((e.x - shotLastX) / ms) + 0.4f * shotSpeed
                shotLastX = e.x; shotLastAt = e.atMs
                shotPolaroid.drag(e.x - shotDownX)
            }
            is RearTouches.Up -> {
                binOwns = false
                if (binCrowded) binCrowded = false
                else if (shotDragging) {
                    // A finger that stopped before lifting is not flicking.
                    shotPolaroid.release(if (e.atMs - shotLastAt > 80L) 0f else shotSpeed)
                } else if (shotPolaroid.binAt(e.x, e.y)) {
                    shotPolaroid.bin()
                    binLastShot()
                }
            }
        }
        return true
    }

    /** A finger on the pill, owned from its landing: where it landed and where the pill was, and whether it moved. Under [sheetLock]. */
    @Volatile private var pillOwns = false
    /** That finger is one of several (fingersCrowded): the pill stays where it is and the lift stops nothing. Under [sheetLock]. */
    private var pillCrowded = false
    private var pillDownX = 0f
    private var pillDownY = 0f
    private var pillFrom = 0 to 0
    private var pillMoved = false

    override fun capture(record: Boolean) {
        runCatching { captureWorker.execute { if (record) toggleRecording("its tile") else screenshot("its tile") } }
    }

    /** Where captures are made: the shell's own space; each is handed to the app open ([handOver], [handOverShot]), then gone. */
    private fun captureDir(): String = "/data/local/tmp/magicrear-captures"

    /** The app has screenshot [name] in hand and asks whether to save it (ShotKeeper.claim; its Claim's code). */
    override fun claimShot(name: String?): Int {
        if (!BackShots.isName(name)) return ShotKeeper.Claim.SAVE.code
        val claim = shots.claim(name!!)
        if (claim != ShotKeeper.Claim.SAVE) Log.i(TAG, "capture: the app came for $name — ${if (claim == ShotKeeper.Claim.SCAN) "the shell has saved it already" else "nothing to do"}")
        return claim.code
    }

    private fun handOverShot(path: String, name: String) {
        val f = java.io.File(path)
        val sent = shots.handOver(name, android.os.SystemClock.uptimeMillis()) {
            runCatching {
                android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    (events ?: error("nobody to hand it to")).onShot(pfd, name)
                }
            }.onFailure { Log.w(TAG, "capture: not handed over: ${it.message}") }.isSuccess
        }
        if (!sent) {
            // Taken by the shell on its saver, after anything already queued there: binned meanwhile, nobody saves it.
            runCatching {
                shotSaver.execute { if (shots.unsent(name)) shellSaveShot(path, name, "not handed to the app", appHasIt = false) else f.delete() }
            }.onFailure { f.delete() }
            return
        }
        runCatching {
            holdHandler.postDelayed({
                runCatching {
                    shotSaver.execute {
                        if (shots.due(name, android.os.SystemClock.uptimeMillis())) shellSaveShot(path, name, "the app did not take it within ${ShotKeeper.CLAIM_MS} ms", appHasIt = true)
                        else f.delete()
                    }
                }.onFailure { f.delete() }
            }, ShotKeeper.CLAIM_MS + 20L)
        }.onFailure { f.delete() }
    }

    /**
     * The shell saves screenshot [name] itself ([why]: the app could not take it): copied into DCIM/Screenshots through
     * the FUSE mount (BackShots.SHELL_DIR), and the media store has it indexed ([indexShot]). The copy failing, a shot
     * the app holds open ([appHasIt]: handed over, not yet claimed) is the app's again (ShotKeeper.failed): it saves it
     * when it thaws. On [shotSaver].
     */
    private fun shellSaveShot(path: String, name: String, why: String, appHasIt: Boolean) {
        val dest = BackShots.shellPath(name)
        val copied = exec("mkdir -p ${BackShots.SHELL_DIR} && cp $path $dest && echo ok").endsWith("ok")
        java.io.File(path).delete()
        if (!copied) {
            // Nothing half-written left beside his screenshots.
            exec("rm -f $dest")
            if (appHasIt && shots.failed(name)) Log.w(TAG, "capture: $why, and the shell could not write $dest either — the app has it open and saves it when it comes")
            else Log.w(TAG, "capture: $why, and the shell could not write $dest either — the screenshot is lost")
            return
        }
        Log.i(TAG, "capture: $why — saved by the shell as ${BackShots.RELATIVE_PATH}/$name, ${indexShot(dest, name)}")
    }

    /**
     * [dest] in the media store, and how: the FUSE mount adds a file made through it as it is closed (Android 11 on);
     * MediaProvider's scan_file call (the one MediaScannerConnection makes, open to the shell's `content` tool) makes
     * sure; a query for its name checks; only if it is still not there, the old scanner broadcast. The app, once it
     * thaws and claims the shot, asks for a scan of it too (CaptureStore.scanShellShot). On [shotSaver].
     */
    private fun indexShot(dest: String, name: String): String {
        exec(BackShots.scanCommand(dest))
        if (BackShots.indexed(exec(BackShots.queryCommand(name)))) return "in the media store (scan_file)"
        exec(BackShots.broadcastCommand(dest))
        Thread.sleep(SCAN_WAIT_MS)
        return if (BackShots.indexed(exec(BackShots.queryCommand(name)))) "in the media store (the scanner broadcast)"
        else "not in the media store yet (the app asks again when it comes)"
    }

    /**
     * The polaroid's bin: the last screenshot gone from whoever has it, or saved by nobody if it is still waiting. The
     * shell's own file goes on [shotSaver], after the copy that made it.
     */
    private fun binLastShot() {
        val name = lastShotName
        when (name?.let { shots.bin(it) }) {
            ShotKeeper.Custodian.BINNED -> Log.i(TAG, "capture: the screenshot binned before it was saved")
            ShotKeeper.Custodian.SHELL -> runCatching {
                shotSaver.execute {
                    val dest = BackShots.shellPath(name)
                    exec("rm -f $dest")
                    // Gone through the FUSE mount is gone from the media store; the scan of a missing file makes sure.
                    exec(BackShots.scanCommand(dest))
                    Log.i(TAG, "capture: the screenshot binned (the shell's own file)")
                }
            }
            else -> {
                Log.i(TAG, "capture: the screenshot binned")
                runCatching { events?.onDeleteLastShot() }
            }
        }
    }

    /** [path] handed to the app open (it copies it to the gallery), and gone from here: an open file outlives its name. */
    private fun handOver(path: String, video: Boolean) {
        val f = java.io.File(path)
        runCatching {
            android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                events?.onCaptured(pfd, video) ?: Log.w(TAG, "capture: nobody to hand it to")
            }
        }.onFailure { Log.w(TAG, "capture: not handed over: ${it.message}") }
        f.delete()
    }

    /** The control centres out of the way of a capture, and long enough for them to have gone. On [captureWorker]. */
    private fun clearForCapture(why: String) {
        controlCenter.closeNow(why)
        runCatching { events?.onPanelCloseControlCenter() }
        Thread.sleep(CAPTURE_CLEAR_MS)
    }

    /**
     * A screenshot of the back ([why]: its tile, three fingers), into DCIM/Screenshots as BackShots names it. The control
     * centre out of the picture first ([clearFirst]; the tile's is always up). Taken before anything of it is drawn:
     * the polaroid plays from the file after. On [captureWorker].
     */
    private fun screenshot(why: String, clearFirst: Boolean = true) {
        val id = rearPhysicalId() ?: run { Log.w(TAG, "capture: no physical id for the rear"); return }
        if (clearFirst) clearForCapture("a screenshot")
        val dir = captureDir()
        val name = BackShots.name(System.currentTimeMillis())
        val path = "$dir/$name"
        val said = exec("mkdir -p $dir && screencap -d $id -p $path && echo ok")
        if (!said.endsWith("ok")) { Log.w(TAG, "capture: screencap failed: ${said.take(120)}"); return }
        Log.i(TAG, "capture: the back's screenshot made ($why)")
        lastShotName = name
        // Shown as taken (ShotPolaroid), from the file itself, before it is handed over and gone.
        runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()?.let { shotPolaroid.play(it) }
        handOverShot(path, name)
    }

    /** A recording started, or the one running stopped ([why]: its tile, three fingers). On [captureWorker]. */
    private fun toggleRecording(why: String) {
        if (recordPid > 0) { stopRecording(why); return }
        val id = rearPhysicalId() ?: run { Log.w(TAG, "capture: no physical id for the rear"); return }
        clearForCapture("a recording")
        val dir = captureDir()
        val path = "$dir/rec_${System.currentTimeMillis()}.mp4"
        // In the background, its output away (exec waits for a pipe closed), its pid back.
        val pid = exec("mkdir -p $dir && (screenrecord --display-id $id --time-limit $RECORD_SECONDS $path >/dev/null 2>&1 & echo \$!)")
            .lines().lastOrNull()?.trim()?.toIntOrNull()
        if (pid == null || pid <= 0) { Log.w(TAG, "capture: screenrecord did not start"); return }
        recordPid = pid
        recordPath = path
        recordPill.show(android.os.SystemClock.uptimeMillis())
        controlCenter.lockChanged() // its tile lights
        runCatching { events?.onRecording(true) }
        Log.i(TAG, "capture: recording the back (pid $pid, at most $RECORD_SECONDS s)")
        // Watched to its end: stopped here, by its tile or pill, or by its own time limit.
        Thread({
            while (exec("kill -0 $pid 2>/dev/null && echo up") == "up") Thread.sleep(RECORD_POLL_MS)
            finishRecording(pid)
        }, "record-watch").apply { isDaemon = true; start() }
    }

    /** The recording asked to stop: screenrecord finishes its file on an interrupt. Any thread. */
    private fun stopRecording(why: String) {
        val pid = recordPid
        if (pid <= 0) return
        Log.i(TAG, "capture: recording stopped — $why")
        exec("kill -INT $pid")
    }

    /** The recording's process gone: the pill down, the tile dark, the file to the gallery. */
    private fun finishRecording(pid: Int) {
        if (recordPid != pid) return
        recordPid = 0
        val path = recordPath
        recordPath = null
        recordPill.hide()
        controlCenter.lockChanged()
        runCatching { events?.onRecording(false) }
        // A moment for the file to be closed out on disk.
        Thread.sleep(RECORD_SETTLE_MS)
        if (path != null) handOver(path, video = true)
    }

    /**
     * A finger on the recording's pill, before anything else reads it: dragged, the pill follows;
     * tapped, the recording stops. True when it was the pill's. Under [sheetLock].
     */
    private fun pillTouch(e: RearTouches.Event): Boolean {
        if (!pillOwns && (e !is RearTouches.Down || !recordPill.contains(e.x, e.y))) return false
        when (e) {
            is RearTouches.Down -> {
                pillOwns = true
                pillCrowded = false
                pillDownX = e.x; pillDownY = e.y
                pillFrom = recordPill.position
                pillMoved = false
            }
            is RearTouches.Move -> if (!pillCrowded) {
                val dx = e.x - pillDownX
                val dy = e.y - pillDownY
                if (!pillMoved && kotlin.math.hypot(dx, dy) > EdgeGestures.SLOP) pillMoved = true
                if (pillMoved) recordPill.moveTo(pillFrom.first, pillFrom.second, dx, dy)
            }
            is RearTouches.Up -> {
                pillOwns = false
                val crowdedLift = pillCrowded
                pillCrowded = false
                // Tapped: it fades at once, as the recording is finished off behind it.
                if (!pillMoved && !crowdedLift) { recordPill.hide(); runCatching { captureWorker.execute { stopRecording("its pill") } } }
            }
        }
        return true
    }

    override fun askBackCode() {
        openLockPrompt("the panel's control centre")
    }

    override fun setPanelControlCenter(up: Boolean) {
        panelControlCenter = up
    }

    override fun setPanelGuarded(guarded: Boolean) {
        if (panelGuarded == guarded) return
        panelGuarded = guarded
        Log.i(TAG, if (guarded) "portal: the panel is guarded — no sheet over it" else "portal: the panel is open again")
    }

    /**
     * The camera we opened is (as far as we know) the rear's top app: launched and not yet
     * seen closed, or its band-tap watch running. No sheet over it — the back is the camera's.
     * Bound in time either way: never longer than a band-tap watch may run ([BAND_WATCH_MS]),
     * so a close nobody saw cannot keep the sheet away for good.
     */
    private fun cameraUp() = (bandTaps != null || cameraLaunched) &&
        android.os.SystemClock.elapsedRealtime() - cameraLaunchedAt < BAND_WATCH_MS

    private val pokeWorker by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> guardedThread("portal-poke", r) }
    }
    @Volatile private var lastPokeAt = 0L

    /**
     * The sheet up over our PANEL: its blocker keeps every touch from the panel, whose sleep
     * countdown (RearActivity.AWAKE_MS) would then run out under it — the rear dozing, the
     * sheet shut. So each opening and each touch on the sheet re-arms it, by a broadcast
     * scoped to our package, at most once a second, off the touch thread. Over a guest on
     * top nothing: the panel is behind, and its sleep there is already not the rear's.
     */
    private fun pokePanel() {
        if (sheetGuestOnTop == true) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPokeAt < POKE_EVERY_MS) return
        lastPokeAt = now
        // Through the app (IRearEvents): the panel's receiver takes only the app — see hide().
        runCatching { pokeWorker.execute { if (events?.let { runCatching { it.onPanelPoke() }.isSuccess } != true) Log.w(TAG, "portal: no app to pass the poke on — the panel's sleep not re-armed") } }
    }

    /** Whether a bottom pull may open the sheet now: [PortalAction.sheetOpens]. */
    private fun pullOpens() = PortalAction.sheetOpens(panelGuarded, guest?.sheetOnTop == true)

    /** Up, or on its way up; a sheet whose layers went is put down here. Under [sheetLock]. */
    private fun sheetAlive(): Boolean {
        if (!sheetUp) return false
        if (beginPending || shellSheet?.up == true) return true
        Log.w(TAG, "portal sheet: its layers are gone — shut")
        sheetDown()
        return false
    }

    /** The sheet's boxes as they are now: the grid, the pinned apps, the guest. */
    private fun sheetLayout() =
        // Laid out where the sheet draws it: right of the lenses it was made with.
        PortalSheet.layout(portalGrid, portalApps.size, sheetGuestOnTop, shellSheet?.lensRight ?: geometry.cutoutUnits)

    /**
     * What a finger landing at [x], [y] on the sheet at rest at [at] shows pressed: a
     * button, the slider's value, its thumb (only a finger on the thumb itself), or nothing.
     */
    private fun pressOf(layout: PortalSheet.Layout, x: Float, y: Float, at: Float) =
        if (sheetLocked) when (lockMode()) {
            BackLock.Kind.PASSWORD -> PortalSheet.kbKeyAt(PortalSheet.keyboard(shellSheet?.lensRight ?: geometry.cutoutUnits, kbPage), x, y)
            BackLock.Kind.PATTERN -> -1
            else -> PortalSheet.keyAt(keypadNow(), x, y)
        }.let { if (it < 0) -1 else PortalSheetPainter.PRESS_KEY + it }
        else when (val hit = PortalSheet.hit(layout, x, y, PortalSheet.scroll(at), pager.position(android.os.SystemClock.uptimeMillis()))) {
            is PortalSheet.Hit.Button -> hit.index
            PortalSheet.Hit.SliderValue -> PortalSheetPainter.PRESS_VALUE
            PortalSheet.Hit.WidthValue -> PortalSheetPainter.PRESS_WIDTH_VALUE
            PortalSheet.Hit.Width -> {
                val track = layout.width?.track
                val pkg = sliderGuest()
                if (track != null && pkg != null && kotlin.math.abs(x - PortalSheet.widthX(track, geometry, portalWidth[pkg])) <= PortalSheet.THUMB / 2) PortalSheetPainter.PRESS_WIDTH_THUMB else -1
            }
            PortalSheet.Hit.Toggle -> PortalSheetPainter.PRESS_TOGGLE
            PortalSheet.Hit.Slider -> {
                val track = layout.slider?.track
                val pkg = sliderGuest()
                if (track != null && pkg != null && PortalSheet.onThumb(track, thumbOf(pkg), x)) PortalSheetPainter.PRESS_THUMB else -1
            }
            else -> -1
        }

    private fun setPressed(i: Int) {
        if (pressed == i) return
        pressed = i
        shellSheet?.press(i)
    }

    /** A sheet a finger held but did not move, let go: on to where it was going, or still where it stands. Under [sheetLock]. */
    private fun steady(atMs: Long) {
        if (motion.phase == SheetMotion.Phase.HELD) { motion.resume(atMs); afterMotion(atMs) }
    }

    /**
     * A tap: on the settled sheet a choice — the app (its launch once the sheet is down),
     * a button ([onSheetButton]), a page's dot, the slider's track off its thumb (the DPI
     * there, applied; on the thumb nothing — one step is a few pixels) or its value
     * (Default), and off the sheet's boxes a shut; on a sheet still moving, it carries on
     * where it was going. Under [sheetLock].
     */
    private fun tap(x: Float, y: Float, atMs: Long) {
        val at = motion.settled(atMs)
        if (at == null || !motion.heading) { motion.resume(atMs); afterMotion(atMs); return }
        if (sheetLocked) { lockTap(x, y, atMs); return }
        // The lock is still leaving: what is under it is not there to tap yet.
        if (atMs < unlockingUntil) { steady(atMs); return }
        val apps = portalApps
        val onTop = sheetGuestOnTop
        val layout = sheetLayout()
        val flat = PortalSheet.hit(layout, x, y, PortalSheet.scroll(at), pager.position(atMs))
        // On the dome the apps are its tiles, where the grid's cells would have been or not.
        val onDome = portalDome && PortalSheet.scroll(at) < 0.5f && PortalDome.area(layout).contains(x, y)
        if (onDome && dome.settling) { shellSheet?.domeAt(dome.stop(atMs)); steady(atMs); return }
        // The sheet's own buttons stand over the dome, and are themselves.
        val hit = if (!onDome || flat is PortalSheet.Hit.Button) flat else
            PortalDome.hit(PortalDome.tiles(PortalDome.area(layout), layout.count, dome.pose(atMs)), x, y)?.let { PortalSheet.Hit.App(it) } ?: PortalSheet.Hit.Blank
        when (hit) {
            is PortalSheet.Hit.App -> {
                // Still gliding: this finger stopped the apps where they are, and opens nothing —
                // the cell under it is not where it was when the finger set off.
                if (pager.settling) { shellSheet?.page(pager.stop(atMs)); steady(atMs); return }
                val pkg = apps.packages.getOrNull(hit.index)
                // The guest's own icon opens nothing new: see PortalAction.appTap.
                val tapped = pkg?.let { PortalAction.appTap(it, sliderGuest(), onTop) }
                when (tapped) {
                    PortalAction.AppTap.SHUT -> { Log.i(TAG, "portal sheet: $pkg is the guest already — only shut"); shutSheet(atMs, null) }
                    PortalAction.AppTap.RESUME -> { Log.i(TAG, "portal sheet: $pkg is the guest, behind — Resume"); shutSheet(atMs) { onSheetButton(0, false) } }
                    else -> {
                        // The curtain comes up now, under the sheet as it goes down: the back stays
                        // blurred from the sheet into the change of app, not cleared and blurred
                        // again (the owner, 2026-11-10). Let fall when the launch is done — or
                        // when the choice is dropped (dropChoice).
                        if (pkg != null) { runCatching { curtain.rise() }; curtainHeld = true }
                        shutSheet(atMs, pkg?.let { p -> { try { launchGuest(p, rearDisplay()) } finally { releaseCurtain() }; Unit } })
                    }
                }
            }
            is PortalSheet.Hit.Button -> {
                // The frame stays as hidden as the open sheet has it while the sheet shuts:
                // the choice may fade it out, and it must not come back up in between.
                frame.holdVeil()
                // Before the shut, which may hand the choice over at once (sheetDown).
                veilHeld = true
                // And the curtain up now, under the sheet as it goes down, as for a launch: the back
                // stays blurred from the sheet into Panel, Front, Close or Resume, not cleared
                // and blurred again (the owner, 2026-11-10).
                runCatching { curtain.rise() }; curtainHeld = true
                shutSheet(atMs) { try { onSheetButton(hit.index, onTop ?: false) } finally { frame.releaseVeil(); releaseCurtain() } }
            }
            is PortalSheet.Hit.Page -> {
                steady(atMs)
                pager.show(hit.index, atMs)
                startAnim()
            }
            PortalSheet.Hit.Slider -> {
                steady(atMs)
                val pkg = sliderGuest() ?: return
                val track = layout.slider?.track ?: return
                val now = RearDensity.clamp(thumbOf(pkg))
                if (PortalSheet.onThumb(track, now, x)) return
                val dpi = PortalSheet.sliderDpi(track, x)
                if (dpi != now) setAppDpi(pkg, dpi)
            }
            PortalSheet.Hit.SliderValue -> {
                steady(atMs)
                val pkg = sliderGuest() ?: return
                if (portalDpi.containsKey(pkg)) setAppDpi(pkg, null)
            }
            // A tap on the width's track: that width, its box flashed; on its value: the whole.
            PortalSheet.Hit.Width -> {
                steady(atMs)
                val pkg = sliderGuest() ?: return
                val track = layout.width?.track ?: return
                val now = portalWidth[pkg]
                if (kotlin.math.abs(x - PortalSheet.widthX(track, geometry, now)) <= PortalSheet.THUMB / 2) return
                val w = PortalSheet.widthAt(track, geometry, x)
                if (w != GuestWidth.clamp(geometry, now)) { setAppWidth(pkg, w); flashWidth(w) }
            }
            PortalSheet.Hit.WidthValue -> {
                steady(atMs)
                val pkg = sliderGuest() ?: return
                if (portalWidth.containsKey(pkg)) { setAppWidth(pkg, null); flashWidth(null) }
            }
            // Shown switched at once, saved through the app, and the app relaunched in its new
            // place when the sheet is down: not under it, whose picture a cover would be of.
            PortalSheet.Hit.Toggle -> {
                val pkg = sliderGuest()
                if (pkg == null) steady(atMs)
                else {
                    // As the switch shows it. Off, one here only for this open is off for good:
                    // saved off, and the whole rear is tried, with no going back behind the lock.
                    val on = !(guest?.let { keepsRight(it) } ?: (pkg in portalConfined))
                    setAppConfined(pkg, on)
                    shellSheet?.keepRight(on)
                    shutSheet(atMs) {
                        synchronized(placing) {
                            if (!on) {
                                wholeRearAsked = true
                                guest?.takeIf { it.pkg == pkg && it.lockRetried }?.let { guest = it.copy(lockRetried = false) }
                            }
                            relaunchIfMoved("switched on the back")
                            wholeRearAsked = false
                        }
                    }
                }
            }
            PortalSheet.Hit.Blank -> steady(atMs)
            PortalSheet.Hit.Outside -> shutSheet(atMs, null)
        }
    }

    /** The guest — on top or behind the panel — whose DPI the slider is: null without one. */
    private fun sliderGuest(): String? = guest?.takeIf { it.sheetOnTop != null }?.pkg

    /** The thumb's DPI for [pkg] as it stands: its own, else the display's as read, else as its geometry says. */
    private fun thumbOf(pkg: String) = portalDpi[pkg] ?: sliderDefault() ?: geometry.dpi

    /**
     * A sideways drag begins: on the settled sheet's slider row it drags the slider's thumb,
     * above it it turns the pages (PortalSheet.swipe), else nothing.
     * The sheet itself stays where it is either way. Under [sheetLock].
     */
    private fun slideStart(x: Float, atMs: Long) {
        setPressed(-1)
        val at = motion.settled(atMs)
        steady(atMs)
        val layout = sheetLayout()
        slide = if (at == null || !motion.heading || sheetLocked) PortalSheet.Swipe.NONE else PortalSheet.swipe(layout, touchDownY, PortalSheet.scroll(at))
        // The dome has no pages to slide: a sideways drag on the sheet's own strips moves nothing.
        if (portalDome && slide == PortalSheet.Swipe.PAGES) slide = PortalSheet.Swipe.NONE
        when (slide) {
            PortalSheet.Swipe.SLIDER -> {
                val pkg = sliderGuest()
                val track = layout.slider?.track
                if (pkg == null || track == null) { slide = PortalSheet.Swipe.NONE; return }
                sliderPkg = pkg
                sliderFrom = thumbOf(pkg)
                sliderAt = PortalSheet.sliderDrag(track, sliderFrom, x - touchDownX)
                setPressed(PortalSheetPainter.PRESS_THUMB)
                shellSheet?.sliderDrag(sliderAt)
            }
            PortalSheet.Swipe.WIDTH -> {
                val pkg = sliderGuest()
                val track = layout.width?.track
                if (pkg == null || track == null) { slide = PortalSheet.Swipe.NONE; return }
                widthPkg = pkg
                widthFrom = GuestWidth.clamp(geometry, portalWidth[pkg])
                widthAt = PortalSheet.widthDrag(track, geometry, widthFrom, x - touchDownX)
                setPressed(PortalSheetPainter.PRESS_WIDTH_THUMB)
                shellSheet?.widthDrag(widthAt, dragging = true)
            }
            PortalSheet.Swipe.PAGES -> {
                stopAnim()
                pager.grab(touchDownX, touchDownAt)
                shellSheet?.page(pager.drag(x, atMs))
            }
            PortalSheet.Swipe.NONE -> Unit
        }
    }

    /** The slide's finger at [x]: the thumb or the pages follow. Under [sheetLock]. */
    private fun slideMove(x: Float, atMs: Long) {
        when (slide) {
            PortalSheet.Swipe.SLIDER -> {
                val track = sheetLayout().slider?.track ?: return
                val dpi = PortalSheet.sliderDrag(track, sliderFrom, x - touchDownX)
                // Drawn only: each DPI change relayouts the whole display, so none is applied mid-drag.
                if (dpi != sliderAt) { sliderAt = dpi; shellSheet?.sliderDrag(dpi) }
            }
            PortalSheet.Swipe.WIDTH -> {
                val track = sheetLayout().width?.track ?: return
                val w = PortalSheet.widthDrag(track, geometry, widthFrom, x - touchDownX)
                if (w != widthAt) { widthAt = w; shellSheet?.widthDrag(w, dragging = true) }
            }
            PortalSheet.Swipe.PAGES -> shellSheet?.page(pager.drag(x, atMs))
            PortalSheet.Swipe.NONE -> Unit
        }
    }

    /** The slide let go at [x]: the slider's value applied, or the pages settling. Under [sheetLock]. */
    private fun slideEnd(x: Float, atMs: Long) {
        when (slide) {
            PortalSheet.Swipe.SLIDER -> {
                slideMove(x, atMs)
                setPressed(-1)
                val pkg = sliderPkg
                // Still the guest it was dragged for, and the thumb moved from where the drag
                // took it up: applied, once, now. Measured from there, not from the value as
                // it stands — a display default read mid-drag, or one off the slider's steps,
                // must not apply a value nobody chose.
                if (pkg != null && pkg == sliderGuest() && sliderAt != RearDensity.clamp(sliderFrom)) setAppDpi(pkg, sliderAt)
                shellSheet?.sliderDrag(null)
                sliderPkg = null
            }
            PortalSheet.Swipe.WIDTH -> {
                slideMove(x, atMs)
                setPressed(-1)
                val pkg = widthPkg
                if (pkg != null && pkg == sliderGuest() && widthAt != widthFrom) setAppWidth(pkg, widthAt)
                shellSheet?.widthDrag(widthAt, dragging = false)
                widthPkg = null
            }
            PortalSheet.Swipe.PAGES -> {
                pager.release(x, atMs)
                startAnim()
            }
            PortalSheet.Swipe.NONE -> Unit
        }
        slide = PortalSheet.Swipe.NONE
    }

    /** Settles it shut, then [then] on a thread of its own. Under [sheetLock]. */
    private fun shutSheet(atMs: Long, then: (() -> Unit)?) {
        motion.settle(0f, atMs)
        afterShut = then
        afterMotion(atMs)
    }

    /**
     * A button's choice, run once the sheet is down, on a thread of its own. [index] 0 is
     * Panel when the guest was on top ([guestOnTop], as the sheet showed it), else Resume;
     * 1 Front; 2 Close. Each is a [PortalAction.Step] (spec "Settled on the device" 1):
     *
     * - Panel, front locked (`keyguardShowing()`): `am display move-stack <guest> 0`. The
     *   guest goes behind the keyguard, unseen and not relaunched (35 ms measured), which
     *   leaves our panel on top of the rear; the rear is woken. HyperOS refuses `am start` of
     *   the panel while the front is locked, so this is the only way. The guest is then
     *   [Guest.hiddenOnFront]: still the guest, behind (see [GuestGate]).
     * - Panel, front unlocked: `am start --display <rear>` of the panel, over the guest.
     * - Resume of a hidden guest: `move-stack <guest> <rear>` and a wake ([unhide]).
     * - Resume of a guest behind the panel on the rear: the panel moves its own task to the
     *   back (RearActivity.ACTION_PORTAL_STEP_BACK, passed on by the app), locked or not. Only if the
     *   panel is still what the rear shows on top after [STEP_BACK_LIMIT_MS] (looked at
     *   every [STEP_BACK_POLL_MS]; nothing read is trusted as done): `move-stack <guest> 0` and
     *   back to the rear (a move to the display a task is already on does not raise it) —
     *   seen on the front for a moment when it is unlocked.
     *
     * Which of Panel / Resume runs is decided from the guest as it is when the button runs,
     * not as the sheet showed it — and when the guest is already where the button would
     * have put it, neither does; a refused `am start` of the panel changes nothing.
     * - Front: `move-stack <guest> 0`, Close: its task removed as Recents does — either way the guest ends and
     *   the panel comes back ([guestAction]).
     * - On a private display: Panel and Resume only hide and show our layers (HIDE_LAYERS,
     *   SHOW_LAYERS), the app alive on its display; Front and Close are the same, and its display
     *   goes when the guest ends — after the move, so that it is not destroyed with it.
     *
     * The guest's DPI stays through Panel and Resume (kept while the guest lives); the frame
     * follows whether it is on top.
     */
    private fun onSheetButton(index: Int, guestOnTop: Boolean) {
        // Decided from the guest as it is now, not as the sheet showed it when it opened.
        val step = synchronized(placing) {
            val g = guest ?: return
            // Panel (shown over a guest on top) or Resume (shown over one behind), and the
            // guest has since gone where the button would have put it: nothing left to do.
            // Running the other one instead would undo what was asked for.
            if (index == 0 && g.onTop != guestOnTop) {
                Log.i(TAG, "portal sheet: ${if (guestOnTop) "Panel" else "Resume"} — the guest is ${if (g.onTop) "on top" else "behind"} already; nothing to do")
                return
            }
            PortalAction.strip(index, g.onTop, g.hiddenOnFront, g.onPrivate) { keyguardShowing() }
        } ?: return
        Log.i(TAG, "portal sheet: $step")
        when (step) {
            // Each change of what is on top of the rear but Close happens under a cover of
            // the rear as it was, slid away once the new top has drawn (covered). Close keeps
            // the system's own close animation, which already plays for it.
            PortalAction.Step.HIDE_ON_FRONT -> synchronized(placing) {
                val g = guest ?: return
                covered("the panel", { panelShown(g) }) {
                    // Refused, it is still on top, and the keep-alive goes on seeing it there.
                    if (!moveToDisplay(g.task, 0)) return@covered false
                    guest = g.copy(onTop = false, hiddenOnFront = true)
                    guestBehind(g.pkg)
                    wake(rearDisplay())
                    true
                }
            }
            PortalAction.Step.START_PANEL -> synchronized(placing) {
                val g = guest ?: return
                covered("the panel", { panelShown(g) }) {
                    // Refused (the front locked in between, say): the guest is still on top, and
                    // its state and frame stay as they are.
                    if (!startPanel("Panel")) return@covered false
                    markOnTop(g.task, false)
                    guestBehind(g.pkg)
                    true
                }
            }
            // Nothing moves: the panel is under the layers, and comes out from under them.
            PortalAction.Step.HIDE_LAYERS -> synchronized(placing) {
                val g = guest ?: return
                covered("the panel", { panelShown(g) }, { awaitPanelLive(g) }) {
                    markOnTop(g.task, false)
                    guestBehind(g.pkg)
                    true
                }
            }
            PortalAction.Step.SHOW_LAYERS -> synchronized(placing) {
                val g = guest ?: return
                val area = privateArea()
                val back = covered(g.pkg, { drawnOn(g.pkg, g.task, g.privateDisplay, area) }, { stripFor() }) {
                    // Back at its root may have put its task behind on its display: started again.
                    if (GuestTask.byId(exec("am stack list"), g.task)?.visible == false) {
                        launcherOf(g.pkg)?.let { exec("am start --display ${g.privateDisplay} -n $it") }
                    }
                    privateDisplay?.ensureAwake()
                    guestOnTopAgain(g, "Resume", showFrame = false)
                    true
                }
                if (back) {
                    if (guest?.let { it.task == g.task && it.onTop } == true) chromeShow()
                    wake(rearDisplay())
                }
            }
            PortalAction.Step.UNHIDE -> synchronized(placing) {
                val g = guest ?: return
                // The frame after the reveal, not before the guest is seen.
                val back = covered(g.pkg, { drawnOn(g.pkg, g.task, rearDisplay()) }, { stripFor() }) {
                    unhide("Resume", showFrame = false)
                }
                if (back) {
                    if (guest?.let { it.task == g.task && it.onTop } == true) chromeShow()
                    wake(rearDisplay())
                }
            }
            PortalAction.Step.PANEL_STEPS_BACK -> synchronized(placing) {
                val g = guest ?: return
                val back = covered(g.pkg, { drawnOn(g.pkg, g.task, rearDisplay()) }, { stripFor() }) changed@{
                    // The panel moves its own task to the back (RearActivity.ACTION_PORTAL_STEP_BACK):
                    // the guest is on top without leaving the rear, locked or not.
                    // Through the app (IRearEvents): the panel's receiver takes only the app.
                    if (events?.let { runCatching { it.onPanelStepBack() }.isSuccess } != true)
                        Log.w(TAG, "portal: no app to pass the step back on — the guest stays behind the panel")
                    // Watched until the guest is on top, up to STEP_BACK_LIMIT_MS: the broadcast
                    // reaches the panel when it does, and one look after a fixed wait took a slow
                    // step back for none.
                    val until = android.os.SystemClock.elapsedRealtime() + STEP_BACK_LIMIT_MS
                    var top: String?
                    do {
                        try { Thread.sleep(STEP_BACK_POLL_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@changed false }
                        top = rearTop(g)
                    } while (top != g.pkg && android.os.SystemClock.elapsedRealtime() < until)
                    val now = guest?.takeIf { it.task == g.task } ?: return@changed false
                    // A guard come up meanwhile is why the panel stayed (RearActivity refuses the
                    // step back then); the fallback would take the guest past it.
                    if (top == PKG && panelGuarded) {
                        Log.i(TAG, "portal: the panel is guarded — ${g.pkg} stays behind it")
                        return@changed false
                    }
                    // Only the panel itself still on top is a step back that did not happen. Nothing
                    // read (a dark rear composes nothing) or something else is no proof against
                    // it, and the fallback is seen on the front.
                    if (top == PKG) {
                        // The fallback: out to display 0 and back, since a move to the display a
                        // task is already on does not raise it. Seen on the front for a moment
                        // when it is unlocked.
                        Log.i(TAG, "portal: the panel did not step back — ${g.pkg} through the front")
                        moveToDisplay(g.task, 0)
                        if (!moveToDisplay(g.task, rearDisplay())) return@changed false
                        shapeGuest(g.task, g.pkg, "back from the front")
                    } else if (top != g.pkg) {
                        Log.i(TAG, "portal: after the step back the rear's top is ${top ?: "unknown"} — trusting it")
                    }
                    // The frame after the reveal, not before the guest is seen.
                    guestOnTopAgain(now, "Resume", showFrame = false)
                    true
                }
                if (back) {
                    if (guest?.let { it.task == g.task && it.onTop } == true) chromeShow()
                    wake(rearDisplay())
                }
            }
            // Moving a task between displays plays no animation: the guest used to vanish from
            // the rear at once. Covered, and the panel revealed as Panel reveals it.
            PortalAction.Step.FRONT -> synchronized(placing) {
                val g = guest ?: return
                covered("the panel", { panelShown(g) }, { awaitPanelLive(g) }) {
                    guestAction("front")
                    guest == null
                }
            }
            // On a private display there is no close to see, only a layer gone: covered too.
            PortalAction.Step.CLOSE -> if (guest?.onPrivate != true) guestAction("close") else synchronized(placing) {
                val g = guest ?: return
                covered("the panel", { panelShown(g) }, { awaitPanelLive(g) }) {
                    guestAction("close")
                    guest == null
                }
            }
        }
    }

    /** The guest no longer on top: no frame, and the sheet told. Its DPI stays. Under [placing]. */
    private fun guestBehind(pkg: String) {
        chromeHide()
        syncDensity("$pkg is behind")
        syncSheetGuest()
    }

    /**
     * [g] on top of the rear again, not hidden: frame, density, touches, the sheet's buttons.
     * The frame not yet with [showFrame] false: a covered Resume shows it after its reveal.
     * Under [placing].
     */
    private fun guestOnTopAgain(g: Guest, why: String, showFrame: Boolean = true) {
        Log.i(TAG, "portal: ${g.pkg} on top again — $why")
        guest = g.copy(onTop = true, hiddenOnFront = false)
        syncDensity("${g.pkg} is on top")
        if (showFrame) chromeShow()
        syncTouchWatch()
        syncSheetGuest()
    }

    /**
     * A guest Panel hid on display 0 moved back onto the rear, on top. False when there is
     * no hidden guest or the move was refused. Under [placing].
     */
    private fun unhide(why: String, showFrame: Boolean = true): Boolean {
        val g = guest?.takeIf { it.hiddenOnFront } ?: return false
        if (!moveToDisplay(g.task, rearDisplay())) return false
        shapeGuest(g.task, g.pkg, "back from the front")
        guestOnTopAgain(g, "back from the front: $why", showFrame)
        return true
    }

    /**
     * Our panel started over whatever is on the rear, with the last payload as show() starts
     * it; false (logged) when `am start` says it was refused, or no panel task is there after
     * it — HyperOS aborts a start under the keyguard without a word (see [show]). Under [placing].
     */
    private fun startPanel(why: String): Boolean {
        val out = exec("am start --display ${rearDisplay()} -n $PKG/$ACTIVITY --es state ${shellQuote(lastPayload)}")
        if (out.contains("Error", ignoreCase = true) || out.contains("Exception", ignoreCase = true)) {
            Log.w(TAG, "portal: $why — the panel's start was refused: ${out.take(200)}")
            return false
        }
        if (pollTaskId() <= 0) {
            Log.w(TAG, "portal: $why — no panel task after its start; taken as refused")
            return false
        }
        return true
    }

    /**
     * The package the rear composes on top (SurfaceFlinger, as the keep-alive reads it), with
     * a window of [g]'s task read as [g]'s own package; null when nothing is read.
     */
    private fun rearTop(g: Guest): String? {
        val entry = GuestTask.byId(exec("am stack list"), g.task)
        val taskTop = entry?.top?.substringBefore('/')?.takeIf { it.isNotEmpty() }
        val rear = rearPhysicalId()
        return SurfaceDump.topPackage(
            exec("dumpsys SurfaceFlinger"),
            isRear = { id -> if (rear != null) id == rear else id.endsWith(REAR_PHYSICAL_ID_SUFFIX) },
            known = listOfNotNull(g.pkg, PKG, taskTop),
        ).let { if (it != null && it == taskTop) g.pkg else it }
    }

    /**
     * The front unlocked with a guest hidden on it: the guest is showing there now. It goes
     * back to the rear, and — since Panel is what put it on the front — behind the panel:
     * moved to the rear, then the panel started over it, finishing as Panel does unlocked
     * (START_PANEL). The rear is left as it is (lit or not). The user unlocked the front, so
     * the guest no longer has a keyguard to hide behind; ending it instead would lose the
     * app's place. KNOWN, AND INHERENT: between the unlock and USER_PRESENT reaching us
     * (or the next keep-alive tick) the guest is seen on the front, and for the moment
     * between its move and the panel's start it is on top of the rear.
     * From the keep-alive (GUEST_EXPOSED) and from [ensureOnRear], which the app process runs
     * on USER_PRESENT, so it is back within a moment of the unlock. Under [placing].
     */
    private fun unhideIfExposed(why: String) {
        if (guest?.hiddenOnFront != true || keyguardShowing()) return
        val g = guest ?: return
        // Already back on the rear (somebody moved it): only the flag was stale.
        if (GuestTask.byId(exec("am stack list"), g.task)?.display != 0) {
            guestRef.compareAndSet(g, g.copy(hiddenOnFront = false))
            return
        }
        if (!moveToDisplay(g.task, rearDisplay())) return
        shapeGuest(g.task, g.pkg, "back from the front")
        if (!startPanel("back from the front: $why")) {
            // The panel refused: the guest is on top of the rear, and is shown as such.
            guestOnTopAgain(g, "back from the front: $why")
            return
        }
        Log.i(TAG, "portal: ${g.pkg} back from the front ($why), behind the panel")
        guest = g.copy(onTop = false, hiddenOnFront = false)
        guestBehind(g.pkg)
    }

    // -- DPI from the back ----------------------------------------------------------------
    //
    // While there is a guest, the open sheet scrolls on to its DPI slider (PortalSheet.layout):
    // dragged, it only shows the value; let go, tapped on its track, or its value tapped
    // (Default), the value is applied at once — the guest relayouts — and saved: the config
    // is the app process's to write, so the value goes there through IRearEvents once
    // changes pause ([DpiEdits]), and comes back with the next payload.

    /** Guards [dpiEdits] and the writes to [portalDpi]. A leaf, taken under [sheetLock] or alone. */
    private val dpiLock = Any()
    private val dpiEdits = DpiEdits()
    /** The app process's ear (setEvents); null until it registers, or after it died. */
    @Volatile private var events: IRearEvents? = null

    override fun setEvents(events: IRearEvents?) {
        this.events = events
        // Changes made while nobody listened go now.
        if (events != null) {
            scheduleDpiFlush()
            scheduleWidthFlush()
            syncBackLock(force = true)
            flushConfined()
            if (panelCovered) tellPanelCovered()
            toldOrder?.let { o -> runCatching { events.onPinsOrdered(o.toLongArray()) } }
            // The face as the shell has it: a choice made on the back while the app was not there to be told (frozen,
            // restarted, its data cleared) is the app's too, so its settings show the face the back shows.
            if (facesStarted && faceSeq > 0L) { val k = faceKey; val n = faceSeq; runCatching { events.onFaceChosen(k, n) } }
        }
    }

    /** [portalDpi] set to what [make] returns (under [dpiLock]), and the sheet told. Any thread. */
    private fun setPortalDpi(make: () -> Map<String, Int>) {
        synchronized(dpiLock) {
            portalDpi = make()
            shellSheet?.dpiValues(portalDpi)
        }
    }

    /** [pkg]'s DPI set to [dpi] (null: Default) from the back. Under [sheetLock], on the reader thread: nothing here waits. */
    private fun setAppDpi(pkg: String, dpi: Int?) {
        setPortalDpi {
            dpiEdits.put(pkg, dpi, android.os.SystemClock.uptimeMillis())
            if (dpi == null) portalDpi - pkg else portalDpi + (pkg to dpi)
        }
        Log.i(TAG, "portal sheet: $pkg's DPI ${dpi ?: "back to default"} from the back")
        // Live if it is the guest. `wm density` takes a moment: off this thread, one at a
        // time; each run reads the latest map, so a quick run of changes ends at the last one.
        if (guest?.pkg == pkg) runCatching { densityWorker.execute { syncDensity("$pkg set on the back") } }
        scheduleDpiFlush()
        // Apps that read their size at start only take it fresh: restarted once the sheet is down.
        if (guest?.pkg == pkg) restartAfterSheet = pkg
    }

    /** [portalWidth] set to what [make] returns (under [dpiLock]), and the sheet told. Any thread. */
    private fun setPortalWidth(make: () -> Map<String, Int>) {
        synchronized(dpiLock) {
            portalWidth = make()
            shellSheet?.widthValues(portalWidth)
        }
    }

    /**
     * [pkg]'s width set to [w] (null: the whole) from the back. Saved through the app as a DPI is;
     * the app moves into it when the sheet is down ([sheetDown]), not under it. A width is a place
     * right of the cameras, so an app not kept there is from now on. Under [sheetLock], on the reader.
     */
    private fun setAppWidth(pkg: String, w: Int?) {
        setPortalWidth {
            widthEdits.put(pkg, w, android.os.SystemClock.uptimeMillis())
            if (w == null) portalWidth - pkg else portalWidth + (pkg to w)
        }
        Log.i(TAG, "portal sheet: $pkg ${w?.let { "$it px wide" } ?: "the whole width"} from the back")
        if (w != null && guest?.takeIf { it.pkg == pkg }?.let { keepsRight(it) } == false) {
            setAppConfined(pkg, true)
            shellSheet?.keepRight(true)
        }
        scheduleWidthFlush()
        // Restarted in it when the sheet is down: not under the sheet, whose picture the cover would be.
        if (guest?.pkg == pkg) restartAfterSheet = pkg
    }

    /** The width's box shown a moment for a tap, as a drag shows it. */
    private fun flashWidth(w: Int?) {
        val s = shellSheet ?: return
        s.widthDrag(w, dragging = true)
        runCatching { sheetClock.schedule({ s.widthDrag(null, dragging = false) }, WIDTH_FLASH_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    /** Guarded by [dpiLock], as [dpiEdits]: the widths set on the back until a payload carries them. */
    private val widthEdits = DpiEdits()

    /** [flushWidth] when the next width falls due. Any thread. */
    private fun scheduleWidthFlush() {
        val at = synchronized(dpiLock) { widthEdits.nextDueAt() } ?: return
        val wait = (at - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)
        runCatching { sheetClock.schedule({ flushWidth() }, wait, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    /** The widths that have fallen due handed to the app process, as [flushDpi] does the DPIs. */
    private fun flushWidth(all: Boolean = false) {
        if (events == null) return
        val due = synchronized(dpiLock) { widthEdits.due(if (all) Long.MAX_VALUE else android.os.SystemClock.uptimeMillis()) }
        for ((pkg, w) in due) {
            val ev = events
            if (ev == null) { synchronized(dpiLock) { widthEdits.unsend(pkg, w) }; continue }
            runCatching { ev.onPortalWidth(pkg, w ?: 0) }.onFailure {
                Log.w(TAG, "portal: width for $pkg not handed on: ${it.message}")
                if (it is android.os.DeadObjectException) {
                    synchronized(dpiLock) { widthEdits.unsend(pkg, w) }
                    if (events === ev) events = null
                }
            }
        }
        if (!all) scheduleWidthFlush()
    }

    /** [pkg]'s "Keep right of the cameras" set from the back. Under [sheetLock], on the reader: nothing here waits. */
    private fun setAppConfined(pkg: String, on: Boolean) {
        synchronized(dpiLock) {
            confinedEdits.put(pkg, on, android.os.SystemClock.uptimeMillis())
            portalConfined = if (on) portalConfined + pkg else portalConfined - pkg
        }
        if (on) wholeAnyway.remove(pkg) else wantedWhole(pkg)
        Log.i(TAG, "portal sheet: $pkg ${if (on) "kept right of the cameras" else "on the whole rear"}, from the back")
        syncSheetKeepRight()
        flushConfined()
    }

    /**
     * The switches made on the back handed to the app, off the caller's thread. With nobody
     * listening they wait for [setEvents]; one the listener died under is due again, as a DPI's.
     */
    private fun flushConfined() {
        runCatching {
            pokeWorker.execute {
                val ev = events ?: return@execute
                val due = synchronized(dpiLock) { confinedEdits.due(android.os.SystemClock.uptimeMillis()) }
                for ((pkg, on) in due) runCatching { ev.onPortalConfined(pkg, on) }.onFailure {
                    Log.w(TAG, "portal: camera setting for $pkg not handed on: ${it.message}")
                    if (it is android.os.DeadObjectException) synchronized(dpiLock) { confinedEdits.unsend(pkg, on) }
                }
            }
        }
    }

    private val densityWorker by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> guardedThread("portal-density", r) }
    }

    /** A [flushDpi] when the next edit falls due. Any thread. */
    private fun scheduleDpiFlush() {
        val at = synchronized(dpiLock) { dpiEdits.nextDueAt() } ?: return
        val wait = (at - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)
        runCatching { sheetClock.schedule({ flushDpi() }, wait, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    /**
     * The edits that have fallen due ([all]: every one, as the service goes) handed to the
     * app process. With nobody listening they wait — [setEvents] sends them — and on the
     * way out they are lost: the overlay then lets the config win after its hold. One the
     * listener died under (or was dropped before) is due again ([DpiEdits.unsend]), for the
     * next [setEvents].
     */
    private fun flushDpi(all: Boolean = false) {
        if (events == null) return
        val due = synchronized(dpiLock) { dpiEdits.due(if (all) Long.MAX_VALUE else android.os.SystemClock.uptimeMillis()) }
        for ((pkg, dpi) in due) {
            val ev = events
            if (ev == null) { synchronized(dpiLock) { dpiEdits.unsend(pkg, dpi) }; continue }
            runCatching { ev.onPortalDpi(pkg, dpi ?: 0) }.onFailure {
                val dead = it is android.os.DeadObjectException
                Log.w(TAG, "portal: DPI for $pkg not handed on: ${it.message}" + if (dead) " — kept for the next listener" else "")
                // Only a dead listener: it is dropped, so this cannot spin; anything else
                // would be re-sent at once, and fail the same way.
                if (dead) {
                    synchronized(dpiLock) { dpiEdits.unsend(pkg, dpi) }
                    if (events === ev) events = null
                }
            }
        }
        if (!all) scheduleDpiFlush()
    }

    // -- the back's lock ----------------------------------------------------------------------
    //
    // See BackLock. While the phone is locked the portal sheet is a keypad for the phone's own code
    // (LockCheck asks the system); the right one opens it until the back goes dark or the phone
    // locks again. A guest up when the back locks goes behind the panel, and Resume wants the code.

    /** Guards [rearLock]. A leaf. */
    private val lockGuard = Any()
    private val rearLock = BackLock()
    /** The phone's lock type, read off `dumpsys lock_settings` ([refreshLockKind]); null until read. */
    @Volatile private var lockKind: BackLock.Kind? = null
    /** The open sheet is the lock. Under [sheetLock], read anywhere. */
    @Volatile private var sheetLocked = false
    /** Until when (uptime) the lock is leaving, its code given: taps on the sheet wait for it. */
    @Volatile private var unlockingUntil = 0L
    /** The code being typed, its message, and whether one is being checked. Under [sheetLock]. */
    private val lockEntry = BackLock.Entry()
    private var lockMessage: String? = null
    private var lockWrong = false
    private var lockChecking = false

    /** A password's keyboard page, and a pattern being drawn: its dots, the finger, whether one is down. Under [sheetLock]. */
    private var kbPage = PortalSheet.KB_LOWER
    private var patternCells: List<Int> = emptyList()
    private var patternFinger: Pair<Float, Float>? = null
    private var patternActive = false

    /**
     * DEBUG BUILDS ONLY: a lock to try without the phone's own (`setprop debug.magicrear.lockdemo
     * pin|pattern|password`, "" to end). The sheet is the lock every time it opens, whatever the
     * phone, and checks against made-up codes, never the system: PIN 1234, a Z pattern (1235789),
     * the password "magic". For seeing each lock right and wrong.
     */
    private fun lockDemo(): BackLock.Kind? {
        if (!BuildConfig.DEBUG) return null
        val v = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.magicrear.lockdemo") as? String
        }.getOrNull()?.trim()?.lowercase()
        return when (v) { "pin" -> BackLock.Kind.PIN; "pattern" -> BackLock.Kind.PATTERN; "password" -> BackLock.Kind.PASSWORD; else -> null }
    }

    private fun demoCode(kind: BackLock.Kind) = when (kind) {
        BackLock.Kind.PATTERN -> "1235789"
        BackLock.Kind.PASSWORD -> "magic"
        else -> "1234"
    }

    /** What takes the code on the sheet: the demo's, else the phone's lock (a PIN until read). */
    private fun lockMode(): BackLock.Kind = lockDemo() ?: lockKind?.takeIf { it != BackLock.Kind.NONE } ?: BackLock.Kind.PIN

    private val lockWorker by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> guardedThread("back-lock", r) }
    }

    /**
     * The front watched for the back's lock: the front going dark, or the phone unlocked and locked
     * again, locks the back. Read here because the sheet and the keep-alive looked too seldom: a lock,
     * an unlock on the front and a lock again between two looks left the back open (the owner,
     * 2026-11-08). Two binder reads every [FRONT_WATCH_MS]; a daemon, gone with the process.
     */
    private fun watchFrontLock() {
        Thread({
            var frontWasOn: Boolean? = null
            while (true) {
                runCatching {
                    val on = context?.getSystemService(android.hardware.display.DisplayManager::class.java)
                        ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state == android.view.Display.STATE_ON
                    val locked = phoneLocked()
                    if (on && frontWasOn == false) autostart.frontCameOn()
                    // Likewise the system search's block, which waits for a lit screen (SearchOffline).
                    if (on && frontWasOn == false) searchOffline.frontCameOn()
                    synchronized(lockGuard) {
                        if (!on && frontWasOn == true) rearLock.dark()
                        rearLock.seen(locked)
                    }
                    frontWasOn = on
                    // The phone's lock as just read, not asked again (it was asked two or three times a look).
                    syncBackLock(phone = locked)
                    val backNowLocked = backLockedNow
                    if (sheetLocked && sheetUp && !backNowLocked) openedElsewhere()
                    // The phone locked with the back open: what is up locks now, not at the next look.
                    if (backNowLocked) {
                        if (sheetUp && !sheetLocked) relockSheet()
                        guest?.takeIf { it.onTop && it.onPrivate && !lockVeiled }?.let { veil(it) }
                    }
                    if (lockVeiled) {
                        if (!backLocked(locked)) unveil("the phone is unlocked")
                        else if (rearLit(rearDisplay()) != true) coverShown = false
                        else if (!coverShown && !sheetUp) { coverShown = true; openLockCover() }
                    }
                }
                Thread.sleep(FRONT_WATCH_MS)
            }
        }, "front-lock-watch").apply { isDaemon = true; start() }
    }

    /** Whether the phone is locked: its keyguard's word, or the lock screen showing where that cannot be had. */
    private fun phoneLocked(): Boolean = runCatching {
        context?.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked
    }.getOrNull() ?: keyguardShowing()

    /**
     * The back's lock as last looked at ([syncBackLock]): what draws reads, every frame, with no
     * binder call. Locked until first looked at.
     */
    @Volatile private var backLockedNow = true
    /** Notifications keep their words while locked (PanelConfig.lockedNoticeText). */
    @Volatile private var lockedNoticeText = false
    /** Companion mode's: a banner keeps its words while the phone is locked (PanelConfig.noticeTextWhenLocked). */
    @Volatile private var noticeTextWhenLocked = false

    /** [backLockedNow] looked at again, and the panel told when it changed (IRearEvents.onBackLock). Any thread. [phone]: the phone's lock as just read; null to read it. */
    private fun syncBackLock(force: Boolean = false, phone: Boolean? = null) {
        val now = backLocked(phone)
        if (now == backLockedNow && !force) return
        backLockedNow = now
        Log.i(TAG, "back lock: ${if (now) "on" else "off"}")
        runCatching { events?.onBackLock(now) }
        // What is up over the back shows it at once, not at its next frame.
        runCatching { notices.lockChanged() }
        runCatching { controlCenter.lockChanged() }
    }

    /** Whether the back is locked now (BackLock). Any thread; one binder call, none where [phone] (the phone's lock as just read) is given. */
    private fun backLocked(phone: Boolean? = null): Boolean {
        if (lockDemo() != null) return true
        val locked = phone ?: phoneLocked()
        return synchronized(lockGuard) { rearLock.locked(lockKind, locked) }
    }

    /** [backLocked], said in the log with why: for a sheet opening, not for every look. */
    private fun backLockedSaid(): Boolean {
        val demo = lockDemo()
        val phone = phoneLocked()
        val locked = demo != null || synchronized(lockGuard) { rearLock.locked(lockKind, phone) }
        Log.i(TAG, "back lock: ${if (locked) "locked" else "open"} — phone ${if (phone) "locked" else "unlocked"}, " +
            "lock ${lockKind ?: "unread"}, given here ${synchronized(lockGuard) { rearLock.unlockedHere }}" + (demo?.let { ", demo $it" } ?: ""))
        return locked
    }

    /** The lock type read again, off the caller's thread. */
    private fun refreshLockKind() {
        runCatching {
            lockWorker.execute {
                BackLock.kindOf(exec("dumpsys lock_settings"))?.let { k ->
                    if (k != lockKind) { lockKind = k; Log.i(TAG, "back lock: the phone's lock is ${k.name}") }
                }
            }
        }
    }

    /** The keypad as the sheet lays it out. */
    private fun keypadNow() = PortalSheet.keypad(shellSheet?.lensRight ?: geometry.cutoutUnits, geometry.fitY / geometry.fitX)

    /** The pattern's dots as the sheet lays them out, round on this rear. */
    private fun patternNow() = PortalSheet.patternGrid(shellSheet?.lensRight ?: geometry.cutoutUnits, geometry.fitY / geometry.fitX)

    /** The lock drawn as it stands. Under [sheetLock]. */
    private fun pushLock(s: ShellSheet? = shellSheet) {
        val kind = lockMode()
        // Shooting for the site (ShootMode): the demo lock as the real one reads.
        val demo = if (lockDemo() != null && !ShootMode.capturesShown()) " (demo)" else ""
        s?.lock(
            PortalSheetPainter.LockLook(
                title = when (kind) {
                    BackLock.Kind.PATTERN -> "Draw pattern$demo"
                    BackLock.Kind.PASSWORD -> "Enter password$demo"
                    else -> "Enter PIN$demo"
                },
                entered = lockEntry.length, message = lockMessage, wrong = lockWrong, pressedKey = -1,
                kind = kind, pattern = patternCells, finger = patternFinger, page = kbPage,
            ),
        )
    }

    /** The word for a wrong code of the lock's kind. */
    private fun wrongWord() = when (lockMode()) {
        BackLock.Kind.PATTERN -> "Wrong pattern"
        BackLock.Kind.PASSWORD -> "Wrong password"
        else -> "Wrong PIN"
    }

    /** The sheet's lock state back to nothing typed. Under [sheetLock]. */
    private fun clearLockInput() {
        lockEntry.clear()
        lockMessage = null
        lockWrong = false
        kbPage = PortalSheet.KB_LOWER
        patternCells = emptyList()
        patternFinger = null
        patternActive = false
    }

    /**
     * A finger on a pattern lock, taken from the raw touches before the sheet's gestures see them:
     * a drag there draws the pattern, not the sheet. True when it was the pattern's. Under [sheetLock].
     */
    private fun patternTouch(e: RearTouches.Event): Boolean {
        val drawing = patternActive
        if (!sheetUp || !sheetLocked || lockChecking || lockMode() != BackLock.Kind.PATTERN) { patternActive = false; return false }
        if (!drawing && (e !is RearTouches.Down || motion.settled(e.atMs) == null)) return false
        val grid = patternNow()
        val cell = PortalSheet.cellAt(grid, e.x, e.y)
        when (e) {
            is RearTouches.Down -> {
                if (cell < 0) return false
                patternActive = true
                patternCells = listOf(cell)
                patternFinger = e.x to e.y
                lockMessage = null
                lockWrong = false
                pokePanel()
            }
            is RearTouches.Move -> {
                if (cell >= 0) patternCells = PortalSheet.patternAdd(patternCells, cell)
                patternFinger = e.x to e.y
            }
            is RearTouches.Up -> {
                patternActive = false
                patternFinger = null
                if (patternCells.size < BackLock.PATTERN_MIN) {
                    lockMessage = "Join 4 dots"
                    lockWrong = true
                    patternCells = emptyList()
                } else {
                    submitLock(PortalSheet.patternCode(patternCells))
                    return true
                }
            }
        }
        pushLock()
        return true
    }

    /** A tap on the locked sheet: a key, or off the keypad's side a shut. Under [sheetLock]. */
    private fun lockTap(x: Float, y: Float, atMs: Long) {
        val lens = shellSheet?.lensRight ?: geometry.cutoutUnits
        when (lockMode()) {
            BackLock.Kind.PASSWORD -> {
                val kb = PortalSheet.keyboard(lens, kbPage)
                val i = PortalSheet.kbKeyAt(kb, x, y)
                if (i < 0) { if (x < kb.left - 8f) shutSheet(atMs, null) else steady(atMs); return }
                steady(atMs)
                pokePanel()
                if (lockChecking) return
                val key = kb.keys[i]
                when (key.type) {
                    PortalSheet.KbType.CHAR -> {
                        if (lockEntry.add(key.ch)) { lockMessage = null; lockWrong = false }
                        // Shift is for one letter, as a phone's.
                        if (kbPage == PortalSheet.KB_UPPER) kbPage = PortalSheet.KB_LOWER
                    }
                    PortalSheet.KbType.SPACE -> if (lockEntry.add(' ')) { lockMessage = null; lockWrong = false }
                    PortalSheet.KbType.BACK -> lockEntry.back()
                    PortalSheet.KbType.SHIFT -> kbPage = if (kbPage == PortalSheet.KB_UPPER) PortalSheet.KB_LOWER else PortalSheet.KB_UPPER
                    PortalSheet.KbType.MODE -> kbPage = if (kbPage == PortalSheet.KB_SYMBOLS) PortalSheet.KB_LOWER else PortalSheet.KB_SYMBOLS
                    PortalSheet.KbType.OK -> { submitLock(); return }
                }
                pushLock()
            }
            BackLock.Kind.PATTERN -> {
                // Drawn, not tapped (patternTouch): a tap left of the dots shuts, as off any lock.
                if (x < patternNow().square.left - 24f) shutSheet(atMs, null) else steady(atMs)
            }
            else -> {
                val k = keypadNow()
                val key = PortalSheet.keyAt(k, x, y)
                if (key < 0) { if (x < k.keys.first().left - 24f) shutSheet(atMs, null) else steady(atMs); return }
                steady(atMs)
                pokePanel()
                if (lockChecking) return
                when (key) {
                    PortalSheet.KEY_BACK -> lockEntry.back()
                    PortalSheet.KEY_OK -> { submitLock(); return }
                    else -> PortalSheet.keyDigit(key)?.let { if (lockEntry.add(it)) { lockMessage = null; lockWrong = false } }
                }
                pushLock()
            }
        }
    }

    /** The typed code checked by the system, off the reader; the sheet opens on the right one. Under [sheetLock]. */
    private fun submitLock(given: String? = null) {
        val code = given ?: lockEntry.text
        if (code.isEmpty()) return
        lockEntry.clear()
        val demo = lockDemo()
        lockChecking = true
        lockMessage = "Checking…"
        lockWrong = false
        pushLock()
        runCatching {
            lockWorker.execute {
                val result = if (demo != null) {
                    Thread.sleep(250)
                    if (code == demoCode(demo)) LockCheck.Result.Right else LockCheck.Result.Wrong
                } else LockCheck.verify(code)
                synchronized(sheetLock) {
                    lockChecking = false
                    when (result) {
                        LockCheck.Result.Right -> {
                            if (demo == null) synchronized(lockGuard) { rearLock.unlocked() }
                            syncBackLock()
                            clearLockInput()
                            Log.i(TAG, "back lock: unlocked with the phone's code")
                            sheetLocked = false
                            // Over a covered guest: it shows again, just as it was, and the sheet goes.
                            if (lockVeiled || lockPrompt) {
                                // The lock leaves first — its glass into a pill, the tick (ShellSheet.unlockFade)
                                // — and as the pill goes, what it covered is back behind the sheet and
                                // the sheet goes down. No finger does anything on the sheet until then.
                                lockPrompt = false
                                shellSheet?.unlockFade()
                                val wait = (PortalSheetPainter.UNLOCK_MS * PortalSheetPainter.UNLOCK_GOING).toLong()
                                unlockingUntil = android.os.SystemClock.uptimeMillis() + wait
                                runCatching {
                                    sheetClock.schedule({
                                        synchronized(sheetLock) {
                                            unveil("the code")
                                            if (sheetUp) shutSheet(android.os.SystemClock.uptimeMillis(), null)
                                        }
                                    }, wait, java.util.concurrent.TimeUnit.MILLISECONDS)
                                }.onFailure {
                                    unveil("the code")
                                    shutSheet(android.os.SystemClock.uptimeMillis(), null)
                                }
                                return@synchronized
                            }
                            lockMessage = null
                            // The lock leaves (its glass into a pill, the tick), the apps it gated coming in as it goes.
                            shellSheet?.unlockFade()
                            unlockingUntil = android.os.SystemClock.uptimeMillis() + (PortalSheetPainter.UNLOCK_MS * PortalSheetPainter.UNLOCK_GOING).toLong()
                            // The guest's buttons and sliders back, and the sheet's stops with them (on the clock).
                            syncSheetGuest()
                            runCatching { sheetClock.execute { restop() } }
                            return@synchronized
                        }
                        LockCheck.Result.Wrong -> { lockMessage = wrongWord(); lockWrong = true }
                        LockCheck.Result.Throttled -> { lockMessage = "Wait, then retry"; lockWrong = true }
                        is LockCheck.Result.Failed -> { lockMessage = "Couldn't check"; lockWrong = true }
                    }
                    pushLock()
                }
            }
        }.onFailure { lockChecking = false; lockMessage = "Couldn't check"; pushLock() }
    }

    /**
     * The keep-alive's look at the lock, with a guest up: the back dark locks it again, and a guest
     * on top of a locked back goes behind the panel (as Panel), so waking it shows the panel.
     */
    private fun lockTick(g: Guest, lit: Boolean) {
        if (!lit) synchronized(lockGuard) { rearLock.dark() }
        if (g.onTop && !lockVeiled && backLocked()) veil(g)
    }

    /**
     * A guest up when the back locks, covered: it stays where it is, on top, but its picture is
     * hidden and no finger reaches it ([forwardOpen], [syncPrivate], [chromeShow]); the code comes
     * over it as the back lights ([watchFrontLock] → [openLockCover]), and the right one shows it
     * again just as it was. Not sent behind the panel: then the code led to the panel, and the app
     * had to be resumed (the owner, 2026-11-08).
     */
    @Volatile private var lockVeiled = false
    /** The cover already came up since the back last lit: shut by a finger, it stays shut. */
    @Volatile private var coverShown = false

    private fun veil(g: Guest) {
        Log.i(TAG, "back lock: locked with ${g.pkg} up — covered")
        lockVeiled = true
        coverShown = false
        chromeHide()
        syncPrivate()
    }

    /**
     * The phone unlocked (the front) while the back's lock is up: it fades away and what it gated
     * comes in under it — the apps, or the covered guest as the sheet goes. Any thread holding no sheet lock.
     */
    private fun openedElsewhere() {
        synchronized(sheetLock) {
            if (!sheetLocked || !sheetUp) return
            Log.i(TAG, "back lock: the phone was unlocked — the code goes")
            sheetLocked = false
            clearLockInput()
            if (lockVeiled || lockPrompt) {
                lockPrompt = false
                unveil("the phone is unlocked")
                shutSheet(android.os.SystemClock.uptimeMillis(), null)
                return
            }
            shellSheet?.unlockFade()
            syncSheetGuest()
            runCatching { sheetClock.execute { restop() } }
        }
    }

    /** An open sheet showing the apps turned into the lock: the phone locked under it. Any thread holding no sheet lock. */
    private fun relockSheet() {
        synchronized(sheetLock) {
            if (!sheetUp || sheetLocked) return
            Log.i(TAG, "back lock: the phone locked with the sheet open — the code")
            sheetLocked = true
            clearLockInput()
            dropChoice()
            pushLock()
            syncSheetGuest()
            runCatching { sheetClock.execute { restop() } }
        }
    }

    /** The guest shown again: the code given, or the phone unlocked. */
    private fun unveil(why: String) {
        if (!lockVeiled) return
        lockVeiled = false
        Log.i(TAG, "back lock: uncovered — $why")
        syncPrivate()
        if (guest?.onTop == true) chromeShow()
    }

    /**
     * The code asked for over whatever is up, by something the lock holds (a switch, a notification):
     * the keypad comes up over it, and the right code shuts it again, leaving things as they were,
     * unlocked. Any thread holding no sheet lock.
     */
    private fun openLockPrompt(why: String) {
        if (!backLockedNow) return
        // The control centre or list it was asked over stays open under the keypad (ShellLayer.Z_PANES),
        // and takes no finger while the keypad is up (onRearTouch): the code given, they are there, unlocked.
        lockPrompt = true
        raiseLock("asked for by $why")
    }

    /** The keypad shuts again once the code is given: it was asked for ([openLockPrompt]), not pulled. */
    @Volatile private var lockPrompt = false

    /** The code over the covered guest, risen as the sheet does, no finger needed. Any thread holding no sheet lock. */
    private fun openLockCover() {
        if (!lockVeiled) return
        raiseLock("over ${guest?.pkg}")
    }

    /** The keypad risen as the sheet does, no finger needed. Any thread holding no sheet lock. */
    private fun raiseLock(what: String) {
        synchronized(sheetLock) {
            if (sheetUp || cameraUp()) return
            val s = sheetOrNull() ?: return
            val now = android.os.SystemClock.uptimeMillis()
            sheetLocked = true
            refreshLockKind()
            clearLockInput()
            pushLock(s)
            syncSheetGuest()
            motion.reset()
            motion.setDetents(listOf(0f, PortalSheet.OPEN), now)
            pager.reset()
            sheetLayout().let { pager.stride = it.pageStride; pager.pages = it.pages }
            slide = PortalSheet.Swipe.NONE
            sheetUp = true
            beginPending = true
            driving = false
            gestures?.sheet = true
            dropChoice()
            pressed = -1
            s.page(0f)
            s.sliderDrag(null)
            s.position(0f)
            motion.settle(PortalSheet.OPEN, now)
            Log.i(TAG, "back lock: the code, $what")
            beginOnClock(s)
        }
    }

    /** The sheet's first rest open, said to the app once (IRearEvents.onPortalOpened): the front's guide ticks it. */
    private val portalOpened = dev.kb896.aiusage.data.OnceLatch()

    /** Whatever [motion] now says, carried out. Under [sheetLock]. */
    private fun afterMotion(nowMs: Long) {
        when (motion.phase) {
            SheetMotion.Phase.SETTLING -> { pushPosition(nowMs); if (!beginPending) startAnim() }
            SheetMotion.Phase.SHUT -> sheetDown()
            // Still: no frames for the sheet, but pages still sliding keep theirs.
            else -> {
                if (!pager.settling && !dome.settling) stopAnim(); pushPosition(nowMs)
                if (motion.phase == SheetMotion.Phase.OPEN) portalOpened.fire { events?.onPortalOpened() != null }
            }
        }
    }

    private fun pushPosition(nowMs: Long) { shellSheet?.position(motion.position(nowMs)) }

    private fun startAnim() {
        if (anim != null) return
        anim = runCatching {
            sheetClock.scheduleAtFixedRate({ animStep() }, SHEET_FRAME_MS, SHEET_FRAME_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun stopAnim() {
        anim?.cancel(false)
        anim = null
    }

    /** One frame of a settle — the sheet's, the pages', or both — on [sheetClock]. */
    private fun animStep() {
        try {
            synchronized(sheetLock) {
                if (!sheetUp || (motion.phase != SheetMotion.Phase.SETTLING && !pager.settling && !dome.settling)) { stopAnim(); return }
                if (!sheetAlive()) return
                val now = android.os.SystemClock.uptimeMillis()
                if (pager.settling) shellSheet?.page(pager.at(now))
                if (dome.settling) shellSheet?.domeAt(dome.at(now))
                if (motion.phase == SheetMotion.Phase.SETTLING) {
                    shellSheet?.position(motion.at(now))
                    if (motion.phase != SheetMotion.Phase.SETTLING) afterMotion(now)
                } else if (!pager.settling && !dome.settling) stopAnim()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "portal sheet: settle frame failed: ${e.message}")
        }
    }

    /**
     * The sheet down: shut, its layers ended on [sheetClock] (never the reader — end()
     * may wait for its render thread), fingers back to the edges, and [afterShut] run.
     * Under [sheetLock].
     */
    private fun sheetDown() {
        stopAnim()
        val wasUp = sheetUp
        sheetUp = false
        beginPending = false
        driving = false
        pressed = -1
        slide = PortalSheet.Swipe.NONE
        sliderPkg = null
        widthPkg = null
        lockPrompt = false
        // A DPI or width set while it was up: the app restarts in it now the sheet is not over it.
        restartAfterSheet?.let { restartAfterSheet = null; restartGuest(it, "its size changed on the back") }
        gestures?.sheet = false
        motion.reset()
        pager.reset()
        val then = afterShut
        afterShut = null
        // From here a held veil is the choice's to let go, if it runs; else it goes now.
        val held = veilHeld
        veilHeld = false
        val unrun = { if (held) frame.releaseVeil() }
        val s = shellSheet ?: return unrun()
        if (!wasUp) return unrun()
        if (then == null) unrun()
        runCatching {
            sheetClock.execute {
                runCatching { s.end() }.onFailure { Log.w(TAG, "portal sheet: end failed: ${it.message}") }
                then?.let { choice -> guarded { choice() }.apply { isDaemon = true; name = "portal-sheet-choice"; start() } }
            }
        }.onFailure {
            runCatching { s.end() }
            if (then != null) unrun()
        }
    }

    /**
     * Shut at once, no settle, nothing chosen run: the rear is going dark, the panel is
     * hidden, the watch stopped. [sync]: end() on this thread (destroy, which exits next).
     */
    private fun closeSheetNow(why: String, sync: Boolean = false) {
        // The control centre over a guest goes with it, for every same reason.
        controlCenter.closeNow(why, sync)
        notices.closeNow(why, sync)
        val s = synchronized(sheetLock) {
            if (!sheetUp) return
            Log.i(TAG, "portal sheet: shut — $why")
            dropChoice()
            sheetDown()
            shellSheet
        }
        if (sync) runCatching { s?.end() }
    }

    // -- the control centre over a guest, in the shell --------------------------------------
    //
    // ShellControlCenter: pulled from the top edge over a guest on top, as the panel's own is
    // over the panel (which keeps it: over our panel nothing here opens). Its brightness and
    // automatic mode are this service's own calls; its tiles are the app's, through IRearEvents.

    private val controlCenter: ShellControlCenter by lazy {
        ShellControlCenter(
            layerStack = ::rearLayerStack,
            geometry = { geometry },
            rear = object : ShellControlCenter.Rear {
                override fun brightness() = rearBrightness()
                override fun auto() = rearAutoBrightness()
                override fun setBrightness(value: Int) = setRearBrightness(value)
                override fun setAuto(on: Boolean) = setRearAutoBrightness(on)
            },
            events = { events },
            // The portal frame fades under it as under the sheet; the strip pauses its samples.
            onProgress = { frame.controlCenterOpen(it); strip.sheetOpen(it) },
            locked = { backLockedNow },
            onLocked = { openLockPrompt("a switch over a guest") },
            onCapture = { record -> capture(record) },
            recording = { recordPid > 0 },
        )
    }

    /**
     * Whether a pull from the top edge opens the control centre here: over a guest on top
     * only — over our panel the panel's own opens — not with the portal sheet up or coming,
     * and not over the camera we opened. Under [sheetLock] (the touch reader's).
     */
    private fun controlCenterOpens(): Boolean =
        !sheetUp && !beginPending && guest?.sheetOnTop == true && !cameraUp() && !notices.isUp

    // -- the notifications over a guest, in the shell ----------------------------------------
    //
    // ShellNotices: the list pulled from the top edge's right half, over a guest or the panel on
    // any of its pages, and the banner as one arrives, over either. The only ones there are.

    private val notices: ShellNotices by lazy {
        ShellNotices(
            // Up (a pull began) and gone: inputs to the navigation, off this caller's thread (it may hold the shade's lock).
            onUpChanged = { up ->
                runCatching { holdHandler.post { navStep(if (up) RearNav.Input.Stroke(RearNav.Way.DOWN, RearNav.From.TOP_EDGE) else RearNav.Input.ShadeShut) } }
            },
            layerStack = ::rearLayerStack,
            geometry = { geometry },
            // A made-up row (a debug build's demo list) is nobody's notification: the app is not told of it.
            // A made-up finger (a test's) never takes one of his away (NoticeThrow.toPhone): only said.
            onDismissed = { key -> when {
                NoticeThrow.isDemo(key) -> Log.i(TAG, "notices: a demo row swiped away ($key)")
                !NoticeThrow.toPhone(key, madeUpFinger()) -> Log.i(TAG, "notices: thrown out by a made-up finger — the notification is unchanged")
                else -> events?.onNoticeDismissed(key)
            } },
            // Thrown the other way: snoozed for the hour on the phone (the app's listener does it); a demo's only logged.
            onSnoozed = { key, ms -> when {
                NoticeThrow.isDemo(key) -> Log.i(TAG, "notices: a demo row snoozed ($key)")
                !NoticeThrow.toPhone(key, madeUpFinger()) -> Log.i(TAG, "notices: snoozed by a made-up finger — the notification is unchanged")
                else -> events?.onNoticeSnoozed(key, ms)
            } },
            onProgress = { frame.controlCenterOpen(it); strip.sheetOpen(it) },
            // Companion mode: the text while locked is its own choice (PanelConfig.noticeTextWhenLocked,
            // NoticeFilter.textShown); Replace mode keeps the panel's lockedNoticeText.
            redact = { backLockedNow && !(if (companion) noticeTextWhenLocked else lockedNoticeText) },
            onLockedTap = { openLockPrompt("a notification") },
            onAction = { key, index -> if (key.startsWith("demo:")) Log.i(TAG, "notices: a demo row's button $index pressed ($key)") else events?.onNoticeAction(key, index) },
            // Companion mode: the apps picked for the back along the list's foot (More apps on).
            dock = { portalApps.takeIf { companion && moreApps && it.size > 0 } },
            onDockTap = { pkg ->
                Log.i(TAG, "notices: dock — $pkg")
                runCatching { popWaiter.execute { launchGuest(pkg, rearDisplay()) } }
            },
            // The pop-up standing over the whole back, or not any more: what is under it may be held, or must move (HiddenFrames).
            onPopPlace = { popInPlace = it; coverMoved() },
            // The pile settled over the whole back, or not any more (a finger on it, a frame that moves, its end): the same.
            onPileCover = { pileInPlace = it; coverMoved() },
            // The pull with nothing waiting: the rim's light in white, or in Xiaomi's rear-glow colours (the settings' choice).
            rimXiaomi = { emptyPullXiaomi },
            // The message island's tint of the live face ("Island · Tint"): the scene's engines read it at each picture.
            veil = scene.veil,
        )
    }
    @Volatile private var emptyPullXiaomi = false
    /** "Message style" (PanelConfig.messageStyle): the full page or the message island; read as each message comes. */
    @Volatile private var messageStyle = dev.kb896.aiusage.data.MessageStyle.FULL
    @Volatile private var noticesOn = true
    @Volatile private var noticesPop = true

    /**
     * Whether a pull from the top edge's right half opens the list: as the portal sheet opens
     * (over a guest, or over the panel with no slider up), not asleep. Under [sheetLock] (the
     * touch reader's).
     */
    private fun noticesOpens(): Boolean =
        !nav.picker && !picker.isUp && noticesOn && !sheetUp && !beginPending && (companion || (pullOpens() && !dozing)) && !cameraUp() && !controlCenter.isUp

    override fun setNotices(list: android.os.Bundle?, pop: String?) {
        val items = notices.items(list)
        if (pop.isNullOrEmpty()) return
        val item = items.firstOrNull { it.key == pop } ?: return
        if (!companion) {
            if (!noticesPop) return
            // Whatever is on the rear, and with the slider up too, as a notification always showed;
            // only not over something of the shell's own, nor the camera.
            val shows = synchronized(sheetLock) {
                noticesOn && !sheetUp && !beginPending && !cameraUp() && !controlCenter.isUp && !notices.isUp
            }
            if (shows) popUp(item)
            return
        }
        // COMPANION: over Xiaomi's screen, locked or not; over a guest of ours only with "Over apps
        // on the back"; never over Xiaomi's rear camera. A dark back is waited for briefly (the app
        // has just woken it, or it was not to be woken and the banner is for a lit back only).
        if (!noticesOn) return
        val display = rearDisplay()
        val litNow = rearLit(display) == true
        if (litNow && noticeFaceReady()) { popOverXiaomi(item, display); return }
        // The wake this banner's back is lit by plays no wake animation: the banner is what it is for.
        if (!litNow) bannerWaitUntil = android.os.SystemClock.uptimeMillis() + POP_LIT_WAIT_MS
        runCatching {
            popWaiter.execute {
                // The face first, here and not on the binder thread: without it the banner is dropped.
                if (!ensureNoticeFace()) { Log.w(TAG, "notices: the banner's font did not load — no banner"); return@execute }
                val until = android.os.SystemClock.uptimeMillis() + POP_LIT_WAIT_MS
                while (android.os.SystemClock.uptimeMillis() < until) {
                    if (rearLit(display) == true) { popOverXiaomi(item, display); return@execute }
                    try { Thread.sleep(POP_LIT_STEP_MS) } catch (_: InterruptedException) { return@execute }
                }
                Log.i(TAG, "notices: the back stayed dark — no banner")
            }
        }
    }

    /** One waits here for a dark back to light, for its banner; one at a time. */
    private val popWaiter = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "notice-pop-wait").apply { isDaemon = true }
    }

    /** Companion mode's banner, the rear lit: through [rearShow], which it always wins. */
    private fun popOverXiaomi(item: NoticeShade.Item, display: Int) {
        if (!noticeFaceReady()) { Log.w(TAG, "notices: no font for the banner yet — no banner"); return }
        val guestUp = guestOnTop()
        if (guestUp && !noticesPop) return
        val shellBusy = synchronized(sheetLock) { sheetUp || beginPending || controlCenter.isUp || notices.isUp }
        if (shellBusy) return
        if (cameraUp() || (!guestUp && cameraHasRear(display))) {
            Log.i(TAG, "notices: Xiaomi's rear camera has the back — no banner")
            return
        }
        // Locked or not, now: the banner's words follow it (redact), read once a banner.
        syncBackLock()
        val now = android.os.SystemClock.uptimeMillis()
        when (val d = rearShow.request(RearShow.Kind.BANNER, now, notices.popMs(messageStyle))) {
            is RearShow.Decision.Drop -> return
            is RearShow.Decision.Start -> when (d.cut) {
                RearShow.Kind.CHARGE -> charge.cut(d.fadeMs, "a notification")
                RearShow.Kind.WAKE -> wakeShow.cut(d.fadeMs, "a notification")
                else -> Unit
            }
        }
        popUp(item)
    }

    /** A pop-up shown, and told to the navigation for its time on the back (RearNav: it lies over everything, and touches go through it). */
    private fun popUp(item: NoticeShade.Item, style: dev.kb896.aiusage.data.MessageStyle = messageStyle) {
        // A new one (or the next in the place of the one up): a finger already down is not aimed at it (NoticeThrow.aimed),
        // and a drag that followed the one before lets go of it (popLost).
        popAt = android.os.SystemClock.uptimeMillis()
        popSerial++
        // In the style chosen; the island's open page tints the face itself when the face is what shows (asked before the
        // navigation has the pop-up). Its time on the back is the style's own.
        islandOverFace = style != dev.kb896.aiusage.data.MessageStyle.FULL && RearNav.seen(nav.copy(pop = false)) == RearNav.Seen.MOON
        val ms = notices.pop(item, style, faceTints = islandOverFace && scene.tintsItself)
        // Whether it may be thrown out (dismissed, snoozed) is its own: one that may not gives under the finger and stays.
        navStep(RearNav.Input.PopArrives(fixed = !item.clearable))
        runCatching { holdHandler.removeCallbacks(popEnds); holdHandler.postDelayed(popEnds, ms) }
    }

    /** The pop-up back in its place after a finger held it aside (NoticeThrow's spring), and its end timed again from what it has left. */
    private fun popBack() {
        val left = runCatching { notices.popBack() }.getOrDefault(0L)
        runCatching { holdHandler.removeCallbacks(popEnds); holdHandler.postDelayed(popEnds, left) }
    }
    private val popEnds = object : Runnable {
        override fun run() {
            val remaining = notices.islandTimeLeft()
            if (remaining > 0L) holdHandler.postDelayed(this, remaining)
            else { navStep(RearNav.Input.PopEnds); applyLive() }
        }
    }
    /** When the pop-up up now came (uptime ms, 0 before any), and which one it is: one more for each. */
    @Volatile private var popAt = 0L
    @Volatile private var popSerial = 0L

    // -- the charging animation over Xiaomi's rear (ShellCharge) -------------------------------

    /** One transient show at a time: banner > charge > wake (RearShow). */
    private val rearShow = RearShow()

    private val charge: ShellCharge by lazy {
        chargeMade = true
        ShellCharge(
            layerStack = ::rearLayerStack,
            geometry = { geometry },
            lit = { rearLit(rearDisplay()) == true },
            litRecently = ::rearLitRecently,
            onEnded = {
                chargeLoose = false
                rearShow.end(RearShow.Kind.CHARGE, android.os.SystemClock.uptimeMillis())
                syncTouchWatch()
            },
            // The whole charging page standing in its place, or not any more: what is under it may be held, or must move (HiddenFrames).
            onPlace = { chargeInPlace = it; coverMoved() },
        )
    }

    /** The rear's light may have changed: told to the charging page only if it was ever made (it waits for this on a dark back). */
    private fun chargeLitChanged() { if (chargeMade) runCatching { charge.litChanged() } }
    @Volatile private var chargeMade = false

    /** PanelConfig.chargeAnim, chargeStyle, chargePercent, from the last payload. */
    @Volatile private var chargeOn = true
    @Volatile private var chargeLook = dev.kb896.aiusage.data.ChargeAnimStyle.LIQUID
    @Volatile private var chargePercent = true

    /** A charging picture that is not the row's live page (the settings' preview, the small one over an app): the navigation leaves it alone. */
    @Volatile private var chargeLoose = false
    private val chargePreviewing: Boolean get() = chargeLoose

    /**
     * THE CHARGER IS IN (the app's word): charging is live from now until it comes out, the lowest of the live
     * things (RearNav.Live.CHARGE). Whether its page comes up is the navigation's to say ([applyLive] raises it):
     * over the moon it does; under a call, a timer or what is playing it has none until they end.
     */
    override fun showCharge(level: Int, fast: Boolean) {
        if (!companion || !chargeOn) return
        // FULL AS THE CHARGER GOES IN: one short moment ([fullMoment]), only on a lit back that shows the moon. On a dark
        // back, under another page or with something higher live it is skipped, and nothing is kept for later.
        if (level >= 100 && !guestOnTop()) {
            val s = nav
            if (!(s.lit && s.ours && RearNav.seen(s) == RearNav.Seen.MOON && rearLit(rearDisplay()) == true)) {
                Log.i(TAG, "charge: full, and the back is dark or shows something else — no moment")
                return
            }
        }
        chargeWantedLevel = level; chargeWantedFast = fast
        chargeRests = false
        if (guestOnTop()) {
            // Over an app on the back: the small picture, once, as it was; not a page of the row.
            chargeLoose = true
            raiseCharge()
            return
        }
        // The charger going in is something new (a page he swiped away comes again); the app saying it once more is not.
        if (!chargeWanted) chargePlugs++
        chargeWanted = true
        chargeTriedAt = 0L
        syncMediaCard()
        if (level >= 100) fullMoment(cameNow = true)
        // Looked at again a moment later: a charger pulled straight out again must not leave the page standing.
        runCatching { holdHandler.postDelayed({ if (chargeWanted && !chargeDemo && !exec("dumpsys battery").contains("powered: true")) { Log.i(TAG, "charge: no charger after all — the page goes"); chargeOver() } }, 2_500L) }
    }

    /** The charger is out: charging is not live any more, and its page drains away if it is up. */
    private fun chargeOver() {
        runCatching { holdHandler.removeCallbacks(fullEnds) }
        chargeFullMoment = false; chargeDemo = false
        if (!chargeWanted && !charge.isUp) return
        chargeWanted = false
        syncMediaCard()
        // The small picture over an app is not the navigation's: it drains here.
        if (chargeLoose && charge.isUp) charge.drain()
    }

    /** The charging page's layer up, at the level the app last gave, if nothing with a better claim to the back is showing. */
    private fun raiseCharge(slideIn: Boolean = false) {
        val display = rearDisplay()
        if (cameraUp() || cameraHasRear(display)) {
            Log.i(TAG, "charge: Xiaomi's rear camera has the back — not shown")
            return
        }
        val level = chargeWantedLevel; val fast = chargeWantedFast
        val compact = guestOnTop()
        val t = ChargeStyle.timing(compact)
        val now = android.os.SystemClock.uptimeMillis()
        when (val d = rearShow.request(RearShow.Kind.CHARGE, now, t.totalMs.toLong() + ShellCharge.LIT_WAIT_MS)) {
            is RearShow.Decision.Drop -> { Log.i(TAG, "charge: a ${d.over.name.lowercase()} is up — not shown"); return }
            is RearShow.Decision.Start -> if (d.cut == RearShow.Kind.WAKE) wakeShow.cut(d.fadeMs, "the charger")
        }
        Log.i(TAG, "charge: $level%${if (fast) ", fast" else ""}${if (compact) ", compact over the guest" else ""}, ${chargeLook.name.lowercase()}")
        charge.show(level, fast, compact, chargeLook, chargePercent, slideIn)
        if (chargeDemo) charge.detail(100, -1f, android.os.SystemClock.uptimeMillis(), 0f, "Demo", holds = true)
        syncTouchWatch()
    }


    override fun chargeDetail(b: android.os.Bundle?) {
        b ?: return
        charge.detail(b.getInt("level"), b.getFloat("fraction", -1f), b.getLong("at"), b.getFloat("rate"), b.getString("kind").orEmpty(), b.getBoolean("holding"))
    }

    override fun chargeTilt(x: Float, y: Float) = charge.tilt(x, y)

    override fun endCharge() = chargeOver()

    // -- the health check's shell half (rear/ShellHealth): scanned once, some seconds after the start, and when asked
    // to look again; an ordinary ask answers with what is known (ShellHealth.Gate) --

    private val healthGate = ShellHealth.Gate()
    private val healthFirstLook = guarded {
        try { Thread.sleep(HEALTH_FIRST_LOOK_MS) } catch (_: InterruptedException) { return@guarded }
        if (synchronized(healthGate) { healthGate.first() }) ShellHealth.look(::exec)
    }.apply { name = "rear-health"; isDaemon = true; start() }

    override fun health(again: Boolean): android.os.Bundle {
        if (synchronized(healthGate) { healthGate.ask(again) }) ShellHealth.look(::exec)
        return ShellHealth.report(cover.made, cover.lastError, autostart.troubles, searchOffline.troubles)
    }

    /**
     * THE PHONE'S OWN SEARCH KEPT OFF THE INTERNET (rear/SearchOffline; "Keep the system search offline" in Advanced):
     * its firewall block applied again in every new shell process (after a restart of the phone, after an update), then
     * read once an hour and applied again only when gone. Only while the front or the back is lit; started from the
     * constructors, last.
     */
    private val searchOffline = SearchOffline.Runner(
        lit = {
            val front = rearState(android.view.Display.DEFAULT_DISPLAY)
            val back = rearState(rearDisplay())
            // Neither readable (no Context): taken as lit, so the block is not held back for ever.
            (front == null && back == null) || (front != null && dev.kb896.aiusage.service.FrontScreen.isOn(front)) || back == android.view.Display.STATE_ON
        },
        exec = ::exec,
    )

    private val autostart = Autostart.Runner(
        frontOn = { dev.kb896.aiusage.service.FrontScreen.isOn(rearState(android.view.Display.DEFAULT_DISPLAY) ?: android.view.Display.STATE_OFF) },
        coverUp = { cover.isUp },
        doNotDisturb = { exec("settings get global zen_mode").trim().let { it.isNotEmpty() && it != "0" && it != "null" } },
    )

    /** Full, the charger still in (ChargeState.Is.FULL): not live any more, and its page slides out as a page that ends does. */
    override fun chargeFull() {
        if (!chargeWanted) return
        // The page is what shows on a lit back: its moment, then it goes. Sent away, under another page or dark: it
        // just ends (the moment is skipped, not kept).
        if (chargeOnTop() && nav.lit && rearLit(rearDisplay()) == true) {
            Log.i(TAG, "charge: the battery is full — \"100\" stands a moment, then the page goes")
            fullMoment(cameNow = false)
        } else {
            Log.i(TAG, "charge: the battery is full — the page is not what shows: it ends without its moment")
            chargeRests = true
            chargeOver()
        }
    }

    /**
     * FULL: THE CHARGING PAGE'S ONE SHORT MOMENT. Come just now (the charger went in at full) it
     * has the page's own rise, [ChargeOverlay.FILL_MS], and [FULL_STANDS_MS] more; already up as the battery reaches
     * full, [FULL_REACHED_MS]. Then charging is not live any more and the page slides out (RearNav's LiveEnds).
     */
    private fun fullMoment(cameNow: Boolean) {
        chargeFullMoment = true
        runCatching {
            holdHandler.removeCallbacks(fullEnds)
            holdHandler.postDelayed(fullEnds, if (cameNow) ChargeOverlay.FILL_MS.toLong() + FULL_STANDS_MS else FULL_REACHED_MS)
        }
    }
    private val fullEnds = Runnable {
        if (!chargeFullMoment) return@Runnable
        Log.i(TAG, "charge: full — its moment is over, the page goes")
        chargeRests = true
        chargeOver()
    }
    @Volatile private var chargeFullMoment = false
    /** A debug build's made-up full moment ("fullmoment" in pop-demo): marked "Demo" on the page, and no charger is looked for. */
    @Volatile private var chargeDemo = false
    /** The page is leaving because the battery is full, not because the charger came out: by its slide, not by draining. */
    @Volatile private var chargeRests = false

    // -- the wake animation over Xiaomi's rear (ShellWake) -------------------------------------

    // -- the scene: clips and the clock over Xiaomi's rear screen (ShellScene) ------------------

    private val scene: ShellScene by lazy {
        ShellScene(
            layerStack = ::rearLayerStack,
            geometry = { geometry },
            lit = { rearLit(rearDisplay()) == true },
            litRecently = ::rearLitRecently,
            onEnded = { syncTouchWatch() },
            faces = faceSource,
            moonLoop = { clipPath(sceneLoop) },
            sensors = { context?.getSystemService(android.hardware.SensorManager::class.java) },
            panelHz = ::rearPanelHz,
        )
    }

    /** The back's panel's refresh rate now (its mode's, not an app's render rate); 0 when it does not say. */
    private fun rearPanelHz(): Float = runCatching {
        context?.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(rearDisplay())?.mode?.refreshRate ?: 0f
    }.getOrDefault(0f)

    /** PanelConfig.sceneOn, sceneWake, sceneLoop and the face's look, from the last payload. */
    @Volatile private var sceneOn = true
    @Volatile private var sceneWake = ""
    @Volatile private var sceneLoop = ""
    @Volatile private var sceneLook = SceneFace.Look()

    private fun sceneWanted() = sceneOn && (sceneWake.isNotEmpty() || sceneLoop.isNotEmpty())

    /** A clip's file by its name, in the app's clips folder, which the shell may read (ClipStore). */
    private fun clipPath(name: String): String? =
        name.takeIf { it.isNotEmpty() && !it.contains('/') }?.let { "$CLIPS_DIR/$it" }

    /**
     * The scene as the back wakes; false when it is not to play, and why is logged. Never over an
     * app of ours on the back, the camera, or a pane of the shell's.
     */
    private fun playSceneOnWake(seenAt: Long, cameraBusy: Boolean): Boolean {
        if (!companion || !sceneWanted()) return false
        val why = when {
            guestOnTop() -> "an app is on the back"
            cameraBusy -> "a camera is in use"
            seenAt < bannerWaitUntil -> "a notification woke it"
            synchronized(sheetLock) { sheetUp || beginPending || controlCenter.isUp || notices.isUp } -> "the shell has the back"
            charge.isUp -> "the charge is showing"
            else -> null
        }
        if (why != null) { Log.i(TAG, "scene: not played — $why"); return false }
        val started = scene.play(faceWakePath(), faceLoopPath(), seenAt, waitForLight = false)
        if (started) { Log.i(TAG, "scene: ${if (shownFaceKey() != FaceCatalog.MOON) faceTitle(faceKey) else "$sceneWake then $sceneLoop"}"); syncTouchWatch() }
        return started
    }

    override fun playScene(what: String?) {
        if (!companion) return
        when (what) {
            "stop" -> scene.dismiss(ShellScene.FADE_OUT_MS, "stopped from the settings")
            "wake", "loop" -> {
                val display = rearDisplay()
                if (cameraUp() || cameraHasRear(display) || guestOnTop()) { Log.i(TAG, "playScene: the back is taken — not played"); return }
                val wake = if (what == "wake") faceWakePath() else null
                val loop = faceLoopPath()
                if (scene.play(wake, loop, android.os.SystemClock.uptimeMillis(), waitForLight = true)) syncTouchWatch()
            }
            else -> Log.w(TAG, "playScene: \"$what\" is nothing")
        }
    }

    // -- staying on: the back lit with the scene on it, for as long as the app's posture says ---
    //
    // The app decides (service/StayOn: in use in the hand, or lying face down, and not in a
    // pocket, not on its back, not switched off by hand) and says so with setStay. Here that is:
    // a bright wake lock on the rear display alone (DisplayWakeLock: it lights nothing by itself
    // and dies with this process), a wake if the back is dark, and the scene kept up: started
    // when the back lights, brought back a few seconds after a finger last touched the back
    // (the finger dismissed it to reach Xiaomi's screen), never over an app or the camera.

    @Volatile private var stay = 0
    private val stayLock = DisplayWakeLock("AiUsage:stay-on")
    private val stayGuard = Any()
    @Volatile private var stayWokeAt = 0L
    /** When the app last said the level (uptime). It says it again every few seconds while the back is to be lit. */
    @Volatile private var staySaidAt = 0L
    @Volatile private var touchToldAt = 0L

    /** The front's dim while the phone lies on its front (FrontDim): on or off as its rule says now. */
    private val frontDim by lazy { FrontDim(holdHandler) { context?.getSystemService(android.hardware.SensorManager::class.java) } }
    private fun syncFrontDim(why: String) {
        val want = companion && FrontDim.wanted(stay, android.os.SystemClock.uptimeMillis() - staySaidAt, call != null, frontDim.lifted)
        if (want != frontDim.isOn) frontDim.set(want, if (want) "lying face down" else why)
    }

    override fun setStay(level: Int, why: String?) {
        val next = level.coerceIn(0, STAY_GAIN.size - 1)
        staySaidAt = android.os.SystemClock.uptimeMillis()
        // Said again, nothing new: the app confirming it (StayOn.CONFIRM_MS).
        if (next == stay) return
        Log.i(TAG, "stay: ${STAY_NAME[next]} — ${why.orEmpty()}")
        // Face down afresh: the tilt's last word (he had lifted it) is forgotten.
        if (next == FrontDim.FACE_DOWN_LEVEL && stay != next) frontDim.fresh()
        // Lit by the rule again: the panel's level read afresh, not one kept from before.
        if (stay == 0 && next > 0) panelKept.forget()
        stay = next
        syncFrontDim("the stay rule")
        applySceneLook()
        runCatching { holdHandler.removeCallbacks(lightTick); if (next > 0) holdHandler.postDelayed(lightTick, 500L) }
        runCatching { holdHandler.removeCallbacks(stayTick); holdHandler.post(stayTick) }
    }

    /**
     * Debug builds: a pop-up shown on demand, for a look at one in an app's colour without waiting for
     * a message. A file `pop-demo` in the app's files folder, one line "RRGGBB|package|from|message",
     * is shown once and removed.
     */
    private fun popDemo() {
        if (!BuildConfig.DEBUG) return
        val file = java.io.File(POP_DEMO_FILE)
        if (!file.exists()) return
        val line = runCatching { file.readText().trim() }.getOrDefault("")
        runCatching { file.delete() }
        val parts = line.split('|')
        if (parts[0] == "island") {
            // No activity launch and no setting write. The third field spends the shell's one-use debug token.
            if (parts.size != 3) return
            val style = when (parts[1]) {
                "tint" -> dev.kb896.aiusage.data.MessageStyle.TINT
                "sweep" -> dev.kb896.aiusage.data.MessageStyle.SWEEP
                else -> return
            }
            val token = file.resolveSibling(dev.kb896.aiusage.DebugLock.FILE)
            val stored = runCatching { token.readText() }.getOrNull()
            val verdict = dev.kb896.aiusage.DebugLock.verdict(BuildConfig.DEBUG, true, parts[2], stored,
                token.lastModified(), System.currentTimeMillis())
            if (!verdict.open || !token.delete()) return
            if (RearNav.seen(nav) != RearNav.Seen.MOON || rearLit(rearDisplay()) != true) {
                Log.i(TAG, "island: demo skipped because the face is not visible")
                return
            }
            val pkg = "com.whatsapp"
            demoIcon(pkg)
            popUp(NoticeShade.Item("demo:${android.os.SystemClock.uptimeMillis()}", pkg, demoLabel(pkg), "Demo",
                "Running ten minutes late", color = 0xFF25D366.toInt()), style)
            return
        }
        // "nolive" takes the live page off the back for a look at something else, FOR THREE MINUTES at most: a real
        // timer of his says itself only once (Xiaomi's Clock), so what the app last told is put back ("relive", or by itself).
        // "nolive": every DEMO's live page down, now. ONLY DEMOS: it used to take whatever was live off the back, his own
        // call or timer too (a test's clean-up must never end a real call's page).
        // "hidelive" is the old word, for a look at the back without his live page, three minutes at most.
        if (parts[0] == "nolive") {
            holdHandler.removeCallbacks(navigationDemoEnds)
            navigationDemoEnds.run()
            holdHandler.removeCallbacks(demoLiveEnds)
            demoLiveEnds.run()
            return
        }
        if (parts[0] == "hidelive") { timer = null; call = null; syncMediaCard(atOnce = true); runCatching { holdHandler.removeCallbacks(liveBack); holdHandler.postDelayed(liveBack, 180_000L) }; return }
        if (parts[0] == "relive") { holdHandler.removeCallbacks(liveBack); liveBack.run(); return }
        // "face|moon", "face|xiaomi:<id>": that face, as a tap in the picker would choose it (a test goes back to the moon
        // so, never by a stroke: a left-to-right stroke on the moon opens the Camera). "faces": a scan now, said in the log.
        // "picker-leave": the picker shut without choosing, as a stroke up does.
        if (parts[0] == "face") { parts.getOrNull(1)?.let { chooseFace(it, "debug") }; return }
        // THE DUST FACE MADE TO REACT WITHOUT HIS HANDS ("face|dust" shows it; the finger's part is the made-up touches'):
        // "dust|push|ax|ay|ms" the phone moved and stopped (m/s² at its briskest along the back as he sees it, x right, y
        // down; default 3 to the right over 500 ms, about 12 cm), "dust|turn|deg|ms" turned about the back's own axis and
        // back (clockwise to him; default 30 degrees over 800 ms), "dust|tilt|deg|ms" tipped left or right and back
        // (default 15 over 1500 ms), as its two sensors would say it (DustMotion.Made). "dust": whether it shows and its
        // sensors are on, in the log.
        if (parts[0] == "dust") {
            fun num(i: Int, def: Float) = parts.getOrNull(i)?.toFloatOrNull() ?: def
            val none = FloatArray(3)
            when (parts.getOrNull(1)) {
                "push" -> scene.dustMove(DustMotion.Made(none, pushX = num(2, 3f), pushY = num(3, 0f), pushS = num(4, 500f).coerceIn(20f, 3000f) / 1000f))
                "turn" -> scene.dustMove(DustMotion.Made(none, turn = Math.toRadians(num(2, 30f).toDouble()).toFloat(), turnS = num(3, 800f).coerceIn(100f, 5000f) / 1000f))
                "tilt" -> scene.dustMove(DustMotion.Made(none, tilt = Math.toRadians(num(2, 15f).toDouble()).toFloat(), turnS = num(3, 1500f).coerceIn(100f, 5000f) / 1000f))
            }
            Log.i(TAG, "dust: ${if (scene.dustShowing) "showing" else "not showing"}, its sensors ${scene.dustSensing}")
            return
        }
        if (parts[0] == "faces") { scanFaces("debug", sha1 = true); return }
        if (parts[0] == "picker-leave") { picker.leave(); return }
        if (parts[0] == "uncover") {
            val seconds = (parts.getOrNull(1)?.toLongOrNull() ?: 8L).coerceIn(1L, 20L)
            uncoverUntil = android.os.SystemClock.uptimeMillis() + seconds * 1000L
            holdHandler.removeCallbacks(coverAgain); holdHandler.postDelayed(coverAgain, seconds * 1000L)
            syncCover("debug: Xiaomi's own screen shown for $seconds s")
            // The charging page is a layer of its own that the cover's rule does not take down (a rear recording of
            // Xiaomi's pin showed our charging page for all of its nine seconds): said, so whoever
            // records sends it away first (one stroke down on it) and brings it back after.
            if (charge.isUp) Log.i(TAG, "debug: the charging page is still up over Xiaomi's screen — send it away first")
            return
        }
        if (parts[0] == "cover") { holdHandler.removeCallbacks(coverAgain); coverAgain.run(); return }
        if (parts[0] == "pins") {
            val made = line.substringAfter('|', "").split("||").map { it.trim() }.filter { it.isNotEmpty() }.take(dev.kb896.aiusage.ui.PinActivity.MOST)
            arrangeDrop("made-up notes are coming")
            if (!demoPinsUp) ownPins = pins
            demoPinsUp = true
            noteTurn.end()
            val now = System.currentTimeMillis()
            pins = made.mapIndexed { i, text -> dev.kb896.aiusage.data.Pin("$text (Demo)", now - i * 60_000L - 1_000_000_000L) }
            navStep(RearNav.Input.PinsAre(pins.size), "made-up notes")
            pinsPage.changed()
            holdHandler.removeCallbacks(demoPinsEnd); holdHandler.postDelayed(demoPinsEnd, 60_000L)
            Log.i(TAG, "notes: ${pins.size} made-up notes for a look (his own are aside)")
            return
        }
        if (parts[0] == "nopins") { holdHandler.removeCallbacks(demoPinsEnd); demoPinsEnd.run(); return }
        // "arrive|Place|words||words": made-up notes whose place he has just reached, marked "Demo", his own aside (back at
        // "noarrive" or after a minute; their bin and "not now" tell the app nothing). The sound is the app's (PlacePinActivity's debug word).
        if (parts[0] == "arrive") {
            val rest = line.substringAfter('|', "")
            val place = rest.substringBefore('|').trim().ifEmpty { "Gym" }
            val words = rest.substringAfter('|', "").split("||").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("A note for this place") }.take(3)
            if (!demoArrivalsUp) ownArrived = pins
            demoArrivalsUp = true
            val now = System.currentTimeMillis()
            val made = words.mapIndexed { i, text -> dev.kb896.aiusage.data.Pin("$text (Demo)", now - 1_000_000_000L - i, 0.0, 0.0, radius = 150, place = place, arrivedAt = now - i, watched = true) }
            arrivalsPlayed?.removeAll(made.map { it.arrivedAt }.toSet())
            arrived = made
            arrivalTurn.end()
            navStep(RearNav.Input.ArrivalsAre(0), "made-up arrivals")
            navStep(RearNav.Input.ArrivalsAre(made.size), "made-up arrivals")
            arrivalPage.changed()
            holdHandler.removeCallbacks(demoArrivalsEnd); holdHandler.postDelayed(demoArrivalsEnd, 60_000L)
            Log.i(TAG, "arrival: ${made.size} demo arrivals")
            return
        }
        if (parts[0] == "noarrive") { holdHandler.removeCallbacks(demoArrivalsEnd); demoArrivalsEnd.run(); return }
        // "pinbeam-gl|words": the same arrival through OpenGL with Xiaomi's own card model (plumbing; not the pin's look yet).
        if (parts[0] == "pinbeam-gl") {
            if (faceWanted() && cover.isUp && rearLit(rearDisplay()) == true) { beamMade = true; pinBeam.playGl(parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "A note") }
            else Log.i(TAG, "beam: a demo asked for with the back dark or another's — not shown")
            return
        }
        // "pinbeam|words": a pinned note's arrival on the back with a made-up note, marked "Demo"; nothing is kept.
        if (parts[0] == "pinbeam") {
            if (faceWanted() && cover.isUp && rearLit(rearDisplay()) == true) { beamMade = true; pinBeam.play(parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "A note") }
            else Log.i(TAG, "beam: a demo asked for with the back dark or another's — not shown")
            return
        }
        if (parts[0] == "nomedia") { media = null; syncMediaCard(); return }
        if (parts[0] == "nonotices") { holdHandler.removeCallbacks(demoListEnds); demoListEnds.run(); return }
        // "nav": the navigation's state, said in the log (a regression script asks for it).
        // (With what is live and whether it is a demo's: the script touches a button only on a demo.)
        if (parts[0] == "nav" && parts.size == 1) {
            val live = listOfNotNull(navigation?.let { if (it.demo) "demo navigation" else "live navigation" }, call?.let { if (it.pkg == CALL_DEMO) "demo call" else "live call" }, timer?.let { if (it.pkg == CALL_DEMO) "demo timer" else "live timer" }, media?.let { if (mediaIsDemo) "demo track" else if (it.playing) "live track" else null }, if (chargeWanted) "charger" else null)
            Log.i(TAG, "nav: is ${navName(nav)} [live: ${live.joinToString(", ").ifEmpty { "nothing" }}]")
            // Which way throws a notification out (RearNav.DISMISS_WAY), for the regression to expect: its own line, so the
            // line above stays as older scripts read it.
            val dismissLeft = RearNav.DISMISS_WAY == RearNav.Way.LEFT
            Log.i(TAG, "notices: throws are ${if (dismissLeft) "left" else "right"} dismisses, ${if (dismissLeft) "right" else "left"} snoozes")
            return
        }
        // Short words that are complete as they are; everything else below needs its four parts.
        if (parts.size < 4 && parts[0] !in SHORT_DEMO_WORDS) { Log.i(TAG, "demo: \"${parts[0]}\" needs more parts — not shown"); return }
        if (parts[0] == "nav") {
            // "nav|distance|instruction|eta|progress" (and a sixth part, the line under it): the navigation page with no
            // route there, marked "Demo", taken down after DEMO_LIVE_MS like every demo. Never over his own route, call,
            // timer or music. Its app icon is the installed Maps' own; no arrow or tracker (there is no app picture for them).
            val demo = NavigationDemo.parse(parts) ?: run { Log.i(TAG, "navigation: a demo needs nav|distance|instruction|eta|progress (0 to 1, or empty)"); return }
            synchronized(liveLooking) {
                if (navigationAsTold != null || realLiveDuringNavigationDemo(android.os.SystemClock.uptimeMillis())) {
                    Log.i(TAG, "navigation: a demo asked for while real activity is live")
                    return
                }
                Log.i(TAG, "navigation: a demo page")
                val icon = runCatching { context?.packageManager?.getApplicationIcon(demo.pkg)?.toBitmap(160, 160) }.getOrNull()
                updateNavigation(demo.copy(appIcon = icon))
                syncMediaCard()
                holdHandler.removeCallbacks(navigationDemoEnds)
                holdHandler.postDelayed(navigationDemoEnds, DEMO_LIVE_MS)
            }
            return
        }
        val color = parts[0].toLongOrNull(16)?.let { (0xFF000000L or it).toInt() } ?: 0
        if (parts[0] == "call") {
            // "call|ringing or talking or speaker|who|what": the call page, with no call there. Its buttons act on the demo alone.
            if (navigationAsTold != null || call?.let { it.pkg != CALL_DEMO } == true || timer?.let { it.pkg != CALL_DEMO } == true) { Log.i(TAG, "call: demo skipped while live content is active"); return }
            Log.i(TAG, "call: a demo page")
            val was = call
            val ringing = parts[1] == "ringing"
            if (was?.ringing == true && !ringing) callChangedAt = android.os.SystemClock.uptimeMillis()
            if (was == null) callShownAt = android.os.SystemClock.uptimeMillis()
            timer = null
            call = CallFace.Model("demo", CALL_DEMO, parts[2], parts[3], ringing, if (ringing) 0L else System.currentTimeMillis() - 83_000L, speaker = parts[1] == "speaker", headset = false)
            syncMediaCard(redraw = true)
            demoLiveFor()
            return
        }
        if (parts[0] == "fullmoment") {
            // "fullmoment": the page's short moment at full, WITHOUT TOUCHING THE BATTERY'S STATE: the page says "Demo"
            // under its figure. Not while his own charger is in (his page is up, or was sent away by him).
            if (chargeWanted) { Log.i(TAG, "charge: the full moment's demo is not shown — his own charger is in"); return }
            Log.i(TAG, "charge: the full moment, a demo")
            chargeDemo = true
            showCharge(100, false)
            if (!chargeWanted) chargeDemo = false
            return
        }
        if (parts[0] == "charge") {
            // "charge": the charging page, AT THE BATTERY'S OWN LEVEL, read here. Never a made-up level on the real
            // back (one shown in a test was taken for a fault): other
            // levels are for the unit tests and the settings' preview.
            val battery = exec("dumpsys battery")
            val level = battery.lineSequence().firstOrNull { it.trim().startsWith("level:") }?.substringAfter(':')?.trim()?.toIntOrNull()
            // And only while it really charges: a page asked for after the charger had come out stood on the back for nothing.
            val powered = battery.lineSequence().any { it.contains("powered: true") }
            Log.i(TAG, "charge: the page asked for by the debug file, at the battery's $level${if (powered) "" else " — not charging, not shown"}")
            if (level != null && powered) showCharge(level, false)
            return
        }
        if (parts[0] == "timer") {
            // "timer|running or paused or over|seconds left|seconds in all": the timer page, with no timer there. Its buttons act on the demo alone (the X ends it).
            // A demo never takes the place of something of his own: with a call or a timer of his live, it is not shown.
            if (navigationAsTold != null || call?.let { it.pkg != CALL_DEMO } == true || timer?.let { it.pkg != CALL_DEMO } == true) { Log.i(TAG, "timer: demo skipped while live content is active"); return }
            Log.i(TAG, "timer: a demo page")
            val left = (parts[2].toLongOrNull() ?: 0L) * 1000L
            call = null
            timer = TimerFace.Model("demo", CALL_DEMO, "Timer", System.currentTimeMillis() + left, left, (parts[3].toLongOrNull() ?: 0L) * 1000L, paused = parts[1] == "paused", over = parts[1] == "over")
            syncMediaCard(redraw = true)
            demoLiveFor()
            return
        }
        if (parts[0] == "musicsound") {
            // "musicsound": a made-up 20 s course for the music page's body (quiet, a build, a drop with kicks, quiet), over a
            // DEMO track's page only ("media|playing|…" first), never over his music. It ends by itself.
            if (mediaIsDemo && media?.playing == true) musicBody.demo(android.os.SystemClock.uptimeMillis())
            else Log.i(TAG, "music sound: a made-up course is only for a demo track's page (media|playing|… first)")
            return
        }
        if (parts[0] == "media") {
            // "media|playing or paused|title|artist": the playing card, with no player there.
            Log.i(TAG, "media: a demo card")
            // A fifth part: a picture file to stand for the cover.
            val was = media
            val cover = parts.getOrNull(4)?.takeIf { it.isNotBlank() }?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it) }.getOrNull() }
            if (parts[1] == "playing" && was?.playing != true) musicStarts++
            media = Media(parts[2], parts[3], parts[1] == "playing", cover)
            if (was != null && was.playing != (parts[1] == "playing")) musicFlippedAt = android.os.SystemClock.uptimeMillis()
            mediaAliveAt = android.os.SystemClock.uptimeMillis()
            mediaIsDemo = true
            syncMediaCard(redraw = true)
            demoLiveFor()
            return
        }
        if (parts[0] == "notices" || parts[0] == "notices-logos") {
            // "notices|RRGGBB|package|from|message||RRGGBB|package|from|message…": a made-up list in the place of his own,
            // for three minutes or until "nonotices". Nothing of his is shown, changed or dismissed by it.
            // "notices-logos|…" is the same list with the page of logos first (NoticePages.logosFirst), the other reading.
            val made = line.substringAfter('|').split("||").mapIndexedNotNull { i, one ->
                // A last part "+Reply;Mark as read" is the row's buttons (they do nothing).
                val all = one.split('|')
                val acts = all.last().takeIf { all.size > 4 && it.startsWith("+") }?.drop(1)?.split(';')?.filter { it.isNotBlank() }.orEmpty()
                val f = if (acts.isEmpty()) all else all.dropLast(1)
                if (f.size < 4) null else NoticeShade.Item(
                    "demo:$i", f[1], demoLabel(f[1]), f[2], f.drop(3).joinToString("|"), time = System.currentTimeMillis() - i * 60_000L, actions = acts,
                    color = f[0].toLongOrNull(16)?.let { (0xFF000000L or it).toInt() } ?: 0,
                )
            }
            Log.i(TAG, "notices: a demo list of ${made.size}")
            made.forEach { demoIcon(it.pkg) }
            // "notices|" with nothing (or "notices|empty"): the pull with nothing waiting, played once at once by the clock
            // (the rim's light). The real list is neither read nor put aside, and no notification is touched.
            if (made.isEmpty() && parts[0] == "notices") {
                if (!notices.emptyDemo()) Log.i(TAG, "notices: the rim's demo not played — the notifications are up")
                return
            }
            notices.demo(made, logosFirst = parts[0] == "notices-logos")
            runCatching { holdHandler.removeCallbacks(demoListEnds); holdHandler.postDelayed(demoListEnds, 180_000L) }
            return
        }
        Log.i(TAG, "notices: a demo pop-up")
        demoIcon(parts[1])
        popUp(NoticeShade.Item("demo:${android.os.SystemClock.uptimeMillis()}", parts[1], demoLabel(parts[1]), parts[2], parts.drop(3).joinToString("|"), color = color))
    }

    /** An app's own icon for the usage page's accounts (Claude's, ChatGPT's), cached for each opening of the page. */
    private val usageAppIcons = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    private val usageAppMissing = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private fun usageAppIcon(pkg: String): android.graphics.Bitmap? {
        usageAppIcons[pkg]?.let { return it }
        if (pkg.isEmpty() || pkg in usageAppMissing) return null
        val bmp = runCatching { context?.packageManager?.getApplicationIcon(pkg)?.toBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888) }.getOrNull()
        if (bmp == null) usageAppMissing += pkg else usageAppIcons[pkg] = bmp
        return bmp
    }

    /**
     * A finger our reader saw while the back was DARK: logged, and in a debug build kept in the app's
     * `files/stay-notes.txt` (the log holds two minutes). It answers whether the rear panel reports touches while
     * its display is off, which decides whether a tap can wake a dark back by our own means (DarkBack).
     */
    private fun noteDarkTouch(what: String) {
        Log.i(TAG, "touch: $what on the dark back")
        if (!BuildConfig.DEBUG) return
        runCatching {
            val file = java.io.File(POP_DEMO_FILE).resolveSibling("stay-notes.txt")
            if (file.length() > 16_000L) return
            file.appendText("${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $what on the dark back (seen by the shell's touch reader)\n")
        }
    }

    private val demoListEnds = Runnable { Log.i(TAG, "notices: demo list ended"); notices.demo(null) }

    /** A made-up notification's app name is its app's own too. */
    private fun demoLabel(pkg: String): String = runCatching {
        val pm = context?.packageManager ?: return ""
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("")

    /** A made-up notification's icon is its app's own, read here: the demo shows what a real one shows. */
    private fun demoIcon(pkg: String) {
        runCatching { context?.packageManager?.getApplicationIcon(pkg)?.toBitmap(96, 96) }.getOrNull()?.let { notices.lend(pkg, it) }
    }

    private val stayTick: Runnable = object : Runnable {
        override fun run() {
            popDemo()
            // The truth read: every ten seconds while the log is followed (each of its lines is read after at once and
            // once more when it has settled), every tick while it is not, or for ten seconds after it was started again
            // (FocusReread). While it is not followed this read is the only one: the dump itself, as before (force).
            val logSince = focusLogSince
            if (faceWanted() && FocusReread.due(android.os.SystemClock.uptimeMillis(), focusReadAt, logSince)) runCatching { readRearFocus(force = logSince == 0L) }
            runCatching { syncMediaCard() }
            // The front's dim is kept only while the app goes on saying "face down", and never through a call.
            runCatching { syncFrontDim(if (call != null) "a call" else "the app has not said face down again") }
            // THE APP HAS GONE SILENT (frozen by Xiaomi's night mode, killed, hung): the level it left is not kept.
            // This process is never frozen and holds the wake lock, and a back held lit keeps the whole phone
            // awake; so without the app's word for half a minute the back is let go, and nothing here wakes it.
            if (stay > 0 && StaySilence.letGo(stay, android.os.SystemClock.uptimeMillis() - staySaidAt, dev.kb896.aiusage.service.FrontScreen.isOn(rearState(android.view.Display.DEFAULT_DISPLAY) ?: android.view.Display.STATE_OFF))) {
                Log.i(TAG, "stay: off — the app has not said so for ${StaySilence.SILENT_MS / 1000} s and the front is off")
                stay = 0
                applySceneLook()
            }
            val want = stay > 0 && companion && sceneWanted()
            synchronized(stayGuard) {
                if (!want) {
                    stayLock.release()
                    // Asked to stay but with nothing to show yet (the settings arrive a moment after
                    // the level on a new shell): looked at again, not given up.
                    if (stay > 0) runCatching { holdHandler.postDelayed(this, STAY_TICK_MS) }
                    return
                }
                val display = rearDisplay()
                stayLock.acquire(display)
                val now = android.os.SystemClock.uptimeMillis()
                val light = WakeStyle.light(rearState(display))
                if (light != WakeStyle.Light.ON) {
                    // Dark, and it is to be on: woken, but not again and again against whatever put it down.
                    // AND NOT AGAINST THE POCKET WATCH (the review's 36, seen on the phone: the phone
                    // lying on its back in use, the sensor beside the back covered: "pocket: covered while lit —
                    // switching the back off", this loop's WAKEUP three seconds later, round and round every 6 s). The
                    // back it switched off stays off until the cover is gone (pocketEnd) or somebody asks for it (wake).
                    if (pocketed) Unit
                    else if (now - stayWokeAt > STAY_REWAKE_MS) {
                        stayWokeAt = now
                        exec("input -d $display keyevent KEYCODE_WAKEUP")
                    }
                } else if (!scene.isUp && sceneMayReturn(now)) {
                    if (scene.play(null, faceLoopPath() ?: faceWakePath(), now, waitForLight = false)) {
                        Log.i(TAG, "scene: back up")
                        syncTouchWatch()
                    }
                }
            }
            runCatching { holdHandler.postDelayed(this, STAY_TICK_MS) }
        }
    }

    /** Whether the scene may come back over Xiaomi's screen now: nobody touching, nothing else of ours or the camera's there. */
    private fun sceneMayReturn(now: Long): Boolean {
        // Wherever the cover is up the scene belongs on it: the cover is up exactly when the back is ours.
        if (!cover.isUp || charge.isUp) return false
        return !synchronized(sheetLock) { sheetUp || beginPending || controlCenter.isUp }
    }

    override fun setBattery(level: Int, charging: Boolean) {
        sceneLook = sceneLook.copy(level = level, charging = charging)
        scene.look(sceneLook)
        // The charging page is for a battery that is charging, whatever brought it up and whatever word of the
        // charger coming out was missed: not charging, it drains away.
        val was = batteryCharging
        batteryCharging = charging
        if (!charging && (charge.isUp || chargeWanted)) { Log.i(TAG, "charge: the battery is not charging — the page goes"); chargeOver() }
        // Charging, and nobody has said the charger went in (this process started with it in): it is live all the same.
        if (charging && was != true && !chargeWanted && companion && chargeOn) { Log.i(TAG, "charge: charging as found — its page is the live page"); showCharge(level, false) }
    }

    /** Whether the battery is charging, as the app last said; null before it has said anything. */
    @Volatile private var batteryCharging: Boolean? = null

    /** The rear as it is now, a JPEG at half size: small enough for the binder, enough for a preview. */
    override fun rearShot(): ByteArray? = runCatching {
        val id = rearPhysicalId() ?: return null
        val tmp = java.io.File("/data/local/tmp/chicken-rear-shot.png")
        exec("screencap -d $id -p ${tmp.path}")
        val full = android.graphics.BitmapFactory.decodeFile(tmp.path) ?: return null
        tmp.delete()
        val half = android.graphics.Bitmap.createScaledBitmap(full, full.width / 2, full.height / 2, true)
        java.io.ByteArrayOutputStream().use { out ->
            half.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }
    }.onFailure { Log.w(TAG, "rearShot: ${it.message}") }.getOrNull()

    // -- rear focus and camera-use events ------------------------------------------------------

    private val rearFocusWatchLock = Any()
    private var rearFocusWatch: Thread? = null
    private var rearFocusLog: Process? = null

    /** Follow activity changes for the cover and camera-use probe while companion mode is active. */
    private fun syncRearFocusWatch() = synchronized(rearFocusWatchLock) {
        val want = companion
        val running = rearFocusWatch?.isAlive == true
        if (want == running) return@synchronized
        if (!want) {
            LogWatch.stop(rearFocusLog)
            rearFocusLog = null
            rearFocusWatch = null
            return@synchronized
        }
        rearFocusWatch = Thread({
            // Orphans of an older build's watchers, still reading the log for nobody: ended as this one starts.
            LogWatch.sweep(::exec).let { if (it > 0) Log.i(TAG, "log watch: $it stale watchers ended") }
            // The camera service's word, registered now: its first statuses are in long before a probe looks.
            cameraWord
            while (companion && !Thread.currentThread().isInterrupted) {
                // THE SYSTEM AND EVENT LOGS ONLY (ActivityTaskManager is the system's, the wm_ lines the event log's): all
                // of the logs was every line of every app for a few tags, filtered here. Xiaomi's pin animation's own
                // lines are in the main log: they are followed only while a pin plays (followPinLog).
                val proc = try {
                    LogWatch.start(FOCUS_LOG_BUFFERS, "ActivityTaskManager:I", "wm_set_resumed_activity:I", "wm_task_moved:I", "wm_on_top_resumed_gained_called:I")
                } catch (e: Exception) { Log.w(TAG, "rear focus watch: no logcat (${e.message})"); return@Thread }
                synchronized(rearFocusWatchLock) { rearFocusLog = proc }
                focusLogSince = android.os.SystemClock.uptimeMillis()
                try {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            // The apps on a display may have changed (the system's event log says so as it happens): the cover follows at once.
                            if (RearCover.changed(line)) { onRearFocusLine(line); continue }
                            // Any activity coming up may be one that opens a camera: asked of the camera service, off this thread.
                            if (line.contains("Displayed ")) runCatching { cameraProbe.execute { probeCamera() } }
                        }
                    }
                } catch (_: java.io.IOException) {
                } catch (e: Throwable) { Log.w(TAG, "rear focus watch: ${e.message}") }
                focusLogSince = 0L
                LogWatch.stop(proc)
                // logcat ended (the log was cleared, the daemon restarted): again, after a breath.
                try { Thread.sleep(2_000L) } catch (_: InterruptedException) { return@Thread }
            }
        }, "rear-focus").apply { isDaemon = true; start() }
        Log.i(TAG, "rear focus: watching for activity changes")
    }

    // -- a camera open on the phone, for the back at full while a picture is taken ---------------
    //
    // The app's CameraManager callback never fired for Xiaomi's Camera on this phone,
    // so the shell asks the camera service itself: once after any activity comes up, and then
    // every two seconds for as long as a camera stays open. The app is told (IRearEvents.onCameraOpen).
    // Asked of its dump until the service's own word, registered here, has been seen to say the same (CameraWord);
    // from then on a look the word says no camera is open for takes the word, and nothing is forked (the dump is
    // still read while a camera is open, and at the first look a minute after the last dump).

    private val cameraProbe = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "camera-probe").apply { isDaemon = true } }
    @Volatile private var cameraOpenNow = false

    /** The camera service's own word (CameraWord), registered as the log watch first runs; null where it cannot be had. */
    private val cameraWord: CameraWord? by lazy {
        runCatching {
            val cameras = context?.getSystemService(android.hardware.camera2.CameraManager::class.java) ?: return@runCatching null
            val word = CameraWord()
            cameras.registerAvailabilityCallback(java.util.concurrent.Executor { it.run() }, object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) = word.available(cameraId)
                override fun onCameraUnavailable(cameraId: String) = word.unavailable(cameraId)
            })
            word
        }.onFailure { Log.w(TAG, "camera: the camera service's word cannot be had here (${it.message}); its dump is read as before") }.getOrNull()
    }

    private fun cameraOpen(): Boolean {
        val word = cameraWord
        val now = android.os.SystemClock.uptimeMillis()
        // Believed and saying none is open (and a dump within the minute): no dump. Anything else: the dump decides, as before.
        if (word != null && word.byWord(now)) return false
        val dump = exec(CameraWord.PROBE)
        when (word?.held(dump, now)) {
            CameraWord.Verdict.TRUST -> Log.i(TAG, "camera: the camera service's own word said what its dump said — a look it says none is open for takes it from now on, no dump")
            CameraWord.Verdict.NEVER -> Log.i(TAG, "camera: the camera service's dump names cameras its word to the shell does not — the dump is read as before")
            CameraWord.Verdict.DROPPED -> Log.i(TAG, "camera: the camera service's word and its dump differ (a camera open by ${if (CameraWord.isOpen(dump)) "the dump" else "the word"} alone) — the word is dropped, the dump is read as before")
            else -> Unit
        }
        return CameraWord.isOpen(dump)
    }

    private fun probeCamera() {
        try { Thread.sleep(CAMERA_OPEN_SETTLE_MS) } catch (_: InterruptedException) { return }
        if (cameraOpenNow) return               // already being followed below
        if (!cameraOpen()) return
        cameraOpenNow = true
        Log.i(TAG, "camera: one is open on the phone")
        runCatching { events?.onCameraOpen(true) }
        try {
            do { Thread.sleep(CAMERA_OPEN_POLL_MS) } while (cameraOpen())
        } catch (_: InterruptedException) {
        } finally {
            cameraOpenNow = false
            Log.i(TAG, "camera: closed")
            runCatching { events?.onCameraOpen(false) }
        }
    }

    private val wakeShow: ShellWake by lazy {
        ShellWake(
            layerStack = ::rearLayerStack,
            geometry = { geometry },
            lit = { rearLit(rearDisplay()) == true },
            onEnded = { rearShow.end(RearShow.Kind.WAKE, android.os.SystemClock.uptimeMillis()) },
        )
    }

    /** PanelConfig.wakeAnim and wakeStyle, from the last payload. */
    @Volatile private var wakeOn = true
    @Volatile private var wakeLook = dev.kb896.aiusage.data.WakeAnimStyle.EDGE_GLOW

    /** A banner waiting for the back it woke to light (uptime): that wake plays no animation. */
    @Volatile private var bannerWaitUntil = 0L

    /** The rear's light as last read by a display listener; null when none is listening. On [holdHandler]. */
    private var rearLight: WakeStyle.Light? = null

    /** A "Play on the back" waiting for the wake it asked for, and until when (uptime). */
    @Volatile private var pendingPreview: dev.kb896.aiusage.data.WakeAnimStyle? = null
    @Volatile private var pendingPreviewUntil = 0L

    private fun rearState(displayId: Int): Int? = displayStates.fresh(displayId)

    /**
     * The rear display changed (a display listener, on [holdHandler]): an OFF or DOZE to ON is a
     * wake — Xiaomi's raise or tap, or ours — and may play the wake animation. ON to DOZE (AOD)
     * and anything else: nothing, so AOD is never drawn over.
     */
    private fun rearLightChanged() {
        val seenAt = android.os.SystemClock.uptimeMillis()
        val now = WakeStyle.light(rearState(rearDisplay()))
        val was = rearLight
        rearLight = now
        if (WakeStyle.isWakeEdge(was, now)) onWakeEdge(seenAt)
        if (now != WakeStyle.Light.ON) {
            // Dark while it was to be lit: WHO PUT IT DOWN is asked of the power manager and said (the log is all
            // there is to go by afterwards).
            if (was == WakeStyle.Light.ON && stay > 0) runCatching {
                val top = rearFocus
                popWaiter.execute {
                    val why = exec("dumpsys power | grep -A8 '^groupId: 1' | grep -m1 mLastSleepReason").trim()
                    Log.i(TAG, "dark: the back went dark while it was to be lit (${STAY_NAME[stay]}); on top: ${top ?: "nothing"}; the power manager: ${why.ifEmpty { "no word" }}")
                }
            }
            dimEnds("the back went dark")
            sceneBrightUntil = 0L
            // What it wakes to may be another room: the next reading of the light is taken as it comes.
            lightSteady.fresh()
            if (scene.isUp) scene.dismiss(1L, "the back went dark")
            navStep(RearNav.Input.Dark, "the back went dark")
            if (beamMade) pinBeam.cut()
            musicFace.hide(moving = false); timerFace.hide(moving = false); callFace.hide(moving = false)
            // Off (not dozing: Xiaomi's always-on face is never covered): the curtain goes up ahead of the next wake.
            if (now == WakeStyle.Light.OFF && companion && sceneWanted() && SCENE_ARM_AHEAD) runCatching { popWaiter.execute { if (WakeStyle.light(rearState(rearDisplay())) == WakeStyle.Light.OFF) scene.arm() } }
            else scene.disarm()
        } else {
            // The panel's level read afresh at the next look: one kept from before the dark is not the panel's now.
            panelKept.forget()
            navStep(RearNav.Input.Lit, "the back is lit")
            facesLit()
            runCatching { holdHandler.removeCallbacks(lightTick); holdHandler.postDelayed(lightTick, 500L) }
            if (!scene.isUp && !WakeStyle.isWakeEdge(was, now)) scene.disarm()
            // Whatever happened on the way: a lit back with no scene playing never keeps the curtain.
            runCatching { holdHandler.postDelayed({ if (!scene.isUp) scene.disarm() }, SCENE_CURTAIN_CHECK_MS) }
        }
        // Dark right after a double tap: switched off by hand, and the app keeps it so (ManualOff).
        if (companion && latchWanted && was != null && now != null && ManualOff.byDoubleTap(
                wasLit = was == WakeStyle.Light.ON, nowLit = now == WakeStyle.Light.ON,
                doubleTapAt = lastDoubleTapAt, now = android.os.SystemClock.elapsedRealtime(), guarded = backGuard.on,
            )) {
            Log.i(TAG, "manual off: the back went dark after a double tap — the app's wakes hold back")
            stay = 0
            runCatching { events?.onSwitchedOffByHand() }.onFailure { Log.w(TAG, "manual off: not told — ${it.message}") }
        }
    }

    /** PanelConfig.noticeWake or chargeWhenDark, from the last payload: a wake of the app's own can come. */
    @Volatile private var latchWanted = false

    /** The baseline for [rearLightChanged], read as a listener starts; null as the last one stops. */
    private fun resetRearLight(listening: Boolean) {
        runCatching { holdHandler.post { rearLight = if (listening) WakeStyle.light(rearState(rearDisplay())) else null } }
    }

    private fun onWakeEdge(seenAt: Long) {
        // Every wake counts for the guard, played or not (RearShow.wakeEdge).
        val fresh = rearShow.wakeEdge(seenAt)
        if (companion && CameraExit.isCamera(rearFocus)) {
            scene.disarm()
            return
        }
        val preview = pendingPreview?.takeIf { seenAt < pendingPreviewUntil }
        if (preview != null) {
            pendingPreview = null
            scene.disarm()
            playWakeNow(preview, seenAt, asPreview = true)
            return
        }
        // Use cached ownership and camera-use signals so the first wake frame can proceed without a dump.
        val cameraBusy = cameraUp() || RearCameraRoute.isOurs(rearFocus) || (cameraInUse && android.os.SystemClock.elapsedRealtime() - cameraInUseAt < BAND_WATCH_MS)
        // The scene is the wake animation when one is chosen; otherwise the curtain goes and the old styles may play.
        if (fresh && playSceneOnWake(seenAt, cameraBusy)) return
        scene.disarm()
        if (sceneWanted()) return
        val skip = WakeStyle.skipWhy(companion, wakeOn, wakeLook, guestOnTop(), cameraBusy)
            ?: when {
                !fresh -> "the back woke under ${RearShow.REWAKE_GUARD_MS / 1000} s ago"
                seenAt < bannerWaitUntil -> "a notification woke it"
                synchronized(sheetLock) { sheetUp || beginPending || controlCenter.isUp || notices.isUp } -> "the shell has the back"
                else -> null
            }
        if (skip != null) {
            if (companion && wakeOn) Log.i(TAG, "wake: not played — $skip")
            return
        }
        playWakeNow(wakeLook, seenAt, asPreview = false)
    }

    /** Through [rearShow]: dropped under a charge or a banner. */
    private fun playWakeNow(look: dev.kb896.aiusage.data.WakeAnimStyle, seenAt: Long, asPreview: Boolean) {
        if (look == dev.kb896.aiusage.data.WakeAnimStyle.NONE) return
        val now = android.os.SystemClock.uptimeMillis()
        val wait = if (asPreview) ShellWake.LIT_WAIT_MS else WakeStyle.FIRST_FRAME_MS
        when (val d = rearShow.request(RearShow.Kind.WAKE, now, WakeStyle.durationMs(look) + wait)) {
            is RearShow.Decision.Drop -> { Log.i(TAG, "wake: a ${d.over.name.lowercase()} is up — not played"); return }
            is RearShow.Decision.Start -> Unit
        }
        Log.i(TAG, "wake: ${look.name.lowercase()}${if (asPreview) " (preview)" else ""}")
        wakeShow.play(look, seenAt, asPreview)
    }

    /**
     * "Play on the back" from the settings: a wake style now if the back is lit, or on the wake the
     * app asks for next (ShizukuBridge.playWake); a charge style through [showCharge]'s path at the
     * level given. Never over Xiaomi's rear camera.
     */
    override fun playWake(style: String?) {
        if (!companion) return
        val p = WakeStyle.parsePreview(style) ?: run { Log.w(TAG, "playWake: \"$style\" is no style"); return }
        val display = rearDisplay()
        if (cameraUp() || cameraHasRear(display)) {
            Log.i(TAG, "playWake: Xiaomi's rear camera has the back — not played")
            return
        }
        when (p) {
            is WakeStyle.Preview.Wake -> {
                val now = android.os.SystemClock.uptimeMillis()
                if (rearLit(display) == true) {
                    pendingPreview = null
                    playWakeNow(p.style, now, asPreview = true)
                } else {
                    pendingPreview = p.style
                    pendingPreviewUntil = now + ShellWake.LIT_WAIT_MS
                }
            }
            is WakeStyle.Preview.Charge -> {
                val compact = guestOnTop()
                val t = ChargeStyle.timing(compact)
                val now = android.os.SystemClock.uptimeMillis()
                when (val d = rearShow.request(RearShow.Kind.CHARGE, now, t.totalMs.toLong() + ShellCharge.LIT_WAIT_MS)) {
                    is RearShow.Decision.Drop -> return
                    is RearShow.Decision.Start -> if (d.cut == RearShow.Kind.WAKE) wakeShow.cut(d.fadeMs, "a charge preview")
                }
                Log.i(TAG, "charge preview: ${p.style.name.lowercase()} at ${p.level}%")
                chargeLoose = true
                charge.show(p.level, false, compact, p.style, chargePercent)
                syncTouchWatch()
            }
        }
    }

    override fun setQuickStates(states: BooleanArray?, reachable: Boolean) {
        controlCenter.quickStates(states ?: return, reachable)
    }

    override fun setQuick(which: String, on: Boolean) {
        val cmd = when (which) {
            "bluetooth" -> "cmd bluetooth_manager ${if (on) "enable" else "disable"}"
            "wifi" -> "cmd wifi set-wifi-enabled ${if (on) "enabled" else "disabled"}"
            "data" -> "svc data ${if (on) "enable" else "disable"}"
            else -> return
        }
        Log.i(TAG, "quick $which -> $on: ${exec(cmd)}")
    }

    override fun setRearBrightness(value: Int) {
        val v = value.coerceIn(1, 255)
        // Manual first, or an automatic mode would take the value straight back.
        exec("[ \"$(settings get secure sub_display_screen_brightness_mode)\" = 0 ] || settings put secure sub_display_screen_brightness_mode 0; settings put system sub_display_screen_brightness $v")
    }

    override fun removeXiaomiRearApp(): Boolean {
        // Companion mode keeps Xiaomi's rear screen as the base: never uninstalled, suspended or stopped.
        if (companion) {
            Log.w(TAG, "companion mode — not removing ${Panel.XIAOMI_REAR_APP}")
            return false
        }
        val out = exec("pm uninstall -k --user 0 ${Panel.XIAOMI_REAR_APP}")
        Log.i(TAG, "remove ${Panel.XIAOMI_REAR_APP}: $out")
        if (out.contains("Success")) return true
        // HyperOS 4 (Android 17) refuses that from shell: "only root can delete system app
        // for a particular user", and `pm disable-user` is refused too ("Cannot disable
        // system packages"). Suspending is still allowed, and a suspended rear-screen app
        // cannot bring its launcher up — measured: the lit rear display then shows nothing
        // of Xiaomi's but its two invisible gesture strips. Its launcher may already be up,
        // and suspension does not take down what is running, so it is stopped as well.
        // restoreXiaomiRearApp() already lifts a suspension.
        val suspended = exec("pm suspend --user 0 ${Panel.XIAOMI_REAR_APP}")
        Log.i(TAG, "suspend ${Panel.XIAOMI_REAR_APP}: $suspended")
        if (!suspended.contains("suspended state: true")) return false
        exec("am force-stop ${Panel.XIAOMI_REAR_APP}")
        return true
    }

    override fun restoreXiaomiRearApp(): Boolean {
        val out = exec("pm install-existing --user 0 ${Panel.XIAOMI_REAR_APP}")
        Log.i(TAG, "restore ${Panel.XIAOMI_REAR_APP}: $out")
        // Installed is not the same as allowed to run. A phone that kept this app out of
        // the way by SUSPENDING it (through shell, the other common workaround) gets it
        // back still suspended — present, dead, and looking exactly like a failed
        // restore. Seen on the Xiaomi: suspendingPackage=com.android.shell survived the
        // uninstall, the reinstall and a reboot. Shell suspended it, so shell may lift it.
        val lifted = exec("pm unsuspend --user 0 ${Panel.XIAOMI_REAR_APP}")
        Log.i(TAG, "unsuspend ${Panel.XIAOMI_REAR_APP}: $lifted")
        return out.contains("installed for user", ignoreCase = true) || out.contains("Success")
    }

    /**
     * Rebuild the panel, at most once per [REPLACE_COOLDOWN_MS].
     *
     * The cooldown is insurance against a fight rather than a normal-path delay. A single
     * takeover — the ordinary case — is recovered on the very next tick. But if the system
     * is REPEATEDLY bringing its own rear-screen launcher forward, replacing every tick
     * would put the panel and HyperOS in a visible tug of war, and losing that contest
     * slowly is better than losing it at six-second intervals in front of the user.
     *
     * Deliberately not a strike counter that gives up. It gives up on nothing: the panel
     * is meant to be there, and it keeps asking for as long as the app is running.
     */
    private fun replace(displayId: Int, why: String) {
        // A keep-alive interrupted by hide() mid-tick must not re-place the hidden panel.
        if (Thread.currentThread().isInterrupted || hiddenOnPurpose) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReplaceAt < REPLACE_COOLDOWN_MS) return
        lastReplaceAt = now
        Log.w(TAG, "keep-alive: $why — re-placing")
        show(lastPayload, displayId)
    }

    /**
     * Keeps the panel placed from INSIDE the Shizuku service, on its own thread.
     *
     * UsagePollService already runs a 20s watchdog doing the same job, and this does
     * not replace it — it survives what that one cannot. That watchdog lives in the
     * app process, so it dies with the app: an `adb install` (pm install -r kills the
     * app), a low-memory kill, or MIUI's background management all leave the panel
     * dead with nothing left running to notice. Observed exactly that on device — no
     * app process, no services, panel gone, and no path back without user action.
     *
     * This thread lives in Shizuku's user-service process at shell uid, which Shizuku
     * manages independently of the app's lifecycle, so it keeps checking through all
     * of the above.
     *
     * The two guards match the app-side watchdog's, for the same reasons:
     *   - nothing happens while the keyguard is up, because re-placing means `am start`
     *     on display 0, which on a locked phone draws over the lock screen and wakes
     *     the main display;
     *   - only a definite "not composited on the rear" triggers a re-place, so an
     *     unparseable dump or a momentary gap does not restart a healthy panel.
     */
    private fun startKeepAlive(displayId: Int) {
        if (keepAlive?.isAlive == true) return
        // A service starting: anything its predecessor left on the freezer's list comes off.
        syncNoRestrict()
        keepAlive = guarded {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(KEEPALIVE_MS)
                    // No keyguard skip. It was here because a respawn woke the main
                    // screen — RearActivity carried FLAG_TURN_SCREEN_ON and is created
                    // on display 0 before being moved. The flag is gone and the wake is
                    // now aimed at the rear display alone, so the watchdog can do its
                    // job while the phone is asleep, which is exactly when the panel
                    // needs it: double-tapping the rear display awake and finding
                    // nothing there was this skip.
                    
                    // NOTHING TO RECOVER ON A DISPLAY THAT IS OFF ON PURPOSE. Every
                    // reading below is about where the panel is being composited, and a
                    // dark display composites nothing of ours and often something of
                    // MIUI's — which reads as "taken" and gets the panel re-placed, and
                    // re-placing wakes it. Skipping the tick is also simply correct: the
                    // panel cannot be in the wrong place on a screen nobody can see, and
                    // the moment anything lights it again this resumes.
                    // Except over a guest on top: the panel's own sleep timer runs out behind
                    // it, and skipping would stop the guest being watched at all. A rear that
                    // is really dark shows here as nothing on top (GUEST_BEHIND).
                    // The touch watch's safety net: a getevent that died is restarted here.
                    syncTouchWatch()
                    // The guest hold's net under its display listener: see GuestHold.
                    syncGuestHold()
                    if (dozing && guest?.onTop != true) continue

                    // The App Portal's guest first: its own rule (GuestGate), and the usual
                    // recovery below only when that rule says so.
                    val g = guest
                    if (g != null && g.onPrivate) {
                        if (privateTick(g, displayId)) continue
                    } else if (g != null) {
                        val entry = GuestTask.byId(exec("am stack list"), g.task)
                        // The guest's window may belong to another package — a permission
                        // dialog, a chooser — started into its task; on top, that is still
                        // the guest, not something that has taken the rear.
                        val taskTop = entry?.top?.substringBefore('/')?.takeIf { it.isNotEmpty() }
                        val rear = rearPhysicalId()
                        // known: SurfaceFlinger shortens long layer names with [...], and a
                        // shortened one is matched back to whichever of these it starts.
                        val known = listOfNotNull(g.pkg, PKG, taskTop)
                        val top = SurfaceDump.topPackage(
                            exec("dumpsys SurfaceFlinger"),
                            isRear = { id -> if (rear != null) id == rear else id.endsWith(REAR_PHYSICAL_ID_SUFFIX) },
                            known = known,
                        ).let { if (it != null && it == taskTop) g.pkg else it }
                        val tick = GuestGate.tick(
                            g.pkg, entry != null, entry?.display == displayId, top, PKG,
                            hiddenOnFront = g.hiddenOnFront, onFront = entry?.display == 0,
                            frontLocked = { keyguardShowing() },
                        )
                        // Mid-launch, the guest is the launch's: only a gone one is acted on,
                        // and that is checked again under the lock, after the launch.
                        if (!g.placed && tick != GuestGate.Tick.GUEST_GONE) continue
                        when (tick) {
                            GuestGate.Tick.GUEST_ON_TOP -> {
                                // Compare-and-set against this reading: a launch or a guest
                                // action since it was taken wins.
                                // A hidden flag here is stale (it is on the rear): cleared with it.
                                if ((!g.onTop || g.hiddenOnFront) &&
                                    guestRef.compareAndSet(g, g.copy(onTop = true, hiddenOnFront = false))) {
                                    syncGuestHold()
                                    syncSheetGuest()
                                }
                                // Every tick, but a comparison unless something changed (or a
                                // refused command is due a retry).
                                syncDensity("${g.pkg} is on top")
                                // Its bounds drifted from the display's (a relayout, the app's own
                                // doing): shaped again. Under the lock, if it is still the guest.
                                // A few times, not forever: a guest that will not take it is left
                                // as it is until its task or density changes (GuestShape.GiveUp).
                                val key = placeKey(g.task, g.pkg)
                                if (GuestShape.drifted(entry?.bounds, GuestShape.full(geometry))) {
                                    if (driftTries.attempt(key)) synchronized(placing) {
                                        if (guest?.task == g.task) shapeGuest(g.task, g.pkg, "its bounds drifted to ${entry?.bounds}")
                                    } else if (driftTries.announce(key)) {
                                        Log.w(TAG, "portal: ${g.pkg}'s bounds still ${entry?.bounds} after $PLACE_TRIES reshapes — " +
                                            "left as they are until its task or density changes")
                                    }
                                } else if (entry?.bounds != null) driftTries.placed(key)
                                if (guest?.let { it.task == g.task && it.onTop } == true) chromeShow()
                                continue
                            }
                            GuestGate.Tick.GUEST_BEHIND -> {
                                // Behind the panel, it is no longer on top. On a dark rear
                                // (top null) only the frame goes: nothing has covered it, and
                                // it is on top again the moment the display lights.
                                // A hidden flag on a guest that is on the rear is stale (it came
                                // back some other way than Resume): cleared in the same swap.
                                val stale = g.hiddenOnFront && entry?.display == displayId
                                val covered = top != null && g.onTop
                                if ((covered || stale) &&
                                    guestRef.compareAndSet(g, g.copy(onTop = g.onTop && !covered, hiddenOnFront = g.hiddenOnFront && !stale)) &&
                                    covered) {
                                    syncGuestHold()
                                    syncSheetGuest()
                                }
                                syncDensity("${g.pkg} is behind")
                                chromeHide()
                                // A dark rear: nobody can see or touch a sheet up there.
                                if (top == null) closeSheetNow("the rear is dark")
                                // The panel back in front after the camera was: somebody closed it.
                                // Never seen in front: forgotten, or no sheet opens over a guest
                                // behind the panel ever again (cameraUp).
                                if (top == PKG) { if (cameraLaunched && cameraWasOnTop) closeCamera() else forgetUnseenCamera() }
                                continue
                            }
                            // The front unlocked over a guest hidden there by Panel.
                            GuestGate.Tick.GUEST_EXPOSED -> {
                                synchronized(placing) { unhideIfExposed("the front was unlocked") }
                                continue
                            }
                            // Checked again under the lock: this reading may predate a launch
                            // that has since placed (or replaced) the guest, and ending that
                            // one would undo it.
                            GuestGate.Tick.GUEST_GONE -> {
                                synchronized(placing) {
                                    val now = guest
                                    val at = now?.let { GuestTask.byId(exec("am stack list"), it.task)?.display }
                                    // Hidden on the front by Panel is not gone.
                                    val still = at == displayId || (at == 0 && now?.hiddenOnFront == true)
                                    // A DARK rear stays dark: the panel's task is already behind,
                                    // and is simply revealed. Gone too, it is relaunched on the
                                    // next tick that finds it so. Decided by the display itself,
                                    // not by `dozing`: the panel's sleep timer runs out behind a
                                    // guest while the guest keeps the rear lit, and a rear that
                                    // went dark with the power button was never "dozing" at all.
                                    if (now != null && !still) {
                                        if (rearLit(displayId) == true) endGuest("its task left the rear")
                                        else dropGuest("its task left the rear, the rear dark")
                                    }
                                }
                                continue
                            }
                            // Something else has the rear; the usual recovery below puts the
                            // panel over it, and the frame has nothing to frame.
                            GuestGate.Tick.USUAL -> {
                                chromeHide()
                                // Companion mode: Xiaomi's launcher (its home or back swipe) or
                                // another app is over the guest. The guest is over; nothing is
                                // placed, and what came up is what shows.
                                if (GuestGate.onUsual(companion) == GuestGate.Usual.DROP_GUEST) {
                                    synchronized(placing) {
                                        if (guest?.task == g.task) dropGuest("$top has the rear")
                                    }
                                    continue
                                }
                                // And it is shown at the display's own density, not the guest's.
                                syncDensity("another app has the rear", thirdOnTop = true)
                                // The usual recovery re-places, which wakes: not on a panel
                                // asleep on purpose.
                                if (dozing) continue
                            }
                        }
                    }

                    // Companion mode keeps no panel: the guest above is all this watches.
                    if (companion) continue

                    // ONE reading per tick. Each call is a full `dumpsys SurfaceFlinger`,
                    // and the old code asked twice per tick for two of its branches —
                    // which also meant the two answers could disagree across the gap.
                    when (val where = panelWhere()) {
                        // Definitely on the main display: a move is enough, and a move
                        // is cheap and invisible. Never relaunch for this.
                        Where.MAIN -> ensureOnRear(displayId)

                        // Alive, and behind whatever has taken the rear display. A move
                        // cannot fix this — the task is already on the right display, it
                        // is simply not on top — so it needs the full placement, which
                        // goes out through display 0 and comes back to the front.
                        //
                        // This is the case that had no branch. HyperOS puts its own
                        // rear-screen launcher up and our panel goes on existing behind
                        // it: task alive, service running, nothing composited, every
                        // check reporting health, and the user looking at a clock.
                        Where.DISPLACED -> {
                            // Something is in front of the panel. If we put it there,
                            // note that it actually arrived — see closeCamera.
                            if (cameraLaunched) cameraWasOnTop = true
                            // NOT OVER THE CAMERA. Its own rear preview, or the camera this
                            // service opened there, is what the back is for while it lasts:
                            // covering it was the panel taking the viewfinder away six seconds
                            // in. Left, logged on the change; the next tick looks again.
                            val camera = cameraHasRear(displayId)
                            if (camera != cameraHadRear) {
                                cameraHadRear = camera
                                if (camera) Log.i(TAG, "keep-alive: the camera has the rear — not re-placing over it")
                            }
                            if (!camera) replace(displayId, "rear display taken")
                        }

                        // Composited nowhere AND no task: genuinely gone, relaunch.
                        // The task check is what makes this safe — "composited nowhere"
                        // on its own is also what a blanked display reports, and
                        // relaunching on that turned every screen-off into an `am start`
                        // on display 0.
                        Where.NOWHERE -> if (taskId() <= 0) {
                            replace(displayId, "no panel and no task")
                        }

                        // Back in front, and the camera was ahead of us until now:
                        // that is somebody closing it. Nothing else did.
                        Where.REAR -> {
                            cameraHadRear = false
                            adoptPlaced(displayId, "keep-alive")
                            if (cameraLaunched && cameraWasOnTop) closeCamera() else forgetUnseenCamera()
                        }
                    }
                } catch (e: InterruptedException) {
                    return@guarded
                } catch (e: Throwable) {
                    // Any of it, an Error too: a keep-alive that dies leaves the rear unwatched
                    // (and once took the process with it).
                    Log.e(TAG, "keep-alive tick failed", e)
                }
            }
        }.apply { isDaemon = true; name = "rear-keepalive"; start() }
    }

    /** Cheap targeted read — `dumpsys window` in full is megabytes. */
    private fun keyguardShowing(): Boolean =
        exec("dumpsys window | grep -m1 isKeyguardShowing").contains("true")

    /**
     * Moves [task] onto [displayId], and reports whether the command itself complained.
     *
     * `am display move-stack`, NOT `service call activity_task <n>`.
     *
     * This app used to issue binder transaction 50 directly, on the basis that 50 was
     * IActivityTaskManager#moveRootTaskToDisplay. Transaction numbers are assigned by
     * position in the AIDL, so they are not an ABI — they shift whenever a method is
     * added or removed above them. Android 16 shifted this one, and the failure mode was
     * the worst kind: `service call` does no type checking, so the call still returned a
     * success parcel (exception code 0) while doing nothing at all, and every check we
     * had downstream reported a panel that was simply never placed.
     *
     * The named command cannot drift like that. It has existed since Android 8, it is
     * resolved by name, and it prints a stack trace when it is unhappy instead of
     * silently succeeding.
     *
     * Not kept as a fallback, either: whatever slot 50 holds now is unknown, and calling
     * an arbitrary method on the activity manager because it once meant something else
     * is not a fallback, it is a loaded gun.
     */
    private fun moveToDisplay(task: Int, displayId: Int): Boolean {
        val out = exec("am display move-stack $task $displayId")
        val failed = out.contains("Exception", ignoreCase = true) ||
            out.contains("Error", ignoreCase = true)
        if (failed) Log.e(TAG, "move task=$task -> display=$displayId refused: $out")
        // Whatever arrives on the back types on the back: asked again on every arrival, so a
        // policy somebody put back does not stay put back.
        else if (displayId != 0) RearKeyboard.showOn(displayId)
        return !failed
    }

    /** What a checked command came to. */
    private sealed interface Run {
        /** Ran, exit 0, no error printed. */
        data class Ok(val out: String) : Run
        /** Ran and said no: non-zero exit, or an error / exception printed. Known not to have happened. */
        data class Refused(val out: String) : Run
        /** Interrupted, or never started: it may or may not have happened. */
        data object Unknown : Run
    }

    /**
     * [exec] for where a failure must not pass for success, and refused must not pass for unknown.
     *
     * A leftover interrupt flag on the calling thread (the keep-alive's, say) used to make
     * waitFor() throw AFTER the command had completed, which read as Unknown forever. So the
     * flag is taken off before waiting, the command is classified by what it really did, and
     * the flag is put back afterwards for the thread's next sleep to see.
     */
    private fun execChecked(cmd: String): Run {
        var interrupted = false
        try {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }.trim()
            if (Thread.interrupted()) interrupted = true
            var code: Int? = null
            while (code == null) {
                try {
                    code = p.waitFor()
                } catch (e: InterruptedException) {
                    interrupted = true
                    // Finished all the same: its outcome is known. Still running: unknown.
                    if (!p.isAlive) code = p.exitValue() else return Run.Unknown
                }
            }
            // Error text only means a refusal from wm; `settings get` may legitimately print anything.
            val errorText = cmd.startsWith("wm ") &&
                (out.contains("Exception", ignoreCase = true) || out.contains("Error", ignoreCase = true))
            return if (code != 0 || errorText) {
                Log.w(TAG, "exit $code: $cmd: ${out.take(200)}")
                Run.Refused(out)
            } else Run.Ok(out)
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $cmd", e); return Run.Unknown
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** Runs via `sh -c` so PATH resolves am/service/input in the forked process. */
    private fun exec(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
        p.waitFor()
        out.trim()
    } catch (e: InterruptedException) {
        // Kept, so the keep-alive's next sleep sees it and the thread ends as asked.
        Thread.currentThread().interrupt(); ""
    } catch (e: Exception) {
        Log.e(TAG, "exec failed: $cmd", e); ""
    }

    private companion object {
        /** How long a banner for a dark back waits for it to light (the app's wake), and how often it looks. */
        private const val POP_LIT_WAIT_MS = 1_200L
        /** A pop-up held aside by a finger: the navigation ends it at the latest this long after (a lift that never came). */
        private const val POP_HELD_MOST_MS = 15_000L
        private const val POP_LIT_STEP_MS = 40L
        /**
         * Asks the system for no animation of its own as an app starts on the back: the change
         * is covered by ours (GuestCurtain), and its slide under that was still seen moving.
         */
        private const val NO_SLIDE = "--activity-no-animation"
        /** The rear display's power group — see rearPowerGroup. */
        const val REAR_POWER_GROUP = 1
        /** The rear's size unread: how long the 17 Pro's stands in before it is asked again. */
        const val GEOMETRY_RETRY_MS = 60_000L
        /** After a failed re-read of display 1's density, how long before the next exec. */
        const val REREAD_BACKOFF_MS = 30_000L
        /** after a direct start on the rear display, before believing it took */
        private const val DIRECT_START_SETTLE_MS = 1_200L
        private val REPORT = """
echo "device: $(getprop ro.product.marketname) / $(getprop ro.product.model) / $(getprop ro.product.device)"
echo "os: HyperOS $(getprop ro.mi.os.version.name) $(getprop ro.mi.os.version.incremental) | android $(getprop ro.build.version.release) | region $(getprop ro.miui.region) | build $(getprop ro.build.version.incremental)"
echo "rear display: $(wm size -d 1 2>/dev/null | tr -d '\n') $(wm density -d 1 2>/dev/null | tr -d '\n')"
echo "physical ids: $(dumpsys display 2>/dev/null | grep -E 'mDisplayId=|mPrimaryDisplayDevice=' | head -8 | tr -s ' ' | tr '\n' ' ')"
echo "composited: $(dumpsys SurfaceFlinger 2>/dev/null | grep -oE 'Display [0-9]+ \((active|inactive)\) HWC layers' | tr '\n' ' ')"
echo "props: cover_mode=$(getprop persist.sys.dual_screen_cover_mode_enable) multidisplay_type=$(getprop persist.sys.muiltdisplay_type)"
echo "settings: subscreen_display_time=$(settings get system subscreen_display_time) rear_doze_always_on=$(settings get secure rear_doze_always_on) doze_always_on=$(settings get secure doze_always_on) stay_on_while_plugged_in=$(settings get global stay_on_while_plugged_in)"
echo
echo "packages that could own the rear screen:"
for p in $(pm list packages -u 2>/dev/null | sed 's/package://' | grep -iE 'subscreen|mirror|rearscreen|backscreen|tgwgroup' | sort -u); do
  st=$(dumpsys package ${'$'}p 2>/dev/null | grep -E 'User 0:|installed=|suspended=' | head -1 | tr -s ' ')
  echo "  ${'$'}p:${'$'}st"
done
echo
echo "system apps around the rear display: factory build or installed update (code path), and version:"
for p in com.xiaomi.subscreencenter com.xiaomi.mirror com.android.systemui miui.systemui.plugin com.miui.securitycenter com.xiaomi.misubscreenui; do
  cp=$(dumpsys package ${'$'}p 2>/dev/null | grep -E '^ *codePath=' | head -1 | sed 's/ *codePath=//')
  vn=$(dumpsys package ${'$'}p 2>/dev/null | grep -E '^ *versionName=' | head -1 | sed 's/ *versionName=//')
  [ -n "${'$'}cp" ] && echo "  ${'$'}p: ${'$'}vn @ ${'$'}cp"
done
echo "system apps running an installed update:"
pm list packages -s -f 2>/dev/null | grep '/data/app/' | sed 's/.*=//' | sort | xargs -n 5 | sed 's/^/  /'
echo
echo "proximity sensors (the panel's pocket mode wants the one on the back):"
dumpsys sensorservice 2>/dev/null | grep -i prox | grep -viE 'connection|active' | cut -c1-120 | sed 's/^/  /' | head -6
echo
echo "rear launcher components registered:"
dumpsys package 2>/dev/null | grep -oiE '[a-z0-9_.]+/[a-z0-9_.]*Sub[sS]creen[a-zA-Z]*Launcher[a-zA-Z]*' | sort -u | head -8
echo
echo "rear-display settings:"
for ns in system global secure; do settings list ${'$'}ns 2>/dev/null | grep -iE 'subscreen|sub_screen|rear_display|back_screen|rearscreen' | grep -v theme_rear_widget | sed "s/^/  ${'$'}ns: /"; done
echo
echo "--- waking the rear display, then watching it for 16 seconds ---"
logcat -c 2>/dev/null
input -d 1 keyevent KEYCODE_WAKEUP
sleep 3
echo "hold-screen window per display:"
dumpsys window 2>/dev/null | grep -E 'Display: mDisplayId=|mHoldScreenWindow' | tr '\n' ' ' | sed 's/  Display:/\n  Display:/g'; echo
echo "power groups (global, group 0, group 1):"
dumpsys power 2>/dev/null | grep -E 'mWakeLockSummary|mUserActivitySummary' | tr '\n' ' '; echo
dumpsys power 2>/dev/null | sed -n '/Secondary display power state/,/mAlwaysWakeUp/p'
for i in 1 2 3 4 5 6 7 8; do s=$(dumpsys display 2>/dev/null | grep -E 'mScreenState=' | sed 's/.*=//' | tr '\n' ' '); echo "  t=$((i*2))s screens(front,rear)=[${'$'}s]"; sleep 2; done
echo
echo "framework log since the wake (power / cover / rear):"
logcat -d 2>/dev/null | grep -E 'DualScreenCover|Going to sleep|Waking up|turned OFF|groupId\\[1\\]|Rear screen|SecondaryDisplay|goToSleep|userActivity.*displayId=1|subscreen_display_time' | grep -viE 'BrightnessEvent|MultiFinger|acquireWakeLock|releaseWakeLock|extCmdAndArgs' | tail -30
echo
echo "the panel's own log since the wake:"
logcat -d 2>/dev/null | grep -E 'AiUsageRear|AiUsagePoll' | grep -vE 'double tap: hidden' | tail -25
echo
echo "wake locks:"
dumpsys power 2>/dev/null | sed -n '/^Wake Locks:/,/^${'$'}/p' | head -12
"""

        /**
         * Tail of the 17 Pro's rear panel physical display id (local:4000000000000004332).
         * Only the fallback now, for when [rearPhysicalId] cannot read the real one: it
         * is the 17 Pro's panel, and nothing says another phone's rear display ends in it.
         */
        const val REAR_PHYSICAL_ID_SUFFIX = "332"

        /**
         * How often the panel's presence is checked. Both this and UsagePollService's
         * watchdog may re-place, and show() is idempotent when the panel is healthy (it
         * short-circuits to update+wake), so a tick that overlaps the other costs one dumpsys.
         *
         * Was 20s, which is a long time to look at the wrong screen. A tick is one
         * `dumpsys SurfaceFlinger` — measured at 40ms on this device — so at six seconds
         * this costs under one per cent of one core, and it used to spend two of those
         * calls per tick in some branches where it now spends one.
         *
         * The number is a trade against how long a failure is visible, not against
         * battery in any meaningful sense.
         */
        const val KEEPALIVE_MS = 6_000L

        /** The shortest gap between two full rebuilds. See replace(). */
        const val REPLACE_COOLDOWN_MS = 15_000L

        /** Corrective moves to try before evicting a panel stuck on the main display. */
        const val MAX_STRAND_STRIKES = 3

        const val TAG = "AiUsageRear"

        /** Global setting holding the density crash marker; see syncDensity's KDoc. */
        const val DENSITY_MARKER = "aiusage_rear_density"

        /** MIUI's camera, and the exact flags its own sub-screen launcher uses. */
        private const val CAMERA_COMPONENT = "com.android.camera/.Camera"

        /** The same app, without the component, for releasing it again. */
        private const val CAMERA_PACKAGE = "com.android.camera"
        /** The app's clips folder as the shell reads it (ClipStore.dir on the app's side). */
        /** Debug builds: with this file there and nobody signed in, the usage page shows made-up figures, marked "Demo". */
        const val USAGE_DEMO_FILE = "/storage/emulated/0/Android/data/${BuildConfig.APPLICATION_ID}/files/usage-demo"
        /** pop-demo words that are whole with fewer than four parts ("notices|" / "notices|empty" = the empty pull's rim). */
        val SHORT_DEMO_WORDS = setOf("charge", "fullmoment", "notices", "notices-logos", "musicsound")
        const val POP_DEMO_FILE ="/storage/emulated/0/Android/data/${BuildConfig.APPLICATION_ID}/files/pop-demo"
        /** The pinned pictures' files (data/PinImage.DIR in the app's external files folder). */
        const val PINS_DIR = "/storage/emulated/0/Android/data/${BuildConfig.APPLICATION_ID}/files/${dev.kb896.aiusage.data.PinImage.DIR}"
        /** The beam's card waits this long at most for a picture's note's picture (NotePictures). */
        const val BEAM_PICTURE_WAIT_MS = 150L
        const val CLIPS_DIR = "/storage/emulated/0/Android/data/${BuildConfig.APPLICATION_ID}/files/clips"
        /** The faces' list and pictures for the app, which cannot read his downloads (FaceScan). */
        const val FACES_APP_DIR = "/storage/emulated/0/Android/data/${BuildConfig.APPLICATION_ID}/files/faces"
        /** The shell's copy of the chosen face, "key seq": a new process shows it before the app speaks. */
        const val FACE_FILE = "/data/local/tmp/chicken-rear-face"
        /** [faceHistory]'s file, and how many lines it keeps. */
        const val FACE_LOG = "/data/local/tmp/chicken-rear-face-log"
        const val FACE_LOG_LINES = 200
        /** Rule 9's scores, "sha1 score" a line, once per face for good. */
        const val FAITHFUL_FILE = "${MamlHost.DIR}/faithful.txt"
        /** The dust's card of any look (dustCard): the earlier ones are deleted when a new one is made. */
        val DUST_CARD_NAME = Regex("""dust-\d+\.png""")
        /** A face drawn offscreen for rule 9 is compared this long after it was resumed (its intro played). */
        const val MEASURE_SETTLE_MS = 1_000L
        /** The black curtain put up while the back is off, so a wake's clip starts from black with no flash. */
        private const val SCENE_ARM_AHEAD = true
        /** A finger this soon after the scene began is the tap that woke the back, not one to end it. */
        /** A tap on the scene: full brightness for this long. */
        private const val SCENE_BRIGHT_MS = 15_000L
        /** The back dimmed by a double tap gives this much of its light: the level it has lying face down ([STAY_GAIN]). */
        private const val DIM_GAIN = 0.42f
        /** The playing card stays this long after a pause, or after its last touch. */
        /** How long a paused player keeps the back before the scene has it again. */
        private const val MEDIA_LINGER_MS = 20_000L
        /** Not playing for less than this is a track change, not a stop. */
        private const val MUSIC_STOP_MS = 3_000L
        private const val MUSIC_FRAME_MS = 33L
        /** The music page: every 16 ms, as the usage page (33 read as "not fluid"). */
        private const val MUSIC_PAGE_FRAME_MS = 16L
        /** After a pause the glows take this long at most to sink to rest. */
        private const val MUSIC_REST_MS = 4_000L
        /**
         * A new "Fluid size": a page standing still is drawn until the body is on it (MusicFace.Body.floorGliding, about 1 s
         * of frames for the whole range), and never longer than this after the change.
         */
        private const val MUSIC_FLOOR_MS = 5_000L
        /** The usage page: sixty pictures a second, as the panel shows them. */
        private const val USAGE_FRAME_MS = 16L
        /** The phone's own dialer (Xiaomi's), which takes the rear page's commands; and the made-up call's name for its app. */
        private const val DIALER = "com.android.incallui"
        private const val CALL_DEMO = "demo"
        /** Xiaomi's Clock, which takes its rear page's commands. */
        private const val XIAOMI_CLOCK = "com.android.deskclock"
        /** A note just pinned: on the back this long, as its confirmation. */
        private const val PIN_CONFIRM_MS = 8_000L
        /** The carousel of the notes is drawn this often while it moves. */
        private const val ARRANGE_FRAME_MS = 16L
        /** An order told to the app is held over its payloads this long at most, until one agrees (keptOrder). */
        private const val ORDER_HOLD_MS = 10 * 60_000L
        private const val VIA_UNCOVER_MOST_MS = 4_000L
        /** At full: how long "100" stands after the fluid has risen, and how long when the page was up already. */
        private const val FULL_STANDS_MS = 800L
        private const val FULL_REACHED_MS = 2_000L
        /** There while a face of ours is wanted: this process's next start covers the back before anything else (RearCover). */
        private const val COVER_HINT_FILE = RearCover.HINT_FILE
        /** The shell's own first health look: after the start's own work. */
        private const val HEALTH_FIRST_LOOK_MS = 30_000L
        private const val SCENE_CURTAIN_CHECK_MS = 400L
        /** The rear's light read this often while a display listener is wanted: the system may hold its events back. */
        private const val LIGHT_POLL_MS = 250L
        /**
         * The oldest reading of the rear's light a look at every picture takes (rearLitRecently): the dust's own look was
         * ten times a second. A dark reading is read again before anything ends on it.
         */
        private const val LIGHT_LOOK_MS = 100L
        /**
         * StayOn.Level by its ordinal: how much of the picture's level each shows. Dim (lying face
         * down, or a still in battery saver) is about a seventh of the light; in use is "a little
         * dim" (about half the light); the camera open is everything.
         */
        private val STAY_GAIN = floatArrayOf(1f, 0.42f, 1f, 0.74f, 1f)
        private val STAY_NAME = arrayOf("off", "on, a dim still", "on, dim", "on", "on, full")
        private const val STAY_STILL = 1
        /** How long a double tap holds the picture still, at most. */
        private const val STAY_TICK_MS = 2_000L
        /** The logs the permanent reader follows: ActivityTaskManager's lines are the system log's, the wm_ lines the event log's. */
        private const val FOCUS_LOG_BUFFERS = "system,events"
        /** Xiaomi's pin lines are followed this long after a pin is handed over (PinVia's lines all come within about 6 s). */
        private const val PIN_LOG_MS = 8_000L
        /** How long after the last finger on the back the scene comes back over Xiaomi's screen. */
        private const val STAY_RETURN_MS = 8_000L
        private const val STAY_REWAKE_MS = 6_000L
        /** A finger on a back that is not held lit: the app is told at most this often. */
        private const val TOUCH_TELL_MS = 3_000L
        private const val CAMERA_OPEN_SETTLE_MS = 700L
        private const val CAMERA_OPEN_POLL_MS = 2_000L
        /** The longest a band-tap watch runs, in case the camera's end is never seen. */
        private const val BAND_WATCH_MS = 30 * 60_000L
        private const val CAMERA_EXIT_SETTLE_MS = 900L
        /** A live page's buttons take a tap only this long after it came up: its slide (ShellPage.SLIDE_MS) and a tap's own length (SceneTouch.TAP_MS). */
        private const val LIVE_TAP_AFTER_MS = 700L
        /** For this long after the X, the closed timer's own notification (still up, or posted once more) is not a timer. */
        private const val TIMER_CLOSED_MS = 6_000L
        /** A demo's live page (a debug build's made-up timer, call or track) is taken down by itself after this, whoever forgot it. */
        private const val DEMO_LIVE_MS = 20_000L
        /** The charging page that could not come up (something with a better claim was showing) is tried again no sooner than this. */
        private const val CHARGE_RETRY_MS = 2_000L
        /** How long Back gets to close the camera before its task is removed instead. */
        private const val BACK_SETTLE_MS = 900L
        /** A camera launch not seen in front of the panel by then, with the panel in front, is over. */
        private const val CAMERA_UNSEEN_MS = 5_000L
        /** How long Resume gives the panel to step back before the guest goes through the front. */
        private const val STEP_BACK_LIMIT_MS = 1_200L
        /** How often Resume looks at the rear's top while the panel steps back. */
        private const val STEP_BACK_POLL_MS = 100L

        /** How long a moved guest must survive before it counts as placed. */
        private const val GUEST_SURVIVE_MS = 2_000L
        /** The longest a launch waits for an app's startup screens to settle. */
        private const val GUEST_SETTLE_LIMIT_MS = 6_000L
        /** Between looks for an arriving guest's first draw: each look is a dumpsys of its own (~100 ms). */
        private const val DRAWN_POLL_MS = 20L
        /** The keep-alive's goes at reshaping a guest before it is left as it is (logged once). */
        private const val PLACE_TRIES = 3
        /** How long after a Back key a private guest is watched for closing, and how often. */
        private const val BACK_WATCH_MS = 800L
        private const val BACK_WATCH_POLL_MS = 24L
        /** Not asked to be visible: how long its task is given to go, before it counts as moved behind. */
        private const val BACK_GONE_MS = 300L
        /** How long a panel just uncovered gets to draw before the picture over it slides off. */
        private const val PANEL_LIVE_MS = 120L
        /** After a change that relayouts a private guest, how long before where it stands is read. */
        private const val APP_RECT_SOON_MS = 500L
        /** A start on a private display that shows no task by then is taken as refused. */
        private const val PRIVATE_NO_TASK_MS = 2_000L
        /** How long a task removed for a relaunch is waited for, in looks. */
        private const val RELAUNCH_POLLS = 10
        private const val RELAUNCH_POLL_MS = 50L
        /** How often a start on a private display looks for its task (`am stack list`, ~30 ms). */
        private const val START_POLL_MS = 50L
        /** Rows read for the keyboard: half, a 2 px step at its top edge. */
        private const val KEYBOARD_SCALE = 0.5f
        private const val STRIP_SAMPLE_SCALE = 0.25f
        private const val BLACK = 0xFF000000.toInt()
        /** The sheet's settle, a frame at a time. */
        private const val SHEET_FRAME_MS = 16L
        /** No rear touchscreen found: how long before getevent is asked again. */
        private const val TOUCH_RETRY_MS = 60_000L
        /** A touch watch that ends sooner than this is suspect: its device path is asked again. */
        private const val TOUCH_SHORT_LIFE_MS = 1_000L
        /** The font did not load: how long before it is tried again. */
        private const val SHEET_FONT_RETRY_MS = 60_000L
        /** The guest's icon on the sheet's Resume is rasterised this big (drawn at PortalSheet.APP_MARK). */
        private const val GUEST_ICON_PX = 96

        /** The least time between two pokes keeping the panel awake under the sheet. */
        private const val POKE_EVERY_MS = 1_000L
        /** A capture waits this long for the control centre to have gone from the picture. */
        private const val CAPTURE_CLEAR_MS = 500L
        /** After the scanner broadcast, before the media store is asked again for the shell's screenshot. */
        private const val SCAN_WAIT_MS = 600L
        /** A hold's check comes this late after the time it said, so the hold is complete when it is asked. */
        private const val THREE_CHECK_LATE_MS = 10L
        /** A recording's longest, the owner's minute. */
        private const val RECORD_SECONDS = 60
        private const val RECORD_POLL_MS = 400L
        private const val RECORD_SETTLE_MS = 400L
        /** How often the front is looked at for the back's lock ([watchFrontLock]). */
        private const val FRONT_WATCH_MS = 750L
        /** A width tapped on its track: its box shown this long, then faded. */
        private const val WIDTH_FLASH_MS = 700L
        /**
         * What may go into a shell command as a package or a component: these come from
         * the app's config and from dumps, and this service runs them at shell uid.
         */
        private val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
        private val COMPONENT = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.]+""")

        /** NEW_TASK | SINGLE_TOP | EXCLUDE_FROM_RECENTS | RESET_TASK_IF_NEEDED | CLEAR_TASK */
        private const val CAMERA_FLAGS = "0x30a08000"
        /**
         * This build's own app id. A debug build is dev.kb896.aiusage.dev, installed
         * beside the release, and with the release's id written here a debug build
         * started, fed and finished the RELEASE app's panel instead of its own.
         */
        const val PKG = BuildConfig.APPLICATION_ID

        /** Xiaomi's "show notification details" on its rear screen (SubScreenNotificationSetting). */
        private const val XIAOMI_NOTICE_DETAIL = "sub_screen_notification_detail_enable"

        /** HyperOS's list of apps its freezer leaves alone; see [NoRestrictList]. */
        private const val NO_RESTRICT_SETTING = "MILLET_NO_RESTRICT_APP"
        /** What this build added to it, per build: the two can be installed side by side. */
        private val NO_RESTRICT_MARKER = "ai_usage_no_restrict_" + PKG.replace('.', '_')

        /** In full: `.rear.RearActivity` resolves against PKG, which is not the namespace on a debug build. */
        const val ACTIVITY = "dev.kb896.aiusage.rear.RearActivity"
    }
}
