package dev.kb896.aiusage.service

import dev.kb896.aiusage.rear.PinB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drop is placed on the island's first frame, by its attack, and never late when asked for in time. */
class DropletTimeTest {
    private val period = 8_333_333L      // 120 Hz
    private val began = 1_000_000_000L

    @Test
    fun theIslandsFrameIsTheFirstPastItsMoment() {
        // Asked on the frame 450 ms before (the frames on the grid from the first one).
        val asked = began + 198 * period
        val island = DropletTime.firstFrameAfter(asked, period, began, PinB.ISLAND_AT)
        assertTrue("past the moment", island - began > PinB.ISLAND_AT * 1_000_000L)
        assertTrue("the first past it", island - period - began <= PinB.ISLAND_AT * 1_000_000L)
        assertEquals("on the vsync grid", 0L, (island - asked) % period)
        // Its opacity there is above 0, one frame before it is 0.
        val tl = PinB.Timeline(PinB.Place())
        assertTrue(tl.at((island - began) / 1e6f).islandOp > 0f)
        assertEquals(0f, tl.at((island - period - began) / 1e6f).islandOp, 0f)
    }

    @Test
    fun aMomentExactlyOnAFrameIsTheNextFrame() {
        // 2100 ms at exactly 252 periods of 8.333 ms: at 2100 itself the island is nothing yet.
        val p = 8_333_333L
        val due = began + 2_100_000_000L
        val frame = due - 10 * p
        val got = DropletTime.firstFrameAfter(frame, p, began, 2_100L)
        assertTrue(got > due && got - p <= due)
        assertEquals("a frame already past it is that frame", due + 5, DropletTime.firstFrameAfter(due + 5, p, began, 2_100L))
    }

    @Test
    fun slowMotionStretchesTheMoment() {
        val got = DropletTime.firstFrameAfter(began, period, began, 2_100L, slow = 4f)
        assertTrue(got - began > 8_400_000_000L && got - began - period <= 8_400_000_000L)
    }

    @Test
    fun onTheGlassByTheDisplaysOwnWord() {
        val f = 5_000_000_000L
        assertEquals(f + 20_000_000L, DropletTime.presentOf(f, f + 20_000_000L, period))
        assertEquals("a TextureView's picture one period later", f + 20_000_000L + period, DropletTime.presentOf(f, f + 20_000_000L, period, handOnFrames = 1))
        assertEquals("nothing said: two periods", f + 2 * period, DropletTime.presentOf(f, null, period))
        assertEquals("nonsense is not taken", f + 2 * period, DropletTime.presentOf(f, f - 1, period))
        assertEquals("nonsense is not taken", f + 2 * period, DropletTime.presentOf(f, f + 100 * period, period))
    }

    @Test
    fun theSilenceBeforeLandsTheAttackOnTheMoment() {
        val rate = 48_000
        val attack = 1_680                 // synthetic 35 ms of leading silence
        // The track: frame 9 600 heard at 7.000 s; 12 000 frames written so far.
        val tsFrame = 9_600L; val tsNanos = 7_000_000_000L; val written = 12_000L
        val target = 7_450_000_000L          // 450 ms after that timestamp
        val need = DropletTime.silenceFrames(written, tsFrame, tsNanos, target, rate, attack)
        // Written then: the attack is the frame (written + need + attack), heard at the target.
        val heard = DropletTime.heardAt(written + need + attack, tsFrame, tsNanos, rate)
        assertTrue("within one sample: ${heard - target} ns", kotlin.math.abs(heard - target) <= 1_000_000_000L / rate)
        assertTrue("in time: silence still to come", need > 0)
        // Asked for too late: negative.
        assertTrue(DropletTime.silenceFrames(written, tsFrame, tsNanos, tsNanos + 10_000_000L, rate, attack) < 0)
    }

    @Test
    fun theAttackIsWhereTheSoundComesUp() {
        val rate = 48_000
        val silent = 30 * rate / 1000
        val pcm = ShortArray((silent + 2_000) * 2)
        // A little hiss, then the drop at 35 ms (both channels).
        for (i in 0 until silent * 2) pcm[i] = (if (i % 2 == 0) 40 else -40).toShort()
        val at = 35 * rate / 1000
        for (f in at until silent + 2_000) { val v = (12_000 * kotlin.math.sin(f * 0.3)).toInt().toShort(); pcm[2 * f] = v; pcm[2 * f + 1] = v }
        val got = DropletTime.attack(pcm, 2)
        assertTrue("about 35 ms: ${got * 1000.0 / rate} ms", got in at..at + 10)
        assertEquals(0, DropletTime.attack(ShortArray(100), 1))
    }
}
