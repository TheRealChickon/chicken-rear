package dev.kb896.aiusage.ui.home

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.core.graphics.drawable.toBitmap
import dev.kb896.aiusage.data.ChargeAnimStyle
import dev.kb896.aiusage.rear.RearGeometry
import dev.kb896.aiusage.rear.SceneEngine
import dev.kb896.aiusage.rear.SceneFace
import dev.kb896.aiusage.rear.ShellCharge
import java.io.File

/** What the preview is showing: the back as it sits, as it wakes, as the charger goes in, as a notification arrives. */
enum class PreviewMode(val label: String) {
    IDLE("Idle"), WAKE("Wake"), CHARGING("Charging"), ALERT("Message"),
    // The back's other pages, each drawn here by the back's own painter, with made-up data where it needs any.
    MUSIC("Music"), NAVIGATION("Navigation"), TIMER("Timer"), CALL("Call"), USAGE("Usage"), NOTES("Notes"),
}

/** The preview's live pages: on the back they sit between home and the usage page (rear/PageRow). */
val PREVIEW_LIVE = setOf(PreviewMode.MUSIC, PreviewMode.NAVIGATION, PreviewMode.TIMER, PreviewMode.CALL, PreviewMode.CHARGING)

/**
 * Where a swipe on the preview leads, as the same swipe does on the back. Across: one step along the row
 * home | the live page | usage (PageRow), [live] being the live page the preview has (showing, or sent
 * away to the left); [forward] is the finger going left (across) or up. Up and down: the notes from home
 * and a message pulled down, and from any other page back to the picture.
 */
fun previewSwipe(now: PreviewMode, live: PreviewMode?, across: Boolean, forward: Boolean): PreviewMode {
    if (!across) return when {
        now == PreviewMode.IDLE && forward -> PreviewMode.NOTES
        now == PreviewMode.IDLE -> PreviewMode.ALERT
        else -> PreviewMode.IDLE
    }
    val at = when (now) {
        PreviewMode.IDLE -> dev.kb896.aiusage.rear.PageRow.Page.HOME
        PreviewMode.USAGE -> dev.kb896.aiusage.rear.PageRow.Page.USAGE
        in PREVIEW_LIVE -> dev.kb896.aiusage.rear.PageRow.Page.LIVE
        else -> return PreviewMode.IDLE
    }
    return when (dev.kb896.aiusage.rear.PageRow.next(at, goRight = forward, live = live != null)) {
        dev.kb896.aiusage.rear.PageRow.Page.USAGE -> PreviewMode.USAGE
        dev.kb896.aiusage.rear.PageRow.Page.LIVE -> live ?: PreviewMode.IDLE
        dev.kb896.aiusage.rear.PageRow.Page.HOME -> PreviewMode.IDLE
        // No page that way (on the back, left of home is Xiaomi's Camera): the preview stays.
        null -> now
    }
}

/** Everything the preview draws from, read from the settings as they are this instant. */
data class PreviewState(
    val sceneOn: Boolean,
    val wakePath: String?,
    val loopPath: String?,
    val look: SceneFace.Look,
    val depth: Boolean,
    val chargeOn: Boolean,
    val chargeStyle: ChargeAnimStyle,
    val chargePercent: Boolean,
    val noticesOn: Boolean,
    val noticeText: Boolean,
    /** Xiaomi's rear screen as last captured, for what is under the scene; null when not known. */
    val xiaomi: Bitmap?,
    /** The usage page as the back would show it now (the accounts'); null with nobody signed in (made-up usage then). */
    val usage: dev.kb896.aiusage.rear.UsageFace.Model? = null,
    /** The switch "Five-hour limits", for the made-up usage too. */
    val usageFiveHours: Boolean = false,
    /** Which of OpenAI's Dots stands in ChatGPT's vessel (PanelConfig.chatGptDot). */
    val chatGptDot: String = "blue_beret",
    /**
     * The first pinned note in his order (the newest unless he put them in order on the back) that the back's notes page
     * lists (a note for a place only while the phone is there: place/Here.onBack), or null for none.
     */
    val note: String? = null,
    /** That note's picture, if it is a picture's (its file's name, data/PinImage): the preview shows it as the back does. */
    val noteImage: String? = null,
    /** How much of its numbers the back's picture keeps (rear/RearLight.gain): its brightness, as the settings have it for a lit room. */
    val gain: Float = 1f,
    /** "Fluid size" (PanelConfig.musicFloor): the music page's body never under this share of its floor's range (MusicBody.floorPx). */
    val musicFloor: Int = dev.kb896.aiusage.data.PanelConfig.MUSIC_FLOOR,
    /** "Message style" (PanelConfig.messageStyle): the message shown as the full page or as the message island. */
    val messageStyle: dev.kb896.aiusage.data.MessageStyle = dev.kb896.aiusage.data.MessageStyle.FULL,
)

