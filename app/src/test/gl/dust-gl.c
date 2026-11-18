/* THE DUST FACE ON THE PC'S GPU (desk check; nothing here runs on the phone).
 *
 * Runs the dust face's own shaders (rear/DustField, written by DustGlCheckTest to app/build/dust-gl) through EGL and
 * OpenGL ES 3 with the calls rear/DustGl makes, call for call and counted: transform feedback for the motes' step (in
 * one or more steps a picture), a clear and the beam's band for the haze, one instanced quad per mote, added into an
 * sRGB framebuffer of the panel's size (as the phone's sRGB window surface); each picture's uniforms are the very arrays
 * DustGl uploads (DustField.packUpdate, packDraw, as frames.txt has them). It then
 *   1. plays the test's timeline (frames.txt, at the phone's pace: 120 or 60 a second) and, at its cues, writes
 *      gl-<ms>.rgba and compares it with the Kotlin twin's picture of the same picture (twin-<ms>.rgba): levels apart,
 *      on average and at worst;
 *   2. times the GPU per picture (EXT_disjoint_timer_query) and the CPU per picture for the calls, at rest and
 *      reacting, and says how many calls a picture makes;
 *   3. times the moon's pass (SceneEngine's own shader, its video as a plain texture: MOON.rgba, 976 x 596) the same way.
 *
 *   (in app/) cc -O2 -o build/dust-gl/run src/test/gl/dust-gl.c -lEGL -lGLESv2 -lm && build/dust-gl/run build/dust-gl [MOON.rgba]
 *   MOON.rgba: the moon clip's first frame as raw RGBA, ffmpeg -i david-moon.loop.mp4 -frames:v 1 -f rawvideo -pix_fmt rgba moon.rgba
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define W 976
#define H 596
#define WAVES 12
#define IN_U (WAVES + 5)
#define IN_D 2
#define MAX_STEPS 4

/* Every GL call of a picture, counted (DustGl makes the same ones). */
static long calls = 0;
#define GL(x) do { x; calls++; } while (0)

static PFNGLGENQUERIESEXTPROC genQ;
static PFNGLBEGINQUERYEXTPROC beginQ;
static PFNGLENDQUERYEXTPROC endQ;
static PFNGLGETQUERYOBJECTUI64VEXTPROC getQ64;
static int timer = 0;

static char *slurp(const char *dir, const char *name) {
    char p[2048]; snprintf(p, sizeof p, "%s/%s", dir, name);
    FILE *f = fopen(p, "rb"); if (!f) { perror(p); exit(1); }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *b = malloc(n + 1); if (fread(b, 1, n, f) != (size_t)n) { perror(p); exit(1); } b[n] = 0; fclose(f); return b;
}
static GLuint shader(GLenum t, const char *src) {
    GLuint s = glCreateShader(t); glShaderSource(s, 1, &src, NULL); glCompileShader(s);
    GLint ok; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[8192]; glGetShaderInfoLog(s, sizeof log, NULL, log); fprintf(stderr, "shader: %s\n", log); exit(1); }
    return s;
}
static GLuint program(const char *vs, const char *fs, int feedback) {
    GLuint p = glCreateProgram();
    glAttachShader(p, shader(GL_VERTEX_SHADER, vs)); glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs));
    if (feedback) { const char *v[] = { "oP", "oD", "oV" }; glTransformFeedbackVaryings(p, 3, v, GL_INTERLEAVED_ATTRIBS); }
    glLinkProgram(p); GLint ok; glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) { char log[8192]; glGetProgramInfoLog(p, sizeof log, NULL, log); fprintf(stderr, "link: %s\n", log); exit(1); }
    return p;
}
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }

/* One picture, as frames.txt has it: its step (s) and steps, its gain, its cue, and the two arrays DustGl uploads. */
typedef struct { float dt; int steps; float gain; int cue; float in[4 * IN_U]; float inD[4 * IN_D]; } Frame;

static GLuint upd, drw, hz, buf[3], stepA[2], drawA[2]; static int cur = 0, count = 0;
static GLint uInU, uInD, uOut, uFragD, uFragH, uOutH;
static float gainHaze = -1.f, gainDraw = -1.f;

