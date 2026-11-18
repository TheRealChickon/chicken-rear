package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import dev.kb896.aiusage.data.Pin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The notes put in order: each card of the carousel is the notes page's own drawing of its note, place notes included. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteArrangeArtTest {
    private fun page(draw: (Canvas) -> Unit): Bitmap = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }

    private fun same(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) if (a.getPixel(x, y) != b.getPixel(x, y)) return false
        return true
    }

    @Test fun `a card being put in order is its note's page - a place note keeps its name and its mark`() {
        val now = 1_763_140_000_000L
        val at = now - 60_000L
        val gym = Pin("Bring the towels", at, lat = 48.1, lng = 11.6, place = "Gym", watched = false)
        val art = NoteArrangeArt()
        assertTrue("unwatched place note", same(art.card(gym, 0, 3, 976f, 596f, now), page { PinFace.note(it, 976f, 596f, gym, 0, 3, now) }))
        // Watched since: the card is drawn again with the whole mark, not kept from before.
        val watched = gym.copy(watched = true)
        val card = art.card(watched, 0, 3, 976f, 596f, now)
        assertTrue("watched place note", same(card, page { PinFace.note(it, 976f, 596f, watched, 0, 3, now) }))
        assertFalse("the place is drawn, not the plain chip", same(card, page { PinFace.draw(it, 976f, 596f, gym.text, PinFace.stamp(at, now), 0, 3, true) }))
        val plain = Pin("Take the keys", at + 1)
        assertTrue("a note with no place", same(art.card(plain, 1, 3, 976f, 596f, now), page { PinFace.note(it, 976f, 596f, plain, 1, 3, now) }))
        art.clear()
    }
}
