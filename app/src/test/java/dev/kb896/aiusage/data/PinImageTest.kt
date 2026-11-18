package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pinned picture's pure rules: the size it is kept at, its file's name, which files go with their notes, the sweep. */
class PinImageTest {

    @Test fun `a picture is kept fitted into the notes page's picture place, in its own shape, never larger than it is`() {
        assertEquals(478 to 359, PinImage.fit(4000, 3000))
        assertEquals(321 to 428, PinImage.fit(3000, 4000))
        // A phone's own portrait photo, turned: fitted by its height.
        assertEquals(321 to 428, PinImage.fit(3072, 4096))
        // Small: as it is.
        assertEquals(200 to 100, PinImage.fit(200, 100))
        assertEquals(478 to 428, PinImage.fit(478, 428))
        // A panorama keeps at least a pixel of height; a picture with no size has none.
        assertEquals(478 to 1, PinImage.fit(10_000, 10))
        assertNull(PinImage.fit(0, 100)); assertNull(PinImage.fit(100, -1))
        // Never over the place, whatever the shape.
        for (w in listOf(1, 7, 333, 479, 1000, 4033, 9999)) for (h in listOf(1, 5, 427, 429, 2000, 7777)) {
            val (fw, fh) = PinImage.fit(w, h)!!
            assertTrue("$w x $h -> $fw x $fh", fw in 1..PinImage.MAX_W && fh in 1..PinImage.MAX_H && fw <= w && fh <= h)
            // Its shape kept (within a pixel's rounding).
            if (fw > 4 && fh > 4) assertEquals(w.toDouble() / h, fw.toDouble() / fh, (w.toDouble() / h) * (2.0 / minOf(fw, fh)))
        }
    }

    @Test fun `the file's name is the time and the size, and nothing from outside can name a path`() {
        val n = PinImage.name(1_763_140_000_000L, 478, 359, alpha = false)
        assertEquals("1763140000000-478x359.jpg", n)
        assertEquals("1763140000000-20x30.webp", PinImage.name(1_763_140_000_000L, 20, 30, alpha = true))
        assertTrue(PinImage.valid(n))
        assertEquals(478 to 359, PinImage.size(n))
        assertEquals("image/jpeg", PinImage.mime(n)); assertEquals("image/webp", PinImage.mime("1-2x3.webp"))
        for (bad in listOf(null, "", "../1-2x3.jpg", "pins/1-2x3.jpg", "/sdcard/1-2x3.jpg", "1-2x3.png", "1-2x3.jpg.part", "x-2x3.jpg", "1-0x3.jpg")) {
            assertFalse("$bad", PinImage.valid(bad) && PinImage.size(bad) != null)
        }
        assertNull(PinImage.size("1-0x3.jpg"))
    }

    @Test fun `a picture's file goes when its note goes, and only then`() {
        val a = Pin("", 1, image = "1-10x10.jpg"); val b = Pin("cap", 2, image = "2-10x10.jpg"); val words = Pin("words", 3)
        assertEquals(setOf("2-10x10.jpg"), PinImage.gone(listOf(a, b, words), listOf(a, words)))
        assertEquals(emptySet<String>(), PinImage.gone(listOf(a, b), listOf(b, a)))
        assertEquals(setOf("1-10x10.jpg", "2-10x10.jpg"), PinImage.gone(listOf(a, b, words), emptyList()))
        // The same file kept by another note (a note moved, its place changed) is not gone.
        assertEquals(emptySet<String>(), PinImage.gone(listOf(a), listOf(a.copy(at = 9, lat = 1.0, lng = 2.0))))
        // A name that is not one of ours is never handed on to be deleted.
        assertEquals(emptySet<String>(), PinImage.gone(listOf(Pin("", 4, image = "../../shared_prefs/x.xml")), emptyList()))
    }

    @Test fun `the sweep takes old files no note has, and leaves the young, the kept and what is not ours`() {
        val now = 10_000_000L
        val old = now - PinImage.YOUNG_MS - 1
        val files = listOf(
            "1-10x10.jpg" to old,          // a note has it
            "2-10x10.jpg" to old,          // no note: swept
            "3-10x10.webp" to now - 1_000, // no note, but being read right now
            "4-10x10.jpg" to old,          // the place prompt's waiting picture
            "notes.txt" to old,            // not ours
        )
        val pins = listOf(Pin("", 1, image = "1-10x10.jpg"), Pin("words", 5))
        assertEquals(listOf("2-10x10.jpg"), PinImage.orphans(files, pins, keep = setOf("4-10x10.jpg"), now = now))
    }

    @Test fun `a note's picture crosses in the stored settings and the payload, and older notes have none`() {
        val old = PanelConfig.decode("""{"pins":[{"text":"milk","at":5}]}""").pins.single()
        assertNull(old.image); assertFalse(old.pictured)
        val pin = Pin("A caption", 7, image = "7-300x200.jpg")
        val back = PanelConfig.decode(PanelConfig.encode(PanelConfig.DEFAULT.copy(pins = listOf(pin, Pin("words", 6))))).pins
        assertEquals("7-300x200.jpg", back[0].image); assertTrue(back[0].pictured)
        assertNull(back[1].image)
        // What the back is sent (StatePayload) carries the name only, and the back has it as the app does.
        val payload = StatePayload.encode(DashboardState.EMPTY, PanelConfig.DEFAULT.copy(pins = listOf(pin)))
        assertTrue(payload.contains("\"image\":\"7-300x200.jpg\""))
        assertEquals(pin, StatePayload.decode(payload).config.pins.single())
    }
}
