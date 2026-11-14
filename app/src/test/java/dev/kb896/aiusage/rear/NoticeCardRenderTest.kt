package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.PanelTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The notification card drawn for real (Robolectric's native graphics), at the rear's size: three
 * rows as the list has them. Kept as a picture in build/ for a look without the phone, and
 * checked where it matters to a finger: a button is drawn where NoticeShade.actionAt finds it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NoticeCardRenderTest {
    @Test fun `a card's buttons are drawn where a finger finds them`() {
        val fit = 976f / Panel.WIDTH
        val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        c.scale(fit, 596f / Panel.HEIGHT)
        val icon = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF25D366.toInt()) }
        val card = NoticeCard({ null }, { 97f / fit }, { icon })
        val look = NoticeCard.Look(PanelTheme.GLASS, Color.WHITE, 0xFF1D1F21.toInt(), 0)
        val left = 296f / fit + 10f + NoticeShade.MARGIN
        val right = NoticeShade.RIGHT
        val now = System.currentTimeMillis()
        val items = listOf(
            NoticeShade.Item("a", "com.whatsapp", "WhatsApp", "Alex", "Are you coming tonight? We are leaving at eight, bring the charger if you can find it", now, listOf("Mark as read", "Mute")),
            NoticeShade.Item("b", "com.instagram.android", "Instagram", "nightcity.posts", "liked your photo and left a comment on it that is long enough to need a second line here", now - 3_600_000),
            NoticeShade.Item("c", "com.google.android.gm", "Gmail", "Your parcel is on its way", "Arriving tomorrow", now - 2 * 86_400_000L),
        )
        var top = NoticeShade.TOP
        val tops = ArrayList<Float>()
        for (item in items) {
            c.save(); c.translate(0f, top)
            card.draw(c, RectF(left, 0f, right, NoticeShade.ROW_H), item, 1f, square = false, screenTop = top, look = look)
            c.restore()
            tops += top
            top += NoticeShade.ROW_H + NoticeShade.GAP
        }
        File("build").mkdirs()
        File("build/notice-cards.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // The first row's first button: lit inside the pill, where actionAt says button 0 is.
        val x = NoticeShade.TEXT_X + 12f
        val y = NoticeShade.PILL_TOP + NoticeShade.PILL_H / 2f
        assertTrue(NoticeShade.actionAt(x, y, 2) == 0)
        val inPill = bmp.getPixel(((left + x) * fit).toInt(), ((tops[0] + y) * 596f / Panel.HEIGHT).toInt())
        // Past the second pill: the card's own ground (the text, and so the pills, start at the card's edge now).
        val past = NoticeShade.TEXT_X + 2 * (NoticeShade.PILL_W + NoticeShade.PILL_GAP) + 30f
        val beside = bmp.getPixel(((left + past) * fit).toInt(), ((tops[0] + y) * 596f / Panel.HEIGHT).toInt())
        assertTrue("the pill is lighter than the card beside it", Color.green(inPill) > Color.green(beside) + 10)
    }
}
