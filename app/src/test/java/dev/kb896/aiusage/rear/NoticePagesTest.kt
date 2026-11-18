package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticePagesTest {
    private val left = 274f
    private val h = Panel.HEIGHT.toFloat()
    private fun item(n: Int, pkg: String = "p$n", actions: List<String> = emptyList()) =
        NoticeShade.Item("k$n", pkg, "App", "From $n", "Text", actions = actions)

    private fun open(count: Int, logos: Boolean = false): NoticePages = NoticePages(left).apply {
        logosFirst = logos
        items((1..count).map(::item))
        beginPull(10f, 30f, 0); movePull(500f, 200); endPull(500f, 200)
        assertTrue(shown(1000))
        assertEquals(1f, progress(1000), 0.001f)
    }

    private fun NoticePages.stroke(x0: Float, y0: Float, x1: Float, y1: Float, t0: Long, ms: Long = 200): List<NoticeShade.Effect> {
        fingerDown(x0, y0, t0)
        for (i in 1..10) fingerMove(x0 + (x1 - x0) * i / 10f, y0 + (y1 - y0) * i / 10f, t0 + ms * i / 10)
        return fingerUp(x1, y1, t0 + ms)
    }

    /** Which way the finger goes to dismiss (-1 right to left), by the one constant; the other way snoozes. */
    private val dismissDir = NoticeThrow.dirOf(NoticeThrow.Kind.DISMISS)
    /** What a throw from [x0] to [x1] does to [key], by the same constant. */
    private fun thrown(key: String, x0: Float, x1: Float): NoticeShade.Effect =
        if (NoticeThrow.kindOfDx(x1 - x0) == NoticeThrow.Kind.DISMISS) NoticeShade.Effect.Dismissed(key) else NoticeShade.Effect.Snoozed(key)

    /** The notification on top once everything has come to rest. */
    private fun NoticePages.onTop(now: Long): String? = scene(now).pages.single().item?.key

    @Test fun `open, it is one page, the newest, in place`() {
        val s = open(3).scene(1000)
        val p = s.pages.single()
        assertEquals("k1", p.item?.key)
        assertEquals(0f, p.dx, 0f); assertEquals(0f, p.dy, 0f); assertEquals(1f, p.alpha, 0f)
        assertEquals(3, s.line.size)
        assertEquals(0f, s.focus, 0f)
        assertFalse(s.empty)
    }

    @Test fun `a stroke up from the middle of a page with several takes the whole pile away, not the page alone`() {
        val s = open(5)
        s.fingerDown(600f, 300f, 1000)
        for (i in 1..10) { s.fingerMove(600f, 300f - i * 25f, 1000 + i * 50L); s.progress(1000 + i * 50L) }
        // The page on top stays in its place on the pile, alone: no next page shows under it, the line's mark stays.
        val mid = s.scene(1500)
        val p = mid.pages.single()
        assertEquals("k1", p.item?.key)
        assertEquals(0f, p.dx, 0f); assertEquals(0f, p.dy, 0f)
        assertEquals(0f, mid.focus, 0f)
        // The whole pile follows the finger (as fast as a drawn frame lets it: ControlCenter.Chase).
        var shown = 1f
        for (t in 1500L..1800L step 16) shown = s.progress(t)
        assertEquals(1f - 250f / ControlCenter.TRAVEL, shown, 0.001f)
        // Past halfway and let go, it shuts: nothing is dismissed or snoozed, and it goes as one.
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.ShutAt(1800 + NoticeShade.SETTLE_MS)), s.fingerUp(600f, 50f, 1800))
        assertEquals("k1", s.scene(1900).pages.single().item?.key)
        assertTrue(s.isShut(1800 + NoticeShade.SETTLE_MS))
        assertEquals(5, s.count)
    }

    @Test fun `let go, the pile goes on up from where it stood and never jumps`() {
        val s = open(3)
        s.fingerDown(600f, 400f, 1000)
        var last = 1f
        for (i in 1..10) { s.fingerMove(600f, 400f - i * 20f, 1000 + i * 20L); last = s.progress(1000 + i * 20L) }
        assertTrue(s.fingerUp(600f, 200f, 1200).single() is NoticeShade.Effect.ShutAt)
        assertEquals(last, s.progress(1200), 0.001f)
        for (t in 1208L..1200L + NoticeShade.SETTLE_MS step 8) {
            val p = s.progress(t)
            assertTrue("at $t: $p after $last", p <= last + 0.001f && last - p < 0.1f)
            last = p
        }
        assertEquals(0f, last, 0.001f)
    }

    @Test fun `a short slow stroke up lets the pile back down, the same page on top`() {
        val s = open(3)
        assertTrue(s.stroke(600f, 400f, 600f, 340f, 1000, ms = 400).isEmpty())
        assertEquals("k1", s.onTop(1400 + NoticeShade.SETTLE_MS))
        assertEquals(1f, s.progress(1400 + NoticeShade.SETTLE_MS), 0f)
        assertEquals(3, s.count)
    }

    @Test fun `a flick up takes the pile away however short`() {
        val s = open(3)
        assertTrue(s.stroke(600f, 400f, 600f, 340f, 1000, ms = 50).single() is NoticeShade.Effect.ShutAt)
        assertTrue(s.isShut(1050 + NoticeShade.SETTLE_MS))
        assertEquals(3, s.count)
    }

    /** Pulled down and let go at 200: the pile still settles in for SETTLE_MS after that. */
    private fun settling(): NoticePages = NoticePages(left).apply {
        items((1..3).map(::item))
        beginPull(10f, 30f, 0); movePull(500f, 200); endPull(500f, 200)
    }

    @Test fun `a stroke up while the pile still settles in takes it from where it stands, it never jumps back to its place first`() {
        val s = settling()
        // The same pile left alone: where the settle has it at each moment.
        val alone = settling()
        s.fingerDown(600f, 400f, 250)
        assertTrue(s.progress(250) < 0.6f)
        var last = s.progress(250)
        var taken = false
        for (k in 1..30) {
            val t = 250L + 16L * k
            s.fingerMove(600f, 400f - 6f * k, t)
            val p = s.progress(t)
            if (!taken) {
                // Until the stroke is the pile's, and on the frame it becomes so, the pile is where the settle has it.
                assertEquals("at $t", alone.progress(t), p, 0.001f)
                taken = 6f * k >= EdgeGestures.SLOP
                assertTrue(!taken || p < 0.9f)
            } else {
                // From there it only goes up with the finger, a drawn frame's step at a time.
                assertTrue("at $t: $p after $last", p <= last + 0.001f && last - p < 0.05f)
            }
            last = p
        }
        assertTrue(taken)
        // Let go below halfway, it shuts, going on up from where it stood.
        assertTrue(s.fingerUp(600f, 220f, 730).single() is NoticeShade.Effect.ShutAt)
        assertEquals(last, s.progress(730), 0.001f)
        for (t in 738L..730L + NoticeShade.SETTLE_MS step 8) {
            val p = s.progress(t)
            assertTrue("at $t: $p after $last", p <= last + 0.001f)
            last = p
        }
        assertTrue(s.isShut(730 + NoticeShade.SETTLE_MS))
        assertEquals("k1", s.scene(800).pages.single().item?.key)
        assertEquals(3, s.count)
    }

    @Test fun `a stroke down while the pile still settles in leaves it settling, and back up past where it landed it is taken from there`() {
        val s = settling()
        val alone = settling()
        // Down: nothing, the pile goes on settling in as if no finger were there.
        s.fingerDown(600f, 400f, 210)
        for ((t, y) in listOf(226L to 410f, 242L to 420f, 258L to 405f)) {
            s.fingerMove(600f, y, t)
            assertEquals("at $t", alone.progress(t), s.progress(t), 0.001f)
        }
        // Back up past where it landed while it still settles: taken where it stands, then up with the finger.
        s.fingerMove(600f, 390f, 274)
        var last = s.progress(274)
        assertEquals(alone.progress(274), last, 0.001f)
        assertTrue(last < 0.9f)
        for (k in 1..10) {
            val t = 274L + 16L * k
            s.fingerMove(600f, 390f - 6f * k, t)
            val p = s.progress(t)
            assertTrue("at $t: $p after $last", p <= last + 0.001f && last - p < 0.05f)
            last = p
        }
        // And a stroke down alone, let go, does nothing at all: the settle ends in place as it would have.
        val d = settling()
        d.fingerDown(600f, 400f, 210)
        for (k in 1..10) d.fingerMove(600f, 400f + 12f * k, 210L + 16L * k)
        assertTrue(d.fingerUp(600f, 520f, 370).isEmpty())
        for (t in 370L..200L + NoticeShade.SETTLE_MS step 8) assertEquals("at $t", alone.progress(t), d.progress(t), 0.001f)
        assertEquals(1f, d.progress(1000), 0f)
        assertFalse(d.isShut(1000))
    }

    @Test fun `a stroke down on a later page brings nothing back down`() {
        // A new one arrived while he read the first: the page he is on has one before it, and down does not bring it.
        val s = open(3)
        s.items(listOf(item(9), item(1), item(2), item(3)))
        s.fingerDown(600f, 200f, 1000)
        s.fingerMove(600f, 350f, 1100)
        val mid = s.scene(1100)
        assertEquals("k1", mid.pages.single().item?.key)
        assertEquals(0f, mid.pages.single().dy, 0f)
        assertEquals(1f, mid.focus, 0f)
        assertEquals(1f, s.progress(1100), 0f)
        assertTrue(s.fingerUp(600f, 350f, 1300).isEmpty())
        assertEquals("k1", s.onTop(1300 + NoticePages.TURN_MS))
        assertFalse(s.moving(1300))
        // And on the page a throw brought up: the one thrown does not come back down either.
        assertEquals(thrown("k1", 700f, 450f), s.stroke(700f, 300f, 450f, 300f, 2000).single())
        assertEquals("k2", s.onTop(3000))
        assertTrue(s.stroke(600f, 200f, 600f, 450f, 3000).isEmpty())
        assertEquals("k2", s.onTop(3500))
        assertEquals(listOf("k9", "k2", "k3"), s.scene(3500).line.map { it.key })
        assertEquals(1f, s.progress(3500), 0f)
    }

    @Test fun `on the first page a stroke down does nothing`() {
        val s = open(3)
        s.fingerDown(600f, 200f, 1000)
        s.fingerMove(600f, 420f, 1100)
        assertEquals(0f, s.scene(1100).pages.single().dy, 0f)
        assertEquals(1f, s.progress(1100), 0f)
        assertTrue(s.fingerUp(600f, 420f, 1200).isEmpty())
        assertEquals("k1", s.onTop(1300))
        assertEquals(1f, s.progress(1300), 0f)
        assertFalse(s.moving(1300))
        // A fast one too.
        assertTrue(s.stroke(600f, 150f, 600f, 450f, 2000, ms = 50).isEmpty())
        assertEquals("k1", s.onTop(2100))
        assertEquals(1f, s.progress(2100), 0f)
    }

    @Test fun `on the last page too a stroke up takes the pile away`() {
        val s = open(2)
        s.stroke(500f, 300f, 800f, 300f, 1000)
        assertEquals("k2", s.onTop(2000))
        s.fingerDown(600f, 450f, 2000)
        s.fingerMove(600f, 250f, 2300)
        // The pile follows the finger (as fast as a drawn frame lets it: ControlCenter.Chase).
        var p = 1f
        for (t in 2300L..2600L step 16) p = s.progress(t)
        assertTrue("$p", p < 0.6f)
        assertEquals("k2", s.scene(2600).pages.single().item?.key)
        assertTrue(s.fingerUp(600f, 100f, 2700).single() is NoticeShade.Effect.ShutAt)
        assertTrue(s.isShut(2700 + NoticeShade.SETTLE_MS))
    }

    @Test fun `from the bottom edge a stroke up shuts it on any page`() {
        val s = open(5)
        val effects = s.stroke(600f, h - 10f, 600f, 150f, 1000, ms = 400)
        assertTrue(effects.single() is NoticeShade.Effect.ShutAt)
        assertTrue(s.isShut(1400 + NoticeShade.SETTLE_MS))
        // On a page with one before it, as on the first.
        val t = open(3).apply { items(listOf(item(9), item(1), item(2), item(3))) }
        assertTrue(t.stroke(600f, h - 10f, 600f, 150f, 1000, ms = 400).single() is NoticeShade.Effect.ShutAt)
        assertTrue(t.isShut(1400 + NoticeShade.SETTLE_MS))
    }

    @Test fun `the pile is gone through by throws alone - one way dismisses, the other snoozes, each brings up the next`() {
        val s = open(3)
        val x0 = 600f
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Dismissed("k1")), s.stroke(x0, 300f, x0 + dismissDir * 250f, 300f, 1000))
        assertEquals("k2", s.onTop(2000))
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Snoozed("k2")), s.stroke(x0, 300f, x0 - dismissDir * 250f, 300f, 2000))
        assertEquals("k3", s.onTop(3000))
        assertEquals(1, s.count)
        // The last one thrown takes the pile away with it.
        val last = s.stroke(x0, 300f, x0 + dismissDir * 250f, 300f, 3000)
        assertEquals(NoticeShade.Effect.Dismissed("k3"), last[0])
        assertTrue(last[1] is NoticeShade.Effect.ShutAt)
        assertTrue(s.isShut(3200 + NoticeShade.SETTLE_MS))
    }

    @Test fun `thrown sideways, a page is dismissed and the next takes its place`() {
        val s = open(3)
        val x1 = 600f + dismissDir * 200f
        s.fingerDown(600f, 300f, 1000)
        s.fingerMove(x1, 300f, 1100)
        val mid = s.scene(1100)
        assertEquals(listOf("k2", "k1"), mid.pages.map { it.item?.key })
        assertEquals(dismissDir * 200f, mid.pages[1].dx, 0.001f)
        // It follows the finger WHOLE: no fade (its corners are the display's, NoticePagesArt).
        assertEquals(1f, mid.pages[1].alpha, 0f)
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Dismissed("k1")), s.fingerUp(x1, 300f, 1150))
        // On its way out it is still drawn, over the one that took its place; then it is gone.
        assertEquals(listOf("k2", "k1"), s.scene(1160).pages.map { it.item?.key })
        assertEquals("k2", s.onTop(1150 + NoticeShade.FLY_MS))
        assertEquals(2, s.count)
        assertEquals(listOf("k2", "k3"), s.scene(2000).line.map { it.key })
        // The app's list still has it for a moment: it does not come back.
        s.items(listOf(item(1), item(2), item(3)))
        assertEquals(2, s.count)
        s.items(listOf(item(2), item(3)))
        assertEquals("k2", s.onTop(2100))
    }

    @Test fun `a short stroke sideways springs back`() {
        val s = open(2)
        assertTrue(s.stroke(500f, 300f, 600f, 300f, 1000).isEmpty())
        val p = s.scene(1200 + NoticeThrow.SPRING_MS).pages.single()
        assertEquals("k1", p.item?.key)
        assertEquals(0f, p.dx, 0f)
        assertEquals(2, s.count)
    }

    @Test fun `the last one thrown out leaves the one before it, and the only one leaves none`() {
        val s = NoticePages(left).apply {
            items(listOf(item(2)))
            beginPull(10f, 30f, 0); movePull(500f, 200); endPull(500f, 200)
            // One came while he read: the page he is on, the last, has one before it.
            items(listOf(item(1), item(2)))
        }
        assertEquals("k2", s.onTop(1000))
        assertEquals(thrown("k2", 500f, 800f), s.stroke(500f, 300f, 800f, 300f, 2000).single())
        assertEquals("k1", s.onTop(3000))
        // The last one thrown out takes the pile with it in the same step: no grey "nothing waiting" page jumps in.
        val effects = s.stroke(500f, 300f, 200f, 300f, 3000)
        assertEquals(thrown("k1", 500f, 200f), effects[0])
        assertTrue(effects[1] is NoticeShade.Effect.ShutAt)
        for (t in 3200L..3200L + NoticeShade.SETTLE_MS step 16) assertFalse("at $t", s.scene(t).empty)
        assertTrue(s.isShut(3200 + NoticeShade.SETTLE_MS))
        assertEquals(0, s.count)
    }

    @Test fun `with none waiting it says so, and a tap shuts it`() {
        val s = NoticePages(left).apply { beginPull(10f, 30f, 0); movePull(500f, 200); endPull(500f, 200) }
        assertTrue(s.scene(1000).empty)
        s.fingerDown(600f, 300f, 1000)
        assertTrue(s.fingerUp(600f, 300f, 1050).single() is NoticeShade.Effect.ShutAt)
    }

    @Test fun `a tap on a button's word presses it, a tap elsewhere on the page presses nothing`() {
        val s = NoticePages(left).apply {
            items(listOf(item(1, actions = listOf("Reply", "Mark as read")), item(2)))
            beginPull(10f, 30f, 0); movePull(500f, 200); endPull(500f, 200)
        }
        val x0 = NoticePages.wordX(0, 2, left)
        val x1 = NoticePages.wordX(1, 2, left)
        s.fingerDown(x0, NoticePages.WORD_Y, 1000)
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Action("k1", 0)), s.fingerUp(x0, NoticePages.WORD_Y, 1050))
        s.fingerDown(x1 + 40f, NoticePages.WORD_Y + 20f, 1100)
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Action("k1", 1)), s.fingerUp(x1 + 40f, NoticePages.WORD_Y + 20f, 1150))
        s.fingerDown(600f, 250f, 1200)
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Tapped("k1")), s.fingerUp(600f, 250f, 1250))
        // The words stand side by side about the middle of what he sees, and a page with no buttons has none to press.
        assertEquals((left + Panel.WIDTH) / 2f, (x0 + x1) / 2f, 0.001f)
        assertEquals(-1, NoticePages.actionAt(x0, NoticePages.WORD_Y, 0, left))
        assertEquals(-1, NoticePages.actionAt(x0, NoticePages.WORD_TOP - 5f, 2, left))
        assertEquals(0, NoticePages.actionAt((left + Panel.WIDTH) / 2f, NoticePages.WORD_Y, 1, left))
    }

    @Test fun `a new one arriving leaves him on the page he was reading`() {
        val s = open(3)
        // The first thrown out (still in the app's list for a moment), the second came up.
        s.stroke(700f, 300f, 450f, 300f, 1000)
        assertEquals("k2", s.onTop(2000))
        s.items(listOf(item(9), item(1), item(2), item(3)))
        val now = s.scene(2100)
        assertEquals("k2", now.pages.single().item?.key)
        assertEquals(1f, now.focus, 0f)
        // The one he was on taken away by its app: the one that stood under it.
        s.items(listOf(item(9), item(1), item(3)))
        assertEquals("k3", s.onTop(2200))
    }

    @Test fun `every opening starts at the newest`() {
        val s = open(3)
        // A new one came while he read the first: he stays on that one, and the next opening starts at the new one.
        s.items(listOf(item(9), item(1), item(2), item(3)))
        assertEquals("k1", s.onTop(1000))
        s.close(2000)
        s.beginPull(10f, 30f, 3000); s.movePull(500f, 3200); s.endPull(500f, 3200)
        assertEquals("k9", s.onTop(4000))
        assertEquals(0f, s.scene(4000).focus, 0f)
    }

    @Test fun `sideways is the page thrown out from the very edge too - a stroke that means something is never Back`() {
        assertEquals(thrown("k1", 890f, 600f), open(3).stroke(890f, 300f, 600f, 300f, 1000).single())
        assertEquals(thrown("k1", 800f, 500f), open(3).stroke(800f, 300f, 500f, 300f, 1000).single())
        // And from the cut-out's edge the other way.
        assertEquals(thrown("k1", 290f, 600f), open(3).stroke(290f, 300f, 600f, 300f, 1000).single())
        // A short one from the edge: the page springs back, and they stay up.
        val s = open(3)
        assertTrue(s.stroke(890f, 300f, 800f, 300f, 1000).isEmpty())
        assertEquals(3, s.count)
        assertTrue(s.shown(1500))
    }

    // -- the review's findings ------------------------------------------------------------------------------------

    @Test fun `a notification arriving while he is on the first page does not swap the page under his finger (39)`() {
        val s = open(2)
        // His finger lands on "k1"; a new one arrives; he throws the page out: it is k1 that goes, not the newcomer.
        s.fingerDown(700f, 300f, 1000)
        s.items(listOf(item(9), item(1), item(2)))
        assertEquals("k1", s.scene(1010).pages.last().item?.key)
        for (i in 1..10) s.fingerMove(700f - i * 30f, 300f, 1000 + i * 15L)
        assertEquals(listOf(thrown("k1", 700f, 400f)), s.fingerUp(400f, 300f, 1160))
        // And a tap: opened on k1, a new one arrives before the finger lands: still k1 under it.
        val t = NoticePages(left).apply {
            items(listOf(item(1, actions = listOf("Reply")), item(2)))
            beginPull(10f, 30f, 0); movePull(500f, 200); endPull(500f, 200)
            items(listOf(item(9, actions = listOf("Delete")), item(1, actions = listOf("Reply")), item(2)))
        }
        val x = NoticePages.wordX(0, 1, left)
        t.fingerDown(x, NoticePages.WORD_Y, 1000)
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Action("k1", 0)), t.fingerUp(x, NoticePages.WORD_Y, 1050))
    }

    @Test fun `a button that is not drawn is not pressed (41)`() {
        val s = NoticePages(left).apply {
            items(listOf(item(1, actions = listOf("Reply", "Mark as read"))))
            beginPull(10f, 30f, 0); movePull(500f, 200); endPull(500f, 200)
            words = false
        }
        val x = NoticePages.wordX(0, 2, left)
        s.fingerDown(x, NoticePages.WORD_Y, 1000)
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Tapped("k1")), s.fingerUp(x, NoticePages.WORD_Y, 1050))
    }

    @Test fun `a hand that rests on the back presses nothing and throws nothing out (42)`() {
        val s = NoticePages(left).apply {
            items(listOf(item(1, actions = listOf("Reply", "Mark as read")), item(2)))
            beginPull(10f, 30f, 0); movePull(500f, 200); endPull(500f, 200)
        }
        val x = NoticePages.wordX(0, 2, left)
        // Down on a button's word for two seconds, then lifted: no press.
        s.fingerDown(x, NoticePages.WORD_Y, 1000)
        assertTrue(s.fingerUp(x, NoticePages.WORD_Y, 3000).isEmpty())
        // A slow drift sideways over three seconds: the page springs back, nothing is dismissed.
        s.fingerDown(700f, 300f, 4000)
        for (i in 1..30) s.fingerMove(700f - i * 8f, 300f, 4000 + i * 100L)
        assertTrue(s.fingerUp(460f, 300f, 7100).isEmpty())
        assertEquals(2, s.count)
    }

    @Test fun `up and back down on a page, let go while it trails - it settles open, not part-way (47)`() {
        // Once on the last page only; now on any page, the only one or the first of several.
        for (count in listOf(1, 3)) {
            val s = open(count)
            s.fingerDown(600f, 450f, 1000)
            s.fingerMove(600f, 250f, 1040)
            s.progress(1040)
            s.fingerMove(600f, 451f, 1080)
            s.progress(1080)
            assertTrue("$count", s.fingerUp(600f, 451f, 1090).isEmpty())
            var p = 0f
            for (t in 1090L..1090L + NoticeShade.SETTLE_MS + 400 step 16) p = s.progress(t)
            assertEquals("$count", 1f, p, 0.001f)
            assertFalse("$count", s.moving(1090 + NoticeShade.SETTLE_MS + 400))
            assertEquals("$count", "k1", s.onTop(2000))
        }
    }

    // -- the other reading: a page of logos first ------------------------------------------------

    @Test fun `logos first, the pull brings the logos alone and a stroke up the newest`() {
        val s = open(3, logos = true)
        val first = s.scene(1000)
        assertNull(first.pages.single().item)
        assertEquals(-1f, first.focus, 0f)
        assertTrue(s.stroke(600f, 400f, 600f, 200f, 1000).isEmpty())
        assertEquals("k1", s.onTop(2000))
        assertEquals(0f, s.scene(2000).focus, 0f)
        assertEquals(1f, s.progress(2000), 0f)
    }

    @Test fun `logos first, the logos' page on its way up never jumps`() {
        val s = open(3, logos = true)
        s.fingerDown(600f, 400f, 1000); s.fingerMove(600f, 280f, 1200)
        val mid = s.scene(1200)
        // The newest under it, the logos' page going up over it.
        assertEquals(listOf("k1", null), mid.pages.map { it.item?.key })
        assertEquals(-120f, mid.pages[1].dy, 0.001f)
        s.fingerUp(600f, 280f, 1200)
        var last = -120f
        for (t in 1200L..1200L + NoticePages.TURN_MS step 8) {
            val dy = s.scene(t).pages.last().takeIf { it.item == null }?.dy ?: break
            assertTrue("at $t: $dy after $last", dy <= last + 0.001f && last - dy < 60f)
            last = dy
        }
        assertTrue(last < -h * 0.9f)
        assertEquals("k1", s.onTop(1200 + NoticePages.TURN_MS))
    }

    @Test fun `logos first, the pages under the logos lose the up and down - down brings no logos back, up takes the pile away`() {
        val s = open(3, logos = true)
        s.stroke(600f, 400f, 600f, 200f, 1000)
        assertEquals("k1", s.onTop(2000))
        // Down from the first page: the logos' page does not come back, and nothing turns.
        assertTrue(s.stroke(600f, 200f, 600f, 420f, 2000).isEmpty())
        assertEquals("k1", s.onTop(2500))
        assertEquals(0f, s.scene(2500).focus, 0f)
        // Thrown out, the next comes up, as without the logos.
        assertEquals(thrown("k1", 700f, 450f), s.stroke(700f, 300f, 450f, 300f, 3000).single())
        assertEquals("k2", s.onTop(4000))
        // Up from the middle of a page: the whole pile goes.
        assertTrue(s.stroke(600f, 400f, 600f, 150f, 4000, ms = 400).single() is NoticeShade.Effect.ShutAt)
        assertTrue(s.isShut(4400 + NoticeShade.SETTLE_MS))
        // And on the logos' page itself, up from the bottom edge still takes the pile away.
        val t = open(3, logos = true)
        assertTrue(t.stroke(600f, h - 10f, 600f, 150f, 1000, ms = 400).single() is NoticeShade.Effect.ShutAt)
        assertTrue(t.isShut(1400 + NoticeShade.SETTLE_MS))
    }

    @Test fun `logos first, a tap on a logo goes to that app's newest`() {
        val s = NoticePages(left).apply {
            logosFirst = true
            items(listOf(item(1, "a"), item(2, "b"), item(3, "a"), item(4, "c")))
            beginPull(10f, 30f, 0); movePull(500f, 200); endPull(500f, 200)
        }
        val apps = NoticePages.apps(listOf(item(1, "a"), item(2, "b"), item(3, "a"), item(4, "c")))
        assertEquals(listOf("a", "b", "c"), apps.map { it.pkg })
        val boxes = NoticePages.logoBoxes(3, left)
        // Centred in what he sees, none on another.
        assertEquals((left + Panel.WIDTH) / 2f, boxes[1][0], 0.001f)
        assertEquals(h / 2f, boxes[1][1], 0.001f)
        assertTrue(boxes[1][0] - boxes[0][0] > boxes[0][2])
        s.fingerDown(boxes[2][0], boxes[2][1], 1000)
        assertTrue(s.fingerUp(boxes[2][0], boxes[2][1], 1050).isEmpty())
        assertEquals("k4", s.onTop(1050 + NoticePages.TURN_MS))
        // And down from there, however often, nothing: no page before it comes down, nor the logos'.
        for (t in listOf(2000L, 3000L, 4000L, 5000L)) assertTrue(s.stroke(600f, 200f, 600f, 420f, t).isEmpty())
        assertEquals("k4", s.onTop(6000))
        assertEquals(1f, s.progress(6000), 0f)
    }

    @Test fun `eight logos stand in two rows inside what he sees`() {
        val boxes = NoticePages.logoBoxes(8, left)
        assertEquals(8, boxes.size)
        for (b in boxes) {
            assertTrue(b[0] - b[2] / 2f > left && b[0] + b[2] / 2f < Panel.WIDTH)
            assertTrue(b[1] - b[2] / 2f > 0f && b[1] + b[2] / 2f < h)
        }
        assertEquals(2, boxes.map { it[1] }.distinct().size)
    }

    // -- standing settled over the whole back (NoticePages.covers, for HiddenFrames) --------------------------------

    /** As ShellNotices reads a frame: the scene, whether it moves, how far down, and whether it covers the back. */
    private fun NoticePages.coversAt(now: Long): Boolean {
        val sc = scene(now); val m = moving(now); val p = progress(now)
        return covers(p, m, sc)
    }

    @Test fun `settled down, the pile covers the back - pulled, settling in or going up, it does not`() {
        val s = NoticePages(left).apply { items((1..3).map(::item)) }
        s.beginPull(10f, 30f, 0); s.movePull(300f, 100)
        assertFalse("pulled", s.coversAt(100))
        s.endPull(500f, 200)
        for (t in 200L until 200L + NoticeShade.SETTLE_MS step 8) assertFalse("settling in at $t", s.coversAt(t))
        assertTrue(s.coversAt(200L + NoticeShade.SETTLE_MS))
        assertTrue(s.coversAt(5_000))
        s.close(5_000)
        for (t in 5_000L..5_000L + NoticeShade.SETTLE_MS step 8) assertFalse("going up at $t", s.coversAt(t))
    }

    @Test fun `a finger landing on the pile ends it at once, before anything moves - a tap gives it back`() {
        val s = open(3)
        assertTrue(s.coversAt(1000))
        s.fingerDown(600f, 300f, 1000)
        assertFalse("a finger on it, nothing moved yet", s.coversAt(1000))
        s.fingerMove(601f, 300f, 1010)
        assertFalse(s.coversAt(1010))
        s.fingerUp(601f, 300f, 1050)
        assertTrue("lifted: settled again", s.coversAt(1060))
    }

    @Test fun `a page thrown out, its next coming up, or springing back does not cover until it is at rest`() {
        val s = open(3)
        s.fingerDown(700f, 300f, 1000); s.fingerMove(480f, 300f, 1100)
        assertFalse("aside under the finger", s.coversAt(1100))
        s.fingerUp(480f, 300f, 1150)
        for (t in 1150L until 1150L + NoticeShade.FLY_MS step 8) assertFalse("flying at $t", s.coversAt(t))
        assertTrue("the next at rest", s.coversAt(1150L + maxOf(NoticeShade.FLY_MS, NoticeThrow.LABEL_MS, NoticeThrow.SPRING_MS)))
        // A short stroke: back on its spring, then at rest.
        val t = open(3)
        t.fingerDown(700f, 300f, 1000); t.fingerMove(690f, 300f, 1100); t.fingerMove(680f, 300f, 1120)
        t.fingerUp(680f, 300f, 1150)
        assertFalse("springing back", t.coversAt(1160))
        assertTrue(t.coversAt(1150L + NoticeThrow.SPRING_MS))
    }

    @Test fun `the last one thrown takes the pile up - never covering on the way`() {
        val s = open(1)
        s.fingerDown(700f, 300f, 1000); s.fingerMove(480f, 300f, 1100); s.fingerUp(480f, 300f, 1150)
        for (t in 1150L..1150L + NoticeShade.SETTLE_MS step 8) assertFalse("at $t", s.coversAt(t))
    }

    @Test fun `the one line with nothing waiting, and the logos' page, are whole pages too`() {
        val none = open(2).apply { items(emptyList()) }
        assertTrue(none.scene(1000).empty)
        assertTrue(none.coversAt(1000))
        assertTrue(open(3, logos = true).coversAt(1000))
    }
}
