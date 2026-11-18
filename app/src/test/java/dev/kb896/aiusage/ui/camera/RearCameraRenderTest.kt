package dev.kb896.aiusage.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.ui.camera.RearCameraPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * THE CAMERA PAGE DRAWN ON THE PC: the real view's pixels at the back's 976 x 596 over A PLACEHOLDER where the live
 * picture goes (grey, marked as one: no camera, nothing from the phone), and its controls alone on a clear ground (to be
 * laid over the reference picture). Written to build/rear-camera/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RearCameraRenderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val out = File("build/rear-camera").apply { mkdirs() }

    private val ground = 0xFF6E7477.toInt()

    /** Plain grey, lighter at the top, with the word PLACEHOLDER where no control stands. */
    private fun placeholder(c: Canvas) {
        c.drawColor(ground)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF7D8386.toInt(); c.drawRect(0f, 0f, 976f, 200f, p)
        p.color = 0xFF62686B.toInt(); c.drawRect(0f, 420f, 976f, 596f, p)
        p.color = 0xFFDADDDE.toInt(); p.textAlign = Paint.Align.CENTER; p.textSize = 22f
        c.drawText("PLACEHOLDER: the live camera picture goes here", 560f, 300f, p)
        p.textSize = 16f
        c.drawText("(PC render of the page's layout, no camera)", 560f, 328f, p)
    }

    private fun page(mode: Mode, bokeh: Boolean, recording: Boolean): RearCameraView {
        context.setTheme(android.R.style.Theme_Material_NoActionBar)
        val page = RearCameraView(context)
        page.background = null
        page.preview.visibility = View.INVISIBLE
        page.showModes(mode, bokeh)
        page.zoom.text = RearCameraPolicy.zoomLabel(wideOn = true, wide = RearCameraPolicy.Wide(null, 0.7f, 0.7f))
        page.flash.on = false
        page.shutter.video = mode == Mode.VIDEO
        page.shutter.recording = recording
        page.recording(if (recording) "0:42" else null)
        // The last one taken: a piece of the placeholder.
        page.lastTaken(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply { drawColor(0xFF8A9093.toInt()); drawCircle(120f, 140f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA4A9AB.toInt() }) }
        })
        page.measure(View.MeasureSpec.makeMeasureSpec(976, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(596, View.MeasureSpec.EXACTLY))
        page.layout(0, 0, 976, 596)
        return page
    }

    private fun render(name: String, mode: Mode = Mode.PHOTO, bokeh: Boolean = false, recording: Boolean = false, clear: Boolean = false): Bitmap {
        val page = page(mode, bokeh, recording)
        val picture = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
        val c = Canvas(picture)
        if (!clear) placeholder(c)
        page.draw(c)
        out.resolve("$name.png").outputStream().use { assertTrue(picture.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return picture
    }

    private fun Bitmap.count(x0: Int, y0: Int, x1: Int, y1: Int, test: (Int) -> Boolean): Int {
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) if (test(getPixel(x, y))) n++
        return n
    }

    private fun yellowish(c: Int) = Color.red(c) > 200 && Color.green(c) > 150 && Color.blue(c) < 130
    private fun white(c: Int) = Color.red(c) > 240 && Color.green(c) > 240 && Color.blue(c) > 240

    @Test fun `photo - the picture behind the lenses too, Photo in yellow, a white shutter, the chip and the flash`() {
        val p = render("photo-placeholder")
        assertEquals("the picture goes on behind the lenses", 0xFF7D8386.toInt(), p.getPixel(30, 30))
        assertTrue("the shutter's white disc", white(p.getPixel(879, 496)))
        assertTrue("the shutter's ring, then the gap", white(p.getPixel(879 + 58, 496)) && !white(p.getPixel(879 + 53, 496)))
        assertTrue("Photo in the camera's yellow", p.count(530, 515, 605, 548, ::yellowish) > 60)
        assertEquals("Video is not", 0, p.count(390, 515, 475, 548, ::yellowish))
        assertTrue("the chip's words", p.count(850, 222, 910, 250, ::white) > 20)
        assertTrue("the flash's bolt", p.count(860, 340, 900, 376, ::white) > 20)
        assertEquals("nothing where Portrait would stand", 0, p.count(660, 515, 770, 548, ::white))
    }

    @Test fun `portrait offered - its word at its place`() {
        val p = render("photo-portrait-offered-placeholder", bokeh = true)
        assertTrue(p.count(660, 515, 770, 548, ::white) > 60)
    }

    @Test fun `video recording - a red square in the ring, the time over the picture`() {
        val p = render("video-recording-placeholder", mode = Mode.VIDEO, recording = true)
        val red = p.getPixel(879, 496)
        assertTrue("red: ${Integer.toHexString(red)}", Color.red(red) > 220 && Color.green(red) < 90)
        assertTrue("the time", p.count(560, 30, 712, 60, ::white) > 20)
        assertTrue("Video in yellow", p.count(390, 515, 475, 548, ::yellowish) > 60)
    }

    @Test fun `the controls alone, to lay over the reference`() {
        val p = render("controls-only", bokeh = true, clear = true)
        assertEquals("clear where there is no control", 0, Color.alpha(p.getPixel(600, 300)))
        assertTrue(white(p.getPixel(879, 496)))
    }
}
