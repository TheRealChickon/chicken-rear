package dev.kb896.aiusage.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.After
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
import java.io.File
import java.util.concurrent.Executor

/**
 * The front's larger copy of a pinned picture (data/PinImageFiles): made from the same single read as the back's, kept
 * in the app's PRIVATE files under the same name, and gone with its note however the note goes (the store's save, a
 * screen dropping what it read, the sweep), also when its note goes while it is being written. Made-up pictures only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinImageFrontFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val back get() = PinImageFiles.dir(context)!!
    private val front get() = PinImageFiles.frontDir(context)
    private val queued = ArrayList<Runnable>()
    private val before = PinImageFiles.frontWork

    @Before fun clean() {
        back.deleteRecursively(); front.deleteRecursively()
        ConfigStore(context).save(PanelConfig.DEFAULT)
        // The front's copies are written when the test says (as a thread of their own would, later).
        PinImageFiles.frontWork = Executor { queued += it }
    }

    @After fun put() { PinImageFiles.frontWork = before }

    private fun runQueued() { val q = queued.toList(); queued.clear(); q.forEach { it.run() } }

    /** A made-up picture: left half [left], right half [right]. */
    private fun picture(w: Int, h: Int, left: Int = Color.RED, right: Int = Color.BLUE): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b); val p = Paint()
            p.color = left; c.drawRect(0f, 0f, w / 2f, h.toFloat(), p)
            p.color = right; c.drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), p)
            // Opaque, as a photo is (a PNG of a picture with an alpha channel is kept as WebP).
            b.setHasAlpha(false)
        }

    private fun png(b: Bitmap): ImageDecoder.Source {
        val out = java.io.ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        return ImageDecoder.createSource(java.nio.ByteBuffer.wrap(out.toByteArray()))
    }

    private fun near(a: Int, b: Int, by: Int = 40) =
        kotlin.math.abs(Color.red(a) - Color.red(b)) <= by && kotlin.math.abs(Color.green(a) - Color.green(b)) <= by && kotlin.math.abs(Color.blue(a) - Color.blue(b)) <= by

    private fun size(f: File): Pair<Int, Int> = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }.let { it.outWidth to it.outHeight }

    @Test fun `one read makes both - the back's at the page's size outside, the front's at 2400 in the private files, one name`() {
        val kept = PinImageFiles.importFrom(context, png(picture(3200, 2400)), 1_000L)
        assertEquals("1000-478x359.jpg", kept.name)
        assertEquals(478, kept.picture.width); assertEquals(359, kept.picture.height)
        assertEquals(478 to 359, size(File(back, kept.name)))
        // The pin does not wait for the front's copy: written after.
        assertFalse(File(front, kept.name).exists())
        runQueued()
        val big = File(front, kept.name)
        assertTrue(big.isFile)
        assertEquals(2400 to 1800, size(big))
        assertFalse("never half written", File(front, kept.name + ".part").exists())
        // Private: under the app's own files, not the folder the back's shell reads.
        assertTrue(big.path.startsWith(context.filesDir.path))
        assertFalse(big.path.startsWith(context.getExternalFilesDir(null)!!.path))
        assertEquals(listOf(kept.name), back.list()!!.toList())
        // The back's copy, made by halves from the front's, keeps the picture.
        val b = BitmapFactory.decodeFile(File(back, kept.name).path)
        assertTrue(near(b.getPixel(60, 180), Color.RED)); assertTrue(near(b.getPixel(420, 180), Color.BLUE))
        // The viewer reads the front's.
        val shown = PinImageFiles.readForView(context, kept.name)!!
        assertEquals(2400, shown.width)
        assertTrue(near(shown.getPixel(300, 900), Color.RED)); assertTrue(near(shown.getPixel(2100, 900), Color.BLUE))
    }

    @Test fun `a picture no larger than its back copy has no front copy, and the viewer shows the back's`() {
        val kept = PinImageFiles.importFrom(context, png(picture(300, 200)), 2_000L)
        runQueued()
        assertEquals("2000-300x200.jpg", kept.name)
        assertFalse(File(front, kept.name).exists())
        assertEquals(300, PinImageFiles.readForView(context, kept.name)!!.width)
        // A picture pinned before there were front copies: the same.
        val old = PinImageFiles.save(context, picture(478, 359).also { it.setHasAlpha(false) }, 3_000L)
        assertEquals(478, PinImageFiles.readForView(context, old.name)!!.width)
        // Nothing of ours by that name, or a name that is not one: nothing.
        assertNull(PinImageFiles.readForView(context, "9-10x10.jpg"))
        assertNull(PinImageFiles.readForView(context, "../shared_prefs/panel_config.xml"))
        assertNull(PinImageFiles.readForView(context, null))
    }

    @Test fun `a picture with see-through parts keeps them in both copies (WebP)`() {
        val clear = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888).also { Canvas(it).drawRect(0f, 0f, 600f, 600f, Paint().apply { color = Color.RED }) }
        val kept = PinImageFiles.importFrom(context, png(clear), 4_000L)
        runQueued()
        assertTrue(kept.name.endsWith(".webp"))
        val big = PinImageFiles.readForView(context, kept.name)!!
        assertEquals(1200, big.width)
        assertTrue(Color.alpha(big.getPixel(1000, 300)) < 40)
        assertTrue(Color.alpha(big.getPixel(100, 300)) > 200)
    }

    @Test fun `whatever takes the note away takes both copies - the store's save, a screen's drop, the sweep`() {
        val store = ConfigStore(context)
        val a = PinImageFiles.importFrom(context, png(picture(1600, 1200)), 100L).name
        val b = PinImageFiles.importFrom(context, png(picture(1600, 1200)), 200L).name
        runQueued()
        store.save(PanelConfig.DEFAULT.copy(pins = listOf(Pin("", 200, image = b), Pin("", 100, image = a))))
        assertTrue(File(front, a).isFile && File(front, b).isFile)
        // The bin on the back, the settings' Remove, the twenty, a debug unpin: all pass the store's save.
        store.save(store.load().let { it.copy(pins = it.pins.filter { p -> p.at != 100L }) })
        assertFalse(File(back, a).exists()); assertFalse(File(front, a).exists())
        assertTrue(File(back, b).isFile && File(front, b).isFile)
        // A screen gone while it read (Pin to location's prompt): forget, both.
        val c = PinImageFiles.importFrom(context, png(picture(1600, 1200)), 300L).name
        runQueued()
        PinImageFiles.forget(context, listOf(c))
        assertFalse(File(back, c).exists()); assertFalse(File(front, c).exists())
        // The sweep: a front copy with no note, old, goes; the young and the kept stay; a half-written one goes.
        val old = System.currentTimeMillis() - PinImage.YOUNG_MS - 5_000
        File(front, "400-478x359.jpg").apply { writeText("x"); setLastModified(old) }
        File(front, "500-478x359.jpg").apply { writeText("x") }
        File(front, "600-478x359.jpg.part").apply { writeText("x"); setLastModified(old) }
        File(front, b).setLastModified(old)
        PinImageFiles.sweep(context, store.load().pins, keep = emptySet())
        assertFalse(File(front, "400-478x359.jpg").exists())
        assertTrue(File(front, "500-478x359.jpg").exists())
        assertFalse(File(front, "600-478x359.jpg.part").exists())
        assertTrue(File(front, b).isFile)
    }

    @Test fun `a note gone while its front copy is being written - the copy does not outlive it`() {
        val kept = PinImageFiles.importFrom(context, png(picture(1600, 1200)), 700L)
        // Taken away before its front copy was written (forget deletes the back's copy first, finds no front copy yet).
        PinImageFiles.forget(context, listOf(kept.name))
        runQueued()
        assertFalse(File(front, kept.name).exists())
        assertTrue(front.list().orEmpty().none { it.startsWith("700") })
    }

    @Test fun `the store's file of a picture's note is never read as a picture and nothing outside the folders is`() {
        assertNull(PinImageFiles.frontFile(context, "../x.jpg"))
        assertNotNull(PinImageFiles.frontFile(context, "1-1x1.jpg"))
        assertEquals(PinImage.FRONT_DIR, PinImageFiles.frontFile(context, "1-1x1.jpg")!!.parentFile!!.name)
    }
}
