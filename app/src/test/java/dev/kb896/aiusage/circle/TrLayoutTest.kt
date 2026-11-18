package dev.kb896.aiusage.circle

import dev.kb896.aiusage.circle.TrLayout.L
import dev.kb896.aiusage.circle.TrLayout.P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geometry fixtures for a 1200 × 2608 dark chat layout with 17 sp text. */
class TrLayoutTest {
    /** A line of words starting at [x], top [t], letter height [h]; words about 16 px a letter, 14 px spaces. */
    private fun line(x: Int, t: Int, text: String, h: Int = 46, conf: Float = 0.95f): L {
        var cx = x
        val parts = text.split(' ').map { wd ->
            val wpx = if (wd.length == 1 && wd[0] == '•') 14 else wd.length * 16
            val top = if (wd == "•") t + 16 else t
            val bot = if (wd == "•") t + 30 else t + h
            P(wd, intArrayOf(cx, top, cx + wpx, bot), conf).also { cx += wpx + (if (wd == "•") 22 else 14) }
        }
        return L(text, intArrayOf(x, t, parts.last().box[2], t + h), parts)
    }

    @Test fun bulletsBecomeOneBlockEach() {
        // five items, one line each except the third and the fifth; 77 px pitch inside an item, 88 between items
        val ls = listOf(
            line(74, 322, "• Seedlings in shallow trays."),
            line(74, 410, "• Water the herbs when the soil dries."),
            line(74, 498, "• The hose beside the raised bed has a"),
            line(110, 575, "leak."),
            line(74, 663, "• Set the empty pots aside for spring."),
            line(74, 751, "• A climbing plant needs a support with"),
            line(110, 828, "enough room to grow."),
        )
        val items = TrLayout.split(ls)
        assertEquals(5, items.size)
        assertEquals("Seedlings in shallow trays.", items[0].text)
        assertEquals("The hose beside the raised bed has a leak.", items[2].text)
        assertEquals(2, items[2].lines.size)
        for (it in items) {
            assertTrue("the bullet stays outside the text: ${it.keepL}", it.keepL in 89..109)
            assertEquals(110, it.box[0])           // the text's own left edge, not the bullet's
        }
    }

    @Test fun paragraphGapSplitsAMergedBlock() {
        val a = (0 until 5).map { line(49, 1250 + it * 77, "Mark each row with a wooden label near the path.") }
        val b = (0 until 3).map { line(49, 1250 + 4 * 77 + 101 + it * 77, "Small birds often find seeds along the edge of a") }
        val items = TrLayout.split(a + b)
        assertEquals(2, items.size)
        assertEquals(5, items[0].lines.size)
        assertEquals(3, items[1].lines.size)
    }

    @Test fun anEvenParagraphStaysWhole() {
        val jitter = listOf(0, 2, -2, 2, -2, 1, 0)
        val ls = jitter.mapIndexed { i, j -> line(49, 900 + i * 77 + j, "The nursery has a covered shelf for those pots.") }
        assertEquals(1, TrLayout.split(ls).size)
        // two lines alone: the gap is a line's gap, not a paragraph's
        assertEquals(1, TrLayout.split(ls.take(2)).size)
    }

    @Test fun oneLineWithAJitteredTopStaysInItsParagraph() {
        // the emulator's chat page: tops 1713/1791/1860/1944 (steps 78/69/84), bottoms 1760/1839/1912/1984 (79/73/72);
        // the third line's box began 9 px high once, and the last line was cut off as a paragraph of its own
        fun l(t: Int, b: Int, text: String) = L(text, intArrayOf(50, t, 1000, b), text.split(' ').mapIndexed { i, wd -> P(wd, intArrayOf(50 + i * 90, t, 130 + i * 90, b)) })
        val ls = listOf(l(1713, 1760, "Water the plants in the early morning when"), l(1791, 1839, "the soil is cool and the leaves have time"),
            l(1860, 1912, "to dry before opening the shade for the"), l(1944, 1984, "day."))
        assertEquals(1, TrLayout.split(ls).size)
        // a real gap moves tops and bottoms alike
        val gap = ls.take(3) + l(1944 + 40, 1984 + 40, "day.")
        assertEquals(2, TrLayout.split(gap).size)
    }

    @Test fun aLoneNumberedMarkerCountsBesideItsList() {
        val three = listOf(line(64, 1152, "3. Turn the saver off."))
        assertNull(TrLayout.split(three)[0].keepL.takeIf { it != Int.MIN_VALUE })
        val listed = TrLayout.split(three, listed = setOf("1."))
        assertEquals("Turn the saver off.", listed[0].text)
        assertTrue(listed[0].keepL != Int.MIN_VALUE)
    }

