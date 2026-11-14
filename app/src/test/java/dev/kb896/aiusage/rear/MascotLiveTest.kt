package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import dev.kb896.aiusage.data.MascotMood
import dev.kb896.aiusage.rear.MascotLive.Actor
import dev.kb896.aiusage.rear.MascotLive.Actor.Doing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The mascot alive: moving within two
 * seconds of the page opening whatever his account has earned, never frozen, never one unbroken loop,
 * never from one place to another without the way between.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MascotLiveTest {
    private val calm = listOf(0.40f, 0.44f, 0.10f)
    private val open = 5_000L

    /** Everything a frame shows of him, as one line: two frames that read the same look the same. */
    private fun look(p: Actor.Pose) = "${p.vessel} ${p.to} ${p.along} ${p.height} ${p.spot} ${p.clip} ${p.frame} ${p.rig.signature()}"

    @Test fun `he is moving within two seconds of the page opening, whatever his account has earned`() {
        for (mood in listOf(MascotMood.WALK, MascotMood.MUSCLE, MascotMood.FLAG, MascotMood.SLEEP, MascotMood.CONFETTI, MascotMood.GHOST)) {
            val a = Actor()
            val seen = HashSet<String>()
            for (t in open..open + 2_000L step 16L) seen += look(a.at(calm, mood, t, open))
            assertTrue("$mood: ${seen.size} different pictures in the first two seconds", seen.size >= 12)
        }
    }

    @Test fun `he is never frozen - no second goes by without a change`() {
        for (mood in listOf(MascotMood.WALK, MascotMood.MUSCLE, MascotMood.FLAG, MascotMood.SLEEP)) {
            val a = Actor(1)
            var was = ""; var same = 0L; var longest = 0L
            for (t in open..open + 90_000L step 16L) {
                val now = look(a.at(calm, mood, t, open))
                same = if (now == was) same + 16L else 0L
                longest = maxOf(longest, same); was = now
            }
            assertTrue("$mood: unchanged for $longest ms at most", longest < 1_000L)
        }
    }

    @Test fun `awake he stands and walks by turns - walking on his legs, with rests between`() {
        val a = Actor()
        var walking = 0L; var standing = 0L; var walks = 0; var was: Doing? = null
        var legs = 0f; var far = 0f; var first = -1L
        for (t in open..open + 60_000L step 16L) {
            val p = a.at(calm, MascotMood.WALK, t, open)
            if (p.doing == Doing.WALK) {
                walking += 16L
                if (was != Doing.WALK) { walks++; if (first < 0) first = t - open }
                legs = maxOf(legs, abs(p.rig.leg[0] - p.rig.leg[1]))
            }
            if (p.doing == Doing.STAND) standing += 16L
            far = maxOf(far, abs(p.spot - 0.5f))
            was = p.doing
            assertTrue("in his meter: ${p.spot}", p.spot in 0.05f..0.95f)
        }
        assertTrue("first walk $first ms after the page opened", first in 0L..1_600L)
        assertTrue("$walks walks in a minute", walks in 6..16)
        assertTrue("legs apart by up to $legs degrees as he walks", legs > 20f)
        assertTrue("more standing ($standing ms) than walking ($walking ms): rests, not a loop", standing > walking)
        assertTrue("he goes somewhere: $far of the meter from its middle", far > 0.2f)
    }

    @Test fun `nothing jumps - his place and his height change a little at a time`() {
        val a = Actor()
        var was: Actor.Pose? = null
        var spot = 0.5f; var height = 0f; var along = 1f; var vessel = 0; var squash = 1f; var inAir = false
        for (t in open..open + 60_000L step 16L) {
            // Half way, the week's own level rises and it comes to matter more: he hops over.
            val used = if (t < open + 30_000L) calm else listOf(0.40f, 0.44f, 0.92f)
            val p = a.at(used, MascotMood.WALK, t, open)
            if (was != null) {
                assertTrue("across his meter at $t: ${abs(p.spot - spot)}", abs(p.spot - spot) < 0.02f)
                assertTrue("his height at $t: ${abs(p.height - height)}", abs(p.height - height) < 0.16f)
                assertTrue("squashed or stretched at $t: ${abs(p.rig.squash - squash)}", abs(p.rig.squash - squash) < 0.12f)
                if (inAir && p.to != p.vessel) assertTrue("across the gap at $t", abs(p.along - along) < 0.12f)
            }
            was = p; spot = p.spot; height = p.height; along = p.along; vessel = p.vessel; squash = p.rig.squash; inAir = p.to != p.vessel
        }
        assertEquals("on the week's meter in the end", 1, was!!.vessel)
    }

    @Test fun `a hop is a crouch, a stretch in the air, a squash on landing - once`() {
        val a = Actor()
        for (t in open..open + 20_000L step 16L) a.at(calm, MascotMood.WALK, t, open)
        val more = listOf(0.40f, 0.44f, 0.92f)
        var low = 1f; var tall = 1f; var top = 0f; var hops = 0; var was: Doing? = null; var landed = 1f
        for (t in open + 20_000L..open + 40_000L step 16L) {
            val p = a.at(more, MascotMood.WALK, t, open)
            if (p.doing == Doing.HOP) {
                if (was != Doing.HOP) hops++
                if (p.height == 0f && p.along == 0f) low = minOf(low, p.rig.squash)
                if (p.height > 0.5f) tall = maxOf(tall, p.rig.squash)
                if (p.along == 1f) landed = minOf(landed, p.rig.squash)
                top = maxOf(top, p.height)
            }
            was = p.doing
        }
        assertEquals("one hop", 1, hops)
        assertTrue("he crouches first: $low", low < 0.82f)
        assertTrue("stretched in the air: $tall", tall > 1.05f)
        assertTrue("squashed as he lands: $landed", landed < 0.82f)
        assertTrue("right up: $top", top > 0.95f)
    }

    @Test fun `at work, near the limit, asleep - upstream's cycles are played, with a rest between rounds`() {
        for ((mood, rests) in listOf(MascotMood.MUSCLE to true, MascotMood.FLAG to true, MascotMood.SLEEP to false)) {
            val a = Actor()
            val frames = HashSet<Int>(); var inCycle = 0L; var out = 0L
            for (t in open + 1_000L..open + 40_000L step 16L) {
                val p = a.at(calm, mood, t, open)
                if (p.clip == mood) { frames += p.frame; inCycle += 16L } else out += 16L
            }
            assertTrue("$mood: ${frames.size} of its drawings shown", frames.size >= 6)
            if (rests) assertTrue("$mood: out of the cycle for $out ms of 39 s", out in 6_000L..24_000L)
            else assertEquals("$mood goes on", 0L, out)
        }
    }

    @Test fun `a window starting again is confetti, once, and then he is himself again`() {
        val a = Actor()
        for (t in open..open + 10_000L step 16L) a.at(listOf(0.95f, 0.30f), MascotMood.WALK, t, open)
        var cheer = 0L; var after: String? = "x"
        for (t in open + 10_000L..open + 20_000L step 16L) {
            val p = a.at(listOf(0.02f, 0.30f), MascotMood.WALK, t, open)
            if (p.clip == MascotMood.CONFETTI) cheer += 16L
            after = p.clip
        }
        assertTrue("confetti for $cheer ms", cheer in 1_200L..3_000L)
        assertEquals("then the figure itself", null, after)
    }

    @Test fun `dead he lies still, and that is the one time he does`() {
        val a = Actor()
        val p = a.at(calm, MascotMood.DEAD, open + 5_000L, open)
        assertTrue(p.dead); assertEquals(MascotMood.STAND, p.clip)
    }

    @Test fun `a contact sheet of him, for eyes`() {
        val cell = 150
        val moods = listOf(MascotMood.WALK, MascotMood.MUSCLE, MascotMood.FLAG, MascotMood.SLEEP, MascotMood.CONFETTI)
        val bmp = Bitmap.createBitmap(cell * 16, cell * moods.size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF101010.toInt())
        val paint = android.graphics.Paint()
        val eye = android.graphics.Paint()
        for ((row, mood) in moods.withIndex()) {
            val a = Actor()
            for (i in 0 until 16) {
                val p = a.at(calm, mood, open + i * 220L, open)
                val cx = cell * i + cell * (0.25f + 0.5f * p.spot)
                val feet = cell * (row + 1) - 14f - p.height * 40f
                val unit = 0.8f
                val clip = p.clip?.let { n -> MascotFrames.CLIPS.first { it.name == n } }
                if (clip == null) MascotLive.draw(c, p.rig, cx, feet, unit, paint, OriginalUsage.CLAUDE, 0xFF1D1F21.toInt())
                else MascotRenderer.draw(c, clip, p.frame * clip.frameMs, cx - clip.boxHeight * unit * clip.aspect / 2f, feet - clip.boxHeight * unit, clip.boxHeight * unit, paint, eye, accent = 0xFFFFFFFF.toInt(), bodyColor = OriginalUsage.CLAUDE)
            }
        }
        File("build").mkdirs()
        File("build/mascot-live-sheet.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertNotNull(bmp)
        // No purple, no pink, whatever he is doing: blue well over green, and red too.
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
            val px = bmp.getPixel(x, y); val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
            assertFalse("purple or pink at $x,$y: ${Integer.toHexString(px)}", b > g + 24 && r > g + 24)
        }
    }
}
