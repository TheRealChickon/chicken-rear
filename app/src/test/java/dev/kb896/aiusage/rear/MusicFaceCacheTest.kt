package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The music page draws the same picture whether what it keeps from frame to frame (the cut title and artist, the
 * cover's shader, the eased colours) is fresh or kept, and a frame on the hardware canvas makes no garbage once the
 * page is up. The cut lines are worked out again only when the words, the room or the size change; the shader again
 * only for another cover or a cover whose pixels changed. Made-up tracks and stand-in covers only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MusicFaceCacheTest {

    /** One picture's inputs. */
    private class Scene(
        val name: String, val w: Int, val h: Int, val title: String, val artist: String, val playing: Boolean,
        val cover: Bitmap?, val icon: Bitmap?, val sinceFlip: Long = Long.MAX_VALUE,
        val pressed: MusicFace.Button? = null, val sincePress: Long = Long.MAX_VALUE,
    )

    private fun flat(w: Int, h: Int, argb: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(argb) }

    /** A stand-in cover with something in it (a block and a stripe), so a wrong scale or place would show. */
    private fun patterned(w: Int, h: Int, a: Int, b: Int) = flat(w, h, a).also { bmp ->
        for (y in 0 until h) for (x in 0 until w) if ((x / 17 + y / 23) % 3 == 0 || x in w / 3 until w / 2) bmp.setPixel(x, y, b)
    }

    private val square = patterned(288, 288, 0xFF1DB954.toInt(), 0xFFE0E0E0.toInt())
    private val wide = patterned(480, 270, 0xFF2060E0.toInt(), 0xFFF0B030.toInt())
    private val icon = flat(256, 256, 0xFF3A6EA5.toInt())

    private val scenes = listOf(
        Scene("long-cut", 976, 596, "A very long made-up title that is far too wide for the room beside the lenses", "Somebody, Somebody Else and a Third Name to cut", true, square, icon),
        Scene("short-paused", 976, 596, "Short", "", false, null, icon),
        Scene("video-flip-press", 976, 596, "A made-up video with a title\nover two lines", "A channel", true, wide, icon, sinceFlip = 180L, pressed = MusicFace.Button.NEXT, sincePress = 140L),
        Scene("emoji-cut", 976, 596, "Made-up 🎶🎶🎶 words that go on and on past the room 👨‍👩‍👧 here", "  spaced  ", true, square, null),
        Scene("half-size", 488, 298, "A very long made-up title that is far too wide for the room beside the lenses", "Somebody, Somebody Else and a Third Name to cut", true, square, icon),
        Scene("nothing", 976, 596, "", "", true, null, null),
    )

    private val body = MusicFace.Body().let { b -> var t = 1_000L; while (t <= 2_000L) { b.step(t, true); t += 16L }; b.frame }

    private fun draw(c: Canvas, s: Scene) = MusicFace.draw(
        c, s.w.toFloat(), s.h.toFloat(), s.title, s.artist, s.playing, s.cover, MusicFace.palette(0xFFE01940.toInt()), body,
        s.sinceFlip, s.pressed, s.sincePress, s.icon,
    )

    private fun software(s: Scene): IntArray {
        val bmp = Bitmap.createBitmap(s.w, s.h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), s)
        return IntArray(s.w * s.h).also { bmp.getPixels(it, 0, s.w, 0, 0, s.w, s.h) }
    }

    @Test fun `a kept line, shader or colour draws the same pixels as a fresh one`() {
        val out = File("build/music-face-cache").apply { mkdirs() }
        val lines = ArrayList<String>()
        // Fresh (each scene follows another), then at once again (all kept), then after the others (fresh again).
        val first = scenes.map { software(it) }
        for ((i, s) in scenes.withIndex()) {
            val kept = software(s)
            assertTrue("${s.name}: the second picture, all kept, is the first", kept.contentEquals(first[i]))
            lines += "${s.name} ${"%08x".format(first[i].contentHashCode())}"
        }
        for ((i, s) in scenes.withIndex().reversed()) assertTrue("${s.name}: drawn again after the others", software(s).contentEquals(first[i]))
        File(out, "software-hashes.txt").writeText(lines.joinToString("\n") + "\n")
        println("music face, software pictures:\n" + lines.joinToString("\n"))
    }

    @Test fun `the same cover with other pixels is drawn with its new pixels`() {
        val cover = flat(288, 288, 0xFF1DB954.toInt())
        val s = Scene("mutable", 976, 596, "A title", "Someone", true, cover, null)
        val before = software(s)
        assertEquals(0x1DB954, before[161 * 976 + 458] and 0xFFFFFF)
        cover.eraseColor(0xFFD03030.toInt())
        val after = software(s)
        assertEquals("the cover's new colour, not the kept shader's old one", 0xD03030, after[161 * 976 + 458] and 0xFFFFFF)
    }

    @Test fun `the hardware canvas gets the same pictures kept and fresh`() {
        val e = runCatching { android.graphics.RuntimeShader(MusicBody.AGSL) }.exceptionOrNull()
        org.junit.Assume.assumeTrue("no shader compiler here: $e", e == null)
        val lines = ArrayList<String>()
        for (s in scenes) {
            val a = runCatching { hardware(s) }.onFailure { println("no hardware drawing here: $it") }.getOrNull()
            org.junit.Assume.assumeTrue("no hardware drawing here", a != null)
            val b = hardware(s)
            assertTrue("${s.name}: hardware, kept against fresh", a!!.contentEquals(b))
            lines += "${s.name} ${"%08x".format(a.contentHashCode())}"
        }
        File("build/music-face-cache").mkdirs()
        File("build/music-face-cache/hardware-hashes.txt").writeText(lines.joinToString("\n") + "\n")
        println("music face, hardware pictures:\n" + lines.joinToString("\n"))
    }

    @Test fun `a frame on the hardware canvas makes no garbage once the page is up`() {
        // The JVM's count of the bytes this thread made (HotSpot's ThreadMXBean; by reflection: the unit tests compile
        // against android.jar, which has no java.lang.management).
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val count = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val mx = object { fun getThreadAllocatedBytes(id: Long) = count.invoke(bean, id) as Long }
        val me = Thread.currentThread().id
        val node = android.graphics.RenderNode("music").apply { setPosition(0, 0, 976, 596) }
        val fade = MusicFace.Fade()
        val colors = MusicFace.palette(0xFFE01940.toInt())
        val b = MusicFace.Body()
        // As the shell does it: one Fade and one Body, asked every frame, the same track. Against the body alone (its
        // shader's native instance is made again whenever a uniform changes, which the platform does with a little
        // garbage of its own: not this page's to keep), what is left is the page's own: colours, cover, words, buttons.
        var t = 10_000L
        fun frame(s: Scene, bodyOnly: Boolean) {
            val c = node.beginRecording()
            if (bodyOnly) MusicBody.draw(c, 976f, 596f, b.step(t, s.playing))
            else MusicFace.draw(c, 976f, 596f, s.title, s.artist, s.playing, s.cover, fade.at(colors, t), b.step(t, s.playing), s.sinceFlip, s.pressed, s.sincePress, s.icon)
            node.endRecording()
            t += 16L
        }
        fun measure(s: Scene, bodyOnly: Boolean): Pair<Long, Double> {
            val n = 300
            var best = Double.MAX_VALUE; var bytes = Long.MAX_VALUE
            repeat(5) {
                val a0 = mx.getThreadAllocatedBytes(me); val t0 = System.nanoTime()
                repeat(n) { frame(s, bodyOnly) }
                best = minOf(best, (System.nanoTime() - t0) / 1000.0 / n)
                bytes = minOf(bytes, (mx.getThreadAllocatedBytes(me) - a0) / n)
            }
            return bytes to best
        }
        for (s in listOf(scenes[0], scenes[2], scenes[1])) {
            repeat(120) { frame(s, false) }                     // the page comes up; its colours settle
            val (bodyBytes, bodyUs) = measure(s, true)
            val (bytes, us) = measure(s, false)
            val own = bytes - bodyBytes
            println("music face, ${s.name}: the page's own %d bytes a frame (%d with the body, %d the body alone); %.1f µs a frame to record, %.1f of them the page's own".format(own, bytes, bodyBytes, us, us - bodyUs))
            assertTrue("${s.name}: the page makes $own bytes a frame", own <= ALLOWED_BYTES)
        }
    }

    @Test fun `the colours settled, the fade gives the same palette and makes nothing`() {
        val fade = MusicFace.Fade()
        val a = MusicFace.palette(0xFF2060E0.toInt()); val b = MusicFace.palette(0xFFE04020.toInt())
        var t = 1_000L
        fade.at(a, t)
        repeat(600) { t += 16L; fade.at(b, t) }
        val settled = fade.at(b, t + 16L)
        assertEquals(b, settled)
        assertTrue("the same object while nothing changes", settled === fade.at(b, t + 32L))
        // A palette equal to the last but another object (a new track with the same colours) changes nothing either.
        assertEquals(b, fade.at(b.copy(), t + 48L))
        // And a new one is followed again.
        val moved = fade.at(a, t + 64L)
        assertTrue("on its way to the next", moved != b)
        assertTrue(Color.red(moved.colA) != Color.red(b.colA) || Color.green(moved.colA) != Color.green(b.colA) || Color.blue(moved.colA) != Color.blue(b.colA))
    }

    private fun hardware(s: Scene): IntArray {
        val reader = android.media.ImageReader.newInstance(s.w, s.h, android.graphics.PixelFormat.RGBA_8888, 1,
            android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val node = android.graphics.RenderNode("music").apply { setPosition(0, 0, s.w, s.h) }
        val rc = node.beginRecording()
        draw(rc, s)
        node.endRecording()
        val renderer = android.graphics.HardwareRenderer()
        renderer.setSurface(reader.surface); renderer.setContentRoot(node)
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = reader.acquireNextImage()
        val plane = image.planes[0]
        val buf = plane.buffer
        val out = IntArray(s.w * s.h)
        for (y in 0 until s.h) for (x in 0 until s.w) {
            val i = y * plane.rowStride + x * plane.pixelStride
            out[y * s.w + x] = Color.argb(buf.get(i + 3).toInt() and 0xFF, buf.get(i + 2).toInt() and 0xFF, buf.get(i + 1).toInt() and 0xFF, buf.get(i).toInt() and 0xFF)
        }
        image.close(); renderer.destroy(); reader.close()
        return out
    }

    companion object {
        const val ALLOWED_BYTES = 64L
    }
}
