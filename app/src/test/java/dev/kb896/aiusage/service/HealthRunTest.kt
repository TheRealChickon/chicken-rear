package dev.kb896.aiusage.service

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthRunTest {
    private val well = Health.Facts(
        shizukuUp = true, shizukuAllowed = true, shellBound = true, guardBound = true, waitedMs = 600_000L, shell = null,
        listenerAllowed = true, listenerConnected = true, batteryUnrestricted = true, notificationsAllowed = true,
        pinByXiaomi = false, pinActivity = true, xiaomiRearVersion = null,
    )

    private class Phone(var stamp: Health.Stamp, var facts: Health.Facts) : HealthReader {
        var shellSeen: Health.Shell? = null
        var boot = "count 1"
        override fun stamp() = stamp
        override fun boot() = boot
        override fun facts(shell: Health.Shell?) = facts.copy(shell = shell).also { shellSeen = shell }
    }
    private class Mem : HealthMemory {
        override var stamp: String? = null; override var shell: Health.Shell? = null
        override var posted: String? = null; override var postedBoot: String? = null
    }
    private class Shade : HealthNotifier {
        val posts = ArrayList<String>(); var cancels = 0
        override fun post(n: Health.Notice) { posts += n.title }
        override fun cancel() { cancels++ }
    }

    @Test fun `a change makes the shell look again, and is kept until a shell has`() {
        val phone = Phone(Health.Stamp("os1", mapOf("com.android.deskclock" to 1L)), well)
        val mem = Mem(); val shade = Shade()
        val asked = ArrayList<Boolean>()
        var shell: Health.Shell? = Health.Shell(coverMade = true)
        val run = HealthRun(phone, mem, shade, { again -> asked += again; shell })
        run.run()
        assertEquals(listOf(false), asked)
        phone.stamp = phone.stamp.copy(versions = mapOf("com.android.deskclock" to 2L))
        shell = null
        run.run()
        assertEquals("changed, no shell there", listOf(false, true), asked)
        assertEquals("what it said last is used", Health.Shell(coverMade = true), phone.shellSeen)
        shell = Health.Shell(coverMade = true, clockMissing = setOf("action.timer_pause"))
        run.run()
        assertEquals("still a change, now looked at", listOf(false, true, true), asked)
        run.run()
        assertEquals(listOf(false, true, true, false), asked)
        assertEquals(setOf("action.timer_pause"), mem.shell?.clockMissing)
    }

    @Test fun `the notification is put up once, not again for the same, and taken down when all is well`() {
        val phone = Phone(Health.Stamp("os1", emptyMap()), well.copy(shizukuUp = false, waitedMs = Health.GRACE_MS))
        val mem = Mem(); val shade = Shade()
        val run = HealthRun(phone, mem, shade, { null })
        run.run(); run.run(); run.run()
        assertEquals("once, though he may have swiped it away", 1, shade.posts.size)
        phone.facts = phone.facts.copy(batteryUnrestricted = false)
        run.run()
        assertEquals("something new: told again", listOf("The back shows Xiaomi's screen: Shizuku is not running.", "2 things need a look"), shade.posts)
        phone.facts = well
        run.run(); run.run()
        assertEquals(1, shade.cancels)
        assertEquals(null, mem.posted)
        phone.facts = well.copy(shizukuUp = false, waitedMs = Health.GRACE_MS, notificationsAllowed = false)
        run.run()
        assertEquals("not allowed: nothing posted", 2, shade.posts.size)
    }

    @Test fun `after a restart the same problems are told again - the restart took the notification away`() {
        val phone = Phone(Health.Stamp("os1", emptyMap()), well.copy(shizukuUp = false, waitedMs = Health.GRACE_MS))
        val mem = Mem(); val shade = Shade()
        val run = HealthRun(phone, mem, shade, { null })
        run.run(); run.run()
        assertEquals(1, shade.posts.size)
        phone.boot = "count 2"
        run.run()
        assertEquals("Shizuku still not up after his restart: told again", 2, shade.posts.size)
        run.run()
        assertEquals("and once only in that boot", 2, shade.posts.size)
    }

    @Test fun `a new shell's answer before its own look keeps what the last one read of the packages`() {
        val phone = Phone(Health.Stamp("os1", emptyMap()), well)
        val mem = Mem(); val shade = Shade()
        var shell: Health.Shell? = Health.Shell(coverMade = true, dialerMissing = setOf("ACTION_REAR_SPEAKER"), clockMissing = emptySet(), xiaomiMissing = emptyList())
        val asked = ArrayList<Boolean>()
        val run = HealthRun(phone, mem, shade, { again -> asked += again; shell })
        assertEquals(listOf(Health.Key.DIALER), run.run().map { it.key })
        // The shell restarted: bound again, it answers at once, before its own look of the dialer's code.
        shell = Health.Shell(coverMade = true, looked = false)
        assertEquals("what the last shell read stands", listOf(Health.Key.DIALER), run.run().map { it.key })
        assertEquals(setOf("ACTION_REAR_SPEAKER"), mem.shell?.dialerMissing)
        assertEquals(listOf(false, false), asked)
        // A change while that shell has not looked: asked to look again; the stamp is kept only once it has.
        phone.stamp = Health.Stamp("os2", emptyMap())
        shell = Health.Shell(coverMade = true, looked = false)
        run.run()
        assertEquals("os1", Health.decode(mem.stamp)?.fingerprint)
        shell = Health.Shell(coverMade = true, dialerMissing = emptySet(), clockMissing = emptySet(), xiaomiMissing = emptyList())
        assertEquals("looked again: the dialer is well", emptyList<Health.Key>(), run.run().map { it.key })
        assertEquals("os2", Health.decode(mem.stamp)?.fingerprint)
        // The cover is always this answer's: a new shell that could not make it says so before its look.
        shell = Health.Shell(coverMade = false, coverError = "no display", looked = false)
        assertEquals(listOf(Health.Key.COVER), run.run().map { it.key })
    }
}
