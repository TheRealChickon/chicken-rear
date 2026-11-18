package dev.kb896.aiusage.ui.home

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.kb896.aiusage.circle.CircleOption
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.kb896.aiusage.data.ChargeAnimStyle
import dev.kb896.aiusage.data.ClipStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.PortalApps
import dev.kb896.aiusage.data.SceneClockStyle
import dev.kb896.aiusage.data.ThreeFingers
import dev.kb896.aiusage.rear.SceneFace
import dev.kb896.aiusage.rear.WakeStyle
import dev.kb896.aiusage.service.TestNotice
import dev.kb896.aiusage.ui.AppPicker
import dev.kb896.aiusage.ui.CompanionRows
import dev.kb896.aiusage.ui.PortalAppSheet
import dev.kb896.aiusage.ui.PortalGuide
import dev.kb896.aiusage.ui.Readiness
import dev.kb896.aiusage.ui.Setup
import dev.kb896.aiusage.ui.companion.CompanionHost
import dev.kb896.aiusage.ui.companion.CompanionUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the one page shows, as Compose state: the stored settings, the clips there are, what the
 * shell and Android currently allow. Every change is saved and handed to the shell at once
 * ([write]), and the preview at the top is drawn from the same state, so it moves with the finger.
 */
class HomeModel(private val host: CompanionHost, private val pickVideo: ((android.net.Uri?) -> Unit) -> Unit) {
    var config by mutableStateOf(host.config())
        private set
    var clips by mutableStateOf(emptyList<ClipStore.Clip>())
        private set
    val thumbs = mutableStateMapOf<String, Bitmap>()
    /** Each clip's colours as a wash for the page's ground: its still, right of the lenses, shrunk to a handful of pixels. */
    val washes = mutableStateMapOf<String, Bitmap>()
    val icons = mutableStateMapOf<String, Bitmap>()
    val labels = mutableStateMapOf<String, String>()
    var shizuku by mutableStateOf(host.graph.bridge.status())
        private set
    var noticeAccess by mutableStateOf(Setup.notificationAccess(host.activity))
        private set
    var battery by mutableStateOf(CompanionUi.batteryPercent(host.activity))
        private set
    var xiaomi by mutableStateOf<Bitmap?>(null)
        private set
    var mode by mutableStateOf(PreviewMode.IDLE)
    /**
     * The choice he has selected under the preview: its sections are the ones shown (HomeSections). Apart from
     * [mode], which is what the preview is playing and goes back to idle by itself when a message or a charge has
     * played out: the sections he is looking at must not go with it.
     */
    var section by mutableStateOf(PreviewMode.IDLE)
    /** The page is on screen: off it, the preview plays nothing (CompanionScreen.resume / pause). */
    var visible by mutableStateOf(true)
    var importing by mutableStateOf(false)
        private set
    /** The pinned app whose move and remove buttons are out; null for none. */
    var selectedApp by mutableStateOf<String?>(null)

    private val scope get() = host.activity.lifecycleScope
    val context: android.content.Context get() = host.activity

    // -- Notes on the back (rear/PinFace), plain or for a place (service/PlaceNotes) ----------------

    fun pin(text: String) = write { it.copy(pins = dev.kb896.aiusage.ui.PinActivity.added(it.pins, text, System.currentTimeMillis())) }

    fun removePin(at: Long) {
        write { it.copy(pins = it.pins.filter { p -> p.at != at }) }
        dev.kb896.aiusage.service.PlaceNotes.forget(host.activity, at)
    }

    /**
     * "Music page follows the music": on only with the permission to hear what is playing, asked for here at the
     * first turning on (Android's dialog calls it recording audio; nothing is recorded or kept: one level, twenty
     * times a second, while the back's music page is lit). Not given: the switch stays off.
     */
    /** ChatGPT's vessel gets the next of OpenAI's launch characters (and a renderer that stopped before is tried again). */
    fun nextChatGptDot() {
        val next = dev.kb896.aiusage.rear.dots.DotsSource.nextHero(config.chatGptDot)
        write { it.copy(chatGptDot = next.id) }
        dev.kb896.aiusage.rear.dots.DotsLink.clearStrikes(host.activity)
    }

    /** "ChatGPT's character" on or off; turned on, a renderer that crashed before is tried again. */
    fun chatGptDots(on: Boolean) {
        write { it.copy(usageDots = on) }
        if (on) dev.kb896.aiusage.rear.dots.DotsLink.clearStrikes(host.activity)
    }

