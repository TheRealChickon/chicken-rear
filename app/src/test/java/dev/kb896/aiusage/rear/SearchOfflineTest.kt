package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.rear.SearchOffline.Hold
import dev.kb896.aiusage.rear.SearchOffline.Marker
import dev.kb896.aiusage.rear.SearchOffline.Step
import dev.kb896.aiusage.rear.SearchOffline.Why
import dev.kb896.aiusage.service.Health
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's own search kept off the internet (rear/SearchOffline): what the system's answers mean, when the block is
 * applied, checked and taken off, and who owns chain 3.
 */
class SearchOfflineTest {
    private val boot = "0f1e2d3c-boot"
    private val hour = SearchOffline.CHECK_EVERY_MS

    // -- the switch ----------------------------------------------------------------------------------------------------

    @Test fun `on by default, also in a stored config written before it existed`() {
        assertTrue(PanelConfig.DEFAULT.searchOffline)
        assertTrue(PanelConfig.decode("""{"moreApps":false}""").searchOffline)
        assertFalse(PanelConfig.decode(PanelConfig.encode(PanelConfig.DEFAULT.copy(searchOffline = false))).searchOffline)
    }

    // -- reading the system's answers ----------------------------------------------------------------------------------

    @Test fun `chain 3 read strictly - help text or an error is not known`() {
        assertEquals(true, SearchOffline.chainOn("chain:enabled"))
        assertEquals(false, SearchOffline.chainOn("chain:disabled\n"))
        assertEquals(true, SearchOffline.chainOn("  Chain: Enabled  "))
        assertNull(SearchOffline.chainOn(""))
        assertNull(SearchOffline.chainOn(null))
        assertNull("the help text names the commands, it says no state",
            SearchOffline.chainOn("Connectivity service commands:\n  set-chain3-enabled [true|false]\n  get-chain3-enabled\n"))
        assertNull(SearchOffline.chainOn("Exception occurred while executing 'get-chain3-enabled':"))
    }

    @Test fun `the package's rule read strictly, for that package only`() {
        assertEquals(true, SearchOffline.denied("com.android.quicksearchbox:deny"))
        assertEquals(false, SearchOffline.denied("com.android.quicksearchbox: allow"))
        assertNull(SearchOffline.denied("com.android.quicksearchboxx:deny"))
        assertNull(SearchOffline.denied("com.other.app:deny"))
        assertNull(SearchOffline.denied("  get-package-networking-enabled <package-name>\n    Get whether the package is allowed or denied"))
        assertNull(SearchOffline.denied(null))
    }

    @Test fun `it holds only with both, either one off is gone`() {
        assertEquals(Hold.HOLDS, SearchOffline.hold(true, true))
        assertEquals(Hold.GONE, SearchOffline.hold(false, true))
        assertEquals(Hold.GONE, SearchOffline.hold(true, false))
        assertEquals("a reboot: both gone", Hold.GONE, SearchOffline.hold(false, false))
        assertEquals("one part off is gone even when the other is not readable", Hold.GONE, SearchOffline.hold(null, false))
        assertEquals(Hold.UNKNOWN, SearchOffline.hold(true, null))
        assertEquals(Hold.UNKNOWN, SearchOffline.hold(null, null))
    }

    @Test fun `a set command's silence is success, only an error's words are a refusal`() {
        assertNull(SearchOffline.refusal(""))
        assertNull(SearchOffline.refusal(null))
        assertNull("a confirmation is not a refusal, \"Denied\" included", SearchOffline.refusal("Denied networking for package com.android.quicksearchbox"))
        assertNull(SearchOffline.refusal("Enabled FIREWALL_CHAIN_OEM_DENY_3"))
        assertEquals("Exception occurred while executing 'set-chain3-enabled':",
            SearchOffline.refusal("\nException occurred while executing 'set-chain3-enabled':\njava.lang.SecurityException: uid 2000 lacks …"))
        assertNotNull(SearchOffline.refusal("Unknown command: set-chain3-enabled"))
        assertNotNull(SearchOffline.refusal("cmd: Can't find service: connectivity\nError"))
    }

    @Test fun `an apply is judged by what is read back, then by what the commands said`() {
        assertNull(SearchOffline.outcome(Hold.HOLDS, listOf("", "")))
        assertNull("read back as holding: a noisy output does not matter", SearchOffline.outcome(Hold.HOLDS, listOf("some error text", "")))
        assertEquals("the phone refused it (the block did not take)", SearchOffline.outcome(Hold.GONE, listOf("", "")))
        assertTrue(SearchOffline.outcome(Hold.GONE, listOf("java.lang.SecurityException: no", ""))!!.contains("SecurityException"))
        assertNull("not readable and the commands silent: taken as done", SearchOffline.outcome(Hold.UNKNOWN, listOf("", null)))
        assertNotNull(SearchOffline.outcome(Hold.UNKNOWN, listOf("", "Unknown command: set-chain3-enabled")))
    }

    // -- this boot's record --------------------------------------------------------------------------------------------

    @Test fun `the record reads back, and one from another boot counts for nothing`() {
        val m = Marker(boot, applied = true, chainByUs = true)
        assertEquals(m, SearchOffline.decode(SearchOffline.encode(m)))
        assertEquals(Marker(boot, false, false), SearchOffline.decode(SearchOffline.encode(Marker(boot, false, false))))
        assertNull(SearchOffline.decode(null))
        assertNull(SearchOffline.decode(""))
        assertEquals(m, SearchOffline.thisBoot(m, boot))
        assertNull("a restart cleared the block and the chain", SearchOffline.thisBoot(m, "another-boot"))
    }

