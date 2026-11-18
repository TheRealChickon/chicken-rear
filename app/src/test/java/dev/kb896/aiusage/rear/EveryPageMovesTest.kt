package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every page of the back moves the same way and has the display's corners: it slides (never fades, never cuts where
 * it can be seen), follows a finger, goes back when let go short. The pages are enumerated from the source.
 */
class EveryPageMovesTest {
    private val w = 976f; private val h = 596f

    @Test fun `a page comes in from its edge in the pages' own time and settles - no jump on the way`() {
        val s = PageSlide()
        s.comeFrom(PageSlide.Edge.RIGHT)
        assertEquals(w, s.x(w), 0.01f); assertEquals(0f, s.y(h), 0.01f)
        var now = 1_000L; var last = s.x(w); var steps = 0
        while (s.step(now)) {
            val x = s.x(w)
            assertTrue("never back out ($last -> $x)", x <= last + 0.01f)
            assertTrue("no jump: ${last - x} px in 8 ms", last - x < w * 0.12f)
            last = x; now += 8; steps++
        }
        assertEquals(0f, s.x(w), 0.01f)
        assertEquals("in ShellPage.SLIDE_MS, as the notes and the usage page", ShellPage.SLIDE_MS, (now - 1_000L).toFloat(), 16f)
        assertFalse(s.gone)
    }

    @Test fun `sent away it slides out to its edge and is gone only when it has arrived`() {
        for (edge in PageSlide.Edge.values()) {
            val s = PageSlide()
            s.inPlace()
            s.leaveTo(edge)
            assertFalse("not gone at once: it does not cut", s.gone)
            var now = 5_000L
            s.step(now); now += 130; s.step(now)
            // Half its time in: part of the way, in the stroke's direction, and still there.
            val x = s.x(w); val y = s.y(h)
            when (edge) {
                PageSlide.Edge.RIGHT -> assertTrue(x > 0f && x < w && y == 0f)
                PageSlide.Edge.LEFT -> assertTrue(x < 0f && x > -w && y == 0f)
                PageSlide.Edge.TOP -> assertTrue(y < 0f && y > -h && x == 0f)
                PageSlide.Edge.BOTTOM -> assertTrue(y > 0f && y < h && x == 0f)
            }
            assertFalse(s.gone)
            while (s.step(now)) now += 8
            assertTrue(s.gone)
            assertTrue(kotlin.math.abs(s.x(w)) == (if (edge == PageSlide.Edge.RIGHT || edge == PageSlide.Edge.LEFT) w else 0f))
        }
    }

    @Test fun `under a finger it is where the finger has it - let go short it slides back, sent on it goes on to that edge`() {
        val s = PageSlide()
        s.inPlace()
        // A finger takes it 40% of the way up.
        s.peek(PageDrag.inPart(false, 0.4f * h, h), PageSlide.Edge.TOP)
        assertTrue(s.held)
        assertEquals(-0.4f * h, s.y(h), 2f)
        // It does not move by itself while held.
        assertTrue(s.step(10_000L)); assertTrue(s.step(10_500L))
        assertEquals(-0.4f * h, s.y(h), 2f)
        // Let go short: back into its place, by its slide.
        s.unpeek()
        var now = 11_000L
        assertTrue(s.step(now)); now += 8
        while (s.step(now)) { assertTrue(s.y(h) <= 0f); now += 8 }
        assertEquals(0f, s.y(h), 0.01f); assertFalse(s.gone)
        // Taken again and sent on: out by the edge the finger was taking it to, from where it was (no jump back).
        s.peek(PageDrag.inPart(false, 0.5f * h, h), PageSlide.Edge.TOP)
        s.leaveTo(PageSlide.Edge.RIGHT)
        assertEquals(PageSlide.Edge.TOP, s.edge)
        assertEquals(-0.5f * h, s.y(h), 2f)
        now = 12_000L
        while (s.step(now)) now += 8
        assertTrue(s.gone)
        // An unpeek with no finger on it changes nothing.
        val t = PageSlide(); t.inPlace(); t.unpeek(); assertFalse(t.step(1L)); assertEquals(0f, t.x(w), 0f)
    }

    // ---- every page, from the source ----

    private val rear = File("src/main/java/dev/kb896/aiusage/rear")
    private fun src(name: String) = File(rear, name).readText()

    @Test fun `every page's layer is made with the display's corners`() {
        // The classes that put a whole page on the back, each with the layer it makes.
        val pages = mapOf(
            "ShellPage.kt" to "the usage page and the notes",
            "ShellCard.kt" to "the navigation, timer, call and music pages",
            "ShellCharge.kt" to "the charging page",
            "ShellNotices.kt" to "the pop-up",
        )
        for ((file, what) in pages) {
            val text = src(file)
            val made = Regex("""ShellLayer\.create\((?:[^()]|\([^()]*\))*\)""").findAll(text).map { it.value }.toList()
            assertTrue("$file makes a layer", made.isNotEmpty())
            assertTrue("$what ($file): its layer has the page's corner", made.any { it.contains("ShellLayer.pageCorner(") })
        }
        // The radius acts on a crop (the picture is a child's): the one rule sets both, and every page's placing uses it.
        val layers = src("ShellLayer.kt")
        val shape = src("RearShape.kt")
        val rule = shape.substring(shape.indexOf("fun place(")).substringBefore("\n    }")
        assertTrue(rule.contains("setCrop(") && rule.contains("roundCorners(") && rule.contains("cornerAt("))
        val made = layers.substring(layers.indexOf("fun create("))
        assertTrue("a layer made with a corner is given its crop", made.indexOf("setCrop(") in 0 until made.indexOf("live.add(layer)"))
        // Which radius, where: RearCornersTest composes every animation and holds the corners themselves.
        for (file in pages.keys) assertTrue("$file places through the one rule", src(file).contains("RearShape.place("))
        // The notifications' pages are drawn in one picture: each is cut there when it is not in its place.
        assertTrue(src("NoticePagesArt.kt").contains("PageCorners.clear("))
        // The notes page given at its end, over black.
        assertTrue(src("NoteTurnArt.kt").contains("PageCorners.cut("))
        // And the corner is the display's own.
        assertEquals(RearGeometry.PRO17.cornerPx.toFloat(), ShellLayer.pageCorner(RearGeometry.PRO17), 0f)
    }