    fun followMusic(on: Boolean) {
        val a = host.activity
        // The wish is kept; the row shows on only once the permission is there (RowStates.followsMusic), by itself
        // when he comes back from allowing it.
        write { it.copy(musicFollows = on) }
        if (on && !dev.kb896.aiusage.service.MusicLevel.allowed(a)) {
            a.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 73)
            toast("Nothing is recorded: only how loud the music is")
        }
        dev.kb896.aiusage.service.MusicLevel.sync(a)
    }

    fun refreshCameraPermissions() {
        val a = host.activity
        cameraAllowed = a.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        audioAllowed = dev.kb896.aiusage.service.MusicLevel.allowed(a)
    }

    /** "Our camera on the back": the wish kept; switched on without the camera permission, it is asked for. */
    fun ownCamera(on: Boolean) {
        write { it.copy(cameraOwnRear = on) }
        if (on && !cameraAllowed) host.askCameraPermission(android.Manifest.permission.CAMERA)
    }

    /** "Sound in videos": the microphone asked for (nothing stored: the camera page records sound when it is allowed). */
    fun videoSound() {
        if (!audioAllowed) host.askCameraPermission(android.Manifest.permission.RECORD_AUDIO)
    }

    // -- what is real outside the app, read each time the page comes to the front (RowStates) --------
    var audioAllowed by mutableStateOf(false)
        private set
    var cameraAllowed by mutableStateOf(false)
        private set
    /** "Display over other apps" for the earbuds animation. */
    var overlayAllowed by mutableStateOf(false)
        private set
    /** "Nearby devices" for the earbuds' battery level. */
    var nearbyAllowed by mutableStateOf(false)
        private set

    /** "Earbuds animation": the wish is kept; what it still needs is asked for here, each his to give. */
    fun earbudsAnim(on: Boolean) {
        write { it.copy(earbudsAnim = on) }
        if (on && !overlayAllowed && !glassRunning) askOverlay()
    }

    /** To the phone's own page for "Display over other apps", for this app. */
    fun askOverlay() {
        val a = host.activity
        runCatching { a.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + a.packageName))) }
        toast("Allow “Display over other apps”: the earbuds animation, a moment when they connect")
    }

    /** "Nearby devices", for the level the earbuds report. */
    fun askNearby() {
        host.activity.requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 74)
        toast("Only to read the battery level your earbuds report")
    }

    /** "Try it": plays it for the earbuds connected now, as they are (no made-up level). */
    fun tryEarbuds() {
        val a = host.activity
        val d = dev.kb896.aiusage.service.EarbudsWatch.connectedNow(a) ?: run { toast("No earbuds connected"); return }
        val way = dev.kb896.aiusage.service.EarbudsGate.way(glassRunning, overlayAllowed)
        if (way == dev.kb896.aiusage.service.EarbudsGate.Way.NONE) { askOverlay(); return }
        // It plays over whatever is in front: here, this page.
        dev.kb896.aiusage.service.EarbudsWatch.play(a, d.address, d.productName?.toString(), way)
    }
    var glassRunning by mutableStateOf(false)
        private set
    var locationFine by mutableStateOf(false)
        private set
    var locationAlways by mutableStateOf(false)
        private set
    var locationAny by mutableStateOf(false)
        private set
    var placeKnown by mutableStateOf(false)
        private set

    /** "Lightning when it storms" needs a position: asked for here when it is switched on without one. */
    fun lightning(on: Boolean) {
        write { it.copy(sceneWeather = on) }
        if (on && !locationAny) host.activity.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), 74)
    }

    // -- Claude: who is signed in, for the usage page on the back ----------------------------------

    /** Which accounts have a stored sign-in. Read again after a sign-in or a sign-out. */
    var signedIn by mutableStateOf(dev.kb896.aiusage.data.Account.entries.associateWith { runCatching { host.graph.store.load(it) != null }.getOrDefault(false) })
        private set
    /**
     * The account whose browser sign-in waits; null for none. The sign-in is the app's (auth/SignIns), not this
     * page's: it goes on while this page is stopped behind the browser, and this only shows where it stands.
     */
    var signingIn by mutableStateOf(host.graph.signIns.state.value.waiting)
        private set
    /** Which attempt that is (auth/SignIns counts them); 0 while none waits. A new number is a new wait. */
    var signInAttempt by mutableStateOf(host.graph.signIns.state.value.attempt)
        private set
    /** Why an account's last sign-in did not work, in plain words; none once it works or a new one begins. */
    var signInFailures by mutableStateOf(host.graph.signIns.state.value.failures)
        private set

    private fun readSignedIn() {
        signedIn = dev.kb896.aiusage.data.Account.entries.associateWith { runCatching { host.graph.store.load(it) != null }.getOrDefault(false) }
    }

    init {
        // Also while this page is not in front: the row is right when he comes back to it, toast seen or not.
        // Posted, not run at once: the first value comes while this model is still being made.
        scope.launch(Dispatchers.Main) {
            host.graph.signIns.state.collect { now ->
                signingIn = now.waiting
                signInAttempt = now.attempt
                signInFailures = now.failures
                val ended = now.last ?: return@collect
                readSignedIn()
                if (host.graph.signIns.takeToast(ended)) toast(ended.failure ?: "Signed in. Swipe left on the back to see it.")
            }
        }
    }

    /** The account's own sign-in page in the browser; the answer comes back to the app by itself (up to ten minutes). */
    fun signIn(account: dev.kb896.aiusage.data.Account) = host.graph.signIns.start(account)

    /** No longer waiting for the browser. Does nothing in a sign-in's first moments (auth/SignIns says why). */
    fun cancelSignIn() { host.graph.signIns.cancel() }

    /** How long until "Cancel" acts on the sign-in that waits; 0 when it does now. */
    fun cancelSignInInMs(): Long = host.graph.signIns.cancelInMs()

    fun signOut(account: dev.kb896.aiusage.data.Account) {
        runCatching { host.graph.store.clear(account) }
        if (account == dev.kb896.aiusage.data.Account.CHATGPT) runCatching { host.graph.chatAccountId = null }
        host.graph.signIns.forget(account)
        readSignedIn()
        scope.launch {
            runCatching { host.graph.repository.refresh(force = true) }
            write { it }
        }
    }

    fun write(edit: (PanelConfig) -> PanelConfig) {
        host.write(edit)
        config = host.config()
    }

    /** Back on screen, or something outside changed: read it all again. */
    /** The pin's glass is switched on in the phone's Accessibility settings. */
    var pinGlass by mutableStateOf(false)
        private set

    /** To the phone's Accessibility settings, where he switches "Pin to back: glass" on himself. */
    fun openPinGlass() {
        runCatching { host.activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        toast("Downloaded apps, then “Pin to back: glass”")
    }

    // -- the faces (rear/FaceCatalog, as the shell wrote them: data/FacesStore) ------------------------------------

    var faces by mutableStateOf(emptyList<dev.kb896.aiusage.data.FacesStore.Face>())
        private set
    val facePictures = mutableStateMapOf<String, Bitmap>()
    private val facesChanged: () -> Unit = { host.activity.runOnUiThread { readFaces() } }

    fun readFaces() {
        faces = dev.kb896.aiusage.data.FacesStore.read(host.activity)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (f in faces) {
                val file = f.picture ?: continue
                val bm = runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull() ?: continue
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { facePictures["${f.key}@${file.lastModified()}"] = bm }
            }
        }
        host.graph.bridge.facesListeners.add(facesChanged)
    }

    /** A tap on a face in the settings: it is the face, the next choice after the last one kept. */
    fun chooseFace(key: String) = write { it.copy(face = key, faceSeq = dev.kb896.aiusage.rear.FaceCatalog.nextSeq(it.faceSeq, System.currentTimeMillis())) }

    fun refresh() {
        val a = host.activity
        readFaces()
        CircleOption.refresh(a)
        pinGlass = dev.kb896.aiusage.service.PinGlassService.enabled(a)
        glassRunning = dev.kb896.aiusage.service.PinGlassService.running()
        refreshCameraPermissions()
        overlayAllowed = android.provider.Settings.canDrawOverlays(a)
        nearbyAllowed = dev.kb896.aiusage.service.EarbudsWatch.btAllowed(a)
        locationFine = dev.kb896.aiusage.service.PlaceNotes.fine(a)
        locationAlways = dev.kb896.aiusage.service.PlaceNotes.always(a)
        locationAny = locationFine || a.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        placeKnown = dev.kb896.aiusage.service.LastPlace.read(a) != null
        // A permission given while the page was away is in force at once (the music's level starts or stops).
        dev.kb896.aiusage.service.MusicLevel.sync(a)
        readSignedIn()
        config = host.config()
        shizuku = host.graph.bridge.status()
        // WHAT IS STORED IS WHAT IS IN FORCE: the settings told to the shell again as the page comes up (a shell that
        // was started while the page was away has them from here, not from an older payload).
        if (shizuku.ready) runCatching { host.write { it } }
        noticeAccess = Setup.notificationAccess(host.activity)
        battery = CompanionUi.batteryPercent(host.activity)
        scope.launch {
            val found = withContext(Dispatchers.IO) { ClipStore.list(host.activity) }
            clips = found
            for (c in found) if (c.file !in thumbs) launch {
                withContext(Dispatchers.IO) { ClipStore.thumb(c) }?.let { still ->
                    thumbs[c.file] = still
                    washes[c.file] = withContext(Dispatchers.Default) { wash(still) }
                }
            }
        }
        loadIcons()
        if (shizuku.ready) scope.launch {
            val shot = withContext(Dispatchers.IO) {
                // Only a lit back has anything to show; a dark one would be a black picture.
                host.graph.bridge.rearShot()?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            }
            if (shot != null && !mostlyBlack(shot)) xiaomi = shot
        }
    }

    /** [still] without its lens column, down to 6 x 10 and back up a little: soft enough to stretch over a window. */
    private fun wash(still: Bitmap): Bitmap {
        val x0 = still.width * 296 / 976
        val part = Bitmap.createBitmap(still, x0, 0, still.width - x0, still.height)
        val tiny = Bitmap.createScaledBitmap(part, 6, 10, true)
        return Bitmap.createScaledBitmap(tiny, 36, 60, true)
    }

    private fun mostlyBlack(b: Bitmap): Boolean {
        var lit = 0
        val stepX = (b.width / 24).coerceAtLeast(1); val stepY = (b.height / 16).coerceAtLeast(1)
        var n = 0
        var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { val p = b.getPixel(x, y); if ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF) > 60) lit++; n++; x += stepX }; y += stepY }
        return lit < n / 40
    }

    private fun loadIcons() {
        val pm = host.activity.packageManager
        val wanted = config.portalApps.filter { it !in icons }
        if (wanted.isEmpty()) return
        scope.launch {
            for (pkg in wanted) {
                withContext(Dispatchers.IO) { runCatching { pm.getApplicationIcon(pkg).toBitmap(168, 168) }.getOrNull() }?.let { icons[pkg] = it }
                withContext(Dispatchers.IO) { runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrNull() }?.let { labels[pkg] = it }
            }
        }
    }

    fun clipPath(file: String): String? = clips.firstOrNull { it.file == file }?.path

    val ready: Boolean get() = shizuku.ready

    /** The one thing missing, if any: what the strip under the preview says, and its fix. */
    fun missing(): Pair<String, CompanionRows.Fix>? = when {
        !shizuku.running -> RowStates.back(false, false, false)!! to CompanionRows.Fix.OPEN_SHIZUKU
        !shizuku.granted -> RowStates.back(true, false, false)!! to CompanionRows.Fix.ALLOW_SHIZUKU
        config.showNotifications && !noticeAccess -> "Notifications need access" to CompanionRows.Fix.ALLOW_NOTIFICATIONS
        else -> null
    }

    fun fix(fix: CompanionRows.Fix) = host.fix(fix)

    fun toast(text: String) = host.toast(text)

    // ---- apps ---------------------------------------------------------------------------------

    fun pickApps() {
        AppPicker.show(host.activity, object : AppPicker.Actions {
            override fun pinned() = host.config().portalApps
            override fun pin(pkg: String, on: Boolean): Boolean {
                val next = PortalApps.pinned(host.config().portalApps, pkg, on)
                if (on && pkg !in next) return false
                write {
                    if (on) PortalGuide.settled(it.copy(portalApps = next))
                    else it.copy(portalApps = next, portalDpi = it.portalDpi - pkg, portalWidth = it.portalWidth - pkg, portalConfined = it.portalConfined - pkg)
                }
                return true
            }
            override fun moveUp(pkg: String) { write { it.copy(portalApps = PortalApps.movedUp(it.portalApps, pkg)) } }
            override fun settings(pkg: String, label: String, changed: () -> Unit) {
                val now = host.config()
                PortalAppSheet.show(
                    host.activity, pkg, label, now.portalDpi[pkg], pkg in now.portalConfined, now.portalWidth[pkg],
                    onDpi = { dpi -> write { c -> c.copy(portalDpi = if (dpi == null) c.portalDpi - pkg else c.portalDpi + (pkg to dpi)) } },
                    onWidth = { w ->
                        write { c ->
                            if (w == null) c.copy(portalWidth = c.portalWidth - pkg)
                            else c.copy(portalWidth = c.portalWidth + (pkg to w), portalConfined = c.portalConfined + pkg)
                        }
                    },
                    onConfined = { on -> write { c -> c.copy(portalConfined = if (on) c.portalConfined + pkg else c.portalConfined - pkg) } },
                    onUnpin = { pin(pkg, false); changed() },
                )
            }
        }) { refresh() }
    }

    fun moveApp(pkg: String, by: Int) {
        val list = config.portalApps.toMutableList()
        val i = list.indexOf(pkg)
        val j = i + by
        if (i < 0 || j !in list.indices) return
        list[i] = list[j]; list[j] = pkg
        write { it.copy(portalApps = list) }
    }

    fun removeApp(pkg: String) {
        selectedApp = null
        write { it.copy(portalApps = it.portalApps - pkg, portalDpi = it.portalDpi - pkg, portalWidth = it.portalWidth - pkg, portalConfined = it.portalConfined - pkg) }
    }

    fun openApp(pkg: String) {
        if (!ready) { missing()?.let { fix(it.second) }; return }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { host.graph.bridge.launchGuest(pkg) }
            if (!ok) toast("It did not open on the back")
        }
    }

    // ---- clips --------------------------------------------------------------------------------

    fun addClip() {
        pickVideo { uri ->
            if (uri == null) return@pickVideo
            importing = true
            val name = runCatching {
                host.activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()?.substringBeforeLast('.') ?: "clip"
            ClipStore.import(host.activity, uri, name) { clip, why ->
                importing = false
                if (clip == null) toast(why ?: "That video could not be added") else refresh()
            }
        }
    }

    fun deleteClip(file: String) {
        ClipStore.delete(host.activity, file)
        write { it.copy(sceneWake = if (it.sceneWake == file) "" else it.sceneWake, sceneLoop = if (it.sceneLoop == file) "" else it.sceneLoop) }
        refresh()
    }

    // ---- on the back, now ---------------------------------------------------------------------

    fun playSceneOnBack() {
        if (!ready) { missing()?.let { fix(it.second) }; return }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { host.graph.bridge.playScene(if (config.sceneWake.isNotEmpty()) "wake" else "loop") }
            if (!ok) toast("The back could not be reached")
        }
    }

    fun playChargeOnBack() =
        CompanionUi.playOnBack(host, Readiness.Feature.CHARGE, WakeStyle.Preview.Charge(config.chargeStyle, battery))

    fun testNotification() {
        if (!config.showNotifications) { toast("Turn Notifications on first"); return }
        missing()?.let { fix(it.second); return }
        host.askPostNotifications { granted ->
            toast(if (granted && TestNotice.send(host.activity)) "Sent. Look at the back." else "Android's notifications are off for Chicken Rear")
        }
    }

    fun switchNavigation(on: Boolean) {
        write { it.copy(navigationOn = on) }
        if (on && !noticeAccess) fix(CompanionRows.Fix.ALLOW_NOTIFICATIONS)
    }

    fun switchNotifications(on: Boolean) {
        write { CompanionRows.switched(it, Readiness.Feature.NOTICES, on) }
        if (on && !noticeAccess) fix(CompanionRows.Fix.ALLOW_NOTIFICATIONS)
    }

    fun preview(): PreviewState {
        val c = config
        return PreviewState(
            sceneOn = c.sceneOn,
            wakePath = clipPath(c.sceneWake),
            // The dust face (a key, drawn by the engine itself) when it is the face; else our clip, as before.
            loopPath = if (c.face == dev.kb896.aiusage.rear.FaceCatalog.DUST) dev.kb896.aiusage.rear.FaceCatalog.DUST else clipPath(c.sceneLoop),
            look = SceneFace.Look(c.sceneClock, c.sceneClockStyle, c.sceneDate, c.sceneBattery, battery, charging = false, hour24 = c.clock12h != true),
            depth = c.sceneDepth,
            chargeOn = c.chargeAnim, chargeStyle = c.chargeStyle, chargePercent = c.chargePercent,
            noticesOn = c.showNotifications, noticeText = c.showNotificationText, messageStyle = c.messageStyle,
            usage = runCatching {
                dev.kb896.aiusage.rear.UsageFace.model(host.graph.repository.state.value, System.currentTimeMillis(), fiveHours = c.usageFiveHours).takeIf { it.columns.isNotEmpty() }
            }.getOrNull(),
            usageFiveHours = c.usageFiveHours,
            chatGptDot = c.chatGptDot,
            // The first in his order (PinOrder) that the back lists: the note the swipe up shows first on the back (a note
            // for a place only while the phone is there: place/Here). The Notes section below lists them all.
            note = dev.kb896.aiusage.place.Here.onBack(c.pins).firstOrNull()?.text,
            noteImage = dev.kb896.aiusage.place.Here.onBack(c.pins).firstOrNull()?.image,
            xiaomi = xiaomi,
            // As the back would be in a lit room, in the hand: so the slider is seen at work here.
            gain = if (c.rearAdaptive) dev.kb896.aiusage.rear.RearLight.gain(dev.kb896.aiusage.rear.RearLight.light(dev.kb896.aiusage.rear.RearLight.NO_READING_LUX, dev.kb896.aiusage.rear.RearLight.Doing(3), c.rearLightBias)) else 0.74f,
            musicFloor = c.musicFloor,
        )
    }
}

