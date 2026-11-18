package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.PinVia.Veil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pin goes through Xiaomi's own pin and the back ends on OUR notes page: Xiaomi's
 * arrival is let through, its own face and its own standing page are never shown, and its list is left as it was.
 */
class PinViaTest {
    /** Xiaomi's arrival beginning where it usually does, a little early, late, before ours has stepped aside, and never. */
    private val enters = listOf<Long?>(PinVia.ENTER_USUAL, PinVia.ENTER_USUAL - 150, PinVia.ENTER_USUAL + 300, PinVia.SWITCH_AT - 120, PinVia.ENTER_LATEST - 1, null)

    /** The back as the frames would have it: [enter] is only known from the moment it is said. */
    private fun at(ms: Long, enter: Long?, full: Boolean = false) = PinVia.at(ms, enter?.takeIf { ms >= it }, full)

    @Test fun `ours gives way and comes back only under a whole veil - no cut is ever seen - whenever Xiaomi says its arrival begins`() {
        for (full in listOf(false, true)) for (enter in enters) {
            var was = at(0, enter, full)
            var switches = 0
            for (ms in 1L..PinVia.ENTER_LATEST + 3_000) {
                val now = at(ms, enter, full)
                if (now.xiaomi != was.xiaomi) {
                    switches++
                    assertEquals("enter $enter full $full at $ms: the switch is under a whole veil", 1f, now.alpha, 0.0001f)
                    assertEquals("and was a moment before", 1f, was.alpha, 0.02f)
                    assertTrue(now.veil != Veil.NONE)
                }
                // Nothing jumps from one millisecond to the next.
                if (now.veil != Veil.NONE && was.veil == now.veil) assertTrue("enter $enter at $ms", kotlin.math.abs(now.alpha - was.alpha) < 0.03f)
                was = now
            }
            assertEquals("aside once and back once (enter $enter)", 2, switches)
            assertEquals(PinVia.At(Veil.NONE, 0f, false), was)
            assertTrue(PinVia.over(PinVia.ENTER_LATEST + 3_000, enter, full)); assertFalse(PinVia.over(PinVia.SWITCH_AT + 5, enter?.takeIf { it <= PinVia.SWITCH_AT + 5 }, full))
            assertFalse(PinVia.over(0, null, full))
        }
    }

    @Test fun `Xiaomi's own face is never seen - nothing of its back shows before its arrival is 0_26 s in`() {
        for (full in listOf(false, true)) for (enter in enters) for (ms in 0L..PinVia.ENTER_LATEST + 3_000) {
            val a = at(ms, enter, full)
            val seen = a.xiaomi && a.alpha < 0.98f
            if (!seen) continue
            assertTrue("enter $enter at $ms: its arrival was said", enter != null)
            assertTrue("enter $enter at $ms: and is past its own face blurring away", ms >= enter!! + PinVia.LIFT_AFTER)
        }
    }

    @Test fun `what is seen of Xiaomi's - its coloured glass, and not its own note under ours`() {
        val e = PinVia.ENTER_USUAL
        // The glass (0.39 s in) is seen whole.
        assertTrue(at(e + 390, e).let { it.xiaomi && it.alpha < 0.02f })
        // Its own words begin 0.30 s in, soft, and are sharp from about 0.5 s: by then ours is over them.
        assertTrue(at(e + 520, e).let { it.veil == Veil.NOTES && it.alpha > 0.6f })
        assertTrue(at(e + 600, e).let { it.veil == Veil.NOTES && it.alpha > 0.97f })
        // The other way, kept to compare: Xiaomi's whole arrival, its lit rim too (over 1.0 s in), then ours.
        assertEquals(PinVia.At(Veil.NONE, 0f, true), at(e + 900, e, full = true))
        assertTrue(at(e + 1_060, e, full = true).let { it.veil == Veil.NOTES && it.alpha in 0.3f..0.7f })
        // Both ways are the same until ours comes.
        for (ms in 0L until e + PinVia.OURS_AFTER) assertEquals("at $ms", at(ms, e), at(ms, e, full = true))
    }

