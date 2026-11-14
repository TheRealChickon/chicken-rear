package dev.kb896.aiusage.ui

import dev.kb896.aiusage.ui.Readiness.Action
import dev.kb896.aiusage.ui.Readiness.Feature
import dev.kb896.aiusage.ui.Readiness.Inputs
import dev.kb896.aiusage.ui.Readiness.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each feature checks only what it needs; one missing thing never turns everything off. */
class ReadinessTest {

    private val all = Inputs(shizukuRunning = true, shizukuGranted = true, rearDisplay = true, notificationAccess = true)

    private fun ready(i: Inputs) = Readiness.of(i).filterValues { it == State.Ready }.keys

    @Test fun `everything there is everything ready, and the line says Working`() {
        assertEquals(Feature.entries.toSet(), ready(all))
        val line = Readiness.line(Readiness.of(all))
        assertTrue(line.working)
        assertEquals(Action.NONE, line.action)
    }

    @Test fun `Shizuku down holds back every feature, the banner on the back included`() {
        val s = Readiness.of(all.copy(shizukuRunning = false))
        assertEquals(emptySet<Feature>(), s.filterValues { it == State.Ready }.keys)
        for (f in Feature.entries) {
            assertEquals(Action.OPEN_SHIZUKU, (s[f] as State.Missing).action)
            assertEquals("Shizuku running", (s[f] as State.Missing).reason)
        }
        // Only the notifications' row says what still works without it.
        assertEquals(Readiness.NOTICES_WITHOUT_SHIZUKU, (s[Feature.NOTICES] as State.Missing).note)
        assertEquals(null, (s[Feature.MORE_APPS] as State.Missing).note)
    }

    @Test fun `Shizuku not allowed asks to allow it, for notifications as well`() {
        val s = Readiness.of(all.copy(shizukuGranted = false))
        assertEquals(emptySet<Feature>(), s.filterValues { it == State.Ready }.keys)
        assertEquals(Action.ALLOW_SHIZUKU, (s[Feature.MORE_APPS] as State.Missing).action)
        assertEquals(Action.ALLOW_SHIZUKU, (s[Feature.NOTICES] as State.Missing).action)
    }

    @Test fun `notification access is asked for before Shizuku`() {
        val s = Readiness.of(all.copy(shizukuRunning = false, notificationAccess = false))
        assertEquals(Action.ALLOW_NOTIFICATIONS, (s[Feature.NOTICES] as State.Missing).action)
    }

    @Test fun `the notifications' note does not split them from the others on the status line`() {
        val line = Readiness.line(Readiness.of(all.copy(shizukuRunning = false)))
        assertEquals("More apps, Charging animation, Notifications and Animations need Shizuku running", line.text)
        assertEquals(Action.OPEN_SHIZUKU, line.action)
    }

    @Test fun `with only notifications on and Shizuku down, the line is not Working`() {
        val only = all.copy(shizukuRunning = false, moreApps = false, chargeAnim = false, wakeAnim = false)
        val line = Readiness.line(Readiness.of(only))
        assertFalse(line.working)
        assertEquals("Notifications needs Shizuku running", line.text)
    }

    @Test fun `no notification access holds back notifications alone`() {
        val s = Readiness.of(all.copy(notificationAccess = false))
        assertEquals(setOf(Feature.MORE_APPS, Feature.CHARGE, Feature.ANIMATIONS), s.filterValues { it == State.Ready }.keys)
        assertEquals(Action.ALLOW_NOTIFICATIONS, (s[Feature.NOTICES] as State.Missing).action)
    }

    @Test fun `battery restrictions never hold anything back`() {
        assertEquals(Feature.entries.toSet(), ready(all.copy(batteryUnrestricted = false)))
        assertTrue(Readiness.line(Readiness.of(all.copy(batteryUnrestricted = false))).working)
    }

    @Test fun `a feature switched off is off, not missing, whatever is absent`() {
        val s = Readiness.of(all.copy(shizukuRunning = false, notificationAccess = false, moreApps = false, notices = false))
        assertEquals(State.Off, s[Feature.MORE_APPS])
        assertEquals(State.Off, s[Feature.NOTICES])
        assertTrue(s[Feature.CHARGE] is State.Missing)
    }

    @Test fun `everything off is working, with nothing to fix`() {
        val off = all.copy(shizukuRunning = false, moreApps = false, chargeAnim = false, notices = false, wakeAnim = false)
        assertTrue(Readiness.line(Readiness.of(off)).working)
    }

    @Test fun `no rear display is missing for every feature that is on`() {
        val s = Readiness.of(all.copy(rearDisplay = false))
        assertTrue(s.values.all { it is State.Missing && it.action == Action.NONE })
    }

    @Test fun `the line names the first reason and every feature it holds back, with one button`() {
        val line = Readiness.line(Readiness.of(all.copy(shizukuRunning = false, notificationAccess = false)))
        assertFalse(line.working)
        assertEquals(Action.OPEN_SHIZUKU, line.action)
        assertEquals("More apps, Charging animation and Animations need Shizuku running", line.text)
        val one = Readiness.line(Readiness.of(all.copy(notificationAccess = false)))
        assertEquals("Notifications needs notification access", one.text)
        assertEquals(Action.ALLOW_NOTIFICATIONS, one.action)
    }
}