/**
 * The page: the back of the phone at the top, live, and under it one scroll of glass sections —
 * Apps on the back, Clock, Animations, Charging, Battery, Notifications, Camera. The preview
 * stays where it is while the sections move under it, so a change is seen as it is made.
 */
@Composable
fun HomeScreen(model: HomeModel, dark: Boolean, onAdvanced: () -> Unit) {
    val tone = if (dark) Tone.Dark else Tone.Light
    val backdrop = rememberLayerBackdrop()
    val bars = WindowInsets.systemBars.asPaddingValues()
    val state = model.preview()
    CompositionLocalProvider(LocalTone provides tone, LocalBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().background(tone.ground)) {
            // The ground every pane bends: the chosen clip's own colours, far out of focus.
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                val wash = model.washes[model.config.sceneLoop] ?: model.washes[model.config.sceneWake]
                if (wash != null) {
                    // A few dozen pixels stretched over the window: every edge gone, only the colours left.
                    Image(
                        wash.asImageBitmap(), null,
                        Modifier.fillMaxSize().graphicsLayer { alpha = if (tone.dark) 0.9f else 0.55f },
                        contentScale = ContentScale.FillBounds, filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    )
                }
                Box(Modifier.fillMaxSize().groundWash(tone))
            }

            val headerH = 318.dp
            // The sections, sliding away under the preview through a soft edge.
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = bars.calculateTopPadding() + headerH - 22.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, endY = 26.dp.toPx()), blendMode = BlendMode.DstIn)
                    }
                    .verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, top = 30.dp, bottom = bars.calculateBottomPadding() + 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                model.missing()?.let { need ->
                    Pane(Modifier.clickable(role = Role.Button) { model.fix(need.second) }, radius = 26.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label(need.first, Modifier.weight(1f), size = 16, weight = 650)
                            Label("Fix  ›", size = 16, weight = 650, color = tone.ink2)
                        }
                    }
                }
                // THE SECTIONS CHANGE WITH THE PAGE SELECTED, AS ONE MOTION: the old ones out and the new ones in, the way the
                // row of pages went (to a page further right: in from the right), on springs, so a second swipe while
                // the first still moves just carries on from where things are.
                val order = PreviewMode.entries
                androidx.compose.animation.AnimatedContent(
                    targetState = model.section,
                    transitionSpec = {
                        val forward = order.indexOf(targetState) > order.indexOf(initialState)
                        val move = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.9f, stiffness = 380f)
                        val light = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 520f)
                        (androidx.compose.animation.slideInHorizontally(move) { w -> if (forward) w / 3 else -w / 3 } + androidx.compose.animation.fadeIn(light))
                            .togetherWith(androidx.compose.animation.slideOutHorizontally(move) { w -> if (forward) -w / 3 else w / 3 } + androidx.compose.animation.fadeOut(light))
                            .using(androidx.compose.animation.SizeTransform(clip = false))
                    },
                    label = "sections",
                ) { shown ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Only what belongs to the page he has selected under the preview (HomeSections).
                    val sections = HomeSections.of(shown)
                    for (s in sections) when (s) {
                        HomeSection.FACES -> Faces(model)
                        HomeSection.CLAUDE -> Claude(model)
                        HomeSection.NOTES -> Notes(model)
                        HomeSection.ALWAYS_ON -> AlwaysOn(model)
                        HomeSection.APPS -> Apps(model)
                        HomeSection.ANIMATIONS -> Animations(model)
                        HomeSection.MUSIC -> Music(model)
                        HomeSection.NAVIGATION -> Navigation(model)
                        HomeSection.CHARGING -> Charging(model)
                        HomeSection.NOTIFICATIONS -> Notifications(model)
                        HomeSection.CAMERA -> Camera(model)
                        HomeSection.THREE_FINGERS -> ThreeFingersHeld(model)
                        HomeSection.ADVANCED -> Pane(Modifier.clickable(role = Role.Button, onClick = onAdvanced), radius = 26.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Label("Advanced", Modifier.weight(1f), size = 17, weight = 600)
                                Label("›", size = 22, color = tone.ink3)
                            }
                        }
                    }
                    // A page with nothing to set says so, once.
                    if (sections.isEmpty()) Pane { Label("Nothing to set for this page.", size = 15, weight = 500, color = tone.ink2) }
                    }
                }
            }

            // The back of the phone, and what it is being shown doing.
            Column(Modifier.fillMaxWidth().padding(top = bars.calculateTopPadding() + 10.dp, start = 22.dp, end = 22.dp)) {
                RearPreview(if (model.visible) state else state.copy(sceneOn = false), if (model.visible) model.mode else PreviewMode.IDLE, Modifier.fillMaxWidth(), onMode = { model.mode = it; model.section = it }) { model.mode = PreviewMode.IDLE }
                Gap(14.dp)
                // What it can be shown doing: no "Wake" where nothing plays on waking.
                val modes = PreviewMode.entries.filter { it != PreviewMode.WAKE || model.config.sceneWake.isNotEmpty() }
                // Every page of the back, to look at here: a row to slide, since they are more than fit.
                // The selected one is marked by ONE plate that slides to it (and stretches to its width), and the row
                // brings it into view; no plate jumping from chip to chip.
                val chips = rememberScrollState()
                val spots = remember { androidx.compose.runtime.mutableStateMapOf<PreviewMode, Pair<Float, Float>>() }
                val spot = spots[model.section]
                val glide = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.82f, stiffness = 420f)
                val plateX by animateFloatAsState(spot?.first ?: 0f, glide, label = "plate x")
                val plateW by animateFloatAsState(spot?.second ?: 0f, glide, label = "plate width")
                val plate = if (tone.dark) Color(0x33FFFFFF) else Color.White
                androidx.compose.runtime.LaunchedEffect(model.section, spot) {
                    val at = spot ?: return@LaunchedEffect
                    val want = (at.first - (chips.viewportSize - at.second) / 2f).toInt().coerceIn(0, chips.maxValue)
                    chips.animateScrollTo(want, androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 380f))
                }
                Row(
                    Modifier.fillMaxWidth().background(tone.well, RoundedCornerShape(20.dp)).padding(4.dp).horizontalScroll(chips)
                        .drawBehind {
                            if (plateW > 0f) drawRoundRect(plate, topLeft = androidx.compose.ui.geometry.Offset(plateX, 0f), size = androidx.compose.ui.geometry.Size(plateW, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
                        },
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (m in modes) {
                        // Selected is what he chose: it stays while the preview's message or charge plays out and rests.
                        val sel = m == model.section
                        Box(
                            Modifier.height(40.dp)
                                .onGloballyPositioned { c -> val at = c.positionInParent().x to c.size.width.toFloat(); if (spots[m] != at) spots[m] = at }
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(role = Role.RadioButton) { model.mode = m; model.section = m }.padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) { Label(m.label, size = 14, weight = if (sel) 650 else 500, color = if (sel) tone.ink else tone.ink2) }
                    }
                }
            }
        }
    }
}

