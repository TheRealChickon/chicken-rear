package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A video on the "what is playing" page: its picture keeps its own wide shape and the buttons stand higher, as one
 * block with the picture and the lines; music keeps Xiaomi's page. Fixtures are made-up pictures and words.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MusicLayoutTest {
    private val glow = floatArrayOf(0.28f, 0.92f, 0.52f, 1.05f, 0.78f, 0.86f)

    @Test fun `which page - by the picture's own shape, never by which app plays`() {
        assertSame(MusicLayout.VIDEO, MusicLayout.of(1280, 720))
        assertSame(MusicLayout.VIDEO, MusicLayout.of(480, 360))            // an old 4:3 thumbnail is wide enough
        assertSame(MusicLayout.MUSIC, MusicLayout.of(640, 640))
        assertSame(MusicLayout.MUSIC, MusicLayout.of(600, 520))            // a nearly square cover is a cover
        assertSame(MusicLayout.MUSIC, MusicLayout.of(720, 1280))           // a tall picture is not a video's page
        assertSame(MusicLayout.MUSIC, MusicLayout.of(0, 0))
        assertSame("no picture: Xiaomi's page, with the player's icon", MusicLayout.MUSIC, MusicLayout.of(null))
    }

    @Test fun `music is Xiaomi's page, unchanged`() {
        val m = MusicLayout.MUSIC
        assertEquals(533.65f, m.btnCy, 0.1f)
        assertEquals(MusicFace.ART, m.artW, 0f); assertEquals(MusicFace.ART, m.artH, 0f)
        assertEquals(MusicFace.TITLE_CY, m.titleCy, 0f); assertEquals(MusicFace.ARTIST_CY, m.artistCy, 0f)
    }

    @Test fun `a video's block - nothing overlaps, the buttons are lifted, and the margins over and under it are alike`() {
        val v = MusicLayout.VIDEO
        // What each thing takes up (the lines by their type's size, the buttons' glyphs the middle half of their square).
        val pictureBottom = v.artY + v.artH
        val titleTop = v.titleCy - MusicFace.TITLE_SIZE / 2f; val titleBottom = v.titleCy + MusicFace.TITLE_SIZE / 2f
        val artistTop = v.artistCy - MusicFace.ARTIST_SIZE / 2f; val artistBottom = v.artistCy + MusicFace.ARTIST_SIZE / 2f
        val glyphTop = v.btnCy - MusicFace.BTN / 4f; val glyphBottom = v.btnCy + MusicFace.BTN / 4f
        assertTrue("the picture ends before the title begins", pictureBottom + 20f <= titleTop)
        assertTrue("the title before the channel", titleBottom <= artistTop)
        assertTrue("the channel before the buttons", artistBottom + 20f <= glyphTop)
        assertTrue("the buttons are well inside the page", glyphBottom <= 596f - 50f)
        // Lifted: 48 units higher than on Xiaomi's page, where the glyphs end 27 units from the edge.
        assertEquals(486.15f, v.btnCy, 0.1f)
        assertTrue(MusicLayout.MUSIC.btnCy - v.btnCy >= 40f)
        // One block: about as much under the buttons as over the picture.
        assertTrue("over ${v.artY}, under ${596f - glyphBottom}", kotlin.math.abs(v.artY - (596f - glyphBottom)) <= 20f)
        // The picture is 16:9, right of the lenses, inside the page.
        assertEquals(16f / 9f, v.artW / v.artH, 0.01f)
        assertTrue(v.artX >= 296f && v.artX + v.artW <= 976f)
        // With no channel the title closes the hole between the picture and the buttons.
        assertTrue(v.titleAloneCy > v.titleCy && v.titleAloneCy + MusicFace.TITLE_SIZE / 2f + 20f <= glyphTop)
    }

    @Test fun `the buttons' touch areas moved with them`() {
        val v = MusicLayout.VIDEO; val m = MusicLayout.MUSIC
        for (b in MusicFace.Button.entries) {
            val x = MusicFace.centre(b)
            assertEquals("on the button, where it is drawn for a video", b, MusicFace.button(x, v.btnCy, v))
            assertEquals(b, MusicFace.button(x, v.btnCy - 60f, v)); assertEquals(b, MusicFace.button(x, v.btnCy + 60f, v))
            // Where the music page has its buttons' lower half, a video's page has nothing.
            assertNull("under the lifted buttons", MusicFace.button(x, 590f, v))
            assertNull("over them, on the channel's line", MusicFace.button(x, v.artistCy, v))
            // Music: as it was, down to the page's edge.
            assertEquals(b, MusicFace.button(x, 590f, m)); assertEquals(b, MusicFace.button(x, 530f)); assertNull(MusicFace.button(x, 440f, m))
        }
        assertNull("behind the lenses", MusicFace.button(150f, v.btnCy, v))
    }

    @Test fun `drawn - a wide picture is shown wide, and the buttons are where the layout says`() {
        File("build").mkdirs()
        // Made-up pictures: one colour each, for this test only.
        val wide = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF2E6FD0.toInt()) }
        val square = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF1DB954.toInt()) }
        fun page(cover: Bitmap, artist: String): Bitmap {
            val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
            MusicFace.draw(Canvas(bmp), 976f, 596f, "A made-up video title", artist, true, cover, MusicFace.palette(MusicFace.seed(cover)), glow)
            return bmp
        }
        fun white(b: Bitmap, x: Int, y0: Int, y1: Int) = (y0..y1).count { y -> b.getPixel(x, y).let { Color.red(it) > 235 && Color.green(it) > 235 && Color.blue(it) > 235 } }
        val video = page(wide, "A made-up channel")
        File("build/music-video.png").outputStream().use { video.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val v = MusicLayout.VIDEO
        // The picture's colour across the whole 16:9 place, and not beyond it.
        assertEquals(0x2E6FD0, video.getPixel((v.artX + 20).toInt(), (v.artY + v.artH / 2).toInt()) and 0xFFFFFF)
        assertEquals(0x2E6FD0, video.getPixel((v.artX + v.artW - 20).toInt(), (v.artY + v.artH / 2).toInt()) and 0xFFFFFF)
        assertTrue(video.getPixel((v.artX + v.artW + 24).toInt(), (v.artY + v.artH / 2).toInt()) and 0xFFFFFF != 0x2E6FD0)
        // The pause glyph's white is about the lifted centre, and none of it is where the music page has its buttons' foot.
        val mid = MusicFace.centre(MusicFace.Button.PLAY_PAUSE).toInt()
        val bars = (mid - 30..mid + 30).maxOf { x -> white(video, x, (v.btnCy - 40).toInt(), (v.btnCy + 40).toInt()) }
        assertTrue("the middle button is drawn about y ${v.btnCy} ($bars white pixels in a column)", bars >= 30)
        assertEquals(0, (mid - 40..mid + 40).sumOf { x -> white(video, x, 545, 595) })
        // Music with a square cover: the buttons where Xiaomi has them.
        val music = page(square, "A made-up artist")
        assertTrue((mid - 30..mid + 30).maxOf { x -> white(music, x, 500, 570) } >= 30)
        assertEquals(0x1DB954, music.getPixel(458, 161) and 0xFFFFFF)
        // A video with no channel: the title lower, in the hole.
        val alone = page(wide, "")
        File("build/music-video-no-channel.png").outputStream().use { alone.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val rows = (270..400).filter { y -> (370..700).any { x -> alone.getPixel(x, y).let { Color.red(it) > 235 && Color.blue(it) > 235 } } }
        assertTrue("the title's rows are about ${v.titleAloneCy}: ${rows.firstOrNull()}..${rows.lastOrNull()}", rows.isNotEmpty() && kotlin.math.abs((rows.first() + rows.last()) / 2f - v.titleAloneCy) <= 14f)
    }
}
