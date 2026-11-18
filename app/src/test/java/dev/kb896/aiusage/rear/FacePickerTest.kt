package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The face picker's arithmetic, one to one with Xiaomi's (SPEC 2), and its finger (PickerTouch). */
class FacePickerTest {
    private val dt = 1.0 / 120.0

    private fun open(count: Int = 5, live: Int = 2): FacePicker = FacePicker(count, live).also { p -> repeat(240) { p.step(dt) } }
    private fun FacePicker.settle(seconds: Double = 2.0) { var t = 0.0; while (t < seconds) { step(dt); t += dt } }
    private fun FacePicker.card(i: Int) = frame().cards.first { it.index == i }
    private fun near(expected: Double, actual: Double, tol: Double = 0.1, what: String = "") = assertTrue("$what: $expected vs $actual", abs(expected - actual) <= tol)

    @Test fun `at rest - Xiaomi's places`() {
        val p = open()
        assertEquals(FacePicker.Phase.UP, p.phase)
        val m = p.card(2)
        near(604.0, m.cx, what = "middle x"); near(298.0, m.cy, what = "middle y")
        near(580.7, m.width, 0.05, "width"); near(354.6, m.height, 0.05, "height")
        near(313.6, m.left, 0.05, "left"); near(894.4, m.right, 0.05, "right")
        near(1224.0, p.card(3).cx, what = "next"); near(-16.0, p.card(1).cx, what = "previous")
        near(933.6, p.card(3).left, 0.05, "the sliver of the next card"); assertTrue(p.card(3).left < 976.0)
        near(274.4, p.card(1).right, 0.05, "the previous one ends under the lenses")
        near(61.3, m.corner, 0.05, "corner")
        near(613.4, FacePicker.RING_W * m.scale, 0.05, "ring width"); near(387.3, FacePicker.RING_H * m.scale, 0.05, "ring height")
        near(1.0, m.alpha, 1e-6); near(0.2, p.card(3).alpha, 1e-6)
        near(0.0, m.strokeAlpha, 1e-6); near(1.0, p.card(3).strokeAlpha, 1e-6)
        near(1.0, m.ringAlpha, 1e-6); near(0.0, p.card(3).ringAlpha, 1e-6)
        near(1.0, m.ringScale, 1e-6); near(0.97, p.card(3).ringScale, 1e-6)
        val d = p.frame().dots
        near(636.0, d.cx, 1e-9); near(37.0, d.pitch, 1e-9); near(553.0, d.cy, 1e-6); assertEquals(2, d.lit)
        // More than 18 faces: the row of dots stays within 652 px.
        val many = FacePicker(30, 0).frame().dots
        assertTrue((many.count - 1) * many.pitch + 2 * FacePicker.DOT_RADIUS <= 652.0 + 1e-9)
    }

    @Test fun `the springs are Folme's default and the exit's two`() {
        val s = FolmeSpring(0.95, 0.35, 0.001)
        near(322.3, s.k, 0.05, "k"); near(34.11, s.c, 0.01, "c")
        near(194.9, FolmeSpring(1.0, 0.45, 0.001).k, 0.06); near(27.93, FolmeSpring(1.0, 0.45, 0.001).c, 0.01)
        near(631.7, FolmeSpring(1.0, 0.25, 0.001).k, 0.05); near(50.27, FolmeSpring(1.0, 0.25, 0.001).c, 0.01)
    }

    @Test fun `the enter reaches Xiaomi's values within a second with no overshoot, and the exit is its mirror`() {
        val p = FacePicker(3, 1)
        var maxShift = 0.0; var minScale = 1.0
        var t = 0.0
        while (t < 1.0) {
            p.step(dt); t += dt
            val m = p.card(1)
            maxShift = maxOf(maxShift, m.cx - 488.0); minScale = minOf(minScale, m.scale)
        }
        assertEquals(FacePicker.Phase.UP, p.phase)
        val m = p.card(1)
        near(0.595, m.scale, 1e-4); near(116.0, m.cx - 488.0, 0.01); near(620.0, p.card(2).cx - m.cx, 0.01)
        assertTrue("no overshoot past half a pixel: $maxShift", maxShift <= 116.0 + 0.5)
        assertTrue("scale: $minScale", (0.595 - minScale) * 976 <= 0.5)
        // The exit, without a change: back to the whole face.
        p.shut(pick = false)
        var minShift = 999.0; t = 0.0
        while (t < 1.0) { p.step(dt); t += dt; minShift = minOf(minShift, p.card(1).cx - 488.0) }
        assertEquals(FacePicker.Phase.GONE, p.phase)
        assertTrue("no undershoot: $minShift", minShift >= -0.5)
        near(1.0, p.card(1).scale, 1e-6); near(0.0, p.card(1).corner, 1e-9)
        assertNull(p.chosen)
    }