static void arrays(GLuint a, GLuint state, int attrs, int div) {
    glBindVertexArray(a);
    glBindBuffer(GL_ARRAY_BUFFER, buf[0]); glEnableVertexAttribArray(0); glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 16, 0); glVertexAttribDivisor(0, div);
    glBindBuffer(GL_ARRAY_BUFFER, state);
    for (int k = 1; k <= attrs; k++) { glEnableVertexAttribArray(k); glVertexAttribPointer(k, 2, GL_FLOAT, GL_FALSE, 24, (void *)(long)((k - 1) * 8)); glVertexAttribDivisor(k, div); }
}

/* What DustGl.setUp sets once (sRGB surface: no encoding in the shaders). */
static void setUpState(void) {
    glViewport(0, 0, W, H); glClearColor(0, 0, 0, 1);
    glBlendEquation(GL_FUNC_ADD); glBlendFunc(GL_ONE, GL_ONE); glDisable(GL_BLEND);
    glUseProgram(hz); glUniform3f(uOutH, 1.f, W, H); glUseProgram(0);
    gainHaze = gainDraw = -1.f;
}

/* DustGl.frame, call for call (no clock). */
static void frame(const Frame *f) {
    float gain = fminf(fmaxf(f->gain, 0.f), 1.f);
    if (f->dt > 0) {
        GL(glUseProgram(upd));
        GL(glUniform4fv(uInU, IN_U, f->in));
        GL(glBindVertexArray(stepA[cur]));
        GL(glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, buf[2 - cur]));
        GL(glEnable(GL_RASTERIZER_DISCARD)); GL(glBeginTransformFeedback(GL_POINTS)); GL(glDrawArrays(GL_POINTS, 0, count));
        GL(glEndTransformFeedback()); GL(glDisable(GL_RASTERIZER_DISCARD));
        GL(glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0));
        cur = 1 - cur;
    }
    GL(glClear(GL_COLOR_BUFFER_BIT));
    GL(glUseProgram(hz));
    if (gain != gainHaze) { GL(glUniform4f(uFragH, H, gain, 0.f, 1.f)); gainHaze = gain; }
    GL(glDrawArrays(GL_TRIANGLE_STRIP, 0, 4));
    GL(glEnable(GL_BLEND));
    GL(glUseProgram(drw));
    if (gain != gainDraw) { GL(glUniform4f(uOut, 1.f, W, H, gain)); GL(glUniform4f(uFragD, H, gain, 0.f, 0.f)); gainDraw = gain; }
    GL(glUniform4fv(uInD, IN_D, f->inD));
    GL(glBindVertexArray(drawA[cur])); GL(glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count));
    GL(glDisable(GL_BLEND));
}

static int readFrame(FILE *in, Frame *f) {
    char tag[4];
    if (fscanf(in, "%3s", tag) != 1 || strcmp(tag, "F")) return 0;
    if (fscanf(in, "%f %d %f %d", &f->dt, &f->steps, &f->gain, &f->cue) != 4) return 0;
    if (fscanf(in, "%3s", tag) != 1 || strcmp(tag, "U")) return 0;
    for (int k = 0; k < 4 * IN_U; k++) if (fscanf(in, "%f", &f->in[k]) != 1) return 0;
    if (fscanf(in, "%3s", tag) != 1 || strcmp(tag, "D")) return 0;
    for (int k = 0; k < 4 * IN_D; k++) if (fscanf(in, "%f", &f->inD[k]) != 1) return 0;
    return 1;
}

/* GPU nanoseconds of what runs between two calls, or -1 with no timer. */
static GLuint q;
static void tick(void) { if (timer) beginQ(GL_TIME_ELAPSED_EXT, q); }
static double tock(void) { if (!timer) return -1; endQ(GL_TIME_ELAPSED_EXT); GLuint64 ns = 0; getQ64(q, GL_QUERY_RESULT_EXT, &ns); return (double)ns; }