// ---- sections ---------------------------------------------------------------------------------

/** The notes pinned to the back: what is there, and a line to write one; "for this place" brings it back when he is back here. */
@Composable
private fun Notes(model: HomeModel) {
    val tone = LocalTone.current
    var draft by remember { mutableStateOf("") }
    Pane {
        Head("Notes on the back")
        for (pin in model.config.pins) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                // A picture's note: a small picture of it first (read off the main thread), its caption or "Picture" beside.
                if (pin.image != null) { NoteThumb(pin.image); GapW(12.dp) }
                // A note for a place: where, its zone, or what it lacks to be noticed; a tap moves it (Pin to location).
                androidx.compose.foundation.layout.Column(Modifier.weight(1f).then(if (pin.placed) Modifier.clickable { dev.kb896.aiusage.ui.place.PlaceSettings.move(model.context as android.app.Activity, pin.at) } else Modifier)) {
                    Label(pin.text.ifBlank { if (pin.image != null) "Picture" else pin.text }, size = 16, weight = 500, maxLines = 2)
                    if (pin.placed) Label(dev.kb896.aiusage.ui.place.PlaceSettings.line(pin, model.locationFine && model.locationAlways), size = 13, weight = 450, color = tone.ink3)
                }
                GapW(10.dp)
                Chip("Remove") { model.removePin(pin.at) }
            }
        }
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().padding(top = 14.dp)
                .background(tone.well, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            if (draft.isEmpty()) Label("Write a note", size = 16, weight = 450, color = tone.ink3)
            androidx.compose.foundation.text.BasicTextField(
                draft, { draft = it.take(dev.kb896.aiusage.ui.PinActivity.LONGEST) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(color = tone.ink, fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(tone.ink),
            )
        }
        // Which animation a pin plays: Xiaomi's own (rear/PinVia) or ours (rear/PinBeam). From the next pin.
        Label("Pin animation", size = 16, weight = 500, modifier = Modifier.padding(top = 16.dp))
        Segmented(listOf("Xiaomi's", "Ours"), if (model.config.pinByXiaomi) 0 else 1, modifier = Modifier.padding(top = 8.dp)) { i ->
            model.write { it.copy(pinByXiaomi = i == 0) }
        }
        // The pin's glass: his to switch on, in the phone's own Accessibility settings (service/PinGlassService).
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Label("Glass when you pin", size = 16, weight = 500)
                Label(RowStates.pinGlass(model.pinGlass, model.glassRunning).note.orEmpty() + ". One picture of the screen as you pin, kept only for the animation", size = 13, weight = 450, color = tone.ink3, maxLines = 3)
            }
            GapW(10.dp)
            if (!model.pinGlass) Chip("Turn on") { model.openPinGlass() }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = RowGap) {
            Chip("Pin", strong = true, enabled = draft.isNotBlank()) { model.pin(draft); draft = "" }
            // The same prompt as from any app's menu: a map, his quick tiles, the zone (ui/place).
            Chip("Pin to location", enabled = draft.isNotBlank()) { dev.kb896.aiusage.ui.place.PlaceSettings.pin(model.context as android.app.Activity, draft); draft = "" }
        }
        dev.kb896.aiusage.ui.place.PlaceRows(model.context as android.app.Activity, model.config.homeNet.isNotEmpty(), model.locationFine, model.locationAlways)
    }
}

