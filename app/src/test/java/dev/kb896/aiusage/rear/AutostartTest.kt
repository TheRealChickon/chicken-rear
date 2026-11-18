package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.Autostart.Decision
import dev.kb896.aiusage.rear.Autostart.Entry
import dev.kb896.aiusage.rear.Autostart.FileInfo
import dev.kb896.aiusage.rear.Autostart.Proc
import dev.kb896.aiusage.rear.Autostart.State
import dev.kb896.aiusage.service.Health
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutostartTest {
    private val script = "/data/local/tmp/trigger.sh"
    private val e = Entry(script)

    // -- the list ---------------------------------------------------------------------------------------------------

    @Test fun `a missing or empty list starts nothing`() {
        assertEquals(emptyList<Autostart.Parsed>(), Autostart.parse(null))
        assertEquals(emptyList<Autostart.Parsed>(), Autostart.parse(""))
        assertEquals(emptyList<Autostart.Parsed>(), Autostart.parse("\n  \n# a comment\n\t\n"))
    }

    @Test fun `one script per line, optionally a pattern, a path listed twice counts once`() {
        val got = Autostart.parse("$script\n# off: /data/local/tmp/other.sh\n  /data/local/tmp/b-2.sh   b-daemon*\n$script\n")
        assertEquals(listOf(Entry(script), Entry("/data/local/tmp/b-2.sh", "b-daemon*")), got.mapNotNull { it.entry })
        assertTrue(got.all { it.problem == null })
        assertEquals(listOf(1, 3), got.map { it.line })
        assertEquals("trigger.sh", Entry(script).name)
        assertEquals("a line with Windows' ending", listOf(Entry(script)), Autostart.parse("$script\r\n").mapNotNull { it.entry })
    }

    @Test fun `a line of more than two fields is refused, and the rest of the list still counts`() {
        val got = Autostart.parse("$script --fast now\n/data/local/tmp/ok.sh")
        assertNull(got[0].entry)
        assertTrue(got[0].problem!!, got[0].problem!!.contains("at most two"))
        assertEquals(Entry("/data/local/tmp/ok.sh"), got[1].entry)
    }

    @Test fun `only a file right in data local tmp, by a plain name`() {
        assertNull(Autostart.pathProblem(script))
        assertNull(Autostart.pathProblem("/data/local/tmp/Trigger_0.7-b.sh"))
        for (bad in listOf(
            "/sdcard/trigger.sh", "trigger.sh", "/data/local/tmp/", "/data/local/tmp/sub/trigger.sh", "/data/local/tmp/../tmp/x.sh",
            "/data/local/tmp/..", "/data/local/tmp/.", "/data/local/tmpx/trigger.sh", "/data/local/tmp/a;rm.sh", "/data/local/tmp/\$(x).sh",
            "/data/local/tmp/a b.sh", "/system/bin/sh", Autostart.LIST_FILE, Autostart.STATE_FILE, Autostart.STATE_FILE + ".tmp", Autostart.LOCK_FILE,
        )) assertNotNull(bad, Autostart.pathProblem(bad))
        assertNull("a refused line is never an entry", Autostart.parse("/sdcard/x.sh").single().entry)
    }

    @Test fun `a pattern that would match nearly anything is refused`() {
        assertNotNull(Autostart.patternProblem("*"))
        assertNotNull(Autostart.patternProblem("**sh*"))
        assertNull(Autostart.patternProblem("sh *trigger*"))
        assertNull(Autostart.parse("$script *").single().entry)
    }


    @Test fun `only a regular file of the shell's, not writable by anyone, may run`() {
        val ok = FileInfo(exists = true, regular = true, uid = 2000, mode = "644".toInt(8))
        assertNull(Autostart.fileProblem(ok))
        assertNull("runnable or not: it is run with sh", Autostart.fileProblem(ok.copy(mode = "755".toInt(8))))
        assertNull("the group (shell) may write", Autostart.fileProblem(ok.copy(mode = "664".toInt(8))))
        assertEquals("missing", Autostart.fileProblem(FileInfo(exists = false)))
        assertEquals("a link, not a file", Autostart.fileProblem(ok.copy(regular = false, link = true)))
        assertEquals("not a regular file", Autostart.fileProblem(ok.copy(regular = false)))
        assertEquals("owned by uid 0, not the shell", Autostart.fileProblem(ok.copy(uid = 0)))
        assertEquals("owned by uid 10503, not the shell", Autostart.fileProblem(ok.copy(uid = 10503)))
        for (m in listOf("666", "646", "777", "602")) assertEquals(m, "writable by anyone", Autostart.fileProblem(ok.copy(mode = m.toInt(8))))
    }

    // -- is it running ----------------------------------------------------------------------------------------------

    private fun cmdline(vararg a: String) = (a.joinToString("\u0000") + "\u0000").toByteArray()

    @Test fun `cmdline is split at NUL`() {
        assertEquals(listOf("sh", script), Autostart.argv(cmdline("sh", script)))
        assertEquals(listOf("sh", script), Autostart.argv("sh\u0000$script".toByteArray()))
        assertEquals(emptyList<String>(), Autostart.argv(ByteArray(0)))
        assertEquals("an empty argument kept", listOf("a", "", "b"), Autostart.argv(cmdline("a", "", "b")))
    }

    @Test fun `the process group is the fifth field, whatever the name holds`() {
        assertEquals(4321, Autostart.pgidOf("4321 (sh) S 1 4321 4321 0 -1 4194560"))
        assertEquals(77, Autostart.pgidOf("80 (a) b) c) S 1 77 77 0"))
        assertNull(Autostart.pgidOf("garbage"))
    }

    @Test fun `a shell running the script, however its path was written`() {
        assertTrue(Autostart.isScript(listOf("sh", script), e))
        assertTrue(Autostart.isScript(listOf("/system/bin/sh", script), e))
        assertTrue("with arguments it is still a copy", Autostart.isScript(listOf("sh", script, "x"), e))
        assertTrue("the shell's options", Autostart.isScript(listOf("/system/bin/sh", "-x", script), e))
        assertTrue(Autostart.isScript(listOf("sh", "-e", "-x", "--", script), e))
        assertTrue(Autostart.isScript(listOf("sh", "-o", "pipefail", script), e))
        assertTrue("mksh", Autostart.isScript(listOf("/system/bin/mksh", script), e))
        assertTrue("behind setsid and nohup", Autostart.isScript(listOf("setsid", "sh", script), e))
        assertTrue(Autostart.isScript(listOf("nohup", "/system/bin/setsid", "-d", "sh", script), e))
        assertTrue("relative, started in its folder", Autostart.isScript(listOf("sh", "trigger.sh"), e, cwd = "/data/local/tmp"))
        assertTrue(Autostart.isScript(listOf("sh", "./trigger.sh"), e, cwd = "/data/local/tmp/"))
        assertTrue(Autostart.isScript(listOf("sh", "tmp/trigger.sh"), e, cwd = "/data/local"))
        assertTrue(Autostart.isScript(listOf("sh", "../tmp/./trigger.sh"), e, cwd = "/data/local/tmp"))
        assertTrue(Autostart.isScript(listOf("sh", "/data/local//tmp/trigger.sh"), e))
        assertTrue("its folder not readable: taken as data local tmp", Autostart.isScript(listOf("sh", "trigger.sh"), e, cwd = null))
        assertFalse("relative, started elsewhere", Autostart.isScript(listOf("sh", "trigger.sh"), e, cwd = "/sdcard"))
        for (no in listOf(
            listOf(script), listOf("sh"), listOf("sh", "-x"), listOf("sh", "-c", "sh $script"), listOf("sh", "-xc", "sh $script"),
            listOf("sh", "-s", script), listOf("setsid"), listOf("nohup", "logcat", script),
            listOf("bash", script), listOf("sh", "$script.bak"), listOf("sh", "/data/local/tmp/trigger.s"), listOf("logcat", "-T", "1"),
            listOf("/system/bin/app_process", "/system/bin", "--nice-name=dev.chicken.rear.dev:rear", script), emptyList(),
        )) assertFalse(no.toString(), Autostart.isScript(no, e))
    }

    @Test fun `paths are resolved as the kernel would`() {
        assertEquals("/data/local/tmp/x.sh", Autostart.resolve("x.sh", "/data/local/tmp"))
        assertEquals("/data/local/tmp/x.sh", Autostart.resolve("x.sh", null))
        assertEquals("/x.sh", Autostart.resolve("../../x.sh", "/a"))
        assertEquals("/a/b", Autostart.resolve("/a/./c/../b/", "/ignored"))
    }

    @Test fun `a pattern is matched over the whole command line`() {
        val p = Entry(script, "*trigger-daemon --watch*")
        assertTrue(Autostart.isScript(listOf("/system/bin/trigger-daemon", "--watch", "-v"), p))
        assertFalse(Autostart.isScript(listOf("sh", script), p))
        assertTrue(Autostart.glob("a*c", "abbbc"))
        assertFalse(Autostart.glob("a*c", "abbbcd"))
        assertTrue("regex characters are plain", Autostart.glob("a.b*", "a.b(x)"))
        assertFalse(Autostart.glob("a.b*", "axb"))
    }

    @Test fun `the group's leader is the one, never our own process`() {
        val procs = listOf(
            Proc(900, 900, listOf("/system/bin/app_process", "x")),
            Proc(1210, 1200, listOf("sh", script)),
            Proc(1200, 1200, listOf("sh", script)),
            Proc(1205, 1200, listOf("logcat", "-T", "1")),
        )
        assertEquals(1200, Autostart.running(procs, e, selfPid = 900))
        assertEquals("no leader among them: the lowest", 1210, Autostart.running(procs.filter { it.pid != 1200 } + Proc(1300, 1200, listOf("sh", script)), e, 900))
        assertNull(Autostart.running(procs.filter { it.argv[0] != "sh" }, e, 900))
        assertNull("our own process is never it", Autostart.running(listOf(Proc(900, 900, listOf("sh", script))), e, selfPid = 900))
        assertEquals("a copy started by hand by a relative path is seen", 1500,
            Autostart.running(listOf(Proc(1500, 1500, listOf("sh", "trigger.sh"), cwd = "/data/local/tmp")), e, 900))
        assertNull(Autostart.running(listOf(Proc(1500, 1500, listOf("sh", "trigger.sh"), cwd = "/sdcard")), e, 900))
    }

    // -- the boot gate ----------------------------------------------------------------------------------------------

    @Test fun `the kept starts belong to one boot`() {
        val kept = State("boot-a", mapOf(script to listOf(1_000L, 2_000L)))
        assertEquals(kept, Autostart.gate(kept, "boot-a"))
        assertFalse(Autostart.newBoot(kept, "boot-a"))
        assertEquals("a restart forgets the last boot's starts", State("boot-b"), Autostart.gate(kept, "boot-b"))
        assertTrue(Autostart.newBoot(kept, "boot-b"))
        assertEquals(State("boot-a"), Autostart.gate(null, "boot-a"))
        assertTrue(Autostart.newBoot(null, "boot-a"))
    }

    @Test fun `a boot id that cannot be read is a fixed word that survives the state file`() {
        assertEquals("5f0c-11", Autostart.bootIdOf("5f0c-11\n"))
        assertEquals(Autostart.UNKNOWN_BOOT, Autostart.bootIdOf(null))
        assertEquals(Autostart.UNKNOWN_BOOT, Autostart.bootIdOf(""))
        assertEquals(Autostart.UNKNOWN_BOOT, Autostart.bootIdOf("  \n"))
        assertEquals(Autostart.UNKNOWN_BOOT, Autostart.bootIdOf("two words"))
        val s = State(Autostart.bootIdOf(null), mapOf(script to listOf(10L, 20L, 30L)))
        val back = Autostart.decode(Autostart.encode(s))
        assertEquals(s, back)
        // So the limit holds with no boot id: three starts kept, the fourth refused.
        val g = Autostart.gate(back, Autostart.bootIdOf(""))
        assertEquals(Decision.TooOften(3), Autostart.decide(null, null, g.recent(script, 40L).size, night = false, frontOn = true))
    }

    @Test fun `this process's own starts count when the state file is lost or not written`() {
        val mine = State("b", mapOf(script to listOf(10L, 20L, 30L)))
        assertEquals("no file", mine, Autostart.merge(null, mine, "b"))
        assertEquals("a file of an older write", mine, Autostart.merge(State("b", mapOf(script to listOf(10L))), mine, "b"))
        assertEquals("another process's starts count too", State("b", mapOf(script to listOf(5L, 10L, 20L, 30L), "/data/local/tmp/o.sh" to listOf(7L))),
            Autostart.merge(State("b", mapOf(script to listOf(5L), "/data/local/tmp/o.sh" to listOf(7L))), mine, "b"))
        assertEquals("a new boot forgets both", State("c"), Autostart.merge(State("b", mapOf(script to listOf(5L))), mine, "c"))
        assertEquals(State("b"), Autostart.merge(null, null, "b"))
        // The limit then holds with the file gone at every look.
        var m: State? = null
        var t = 100_000L
        repeat(Autostart.MOST_STARTS_AN_HOUR) {
            val s = Autostart.merge(null, m, "b")
            assertEquals(Decision.Start, Autostart.decide(null, null, s.recent(script, t).size, false, true, s.last(script, t)?.let { t - it }))
            m = s.started(script, t)
            t += Autostart.LOOK_EVERY_MS
        }
        val s = Autostart.merge(null, m, "b")
        assertEquals(Decision.TooOften(3), Autostart.decide(null, null, s.recent(script, t).size, false, true, s.last(script, t)?.let { t - it }))
    }

    @Test fun `a script started a moment ago and not seen yet is not started again`() {
        val s = State("b", mapOf(script to listOf(1_000L)))
        assertEquals(1_000L, s.last(script, 5_000L))
        assertNull("a start in the future is no last start", s.last(script, 500L))
        assertNull(s.last("/data/local/tmp/o.sh", 5_000L))
        assertEquals(Decision.JustStarted(4_000L), Autostart.decide(null, null, 1, night = false, frontOn = true, sinceStartMs = 4_000L))
        assertEquals(Decision.JustStarted(0L), Autostart.decide(null, null, 1, false, true, sinceStartMs = 0L))
        assertEquals(Decision.Start, Autostart.decide(null, null, 1, false, true, sinceStartMs = Autostart.START_GRACE_MS))
        assertEquals("running wins", Decision.Running(9), Autostart.decide(null, 9, 1, false, true, sinceStartMs = 10L))
        assertEquals("refused wins", Decision.Refused("missing"), Autostart.decide("missing", null, 1, false, true, sinceStartMs = 10L))
        assertNull("no trouble", Autostart.trouble(e, Decision.JustStarted(10L)))
    }

    @Test fun `the state file reads back what was written, and nothing from garbage`() {
        val s = State("5f0c-11", mapOf(script to listOf(10L, 20L), "/data/local/tmp/b.sh" to listOf(30L)))
        assertEquals(s, Autostart.decode(Autostart.encode(s)))
        assertEquals(State("x"), Autostart.decode(Autostart.encode(State("x"))))
        assertNull(Autostart.decode(null))
        assertNull(Autostart.decode(""))
        assertNull(Autostart.decode("start 10 $script"))
        assertEquals("broken lines are skipped", State("x", mapOf(script to listOf(5L))), Autostart.decode("boot x\nstart y $script\nstart 5 $script\nnonsense"))
    }

    // -- the rate limit ---------------------------------------------------------------------------------------------

    @Test fun `a script that keeps dying is started three times an hour, then reported`() {
        val hour = Autostart.HOUR_MS
        var s = State("b")
        var t = 100_000L
        repeat(Autostart.MOST_STARTS_AN_HOUR) {
            assertEquals(Decision.Start, Autostart.decide(null, null, s.recent(script, t).size, night = false, frontOn = true))
            s = s.started(script, t)
            t += Autostart.LOOK_EVERY_MS
        }
        val d = Autostart.decide(null, null, s.recent(script, t).size, night = false, frontOn = true)
        assertEquals(Decision.TooOften(3), d)
        assertEquals("trigger.sh: keeps stopping (started 3 times in the last hour)", Autostart.trouble(e, d))
        // An hour after the first start it may be started again, and the old start is forgotten.
        val later = 100_000L + hour
        assertEquals(2, s.recent(script, later).size)
        assertEquals(Decision.Start, Autostart.decide(null, null, s.recent(script, later).size, false, true))
        assertEquals(listOf(100_000L + Autostart.LOOK_EVERY_MS, 100_000L + 2 * Autostart.LOOK_EVERY_MS, later), s.started(script, later).starts[script])
        assertEquals("starts of another script do not count", 0, s.recent("/data/local/tmp/b.sh", t).size)
        assertEquals("a start in the future (a clock that went back) does not count", 0, State("b", mapOf(script to listOf(t + 5))).recent(script, t).size)
    }

    @Test fun `starts older than an hour are forgotten as a new one is kept`() {
        val now = 1L + Autostart.HOUR_MS
        // script's start at 1 is exactly an hour old (gone); b.sh's at 0 is older (gone), its one at 5000 is not (kept).
        val s = State("b", mapOf(script to listOf(1L), "/data/local/tmp/b.sh" to listOf(0L, 5_000L))).started(script, now)
        assertEquals(mapOf(script to listOf(now), "/data/local/tmp/b.sh" to listOf(5_000L)), s.starts)
        assertEquals("a script with no start left in the hour is dropped",
            mapOf(script to listOf(now)), State("b", mapOf(script to listOf(1L), "/data/local/tmp/b.sh" to listOf(0L))).started(script, now).starts)
    }

    // -- the decision and the night rule ----------------------------------------------------------------------------

    @Test fun `refused before running before night before the limit`() {
        assertEquals(Decision.Refused("missing"), Autostart.decide("missing", 1200, 5, night = true, frontOn = false))
        assertEquals(Decision.Running(1200), Autostart.decide(null, 1200, 5, night = true, frontOn = false))
        assertEquals(Decision.Night, Autostart.decide(null, null, 5, night = true, frontOn = false))
        assertEquals(Decision.TooOften(5), Autostart.decide(null, null, 5, night = false, frontOn = false))
    }

    @Test fun `never at night with the front off, at night with the front on and by day as always`() {
        assertEquals(Decision.Night, Autostart.decide(null, null, 0, night = true, frontOn = false))
        assertEquals(Decision.Start, Autostart.decide(null, null, 0, night = true, frontOn = true))
        assertEquals(Decision.Start, Autostart.decide(null, null, 0, night = false, frontOn = false))
        assertEquals(Decision.Start, Autostart.decide(null, null, 0, night = false, frontOn = true))
        for (h in listOf(22, 23, 0, 3, 6)) assertTrue("$h", Autostart.night(h, false))
        for (h in listOf(7, 8, 12, 21)) assertFalse("$h", Autostart.night(h, false))
        assertTrue("Do Not Disturb is night too", Autostart.night(14, true))
        assertNull("held back by the night is no trouble", Autostart.trouble(e, Decision.Night))
    }

    @Test fun `a look every ten minutes at most, and at once when the front comes on after the night held one back`() {
        val ten = Autostart.LOOK_EVERY_MS
        assertTrue("the first look", Autostart.lookDue(null, 0L, heldByNight = false, frontOn = false))
        assertFalse(Autostart.lookDue(1_000L, 1_000L + ten - 1, heldByNight = false, frontOn = true))
        assertTrue(Autostart.lookDue(1_000L, 1_000L + ten, heldByNight = false, frontOn = false))
        assertTrue("the night held a start: the front on", Autostart.lookDue(1_000L, 2_000L, heldByNight = true, frontOn = true))
        assertFalse("the night held a start: the front still off", Autostart.lookDue(1_000L, 2_000L, heldByNight = true, frontOn = false))
    }

    @Test fun `what the health check is told`() {
        assertEquals("trigger.sh: missing", Autostart.trouble(e, Decision.Refused("missing")))
        assertEquals("line 2: x", Autostart.trouble(null, Decision.Refused("line 2: x")))
        assertNull(Autostart.trouble(e, Decision.Running(5)))
        assertNull(Autostart.trouble(e, Decision.Start))
    }

    // -- the Health line --------------------------------------------------------------------------------------------

    @Test fun `a missing or dying script is one Health line, no notification`() {
        val well = Health.Facts(
            shizukuUp = true, shizukuAllowed = true, shellBound = true, guardBound = true, waitedMs = 600_000L,
            shell = Health.Shell(coverMade = true, dialerMissing = emptySet(), clockMissing = emptySet(), xiaomiMissing = emptyList(), autostart = emptyList()),
            listenerAllowed = true, listenerConnected = true, batteryUnrestricted = true, notificationsAllowed = true,
            pinByXiaomi = false, pinActivity = true, xiaomiRearVersion = null,
        )
        assertEquals(emptyList<Health.Problem>(), Health.problems(well))
        val bad = well.copy(shell = well.shell!!.copy(autostart = listOf("trigger.sh: missing")))
        val p = Health.problems(bad).single()
        assertEquals(Health.Key.AUTOSTART, p.key)
        assertTrue(p.what, p.what.contains("trigger.sh: missing"))
        assertFalse(p.warn)
        assertNull(Health.notice(listOf(p), allowed = true))
        assertEquals("not looked yet is not wrong", emptyList<Health.Problem>(), Health.problems(well.copy(shell = well.shell!!.copy(autostart = null))))
        val two = Health.problems(well.copy(shell = well.shell!!.copy(autostart = listOf("a.sh: missing", "b.sh: keeps stopping")))).single()
        assertTrue(two.what, two.what.startsWith("2 scripts"))
    }

    @Test fun `a new shell's answer before its own look keeps what the last one said of the scripts`() {
        val old = Health.Shell(coverMade = true, autostart = listOf("trigger.sh: missing"))
        assertEquals(listOf("trigger.sh: missing"), Health.Shell(coverMade = true, autostart = null).over(old).autostart)
        assertEquals(emptyList<String>(), Health.Shell(coverMade = true, autostart = emptyList()).over(old).autostart)
        assertNull(Health.Shell(coverMade = true).over(null).autostart)
    }
}