    @Test fun `the order - our page dips to black as the front ends, the black lifts on Xiaomi's arrival, our notes page fades in, and it is ours again`() {
        val e = PinVia.ENTER_USUAL
        assertEquals(PinVia.At(Veil.NONE, 0f, false), at(PinVia.DIP_AT - 1, e))
        assertEquals(Veil.BLACK, at(PinVia.DIP_AT + 75, e).veil); assertFalse(at(PinVia.DIP_AT + 75, e).xiaomi)
        assertEquals(PinVia.At(Veil.BLACK, 1f, true), at(PinVia.SWITCH_AT, e))
        assertEquals(PinVia.At(Veil.BLACK, 1f, true), at(e + PinVia.LIFT_AFTER - 1, e))
        assertTrue(at(e + PinVia.LIFT_AFTER + PinVia.BLOOM_MS / 2, e).let { it.veil == Veil.BLACK && it.alpha in 0.3f..0.7f && it.xiaomi })
        assertTrue(at(e + PinVia.OURS_AFTER + PinVia.OURS_MS / 2, e).let { it.veil == Veil.NOTES && it.alpha in 0.3f..0.7f && it.xiaomi })
        assertEquals(PinVia.At(Veil.NOTES, 1f, false), at(e + PinVia.OURS_AFTER + PinVia.OURS_MS + 20, e))
        // The back dips as the front's card goes (Xiaomi does not overlap the two screens), and ours is aside before
        // Xiaomi's arrival usually begins.
        assertTrue(PinVia.DIP_AT in (PinVia.FRONT_GONE_MS - 150)..PinVia.FRONT_GONE_MS)
        assertTrue(PinVia.SWITCH_AT < PinVia.ENTER_USUAL)
    }

    @Test fun `Xiaomi's arrival not said in its time - ours comes back under the black and the black lifts on our notes page`() {
        for (ms in PinVia.SWITCH_AT until PinVia.ENTER_LATEST) assertEquals("at $ms", PinVia.At(Veil.BLACK, 1f, true), PinVia.at(ms, null))
        assertEquals(PinVia.At(Veil.BLACK, 1f, false), PinVia.at(PinVia.ENTER_LATEST, null))
        assertTrue(PinVia.at(PinVia.ENTER_LATEST + PinVia.OURS_MS / 2, null).let { it.veil == Veil.BLACK && it.alpha in 0.3f..0.7f && !it.xiaomi })
        assertEquals(PinVia.At(Veil.NONE, 0f, false), PinVia.at(PinVia.ENTER_LATEST + PinVia.OURS_MS, null))
        // Xiaomi's back is never seen that way.
        for (ms in 0L..PinVia.ENTER_LATEST + 1_000) assertFalse("at $ms", PinVia.at(ms, null).let { it.xiaomi && it.alpha < 1f })
    }

    @Test fun `said before ours had stepped aside - nothing is lifted before the switch, and its steps are where it says`() {
        val e = PinVia.SWITCH_AT - 250          // its glass would be due before ours is even aside
        assertEquals(PinVia.At(Veil.BLACK, 1f, true), at(PinVia.SWITCH_AT + 29, e))
        assertTrue(at(PinVia.SWITCH_AT + 31, e).let { it.veil == Veil.BLACK && it.alpha > 0.9f })
        // Ours over it no earlier than the black has lifted, no later than that.
        assertTrue(at(PinVia.SWITCH_AT + 30 + PinVia.BLOOM_MS + PinVia.OURS_MS, e).let { it.veil == Veil.NOTES && it.alpha == 1f })
    }

    @Test fun `which animation a pin plays is the settings' choice - Xiaomi's by default, never for a demo`() {
        assertTrue("the default is Xiaomi's", dev.kb896.aiusage.data.PanelConfig().pinByXiaomi)
        assertTrue(PinVia.wanted(setting = true, demo = false, ownAsked = false))
        assertFalse("Ours chosen", PinVia.wanted(setting = false, demo = false, ownAsked = false))
        assertFalse("a demo keeps nothing and never goes to Xiaomi's list", PinVia.wanted(setting = true, demo = true, ownAsked = false))
        assertFalse(PinVia.wanted(setting = true, demo = false, ownAsked = true))
        // The choice is kept with the rest of the settings.
        val chosen = dev.kb896.aiusage.data.PanelConfig().copy(pinByXiaomi = false)
        assertFalse(dev.kb896.aiusage.data.PanelConfig.decode(dev.kb896.aiusage.data.PanelConfig.encode(chosen)).pinByXiaomi)
        assertTrue(dev.kb896.aiusage.data.PanelConfig.decode(dev.kb896.aiusage.data.PanelConfig.encode(dev.kb896.aiusage.data.PanelConfig())).pinByXiaomi)
    }