private const val REAR_W = 976f
private const val REAR_H = 596f
private const val LENS_W = 296f
private const val CORNER = 97f
private val GEOMETRY = RearGeometry(976, 596, 296, 97, 450)

/**
 * The back of the phone, live: the rear panel's own shape (976 x 596, its corners, the two
 * lenses over the left 296 px), showing what the settings below it would put there. The scene is
 * drawn by the very engine that draws it on the back (SceneEngine: the clip's own file, the black
 * crush, the clock behind the character), the charge by the back's own painter too
 * (ShellCharge.PreviewPainter). Xiaomi's screen under it is a capture of the real one when the
 * shell could take it.
 */
@Composable
fun RearPreview(state: PreviewState, mode: PreviewMode, modifier: Modifier = Modifier, onMode: (PreviewMode) -> Unit = {}, onModeEnded: () -> Unit) {
    val ended by rememberUpdatedState(onModeEnded)
    val go by rememberUpdatedState(onMode)
    val now by rememberUpdatedState(mode)
    // The live page the preview has: the one last shown, kept while a swipe sends it away to the left (it comes
    // back with a swipe the other way, as on the back) and forgotten when another page is picked by its button.
    var live by remember { mutableStateOf<PreviewMode?>(null) }
    var bySwipe by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(mode) {
        if (mode in PREVIEW_LIVE) live = mode
        else if (!bySwipe && mode != PreviewMode.USAGE) live = null
        bySwipe = false
    }
    BoxWithConstraints(
        modifier
            .aspectRatio(REAR_W / REAR_H)
            .semantics { contentDescription = "Preview of the back screen" }
            // TOUCHED AS THE BACK IS: a swipe here goes where the same swipe goes on the back (across, along the row
            // home | the live page | usage: a live page swiped right is sent away and comes back with a swipe left;
            // up to the notes and down from them, down from the picture for a message). Only the preview moves: nothing here reaches
            // the phone's notifications, calls or music.
            .pointerInput(Unit) {
                var dx = 0f; var dy = 0f; var done = false
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f; done = false },
                    onDrag = { change, by ->
                        change.consume()
                        dx += by.x; dy += by.y
                        val far = size.width * 0.16f
                        if (!done && maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) >= far) {
                            done = true
                            val across = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                            val to = previewSwipe(now, live, across, forward = if (across) dx < 0 else dy < 0)
                            if (to != now) { bySwipe = true; go(to) }
                        }
                    },
                )
            }
            .drawBehind {
                // The phone's back around the panel: a dark rim with a lit edge.
                val r = CornerRadius(size.width * (CORNER + 10f) / REAR_W)
                drawRoundRect(Color(0xFF151517), cornerRadius = r)
                drawRoundRect(Color(0x33FFFFFF), cornerRadius = r, style = Stroke(1.dp.toPx()))
            }
            .padding(5.dp),
    ) {
        val corner = maxWidth * (CORNER / REAR_W)
        val lensPad = maxWidth * (LENS_W / REAR_W)
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(corner)).background(Color.Black)) {
            // What is under everything this app adds: Xiaomi's own rear screen.
            val base = state.xiaomi
            if (base != null) {
                Image(base.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Label("Xiaomi's screen", Modifier.padding(start = lensPad), size = 13, color = Color(0x66FFFFFF))
                }
            }

            // The scene: the wake clip once, then the loop under the clock. The dust face is a key, not a file: drawn by
            // the same engine (its GLES 3 part), with no wake clip before it, as on the back.
            val dust = state.loopPath == dev.kb896.aiusage.rear.FaceCatalog.DUST
            val wake = state.wakePath?.takeIf { state.sceneOn && !dust && File(it).canRead() }
            val loop = state.loopPath?.takeIf { state.sceneOn && (dust || File(it).canRead()) }
            // The message island's tint of the live face, as on the back: the preview's engine reads it at each picture.
            val veil = remember { dev.kb896.aiusage.rear.NoticeVeil() }
            if (wake != null || loop != null) {
                ScenePlayer(wake, loop, withWake = mode == PreviewMode.WAKE, state.look, state.depth, Modifier.fillMaxSize(), veil) {
                    if (mode == PreviewMode.WAKE) ended()
                }
            } else {
                LaunchedEffect(mode) { if (mode == PreviewMode.WAKE) { kotlinx.coroutines.delay(900); ended() } }
            }

            if (mode == PreviewMode.CHARGING) ChargeShow(state, onEnded = { ended() })
            if (mode == PreviewMode.ALERT) {
                if (state.messageStyle == dev.kb896.aiusage.data.MessageStyle.FULL) Banner(state, onEnded = { ended() })
                else IslandBanner(state, veil, faceTints = loop != null, onEnded = { ended() })
            }
            if (mode in LIVE_PAGES) LivePage(mode, state)

            // The back's brightness, as the dim layer puts it on there: over the picture and every page.
            val gain by androidx.compose.animation.core.animateFloatAsState(state.gain.coerceIn(0f, 1f), androidx.compose.animation.core.tween(220), label = "gain")
            if (gain < 0.999f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - gain)))

            Canvas(Modifier.fillMaxSize()) { lenses() }
        }
    }
}