    private fun released(dx: Double, v: Double, count: Int = 5, live: Int = 2): FacePicker {
        val p = open(count, live)
        p.grab(); p.drag(dx); p.release(v)
        return p
    }

    @Test fun `a release moves on past half a card or a fling over 500 px a second the way it was dragged`() {
        assertEquals(2, released(-309.0, 0.0).index)
        assertEquals(3, released(-311.0, 0.0).index)
        assertEquals(1, released(311.0, 0.0).index)
        assertEquals(2, released(-40.0, -499.0).index)
        assertEquals(3, released(-40.0, -501.0).index)
        assertEquals(1, released(40.0, 501.0).index)
        // Against the drag: no.
        assertEquals(2, released(-40.0, 2000.0).index)
        // One card per release, however fast.
        val p = released(-600.0, -9000.0)
        assertEquals(3, p.index); p.settle(); assertEquals(3, p.index); near(0.0, p.offset, 0.05)
    }

    @Test fun `the finger's speed goes into the spring - a fling arrives sooner, with no jump at the lift`() {
        fun until(v: Double): Double {
            val p = open(); p.grab(); p.drag(-320.0)
            val before = p.card(3).cx
            p.release(v)
            near(before, p.card(3).cx, 1e-6, "no jump at the lift")
            var t = 0.0
            while (abs(p.card(3).cx - 604.0) > 2.0 && t < 3.0) { p.step(dt); t += dt }
            return t
        }
        assertTrue(until(-2000.0) < until(0.0))
    }

    @Test fun `the ends give a rubber band and spring back, there is no going round`() {
        val p = open(5, 0)
        p.grab(); p.drag(300.0)
        val u = 300.0 / 620.0
        near(620 * (u - u * u + u * u * u / 3), p.offset, 1e-6, "band")
        p.drag(5000.0); assertTrue(p.offset <= 206.7 + 1e-6)
        p.release(3000.0); assertEquals(0, p.index); p.settle(); near(0.0, p.offset, 0.05)
        val last = open(5, 4); last.grab(); last.drag(-700.0); last.release(-3000.0); assertEquals(4, last.index)
        // One face: no neighbours, the band both ways.
        val one = open(1, 0)
        assertEquals(listOf(0), one.frame().cards.map { it.index })
        one.grab(); one.drag(-200.0); assertTrue(one.offset > -206.7 && one.offset < -100.0); one.release(-4000.0); assertEquals(0, one.index)
        near(206.7, FacePicker.BAND_MAX, 0.05)
        for (r in listOf(10.0, 100.0, 300.0, 619.0)) near(r, FacePicker.unband(FacePicker.band(r)), 1e-6)
    }

    @Test fun `a finger during a settle takes the row where it is`() {
        val p = open()
        p.grab(); p.drag(-400.0); p.release(0.0)
        repeat(6) { p.step(dt) }
        val at = p.card(3).cx
        p.grab()
        near(at, p.card(3).cx, 0.1)
        p.step(dt); p.step(dt)
        near(at, p.card(3).cx, 0.1, "held still")
        p.drag(0.0); near(at, p.card(3).cx, 0.1, "the drag goes on from there")
    }

    @Test fun `alpha, inner stroke and ring follow the selection, trailing it`() {
        val p = open()
        p.grab(); p.drag(-310.0)
        p.settle(1.0)
        // Half way: both cards half selected.
        near(0.2 + 0.8 * 0.5, p.card(2).alpha, 0.01); near(0.2 + 0.8 * 0.5, p.card(3).alpha, 0.01)
        near(0.5, p.card(3).strokeAlpha, 0.01)
        // The ring stays on the card the row rests on, at its p.
        near(0.5, p.card(2).ringAlpha, 0.01); near(0.0, p.card(3).ringAlpha, 0.01)
        near(0.97, p.card(3).ringScale, 0.01)
        // Trailing: right after a quick move the alpha is not there yet.
        val q = open(); q.grab(); q.drag(-310.0); q.step(dt)
        assertTrue(q.card(3).alpha < 0.2 + 0.8 * 0.5 - 0.01)
    }

