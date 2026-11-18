package dev.kb896.aiusage.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.place.Here
import dev.kb896.aiusage.place.PinList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.launch
import java.io.File

/**
 * The pinned pictures' files: read at their stored size, kept, read back, deleted with their notes (whatever takes a
 * note away passes ConfigStore.save), and swept when no note has them. Made-up pictures only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinImageFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir get() = PinImageFiles.dir(context)!!

    @Before fun clean() { dir.deleteRecursively(); ConfigStore(context).save(PanelConfig.DEFAULT) }

    /** A made-up picture: left half [left], right half [right]. */
    private fun picture(w: Int, h: Int, left: Int = Color.RED, right: Int = Color.BLUE, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap =
        Bitmap.createBitmap(w, h, config).also { b ->
            val c = Canvas(b); val p = Paint()
            p.color = left; c.drawRect(0f, 0f, w / 2f, h.toFloat(), p)
            p.color = right; c.drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), p)
        }

    private fun near(a: Int, b: Int, by: Int = 40) =
        kotlin.math.abs(Color.red(a) - Color.red(b)) <= by && kotlin.math.abs(Color.green(a) - Color.green(b)) <= by && kotlin.math.abs(Color.blue(a) - Color.blue(b)) <= by

    @Test fun `a big picture is decoded straight to its stored size and kept as a JPEG under its name`() {
        val big = File(context.cacheDir, "big.png")
        big.outputStream().use { picture(2400, 1800).compress(Bitmap.CompressFormat.PNG, 100, it) }
        // (Read from its bytes: this test's runtime decodes no file by its path; the phone reads the shared address.)
        val decoded = PinImageFiles.decode(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(big.readBytes())))
        assertEquals(478, decoded.width); assertEquals(359, decoded.height)
        val kept = PinImageFiles.save(context, picture(478, 359).let { b -> b.setHasAlpha(false); b }, 1_000L)
        assertEquals("1000-478x359.jpg", kept.name); assertEquals(1_000L, kept.at)
        val f = File(dir, kept.name)
        assertTrue(f.isFile)
        // Not half written: no part file left.
        assertFalse(File(dir, kept.name + ".part").exists())
        val back = PinImageFiles.read(context, kept.name)!!
        assertEquals(478, back.width); assertEquals(359, back.height)
        assertTrue(near(back.getPixel(60, 180), Color.RED)); assertTrue(near(back.getPixel(420, 180), Color.BLUE))
        // The name taken: the next millisecond.
        val again = PinImageFiles.save(context, picture(10, 10).let { b -> b.setHasAlpha(false); b }, 1_000L)
        assertEquals("1001-10x10.jpg", again.name)
        // A thumbnail is read smaller.
        assertTrue(PinImageFiles.read(context, kept.name, most = 120)!!.width <= 240)
        // Nothing outside the folder is ever read by name.
        assertNull(PinImageFiles.read(context, "../big.png"))
    }

    @Test fun `a picture read for a screen that went meanwhile is deleted, never left with no note`() = kotlinx.coroutines.runBlocking<Unit> {
        val started = java.util.concurrent.CountDownLatch(1); val go = java.util.concurrent.CountDownLatch(1)
        var written: String? = null
        // The place prompt's read: the prompt is closed (its coroutines cancelled) while the picture is still being read.
        // (Started at once, as the screen's own scope on the main thread starts it.)
        val screen = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            PinImageFiles.readOrDrop(kotlinx.coroutines.Dispatchers.IO, {
                started.countDown(); go.await(5, java.util.concurrent.TimeUnit.SECONDS)
                PinImageFiles.save(context, picture(40, 30).also { it.setHasAlpha(false) }, 3_000L).also { written = it.name }
            }) { PinImageFiles.forget(context, listOf(it.name)) }
            org.junit.Assert.fail("a closed screen goes no further")
        }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        screen.cancel()
        go.countDown()
        screen.join()
        assertEquals("the read was not cut short", "3000-40x30.jpg", written)
        assertFalse("its file went with the screen", File(dir, "3000-40x30.jpg").exists())
        assertTrue(dir.list().orEmpty().none { it.startsWith("3000") })
        // A screen still there keeps what was read, and nothing is dropped.
        val kept = PinImageFiles.readOrDrop(kotlinx.coroutines.Dispatchers.IO, { PinImageFiles.save(context, picture(40, 30).also { it.setHasAlpha(false) }, 4_000L) }) { org.junit.Assert.fail("not dropped") }
        assertEquals("4000-40x30.jpg", kept!!.name)
        assertTrue(File(dir, kept.name).isFile)
        // Nothing read: nothing to drop.
        assertNull(PinImageFiles.readOrDrop(kotlinx.coroutines.Dispatchers.IO, { null as PinImageFiles.Imported? }) { org.junit.Assert.fail("nothing to drop") })
    }

    @Test fun `a picture with see-through parts is kept as WebP, and keeps them`() {
        val clear = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).also { Canvas(it).drawRect(0f, 0f, 20f, 20f, Paint().apply { color = Color.RED }) }
        val kept = PinImageFiles.save(context, clear, 2_000L)
        assertEquals("2000-40x20.webp", kept.name)
        val back = PinImageFiles.read(context, kept.name)!!
        assertTrue(Color.alpha(back.getPixel(35, 10)) < 40)
        assertTrue(Color.alpha(back.getPixel(5, 10)) > 200)
    }

    @Test fun `the picture's own orientation is honoured - a sideways JPEG stands as it was taken`() {
        val f = File(context.cacheDir, "sideways.jpg")
        f.outputStream().use { picture(800, 400).compress(Bitmap.CompressFormat.JPEG, 95, it) }
        android.media.ExifInterface(f.path).apply { setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val d = PinImageFiles.decode(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(f.readBytes())))
        // 800 x 400 turned a quarter: 400 x 800 as it stands, fitted to 214 x 428; its left half (red) now on top.
        assertEquals(214, d.width); assertEquals(428, d.height)
        assertTrue(near(d.getPixel(107, 60), Color.RED)); assertTrue(near(d.getPixel(107, 370), Color.BLUE))
    }

    @Test fun `an animated picture gives its first frame`() {
        // A made-up GIF of 2 x 2: a red frame, then a blue one.
        val gif = "47494638396102000200800000ff00000000ff21ff0b4e45545343415045322e30030100000021f904000a0000002c0000000002000200000204044110050021f904000a0000002c00000000020002000002040cc33005003b"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val d = PinImageFiles.decode(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(gif)))
        assertEquals(2, d.width)
        assertTrue(near(d.getPixel(0, 0), Color.RED, 10))
    }

    private fun keep(at: Long): String = PinImageFiles.save(context, picture(30, 20).let { b -> b.setHasAlpha(false); b }, at).name

    @Test fun `the bin, the settings' Remove, the twenty-note limit and a debug unpin each take the note's file with it`() {
        val store = ConfigStore(context)
        val a = keep(100); val b = keep(200); val c = keep(300)
        var pins = listOf(Pin("first", 300, image = c), Pin("words", 250), Pin("", 200, image = b), Pin("Look", 100, image = a))
        store.save(PanelConfig.DEFAULT.copy(pins = pins))
        assertTrue(File(dir, a).isFile && File(dir, b).isFile && File(dir, c).isFile)
        // THE BIN on the back (the app's onPinRemoved: the note of that time off the list): that file, no other.
        store.save(store.load().let { it.copy(pins = it.pins.filter { p -> p.at != 200L }) })
        assertFalse(File(dir, b).exists()); assertTrue(File(dir, a).isFile && File(dir, c).isFile)
        // A note moved in the order (or a payload saved again): nothing goes.
        store.save(store.load().let { it.copy(pins = it.pins.reversed()) })
        assertTrue(File(dir, a).isFile && File(dir, c).isFile)
        // A DEBUG UNPIN by words takes the picture's note with that caption.
        store.save(store.load().let { it.copy(pins = it.pins.filter { p -> p.text != "Look" }) })
        assertFalse(File(dir, a).exists()); assertTrue(File(dir, c).isFile)
        // THE TWENTY: the list full ("words" at 250, the oldest plain note, went on the way), the next note pushes the
        // picture's note (300, now the oldest plain one) out, and its file goes.
        pins = store.load().pins
        for (i in 1..19) pins = PinList.added(pins, "plain $i", 1_000L + i).pins
        store.save(store.load().copy(pins = pins))
        assertEquals(20, store.load().pins.size)
        assertTrue(store.load().pins.none { it.text == "words" })
        assertTrue(File(dir, c).isFile)
        store.save(store.load().let { it.copy(pins = PinList.added(it.pins, "one too many", 5_000L).pins) })
        assertFalse(File(dir, c).exists())
        assertTrue(store.load().pins.none { it.image != null })
    }

    @Test fun `the back's list with words and pictures - a bin takes the right note and its file, and the next older shows`() {
        val store = ConfigStore(context)
        val x = keep(30); val y = keep(10)
        val mixed = listOf(Pin("newest words", 40), Pin("", 30, image = x), Pin("middle words", 20), Pin("A caption", 10, image = y))
        store.save(PanelConfig.DEFAULT.copy(pins = mixed))
        // The back lists them all (pictures' notes as any note with no place: dev.kb896.aiusage.place.Here).
        assertEquals(mixed, Here.onBack(mixed))
        // He is on note 1 (the picture with no caption) and bins it: the table shows the next older in its place.
        var s = dev.kb896.aiusage.rear.RearNav.State(notes = true, pins = 4, pin = 1)
        s = dev.kb896.aiusage.rear.RearNav.next(s, dev.kb896.aiusage.rear.RearNav.Input.PinRemoved(1)).state
        val left = mixed.filter { it.at != 30L }
        assertEquals(3, s.pins); assertEquals("middle words", left[s.pin].text)
        store.save(store.load().copy(pins = store.load().pins.filter { it.at != 30L }))
        assertFalse(File(dir, x).exists()); assertTrue(File(dir, y).isFile)
        assertEquals(left, store.load().pins)
    }

    @Test fun `the sweep at start takes files no note has, and keeps the prompt's and the young`() {
        val store = ConfigStore(context)
        val had = keep(1); val none = keep(2); val draft = keep(3); val young = keep(4)
        store.save(PanelConfig.DEFAULT.copy(pins = listOf(Pin("", 1, image = had))))
        val old = System.currentTimeMillis() - PinImage.YOUNG_MS - 5_000
        for (n in listOf(had, none, draft)) File(dir, n).setLastModified(old)
        File(dir, "x.jpg.part").apply { writeText("half"); setLastModified(old) }
        File(dir, "readme.txt").apply { writeText("not ours"); setLastModified(old) }
        PinImageFiles.sweep(context, store.load().pins, keep = setOf(draft))
        assertTrue(File(dir, had).isFile); assertTrue(File(dir, draft).isFile); assertTrue(File(dir, young).isFile)
        assertFalse(File(dir, none).exists()); assertFalse(File(dir, "x.jpg.part").exists())
        assertTrue(File(dir, "readme.txt").exists())
        assertNotNull(PinImageFiles.file(context, had))
    }
}
