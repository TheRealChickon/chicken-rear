package dev.kb896.aiusage.rear.dots

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SharedMemory
import android.util.Log
import com.openai.orbit.characters.NativeBridge
import dev.kb896.aiusage.IDots
import dev.kb896.aiusage.IDotsSink
import java.io.File
import java.nio.ByteBuffer

class DotsService : Service() {
    private val thread = HandlerThread("dots-gl").apply { start() }
    private val gl = Handler(thread.looper)
    private val sinks = LinkedHashMap<IBinder, IDotsSink>()
    @Volatile private var why = ""

    private var loaded = false
    private var bundle = 0L
    private var handle = 0L
    private var reader: ImageReader? = null
    private var memory: SharedMemory? = null
    private var mapped: ByteBuffer? = null
    private var side = 0
    private var shownCharacter: String? = null
    private var seq = 0L
    private var running = false
    private var failed = false

    private val binder = object : IDots.Stub() {
        override fun watch(sink: IDotsSink?, character: String?, size: Int) {
            if (sink == null || character.isNullOrEmpty()) return
            gl.post { add(sink, character, size.coerceIn(64, 512)) }
        }
        override fun stop(sink: IDotsSink?) { if (sink != null) gl.post { remove(sink.asBinder()) } }
        override fun signature() { gl.post { if (handle != 0L) runCatching { NativeBridge.playSignature(handle) } } }
        override fun calm(on: Boolean) { gl.post { if (handle != 0L) runCatching { NativeBridge.setReducedMotion(handle, on) } } }
        override fun state(): String = why
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        gl.post { close(); thread.quitSafely() }
        super.onDestroy()
    }

    private fun add(sink: IDotsSink, character: String, size: Int) {
        val b = sink.asBinder()
        if (b !in sinks) {
            runCatching { b.linkToDeath({ gl.post { remove(b) } }, 0) }.onFailure { return }
            sinks[b] = sink
        }
        gl.removeCallbacks(closeIdle)
        val err = open(character, size)
        if (err != null) {
            why = err
            Log.w(TAG, "dots: no character — $err")
            tellGone(err)
            return
        }
        why = ""
        memory?.let { m -> runCatching { sink.frames(Bundle().apply { putParcelable("memory", m); putInt("size", side); putInt("slots", SLOTS) }) } }
        if (!running) {
            running = true
            runCatching { NativeBridge.setActive(handle, true) }
            gl.post(frameTick)
        }
    }

    private fun remove(b: IBinder) {
        sinks.remove(b) ?: return
        if (sinks.isNotEmpty()) return
        running = false
        gl.removeCallbacks(frameTick)
        if (handle != 0L) runCatching { NativeBridge.setActive(handle, false) }
        gl.postDelayed(closeIdle, IDLE_CLOSE_MS)
    }

    private fun tellGone(err: String) {
        for (s in sinks.values.toList()) runCatching { s.gone(err) }
        sinks.clear()
    }

