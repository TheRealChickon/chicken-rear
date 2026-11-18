package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The front's larger copy of a pinned picture: its size rule, when it is kept at all, and the steps the back's copy is made through. */
class PinImageFrontTest {

    @Test fun `the front's copy is at most 2400 on its longest side, in its own shape, never larger than the picture`() {
        assertEquals(2400 to 1800, PinImage.fitFront(4000, 3000))
        assertEquals(1800 to 2400, PinImage.fitFront(3000, 4000))
        assertEquals(2400 to 1800, PinImage.fitFront(2400, 1800))
        assertEquals(1600 to 1200, PinImage.fitFront(1600, 1200))
        assertEquals(2400 to 2, PinImage.fitFront(12_000, 10))
        assertNull(PinImage.fitFront(0, 10))
        for (w in listOf(1, 640, 2399, 2401, 4032, 8000, 12_000)) for (h in listOf(1, 480, 2400, 3024, 9000)) {
            val (fw, fh) = PinImage.fitFront(w, h)!!
            assertTrue("$w x $h -> $fw x $fh", maxOf(fw, fh) <= PinImage.FRONT_MAX && fw <= w && fh <= h && fw >= 1 && fh >= 1)
        }
        // Larger than the back's copy for a photo of a phone's (so it is worth keeping), and the back's is the page's.
        val (bw, bh) = PinImage.fit(2400, 1800)!!
        assertEquals(478 to 359, bw to bh)
        assertTrue(PinImage.frontWorth(2400, 1800, bw, bh))
        // A picture no larger than its back copy has no front copy: the front shows the back's.
        assertFalse(PinImage.frontWorth(300, 200, 300, 200))
        assertTrue(PinImage.frontWorth(479, 300, 478, 299))
    }

    @Test fun `the back's copy is made from the front's by halves, then the last step to its size`() {
        assertEquals(listOf(1200 to 900, 600 to 450, 478 to 359), PinImage.halvings(2400, 1800, 478, 359))
        assertEquals(listOf(478 to 359), PinImage.halvings(600, 450, 478, 359))
        // Exactly half: one step.
        assertEquals(listOf(478 to 359), PinImage.halvings(956, 718, 478, 359))
        assertTrue(PinImage.halvings(478, 359, 478, 359).isEmpty())
        assertTrue(PinImage.halvings(0, 10, 5, 5).isEmpty())
        // No step takes it down by more than half, and it ends where it is meant to.
        for ((w, h) in listOf(2400 to 1800, 1800 to 2400, 2400 to 2, 1201 to 999, 4000 to 4000)) {
            val (tw, th) = PinImage.fit(w, h)!!
            val steps = PinImage.halvings(w, h, tw, th)
            assertEquals(tw to th, steps.last())
            var pw = w; var ph = h
            for ((sw, sh) in steps) {
                assertTrue("$w x $h: $pw x $ph -> $sw x $sh", sw * 2 >= pw - 1 && sh * 2 >= ph - 1 || (sw == tw && sh == th))
                pw = sw; ph = sh
            }
        }
    }

    @Test fun `both copies have one name, and the sweep's rule holds for the front's folder too`() {
        // The front's copy is found by the back's name: one name, two folders.
        assertTrue(PinImage.valid(PinImage.name(1_000L, 478, 359, false)))
        val now = 100_000_000L
        val files = listOf("1-478x359.jpg" to 0L, "2-478x359.jpg" to 0L, "3-478x359.jpg" to now - 1_000L, "x.jpg.part" to 0L, "notes.txt" to 0L)
        val pins = listOf(Pin("", 1, image = "1-478x359.jpg"))
        assertEquals(listOf("2-478x359.jpg"), PinImage.orphans(files, pins, emptySet(), now))
    }
}
