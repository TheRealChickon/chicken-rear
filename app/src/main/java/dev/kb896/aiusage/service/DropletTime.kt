package dev.kb896.aiusage.service

import kotlin.math.abs
import kotlin.math.roundToLong

object DropletTime {
    /**
     * The frame time (on the vsync grid through [frameNanos], [periodNanos] apart, never before [frameNanos]) of the
     * first frame whose animation clock, ([it] - [beganNanos]) / 1e6 / [slow] ms, is PAST [atMs]: at exactly [atMs]
     * the island is still nothing (its opacity is 0 there).
     */
    fun firstFrameAfter(frameNanos: Long, periodNanos: Long, beganNanos: Long, atMs: Long, slow: Float = 1f): Long {
        require(periodNanos > 0)
        val due = beganNanos + (atMs * slow.toDouble() * 1e6).roundToLong()
        if (frameNanos > due) return frameNanos
        return frameNanos + ((due - frameNanos) / periodNanos + 1) * periodNanos
    }

    /**
     * When a frame drawn at [frameNanos] is on the glass: the display's own expected presentation time for it where
     * Choreographer gave one ([expectedPresentNanos], taken only if it is 0 to 6 periods after the frame), else two
     * periods; plus [handOnFrames] periods for a picture that is composed once more on its way (a TextureView's is
     * drawn into its window's next frame).
     */
    fun presentOf(frameNanos: Long, expectedPresentNanos: Long?, periodNanos: Long, handOnFrames: Int = 0): Long {
        val latency = expectedPresentNanos?.minus(frameNanos)?.takeIf { it in 0..6 * periodNanos } ?: (2 * periodNanos)
        return frameNanos + latency + handOnFrames * periodNanos
    }

    /**
     * How many frames of silence still to write before the sound, so that its attack ([attackFrames] into it) is heard
     * at [targetNanos]: from the track's timestamp (frame [tsFrame] heard at [tsNanos]) and the frames [written] so far.
     * Negative: too late by that many frames already.
     */
    fun silenceFrames(written: Long, tsFrame: Long, tsNanos: Long, targetNanos: Long, rate: Int, attackFrames: Int): Long {
        val heard = tsFrame + ((targetNanos - tsNanos).toDouble() * rate / 1e9).roundToLong()
        return heard - attackFrames - written
    }

    /** When frame [frame] of the track is (or was) heard, by its timestamp. */
    fun heardAt(frame: Long, tsFrame: Long, tsNanos: Long, rate: Int): Long =
        tsNanos + ((frame - tsFrame).toDouble() * 1e9 / rate).roundToLong()

    /**
     * The sound's ATTACK, in frames from its start: the first frame where any channel reaches a tenth of the sound's
     * peak (and at least 1% of full scale, so a quiet hiss before it does not count). 0 for a silent sound.
     */
    fun attack(pcm: ShortArray, channels: Int): Int {
        require(channels >= 1)
        var peak = 0
        for (s in pcm) peak = maxOf(peak, abs(s.toInt()))
        if (peak == 0) return 0
        val level = maxOf(peak / 10, 328)
        for (i in pcm.indices) if (abs(pcm[i].toInt()) >= level) return i / channels
        return 0
    }
}
