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

/** The timer page: its figures, the water's height, its two buttons, and the picture. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TimerFaceTest {
    private val now = 1_800_000_000_000L
    private val running = TimerFace.Model("k", "com.android.deskclock", "Timer", endAt = now + 271_400L, left = 0L, total = 600_000L, paused = false, over = false)
    private val paused = running.copy(paused = true, left = 271_400L)

    @Test fun `the figures show what is left, rounded up, and stand still when paused`() {
        assertEquals(272L, TimerFace.seconds(running, now))
        assertEquals(0L, TimerFace.seconds(running, now + 900_000L))
        assertEquals(272L, TimerFace.seconds(paused, now + 50_000L))
        val watch = TimerFace.Model("k", "c", "Stopwatch", endAt = now - 65_000L, left = 0L, total = 0L, paused = false, over = false, up = true)
        assertEquals("a stopwatch counts up", 65L, TimerFace.seconds(watch, now))
    }

    @Test fun `the water sinks as the time runs out`() {
        assertEquals(0f, TimerFace.sunk(running.copy(endAt = now + 600_000L), now), 0.001f)
        assertEquals(0.5f, TimerFace.sunk(running.copy(endAt = now + 300_000L), now), 0.001f)
        assertEquals(1f, TimerFace.sunk(running, now + 900_000L), 0.001f)
    }

    @Test fun `left pauses or goes on, right closes, and one that has run out has one button in the middle`() {
        assertEquals(TimerFace.Side.MAIN, TimerFace.side(470f, 453f, over = false))
        assertEquals(TimerFace.Side.CLOSE, TimerFace.side(790f, 453f, over = false))
        assertNull(TimerFace.side(630f, 453f, over = false))
        assertEquals(TimerFace.Side.CLOSE, TimerFace.side(630f, 453f, over = true))
        assertNull(TimerFace.side(790f, 453f, over = true))
        assertEquals(TimerFace.Act.PAUSE, TimerFace.act(TimerFace.Side.MAIN, running))
        assertEquals(TimerFace.Act.RESUME, TimerFace.act(TimerFace.Side.MAIN, paused))
        assertEquals(TimerFace.Act.CANCEL, TimerFace.act(TimerFace.Side.CLOSE, running))
        assertEquals(TimerFace.Act.DISMISS, TimerFace.act(TimerFace.Side.CLOSE, running.copy(over = true)))
        assertEquals("another clock's own button", 0, TimerFace.actionFor(TimerFace.Act.CANCEL, listOf("Stop")))
        assertEquals(-1, TimerFace.actionFor(TimerFace.Act.PAUSE, listOf("Stop")))
    }

    @Test fun `the page is drawn, full bleed`() {
        File("build").mkdirs()
        val apk = System.getenv("XIAOMI_SUBSCREEN_APK")?.let { File(it) }?.takeIf { it.isFile }?.path
        val art = XiaomiArt.load("timer", TimerFace.ART, apk)
        val pages = mapOf("running" to running, "paused" to paused, "hours" to running.copy(endAt = now + 4_000_000L, total = 7_200_000L), "over" to running.copy(over = true))
        for ((name, m) in pages) {
            val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
            TimerFace.draw(Canvas(bmp), 976f, 596f, m, now, art)
            File("build/timer-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals("$name: opaque behind the lenses", 255, Color.alpha(bmp.getPixel(20, 20)))
            assertEquals("$name: and in the far corner", 255, Color.alpha(bmp.getPixel(970, 590)))
            assertTrue("$name: blue, not grey", Color.blue(bmp.getPixel(900, 300)) > Color.red(bmp.getPixel(900, 300)) + 15)
        }
    }
}