private val LIVE_PAGES = setOf(PreviewMode.MUSIC, PreviewMode.NAVIGATION, PreviewMode.TIMER, PreviewMode.CALL, PreviewMode.USAGE, PreviewMode.NOTES)

/**
 * One of the back's pages, drawn frame by frame by the back's own painter (MusicFace, TimerFace, CallFace,
 * UsageFace, PinFace), so the preview is the page and not a picture of it. Its data is made up where the page
 * needs any (a track, a timer, a caller): nothing here is the phone's, and a tap on a button changes only this
 * preview (pause and play, answer and the speaker, pause the timer).
 */
@Composable
private fun LivePage(mode: PreviewMode, state: PreviewState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var frame by remember { mutableLongStateOf(0L) }
    val began = remember(mode) { android.os.SystemClock.uptimeMillis() }
    var flippedAt by remember(mode) { mutableLongStateOf(0L) }
    var on by remember(mode) { mutableStateOf(true) }          // playing, running, ringing
    var speaker by remember(mode) { mutableStateOf(false) }
    // The page's own body, with no sound told to it: its made-up spectrum moves it calmly, as on the back with no level.
    val body = remember(mode) { dev.kb896.aiusage.rear.MusicFace.Body() }
    // A player that is on this phone, for the music page's cover place (a made-up track has no cover).
    val player: Bitmap? = remember {
        listOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.apple.android.music", "com.miui.player")
            .firstNotNullOfOrNull { pkg -> runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(256, 256, Bitmap.Config.ARGB_8888) }.getOrNull() }
    }
    val navigationIcon = remember {
        runCatching { context.packageManager.getApplicationIcon("com.google.android.apps.maps").toBitmap(128, 128, Bitmap.Config.ARGB_8888) }.getOrNull()
    }
    val navigation = remember(navigationIcon) { dev.kb896.aiusage.rear.NavFace.demo(appIcon = navigationIcon) }
    val palette = remember(player) { dev.kb896.aiusage.rear.MusicFace.palette(dev.kb896.aiusage.rear.MusicFace.seed(player)) }
    val timerArt = remember { runCatching { dev.kb896.aiusage.rear.XiaomiArt.load("timer", dev.kb896.aiusage.rear.TimerFace.ART) }.getOrDefault(emptyMap()) }
    val callArt = remember { runCatching { dev.kb896.aiusage.rear.XiaomiArt.load("phone", dev.kb896.aiusage.rear.CallFace.ART) }.getOrDefault(emptyMap()) }
    val timerLeft = remember(mode) { longArrayOf(272_000L, 0L) }      // what is left, and when it last set off (uptime)
    LaunchedEffect(mode) {
        if (mode != PreviewMode.NAVIGATION) while (true) withFrameMillis { frame = it }
    }
    // OpenAI's Dots character, as the back has it in ChatGPT's vessel (rear/dots), only while the usage page is shown here.
    val dotNow = androidx.compose.runtime.rememberUpdatedState(state.chatGptDot)
    val dots = remember(mode == PreviewMode.USAGE) { if (mode == PreviewMode.USAGE) dev.kb896.aiusage.rear.dots.DotsLink.preview { dotNow.value } else null }
    androidx.compose.runtime.DisposableEffect(dots) { onDispose { dots?.second?.invoke() } }
    val appIcons = remember(context, mode) { HashMap<String, Bitmap?>() }
    // The first note's picture, if it is a picture's, read off the main thread (its place empty until then).
    val notePicture by androidx.compose.runtime.produceState<Bitmap?>(null, state.noteImage) {
        value = state.noteImage?.let { n -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.kb896.aiusage.data.PinImageFiles.read(context, n) } }
    }
    Canvas(
        Modifier.fillMaxSize().pointerInput(mode) {
            detectTapGestures { at ->
                val x = at.x * REAR_W / size.width; val y = at.y * REAR_H / size.height
                val up = android.os.SystemClock.uptimeMillis()
                when (mode) {
                    PreviewMode.MUSIC -> if (dev.kb896.aiusage.rear.MusicFace.button(x, y) == dev.kb896.aiusage.rear.MusicFace.Button.PLAY_PAUSE) { on = !on; flippedAt = up }
                    PreviewMode.TIMER -> if (dev.kb896.aiusage.rear.TimerFace.side(x, y, false) == dev.kb896.aiusage.rear.TimerFace.Side.MAIN) {
                        if (on) timerLeft[0] = (timerLeft[0] - (up - maxOf(timerLeft[1], began))).coerceAtLeast(0L)
                        timerLeft[1] = up; on = !on
                    }
                    PreviewMode.CALL -> when (dev.kb896.aiusage.rear.CallFace.side(x, y)) {
                        dev.kb896.aiusage.rear.CallFace.Side.RIGHT -> if (on) { on = false; flippedAt = up }           // answered, here only
                        dev.kb896.aiusage.rear.CallFace.Side.LEFT -> if (!on) speaker = !speaker
                        null -> Unit
                    }
                    else -> Unit
                }
            }
        },
    ) {
        frame                                           // read: drawn again each frame
        val up = android.os.SystemClock.uptimeMillis()
        val wall = System.currentTimeMillis()
        scale(size.width / REAR_W, size.height / REAR_H, pivot = Offset.Zero) {
            drawIntoCanvas { cv ->
                val c = cv.nativeCanvas
                when (mode) {
                    PreviewMode.MUSIC -> {
                        // The setting as it is now: a change glides here as it does on the back.
                        body.floor = state.musicFloor
                        dev.kb896.aiusage.rear.MusicFace.draw(
                            c, REAR_W, REAR_H, "A song, as it plays", "The artist", on, null, palette, body.step(up, on),
                            sinceFlip = if (flippedAt == 0L) Long.MAX_VALUE else up - flippedAt, playerIcon = player,
                        )
                    }
                    PreviewMode.NAVIGATION -> dev.kb896.aiusage.rear.NavFace.draw(c, REAR_W, REAR_H, navigation)
                    PreviewMode.TIMER -> {
                        val left = if (on) (timerLeft[0] - (up - maxOf(timerLeft[1], began))).coerceAtLeast(0L) else timerLeft[0]
                        dev.kb896.aiusage.rear.TimerFace.draw(c, REAR_W, REAR_H, dev.kb896.aiusage.rear.TimerFace.Model("preview", "", "Timer", wall + left, left, 600_000L, paused = !on, over = false), wall, timerArt)
                    }
                    PreviewMode.CALL -> dev.kb896.aiusage.rear.CallFace.draw(
                        c, REAR_W, REAR_H,
                        dev.kb896.aiusage.rear.CallFace.Model("preview", "", "A caller", "Incoming call", ringing = on, since = if (on) 0L else wall - (up - flippedAt), speaker = speaker, headset = false),
                        wall, callArt, sinceChange = if (flippedAt == 0L) Long.MAX_VALUE else up - flippedAt, ringAt = wall - (up - began),
                    )
                    PreviewMode.USAGE -> dev.kb896.aiusage.rear.UsageFace.draw(
                        c, REAR_W, REAR_H, state.usage ?: dev.kb896.aiusage.rear.UsageFace.demo(wall, state.usageFiveHours), wall, up - began, timerArt[dev.kb896.aiusage.rear.TimerFace.ART_WATER],
                        icon = { pkg -> appIcons.getOrPut(pkg) { runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96, Bitmap.Config.ARGB_8888) }.getOrNull() } },
                        dots = dots?.first,
                    )
                    PreviewMode.NOTES -> dev.kb896.aiusage.rear.PinFace.draw(c, REAR_W, REAR_H, state.note.orEmpty(), "", 0, if (state.note == null) 0 else 1,
                        image = state.noteImage, picture = notePicture)
                    else -> Unit
                }
            }
        }
    }
}