    @Test fun `chain 3 is ours only if we switched it on`() {
        assertTrue("it was off and we turned it on", SearchOffline.chainByUs(false, null))
        assertFalse("it was on already (a block made by hand)", SearchOffline.chainByUs(true, null))
        assertTrue("on because we had turned it on earlier in this boot", SearchOffline.chainByUs(true, Marker(boot, true, true)))
        assertFalse(SearchOffline.chainByUs(null, null))
        assertTrue(SearchOffline.chainByUs(null, Marker(boot, true, true)))
    }

    // -- what happens next ---------------------------------------------------------------------------------------------

    @Test fun `nothing before the settings are heard, nothing while both screens are dark`() {
        assertEquals(Step.Wait, SearchOffline.next(null, lit = true, due = Why.START, ours = null, sinceCheckMs = null))
        assertEquals(Step.Wait, SearchOffline.next(true, lit = false, due = Why.START, ours = null, sinceCheckMs = null))
        assertEquals(Step.Wait, SearchOffline.next(true, lit = false, due = null, ours = null, sinceCheckMs = 5 * hour))
        assertEquals("not even taking it off", Step.Wait, SearchOffline.next(false, lit = false, due = null, ours = Marker(boot, true, true), sinceCheckMs = null))
    }

    @Test fun `a new shell applies it once, as soon as a screen is lit`() {
        assertEquals(Step.Apply(Why.START), SearchOffline.next(true, lit = true, due = Why.START, ours = null, sinceCheckMs = null))
        assertEquals("also when this boot's record says it was applied (a new process after an update)",
            Step.Apply(Why.START), SearchOffline.next(true, lit = true, due = Why.START, ours = Marker(boot, true, false), sinceCheckMs = null))
        assertEquals("applied after start", Why.START.words)
    }

    @Test fun `then a look once an hour, an apply only if it has gone`() {
        assertEquals(Step.Wait, SearchOffline.next(true, lit = true, due = null, ours = Marker(boot, true, true), sinceCheckMs = hour - 1))
        assertEquals(Step.Check, SearchOffline.next(true, lit = true, due = null, ours = Marker(boot, true, true), sinceCheckMs = hour))
        assertNull(SearchOffline.afterCheck(Hold.HOLDS))
        assertEquals(Why.GONE, SearchOffline.afterCheck(Hold.GONE))
        assertEquals(Why.UNREADABLE, SearchOffline.afterCheck(Hold.UNKNOWN))
    }

    @Test fun `switched on applies it at once, switched off takes off only what we put there`() {
        assertEquals(Why.SWITCHED_ON, SearchOffline.dueAfter(false, true, null))
        assertEquals("the first settings heard: the start's apply stands", Why.START, SearchOffline.dueAfter(null, true, Why.START))
        assertEquals(Step.Apply(Why.SWITCHED_ON), SearchOffline.next(true, lit = true, due = Why.SWITCHED_ON, ours = null, sinceCheckMs = 10L))
        assertEquals(Step.Release(chainToo = true), SearchOffline.next(false, lit = true, due = null, ours = Marker(boot, true, true), sinceCheckMs = 10L))
        assertEquals("the chain was on before us: left on", Step.Release(chainToo = false), SearchOffline.next(false, lit = true, due = null, ours = Marker(boot, true, false), sinceCheckMs = 10L))
        assertEquals("off, and nothing of ours stands: nothing at all (a block made by hand is left)",
            Step.Wait, SearchOffline.next(false, lit = true, due = Why.START, ours = null, sinceCheckMs = null))
        assertEquals("taken off already", Step.Wait, SearchOffline.next(false, lit = true, due = null, ours = Marker(boot, false, false), sinceCheckMs = null))
    }

    @Test fun `the thread never waits less than a second and looks at least every hour while lit`() {
        assertEquals(hour, SearchOffline.waitMs(true, true, 0L))
        assertEquals(1_000L, SearchOffline.waitMs(true, true, hour + 5))
        assertEquals(SearchOffline.DARK_LOOK_MS, SearchOffline.waitMs(true, false, 0L))
        assertEquals(SearchOffline.DARK_LOOK_MS, SearchOffline.waitMs(null, true, null))
    }

    // -- the health check ----------------------------------------------------------------------------------------------

    @Test fun `a refusal is one health line, nothing when all is well`() {
        val shell = Health.Shell(coverMade = true, dialerMissing = emptySet(), clockMissing = emptySet(), xiaomiMissing = emptyList())
        val facts = Health.Facts(
            shizukuUp = true, shizukuAllowed = true, shellBound = true, guardBound = true, waitedMs = 600_000L, shell = shell,
            listenerAllowed = true, listenerConnected = true, batteryUnrestricted = true, notificationsAllowed = true,
            pinByXiaomi = true, pinActivity = true, xiaomiRearVersion = PinVia.BUILT_ON,
        )
        assertEquals(emptyList<Health.Problem>(), Health.problems(facts.copy(shell = shell.copy(searchOffline = emptyList()))))
        val p = Health.problems(facts.copy(shell = shell.copy(searchOffline = listOf("the phone's search is not kept offline: the phone refused it (x)")))).single()
        assertEquals(Health.Key.SEARCH_OFFLINE, p.key)
        assertTrue(p.what.startsWith("The phone's search is not kept offline"))
        assertFalse("advice, not a notification", p.warn)
        assertEquals("a new shell's first answer keeps what the last one met",
            listOf("x"), Health.Shell().over(shell.copy(searchOffline = listOf("x"))).searchOffline)
    }
}
