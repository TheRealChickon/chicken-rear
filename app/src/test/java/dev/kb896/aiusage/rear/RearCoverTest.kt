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
        assertTrue("nothing known yet: black, not Xiaomi's", RearCover.wanted(true, null))
        assertTrue(RearCover.wanted(true, ""))
        assertTrue("a system window's bare name is no app", RearCover.wanted(true, "ScreenOnProximitySensorGuideBack"))
    }

    @Test fun `it steps away only for what is meant to be on the back`() {
        val his = setOf("com.spotify.music")
        assertFalse("Xiaomi's Camera opened from the back", RearCover.wanted(true, RearCover.CAMERA, his))
        assertFalse("an app he put on the back", RearCover.wanted(true, "com.spotify.music/.MainActivity", his))
        assertFalse("this app's own page", RearCover.wanted(true, "${RearCover.OWN}/dev.kb896.aiusage.rear.RearActivity"))
        assertEquals("the debug build's package", "dev.chicken.rear.dev", RearCover.OWN)
        assertFalse("with no face of ours wanted, never", RearCover.wanted(false, launcher))
    }

    @Test fun `an allowlist - anything not named stays covered`() {
        val his = setOf("com.spotify.music", "com.xiaomi.subscreencenter")
        for (top in listOf(
            "com.miui.home/.launcher.SecondaryDisplayLauncher",
            "com.spotify.music/.MainActivity".replace("spotify", "unknown"),
            "com.xiaomi.subscreencenter/.SubScreenLauncher",
            "org.example.newrearpage/.Page",
            "", "   ",
        )) assertTrue("covered: '$top'", RearCover.wanted(true, top, his))
        assertTrue("null", RearCover.wanted(true, null, his))
        assertTrue("an app he did not put there", RearCover.wanted(true, "com.whatsapp/.Main", his))
        assertTrue("Xiaomi's rear app even if a list names it", RearCover.wanted(true, "com.xiaomi.subscreencenter/.pin.PinActivity", his))
        assertFalse("another Camera activity", RearCover.wanted(true, "com.android.camera/.CameraSettingsActivity", emptySet()))
    }

    @Test fun `the hint file - a missing, empty or old one means cover, only 0 lets Xiaomi's back be`() {
        assertEquals(RearCover.Hint(true, emptySet()), RearCover.Hint.parse(null))
        assertEquals("touched by an install", RearCover.Hint(true, emptySet()), RearCover.Hint.parse(""))
        assertEquals("the old format", RearCover.Hint(true, emptySet()), RearCover.Hint.parse("1"))
        assertEquals(RearCover.Hint(true, emptySet()), RearCover.Hint.parse("garbage"))
        assertEquals(RearCover.Hint(false, emptySet()), RearCover.Hint.parse("0\n"))
        assertEquals(RearCover.Hint(false, emptySet()), RearCover.Hint.parse("0\ncom.spotify.music\n"))
        val h = RearCover.Hint(true, setOf("com.spotify.music", "org.telegram.messenger"))
        assertEquals(h, RearCover.Hint.parse(h.text()))
        assertEquals("1 1000\ncom.spotify.music\norg.telegram.messenger\n", h.text(now = 1_000L, pid = null))
        assertEquals("1 1000 pid=4242\ncom.spotify.music\norg.telegram.messenger\n", h.text(now = 1_000L, pid = 4242))
        assertEquals("the old form of it", h, RearCover.Hint.parse("1\ncom.spotify.music\norg.telegram.messenger\n"))
        assertEquals("what is no package is dropped", setOf("com.a.b"), RearCover.Hint.parse("1\ncom.a.b\n../etc\nrm -rf\n").allow)
        assertEquals("0 1000\n", RearCover.Hint(false, setOf("com.a.b")).text(now = 1_000L, pid = null))
        assertEquals("0 1000 pid=4242\n", RearCover.Hint(false, setOf("com.a.b")).text(now = 1_000L, pid = 4242))
        val dir = File("build/hint-test").apply { mkdirs() }
        assertEquals("no file at all", RearCover.Hint(true, emptySet()), RearCover.Hint.read(File(dir, "absent").path))
        File(dir, "h").writeText(h.text())
        assertEquals(h, RearCover.Hint.read(File(dir, "h").path))
    }

    @Test fun `an install's touch on a file that says 0 means cover - touch keeps the text, not the time`() {
        val off = RearCover.Hint(false, emptySet())
        val written = 1_700_000_000_000L
        val text = off.text(now = written)
        assertEquals("as written: Xiaomi's back may be", off, RearCover.Hint.parse(text, modifiedMs = written + 5L))
        assertEquals("seconds-only file times are older, not newer", off, RearCover.Hint.parse(text, modifiedMs = written - 999L))
        assertEquals("touched a minute later: cover", RearCover.Hint(true, emptySet()), RearCover.Hint.parse(text, modifiedMs = written + 60_000L))
        assertEquals("touched just past the slack", RearCover.Hint(true, emptySet()), RearCover.Hint.parse(text, modifiedMs = written + RearCover.Hint.TOUCHED_MS + 1L))
        assertEquals("no file time known: as written", off, RearCover.Hint.parse(text))
        val f = File(File("build/hint-test").apply { mkdirs() }, "touched")
        f.writeText(RearCover.Hint(false, emptySet()).text())
        assertEquals("just written", off, RearCover.Hint.read(f.path))
        assertTrue(f.setLastModified(System.currentTimeMillis() + 30_000L))
        assertEquals("touched", RearCover.Hint(true, emptySet()), RearCover.Hint.read(f.path))
        assertEquals("the guard then covers Xiaomi's launcher", true, RearCover.Hint.read(f.path).let { RearCover.wanted(it.face, launcher, it.allow) })
    }

    @Test fun `the hint names the process that wrote it, and every reader, old or new, reads the rest as before`() {
        val on = RearCover.Hint(true, setOf("com.spotify.music"))
        val off = RearCover.Hint(false, emptySet())
        for (h in listOf(on, off)) {
            val text = h.text(now = 1_700_000_000_000L, pid = 4242)
            assertEquals("the pid", 4242, RearCover.Hint.writerOf(text))
            // parse is the reading every guard and main process has had: the pid and the time are words it skips.
            assertEquals("read as before", h, RearCover.Hint.parse(text, modifiedMs = 1_700_000_000_000L + 5L))
            assertEquals("no pid asked for, none written", null, RearCover.Hint.writerOf(h.text(now = 1L, pid = null)))
        }
        assertEquals("a 0 with a pid, touched by an install: cover", RearCover.Hint(true, emptySet()),
            RearCover.Hint.parse(off.text(now = 1_000L, pid = 7), modifiedMs = 61_000L))
        for (old in listOf(null, "", "1", "1\ncom.a.b\n", "0\n", "0 1000\n", "garbage", "pid=", "1 pid=0\n", "1 pid=-3\n"))
            assertNull("no writer known: '$old'", RearCover.Hint.writerOf(old))
        assertNull("only the first line says who wrote it", RearCover.Hint.writerOf("1 1000\npid=55\n"))
        assertEquals("written by this process when no pid is given", android.os.Process.myPid().takeIf { it > 0 }, RearCover.Hint.writerOf(on.text()))
    }

    @Test fun `the guard polls the window manager only while the main shell is not there`() {
        assertEquals("dev.chicken.rear.dev:rear", RearCover.MAIN_PROCESS)
        assertTrue(RearCover.isMainShell("dev.chicken.rear.dev:rear\u0000"))
        assertTrue(RearCover.isMainShell("dev.chicken.rear.dev:rear"))
        assertFalse("the guard itself", RearCover.isMainShell("dev.chicken.rear.dev:guard\u0000"))
        assertFalse("the app's own process", RearCover.isMainShell("dev.chicken.rear.dev\u0000"))
        assertFalse("its pid given to another process since", RearCover.isMainShell("/system/bin/sh\u0000-c\u0000dev.chicken.rear.dev:rear\u0000"))
        assertFalse("no such process", RearCover.isMainShell(null))
        assertFalse(RearCover.isMainShell(""))
        assertTrue("no main shell: on its own clock", RearCover.guardPolls(mainAlive = false))
        assertFalse("the main shell lives: not", RearCover.guardPolls(mainAlive = true))
        assertTrue(RearCover.guardReads(mainAlive = false, hintChanged = false))
        assertTrue(RearCover.guardReads(mainAlive = false, hintChanged = true))
        assertTrue("the main shell's cover went up or away: read", RearCover.guardReads(mainAlive = true, hintChanged = true))
        assertFalse("nothing changed and the main shell lives: no fork", RearCover.guardReads(mainAlive = true, hintChanged = false))
    }

    @Test fun `while the main process steps away for the guest, the guard is told what is on top in the guest's task`() {
        val pinned = setOf("com.spotify.music")
        val prompt = "com.google.android.permissioncontroller/com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"
        // The main process: the guest is on top (by its task), so it steps away whatever package that is.
        val hint = RearCover.Hint(true, RearCover.hintAllow(pinned, guestOnTop = true, top = prompt))
        val guard = RearCover.Hint.parse(hint.text())
        assertFalse("the guard steps away for the prompt in the guest's task", RearCover.wanted(guard.face, prompt, guard.allow))
        assertFalse("the system's chooser", RearCover.wanted(true, "android/com.android.internal.app.ChooserActivity", RearCover.hintAllow(pinned, true, "android/com.android.internal.app.ChooserActivity")))
        assertTrue("the guard still covers Xiaomi's launcher", RearCover.wanted(guard.face, launcher, guard.allow))
        assertEquals("no guest on top: the list as it is", pinned, RearCover.hintAllow(pinned, guestOnTop = false, top = prompt))
        assertEquals("never Xiaomi's rear app", pinned, RearCover.hintAllow(pinned, true, launcher))
        assertEquals("a bare window name is no package", pinned, RearCover.hintAllow(pinned, true, "ScreenOnProximitySensorGuideBack"))
        assertEquals(pinned, RearCover.hintAllow(pinned, true, null))
    }

    @Test fun `the app on top of the back is read from the window manager, and the event log says when to read`() {
        val dump = """
              Display: mDisplayId=0 (organized)
              mImeWindow=Window{1ece27e u0 InputMethod}  Display: mDisplayId=1 (organized)
              mFocusedApp=ActivityRecord{222215042 u0 com.android.camera/.Camera t665}
        """.trimIndent()
        assertEquals(RearCover.CAMERA, RearCover.focusOf(dump, 1))
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
