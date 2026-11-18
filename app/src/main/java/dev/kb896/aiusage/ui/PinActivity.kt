package dev.kb896.aiusage.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.data.StatePayload
import dev.kb896.aiusage.rear.PinBeam
import dev.kb896.aiusage.rear.PinBeamArt
import dev.kb896.aiusage.rear.PinFace
import dev.kb896.aiusage.service.Graph
import dev.kb896.aiusage.service.WeatherPoll

class PinActivity : Activity() {
    /** This pin: the back told and the sound played once, whatever draws it and however it ends (PinRun). Null: nothing to pin. */
    private var run: PinRun? = null
    /** The glass service's overlay plays it (over an app that is itself an overlay): this screen has gone and ends nothing. */
    private var handedOver = false
    /** The page behind this screen as it was when he pinned (service/PinGlassService), while the animation plays; null without. NEVER kept past [onDestroy], never written or logged. */
    @Volatile private var behind: android.graphics.Bitmap? = null
    /** This intent's debug extras, let in only by the shell's token (DebugLock): any app can start this screen. */
    private lateinit var debug: dev.kb896.aiusage.DebugExtras

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::debug.isInitialized) debug.save(outState)
    }

    override fun onDestroy() {
        behind?.recycle(); behind = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val debug = dev.kb896.aiusage.DebugExtras.of(this, intent, DEBUG_EXTRAS, savedInstanceState).also { this.debug = it }
        // A debug build: draft B's back alone in this window (--ez rearpreview true [--ef slow 4]), for an emulator.
        if (debug.bool("rearpreview")) {
            val apk = java.io.File(getExternalFilesDir(null), "xiaomi-test.apk").takeIf { it.canRead() }?.path
            val seq = dev.kb896.aiusage.rear.XiaomiPin.veil(apk) ?: run { finish(); return }
            val box = android.widget.FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
            box.addView(PinBRearPreview(this, seq, debug.float("slow", 1f).coerceIn(0.25f, 20f)) { finish() },
                android.widget.FrameLayout.LayoutParams(976, 596, android.view.Gravity.CENTER))
            setContentView(box)
            return
        }
        val share = PinShare.of(intent)
        val keptAt = intent.getLongExtra(EXTRA_KEPT_AT, 0L)
        // Words, or a picture's caption (a note kept by Pin to location may be a picture's with none: it is found below).
        val text = when (share) { is PinShare.Words -> share.text; is PinShare.Pictures -> share.caption; PinShare.Nothing -> "" }
        if (share == PinShare.Nothing && keptAt == 0L) {
            Toast.makeText(this, "Nothing to pin", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // A debug build: --ez unpin true takes the note with exactly these words away again (a test's own note), no show.
        // A picture's note by its caption (its file goes with it: ConfigStore.save); never by no words, which would be
        // every picture's note without a caption.
        if (debug.bool("unpin")) {
            val store = ConfigStore(applicationContext)
            val config = store.load()
            val words = text.trim().take(LONGEST)
            val next = if (words.isEmpty()) config else config.copy(pins = config.pins.filter { it.text != words })
            if (next.pins.size != config.pins.size) {
                store.save(next)
                val graph = Graph.of(this)
                runCatching { graph.bridge.update(StatePayload.encode(graph.repository.state.value, next, WeatherPoll.latest)) }
            }
            finish()
            return
        }
        // A PICTURE (data/PinImage): read ONCE now, through the address the share granted, off the main thread (it may
        // come from another app's storage or the network), scaled and kept as a file of ours; the address is not kept.
        // Then the pin as for words, the picture where the words would be. Not for a demo, which keeps nothing.
        if (share is PinShare.Pictures && !debug.bool("demo")) {
            val app = applicationContext
            val base = System.currentTimeMillis()
            val n = share.uris.size
            if (share.left > 0) android.util.Log.i(TAG, "pin: ${n + share.left} pictures shared — the first $n pinned, one note each")
            val read = importPicture
            reading = true
            Thread({
                // One note each, the first shared in front (the latest time); each read once, in turn.
                val made = share.uris.mapIndexedNotNull { i, uri -> read(app, uri, base + (n - 1 - i)) }
                runOnUiThread { picturesRead(share, made) }
            }, "pin-picture").apply { isDaemon = true; start() }
            return
        }
        begin(text, emptyList())
    }

    /** A shared picture is being read: nothing of the pin is on the screen yet ([onTouchEvent]). */
    private var reading = false

    /**
     * WHILE A SHARED PICTURE IS READ nothing of the pin is drawn, but this see-through window lies over the whole screen
     * and would hold every touch (a not-touchable one would not pass them on either: an untrusted occluder from Android
     * 12, PinGlassTest), the phone looking frozen for as long as the read takes (a picture from the network). A touch
     * ends it, as one ends the pin's animation; the picture is pinned all the same once it is read, and the back shows it.
     */
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (reading && event.actionMasked == android.view.MotionEvent.ACTION_DOWN) { end(); return true }
        return super.onTouchEvent(event)
    }

    /** The shared pictures read ([made]: those that could be): pinned, or, with none readable, the caption alone or nothing. */
    private fun picturesRead(share: PinShare.Pictures, made: List<dev.kb896.aiusage.data.PinImageFiles.Imported>) {
        reading = false
        if (made.isEmpty() && share.caption.isBlank()) {
            if (!isDestroyed) Toast.makeText(this, "The picture could not be read", Toast.LENGTH_SHORT).show()
            if (!isFinishing) finish()
            return
        }
        begin(share.caption, made)
    }

    /**
     * THE PIN: [text] (words, or the pictures' caption) and [made] (the pictures read, one note each, the first in front),
     * kept, the back told, and its animation (Xiaomi's own or ours). A screen that has gone while the pictures were read
     * keeps the notes and tells the back, with no animation.
     */
    private fun begin(text: String, made: List<dev.kb896.aiusage.data.PinImageFiles.Imported>) {
        val debug = this.debug
        val demo = debug.bool("demo")
        val clean = text.trim().take(LONGEST)
        // A demo or a test's pin is silent unless it asks for the sound (--ez sound true).
        val demoPin = demo || debug.bool("test")
        val soundAsked = debug.bool("sound")
        val tell: Runnable
        var byXiaomi = true
        // The picture the animation shows (the note in front's), and its file's name.
        var image: String? = made.firstOrNull()?.name
        var picture: android.graphics.Bitmap? = made.firstOrNull()?.picture
        if (demo) {
            if (clean.isBlank()) { Toast.makeText(this, "Nothing to pin", Toast.LENGTH_SHORT).show(); finish(); return }
            // Nothing kept: the back is asked for its own made-up arrival, through the debug file it reads.
            tell = Runnable {
                runCatching { java.io.File(getExternalFilesDir(null), "pop-demo").writeText("pinbeam|" + clean.replace('|', ' ').replace('\n', ' ')) }
            }
        } else {
            // KEPT AT ONCE (whatever becomes of this screen); the back is told as the card leaves the front.
            val store = ConfigStore(applicationContext)
            val config = store.load()
            // Pin to location (ui/place/PlacePinActivity) has kept the note with its place already: only its animation here,
            // and the tell (the store as it is then: the place's fence may have been set meanwhile; Places held it till now).
            val keptAt = intent.getLongExtra(EXTRA_KEPT_AT, 0L)
            val keptPin = if (keptAt != 0L) config.pins.firstOrNull { it.at == keptAt && it.text == clean } else null
            val kept = keptPin != null
            if (keptPin?.image != null) { image = keptPin.image; picture = dev.kb896.aiusage.data.PinImageFiles.read(this, keptPin.image) }
            if (!kept && clean.isBlank() && made.isEmpty()) { Toast.makeText(this, "Nothing to pin", Toast.LENGTH_SHORT).show(); finish(); return }
            val next = when {
                kept -> config
                made.isNotEmpty() -> config.copy(pins = dev.kb896.aiusage.place.PinList.addedPictures(config.pins, clean, made.map { it.at to it.name }).pins)
                else -> config.copy(pins = added(config.pins, text, System.currentTimeMillis()))
            }
            if (!kept) store.save(next)
            byXiaomi = config.pinByXiaomi
            tell = if (kept) Runnable { dev.kb896.aiusage.place.Places.toldByPin(applicationContext) } else Runnable {
                val graph = Graph.of(this)
                runCatching { graph.bridge.update(StatePayload.encode(graph.repository.state.value, next, WeatherPoll.latest)) }
            }
        }
        // The screen went while the pictures were read: the notes are his all the same, and the back shows them now.
        if (isFinishing || isDestroyed) { tell.run(); return }
        // The sound made ready now, so that it is heard when it is asked for and not a quarter of a second later.
        if (!demoPin || soundAsked) dev.kb896.aiusage.service.PinChime.prepare(this)
        val ownAsked = debug.bool("gl") || debug.bool("own")
        if (dev.kb896.aiusage.rear.PinVia.wanted(byXiaomi, demo, ownAsked) && viaXiaomi(clean, image, tell)) {
            handedOver = true
            finish()
            overridePendingTransition(0, 0)
            return
        }
        val run = PinRun(applicationContext, tell, demoPin, soundAsked).also { this.run = it }
        // Where the words are on the screen, if the caller says (left, top, right, bottom in screen pixels): the sheet lifts off there.
        val origin = rectOf(
            intent.getIntArrayExtra(EXTRA_TEXT_RECT),
            // Circle Search's own word for the same: a Rect of the selected words, in screen pixels.
            runCatching { intent.getParcelableExtra(EXTRA_SELECTION_RECT, android.graphics.Rect::class.java) }.getOrNull()?.let { intArrayOf(it.left, it.top, it.right, it.bottom) },
        )?.let { android.graphics.RectF(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) }
        // The caller is itself an overlay (Circle Search says so): it keeps the keys while this plays, so this screen
        // must not take them, here or on its way out. (Nothing here ever sends Back or Home.)
        val fromOverlay = intent.getBooleanExtra(EXTRA_FROM_OVERLAY, false)
        val gl = debug.bool("gl")
        val debugApk = if (dev.kb896.aiusage.BuildConfig.DEBUG) java.io.File(getExternalFilesDir(null), "xiaomi-test.apk").takeIf { it.canRead() }?.path else null
        val plainAsked = debug.bool("plain")
        val bLight = if (gl || plainAsked) null else dev.kb896.aiusage.rear.XiaomiPin.light(debugApk)
        val slow = if (demo) debug.float("slow", 1f).coerceIn(1f, 20f) else 1f
        if (!gl && dev.kb896.aiusage.service.PinGlassService.beam(clean, origin, run, bLight, slow, picture, image)) {
            handedOver = true
            finish()
            overridePendingTransition(0, 0)
            return
        }
        // THE PAGE BEHIND, FOR THE GLASS: asked for now, before anything of the pin is on the screen. The animation never
        // waits for it (it begins over the system's blur) and takes it when it comes. In memory only, let go at the end.
        dev.kb896.aiusage.service.PinGlassService.shot { page ->
            if (isFinishing || isDestroyed) page?.recycle() else { behind = page; bView?.behind = page }
        }
        window.apply {
            if (fromOverlay) addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            // The old translucent theme lets the system draw its own black bars behind the status and navigation bars
            // (seen on the emulator: a black band over the top of the screen while a pin played, the hole and the island
            // under it). This window draws them itself, see-through: the card comes out of the hole, the island grows there.
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = 0; navigationBarColor = 0
            setDecorFitsSystemWindows(false)
            attributes = attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        }
        // THE MODELS' PLUMBING (Xiaomi's own models, OpenGL: rear/PinGl) is not the pin's look: only a debug build asked
        // for it (--ez gl true) takes it. A real pin of ours is draft B (above) or, without Xiaomi's pictures, the plain drawing.
        val assets = if (gl) dev.kb896.aiusage.rear.XiaomiPin.load(debugApk) else null
        // A picture's pin: the picture where the words would be, in each drawing (the plain one too, its fallback).
        val plain = { PinBeamView(this, clean, origin, run, { r -> window.setBackgroundBlurRadius(r) }, touchEnds = true, picture = picture, image = image) { end() } }
        setContentView(when {
            assets != null -> GlBeamView(clean, assets, run, picture, image)
            bLight != null -> PinBView(
                // A touch ends it, the island too. NOT LET THROUGH: from Android 12 a touch that passes through another
                // app's not-touchable activity window is dropped (untrusted touch occlusion), so "through to the app under"
                // would have been 2.2 s of dead screen.
                this, clean, origin, run, bLight, blur = { r -> runCatching { window.setBackgroundBlurRadius(r) } }, touchEnds = true,
                onFailed = { bView = null; if (!isFinishing) setContentView(plain()) },
                end = { end() },
                slow = slow,
                picture = picture, image = image,
            ).also { v -> bView = v; behind?.let { v.behind = it } }
            else -> plain()
        })
    }

    /** Draft B's view while it plays (it takes the page behind when the glass's picture comes). */
    private var bView: PinBView? = null

    /**
     * The front's half through OpenGL: a see-through surface over the app he is in, Xiaomi's models where its 22
     * values put them ([dev.kb896.aiusage.rear.PinKeys]), on a thread of its own. The page behind, when the glass's
     * picture comes, is handed to the scene as a texture.
     */
    private inner class GlBeamView(private val text: String, private val assets: dev.kb896.aiusage.rear.XiaomiPin.Assets, private val run: PinRun,
                                   private val picture: android.graphics.Bitmap? = null, private val image: String? = null) : android.view.SurfaceView(this), android.view.SurfaceHolder.Callback {
        @Volatile private var running = false
        private var thread: Thread? = null

        init {
            setZOrderOnTop(true)
            holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            holder.addCallback(this)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean { if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) end(); return true }
        override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) = Unit
        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) { running = false; runCatching { thread?.join(500) } }

        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
            if (running) return
            running = true
            // The content: the pinned words as a sheet; the card's face: the back's own notes page with this note on it.
            val content = android.graphics.Bitmap.createBitmap(1000, 420, android.graphics.Bitmap.Config.ARGB_8888).also {
                if (picture != null) PinBeamArt().sheetPicture(android.graphics.Canvas(it), picture, 500f, 210f, 960f, 1f, 1f, 0f, 1f)
                else PinBeamArt().sheet(android.graphics.Canvas(it), text, 500f, 210f, 960f, 1f, 1f, 0f, 1f)
            }
            val face = android.graphics.Bitmap.createBitmap(976, 596, android.graphics.Bitmap.Config.ARGB_8888).also {
                PinFace.draw(android.graphics.Canvas(it), 976f, 596f, text, PinFace.stamp(System.currentTimeMillis(), System.currentTimeMillis()), 0, 1, false, image = image, picture = picture)
            }
            thread = Thread({
                val host = dev.kb896.aiusage.rear.PinGl.Host(holder.surface)
                var frames = 0; var worst = 0L; var last = 0L
                try {
                    if (!host.make()) { runOnUiThread { end() }; return@Thread }
                    val scene = dev.kb896.aiusage.rear.PinGl.Scene(assets)
                    scene.init(content, face)
                    val keys = FloatArray(dev.kb896.aiusage.rear.PinKeys.COUNT)
                    val began = android.os.SystemClock.uptimeMillis()
                    run.beganAt = began
                    android.util.Log.i(TAG, "beam: the front begins (OpenGL, Xiaomi's models)")
                    var given = false; var toldBack = false; var chimedNow = false; var blurred = -1
                    while (running) {
                        val now = android.os.SystemClock.uptimeMillis()
                        val t = now - began
                        if (!given) behind?.let { runCatching { scene.behind(it) }; given = true }
                        dev.kb896.aiusage.rear.PinKeys.at(t, width.toFloat(), height.toFloat(), content.width.toFloat(), content.height.toFloat(), keys)
                        scene.draw(keys, width, height)
                        host.swap()
                        frames++; if (last != 0L) worst = maxOf(worst, now - last); last = now
                        val radius = (keys[dev.kb896.aiusage.rear.PinKeys.BLUR] * 0.7f).toInt()
                        if (radius != blurred) { blurred = radius; runOnUiThread { runCatching { window.setBackgroundBlurRadius(radius) } } }
                        if (!toldBack && t >= PinBeam.ARRIVE_AT - TELL_LEAD_MS) { toldBack = true; runOnUiThread { run.tellBack() } }
                        if (!chimedNow && t >= PinBeam.CHIME_AT - dev.kb896.aiusage.service.PinChime.LEAD_MS) { chimedNow = true; runOnUiThread { run.chime() } }
                        if (t >= PinBeam.FRONT_MS) break
                    }
                    android.util.Log.i(TAG, "beam: the front is done (OpenGL); $frames pictures, $worst ms at most between two")
                } catch (e: Throwable) {
                    android.util.Log.w(TAG, "beam: OpenGL failed: ${e.message.orEmpty().take(160)}")
                } finally {
                    host.release()
                    content.recycle(); face.recycle()
                    runOnUiThread { end() }
                }
            }, "pin-gl").apply { isDaemon = true; start() }
        }
    }

    /**
     * The words (or the picture: [image], a kept file's name) handed to Xiaomi's own pin and the back told; false where
     * either cannot be done (then nothing was started). A PICTURE GOES AS XIAOMI'S PIN TAKES ONE FROM ANY APP: ACTION_SEND
     * image/any, EXTRA_STREAM a content address of OUR OWN (the FileProvider over `pins/`, data/PinImageFiles.uri), which
     * Xiaomi's package alone is let read, that one address (the grant is let go when the note's file goes).
     */
    private fun viaXiaomi(text: String, image: String?, tell: Runnable): Boolean {
        val graph = Graph.of(this)
        if (!graph.bridge.status().granted) return false
        val pin = Intent(Intent.ACTION_SEND).setClassName(dev.kb896.aiusage.rear.PinVia.PACKAGE, dev.kb896.aiusage.rear.PinVia.ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (image == null) pin.setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        else {
            val uri = dev.kb896.aiusage.data.PinImageFiles.uri(this, image)
                ?: run { android.util.Log.i(TAG, "pin: the picture's file has no address to hand over — our own animation"); return false }
            pin.setType(dev.kb896.aiusage.data.PinImage.mime(image)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            pin.clipData = android.content.ClipData.newRawUri("", uri)
        }
        if (packageManager.resolveActivity(pin, 0) == null) return false
        // Only on the version of Xiaomi's rear app its times were measured on (PinVia.BUILT_ON): else ours.
        val version = runCatching { packageManager.getPackageInfo(dev.kb896.aiusage.rear.PinVia.PACKAGE, 0).longVersionCode }.getOrNull()
        if (!dev.kb896.aiusage.rear.PinVia.timedFor(version)) { android.util.Log.i(TAG, "pin: Xiaomi's rear app is version $version, the pin was timed on ${dev.kb896.aiusage.rear.PinVia.BUILT_ON} — our own animation"); return false }
        // The shell first (so the note arriving in our list is not taken for one to beam in itself), then our list (the
        // back's notes page has the note whatever Xiaomi does), then Xiaomi's pin.
        val at = android.os.SystemClock.uptimeMillis()
        val heard = graph.bridge.pinVia(android.os.Bundle().apply { putString("text", text); putString("image", image); putLong("at", at); putBoolean("full", debug.bool("full")) })
        if (!heard) { android.util.Log.i(TAG, "pin: no shell to bring our page in after Xiaomi's — our own animation"); return false }
        // A picture: also beyond the screen that gets it (Xiaomi's back may read it as its arrival plays), for that one
        // address and its package only; given only now that it is really handed over.
        pin.clipData?.getItemAt(0)?.uri?.let { uri -> runCatching { grantUriPermission(dev.kb896.aiusage.rear.PinVia.PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        tell.run()
        return runCatching { startActivity(pin); android.util.Log.i(TAG, "pin: handed to Xiaomi's own pin"); true }
            .onFailure { android.util.Log.w(TAG, "pin: Xiaomi's own pin could not be started (${it.javaClass.simpleName}) — our own animation") }.getOrDefault(false)
    }

    override fun onPause() {
        super.onPause()
        if (handedOver) return
        // Covered or left before the end: the note is his all the same, and the back shows it now.
        run?.tellBack()
        if (!isFinishing) end()
    }

    private fun end() {
        run?.finish()
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        /** The debug extras this screen takes (a debug build, with the shell's token: dev.kb896.aiusage.DebugLock). */
        val DEBUG_EXTRAS = listOf("rearpreview", "slow", "unpin", "demo", "test", "sound", "gl", "own", "plain", "full")
        /** The back is told this long before its arrival is due: the way there (a binder call, a payload read). */
        const val TELL_LEAD_MS = 70L
        /** Where the pinned words are on the screen: an int array left, top, right, bottom in screen pixels. Optional, from any caller. */
        const val EXTRA_TEXT_RECT = "dev.chicken.rear.extra.TEXT_RECT"
        /** Circle Search's extras: the selected words' rectangle (an android.graphics.Rect, screen pixels), and that the caller is an overlay. */
        const val EXTRA_SELECTION_RECT = "dev.chicken.extra.SELECTION_RECT"
        const val EXTRA_FROM_OVERLAY = "dev.chicken.extra.FROM_OVERLAY"
        /** The note with these words pinned at this time (wall ms) is kept already (Pin to location): the animation alone, nothing added. */
        const val EXTRA_KEPT_AT = "dev.chicken.rear.extra.KEPT_AT"

        /** The first of [given] that is a rectangle with something in it (left, top, right, bottom): where the words are; null for none. Pure. */
        fun rectOf(vararg given: IntArray?): IntArray? = given.firstOrNull { it != null && it.size == 4 && it[2] > it[0] && it[3] > it[1] }
        /** How a shared picture is read and kept (data/PinImageFiles.import), off the main thread; a test puts its own in to hold a read. */
        @Volatile internal var importPicture: (android.content.Context, android.net.Uri, Long) -> dev.kb896.aiusage.data.PinImageFiles.Imported? =
            dev.kb896.aiusage.data.PinImageFiles::import
        private const val TAG = "AiUsagePin"
        const val MOST = 20
        const val LONGEST = 600

        fun textOf(intent: Intent?): String? = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }?.trim()

        /**
         * [pins] with [text] pinned at [now] in front: the same words pinned before move up rather than twice; at most
         * [MOST], plain notes going first and a note for a place never replaced by a plain one (place/PinList).
         */
        fun added(pins: List<Pin>, text: String, now: Long): List<Pin> = dev.kb896.aiusage.place.PinList.added(pins, text, now).pins
    }
}
