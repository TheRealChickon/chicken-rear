package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import dev.kb896.aiusage.data.Account
import dev.kb896.aiusage.data.AccountStatus
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.Health
import dev.kb896.aiusage.data.MascotMood
import dev.kb896.aiusage.data.UsageSnapshot
import dev.kb896.aiusage.data.UsageWindow
import dev.kb896.aiusage.data.WindowKind
import dev.kb896.aiusage.rear.dots.DotsActor
import dev.kb896.aiusage.rear.dots.DotsFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Claude mascot stands in Claude's vessels only; ChatGPT's vessel has OpenAI's character as its renderer draws it (here a test's
 * picture of one colour, standing in for the renderer's frames ONLY in this test), never the Claude mascot; with no
 * picture to be had, nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class UsageFaceDotsTest {
    private val now = 1_800_000_000_000L
    private fun status(a: Account, five: Float, week: Float, mood: String = MascotMood.WALK) = AccountStatus(
        a, Health.OK,
        UsageSnapshot(listOf(UsageWindow(WindowKind.SESSION, "5 h", five, now + 3_600_000L), UsageWindow(WindowKind.WEEKLY_ALL, "Week", week, now + 86_400_000L)), now),
        null, mood,
    )
    /** A colour nothing else on the page has: the test's stand-in for the renderer's picture. */
    private val DOT = 0xFF13C2F0.toInt()

    private class Feed(private val pic: Bitmap?) : DotsFeed {
        var signatures = 0
        var calm: Boolean? = null
        override fun picture(): Bitmap? = pic
        override fun signature() { signatures++ }
        override fun calm(on: Boolean) { calm = on }
    }
    private fun frame(): Bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
        // Transparent round a disc, as the renderer's picture is round its figure.
        val c = Canvas(this)
        c.drawCircle(128f, 128f, 100f, android.graphics.Paint().apply { color = DOT })
    }

    private fun draw(m: UsageFace.Model, feed: DotsFeed?, at: Long = 5_000L): Bitmap {
        val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
        UsageFace.draw(Canvas(bmp), 976f, 596f, m, now + at, sinceShown = at, dots = feed)
        return bmp
    }

    private fun count(bmp: Bitmap, colour: Int, inside: (Int, Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until 596 step 2) for (x in 0 until 976 step 2) if (inside(x, y) && bmp.getPixel(x, y) and 0xFFFFFF == colour and 0xFFFFFF) n++
        return n
    }

    @Test fun `Claude's vessel keeps the Claude mascot, ChatGPT's never has it, and has OpenAI's character`() {
        File("build").mkdirs()
        for (five in listOf(false, true)) {
            val m = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL, 0.3f, 0.5f), status(Account.CHATGPT, 0.37f, 0.62f))), now, fiveHours = five)
            val tanks = UsageFace.tanks(m)
            val mid = tanks.first { it.account == 1 }.vessel.left
            for (step in 0 until 40) {
                val bmp = draw(m, Feed(frame()), at = step * 300L)
                if (step == 10) File("build/usage-dots-${if (five) "five" else "week"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue("five $five, $step: Claude's mascot in Claude's half", count(bmp, OriginalUsage.CLAUDE) { x, _ -> x < mid } > 40)
                assertEquals("five $five, $step: no Claude mascot in ChatGPT's half", 0, count(bmp, OriginalUsage.CLAUDE) { x, _ -> x >= mid })
                assertTrue("five $five, $step: OpenAI's character in ChatGPT's half", count(bmp, DOT) { x, _ -> x >= mid } > 200)
                assertEquals("five $five, $step: and not in Claude's", 0, count(bmp, DOT) { x, _ -> x < mid })
                // Never in a vessel's head (where its time is), never outside its vessel.
                for (t in tanks) assertEquals("five $five, $step: the character in a reading's room", 0, count(bmp, DOT) { x, y -> t.reading.contains(x.toFloat(), y.toFloat()) })
                val chat = tanks.filter { it.account == 1 }
                assertEquals("five $five, $step: the character outside ChatGPT's vessels", 0, count(bmp, DOT) { x, y -> chat.none { it.vessel.contains(x.toFloat(), y.toFloat()) } })
            }
        }
    }

    @Test fun `with nothing to be had from the ChatGPT app, its vessel has nothing in its place`() {
        val m = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL, 0.3f, 0.5f), status(Account.CHATGPT, 0.37f, 0.62f))), now)
        val mid = UsageFace.tanks(m).first { it.account == 1 }.vessel.left
        for (feed in listOf(null, Feed(null))) {
            val bmp = draw(m, feed)
            assertEquals(0, count(bmp, OriginalUsage.CLAUDE) { x, _ -> x >= mid })
            assertEquals(0, count(bmp, DOT) { _, _ -> true })
            // ChatGPT's half below its head is its fluid and the black only: nothing of a mascot of any colour.
            val t = UsageFace.tanks(m).first { it.account == 1 }
            var odd = 0
            for (y in (t.reading.bottom.toInt() + 4) until (t.vessel.bottom.toInt() - 4) step 3) for (x in (t.vessel.left.toInt() + 6) until (t.vessel.right.toInt() - 6) step 3) {
                val p = bmp.getPixel(x, y)
                val r = android.graphics.Color.red(p); val g = android.graphics.Color.green(p); val b = android.graphics.Color.blue(p)
                // The fluid is the neutral (near grey); the ground black: a saturated pixel would be a figure.
                if (maxOf(r, g, b) - minOf(r, g, b) > 60) odd++
            }
            assertEquals("nothing coloured in ChatGPT's vessel", 0, odd)
        }
    }

    @Test fun `ChatGPT alone has the whole panel, its character in it, and no Claude mascot`() {
        val m = UsageFace.model(DashboardState(now, listOf(status(Account.CHATGPT, 0.37f, 0.62f))), now)
        assertEquals(1, UsageFace.tanks(m).size)
        assertEquals(UsageFace.RIGHT - UsageFace.LEFT, UsageFace.tanks(m)[0].vessel.width(), 0.5f)
        val bmp = draw(m, Feed(frame()))
        assertEquals(0, count(bmp, OriginalUsage.CLAUDE) { _, _ -> true })
        assertTrue(count(bmp, DOT) { _, _ -> true } > 400)
    }

    @Test fun `its box stays inside its vessel and under its head, however full`() {
        for (m in listOf(
            UsageFace.model(DashboardState(now, listOf(status(Account.CHATGPT, 0.37f, 0.62f))), now),
            UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL, 0.3f, 0.5f), status(Account.CHATGPT, 0.37f, 0.62f))), now),
            UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL, 0.3f, 0.5f), status(Account.CHATGPT, 0.37f, 0.62f))), now, fiveHours = true),
        )) for (t in UsageFace.tanks(m)) for (level in 0..20) {
            val surface = t.vessel.bottom - level / 20f * t.vessel.height()
            val box = UsageFace.dotBox(t, surface)
            assertTrue("inside its vessel at $level: $box in ${t.vessel}", RectF(t.vessel).contains(box))
            assertTrue("under its head at $level", box.top >= t.reading.bottom)
            assertTrue("square", kotlin.math.abs(box.width() - box.height()) < 0.5f)
        }
    }

    @Test fun `the page tells the character when to act - as it opens, and calm while spent`() {
        val m = UsageFace.model(DashboardState(now, listOf(status(Account.CHATGPT, 0.37f, 0.62f))), now)
        val feed = Feed(frame())
        // A fresh opening: within the first second, its signature move once.
        for (t in 0L..1_000L step 50L) draw(m, feed, at = t)
        assertEquals(1, feed.signatures)
        assertEquals(false, feed.calm)
        val spent = UsageFace.model(DashboardState(now, listOf(status(Account.CHATGPT, 0.37f, 1f, mood = MascotMood.SLEEP))), now)
        val calm = Feed(frame())
        for (t in 0L..30_000L step 250L) draw(spent, calm, at = 100_000L + t)
        assertEquals(0, calm.signatures)
        assertEquals(true, calm.calm)
        assertTrue(DotsActor.spent(MascotMood.SLEEP))
    }
}