/** A pinned picture, small (its file read off the main thread; its place a plain well until then, or if it is gone). */
@Composable
private fun NoteThumb(name: String) {
    val tone = LocalTone.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val shot by androidx.compose.runtime.produceState<Bitmap?>(null, name) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.kb896.aiusage.data.PinImageFiles.read(context, name, most = 160) }
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val b = shot
    if (b != null) androidx.compose.foundation.Image(
        b.asImageBitmap(), null, Modifier.size(44.dp).clip(shape).background(tone.well, shape),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
    ) else androidx.compose.foundation.layout.Box(Modifier.size(44.dp).background(tone.well, shape))
}

/** Claude: sign in here, and the usage page is one swipe left of the picture on the back. */
@Composable
private fun Claude(model: HomeModel) {
    val tone = LocalTone.current
    Pane {
        Head("Claude usage")
        for (account in dev.kb896.aiusage.data.Account.entries) {
            // ChatGPT is said as what it is: its plan's Codex limits (five hours and the week) are all it gives a client.
            val name = if (account == dev.kb896.aiusage.data.Account.CHATGPT) "ChatGPT (Codex limits)"
                else model.config.accounts.getOrNull(account.ordinal)?.label?.takeIf { it.isNotBlank() } ?: account.title
            val inNow = model.signedIn[account] == true
            val waiting = !inNow && model.signingIn == account
            Line(name) {
                if (inNow) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("Signed in", size = 14, weight = 500, color = tone.ink2)
                        GapW(12.dp)
                        Chip("Sign out") { model.signOut(account) }
                    }
                } else if (waiting) {
                    // The browser has its page; the answer is waited for up to ten minutes, whatever is in front.
                    // "Cancel" would stand where "Sign in" stood a frame ago, under the second tap of a double
                    // tap: it is not there (and auth/SignIns would not act on it) until the browser has long
                    // covered this row. Until then the word alone.
                    androidx.compose.runtime.key(model.signInAttempt) {
                        var mayCancel by remember { mutableStateOf(model.cancelSignInInMs() == 0L) }
                        LaunchedEffect(Unit) {
                            while (true) {
                                val left = model.cancelSignInInMs()
                                if (left == 0L) break
                                kotlinx.coroutines.delay(left)
                            }
                            mayCancel = true
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label("Waiting…", size = 14, weight = 500, color = tone.ink2)
                            if (mayCancel) {
                                GapW(12.dp)
                                Chip("Cancel") { model.cancelSignIn() }
                            }
                        }
                    }
                } else {
                    Chip("Sign in", strong = true) { model.signIn(account) }
                }
            }
            // Why the last try did not work, in words that say what to do (auth/SignIns writes them).
            if (!inNow && !waiting) model.signInFailures[account]?.let { why ->
                Label(why, Modifier.padding(top = 6.dp), size = 13, weight = 450, color = tone.ink3, maxLines = 5)
            }
        }
        Gap(4.dp)
        // Off by default: each account is its week alone.
        Line("Five-hour limits") {
            Toggle(model.config.usageFiveHours, "Five-hour limits") { on -> model.write { it.copy(usageFiveHours = on) } }
        }
        // ChatGPT's vessel may have one of OpenAI's own Dots characters (rear/dots), drawn by the ChatGPT app's own
        // renderer on this phone: off until he switches it on (PanelConfig.usageDots); on, a chip picks which.
        if (model.signedIn[dev.kb896.aiusage.data.Account.CHATGPT] == true) {
            val on = model.config.usageDots
            Line("ChatGPT's character") {
                if (on) {
                    val dot = model.config.chatGptDot
                    val name = dev.kb896.aiusage.rear.dots.DotsSource.HEROES.firstOrNull { it.id == dot }?.name ?: dot
                    Chip(name) { model.nextChatGptDot() }
                    Spacer(Modifier.width(10.dp))
                }
                Toggle(on, "ChatGPT's character") { model.chatGptDots(it) }
            }
        }
        Gap(12.dp)
        Label("Swipe left on the back to see it.", size = 13, weight = 450, color = tone.ink3)
    }
}

