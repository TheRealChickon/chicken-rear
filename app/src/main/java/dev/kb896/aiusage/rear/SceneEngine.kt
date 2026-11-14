package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Plays the scene onto a Surface: a wake clip once, then a loop under the clock. One engine for
 * both places it is seen: the shell's layer on the back (ShellScene) and the settings' preview on
 * the front (ui/home/RearPreview), so the preview is the thing itself.
 *
 * Every frame is composed in one OpenGL pass:
 *  - the clip, decoded by MediaCodec into a SurfaceTexture;
 *  - NEAR-BLACK SWITCHED OFF here, whatever the file or the decoder did: a pixel whose brightest
 *    channel is under [CRUSH_LO] comes out exactly 0, so an OLED pixel there is off, and no clip
 *    can lift the black to grey;
 *  - THE CLOCK IN FRONT AND BEHIND, as the stock face's fish swims past its clock. A clip that
 *    carries a matte (the same picture stacked twice in the file: the colours on top, under
 *    them a grey picture that is white where the subject is) has the hours drawn BEHIND its
 *    subject (cut away wherever the matte says the character stands) and the minutes and date
 *    over everything. A clip with no matte has all of the clock over it;
 *  - the face is not drawn while the wake clip plays, and fades in with the loop;
 *  - [dim] for a back left lying face up, and the show's fade.
 *
 * FRAMES ON TIME. Each frame is given the time it is due (eglPresentationTimeANDROID), so
 * SurfaceFlinger puts it on the vsync nearest its own clock and the clip keeps the frame rate
 * it was made at.
 *
 * One thread: [play] blocks its caller until the show is over, and makes and releases its own
 * EGL context there. [look], [dim] and [depth] may be set from any thread while it plays.
 */
