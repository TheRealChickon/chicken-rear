package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The back's pinned pictures: never a wait on a drawing thread, a few in memory, a missing file not asked for again at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotePicturesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The reads, held until the test runs them (the shell's own thread does this). */
    private val queue = ArrayList<Runnable>()
    private fun runAll() { while (queue.isNotEmpty()) queue.removeAt(0).run() }

    private fun file(name: String, w: Int = 40, h: Int = 30) {
        File(tmp.root, name).outputStream().use { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF00FF00.toInt()) }.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    @Test fun `a picture not in memory is null at once and read off the drawing thread, once, then there`() {
        file("1-40x30.jpg")
        var readies = 0
        val p = NotePictures(tmp.root.path, ready = { readies++ }, work = { queue += it })
        assertNull(p.get("1-40x30.jpg"))
        assertNull(p.get("1-40x30.jpg"))
        assertEquals("asked for once", 1, queue.size)
        assertFalse(p.has("1-40x30.jpg"))
        runAll()
        assertEquals(1, readies)
        val b = p.get("1-40x30.jpg")
        assertNotNull(b); assertEquals(40, b!!.width)
        // Not a name of ours: never read.
        assertNull(p.get("../../secret.jpg")); assertNull(p.get(null))
        assertTrue(queue.isEmpty())
    }

    @Test fun `a few at most in memory, the longest unused letting go first`() {
        for (i in 1..5) file("$i-40x30.jpg")
        val p = NotePictures(tmp.root.path, ready = {}, most = 3, work = { queue += it })
        p.warm(listOf("1-40x30.jpg", "2-40x30.jpg", "3-40x30.jpg")); runAll()
        assertEquals(3, p.size())
        p.get("1-40x30.jpg")                  // the one showing, just used
        p.warm(listOf("4-40x30.jpg", null)); runAll()
        assertEquals(3, p.size())
        assertTrue(p.has("1-40x30.jpg")); assertFalse(p.has("2-40x30.jpg")); assertTrue(p.has("4-40x30.jpg"))
        p.forget("4-40x30.jpg")
        assertFalse(p.has("4-40x30.jpg"))
    }

    @Test fun `a file that is not there is not asked for again for a while`() {
        var now = 1_000L
        val p = NotePictures(tmp.root.path, ready = {}, work = { queue += it }, clock = { now })
        assertNull(p.get("9-40x30.jpg")); runAll()
        assertNull(p.get("9-40x30.jpg"))
        assertTrue("not asked again at once", queue.isEmpty())
        now += NotePictures.MISSING_MS
        file("9-40x30.jpg")
        assertNull(p.get("9-40x30.jpg")); runAll()
        assertNotNull(p.get("9-40x30.jpg"))
    }

    @Test fun `a picture a carousel card held comes back into memory as the last used, nothing read`() {
        for (i in 1..4) file("$i-40x30.jpg")
        val p = NotePictures(tmp.root.path, ready = {}, most = 3, work = { queue += it })
        p.warm(listOf("1-40x30.jpg")); runAll()
        val one = p.get("1-40x30.jpg")!!
        // The carousel shows more notes than the few in memory: this one let go meanwhile.
        p.warm(listOf("2-40x30.jpg", "3-40x30.jpg", "4-40x30.jpg")); runAll()
        assertFalse(p.has("1-40x30.jpg"))
        p.keep("1-40x30.jpg", one)
        assertTrue("nothing read", queue.isEmpty())
        assertSame(one, p.get("1-40x30.jpg"))
        assertEquals(3, p.size())
        assertFalse("the longest unused went for it", p.has("2-40x30.jpg"))
        // Never a name not ours, nor nothing.
        p.keep("../x.jpg", one); p.keep(null, one); p.keep("5-40x30.jpg", null)
        assertFalse(p.has("../x.jpg")); assertFalse(p.has("5-40x30.jpg"))
        assertEquals(3, p.size())
    }

    @Test fun `a picture read while the notes page is pulled up comes onto it under the finger, not at the lift`() {
        // ShellPage.peek draws a page once as the finger takes it; a note's picture read after that draws it again
        // (the ready call: pinsPage.changed) while it is up and also while it is under the finger on its way in.
        assertTrue(ShellPage.drawnWhenChanged(isUp = false, peeking = true))
        assertTrue(ShellPage.drawnWhenChanged(isUp = true, peeking = false))
        assertTrue(ShellPage.drawnWhenChanged(isUp = true, peeking = true))
        assertFalse("gone, or let go on its way out", ShellPage.drawnWhenChanged(isUp = false, peeking = false))
        val src = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val page = File(src, "dev/kb896/aiusage/rear/ShellPage.kt").readText()
        assertTrue(Regex("""fun changed\(\) = handler\.post \{ if \(drawnWhenChanged\(isUp, peeking\)\) draw\(\) \}""").containsMatchIn(page))
        val service = File(src, "dev/kb896/aiusage/rear/RearUserService.kt").readText()
        assertTrue("a picture read draws the notes page again", Regex("""NotePictures\(PINS_DIR, ready = \{ musicHandler\.post \{ pinsPage\.changed\(\)""").containsMatchIn(service))
    }

    @Test fun `a file far larger than a kept picture is read smaller`() {
        file("2-2000x1600.jpg", 2000, 1600)
        val b = NotePictures.decode(File(tmp.root, "2-2000x1600.jpg"))!!
        assertTrue(b.width <= 2 * dev.kb896.aiusage.data.PinImage.MAX_W && b.height <= 2 * dev.kb896.aiusage.data.PinImage.MAX_H)
        assertNull(NotePictures.decode(File(tmp.root, "none.jpg")))
    }
}
