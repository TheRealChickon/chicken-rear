package dev.kb896.aiusage.rear.dots

import android.app.ApplicationExitInfo
import dev.kb896.aiusage.rear.dots.DotsLink.Exit
import dev.kb896.aiusage.rear.dots.DotsLink.Strikes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the ":dots" process is bound at all, and which of its ends count against a ChatGPT build. */
class DotsLinkTest {
    private val dots = "app.test:dots"

    @Test fun `bound only with the switch on and ChatGPT signed in`() {
        assertFalse(DotsLink.wanted(switchOn = false, chatGptSignedIn = false))
        assertFalse("ChatGPT installed but nobody signed in: no process", DotsLink.wanted(switchOn = true, chatGptSignedIn = false))
        assertFalse("signed in but the switch off (the default): no process", DotsLink.wanted(switchOn = false, chatGptSignedIn = true))
        assertTrue(DotsLink.wanted(switchOn = true, chatGptSignedIn = true))
    }

    @Test fun `only a crash is a strike - the system killing it is not`() {
        val kills = listOf(
            ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_FREEZER, ApplicationExitInfo.REASON_USER_STOPPED, ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED, ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_UNKNOWN,
        ).mapIndexed { i, r -> Exit(dots, r, 100L + i) }
        val after = Strikes.tally(null, kills, dots, 7L)
        assertEquals(0, after.count)
        assertEquals("every record looked at", 108L, after.seen)
        val crashed = Strikes.tally(after, listOf(Exit(dots, ApplicationExitInfo.REASON_CRASH_NATIVE, 200L), Exit(dots, ApplicationExitInfo.REASON_CRASH, 210L)), dots, 7L)
        assertEquals(2, crashed.count)
        assertEquals(210L, crashed.seen)
    }

    @Test fun `a record is counted once, and only the dots process's`() {
        val exits = listOf(Exit(dots, ApplicationExitInfo.REASON_CRASH_NATIVE, 50L), Exit("app.test", ApplicationExitInfo.REASON_CRASH, 60L), Exit("app.test:guard", ApplicationExitInfo.REASON_CRASH_NATIVE, 70L))
        val once = Strikes.tally(null, exits, dots, 7L)
        assertEquals(1, once.count)
        val again = Strikes.tally(once, exits, dots, 7L)
        assertEquals("the same records read again add nothing", once, again)
        val three = Strikes.tally(again, listOf(Exit(dots, ApplicationExitInfo.REASON_CRASH_NATIVE, 80L), Exit(dots, ApplicationExitInfo.REASON_CRASH_NATIVE, 90L)), dots, 7L)
        assertEquals(DotsLink.MAX_STRIKES, three.count)
    }

    @Test fun `a new ChatGPT build starts from none, and old crashes are not counted against it`() {
        val before = Strikes(7L, 2, 90L)
        val after = Strikes.tally(before, listOf(Exit(dots, ApplicationExitInfo.REASON_CRASH_NATIVE, 80L)), dots, 8L)
        assertEquals(Strikes(8L, 0, 90L), after)
        assertEquals(Strikes(8L, 1, 95L), Strikes.tally(after, listOf(Exit(dots, ApplicationExitInfo.REASON_CRASH_NATIVE, 95L)), dots, 8L))
    }

    @Test fun `the strikes file is read back, also as the older two-word note`() {
        val s = Strikes(2630718L, 2, 1_700_000_000_123L)
        assertEquals(s, Strikes.parse(s.text()))
        assertEquals(Strikes(2630718L, 3, 0L), Strikes.parse("2630718 3"))
        assertEquals(null, Strikes.parse("nonsense"))
    }
}
