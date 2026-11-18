package dev.kb896.aiusage.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.StatePayload
import dev.kb896.aiusage.place.PlacePermission
import dev.kb896.aiusage.service.Graph
import dev.kb896.aiusage.service.NotificationRelay
import dev.kb896.aiusage.service.WeatherPoll
import dev.kb896.aiusage.ui.Readiness.Feature
import dev.kb896.aiusage.ui.companion.AdvancedPage
import dev.kb896.aiusage.ui.companion.CompanionHost
import dev.kb896.aiusage.ui.companion.CompanionUi
import dev.kb896.aiusage.ui.glass.Backdrop
import dev.kb896.aiusage.ui.glass.Ink
import dev.kb896.aiusage.ui.glass.UiTheme
import dev.kb896.aiusage.ui.glass.Unclip
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The launcher screen in companion mode (PanelConfig.rearMode COMPANION): Xiaomi's rear screen
 * stays the base, and this is the one screen for what Chicken Rear adds to it.
 *
 * HOME is the Compose page (ui/home/HomeScreen): a live preview of the back at the top, and one
 * scroll of glass sections under it. ADVANCED keeps the older rows (ui/companion/AdvancedPage):
 * the posture rules, Replace mode, the log. This class is what both stand on: the stored config
 * and its writes, the fixes for what is missing (Shizuku, notification access), the permission
 * and file pickers, which must be registered before the activity starts.
 *
 * Built in code by [MainActivity] in onCreate, for that reason.
 */
