package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.CameraWord.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera service's word on open cameras (CameraWord): believed only once its dump has said the same. */
class CameraWordTest {
    // The probe's lines, in the camera service's dump format (a made-up phone with three cameras).
    private fun dump(open: Set<String>, ids: List<String> = listOf("0", "1", "2")) = ids.joinToString("\n") { id ->
        "== Camera device $id dynamic info: ==\n  " + if (id in open) "Device $id is open. Client instance dump:" else "Device $id is closed, no client instance"
    }

    @Test fun `open is what the old count said`() {
        assertTrue(CameraWord.isOpen(dump(setOf("1"))))
        assertFalse(CameraWord.isOpen(dump(emptySet())))
        assertFalse("no dump", CameraWord.isOpen(""))
        assertFalse("an error in place of the dump", CameraWord.isOpen("Can't find service: media.camera"))
        assertEquals(setOf("0", "2"), CameraWord.openIn(dump(setOf("0", "2"))))
        assertEquals(setOf("0", "1", "2"), CameraWord.listedIn(dump(setOf("1"))))
        assertEquals("named by the closed lines alone too", setOf("0", "1"), CameraWord.listedIn("Device 0 is closed, no client instance\nDevice 1 is open. Client"))
    }

    @Test fun `the verdict`() {
        val all = setOf("0", "1", "2")
        assertEquals(Verdict.TRUST, CameraWord.verdict(all, setOf("1"), all, setOf("1")))
        assertEquals("nothing open: nothing seen yet", Verdict.NOT_YET, CameraWord.verdict(all, emptySet(), all, emptySet()))
        assertEquals("the word a moment behind", Verdict.NOT_YET, CameraWord.verdict(all, setOf("1"), all, emptySet()))
        assertEquals("the word a moment ahead", Verdict.NOT_YET, CameraWord.verdict(all, setOf("1"), all, setOf("1", "2")))
        assertEquals("a camera the word does not know", Verdict.NEVER, CameraWord.verdict(all + "20", setOf("20"), all, emptySet()))
        assertEquals("the word not heard yet", Verdict.NOT_YET, CameraWord.verdict(all, setOf("1"), emptySet(), emptySet()))
        assertEquals("a dump that names only the open ones never proves it", Verdict.NOT_YET, CameraWord.verdict(setOf("1"), setOf("1"), all, setOf("1")))
        assertEquals("no dump", Verdict.NOT_YET, CameraWord.verdict(emptySet(), emptySet(), all, emptySet()))
    }

    @Test fun `believed after one open both say, and then it is the word while it says none is open`() {
        val w = CameraWord()
        var now = 1_000L
        for (id in listOf("0", "1", "2")) w.available(id)
        assertFalse("not believed: the dump", w.byWord(now))
        assertEquals(Verdict.NOT_YET, w.held(dump(emptySet()), now))
        assertFalse(w.trusted)
        w.unavailable("1")
        assertTrue(w.open())
        now += 2_000L
        assertEquals(Verdict.TRUST, w.held(dump(setOf("1")), now))
        assertTrue(w.trusted)
        assertFalse("a camera open by the word: the dump all the same", w.byWord(now + 2_000L))
        now += 2_000L
        assertNull("the dump agrees", w.held(dump(setOf("1")), now))
        w.available("1")
        assertFalse(w.open())
        assertTrue("none open by the word: the word alone", w.byWord(now + 1_000L))
        assertTrue(w.trusted)
    }

    @Test fun `a camera gone unavailable while the dump has it closed is not open, and the word is dropped`() {
        val w = believed()
        // The camera service restarting (every camera NOT_PRESENT for a moment), or one unplugged: "unavailable" by the word.
        w.unavailable("2")
        assertTrue(w.open())
        assertFalse("open by the word: the dump is read", w.byWord(T + 1_000L))
        val dump = dump(emptySet())
        assertEquals(Verdict.DROPPED, w.held(dump, T + 1_000L))
        assertFalse("and its answer taken", CameraWord.isOpen(dump))
        assertFalse(w.trusted)
        assertTrue(w.blind)
        w.available("2")
        assertFalse("never believed again", w.byWord(T + 2_000L))
        assertNull(w.held(dump(setOf("1")), T + 3_000L))
        assertFalse(w.trusted)
    }

    @Test fun `a believed word that says none is open is checked against the dump once a minute`() {
        val w = believed()
        assertTrue(w.byWord(T + CameraWord.AUDIT_MS - 1L))
        assertFalse("a minute after the last dump: the dump", w.byWord(T + CameraWord.AUDIT_MS))
        assertNull("it agrees: still believed", w.held(dump(emptySet()), T + CameraWord.AUDIT_MS))
        assertTrue("and the next minute counts from it", w.byWord(T + 2 * CameraWord.AUDIT_MS - 1L))
        // An open the word was not told of (a lost status, a camera it does not know).
        val dump = dump(setOf("0"))
        assertEquals(Verdict.DROPPED, w.held(dump, T + 2 * CameraWord.AUDIT_MS))
        assertTrue("its answer taken", CameraWord.isOpen(dump))
        assertFalse(w.byWord(T + 2 * CameraWord.AUDIT_MS + 1L))
    }

    @Test fun `dropped by hand, it is never believed again`() {
        val w = believed()
        w.distrust()
        assertFalse(w.trusted)
        assertTrue(w.blind)
        assertFalse(w.byWord(T + 1L))
    }

    @Test fun `never believed when the dump has cameras it does not tell`() {
        val w = CameraWord()
        for (id in listOf("0", "1")) w.available(id)
        assertEquals(Verdict.NEVER, w.held(dump(setOf("2")), 0L))
        assertTrue(w.blind)
        assertFalse(w.trusted)
        w.unavailable("0")
        assertNull(w.held(dump(setOf("0"), listOf("0", "1")), 1L))
        assertFalse("stays unbelieved", w.trusted)
        assertFalse(w.byWord(2L))
    }

    private val T = 10_000_000L

    /** A word believed at [T] (camera 1 opened and closed again), none open now. */
    private fun believed(): CameraWord {
        val w = CameraWord()
        for (id in listOf("0", "1", "2")) w.available(id)
        w.unavailable("1")
        assertEquals(Verdict.TRUST, w.held(dump(setOf("1")), T - 2_000L))
        w.available("1")
        assertNull(w.held(dump(emptySet()), T))
        assertTrue(w.byWord(T))
        return w
    }

    @Test fun `only cameras named by digits count, as the old count`() {
        val w = CameraWord()
        w.unavailable("ext-usb")
        assertFalse(w.open())
    }
}