class SceneEngine(
    private val output: Surface,
    private val width: Int,
    private val height: Int,
    /** The lens column's width in the output's pixels, for the face's layout. */
    private val lens: Float,
) {
    /** What decides, frame by frame, whether the show goes on. */
    interface Control {
        /** What is left of the show, 1 down to 0 as it fades; null once it is to stop, [why] having the reason. */
        fun strength(): Float?
        val why: String
        /** The first frame of the first clip is on the surface. */
        fun onFirstFrame() {}
        /** The wake clip is over and the loop begins. */
        fun onLoop() {}
    }

    @Volatile var look = SceneFace.Look()
    /** 1 for the picture as it is; less for a dimmer back. */
    @Volatile var dim = 1f
    /** The clock behind the subject, where the clip has a matte. */
    @Volatile var depth = true
    /**
     * The loop held on the frame it is at: a still picture, the clock still kept right. Nothing is
     * decoded meanwhile, so it costs next to nothing. The wake clip is never held.
     */
    @Volatile var still = false
    /** The weather where the phone is, for the moon face's clouds; null when it is not known (they stir gently then). */
    @Volatile var weather: dev.kb896.aiusage.data.Condition? = null
    /** The clip is the moon's (SceneEffects' places are that picture's): the clouds and the glitch are for it alone. */
    @Volatile var moonClip = false
    /** The clouds stirred by the weather, and the glitch: each as the settings have it. */
    @Volatile var clouds = true
    @Volatile var glitch = true
    private val effects = SceneEffects()

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var videoTex = 0
    private var faceTex = 0
    private var texture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null
    private var frameThread: HandlerThread? = null
    private val frameReady = Semaphore(0)
    private val texMatrix = FloatArray(16)
    private var faceBitmap: Bitmap? = null
    private var faceKey = ""
    private var faceOn = false
    private var clipPlace: SceneFace.Place? = null
    private var frontTex = 0
    private var frontBitmap: Bitmap? = null

    /**
     * Black on the surface now, and nothing else: the curtain a wake starts from. Makes the
     * context if there is none and leaves it made. On the engine's thread.
     */
    fun black() {
        setUp()
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(display, surface)
    }

    /**
     * [wake] once (null for none), then [loop] round and round (null: the show ends with the
     * wake clip, "played"). Blocks until it is over; says why it ended. Releases everything.
     */
    fun play(wake: String?, loop: String?, control: Control): String {
        try {
            setUp()
            var first = true
            if (wake != null) {
                val end = playFile(wake, repeat = false, withFace = false, control) { if (first) { first = false; control.onFirstFrame() } }
                if (end != null) return end
            }
            if (loop == null) return "played"
            control.onLoop()
            return playFile(loop, repeat = true, withFace = true, control) { if (first) { first = false; control.onFirstFrame() } } ?: "played"
        } finally {
            release()
        }
    }

    private fun playFile(path: String, repeat: Boolean, withFace: Boolean, control: Control, shown: () -> Unit): String? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return "no video in ${File(path).name}"
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val hasMatte = hasMatte(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
            // Where this clip wants its clock (its side file), unless the look already says.
            clipPlace = if (withFace) SceneFace.Place.of(path) else null
            val made = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = made
            frameReady.drainPermits()
            made.configure(format, decoderSurface, null, 0)
            made.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var ptsOffsetUs = 0L
            var lastPtsUs = 0L
            var frameUs = 41_708L
            var startNs = 0L
            var faceUpAt = 0L
            var haveFrame = false
            var lastDrawnStrength = -1f
            val cal = java.util.Calendar.getInstance()

            while (true) {
                val left = control.strength() ?: return control.why.ifEmpty { "dismissed" }

                if (still && repeat && haveFrame) {
                    // Held: the same frame, drawn again only for the clock, the dim or the fade.
                    cal.timeInMillis = System.currentTimeMillis()
                    val before = faceKey
                    if (withFace) face(look, cal)
                    val gain = left * dim
                    if (faceKey != before || gain != lastDrawnStrength) {
                        draw(left, if (withFace) 1f else 0f, hasMatte, System.nanoTime())
                        lastDrawnStrength = gain
                    }
                    startNs = 0L                    // moving again starts its clock again
                    Thread.sleep(STILL_STEP_MS)
                    continue
                }

                if (!inputDone) {
                    val i = made.dequeueInputBuffer(0)
                    if (i >= 0) {
                        val buf = made.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            if (repeat) {
                                // Round again: back to the first frame, later in the codec's time.
                                ptsOffsetUs = lastPtsUs + frameUs
                                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                made.queueInputBuffer(i, 0, 0, 0, 0)
                            } else {
                                made.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            }
                        } else {
                            val pts = extractor.sampleTime + ptsOffsetUs
                            if (pts > lastPtsUs) frameUs = (pts - lastPtsUs).coerceIn(8_000L, 100_000L)
                            lastPtsUs = maxOf(lastPtsUs, pts)
                            made.queueInputBuffer(i, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val o = made.dequeueOutputBuffer(info, 8_000)
                var drew = false
                if (o >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        val now = System.nanoTime()
                        if (startNs == 0L) startNs = now + START_LEAD_NS - info.presentationTimeUs * 1000
                        var due = startNs + info.presentationTimeUs * 1000
                        // Fallen behind by more than three frames (a stall): the clock is moved, not the frames dropped.
                        if (now - due > frameUs * 3000) { startNs += now - due; due = now }
                        val wait = due - now - RELEASE_LEAD_NS
                        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                        made.releaseOutputBuffer(o, true)
                        if (frameReady.tryAcquire(FRAME_WAIT_MS, TimeUnit.MILLISECONDS)) {
                            frameReady.drainPermits()
                            texture?.updateTexImage()
                            texture?.getTransformMatrix(texMatrix)
                            haveFrame = true
                            val nowMs = SystemClock.uptimeMillis()
                            var faceAlpha = 0f
                            if (withFace) {
                                cal.timeInMillis = System.currentTimeMillis()
                                face(look, cal)
                                if (faceUpAt == 0L) faceUpAt = nowMs
                                faceAlpha = ((nowMs - faceUpAt).toFloat() / FACE_IN_MS).coerceIn(0f, 1f)
                            }
                            draw(left, faceAlpha, hasMatte, due)
                            lastDrawnStrength = left * dim
                            drew = true
                            shown()
                        }
                    } else {
                        made.releaseOutputBuffer(o, false)
                    }
                    if (eos) return null
                }
                // Fading with no new frame due yet: the same frame again, weaker, so the fade is smooth.
                if (!drew && haveFrame && left * dim != lastDrawnStrength) {
                    val faceAlpha = if (withFace && faceUpAt != 0L) ((SystemClock.uptimeMillis() - faceUpAt).toFloat() / FACE_IN_MS).coerceIn(0f, 1f) else 0f
                    draw(left, faceAlpha, hasMatte, System.nanoTime())
                    lastDrawnStrength = left * dim
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * The face's two textures as [asked] is at [cal], drawn again only when its words or look
     * change: what goes behind the clip's subject (the hours), and what stays over it.
     */
    private fun face(asked: SceneFace.Look, cal: java.util.Calendar) {
        val want = if (asked.place == null && clipPlace != null) asked.copy(place = clipPlace) else asked
        val key = SceneFace.key(want, cal)
        if (key == faceKey) return
        faceKey = key
        faceOn = !want.empty
        if (!faceOn) return
        fun upload(unit: Int, tex: Int, part: SceneFace.Part, held: Bitmap?): Bitmap {
            val bitmap = held ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0)
            SceneFace.draw(Canvas(bitmap), width.toFloat(), height.toFloat(), want, cal, part)
            GLES20.glActiveTexture(unit)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            return bitmap
        }
        faceBitmap = upload(GLES20.GL_TEXTURE1, faceTex, SceneFace.Part.BACK, faceBitmap)
        frontBitmap = upload(GLES20.GL_TEXTURE2, frontTex, SceneFace.Part.FRONT, frontBitmap)
    }

    private fun draw(strength: Float, faceAlpha: Float, hasMatte: Boolean, dueNs: Long) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uVideo"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, faceTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uFace"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frontTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uFront"), 2)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMatte"), if (hasMatte) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDepth"), if (hasMatte && depth) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uFaceAlpha"), if (faceOn) faceAlpha else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uGain"), strength.coerceIn(0f, 1f) * dim.coerceIn(0f, 1f))
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uCrush"), CRUSH_LO, CRUSH_HI)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexF"), 1, false, texMatrix, 0)
        val nowMs = android.os.SystemClock.uptimeMillis()
        val fx = effects.at(nowMs, weather, moonClip && !still, clouds, glitch)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uCloud"), fx[0], fx[1], fx[2])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uGlitch"), fx[3], fx[4])
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        QUAD.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, QUAD)
        GLES20.glEnableVertexAttribArray(pos)
        QUAD.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, QUAD)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, surface, dueNs)
        EGL14.eglSwapBuffers(display, surface)
    }

    private fun setUp() {
        if (context != EGL14.EGL_NO_CONTEXT) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL did not start" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no EGL config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "no EGL context" }
        surface = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "no EGL surface: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL make current failed" }
        EGL14.eglSwapInterval(display, 0)

        program = link(VERTEX, FRAGMENT)
        val tex = IntArray(3)
        GLES20.glGenTextures(3, tex, 0)
        videoTex = tex[0]; faceTex = tex[1]; frontTex = tex[2]
        for ((target, id) in listOf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES to videoTex, GLES20.GL_TEXTURE_2D to faceTex, GLES20.GL_TEXTURE_2D to frontTex)) {
            GLES20.glBindTexture(target, id)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            // One clear pixel until the face is first drawn.
            if (target == GLES20.GL_TEXTURE_2D) GLES20.glTexImage2D(target, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4))
        }
        android.opengl.Matrix.setIdentityM(texMatrix, 0)

        val thread = HandlerThread("scene-frames").apply { start() }
        frameThread = thread
        val st = SurfaceTexture(videoTex)
        st.setOnFrameAvailableListener({ frameReady.release() }, Handler(thread.looper))
        texture = st
        decoderSurface = Surface(st)
    }

    /** Lets go of the context, after a [black] with no [play] following. On the engine's thread. */
    fun close() = release()

    private fun release() {
        runCatching { decoderSurface?.release() }; decoderSurface = null
        runCatching { texture?.release() }; texture = null
        runCatching { frameThread?.quitSafely() }; frameThread = null
        faceBitmap = null; frontBitmap = null; faceKey = ""; faceOn = false
        if (display != EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            if (surface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, surface) }
            if (context != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglReleaseThread() }
        }
        display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; surface = EGL14.EGL_NO_SURFACE
        program = 0
    }

    private fun link(vertex: String, fragment: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    companion object {
        /** A clip twice as tall as the rear's shape carries its matte under its picture. */
        fun hasMatte(videoWidth: Int, videoHeight: Int): Boolean = videoHeight * 976L > videoWidth * 596L * 3 / 2

        /** At and under this (of 1), a pixel's brightest channel is switched off; whole from [CRUSH_HI]. */
        const val CRUSH_LO = 7f / 255f
        const val CRUSH_HI = 15f / 255f

        /** What the crush makes of a pixel whose brightest channel is [peak] (0..1): the shader's smoothstep. For tests. */
        fun crushGain(peak: Float): Float {
            val t = ((peak - CRUSH_LO) / (CRUSH_HI - CRUSH_LO)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private const val FACE_IN_MS = 320f
        private const val STILL_STEP_MS = 200L
        private const val FRAME_WAIT_MS = 120L
        private const val START_LEAD_NS = 30_000_000L
        private const val RELEASE_LEAD_NS = 8_000_000L

        // x, y, s, t: a strip over the whole surface; t runs up, as a SurfaceTexture's does.
        private val QUAD = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        }

        private const val VERTEX = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            uniform mat4 uTex;
            uniform float uMatte;
            varying vec2 vColor;
            varying vec2 vMatte;
            varying vec2 vFace;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                // With a matte the picture is the file's top half and the matte its bottom half.
                vec2 c = uMatte > 0.5 ? vec2(aUv.x, 0.5 + aUv.y * 0.5) : aUv;
                vec2 m = vec2(aUv.x, aUv.y * 0.5);
                vColor = (uTex * vec4(c, 0.0, 1.0)).xy;
                vMatte = (uTex * vec4(m, 0.0, 1.0)).xy;
                vFace = vec2(aUv.x, 1.0 - aUv.y);
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uVideo;
            uniform sampler2D uFace;
            uniform sampler2D uFront;
            uniform float uMatte;
            uniform float uDepth;
            uniform float uFaceAlpha;
            uniform float uGain;
            uniform vec2 uCrush;
            // The picture's own coordinates again, for moving a sample by rear pixels (the vertex shader's uTex).
            uniform mat4 uTexF;
            // The weather: x and y are not used any more (the clouds are not moved here),
            // z a storm's flash (0..1). All nought on a clip that is not the moon's.
            uniform vec3 uCloud;
            // The glitch: x its strength now (0 when there is none, which is nearly always), y a seed that
            // jumps while it lasts.
            uniform vec2 uGlitch;
            varying vec2 vColor;
            varying vec2 vMatte;
            varying vec2 vFace;
            float hash(float n) { return fract(sin(n * 12.9898) * 43758.5453); }
            void main() {
                // Where on the back, in rear pixels of a 976x596 panel (y down).
                vec2 px = vFace * vec2(976.0, 596.0);
                vec2 d = vec2(0.0);
                // THE EARTH IS NEVER MOVED HERE. At first its clouds were stirred in this shader by the
                // weather, a displacement that read as the planet changing shape. It turns in the clip itself, rigid.
                // What is left of the weather here is light only: a storm's lightning, inside the disc.
                float earth = smoothstep(505.0, 462.0, length(px - vec2(702.0, -144.0))) * smoothstep(232.0, 180.0, px.y);
                // THE GLITCH, a moment of it now and then: a few bands of the picture torn sideways, its red and
                // blue pulled apart. Subtle: a twitch, not a show.
                float g = uGlitch.x;
                float split = 0.0;
                if (g > 0.0) {
                    float band = floor(px.y / 19.0);
                    float r = hash(band + uGlitch.y * 7.31);
                    d.x += step(0.84, r) * (hash(band * 3.7 + uGlitch.y) - 0.5) * 34.0 * g;
                    split = 4.5 * g;
                }
                vec2 uv = vColor + (uTexF * vec4(d.x / 976.0, -d.y / 596.0 * (uMatte > 0.5 ? 0.5 : 1.0), 0.0, 0.0)).xy;
                vec2 off = (uTexF * vec4(split / 976.0, 0.0, 0.0, 0.0)).xy;
                vec3 c = texture2D(uVideo, uv).rgb;
                if (g > 0.0) {
                    c.r = texture2D(uVideo, uv + off).r;
                    c.b = texture2D(uVideo, uv - off).b;
                }
                // A storm's lightning, in the clouds only.
                c += uCloud.z * earth * vec3(0.55, 0.6, 0.7) * max(c.r, max(c.g, c.b));
                float peak = max(c.r, max(c.g, c.b));
                c *= smoothstep(uCrush.x, uCrush.y, peak);
                vec4 f = texture2D(uFace, vFace) * uFaceAlpha;
                if (uDepth > 0.5) {
                    float subject = smoothstep(0.35, 0.65, texture2D(uVideo, vMatte).g);
                    f *= 1.0 - subject;
                }
                // Behind the subject: the hours. Over everything: the minutes and the date.
                vec4 top = texture2D(uFront, vFace) * uFaceAlpha;
                vec3 o = c * (1.0 - f.a) + f.rgb;
                o = (o * (1.0 - top.a) + top.rgb) * uGain;
                // Darkened, its soft glows would step: half a level of noise, fixed to the pixel, hides the steps.
                if (uGain < 0.999) o += (hash(px.x * 0.731 + px.y * 13.17) - 0.5) / 255.0 * step(0.004, max(o.r, max(o.g, o.b)));
                gl_FragColor = vec4(o, 1.0);
            }
        """
    }
}
