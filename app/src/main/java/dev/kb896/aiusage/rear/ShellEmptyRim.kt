package dev.kb896.aiusage.rear

import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * THE RIM LIGHT WITH NOTHING WAITING ([EmptyRim]), drawn on the back: ONE see-through layer over every page of ours
 * ([ShellLayer.Z_EMPTY_RIM], the notifications' own place: the two are never up together), under the dim by hand and
 * the display's shape, with the display's corners. OpenGL ES 3 on a thread of its own that lives only while it
 * plays: every frame drawn (the swap waits for the display), and when it is over the layer, the surface and the
 * context are gone. Nothing is drawn after it ends.
 *
 * [begin] as the finger starts the pull, [pull] as it goes, [release] at the lift, [stop] to end it at once (the back
 * dark, a call). [onEnded] once it has gone by itself or been stopped, on its thread.
 */
internal class ShellEmptyRim(
    private val layerStack: () -> Int,
    private val geometry: () -> RearGeometry,
    private val onEnded: () -> Unit,
) {
    private val lock = Any()
    private var play: EmptyRim.Play? = null
    private var colours = EmptyRim.WHITE
    private var thread: Thread? = null
    @Volatile private var stopping = false

    /** Playing: from [begin] until it has gone. */
    val isPlaying: Boolean get() = synchronized(lock) { play != null }

    /**
     * The finger has landed and pulls from [xPanel] (panel units) on the top edge: the pool starts there. Again while it
     * plays: it starts again from there (a second pull). [xiaomi]: Xiaomi's rear-glow colours, else white.
     */
    fun begin(xPanel: Float, xiaomi: Boolean, nowMs: Long = android.os.SystemClock.uptimeMillis()) {
        val rear = geometry()
        synchronized(lock) {
            play = EmptyRim.Play(EmptyRim.Path(rear.widthPx.toDouble(), rear.heightPx.toDouble(), rear.cornerPx.toDouble(), (xPanel * rear.fitX).toDouble()), nowMs.toDouble())
            colours = EmptyRim.colours(xiaomi)
            stopping = false
            if (thread?.isAlive != true) thread = Thread({ run() }, "empty-rim").apply { isDaemon = true; start() }
        }
    }

    /** The finger has pulled [dy] panel units down. */
    fun pull(dy: Float) = synchronized(lock) { play?.pull(EmptyRim.pullOf(dy)) }

    /** The light that plays is over at [nowMs] (uptime), or none plays: a new pull may play it again (EmptyPull). */
    fun lightOver(nowMs: Long): Boolean = synchronized(lock) { play?.lightOver(nowMs.toDouble()) ?: true }

    /** The lift at [nowMs] (uptime): the heads leave the pool. */
    fun release(nowMs: Long) = synchronized(lock) { play?.release(nowMs.toDouble()) }

    /**
     * A debug build's play with no finger and no notification touched: the draft's own pull by the clock from [EmptyRim.DEMO_X],
     * then the lift at its 200 ms.
     */
    fun demo(xiaomi: Boolean) {
        val rear = geometry()
        begin((EmptyRim.DEMO_X / rear.fitX).toFloat(), xiaomi)
        val at = android.os.SystemClock.uptimeMillis()
        // Only this play's: stopped, or another begun meanwhile, the demo's clock touches nothing.
        val mine = synchronized(lock) { play } ?: return
        Thread({
            try {
                while (true) {
                    val t = android.os.SystemClock.uptimeMillis() - at
                    if (t >= EmptyRim.PULL_S * 1000) break
                    synchronized(lock) { if (play !== mine) return@Thread; mine.pull(EmptyRim.easeOutCubic(t / (EmptyRim.PULL_S * 1000))) }
                    Thread.sleep(8)
                }
                synchronized(lock) { if (play === mine) { mine.pull(1.0); mine.release((at + (EmptyRim.PULL_S * 1000).toLong()).toDouble()) } }
            } catch (_: InterruptedException) {}
        }, "empty-rim-demo").apply { isDaemon = true; start() }
    }

    /** Gone at once, whatever it was doing. */
    fun stop() {
        synchronized(lock) {
            if (play == null) return
            stopping = true
            play = null
        }
    }

    private fun run() {
        var sc: SurfaceControl? = null
        var surface: Surface? = null
        var host: PinGl.Host? = null
        var frames = 0
        var worst = 0.0
        var gpuMax = 0.0
        var gpuSum = 0.0
        var gpuN = 0
        val started = System.nanoTime()
        var firstAt = 0.0
        try {
            val rear = geometry()
            sc = ShellLayer.create(
                "empty-rim", layerStack(), ShellLayer.Z_EMPTY_RIM, rear.widthPx, rear.heightPx,
                trusted = true, visible = false, alpha = 1f, format = PixelFormat.RGBA_8888, opaque = false,
                corner = ShellLayer.pageCorner(rear),
            )
            surface = Surface(sc)
            host = PinGl.Host(surface)
            check(host.make()) { "no OpenGL on its layer" }
            val program = program()
            // Where each uniform is, and the arrays its numbers are written into: found and made once, nothing made per frame.
            val names = EmptyRim.UNIFORM_NAMES
            val locs = IntArray(names.size) { GLES20.glGetUniformLocation(program, names[it]) }
            val pass = names.indexOf("uPass")
            val values = EmptyRim.uniformArrays()
            val done = IntArray(1)
            val ns = IntArray(1)
            val tri = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)).also { it.position(0) }
            GLES20.glUseProgram(program)
            val pos = GLES20.glGetAttribLocation(program, "aPos")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, tri)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glViewport(0, 0, rear.widthPx, rear.heightPx)
            // The GPU's own time for each picture, where it can say (Adreno can): logged at the end.
            val timed = GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains("GL_EXT_disjoint_timer_query") == true
            val queries = IntArray(2)
            if (timed) GLES30.glGenQueries(2, queries, 0)
            var lastAt = 0.0
            while (true) {
                val nowMs = System.nanoTime() / 1e6
                // System.nanoTime is the uptime's own clock (CLOCK_MONOTONIC), in finer steps: the lift's time is the touch's uptime.
                val drawn = synchronized(lock) {
                    val p = play
                    val f = p?.at(nowMs)
                    if (p != null && f == null) play = null
                    if (p == null || f == null) false else { EmptyRim.fill(p.path, f, colours, values); true }
                }
                if (!drawn) break
                if (lastAt > 0) worst = maxOf(worst, nowMs - lastAt)
                lastAt = nowMs
                val q = queries[frames % 2]
                if (timed && frames >= 2) {
                    GLES30.glGetQueryObjectuiv(q, GLES30.GL_QUERY_RESULT_AVAILABLE, done, 0)
                    if (done[0] != 0) {
                        GLES30.glGetQueryObjectuiv(q, GLES30.GL_QUERY_RESULT, ns, 0)
                        val ms = (ns[0].toLong() and 0xFFFFFFFFL) / 1e6
                        gpuMax = maxOf(gpuMax, ms); gpuSum += ms; gpuN++
                    }
                }
                if (timed) GLES30.glBeginQuery(TIME_ELAPSED, q)
                for (i in names.indices) {
                    val loc = locs[i]
                    if (loc < 0) continue
                    val v = values[i]
                    when {
                        i == pass -> GLES20.glUniform1fv(loc, v.size, v, 0)
                        v.size == 1 -> GLES20.glUniform1f(loc, v[0])
                        v.size == 2 -> GLES20.glUniform2f(loc, v[0], v[1])
                        v.size == 3 -> GLES20.glUniform3f(loc, v[0], v[1], v[2])
                        else -> GLES20.glUniform4f(loc, v[0], v[1], v[2], v[3])
                    }
                }
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
                if (timed) GLES30.glEndQuery(TIME_ELAPSED)
                host.swap()
                frames++
                if (frames == 1) {
                    firstAt = (System.nanoTime() - started) / 1e6
                    SurfaceControl.Transaction().use { it.setVisibility(sc, true).apply() }
                }
            }
        } catch (e: Throwable) {
            // No OpenGL, a shader the GPU refused, a lost surface: this play is over. Left standing, it would keep the shade
            // up for good (no pop-up, no touch on the back) and the finally below would start the same failing thread again.
            Log.w(TAG, "empty rim: stopped: ${e.message}")
            synchronized(lock) { play = null }
        } finally {
            runCatching { host?.release() }
            runCatching { surface?.release() }
            ShellLayer.remove(sc)
            val stopped = synchronized(lock) {
                if (thread === Thread.currentThread()) thread = null
                // A begin that came while this one was ending: its own thread.
                if (play != null && thread == null) thread = Thread({ run() }, "empty-rim").apply { isDaemon = true; start() }
                stopping.also { stopping = false }
            }
            Log.i(TAG, "empty rim: ${if (stopped) "stopped" else "over"}; $frames pictures, the first ${"%.0f".format(firstAt)} ms after the pull began, " +
                "${"%.1f".format(worst)} ms at most between two" + if (gpuN > 0) ", the GPU ${"%.3f".format(gpuSum / gpuN)} ms a picture (${"%.3f".format(gpuMax)} at most)" else "")
            if (synchronized(lock) { play == null }) runCatching { onEnded() }
        }
    }

    private fun program(): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, EmptyRim.VERTEX))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, EmptyRim.FRAGMENT))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private companion object {
        const val TAG = "AiUsageRear"
        /** GL_TIME_ELAPSED_EXT. */
        const val TIME_ELAPSED = 0x88BF
    }
}