    @Test fun `the dot swaps as Xiaomi's holders do - past half a card one way, three tenths the other`() {
        assertEquals(2, FacePicker.dotFor(2, 2.49)); assertEquals(3, FacePicker.dotFor(2, 2.51))
        assertEquals(2, FacePicker.dotFor(2, 1.71)); assertEquals(1, FacePicker.dotFor(2, 1.69))
        val p = open()
        p.grab(); p.drag(-300.0); p.step(dt); assertEquals(2, p.dot)
        p.drag(-320.0); p.step(dt); assertEquals(3, p.dot)
        val q = open(); q.grab(); q.drag(180.0); q.step(dt); assertEquals(2, q.dot)
        q.drag(190.0); q.step(dt); assertEquals(1, q.dot)
    }

    @Test fun `a whole enter, drag, settle and exit at 120 Hz has no jumps`() {
        val p = FacePicker(5, 2)
        val track = ArrayList<DoubleArray>()
        fun look() { val f = p.frame(); track += doubleArrayOf(p.card(2).cx, p.card(2).scale * 976, p.card(3).cx, f.dots.cy) }
        repeat(80) { p.step(dt); look() }
        p.grab()
        for (i in 1..30) { p.drag(-12.0 * i); p.step(dt); look() }
        p.release(-900.0)
        repeat(120) { p.step(dt); look() }
        val exitAt = track.size
        p.shut(pick = true)
        repeat(160) { p.step(dt); look() }
        assertEquals(FacePicker.Phase.GONE, p.phase)
        // Where a spring leaves rest (the enter, the exit after its 150 ms wait) it gathers speed as a spring does, its
        // steps growing as 1, 3, 5…: those frames are held to the spring's own top speed instead. The finger's own frames
        // (12 px a frame from a standing start) are the finger's.
        val exitMoves = (exitAt until track.size).first { abs(track[it][0] - track[it - 1][0]) > 1e-9 }
        val springStarts = listOf(0, exitMoves)
        for (k in 0 until 4) for (i in 2 until track.size) {
            val step = abs(track[i][k] - track[i - 1][k]); val prev = abs(track[i - 1][k] - track[i - 2][k])
            val gathering = springStarts.any { i in it..it + 4 }
            if (gathering) { assertTrue("value $k frame $i: $step", step <= 40.0); continue }
            assertTrue("value $k frame $i: $step after $prev", step <= 1.5 * prev + 2.0 + 12.0 * (if (i in 80..111) 1 else 0))
        }
        assertEquals(3, p.chosen)
    }

    @Test fun `the exit with a new face cross-fades, after Xiaomi's 150 ms wait to build it`() {
        val p = open(); p.grab(); p.drag(-400.0); p.release(0.0); p.settle()
        p.shut(pick = true)
        assertTrue(p.changesFace)
        near(0.0, p.frame().newFaceAlpha, 1e-9); near(1.0, p.frame().pictureAlpha, 1e-9)
        val open0 = p.openProgress
        repeat(17) { p.step(dt) }   // 141 ms: still waiting
        near(open0, p.openProgress, 1e-9, "waits")
        assertFalse(p.takesTouch)
        p.settle(1.5)
        assertEquals(FacePicker.Phase.GONE, p.phase)
        near(1.0, p.frame().newFaceAlpha, 1e-3); near(0.0, p.frame().pictureAlpha, 1e-3)
        // The face that was the face again: no cross-fade, no wait.
        val q = open(); q.shut(pick = true); assertFalse(q.changesFace)
        // Left without choosing, after swiping away: the old face grows back.
        val r = open(); r.grab(); r.drag(-400.0); r.release(0.0); r.settle(); assertEquals(3, r.index)
        r.shut(pick = false); assertNull(r.chosen); assertEquals(2, r.index); r.settle(); near(604.0 - 116.0, r.card(2).cx, 0.1)
    }

    // -- the finger (PickerTouch) --------------------------------------------------------------------------------------

    private val ux = PickerTouch.UNITS_PER_PX_X
    private fun down(x: Float, y: Float, t: Long) = RearTouches.Down(x, y, t)
    private fun move(x: Float, y: Float, t: Long) = RearTouches.Move(x, y, t)
    private fun up(x: Float, y: Float, t: Long) = RearTouches.Up(x, y, t)