class CompanionScreen(
    override val activity: ComponentActivity,
    override val graph: Graph,
    private val onModeChanged: () -> Unit,
) : CompanionHost {

    enum class Page(val title: String) {
        HOME("Chicken Rear"),
        ADVANCED("Advanced"),
        SETUP("Setup"),
    }

    private val store = ConfigStore(activity)
    val root = FrameLayout(activity)
    private val scroll = ScrollView(activity).apply { isFillViewport = true; clipToPadding = false }
    private val column = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val p = CompanionUi.dp(activity, 24)
        setPadding(p, CompanionUi.dp(activity, 12), p, p)
    }
    private val setupPrefs = activity.getSharedPreferences("onboarding", android.content.Context.MODE_PRIVATE)
    private val restored = activity.savedStateRegistry.consumeRestoredStateForKey("companionPage")
    private var setupStep = Onboarding.Step.entries.getOrNull(restored?.getInt("step") ?: 0) ?: Onboarding.Step.SHIZUKU
    private var fromAdvanced = restored?.getBoolean("fromAdvanced") ?: false
    private var page = restored?.getString("page")?.let(Page::valueOf)
        ?: if (!setupPrefs.getBoolean("seen", false) && store.raw() == null) Page.SETUP else Page.HOME
    private var legacyPane: View? = null

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goBack()
    }

    /** The one page (ui/home): the preview and its sections, in Compose over the same window. */
    private var videoAnswer: ((Uri?) -> Unit)? = null
    private val askVideo = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        videoAnswer?.invoke(uri)
        videoAnswer = null
    }
    private val model = dev.kb896.aiusage.ui.home.HomeModel(this) { then -> videoAnswer = then; askVideo.launch("video/*") }
    private val home = androidx.compose.ui.platform.ComposeView(activity).apply {
        setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        setContent { dev.kb896.aiusage.ui.home.HomeScreen(model, dark = Ink.dark) { open(Page.ADVANCED) } }
    }

    private var postAnswer: ((Boolean) -> Unit)? = null
    private val askPost = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        postAnswer?.invoke(granted)
        postAnswer = null
    }

    private val cameraPermissionHistory = activity.getSharedPreferences("camera_permission_requests", android.content.Context.MODE_PRIVATE)
    private val askCamera = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraPermissionResult(Manifest.permission.CAMERA, it)
    }
    private val askPlaceLocation = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (page == Page.SETUP) rebuild()
    }
    private val askMicrophone = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraPermissionResult(Manifest.permission.RECORD_AUDIO, it)
    }

    private fun cameraPermissionResult(permission: String, granted: Boolean) {
        cameraPermissionHistory.edit().putBoolean(permission, !granted).apply()
        model.refreshCameraPermissions()
        if (page == Page.SETUP) rebuild()
    }

    override fun askCameraPermission(permission: String) {
        val launcher = when (permission) {
            Manifest.permission.CAMERA -> askCamera
            Manifest.permission.RECORD_AUDIO -> askMicrophone
            else -> return
        }
        if (cameraPermissionHistory.getBoolean(permission, false) && !activity.shouldShowRequestPermissionRationale(permission)) {
            fix(CompanionRows.Fix.OPEN_APP_INFO)
        } else {
            launcher.launch(permission)
        }
    }

    init {
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Advanced keeps the older rows, in a pane of its own so the window insets stay the page's.
        val legacy = FrameLayout(activity)
        legacy.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(legacy, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(home, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        legacyPane = legacy
        if (UiTheme.isGlass(activity)) {
            Backdrop.install(legacy)
            Backdrop.track(scroll)
        } else {
            legacy.setBackgroundColor(Backdrop.ground())
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !Ink.dark
            isAppearanceLightNavigationBars = !Ink.dark
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(legacy) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        activity.savedStateRegistry.registerSavedStateProvider("companionPage") {
            android.os.Bundle().apply {
                putString("page", page.name)
                putInt("step", setupStep.ordinal)
                putBoolean("fromAdvanced", fromAdvanced)
            }
        }
        back.isEnabled = page != Page.HOME
        activity.onBackPressedDispatcher.addCallback(activity, back)
        rebuild()
    }

    private fun refreshSetupAccess() {
        if (page == Page.SETUP && setupStep == Onboarding.Step.SHIZUKU) rebuild()
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshSetupAccess() }
    private val binderDead = Shizuku.OnBinderDeadListener { refreshSetupAccess() }
    private val permissionChanged = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshSetupAccess() }

    fun pause() {
        model.visible = false
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionChanged)
    }

    fun resume() {
        model.visible = page == Page.HOME
        Shizuku.addBinderReceivedListener(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionChanged)
        if (graph.bridge.status().granted) graph.bridge.bind()
        if (Setup.notificationAccess(activity)) listenerSettingsOpened = false
        model.refresh()
        rebuild()
    }

    fun open(next: Page) {
        page = next
        model.visible = page == Page.HOME
        back.isEnabled = next != Page.HOME
        rebuild()
        scroll.scrollTo(0, 0)
    }

    // ---- what the pages ask of it -----------------------------------------------------------

    override fun config(): PanelConfig = store.load()

    override fun write(edit: (PanelConfig) -> PanelConfig) {
        val config = edit(store.load())
        store.save(config)
        // Companion mode: update() only applies the config in the shell, it places nothing.
        graph.bridge.update(StatePayload.encode(graph.repository.state.value, config, WeatherPoll.latest))
    }

    private fun states(): Map<Feature, Readiness.State> {
        val status = graph.bridge.status()
        val config = store.load()
        return Readiness.of(Readiness.Inputs(
            shizukuRunning = status.running,
            shizukuGranted = status.granted,
            rearDisplay = status.rearDisplayId >= 0,
            notificationAccess = Setup.notificationAccess(activity),
            moreApps = config.moreApps,
            chargeAnim = config.chargeAnim,
            notices = config.showNotifications,
            wakeAnim = config.wakeAnim,
        ))
    }

    /** Asked in the listener settings and still not there: Android's restricted-settings block. */
    private fun restricted() = listenerSettingsOpened && !Setup.notificationAccess(activity)

    override fun row(feature: Feature): CompanionRows.Row =
        CompanionRows.row(feature, states()[feature] ?: Readiness.State.Off, restricted())

    override fun fix(fix: CompanionRows.Fix) {
        when (fix) {
            CompanionRows.Fix.OPEN_SHIZUKU -> start(Setup.shizukuIntent(activity), "Could not open Shizuku")
            CompanionRows.Fix.ALLOW_SHIZUKU -> Privacy.disclose(activity, Privacy.Access.SHIZUKU, next = "Next: Shizuku asks whether this app may use it.") {
                if (!graph.bridge.status().running) toast("Start Shizuku first") else graph.bridge.requestPermission(activity)
            }
            CompanionRows.Fix.ALLOW_NOTIFICATIONS -> allowNotifications()
            CompanionRows.Fix.OPEN_APP_INFO -> start(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", activity.packageName, null)),
                "Could not open App info",
            )
        }
    }

    override fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    override fun askPostNotifications(then: (Boolean) -> Unit) {
        val granted = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) { then(true); return }
        postAnswer = then
        askPost.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun modeChanged() = onModeChanged()

    override fun shareLog() {
        toast("Gathering the log…")
        val states = states()
        activity.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { LogShare.text(activity, CompanionRows.rows(states, restricted())) }
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Chicken Rear log")
                .putExtra(Intent.EXTRA_TEXT, text)
            start(Intent.createChooser(send, "Share the log"), "Nothing here can share text")
        }
    }

    /**
     * Notification access, on one tap. With Shizuku: `cmd notification allow_listener` for this
     * app's relay, which also gets past the restricted-settings block. Without it, or if that did
     * not take: Android's listener page; come back from that still without access and the line
     * says how to lift the block ([restricted]). [declined] when the disclosure is turned down.
     */
    private fun allowNotifications(declined: () -> Unit = {}) {
        val viaShell = graph.bridge.status().ready
        Privacy.disclose(
            activity, Privacy.Access.NOTIFICATIONS,
            next = if (viaShell) "Shizuku allows it for this app now." else "Allow it for this app on the next screen.",
            onCancel = declined,
        ) {
            if (!viaShell) { openListenerSettings(); return@disclose }
            val component = ComponentName(activity, NotificationRelay::class.java).flattenToString()
            activity.lifecycleScope.launch {
                val allowed = withContext(Dispatchers.IO) { graph.bridge.allowListener(component) }
                if (allowed == true || Setup.notificationAccess(activity)) {
                    listenerSettingsOpened = false
                    toast("Notification access allowed")
                    rebuild()
                } else openListenerSettings()
            }
        }
    }

    private fun openListenerSettings() {
        listenerSettingsOpened = true
        start(Setup.notificationAccessIntent(), "Could not open that settings page")
    }

    private fun start(intent: Intent, failure: String) {
        try { activity.startActivity(intent) } catch (_: Exception) { toast(failure) }
    }

    private fun goBack() {
        if (page != Page.SETUP) open(Page.HOME)
        else if (setupStep == Onboarding.Step.SHIZUKU) finishSetup()
        else showStep(Onboarding.Step.entries[setupStep.ordinal - 1])
    }

    private fun showStep(step: Onboarding.Step) {
        setupStep = step
        open(Page.SETUP)
    }

    private fun finishSetup() {
        setupPrefs.edit().putBoolean("seen", true).apply()
        open(if (fromAdvanced) Page.ADVANCED else Page.HOME)
        fromAdvanced = false
    }

    private fun setupAction(action: Onboarding.Action) {
        when (action) {
            Onboarding.Action.MANAGER -> fix(CompanionRows.Fix.OPEN_SHIZUKU)
            Onboarding.Action.GUIDE -> start(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://rear.896.ro/en/guides/shizuku/")), "Could not open the guide")
            Onboarding.Action.AUTHORIZE -> fix(CompanionRows.Fix.ALLOW_SHIZUKU)
            Onboarding.Action.LISTENER -> if (Setup.notificationAccess(activity)) openListenerSettings() else allowNotifications()
            Onboarding.Action.LOCATION -> askSetupLocation()
            Onboarding.Action.CAMERA -> {
                if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    fix(CompanionRows.Fix.OPEN_APP_INFO)
                else askCameraPermission(Manifest.permission.CAMERA)
            }
            Onboarding.Action.OVERLAY -> model.askOverlay()
            Onboarding.Action.POWER -> fix(CompanionRows.Fix.OPEN_APP_INFO)
        }
    }

    private fun askSetupLocation() {
        val need = PlacePermission.need(activity)
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val askPrecise = need == PlacePermission.Need.PRECISE &&
            (!setupPrefs.getBoolean("askedPrecise", false) || activity.shouldShowRequestPermissionRationale(fine))
        val next = when {
            askPrecise -> "Choose precise location for place notes."
            need == PlacePermission.Need.LOCATION_OFF -> "Turn on Location on the next screen."
            else -> "In Permissions, choose Location, precise and all the time."
        }
        Privacy.disclose(activity, Privacy.Access.LOCATION, next = next) {
            when {
                askPrecise -> {
                    setupPrefs.edit().putBoolean("askedPrecise", true).apply()
                    askPlaceLocation.launch(arrayOf(fine, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                need == PlacePermission.Need.LOCATION_OFF ->
                    start(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), "Could not open Location")
                else -> fix(CompanionRows.Fix.OPEN_APP_INFO)
            }
        }
    }

    // ---- building -----------------------------------------------------------------------------

    override fun rebuild() {
        column.removeAllViews()
        // Home is the Compose page; the older pages (Advanced) are the rows under it.
        home.visibility = if (page == Page.HOME) View.VISIBLE else View.GONE
        legacyPane?.visibility = if (page == Page.HOME) View.GONE else View.VISIBLE
        if (page == Page.HOME) { model.refresh(); return }
        header()
        when (page) {
            Page.HOME -> Unit
            Page.ADVANCED -> AdvancedPage.build(column, this) {
                fromAdvanced = true
                setupStep = Onboarding.Step.SHIZUKU
                open(Page.SETUP)
            }
            Page.SETUP -> {
                val status = graph.bridge.status()
                Onboarding.build(column, setupStep, status.running, status.granted, ::setupAction, {
                    val next = Onboarding.Step.entries.getOrNull(setupStep.ordinal + 1)
                    if (next == null) finishSetup() else showStep(next)
                }, ::finishSetup)
            }
        }
        Unclip.tree(column)
    }

    /** The page's name over the wallpaper; a sub-page's is also the way back. */
    private fun header() {
        val title = TextView(activity).apply {
            text = when (page) {
                Page.SETUP -> "‹  Setup · ${setupStep.ordinal + 1} of ${Onboarding.Step.entries.size}"
                Page.ADVANCED -> "‹  Advanced"
                Page.HOME -> page.title
            }
            textSize = 24f
            setTextColor(Ink.primary(0f))
            UiType.dress(this)
            fontVariationSettings = "'wght' 600"
            setPadding(0, CompanionUi.dp(activity, 12), 0, CompanionUi.dp(activity, 16))
            if (page != Page.HOME) {
                contentDescription = "Back"
                setOnClickListener { goBack() }
            }
        }
        column.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    companion object {
        /**
         * Android's listener page was opened for access. Process-wide, so coming back to a new
         * activity (a theme change recreates it) still knows; cleared once access is there.
         */
        @Volatile private var listenerSettingsOpened = false
    }
}

/** The text "Share the log" sends: what the screen says, then this app's own recent log. */
internal object LogShare {
    private const val LINES = 3000
    private const val MAX_CHARS = 150_000

    fun text(context: android.content.Context, rows: List<CompanionRows.Row>): String {
        val head = buildString {
            appendLine("Chicken Rear ${dev.kb896.aiusage.BuildConfig.VERSION_NAME} (${context.packageName})")
            appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
            for (r in rows) appendLine("${r.title}: ${if (!r.on) "off" else if (r.greyed) "missing: ${r.line}" else "ready"}")
            appendLine()
        }
        // Without READ_LOGS, logcat gives an app only its own uid's lines: every process of ours.
        val log = runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", LINES.toString()).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            out
        }.getOrElse { "(the log could not be read: ${it.message})" }
        return head + log.takeLast(MAX_CHARS - head.length)
    }
}
