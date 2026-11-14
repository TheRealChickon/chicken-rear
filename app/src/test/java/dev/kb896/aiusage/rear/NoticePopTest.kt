package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.kb896.aiusage.service.BackNotices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** The pop-up against Xiaomi's pinned-message page as measured from the phone (the reference picture's own pixels). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NoticePopTest {
    private fun near(what: String, want: Int, got: Int, by: Int) {
        for ((name, shift) in listOf("red" to 16, "green" to 8, "blue" to 0)) {
            val w = (want shr shift) and 0xFF; val g = (got shr shift) and 0xFF
            assertTrue("$what $name: wanted $w, got $g", abs(w - g) <= by)
        }
    }

    @Test fun `in the pin's own colour the ground is the pin page's, top to bottom`() {
        // y to the colour read off Xiaomi's page at x 150.
        val page = mapOf(0f to 0x030000, 120f to 0x110301, 240f to 0x290B04, 360f to 0x4F1A0C, 480f to 0x843C1F, 550f to 0xB56F3E, 595f to 0xD49253)
        for ((y, rgb) in page) near("the ground at y ${y.toInt()}", rgb, NoticePop.shade(NoticePop.PIN, y), 7)
    }

    @Test fun `the head line and the message have the pin's colours`() {
        near("the head line", 0xF2A753, NoticePop.head(NoticePop.PIN), 6)
        near("the message", 0xE8DCBD, NoticePop.ink(NoticePop.PIN), 8)
    }

    @Test fun `an app's colour makes a page of that colour, and one with none a quiet grey one`() {
        val green = 0xFF25D366.toInt()
        val bottom = NoticePop.shade(green, 595f)
        assertTrue("green at the bottom", Color.green(bottom) > Color.red(bottom) + 60 && Color.green(bottom) > Color.blue(bottom) + 30)
        val top = NoticePop.shade(green, 0f)
        assertTrue("black at the top", maxOf(Color.red(top), Color.green(top), Color.blue(top)) <= 4)
        val head = NoticePop.head(0xFF075E54.toInt())           // a dark app colour is lifted to be read on black
        assertTrue(maxOf(Color.red(head), Color.green(head), Color.blue(head)) >= 235)
        val none = NoticePop.shade(NoticePop.accentOf(0), 595f)
        assertTrue("no colour of its own", abs(Color.red(none) - Color.blue(none)) <= 6)
    }

    @Test fun `the block of icon, sender and message is centred in the part of the back he sees`() {
        // The lenses cover the panel's left 296 px: what he sees runs from 296 to 976, top to bottom.
        assertEquals(636f, NoticePop.CENTRE, 0f)
        for (head in listOf(true, false)) for ((n, size) in NoticePop.SIZES.withIndex()) {
            val at = NoticePop.layout(n + 1, size, head)
            assertEquals("across, ${n + 1} lines", 636f, at.cx, 0f)
            assertEquals("up and down, ${n + 1} lines, head $head", 298f, (at.top + at.bottom) / 2f, 0.01f)
            assertTrue("inside the panel", at.top >= 40f && at.bottom <= 556f)
            if (head) assertTrue("the head line over the message", at.headCy + NoticePop.HEAD_ICON / 2f < at.firstBase - size * 0.72f + 0.01f + 30f)
        }
        // A line as wide as it may be stays clear of the lenses and of the right edge, the same each side.
        assertTrue(NoticePop.CENTRE - NoticePop.ROOM / 2f >= NoticePop.SEEN_LEFT + 30f)
        assertEquals(976f - (NoticePop.CENTRE + NoticePop.ROOM / 2f), NoticePop.CENTRE - NoticePop.ROOM / 2f - NoticePop.SEEN_LEFT, 0.01f)
        // The icon alone (no words at all) stands in the very middle.
        assertEquals(298f, NoticePop.layout(0, NoticePop.SIZES[0], head = true).headCy, 0.01f)
    }

    @Test fun `drawn, its words are centred on the seen part and nothing of them is under the lenses`() {
        val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
        val icon = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (text in listOf("On my way", "Can you pick up bread and milk on the way home? Thanks", "")) {
            bmp.eraseColor(Color.BLACK)
            NoticePop.draw(Canvas(bmp), 976f, 596f, "Alex", text, 0xFF25D366.toInt(), icon)
            var x0 = 976; var x1 = 0; var y0 = 596; var y1 = 0
            for (y in 0 until 596 step 2) for (x in 0 until 976 step 2) {
                val c = bmp.getPixel(x, y)
                val g = bmp.getPixel(4, y)
                // Ink: far brighter than the ground of its own row (the ground is the same across).
                if (maxOf(Color.red(c), Color.green(c), Color.blue(c)) - maxOf(Color.red(g), Color.green(g), Color.blue(g)) > 60) { x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y) }
            }
            assertTrue("something drawn for '$text'", x1 > x0)
            assertTrue("clear of the lenses for '$text': from $x0", x0 >= 326)
            assertEquals("centred across for '$text'", 636f, (x0 + x1) / 2f, 6f)
            assertEquals("centred up and down for '$text'", 298f, (y0 + y1) / 2f, 14f)
        }
    }

    @Test fun `drawn, the hard texts too stay on what he sees - a long sender, a link, an ellipsis, no spaces, emoji, right to left`() {
        // The review's 50: the test above tried three short English messages only.
        val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
        val icon = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val cases = listOf(
            "A sender with a very long name indeed, and a second name after it" to "ok",
            "Alex" to "https://maps.example.org/place/somewhere/far/away/and/further/still?x=1&y=2&z=3",
            "Alex" to "Call me…",
            "Alex" to "字".repeat(120),
            "Alex" to "wait " + "\uD83D\uDE00".repeat(60),
            "Alex" to "שלום מה שלומך היום אני בדרך הביתה עכשיו ואגיע בעוד כמה דקות",
            "Alex" to "IBAN XX00000000000000000000 and the reference 1234567890123456789012345678901234",
        )
        for ((from, text) in cases) {
            bmp.eraseColor(Color.BLACK)
            NoticePop.draw(Canvas(bmp), 976f, 596f, from, text, 0xFF25D366.toInt(), icon)
            var x0 = 976; var x1 = 0; var y0 = 596; var y1 = 0
            for (y in 0 until 596 step 2) for (x in 0 until 976 step 2) {
                val c = bmp.getPixel(x, y)
                val g = bmp.getPixel(4, y)
                if (maxOf(Color.red(c), Color.green(c), Color.blue(c)) - maxOf(Color.red(g), Color.green(g), Color.blue(g)) > 60) { x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y) }
            }
            val what = "'${text.take(24)}' from '${from.take(12)}'"
            assertTrue("something drawn for $what", x1 > x0)
            assertTrue("clear of the lenses for $what: from $x0", x0 >= 326)
            assertTrue("clear of the right edge for $what: to $x1", x1 <= 948)
            assertTrue("inside the panel for $what: $y0 to $y1", y0 >= 30 && y1 <= 566)
        }
    }

    @Test fun `an app with no colour of its own is grey all the way down, not red in the middle`() {
        val none = NoticePop.accentOf(0)
        for (y in listOf(0f, 120f, 200f, 300f, 430f, 550f, 595f)) {
            val c = NoticePop.shade(none, y)
            assertTrue("grey at y $y: ${Integer.toHexString(c)}", maxOf(Color.red(c), Color.green(c), Color.blue(c)) - minOf(Color.red(c), Color.green(c), Color.blue(c)) <= 6)
        }
    }

    @Test fun `a message breaks into lines and ends in an ellipsis when it is too long`() {
        val w = { s: String -> s.length * 10f }
        assertEquals(listOf("Remember"), NoticePop.lines("Remember", 1, 300f, w))
        assertEquals(listOf("see you at", "eight then"), NoticePop.lines("see you at eight then", 2, 100f, w))
        val cut = NoticePop.lines("one two three four five six seven eight nine ten", 2, 100f, w)
        assertEquals(2, cut.size)
        assertTrue(cut.last().endsWith("…") && cut.all { it.length <= 10 })
    }

    // ---- the review's 46: text that ends in an ellipsis, a word wider than the page, emoji at the cut ----

    private val ten = { s: String -> s.length * 10f }

    @Test fun `a message that itself ends in an ellipsis is as large as the same message without it`() {
        val sizes = floatArrayOf(66f, 58f, 50f, 44f)
        val wide = { size: Float, s: String -> s.length * size * 0.5f }
        val (plain, _) = NoticePop.fit("Call me", sizes, 560f, wide)
        val (dots, lines) = NoticePop.fit("Call me…", sizes, 560f, wide)
        assertEquals(66f, plain)
        assertEquals("it was the smallest size before: its own … was read as a cut", 66f, dots)
        assertEquals(listOf("Call me…"), lines)
    }

    @Test fun `the size steps down only as far as the message needs, and a message too long for all is the smallest, cut`() {
        val sizes = floatArrayOf(66f, 58f, 50f, 44f)
        val wide = { size: Float, s: String -> s.length * size * 0.5f }
        val (two, lines) = NoticePop.fit("see you at the station then", sizes, 560f, wide)
        assertEquals(58f, two); assertEquals(2, lines.size)
        val (last, cut) = NoticePop.fit("word ".repeat(80).trim(), sizes, 560f, wide)
        assertEquals(44f, last); assertEquals(4, cut.size); assertTrue(cut.last().endsWith("…"))
    }

    @Test fun `a link is broken across the lines there are, not cut to one`() {
        val link = "https://maps.example.org/place/somewhere/far/away?x=1"
        val got = NoticePop.lines(link, 4, 200f, ten)
        assertTrue("on more than one line: $got", got.size > 1)
        assertEquals("all of it is there", link, got.joinToString(""))
        assertTrue(got.all { it.length <= 20 })
        assertTrue(NoticePop.whole(link, got))
    }

    @Test fun `words and a link together - the words keep their line, the link goes on below`() {
        val got = NoticePop.lines("look at https://maps.example.org/place/somewhere now", 4, 200f, ten)
        assertEquals("look at", got.first())
        assertEquals("lookathttps://maps.example.org/place/somewherenow", got.joinToString("").replace(" ", ""))
    }

    @Test fun `text with no spaces at all fills its lines, and what is over is cut at the end of the last`() {
        val got = NoticePop.lines("字".repeat(70), 3, 200f, ten)
        assertEquals(3, got.size)
        assertEquals(20, got[0].length); assertEquals(20, got[1].length)
        assertTrue(got[2].endsWith("…") && got[2].length <= 20)
        assertFalse(NoticePop.whole("字".repeat(70), got))
    }

    @Test fun `an emoji is never cut in half, at any width, in a cut line or a broken one`() {
        val face = "\uD83D\uDE00"; val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"; val heart = "\u2764\uFE0F"
        for (text in listOf("ok " + face.repeat(30), "abc" + face.repeat(30), family.repeat(8), "x" + heart.repeat(25), face.repeat(40))) {
            for (room in 15..260 step 5) for (most in 1..4) {
                for (line in NoticePop.lines(text, most, room.toFloat(), ten)) {
                    val body = line.removeSuffix("…")
                    for (i in body.indices) {
                        if (Character.isHighSurrogate(body[i])) assertTrue("half an emoji at the end of '$line' (room $room)", i + 1 < body.length && Character.isLowSurrogate(body[i + 1]))
                        if (Character.isLowSurrogate(body[i])) assertTrue("half an emoji at the start of '$line' (room $room)", i > 0 && Character.isHighSurrogate(body[i - 1]))
                    }
                    assertFalse("a joiner left hanging in '$line' (room $room)", body.endsWith("\u200D") || body.startsWith("\u200D") || body.startsWith("\uFE0F"))
                }
            }
        }
    }

    @Test fun `breaking never loops and never loses a character while there is room`() {
        for (room in listOf(1f, 5f, 10f, 15f, 25f)) {
            val got = NoticePop.lines("abcdefghij \uD83D\uDE00\uD83D\uDE00 klm", 40, room, ten)
            assertEquals("room $room", "abcdefghij\uD83D\uDE00\uD83D\uDE00klm", got.joinToString("").replace(" ", "").replace("…", ""))
        }
    }

    @Test fun `an icon's colour is its strongest, and a grey icon has none`() {
        val green = IntArray(100) { if (it < 70) 0xFF25D366.toInt() else 0xFFFFFFFF.toInt() }
        val c = BackNotices.dominant(green)
        assertTrue(Color.green(c) > 180 && Color.red(c) < 80)
        assertEquals(0, BackNotices.dominant(IntArray(100) { 0xFF808080.toInt() }))
        assertEquals(0, BackNotices.dominant(IntArray(100) { 0 }))
    }

    @Test fun `the page is drawn, for a look without the phone`() {
        File("build").mkdirs()
        val icon = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF25D366.toInt()) }
        for ((name, args) in listOf(
            "pin" to Triple("Pinned message", "Remember", NoticePop.PIN),
            "whatsapp" to Triple("Alex", "Are you coming tonight? We are leaving at eight", 0xFF25D366.toInt()),
            "long" to Triple("Robin · Film club", "I did tell you we were meeting at eight, where are you, we have been waiting outside the cinema for half an hour by now", 0xFF2AABEE.toInt()),
        )) {
            val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
            NoticePop.draw(Canvas(bmp), 976f, 596f, args.first, args.second, args.third, icon)
            File("build/notice-pop-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            near("$name: the corner is the ground's", NoticePop.shade(NoticePop.accentOf(args.third), 2f) and 0xFFFFFF, bmp.getPixel(5, 2) and 0xFFFFFF, 4)
        }
    }
}
