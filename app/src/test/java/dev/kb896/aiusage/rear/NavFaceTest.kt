package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** The navigation page (NavFace) drawn by its own painter at its moments, and its pure parts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NavFaceTest {
    private fun render(m: NavFace.Model, fraction: Float? = m.fraction) =
        Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888).apply { NavFace.draw(Canvas(this), 976f, 596f, m, fraction) }

    private fun art(color: Int, w: Int = 16, h: Int = 16) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** The page with nothing on it: the ground alone. */
    private val bare by lazy { render(NavFace.Model("bare", "app")) }

    private fun near(a: Int, b: Int, tolerance: Int = 6) =
        abs(Color.red(a) - Color.red(b)) <= tolerance && abs(Color.green(a) - Color.green(b)) <= tolerance && abs(Color.blue(a) - Color.blue(b)) <= tolerance

    private fun x(at: Float) = (NavFace.LEFT + (NavFace.RIGHT - NavFace.LEFT) * at).toInt()
    private val barY = NavFace.BAR_Y.toInt()

    @Test fun `the whole page is opaque, nothing lies under the lenses, and the words' block is centred on 636`() {
        val page = render(NavFace.demo(art(Color.RED), maneuver = art(Color.WHITE), tracker = art(Color.BLUE)))
        for (y in 0 until 596 step 4) for (x in 0 until 976 step 4) assertEquals(255, Color.alpha(page.getPixel(x, y)))
        for (y in 0 until 596 step 3) for (x in 0 until 296 step 3) assertEquals("($x, $y)", bare.getPixel(x, y), page.getPixel(x, y))
        assertEquals(636f, (NavFace.LEFT + NavFace.RIGHT) / 2f, 0f)
        // The ground is the reference's: dark at the top, green at the foot.
        val top = bare.getPixel(636, 4); val foot = bare.getPixel(636, 590)
        assertTrue(Color.green(foot) > Color.green(top) + 20)
        assertTrue(Color.green(foot) > Color.red(foot) && Color.green(foot) > Color.blue(foot))
        // The app's icon at the top left of what is seen.
        assertEquals(Color.RED, page.getPixel((NavFace.LEFT + NavFace.ICON / 2f).toInt(), (NavFace.ICON_TOP + NavFace.ICON / 2f).toInt()))
    }

    @Test fun `the words - the chip's distance, a dot and the title, then the text, the distance left out where it is said already`() {
        val m = NavFace.Model("k", "app", short = "2 km", title = "Turn right", text = "toward Example Way")
        assertEquals("2 km${NavFace.DOT}Turn right\ntoward Example Way", NavFace.words(m))
        assertEquals("Turn right\ntoward Example Way", NavFace.words(m.copy(short = "")))
        assertEquals("2 km${NavFace.DOT}Turn right", NavFace.words(m.copy(text = "")))
        assertEquals("2 km - Turn right\ntoward Example Way", NavFace.words(m.copy(title = "2 km - Turn right")))
        assertEquals("toward Example Way", NavFace.words(m.copy(short = "", title = "")))
        assertEquals("2 km${NavFace.DOT}Turn right\nContinue for 12 km", NavFace.words(m.copy(text = "Continue for 12 km")))
        assertEquals("", NavFace.words(NavFace.Model("k", "app")))
    }

    @Test fun `a distance alone runs on into the instruction after a dot, as the reference's line does, and words alone end their line`() {
        // Maps' title the distance, its text the instruction: one flowing line, "2 km • Turn right toward Example Way".
        val maps = NavFace.Model("k", "app", title = "2 km", text = "Turn right toward Example Way")
        assertEquals("2 km${NavFace.DOT}Turn right toward Example Way", NavFace.words(maps))
        assertEquals("350 ft${NavFace.DOT}Turn left", NavFace.words(maps.copy(title = "350 ft", text = "Turn left")))
        assertEquals("2 km${NavFace.DOT}toward Example Way", NavFace.words(NavFace.Model("k", "app", short = "2 km", text = "toward Example Way")))
        // A short head with no figure is not a distance: it ends its line.
        assertEquals("Continue\nonto Main St", NavFace.words(maps.copy(title = "Continue", text = "onto Main St")))
        // Laid out at the page's width, the reference's two lines.
        val page = render(maps.copy(title = "2 km"))
        var second = 0
        for (y in 280..330) for (x in NavFace.LEFT.toInt()..(NavFace.LEFT + 300f).toInt()) if (Color.red(page.getPixel(x, y)) > 200) second++
        assertTrue("a second line under the first", second > 300)
    }

    @Test fun `the pill - the time of arrival whole, else its last part, else nothing`() {
        val wide: (String) -> Float = { it.length * 15f }
        assertEquals("08:16", NavFace.pill("08:16", wide))
        assertEquals("08:31", NavFace.pill("15 min · 4.2 km · 08:31", wide))
        assertEquals("08:31 ETA", NavFace.pill("25 min • 18 km • 08:31 ETA", wide))
        assertNull(NavFace.pill("", wide))
        assertNull(NavFace.pill("arrival at 08:16 tomorrow morning in the old town", wide))
        assertNull(NavFace.pill("1 h 25 min · 112 km · arrival at 08:16 tomorrow morning", wide))
    }

    @Test fun `the route behind the tracker is one muted stretch, the part ahead in the app's own colours, cut at the tracker`() {
        val m = NavFace.demo()
        val (blue, yellow) = m.segments[0].color to m.segments[1].color
        assertEquals(
            listOf(NavFace.Piece(0f, .55f, NavFace.TRAVELLED), NavFace.Piece(.55f, .75f, yellow), NavFace.Piece(.75f, 1f, blue)),
            NavFace.pieces(m, .55f),
        )
        // Not styled by progress (the app's own word): every segment in its colour, the tracker over them.
        assertEquals(listOf(NavFace.Piece(0f, .45f, blue), NavFace.Piece(.45f, .75f, yellow), NavFace.Piece(.75f, 1f, blue)), NavFace.pieces(m.copy(styledByProgress = false), .55f))
        // No progress given: the segments as they are.
        assertEquals(3, NavFace.pieces(m, null).size)
        // At the start nothing is behind; at the end everything is.
        assertEquals(NavFace.Piece(0f, .45f, blue), NavFace.pieces(m, 0f).first())
        assertEquals(listOf(NavFace.Piece(0f, 1f, NavFace.TRAVELLED)), NavFace.pieces(m, 1f))
        // A plain progress bar (no segments): behind muted, ahead in the default ink.
        assertEquals(listOf(NavFace.Piece(0f, .3f, NavFace.TRAVELLED), NavFace.Piece(.3f, 1f, 0)), NavFace.pieces(NavFace.Model("k", "app", progressMax = 10, fraction = .3f), .3f))
        // Segments without a max: their sum is the max.
        assertEquals(.5f, NavFace.pieces(NavFace.Model("k", "app", segments = listOf(NavFace.Segment(2, Color.RED), NavFace.Segment(2, Color.BLUE))), null)[0].to, 0f)
        // Nothing to draw: no segments and no progress, or no max at all.
        assertTrue(NavFace.pieces(NavFace.Model("k", "app", progressMax = 100), null).isEmpty())
        assertTrue(NavFace.pieces(NavFace.Model("k", "app", fraction = .5f), .5f).isEmpty())
    }

    @Test fun `drawn - the part behind is muted, the part ahead in its colours with a gap between, the app's tracker on it`() {
        val m = NavFace.demo(tracker = art(Color.MAGENTA))
        val page = render(m, .55f)
        val behind = page.getPixel(x(.3f), barY)
        assertFalse("behind is not the segment's blue", near(behind, m.segments[0].color, 40))
        assertTrue("behind is lighter than the ground", Color.green(behind) > Color.green(bare.getPixel(x(.3f), barY)) + 15)
        assertTrue(near(page.getPixel(x(.65f), barY), m.segments[1].color, 2))
        assertTrue(near(page.getPixel(x(.9f), barY), m.segments[2].color, 2))
        // The gap between the yellow and the blue.
        assertEquals(bare.getPixel(x(.75f), barY), page.getPixel(x(.75f), barY))
        assertEquals(Color.MAGENTA, page.getPixel(x(.55f), barY))
        // Not styled: the blue behind the tracker is blue.
        assertTrue(near(render(m.copy(styledByProgress = false), .55f).getPixel(x(.3f), barY), m.segments[0].color, 2))
        // The tracker goes where it is told, and its stretch behind with it.
        val later = render(m, .8f)
        assertEquals(Color.MAGENTA, later.getPixel(x(.8f), barY))
        assertFalse(near(later.getPixel(x(.65f), barY), m.segments[1].color, 40))
    }

    @Test fun `a segment or point at the default colour is drawn in the default ink, never transparent`() {
        val m = NavFace.Model("k", "app", progressMax = 100, segments = listOf(NavFace.Segment(100, 0)), points = listOf(NavFace.Point(50, 0)))
        val page = render(m, null)
        assertEquals(NavFace.DEFAULT_INK, page.getPixel(x(.25f), barY))
        assertEquals(NavFace.DEFAULT_INK, page.getPixel(x(.5f), barY))
    }

    @Test fun `the same prepared page draws the tracker at intermediate and final reported positions`() {
        val model = NavFace.demo(tracker = art(Color.MAGENTA), fraction = .4f)
        val next = model.copy(fraction = .6f)
        val motion = NavFace.TrackerMotion()
        motion.update(model, 0L)
        motion.update(next, 1000L)
        val middle = render(next, motion.position(next, 1000L + NavFace.MOTION_MS / 2))
        val end = render(next, motion.position(next, 1000L + NavFace.MOTION_MS))
        val y = NavFace.BAR_Y.toInt()
        assertEquals(Color.MAGENTA, middle.getPixel(x(.5f), y))
        assertEquals(Color.MAGENTA, end.getPixel(x(.6f), y))
        assertNotEquals(Color.MAGENTA, end.getPixel(x(.5f), y))
    }

    @Test fun `short supplied traffic segments remain visible between gaps`() {
        val model = NavFace.Model("route", "maps", progressMax = 1000, segments = listOf(
            NavFace.Segment(495, Color.BLUE), NavFace.Segment(10, Color.YELLOW), NavFace.Segment(495, Color.BLUE),
        ))
        assertTrue(near(Color.YELLOW, render(model).getPixel(x(0.5f), NavFace.BAR_Y.toInt())))
    }

    @Test fun `nothing made up - no bar, arrow or tracker the app did not give`() {
        val m = NavFace.Model("k", "app", short = "500 m", title = "Turn left")
        val page = render(m)
        for (y in 380 until 596 step 3) for (x in 296 until 976 step 3) assertEquals("($x, $y)", bare.getPixel(x, y), page.getPixel(x, y))
        // Segments but no max and no length: nothing; progress but no tracker icon: the bar, no tracker.
        val bar = render(m.copy(progressMax = 100, fraction = .5f), .5f)
        assertNotEquals(bare.getPixel(x(.25f), barY), bar.getPixel(x(.25f), barY))
        assertTrue(near(bar.getPixel(x(.75f), barY), NavFace.DEFAULT_INK, 2))
        // No pill without a sub text, no "Demo" on his own route.
        for (y in 70 until 122 step 2) for (x in 700 until 926 step 2) assertEquals(bare.getPixel(x, y), page.getPixel(x, y))
    }

    @Test fun `the maneuver stands over the bar's right end, and dark ink is shown light on the green`() {
        val cx = (NavFace.RIGHT - NavFace.MANEUVER_W / 2f).toInt()
        val cy = (NavFace.BAR_Y - NavFace.MANEUVER_ABOVE - NavFace.MANEUVER_H / 2f).toInt()
        val white = render(NavFace.demo(maneuver = art(Color.WHITE)))
        assertEquals(Color.WHITE, white.getPixel(cx, cy))
        val dark = render(NavFace.demo(maneuver = art(0xFF202124.toInt())))
        assertEquals(Color.WHITE, dark.getPixel(cx, cy))
        assertTrue(NavFace.darkInk(art(Color.BLACK)))
        assertFalse(NavFace.darkInk(art(Color.WHITE)))
        assertFalse(NavFace.darkInk(art(Color.TRANSPARENT)))
        // A coloured arrow keeps its colour.
        val blue = render(NavFace.demo(maneuver = art(0xFF4285F4.toInt())))
        assertEquals(0xFF4285F4.toInt(), blue.getPixel(cx, cy))
    }

    @Test fun `the time of arrival in its pill at the top right, only where it fits whole`() {
        val m = NavFace.Model("k", "app", title = "Turn left")
        val short = render(m.copy(eta = "08:16"))
        var changed = 0
        for (y in 72 until 120 step 2) for (x in 780 until 926 step 2) if (short.getPixel(x, y) != bare.getPixel(x, y)) changed++
        assertTrue(changed > 100)
        val long = render(m.copy(eta = "1 h 25 min · 112 km · arrival at 08:16 tomorrow morning"))
        for (y in 64 until 128 step 2) for (x in 500 until 976 step 2) assertEquals(bare.getPixel(x, y), long.getPixel(x, y))
    }

    @Test fun `long words take three lines at most, smaller first, and stay above the maneuver and the route`() {
        val m = NavFace.Model("k", "app", short = "200 m", title = "After the next intersection take the second exit at the roundabout",
            text = "toward the central station along the riverside road and continue to the destination on the left")
        val page = render(m)
        val words = render(m.copy(short = "", title = "", text = ""))
        for (y in 400 until 596 step 2) for (x in 296 until 976 step 2) assertEquals(words.getPixel(x, y), page.getPixel(x, y))
        var lit = 0
        for (y in 190..398) for (x in NavFace.LEFT.toInt()..NavFace.RIGHT.toInt()) if (Color.red(page.getPixel(x, y)) > 200) lit++
        assertTrue(lit > 2000)
        // Nothing of the words right of the column.
        for (y in 190..398 step 2) for (x in (NavFace.RIGHT + 4).toInt() until 976 step 2) assertEquals(bare.getPixel(x, y), page.getPixel(x, y))
    }

    @Test fun `a demo says so`() {
        val withIcon = render(NavFace.demo(art(Color.RED)))
        val notDemo = render(NavFace.demo(art(Color.RED)).copy(demo = false))
        var differs = 0
        val y0 = (NavFace.ICON_TOP + NavFace.ICON / 2f - 24f).toInt()
        for (y in y0 until y0 + 40) for (x in (NavFace.LEFT + NavFace.ICON + 16f).toInt() until (NavFace.LEFT + NavFace.ICON + 120f).toInt()) if (withIcon.getPixel(x, y) != notDemo.getPixel(x, y)) differs++
        assertTrue(differs > 50)
    }

    @Test fun `the tracker eases only between two reported places and stops there, without running ahead`() {
        val motion = NavFace.TrackerMotion()
        val m = NavFace.demo(fraction = .2f)
        motion.update(m, 1000)
        assertFalse(motion.moving(1000))
        motion.update(m.copy(fraction = .8f), 2000)
        assertEquals(.2f, motion.value(2000)!!, .0001f)
        assertEquals(.5f, motion.value(2000 + NavFace.MOTION_MS / 2)!!, .0001f)
        assertTrue(motion.moving(2000 + NavFace.MOTION_MS / 2))
        assertEquals(.8f, motion.value(2000 + NavFace.MOTION_MS)!!, .0001f)
        assertFalse(motion.moving(2000 + NavFace.MOTION_MS))
        assertEquals(.8f, motion.value(100_000)!!, .0001f)
        // A new place while it moves: from where it is, not from where it was going.
        motion.update(m.copy(fraction = .3f), 3000)
        assertEquals(.55f, motion.value(3000 + NavFace.MOTION_MS / 2)!!, .0001f)
        // Another route: where it is said to be, at once.
        motion.update(m.copy(key = "new", fraction = .1f), 3400)
        assertEquals(.1f, motion.value(3400)!!, .0001f)
        assertFalse(motion.moving(3400))
        motion.update(m.copy(key = "new", fraction = null), 3500)
        assertNull(motion.value(3500))
        assertFalse(motion.moving(3500))
    }

    @Test fun `a step too small to see is drawn once in its place, not eased frame by frame`() {
        val motion = NavFace.TrackerMotion()
        val px = 1f / (NavFace.RIGHT - NavFace.LEFT)
        val m = NavFace.demo(fraction = .5f)
        motion.update(m, 0)
        // A pixel and a half along the bar (a few tens of metres of a long route): there at once, no frames asked for.
        motion.update(m.copy(fraction = .5f + 1.5f * px), 1_000)
        assertFalse(motion.moving(1_000))
        assertEquals(.5f + 1.5f * px, motion.value(1_000)!!, 1e-6f)
        // Three pixels: eased.
        motion.update(m.copy(fraction = .5f + 4.5f * px), 2_000)
        assertTrue(motion.moving(2_000))
        assertEquals(.5f + 1.5f * px, motion.value(2_000)!!, 1e-6f)
        assertEquals(.5f + 4.5f * px, motion.value(2_000 + NavFace.MOTION_MS)!!, 1e-6f)
    }
}
