package dev.kb896.aiusage.rear

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * THE DUST FACE ON THE GPU (DustField has the shaders and their Kotlin twins): needs a GLES 3 context current on the
 * caller's thread (SceneEngine makes one for this face; nothing else draws in it while the dust plays). Per frame:
 *  1. the motes are stepped by [DustField.UPDATE_VS] from one state buffer into the other (transform feedback, the
 *     rasteriser off), in one or more steps (DustPace.steps): where each is, how far a finger has pushed it, how fast it
 *     comes back;
 *  2. the frame is cleared to black and the beam's haze drawn in the beam's band ([DustField.HAZE_VS], [DustField.HAZE_FS]);
 *  3. the motes are added as small quads, one instance each ([DustField.DRAW_VS], [DustField.DRAW_FS]), in linear light
 *     when the surface is sRGB ([srgb]: the hardware then encodes and blends right), else encoded in the shader;
 *  4. the clock over it, if it is on (SceneFace's two textures, both over: no matte here).
 *
 * FEW CALLS, EACH A TRIP THROUGH JNI INTO THE DRIVER: 19 a frame (was 39). The frame's inputs go up as two arrays
 * ([DustField.packUpdate], [DustField.packDraw], from direct buffers: no array pinned or copied); what stays the same
 * (the viewport, the clear colour, the blend equation and function, the clock's samplers, the output's size) is set
 * once in [setUp]; the gain only when it changes; no vertex array is unbound for the haze, which reads none. No
 * glGetError, glFinish or fence per frame; no per-mote work, no allocation (src/test/gl/dust-gl.c makes the same calls on
 * the PC, and counts them).
 */
internal class DustGl(private val outW: Int, private val outH: Int, private val srgb: Boolean) {
    private var update = 0
    private var draw = 0
    private var haze = 0
    private var clock = 0
    private val buffers = IntArray(3)       // the motes (fixed), state A, state B
    /** Per state: the update's arrays (one vertex a mote) and the draw's (one instance a mote). */
    private val stepArrays = IntArray(2)
    private val drawArrays = IntArray(2)
    private var cur = 0
    private var count = 0

    // Uniform places, looked up once.
    private var uInU = 0; private var uInD = 0; private var uOut = 0; private var uFragD = 0
    private var uFragH = 0; private var uOutH = 0
    private var uFragC = 0; private var uAlphaC = 0
    /** The message island's tint (DustField.Inputs.tint): its uniforms in the motes' and the haze's programs. */
    private var uTintD = 0; private var uBoxD = 0; private var uShapeD = 0
    private var uTintH = 0; private var uBoxH = 0; private var uShapeH = 0
    /** What the two programs were last given of it (NaN: nothing yet; 0 after it went, and then nothing until it comes again). */
    private val tintSent = FloatArray(TINT_KEYS) { if (it == 0) 0f else Float.NaN }
    /** The frame's two arrays of uniforms, filled in place (DustField.packUpdate, packDraw). */
    private val inU = floats(4 * DustField.IN_U)
    private val inD = floats(4 * DustField.IN_D)
    /** What the programs were last given: the gain (haze, motes, clock) and the clock's fade. NaN: nothing yet. */
    private var gainHaze = Float.NaN
    private var gainDraw = Float.NaN
    private var gainClock = Float.NaN
    private var alphaClock = Float.NaN
    private val encode = if (srgb) 0f else 1f

    /** Made: programs, the motes' buffers ([m] fixed, [s] the state they start from). Throws if this GPU cannot. */
    fun setUp(m: DustField.Motes, s: DustField.State) {
        update = link(DustField.UPDATE_VS, DustField.UPDATE_FS, feedback = true)
        // Whether the shaders encode to sRGB themselves is known here, once: put in as their ENCODE (DustField.encoding).
        draw = link(DustField.DRAW_VS, DustField.encoding(DustField.DRAW_FS, !srgb))
        haze = link(DustField.HAZE_VS, DustField.encoding(DustField.HAZE_FS, !srgb))
        clock = link(DustField.FULL_VS, DustField.encoding(DustField.CLOCK_FS, !srgb))
        count = m.count
        val fixed = floats(m.count * 4).apply { for (i in 0 until m.count) { put(m.z[i]); put(m.b[i]); put(m.sig[i]); put(0f) }; position(0) }
        val state = floats(m.count * STATE).apply {
            for (i in 0 until m.count) { put(s.px[i]); put(s.py[i]); put(s.dx[i]); put(s.dy[i]); put(s.vx[i]); put(s.vy[i]) }
            position(0)
        }
        GLES30.glGenBuffers(3, buffers, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.count * 16, fixed, GLES30.GL_STATIC_DRAW)
        for (b in 1..2) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[b])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.count * STATE * 4, state, GLES30.GL_DYNAMIC_COPY)
        }
        GLES30.glGenVertexArrays(2, stepArrays, 0)
        GLES30.glGenVertexArrays(2, drawArrays, 0)
        for (a in 0..1) {
            arrays(stepArrays[a], buffers[1 + a], attributes = 3, divisor = 0)
            arrays(drawArrays[a], buffers[1 + a], attributes = 2, divisor = 1)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        uInU = loc(update, "uIn")
        uInD = loc(draw, "uIn"); uOut = loc(draw, "uOut"); uFragD = loc(draw, "uFrag")
        uFragH = loc(haze, "uFrag"); uOutH = loc(haze, "uOutV")
        uFragC = loc(clock, "uFrag"); uAlphaC = loc(clock, "uAlpha")
        uTintD = loc(draw, "uTint"); uBoxD = loc(draw, "uTintBox"); uShapeD = loc(draw, "uTintShape")
        uTintH = loc(haze, "uTint"); uBoxH = loc(haze, "uTintBox"); uShapeH = loc(haze, "uTintShape")
        // What never changes in this context, set once: the frame's size, its black, how the motes are added, the
        // haze's size, the clock's two texture units.
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(haze)
        GLES30.glUniform3f(uOutH, outW / DustField.W, outW.toFloat(), outH.toFloat())
        GLES30.glUseProgram(clock)
        GLES30.glUniform1i(loc(clock, "uFace"), 1); GLES30.glUniform1i(loc(clock, "uFront"), 2)
        GLES30.glUseProgram(0)
        gainHaze = Float.NaN; gainDraw = Float.NaN; gainClock = Float.NaN; alphaClock = Float.NaN
        // A program's uniforms are 0 as it is linked: no tint, nothing to send until one comes.
        tintSent.fill(Float.NaN); tintSent[0] = 0f
        val err = GLES30.glGetError()
        check(err == GLES30.GL_NO_ERROR) { "dust: GL error 0x${Integer.toHexString(err)} while setting up" }
    }

    /** One vertex array: the fixed motes at location 0, then [attributes] pairs of the state from [state]. */
    private fun arrays(array: Int, state: Int, attributes: Int, divisor: Int) {
        GLES30.glBindVertexArray(array)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glVertexAttribDivisor(0, divisor)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, state)
        for (k in 1..attributes) {
            GLES30.glEnableVertexAttribArray(k)
            GLES30.glVertexAttribPointer(k, 2, GLES30.GL_FLOAT, false, STATE * 4, (k - 1) * 8)
            GLES30.glVertexAttribDivisor(k, divisor)
        }
    }

    /**
     * One frame: the motes stepped [dt] seconds on in [steps] equal steps (none when [dt] is 0: a held picture drawn
     * again for its light), then drawn with [inp]. The clock's textures on units 1 and 2 ([clockAlpha] 0: no clock).
     */
    fun frame(inp: DustField.Inputs, dt: Float, steps: Int, clockAlpha: Float, faceTex: Int, frontTex: Int) {
        step(inp, dt, steps)
        picture(inp, clockAlpha, faceTex, frontTex)
    }

    /** [frame]'s first half, the motes stepped (SceneEngine times the two halves apart for its log line). */
    fun step(inp: DustField.Inputs, dt: Float, steps: Int) {
        if (dt > 0f) {
            val n = steps.coerceIn(1, DustField.MAX_STEPS)
            GLES30.glUseProgram(update)
            DustField.packUpdate(inp, dt / n, n, inU)
            GLES30.glUniform4fv(uInU, DustField.IN_U, inU)
            GLES30.glBindVertexArray(stepArrays[cur])
            GLES30.glBindBufferBase(GLES30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, buffers[2 - cur])
            GLES30.glEnable(GLES30.GL_RASTERIZER_DISCARD)
            GLES30.glBeginTransformFeedback(GLES30.GL_POINTS)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
            GLES30.glEndTransformFeedback()
            GLES30.glDisable(GLES30.GL_RASTERIZER_DISCARD)
            GLES30.glBindBufferBase(GLES30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0)
            cur = 1 - cur
        }
    }

    /** [frame]'s second half: the picture drawn. */
    fun picture(inp: DustField.Inputs, clockAlpha: Float, faceTex: Int, frontTex: Int) {
        val gain = inp.gain.coerceIn(0f, 1f)
        // The ground: black, and the beam's haze over it in the beam's band (blending off; it reads no vertex array, so
        // whichever is bound stays bound).
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        // The message island's tint, only when it changed (none at all while there is no island: no call).
        val tintNew = tintChanged(inp)
        GLES30.glUseProgram(haze)
        if (gain != gainHaze) { GLES30.glUniform4f(uFragH, outH.toFloat(), gain, encode, outW / DustField.W); gainHaze = gain }
        if (tintNew) tintTo(uTintH, uBoxH, uShapeH, inp, inp.tintHaze)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        // The motes, added (ONE, ONE, set once).
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glUseProgram(draw)
        if (gain != gainDraw) {
            GLES30.glUniform4f(uOut, outW / DustField.W, outW.toFloat(), outH.toFloat(), gain)
            GLES30.glUniform4f(uFragD, outH.toFloat(), gain, encode, outW / DustField.W)
            gainDraw = gain
        }
        if (tintNew) tintTo(uTintD, uBoxD, uShapeD, inp, inp.tintCol)
        DustField.packDraw(inp, inD)
        GLES30.glUniform4fv(uInD, DustField.IN_D, inD)
        GLES30.glBindVertexArray(drawArrays[cur])
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
        // The clock, if it is on (its textures bound again: SceneEngine.face uploads on the same units).
        if (clockAlpha > 0f) {
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUseProgram(clock)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, faceTex)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frontTex)
            if (gain != gainClock) { GLES30.glUniform4f(uFragC, outW.toFloat(), outH.toFloat(), gain, encode); gainClock = gain }
            if (clockAlpha != alphaClock) { GLES30.glUniform1f(uAlphaC, clockAlpha); alphaClock = clockAlpha }
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Whether the tint differs from what the programs were last given (and it is kept as given). */
    private fun tintChanged(inp: DustField.Inputs): Boolean {
        val a = inp.tint.coerceIn(0f, 1f)
        // None, and none given: nothing to send (the face as always, its 19 calls).
        if (a <= 0f && (tintSent[0] == 0f)) return false
        val b = inp.tintBox; val sh = inp.tintShape; val c = inp.tintCol; val h = inp.tintHaze
        if (a == tintSent[0] && b[0] == tintSent[1] && b[1] == tintSent[2] && b[2] == tintSent[3] && b[3] == tintSent[4] &&
            sh[0] == tintSent[5] && sh[1] == tintSent[6] && c[0] == tintSent[7] && c[1] == tintSent[8] && c[2] == tintSent[9] &&
            h[0] == tintSent[10] && h[1] == tintSent[11] && h[2] == tintSent[12]) return false
        tintSent[0] = a; tintSent[1] = b[0]; tintSent[2] = b[1]; tintSent[3] = b[2]; tintSent[4] = b[3]; tintSent[5] = sh[0]; tintSent[6] = sh[1]
        tintSent[7] = c[0]; tintSent[8] = c[1]; tintSent[9] = c[2]; tintSent[10] = h[0]; tintSent[11] = h[1]; tintSent[12] = h[2]
        return true
    }

    /** The tint to the program in use: its colour [col] and strength, the outline. */
    private fun tintTo(uTint: Int, uBox: Int, uShape: Int, inp: DustField.Inputs, col: FloatArray) {
        val a = inp.tint.coerceIn(0f, 1f)
        GLES30.glUniform4f(uTint, col[0], col[1], col[2], a)
        if (a > 0f) {
            GLES30.glUniform4f(uBox, inp.tintBox[0], inp.tintBox[1], inp.tintBox[2], inp.tintBox[3])
            GLES30.glUniform2f(uShape, inp.tintShape[0], inp.tintShape[1])
        }
    }

    fun release() {
        runCatching {
            if (stepArrays[0] != 0) GLES30.glDeleteVertexArrays(2, stepArrays, 0)
            if (drawArrays[0] != 0) GLES30.glDeleteVertexArrays(2, drawArrays, 0)
            if (buffers[0] != 0) GLES30.glDeleteBuffers(3, buffers, 0)
            for (p in intArrayOf(update, draw, haze, clock)) if (p != 0) GLES30.glDeleteProgram(p)
        }
        stepArrays.fill(0); drawArrays.fill(0); buffers.fill(0); update = 0; draw = 0; haze = 0; clock = 0
    }

    private fun loc(p: Int, name: String) = GLES30.glGetUniformLocation(p, name)

    private fun floats(n: Int) = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun link(vertex: String, fragment: String, feedback: Boolean = false): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "dust shader: ${GLES30.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES30.glCreateProgram()
        val vs = shader(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = shader(GLES30.GL_FRAGMENT_SHADER, fragment)
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        if (feedback) GLES30.glTransformFeedbackVaryings(p, arrayOf("oP", "oD", "oV"), GLES30.GL_INTERLEAVED_ATTRIBS)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "dust program: ${GLES30.glGetProgramInfoLog(p)}" }
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        return p
    }

    companion object {
        /** Floats of state per mote: place, push, pace. */
        const val STATE = 6
        /** What of the tint is compared from picture to picture: strength, outline (4), corner and edge, both colours. */
        private const val TINT_KEYS = 13
    }
}
