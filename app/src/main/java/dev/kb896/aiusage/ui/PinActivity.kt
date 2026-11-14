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

/**
 * "Pin to back": text shared to this app, or picked in any app's text-selection menu, becomes a note on the back's
 * notes page (rear/PinFace: one swipe up from the scene), AND IS BEAMED THERE (rear/PinBeam). Over whatever app he is
 * in, see-through: what is behind blurs, the pinned words lift off as a sheet, a card in the back screen's shape
 * comes down to the top of the screen, takes the words as its face, turns away and goes into the top edge; as it goes
 * the back is told, and the same card comes out there and becomes the notes page. No toast, no icon.
 *
 * Xiaomi's own pin page is under the cover and never seen, so pins sent there went nowhere anyone could look; this is
 * ours, with Xiaomi's motion.
 *
 *   ACTION_SEND, text/plain, EXTRA_TEXT            -> dev.chicken.rear.dev/dev.kb896.aiusage.ui.PinActivity
 *   ACTION_PROCESS_TEXT, text/plain ("Pin to back")
 *   a debug build: --ez demo true shows it all with nothing kept (the back plays a made-up note, marked "Demo");
 *                  --ez unpin true takes a note with exactly these words away again (a test's own);
 *                  --ez test true marks a real pin as a test's (silent); --ez sound true lets a demo or a test sound
 */
class PinActivity : Activity() {
    /** This pin: the back told and the sound played once, whatever draws it and however it ends (PinRun). Null: nothing to pin. */
    private var run: PinRun? = null
    /** The glass service's overlay plays it (over an app that is itself an overlay): this screen has gone and ends nothing. */
    private var handedOver = false
    /** The page behind this screen as it was when he pinned (service/PinGlassService), while the animation plays; null without. NEVER kept past [onDestroy], never written or logged. */
    @Volatile private var behind: android.graphics.Bitmap? = null

    override fun onDestroy() {
        behind?.recycle(); behind = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val text = textOf(intent)
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Nothing to pin", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // A debug build: --ez unpin true takes the note with exactly these words away again (a test's own note), no show.
        if (dev.kb896.aiusage.BuildConfig.DEBUG && intent.getBooleanExtra("unpin", false)) {
            val store = ConfigStore(applicationContext)
            val config = store.load()
            val next = config.copy(pins = config.pins.filter { it.text != text.trim().take(LONGEST) })
            if (next.pins.size != config.pins.size) {
                store.save(next)
                val graph = Graph.of(this)
                runCatching { graph.bridge.update(StatePayload.encode(graph.repository.state.value, next, WeatherPoll.latest)) }
            }
            finish()
            return
        }
        val demo = dev.kb896.aiusage.BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)
        val clean = text.trim().take(LONGEST)
        // A demo or a test's pin is silent unless it asks for the sound (--ez sound true).
        val demoPin = demo || (dev.kb896.aiusage.BuildConfig.DEBUG && intent.getBooleanExtra("test", false))
        val soundAsked = intent.getBooleanExtra("sound", false)
        val tell: Runnable
        var byXiaomi = true
        // The sound made ready now, so that it is heard when it is asked for and not a quarter of a second later.
        if (!demoPin || soundAsked) dev.kb896.aiusage.service.PinChime.prepare(this)
        if (demo) {
            // Nothing kept: the back is asked for its own made-up arrival, through the debug file it reads.
            tell = Runnable {
                runCatching { java.io.File(getExternalFilesDir(null), "pop-demo").writeText("pinbeam|" + clean.replace('|', ' ').replace('\n', ' ')) }
            }
        } else {
            // KEPT AT ONCE (whatever becomes of this screen); the back is told as the card leaves the front.
            val store = ConfigStore(applicationContext)
            val config = store.load()
            val next = config.copy(pins = added(config.pins, text, System.currentTimeMillis()))
            store.save(next)
            byXiaomi = config.pinByXiaomi
            tell = Runnable {
                val graph = Graph.of(this)
                runCatching { graph.bridge.update(StatePayload.encode(graph.repository.state.value, next, WeatherPoll.latest)) }
            }
        }
        // THROUGH XIAOMI'S OWN PIN (rear/PinVia): the note is ours (kept above), the words go
        // to Xiaomi's pin, which plays its real animation, island and sound on the front; the back is told so and ends
        // on our notes page. NOTHING OF OURS IS DRAWN ON THE FRONT AND NO SOUND OF OURS PLAYS. Where Xiaomi's pin cannot
        // be started (another phone, its app gone or refusing), or the shell is not there to bring our page in at the
        // end, it is our own animation, as before. Not for a demo.
        // WHICH OF THE TWO is the settings' choice ("Pin animation": Xiaomi's or Ours, PanelConfig.pinByXiaomi).
        val ownAsked = dev.kb896.aiusage.BuildConfig.DEBUG && (intent.getBooleanExtra("gl", false) || intent.getBooleanExtra("own", false))
        if (dev.kb896.aiusage.rear.PinVia.wanted(byXiaomi, demo, ownAsked) && viaXiaomi(clean, tell)) {
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
        val gl = dev.kb896.aiusage.BuildConfig.DEBUG && intent.getBooleanExtra("gl", false)
        // OVER AN APP THAT IS ITSELF AN OVERLAY (Circle Search): an activity's window lies under such an overlay, so
        // with the glass service on the animation plays in an overlay of the service's own, above it, touching
        // nothing, and this screen is gone at once: he is where he was, with Circle Search still open. Without the
        // service it plays here, as before. (The service takes the picture of the page behind itself, before its
        // overlay is there.)
        if (!gl && dev.kb896.aiusage.service.PinGlassService.beam(clean, origin, run)) {
            handedOver = true
            finish()
            overridePendingTransition(0, 0)
            return
        }
        // THE PAGE BEHIND, FOR THE GLASS: asked for now, before anything of the pin is on the screen. The animation never
        // waits for it (it begins over the system's blur) and takes it when it comes. In memory only, let go at the end.
        dev.kb896.aiusage.service.PinGlassService.shot { picture ->
            if (isFinishing || isDestroyed) picture?.recycle() else behind = picture
        }
        window.apply {
            if (fromOverlay) addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            statusBarColor = 0; navigationBarColor = 0
            setDecorFitsSystemWindows(false)
            attributes = attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        }
        // THE ONE-TO-ONE PATH (Xiaomi's own models and pictures, OpenGL: rear/PinGl) is plumbing so far and not the pin's
        // look: only a debug build asked for it (--ez gl true) takes it; every real pin is the plain drawing.
        val assets = if (gl) {
            val own = java.io.File(getExternalFilesDir(null), "xiaomi-test.apk").takeIf { it.canRead() }?.path
            dev.kb896.aiusage.rear.XiaomiPin.load(own)
        } else null
        setContentView(if (assets != null) GlBeamView(clean, assets, run) else PinBeamView(this, clean, origin, run, { r -> window.setBackgroundBlurRadius(r) }, touchEnds = true) { end() })
    }

