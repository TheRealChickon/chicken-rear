package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.rear.EdgeGestures.Action
import dev.kb896.aiusage.rear.RearTouches.Down
import dev.kb896.aiusage.rear.RearTouches.Move
import dev.kb896.aiusage.rear.RearTouches.Up
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalPullTest {

    /** The 17 Pro's lens column, in panel units. */
    private val pro = Panel.CUTOUT_RIGHT.toFloat()

    @Test fun `a finger in the bottom band right of the lenses starts a pull`() {
        assertTrue(PortalPull.startsPull(600f, 560f, pro))
        assertTrue(PortalPull.startsPull(600f, 572f - PortalPull.EDGE, pro))
    }

    @Test fun `higher up, or over the lens column, it does not`() {
        assertFalse(PortalPull.startsPull(600f, 400f, pro))
        assertFalse(PortalPull.startsPull(200f, 560f, pro))
    }

    @Test fun `on a Pro Max the band starts where its lenses end, in panel units`() {
        // 296 lens pixels of 976 are 274 panel units: a finger at 280 is clear of them there.
        val left = RearGeometry(976, 596, 296, 110, 450).cutoutUnits
        assertTrue(PortalPull.startsPull(280f, 560f, left))
        assertFalse(PortalPull.startsPull(270f, 560f, left))
        assertFalse("the 17 Pro's band starts at 296", PortalPull.startsPull(280f, 560f, pro))
    }

    // -- companion mode's top pull: x 296..914 of the rear's pixels (clear of the camera island
    // and Xiaomi's back strip), committed 60 units down, its y mirrored so the sheet rises as
    // it would from the bottom.

    /** The 17 Pro Max: 976 px across for the panel's 904 units. */
    private val fitX = 976f / Panel.WIDTH

    @Test fun `the band is x 296 to 914 rear pixels, at the top edge only`() {
        assertTrue(PortalPull.startsTopPull(296f / fitX, 10f, fitX))
        assertTrue(PortalPull.startsTopPull(914f / fitX, 10f, fitX))
        assertTrue(PortalPull.startsTopPull(600f / fitX, PortalPull.EDGE, fitX))
        assertFalse(PortalPull.startsTopPull(295f / fitX, 10f, fitX))   // the camera island
        assertFalse(PortalPull.startsTopPull(915f / fitX, 10f, fitX))   // Xiaomi's back strip
        assertFalse(PortalPull.startsTopPull(600f / fitX, PortalPull.EDGE + 1f, fitX))
    }

    @Test fun `it commits 60 units down, more down than across`() {
        assertFalse(PortalPull.topCommits(500f, 10f, 500f, 69f))
        assertTrue(PortalPull.topCommits(500f, 10f, 500f, 70f))
        assertFalse(PortalPull.topCommits(500f, 10f, 580f, 80f)) // more across than down
    }

    @Test fun `sideways or back up before the commit is abandoned`() {
        assertTrue(PortalPull.topAbandoned(500f, 10f, 530f, 20f, EdgeGestures.SLOP))
        assertTrue(PortalPull.topAbandoned(500f, 30f, 500f, 10f, EdgeGestures.SLOP))
        assertFalse(PortalPull.topAbandoned(500f, 10f, 505f, 40f, EdgeGestures.SLOP))
    }

    @Test fun `mirrored, a pull down reads as the same pull up from the bottom edge`() {
        assertEquals(Panel.HEIGHT.toFloat(), PortalPull.mirrored(10f, 10f))
        assertEquals(Panel.HEIGHT - 60f, PortalPull.mirrored(70f, 10f))
    }

    private fun gestures(topPortal: Boolean) = EdgeGestures(296f / fitX, fitX).also { g -> g.topPortal = { topPortal } }

    @Test fun `in companion mode the top pull opens the sheet once it commits, mirrored`() {
        val g = gestures(topPortal = true)
        val x = 600f
        val out = listOf(Down(x, 10f, 0), Move(x, 40f, 10), Move(x, 80f, 20), Move(x, 120f, 30), Up(x, 130f, 40)).flatMap { g.feed(it) }
        assertEquals(
            listOf(
                Action.PullStart(PortalPull.mirrored(80f, 10f)),
                Action.PullMove(PortalPull.mirrored(120f, 10f)),
                Action.PullEnd(PortalPull.mirrored(130f, 10f)),
            ),
            out,
        )
    }

    @Test fun `short of the commit it is Xiaomi's, nothing of ours`() {
        val g = gestures(topPortal = true)
        assertEquals(emptyList<Action>(), listOf(Down(600f, 10f, 0), Move(600f, 50f, 10), Up(600f, 50f, 20)).flatMap { g.feed(it) })
    }

    @Test fun `in companion mode the bottom band is Xiaomi's home strip, not a pull`() {
        val g = gestures(topPortal = true)
        val out = listOf(Down(600f, 560f, 0), Move(600f, 500f, 10), Up(600f, 400f, 20)).flatMap { g.feed(it) }
        assertEquals(emptyList<Action>(), out)
    }

    @Test fun `with notifications on, the pull down from the top is theirs, anywhere between the lenses and the back strip`() {
        for (x in listOf(320f / fitX, 600f / fitX, 900f / fitX)) {
            val g = gestures(topPortal = true).also { it.topNotices = { true } }
            val out = listOf(Down(x, 10f, 0), Move(x, 40f, 10), Move(x, 120f, 20), Up(x, 130f, 30)).flatMap { g.feed(it) }
            assertEquals(listOf(Action.NoticePullStart(40f), Action.NoticePullMove(120f), Action.NoticePullEnd(130f)), out)
        }
    }

    @Test fun `over the lenses and on Xiaomi's strips the notifications' pull does not start`() {
        val g = gestures(topPortal = false).also { it.topNotices = { true } }
        // Over the lens column, on the back strip's corner, and on the home strip at the bottom.
        assertEquals(emptyList<Action>(), listOf(Down(100f / fitX, 10f, 0), Move(100f / fitX, 120f, 10), Up(100f / fitX, 130f, 20)).flatMap { g.feed(it) })
        assertEquals(emptyList<Action>(), listOf(Down(950f / fitX, 10f, 0), Move(950f / fitX, 120f, 10), Up(950f / fitX, 130f, 20)).flatMap { g.feed(it) })
        assertEquals(emptyList<Action>(), listOf(Down(600f / fitX, 560f, 0), Move(600f / fitX, 480f, 10), Up(600f / fitX, 400f, 20)).flatMap { g.feed(it) })
    }

    @Test fun `outside companion mode the top band is the control centre's, as before`() {
        val g = gestures(topPortal = false)
        val out = listOf(Down(400f, 10f, 0), Move(400f, 40f, 10), Up(400f, 60f, 20)).flatMap { g.feed(it) }
        assertTrue(out.none { it is Action.PullStart })
        assertTrue(out.any { it is Action.TopPullStart || it is Action.NoticePullStart })
    }

    @Test fun `the touch watch in companion mode is a guest, a shell pane, or the lit rear with More apps`() {
        assertTrue(PortalPull.companionTouchWanted(guestOnTop = true, shellUp = false, rearLit = false, moreApps = false))
        assertTrue(PortalPull.companionTouchWanted(guestOnTop = false, shellUp = true, rearLit = false, moreApps = false))
        assertTrue(PortalPull.companionTouchWanted(guestOnTop = false, shellUp = false, rearLit = true, moreApps = true))
        assertFalse(PortalPull.companionTouchWanted(guestOnTop = false, shellUp = false, rearLit = true, moreApps = false))
        assertFalse(PortalPull.companionTouchWanted(guestOnTop = false, shellUp = false, rearLit = false, moreApps = true))
    }

    @Test fun `with More apps off, a lit back is still read for the double tap's latch when a wake of ours can come`() {
        assertTrue(PortalPull.companionTouchWanted(guestOnTop = false, shellUp = false, rearLit = true, moreApps = false, doubleTaps = true))
        assertFalse(PortalPull.companionTouchWanted(guestOnTop = false, shellUp = false, rearLit = false, moreApps = false, doubleTaps = true))
    }
}
