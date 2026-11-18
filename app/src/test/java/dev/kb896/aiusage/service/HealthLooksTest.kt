package dev.kb896.aiusage.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthLooksTest {
    private val well = Health.Facts(
        shizukuUp = true, shizukuAllowed = true, shellBound = true, guardBound = true, waitedMs = 0L, shell = null,
        listenerAllowed = true, listenerConnected = true, batteryUnrestricted = true, notificationsAllowed = true,
        pinByXiaomi = false, pinActivity = true, xiaomiRearVersion = null,
    )

    /** The phone as the test has it: what is up at each moment, and the looks HealthCheck would make. */
    private class Sim(val state: (Long) -> Pair<Boolean, Boolean>, val base: Health.Facts) : HealthReader, HealthMemory, HealthNotifier {
        var now = 0L
        var changedAt = 0L
        val dues = Health.Slot.entries.associateWith { Health.Due() }
        val looks = sortedMapOf<Long, MutableList<String>>()
        var soonAt: Long? = null
        val posts = ArrayList<Pair<Long, String>>()

        override fun stamp() = Health.Stamp("os", emptyMap())
        override fun boot() = "count 1"
        override fun facts(shell: Health.Shell?): Health.Facts {
            val (shizuku, shellUp) = state(now)
            return base.copy(shizukuUp = shizuku, shellBound = shellUp, guardBound = shellUp, waitedMs = now - changedAt)
        }
        override var stamp: String? = null
        override var shell: Health.Shell? = null
        override var posted: String? = null
        override var postedBoot: String? = null
        override fun post(n: Health.Notice) { posts += now to n.signature }
        override fun cancel() {}

        fun cue(at: Long, cue: Health.Cue) {
            advance(at)
            if (Health.startsGrace(cue)) changedAt = at
            for (w in Health.waitsFor(cue)) {
                if (w.slot == Health.Slot.SOON) {
                    // HealthCheck.soon: coalesced while one is queued.
                    if (soonAt == null || soonAt!! <= at) soonAt = at + w.delayMs
                    continue
                }
                val due = dues.getValue(w.slot)
                if (due.arm(at, w)) {
                    looks.values.forEach { it.remove(w.slot.name) }
                    looks.getOrPut(at + w.delayMs) { ArrayList() } += w.slot.name
                }
            }
            soonAt?.let { looks.getOrPut(it) { ArrayList() } += "SOON" }
        }

        /** Every look due up to [to], in order, each a HealthRun. */
        fun advance(to: Long) {
            while (true) {
                val next = looks.entries.firstOrNull { it.key <= to && it.value.isNotEmpty() } ?: break
                looks.remove(next.key)
                now = next.key
                for (slot in next.value.distinct()) if (slot != "SOON") dues.getValue(Health.Slot.valueOf(slot)).fired() else soonAt = null
                HealthRun(this, this, this, { null }).run()
                lookCount++
            }
            now = maxOf(now, to)
        }
        var lookCount = 0
    }

    private val s = 1_000L

    @Test fun `Shizuku dying mid-day is told past its grace`() {
        val sim = Sim({ t -> (t < 3_600 * s) to (t < 3_600 * s) }, well)
        sim.cue(0, Health.Cue.START)
        sim.cue(0, Health.Cue.SHIZUKU_CAME)
        sim.cue(10 * s, Health.Cue.BOUND)
        sim.advance(3_599 * s)
        assertEquals("all well for an hour", emptyList<Pair<Long, String>>(), sim.posts)
        sim.cue(3_600 * s, Health.Cue.SHIZUKU_WENT)
        sim.cue(3_600 * s, Health.Cue.SHELL_GONE)
        sim.advance(3_600 * s + 10 * 60 * s)
        assertEquals(1, sim.posts.size)
        assertEquals("SHIZUKU_DOWN", sim.posts[0].second)
        assertTrue("past the grace, soon after it", sim.posts[0].first in (3_600 * s + Health.GRACE_MS)..(3_600 * s + Health.GRACE_MS + 10 * s))
    }

    @Test fun `a late arrival whose shell never binds is told past the arrival's grace`() {
        // The restart: the process at 0, Shizuku at 60 s, bindUserService never connects (the back-off's retries).
        val sim = Sim({ t -> (t >= 60 * s) to false }, well)
        sim.cue(0, Health.Cue.START)
        sim.cue(60 * s, Health.Cue.SHIZUKU_CAME)
        for (retry in listOf(70L, 130L, 430L)) sim.cue(retry * s, Health.Cue.BIND_RETRY)
        sim.advance(30 * 60 * s)
        assertEquals(listOf("SHELL_DOWN"), sim.posts.map { it.second })
        assertTrue("not before the arrival's grace is over", sim.posts[0].first >= 60 * s + Health.GRACE_MS)
        assertTrue("and not long after", sim.posts[0].first <= 60 * s + Health.GRACE_MS + 10 * s)
    }

    @Test fun `a shell that stays away mid-day is told once, and its every-8-s retries are not a look each`() {
        val sim = Sim({ t -> true to (t < 1_000 * s) }, well)
        sim.cue(0, Health.Cue.START)
        sim.cue(0, Health.Cue.SHIZUKU_CAME)
        sim.cue(5 * s, Health.Cue.BOUND)
        sim.advance(999 * s)
        val before = sim.lookCount
        // ShizukuBridge.ensureAlive says "still away" every 8 s or so for as long as it is.
        var t = 1_000 * s
        while (t < 1_600 * s) { sim.cue(t, Health.Cue.SHELL_GONE); t += 8 * s }
        sim.advance(1_600 * s)
        assertEquals(listOf("SHELL_DOWN"), sim.posts.map { it.second })
        assertTrue("past the first one's grace", sim.posts[0].first >= 1_000 * s + Health.GRACE_MS)
        assertTrue("a few looks in ten minutes, not one per retry (${sim.lookCount - before})", sim.lookCount - before <= 8)
    }

    @Test fun `Shizuku coming and going within its grace is not told`() {
        // Up, down and up again 40 s apart after a restart: never down for a whole grace, never told.
        val sim = Sim({ t -> (t in 20 * s until 60 * s || t >= 100 * s) to (t >= 110 * s) }, well)
        sim.cue(0, Health.Cue.START)
        sim.cue(20 * s, Health.Cue.SHIZUKU_CAME)
        sim.cue(60 * s, Health.Cue.SHIZUKU_WENT)
        sim.cue(100 * s, Health.Cue.SHIZUKU_CAME)
        sim.cue(110 * s, Health.Cue.BOUND)
        sim.advance(20 * 60 * s)
        assertEquals(emptyList<Pair<Long, String>>(), sim.posts)
    }

    @Test fun `a new shell is looked at again past its own first look`() {
        val waits = Health.waitsFor(Health.Cue.BOUND)
        val later = waits.single { it.slot == Health.Slot.SHELL }
        assertTrue("past the shell's 30 s first look", later.delayMs > 30_000L)
        val due = Health.Due()
        assertEquals(true, due.arm(0, later))
        assertEquals("a newer shell moves it", true, due.arm(10_000L, later))
        assertEquals(10_000L + later.delayMs, due.at)
        val grace = Health.waitsFor(Health.Cue.SHELL_GONE).single()
        val g = Health.Due()
        assertEquals(true, g.arm(0, grace))
        assertEquals("a waiting one stands", false, g.arm(8_000L, grace))
        assertEquals("one that has fired is gone", true, g.arm(grace.delayMs + 1, grace))
    }
}
