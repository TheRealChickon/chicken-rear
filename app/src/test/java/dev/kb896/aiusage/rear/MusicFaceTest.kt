package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.kb896.aiusage.color.mcu.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The music page: Xiaomi's measures, the colours taken from the cover as Xiaomi takes them, the wiring of the level,
 * the picture. The body's motion and light are MusicBodyTest's; the sound's arithmetic is MusicSoundTest's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MusicFaceTest {

    @Test fun `the three buttons stand along the bottom where Xiaomi's do`() {
        assertEquals(402.75f, MusicFace.centre(MusicFace.Button.PREVIOUS), 0.2f)
        assertEquals(626.35f, MusicFace.centre(MusicFace.Button.PLAY_PAUSE), 0.2f)
        assertEquals(858.65f, MusicFace.centre(MusicFace.Button.NEXT), 0.2f)
        assertEquals(MusicFace.Button.PREVIOUS, MusicFace.button(400f, 530f))
        assertEquals(MusicFace.Button.PLAY_PAUSE, MusicFace.button(630f, 500f))
        assertEquals(MusicFace.Button.NEXT, MusicFace.button(880f, 590f))
        assertNull("between two", MusicFace.button(515f, 530f))
        assertNull("on the title", MusicFace.button(630f, 350f))
        assertNull("behind the lenses", MusicFace.button(150f, 530f))
    }

    // -- the colours, Xiaomi's way (SPEC.md 5 and 8.11) --

    private val red = Hct.from(17.0, 90.0, 50.0).toInt()
    private val purple = Hct.from(295.0, 20.0, 10.0).toInt()

    /** A made-up 64 x 64 cover: [purpleShare] dark purple, [redShare] a red patch, the rest near black. Nobody's cover. */
    private fun madeUpCover(purpleShare: Double, redShare: Double): IntArray {
        val nr = Math.round(redShare * 4096).toInt(); val np = Math.round(purpleShare * 4096).toInt()
        return IntArray(4096) { i -> if (i < nr) red else if (i < nr + np) purple else 0xFF080808.toInt() }
    }

    @Test fun `the seed is the cover's most prominent colour as Score finds it - chroma can outweigh area, but not any area`() {
        fun hue(px: IntArray) = Hct.fromInt(MusicFace.seedOf(px)).hue
        val wins = madeUpCover(0.40, 0.20)
        assertTrue("a 20 % red patch over 40 % dark purple: ${hue(wins)}", kotlin.math.abs(hue(wins) - 17.0) <= 5.0)
        val loses = madeUpCover(0.70, 0.15)
        assertTrue("a 15 % red patch over 70 % dark purple is the purple, as Material's Score has it: ${hue(loses)}", kotlin.math.abs(hue(loses) - 295.0) <= 5.0)
        // The same through a Bitmap, as the shell takes it.
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { it.setPixels(wins, 0, 64, 0, 0, 64, 64) }
        assertTrue(kotlin.math.abs(Hct.fromInt(MusicFace.seed(bmp)).hue - 17.0) <= 5.0)
    }

    @Test fun `A at tone 60, B the same 20 degrees lower, the ground at tone 4`() {
        for (seed in listOf(0xFFE01940.toInt(), 0xFFFEEC02.toInt(), 0xFF1DB954.toInt(), 0xFF2060E0.toInt(), 0xFF8E44AD.toInt())) {
            val p = MusicFace.palette(seed)
            val a = Hct.fromInt(p.colA); val b = Hct.fromInt(p.colB); val g = Hct.fromInt(p.ground)
            assertEquals("A's tone", 60.0, a.tone, 0.5); assertEquals("B's tone", 60.0, b.tone, 0.5); assertEquals("the ground's tone", 4.0, g.tone, 0.5)
            val want = (a.hue - 20.0 + 360.0) % 360.0
            val off = kotlin.math.abs(((b.hue - want + 540.0) % 360.0) - 180.0)
            assertTrue("B's hue ${b.hue} against A's ${a.hue} minus 20", off <= 2.0)
            val seedHue = Hct.fromInt(seed).hue
            assertTrue("A has the seed's hue: ${a.hue} against $seedHue", kotlin.math.abs(((a.hue - seedHue + 540.0) % 360.0) - 180.0) <= 2.0)
        }
    }

    @Test fun `his two covers' seeds give the colours checked against Xiaomi's card`() {
        fun near(want: Int, got: Int) = maxOf(kotlin.math.abs(Color.red(want) - Color.red(got)), kotlin.math.abs(Color.green(want) - Color.green(got)), kotlin.math.abs(Color.blue(want) - Color.blue(got)))
        val one = MusicFace.palette(0xFFE01940.toInt()); val two = MusicFace.palette(0xFFFEEC02.toInt())
        assertTrue("%08X".format(one.colA), near(0xFFFF5262.toInt(), one.colA) <= 2); assertTrue("%08X".format(one.colB), near(0xFFFF46A0.toInt(), one.colB) <= 2)
        assertTrue("%08X".format(two.colA), near(0xFF9F9300.toInt(), two.colA) <= 2); assertTrue("%08X".format(two.colB), near(0xFFB88900.toInt(), two.colB) <= 2)
    }

    @Test fun `a grey cover gives greys and no cover the seed 0 - never a made-up hue`() {
        val grey = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF7A7A7A.toInt()) }
        assertEquals(0, MusicFace.seed(grey)); assertEquals(0, MusicFace.seed(null))
        val p = MusicFace.palette(0)
        for (c in listOf(p.ground, p.colA, p.colB)) {
            assertTrue(maxOf(Color.red(c), Color.green(c), Color.blue(c)) - minOf(Color.red(c), Color.green(c), Color.blue(c)) <= 1)
        }
        assertEquals(4.0, Hct.fromInt(p.ground).tone, 0.5); assertEquals(60.0, Hct.fromInt(p.colA).tone, 0.5)
    }

    @Test fun `the colours are taken once for one picture, and again for another`() {
        val a = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFFE01940.toInt()) }
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF2060E0.toInt()) }
        val pa = MusicFace.colorsOf(a)
        assertTrue(pa === MusicFace.colorsOf(a))
        assertTrue(MusicFace.colorsOf(b) != pa)
    }

    @Test fun `a new cover's colours come in over half a second, not at once - all three of them`() {
        val fade = MusicFace.Fade()
        val a = MusicFace.palette(0xFF2060E0.toInt()); val b = MusicFace.palette(0xFFE04020.toInt())
        assertEquals(a, fade.at(a, 1_000L))
        var t = 1_016L; var prev = a; var worst = 0
        while (t < 4_000L) {
            val now = fade.at(b, t)
            for ((x, y) in listOf(now.ground to prev.ground, now.colA to prev.colA, now.colB to prev.colB)) {
                worst = maxOf(worst, kotlin.math.abs(Color.red(x) - Color.red(y)), kotlin.math.abs(Color.green(x) - Color.green(y)), kotlin.math.abs(Color.blue(x) - Color.blue(y)))
            }
            prev = now; t += 16L
        }
        assertTrue("no picture changes a colour by more than a few steps: $worst", worst <= 12)
        assertEquals(b, prev)
    }

    // -- the wiring (SPEC.md 2.6, 8.12) --

    @Test fun `the back lit again calls each wake listener once, and MusicLevel registers once`() {
        var one = 0; var two = 0
        val l1: () -> Unit = { one++ }; val l2: () -> Unit = { two++ }
        val awake = dev.kb896.aiusage.service.PanelAwake
        awake.addWakeListener(l1); awake.addWakeListener(l1); awake.addWakeListener(l2)
        awake.setDozing(true); awake.setDozing(false)
        assertEquals(1, one); assertEquals(1, two)
        awake.setDozing(false)
        assertEquals("awake to awake is no wake", 1, one)
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val before = awake.wakeListenerCount()
        dev.kb896.aiusage.service.MusicLevel.listenForWake(app)
        val once = awake.wakeListenerCount()
        assertTrue("MusicLevel's listener is there ($once after $before; another test may have registered it already)", once - before in 0..1 && once >= 3)
        dev.kb896.aiusage.service.MusicLevel.listenForWake(app); dev.kb896.aiusage.service.MusicLevel.listenForWake(app)
        assertEquals("a second registration adds no second call", once, awake.wakeListenerCount())
    }

    @Test fun `the body's shader is valid AGSL where the test runtime can compile one`() {
        val e = runCatching { android.graphics.RuntimeShader(MusicBody.AGSL) }.exceptionOrNull()
        // A compile error is an IllegalArgumentException with the compiler's words; a runtime with no shader compiler
        // at all is something else, and says nothing about the program.
        org.junit.Assume.assumeTrue("no shader compiler here: $e", e == null || e is IllegalArgumentException)
        assertNull("the compiler: ${e?.message}", e)
    }

    @Test fun `the shader draws what the Kotlin formula says, pixel for pixel`() {
        // Robolectric's renderer runs AGSL but passes no uniforms (all read 0 there; a uniform-free shader draws right),
        // so the frame's values are written into the program as constants. On the phone the uniforms are set as
        // TableGlow's are; what this holds is that the program and MusicBody's Kotlin are the same formula.
        fun baked(f: MusicBody.Frame): String {
            fun v(x: Float) = "%.6f".format(java.util.Locale.ROOT, x)
            fun v3(c: FloatArray) = "float3(${v(c[0])}, ${v(c[1])}, ${v(c[2])})"
            fun v4(i: Int) = "float4(${v(f.bands[i])}, ${v(f.bands[i + 1])}, ${v(f.bands[i + 2])}, ${v(f.bands[i + 3])})"
            val consts = "const float u = 1.0; const float base = ${v(f.base)}; const float peak = ${v(f.peak)}; const float kickAmp = ${v(f.kickAmp)}; " +
                "const float breath = ${v(f.breath)}; const float wave = ${v(f.wave)}; const float wph = ${v(f.wph)}; " +
                "const float limit = ${v(f.limit)}; const float soft = ${v(f.soft)}; const float G = ${v(f.g)}; " +
                "const float glow = ${v(f.glow)}; const float tilt = ${v(f.tilt)}; const float shift = ${v(f.shift)};\n" +
                "const float4 b0 = ${v4(0)}; const float4 b1 = ${v4(4)}; const float4 b2 = ${v4(8)};\n" +
                "const float3 ground = ${v3(f.ground)}; const float3 colA = ${v3(f.colA)}; const float3 colB = ${v3(f.colB)};"
            val src = MusicBody.AGSL.replace(Regex("uniform float u,[^\\n]*\\n"), "").replace(Regex("uniform float4 b0[^\\n]*\\n"), "")
                .replace(Regex("uniform float3 ground[^\\n]*"), consts)
            check(!src.contains("uniform")) { src }
            return src
        }
        val e = runCatching { android.graphics.RuntimeShader(MusicBody.AGSL) }.exceptionOrNull()
        org.junit.Assume.assumeTrue("no shader compiler here: $e", e == null)
        // A drop with a kick in it, in his first cover's colours; and the body at rest, in the second's.
        val drop = MusicFace.Body(); var t = 1_000L
        while (t < 6_000L) {
            if (t % 33L < 16L) drop.hear(FloatArray(dev.kb896.aiusage.service.MusicSound.PACKET) { i ->
                when { i == 0 -> 1f; i == 1 -> if (t in 5_900L..5_933L) 0.9f else 0f; i == 2 -> 1f; i >= dev.kb896.aiusage.service.MusicSound.BAND0 -> ((i * 37) % 10) / 10f; else -> 0f }
            }, t)
            drop.step(t, true); t += 16L
        }
        val rest = MusicFace.Body().also { it.step(1_000L, false) }
        for ((f, seed) in listOf(drop.step(t, true) to 0xFFE01940.toInt(), rest.frame to 0xFFFEEC02.toInt())) {
            val p = MusicFace.palette(seed); f.setColors(p.ground, p.colA, p.colB)
            val sh = android.graphics.RuntimeShader(baked(f))
            val bmp = runCatching { hardwareDraw(976, 596) { c -> c.drawPaint(android.graphics.Paint().apply { shader = sh }) } }
                .onFailure { println("no hardware drawing here: $it") }.getOrNull()
            org.junit.Assume.assumeTrue("no hardware drawing here", bmp != null)
            val px = FloatArray(3); var worst = 0; var at = ""
            for (y in 0 until 596 step 7) for (x in 0 until 976 step 9) {
                MusicBody.linear(f, x + 0.5f, y + 0.5f, px)
                val want = MusicBody.srgb(px[0], px[1], px[2]); val got = bmp!!.getPixel(x, y)
                val d = maxOf(kotlin.math.abs(Color.red(want) - Color.red(got)), kotlin.math.abs(Color.green(want) - Color.green(got)), kotlin.math.abs(Color.blue(want) - Color.blue(got)))
                if (d > worst) { worst = d; at = "($x, $y): Kotlin %06X, shader %06X".format(want and 0xFFFFFF, got and 0xFFFFFF) }
            }
            // The shader's half-level dither and its half floats: two levels at most.
            assertTrue("the largest difference is $worst levels at $at", worst <= 2)
        }
    }

    @Test fun `the page is drawn, full bleed, with and without a cover`() {
        File("build").mkdirs()
        // A stand-in for a cover, for this test only: on the phone the cover is the player's own picture.
        val cover = Bitmap.createBitmap(288, 288, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(0xFF1DB954.toInt()) }
        val icon = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(0xFF3A6EA5.toInt()) }
        // The body a second into playing, with no sound told to it.
        val body = MusicFace.Body().let { b -> var t = 1_000L; while (t <= 2_000L) { b.step(t, true); t += 16L }; b.frame }
        for ((name, art) in mapOf("cover" to cover, "none" to null)) for (playing in listOf(true, false)) {
            val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
            MusicFace.draw(Canvas(bmp), 976f, 596f, "Who Shot Johnny", "Rosa Walton, Hallie Coggins", playing, art, MusicFace.palette(MusicFace.seed(art ?: icon)), body, playerIcon = icon)
            // The cover's place holds the cover, or without one the player's icon: never something drawn by us.
            assertEquals("$name: what stands in the cover's place", (if (art != null) 0x1DB954 else 0x3A6EA5), bmp.getPixel(458, 161) and 0xFFFFFF)
            File("build/music-$name-${if (playing) "playing" else "paused"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals("opaque behind the lenses too", 255, Color.alpha(bmp.getPixel(20, 20)))
            assertEquals("and in the far corner", 255, Color.alpha(bmp.getPixel(970, 590)))
            assertTrue("dark at the top", Color.green(bmp.getPixel(600, 10)) < 40)
            assertEquals("the title's white", 0xFFFFFF, bmp.getPixel(374, 352) and 0xFFFFFF or findWhite(bmp))
            // The body is lit along the foot, behind the lenses too, one colour body over the ground.
            val ground = MusicFace.palette(MusicFace.seed(art ?: icon)).ground
            for (x in listOf(20, 300, 636, 960)) assertTrue("lit at the foot, x $x", Color.green(bmp.getPixel(x, 590)) + Color.red(bmp.getPixel(x, 590)) > Color.green(ground) + Color.red(ground) + 40)
        }
    }

    private fun hardwareDraw(w: Int, h: Int, draw: (Canvas) -> Unit): Bitmap {
        val reader = android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 1,
            android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val node = android.graphics.RenderNode("music").apply { setPosition(0, 0, w, h) }
        val rc = node.beginRecording()
        assertTrue(rc.isHardwareAccelerated)
        draw(rc)
        node.endRecording()
        val renderer = android.graphics.HardwareRenderer()
        renderer.setSurface(reader.surface); renderer.setContentRoot(node)
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = reader.acquireNextImage()
        val plane = image.planes[0]
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val buf = plane.buffer
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * plane.rowStride + x * plane.pixelStride
            // (Robolectric's reader gives the bytes blue, green, red, alpha: a flat 0x3366CC read back as 0xCC6633 the other way.)
            out.setPixel(x, y, Color.argb(buf.get(i + 3).toInt() and 0xFF, buf.get(i + 2).toInt() and 0xFF, buf.get(i + 1).toInt() and 0xFF, buf.get(i).toInt() and 0xFF))
        }
        image.close(); renderer.destroy(); reader.close()
        return out
    }

    /** White somewhere along the title's line. */
    private fun findWhite(bmp: Bitmap): Int {
        for (x in 362 until 860) if (bmp.getPixel(x, 348) and 0xFFFFFF == 0xFFFFFF) return 0xFFFFFF
        return 0
    }
}
