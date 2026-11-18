package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.rear.SceneTouch.Mode
import dev.kb896.aiusage.rear.SceneTouch.Out
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tap on a pinned picture on the back opens it on the front (PictureTap), the finger read as the shell reads it
 * (SceneTouch, the bin first, every landing told): only a tap left alone for the double tap's window opens, once; a
 * double tap is the dim, a double tap held the back off, a stroke the next note, a press held the notes in order, and
 * a tap elsewhere the back bright, as they were. Made-up notes and places only. (Robolectric: the bin is PinFace's, which
 * holds paints.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PictureTapTest {
    /** A picture's note and where the page has its picture (rear pixels of 976x596). */
    private val pic = Pin("A caption", 5_000L, image = "5000-478x359.jpg")
    private val place = floatArrayOf(322f, 150f, 800f, 509f)
    private val words = Pin("just words", 4_000L)

    /** The shell's part: the bin first, then the tap; a landing told before anything else; the window checked when it is over. */
    private inner class Shell(var mode: Mode = Mode.NOTES, var under: PictureTap.Under = PictureTap.Under(pic, place)) {
        val touch = SceneTouch()
        val tap = PictureTap()
        val opened = ArrayList<PictureTap.Pending>()
        var bright = 0; var doubles = 0; var bins = 0; var swipes = 0; var holdsAsked = 0

        fun feed(e: RearTouches.Event) {
            if (e is RearTouches.Down) tap.landed()
            for (o in touch.feed(e) { mode }) when (o) {
                is Out.Tap -> if (PinFace.onBin(o.x, o.y)) bins++ else { bright++; tap.tapped(o.x, o.y, o.on, under, e.atMs) }
                is Out.DoubleTap -> doubles++
                is Out.Swipe -> swipes++
                Out.NotesDown, Out.SecondDown, Out.FirstDown -> holdsAsked++
            }
        }

        fun at(now: Long) { tap.due(now) { under }?.let { opened += it } }
        fun down(x: Float, y: Float, t: Long) = feed(RearTouches.Down(x, y, t))
        fun move(x: Float, y: Float, t: Long) = feed(RearTouches.Move(x, y, t))
        fun up(x: Float, y: Float, t: Long) = feed(RearTouches.Up(x, y, t))
        fun tapAt(x: Float, y: Float, t: Long, down: Long = 80) { down(x, y, t); up(x, y, t + down) }
    }

    @Test fun `a tap on the picture brightens at once and opens it once the double tap's window is over, once`() {
        val s = Shell()
        s.tapAt(560f, 330f, 1_000)
        assertEquals("bright as any tap", 1, s.bright)
        assertTrue(s.tap.waiting())
        s.at(1_080 + SceneTouch.DOUBLE_MS - 1)
        assertTrue("not before the window is over", s.opened.isEmpty())
        s.at(1_080 + PictureTap.WAIT_MS)
        assertEquals(listOf(PictureTap.Pending(5_000L, Mode.NOTES, 1_080)), s.opened)
        s.at(1_080 + PictureTap.WAIT_MS + 500)
        assertEquals("once", 1, s.opened.size)
    }

    @Test fun `a tap elsewhere on the page is the back bright and opens nothing`() {
        val s = Shell()
        s.tapAt(310f, 60f, 1_000)   // the head
        s.tapAt(900f, 120f, 2_000)  // the chip's side
        s.at(5_000)
        assertEquals(2, s.bright)
        assertTrue(s.opened.isEmpty())
    }

    @Test fun `the bin is the bin, even where it meets the picture's place`() {
        val s = Shell(under = PictureTap.Under(pic, floatArrayOf(322f, 128f, 800f, 556f)))
        s.tapAt(PinFace.BIN_X, PinFace.BIN_Y, 1_000)
        assertTrue(PinFace.onBin(810f, 496f) && PictureTap.onPicture(s.under.place, 810f, 496f))
        s.tapAt(810f, 496f, 3_000)  // in the bin's reach and within the picture's give
        s.at(9_000)
        assertEquals(2, s.bins)
        assertTrue(s.opened.isEmpty())
    }

    @Test fun `a double tap on the picture is the dim and opens nothing`() {
        val s = Shell()
        s.tapAt(560f, 330f, 1_000, down = 60)
        s.tapAt(565f, 334f, 1_060 + 200, down = 60)
        s.at(10_000)
        assertEquals(1, s.doubles)
        assertTrue(s.opened.isEmpty())
        val t = Shell()
        t.tapAt(560f, 330f, 1_000, down = 120)
        t.tapAt(570f, 320f, 1_120 + 215, down = 315)
        t.at(10_000)
        assertEquals(1, t.doubles)
        assertTrue(t.opened.isEmpty())
    }

    @Test fun `a double tap with the second held opens nothing - the hold is the shell's to read`() {
        val s = Shell(mode = Mode.ARRIVAL, under = PictureTap.Under(pic, place))
        s.tapAt(560f, 330f, 1_000)
        s.down(562f, 331f, 1_200)
        assertTrue(s.touch.held(1_200 + SceneTouch.HOLD_MS))
        s.up(562f, 331f, 1_900)
        s.at(10_000)
        assertTrue(s.opened.isEmpty())
    }

    @Test fun `a second tap far away within the window is a tap of its own - the first opens nothing`() {
        val s = Shell()
        s.tapAt(560f, 330f, 1_000)
        s.tapAt(320f, 560f, 1_300)  // too far for a double tap, off the picture
        s.at(10_000)
        assertEquals(2, s.bright)
        assertTrue(s.opened.isEmpty())
    }

    @Test fun `a stroke within the window, or a press held, opens nothing`() {
        val s = Shell()
        s.tapAt(560f, 330f, 1_000)
        s.down(700f, 330f, 1_200)
        for (i in 1..6) s.move(700f - 40f * i, 330f, 1_200L + 20 * i)
        s.up(460f, 330f, 1_340)
        s.at(10_000)
        assertEquals(1, s.swipes)
        assertTrue(s.opened.isEmpty())
        // Held on the notes: the notes lifted to be put in order; no tap as it lifts.
        val h = Shell()
        h.down(560f, 330f, 1_000)
        assertTrue(h.touch.heldOnNotes(1_000 + SceneTouch.NOTE_HOLD_MS))
        h.up(560f, 330f, 1_700)
        h.at(10_000)
        assertEquals(0, h.bright)
        assertTrue(h.opened.isEmpty())
    }

    @Test fun `more fingers coming (three held) call it off`() {
        val s = Shell()
        s.tapAt(560f, 330f, 1_000)
        // The shell is told of a crowd as the other fingers land (RearTouches.crowded): every landing is told.
        s.tap.landed()
        s.at(10_000)
        assertTrue(s.opened.isEmpty())
    }

    @Test fun `a slow tap is still a tap and opens`() {
        val s = Shell()
        s.tapAt(560f, 330f, 1_000, down = SceneTouch.TAP_MS)
        s.at(1_000 + SceneTouch.TAP_MS + PictureTap.WAIT_MS)
        assertEquals(1, s.opened.size)
    }

    @Test fun `nothing opens while the page moves, is gone, shows another note, the back is dark, a call is on, or the notes are made up`() {
        fun opensWith(u: PictureTap.Under, later: PictureTap.Under = u): Boolean {
            val s = Shell(under = u)
            s.tapAt(560f, 330f, 1_000)
            s.under = later
            s.at(10_000)
            return s.opened.isNotEmpty()
        }
        val ok = PictureTap.Under(pic, place)
        assertTrue(opensWith(ok))
        assertFalse("a note turning", opensWith(ok.copy(moving = true)))
        assertFalse("the back dark", opensWith(ok.copy(lit = false)))
        assertFalse("a call on", opensWith(ok.copy(inCall = true)))
        assertFalse("a debug build's made-up notes", opensWith(ok.copy(madeUp = true)))
        assertFalse("a note of words", opensWith(PictureTap.Under(words, null)))
        // Looked at again as the window ends:
        assertFalse("the page went meanwhile", opensWith(ok, ok.copy(up = false)))
        assertFalse("another note showing by then", opensWith(ok, PictureTap.Under(pic.copy(at = 6_000L), place)))
        assertFalse("a call came meanwhile", opensWith(ok, ok.copy(inCall = true)))
        assertFalse("put in order meanwhile", opensWith(ok, ok.copy(moving = true)))
        // A dim back is lit: the picture opens (the page is there to be seen, only darker).
        assertTrue(opensWith(ok.copy(lit = true)))
    }

    @Test fun `only on the notes page and an arrival's - the moon, usage and the charging page never open a picture`() {
        for (m in listOf(Mode.SCENE, Mode.USAGE, Mode.CHARGE)) {
            val s = Shell(mode = m)
            s.tapAt(560f, 330f, 1_000)
            s.at(10_000)
            assertTrue("$m", s.opened.isEmpty())
        }
        val a = Shell(mode = Mode.ARRIVAL)
        a.tapAt(560f, 330f, 1_000)
        a.at(10_000)
        assertEquals(Mode.ARRIVAL, a.opened.single().on)
    }

    @Test fun `on the picture means within it, a fingertip's give round it counted in`() {
        assertTrue(PictureTap.onPicture(place, 322f - PictureTap.PAD, 150f))
        assertTrue(PictureTap.onPicture(place, 800f, 509f + PictureTap.PAD))
        assertFalse(PictureTap.onPicture(place, 322f - PictureTap.PAD - 1f, 300f))
        assertFalse(PictureTap.onPicture(place, 500f, 150f - PictureTap.PAD - 1f))
        assertFalse(PictureTap.onPicture(null, 500f, 300f))
        assertFalse(PictureTap.onPicture(floatArrayOf(1f, 2f), 1f, 2f))
    }

    @Test fun `the shell's command carries one number and names one screen`() {
        assertEquals("am start --display 0 --activity-no-animation -n dev.example.app/dev.kb896.aiusage.ui.PinViewActivity --el at 1731600000123",
            PictureTap.command("dev.example.app", 1_731_600_000_123L))
        assertNull(PictureTap().due(0) { PictureTap.Under(pic, place) })
    }
}
