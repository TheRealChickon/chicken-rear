package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the review of the face picker found, held: the moon after a Xiaomi face is not under that face's last picture;
 * the picker never stays up by itself (left alone, an exit whose frames stop); a choice in the settings is never older
 * than one made on the back; a face that failed once keeps his choice.
 */
class PickerReviewFixesTest {
    private val rear = File("src/main/java/dev/kb896/aiusage/rear")
    private fun src(name: String) = File(rear, name).readText()

    // -- 1. the moon and a Xiaomi face are two layers at one z --------------------------------------------------------

    @Test fun `whichever shows hides the other`() {
        val moon = Any(); val face = Any()
        assertEquals(listOf(face), ShellScene.Swap.hiddenWith(ShellScene.Swap.Layer.MOON, layer = moon, face = face))
        assertEquals(listOf(moon), ShellScene.Swap.hiddenWith(ShellScene.Swap.Layer.FACE, layer = moon, face = face))
        // No face layer made yet: nothing to hide.
        assertEquals(emptyList<Any>(), ShellScene.Swap.hiddenWith(ShellScene.Swap.Layer.MOON, layer = moon, face = null))
    }

    @Test fun `the moon's first placement and a face's first frame both use it`() {
        val s = src("ShellScene.kt")
        val show = s.substringAfter("private fun show(r: Request").substringBefore("private fun faceLayerOrNull")
        assertTrue("the moon's first placement hides the face's layer", show.contains("Swap.hiddenWith(Swap.Layer.MOON"))
        val face = s.substringAfter("private fun showFace(").substringBefore("object Swap")
        assertTrue("a face's first frame hides the moon's layer", face.contains("Swap.hiddenWith(Swap.Layer.FACE"))
        // A failed face is followed by the moon, not an empty back.
        assertTrue(s.contains("r.faceFailed && r.dismissAt == 0L && lit()"))
    }

    // -- 4 and 5. the picker never stays up by itself ------------------------------------------------------------------

    @Test fun `left alone it leaves, counted from the last finger landing or lifting`() {
        val w = PickerWatch()
        assertEquals(PickerWatch.Due.NOTHING, w.due(0)); assertNull(w.nextAt())
        w.opened(1_000)
        assertEquals(1_000 + PickerWatch.ALONE_MS, w.nextAt())
        assertEquals(PickerWatch.Due.NOTHING, w.due(1_000 + PickerWatch.ALONE_MS - 1))
        w.touched(5_000)
        assertEquals(PickerWatch.Due.NOTHING, w.due(1_000 + PickerWatch.ALONE_MS))
        assertEquals(PickerWatch.Due.LEAVE, w.due(5_000 + PickerWatch.ALONE_MS))
        // The hold's finger resting on the back from the opening on: it leaves all the same.
        val resting = PickerWatch(); resting.opened(0)
        assertEquals(PickerWatch.Due.LEAVE, resting.due(PickerWatch.ALONE_MS))
    }

    @Test fun `its exit is gone by the deadline whatever the frames did, and the springs end well before it`() {
        val w = PickerWatch()
        w.opened(0); w.shut(3_000)
        w.touched(3_500)   // a touch in the exit counts for nothing
        assertEquals(3_000 + PickerWatch.EXIT_DEADLINE_MS, w.nextAt())
        assertEquals(PickerWatch.Due.NOTHING, w.due(3_000 + PickerWatch.EXIT_DEADLINE_MS - 1))
        assertEquals(PickerWatch.Due.GONE, w.due(3_000 + PickerWatch.EXIT_DEADLINE_MS))
        w.gone()
        assertEquals(PickerWatch.Due.NOTHING, w.due(100_000)); assertNull(w.nextAt())
        // The real exit, with a new face to build (the longest one): over in well under the deadline at 120 Hz.
        for (pick in listOf(true, false)) {
            val p = FacePicker(5, 2); repeat(240) { p.step(1.0 / 120) }
            p.grab(); p.drag(-400.0); p.release(-800.0); repeat(120) { p.step(1.0 / 120) }
            p.shut(pick)
            var t = 0.0
            while (p.phase != FacePicker.Phase.GONE && t < 10.0) { p.step(1.0 / 120); t += 1.0 / 120 }
            assertEquals(FacePicker.Phase.GONE, p.phase)
            assertTrue("exit ${if (pick) "with" else "without"} a new face took $t s", t * 1000 < PickerWatch.EXIT_DEADLINE_MS / 2)
        }
    }