@Composable
private fun AlwaysOn(model: HomeModel) {
    val c = model.config
    Pane {
        Head("Always on") { Toggle(c.stayOn, "Always on") { on -> model.write { it.copy(stayOn = on) } } }
        Line("Dim when face down", enabled = c.stayOn) {
            Toggle(c.stayDimFaceDown, "Dim when face down", enabled = c.stayOn) { on -> model.write { it.copy(stayDimFaceDown = on) } }
        }
        // Its brightness by the light around it (rear/RearLight): dim in a dark room, bright outdoors; and one slider for all of it.
        Line("Brightness follows the light", enabled = c.stayOn) {
            Toggle(c.rearAdaptive, "Brightness follows the light", enabled = c.stayOn) { on -> model.write { it.copy(rearAdaptive = on) } }
        }
        if (c.rearAdaptive) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Dimmer", size = 13, weight = 500, color = LocalTone.current.ink3)
                Slide(c.rearLightBias, "Overall brightness of the back", enabled = c.stayOn, modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) { v ->
                    model.write { it.copy(rearLightBias = v) }
                }
                Label("Brighter", size = 13, weight = 500, color = LocalTone.current.ink3)
            }
        }
        // At night (22:00 to 07:00, or Do Not Disturb) with the front off: dark after two minutes, and only a finger
        // on the back or the front lights it again (service/NightRule). A lit back keeps the whole phone awake.
        Line("Sleep at night", enabled = c.stayOn) {
            Toggle(c.sleepAtNight, "Sleep at night", enabled = c.stayOn) { on -> model.write { it.copy(sleepAtNight = on) } }
        }
        // Home detection: at home it stays on for less time, and off at night; face down as always.
        Line("Home detection", enabled = c.stayOn) {
            Toggle(c.homeDetect, "Home detection", enabled = c.stayOn) { on -> model.write { it.copy(homeDetect = on) } }
        }
        if (c.homeDetect) {
            val here = remember(model.visible) { dev.kb896.aiusage.service.HomeNet.current(model.context) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(
                    when {
                        c.homeNet.isEmpty() -> "No home Wi-Fi set"
                        here == c.homeNet -> "You are home"
                        else -> "Not on the home Wi-Fi"
                    },
                    Modifier.weight(1f), size = 14, weight = 500, color = LocalTone.current.ink2,
                )
                Chip("This Wi-Fi is home", strong = c.homeNet.isEmpty(), enabled = here != null && here != c.homeNet) {
                    if (here != null) model.write { it.copy(homeNet = here) }
                }
            }
            Label("At home: on for 2 minutes, off at night. Face down as always.", Modifier.padding(top = 8.dp), size = 13, weight = 450, color = LocalTone.current.ink3, maxLines = 2)
        }
    }
}

@Composable
private fun Apps(model: HomeModel) {
    val c = model.config
    val tone = LocalTone.current
    Pane {
        Head("Apps on the back") { Toggle(c.moreApps, "Apps on the back") { on -> model.write { it.copy(moreApps = on) } } }
        // The tray: the apps' own icons as the phone draws them, and a plain plus.
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp).graphicsLayer { alpha = if (c.moreApps) 1f else 0.4f }
                .clip(RoundedCornerShape(24.dp)).background(tone.well)
                .horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            for (pkg in c.portalApps) {
                val icon = model.icons[pkg]
                val sel = model.selectedApp == pkg
                Box(
                    Modifier.size(52.dp)
                        .graphicsLayer { val k = if (sel) 1.12f else 1f; scaleX = k; scaleY = k }
                        .semantics { contentDescription = model.labels[pkg] ?: pkg }
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                            model.selectedApp = if (sel) null else pkg
                        },
                ) {
                    if (icon != null) Image(icon.asImageBitmap(), null, Modifier.fillMaxSize())
                }
            }
            Box(
                Modifier.size(52.dp).clip(CircleShape).border(1.dp, tone.edge, CircleShape)
                    .semantics { contentDescription = "Add or remove apps" }
                    .clickable(role = Role.Button) { model.pickApps() },
                contentAlignment = Alignment.Center,
            ) { Label("+", size = 26, weight = 300, color = tone.ink2) }
        }
        val sel = model.selectedApp?.takeIf { it in c.portalApps }
        if (sel != null) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = RowGap, verticalAlignment = Alignment.CenterVertically) {
                Chip("‹") { model.moveApp(sel, -1) }
                Chip("›") { model.moveApp(sel, 1) }
                Chip("Open", strong = true) { model.openApp(sel) }
                Chip("Remove") { model.removeApp(sel) }
            }
        }
    }
}

