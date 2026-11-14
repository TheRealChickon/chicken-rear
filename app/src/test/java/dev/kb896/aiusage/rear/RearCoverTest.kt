package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.ui.PinActivity
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RearCoverTest {
    private val launcher = "com.xiaomi.subscreencenter/.SubScreenLauncher"

    @Test fun `Xiaomi's screen is always covered, whatever of Xiaomi's is on the back`() {
        assertTrue(RearCover.wanted(true, launcher))
        assertTrue("its pin page too", RearCover.wanted(true, "com.xiaomi.subscreencenter/.pin.PinActivity"))
        assertTrue("the Camera's blank standby page", RearCover.wanted(true, "com.android.camera/.fragment.presentation.MainScreenSelfieActivity"))
        assertTrue("nothing known yet: black, not Xiaomi's", RearCover.wanted(true, null))
        assertTrue(RearCover.wanted(true, ""))
        assertTrue("a system window's bare name is no app", RearCover.wanted(true, "ScreenOnProximitySensorGuideBack"))
    }

    @Test fun `it steps away only for what is meant to be on the back`() {
        assertFalse("Xiaomi's Camera opened from the back", RearCover.wanted(true, RearCover.CAMERA))
        assertFalse("an app put on the back", RearCover.wanted(true, "com.spotify.music/.MainActivity"))
        assertFalse("with no face of ours wanted, never", RearCover.wanted(false, launcher))
    }

    @Test fun `the app on top of the back is read from the window manager, and the event log says when to read`() {
        val dump = """
              Display: mDisplayId=0 (organized)
              mFocusedApp=ActivityRecord{167742628 u0 com.android.camera/.fragment.presentation.MainScreenSelfieActivity t661}
              mImeWindow=Window{1ece27e u0 InputMethod}  Display: mDisplayId=1 (organized)
              mFocusedApp=ActivityRecord{222215042 u0 com.android.camera/.Camera t665}
        """.trimIndent()
        assertEquals(RearCover.CAMERA, RearCover.focusOf(dump, 1))
        assertEquals("com.android.camera/.fragment.presentation.MainScreenSelfieActivity", RearCover.focusOf(dump, 0))
        assertNull(RearCover.focusOf(dump.replace("mFocusedApp=ActivityRecord{222215042 u0 com.android.camera/.Camera t665}", "mFocusedApp=null"), 1))
        assertNull(RearCover.focusOf(dump, 7))
        assertTrue(RearCover.changed("I/wm_set_resumed_activity( 3832): [0,com.android.camera/.Camera,resumeTopActivity - onActivityStateChanged]"))
        assertTrue(RearCover.changed("I/wm_task_moved( 3832): [665,665,1,1,2]"))
        assertFalse(RearCover.changed("I/ActivityTaskManager( 3832): Displayed com.android.camera/.Camera for user 0: +412ms"))
        assertTrue(RearCover.launcherResumed("I/wm_set_resumed_activity( 3832): [0,com.xiaomi.subscreencenter/.SubScreenLauncher,resumeTopActivity]"))
        assertFalse(RearCover.launcherResumed("I/wm_set_resumed_activity( 3832): [0,com.miui.home/.launcher.Launcher,resumeTopActivity]"))
        assertEquals(launcher, RearCover.LAUNCHER)
    }

    @Test fun `a note pinned goes in front, the same words do not pin twice, and the list has an end`() {
        val a = PinActivity.added(emptyList(), "  Remember  ", 10)
        assertEquals(listOf(Pin("Remember", 10)), a)
        val b = PinActivity.added(a, "Milk", 20)
        assertEquals(listOf("Milk", "Remember"), b.map { it.text })
        assertEquals("pinned again: moved up, once", listOf(Pin("Remember", 30), Pin("Milk", 20)), PinActivity.added(b, "Remember", 30))
        var many = emptyList<Pin>()
        for (i in 1..30) many = PinActivity.added(many, "note $i", i.toLong())
        assertEquals(PinActivity.MOST, many.size)
        assertEquals("note 30", many.first().text)
        assertEquals(PinActivity.LONGEST, PinActivity.added(emptyList(), "x".repeat(5000), 1).first().text.length)
    }

    @Test fun `the bin is where it is drawn, and the notes page is drawn`() {
        assertTrue(PinFace.onBin(876f, 496f))
        assertTrue(PinFace.onBin(830f, 470f))
        assertFalse(PinFace.onBin(600f, 300f))
        File("build").mkdirs()
        for ((name, args) in mapOf("one" to Triple("Remember", 0, 1), "long" to Triple("Collect the tickets on Saturday, counter 3, by six", 1, 3), "none" to Triple("", 0, 0))) {
            val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
            PinFace.draw(Canvas(bmp), 976f, 596f, args.first, "09:41", args.second, args.third)
            File("build/pins-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals("the pin page's ground at the bottom", NoticePop.shade(NoticePop.PIN, 595f) and 0xFFFFFF, bmp.getPixel(100, 595) and 0xFFFFFF)
        }
    }
}