    @Test fun twoLinesWithAParagraphGap() {
        val ls = listOf(line(49, 900, "First paragraph ends here."), line(49, 900 + 46 + 50, "Second one starts here"))
        assertEquals(2, TrLayout.split(ls).size)
        // the screen's own pitch decides when it is known
        assertEquals(2, TrLayout.split(listOf(line(49, 900, "aaa bbb ccc"), line(49, 990, "ddd eee")), refPitch = 77).size)
        assertEquals(1, TrLayout.split(listOf(line(49, 900, "aaa bbb ccc"), line(49, 977, "ddd eee")), refPitch = 77).size)
    }

    @Test fun anOutdentedLineStartsANewBlock() {
        val ls = listOf(line(110, 500, "rest of a long list item that wraps"), line(110, 577, "onto a second line and a third"),
            line(110, 654, "and ends."), line(49, 731, "A paragraph after the list"))
        val items = TrLayout.split(ls)
        assertEquals(2, items.size)
        assertEquals(49, items[1].box[0])
    }

    @Test fun centredLinesAreNotSplitByTheirEdges() {
        val ls = listOf(L("Welcome to the", intArrayOf(400, 500, 800, 546)), L("garden planner for", intArrayOf(370, 577, 830, 623)),
            L("everyone", intArrayOf(500, 654, 700, 700)))
        assertEquals(1, TrLayout.split(ls).size)
    }

    @Test fun weakMarkersSplitOnlyWhenRepeated() {
        val list = listOf(line(49, 500, "- apples and pears"), line(49, 577, "- plums"))
        assertEquals(2, TrLayout.split(list).size)
        assertEquals("apples and pears", TrLayout.split(list)[0].text)
        // a dash that begins a wrapped line of a sentence is part of it
        val prose = listOf(line(49, 500, "the plan was simple"), line(49, 577, "- or so it seemed then"))
        assertEquals(1, TrLayout.split(prose).size)
    }

    @Test fun iconsAreNotText() {
        // a round clock icon read as "9", the text, a chevron read as ">"
        val parts = listOf(P("9", intArrayOf(49, 2240, 97, 2288), 0.7f), P("Thought", intArrayOf(120, 2242, 250, 2286)),
            P("process", intArrayOf(264, 2242, 400, 2296)), P(">", intArrayOf(500, 2250, 516, 2280), 0.6f))
        val c = TrLayout.clean(L("9 Thought process >", intArrayOf(49, 2240, 516, 2296), parts))
        assertEquals("Thought process", c.line!!.text)
        assertEquals(120, c.line!!.box[0])
        assertTrue(c.keepL in 98..119)
        assertTrue(c.keepR in 401..499)
        // a real number before a word is text
        val n = listOf(P("9", intArrayOf(49, 2242, 72, 2286)), P("apples", intArrayOf(86, 2242, 190, 2296)))
        assertEquals("9 apples", TrLayout.clean(L("9 apples", intArrayOf(49, 2242, 190, 2296), n)).line!!.text)
        // a word of one letter in a sentence is text
        val a = listOf(P("A", intArrayOf(49, 2242, 80, 2286)), P("video", intArrayOf(94, 2242, 180, 2286)))
        assertEquals("A video", TrLayout.clean(L("A video", intArrayOf(49, 2242, 180, 2286), a)).line!!.text)
        // Chinese characters are square, and text
        val z = listOf(P("中", intArrayOf(49, 2242, 95, 2288)), P("文字", intArrayOf(120, 2242, 212, 2288)))
        assertFalse(TrLayout.isIcon(L("中 文字", intArrayOf(49, 2242, 212, 2288), z), 0))
    }

    @Test fun boldLabelAndNames() {
        val words = listOf("Circle" to true, "Search:" to true, "that" to false, "session" to false, "is" to false, "mid-turn" to false)
        val t = "Kreissuche: Diese Sitzung ist mittlerweile"
        assertEquals(listOf(0..10), TrLayout.boldRanges(words, t))
        // a bold name inside the paragraph is found where the translation has it
        val w2 = listOf("Ask" to false, "Ada" to true, "Lovelace" to true, "about" to false, "it" to false)
        val t2 = "Frag Ada Lovelace danach"
        assertEquals(listOf(5 until 17), TrLayout.boldRanges(w2, t2))
        // nothing to place: nothing bold
        assertTrue(TrLayout.boldRanges(listOf("Go" to true, "now" to false), "Jetzt los").isEmpty())
        // all bold or none: handled by the block's own weight
        assertTrue(TrLayout.boldRanges(listOf("a" to true, "b" to true), "x").isEmpty())
    }

