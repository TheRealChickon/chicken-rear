package dev.kb896.aiusage.place

import dev.kb896.aiusage.data.Pin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pictures' notes in the list: never the same note twice, several at once, the twenty-note limit. */
class PinListPicturesTest {

    @Test fun `a picture's note is never replaced, nor does it replace a note of words with its caption`() {
        var pins = PinList.added(emptyList(), "Look", 1).pins
        pins = PinList.added(pins, "Look", 2, image = "2-10x10.jpg").pins
        pins = PinList.added(pins, "Look", 3, image = "3-10x10.jpg").pins
        pins = PinList.added(pins, "", 4, image = "4-10x10.jpg").pins
        pins = PinList.added(pins, "", 5, image = "5-10x10.jpg").pins
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), pins.map { it.at })
        // The same words again move the note of words up, and leave the pictures' notes with that caption where they are.
        pins = PinList.added(pins, "Look", 6).pins
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L), pins.map { it.at })
        assertNull(pins.first().image)
        assertEquals("3-10x10.jpg", pins.first { it.at == 3L }.image)
    }

    @Test fun `several pictures at once are one note each, the first in front with the caption`() {
        val before = listOf(Pin("older", 1))
        val a = PinList.addedPictures(before, "Our trip", listOf(102L to "102-10x10.jpg", 101L to "101-10x10.jpg", 100L to "100-10x10.jpg"))
        assertEquals(listOf("102-10x10.jpg", "101-10x10.jpg", "100-10x10.jpg", null), a.pins.map { it.image })
        assertEquals(listOf("Our trip", "", "", "older"), a.pins.map { it.text })
        assertEquals(listOf(102L, 101L, 100L, 1L), a.pins.map { it.at })
        assertTrue(a.dropped.isEmpty())
        // For a place: every one of them is for it.
        val gym = PinList.Where(48.1, 11.5, 200, "Gym")
        val b = PinList.addedPictures(emptyList(), "", listOf(5L to "5-1x1.jpg"), gym)
        assertTrue(b.pins.single().placed); assertEquals("Gym", b.pins.single().place)
    }

    @Test fun `twenty at most - a picture's note is dropped as any plain note, and said, so its file goes`() {
        var pins = emptyList<Pin>()
        pins = PinList.added(pins, "", 1, image = "1-10x10.jpg").pins
        for (i in 2..20) pins = PinList.added(pins, "plain $i", i.toLong()).pins
        val a = PinList.added(pins, "one more", 21)
        assertEquals(20, a.pins.size)
        assertEquals("1-10x10.jpg", a.dropped.single().image)
        assertEquals(setOf("1-10x10.jpg"), dev.kb896.aiusage.data.PinImage.gone(pins, a.pins))
        // Three pictures into a full list: the three oldest plain notes go.
        val b = PinList.addedPictures(a.pins, "", listOf(32L to "32-1x1.jpg", 31L to "31-1x1.jpg", 30L to "30-1x1.jpg"))
        assertEquals(20, b.pins.size)
        assertEquals(listOf("plain 2", "plain 3", "plain 4"), b.dropped.map { it.text })
        assertEquals(listOf(32L, 31L, 30L), b.pins.take(3).map { it.at })
    }
}
