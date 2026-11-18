package dev.kb896.aiusage.rear

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.View
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MamlHost(private val app: Context?) {
    private val thread = HandlerThread("rear-maml", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var engine: Engine? = null
    @Volatile private var engineFailed: String? = null

    /** What this renderer can draw (FaceCatalog rule 7): the software path's. */
    val abilities: Set<MamlCensus.Kind> = setOf(MamlCensus.Kind.LOTTIE)

    /** Why the engine could not be loaded in this process, or null. */
    val failure: String? get() = engineFailed

    private class Engine(
        val ctx: Context,
        val cZip: Class<*>, val cLoader: Class<*>, val cRm: Class<*>, val cSc: Class<*>, val cRoot: Class<*>, val cView: Class<*>,
        val cRenderable: Class<*>,
        val parseVar: java.lang.reflect.Method?, val toOne: java.lang.reflect.Method?, val restore: java.lang.reflect.Method?,
        val gson: Any?, val cOne: Class<*>?,
    )

    /** [block] on the engine's thread, waiting at most [ms]; its throwable rethrown here. */
    private fun <T> onThread(ms: Long = 5_000L, block: () -> T): T {
        if (android.os.Looper.myLooper() == thread.looper) return block()
        val done = CountDownLatch(1)
        var out: Result<T>? = null
        handler.post { out = runCatching(block); done.countDown() }
        if (!done.await(ms, TimeUnit.MILLISECONDS)) throw IllegalStateException("the engine's thread did not answer in $ms ms")
        return out!!.getOrThrow()
    }

    private fun engine(): Engine {
        engine?.let { return it }
        engineFailed?.let { throw IllegalStateException(it) }
        return try { load().also { engine = it } } catch (e: Throwable) {
            val why = cause(e); engineFailed = why
            Log.w(TAG, "face: Xiaomi's engine could not be loaded — $why")
            throw IllegalStateException(why)
        }
    }

    private fun load(): Engine {
        val t0 = SystemClock.uptimeMillis()
        val at = Class.forName("android.app.ActivityThread")
        val thread = runCatching { at.getMethod("currentActivityThread").invoke(null) }.getOrNull() ?: at.getMethod("systemMain").invoke(null)
        val sys = (runCatching { at.getMethod("getSystemContext").invoke(thread) as Context }.getOrNull() ?: app)
            ?: error("no system context")
        var pkg = sys.createPackageContext(PKG, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        var shell = sys.createPackageContext("com.android.shell", 0)
        val rear = (sys.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(REAR_DISPLAY_ID)
        if (rear != null) { pkg = pkg.createDisplayContext(rear); shell = shell.createDisplayContext(rear) }
        runCatching { asShell(shell, thread) }.onFailure { Log.w(TAG, "face: the shell's context: ${cause(it)}") }
        runCatching { android.graphics.Typeface::class.java.getMethod("loadPreinstalledSystemFontMap").invoke(null) }
        runCatching { settingsReadOnly() }.onFailure { Log.w(TAG, "face: settings not handed to the engine — ${cause(it)}") }
        File(DIR).mkdirs()
        val ctx = SafeContext(pkg, shell, File(DIR, "ctx"))
        val cl = pkg.classLoader
        fun c(n: String) = cl.loadClass(n)
        val edit = runCatching {
            val p = c("com.miui.maml.widget.edit.ParseEditMamlConfig").getMethod("parseVarXml", java.io.InputStream::class.java)
            val w = c("com.miui.maml.widget.edit.WidgetEditSave")
            val one = c("com.miui.maml.widget.edit.OneConfig")
            Triple(p, w.getMethod("BaseConfigToOneConfig", List::class.java), w.getMethod("restoreMamlView", one, c("com.miui.maml.component.MamlView")))
        }.onFailure { Log.w(TAG, "face: Xiaomi's edit code not found — faces keep their defaults: ${cause(it)}") }.getOrNull()
        val gson = runCatching { c("com.miui.maml.widget.edit.MamlutilKt").getMethod("getGSON_DEFAULT").invoke(null) }.getOrNull()
        val e = Engine(
            ctx, c("com.miui.maml.util.ZipResourceLoader"), c("com.miui.maml.ResourceLoader"), c("com.miui.maml.ResourceManager"),
            c("com.miui.maml.ScreenContext"), c("com.miui.maml.ScreenElementRoot"), c("com.miui.maml.component.MamlView"),
            c("com.miui.maml.RendererController\$IRenderable"),
            edit?.first, edit?.second, edit?.third, gson, runCatching { c("com.miui.maml.widget.edit.OneConfig") }.getOrNull(),
        )
        Log.i(TAG, "face: Xiaomi's engine loaded in ${SystemClock.uptimeMillis() - t0} ms")
        return e
    }

    /** A face loaded and drawing (or ready to). All its calls go through the engine's thread. */
    inner class Face internal constructor(val key: String, private val root: Any, private val view: View) {
        @Volatile private var surface: Surface? = null
        @Volatile private var frames = 0
        @Volatile var lastFrameAt = 0L
            private set
        @Volatile private var closed = false
        private var minuteFrames = 0
        private var minuteSince = SystemClock.uptimeMillis()
        /** The first frame drawn (SPEC 4.7: the crash marker goes then). */
        @Volatile var drawn = false
            private set

        private val draw = Runnable { drawNow() }
        private val renderable: Any = Proxy.newProxyInstance(engine!!.cRenderable.classLoader, arrayOf(engine!!.cRenderable)) { proxy, m, a ->
            when (m.name) {
                "doRender" -> { if (!closed) { handler.removeCallbacks(draw); handler.post(draw) }; null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === a?.getOrNull(0)
                "toString" -> "chicken-rear face $key"
                else -> null
            }
        }

        init { onThread { engine!!.cRoot.getMethod("setRenderControllerRenderable", engine!!.cRenderable).invoke(root, renderable) } }

        /** Draws into [s] from now on (null: nowhere). */
        fun drawTo(s: Surface?) { surface = s; if (s != null) handler.post(draw) }

        private fun drawNow() {
            val s = surface ?: return
            if (closed || !s.isValid) return
            try {
                val c = ShellLayer.lockPanelCanvas(s)
                try {
                    c.drawColor(Color.BLACK, android.graphics.PorterDuff.Mode.SRC)
                    view.draw(c)
                } finally { s.unlockCanvasAndPost(c) }
                frames++; minuteFrames++; lastFrameAt = SystemClock.uptimeMillis()
                if (!drawn) { drawn = true; runCatching { File(CRASHING).delete() } }
            } catch (e: Throwable) { Log.w(TAG, "face: $key not drawn — ${cause(e)}") }
            val now = SystemClock.uptimeMillis()
            if (now - minuteSince >= 60_000L) { Log.i(TAG, "face: $key drew $minuteFrames frames in the last minute"); minuteFrames = 0; minuteSince = now }
        }

        /** One picture of the face now, for the faithfulness check (Faithful) and the debug render. */
        fun snapshot(w: Int, h: Int): Bitmap = onThread {
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bm); c.drawColor(Color.BLACK)
            view.draw(c); bm
        }

        fun resume() = call("onResume") { command(MamlFeed.RESUME) }
        fun pause() = call("onPause") { command(MamlFeed.PAUSE) }

        /** The minute changed: the face's clock ticks and it is asked to draw. */
        fun minute() = safe {
            engine!!.cRoot.getMethod("tick", Long::class.javaPrimitiveType).invoke(root, SystemClock.elapsedRealtime())
            engine!!.cRoot.getMethod("requestUpdate").invoke(root)
            handler.post(draw)
        }

        fun command(c: String) = safe { engine!!.cView.getMethod("sendCommand", String::class.java).invoke(view, c) }
        fun number(name: String, v: Double) = safe { engine!!.cView.getMethod("putVariableNumber", String::class.java, Double::class.javaPrimitiveType).invoke(view, name, v) }

        /** A tap of his, given to the face (Charlie's next pet animation, as on Xiaomi). */
        fun touch(action: Int, x: Float, y: Float) = safe {
            val e = android.view.MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, y, 0)
            try { view.dispatchTouchEvent(e) } finally { e.recycle() }
        }

        fun close() {
            closed = true
            surface = null
            safe {
                runCatching { engine!!.cView.getMethod("onPause").invoke(view) }
                runCatching { engine!!.cRoot.getMethod("finish").invoke(root) }
                runCatching { engine!!.cView.getMethod("onDestroy").invoke(view) }
            }
            Log.i(TAG, "face: $key let go after $frames frames")
        }

        private fun call(method: String, after: () -> Unit) = safe { engine!!.cView.getMethod(method).invoke(view); after() }
        private fun safe(block: () -> Unit) {
            runCatching { onThread { block() } }.onFailure { Log.w(TAG, "face: $key — ${cause(it)}") }
        }
    }

    /**
     * Loads the face at [content] (a `.mrc`) with his [editConfig] (else the face's own defaults), laid out at [w] x
     * [h]. Throws with why when it cannot be had; the crash marker names [key] meanwhile.
     */
    fun open(key: String, content: String, editConfig: String?, w: Int, h: Int, night: Boolean, battery: Int): Face {
        val e = engine()
        runCatching { File(DIR).mkdirs(); File(CRASHING).writeText(key) }
        return try {
            onThread(10_000L) {
                val t0 = SystemClock.uptimeMillis()
                val loader = e.cZip.getConstructor(String::class.java, String::class.java).newInstance(content, "")
                val rm = e.cRm.getConstructor(e.cLoader).newInstance(loader)
                val sc = e.cSc.getConstructor(Context::class.java, e.cRm, Int::class.javaPrimitiveType).newInstance(e.ctx, rm, 0)
                val root = e.cRoot.getConstructor(e.cSc).newInstance(sc)
                runCatching { e.cRoot.getMethod("setResizeRadio", Float::class.javaPrimitiveType).invoke(root, 0f) }
                if (e.cRoot.getMethod("load").invoke(root) != true) error("its root did not load")
                val view = e.cView.getConstructor(Context::class.java, e.cRoot).newInstance(e.ctx, root) as View
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, w, h)
                applyEdit(e, view, content, editConfig)
                MamlFeed.batteryLevel(battery)?.let { v -> runCatching { e.cView.getMethod("putVariableNumber", String::class.java, Double::class.javaPrimitiveType).invoke(view, MamlFeed.BATTERY_LEVEL, v) } }
                runCatching { e.cView.getMethod("putVariableNumber", String::class.java, Double::class.javaPrimitiveType).invoke(view, MamlFeed.DARK_MODE, MamlFeed.darkMode(night)) }
                runCatching { e.cView.getMethod("putVariableNumber", String::class.java, Double::class.javaPrimitiveType).invoke(view, MamlFeed.SELECTED, 1.0) }
                Log.i(TAG, "face: $key loaded in ${SystemClock.uptimeMillis() - t0} ms")
                Face(key, root, view)
            }
        } catch (t: Throwable) {
            runCatching { File(CRASHING).delete() }
            throw IllegalStateException(cause(t))
        }
    }

    /** His edit, else the face's defaults, by Xiaomi's own code (`E2.d` then `WidgetEditSave.restoreMamlView`). */
    private fun applyEdit(e: Engine, view: View, content: String, editConfig: String?) {
        val restore = e.restore ?: return
        val one: Any? = runCatching {
            if (editConfig != null && File(editConfig).isFile && e.gson != null && e.cOne != null) {
                // Gson's fromJson(String, Class): found by its shape, the class being obfuscated.
                val text = File(editConfig).readText()
                val from = e.gson.javaClass.methods.firstOrNull { m -> m.parameterTypes.contentEquals(arrayOf(String::class.java, Class::class.java)) && m.returnType == Any::class.java }
                from?.invoke(e.gson, text, e.cOne)
            } else java.util.zip.ZipFile(content).use { z ->
                val entry = z.getEntry("var_config.xml") ?: return@use null
                val list = z.getInputStream(entry).use { e.parseVar?.invoke(null, it) } ?: return@use null
                e.toOne?.invoke(null, list)
            }
        }.onFailure { Log.w(TAG, "face: its settings not read — ${cause(it)}") }.getOrNull()
        if (one != null) runCatching { restore.invoke(null, one, view) }.onFailure { Log.w(TAG, "face: its settings not applied — ${cause(it)}") }
    }

    /** The settings provider for MAML, read-only (SettingsShim), in Settings' own holders. */
    private fun settingsReadOnly() {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val ext = am.javaClass.methods.first { it.name == "getContentProviderExternal" }
        val holder = ext.invoke(am, "settings", 0, android.os.Binder(), "chicken-rear-face") ?: error("no settings provider")
        val provider = holder.javaClass.getField("provider").get(holder) ?: error("no settings provider")
        val icp = Class.forName("android.content.IContentProvider")
        val refused = HashSet<String>()
        val readOnly = Proxy.newProxyInstance(icp.classLoader, arrayOf(icp)) { _, m, a ->
            val callMethod = if (m.name == "call") SettingsShim.callMethodOf(a) else null
            if (!SettingsShim.allows(m.name, callMethod)) {
                val what = callMethod ?: m.name
                if (refused.add(what)) Log.w(TAG, "face: the engine asked the settings for $what — refused (read-only)")
                return@newProxyInstance when (m.returnType) {
                    Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0; Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
            try { m.invoke(provider, *(a ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        }
        val holderClass = Class.forName("android.provider.Settings\$ContentProviderHolder")
        val cp = holderClass.getDeclaredField("mContentProvider").apply { isAccessible = true }
        var n = 0
        for (t in Class.forName("android.provider.Settings").declaredClasses) for (f in t.declaredFields) {
            if (f.type != holderClass || !java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            val h = f.get(null) ?: continue
            synchronized(h) { cp.set(h, readOnly) }; n++
        }
        Log.i(TAG, "face: the settings handed to the engine read-only ($n holders)")
    }

    /** A context made from the system context keeps the op package "android", which AMS refuses for uid 2000. */
    private fun asShell(impl: Context, thread: Any?) {
        val ci = Class.forName("android.app.ContextImpl")
        if (!ci.isInstance(impl)) return
        val source = android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build()
        ci.getDeclaredField("mAttributionSource").apply { isAccessible = true }.set(impl, source)
        runCatching { ci.getDeclaredField("mBasePackageName").apply { isAccessible = true }.set(impl, "com.android.shell") }
        val acr = Class.forName("android.app.ContextImpl\$ApplicationContentResolver")
        val k = acr.getDeclaredConstructor(Context::class.java, Class.forName("android.app.ActivityThread")).apply { isAccessible = true }
        ci.getDeclaredField("mContentResolver").apply { isAccessible = true }.set(impl, k.newInstance(impl, thread))
    }

    /** Keeps MAML away from subscreencenter's data and from calls a shell process cannot make (the probe's, kept). */
    private class SafeContext(base: Context, private val shell: Context, private val dir: File) : ContextWrapper(base) {
        private val said = HashSet<String>()
        private fun once(what: String) { if (synchronized(said) { said.add(what) }) Log.i(TAG, "face: the engine wanted $what — swallowed") }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = sub("files")
        override fun getCacheDir(): File = sub("cache")
        override fun getDataDir(): File = dir
        override fun getNoBackupFilesDir(): File = sub("nobackup")
        override fun getCodeCacheDir(): File = sub("codecache")
        override fun getExternalFilesDir(type: String?): File = sub("ext")
        override fun getExternalCacheDir(): File = sub("extcache")
        override fun getDir(name: String?, mode: Int): File = sub("dir_$name")
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            Context::class.java.getMethod("getSharedPreferences", File::class.java, Int::class.javaPrimitiveType)
                .invoke(baseContext, File(sub("prefs"), "$name.xml"), mode) as SharedPreferences
        override fun getContentResolver(): ContentResolver = shell.contentResolver
        override fun getSystemService(name: String): Any? = if (name == LAYOUT_INFLATER_SERVICE) super.getSystemService(name) else shell.getSystemService(name)
        override fun getOpPackageName(): String = "com.android.shell"
        override fun registerReceiver(r: BroadcastReceiver?, f: IntentFilter?): Intent? { once("a receiver for ${f?.takeIf { it.countActions() > 0 }?.getAction(0)}"); return null }
        override fun registerReceiver(r: BroadcastReceiver?, f: IntentFilter?, flags: Int): Intent? = registerReceiver(r, f)
        override fun registerReceiver(r: BroadcastReceiver?, f: IntentFilter?, p: String?, h: Handler?): Intent? = registerReceiver(r, f)
        override fun registerReceiver(r: BroadcastReceiver?, f: IntentFilter?, p: String?, h: Handler?, flags: Int): Intent? = registerReceiver(r, f)
        override fun unregisterReceiver(r: BroadcastReceiver?) {}
        override fun sendBroadcast(i: Intent?) { once("to send ${i?.action}") }
        override fun startActivity(i: Intent?) { once("to start ${i?.component ?: i?.action}") }
        override fun startService(i: Intent?): ComponentName? { once("to start a service ${i?.component ?: i?.action}"); return null }
        override fun bindService(i: Intent, c: ServiceConnection, f: Int): Boolean { once("to bind ${i.component ?: i.action}"); return false }
        private fun sub(n: String) = File(dir, n).apply { mkdirs() }
    }

    fun close() { runCatching { thread.quitSafely() } }

    companion object {
        private const val TAG = "AiUsageRear"
        const val PKG = "com.xiaomi.subscreencenter"
        const val REAR_DISPLAY_ID = 1
        /** The engine's own files (never Xiaomi's folders). */
        const val DIR = "/data/local/tmp/chicken-rear-faces"
        /** Names the face being loaded until its first good frame: a native crash leaves it for the next process. */
        const val CRASHING = "$DIR/crashing"

        fun cause(e: Throwable): String {
            var t = e
            while (t is InvocationTargetException && t.targetException != null) t = t.targetException
            return "${t.javaClass.simpleName}: ${t.message}"
        }

        /** The face a crash took down last time, if any (and the marker taken away). */
        fun crashedLastTime(): String? = runCatching { File(CRASHING).takeIf { it.isFile }?.let { f -> f.readText().trim().also { f.delete() } } }.getOrNull()?.ifEmpty { null }
    }
}
