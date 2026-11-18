package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A picture through Xiaomi's own pin: its copy taken out of Xiaomi's list again by its kind and its time, never his. */
class PinViaPictureTest {
    // A synthetic list with an older image, a text pin and the new image.
    private val listing = """
        {
          "androidAppfunctionsReturnValue": [ {
              "success": [ true ], "total_count": [ 4 ], "max_count": [ 9 ],
              "items": [
                { "text": [ "Sample note" ], "type": [ "text" ], "content_preview": [ "Sample note" ], "card_id": [ "-910000000" ] },
                { "text": [ "" ], "type": [ "image" ], "image_uri": [ "content://xiaomi.example/pin/1" ], "content_preview": [ "[Image]" ], "card_id": [ "-900387217" ] },
                { "text": [ "Sample text" ], "type": [ "text" ], "content_preview": [ "Sample text" ], "card_id": [ "-900000079" ] },
                { "text": [ "" ], "type": [ "image" ], "image_uri": [ "content://xiaomi.example/pin/2" ], "content_preview": [ "[Image]" ], "card_id": [ "-900000000" ] }
              ],
              "remaining": [ 5 ]
          } ]
        }
    """.trimIndent()

    @Test fun `a picture card is read with its kind and where Xiaomi has it`() {
        val notes = PinVia.notes(listing)!!.first
        assertEquals(PinVia.Note("-900000000", "", "image", "content://xiaomi.example/pin/2"), notes[3])
        assertTrue(notes[1].picture && notes[3].picture)
        assertFalse(notes[0].picture || notes[2].picture)
        // Its kind in other words, or no kind with a picture: a picture; "text" never, whatever else it carries.
        assertTrue(PinVia.Note("1", "", "IMAGE").picture); assertTrue(PinVia.Note("1", "", "photo").picture)
        assertTrue(PinVia.Note("1", "", "", "content://x/1").picture)
        assertFalse(PinVia.Note("1", "", "text", "content://x/1").picture)
        assertFalse(PinVia.Note("1", "words").picture)
    }

    @Test fun `only the picture card this pin made is taken out - never his own picture, never a card of words made in those seconds`() {
        val after = PinVia.notes(listing)!!.first
        val began = (-900000000 - 171).toLong()
        assertEquals(listOf("-900000000"), PinVia.tidyPicture(began, after))
        // The nearby text card is outside the image cleanup.
        // The older image is outside the matching window.
        assertEquals(emptyList<String>(), PinVia.tidyPicture(began - 60_000, after))
        assertEquals(emptyList<String>(), PinVia.tidyPicture(began + 60_000, after))
        assertEquals(emptyList<String>(), PinVia.tidyPicture(-900000000L + PinVia.BORN_BEFORE_MS + 1, after))
        assertEquals(emptyList<String>(), PinVia.tidyPicture(-900000000L - PinVia.BORN_AFTER_MS - 1, after))
        // Two picture cards in the window: the one alone nearest the moment Xiaomi makes its card.
        val two = after + PinVia.Note((-900000000 + 2_500).toString(), "", "image")
        assertEquals(listOf("-900000000"), PinVia.tidyPicture(began, two))
        // Across the 32 bits' turn-over, and the clock's whole value.
        assertEquals(listOf("-900000000"), PinVia.tidyPicture((1L shl 40) + (began and 0xFFFFFFFFL), after))
        // A note of words is never found by the picture's tidy, and a picture card never by the words' tidy.
        assertEquals(emptyList<String>(), PinVia.tidy(began, listOf(PinVia.Note("-900000000", "", "image")), ""))
        assertEquals(emptyList<String>(), PinVia.tidyPicture(began, listOf(PinVia.Note("-900000000", "", "text"))))
        // The log's line when none is found: the kinds made in those seconds, never their words.
        assertEquals("text, image+uri", PinVia.kindsNear(began, after))
        assertEquals("none", PinVia.kindsNear(began + 60_000, after))
        assertFalse(PinVia.kindsNear(began, after).contains("Sample text"))
    }
}
