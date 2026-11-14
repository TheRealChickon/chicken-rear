package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notifications pulled down as pop-up pages: one page at a time, up for the next, down for the one before,
 * sideways to dismiss, and the old ways out.
 */
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

    @Test fun `a stroke up sends the page up and the next one is under it`() {
        val s = open(3)
        s.fingerDown(600f, 400f, 1000)
        s.fingerMove(600f, 300f, 1100)
        val mid = s.scene(1100)
        assertEquals(listOf("k2", "k1"), mid.pages.map { it.item?.key })
        assertEquals(0f, mid.pages[0].dy, 0f)
        assertEquals(-100f, mid.pages[1].dy, 0.001f)
        // The line's mark is on its way from the first to the second, by how much of the page has gone.
        assertEquals(100f / h, mid.focus, 0.001f)
        assertTrue(s.fingerUp(600f, 300f, 1300).isEmpty())
        assertTrue(s.moving(1310))
        assertEquals("k2", s.onTop(1300 + NoticePages.TURN_MS))
        assertEquals(1f, s.scene(1300 + NoticePages.TURN_MS).focus, 0f)
        assertFalse(s.moving(1300 + NoticePages.TURN_MS + NoticeShade.SETTLE_MS))
        assertEquals(1f, s.progress(2000), 0f)
    }

    @Test fun `a page on its way never jumps`() {
        val s = open(3)
        s.fingerDown(600f, 400f, 1000); s.fingerMove(600f, 280f, 1200); s.fingerUp(600f, 280f, 1200)
        var last = -120f
        for (t in 1200L..1200L + NoticePages.TURN_MS step 8) {
            val dy = s.scene(t).pages.last().takeIf { it.item?.key == "k1" }?.dy ?: break
            assertTrue("at $t: $dy after $last", dy <= last + 0.001f && last - dy < 60f)
            last = dy
        }
        assertTrue(last < -h * 0.9f)
    }

    @Test fun `a short slow stroke up comes back`() {
        val s = open(3)
        assertTrue(s.stroke(600f, 400f, 600f, 340f, 1000, ms = 400).isEmpty())
        assertEquals("k1", s.onTop(1400 + NoticePages.TURN_MS))
    }

    @Test fun `a flick turns the page however short`() {
        val s = open(3)
        s.stroke(600f, 400f, 600f, 340f, 1000, ms = 50)
        assertEquals("k2", s.onTop(1050 + NoticePages.TURN_MS))
    }

    @Test fun `a stroke down brings the one before back down from the top`() {
        val s = open(3)
        s.stroke(600f, 400f, 600f, 200f, 1000)
        assertEquals("k2", s.onTop(2000))
        s.fingerDown(600f, 200f, 2000)
        s.fingerMove(600f, 350f, 2100)
        val mid = s.scene(2100)
        assertEquals(listOf("k2", "k1"), mid.pages.map { it.item?.key })
        assertEquals(-h + 150f, mid.pages[1].dy, 0.001f)
        s.fingerUp(600f, 350f, 2300)
        assertEquals("k1", s.onTop(2300 + NoticePages.TURN_MS))
    }

    @Test fun `on the first page a stroke down does nothing`() {
        val s = open(3)
        assertTrue(s.stroke(600f, 200f, 600f, 420f, 1000).isEmpty())
        assertEquals("k1", s.onTop(1300))
        assertEquals(1f, s.progress(1300), 0f)
    }

    @Test fun `on the last page a stroke up takes the pile away`() {
        val s = open(2)
        s.stroke(600f, 400f, 600f, 200f, 1000)
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
    }

    @Test fun `thrown sideways, a page is dismissed and the next takes its place`() {
        val s = open(3)
        s.fingerDown(500f, 300f, 1000)
        s.fingerMove(700f, 300f, 1100)
        val mid = s.scene(1100)
        assertEquals(listOf("k2", "k1"), mid.pages.map { it.item?.key })
        assertEquals(200f, mid.pages[1].dx, 0.001f)
        assertTrue(mid.pages[1].alpha < 1f)
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Dismissed("k1")), s.fingerUp(700f, 300f, 1150))
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
        val p = s.scene(1200 + NoticeShade.SPRING_MS).pages.single()
        assertEquals("k1", p.item?.key)
        assertEquals(0f, p.dx, 0f)
        assertEquals(2, s.count)
    }

    @Test fun `the last one thrown out leaves the one before it, and the only one leaves none`() {
        val s = open(2)
        s.stroke(600f, 400f, 600f, 200f, 1000)
        s.stroke(500f, 300f, 800f, 300f, 2000)
        assertEquals("k1", s.onTop(3000))
        // The last one thrown out takes the pile with it in the same step: no grey "nothing waiting" page jumps in.
        val effects = s.stroke(500f, 300f, 200f, 300f, 3000)
        assertTrue(effects[0] is NoticeShade.Effect.Dismissed)
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
        s.stroke(600f, 400f, 600f, 200f, 1000)
        assertEquals("k2", s.onTop(2000))
        s.items(listOf(item(9), item(1), item(2), item(3)))
        val now = s.scene(2100)
        assertEquals("k2", now.pages.single().item?.key)
        assertEquals(2f, now.focus, 0f)
        // The one he was on taken away by its app: the one that stood under it.
        s.items(listOf(item(9), item(1), item(3)))
        assertEquals("k3", s.onTop(2200))
    }

    @Test fun `every opening starts at the newest`() {
        val s = open(3)
        s.stroke(600f, 400f, 600f, 200f, 1000)
        s.close(2000)
        s.beginPull(10f, 30f, 3000); s.movePull(500f, 3200); s.endPull(500f, 3200)
        assertEquals("k1", s.onTop(4000))
    }

    @Test fun `sideways is the page thrown out from the very edge too - a stroke that means something is never Back`() {
        assertTrue(open(3).stroke(890f, 300f, 600f, 300f, 1000).single() is NoticeShade.Effect.Dismissed)
        assertTrue(open(3).stroke(800f, 300f, 500f, 300f, 1000).single() is NoticeShade.Effect.Dismissed)
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
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Dismissed("k1")), s.fingerUp(400f, 300f, 1160))
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

    @Test fun `up and back down on the last page, let go while it trails - it settles open, not part-way (47)`() {
        val s = open(1)
        s.fingerDown(600f, 450f, 1000)
        s.fingerMove(600f, 250f, 1040)
        s.progress(1040)
        s.fingerMove(600f, 451f, 1080)
        s.progress(1080)
        s.fingerUp(600f, 451f, 1090)
        var p = 0f
        for (t in 1090L..1090L + NoticeShade.SETTLE_MS + 400 step 16) p = s.progress(t)
        assertEquals(1f, p, 0.001f)
        assertFalse(s.moving(1090 + NoticeShade.SETTLE_MS + 400))
    }

    // -- the other reading: a page of logos first ------------------------------------------------

    @Test fun `logos first, the pull brings the logos alone and a stroke up the newest`() {
        val s = open(3, logos = true)
        val first = s.scene(1000)
        assertNull(first.pages.single().item)
        assertEquals(-1f, first.focus, 0f)
        s.stroke(600f, 400f, 600f, 200f, 1000)
        assertEquals("k1", s.onTop(2000))
        assertEquals(0f, s.scene(2000).focus, 0f)
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
        // And down from the first page, the logos again.
        s.stroke(600f, 200f, 600f, 420f, 2000)
        s.stroke(600f, 200f, 600f, 420f, 3000)
        s.stroke(600f, 200f, 600f, 420f, 4000)
        s.stroke(600f, 200f, 600f, 420f, 5000)
        assertNull(s.scene(6000).pages.single().item)
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
}
