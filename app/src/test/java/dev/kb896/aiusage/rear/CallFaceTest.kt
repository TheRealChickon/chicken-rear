package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The call page: Xiaomi's two buttons, what each does ringing and answered, and the picture. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CallFaceTest {
    private val now = 1_800_000_000_000L
    private val ringing = CallFace.Model("k", "com.android.incallui", "Alex", "Incoming call", ringing = true, since = 0L, speaker = false, headset = false)
    private val talking = ringing.copy(ringing = false, since = now - 83_000L, what = "")

    @Test fun `left declines and right answers while it rings, then left is the speaker and right ends it`() {
        assertEquals(CallFace.Side.LEFT, CallFace.side(469f, 444f))
        assertEquals(CallFace.Side.RIGHT, CallFace.side(787f, 444f))
        assertNull("between the two", CallFace.side(628f, 444f))
        assertNull("on the name", CallFace.side(633f, 180f))
        assertNull("behind the lenses", CallFace.side(150f, 444f))
        assertEquals(CallFace.Act.DECLINE, CallFace.act(CallFace.Side.LEFT, ringing = true))
        assertEquals(CallFace.Act.ANSWER, CallFace.act(CallFace.Side.RIGHT, ringing = true))
        assertEquals(CallFace.Act.SPEAKER, CallFace.act(CallFace.Side.LEFT, ringing = false))
        assertEquals(CallFace.Act.END, CallFace.act(CallFace.Side.RIGHT, ringing = false))
    }

    @Test fun `under the name is what is going on, then the time it has run`() {
        assertEquals("Incoming call", CallFace.words(ringing, now))
        assertEquals("Incoming call", CallFace.words(ringing.copy(what = ""), now))
        assertEquals("01:23", CallFace.words(talking, now))
        assertEquals("1:02:03", CallFace.words(talking.copy(since = now - 3_723_000L), now))
    }

    @Test fun `another app's call is worked by its notification's own buttons, by what they say`() {
        val words = listOf("Hang Up", "Speaker", "Mute")
        assertEquals(0, CallFace.actionFor(CallFace.Act.END, words))
        assertEquals(1, CallFace.actionFor(CallFace.Act.SPEAKER, words))
        assertEquals(-1, CallFace.actionFor(CallFace.Act.ANSWER, words))
        assertEquals(1, CallFace.actionFor(CallFace.Act.ANSWER, listOf("Decline", "Answer")))
        assertEquals(0, CallFace.actionFor(CallFace.Act.DECLINE, listOf("Decline", "Answer")))
    }

    @Test fun `the page is drawn, full bleed, in Xiaomi's greens`() {
        File("build").mkdirs()
        // Xiaomi's own glyphs where XIAOMI_SUBSCREEN_APK names a copy of its package pulled from your own phone; bare
        // buttons otherwise.
        val apk = System.getenv("XIAOMI_SUBSCREEN_APK")?.let { File(it) }?.takeIf { it.isFile }?.path
        val art = XiaomiArt.load("phone", CallFace.ART, apk)
        val pages = mapOf("ringing" to ringing, "talking" to talking, "speaker" to talking.copy(speaker = true), "earphones" to ringing.copy(headset = true))
        for ((name, m) in pages) {
            val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
            CallFace.draw(Canvas(bmp), 976f, 596f, m, now, art, ringAt = now - 700L)
            File("build/call-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals("$name: the ground's top", 0x000E04, bmp.getPixel(20, 0) and 0xFFFFFF)
            assertEquals("$name: opaque in the far corner", 255, Color.alpha(bmp.getPixel(970, 590)))
            // The red button: on the left while it rings, on the right once answered.
            val red = bmp.getPixel(if (m.ringing) 400 else 718, 444)
            assertTrue("$name: the red button", Color.red(red) > 220 && Color.green(red) < 90)
        }
    }
}