    @Test fun barsAndFieldsAreNotPage() {
        val sw = 1200; val sh = 2608
        val nodes = listOf(
            TrLayout.N(intArrayOf(0, 0, sw, sh), 0),
            TrLayout.N(intArrayOf(0, 140, sw, 300), 1),                         // the app bar
            TrLayout.N(intArrayOf(0, 300, sw, sh), 1, scroll = true),           // the chat, under the input bar too
            TrLayout.N(intArrayOf(40, 400, 1160, 500), 2),
            TrLayout.N(intArrayOf(48, 2340, 1150, 2490), 1),                    // the input bar, drawn after the chat
            TrLayout.N(intArrayOf(200, 2370, 860, 2460), 2, edit = true),       // its field
        )
        val a = TrLayout.area(nodes, sw, sh, 110, sh - 60)
        assertEquals(300, a.page[1])
        assertEquals(2340, a.page[3])
        // page text: drawn inside the page
        assertEquals(2340, TrLayout.clipFor(a, intArrayOf(49, 1500, 1100, 1700), sw, sh)!![3])
        // the field's hint: not page text
        assertNull(TrLayout.clipFor(a, intArrayOf(220, 2390, 690, 2440), sw, sh))
        // chat text seen below the input bar, in the gesture strip: not page text
        assertNull(TrLayout.clipFor(a, intArrayOf(49, 2500, 1100, 2550), sw, sh))
        // the title in the app bar: translated, but only in its own strip
        val title = TrLayout.clipFor(a, intArrayOf(140, 190, 900, 240), sw, sh)
        assertNotNull(title)
        assertEquals(300, title!![3])
        // no list at all: the screen between the system bars
        val none = TrLayout.area(listOf(TrLayout.N(intArrayOf(0, 0, sw, sh), 0)), sw, sh, 110, sh - 60)
        assertNull(none.list)
        assertNull(TrLayout.clipFor(none, intArrayOf(0, 2570, 400, 2600), sw, sh))
    }

    @Test fun aBulletSeenOnlyInThePictureStartsAnItem() {
        // the recognizer dropped the bullets of the first three items (seen on the emulator): the picture has them
        val ls = listOf(line(110, 288, "Water the seedlings on the north bed."), line(110, 355, "Check the rain barrel for leaves."),
            line(110, 422, "The tomato stakes in the corner need to be tied"), line(110, 491, "again."))
        assertEquals(1, TrLayout.split(ls).size)
        val items = TrLayout.split(ls, bullets = listOf(95, 95, 95, null))
        assertEquals(3, items.size)
        assertEquals("The tomato stakes in the corner need to be tied again.", items[2].text)
        assertEquals(95, items[0].keepL)
    }

    @Test fun aWrappedLineReturnedAloneJoinsItsItem() {
        val item = TrLayout.split(listOf(line(74, 625, "• A new hose arrives on Friday with the fittings for")))
        val tail = TrLayout.split(listOf(line(110, 702, "the tap.")))
        val next = TrLayout.split(listOf(line(74, 790, "• Next item")))
        val para = TrLayout.split(listOf(line(49, 880, "A paragraph after the list")))
        val j = TrLayout.join(item + tail + next + para)
        assertEquals(3, j.size)
        assertEquals("A new hose arrives on Friday with the fittings for the tap.", j[0].text)
        assertTrue(j[0].keepL in 89..109)
        // a short line above is a paragraph's end: what follows is not its continuation
        val shortEnd = TrLayout.split(listOf(line(110, 625, "• Done."))) + TrLayout.split(listOf(line(110, 702, "and more text here")))
        assertEquals(2, TrLayout.join(shortEnd).size)
        // a line further down than the item's own pitch is the next paragraph
        val far = item + TrLayout.split(listOf(line(110, 760, "the tap.")))
        assertEquals(2, TrLayout.join(far).size)
    }

    @Test fun anotherColourIsAnotherBlock() {
        val ls = listOf(line(147, 162, "Garden plan for the weekend"), line(147, 214, "Shared notes", h = 40))
        assertEquals(1, TrLayout.split(ls).size)
        val two = TrLayout.split(ls, ink = listOf(0xECECEC, 0x969696))
        assertEquals(2, two.size)
        // and they stay apart when blocks are joined again
        assertEquals(2, TrLayout.join(two).size)
        // a slightly different reading of one colour is one block
        assertEquals(1, TrLayout.split(ls, ink = listOf(0xECECEC, 0xE0E4E0)).size)
    }

    @Test fun twoLinesWithoutDescendersAreOneParagraph() {
        // box heights 34 (ascenders only), gap 35: an ordinary 1.32 spacing, not a paragraph (seen on the emulator)
        val ls = listOf(L("The rest of the list can wait", intArrayOf(48, 1946, 988, 1980)), L("the weather is better.", intArrayOf(47, 2015, 448, 2049)))
        assertEquals(1, TrLayout.split(ls, xh = listOf(24, 24)).size)
        // the same lines a paragraph apart
        val far = listOf(ls[0], L("the weather is better.", intArrayOf(47, 2045, 448, 2079)))
        assertEquals(2, TrLayout.split(far, xh = listOf(24, 24)).size)
    }

