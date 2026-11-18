package dev.kb896.aiusage.circle

import dev.kb896.aiusage.circle.SelLogic.Box
import dev.kb896.aiusage.circle.SelLogic.Pick
import dev.kb896.aiusage.circle.SelLogic.W
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** One test per selection or zoom behaviour that was wrong, missing or easy to break. */
class SelLogicTest {
    /** Lays out lines of words like a page: every word 20 px per character, 12 px gaps, glyph boxes inside the line box. */
    private fun page(vararg lines: String, x0: Int = 100, y0: Int = 400, lineH: Int = 60, pitch: Int = 90, block: (Int) -> Int = { 0 }, charW: Int = 20): List<W> {
        val out = ArrayList<W>()
        for ((li, line) in lines.withIndex()) {
            var x = x0; val top = y0 + li * pitch
            for (word in line.split(' ')) {
                val w = word.length * charW
                // short letters make a lower glyph box than the line
                val gt = if (word.all { it in "aceimnorsuvwxz" }) top + lineH / 3 else top
                out.add(W(word, x, gt, x + w, top + lineH - 6, top, top + lineH, li, block(li)))
                x += w + 12
            }
        }
        return out
    }
    private fun idx(ws: List<W>, t: String) = ws.indexOfFirst { it.text == t }
    private fun stroke(vararg p: Float) = p
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 24) = FloatArray(n * 2) { k -> val f = (k / 2) / (n - 1f); if (k % 2 == 0) x0 + (x1 - x0) * f else y0 + (y1 - y0) * f }
    private fun loop(l: Float, t: Float, r: Float, b: Float): FloatArray {
        val p = ArrayList<Float>()
        fun seg(ax: Float, ay: Float, bx: Float, by: Float) { for (k in 0 until 10) { p.add(ax + (bx - ax) * k / 10f); p.add(ay + (by - ay) * k / 10f) } }
        seg(l, t, r, t); seg(r, t, r, b); seg(r, b, l, b); seg(l, b, l, t + 4)
        return p.toFloatArray()
    }
    private fun zigzag(l: Float, t: Float, r: Float, b: Float, passes: Int): FloatArray {
        val p = ArrayList<Float>()
        for (k in 0..passes) { p.add(l + (r - l) * k / passes); p.add(if (k % 2 == 0) t else b) }
        // densify
        val q = ArrayList<Float>()
        for (k in 0 until p.size / 2 - 1) for (s in 0 until 8) { q.add(p[2 * k] + (p[2 * k + 2] - p[2 * k]) * s / 8f); q.add(p[2 * k + 1] + (p[2 * k + 3] - p[2 * k + 1]) * s / 8f) }
        return q.toFloatArray()
    }
    private val finger = 72f

    // ---- taps ----
    @Test fun tapOnShortLetterWordCountsOverTheWholeLineHeight() {
        val ws = page("we are on a new moon")
        val w = ws[idx(ws, "are")]
        // above the low glyph box of "are" but inside its line
        assertEquals(idx(ws, "are"), SelLogic.wordAt(ws, w.cx, w.lt + 4f, 12f))
    }
    @Test fun tapBetweenTwoSmallWordsPicksTheNearerOne() {
        val ws = page("alpha beta gamma", lineH = 18, charW = 7)
        val a = ws[0]; val b = ws[1]
        assertEquals(0, SelLogic.wordAt(ws, a.r + 3f, a.cy, 12f))
        assertEquals(1, SelLogic.wordAt(ws, b.l - 3f, b.cy, 12f))
    }
    @Test fun tapFarFromTextIsNoWord() {
        val ws = page("alpha beta")
        assertEquals(-1, SelLogic.wordAt(ws, 900f, 1500f, 12f))
    }
    @Test fun magnifierReadAsQIsNeverAWord() {
        val ws = page("Q Search")
        assertTrue(SelLogic.iconish(ws, 0))
        assertEquals(-1, SelLogic.wordAt(ws, ws[0].cx, ws[0].cy, 12f))
        assertFalse(SelLogic.iconish(page("press Q now"), 1))
    }

    // ---- strokes ----
    @Test fun dragAlongOneLineSelectsFromFirstToLastWord() {
        val ws = page("the quick brown fox jumps", "over the lazy dog")
        val a = ws[idx(ws, "quick")]; val b = ws[idx(ws, "fox")]
        val p = SelLogic.classify(ws, line(a.cx, a.cy, b.cx, b.cy + 10f), finger) as Pick.Text
        assertEquals(idx(ws, "quick"), p.a); assertEquals(idx(ws, "fox"), p.b)
    }
    @Test fun dragAcrossLinesSelectsEverythingBetweenLikeADesktop() {
        val ws = page("the quick brown fox jumps", "over the lazy dog", "and runs far away")
        val a = ws[idx(ws, "brown")]; val b = ws[idx(ws, "runs")]
        val p = SelLogic.classify(ws, line(a.cx, a.cy, b.cx, b.cy), finger) as Pick.Text
        assertEquals(idx(ws, "brown"), p.a); assertEquals(idx(ws, "runs"), p.b)
    }
    @Test fun dragBackwardsGivesTheSameSelection() {
        val ws = page("the quick brown fox jumps", "over the lazy dog")
        val a = ws[idx(ws, "lazy")]; val b = ws[idx(ws, "quick")]
        val p = SelLogic.classify(ws, line(a.cx, a.cy, b.cx, b.cy), finger) as Pick.Text
        assertEquals(idx(ws, "quick"), p.a); assertEquals(idx(ws, "lazy"), p.b)
    }
    @Test fun underliningJustBelowALineSelectsThatLineNotTheNext() {
        val ws = page("the quick brown fox", "over the lazy dog", pitch = 110)
        val a = ws[0]; val b = ws[3]
        val p = SelLogic.classify(ws, line(a.l.toFloat(), a.lb + 14f, b.r.toFloat(), b.lb + 16f), finger) as Pick.Text
        assertEquals(0, p.a); assertEquals(3, p.b)
    }
    @Test fun slightlySlantedDragDoesNotSwallowTheNeighbouringLines() {
        val ws = page("one two three four five", "six seven eight nine ten", "red green blue pink grey")
        val a = ws[idx(ws, "seven")]; val b = ws[idx(ws, "nine")]
        val p = SelLogic.classify(ws, line(a.cx, a.cy - 20f, b.cx, b.cy + 22f), finger) as Pick.Text
        assertEquals(idx(ws, "seven"), p.a); assertEquals(idx(ws, "nine"), p.b)
    }
    @Test fun loopAroundAParagraphSelectsItAndOffersThePicture() {
        val ws = page("the quick brown fox", "over the lazy dog", "and runs far away")
        val p = SelLogic.classify(ws, loop(60f, 370f, 560f, 660f), finger) as Pick.Text
        assertEquals(0, p.a); assertEquals(ws.size - 1, p.b); assertTrue(p.around != null)
    }
    @Test fun bigLoopWithOnlyACaptionInsideIsAPictureSearch() {
        val ws = page("photo", x0 = 300, y0 = 1500, lineH = 40)
        assertTrue(SelLogic.classify(ws, loop(100f, 500f, 1100f, 1700f), finger) is Pick.Image)
    }
    @Test fun scribbleOverIconAndLabelIsNeverTheLetterQ() {
        val ws = page("Q Search", x0 = 500, y0 = 1170, lineH = 44)
        when (val p = SelLogic.classify(ws, zigzag(250f, 300f, 950f, 1380f, 14), finger)) {
            is Pick.Image -> {}
            is Pick.Text -> { assertEquals(1, p.a); assertEquals(1, p.b) }
            else -> throw AssertionError("nothing picked")
        }
        // a small scribble right on the pill selects the word, not the icon
        val small = SelLogic.classify(ws, zigzag(490f, 1160f, 700f, 1225f, 6), finger) as Pick.Text
        assertEquals(1, small.a); assertEquals(1, small.b)
    }
    @Test fun strokeOverNothingReadableIsAPictureSearch() {
        assertTrue(SelLogic.classify(emptyList(), loop(100f, 100f, 500f, 500f), finger) is Pick.Image)
        assertTrue(SelLogic.classify(page("alpha beta"), loop(100f, 1500f, 500f, 1900f), finger) is Pick.Image)
    }
    @Test fun aSinglePointIsNotAStroke() {
        assertTrue(SelLogic.classify(page("alpha"), stroke(10f, 10f), finger) is Pick.None)
    }

    // ---- handles ----
    @Test fun handleDragFollowsTheLineUnderTheFingerNotTheNearestColumn() {
        val ws = page("aaaa bbbb cccc", "dddd eeee ffff", "gggg hhhh iiii")
        val e = ws[idx(ws, "eeee")]
        // a little outside line 2 vertically, far left of it: still line 2's first word, not line 1 or 3
        assertEquals(idx(ws, "dddd"), SelLogic.nearestWord(ws, 20f, e.cy + 20f))
    }
    @Test fun wordsInsideARegionForSelectText() {
        val ws = page("the quick brown fox", "over the lazy dog")
        assertEquals(0 to ws.size - 1, SelLogic.wordsIn(ws, Box(50f, 350f, 700f, 600f)))
        assertEquals(null, SelLogic.wordsIn(ws, Box(50f, 1000f, 700f, 1200f)))
    }

    // ---- highlighted versus sent ----
    @Test fun copyKeepsTheTextSearchDropsSurroundingPunctuation() {
        val ws = page("klarer Himmel, sagte er.")
        val i = idx(ws, "Himmel,")
        assertEquals("Himmel,", SelLogic.text(ws, i, i))
        assertEquals("Himmel", SelLogic.query(ws, i, i))
        assertEquals("Himmel, sagte er", SelLogic.query(ws, i, ws.size - 1))
    }
    @Test fun wordBrokenAtALineEndIsJoinedForTheSearch() {
        val ws = page("alle Fahr-", "gäste bitte")
        assertEquals("alle Fahrgäste bitte", SelLogic.query(ws, 0, 3))
        assertEquals("alle Fahr- gäste bitte", SelLogic.text(ws, 0, 3))
    }
    @Test fun aRealHyphenBeforeACapitalStays() {
        val ws = page("Baden-", "Württemberg")
        assertEquals("Baden- Württemberg", SelLogic.query(ws, 0, 1))
    }
    @Test fun chineseIsJoinedWithoutSpacesAndBlocksWithNewlines() {
        val ws = listOf(W("你", 0, 0, 40, 40, 0, 40, 0, 0), W("好", 44, 0, 84, 40, 0, 40, 0, 0), W("World", 0, 80, 120, 120, 80, 120, 1, 1))
        assertEquals("你好\nWorld", SelLogic.text(ws, 0, 2))
        assertEquals("你好 World", SelLogic.query(ws, 0, 2))
    }
    @Test fun symbolsOnlySelectionIsNotTurnedIntoAnEmptyQuery() {
        val ws = page("-> <-")
        assertEquals("-> <-", SelLogic.query(ws, 0, 1))
    }
    @Test fun badRangesGiveEmptyText() {
        val ws = page("a b")
        assertEquals("", SelLogic.text(ws, -1, 0)); assertEquals("", SelLogic.query(ws, 1, 5))
    }

    // ---- reading order ----
    @Test fun blocksOnOneRowReadLeftToRightEvenIfTheRightOneStartsHigher() {
        val left = intArrayOf(40, 302, 400, 360); val right = intArrayOf(600, 300, 1000, 358); val below = intArrayOf(40, 420, 900, 480)
        assertEquals(listOf(1, 0, 2), SelLogic.readingOrder(listOf(right, left, below)))
    }
    @Test fun columnsKeepTopToBottomOrderWithinARow() {
        val a = intArrayOf(40, 100, 500, 160); val b = intArrayOf(40, 200, 500, 260); val c = intArrayOf(600, 100, 1100, 160)
        assertEquals(listOf(0, 2, 1), SelLogic.readingOrder(listOf(a, b, c)))
    }

    // ---- zoom ----
    @Test fun thePointUnderTheFingersStaysThereWhileZooming() {
        val o = SelLogic.zoomAt(1f, 0f, 0f, 300f, 800f, 2.5f)
        // content point (300, 800) must still be at screen (300, 800)
        assertEquals(300f, 300f * 2.5f + o[0], 0.01f); assertEquals(800f, 800f * 2.5f + o[1], 0.01f)
        val o2 = SelLogic.zoomAt(2.5f, o[0], o[1], 900f, 200f, 4f)
        val cx = (900f - o[0]) / 2.5f; val cy = (200f - o[1]) / 2.5f
        assertEquals(900f, cx * 4f + o2[0], 0.01f); assertEquals(200f, cy * 4f + o2[1], 0.01f)
    }
    @Test fun theZoomedPictureAlwaysCoversTheScreen() {
        assertEquals(listOf(0f, 0f), SelLogic.clampOffset(2f, 150f, 90f, 1200f, 2608f).toList())
        assertEquals(listOf(-1200f, -2608f), SelLogic.clampOffset(2f, -5000f, -9000f, 1200f, 2608f).toList())
        assertEquals(listOf(-300f, -700f), SelLogic.clampOffset(2f, -300f, -700f, 1200f, 2608f).toList())
        assertEquals(listOf(0f, 0f), SelLogic.clampOffset(0.8f, 77f, -40f, 1200f, 2608f).toList())
    }
    @Test fun zoomLimitsAndSnapBackToFit() {
        assertEquals(1f, SelLogic.settleZoom(0.7f), 0f); assertEquals(1f, SelLogic.settleZoom(1.05f), 0f)
        assertEquals(3f, SelLogic.settleZoom(3f), 0f); assertEquals(SelLogic.ZOOM_MAX, SelLogic.settleZoom(9f), 0f)
        assertTrue(SelLogic.rubberZoom(0.5f) > 0.5f && SelLogic.rubberZoom(0.5f) < 1f)
        assertTrue(SelLogic.rubberZoom(8f) > SelLogic.ZOOM_MAX && SelLogic.rubberZoom(8f) < 8f)
        assertEquals(2f, SelLogic.rubberZoom(2f), 0f)
    }

    // ---- found by the adversarial review ----
    @Test fun adjustingATinyRegionOrOneAgainstAnEdgeNeverThrows() {
        for (mode in listOf(1, 2, 4, 8, 1 or 2, 4 or 8, 1 or 8, 2 or 4)) for (dx in listOf(-5000f, -3f, 0f, 3f, 5000f)) {
            for (box in listOf(Box(0f, 0f, 10f, 10f), Box(1190f, 2600f, 1200f, 2608f), Box(0f, 100f, 30f, 2608f), Box(500f, 500f, 500f, 500f))) {
                val b = SelLogic.adjust(box, mode, dx, -dx, 1200f, 2608f, 120f)
                assertTrue(b.l >= 0f && b.r <= 1200f && b.t >= 0f && b.b <= 2608f && b.l <= b.r && b.t <= b.b)
            }
        }
        val whole = SelLogic.adjust(Box(-50f, -50f, 1300f, 2700f), 0, 10f, 10f, 1200f, 2608f, 120f)
        assertEquals(0f, whole.l, 0f); assertEquals(0f, whole.t, 0f)
    }
    @Test fun aLetterAtTheEndOfALineIsTextNotAnIcon() {
        val ws = page("Vitamin C")
        assertFalse(SelLogic.iconish(ws, 1))
        assertEquals(1, SelLogic.wordAt(ws, ws[1].cx, ws[1].cy, 12f))
        assertTrue(SelLogic.iconish(page("O"), 0))
    }
    @Test fun rubberBandAndItsInverseAgreeSoRebasingAPinchDoesNotJump() {
        for (z in listOf(0.3f, 0.9f, 1f, 2.5f, 5f, 6f, 9f)) assertEquals(z, SelLogic.unrubberZoom(SelLogic.rubberZoom(z)), 0.001f)
        assertEquals(SelLogic.rubberZoom(0.999f), SelLogic.rubberZoom(1f), 0.01f)
        assertTrue(SelLogic.rubberZoom(5.5f) < SelLogic.rubberZoom(6f))
    }
    @Test fun emptyInputsNeverThrow() {
        assertEquals(-1, SelLogic.wordAt(emptyList(), 1f, 1f, 10f)); assertEquals(-1, SelLogic.nearestWord(emptyList(), 1f, 1f))
        assertTrue(SelLogic.classify(emptyList(), floatArrayOf(5f, 5f, 5f, 5f), 0f) is Pick.Image)
        assertEquals(emptyList<Int>(), SelLogic.readingOrder(emptyList()))
        assertEquals(listOf(0, 1), SelLogic.readingOrder(listOf(intArrayOf(0, 0, 10, 10), intArrayOf(0, 0, 10, 10))))
        val flat = listOf(W("x", 0, 10, 20, 10, 10, 10, 0, 0))            // a word box without height
        assertTrue(SelLogic.classify(flat, line(0f, 10f, 20f, 10f), 72f) !is Pick.None)
    }
    @Test fun onlyAnIconUnderALoopIsAPictureSearch() {
        assertTrue(SelLogic.classify(page("Q", x0 = 400, y0 = 800), loop(300f, 700f, 600f, 950f), 72f) is Pick.Image)
    }

    // ---- lasso and stroke look (0.9) ----
    private fun circle(cx: Float, cy: Float, rx: Float, ry: Float): FloatArray {
        val n = 48
        return FloatArray(n * 2) { k -> val a = (k / 2) * 2 * Math.PI / (n - 1); if (k % 2 == 0) cx + rx * Math.cos(a).toFloat() else cy + ry * Math.sin(a).toFloat() }
    }
    @Test fun aRoundLoopTakesWhatItEnclosesNotWhatItsBoundingBoxHolds() {
        // five lines; an ellipse around the middle of them: the words in the box's corners are outside the loop
        val ws = page("aa bb cc dd ee ff gg", "hh ii jj kk ll mm nn", "oo pp qq rr ss tt uu", "vv ww xx yy zz ab cd", "ef gh ij kl mn op qr")
        val first = ws.first(); val last = ws.last()
        val cx = (first.l + last.r) / 2f; val cy = (first.lt + last.lb) / 2f
        val p = SelLogic.classify(ws, circle(cx, cy, (last.r - first.l) / 2f + 10f, (last.lb - first.lt) / 2f + 10f), finger) as Pick.Text
        assertTrue("corner word must not be selected", p.a > 0)
        assertTrue(p.b < ws.size - 1)
        assertTrue(SelLogic.coveredBy(circle(cx, cy, 2000f, 2000f), first) > 0.99f)
    }
    @Test fun aWordTheLoopCutsInHalfBelongsToItWhenMostOfItIsInside() {
        val ws = page("alpha beta gamma delta epsilon")
        val g = ws[2]
        // box-shaped loop that ends at 70 % of "gamma": more than half inside -> selected; at 30 % -> not
        val seven = loop(ws[1].l - 6f, g.lt - 20f, g.l + (g.r - g.l) * 0.7f, g.lb + 20f)
        val three = loop(ws[1].l - 6f, g.lt - 20f, g.l + (g.r - g.l) * 0.3f, g.lb + 20f)
        assertTrue(SelLogic.coveredBy(seven, g) >= 0.5f); assertTrue(SelLogic.coveredBy(three, g) < 0.5f)
    }
    @Test fun alongALineAndBackAgainSelectsThatTextNotAStripOfPicture() {
        val ws = page("the quick brown fox jumps", "over the lazy dog")
        val a = ws[idx(ws, "quick")]; val b = ws[idx(ws, "fox")]
        val there = line(a.cx, a.cy, b.cx, b.cy, 20); val back = line(b.cx, b.cy + 6f, a.cx + 4f, a.cy + 6f, 20)
        val p = SelLogic.classify(ws, there + back, finger) as Pick.Text
        assertEquals(idx(ws, "quick"), p.a); assertEquals(idx(ws, "fox"), p.b)
    }
    @Test fun aLoopDrawnTwiceAroundStillSelectsWhatIsInside() {
        val ws = page("aa bb cc dd ee", "ff gg hh ii jj", "kk ll mm nn oo")
        val first = ws.first(); val last = ws.last()
        val cx = (first.l + last.r) / 2f; val cy = (first.lt + last.lb) / 2f
        val one = circle(cx, cy, (last.r - first.l) / 2f + 40f, (last.lb - first.lt) / 2f + 40f)
        val p = SelLogic.classify(ws, one + one, finger)
        assertTrue("two laps must not turn the inside out", p is Pick.Text); p as Pick.Text
        assertTrue(p.a <= idx(ws, "hh") && p.b >= idx(ws, "hh"))
        assertTrue(SelLogic.inPolygon(one + one, cx, cy))
    }
    @Test fun aLoopAroundOneWordSelectsOnlyThatWord() {
        val ws = page("alpha beta gamma delta", "one two three four")
        val g = ws[idx(ws, "beta")]
        val p = SelLogic.classify(ws, circle(g.cx, g.cy, (g.r - g.l) / 2f + 5f, g.h / 2f + 6f), finger) as Pick.Text
        assertEquals(idx(ws, "beta"), p.a); assertEquals(idx(ws, "beta"), p.b)
    }
    @Test fun aLoopSmallerThanTheWordStillPicksIt() {
        val ws = page("alpha beta gamma")
        val g = ws[1]
        val p = SelLogic.classify(ws, circle(g.cx, g.cy, 14f, 12f), finger)
        assertTrue(p is Pick.Text); assertEquals(1, (p as Pick.Text).a); assertEquals(1, p.b)
    }
    @Test fun figureEightHasTwoInsides() {
        val eight = floatArrayOf(0f, 0f, 10f, 10f, 20f, 0f, 20f, 10f, 10f, 0f, 0f, 10f)
        assertTrue(SelLogic.inPolygon(eight, 3f, 5f)); assertTrue(SelLogic.inPolygon(eight, 17f, 5f))
    }
    @Test fun aScribbleSelectsWhatItRunsOverNotItsWholeRectangle() {
        // small text, five lines; a slanted scribble from the first words of line 2 down to the last words of line 4
        val ws = page("a1 a2 a3 a4 a5 a6", "b1 b2 b3 b4 b5 b6", "c1 c2 c3 c4 c5 c6", "d1 d2 d3 d4 d5 d6", "e1 e2 e3 e4 e5 e6", lineH = 22, pitch = 30, charW = 9)
        val from = ws[idx(ws, "b1")]; val to = ws[idx(ws, "d6")]
        val p = ArrayList<Float>()
        for (k in 0..40) { val f = k / 40f; p.add(from.cx + (to.cx - from.cx) * f); p.add(from.cy + (to.cy - from.cy) * f + (if (k % 2 == 0) -7f else 7f)) }
        for (k in 40 downTo 0) { val f = k / 40f; p.add(from.cx + (to.cx - from.cx) * f + 3f); p.add(from.cy + (to.cy - from.cy) * f + (if (k % 2 == 0) 7f else -7f)) }
        for (k in 0..40) { val f = k / 40f; p.add(from.cx + (to.cx - from.cx) * f); p.add(from.cy + (to.cy - from.cy) * f) }
        val pick = SelLogic.classify(ws, p.toFloatArray(), 72f) as Pick.Text
        assertEquals(idx(ws, "b1"), pick.a); assertEquals(idx(ws, "d6"), pick.b)
    }
    @Test fun pointInPolygonBasics() {
        val sq = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertTrue(SelLogic.inPolygon(sq, 5f, 5f)); assertFalse(SelLogic.inPolygon(sq, 15f, 5f)); assertFalse(SelLogic.inPolygon(floatArrayOf(), 1f, 1f))
    }
    @Test fun theStrokeCoreKeepsItsMinimumWidthAtEveryZoom() {
        for (zoom in listOf(1f, 2f, 4f)) {
            val px = SelLogic.strokeCoreOnScreen(3f, zoom)
            assertTrue("core is $px px at zoom $zoom, must be at least 15.9 (5.3 dp)", px >= 15.9f)
            assertEquals(px, SelLogic.strokeCoreOnScreen(3f, 1f), 0f)
        }
        assertTrue(SelLogic.strokeLook(2.625f).coreWidth >= 13.9f)
    }
    @Test fun theStrokeHasABodyAndAFadingHalo() {
        for (d in listOf(1f, 2.625f, 3f)) {
            val k = SelLogic.strokeLook(d)
            assertTrue("rim under the core, for white pages", k.rimWidth > k.coreWidth + d)
            assertTrue("older parts keep most of their core", k.oldCoreAlpha >= 190)
            // Expected falloff away from the stroke centre.
            assertEquals(0.55f, SelLogic.haloAt(k, 10f * d), 0.03f)
            assertEquals(0.53f, SelLogic.haloAt(k, 17.6f * d), 0.04f)
            assertEquals(0.41f, SelLogic.haloAt(k, 25.6f * d), 0.05f)
            assertEquals(0.20f, SelLogic.haloAt(k, 32.6f * d), 0.05f)
            assertEquals(0.07f, SelLogic.haloAt(k, 44.6f * d), 0.04f)
            assertTrue(SelLogic.haloAt(k, 62f * d) < 0.01f)
            // smooth: no step of more than 4 % between neighbouring dp
            var prev = SelLogic.haloAt(k, 0f)
            for (x in 1..70) { val v = SelLogic.haloAt(k, x * d); assertTrue(abs(v - prev) < 0.04f); prev = v }
        }
    }
    @Test fun contentToScreenMapping() {
        assertEquals(700f, SelLogic.toScreen(300f, 2f, 100f), 0f); assertEquals(300f, SelLogic.toScreen(300f, 1f, 0f), 0f)
    }

    // ---- second reading of tiny text ----
    @Test fun theSecondReadingReplacesOnlyTheSelectedPlaces() {
        val first = page("kockato0.or9 points by mrkn1", lineH = 14, charW = 6)
        val again = page("kockatoo.org points by mrkn1", lineH = 14, charW = 6)
        assertEquals("kockatoo.org", SelLogic.reread(first.subList(0, 1), again))
        assertEquals("kockatoo.org points by", SelLogic.reread(first.subList(0, 3), again))
    }
    @Test fun aSecondReadingThatLostOrInventedTooMuchIsRejected() {
        val first = page("important update for readers", lineH = 14, charW = 6)
        assertEquals(null, SelLogic.reread(first, page("im", lineH = 14, charW = 6)))
        assertEquals(null, SelLogic.reread(first, emptyList()))
        assertEquals(null, SelLogic.reread(first.subList(0, 1), page("importantimportantimportant x", lineH = 14, charW = 6).subList(0, 1)))
    }
    @Test fun secondReadingKeepsLineOrderAcrossTwoLines() {
        val first = page("aaa bbb", "ccc ddd", lineH = 14, pitch = 20, charW = 6)
        val again = page("aaa bbb", "ccc ddd", lineH = 14, pitch = 20, charW = 6).reversed()
        assertEquals("aaa bbb ccc ddd", SelLogic.reread(first, again))
    }

    // ---- back: the whole table ----
    private fun back(preparing: Boolean = false, closing: Boolean = false, translating: Boolean = false, editing: Boolean = false, card: Boolean = false, page: Boolean = false, searches: Int = 0, levels: Int = 1) =
        SelLogic.backStep(preparing, closing, translating, editing, card, page, searches, levels)
    @Test fun backOnAFreshOverlayOrOnTheFirstSearchCloses() {
        assertEquals(SelLogic.Back.CLOSE, back(searches = 0)); assertEquals(SelLogic.Back.CLOSE, back(searches = 1))
    }
    @Test fun backGoesThroughTheSearchesNewestFirstThenCloses() {
        var searches = 4; val steps = ArrayList<SelLogic.Back>()
        while (true) { val b = back(searches = searches); steps.add(b); if (b != SelLogic.Back.PREVIOUS_SEARCH) break; searches-- }
        assertEquals(listOf(SelLogic.Back.PREVIOUS_SEARCH, SelLogic.Back.PREVIOUS_SEARCH, SelLogic.Back.PREVIOUS_SEARCH, SelLogic.Back.CLOSE), steps)
    }
    @Test fun backWithTheKeyboardUpOnlyLeavesTheBar() {
        assertEquals(SelLogic.Back.LEAVE_BAR, back(editing = true, searches = 3, levels = 2, page = true, card = true))
    }
    @Test fun backOrder() {
        assertEquals(SelLogic.Back.CLOSE_CARD, back(card = true, page = true, searches = 3, levels = 2))
        assertEquals(SelLogic.Back.PAGE_BACK, back(page = true, searches = 3, levels = 2))
        assertEquals(SelLogic.Back.PREVIOUS_SEARCH, back(searches = 2, levels = 2))
        assertEquals(SelLogic.Back.PREVIOUS_LEVEL, back(searches = 1, levels = 2))
        assertEquals(SelLogic.Back.PREVIOUS_LEVEL, back(searches = 0, levels = 3))
    }
    @Test fun backDuringOpeningOrClosingIsSwallowedSoItNeverReachesTheAppBelow() {
        for (s in 0..3) for (l in 1..3) {
            assertEquals(SelLogic.Back.NONE, back(preparing = true, searches = s, levels = l))
            assertEquals(SelLogic.Back.NONE, back(closing = true, searches = s, levels = l, editing = true, card = true, page = true))
        }
    }
    @Test fun backInTranslateModeCloses() { assertEquals(SelLogic.Back.CLOSE, back(translating = true, searches = 5, levels = 2)) }
    @Test fun everyStateGivesExactlyOneStep() {
        for (pr in listOf(false, true)) for (cl in listOf(false, true)) for (tr in listOf(false, true)) for (ed in listOf(false, true)) for (cd in listOf(false, true)) for (pg in listOf(false, true)) for (s in 0..3) for (l in 1..3)
            SelLogic.backStep(pr, cl, tr, ed, cd, pg, s, l)       // total: no state throws, each returns one value
    }

    // ---- the search bar ----
    @Test fun typedTextBecomesASearchAndTheSameTextSearchesAgain() {
        assertEquals("https://www.google.com/search?q=klarer+Himmel&hl=de", SelLogic.searchUrl("klarer Himmel", "de"))
        assertEquals(SelLogic.searchUrl("klarer Himmel", "de"), SelLogic.searchUrl(" klarer Himmel ", "de"))
        assertEquals("https://www.google.com/?hl=en", SelLogic.searchUrl("  ", "en"))
    }
    @Test fun specialCharactersInTheBarAreEncoded() {
        assertEquals("https://www.google.com/search?q=a%26b+%3D+%C3%BC%3F&hl=en", SelLogic.searchUrl("a&b = ü?", "en"))
    }
    @Test fun aLongParagraphIsCutToThirtyTwoWords() {
        val q = (1..50).joinToString(" ") { "w$it" }
        assertEquals(32, SelLogic.limitWords(q).split(" ").size)
    }
    @Test fun textTypedAfterAPictureSearchIsAddedToThatPicture() {
        val lens = "https://www.google.com/search?vsrid=ABC123&udm=26&lns_mode=un&hl=de"
        assertEquals("https://www.google.com/search?vsrid=ABC123&udm=26&lns_mode=un&hl=de&q=in+rot", SelLogic.withQuery(lens, "in rot"))
        // typing again replaces the earlier words instead of piling them up; clearing the bar removes them
        val twice = SelLogic.withQuery(SelLogic.withQuery(lens, "in rot"), "blau")
        assertEquals("https://www.google.com/search?vsrid=ABC123&udm=26&lns_mode=un&hl=de&q=blau", twice)
        assertEquals(lens, SelLogic.withQuery(twice, ""))
        assertEquals("https://x.test/p?q=a#frag", SelLogic.withQuery("https://x.test/p#frag", "a"))
    }

    // ---- adjusting a picture region ----
    @Test fun regionMovesAsAWholeAndStaysOnThePicture() {
        val b = SelLogic.adjust(Box(100f, 100f, 400f, 500f), 0, 5000f, -5000f, 1200f, 2608f, 72f)
        assertEquals(900f, b.l, 0f); assertEquals(1200f, b.r, 0f); assertEquals(0f, b.t, 0f); assertEquals(400f, b.b, 0f)
    }
    @Test fun regionBorderFollowsTheFingerButNeverCrossesTheOther() {
        val b = SelLogic.adjust(Box(100f, 100f, 400f, 500f), 4 or 8, -1000f, 120f, 1200f, 2608f, 72f)
        assertEquals(172f, b.r, 0f); assertEquals(620f, b.b, 0f); assertEquals(100f, b.l, 0f)
    }
    @Test fun grabFindsCornersBordersInsideAndNothing() {
        val box = Box(200f, 200f, 800f, 1000f)
        assertEquals(1 or 2, SelLogic.grab(box, 205f, 195f, 60f))
        assertEquals(4, SelLogic.grab(box, 790f, 600f, 60f))
        assertEquals(0, SelLogic.grab(box, 500f, 600f, 60f))
        assertEquals(-1, SelLogic.grab(box, 1000f, 600f, 60f))
    }

    // ---- edge touches ----
    @Test fun aSidewaysStrokeFromTheEdgeIsBack() {
        assertEquals(SelLogic.Edge.WAIT, SelLogic.edgeVerdict(4f, 2f))
        assertEquals(SelLogic.Edge.BACK, SelLogic.edgeVerdict(8f, 3f))
        assertEquals(SelLogic.Edge.BACK, SelLogic.edgeVerdict(30f, -28f))          // slanted, but more sideways than up
        assertEquals(SelLogic.Edge.BACK, SelLogic.edgeVerdict(9f, 9f))
    }
    @Test fun upDownOrOutwardFromTheEdgeIsAMark() {
        assertEquals(SelLogic.Edge.MARK, SelLogic.edgeVerdict(3f, 14f))
        assertEquals(SelLogic.Edge.MARK, SelLogic.edgeVerdict(10f, -20f))
        assertEquals(SelLogic.Edge.MARK, SelLogic.edgeVerdict(-9f, 1f))
        assertEquals(SelLogic.Edge.WAIT, SelLogic.edgeVerdict(5f, 10f))            // not yet clear
    }
    @Test fun aBackSwipeNeedsDistanceOrSpeed() {
        assertTrue(SelLogic.edgeBackCommits(121f, 1600f))
        assertTrue(SelLogic.edgeBackCommits(33f, 0f))
        assertTrue(SelLogic.edgeBackCommits(15f, 600f))
        assertFalse(SelLogic.edgeBackCommits(15f, 100f))
        assertFalse(SelLogic.edgeBackCommits(9f, 2000f))
    }
    // ---- released sheet ----
    @Test fun aSheetLetGoLowOrFlungDownGoesAwayWithoutStopping() {
        val d = 3f; val peek = 760 * d; val off = 870 * d
        for ((pos, v) in listOf(458f to 2451f, 446f to 2967f, 482f to 2235f, 481f to 3136f)) assertNull(SelLogic.sheetRest(pos * d, v * d, peek, off))
        assertNull(SelLogic.sheetRest(700 * d, 0f, peek, off))                      // carried down slowly and let go
        assertNull(SelLogic.sheetRest(800 * d, -50f, peek, off))
    }
    @Test fun aSheetLetGoElsewhereStaysOrSnapsToFull() {
        val d = 3f; val peek = 760 * d; val off = 870 * d
        assertEquals(300 * d, SelLogic.sheetRest(300 * d, 0f, peek, off)!!, 0.5f)
        assertEquals(0f, SelLogic.sheetRest(60 * d, 0f, peek, off)!!, 0.5f)
        assertEquals(0f, SelLogic.sheetRest(430 * d, -3000 * d, peek, off)!!, 0.5f)  // flung up
        assertEquals(430 * d + 100 * d * 0.17f, SelLogic.sheetRest(430 * d, 100 * d, peek, off)!!, 0.5f)
    }
}