@Composable
private fun Animations(model: HomeModel) {
    val c = model.config
    val tone = LocalTone.current
    Pane {
        Head("Animations") { Toggle(c.sceneOn, "Animations") { on -> model.write { it.copy(sceneOn = on) } } }
        // A row of choices only where there is a choice: the back has one face now (the moon), and no wake clip.
        val wakes = model.clips.filter { it.wake }
        val faces = model.clips.filter { it.loop }
        if (wakes.isNotEmpty()) {
            Label("On wake", Modifier.padding(top = 14.dp), size = 14, weight = 600, color = tone.ink2)
            ClipRow(model, wakes, c.sceneWake, noneLabel = "None", enabled = c.sceneOn) { file ->
                model.write { it.copy(sceneWake = file) }
                if (file.isNotEmpty()) model.mode = PreviewMode.WAKE
            }
        }
        if (faces.size > 1) {
            Label("Face", Modifier.padding(top = 16.dp), size = 14, weight = 600, color = tone.ink2)
            ClipRow(model, faces, c.sceneLoop, noneLabel = "Black", enabled = c.sceneOn) { file ->
                model.write { it.copy(sceneLoop = file) }
                model.mode = PreviewMode.IDLE
            }
        }
        // The moon face's own motions beside its clip (rear/SceneEffects).
        Line("Glitch", enabled = c.sceneOn) { Toggle(c.sceneGlitch, "Glitch", enabled = c.sceneOn) { on -> model.write { it.copy(sceneGlitch = on) } } }
        Line("Lightning when it storms", enabled = c.sceneOn) {
            Toggle(RowStates.lightning(c.sceneWeather, model.placeKnown, model.locationAny).on, "Lightning when it storms", enabled = c.sceneOn) { on -> model.lightning(on) }
        }
        RowStates.lightning(c.sceneWeather, model.placeKnown, model.locationAny).note?.let { Label(it, size = 13, weight = 450, color = LocalTone.current.ink3, modifier = Modifier.padding(top = 4.dp)) }
        // The clock: off by default, one line, its choices only while on.
        Line("Clock over it", enabled = c.sceneOn) { Toggle(c.sceneClock, "Clock", enabled = c.sceneOn) { on -> model.write { it.copy(sceneClock = on) } } }
        if (c.sceneClock) {
            Gap(12.dp)
            // Three weights of the same clock (the stored names are older than this look).
            val weights = listOf(SceneClockStyle.THIN, SceneClockStyle.STACKED, SceneClockStyle.BOLD)
            Segmented(listOf("Thin", "Light", "Medium"), weights.indexOf(c.sceneClockStyle), enabled = c.sceneOn) { i ->
                model.write { it.copy(sceneClockStyle = weights[i]) }
            }
            Line("Date", enabled = c.sceneOn) { Toggle(c.sceneDate, "Date", enabled = c.sceneOn) { on -> model.write { it.copy(sceneDate = on) } } }
            Line("Behind the character", enabled = c.sceneOn) {
                Toggle(c.sceneDepth, "Clock behind the character", enabled = c.sceneOn) { on -> model.write { it.copy(sceneDepth = on) } }
            }
            Line("Battery percent", enabled = c.sceneOn) {
                Toggle(c.sceneBattery, "Battery percent on the clock", enabled = c.sceneOn) { on -> model.write { it.copy(sceneBattery = on) } }
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = RowGap) {
            Chip("Play on the back", strong = true, enabled = c.sceneOn) { model.playSceneOnBack() }
            Chip(if (model.importing) "Adding…" else "Add a clip", enabled = !model.importing) { model.addClip() }
        }
    }
}

/** One slot's choices: none, then each clip's still; the chosen one ringed. Hold a clip to delete it. */
@Composable
private fun ClipRow(model: HomeModel, clips: List<ClipStore.Clip>, chosen: String, noneLabel: String, enabled: Boolean, onChoose: (String) -> Unit) {
    val tone = LocalTone.current
    var confirm by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val step = with(androidx.compose.ui.platform.LocalDensity.current) { 134.dp.toPx() }
    // The chosen one in view as the row first fills: it may be past the edge.
    LaunchedEffect(clips.size) {
        val i = clips.indexOfFirst { it.file == chosen }
        if (i >= 1) scroll.scrollTo((i * step).toInt())
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(scroll).graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Still(null, noneLabel, chosen.isEmpty(), enabled) { onChoose("") }
        for (clip in clips) {
            Still(model.thumbs[clip.file], clip.title, chosen == clip.file, enabled, onLong = { confirm = clip.file }) { onChoose(clip.file) }
        }
    }
    confirm?.let { file ->
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = RowGap, verticalAlignment = Alignment.CenterVertically) {
            Label("Delete ${ClipStore.title(file)}?", size = 14, color = tone.ink2)
            Chip("Delete") { model.deleteClip(file); confirm = null }
            Chip("Keep") { confirm = null }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Still(picture: Bitmap?, name: String, chosen: Boolean, enabled: Boolean, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val tone = LocalTone.current
    Column(Modifier.width(124.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(976f / 596f)
                .then(if (chosen) Modifier.border(1.5.dp, tone.ink, RoundedCornerShape(15.dp)) else Modifier)
                .padding(if (chosen) 3.5.dp else 0.dp)
                .clip(RoundedCornerShape(if (chosen) 11.5.dp else 13.dp))
                .background(if (picture != null) Color.Black else tone.well)
                .semantics { contentDescription = name }
                .then(
                    if (onLong != null) Modifier.combinedClickable(enabled = enabled, onLongClick = onLong, onClick = onClick)
                    else Modifier.clickable(enabled = enabled, onClick = onClick),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (picture != null) Image(picture.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Label(name, Modifier.padding(top = 6.dp, start = 2.dp), size = 12, weight = if (chosen) 650 else 500, color = if (chosen) tone.ink else tone.ink2)
    }
}

/**
 * The navigation page (rear/NavFace): found with Navigation selected under the preview. A route is read from its app's
 * notification, so the switch is on only with notification access (the message pop-ups' own switch is apart from it).
 */
@Composable
private fun Navigation(model: HomeModel) {
    val state = RowStates.notifications(model.config.navigationOn, model.noticeAccess)
    val tone = LocalTone.current
    Pane {
        Head("Navigation")
        Line("Navigation on the back") {
            Toggle(state.on, "Navigation on the back") { model.switchNavigation(it) }
        }
        state.note?.let { Gap(12.dp); Label(it, size = 13, weight = 450, color = tone.ink3) }
    }
}

/** The music page: found with Music selected under the preview. */
@Composable
private fun Music(model: HomeModel) {
    val c = model.config
    val tone = LocalTone.current
    Pane {
        Head("Music")
        // The glows rise and fall with the sound. Android gives the sound's level only with the permission to hear
        // it: asked for here, when he turns this on, and nowhere else (service/MusicLevel).
        val follows = RowStates.followsMusic(c.musicFollows, model.audioAllowed)
        Line("Follows the music") {
            Toggle(follows.on, "Follows the music") { on -> model.followMusic(on) }
        }
        follows.note?.let { Gap(12.dp); Label(it, size = 13, weight = 450, color = tone.ink3) }
        Label("Nothing is recorded.", size = 13, weight = 450, color = tone.ink3)
        // The body's least size, 0 to 100 (PanelConfig.musicFloor, MusicBody.floorPx): the slider the brightness has, from
        // 0, and the number. Saved and sent to the back as it moves; the preview above follows it.
        Line("Fluid size") {
            Slide(c.musicFloor / 100f, "Fluid size", from = 0f, modifier = Modifier.width(156.dp)) { v ->
                model.write { it.copy(musicFloor = Math.round(v * 100f).coerceIn(0, 100)) }
            }
            Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterEnd) {
                Label("${c.musicFloor}", size = 15, weight = 500, color = tone.ink3)
            }
        }
        // Earbuds connecting (service/EarbudsWatch): the ripple on the front, its moment on the back.
        val buds = RowStates.earbuds(c.earbudsAnim, model.overlayAllowed, model.glassRunning, model.nearbyAllowed)
        Line("Earbuds animation") {
            Toggle(buds.on, "Earbuds animation") { on -> model.earbudsAnim(on) }
        }
        buds.note?.let { Gap(12.dp); Label(it, size = 13, weight = 450, color = tone.ink3) }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = RowGap) {
            if (c.earbudsAnim && !model.overlayAllowed && !model.glassRunning) Chip("Allow", strong = true) { model.askOverlay() }
            if (c.earbudsAnim && !model.nearbyAllowed) Chip("Battery level") { model.askNearby() }
            Chip("Try it", enabled = c.earbudsAnim && (model.overlayAllowed || model.glassRunning)) { model.tryEarbuds() }
        }
    }
}

@Composable
private fun Charging(model: HomeModel) {
    val c = model.config
    Pane {
        Head("Charging") {
            Toggle(c.chargeAnim, "Charging animation") { on ->
                model.write { it.copy(chargeAnim = on) }
                if (on) model.mode = PreviewMode.CHARGING
            }
        }
        Gap(14.dp)
        Segmented(listOf("Liquid", "Ring", "Edge", "Minimal"), c.chargeStyle.ordinal, enabled = c.chargeAnim) { i ->
            model.write { it.copy(chargeStyle = ChargeAnimStyle.entries[i]) }
            model.mode = PreviewMode.CHARGING
        }
        Line("Percent", enabled = c.chargeAnim) {
            Toggle(c.chargePercent, "Percent while charging", enabled = c.chargeAnim) { on ->
                model.write { it.copy(chargePercent = on) }
                model.mode = PreviewMode.CHARGING
            }
        }
        Line("Wake a dark back for it", enabled = c.chargeAnim) {
            Toggle(c.chargeWhenDark, "Wake a dark back for it", enabled = c.chargeAnim) { on -> model.write { it.copy(chargeWhenDark = on) } }
        }
        Row(Modifier.padding(top = 16.dp)) { Chip("Play on the back", strong = true, enabled = c.chargeAnim) { model.playChargeOnBack() } }
    }
}

/** "Message style"'s three choices, in PanelConfig.MessageStyle's order. */
internal val MESSAGE_STYLES = listOf("Full page", "Island · Tint", "Island · Sweep")

@Composable
private fun Notifications(model: HomeModel) {
    val c = model.config
    val state = RowStates.notifications(c.showNotifications, model.noticeAccess)
    val on = state.on
    Pane {
        Head("Notifications") { Toggle(on, "Notifications") { v -> model.switchNotifications(v); if (v) model.mode = PreviewMode.ALERT } }
        state.note?.let { Label(it, size = 13, weight = 450, color = LocalTone.current.ink3, modifier = Modifier.padding(top = 8.dp)) }
        Line("Light up the back", enabled = on) { Toggle(c.noticeWake, "Light up the back", enabled = on) { v -> model.write { it.copy(noticeWake = v) } } }
        Line("Show the message", enabled = on) {
            Toggle(c.showNotificationText, "Show the message", enabled = on) { v ->
                model.write { it.copy(showNotificationText = v) }
                model.mode = PreviewMode.ALERT
            }
        }
        Line("Also while locked", enabled = on && c.showNotificationText) {
            Toggle(c.noticeTextWhenLocked, "Show the message while locked", enabled = on && c.showNotificationText) { v -> model.write { it.copy(noticeTextWhenLocked = v) } }
        }
        // How a message shows on the back: the whole back (rear/NoticePop), or the message island (rear/MessageIsland) whose
        // open page tints the live face in the app's colour, or dims it under one sweep of Xiaomi's light. Read at each message.
        Label("Message style", size = 16, weight = 500, modifier = Modifier.padding(top = 16.dp))
        Segmented(MESSAGE_STYLES, c.messageStyle.ordinal, enabled = on, modifier = Modifier.padding(top = 8.dp), size = 13) { i ->
            model.write { it.copy(messageStyle = dev.kb896.aiusage.data.MessageStyle.entries[i]) }
            model.mode = PreviewMode.ALERT
        }
        // The pull with nothing waiting lights the back's rim (rear/EmptyRim): white, or Xiaomi's own rear-glow colours.
        Label("Empty pull light", size = 16, weight = 500, modifier = Modifier.padding(top = 16.dp))
        Segmented(listOf("White", "Xiaomi colours"), if (c.emptyPullXiaomi) 1 else 0, modifier = Modifier.padding(top = 8.dp)) { i ->
            model.write { it.copy(emptyPullXiaomi = i == 1) }
        }
        Row(Modifier.padding(top = 16.dp)) { Chip("Send a test", strong = true, enabled = on) { model.testNotification() } }
    }
}

@Composable
private fun Faces(model: HomeModel) {
    val tone = LocalTone.current
    val c = model.config
    val offered = model.faces.filter { it.offered }
    Pane {
        Head("Faces")
        for (f in offered) {
            val chosen = f.key == c.face || (offered.none { it.key == c.face } && f.key == "moon")
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).clickable(role = Role.RadioButton) { model.chooseFace(f.key) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pic = f.picture?.let { model.facePictures["${f.key}@${it.lastModified()}"] }
                // The card's own corners: 61 of its 580 px.
                Box(Modifier.width(112.dp).aspectRatio(976f / 596f).clip(RoundedCornerShape((112 * 61f / 580f).dp)).background(Color.Black)) {
                    if (pic != null) Image(pic.asImageBitmap(), f.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Label(f.title, Modifier.weight(1f).padding(start = 14.dp), size = 16, weight = 550)
                Box(Modifier.size(22.dp).border(2.dp, tone.ink, CircleShape), contentAlignment = Alignment.Center) {
                    if (chosen) Box(Modifier.size(11.dp).background(tone.ink, CircleShape))
                }
            }
        }
        // Dust in Light's one choice: whether it follows the phone's movement (PanelConfig.dustMovesWithPhone).
        if (c.face == dev.kb896.aiusage.rear.FaceCatalog.DUST) {
            Gap(12.dp)
            Line("Moves with the phone") { Toggle(c.dustMovesWithPhone, "Moves with the phone") { on -> model.write { it.copy(dustMovesWithPhone = on) } } }
        }
    }
}

/**
 * What three fingers held on the back do (rear/ThreeHold, rear/ThreeFingersAct): one row of choices, as the pin
 * animation's, a screenshot of the back by default. The same plain segments, no colour.
 */
@Composable
private fun ThreeFingersHeld(model: HomeModel) {
    val c = model.config
    Pane {
        Label("Three fingers held", size = 16, weight = 500)
        Segmented(listOf("Screenshot", "Recording", "Back off", "Nothing"), c.threeFingers.ordinal, modifier = Modifier.padding(top = 8.dp)) { i ->
            model.write { it.copy(threeFingers = ThreeFingers.entries[i]) }
        }
        val circle by CircleOption.changes.collectAsState()
        Line("Circle to Search") {
            Toggle(circle.available && circle.requested, "Circle to Search", enabled = circle.available) { CircleOption.setEnabled(model.context, it) }
        }
        Label(circle.hint, size = 13, weight = 450, color = LocalTone.current.ink3)
        if (!circle.serviceEnabled && !circle.standaloneInstalled) Chip("Accessibility") { model.openPinGlass() }
    }
}

@Composable
private fun Camera(model: HomeModel) {
    val c = model.config
    val tone = LocalTone.current
    Pane {
        Head("Camera")
        // The stroke from the left on the back: our camera page there (ui/camera), or Xiaomi's Camera. On by default; in
        // force only with the camera permission, asked for here by his tap (RowStates.ownCamera).
        val own = RowStates.ownCamera(c.cameraOwnRear, model.cameraAllowed)
        Line("Our camera on the back") { Toggle(own.on, "Our camera on the back") { on -> model.ownCamera(on) } }
        own.note?.let { Label(it, size = 13, weight = 450, color = tone.ink3) }
        RowStates.videoSound(own, model.audioAllowed)?.let { sound ->
            Line("Sound in videos") { Toggle(sound.on, "Sound in videos") { on -> if (on) model.videoSound() } }
            sound.note?.let { Label(it, size = 13, weight = 450, color = tone.ink3) }
        }
        Label("Turn on Rear display preview in Xiaomi Camera.", size = 14, color = LocalTone.current.ink2)
    }
}