    @Test fun `no page comes or goes by a fade - the row's pages slide, the charging page with them`() {
        // The live pages are made to slide.
        val service = File(rear, "RearUserService.kt").readText()
        val cards = Regex("""ShellCard\("(\w+)"[^\n]*""").findAll(service).map { it.groupValues[1] to it.value }.toList()
        assertEquals(setOf("music", "timer", "call", "navigation"), cards.map { it.first }.toSet())
        for ((name, line) in cards) assertTrue("$name slides", line.contains("slide = true") && line.contains("opaque = true"))
        // The charging page: sent away it leaves by its slide (PageSlide), and the fade is only the small one's over an app.
        val charge = src("ShellCharge.kt")
        val away = charge.substring(charge.indexOf("fun swipedAway()")).substringBefore("\n    }")
        assertTrue(away.contains("slide.leaveTo("))
        assertTrue("the fade is kept for the small picture over an app only", away.indexOf("cutAt =") in away.indexOf("if (compact)")..away.indexOf("} else {"))
        assertTrue(charge.contains("slide.x(") && charge.contains("RearShape.place(tx, sc, rear, x, y)"))
        // It follows a finger out, as the others do.
        assertTrue(service.contains("charge.peek(it, edge)") && service.contains("charge.unpeek()"))
        // The usage page and the notes: ShellPage places by position, never by alpha.
        val page = src("ShellPage.kt")
        assertTrue(page.contains("RearShape.place(it, sc") && !page.contains("setAlpha("))
    }

    @Test fun `the navigation's animations - what still changes at once, and why that is not seen moving`() {
        // Every animation the table can ask for is a slide or a page's own arrival, but CUT and NONE.
        val slides = setOf(
            RearNav.Anim.LIVE_IN, RearNav.Anim.LIVE_OUT, RearNav.Anim.USAGE_IN, RearNav.Anim.USAGE_OUT, RearNav.Anim.NOTES_IN, RearNav.Anim.NOTES_OUT,
            RearNav.Anim.NOTE_OLDER, RearNav.Anim.NOTE_NEWER, RearNav.Anim.NOTE_END_OLDER, RearNav.Anim.NOTE_END_NEWER,
            RearNav.Anim.SHADE_IN, RearNav.Anim.SHADE_OUT, RearNav.Anim.POP_IN, RearNav.Anim.POP_OUT,
            // The face picker's: its own springs (FacePicker), the face shrinking into its card and growing back.
            RearNav.Anim.PICKER_IN, RearNav.Anim.PICKER_OUT,
            // A note standing at its place (Pin to location): a ShellPage up from the bottom edge and down to it, and the
            // notes' own turn sideways (NoteTurn) along the ones standing.
            RearNav.Anim.ARRIVAL_IN, RearNav.Anim.ARRIVAL_OUT, RearNav.Anim.ARRIVAL_OLDER, RearNav.Anim.ARRIVAL_NEWER,
            RearNav.Anim.ARRIVAL_END_OLDER, RearNav.Anim.ARRIVAL_END_NEWER,
            // A pop-up thrown out flies off the way the finger went; one that may not be thrown springs back (ShellNotices' banner).
            RearNav.Anim.POP_THROWN_LEFT, RearNav.Anim.POP_THROWN_RIGHT, RearNav.Anim.POP_GIVES,
        )
        // The notes put in order: the carousel's springs (NoteCarousel; NoteCarouselTest holds that nothing jumps a frame).
        val springs = setOf(RearNav.Anim.ARRANGE_IN, RearNav.Anim.ARRANGE_OUT, RearNav.Anim.NOTE_CARRIED, RearNav.Anim.NOTE_SWAP)
        val atOnce = RearNav.Anim.values().toSet() - slides - springs
        // LIVE_SWAP: one live thing's page in another's place under the same finger-less moment (a timer ending under
        // music); CUT: the light going or coming, the back given to another app or taken back; NONE: nothing seen changes.
        assertEquals(setOf(RearNav.Anim.NONE, RearNav.Anim.LIVE_SWAP, RearNav.Anim.CUT), atOnce)
        // CUT is asked for only by the light and by another app taking or leaving the back.
        val table = src("RearNav.kt")
        val cuts = table.lines().filter { it.contains("Anim.CUT") && !it.trim().startsWith("CUT") && !it.trim().startsWith("*") && !it.trim().startsWith("/") }
        assertEquals(4, cuts.size)
        val lines = table.lines()
        val inputs = lines.indices.filter { i -> lines[i].contains("Anim.CUT") && cuts.contains(lines[i]) }
            .mapNotNull { i -> (i downTo maxOf(0, i - 2)).firstNotNullOfOrNull { j -> Regex("""Input\.(\w+) ->""").find(lines[j])?.groupValues?.get(1) } }.toSet()
        assertEquals(setOf("Dark", "Lit", "OthersTake", "OthersLeave"), inputs)
    }
}
