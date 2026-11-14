package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.kb896.aiusage.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The notifications' pages drawn for real (Robolectric's native graphics) at the rear's size. A page is its
 * pop-up's own picture; the line of logos stands at the right edge, clear of the words; a button's word is where
 * a finger finds it. Pictures kept in build/ for a look without the phone (not for showing as the real page: the
 * icons here are plain squares, stand-ins a test may have and the back may not).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NoticePagesRenderTest {
    private val rear = RearGeometry(976, 596, 296, 97, 440)
    private val items = listOf(
        NoticeShade.Item("a", "com.whatsapp", "WhatsApp", "Alex", "Are you coming tonight? We leave at eight", actions = listOf("Reply", "Mark as read"), color = 0xFF25D366.toInt()),
        NoticeShade.Item("b", "org.telegram.messenger", "Telegram", "Kim", "Can you pick up bread and milk on the way home? Thanks", color = 0xFF2AABEE.toInt()),
        NoticeShade.Item("c", "com.instagram.android", "Instagram", "Instagram", "Someone liked your photo", color = 0xFFE1306C.toInt()),
    )
    private val icons = mapOf(
        "com.whatsapp" to square(0xFF25D366.toInt()), "org.telegram.messenger" to square(0xFF2AABEE.toInt()), "com.instagram.android" to square(0xFFE1306C.toInt()),
    )
    private fun square(color: Int) = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun pages(logos: Boolean = false) = NoticePages(rear.cutoutUnits).apply {
        logosFirst = logos
        items(this@NoticePagesRenderTest.items)
        beginPull(10f, 30f, 0); movePull(500f, 200); endPull(500f, 200)
    }

    private fun render(scene: NoticePages.Scene, progress: Float = 1f, name: String? = null): Bitmap {
        val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        c.scale(rear.fitX, rear.fitY)
        NoticePagesArt().draw(c, rear, scene, progress, words = true, icon = { icons[it] })
        if (name != null) File("build/notice-pages").apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private fun popUp(item: NoticeShade.Item): Bitmap {
        val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
        NoticePop.draw(Canvas(bmp), 976f, 596f, item.title, item.text, item.color, icons[item.pkg])
        return bmp
    }

    @Test fun `a page is its pop-up's own picture, but for the logos' line and the buttons' words`() {
        val page = render(pages().scene(1000), name = "page-1")
        val pop = popUp(items[0])
        var differ = 0
        // Left of the line, above the words: every pixel the pop-up's.
        for (y in 0 until 460 step 2) for (x in 0 until 925 step 2) {
            val p = page.getPixel(x, y); val q = pop.getPixel(x, y)
            if (kotlin.math.abs(Color.red(p) - Color.red(q)) > 3 || kotlin.math.abs(Color.green(p) - Color.green(q)) > 3 || kotlin.math.abs(Color.blue(p) - Color.blue(q)) > 3) differ++
        }
        assertEquals(0, differ)
    }

    @Test fun `the line of logos stands at the right edge, the one on top at full strength`() {
        val page = render(pages().scene(1000))
        val pop = popUp(items[0])
        // Where the two differ right of the words and above the foot is the line: three logos, one over another.
        var minX = 976; var maxX = 0; var minY = 596; var maxY = 0
        for (y in 0 until 460) for (x in 925 until 976) if (page.getPixel(x, y) != pop.getPixel(x, y)) {
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        assertTrue("the line runs from x $minX to $maxX", minX >= 930 && maxX <= 970)
        assertTrue("and from y $minY to $maxY, about the middle", minY in 230..270 && maxY in 320..360 && kotlin.math.abs((minY + maxY) / 2 - 298) <= 6)
        // The first (on top) is whole; the two under it are dimmed towards the ground.
        val top = page.getPixel(950, 298 - 42)
        val second = page.getPixel(950, 298)
        assertEquals(0x25D366, top and 0xFFFFFF)
        assertTrue(Color.blue(second) < 0xEE * 0.6f && Color.blue(second) > 0xEE * 0.2f)
    }

    @Test fun `a button's word is drawn where a finger finds it`() {
        val with = render(pages().scene(1000))
        val bare = popUp(items[0])
        var minX = 976; var maxX = 0; var minY = 596; var maxY = 0
        for (y in 460 until 596) for (x in 300 until 925) {
            val p = with.getPixel(x, y); val q = bare.getPixel(x, y)
            if (kotlin.math.abs(Color.green(p) - Color.green(q)) > 40) { minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
        }
        assertTrue("words from x $minX to $maxX, y $minY to $maxY", maxX > minX)
        val left = rear.cutoutUnits
        // Both ends of what is drawn are on a button, the first word's on the first and the second's on the second.
        assertEquals(0, NoticePages.actionAt(minX / rear.fitX, minY / rear.fitY, 2, left))
        assertEquals(1, NoticePages.actionAt(maxX / rear.fitX, maxY / rear.fitY, 2, left))
        // And they stand about the middle of what he sees.
        assertTrue(kotlin.math.abs((minX + maxX) / 2f - 636f) < 40f)
        assertTrue(maxY < 560)
    }

    @Test fun `pictures of the rest, and nothing drawn outside the panel's size`() {
        val s = pages()
        s.fingerDown(600f, 400f, 1000); s.fingerMove(600f, 220f, 1200)
        render(s.scene(1200), name = "page-1-going-up-over-page-2")
        s.fingerUp(600f, 220f, 1200)
        render(s.scene(2000), name = "page-2")
        s.fingerDown(700f, 300f, 2000); s.fingerMove(480f, 300f, 2100)
        render(s.scene(2100), name = "page-2-thrown-sideways-over-page-3")
        render(pages().scene(1000), progress = 0.5f, name = "page-1-half-pulled")
        val logos = render(pages(logos = true).scene(1000), name = "logos-first")
        // The logos' page: three logos in a row about the middle of what he sees, no line at the edge.
        assertEquals(0x2AABEE, logos.getPixel(636, 298) and 0xFFFFFF)
        assertTrue(Color.red(logos.getPixel(950, 298)) < 60)
        val none = NoticePages(rear.cutoutUnits).apply { beginPull(10f, 30f, 0); movePull(500f, 200); endPull(500f, 200) }
        render(none.scene(1000), name = "none-waiting")
    }
}