    /** The renderer ready for [character] at [size]; null, or what stood in the way. */
    private fun open(character: String, size: Int): String? {
        if (failed) return why.ifEmpty { "the renderer failed before" }
        if (handle != 0L && side == size) {
            if (shownCharacter != character) appearance(character)?.let { return it }
            return null
        }
        if (handle != 0L) close()
        val found = DotsSource.onPhone(this)
        if (found !is DotsSource.Found.Ready) return (found as DotsSource.Found.Missing).why
        if (DotsLink.struckOut(this, found.versionCode)) return "OpenAI's renderer stopped this app's character process before, on this ChatGPT version"
        if (!loaded) {
            val t0 = System.nanoTime()
            try {
                System.load(found.lib)
            } catch (e: Throwable) {
                failed = true
                return "its renderer cannot be loaded here (${e.javaClass.simpleName}: ${e.message})"
            }
            val compat = runCatching { NativeBridge.resourceCompatibilityVersion() }.getOrElse { failed = true; return "its renderer does not answer (${it.message})" }
            if (compat != DotsSource.RESOURCES) { failed = true; return "its renderer is of another version ($compat)" }
            loaded = true
            Log.i(TAG, "dots: OpenAI's renderer ${runCatching { NativeBridge.version() }.getOrNull()} loaded from ChatGPT ${found.versionCode}${if (found.known) "" else " (a build not read before)"} in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        if (bundle == 0L) {
            val assets = runCatching { createPackageContext(DotsSource.PACKAGE, 0).assets }.getOrElse { return "ChatGPT's assets cannot be read (${it.message})" }
            val t0 = System.nanoTime()
            bundle = runCatching { NativeBridge.prepareResources(assets) }.getOrDefault(0L)
            if (bundle == 0L) return "its characters' resources could not be prepared"
            Log.i(TAG, "dots: resources prepared in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        val r = ImageReader.newInstance(size, size, PixelFormat.RGBA_8888, 3, HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        r.setOnImageAvailableListener({ copy(it) }, gl)
        val cache = File(cacheDir, "orbit-gles-v1").apply { mkdirs() }
        val h = runCatching { NativeBridge.create(r.surface, size, size, bundle, cache.path.toByteArray(Charsets.UTF_8), android.os.Build.FINGERPRINT) }.getOrDefault(0L)
        if (h == 0L) { r.close(); return "its renderer would not start" }
        handle = h; reader = r; side = size
        val compiled = File(noBackupFilesDir, "OrbitCharacters/compiled-v1").apply { mkdirs() }
        runCatching {
            NativeBridge.setCacheDirectory(h, compiled.absolutePath)
            NativeBridge.setDisplayScale(h, resources.displayMetrics.density)
            NativeBridge.setReducedMotion(h, false)
        }
        appearance(character)?.let { close(); return it }
        val len = size * size * 4
        memory = SharedMemory.create("dots", len * SLOTS).also { mapped = it.mapReadWrite() }
        return null
    }

    private fun appearance(character: String): String? {
        val state = runCatching { NativeBridge.presetAppearance(character) }.getOrNull()
        if (state == null || state.isEmpty()) return "ChatGPT has no character \"$character\""
        val said = runCatching { NativeBridge.restoreAppearanceState(handle, state) }.getOrElse { return "its character could not be set (${it.message})" }
        if (!said.isNullOrEmpty()) Log.i(TAG, "dots: setting \"$character\" said: $said")
        shownCharacter = character
        Log.i(TAG, "dots: \"$character\"")
        return null
    }

    private val frameTick = object : Runnable {
        override fun run() {
            if (!running || handle == 0L) return
            val ok = runCatching { NativeBridge.render(handle, System.nanoTime() / 1e9) }.getOrDefault(false)
            if (!ok) {
                val err = runCatching { NativeBridge.lastError(handle) }.getOrNull().orEmpty()
                if (err.isNotEmpty()) {
                    Log.w(TAG, "dots: the renderer stopped: $err")
                    why = err
                    tellGone(err)
                    running = false
                    close()
                    return
                }
            }
            gl.postDelayed(this, FRAME_MS)
        }
    }

    private fun copy(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        try {
            val out = mapped ?: return
            val plane = image.planes[0]
            val src = plane.buffer
            val row = side * 4
            val slot = (seq % SLOTS).toInt()
            val base = slot * side * row
            for (y in 0 until side) {
                val from = y * plane.rowStride
                if (from + row > src.capacity()) break
                src.limit(from + row); src.position(from)
                out.position(base + y * row)
                out.put(src)
                src.limit(src.capacity())
            }
            val n = seq++
            for (s in sinks.values.toList()) runCatching { s.frame(slot, n) }
        } finally {
            image.close()
        }
    }

    private val closeIdle = Runnable { close() }

    /** Everything let go (nothing is watching, or it failed). */
    private fun close() {
        running = false
        gl.removeCallbacks(frameTick)
        if (handle != 0L) runCatching { NativeBridge.destroy(handle) }
        handle = 0L; shownCharacter = null
        reader?.close(); reader = null
        mapped?.let { runCatching { SharedMemory.unmap(it) } }; mapped = null
        memory?.close(); memory = null
        if (bundle != 0L) runCatching { NativeBridge.releaseResources(bundle) }
        bundle = 0L
    }

    companion object {
        private const val TAG = "AiUsageDots"
        /** 30 pictures a second at most, as the ChatGPT app draws them. */
        const val FRAME_MS = 33L
        const val SLOTS = 3
        const val IDLE_CLOSE_MS = 30_000L
    }
}
