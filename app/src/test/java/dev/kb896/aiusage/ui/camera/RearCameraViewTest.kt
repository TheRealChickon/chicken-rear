package dev.kb896.aiusage.ui.camera

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.ui.camera.RearCameraPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The camera page laid out at the back's own 976 x 596 (and at half that), its fingers, and its way in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RearCameraViewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The page in a window (a click is posted to the window's thread), at [w] x [h] whatever the window's own size. */
    private fun page(w: Int = 976, h: Int = 596): RearCameraView {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val page = RearCameraView(activity)
        activity.setContentView(android.widget.FrameLayout(activity).apply { addView(page, android.widget.FrameLayout.LayoutParams(w, h)) })
        page.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        page.layout(0, 0, w, h)
        return page
    }

    private fun View.rect() = Rect(left, top, right, bottom)
    private fun centreX(v: View) = (v.left + v.right) / 2

    @Test fun `the picture fills the whole panel, behind the lenses too, drawn in the page's own window`() {
        val page = page()
        assertEquals(Rect(0, 0, 976, 596), page.preview.rect())
        assertEquals(androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE, page.preview.implementationMode)
        assertEquals("a mirror for the person in front of the back", -1f, page.preview.scaleX)
    }

    @Test fun `every control stands right of the lenses, on the panel, and none on another`() {
        val page = page()
        page.lastTaken(android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888))
        page.showModes(Mode.PHOTO, bokeh = true)
        page.measure(View.MeasureSpec.makeMeasureSpec(976, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(596, View.MeasureSpec.EXACTLY))
        page.layout(0, 0, 976, 596)
        val controls = listOf(page.thumbnail, page.zoom, page.flash, page.shutter) + page.modeWords
        for (c in controls) {
            assertTrue("${c.contentDescription} right of the lenses (${c.left})", c.left >= RearCameraLayout.SEEN_LEFT)
            assertTrue("${c.contentDescription} on the panel", c.top >= 0 && c.right <= 976 && c.bottom <= 596)
            assertTrue("${c.contentDescription} has a size", c.width > 0 && c.height > 0)
        }
        for (i in controls.indices) for (j in i + 1 until controls.size) {
            assertFalse("${controls[i].contentDescription} / ${controls[j].contentDescription}", Rect.intersects(controls[i].rect(), controls[j].rect()))
        }
    }

    @Test fun `the words stand where the reference has them, Photo chosen, Portrait only when offered`() {
        val page = page()
        assertEquals(listOf("Video", "Photo"), page.modeWords.map { it.text.toString() })
        assertTrue(page.modeWords[1].isSelected)
        assertEquals(RearCameraLayout.YELLOW, page.modeWords[1].currentTextColor)
        assertEquals(android.graphics.Color.WHITE, page.modeWords[0].currentTextColor)
        val photoAt = centreX(page.modeWords[1])
        assertEquals(567.0, photoAt.toDouble(), 2.0)
        assertEquals(431.0, centreX(page.modeWords[0]).toDouble(), 2.0)
        page.showModes(Mode.PORTRAIT, bokeh = true)
        page.layout(0, 0, 976, 596)
        assertEquals(3, page.modeWords.size)
        assertEquals("Photo does not move when Portrait comes", photoAt, centreX(page.modeWords[1]))
        assertEquals(714.0, centreX(page.modeWords[2]).toDouble(), 2.0)
        assertTrue(page.modeWords[2].isSelected)
        assertEquals("Portrait mode, chosen", page.modeWords[2].contentDescription)
        assertEquals("the words' middle line", 531.0, ((page.modeWords[0].top + page.modeWords[0].bottom) / 2).toDouble(), 2.0)
    }

    @Test fun `the shutter says what it does, and the controls wait while the camera is not ready`() {
        val page = page()
        assertEquals("Take photo", page.shutter.contentDescription)
        page.shutter.video = true; assertEquals("Record video", page.shutter.contentDescription)
        page.shutter.recording = true; assertEquals("Stop recording", page.shutter.contentDescription)
        page.controlsEnabled(false)
        assertFalse(page.shutter.isEnabled); assertFalse(page.zoom.isEnabled); assertFalse(page.modeWords[0].isEnabled)
        page.recording("0:42")
        assertEquals(View.VISIBLE, page.time.visibility)
        assertEquals("the time stands centred on what he sees", RearCameraLayout.SEEN_CX.toDouble(), centreX(page.time).toDouble(), 2.0)
        page.recording(null)
        assertEquals(View.GONE, page.time.visibility)
    }

    // -- fingers, at the back's size and at half of it (the view scales the back's pixels) -----------------------------

    private class Seen(page: RearCameraView) {
        var backs = 0; var modes = mutableListOf<Mode>(); var focus = 0; var flashes = 0
        init {
            page.onBack = { backs++ }; page.onMode = { modes += it }; page.onFocus = { _, _ -> focus++ }
            page.flash.setOnClickListener { flashes++ }
        }
    }

    /** One finger from ([x0], [y0]) to ([x1], [y1]) in the back's pixels, through the page as the window would send it. */
    private fun stroke(page: RearCameraView, x0: Float, y0: Float, x1: Float, y1: Float, scale: Int, steps: Int = 6, ms: Long = 180L) {
        val t = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float, at: Long) {
            val e = MotionEvent.obtain(t, at, action, x / scale, y / scale, 0)
            try { page.dispatchTouchEvent(e) } finally { e.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, x0, y0, t)
        for (i in 1..steps) send(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps, t + ms * i / steps)
        send(MotionEvent.ACTION_UP, x1, y1, t + ms)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `a stroke right to left on the picture goes back, on the words it does not, a word tapped chooses`() {
        for (scale in listOf(1, 2)) {
            val page = page(976 / scale, 596 / scale)
            val seen = Seen(page)
            stroke(page, 780f, 240f, 520f, 250f, scale)
            assertEquals("scale $scale", 1, seen.backs)
            stroke(page, 780f, 531f, 400f, 531f, scale)
            assertEquals("a stroke along the words is not the way back", 1, seen.backs)
            stroke(page, 520f, 240f, 780f, 240f, scale)
            assertEquals("left to right does nothing here", 1, seen.backs)
            // A tap on "Video".
            stroke(page, 431f, 531f, 431f, 531f, scale, steps = 1, ms = 60L)
            assertEquals(listOf(Mode.VIDEO), seen.modes)
            // A tap on the picture: focus there.
            stroke(page, 600f, 300f, 602f, 301f, scale, steps = 1, ms = 60L)
            assertEquals(1, seen.focus)
        }
    }

    @Test fun `a stroke back that starts on a round button goes back and does not press it`() {
        val page = page()
        val seen = Seen(page)
        stroke(page, 879f, 358f, 640f, 368f, scale = 1)
        assertEquals(1, seen.backs)
        assertEquals("the flash's press was taken from it", 0, seen.flashes)
        // A plain tap on it still presses it.
        stroke(page, 879f, 358f, 880f, 358f, scale = 1, steps = 1, ms = 60L)
        assertEquals(1, seen.flashes)
        assertEquals(1, seen.backs)
    }

    @Test fun `two fingers never go back`() {
        val page = page()
        val seen = Seen(page)
        val t = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0 }, MotionEvent.PointerProperties().apply { id = 1 })
        fun coords(x: Float) = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; y = 240f }, MotionEvent.PointerCoords().apply { this.x = x - 60f; y = 300f })
        fun send(action: Int, n: Int, x: Float, at: Long) {
            val e = MotionEvent.obtain(t, at, action, n, props.copyOf(n).requireNoNulls(), coords(x).copyOf(n).requireNoNulls(), 0, 0, 1f, 1f, 0, 0, 0, 0)
            try { page.dispatchTouchEvent(e) } finally { e.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 1, 800f, t)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 800f, t + 20)
        send(MotionEvent.ACTION_MOVE, 2, 600f, t + 100)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 500f, t + 150)
        send(MotionEvent.ACTION_UP, 1, 500f, t + 180)
        assertEquals(0, seen.backs)
    }

    // -- the way in ------------------------------------------------------------------------------------------------------

    @Test fun `started on the front screen it closes at once even with camera permission`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.CAMERA)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        val controller = Robolectric.buildActivity(RearCameraActivity::class.java).create()
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun `only the shell may start it, in a process and a task of its own, never in Recents`() {
        val info = context.packageManager.getActivityInfo(android.content.ComponentName(context, RearCameraActivity::class.java), 0)
        assertTrue(info.exported)
        assertEquals("android.permission.DUMP", info.permission)
        assertTrue(info.processName.endsWith(":camera"))
        assertTrue(info.flags and ActivityInfo.FLAG_HARDWARE_ACCELERATED != 0)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        assertEquals("the class the shell starts", dev.kb896.aiusage.rear.RearCameraRoute.CLASS, RearCameraActivity::class.java.name)
    }
}