    @Test fun aWrappedLineJoinsAOneLineItemByItsXHeight() {
        // seen on the emulator: "The tomato stakes ... tied" (ascenders only, 34 px box) and "again." returned apart
        val first = TrLayout.Item(listOf(L("The tomato stakes in the corner need to be tied", intArrayOf(94, 429, 1006, 463))), keepL = 81, xh = 23)
        val tail = TrLayout.Item(listOf(L("again.", intArrayOf(95, 498, 208, 538))))
        assertEquals(1, TrLayout.join(listOf(first, tail)).size)
        // without the x-height, the box alone would not have joined them... and a paragraph four x-heights down is not joined
        val far = TrLayout.Item(listOf(L("again.", intArrayOf(95, 529, 208, 569))))
        assertEquals(2, TrLayout.join(listOf(first, far)).size)
    }
    @Test fun splitOcrBlocksBecomeWholeSentencesInTheirOwnColumns() {
        val left = line(42, 150, "Die Bahn meldet wegen Bauarbeiten")
        val right = line(730, 152, "Die Stadt erwartet im Herbst")
        val leftEnd = line(43, 226, "Verspätungen zwischen Köln und Bonn.")
        val rightEnd = line(728, 229, "zahlreiche Besucher.")
        val paragraphs = TrLayout.join(listOf(rightEnd, leftEnd, right, left).map { TrLayout.Item(listOf(it)) })
        assertEquals(listOf(
            "Die Bahn meldet wegen Bauarbeiten Verspätungen zwischen Köln und Bonn.",
            "Die Stadt erwartet im Herbst zahlreiche Besucher."
        ), paragraphs.map { it.text })
    }

    @Test fun aParagraphEndDoesNotJoinTheNextLongerParagraph() {
        val end = line(49, 150, "Hier endet der Satz.")
        val next = line(49, 227, "Danach beginnt ein neuer und deutlich längerer Absatz")
        assertEquals(2, TrLayout.join(listOf(end, next).map { TrLayout.Item(listOf(it)) }).size)
    }

    @Test fun aWideGutterInOneOcrLineProducesSeparateText() {
        val left = P("Lesen", intArrayOf(20, 20, 54, 36))
        val left2 = P("macht", intArrayOf(61, 20, 100, 36))
        val right = P("Bücher", intArrayOf(170, 20, 214, 36))
        val right2 = P("öffnen", intArrayOf(220, 20, 260, 36))
        val combined = L("Lesen macht Bücher öffnen", intArrayOf(20, 20, 260, 36), listOf(left, left2, right, right2))
        assertEquals(listOf("Lesen macht", "Bücher öffnen"), TrLayout.columns(combined).map { it.text })
        assertEquals(1, TrLayout.columns(L("Lesen macht", intArrayOf(20, 20, 100, 36), listOf(left, left2))).size)
    }

    @Test fun separateInkColorsKeepAdjacentOcrBlocksApart() {
        val heading = L("Lesen im Sommer", intArrayOf(20, 20, 170, 40))
        val prose = L("Der Garten ist offen", intArrayOf(20, 46, 185, 66))
        val items = listOf(
            TrLayout.Item(listOf(heading), lastInk = 0x234b75),
            TrLayout.Item(listOf(prose), firstInk = 0x18293a)
        )
        assertEquals(2, TrLayout.join(items).size)
    }

    @Test fun aFullLineEndingInAPeriodStillJoinsTheNextLineOfItsParagraph() {
        val first = L("Im September zeigen wir alte Karten der Stadt.", intArrayOf(60, 200, 969, 248))
        val next = L("Sie erzählen, wie sich Straßen und Plätze verändert haben.", intArrayOf(62, 270, 934, 308))
        val grouped = TrLayout.join(listOf(first, next).map { TrLayout.Item(listOf(it)) })
        assertEquals(listOf(first.text + " " + next.text), grouped.map { it.text })
    }

    @Test fun aTallFinalWordStaysWithItsSentence() {
        val first = L("Am Sonntag laden wir zum gemeinsamen", intArrayOf(59, 1933, 879, 1974))
        val next = L("Basteln ein. Das Material stellen wir zur", intArrayOf(61, 1999, 835, 2032))
        val tail = L("Verfügung.", intArrayOf(71, 2053, 278, 2111))
        val items = listOf(TrLayout.Item(listOf(first, next)), TrLayout.Item(listOf(tail)))
        assertEquals(listOf(first.text + " " + next.text + " " + tail.text), TrLayout.join(items).map { it.text })
    }
}