    @Test fun `whether Xiaomi's pin really began is looked at before anything of ours gives way`() {
        assertTrue(PinVia.CHECK_AT > PinVia.FRONT_FIRST_MS + 300)
        assertTrue(PinVia.CHECK_AT + 400 < PinVia.DIP_AT)
    }

    @Test fun `Xiaomi says its stages and its arrival in the log - and the back goes by what it says`() {
        fun line(what: String) = "W/SubScreenCenter_PinAnimationSurfaceView( 9970): [PinAnimationThread] - $what"
        fun rear(what: String) = "W/SubScreenCenter_SmartAssistantPanel( 9970): [main] - $what"
        assertEquals(PinVia.Heard.STAGE0, PinVia.heard(line("start animation 0")))
        assertEquals(PinVia.Heard.STAGE1, PinVia.heard(line("start animation 1")))
        assertEquals(PinVia.Heard.STAGE2, PinVia.heard(line("start animation 2")))
        assertEquals(PinVia.Heard.FINISHED, PinVia.heard(line("finished")))
        assertEquals(PinVia.Heard.ENTER, PinVia.heard(rear("Pin card enter animation start true")))
        // The longer line before each stage is not a stage (taking it moved the count on six times in one pin).
        for (i in 0..3) assertNull(PinVia.heard(line("start animation mFinished = false, mIndex = $i, mBitmap = android.graphics.Bitmap@11e7262, mInitEngine = true")))
        for (other in listOf("prepareFrame done", "perform finish callback animation finish, mFinished = false, mInitEngine = true", "post run callback", "start destroy render engine", "start animation 3"))
            assertNull(other, PinVia.heard(line(other)))
        for (other in listOf("New view show animation start", "New view content show animation start", "Background animation complete", "Pag animation end", "onStartGesture activated = false", "update round corner state, new state = true"))
            assertNull(other, PinVia.heard(rear(other)))
        assertNull(PinVia.heard("W/SubScreenCenter_PinAnimationSurfaceView( 9970): [main] - surfaceDestroyed"))
        assertNull(PinVia.heard("I/ActivityTaskManager( 3832): START u0 {act=android.intent.action.SEND cmp=com.xiaomi.subscreencenter/.pin.PinReceiveActivity} start animation 1"))
        val zeros = listOf(PinVia.Heard.STAGE0 to 24_604L, PinVia.Heard.STAGE1 to 24_805L, PinVia.Heard.STAGE2 to 26_107L, PinVia.Heard.FINISHED to 26_658L).map { PinVia.zero(it.first, it.second) }
        assertTrue("within 5 ms of each other: $zeros", zeros.max() - zeros.min() <= 5)
        assertEquals(26_654L, zeros[0] + PinVia.FRONT_GONE_MS)
        val zero = PinVia.zero(PinVia.Heard.STAGE1, 42_114L)
        val enter = 44_162L - zero
        assertTrue("ours is aside before it begins", PinVia.SWITCH_AT < enter)
        assertTrue("the usual place is that one, within 30 ms", kotlin.math.abs(enter - PinVia.ENTER_USUAL) <= 30)
        fun wall(ms: Long) = zero + ms
        val lifts = (0L..4_000).first { at(it, enter).let { a -> a.xiaomi && a.alpha < 0.98f } }
        assertTrue("the black begins to lift after its face has blurred (${wall(lifts)})", wall(lifts) >= 44_400L)
        val whole = (0L..4_000).first { at(it, enter).let { a -> a.veil == Veil.NOTES && a.alpha > 0.97f } }
        assertTrue("ours is whole soon after its note is sharp (${wall(whole)})", wall(whole) in 44_600L..44_800L)
    }

