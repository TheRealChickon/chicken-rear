package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * THE ONE-TO-ONE PIN'S DRAWING PATH: OpenGL ES 3 on a Surface, the same for the front (a see-through surface over
 * the app he is in) and the back (a shell layer), drawing XIAOMI'S OWN three models with its own pictures (read on
 * the phone: [XiaomiPin]) where its 22 values ([PinKeys]) put them. Xiaomi's renderer is Vulkan with SPIR-V shaders
 * (`libbackpinengine`); these are ours, written for GLSL ES after its passes.
 *
 * THIS IS THE PLUMBING ONLY: the models are loaded, placed and lit by the matcap picture, the content
 * and the card's face are textured, the rim is drawn in its vertex colours. The passes that make the look (the
 * content sampled through blurred mips, the light scanning the rim, the rim's bloom, the added light, the peel)
 * are to be ported one by one from a storyboard that names Xiaomi's shader for each: NOT TUNED, not to be shown
 * as the pin's look. Behind a debug word until then.
 *
 * One thread: everything here is called on the thread that called [Host.make].
 */
object PinGl {
    private const val TAG = "AiUsageRear"

    /** An OpenGL ES 3 context on [output], see-through (alpha kept). */
    class Host(private val output: Surface) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        fun make(): Boolean = runCatching {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL did not start" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no EGL config" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "no EGL context" }
            surface = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "no EGL surface: 0x${Integer.toHexString(EGL14.eglGetError())}" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL make current failed" }
            EGL14.eglSwapInterval(display, 1)
            true
        }.onFailure { Log.w(TAG, "pin gl: ${it.message}"); release() }.getOrDefault(false)

        fun swap() { if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglSwapBuffers(display, surface) }

        /** This context current on the calling thread again (another may have been made current there meanwhile). */
        fun current(): Boolean = surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, context)

        fun release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
                if (surface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, surface) }
                if (context != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, context) }
                runCatching { EGL14.eglReleaseThread() }
            }
            display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; surface = EGL14.EGL_NO_SURFACE
        }
    }

    private class Buffers(val vao: Int, val count: Int)

    /**
     * The three models with their pictures, drawn where [PinKeys]' values say, on a view [w] x [h] pixels whose
     * places are the values' own (x across, y down). [content] is the pinned content's picture (the sheet, and the
     * card's face until [face] is given); [behind] the page behind the pin, where there is one (the glass's, unused
     * by these first shaders).
     */
    class Scene(private val assets: XiaomiPin.Assets) {
        private var card: Buffers? = null; private var edge: Buffers? = null; private var sheet: Buffers? = null
        private var meshProgram = 0; private var edgeProgram = 0; private var sheetProgram = 0
        private val tex = IntArray(6)
        private var hasBehind = false
        private val proj = FloatArray(16); private val view = FloatArray(16); private val model = FloatArray(16)
        private val mv = FloatArray(16); private val mvp = FloatArray(16); private val tmp = FloatArray(16)

        fun init(content: Bitmap, face: Bitmap?) {
            meshProgram = link(MESH_VERT, CARD_FRAG); edgeProgram = link(MESH_VERT, EDGE_FRAG); sheetProgram = link(MESH_VERT, SHEET_FRAG)
            card = upload(assets.card); edge = upload(assets.edge); sheet = upload(assets.sheet)
            GLES30.glGenTextures(tex.size, tex, 0)
            texture(ENV, assets.env); texture(LIGHT, assets.light); assets.noise?.let { texture(NOISE, it) }
            texture(CONTENT, content); texture(FACE, face ?: content)
        }

        /** The page behind the pin has come (the glass's): kept as a texture from now. */
        fun behind(picture: Bitmap) { texture(BEHIND, picture); hasBehind = true }

        private fun texture(slot: Int, picture: Bitmap) {
            // A picture of the screen is a hardware bitmap: its pixels go to the texture through a copy that is let go at once.
            val soft = if (picture.config == Bitmap.Config.HARDWARE) picture.copy(Bitmap.Config.ARGB_8888, false) else picture
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[slot])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, soft, 0)
            // The mips are what Xiaomi's card samples its content through (frosted, then clear).
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            if (soft !== picture) soft.recycle()
        }

        private fun upload(m: Glb.Mesh): Buffers {
            val n = m.vertices
            // 12 floats a vertex: place 3, normal 3, texture 2, colour 4.
            val data = ByteBuffer.allocateDirect(n * 12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until n) {
                data.put(m.positions, i * 3, 3)
                if (m.normals.size >= (i + 1) * 3) data.put(m.normals, i * 3, 3) else data.put(0f).put(1f).put(0f)
                if (m.uvs.size >= (i + 1) * 2) data.put(m.uvs, i * 2, 2) else data.put(0f).put(0f)
                if (m.colors.size >= (i + 1) * 4) data.put(m.colors, i * 4, 4) else data.put(1f).put(1f).put(1f).put(1f)
            }
            data.position(0)
            val index = ByteBuffer.allocateDirect(m.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(m.indices).also { it.position(0) }
            val ids = IntArray(3)
            GLES30.glGenVertexArrays(1, ids, 0); GLES30.glGenBuffers(2, ids, 1)
            GLES30.glBindVertexArray(ids[0])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[1])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 12 * 4, data, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[2])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4, index, GLES30.GL_STATIC_DRAW)
            var at = 0
            for ((slot, size) in listOf(0 to 3, 1 to 3, 2 to 2, 3 to 4)) {
                GLES30.glEnableVertexAttribArray(slot)
                GLES30.glVertexAttribPointer(slot, size, GLES30.GL_FLOAT, false, 12 * 4, at * 4)
                at += size
            }
            GLES30.glBindVertexArray(0)
            return Buffers(ids[0], m.indices.size)
        }

        /** One frame: [keys] as [PinKeys.at] gives them, for a view [w] x [h]. Cleared first (what is behind shows through). */
        fun draw(keys: FloatArray, w: Int, h: Int, shade: Float = 0f) {
            GLES30.glViewport(0, 0, w, h)
            // What is under the surface: as it is, or darkened by [shade] (the back's page under the arriving card).
            GLES30.glClearColor(0f, 0f, 0f, shade.coerceIn(0f, 1f))
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            // The eye three screen-widths off, looking straight at the screen: one unit is one pixel on the screen's plane.
            val far = 3f * w
            val near = far * 0.1f
            Matrix.frustumM(proj, 0, -w / 2f * 0.1f, w / 2f * 0.1f, -h / 2f * 0.1f, h / 2f * 0.1f, near, far * 3f)
            Matrix.setIdentityM(view, 0)
            Matrix.translateM(view, 0, -w / 2f, h / 2f, -far)
            Matrix.scaleM(view, 0, 1f, -1f, 1f)                       // y down, as the values have it

            // The content's sheet: Xiaomi's plane is 2 x 2 in x and z; turned to face the eye, its size in pixels.
            if (keys[PinKeys.SHEET_ALPHA] > 0.003f) {
                GLES30.glDisable(GLES30.GL_DEPTH_TEST)
                place(keys[PinKeys.SHEET_X], keys[PinKeys.SHEET_Y], 90f, 0f, 0f, keys[PinKeys.SHEET_W] / 2f * keys[PinKeys.SHEET_SCALE], keys[PinKeys.SHEET_H] / 2f * keys[PinKeys.SHEET_SCALE])
                use(sheetProgram)
                bind(sheetProgram, "uContent", 0, CONTENT)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(sheetProgram, "uAlpha"), keys[PinKeys.SHEET_ALPHA])
                GLES30.glUniform1f(GLES30.glGetUniformLocation(sheetProgram, "uCorner"), keys[PinKeys.CORNER])
                GLES30.glUniform1f(GLES30.glGetUniformLocation(sheetProgram, "uPeel"), keys[PinKeys.PEEL])
                GLES30.glUniform2f(GLES30.glGetUniformLocation(sheetProgram, "uSize"), keys[PinKeys.SHEET_W], keys[PinKeys.SHEET_H])
                draw(sheet)
            }
            // The card and its rim: Xiaomi's slab is 1.9 x 1.2 in x and z (its rotation about x of 90 turns it to the eye).
            if (keys[PinKeys.CARD_ALPHA] > 0.003f) {
                GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                val s = keys[PinKeys.CARD_W] * keys[PinKeys.CARD_SCALE] / 1.9f
                place(keys[PinKeys.CARD_X], keys[PinKeys.CARD_Y], keys[PinKeys.CARD_ROT_X], keys[PinKeys.CARD_ROT_Y], keys[PinKeys.CARD_ROT_Z], s, s)
                use(meshProgram)
                bind(meshProgram, "uEnv", 0, ENV); bind(meshProgram, "uFace", 1, FACE); bind(meshProgram, "uBehind", 2, if (hasBehind) BEHIND else CONTENT)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(meshProgram, "uAlpha"), keys[PinKeys.CARD_ALPHA])
                GLES30.glUniform1f(GLES30.glGetUniformLocation(meshProgram, "uFaceAlpha"), keys[PinKeys.FACE_ALPHA])
                GLES30.glUniform1f(GLES30.glGetUniformLocation(meshProgram, "uFaceBlur"), keys[PinKeys.FACE_BLUR])
                draw(card)
                use(edgeProgram)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(edgeProgram, "uAlpha"), keys[PinKeys.CARD_ALPHA])
                GLES30.glUniform1f(GLES30.glGetUniformLocation(edgeProgram, "uGlow"), keys[PinKeys.GLOW])
                GLES30.glUniform1f(GLES30.glGetUniformLocation(edgeProgram, "uThickness"), keys[PinKeys.EDGE])
                draw(edge)
            }
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        }

        private fun place(x: Float, y: Float, rx: Float, ry: Float, rz: Float, sx: Float, sz: Float) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, x, y, 0f)
            Matrix.rotateM(model, 0, rz, 0f, 0f, 1f)
            Matrix.rotateM(model, 0, ry, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, rx, 1f, 0f, 0f)
            Matrix.scaleM(model, 0, sx, (sx + sz) / 2f, sz)
            Matrix.multiplyMM(mv, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)
        }

        private fun use(program: Int) {
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMv"), 1, false, mv, 0)
        }

        private fun bind(program: Int, name: String, unit: Int, slot: Int) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[slot])
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit)
        }

        private fun draw(b: Buffers?) {
            b ?: return
            GLES30.glBindVertexArray(b.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, b.count, GLES30.GL_UNSIGNED_INT, 0)
            GLES30.glBindVertexArray(0)
        }

        private fun link(vertex: String, fragment: String): Int {
            fun shader(type: Int, src: String): Int {
                val s = GLES30.glCreateShader(type)
                GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
                val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "shader: ${GLES30.glGetShaderInfoLog(s)}" }
                return s
            }
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vertex)); GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fragment))
            for ((slot, name) in listOf(0 to "aPos", 1 to "aNormal", 2 to "aUv", 3 to "aColor")) GLES30.glBindAttribLocation(p, slot, name)
            GLES30.glLinkProgram(p)
            val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "program: ${GLES30.glGetProgramInfoLog(p)}" }
            return p
        }
    }

    private const val ENV = 0; private const val LIGHT = 1; private const val NOISE = 2; private const val CONTENT = 3; private const val FACE = 4; private const val BEHIND = 5

    private const val MESH_VERT = """#version 300 es
        layout(location = 0) in vec3 aPos; layout(location = 1) in vec3 aNormal; layout(location = 2) in vec2 aUv; layout(location = 3) in vec4 aColor;
        uniform mat4 uMvp; uniform mat4 uMv;
        out vec3 vNormal; out vec2 vUv; out vec4 vColor;
        void main() {
            // The models lie in x and z and are turned to the eye about x: their pictures' top is then at the bottom (seen
            // on the emulator), so the picture is taken from its other end.
            vNormal = mat3(uMv) * aNormal; vUv = vec2(aUv.x, 1.0 - aUv.y); vColor = aColor;
            gl_Position = uMvp * vec4(aPos, 1.0);
        }"""

    /** The card: lit by the matcap picture (after Xiaomi's card_mesh), its face the pinned content as far as it has come. */
    private const val CARD_FRAG = """#version 300 es
        precision highp float;
        in vec3 vNormal; in vec2 vUv; in vec4 vColor;
        uniform sampler2D uEnv; uniform sampler2D uFace; uniform sampler2D uBehind;
        uniform float uAlpha; uniform float uFaceAlpha; uniform float uFaceBlur;
        out vec4 color;
        void main() {
            vec3 n = normalize(vNormal);
            vec3 lit = texture(uEnv, n.xy * 0.5 + 0.5).rgb;
            // The content through its mips: a sigma of 50 is far down the chain, 0 the picture itself.
            vec4 face = textureLod(uFace, vUv, log2(1.0 + uFaceBlur * 0.5));
            float shows = uFaceAlpha * face.a * step(0.0, n.z);
            color = vec4(mix(lit, face.rgb, shows), 1.0) * uAlpha;
        }"""

    /** The rim, in its model's own vertex colours, as lit as its glow says (after Xiaomi's edge_color; its scan and bloom are to come). */
    private const val EDGE_FRAG = """#version 300 es
        precision highp float;
        in vec3 vNormal; in vec2 vUv; in vec4 vColor;
        uniform float uAlpha; uniform float uGlow; uniform float uThickness;
        out vec4 color;
        void main() { color = vec4(vColor.rgb * (0.25 + 0.75 * uGlow), 1.0) * uAlpha; }"""

    /** The content's sheet: its picture with round corners (after Xiaomi's user_image_shader; its peel is to come). */
    private const val SHEET_FRAG = """#version 300 es
        precision highp float;
        in vec3 vNormal; in vec2 vUv; in vec4 vColor;
        uniform sampler2D uContent; uniform float uAlpha; uniform float uCorner; uniform float uPeel; uniform vec2 uSize;
        out vec4 color;
        void main() {
            vec2 p = (vUv - 0.5) * uSize;
            float r = uCorner * 0.5 * min(uSize.x, uSize.y);
            vec2 d = abs(p) - (uSize * 0.5 - r);
            float edge = length(max(d, 0.0)) - r;
            float inside = 1.0 - smoothstep(-1.0, 1.0, edge);
            vec4 c = texture(uContent, vUv);
            color = vec4(c.rgb * c.a, c.a) * uAlpha * inside;
        }"""
}