/** The two lenses over the left of the panel; the picture runs on behind and around them, as on the phone. */
private fun DrawScope.lenses() {
    val k = size.width / REAR_W
    val r = 92f * k
    for (cy in listOf(152f * k, size.height - 152f * k)) {
        val c = Offset(150f * k, cy)
        drawCircle(Color(0xFF17181B), r + 5f * k, c)
        // Dark shapes and nothing in them: no glow, no highlight dot.
        drawCircle(Color(0xFF0B0C0F), r, c)
        drawCircle(Color(0x14FFFFFF), r + 5f * k, c, style = Stroke(1.2f * k))
    }
}

/**
 * The scene by the back's own engine, on a TextureView. A change of clip starts it again; the
 * clock's look and depth go to the running engine. [withWake] plays the wake clip first, and
 * [onWakeOver] says when the loop has taken over (or it has ended with nothing to follow).
 */
@Composable
private fun ScenePlayer(
    wake: String?, loop: String?, withWake: Boolean, look: SceneFace.Look, depth: Boolean,
    modifier: Modifier, veil: dev.kb896.aiusage.rear.NoticeVeil? = null, onWakeOver: () -> Unit,
) {
    val over by rememberUpdatedState(onWakeOver)
    val holder = remember { EngineHolder() }
    holder.veil = veil
    DisposableEffect(Unit) { onDispose { holder.release() } }
    holder.look(look, depth)
    LaunchedEffect(wake, loop, withWake) { holder.play(if (withWake) wake else null, loop, withWake) { over() } }
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                isOpaque = true
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = holder.surface(Surface(st), w, h)
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = holder.surface(Surface(st), w, h)
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { holder.surface(null, 0, 0); return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        modifier = modifier,
    )
}

