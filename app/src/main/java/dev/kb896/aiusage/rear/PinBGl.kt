package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PinBGl {
    private const val LEVELS = 7

    /** A texture array of the seven blur levels of one picture (BlurLevels.RADII), all [w] x [h]. */
    class Levels(val w: Int, val h: Int) {
        val id: Int = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        var ready = false
            private set

        init {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, id)
            GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, GLES30.GL_RGBA8, w, h, LEVELS)
            for ((k, v) in listOf(GLES30.GL_TEXTURE_MIN_FILTER to GLES30.GL_LINEAR, GLES30.GL_TEXTURE_MAG_FILTER to GLES30.GL_LINEAR,
                GLES30.GL_TEXTURE_WRAP_S to GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_TEXTURE_WRAP_T to GLES30.GL_CLAMP_TO_EDGE)) GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, k, v)
        }

        /** Level [i] from RGBA bytes. */
        fun put(i: Int, rgba: ByteArray) {
            val buf = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.nativeOrder()).put(rgba).also { it.position(0) }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, id)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, i, w, h, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        }

        /** Every level the same picture (until its blurred levels are made). */
        fun putAll(rgba: ByteArray) { for (i in 0 until LEVELS) put(i, rgba) }

        fun putLevels(levels: List<ByteArray>) { levels.forEachIndexed { i, b -> put(i, b) }; ready = true }

        fun release() { GLES30.glDeleteTextures(1, intArrayOf(id), 0) }
    }

    /** A picture's pixels as RGBA bytes (opaque pictures; premultiplied as Android keeps them). */
    fun rgba(b: Bitmap): ByteArray {
        val soft = if (b.config == Bitmap.Config.HARDWARE || b.config != Bitmap.Config.ARGB_8888) b.copy(Bitmap.Config.ARGB_8888, false) else b
        val buf = ByteBuffer.allocate(soft.byteCount)
        soft.copyPixelsToBuffer(buf)
        if (soft !== b) soft.recycle()
        return buf.array()
    }

    fun texture2d(picture: Bitmap): Int {
        val id = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        for ((k, v) in listOf(GLES30.GL_TEXTURE_MIN_FILTER to GLES30.GL_LINEAR, GLES30.GL_TEXTURE_MAG_FILTER to GLES30.GL_LINEAR,
            GLES30.GL_TEXTURE_WRAP_S to GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_TEXTURE_WRAP_T to GLES30.GL_CLAMP_TO_EDGE)) GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, k, v)
        val soft = if (picture.config == Bitmap.Config.HARDWARE) picture.copy(Bitmap.Config.ARGB_8888, false) else picture
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, soft, 0)
        if (soft !== picture) soft.recycle()
        return id
    }

    private class Program(vs: String, fs: String) {
        val id: Int = link(vs, fs)
        private val at = HashMap<String, Int>()
        fun u(n: String): Int = at.getOrPut(n) { GLES30.glGetUniformLocation(id, n) }
        fun use() = GLES30.glUseProgram(id)
        fun f(n: String, v: Float) = GLES30.glUniform1f(u(n), v)
        fun f2(n: String, a: Float, b: Float) = GLES30.glUniform2f(u(n), a, b)
        fun f3(n: String, a: Float, b: Float, c: Float) = GLES30.glUniform3f(u(n), a, b, c)
        fun tex(n: String, unit: Int, target: Int, tex: Int) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit); GLES30.glBindTexture(target, tex); GLES30.glUniform1i(u(n), unit)
        }
    }

    private class Target(val w: Int, val h: Int, n: Int, float: Boolean) {
        val fb = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        val tex = IntArray(n)
        init {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb)
            GLES30.glGenTextures(n, tex, 0)
            for (i in 0 until n) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i])
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, if (float) GLES30.GL_RGBA16F else GLES30.GL_RGBA8, w, h)
                for ((k, v) in listOf(GLES30.GL_TEXTURE_MIN_FILTER to GLES30.GL_LINEAR, GLES30.GL_TEXTURE_MAG_FILTER to GLES30.GL_LINEAR,
                    GLES30.GL_TEXTURE_WRAP_S to GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_TEXTURE_WRAP_T to GLES30.GL_CLAMP_TO_EDGE)) GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, k, v)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + i, GLES30.GL_TEXTURE_2D, tex[i], 0)
            }
            GLES30.glDrawBuffers(n, IntArray(n) { GLES30.GL_COLOR_ATTACHMENT0 + it }, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "a target of $w x $h is not complete" }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
        fun release() { GLES30.glDeleteFramebuffers(1, intArrayOf(fb), 0); GLES30.glDeleteTextures(tex.size, tex, 0) }
    }

    private val quad: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).also { it.asFloatBuffer().put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)) }
    }

    private fun drawQuad() {
        GLES30.glBindVertexArray(0); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        quad.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun into(t: Target?, w: Int, h: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t?.fb ?: 0)
        GLES30.glViewport(0, 0, t?.w ?: w, t?.h ?: h)
    }

    /** Whether this context can draw into half-float pictures (for the light, which goes past 1). */
    fun floatTargets(): Boolean {
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        return "GL_EXT_color_buffer_float" in ext || "GL_EXT_color_buffer_half_float" in ext
    }

    /**
     * The front, on a surface [w] x [h] pixels covering the whole screen; [unit] screen pixels per unit of Xiaomi's
     * 1200-wide front. [photo] the content's levels; [env], [light] Xiaomi's pictures.
     */
    class Front(private val w: Int, private val h: Int, private val unit: Float, env: Bitmap, light: Bitmap, val photo: Levels) {
        private val bloomOn = floatTargets()
        private val scene = Program(VS, FRONT_FS)
        private val comp = Program(VS, COMP_FS)
        private val down = Program(VS, DOWN_FS)
        private val blur = Program(VS, BLUR_FS)
        private val envTex = texture2d(env)
        private val lightTex = texture2d(light)
        private val main = Target(w, h, 2, bloomOn)
        private val q = if (bloomOn) Target(maxOf(1, w shr 2), maxOf(1, h shr 2), 1, true) else null
        private val q2 = if (bloomOn) Target(maxOf(1, w shr 2), maxOf(1, h shr 2), 1, true) else null
        private val s = if (bloomOn) Target(maxOf(1, w shr 4), maxOf(1, h shr 4), 1, true) else null
        private val s2 = if (bloomOn) Target(maxOf(1, w shr 4), maxOf(1, h shr 4), 1, true) else null
        /** The page behind (the glass's picture), when it has come: its levels, and how far it is shown. */
        var page: Levels? = null

        /** One frame of [f]; [hole] the camera hole in units (x, y, r) and whether it is known; [pageOn] how far the page behind is drawn. */
        fun draw(f: PinB.Front, screenH: Float, holeX: Float, holeY: Float, holeR: Float, holeKnown: Boolean, pageOn: Float) {
            GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            into(main, w, h)
            GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            scene.use()
            scene.f2("uRes", w.toFloat(), h.toFloat()); scene.f("uS", unit); scene.f("uH", screenH)
            val pg = page
            scene.tex("uPage", 0, GLES30.GL_TEXTURE_2D_ARRAY, (pg ?: photo).id)
            scene.f("uPageOn", if (pg != null && pg.ready) pageOn.coerceIn(0f, 1f) else 0f)
            scene.tex("uPhoto", 1, GLES30.GL_TEXTURE_2D_ARRAY, photo.id)
            scene.tex("uEnv", 2, GLES30.GL_TEXTURE_2D, envTex); scene.tex("uLight", 3, GLES30.GL_TEXTURE_2D, lightTex)
            scene.f("uPageBlur", f.blur); scene.f("uDim", f.dim)
            scene.f2("uC", f.cardX, f.cardY); scene.f2("uSz", f.cardW, f.cardH); scene.f("uCorner", f.cardCorner)
            scene.f("uRotY", Math.toRadians(f.rotY.toDouble()).toFloat()); scene.f("uThick", f.thick); scene.f("uCardOp", f.cardOp)
            scene.f("uGlow", f.glow); scene.f("uFtOp", f.ftOp); scene.f("uFtSigma", f.ftSigma); scene.f("uEdgeTh", f.edgeTh)
            scene.f3("uSpr", f.sprX, f.sprY, f.sprW); scene.f("uSprOp", f.sprOp); scene.f("uSprCorner", f.sprCorner)
            scene.f3("uHole", holeX, holeY, holeR); scene.f("uHoleOn", if (holeKnown) 1f else 0f)
            scene.f2("uIsl", f.islandW, f.islandH); scene.f("uIslOp", f.islandOp); scene.f("uIslContent", f.islandContent)
            drawQuad()
            if (bloomOn) {
                val q = q!!; val q2 = q2!!; val s = s!!; val s2 = s2!!
                down.use(); down.tex("uT", 0, GLES30.GL_TEXTURE_2D, main.tex[1]); down.f2("uOutSize", q.w.toFloat(), q.h.toFloat()); into(q, w, h); drawQuad()
                blur.use(); blur.tex("uT", 0, GLES30.GL_TEXTURE_2D, q.tex[0]); blur.f2("uDir", 1f, 0f); into(q2, w, h); drawQuad()
                blur.tex("uT", 0, GLES30.GL_TEXTURE_2D, q2.tex[0]); blur.f2("uDir", 0f, 1f); into(q, w, h); drawQuad()
                down.use(); down.tex("uT", 0, GLES30.GL_TEXTURE_2D, q.tex[0]); down.f2("uOutSize", s.w.toFloat(), s.h.toFloat()); into(s, w, h); drawQuad()
                blur.use(); blur.tex("uT", 0, GLES30.GL_TEXTURE_2D, s.tex[0]); blur.f2("uDir", 1f, 0f); into(s2, w, h); drawQuad()
                blur.tex("uT", 0, GLES30.GL_TEXTURE_2D, s2.tex[0]); blur.f2("uDir", 0f, 1f); into(s, w, h); drawQuad()
            }
            into(null, w, h)
            comp.use()
            comp.f2("uOut", w.toFloat(), h.toFloat())
            comp.tex("uCol", 0, GLES30.GL_TEXTURE_2D, main.tex[0])
            comp.tex("uB1", 1, GLES30.GL_TEXTURE_2D, q?.tex?.get(0) ?: main.tex[1])
            comp.tex("uB2", 2, GLES30.GL_TEXTURE_2D, s?.tex?.get(0) ?: main.tex[1])
            // The draft's front bloom strength (1.1); none where the light cannot be kept past 1.
            comp.f("uBloom", if (bloomOn) 1.1f else 0f)
            drawQuad()
        }

        fun release() {
            for (t in listOfNotNull(main, q, q2, s, s2)) t.release()
            GLES30.glDeleteTextures(2, intArrayOf(envTex, lightTex), 0)
            photo.release(); page?.release()
            for (p in listOf(scene, comp, down, blur)) GLES30.glDeleteProgram(p.id)
        }
    }

    /** The back, on a surface [w] x [h] (the rear panel); [photo] the content's levels (the notes page). */
    class Rear(private val w: Int, private val h: Int, val photo: Levels) {
        private val prog = Program(VS, REAR_FS)
        private val veilTex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        private var veilW = 0; private var veilH = 0

        init {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, veilTex)
            for ((k, v) in listOf(GLES30.GL_TEXTURE_MIN_FILTER to GLES30.GL_LINEAR, GLES30.GL_TEXTURE_MAG_FILTER to GLES30.GL_LINEAR,
                GLES30.GL_TEXTURE_WRAP_S to GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_TEXTURE_WRAP_T to GLES30.GL_CLAMP_TO_EDGE)) GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, k, v)
        }

        /** pin_show.pag's frame as it is to lie over the back now (a premultiplied picture of any size). */
        fun veil(frame: Bitmap) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, veilTex)
            if (frame.width != veilW || frame.height != veilH) { GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, frame, 0); veilW = frame.width; veilH = frame.height }
            else GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, frame)
        }

        fun draw(b: PinB.Back, veilOn: Boolean) {
            GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            into(null, w, h)
            GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            prog.use()
            prog.f2("uRes", w.toFloat(), h.toFloat()); prog.f("uS", w / 976f)
            prog.tex("uPhoto", 0, GLES30.GL_TEXTURE_2D_ARRAY, photo.id)
            prog.tex("uVeil", 1, GLES30.GL_TEXTURE_2D, veilTex)
            prog.f("uVeilOn", if (veilOn && veilW > 0) 1f else 0f)
            prog.f("uPhotoA", b.photoA); prog.f("uPhotoBlur", b.photoBlur)
            drawQuad()
        }

        fun release() {
            GLES30.glDeleteTextures(1, intArrayOf(veilTex), 0)
            photo.release()
            GLES30.glDeleteProgram(prog.id)
        }
    }

    private fun link(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: ${GLES30.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aP")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: ${GLES30.glGetProgramInfoLog(p)}" }
        return p
    }

    // ------------------------------------------------------------------------------------------------ the shaders
    // The draft's (shaders.js), B only. All light in linear space; out to sRGB at the end. Kept as the draft has them
    // so that what was judged in its frames is what plays; what differs is said where it is.

    private const val COMMON = """#version 300 es
precision highp float; precision highp sampler2DArray;
float sdRR(vec2 p, vec2 h, float r) { r = min(r, min(h.x, h.y)); vec2 d = abs(p) - h + r; return length(max(d, 0.)) + min(max(d.x, d.y), 0.) - r; }
vec3 lin(vec3 c) { return pow(max(c, 0.), vec3(2.2)); }
// a picture blurred by radius r (px of its units): 7 levels made beforehand, radius 0 4 8 16 32 64 128
vec3 blurAt(sampler2DArray s, vec2 uv, float r) {
  float f = r <= 0. ? 0. : (r < 4. ? r / 4. : min(log2(r / 4.) + 1., 6.));
  float i = floor(f);
  vec3 a = texture(s, vec3(uv, i)).rgb, b = texture(s, vec3(uv, min(i + 1., 6.))).rgb;
  return lin(mix(a, b, f - i));
}
"""

    const val VS = """#version 300 es
in vec2 aP; void main() { gl_Position = vec4(aP, 0., 1.); }"""

    /** drafts' FRONT_FS, B: what is drawn is premultiplied (colour times cover, cover in alpha) over the real page. */
    const val FRONT_FS = COMMON + """
uniform vec2 uRes; uniform float uS; uniform float uH;
uniform sampler2DArray uPage, uPhoto; uniform sampler2D uEnv, uLight;
uniform float uPageOn, uPageBlur, uDim;
uniform vec2 uC, uSz; uniform float uCorner, uRotY, uThick, uCardOp;
uniform float uGlow, uFtOp, uFtSigma, uEdgeTh;
uniform vec3 uSpr; uniform float uSprOp, uSprCorner;
uniform vec3 uHole; uniform float uHoleOn;
uniform vec2 uIsl; uniform float uIslOp, uIslContent;
layout(location = 0) out vec4 oCol; layout(location = 1) out vec4 oEm;

const vec3 L = normalize(vec3(-0.45, -0.75, 0.55));
const vec2 LENS1 = vec2(148., 152.), LENS2 = vec2(148., 444.); const float LENS_R = 124.;

mat3 R;
float sd2(vec2 q) { return sdRR(q, uSz * 0.5, uCorner); }
float sd3(vec3 q) {
  float er = uThick * 0.45;
  vec2 w = vec2(sd2(q.xy) + er, abs(q.z) - uThick * 0.5 + er);
  return length(max(w, 0.)) + min(max(w.x, w.y), 0.) - er;
}
vec3 nrm(vec3 q) {
  vec2 e = vec2(0.25, -0.25);
  return normalize(e.xyy * sd3(q + e.xyy) + e.yyx * sd3(q + e.yyx) + e.yxy * sd3(q + e.yxy) + e.xxx * sd3(q + e.xxx));
}
vec3 photo(vec2 uv, float r) { return blurAt(uPhoto, clamp(uv, 0.001, 0.999), r); }
vec2 lensC(int i) { vec2 c = i == 0 ? LENS1 : LENS2; return (c / vec2(976., 596.) - 0.5) * uSz; }
float lensR() { return LENS_R / 976. * uSz.x; }

// a layer over what is there: premultiplied
void over(inout vec4 col, vec3 c, float a) { col = vec4(col.rgb * (1. - a) + c * a, col.a * (1. - a) + a); }

void main() {
  vec2 p = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y) / uS;
  vec2 puv = p / vec2(1200., uH);
  vec4 col = vec4(0.);
  // the page behind, where its picture is (else the system's blur is under), then the dim over all of it
  if (uPageOn > 0.001) col = vec4(blurAt(uPage, puv, uPageBlur) * uPageOn, uPageOn);
  over(col, vec3(0.), uDim);
  vec3 em = vec3(0.);
  float px = 1. / uS;

  // ---- the card: Xiaomi's slab, ray-marched in perspective. matte black, then the light turns it to coloured glass
  if (uCardOp > 0.001 && uSz.x > 1.) {
    float cy = cos(uRotY), sy = sin(uRotY);
    R = mat3(cy, 0., -sy, 0., 1., 0., sy, 0., cy);
    vec3 E = vec3(600., uC.y, 2600.);
    vec3 rd = normalize(vec3(p, 0.) - E);
    mat3 Rt = transpose(R);
    vec3 ro = Rt * (E - vec3(uC, 0.)), rl = Rt * rd;
    rl += vec3(equal(rl, vec3(0.))) * 1e-6;
    vec3 hx = vec3(uSz * 0.5, uThick * 0.5) + 2. * px;
    vec3 t1 = (-hx - ro) / rl, t2 = (hx - ro) / rl;
    vec3 tn = min(t1, t2), tf = max(t1, t2);
    float a = max(max(tn.x, tn.y), tn.z), b = min(min(tf.x, tf.y), tf.z);
    if (b > max(a, 0.)) {
      float t = a, md = 1e9; vec3 mq = ro + rl * a; bool hit = false;
      for (int i = 0; i < 64; i++) {
        vec3 q = ro + rl * t; float d = sd3(q);
        if (d < md) { md = d; mq = q; }
        if (d < 0.02) { hit = true; break; }
        t += d; if (t > b) break;
      }
      float cov = hit ? 1. : clamp(0.5 - md / px * 0.7, 0., 1.);
      if (cov > 0.) {
        vec3 q = mq; vec3 nl = nrm(q); vec3 nw = R * nl;
        vec2 uv = q.xy / uSz + 0.5;
        float e = -sd2(q.xy);
        vec3 refl = reflect(rd, nw);
        float spec = pow(max(dot(refl, -L), 0.), 60.);
        vec3 c = vec3(0.), m = vec3(0.);
        float face = nl.z;
        vec2 o = vec2(0.66, 0.5);
        float dw = length((uv - o) * vec2(uSz.x / uSz.y, 1.)) / (uSz.x / uSz.y);
        float lr = lensR();
        float dl = min(length(q.xy - lensC(0)), length(q.xy - lensC(1)));
        vec3 env = lin(texture(uEnv, refl.xy * 0.45 + 0.5).rgb);
        float lum = dot(env, vec3(0.3, 0.59, 0.11));
        vec3 xl = lin(texture(uLight, clamp(uv, 0.01, 0.99)).rgb);
        float R0 = uGlow * 1.5;
        float inside = smoothstep(R0 + 0.30, R0 - 0.30, dw) * smoothstep(0., 0.03, uGlow);
        float flash = exp(-pow((dw - R0) / 0.20, 2.)) * (1. - smoothstep(0.6, 1.0, uGlow)) * step(0.01, uGlow);
        if (face > 0.5) {
          vec3 slab = vec3(0.0022) + lum * 0.006 + spec * 0.03;
          vec3 frost = mix(xl * 1.05, photo(uv, 96.) * 1.1, 0.3);
          vec3 sharp = photo(uv, uFtSigma * 2.) * 0.9 + xl * 0.09;
          vec3 lit = mix(frost, sharp, clamp(uFtOp, 0., 1.));
          lit += env * 0.04;
          c = mix(slab, lit, inside);
          float hole = smoothstep(lr + 1., lr - 1., dl);
          c = mix(c, vec3(0.0010) + lum * 0.004, hole);
          float ring = exp(-pow((dl - lr) / 1.8, 2.)) * inside * uGlow;
          c += vec3(0.07, 0.23, 0.98) * ring * 0.6; m += vec3(0.07, 0.23, 0.98) * ring * 0.9;
          c += xl * flash * 0.25; m += xl * flash * 0.15;
          float rim = exp(-pow(e / (1.6 + uEdgeTh * 70.), 2.)) * inside;
          c += xl * rim * 0.9; m += xl * rim * 2.2 * uGlow;
        } else if (face < -0.5) {
          c = mix(vec3(0.0035 + lum * 0.012 + spec * 0.05), xl * 0.85 + env * 0.05, uGlow);
          m = xl * uGlow * 0.35;
          float rim = exp(-pow(e / (1.6 + uEdgeTh * 70.), 2.));
          c += xl * rim * uGlow; m += xl * rim * 2.0 * uGlow;
        } else {
          c = mix(vec3(0.05) + lum * 0.06 + spec * 0.2, xl * 1.2, uGlow);
          m = xl * uGlow * 1.6;
        }
        float a2 = cov * uCardOp;
        over(col, c, a2); em = mix(em, m, a2);
      }
    }
  }

  // ---- the content lifting off the page (flat, plain, as Xiaomi's content is)
  if (uSprOp > 0.001) {
    vec2 h = vec2(uSpr.z, uSpr.z / (976. / 596.)) * 0.5;
    float d = sdRR(p - uSpr.xy, h, uSprCorner);
    float cov = clamp(0.5 - d / px, 0., 1.) * uSprOp;
    vec2 uv = (p - uSpr.xy) / (2. * h) + 0.5;
    over(col, photo(uv, 0.), cov); em *= 1. - cov;
  }

  // ---- the camera hole, only where the screen says where it is (it is the hole itself: nothing of ours shows there)
  if (uHoleOn > 0.5) {
    float dh = length(p - uHole.xy) - uHole.z;
    float hole = clamp(0.5 - dh / px, 0., 1.);
    over(col, vec3(0.), hole); em *= 1. - hole;
  }

  // ---- the island grows out of the hole: black, the content small inside, no words
  if (uIslOp > 0.001) {
    float d = sdRR(p - uHole.xy, uIsl * 0.5, uIsl.y * 0.5);
    float cov = clamp(0.5 - d / px, 0., 1.) * uIslOp;
    vec3 ic = vec3(0.);
    vec2 th = vec2(uIsl.y * 0.62 * (976. / 596.), uIsl.y * 0.62) * 0.5;
    vec2 tc = vec2(uHole.x - uIsl.x * 0.5 + uIsl.y * 0.5 + th.x - 6., uHole.y);
    float dt = sdRR(p - tc, th, th.y * 0.45);
    vec3 thumb = photo((p - tc) / (2. * th) + 0.5, 0.);
    ic = mix(ic, thumb, clamp(0.5 - dt / px, 0., 1.) * uIslContent);
    // its foot glows a little in the content's colour (Xiaomi's music island does this with the cover)
    ic += photo(vec2(0.5), 128.) * 0.10 * smoothstep(-uIsl.y * 0.5, uIsl.y * 0.5, p.y - uHole.y) * uIslContent;
    over(col, ic, cov); em *= 1. - cov;
  }

  oCol = col; oEm = vec4(em, 1.);
}"""

    // The draft's bloom: a quarter and a sixteenth of the light, each blurred.
    const val DOWN_FS = """#version 300 es
precision highp float; uniform sampler2D uT; uniform vec2 uOutSize; out vec4 o;
void main() { vec2 uv = gl_FragCoord.xy / uOutSize; vec2 st = 1. / vec2(textureSize(uT, 0));
  o = 0.25 * (texture(uT, uv + st * vec2(-1., -1.)) + texture(uT, uv + st * vec2(1., -1.)) + texture(uT, uv + st * vec2(-1., 1.)) + texture(uT, uv + st * vec2(1., 1.))); }"""

    const val BLUR_FS = """#version 300 es
precision highp float; uniform sampler2D uT; uniform vec2 uDir; out vec4 o;
void main() { vec2 ts = 1. / vec2(textureSize(uT, 0)); vec2 uv = gl_FragCoord.xy * ts; vec4 s = vec4(0.); float wsum = 0.;
  for (int i = -8; i <= 8; i++) { float w = exp(-float(i * i) / (2. * 3.2 * 3.2)); s += texture(uT, uv + uDir * ts * float(i)) * w; wsum += w; }
  o = s / wsum; }"""

    /**
     * To the screen: the drawn colour unpremultiplied, the bloom added, the draft's tone curve, sRGB, premultiplied
     * again; the bloom outside what is drawn is added light over the page.
     */
    const val COMP_FS = """#version 300 es
precision highp float;
uniform vec2 uOut; uniform sampler2D uCol, uB1, uB2; uniform float uBloom;
out vec4 o;
vec3 tone(vec3 c) { vec3 k = 0.82 + 0.18 * (1. - exp(-(c - 0.82) / 0.18)); return mix(c, k, step(0.82, c)); }
vec3 srgb(vec3 c) { return pow(tone(max(c, 0.)), vec3(1. / 2.2)); }
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
void main() {
  vec2 uv = gl_FragCoord.xy / uOut;
  vec4 c = texture(uCol, uv);
  vec3 b = (texture(uB1, uv).rgb * 0.55 + texture(uB2, uv).rgb * 0.75) * uBloom;
  float a = clamp(c.a, 0., 1.);
  vec3 straight = c.rgb / max(a, 1e-4);
  vec3 rgb = (a > 0.0001 ? srgb(straight + b) * a : vec3(0.)) + srgb(b) * (1. - a);
  rgb += (hash(gl_FragCoord.xy) - 0.5) / 255. * step(0.002, a + dot(b, vec3(1.)));
  o = vec4(max(rgb, 0.), a);
}"""

    /**
     * drafts' REAR_FS, B: the content (blurred as far as it is out of focus) at its strength, pin_show.pag's frame laid
     * over it (straight colour from a premultiplied picture). What blurs out under it is the system's (the layer's
     * background blur). No tone curve: the picture it ends on is the notes page as it is, pixel for pixel.
     */
    const val REAR_FS = COMMON + """
uniform vec2 uRes; uniform float uS;
uniform sampler2DArray uPhoto; uniform sampler2D uVeil; uniform float uVeilOn, uPhotoA, uPhotoBlur;
out vec4 o;
void main() {
  vec2 p = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y) / uS;
  vec2 uv = p / vec2(976., 596.);
  vec4 col = vec4(blurAt(uPhoto, clamp(uv, 0.001, 0.999), uPhotoBlur) * uPhotoA, uPhotoA);
  if (uVeilOn > 0.5) {
    vec4 v = texture(uVeil, uv);
    vec3 vs = v.rgb / max(v.a, 1e-4);
    col = vec4(col.rgb * (1. - v.a) + lin(vs) * v.a, col.a * (1. - v.a) + v.a);
  }
  float a = clamp(col.a, 0., 1.);
  vec3 rgb = a > 0.0001 ? pow(max(col.rgb / a, 0.), vec3(1. / 2.2)) * a : vec3(0.);
  o = vec4(rgb, a);
}"""
}
