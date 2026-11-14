package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ManualOffTest {
    private fun file() = File(Files.createTempDirectory("manual-off").toFile(), ManualOff.FILE)

    @Test fun `off until set, and set until cleared`() {
        val m = ManualOff(file())
        assertFalse(m.on)
        assertTrue(m.set(true))
        assertTrue(m.on)
        assertFalse(m.set(true)) // no change
        assertTrue(m.set(false))
        assertFalse(m.on)
        assertFalse(m.set(false))
    }

    @Test fun `outlives the process - another reader of the same file sees it`() {
        val f = file()
        ManualOff(f).set(true)
        assertTrue(ManualOff(f).on)
        ManualOff(f).set(false)
        assertFalse(ManualOff(f).on)
    }

    @Test fun `companion - lit to dark right after a double tap is switched off by hand`() {
        assertTrue(ManualOff.byDoubleTap(wasLit = true, nowLit = false, doubleTapAt = 10_000L, now = 10_400L, guarded = false))
        assertTrue(ManualOff.byDoubleTap(true, false, 10_000L, 10_000L + ManualOff.DOUBLE_TAP_DARK_MS, false))
    }

    @Test fun `companion - dark long after the double tap is Xiaomi's timeout, not the user`() {
        assertFalse(ManualOff.byDoubleTap(true, false, 10_000L, 10_001L + ManualOff.DOUBLE_TAP_DARK_MS, false))
    }

    @Test fun `companion - no double tap, still lit, already dark, or the tap left to the app is no latch`() {
        assertFalse(ManualOff.byDoubleTap(true, false, doubleTapAt = 0L, now = 400L, guarded = false))
        assertFalse(ManualOff.byDoubleTap(true, true, 10_000L, 10_200L, false))
        assertFalse(ManualOff.byDoubleTap(false, false, 10_000L, 10_200L, false))
        assertFalse(ManualOff.byDoubleTap(true, false, 10_000L, 10_200L, guarded = true))
        // A double tap read after the edge (clock order) is not its cause.
        assertFalse(ManualOff.byDoubleTap(true, false, 10_300L, 10_200L, false))
    }
}