/** One engine thread at a time for the preview's surface. Main thread, except the engine's own. */
private class EngineHolder {
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var wake: String? = null
    private var loop: String? = null
    private var playingLoop: String? = null
    private var onWakeOver: () -> Unit = {}
    private var look = SceneFace.Look()
    private var depth = true
    /** The message island's tint (the preview's island): given to each engine made. */
    var veil: dev.kb896.aiusage.rear.NoticeVeil? = null
    private var run: Run? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private class Run(val thread: Thread, @Volatile var stop: Boolean = false, @Volatile var engine: SceneEngine? = null)

    fun surface(s: Surface?, w: Int, h: Int) {
        stop()
        surface?.takeIf { it !== s }?.let { runCatching { it.release() } }
        surface = s; width = w; height = h
        if (s != null) start()
    }

    fun look(next: SceneFace.Look, d: Boolean) {
        look = next; depth = d
        run?.engine?.let { it.look = next; it.depth = d }
    }

    fun play(wake: String?, loop: String?, withWake: Boolean, onWakeOver: () -> Unit) {
        this.onWakeOver = onWakeOver
        // The loop already running is left running: the wake has just handed over to it.
        if (wake == null && loop == playingLoop && run?.thread?.isAlive == true) { this.wake = null; this.loop = loop; return }
        this.wake = wake; this.loop = loop
        if (withWake && wake == null) main.postDelayed({ this.onWakeOver() }, 600)
        start()
    }

