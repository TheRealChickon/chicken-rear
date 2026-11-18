package dev.kb896.aiusage.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.view.WindowManager
import dev.kb896.aiusage.rear.EarbudsMotion
import dev.kb896.aiusage.rear.EarbudsEdge
import dev.kb896.aiusage.service.EarbudsGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** The earbuds' edge light on the front: it never takes a touch or focus, it is light only, and the shader is the timeline's formula. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EarbudsOverlayTest {
    @Test fun `both ways onto the front take no touch and no focus`() {
        for (way in listOf(EarbudsGate.Way.GLASS_SERVICE, EarbudsGate.Way.APP_OVERLAY)) {
            val lp = EarbudsOverlay.params(way)
            assertTrue("$way not touchable", lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue("$way not focusable", lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertEquals("no blur of the page: light only", 0, lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            // the whole screen: the edge light begins at the bottom edge itself, not over the navigation bar's inset
            assertEquals("$way fits no insets", 0, lp.fitInsetsTypes)
            assertEquals(0f, lp.preferredRefreshRate, 0f)
            assertEquals(120f, EarbudsOverlay.params(way, 120f).preferredRefreshRate, 0f)
        }
        // the service's window is an accessibility overlay: trusted, so touches pass at full opacity
        val glass = EarbudsOverlay.params(EarbudsGate.Way.GLASS_SERVICE)
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, glass.type)
        assertEquals(1f, glass.alpha, 0f)
        // an app's overlay must stay at or under Android's maximum obscuring opacity, or every touch under it is dropped
        val app = EarbudsOverlay.params(EarbudsGate.Way.APP_OVERLAY)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, app.type)
        assertTrue(app.alpha <= 0.8f)
    }

    @Test fun `the front is the edge light alone, not put on the screen the pin beam's way`() {
        val src = File("src/main/java/dev/kb896/aiusage/ui/EarbudsOverlay.kt").readText()
        assertFalse(src.contains("SurfaceControlViewHost("))
        assertFalse(src.contains("attachAccessibilityOverlayToDisplay("))
        for (gone in listOf("drawBitmap(", "drawText(", "drawRoundRect(", "Vibrat", "BlurMaskFilter", "blurBehind")) assertFalse(gone, src.contains(gone))
        assertTrue(EarbudsOverlay.LONGEST_MS > EarbudsEdge.RUN_MS)
    }

    @Test fun `the shader compiles and takes every uniform the view sets`() {
        val e = runCatching { RuntimeShader(EarbudsEdge.AGSL) }.exceptionOrNull()
        org.junit.Assume.assumeTrue("no shader compiler here: $e", e == null || e is IllegalArgumentException)
        assertNull("the compiler: ${e?.message}", e)
        val sh = RuntimeShader(EarbudsEdge.AGSL)
        EarbudsEdgeView.Uniforms().apply {
            geometry(sh, EarbudsEdge.Geometry(1200f, 2608f, 170f, 170f, 170f, 170f))
            frame(sh, EarbudsEdge.at(400f), 1f)
        }   // a name the program lacks throws
    }

    /** Native AGSL render vs pure Kotlin. Constants work around Robolectric not forwarding runtime uniforms. */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `the shader and edge strips draw the Kotlin formula on both backgrounds and orientations`() {
        val out = File("build/earbuds-siri-shader").apply { mkdirs() }
        for ((w, h) in listOf(300 to 652, 652 to 300)) {
            val k = minOf(w, h) / 1200f
            val g = EarbudsEdge.Geometry(w.toFloat(), h.toFloat(), 170f*k, 150f*k, 190f*k, 130f*k)
            for (t in listOf(-20f, 0f, 120f, 300f, 700f, 1100f, 1350f, 1550f, 1700f, 1750f, 1800f)) {
                val f = EarbudsEdge.at(t)
                fun v(x: Float) = "%.8f".format(java.util.Locale.ROOT, x)
                val src = EarbudsEdge.AGSL
                    .replace("uniform float2 uSize;", "const float2 uSize = float2(${v(g.w)}, ${v(g.h)});")
                    .replace("uniform float4 uCorners;", "const float4 uCorners = float4(${v(g.tl)}, ${v(g.tr)}, ${v(g.br)}, ${v(g.bl)});")
                    .replace("uniform float4 uFrame;", "const float4 uFrame = float4(${v(f.time)}, ${v(f.amp)}, ${v(f.spread)}, ${v(k)});")
                check(!src.contains("uniform"))
                val paint = Paint().apply { shader = RuntimeShader(src) }
                for (ground in listOf(Color.BLACK, Color.WHITE)) {
                    val bmp = hardwareDraw(w, h) { c ->
                        c.drawColor(ground)
                        EarbudsEdgeView.drawBand(c, g, paint)
                    }
                    val bg = if (ground == Color.WHITE) 1f else 0f
                    File(out, "${w}x${h}-${if (bg > 0) "light" else "dark"}-${t.toInt()}ms.png").outputStream().use {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    val px = FloatArray(4); val scratch = FloatArray(3)
                    var worst = 0; var where = ""
                    for (y in 0 until h step 2) for (x in 0 until w step 2) {
                        EarbudsEdge.light(f, g, x + 0.5f, y + 0.5f, px, scratch)
                        val got = bmp.getPixel(x, y)
                        val want = IntArray(3) { i -> Math.round(255f * (px[i] + bg * (1f - px[3]))) }
                        val delta = maxOf(abs(want[0] - Color.red(got)), abs(want[1] - Color.green(got)), abs(want[2] - Color.blue(got)))
                        if (delta > worst) { worst = delta; where = "($x,$y), $t ms, $w x $h, background $bg" }
                    }
                    assertTrue("$worst levels at $where", worst <= 2)
                    assertEquals("untouched centre", ground, bmp.getPixel(w/2,h/2))
                    if (t <= 0f || t >= EarbudsEdge.RUN_MS) for (y in 0 until h step 4) for (x in 0 until w step 4) assertEquals(ground, bmp.getPixel(x,y))
                }
            }
        }
    }

    private fun hardwareDraw(w: Int, h: Int, draw: (Canvas) -> Unit): Bitmap {
        val reader = android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 1,
            android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val node = android.graphics.RenderNode("edge light").apply { setPosition(0, 0, w, h) }
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
            // (Robolectric's reader gives the bytes blue, green, red, alpha, as in MusicFaceTest.)
            out.setPixel(x, y, Color.argb(buf.get(i + 3).toInt() and 0xFF, buf.get(i + 2).toInt() and 0xFF, buf.get(i + 1).toInt() and 0xFF, buf.get(i).toInt() and 0xFF))
        }
        image.close(); renderer.destroy(); reader.close()
        return out
    }

    /** The back's moment at the storyboard's moments (frames beside the front's), and nothing left at its end. */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `the back's moment is drawn in the middle of what is seen and is gone at its end`() {
        val out = File("build/earbuds-frames").apply { mkdirs() }
        val picture = System.getenv("EARBUDS_PICTURE")?.let { File(it) }?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        val rear = dev.kb896.aiusage.rear.RearGeometry(976, 596, 296, 110, 450)
        val shell = dev.kb896.aiusage.rear.ShellEarbuds({ 0 }, { rear }, android.os.Handler(android.os.Looper.getMainLooper()))
        fun frame(t: Long): Bitmap {
            val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.drawColor(Color.rgb(20, 22, 26))
            shell.render(c, rear, t.toFloat(), picture, 82)
            File(out, "back-%04d.png".format(t)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return bmp
        }
        val ground = frame(1000).getPixel(800, 300)
        for (t in listOf(150L, 350L, 650L, 1250L, 2250L).map { EarbudsMotion.BACK_AT + it }) frame(t)
        // the figure stands under the middle of what is seen (x 636), not of the whole panel
        val lit = frame(EarbudsMotion.BACK_AT + 1250L)
        var left = 976; var right = 0
        for (x in 296 until 976) for (y in 440 until 500) if (Color.red(lit.getPixel(x, y)) > 200) { left = minOf(left, x); right = maxOf(right, x) }
        assertTrue("figure found", right > left)
        assertEquals(636f, (left + right) / 2f, 12f)
        // at its end the back is as it was (the dim gone to within a few levels)
        val end = frame(EarbudsMotion.BACK_END_MS - 1).getPixel(800, 300)
        assertEquals(Color.red(ground).toFloat(), Color.red(end).toFloat(), 3f)
    }
}