    @Test fun `a tap chooses, the hold's own finger lifted in place does not, and the tap is not a double tap's first`() {
        val t = PickerTouch()
        assertEquals(listOf(PickerTouch.Out.Grab), t.feed(down(500f, 300f, 0)))
        assertEquals(listOf(PickerTouch.Out.Release(0.0), PickerTouch.Out.Choose), t.feed(up(503f, 302f, 120)))
        val held = PickerTouch()
        held.holdFinger(500f, 300f, 0)
        assertEquals(listOf<PickerTouch.Out>(PickerTouch.Out.Release(0.0)), held.feed(up(500f, 300f, 400)))
        // Long is not a tap.
        val long = PickerTouch(); long.feed(down(500f, 300f, 0))
        assertFalse(PickerTouch.Out.Choose in long.feed(up(500f, 300f, 600)))
        // The tap that chooses is not the first of a double tap: SceneTouch in the picker's mode keeps no tap of it.
        val scene = SceneTouch()
        scene.feed(down(500f, 300f, 0)) { SceneTouch.Mode.PICKER }; scene.feed(up(500f, 300f, 80)) { SceneTouch.Mode.PICKER }
        assertEquals(listOf(SceneTouch.Out.FirstDown), scene.feed(down(500f, 300f, 200)) { SceneTouch.Mode.SCENE })
    }

    @Test fun `sideways past 22 px drags from there, and the lift hands on the speed`() {
        val t = PickerTouch(); t.feed(down(600f, 300f, 0))
        assertEquals(emptyList<PickerTouch.Out>(), t.feed(move(600f - 21f * ux, 300f, 10)))
        assertEquals(listOf<PickerTouch.Out>(PickerTouch.Out.Drag(0.0)), t.feed(move(600f - 23f * ux, 300f, 20)))
        val d = t.feed(move(600f - 123f * ux, 300f, 40)).single() as PickerTouch.Out.Drag
        near(-100.0, d.dxPx, 0.01)
        t.feed(move(600f - 223f * ux, 302f, 60))
        val r = t.feed(up(600f - 263f * ux, 302f, 70)).single() as PickerTouch.Out.Release
        assertTrue("left fast: ${r.vxPxPerS}", r.vxPxPerS < -2000)
    }

    @Test fun `a stroke up or down leaves without choosing, a drift only takes the drag away`() {
        val t = PickerTouch(); t.feed(down(500f, 450f, 0))
        val out = (1..6).flatMap { t.feed(move(500f, 450f - 30f * it, 30L * it)) }
        assertEquals(listOf<PickerTouch.Out>(PickerTouch.Out.Leave), out)
        assertEquals(emptyList<PickerTouch.Out>(), t.feed(up(500f, 270f, 200)))
        // Down too, from the top edge.
        val top = PickerTouch(); top.feed(down(700f, 2f, 0))
        assertTrue(PickerTouch.Out.Leave in (1..6).flatMap { top.feed(move(700f, 2f + 30f * it, 30L * it)) })
        // A drift: a drag, then the finger wanders down slowly.
        val drift = PickerTouch(); drift.feed(down(600f, 200f, 0))
        drift.feed(move(560f, 200f, 50))
        val o = drift.feed(move(560f, 250f, 900))
        assertEquals(listOf<PickerTouch.Out>(PickerTouch.Out.Cancel), o)
        assertFalse(PickerTouch.Out.Choose in drift.feed(up(560f, 250f, 1000)))
        // And the model springs the row back.
        val p = open(); p.grab(); p.drag(-200.0); p.cancelDrag(); p.settle(); near(0.0, p.offset, 0.05); assertEquals(2, p.index)
    }

    @Test fun `left to right is the previous card here, never the Camera`() {
        val t = PickerTouch(); t.feed(down(300f, 300f, 0))
        val out = (1..8).flatMap { t.feed(move(300f + 50f * it, 300f, 20L * it)) }
        assertTrue(out.all { it is PickerTouch.Out.Drag })
        val r = t.feed(up(700f, 300f, 170)).single() as PickerTouch.Out.Release
        val p = open(); p.grab(); p.drag((out.last() as PickerTouch.Out.Drag).dxPx); p.release(r.vxPxPerS)
        assertEquals(1, p.index)
    }
}