    private fun start() {
        val s = surface ?: return
        if (width <= 0 || height <= 0 || (wake == null && loop == null)) return
        stop()
        val w = wake; val l = loop
        playingLoop = l
        lateinit var mine: Run
        mine = Run(Thread({
            val engine = SceneEngine(s, width, height, width * LENS_W / REAR_W, gles3 = l == dev.kb896.aiusage.rear.FaceCatalog.DUST).also { it.look = look; it.depth = depth; it.moonClip = loop?.endsWith(dev.kb896.aiusage.rear.ShellScene.MOON_CLIP) == true; it.weather = dev.kb896.aiusage.service.WeatherPoll.latest?.condition; it.veil = veil }
            mine.engine = engine
            runCatching {
                engine.play(w, l, object : SceneEngine.Control {
                    override val why = "the preview moved on"
                    override fun strength(): Float = if (mine.stop) SceneEngine.STOP else 1f
                    override fun onLoop() { main.post { if (run === mine) onWakeOver() } }
                })
            }.onSuccess { why -> if (why == "played") main.post { if (run === mine) onWakeOver() } }
                .onFailure { android.util.Log.w("AiUsagePreview", "preview scene stopped: ${it.message}") }
            mine.engine = null
        }, "preview-scene").apply { isDaemon = true })
        run = mine
        mine.thread.start()
    }

    private fun stop() {
        val r = run ?: return
        run = null
        r.stop = true
        // The surface is the engine's until its thread has let go of it.
        runCatching { r.thread.join(600) }
    }

    fun release() {
        stop()
        runCatching { surface?.release() }
        surface = null
    }
}

/** The charging animation, drawn by the back's own painter, once through. */
@Composable
private fun ChargeShow(state: PreviewState, onEnded: () -> Unit) {
    val painter = remember { ShellCharge.PreviewPainter(GEOMETRY) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    var over by remember { mutableStateOf(false) }
    LaunchedEffect(state.chargeStyle, state.chargePercent) {
        startedAt = 0L; over = false
        while (!over) withFrameMillis { t -> if (startedAt == 0L) startedAt = t; now = t }
        onEnded()
    }
    val level = (state.look.level.takeIf { it >= 0 } ?: 64) / 100f
    Canvas(Modifier.fillMaxSize()) {
        val elapsed = (now - startedAt).toFloat()
        scale(size.width / REAR_W, size.height / REAR_H, pivot = Offset.Zero) {
            drawIntoCanvas { c ->
                val canvas = c.nativeCanvas
                val layer = canvas.saveLayerAlpha(0f, 0f, REAR_W, REAR_H, 255)
                val alpha = painter.draw(canvas, state.chargeStyle, level, state.chargePercent, elapsed)
                canvas.restoreToCount(layer)
                if (alpha == null) over = true
            }
        }
    }
}

/** A notification's banner as it arrives on the back: in, held, out. */
@Composable
private fun Banner(state: PreviewState, onEnded: () -> Unit) {
    // The pop-up as the back draws it (rear/NoticePop): the whole screen, in the colour of the app it is from.
    // Shown as a message from a messenger that is on this phone, with its real icon and that icon's colour (green for
    // WhatsApp, say); with none of them installed, the quiet grey of an app without a colour.
    val context = androidx.compose.ui.platform.LocalContext.current
    val from = remember { SAMPLE_FROM.firstOrNull { pkg -> runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess } }
    val icon: Bitmap? = remember {
        from?.let { runCatching { context.packageManager.getApplicationIcon(it).toBitmap(96, 96, Bitmap.Config.ARGB_8888) }.getOrNull() }
    }
    val color = remember { from?.let { dev.kb896.aiusage.service.BackNotices.iconColor(context, it) } ?: 0 }
    var startedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (now - startedAt < BANNER_MS || startedAt == 0L) withFrameMillis { t -> if (startedAt == 0L) startedAt = t; now = t }
        onEnded()
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = (now - startedAt).toFloat()
        val inOut = (t / 260f).coerceIn(0f, 1f) * ((BANNER_MS - t) / 260f).coerceIn(0f, 1f)
        drawIntoCanvas { c ->
            val layer = c.nativeCanvas.saveLayerAlpha(0f, 0f, size.width, size.height, (inOut * 255f).toInt())
            if (state.noticeText) dev.kb896.aiusage.rear.NoticePop.draw(c.nativeCanvas, size.width, size.height, "Lucy", "I'm at the station", color, icon)
            else dev.kb896.aiusage.rear.NoticePop.draw(c.nativeCanvas, size.width, size.height, "", "New notification", color, icon)
            c.nativeCanvas.restoreToCount(layer)
        }
    }
}