static int cmpd(const void *a, const void *b) { double x = *(const double *)a, y = *(const double *)b; return x < y ? -1 : x > y; }
static void stats(const char *what, double *v, int n) {
    qsort(v, n, sizeof *v, cmpd);
    double sum = 0; for (int i = 0; i < n; i++) sum += v[i];
    printf("%-44s n %5d  mean %7.1f us  median %7.1f  p95 %7.1f  max %7.1f\n", what, n, sum / n / 1e3, v[n / 2] / 1e3, v[(int)(n * 0.95)] / 1e3, v[n - 1] / 1e3);
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: dust-gl DIR [MOON.rgba]\n"); return 2; }
    const char *dir = argv[1];
    EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(d, NULL, NULL)) { fprintf(stderr, "no EGL\n"); return 1; }
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint ca[] = { EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE };
    EGLConfig c; EGLint n; eglChooseConfig(d, ca, &c, 1, &n); if (n < 1) { fprintf(stderr, "no config\n"); return 1; }
    EGLint sa[] = { EGL_WIDTH, W, EGL_HEIGHT, H, EGL_NONE };
    EGLSurface s = eglCreatePbufferSurface(d, c, sa);
    EGLint xa[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext x = eglCreateContext(d, c, EGL_NO_CONTEXT, xa);
    if (!eglMakeCurrent(d, s, s, x)) { fprintf(stderr, "no context\n"); return 1; }
    printf("GL: %s / %s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));
    if (strstr((const char *)glGetString(GL_EXTENSIONS), "GL_EXT_disjoint_timer_query")) {
        genQ = (void *)eglGetProcAddress("glGenQueriesEXT"); beginQ = (void *)eglGetProcAddress("glBeginQueryEXT");
        endQ = (void *)eglGetProcAddress("glEndQueryEXT"); getQ64 = (void *)eglGetProcAddress("glGetQueryObjectui64vEXT");
        timer = genQ && beginQ && endQ && getQ64; if (timer) genQ(1, &q);
    }
    printf("GPU timer: %s\n", timer ? "EXT_disjoint_timer_query" : "none (glFinish wall time only)");

    /* The panel as the phone's sRGB window surface: an SRGB8_ALPHA8 framebuffer (written encoded, blended in linear light). */
    GLuint fb, tex; glGenFramebuffers(1, &fb); glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex); glTexStorage2D(GL_TEXTURE_2D, 1, GL_SRGB8_ALPHA8, W, H);
    glBindFramebuffer(GL_FRAMEBUFFER, fb); glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { fprintf(stderr, "no sRGB framebuffer\n"); return 1; }

    upd = program(slurp(dir, "update.vert"), slurp(dir, "update.frag"), 1);
    drw = program(slurp(dir, "draw.vert"), slurp(dir, "draw.frag"), 0);
    hz = program(slurp(dir, "haze.vert"), slurp(dir, "haze.frag"), 0);
    uInU = glGetUniformLocation(upd, "uIn"); uInD = glGetUniformLocation(drw, "uIn");
    uOut = glGetUniformLocation(drw, "uOut"); uFragD = glGetUniformLocation(drw, "uFrag");
    uFragH = glGetUniformLocation(hz, "uFrag"); uOutH = glGetUniformLocation(hz, "uOutV");
    if (uInU < 0 || uInD < 0) { fprintf(stderr, "no uIn\n"); return 1; }

    /* The motes: fixed, then the state they start from. */
    char p[2048]; snprintf(p, sizeof p, "%s/motes.bin", dir);
    FILE *mb = fopen(p, "rb"); if (!mb) { perror(p); return 1; }
    if (fread(&count, 4, 1, mb) != 1) return 1;
    float *fixed = malloc(count * 16), *state = malloc(count * 24);
    if (fread(fixed, 16, count, mb) != (size_t)count || fread(state, 24, count, mb) != (size_t)count) { fprintf(stderr, "motes.bin short\n"); return 1; }
    fclose(mb);
    glGenBuffers(3, buf);
    glBindBuffer(GL_ARRAY_BUFFER, buf[0]); glBufferData(GL_ARRAY_BUFFER, count * 16, fixed, GL_STATIC_DRAW);
    for (int b = 1; b <= 2; b++) { glBindBuffer(GL_ARRAY_BUFFER, buf[b]); glBufferData(GL_ARRAY_BUFFER, count * 24, state, GL_DYNAMIC_COPY); }
    glGenVertexArrays(2, stepA); glGenVertexArrays(2, drawA);
    for (int a = 0; a < 2; a++) { arrays(stepA[a], buf[1 + a], 3, 0); arrays(drawA[a], buf[1 + a], 2, 1); }
    glBindVertexArray(0); glBindBuffer(GL_ARRAY_BUFFER, 0);
    setUpState();
    if (glGetError() != GL_NO_ERROR) { fprintf(stderr, "GL error setting up\n"); return 1; }
    printf("%d motes\n", count);

    /* 1. The timeline, compared with the twin at its cues; timed frame by frame. */
    snprintf(p, sizeof p, "%s/frames.txt", dir);
    FILE *in = fopen(p, "r"); if (!in) { perror(p); return 1; }
    Frame *frames = malloc(sizeof(Frame) * 2000); int nf = 0;
    while (nf < 2000 && readFrame(in, &frames[nf])) nf++;
    fclose(in);
    double *gpuRest = malloc(sizeof(double) * nf), *gpuReact = malloc(sizeof(double) * nf), *cpu = malloc(sizeof(double) * nf);
    int nr = 0, na = 0; long callsMin = 1L << 30, callsMax = 0, callsAll = 0;
    unsigned char *px = malloc(W * H * 4), *twin = malloc(W * H * 4), *row = malloc(W * 4);
    for (int i = 0; i < nf; i++) {
        Frame *f = &frames[i];
        int reacting = f->steps > 1;
        long c0 = calls;
        tick(); double t0 = now(); frame(f); double t1 = now(); double g = tock();
        long c = calls - c0; callsAll += c; if (c < callsMin) callsMin = c; if (c > callsMax) callsMax = c;
        cpu[i] = (t1 - t0) * 1e9;
        if (g >= 0) { if (reacting) gpuReact[na++] = g; else gpuRest[nr++] = g; }
        if (f->cue >= 0) {
            glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px);
            for (int y = 0; y < H / 2; y++) { memcpy(row, px + y * W * 4, W * 4); memcpy(px + y * W * 4, px + (H - 1 - y) * W * 4, W * 4); memcpy(px + (H - 1 - y) * W * 4, row, W * 4); }
            snprintf(p, sizeof p, "%s/gl-%05d.rgba", dir, f->cue); FILE *o = fopen(p, "wb"); fwrite(px, 1, W * H * 4, o); fclose(o);
            snprintf(p, sizeof p, "%s/twin-%05d.rgba", dir, f->cue); FILE *tw = fopen(p, "rb");
            if (tw && fread(twin, 1, W * H * 4, tw) == W * H * 4) {
                double sum = 0; int worst = 0, over2 = 0, lit = 0;
                for (int k = 0; k < W * H * 4; k++) {
                    if ((k & 3) == 3) continue;
                    int dlev = abs((int)px[k] - (int)twin[k]); sum += dlev; if (dlev > worst) worst = dlev; if (dlev > 2) over2++; if (twin[k] > 40) lit++;
                }
                printf("cue %5d ms: GPU vs twin: %.4f levels apart on average, %d at worst, %d of %d channel values more than 2 apart (%d over 40 in the twin)\n",
                       f->cue, sum / (W * H * 3), worst, over2, W * H * 3, lit);
            }
            if (tw) fclose(tw);
        }
    }
    printf("\nThe timeline (%d pictures at the phone's pace, %d of them stepped in two; each picture's own work):\n", nf, na);
    printf("GL calls a picture: %ld to %ld, %.1f on average\n", callsMin, callsMax, (double)callsAll / nf);
    if (nr) stats("GPU, at rest (one step)", gpuRest, nr);
    if (na) stats("GPU, reacting (two steps)", gpuReact, na);
    stats("CPU, the calls (no wait for the GPU)", cpu, nf);

    /* 2. Steady: the last picture's inputs, 3000 pictures, at rest (no finger, no lag, no swirl, one step) and in two steps. */
    for (int two = 0; two <= 1; two++) {
        Frame rest = frames[nf - 1];
        rest.in[4 * (WAVES + 3) + 2] = 0;                                    /* no push */
        rest.in[4 * (WAVES + 4) + 2] = rest.in[4 * (WAVES + 4) + 3] = 0;     /* no lag */
        rest.in[4 * WAVES + 2] = 0; rest.inD[4] = 0;                         /* no swirl */
        rest.inD[2] = rest.inD[3] = 0; rest.inD[7] = 0;                      /* no lag, no glow */
        rest.steps = two ? 2 : 1; rest.dt = 1.f / 60.f;
        rest.in[4 * WAVES] = rest.dt / rest.steps; rest.in[4 * WAVES + 1] = (float)rest.steps;
        double *g2 = malloc(sizeof(double) * 3000), *w2 = malloc(sizeof(double) * 3000); int n2 = 0;
        for (int i = 0; i < 3000; i++) {
            tick(); double t0 = now(); frame(&rest); glFinish(); double t1 = now(); double g = tock();
            w2[i] = (t1 - t0) * 1e9; if (g >= 0) g2[n2++] = g;
        }
        printf("\nSteady, %s, 3000 pictures:\n", two ? "two steps a picture" : "at rest, one step");
        if (n2) stats("GPU per picture", g2, n2);
        stats("wall per picture, calls + glFinish", w2, 3000);
    }

    /* 3. The moon's pass: SceneEngine's shader, its video a plain texture (the phone samples a decoder's YUV picture). */
    if (argc > 2) {
        char *mv = slurp(dir, "moon.vert"), *mf = slurp(dir, "moon.frag");
        char *ext = strstr(mf, "#extension GL_OES_EGL_image_external : require"); if (ext) memset(ext, ' ', strlen("#extension GL_OES_EGL_image_external : require"));
        for (char *t; (t = strstr(mf, "samplerExternalOES")); ) { memcpy(t, "sampler2D         ", 18); }
        GLuint moon = program(mv, mf, 0);
        GLuint mt[3]; glGenTextures(3, mt);
        FILE *vf = fopen(argv[2], "rb"); unsigned char *video = malloc(W * H * 4);
        if (!vf || fread(video, 1, W * H * 4, vf) != W * H * 4) { fprintf(stderr, "moon picture %s\n", argv[2]); return 1; } fclose(vf);
        unsigned char clear[4] = { 0, 0, 0, 0 };
        for (int k = 0; k < 3; k++) {
            glBindTexture(GL_TEXTURE_2D, mt[k]);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            if (k == 0) glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, video);
            else glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, clear);
        }
        /* The moon draws into a plain (not sRGB) surface. */
        GLuint fb2, t2; glGenFramebuffers(1, &fb2); glGenTextures(1, &t2);
        glBindTexture(GL_TEXTURE_2D, t2); glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, W, H);
        glBindFramebuffer(GL_FRAMEBUFFER, fb2); glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t2, 0);
        float quad[] = { -1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1 };
        float ident[16] = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };
        double *g3 = malloc(sizeof(double) * 3000), *w3 = malloc(sizeof(double) * 3000); int n3 = 0;
        for (int i = 0; i < 3000; i++) {
            tick(); double t0 = now();
            /* SceneEngine.draw, call for call (its uniforms looked up by name every frame, as there). */
            glViewport(0, 0, W, H); glUseProgram(moon);
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, mt[0]); glUniform1i(glGetUniformLocation(moon, "uVideo"), 0);
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, mt[1]); glUniform1i(glGetUniformLocation(moon, "uFace"), 1);
            glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, mt[2]); glUniform1i(glGetUniformLocation(moon, "uFront"), 2);
            glUniformMatrix4fv(glGetUniformLocation(moon, "uTex"), 1, GL_FALSE, ident);
            glUniform1f(glGetUniformLocation(moon, "uMatte"), 0); glUniform1f(glGetUniformLocation(moon, "uDepth"), 0);
            glUniform1f(glGetUniformLocation(moon, "uFaceAlpha"), 0); glUniform1f(glGetUniformLocation(moon, "uGain"), 1);
            glUniform2f(glGetUniformLocation(moon, "uCrush"), 7.f / 255, 15.f / 255);
            glUniformMatrix4fv(glGetUniformLocation(moon, "uTexF"), 1, GL_FALSE, ident);
            glUniform3f(glGetUniformLocation(moon, "uCloud"), 0, 0, 0); glUniform2f(glGetUniformLocation(moon, "uGlitch"), 0, 0);
            GLint pos = glGetAttribLocation(moon, "aPos"), uv = glGetAttribLocation(moon, "aUv");
            glVertexAttribPointer(pos, 2, GL_FLOAT, GL_FALSE, 16, quad); glEnableVertexAttribArray(pos);
            glVertexAttribPointer(uv, 2, GL_FLOAT, GL_FALSE, 16, quad + 2); glEnableVertexAttribArray(uv);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glFinish(); double t1 = now(); double g = tock();
            w3[i] = (t1 - t0) * 1e9; if (g >= 0) g3[n3++] = g;
        }
        printf("\nThe moon's pass (no decode), 3000 frames:\n");
        if (n3) stats("GPU per frame", g3, n3);
        stats("wall per frame, calls + glFinish", w3, 3000);
    }
    return 0;
}
