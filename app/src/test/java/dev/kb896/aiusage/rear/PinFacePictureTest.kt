package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.kb896.aiusage.data.Pin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A picture's note on the back's notes page (PinFace): fitted in the note's place, rounded, its caption, the rest as any note. Made-up pictures only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinFacePictureTest {
    private fun page(draw: (Canvas) -> Unit): Bitmap = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }

    /** A made-up picture, one pure green (nothing on the page is green). */
    private fun green(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.rgb(0, 255, 0)) }

    private fun isGreen(p: Int) = Color.green(p) > 200 && Color.red(p) < 60 && Color.blue(p) < 60

    /** Where the green is on [b]: left, top, right, bottom (inclusive), or null. */
    private fun greenBox(b: Bitmap): IntArray? {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var bo = -1
        for (y in 0 until b.height) for (x in 0 until b.width) if (isGreen(b.getPixel(x, y))) { l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); bo = maxOf(bo, y) }
        return if (r < 0) null else intArrayOf(l, t, r, bo)
    }

    private fun same(a: Bitmap, b: Bitmap, inside: (Int, Int) -> Boolean): Boolean {
        for (y in 0 until 596) for (x in 0 until 976) if (inside(x, y) && a.getPixel(x, y) != b.getPixel(x, y)) return false
        return true
    }

    @After fun noPictures() { PinFace.pictures = { null } }

    @Test fun `the place is the note's - clear of the lenses, the head, the bin and the handle - and a picture fills it in its own shape`() {
        val whole = PinFace.picturePlace(478, 428)
        assertEquals(listOf(PinFace.PIC_LEFT, PinFace.PIC_TOP, PinFace.PIC_RIGHT, PinFace.PIC_BOTTOM), whole.toList())
        // Wide: the place's width, in the middle of its height.
        val wide = PinFace.picturePlace(478, 239)
        assertEquals(478f, wide[2] - wide[0], 0.01f); assertEquals(239f, wide[3] - wide[1], 0.01f)
        assertEquals((PinFace.PIC_TOP + PinFace.PIC_BOTTOM) / 2f, (wide[1] + wide[3]) / 2f, 0.01f)
        // Narrow: centred on x 512 as the words are.
        val tall = PinFace.picturePlace(200, 428)
        assertEquals(512f, (tall[0] + tall[2]) / 2f, 0.01f)
        // Small: twice its size at most, never stretched.
        val small = PinFace.picturePlace(60, 30)
        assertEquals(120f, small[2] - small[0], 0.01f); assertEquals(60f, small[3] - small[1], 0.01f)
        // With a caption the picture is smaller, and the block stays in the place.
        val captioned = PinFace.picturePlace(478, 428, captionH = 66f, captionW = 300f)
        assertTrue(captioned[3] + 66f <= PinFace.PIC_BOTTOM + 0.01f && captioned[1] >= PinFace.PIC_TOP - 0.01f)
        assertEquals(1.1168f, (captioned[2] - captioned[0]) / (captioned[3] - captioned[1]), 0.01f)
        for (p in listOf(whole, wide, tall, small, captioned)) {
            assertTrue("never under the lenses", p[0] >= 296f)
            assertTrue("never under the bin", p[2] <= PinFace.BIN_X - PinFace.BIN_R)
        }
    }

    @Test fun `a picture's note - the picture fitted, its corners rounded, the chip and the count as for any note`() {
        val pic = green(300, 200)
        val drawn = page { PinFace.draw(it, 976f, 596f, "", "09:41", 1, 3, image = "1-300x200.jpg", picture = pic) }
        File("build").mkdirs()
        File("build/pin-picture-note.png").outputStream().use { drawn.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val box = greenBox(drawn)!!
        val at = PinFace.picturePlace(300, 200)
        // Where the place says, in its own shape (3:2), not stretched.
        assertEquals(at[0], box[0].toFloat(), 2f); assertEquals(at[2], box[2].toFloat() + 1f, 2f)
        assertEquals(at[1], box[1].toFloat(), 2f); assertEquals(at[3], box[3].toFloat() + 1f, 2f)
        assertEquals(1.5f, (box[2] - box[0] + 1f) / (box[3] - box[1] + 1f), 0.02f)
        // Rounded: its very corners are the page's ground, its middle and its edges' middles the picture.
        assertFalse(isGreen(drawn.getPixel(box[0] + 1, box[1] + 1)))
        assertFalse(isGreen(drawn.getPixel(box[2] - 1, box[3] - 1)))
        assertTrue(isGreen(drawn.getPixel((box[0] + box[2]) / 2, (box[1] + box[3]) / 2)))
        assertTrue(isGreen(drawn.getPixel(box[0] + 1, (box[1] + box[3]) / 2)))
        // The head, the chip, the bin and "2 / 3" are exactly the notes page's for a note of words.
        val words = page { PinFace.draw(it, 976f, 596f, "Some words", "09:41", 1, 3) }
        assertTrue("head and chip", same(drawn, words) { _, y -> y < 115 })
        assertTrue("bin", same(drawn, words) { x, y -> x >= 812 && y in 432..560 })
        assertTrue("count", same(drawn, words) { x, y -> x in 560..712 && y >= 562 })
        // A place note's mark and name in the chip, as for words.
        val placed = page { PinFace.draw(it, 976f, 596f, "", "09:41", 0, 1, placed = true, place = "Gym", image = "1-300x200.jpg", picture = pic) }
        val placedWords = page { PinFace.draw(it, 976f, 596f, "Some words", "09:41", 0, 1, placed = true, place = "Gym") }
        assertTrue(same(placed, placedWords) { _, y -> y < 115 })
    }

    @Test fun `the caption is the note's words under the picture, and a picture not decoded yet leaves its place empty without moving them`() {
        val pic = green(300, 300)
        val bare = page { PinFace.draw(it, 976f, 596f, "", "09:41", 0, 1, image = "1-300x300.jpg", picture = pic) }
        val captioned = page { PinFace.draw(it, 976f, 596f, "At the lake", "09:41", 0, 1, image = "1-300x300.jpg", picture = pic) }
        File("build").mkdirs()
        File("build/pin-picture-caption.png").outputStream().use { captioned.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val a = greenBox(bare)!!; val b = greenBox(captioned)!!
        assertTrue("the picture makes room for its caption", b[3] - b[1] < a[3] - a[1])
        // The caption is drawn under the picture (light pixels there that the bare page has not).
        val under = (b[3] + 6)..(b[3] + 80)
        assertFalse(same(bare, captioned) { x, y -> y in under && x in b[0]..b[2] })
        // NOT DECODED YET: the place empty (the ground), the caption exactly where it will be with the picture.
        val waiting = page { PinFace.draw(it, 976f, 596f, "At the lake", "09:41", 0, 1, image = "1-300x300.jpg", picture = null) }
        assertEquals(null, greenBox(waiting))
        assertTrue(same(waiting, captioned) { x, y -> y > b[3] + 2 || y < b[1] - 2 || x < b[0] - 2 || x > b[2] + 2 })
        val empty = page { PinFace.draw(it, 976f, 596f, "", "09:41", 0, 1, image = "1-300x300.jpg", picture = null) }
        val wordsless = page { PinFace.draw(it, 976f, 596f, "", "09:41", 0, 1) }
        assertTrue("an empty place is the ground", same(empty, wordsless) { _, _ -> true })
    }

    @Test fun `every notes page of the shell takes a picture's note's picture from the shell's pictures, and a note of words is as it was`() {
        val pic = green(200, 100)
        val now = 1_763_140_000_000L
        val note = Pin("", now - 60_000L, image = "5-200x100.jpg")
        PinFace.pictures = { name -> if (name == "5-200x100.jpg") pic else null }
        val viaNote = page { PinFace.note(it, 976f, 596f, note, 0, 2, now) }
        val direct = page { PinFace.draw(it, 976f, 596f, "", PinFace.stamp(note.freshAt, now), 0, 2, image = note.image, picture = pic) }
        assertTrue(same(viaNote, direct) { _, _ -> true })
        assertTrue(greenBox(viaNote) != null)
        // The veil before the note is in the list: the pin's own picture by its name.
        assertTrue(greenBox(page { PinFace.note(it, 976f, 596f, null, 0, 1, now, text = "", image = "5-200x100.jpg") }) != null)
        // Words: exactly the page of before (no picture asked for).
        var asked = 0
        PinFace.pictures = { asked++; null }
        val words = Pin("Take the keys", now - 60_000L)
        assertTrue(same(page { PinFace.note(it, 976f, 596f, words, 1, 3, now) }, page { PinFace.draw(it, 976f, 596f, words.text, PinFace.stamp(words.freshAt, now), 1, 3, false) }) { _, _ -> true })
        assertEquals(0, asked)
        // Not decoded yet: the shell's answer is null and the page is drawn at once, its place empty.
        assertEquals(null, greenBox(page { PinFace.note(it, 976f, 596f, note, 0, 2, now) }))
        assertNotEquals(0, asked)
    }

    @Test fun `the carousel's card of a picture's note is drawn again once its picture comes, and kept after`() {
        val pic = green(200, 100)
        val now = 1_763_140_000_000L
        val note = Pin("", now - 60_000L, image = "6-200x100.jpg")
        val art = NoteArrangeArt()
        var ready = false
        PinFace.pictures = { if (ready) pic else null }
        assertEquals(null, greenBox(art.card(note, 0, 2, 976f, 596f, now)))
        ready = true
        assertTrue(greenBox(art.card(note, 0, 2, 976f, 596f, now)) != null)
        // The shell let it go meanwhile: the card keeps it (no flicker back to an empty place).
        ready = false
        assertTrue(greenBox(art.card(note, 0, 2, 976f, 596f, now)) != null)
    }

    @Test fun `a carousel card drawn again at another place keeps its picture when the shell has let it go, and hands it back to the page`() {
        val pic = green(200, 100)
        val now = 1_763_140_000_000L
        val note = Pin("", now - 60_000L, image = "7-200x100.jpg")
        val art = NoteArrangeArt()
        var inMemory = true
        PinFace.pictures = { name -> if (inMemory && name == note.image) pic else null }
        assertTrue(greenBox(art.card(note, 0, 5, 976f, 596f, now)) != null)
        // Five cards and three pictures in memory: this one let go; then a note carried past it puts it at 2 of 5.
        inMemory = false
        val moved = art.card(note, 1, 5, 976f, 596f, now)
        assertTrue("never drawn empty for the frames a read would take", greenBox(moved) != null)
        // It is the notes page's own drawing of the note at its new place, its count with it.
        PinFace.pictures = { name -> if (name == note.image) pic else null }
        assertTrue(same(moved, page { PinFace.note(it, 976f, 596f, note, 1, 5, now) }) { _, _ -> true })
        // The page the carousel gives way to takes it back from the card (NotePictures.keep), then it is let go.
        assertSame(pic, art.pictureOf(note.image))
        assertNull(art.pictureOf(null)); assertNull(art.pictureOf("8-200x100.jpg"))
        art.clear()
        assertNull(art.pictureOf(note.image))
        // A note of words holds no picture.
        PinFace.pictures = { null }
        assertTrue(greenBox(art.card(Pin("Take the keys", now - 1_000L), 2, 5, 976f, 596f, now)) == null)
        assertNull(art.pictureOf(null))
    }
}
