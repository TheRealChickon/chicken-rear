package dev.kb896.aiusage.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

class FollowTrackTest {
    private val F = 8_333_333L            // 120 Hz
    private val JUMP = 26f                // 8 dp at 3.25 px/dp

    /** Content position (px up) at frame i of a fling that starts at [v0] px per frame and decays like Android's. */
    private fun fling(i: Int, v0: Double): Double = v0 * (1 - exp(-i / 40.0)) * 40.0

    /** Feeds readings for frames [from] until [to] from [pos], every [every] frames, and returns the largest error of
     *  the value drawn for each frame (at the frame itself, from the readings before it) against the truth. */
    private fun run(tr: FollowTrack, pos: (Int) -> Double, to: Int, every: Int = 1, skip: (Int) -> Boolean = { false }): Double {
        var worst = 0.0
        for (i in 0 until to) {
            val drawn = tr.at(i * F)
            if (i > 3) worst = maxOf(worst, abs(drawn - pos(i)))
            if (i % every == 0 && !skip(i)) tr.add(i * F, pos(i).roundToInt())
        }
        return worst
    }

    @Test fun steadyDragIsFollowedWithinTwoPixels() {
        val tr = FollowTrack(JUMP, F)
        // The value drawn for frame i uses readings up to frame i-1 carried on by one frame.
        val w = run(tr, { it * 12.0 }, 120)
        assertTrue("worst $w", w <= 2.0)
        assertFalse(tr.rough)
        assertTrue(tr.clean >= FollowTrack.CLEAN_READINGS)
    }

    @Test fun flingIsFollowedAndNotRough() {
        val tr = FollowTrack(JUMP, F)
        val w = run(tr, { fling(it, 45.0) }, 240)
        assertTrue("worst $w", w <= 4.0)
        assertFalse(tr.rough)
    }

    @Test fun aReadingMissedNowAndThenIsBridged() {
        val tr = FollowTrack(JUMP, F)
        val w = run(tr, { fling(it, 30.0) }, 200, skip = { it % 5 == 2 })
        assertTrue("worst $w", w <= 5.0)
        assertFalse(tr.rough)
    }

    @Test fun aSourceThatUpdatesTenTimesASecondIsRough() {
        // Chrome on the emulator: the node's bounds change about every 100 ms, in big steps.
        val tr = FollowTrack(JUMP, F)
        for (i in 0 until 120) tr.add(i * F, (((i / 12) * 12) * 10.0).roundToInt())
        assertTrue(tr.rough)
    }

    @Test fun aStopIsNotOvershotForLong() {
        val tr = FollowTrack(JUMP, F)
        for (i in 0..30) tr.add(i * F, i * 20)
        for (i in 31..40) tr.add(i * F, 600)
        assertEquals(600f, tr.at(41 * F), 0.01f)
        assertFalse(tr.rough)
    }

    @Test fun aSuddenStopOfAFastDragIsNotRough() {
        val tr = FollowTrack(JUMP, F)
        for (i in 0..20) tr.add(i * F, i * 45)
        for (i in 21..40) tr.add(i * F, 900)
        assertFalse(tr.rough)
    }

    @Test fun aStartFromRestIsNotAMiss() {
        val tr = FollowTrack(JUMP, F)
        for (i in 0..10) tr.add(i * F, 0)
        tr.add(11 * F, 60)       // first frame of a fling: big step out of rest
        tr.add(12 * F, 118)
        tr.add(13 * F, 172)
        assertEquals(0, tr.hits)
    }

    @Test fun nothingIsGuessedMoreThanTwoFramesAhead() {
        val tr = FollowTrack(JUMP, F)
        for (i in 0..10) tr.add(i * F, i * 10)
        assertEquals(120f, tr.at(30 * F), 0.5f)
    }

    @Test fun aLongSilenceWhileMovingIsRough() {
        val tr = FollowTrack(JUMP, F)
        for (i in 0..10) tr.add(i * F, i * 10)
        tr.add(17 * F, 170); tr.add(18 * F, 180)
        tr.add(25 * F, 250)
        assertTrue(tr.rough)
    }
}
