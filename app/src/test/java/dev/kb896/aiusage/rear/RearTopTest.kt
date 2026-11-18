package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.RearTop.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app on top of the back, asked by binder first (RearTop): the window manager's dump decides every change, the
 * binder only spares the reads that would find nothing new; and one settled read pending at a time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RearTopTest {
    private val launcher = RearCover.LAUNCHER
    private fun dump(rear: String?) = """
        Display: mDisplayId=0 (organized)
          mFocusedApp=ActivityRecord{170990211 u0 com.miui.home/.launcher.Launcher t2}
        Display: mDisplayId=1
          mFocusedApp=${if (rear == null) "null" else "ActivityRecord{222215042 u0 $rear t665}"}
    """.trimIndent()

    @Test fun `the name is the one the window manager prints`() {
        assertEquals(launcher, RearTop.nameOf("com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter.SubScreenLauncher"))
        assertEquals(RearCover.CAMERA, RearTop.nameOf("com.android.camera", "com.android.camera.Camera"))
        assertEquals("another package's class stays whole", "com.example/org.other.Main", RearTop.nameOf("com.example", "org.other.Main"))
        assertEquals("and as the dump's own reading gives it", RearCover.CAMERA, RearCover.focusOf(dump(RearCover.CAMERA), 1))
    }

    @Test fun `the focused root task's top is the app on top, and nothing else is guessed`() {
        assertEquals(RearCover.CAMERA, RearTop.pick(listOf(Task(false, launcher), Task(true, RearCover.CAMERA))))
        assertNull("none focused: the dump decides", RearTop.pick(listOf(Task(false, launcher), Task(false, RearCover.CAMERA))))
        assertNull("the focused one with no activity: the dump decides", RearTop.pick(listOf(Task(true, null), Task(false, launcher))))
        assertNull(RearTop.pick(emptyList()))
    }

    @Test fun `the dump is read for anything new, anything unknown, and once a minute`() {
        assertFalse(RearTop.dumpNeeded(launcher, launcher, 1_000L))
        assertTrue("new", RearTop.dumpNeeded(RearCover.CAMERA, launcher, 1_000L))
        assertTrue("nothing known yet", RearTop.dumpNeeded(launcher, null, 1_000L))
        assertTrue("not asked", RearTop.dumpNeeded(null, launcher, 1_000L))
        assertTrue("checked once a minute", RearTop.dumpNeeded(launcher, launcher, RearTop.AUDIT_MS))
    }

    @Test fun `every change is the dump's answer, and an unchanged one forks nothing`() {
        var now = 1_000_000L
        var onTop: String? = launcher
        var asked: List<Task>? = listOf(Task(true, launcher))
        var dumps = 0
        val r = RearTop.Reader({ cmd -> assertEquals(RearCover.READ, cmd); dumps++; dump(onTop) }, "test", { asked }, { now })

        assertEquals("the first read is the dump's", launcher, r.read(1, null))
        assertEquals(1, dumps)
        now += 250L
        assertEquals("the same by binder: nothing forked", launcher, r.read(1, launcher))
        assertEquals(1, dumps)

        onTop = RearCover.CAMERA; asked = listOf(Task(false, launcher), Task(true, RearCover.CAMERA)); now += 1_000L
        assertEquals("new by binder: the dump read, and its answer taken", RearCover.CAMERA, r.read(1, launcher))
        assertEquals(2, dumps)

        asked = listOf(Task(true, "com.example/.Ahead")); now += 1_000L
        assertEquals("the binder ahead of the dump: the dump's answer", RearCover.CAMERA, r.read(1, RearCover.CAMERA))
        assertEquals(3, dumps)

        asked = null; now += 1_000L
        assertEquals("the binder refused: the dump, as before", RearCover.CAMERA, r.read(1, RearCover.CAMERA))
        assertEquals(4, dumps)

        onTop = null; asked = listOf(Task(false, launcher)); now += 1_000L
        assertNull("no focused app on the back, as the dump says", r.read(1, RearCover.CAMERA))
        assertEquals(5, dumps)
    }

    @Test fun `an unchanged answer that is wrong lasts a minute at most`() {
        var now = 5_000_000L
        val r = RearTop.Reader({ dump(RearCover.CAMERA) }, "test", { listOf(Task(true, launcher)) }, { now })
        assertEquals(RearCover.CAMERA, r.read(1, null))
        now += 1_000L
        assertEquals("the binder's stale agreement with what is known stands", launcher, r.read(1, launcher))
        now += RearTop.AUDIT_MS
        assertEquals("then the dump puts it right", RearCover.CAMERA, r.read(1, launcher))
    }

    @Test fun `a forced read is the dump every time, whatever the binder says`() {
        var now = 7_000_000L
        var onTop: String? = launcher
        var dumps = 0
        var asks = 0
        val r = RearTop.Reader({ dumps++; dump(onTop) }, "test", { asks++; listOf(Task(true, launcher)) }, { now })
        assertEquals(launcher, r.read(1, null))
        assertEquals(1, dumps)
        now += 2_500L
        assertEquals("unforced, the same by binder: nothing forked", launcher, r.read(1, launcher))
        assertEquals(1, dumps)
        // The binder's answer agrees with what is known, the dump does not (a focus the two name differently).
        onTop = RearCover.CAMERA
        now += 2_500L
        assertEquals("forced: the dump's answer at once, not a minute later", RearCover.CAMERA, r.read(1, launcher, force = true))
        assertEquals(2, dumps)
        now += 2_500L
        assertEquals("and at every tick", RearCover.CAMERA, r.read(1, RearCover.CAMERA, force = true))
        assertEquals(3, dumps)
        assertEquals("the binder is not asked for a forced read", 2, asks)
        onTop = launcher
        now += 1_000L
        assertEquals("unforced again: the binder's answer differs from the known one, so the dump", launcher, r.read(1, RearCover.CAMERA))
        assertEquals(4, dumps)
        now += 1_000L
        assertEquals("then the same by binder: nothing forked", launcher, r.read(1, launcher))
        assertEquals(4, dumps)
    }

    @Test fun `one settled read at a time, at the first line's time and the last one's`() {
        val s = RearTop.Settle(250L)
        assertEquals(250L, s.line(0L))
        assertEquals("pending", -1L, s.line(50L))
        assertEquals(-1L, s.line(100L))
        assertEquals("then the last line's, at its own time", 100L, s.ran(250L))
        assertEquals("settled", -1L, s.ran(350L))
        assertEquals("a new burst starts again", 250L, s.line(1_000L))
        assertEquals("a read late on its clock still covers its line", -1L, s.ran(1_260L))
        assertEquals(250L, s.line(2_000L))
        s.dropped()
        assertEquals("a read that could not be scheduled leaves none pending", 250L, s.line(2_010L))
        assertEquals("pending, on time", -1L, s.line(2_010L + 250L + RearTop.Settle.LOST_MS))
        assertEquals("one long past its time is taken as lost", 250L, s.line(2_010L + 250L + RearTop.Settle.LOST_MS + 1L))
    }

    @Test fun `the old per-line reads are all covered`() {
        // Lines at these times read once more 250 ms later each; with one pending, every such read time has one at or after it
        // within the same burst, and the first and the last are made at exactly their own times.
        val lines = listOf(0L, 40L, 90L, 600L, 610L, 1_500L)
        val s = RearTop.Settle(250L)
        val reads = mutableListOf<Long>()
        var next = -1L
        var i = 0
        var t = 0L
        while (i < lines.size || next >= 0L) {
            if (i < lines.size && (next < 0L || lines[i] <= next)) {
                t = lines[i++]
                s.line(t).let { if (it >= 0L) next = t + it }
            } else {
                t = next; reads += t
                s.ran(t).let { next = if (it >= 0L) t + it else -1L }
            }
        }
        assertEquals(listOf(250L, 340L, 850L, 860L, 1_750L), reads)
        for (l in lines) assertTrue("the line at $l", reads.any { it >= l + 250L && it <= l + 250L + 90L })
    }
}