    /**
     * The front's half through OpenGL: a see-through surface over the app he is in, Xiaomi's models where its 22
     * values put them ([dev.kb896.aiusage.rear.PinKeys]), on a thread of its own. The page behind, when the glass's
     * picture comes, is handed to the scene as a texture.
     */
    private inner class GlBeamView(private val text: String, private val assets: dev.kb896.aiusage.rear.XiaomiPin.Assets, private val run: PinRun) : android.view.SurfaceView(this), android.view.SurfaceHolder.Callback {
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
                PinBeamArt().sheet(android.graphics.Canvas(it), text, 500f, 210f, 960f, 1f, 1f, 0f, 1f)
            }
            val face = android.graphics.Bitmap.createBitmap(976, 596, android.graphics.Bitmap.Config.ARGB_8888).also {
                PinFace.draw(android.graphics.Canvas(it), 976f, 596f, text, PinFace.stamp(System.currentTimeMillis(), System.currentTimeMillis()), 0, 1, false)
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

    /** The words handed to Xiaomi's own pin and the back told; false where either cannot be done (then nothing was started). */
    private fun viaXiaomi(text: String, tell: Runnable): Boolean {
        val graph = Graph.of(this)
        if (!graph.bridge.status().granted) return false
        val pin = Intent(Intent.ACTION_SEND).setClassName(dev.kb896.aiusage.rear.PinVia.PACKAGE, dev.kb896.aiusage.rear.PinVia.ACTIVITY)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (packageManager.resolveActivity(pin, 0) == null) return false
        // The shell first (so the note arriving in our list is not taken for one to beam in itself), then our list (the
        // back's notes page has the note whatever Xiaomi does), then Xiaomi's pin.
        val at = android.os.SystemClock.uptimeMillis()
        val heard = graph.bridge.pinVia(android.os.Bundle().apply { putString("text", text); putLong("at", at); putBoolean("full", dev.kb896.aiusage.BuildConfig.DEBUG && intent.getBooleanExtra("full", false)) })
        if (!heard) { android.util.Log.i(TAG, "pin: no shell to bring our page in after Xiaomi's — our own animation"); return false }
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
        /** The back is told this long before its arrival is due: the way there (a binder call, a payload read). */
        const val TELL_LEAD_MS = 70L
        /** Where the pinned words are on the screen: an int array left, top, right, bottom in screen pixels. Optional, from any caller. */
        const val EXTRA_TEXT_RECT = "dev.chicken.rear.extra.TEXT_RECT"
        /** Circle Search's extras: the selected words' rectangle (an android.graphics.Rect, screen pixels), and that the caller is an overlay. */
        const val EXTRA_SELECTION_RECT = "dev.chicken.extra.SELECTION_RECT"
        const val EXTRA_FROM_OVERLAY = "dev.chicken.extra.FROM_OVERLAY"

        /** The first of [given] that is a rectangle with something in it (left, top, right, bottom): where the words are; null for none. Pure. */
        fun rectOf(vararg given: IntArray?): IntArray? = given.firstOrNull { it != null && it.size == 4 && it[2] > it[0] && it[3] > it[1] }
        private const val TAG = "AiUsagePin"
        const val MOST = 20
        const val LONGEST = 600

        fun textOf(intent: Intent?): String? = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }?.trim()

        /** [pins] with [text] pinned at [now] in front: the same words pinned before move up rather than twice; at most [MOST]. */
        fun added(pins: List<Pin>, text: String, now: Long): List<Pin> {
            val clean = text.trim().take(LONGEST)
            return (listOf(Pin(clean, now)) + pins.filter { it.text != clean }).take(MOST)
        }
    }
}
