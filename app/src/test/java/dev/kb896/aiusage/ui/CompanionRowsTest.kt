package dev.kb896.aiusage.ui

import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.ui.CompanionRows.Fix
import dev.kb896.aiusage.ui.Readiness.Feature
import dev.kb896.aiusage.ui.Readiness.Inputs
import dev.kb896.aiusage.ui.Readiness.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The companion screen's rows, from Readiness: a missing need is one line and one button, on its feature alone. */
class CompanionRowsTest {

    private val all = Inputs(shizukuRunning = true, shizukuGranted = true, rearDisplay = true, notificationAccess = true)

    private fun rows(i: Inputs, restricted: Boolean = false) =
        CompanionRows.rows(Readiness.of(i), restricted).associateBy { it.feature }

    @Test fun `everything there, four rows on, none greyed, no line, no button, and the status says Working`() {
        val r = CompanionRows.rows(Readiness.of(all))
        assertEquals(Feature.entries, r.map { it.feature })
        assertTrue(r.all { it.on && !it.greyed && it.line == null && it.fix == null })
        val status = CompanionRows.status(Readiness.of(all))
        assertTrue(status.working)
        assertEquals("Working", status.text)
        assertNull(status.fix)
    }

    @Test fun `Shizuku stopped greys out every feature, each with one line and Open Shizuku`() {
        val r = rows(all.copy(shizukuRunning = false))
        for (f in listOf(Feature.MORE_APPS, Feature.CHARGE, Feature.ANIMATIONS)) {
            val row = r.getValue(f)
            assertTrue(row.on)
            assertTrue(row.greyed)
            assertEquals("Needs Shizuku running.", row.line)
            assertEquals(Fix.OPEN_SHIZUKU, row.fix)
        }
        // The banner on the back is a shell layer too: greyed, and the test button with it.
        val notices = r.getValue(Feature.NOTICES)
        assertTrue(notices.on)
        assertTrue(notices.greyed)
        assertEquals("Needs Shizuku running. " + Readiness.NOTICES_WITHOUT_SHIZUKU, notices.line)
        assertEquals(Fix.OPEN_SHIZUKU, notices.fix)
    }

    @Test fun `Shizuku not allowed asks to allow it in Shizuku`() {
        val row = rows(all.copy(shizukuGranted = false)).getValue(Feature.MORE_APPS)
        assertEquals("Needs this app allowed in Shizuku.", row.line)
        assertEquals(Fix.ALLOW_SHIZUKU, row.fix)
        assertEquals("Allow in Shizuku", row.fix!!.button)
    }

    @Test fun `no notification access greys notifications alone, with Allow`() {
        val r = rows(all.copy(notificationAccess = false))
        val notices = r.getValue(Feature.NOTICES)
        assertTrue(notices.greyed)
        assertEquals("Needs notification access.", notices.line)
        assertEquals(Fix.ALLOW_NOTIFICATIONS, notices.fix)
        assertEquals(3, r.values.count { !it.greyed })
    }

    @Test fun `asked in Android's page and still blocked, the line explains restricted settings and opens App info`() {
        val notices = rows(all.copy(notificationAccess = false), restricted = true).getValue(Feature.NOTICES)
        assertEquals(CompanionRows.RESTRICTED_LINE, notices.line)
        assertEquals(Fix.OPEN_APP_INFO, notices.fix)
        val status = CompanionRows.status(Readiness.of(all.copy(notificationAccess = false)), restricted = true)
        assertEquals(Fix.OPEN_APP_INFO, status.fix)
        assertEquals(CompanionRows.RESTRICTED_LINE, status.detail)
    }

    @Test fun `restricted changes nothing for the Shizuku features`() {
        val more = rows(all.copy(shizukuRunning = false), restricted = true).getValue(Feature.MORE_APPS)
        assertEquals(Fix.OPEN_SHIZUKU, more.fix)
        assertEquals("Needs Shizuku running.", more.line)
    }

    @Test fun `a feature switched off is off, not greyed, with nothing to fix`() {
        val r = rows(all.copy(shizukuRunning = false, moreApps = false))
        val more = r.getValue(Feature.MORE_APPS)
        assertFalse(more.on)
        assertFalse(more.greyed)
        assertNull(more.line)
        assertNull(more.fix)
    }

    @Test fun `the status names the first missing need and has its one button`() {
        val status = CompanionRows.status(Readiness.of(all.copy(shizukuRunning = false)))
        assertFalse(status.working)
        assertEquals("More apps, Charging animation, Notifications and Animations need Shizuku running", status.text)
        assertEquals(Fix.OPEN_SHIZUKU, status.fix)
        assertNotNull(status.fix!!.button)
    }

    @Test fun `no rear display greys every feature that is on, with no button to press`() {
        val r = CompanionRows.rows(Readiness.of(all.copy(rearDisplay = false)))
        assertTrue(r.all { it.greyed && it.fix == null && it.line != null })
    }

    @Test fun `each switch reads and writes its own field and nothing else`() {
        val base = PanelConfig.FRESH
        for (f in Feature.entries) {
            for (on in listOf(true, false)) {
                val next = CompanionRows.switched(base, f, on)
                assertEquals(on, CompanionRows.isOn(next, f))
                for (other in Feature.entries - f) assertEquals(CompanionRows.isOn(base, other), CompanionRows.isOn(next, other))
                assertEquals(base.rearMode, next.rearMode)
            }
        }
    }
}
