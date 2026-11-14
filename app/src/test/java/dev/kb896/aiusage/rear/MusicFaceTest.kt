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

/** The music page: Xiaomi's measures, the colours taken from the cover, the glows' wandering. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MusicFaceTest {

    @Test fun `the three buttons stand along the bottom where Xiaomi's do`() {
        assertEquals(402.75f, MusicFace.centre(MusicFace.Button.PREVIOUS), 0.2f)
        assertEquals(626.35f, MusicFace.centre(MusicFace.Button.PLAY_PAUSE), 0.2f)
        assertEquals(858.65f, MusicFace.centre(MusicFace.Button.NEXT), 0.2f)
        assertEquals(MusicFace.Button.PREVIOUS, MusicFace.button(400f, 530f))
        assertEquals(MusicFace.Button.PLAY_PAUSE, MusicFace.button(630f, 500f))
        assertEquals(MusicFace.Button.NEXT, MusicFace.button(880f, 590f))
        assertNull("between two", MusicFace.button(515f, 530f))
        assertNull("on the title", MusicFace.button(630f, 350f))
        assertNull("behind the lenses", MusicFace.button(150f, 530f))
    }

    @Test fun `the page's colours are the cover's hue at Xiaomi's tones`() {
        val green = 0xFF1DB954.toInt()
        val p = MusicFace.palette(green)
        assertEquals("the ground: tone 4", 4f, MusicFace.lch(p.ground)[0], 1.2f)
        assertEquals("the first glow: tone 20", 20f, MusicFace.lch(p.glow1)[0], 1.2f)
        assertEquals("the second: tone 60", 60f, MusicFace.lch(p.glow2)[0], 1.2f)
        assertEquals("the third: tone 70", 70f, MusicFace.lch(p.glow3)[0], 1.2f)
        val hue = MusicFace.lch(green)[2]
        assertEquals("the cover's own hue", hue, MusicFace.lch(p.glow2)[2], 6f)
        assertEquals("and its neighbour's for the third", (hue + 60f) % 360f, MusicFace.lch(p.glow3)[2], 8f)
        // A cover with no colour: greys, never a made-up hue.
        val grey = MusicFace.palette(0)
        for (c in listOf(grey.ground, grey.glow1, grey.glow2, grey.glow3)) {
            assertTrue(maxOf(Color.red(c), Color.green(c), Color.blue(c)) - minOf(Color.red(c), Color.green(c), Color.blue(c)) <= 2)
        }
    }

    // -- the glows' motion: up and down, never across, and never a cut --

    /** A minute of the page at 16 ms a picture; [each] sees every picture's glows and how far each moved since the last. */
    private fun run(glow: MusicFace.Glow, fromMs: Long, forMs: Long, stepMs: Long = 16L, playing: (Long) -> Boolean = { true }, each: (Long, FloatArray, Float) -> Unit) {
        var prev: FloatArray? = null
        var t = fromMs
        while (t <= fromMs + forMs) {
            val at = glow.at(t, playing(t)).copyOf()
            val moved = prev?.let { p -> (0..5).maxOf { kotlin.math.abs(at[it] - p[it]) } } ?: 0f
            each(t, at, moved)
            prev = at; t += stepMs
        }
    }

    @Test fun `the glows never move across, only up and down, inside Xiaomi's own band`() {
        val glow = MusicFace.Glow()
        var low = 9f; var high = -9f
        run(glow, 1_000L, 60_000L) { _, at, _ ->
            for (i in 0..2) {
                assertEquals("across, glow $i", MusicFace.Glow.X[i], at[2 * i], 0f)
                low = maxOf(low.takeIf { it < 9f } ?: at[2 * i + 1], at[2 * i + 1]); high = if (high < -8f) at[2 * i + 1] else minOf(high, at[2 * i + 1])
                assertTrue("in the band: ${at[2 * i + 1]}", at[2 * i + 1] in 0.8f..1.2001f)
            }
        }
        assertTrue("it does rise and fall: from $low to $high", low - high > 0.08f)
    }

    @Test fun `a minute of it is one unbroken motion - no picture moves a glow more than its fastest honest step`() {
        val glow = MusicFace.Glow()
        val step = MusicFace.Glow.TOP_SPEED * 0.016f * 1.05f
        var worst = 0f
        run(glow, 1_000L, 60_000L) { _, _, moved -> worst = maxOf(worst, moved) }
        assertTrue("the largest step in a minute: $worst of the page's height (a picture's honest step: $step)", worst <= step)
        // And without sound it is slow: under a twentieth of the page's height in a second.
        val quiet = MusicFace.Glow(); var before: FloatArray? = null; var fastest = 0f
        run(quiet, 1_000L, 30_000L, stepMs = 1_000L) { t, at, _ -> if (t > 4_000L) before?.let { b -> fastest = maxOf(fastest, (0..2).maxOf { kotlin.math.abs(at[2 * it + 1] - b[2 * it + 1]) }) }; before = at }
        assertTrue("breathing: $fastest a second", fastest < 0.07f)
    }

    @Test fun `a new track, a new cover or a blink of the player's state starts nothing over`() {
        val glow = MusicFace.Glow()
        val step = MusicFace.Glow.TOP_SPEED * 0.016f * 1.05f
        var worst = 0f
        // A track change as a player reports it: not playing for a picture or two, then playing again; twice in ten seconds.
        run(glow, 1_000L, 20_000L, playing = { t -> t !in 8_000L..8_040L && t !in 14_000L..14_020L }) { t, _, moved -> if (t > 1_016L) worst = maxOf(worst, moved) }
        assertTrue("through two track changes the largest step was $worst", worst <= step)
    }

    @Test fun `paused it sinks to rest and stays, and playing again it goes on from there`() {
        val glow = MusicFace.Glow()
        run(glow, 1_000L, 12_000L) { _, _, _ -> }
        var last = FloatArray(6)
        run(glow, 13_016L, 6_000L, playing = { false }) { _, at, _ -> last = at }
        for (i in 0..2) assertEquals("at rest", MusicFace.Glow.REST_Y, last[2 * i + 1], 0.002f)
        val still = glow.at(60_000L, playing = false).copyOf()
        for (i in 0..5) assertEquals("it stays", last[i], still[i], 0.0005f)
        // On again: a first picture is a small step from rest, not a leap to where the breath "would be by now".
        val on = glow.at(60_016L, playing = true)
        for (i in 0..2) assertTrue(kotlin.math.abs(on[2 * i + 1] - still[2 * i + 1]) < 0.01f)
    }

    @Test fun `the page away for a while and back - no leap, the time it was not drawn is not caught up with`() {
        val glow = MusicFace.Glow()
        var before = FloatArray(6)
        run(glow, 1_000L, 9_000L) { _, at, _ -> before = at }
        val back = glow.at(10_000L + 45_000L, playing = true)
        for (i in 0..2) assertTrue("glow $i moved ${kotlin.math.abs(back[2 * i + 1] - before[2 * i + 1])}", kotlin.math.abs(back[2 * i + 1] - before[2 * i + 1]) <= MusicFace.Glow.TOP_SPEED * 0.05f * 1.05f)
    }

    @Test fun `with the sound it rises on a loud passage and sinks slower than it rose, never jumping on a beat`() {
        val glow = MusicFace.Glow()
        val step = MusicFace.Glow.TOP_SPEED * 0.016f * 1.05f
        var worst = 0f
        val height = { at: FloatArray -> (0..2).map { at[2 * it + 1] }.average().toFloat() }
        var quiet = 0f; var loud = 0f; var afterHalfSecond = 0f; var sunkHalfSecond = 0f
        // Ten seconds quiet (a soft passage, 0.1), ten loud with a hard beat twice a second (0.9 / 0.4), then quiet again.
        run(glow, 1_000L, 30_000L) { t, at, moved ->
            val heard = when { t < 11_000L -> 0.1f; t < 21_000L -> if ((t / 250L) % 2L == 0L) 0.9f else 0.4f; else -> 0.1f }
            // Told as the app tells it: every 50 ms or so (each third picture here).
            if (((t - 1_000L) / 16L) % 3L == 0L) glow.hear(heard, t)
            if (t > 1_016L) worst = maxOf(worst, moved)
            if (t in 10_900L..10_916L) quiet = height(at)
            if (t in 11_500L..11_516L) afterHalfSecond = height(at)
            if (t in 20_900L..20_916L) loud = height(at)
            if (t in 21_500L..21_516L) sunkHalfSecond = height(at)
        }
        assertTrue("higher in the loud passage (smaller y): quiet $quiet, loud $loud", loud < quiet - 0.05f)
        assertTrue("the hardest beat is no jump: the largest step was $worst", worst <= step)
        assertTrue("slow to rise: half a second in it is not there yet", afterHalfSecond > loud + 0.02f)
        assertTrue("slower to sink than to rise", (sunkHalfSecond - loud) < (quiet - afterHalfSecond))
        assertTrue("and it shines a little more with it", glow.strength >= MusicFace.Glow.STRENGTH_QUIET)
    }

    @Test fun `not told the level any more, it is back to breathing by itself`() {
        val glow = MusicFace.Glow()
        run(glow, 1_000L, 3_000L) { t, _, _ -> glow.hear(1f, t) }
        run(glow, 4_016L, 8_000L) { _, _, _ -> }
        assertEquals(MusicFace.Glow.STRENGTH_QUIET, glow.strength, 0.01f)
    }

    @Test fun `a new cover's colours come in over half a second, not at once`() {
        val fade = MusicFace.Fade()
        val a = MusicFace.palette(0xFF2060E0.toInt()); val b = MusicFace.palette(0xFFE04020.toInt())
        assertEquals(a, fade.at(a, 1_000L))
        var t = 1_016L; var prev = a; var worst = 0
        while (t < 4_000L) {
            val now = fade.at(b, t)
            worst = maxOf(worst, kotlin.math.abs(Color.red(now.glow2) - Color.red(prev.glow2)), kotlin.math.abs(Color.blue(now.glow2) - Color.blue(prev.glow2)))
            prev = now; t += 16L
        }
        assertTrue("no picture changes a colour by more than a few steps: $worst", worst <= 12)
        assertEquals(b, prev)
    }

    @Test fun `the sound's level from a measured loudness - a tone generated here, quiet to loud`() {
        // A sine of a given peak has an RMS of peak / sqrt 2: in millibels below full scale, 2000 log10 of it.
        fun rmsMilliBel(peak: Double): Int {
            var sum = 0.0
            val n = 4_800
            for (i in 0 until n) { val v = peak * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 48_000.0); sum += v * v }
            return Math.round(2_000.0 * kotlin.math.log10(kotlin.math.sqrt(sum / n))).toInt()
        }
        val level = dev.kb896.aiusage.service.MusicLevel::level
        assertEquals("silence", 0f, level(-9_600), 0f)
        assertEquals("a tone at a thousandth of full scale: nothing yet", 0f, level(rmsMilliBel(0.001)), 0f)
        val soft = level(rmsMilliBel(0.03)); val mid = level(rmsMilliBel(0.15)); val full = level(rmsMilliBel(0.9))
        assertTrue("soft $soft, middle $mid, loud $full", soft in 0.2f..0.5f && mid in 0.6f..0.9f && full == 1f)
        assertTrue(soft < mid && mid < full)
    }

    @Test fun `the page is drawn, full bleed, with and without a cover`() {
        File("build").mkdirs()
        // A stand-in for a cover, for this test only: on the phone the cover is the player's own picture.
        val cover = Bitmap.createBitmap(288, 288, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(0xFF1DB954.toInt()) }
        val icon = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(0xFF3A6EA5.toInt()) }
        val glow = floatArrayOf(0.28f, 0.92f, 0.52f, 1.05f, 0.78f, 0.86f)
        for ((name, art) in mapOf("cover" to cover, "none" to null)) for (playing in listOf(true, false)) {
            val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
            MusicFace.draw(Canvas(bmp), 976f, 596f, "Who Shot Johnny", "Rosa Walton, Hallie Coggins", playing, art, MusicFace.palette(MusicFace.seed(art ?: icon)), glow, playerIcon = icon)
            // The cover's place holds the cover, or without one the player's icon: never something drawn by us.
            assertEquals("$name: what stands in the cover's place", (if (art != null) 0x1DB954 else 0x3A6EA5), bmp.getPixel(458, 161) and 0xFFFFFF)
            File("build/music-$name-${if (playing) "playing" else "paused"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals("opaque behind the lenses too", 255, Color.alpha(bmp.getPixel(20, 20)))
            assertEquals("and in the far corner", 255, Color.alpha(bmp.getPixel(970, 590)))
            assertTrue("dark at the top", Color.green(bmp.getPixel(600, 10)) < 40)
            assertEquals("the title's white", 0xFFFFFF, bmp.getPixel(374, 352) and 0xFFFFFF or findWhite(bmp))
        }
    }

    /** White somewhere along the title's line. */
    private fun findWhite(bmp: Bitmap): Int {
        for (x in 362 until 860) if (bmp.getPixel(x, 348) and 0xFFFFFF == 0xFFFFFF) return 0xFFFFFF
        return 0
    }
}