/**
 * THE MESSAGE ISLAND as the back draws it (rear/IslandArt, rear/MessageIsland), over the preview's live face: the pill drops
 * in with Xiaomi's light round it, grows into the page, holds a little shorter than on the back, folds back and sinks away.
 * "Island · Tint": the preview's own engine takes the app's colour under the page ([veil]), as the back's does.
 */
@Composable
private fun IslandBanner(state: PreviewState, veil: dev.kb896.aiusage.rear.NoticeVeil, faceTints: Boolean, onEnded: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val from = remember { SAMPLE_FROM.firstOrNull { pkg -> runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess } }
    val icon: Bitmap? = remember {
        from?.let { runCatching { context.packageManager.getApplicationIcon(it).toBitmap(96, 96, Bitmap.Config.ARGB_8888) }.getOrNull() }
    }
    val color = remember { from?.let { dev.kb896.aiusage.service.BackNotices.iconColor(context, it) } ?: 0 }
    val art = remember { dev.kb896.aiusage.rear.IslandArt() }
    val frame = remember { dev.kb896.aiusage.rear.MessageIsland.Frame() }
    var startedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    val total = dev.kb896.aiusage.rear.MessageIsland.total(ISLAND_HOLD_MS)
    LaunchedEffect(state.messageStyle, state.noticeText) {
        val look = when {
            state.messageStyle == dev.kb896.aiusage.data.MessageStyle.SWEEP -> dev.kb896.aiusage.rear.MessageIsland.Look.SWEEP
            faceTints -> dev.kb896.aiusage.rear.MessageIsland.Look.TINT_OVER_FACE
            else -> dev.kb896.aiusage.rear.MessageIsland.Look.TINT_OWN
        }
        if (state.noticeText) art.set("Lucy", "I'm at the station", color, icon, look) else art.set("", "New notification", color, icon, look)
        val start = android.os.SystemClock.uptimeMillis()
        veil.begin(color, start, art.pillW, ISLAND_HOLD_MS, tints = look === dev.kb896.aiusage.rear.MessageIsland.Look.TINT_OVER_FACE)
        startedAt = start; now = start
        while (now - start < total) withFrameMillis { now = android.os.SystemClock.uptimeMillis() }
        veil.end(android.os.SystemClock.uptimeMillis())
        onEnded()
    }
    DisposableEffect(Unit) { onDispose { veil.end(android.os.SystemClock.uptimeMillis()) } }
    Canvas(Modifier.fillMaxSize()) {
        if (startedAt == 0L) return@Canvas
        dev.kb896.aiusage.rear.MessageIsland.at((now - startedAt).toFloat(), art.pillW, frame, ISLAND_HOLD_MS.toFloat(), sweeps = art.look.sweep)
        drawIntoCanvas { c -> art.draw(c.nativeCanvas, size.width, size.height, frame) }
    }
}

/** The island's page held in the preview: shorter than on the back, so the whole of it is seen in a few seconds. */
private const val ISLAND_HOLD_MS = 2_500L

/** The preview's message is shown as from the first of these that is installed. */
private val SAMPLE_FROM = listOf("com.whatsapp", "org.telegram.messenger")
private const val BANNER_MS = 3200f