    private val listing = """
        {
          "androidAppfunctionsReturnValue": [ {
              "success": [ true ], "summary": [ "Listed 3 sticky note(s)" ], "total_count": [ 3 ], "max_count": [ 9 ],
              "items": [
                { "text": [ "Sample note" ], "type": [ "text" ], "content_preview": [ "Sample note" ], "card_id": [ "-910000000" ] },
                { "text": [ "Water the plant" ], "type": [ "text" ], "content_preview": [ "Water the plant" ], "card_id": [ "-900387217" ] },
                { "text": [ "Water the plant" ], "type": [ "text" ], "content_preview": [ "Water the plant" ], "card_id": [ "-900000000" ] }
              ],
              "remaining": [ 6 ]
          } ]
        }
    """.trimIndent()

    @Test fun `Xiaomi's own list is read as its function prints it`() {
        val (notes, remaining) = PinVia.notes("some line before\n$listing")!!
        assertEquals(listOf(PinVia.Note("-910000000", "Sample note", "text"), PinVia.Note("-900387217", "Water the plant", "text"), PinVia.Note("-900000000", "Water the plant", "text")), notes)
        assertEquals(6, remaining)
        assertEquals(emptyList<PinVia.Note>() to 9, PinVia.notes("""{"androidAppfunctionsReturnValue":[{"success":[true],"total_count":[0],"max_count":[9],"remaining":[9]}]}"""))
        assertNull(PinVia.notes("Error: no such function")); assertNull(PinVia.notes(""))
    }

    @Test fun `only what this pin added is taken out of Xiaomi's list again - never a note he pinned there, even with the same words`() {
        val after = PinVia.notes(listing)!!.first
        // Begin the synthetic pin 171 ms before its card appears.
        val began = (-900000000 - 171).toLong()
        assertEquals(listOf("-900000000"), PinVia.tidy(began, after, "Water the plant"))
        // The same with the clock's whole value (only its low 32 bits count), and across the 32 bits' turn-over.
        assertEquals(listOf("-900000000"), PinVia.tidy((1L shl 40) + ((-900000000 - 171).toLong() and 0xFFFFFFFFL), after, "Water the plant"))
        assertEquals(listOf(Int.MIN_VALUE.toString()), PinVia.tidy(Int.MAX_VALUE.toLong() - 99, listOf(PinVia.Note(Int.MIN_VALUE.toString(), "x")), "x"))
        // Not our words (he pinned by Xiaomi's own share target at that moment): it stays.
        assertEquals(emptyList<String>(), PinVia.tidy(began, after, "Close the window"))
        // Our words, but made at another time: his own, they stay.
        assertEquals(emptyList<String>(), PinVia.tidy(began - 60_000, after, "Water the plant"))
        assertEquals(emptyList<String>(), PinVia.tidy(began + 60_000, after, "Water the plant"))
        assertEquals(emptyList<String>(), PinVia.tidy(-900000000L + PinVia.BORN_BEFORE_MS + 1, after, "Water the plant"))
        assertEquals(emptyList<String>(), PinVia.tidy(-900000000L - PinVia.BORN_AFTER_MS - 1, after, "Water the plant"))
        // Spaces around the words do not matter; an id that is no number is never ours.
        assertEquals(listOf("-900000000"), PinVia.tidy(began, after, "  Water the plant "))
        assertEquals(emptyList<String>(), PinVia.tidy(began, listOf(PinVia.Note("abc", "Water the plant")), "Water the plant"))
        assertTrue(PinVia.delete("-900000000").contains("\"card_id\":\"-900000000\"") && PinVia.delete("-900000000").contains("\"confirmed\":true"))
        val odd = PinVia.delete("1'; rm -rf /; '")
        assertFalse("nothing but an id gets into the command", odd.contains("rm -rf") || odd.contains(";") || odd.contains("card_id\":\"1'"))
        assertTrue(odd.contains("\"card_id\":\"1-\""))
    }

    @Test fun `through Xiaomi's own only on the version its times were measured on`() {
        assertTrue(PinVia.timedFor(426091419L))
        assertFalse(PinVia.timedFor(426091420L))
        assertFalse("not known", PinVia.timedFor(null))
    }
}
