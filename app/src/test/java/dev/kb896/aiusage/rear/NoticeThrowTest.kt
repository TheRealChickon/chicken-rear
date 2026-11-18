package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NoticeThrowTest {
    private val left = 274f
    private fun item(n: Int, clearable: Boolean = true, key: String = "k$n") =
        NoticeShade.Item(key, "p$n", "App", "From $n", "Text", clearable = clearable)

    private fun open(items: List<NoticeShade.Item>): NoticePages = NoticePages(left).apply {
        items(items)
        beginPull(10f, 30f, 0); movePull(500f, 200); endPull(500f, 200)
    }

    private fun NoticePages.stroke(x0: Float, x1: Float, t0: Long, y: Float = 300f, ms: Long = 200): List<NoticeShade.Effect> {
        fingerDown(x0, y, t0)
        for (i in 1..10) fingerMove(x0 + (x1 - x0) * i / 10f, y, t0 + ms * i / 10)
        return fingerUp(x1, y, t0 + ms)
    }

    private val L = RearNav.Way.LEFT
    private val R = RearNav.Way.RIGHT

    // -- the way: one constant ---------------------------------------------------------------------------------------

    @Test fun `right to left dismisses and left to right snoozes, by the one constant, and everything follows it when flipped`() {
        assertEquals(L, RearNav.DISMISS_WAY)
        assertEquals(NoticeThrow.Kind.DISMISS, NoticeThrow.kindOf(L))
        assertEquals(NoticeThrow.Kind.SNOOZE, NoticeThrow.kindOf(R))
        assertNull(NoticeThrow.kindOf(RearNav.Way.UP))
        assertNull(NoticeThrow.kindOf(RearNav.Way.DOWN))
        assertEquals(NoticeThrow.Kind.DISMISS, NoticeThrow.kindOfDx(-200f))
        assertEquals(NoticeThrow.Kind.SNOOZE, NoticeThrow.kindOfDx(200f))
        assertEquals(-1f, NoticeThrow.dirOf(NoticeThrow.Kind.DISMISS), 0f)
        assertEquals(1f, NoticeThrow.dirOf(NoticeThrow.Kind.SNOOZE), 0f)
        // Flipped (his to say), the other way round, and nothing else changes.
        assertEquals(NoticeThrow.Kind.SNOOZE, NoticeThrow.kindOf(L, dismissWay = R))
        assertEquals(NoticeThrow.Kind.DISMISS, NoticeThrow.kindOf(R, dismissWay = R))
        assertEquals(NoticeThrow.Kind.DISMISS, NoticeThrow.kindOfDx(200f, dismissWay = R))
        assertEquals(1f, NoticeThrow.dirOf(NoticeThrow.Kind.DISMISS, dismissWay = R), 0f)
        // Its hour is Android's hour.
        assertEquals(3_600_000L, NoticeThrow.SNOOZE_MS)
    }

    // -- the pile ------------------------------------------------------------------------------------------------------

    @Test fun `on the pile, thrown right to left it is dismissed, and the next comes up under it`() {
        val s = open((1..3).map { item(it) })
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Dismissed("k1")), s.stroke(700f, 450f, 1000))
        // It flies on to the left, whole, over the next one.
        val flying = s.scene(1260)
        assertEquals(listOf("k2", "k1"), flying.pages.map { it.item?.key })
        assertTrue(flying.pages[1].dx < -250f)
        assertEquals(1f, flying.pages[1].alpha, 0f)
        assertEquals("k2", s.scene(1200 + NoticeShade.FLY_MS).pages.single().item?.key)
        assertEquals(2, s.count)
    }

    @Test fun `on the pile, thrown left to right it is snoozed for an hour, and the next comes up under it`() {
        val s = open((1..3).map { item(it) })
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Snoozed("k1", NoticeThrow.SNOOZE_MS)), s.stroke(400f, 650f, 1000))
        val flying = s.scene(1260)
        assertEquals(listOf("k2", "k1"), flying.pages.map { it.item?.key })
        assertTrue(flying.pages[1].dx > 250f)
        assertEquals("k2", s.scene(1200 + NoticeShade.FLY_MS).pages.single().item?.key)
        // And a second one, the same way: the third is on top.
        assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Snoozed("k2")), s.stroke(400f, 650f, 2000))
        assertEquals("k3", s.scene(2200 + NoticeShade.FLY_MS).pages.single().item?.key)
    }

    @Test fun `a page on its way out never jumps back and never fades`() {
        for (x1 in listOf(300f, 800f)) {
            val s = open((1..2).map { item(it) })
            assertEquals(1, s.stroke(550f, x1, 1000).size)
            val dir = if (x1 < 550f) -1f else 1f
            var last = (x1 - 550f)
            for (t in 1200L..1200L + NoticeShade.FLY_MS step 8) {
                val p = s.scene(t).pages.firstOrNull { it.item?.key == "k1" } ?: break
                assertTrue("at $t: ${p.dx} after $last", (p.dx - last) * dir >= -0.001f)
                assertEquals(1f, p.alpha, 0f)
                last = p.dx
            }
        }
    }

    @Test fun `each throw leaves its one word rising for at most 0,6 s, centred on x 636`() {
        for ((x1, words) in listOf(300f to "Dismissed", 800f to "Snoozed 1 h")) {
            val s = open((1..3).map { item(it) })
            s.stroke(550f, x1, 1000)
            val first = s.scene(1210).label
            assertNotNull(first)
            assertEquals(words, first!!.words)
            var y = first.y
            for (t in 1220L until 1200L + NoticeThrow.LABEL_MS step 20) {
                val l = s.scene(t).label ?: error("no label at $t")
                assertTrue("it rises: ${l.y} after $y", l.y <= y + 0.001f)
                assertTrue(l.alpha in 0f..1f)
                y = l.y
                assertTrue("the page keeps being drawn while its label shows", s.moving(t))
            }
            assertNull(s.scene(1200 + NoticeThrow.LABEL_MS).label)
            assertTrue("it rose", first.y - y > 10f)
        }
        // The middle of what he sees past the lenses (296 to 976 of the back's pixels): his rule for any words.
        assertEquals(636f, NoticeThrow.LABEL_X, 0f)
        assertEquals(NoticePop.CENTRE, NoticeThrow.LABEL_X, 0f)
        // Within the back, above the place a message is set (NoticePop.MIDDLE), for every moment of it.
        for (t in 0L until NoticeThrow.LABEL_MS step 10) {
            val l = NoticeThrow.labelAt(NoticeThrow.Kind.DISMISS, t)!!
            assertTrue(l.y - NoticeThrow.LABEL_SIZE > 0f && l.y < NoticePop.MIDDLE - 100f)
        }
        assertNull(NoticeThrow.labelAt(NoticeThrow.Kind.SNOOZE, NoticeThrow.LABEL_MS))
        assertNull(NoticeThrow.labelAt(NoticeThrow.Kind.SNOOZE, -1))
    }

    @Test fun `a short throw comes back on a spring and does nothing`() {
        val s = open((1..2).map { item(it) })
        assertTrue(s.stroke(550f, 650f, 1000).isEmpty())
        val back = (1200L..1200L + NoticeThrow.SPRING_MS step 8).map { s.scene(it).pages.last().dx }
        // It passes its place a little once (a spring) and comes to rest in it, with no leap.
        assertTrue("it passes its place: ${back.minOrNull()}", back.minOrNull()!! < 0f)
        assertTrue(back.zipWithNext().all { (a, b) -> abs(a - b) < 40f })
        assertEquals(0f, s.scene(1200 + NoticeThrow.SPRING_MS).pages.single().dx, 0f)
        assertNull(s.scene(1300).label)
        assertEquals(2, s.count)
    }

    // -- one that may not be thrown: ongoing, not clearable, a call, a timer, a player -----------------------------------

    @Test fun `one that may not be thrown gives a little under the finger, springs back and stays, either way`() {
        for (x1 in listOf(150f, 900f)) {
            val s = open(listOf(item(1, clearable = false), item(2)))
            s.fingerDown(550f, 300f, 1000)
            for (i in 1..10) s.fingerMove(550f + (x1 - 550f) * i / 10f, 300f, 1000L + i * 20)
            val held = s.scene(1200).pages.last()
            assertEquals("k1", held.item?.key)
            assertTrue("it gives, it does not go: ${held.dx}", abs(held.dx) <= NoticeThrow.GIVE_MOST && abs(held.dx) > 10f)
            assertEquals(listOf<NoticeShade.Effect>(NoticeShade.Effect.Refused("k1")), s.fingerUp(x1, 300f, 1200))
            assertEquals(0f, s.scene(1200 + NoticeThrow.SPRING_MS).pages.single().dx, 0f)
            assertEquals("k1", s.scene(1200 + NoticeThrow.SPRING_MS).pages.single().item?.key)
            assertNull(s.scene(1300).label)
            assertEquals(2, s.count)
        }
    }

    @Test fun `what may be thrown is what the phone lets be swiped away`() {
        assertTrue(NoticeThrow.throwable(ongoing = false, clearable = true, category = null, media = false))
        assertTrue(NoticeThrow.throwable(ongoing = false, clearable = true, category = "msg", media = false))
        assertTrue(NoticeThrow.throwable(ongoing = false, clearable = true, category = "email", media = false))
        assertFalse(NoticeThrow.throwable(ongoing = true, clearable = true, category = null, media = false))
        assertFalse(NoticeThrow.throwable(ongoing = false, clearable = false, category = null, media = false))
        assertFalse(NoticeThrow.throwable(ongoing = false, clearable = true, category = null, media = true))
        for (c in listOf("call", "alarm", "stopwatch", "transport", "service", "progress", "navigation")) {
            assertFalse(c, NoticeThrow.throwable(ongoing = false, clearable = true, category = c, media = false))
        }
        // The strings are Android's own (Notification.CATEGORY_*).
        assertEquals(setOf(
            android.app.Notification.CATEGORY_CALL, android.app.Notification.CATEGORY_ALARM, android.app.Notification.CATEGORY_STOPWATCH,
            android.app.Notification.CATEGORY_TRANSPORT, android.app.Notification.CATEGORY_SERVICE, android.app.Notification.CATEGORY_PROGRESS,
            android.app.Notification.CATEGORY_NAVIGATION,
        ), NoticeThrow.FIXED_CATEGORIES)
    }

    // -- the debug build's made-up pile ----------------------------------------------------------------------------

    @Test fun `the demo pile takes both strokes, and all it says is of its own made-up keys`() {
        val demo = (0..2).map { item(it, key = "demo:$it") }
        val s = open(demo)
        val effects = s.stroke(700f, 450f, 1000) + s.stroke(400f, 650f, 2000) + s.stroke(700f, 450f, 3000)
        assertEquals(listOf(NoticeShade.Effect.Dismissed("demo:0"), NoticeShade.Effect.Snoozed("demo:1"), NoticeShade.Effect.Dismissed("demo:2")), effects.filter { it !is NoticeShade.Effect.ShutAt })
        for (e in effects) when (e) {
            is NoticeShade.Effect.Dismissed -> assertTrue(NoticeThrow.isDemo(e.key))
            is NoticeShade.Effect.Snoozed -> assertTrue(NoticeThrow.isDemo(e.key))
            is NoticeShade.Effect.ShutAt -> Unit
            else -> error("$e")
        }
        assertFalse(NoticeThrow.isDemo("0|com.example.chat|7|null|10191"))
    }

    // -- the spring ----------------------------------------------------------------------------------------------------

    @Test fun `the spring starts in place, ends in place, passes it once a little and never leaps`() {
        assertEquals(0f, NoticeThrow.spring(0f), 0f)
        assertEquals(1f, NoticeThrow.spring(1f), 0f)
        val v = (0..100).map { NoticeThrow.spring(it / 100f) }
        assertTrue("over: ${v.maxOrNull()}", v.maxOrNull()!! in 1.01f..1.2f)
        assertTrue(v.zipWithNext().all { (a, b) -> abs(a - b) < 0.08f })
        assertEquals(NoticeThrow.GIVE_MOST, NoticeThrow.give(1000f), 0f)
        assertEquals(-NoticeThrow.GIVE_MOST, NoticeThrow.give(-1000f), 0f)
        assertEquals(10f * NoticeThrow.GIVE, NoticeThrow.give(10f), 0.0001f)
        // Within the back's width.
        assertTrue(NoticeThrow.GIVE_MOST < Panel.WIDTH / 4f)
    }

    // -- only a throw MEANT for it (the review of c6b10c9) ----------------------------------------------------------

    @Test fun `a finger is aimed at a pop-up only once it has been there a moment - not one already on its way as it came`() {
        // The skeptic's timeline: the finger lands at 1000, the pop-up comes at 1100.
        assertFalse(NoticeThrow.aimed(popAt = 1100, downAt = 1000))
        // It came, and the finger landed in its slide in, or a reaction's time after it.
        assertFalse(NoticeThrow.aimed(popAt = 1000, downAt = 1000))
        assertFalse(NoticeThrow.aimed(popAt = 1000, downAt = 1000 + NoticeThrow.AIM_MS - 1))
        assertTrue(NoticeThrow.aimed(popAt = 1000, downAt = 1000 + NoticeThrow.AIM_MS))
        assertTrue(NoticeThrow.aimed(popAt = 1000, downAt = 5000))
        // No pop-up ever: nothing to aim at.
        assertFalse(NoticeThrow.aimed(popAt = 0, downAt = 5000))
        // Longer than the pop-up's own slide in (ShellNotices' IN_MS, 380 ms).
        assertTrue(NoticeThrow.AIM_MS > 380L)
    }

    @Test fun `a throw is 150 units within a swipe's time - not a fling's 40, not a resting thumb that drifts`() {
        assertTrue(NoticeThrow.meant(-150f, 0, 120))
        assertTrue(NoticeThrow.meant(150f, 0, SceneTouch.SWIPE_MS))
        // The regression's right_fling (80 units in 60 ms) and PageDrag's least fling (40 units) turn a page, never throw.
        assertTrue(PageDrag.turns(80f, 80f / 60f))
        assertFalse(NoticeThrow.meant(80f, 0, 60))
        assertFalse(NoticeThrow.meant(-40f, 0, 30))
        // A tap that rolls (SceneTouch.TAP_SLOP, 30 units).
        assertFalse(NoticeThrow.meant(SceneTouch.TAP_SLOP, 0, 100))
        // Far, but slowly: a hand that rested and drifted.
        assertFalse(NoticeThrow.meant(400f, 0, SceneTouch.SWIPE_MS + 1))
        // The pile measures the same way.
        assertEquals(NoticeShade.DISMISS_DX, 150f, 0f)
    }

    @Test fun `on the pile a fling of 40 units, or a slow drift, springs back and says nothing`() {
        val s = open(listOf(item(1), item(2)))
        assertEquals(emptyList<NoticeShade.Effect>(), s.stroke(700f, 640f, 1000, ms = 40))
        assertEquals(emptyList<NoticeShade.Effect>(), s.stroke(700f, 300f, 3000, ms = SceneTouch.SWIPE_MS + 200))
        assertEquals(listOf(NoticeShade.Effect.Dismissed("k1")), s.stroke(700f, 450f, 6000).filter { it !is NoticeShade.Effect.ShutAt })
    }

    @Test fun `the lift of a finger that held the pop-up - nothing when it went or another took its place, back when short`() {
        // Thrown: the same pop-up, still up, a stroke meant as one.
        assertEquals(NoticeThrow.Lift.THROW, NoticeThrow.popLift(followed = 3, now = 3, up = true, travel = 300f, downAt = 0, at = 200))
        // Short or slow: back to its place.
        assertEquals(NoticeThrow.Lift.BACK, NoticeThrow.popLift(3, 3, true, 80f, 0, 60))
        assertEquals(NoticeThrow.Lift.BACK, NoticeThrow.popLift(3, 3, true, 300f, 0, SceneTouch.SWIPE_MS + 1))
        // ANOTHER CAME IN ITS PLACE under the finger: the new one is not thrown, whatever the stroke.
        assertEquals(NoticeThrow.Lift.NOTHING, NoticeThrow.popLift(3, 4, true, 300f, 0, 200))
        // ITS TIME ENDED under the finger (the navigation has none up): the lift is no stroke at all (it would have been
        // usage to the left, the Camera to the right).
        assertEquals(NoticeThrow.Lift.NOTHING, NoticeThrow.popLift(3, 3, false, 300f, 0, 200))
    }

    @Test fun `a made-up finger never takes a notification of his away, and a demo never reaches the phone`() {
        // His own finger, his notification: told to the phone.
        assertTrue(NoticeThrow.toPhone("0|com.example.chat|7|null|10191", madeUpFinger = false))
        // A test's stroke on a real pop-up of his that came in the middle of a run: left alone.
        assertFalse(NoticeThrow.toPhone("0|com.example.chat|7|null|10191", madeUpFinger = true))
        assertFalse(NoticeThrow.toPhone("demo:12345", madeUpFinger = false))
        assertFalse(NoticeThrow.toPhone("demo:12345", madeUpFinger = true))
        // Which stroke is made up: a made-up line at or after its landing.
        assertTrue(NoticeThrow.madeUp(madeUpLineAt = 1000, downAt = 1000))
        assertTrue(NoticeThrow.madeUp(madeUpLineAt = 1200, downAt = 1000))
        assertFalse(NoticeThrow.madeUp(madeUpLineAt = 900, downAt = 1000))
        assertFalse(NoticeThrow.madeUp(madeUpLineAt = 0, downAt = 0))
    }
}
