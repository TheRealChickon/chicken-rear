package dev.kb896.aiusage.rear.dots

import dev.kb896.aiusage.data.MascotMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When OpenAI's Dots character acts on the usage page: as it opens, then after rests by its account's mood; never while spent. */
class DotsActorTest {
    private val opened = 1_000_000L

    /** The times (ms after opening) it acted in [ms], asked every [step] ms, for [used] and [mood] (both may change with time). */
    private fun acts(a: DotsActor, ms: Long, step: Long = 50L, used: (Long) -> List<Float> = { listOf(0.4f) }, mood: (Long) -> String = { MascotMood.WALK }): List<Long> {
        val out = ArrayList<Long>()
        var t = 0L
        while (t <= ms) {
            if (a.at(used(t), mood(t), opened + t, opened).signature) out += t
            t += step
        }
        return out
    }

    @Test fun `it acts once as the page opens, within half a second`() {
        val first = acts(DotsActor(), 2_000L)
        assertEquals(1, first.size)
        assertTrue(first[0] in DotsActor.FIRST_MS..DotsActor.FIRST_MS + 50L)
    }

    @Test fun `then it rests, longest when all is calm and shortest near the limit`() {
        for ((mood, range) in mapOf(MascotMood.WALK to 9_000L..14_000L, MascotMood.MUSCLE to 6_000L..9_000L, MascotMood.FLAG to 3_500L..5_500L)) {
            val times = acts(DotsActor(3), 120_000L, mood = { mood })
            assertTrue("$mood: acts again and again (${times.size})", times.size >= 5)
            for (i in 1 until times.size) {
                val gap = times[i] - times[i - 1] - DotsActor.ACT_MS
                assertTrue("$mood: a rest of $gap ms", gap in (range.first - 50L)..(range.last + 50L))
            }
            assertFalse("$mood: not all its rests alike", times.zipWithNext { x, y -> y - x }.distinct().size == 1)
        }
    }

    @Test fun `spent, it never acts and is calm, and acts again soon after it is awake`() {
        val a = DotsActor()
        for (mood in listOf(MascotMood.SLEEP, MascotMood.GHOST, MascotMood.DEAD)) {
            val b = DotsActor()
            assertTrue(acts(b, 60_000L, mood = { mood }).isEmpty())
            assertTrue(b.at(listOf(1f), mood, opened + 60_100L, opened).calm)
        }
        val times = acts(a, 40_000L, mood = { t -> if (t < 20_000L) MascotMood.SLEEP else MascotMood.WALK })
        assertTrue(times.isNotEmpty())
        assertTrue("awake at 20 s, acting by ${times[0]}", times[0] in 20_000L..20_000L + DotsActor.WAKE_MS + 100L)
        assertFalse(a.at(listOf(0.4f), MascotMood.WALK, opened + 40_100L, opened).calm)
    }

    @Test fun `a window starting again makes it act at once, but never over its last act`() {
        val times = acts(DotsActor(), 30_000L, used = { t -> listOf(if (t < 5_000L) 0.8f else 0.1f) })
        assertTrue("acts at the reset (${times})", times.any { it in 5_000L..5_050L })
        val soon = acts(DotsActor(), 10_000L, used = { t -> listOf(if (t < 1_000L) 0.8f else 0.1f) })
        // The opening's act at 450 ms, the reset at 1 s: the reset's act waits for that one to be over.
        assertEquals(DotsActor.FIRST_MS, soon[0])
        assertTrue("then the reset's, after it (${soon})", soon[1] in (DotsActor.FIRST_MS + DotsActor.ACT_MS)..(DotsActor.FIRST_MS + DotsActor.ACT_MS + 50L))
        for (i in 1 until times.size) assertTrue(times[i] - times[i - 1] >= DotsActor.ACT_MS)
    }

    @Test fun `long unseen, it does not burst into acts, and a new opening starts afresh`() {
        val a = DotsActor()
        assertEquals(1, acts(a, 1_000L).size)
        // Asked again ten minutes on: once at most.
        var n = 0
        for (t in 0L..1_000L step 50L) if (a.at(listOf(0.4f), MascotMood.WALK, opened + 600_000L + t, opened).signature) n++
        assertTrue(n <= 1)
        a.reset()
        val again = ArrayList<Long>()
        val reopened = opened + 900_000L
        for (t in 0L..1_000L step 50L) if (a.at(listOf(0.4f), MascotMood.WALK, reopened + t, reopened).signature) again += t
        assertEquals(listOf(DotsActor.FIRST_MS), again)
    }
}
