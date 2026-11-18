package dev.kb896.aiusage.ui

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.data.PinImageFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.io.File
import java.time.Duration
import kotlin.math.abs

/**
 * The pinned picture on the front (PinViewActivity): only the shell may start it, it reads one number and shows his own
 * picture (its front copy where there is one), opens and closes, and never shows anything over the lock screen or in
 * a call; it leaves nothing behind. Made-up pictures only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinViewActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val before = PinImageFiles.frontWork
    private val readBefore = PinViewActivity.work
    private var reads = 0

    @Before fun setUp() {
        PinImageFiles.dir(context)?.deleteRecursively(); PinImageFiles.frontDir(context).deleteRecursively()
        ConfigStore(context).save(PanelConfig.DEFAULT)
        PinImageFiles.frontWork = java.util.concurrent.Executor { it.run() }
        PinViewActivity.work = { job -> reads++; job() }
    }

    @After fun tearDown() {
        PinImageFiles.frontWork = before
        PinViewActivity.work = readBefore
        ShadowChoreographer.setPaused(false)
    }

    /**
     * Frames by the clock, a millisecond each: a spring is where it would be that long after. (Robolectric's own way,
     * unpaused, runs every frame of a move at once, its clock with them, whatever the test idles.)
     */
    private fun framesByTheClock() = ShadowChoreographer.setPaused(true)

    private fun solid(w: Int, h: Int, color: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(color); it.setHasAlpha(false) }

    /** A note with a picture: its back copy [backColor]; with [frontColor], a front copy of that colour too ([tall]: upright, as a screenshot is). */
    private fun pin(at: Long, backColor: Int = Color.RED, frontColor: Int? = Color.GREEN, caption: String = "", tall: Boolean = false): Pin {
        val back = if (tall) solid(359, 478, backColor) else solid(478, 359, backColor)
        val name = PinImageFiles.save(context, back, at, front = frontColor?.let { if (tall) solid(1800, 2400, it) else solid(2400, 1800, it) }).name
        val p = Pin(caption, at, image = name)
        val store = ConfigStore(context)
        store.save(store.load().let { it.copy(pins = listOf(p) + it.pins) })
        return p
    }

    private fun intent(at: Long?): Intent = Intent(context, PinViewActivity::class.java).apply { if (at != null) putExtra("at", at) }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun start(i: Intent): ActivityController<PinViewActivity> = Robolectric.buildActivity(PinViewActivity::class.java, i).setup()

    private fun keyguard() = shadowOf(context.getSystemService(KeyguardManager::class.java))

    private fun color(b: Bitmap) = b.getPixel(b.width / 2, b.height / 2)

    @Test fun `only the shell may start it - DUMP, its own task, not in Recents, never over the lock screen`() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getActivityInfo(ComponentName(context, PinViewActivity::class.java), 0)
        assertTrue("the shell's am start reaches only an exported screen", info.exported)
        assertEquals("android.permission.DUMP", info.permission)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertTrue(info.taskAffinity!!.endsWith(".pinview"))
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        // ActivityInfo.FLAG_SHOW_WHEN_LOCKED (hidden from apps): not set.
        assertEquals(0, info.flags and 0x800000)
        // Translucent, so it must ask for an orientation of its own, or the screen under it (the launcher, upright)
        // decides, and a turned phone's picture stays upright and small: it turns as the phone's own settings allow.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, info.screenOrientation)
    }

    @Test fun `his picture opens at its front copy's size, grows in, and closes back into the back's place`() {
        val p = pin(1_000L, caption = "A caption")
        val c = start(intent(p.at))
        val a = c.get()
        val v = a.shownViewer
        assertNotNull(v)
        assertEquals(2400, v!!.shownPicture.width)
        assertTrue(Color.green(color(v.shownPicture)) > 200)
        idle(800)
        assertEquals(PinViewer.Phase.OPEN, v.phase)
        assertEquals(1f, v.opened, 0.001f)
        assertFalse(a.isFinishing)
        v.closeBack()
        idle(800)
        assertEquals(PinViewer.Phase.CLOSED, v.phase)
        assertTrue("gone with its task", a.isFinishing)
    }

    @Test fun `a picture from before the front copies shows its back copy`() {
        val p = pin(2_000L, frontColor = null)
        val v = start(intent(p.at)).get().shownViewer!!
        assertEquals(478, v.shownPicture.width)
        assertTrue(Color.red(color(v.shownPicture)) > 200)
    }

    @Test fun `nothing but the note's time is read - an address, a path or another file in the intent is not`() {
        val p = pin(3_000L)
        val other = File(context.filesDir, "elsewhere.png").apply { outputStream().use { solid(64, 64, Color.BLUE).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val i = intent(p.at).apply {
            data = Uri.fromFile(other)
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(other))
            putExtra("image", other.path); putExtra("path", other.path); putExtra("name", "../elsewhere.png")
        }
        val v = start(i).get().shownViewer!!
        assertTrue("his own picture", Color.green(color(v.shownPicture)) > 200)
        // No note of that time, a time as words, none at all: nothing shown, and gone.
        for (bad in listOf(intent(9_999L), Intent(context, PinViewActivity::class.java).putExtra("at", "3000"), intent(null), intent(-3_000L))) {
            val a = start(bad).get()
            assertNull(a.shownViewer)
            assertTrue(a.isFinishing)
        }
        // A note of words only: nothing.
        val store = ConfigStore(context)
        store.save(store.load().let { it.copy(pins = it.pins + Pin("words", 4_000L)) })
        assertNull(start(intent(4_000L)).get().shownViewer)
    }

    @Test fun `locked - nothing is read or shown until he unlocks, then it opens`() {
        val p = pin(5_000L)
        keyguard().setKeyguardLocked(true)
        val a = start(intent(p.at)).get()
        assertNull(a.shownViewer)
        assertEquals("not even read", 0, reads)
        assertFalse(a.isFinishing)
        keyguard().setKeyguardLocked(false)
        assertNotNull(a.shownViewer)
        assertEquals(1, reads)
    }

    @Test fun `locked and not unlocked - nothing shown, and it goes`() {
        val p = pin(6_000L)
        keyguard().setKeyguardLocked(true)
        val a = start(intent(p.at)).get()
        // He backs out of the unlock: the shadow says the lock stays.
        keyguard().setKeyguardLocked(true)
        assertNull(a.shownViewer)
        assertTrue(a.isFinishing)
        assertEquals(0, reads)
        // Never answered at all: a minute, then gone.
        val b = start(intent(p.at)).get()
        assertFalse(b.isFinishing)
        idle(PinViewActivity.UNLOCK_WAIT_MS + 100)
        assertTrue(b.isFinishing)
        assertNull(b.shownViewer)
    }

    @Test fun `in a call nothing is shown`() {
        val p = pin(7_000L)
        context.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        val a = start(intent(p.at)).get()
        assertNull(a.shownViewer)
        assertTrue(a.isFinishing)
        context.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
        assertNull(start(intent(p.at)).get().shownViewer)
        context.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        assertNotNull(start(intent(p.at)).get().shownViewer)
    }

    @Test fun `left any other way (Home, the screen off) it goes, not to be found later`() {
        val p = pin(8_000L)
        val c = start(intent(p.at))
        idle(500)
        c.pause().stop()
        assertTrue(c.get().isFinishing)
    }

    private fun touch(v: PinViewer, action: Int, x: Float, y: Float, t: Long) {
        val e = MotionEvent.obtain(1_000L, t, action, x, y, 0)
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    @Test fun `a stroke down follows the finger and, let go far enough, takes it away down`() {
        val p = pin(9_000L)
        val c = start(intent(p.at))
        val v = c.get().shownViewer!!
        idle(800)
        val x = v.width / 2f; val y = v.height / 2f
        touch(v, MotionEvent.ACTION_DOWN, x, y, 1_000L)
        for (i in 1..10) touch(v, MotionEvent.ACTION_MOVE, x, y + i * v.width * 0.04f, 1_000L + i * 16L)
        touch(v, MotionEvent.ACTION_UP, x, y + 10 * v.width * 0.04f, 1_170L)
        idle(1_500)
        assertEquals(PinViewer.Phase.CLOSED, v.phase)
        assertTrue(c.get().isFinishing)
    }

    @Test fun `a short stroke springs back, and a double tap zooms in, and again out`() {
        val p = pin(10_000L)
        val c = start(intent(p.at))
        val v = c.get().shownViewer!!
        idle(800)
        val x = v.width / 2f; val y = v.height / 2f
        touch(v, MotionEvent.ACTION_DOWN, x, y, 2_000L)
        for (i in 1..4) touch(v, MotionEvent.ACTION_MOVE, x, y + i * 4f, 2_000L + i * 60L)
        touch(v, MotionEvent.ACTION_UP, x, y + 16f, 2_300L)
        idle(1_000)
        assertEquals(PinViewer.Phase.OPEN, v.phase)
        assertFalse(c.get().isFinishing)
        val t0 = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, x, y, t0)
        touch(v, MotionEvent.ACTION_UP, x, y, t0 + 50)
        touch(v, MotionEvent.ACTION_DOWN, x, y, t0 + 150)
        touch(v, MotionEvent.ACTION_UP, x, y, t0 + 200)
        idle(1_000)
        assertTrue("in: ${v.zoomNow}", v.zoomNow >= 2f)
        val t1 = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, x, y, t1)
        touch(v, MotionEvent.ACTION_UP, x, y, t1 + 50)
        touch(v, MotionEvent.ACTION_DOWN, x, y, t1 + 150)
        touch(v, MotionEvent.ACTION_UP, x, y, t1 + 200)
        idle(1_000)
        assertEquals(1f, v.zoomNow, 0.001f)
    }

    // ---- caught as it moves, and let go -------------------------------------------------------------------------

    /** Event times for a new gesture: after everything sent before (a double tap's events run ahead of the clock). */
    private var sent = 0L
    private fun later(): Long = maxOf(SystemClock.uptimeMillis(), sent + 400).also { sent = it + 300 }

    private fun doubleTap(v: PinViewer, x: Float, y: Float) {
        val t = later()
        touch(v, MotionEvent.ACTION_DOWN, x, y, t); touch(v, MotionEvent.ACTION_UP, x, y, t + 50)
        touch(v, MotionEvent.ACTION_DOWN, x, y, t + 150); touch(v, MotionEvent.ACTION_UP, x, y, t + 200)
    }

    /** [ms] of frames by the clock ([framesByTheClock]), 8 ms each (the phone's 120 Hz). */
    private fun play(ms: Long) = repeat((ms / 8).toInt()) { idle(8) }

    /** Frames by the clock until [still] no longer holds. */
    private fun idleWhile(still: () -> Boolean) {
        var n = 0
        while (still() && n++ < 1_000) idle(8)
        assertFalse("never came", still())
    }

    private fun tap(v: PinViewer, x: Float, y: Float) {
        val t = later()
        touch(v, MotionEvent.ACTION_DOWN, x, y, t); touch(v, MotionEvent.ACTION_UP, x, y, t + 40)
    }

    /**
     * Where a picture at rest belongs: as wide as the screen or more, no black at its sides; narrower, in the middle
     * across (and the same up and down); its zoom within its ends.
     */
    private fun assertHeld(v: PinViewer, what: String) {
        val r = v.shownPlace
        val w = v.width.toFloat(); val h = v.height.toFloat()
        assertTrue("$what: zoom ${v.zoomNow}", v.zoomNow >= 0.999f && v.zoomNow <= v.zoomMost + 0.001f)
        if (r.width() >= w) assertTrue("$what: black at a side, $r", r.left <= 0.5f && r.right >= w - 0.5f)
        else assertEquals("$what: off the middle across, $r", w / 2f, r.centerX(), 0.5f)
        if (r.height() >= h) assertTrue("$what: black above or below, $r", r.top <= 0.5f && r.bottom >= h - 0.5f)
        else assertEquals("$what: off the middle up and down, $r", h / 2f, r.centerY(), 0.5f)
    }

    /** Opened, zoomed in about a point well off the middle, then out by a double tap, and halfway out. */
    private fun onItsWayOut(at: Long): PinViewer {
        framesByTheClock()
        val v = start(intent(pin(at).at)).get().shownViewer!!
        play(800)
        doubleTap(v, v.width * 0.15f, v.height / 2f)
        play(1_000)
        val zoomedIn = v.zoomNow
        assertTrue("in: $zoomedIn", zoomedIn >= 2f)
        assertTrue("moved off the middle: ${v.shownPlace}", abs(v.shownPlace.centerX() - v.width / 2f) > 20f)
        assertHeld(v, "zoomed in")
        doubleTap(v, v.width / 2f, v.height / 2f)
        idleWhile { v.zoomNow > (zoomedIn + 1f) / 2f }
        assertTrue("on its way out: ${v.zoomNow}", v.zoomNow > 1.1f && v.zoomNow < zoomedIn - 0.1f)
        assertTrue("still off the middle: ${v.shownPlace}", abs(v.shownPlace.centerX() - v.width / 2f) > 10f)
        return v
    }

    @Test fun `a finger landing while the zoom settles catches it all - let go, it is held at its edges, never off to one side`() {
        // A tap (the caption's, or a double tap's first) on its way out.
        val a = onItsWayOut(12_000L)
        tap(a, a.width / 2f, a.height / 2f)
        play(3_000)
        assertHeld(a, "a tap on its way out")
        assertEquals(PinViewer.Phase.OPEN, a.phase)

        // A move on its way out: the pan's edges are the zoom's it was caught at.
        val b = onItsWayOut(13_000L)
        val y = b.height / 2f
        val t = later()
        touch(b, MotionEvent.ACTION_DOWN, b.width / 2f, y, t)
        for (i in 1..6) touch(b, MotionEvent.ACTION_MOVE, b.width / 2f + i * 10f, y, t + i * 16L)
        touch(b, MotionEvent.ACTION_UP, b.width / 2f + 60f, y, t + 112L)
        play(3_000)
        assertHeld(b, "a move on its way out")
    }

    @Test fun `a stroke let go short and caught on its way home - a tap sends it home, a stroke takes it on with no jump`() {
        framesByTheClock()
        val v = start(intent(pin(14_000L).at)).get().shownViewer!!
        play(800)
        val fit = v.shownPlace
        val x = v.width / 2f; val y = v.height / 2f
        val stroke = { by: Float ->
            val t = later()
            touch(v, MotionEvent.ACTION_DOWN, x, y, t)
            for (i in 1..9) touch(v, MotionEvent.ACTION_MOVE, x, y + i * by / 9f, t + i * 60L)
            touch(v, MotionEvent.ACTION_UP, x, y + by, t + 600L)
        }
        val short = v.width * PinViewMotion.DISMISS_TRAVEL * 0.75f
        stroke(short)
        idleWhile { v.shownPlace.centerY() > fit.centerY() + short * 0.6f }
        val mid = v.shownPlace
        assertTrue("on its way home: $mid", mid.centerY() > fit.centerY() + 3f)
        // Caught: not a pixel off. Let go: home.
        val t = later()
        touch(v, MotionEvent.ACTION_DOWN, x, y, t)
        assertEquals(mid, v.shownPlace)
        touch(v, MotionEvent.ACTION_UP, x, y, t + 40)
        play(2_000)
        assertEquals(fit, v.shownPlace)
        assertEquals(PinViewer.Phase.OPEN, v.phase)

        // Caught again and moved: it goes on from where it was caught, the finger's way, no jump.
        stroke(short)
        idleWhile { v.shownPlace.centerY() > fit.centerY() + short * 0.6f }
        val caught = v.shownPlace
        assertTrue(caught.centerY() > fit.centerY() + 3f)
        val t2 = later()
        touch(v, MotionEvent.ACTION_DOWN, x, y, t2)
        touch(v, MotionEvent.ACTION_MOVE, x, y + 20f, t2 + 100L)
        assertEquals("with the finger from where it was", caught.centerY() + 20f, v.shownPlace.centerY(), 0.5f)
        // Held still, then let go (not thrown): short of the line, it goes home.
        touch(v, MotionEvent.ACTION_MOVE, x, y + 20f, t2 + 160L)
        touch(v, MotionEvent.ACTION_MOVE, x, y + 20f, t2 + 220L)
        touch(v, MotionEvent.ACTION_UP, x, y + 20f, t2 + 260L)
        play(2_000)
        assertEquals(fit, v.shownPlace)
    }

    @Test fun `a stroke down keeps the point it took under the finger, wherever it took it`() {
        // Upright, as a screenshot is: taken near its top, far from its middle.
        val v = start(intent(pin(15_000L, tall = true).at)).get().shownViewer!!
        idle(800)
        val r0 = v.shownPlace
        val x = r0.left + r0.width() * 0.8f; val y = r0.top + r0.height() * 0.1f
        val u = (x - r0.left) / r0.width(); val w = (y - r0.top) / r0.height()
        val t = later()
        touch(v, MotionEvent.ACTION_DOWN, x, y, t)
        var followed = 0
        for (i in 1..14) {
            val fx = x - i * 2f; val fy = y + i * 12f
            touch(v, MotionEvent.ACTION_MOVE, fx, fy, t + i * 16L)
            val r = v.shownPlace
            // Still within the touch slop: not moved yet. From then on, with the finger.
            if (r == r0) { assertEquals("only at the start", 0, followed); continue }
            followed++
            assertEquals("across, at $i", fx, r.left + u * r.width(), 0.5f)
            assertEquals("down, at $i", fy, r.top + w * r.height(), 0.5f)
        }
        assertTrue("followed: $followed", followed >= 10)
        assertTrue("it did grow smaller: ${v.shownPlace}", v.shownPlace.width() < r0.width() * 0.9f)
    }

    @Test fun `the finger's own way decides the close, wherever it took the picture`() {
        val line = { v: PinViewer -> v.width * PinViewMotion.DISMISS_TRAVEL }
        /** A slow stroke down by [by], taken at [part] of the picture's height. */
        fun slow(v: PinViewer, part: Float, by: Float): Float {
            val r0 = v.shownPlace
            val x = r0.centerX(); val y = r0.top + r0.height() * part
            val t = later()
            val steps = 30
            touch(v, MotionEvent.ACTION_DOWN, x, y, t)
            for (i in 1..steps) touch(v, MotionEvent.ACTION_MOVE, x, y + i * by / steps, t + i * 50L)
            val centreWent = v.shownPlace.centerY() - r0.centerY()
            touch(v, MotionEvent.ACTION_UP, x, y + by, t + steps * 50L + 50L)
            return centreWent
        }
        // Taken near its top and taken past the line: its centre went less, and still it goes.
        val c = start(intent(pin(16_000L, tall = true).at))
        val high = c.get().shownViewer!!
        idle(800)
        val went = slow(high, 0.1f, line(high) * 1.1f)
        assertTrue("its centre short of the line: $went", went < line(high))
        idle(1_500)
        assertEquals(PinViewer.Phase.CLOSED, high.phase)
        assertTrue(c.get().isFinishing)
        // Taken near its bottom, short of the line: its centre went past it, and still it stays.
        val d = start(intent(pin(17_000L, tall = true).at))
        val low = d.get().shownViewer!!
        idle(800)
        val lowWent = slow(low, 0.9f, line(low) * 0.9f)
        assertTrue("its centre past the line: $lowWent", lowWent > line(low))
        idle(1_500)
        assertEquals(PinViewer.Phase.OPEN, low.phase)
        assertFalse(d.get().isFinishing)
    }
}