    @Test fun `the picker's frames do not hang on the vsync alone, and a frame that throws drops it`() {
        val s = src("ShellPicker.kt")
        assertTrue(s.contains("handler.postDelayed(frameFallback, PickerWatch.FRAME_FALLBACK_MS)"))
        assertTrue(s.contains("private fun safeFrame("))
        assertTrue("the vsync's frames go through safeFrame", s.contains("Choreographer.FrameCallback { nanos -> handler.removeCallbacks(frameFallback); safeFrame(nanos) }"))
        assertTrue("the watch is armed as it opens", s.contains("watch.opened(SystemClock.uptimeMillis()); rewatch()"))
        assertTrue("and as it shuts", s.contains("watch.shut(SystemClock.uptimeMillis()); rewatch()"))
    }

    // -- 6. a choice in the settings is never older than one made on the back ------------------------------------------

    @Test fun `a choice is numbered by the clock, and the later tap wins wherever it was made`() {
        assertEquals(1_700_000_000_000L, FaceCatalog.nextSeq(0L, 1_700_000_000_000L))
        assertEquals("never below the last plus one", 501L, FaceCatalog.nextSeq(500L, 100L))
        // The shell's old count ran ahead of the app's (its copy outlives the app's data): the app's next tap still wins.
        var shellSeq = 7L
        val shellKey = "xiaomi:a"
        val appSeq = FaceCatalog.nextSeq(2L, 1_700_000_000_000L)
        assertTrue(appSeq > shellSeq)
        // The shell's rule for a payload (RearUserService.faceFromPayload): followed unless older than its own.
        fun follows(seq: Long, key: String) = !(seq < shellSeq || seq == shellSeq && key == shellKey)
        assertTrue(follows(appSeq, FaceCatalog.MOON))
        // A tap on the back after it, then the settings again a second later: each the latest.
        shellSeq = FaceCatalog.nextSeq(appSeq, 1_700_000_000_500L)
        assertTrue(shellSeq > appSeq)
        assertTrue(follows(FaceCatalog.nextSeq(appSeq, 1_700_000_001_500L), FaceCatalog.MOON))
        // Both sides use it, and the shell tells the app its choice when the app connects.
        assertTrue(src("RearUserService.kt").contains("faceSeq = FaceCatalog.nextSeq(faceSeq, System.currentTimeMillis())"))
        assertTrue(src("RearUserService.kt").contains("runCatching { events.onFaceChosen(k, n) }"))
        assertTrue(File("src/main/java/dev/kb896/aiusage/ui/home/HomeScreen.kt").readText().contains("FaceCatalog.nextSeq(it.faceSeq, System.currentTimeMillis())"))
    }

    // -- 7. a face that failed once keeps his choice ---------------------------------------------------------------

    private fun face(key: String, leftOut: String? = null, pending: Boolean = false) = FaceCatalog.Face(
        key, if (key == FaceCatalog.MOON) FaceCatalog.Source.OURS else FaceCatalog.Source.PREINSTALLED, mapOf("en_US" to key),
        null, if (key == FaceCatalog.MOON) null else "/x/$key", "sha-$key", 0L, null, null, false, false, null, leftOut, pending,
    )

    @Test fun `failed in this process - the moon shows and the choice is kept`() {
        val k = "xiaomi:stretch"
        val was = listOf(face(FaceCatalog.MOON), face(k))
        for (why in listOf("failed: a first frame that stays empty", "failed: the engine's thread did not answer in 5000 ms")) {
            assertEquals(FaceCatalog.AfterScan.SHOW_MOON, FaceCatalog.afterScan(k, listOf(face(FaceCatalog.MOON), face(k, why)), was))
        }
        // Still out at the next scan: nothing more.
        val out = listOf(face(FaceCatalog.MOON), face(k, "failed: x"))
        assertEquals(FaceCatalog.AfterScan.KEEP, FaceCatalog.afterScan(k, out, out))
        // Offered again (a new shell process): it shows.
        assertEquals(FaceCatalog.AfterScan.SHOW_IT, FaceCatalog.afterScan(k, was, out))
        // Waiting for its check: kept.
        assertEquals(FaceCatalog.AfterScan.KEEP, FaceCatalog.afterScan(k, listOf(face(FaceCatalog.MOON), face(k, "not drawn yet", pending = true)), listOf(face(FaceCatalog.MOON))))
    }

    @Test fun `gone from the phone, or a crash, takes the choice back to the moon`() {
        val k = "xiaomi:stretch"
        val was = listOf(face(FaceCatalog.MOON), face(k))
        assertEquals(FaceCatalog.AfterScan.BACK_TO_MOON, FaceCatalog.afterScan(k, listOf(face(FaceCatalog.MOON)), was))
        assertEquals(FaceCatalog.AfterScan.BACK_TO_MOON, FaceCatalog.afterScan(k, listOf(face(FaceCatalog.MOON), face(k, "failed: ${FaceCatalog.CRASHED}")), was))
        assertEquals(FaceCatalog.AfterScan.KEEP, FaceCatalog.afterScan(FaceCatalog.MOON, listOf(face(FaceCatalog.MOON)), was))
    }
}
