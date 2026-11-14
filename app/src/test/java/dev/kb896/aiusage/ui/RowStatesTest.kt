package dev.kb896.aiusage.ui

import dev.kb896.aiusage.ui.home.RowState
import dev.kb896.aiusage.ui.home.RowStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every row of the settings that leans on something outside the app shows what is real: on only while that thing
 * is there, off with a few words when it is missing, on by itself when it is given. Against faked system states.
 */
class RowStatesTest {
    @Test fun `follows the music - on only with the permission, and by itself once it is given`() {
        assertEquals(RowState(false), RowStates.followsMusic(stored = false, audioAllowed = false))
        assertEquals(RowState(false), RowStates.followsMusic(stored = false, audioAllowed = true))
        // Wanted, the permission not given (never asked, refused, or taken away later in the phone's settings).
        val missing = RowStates.followsMusic(stored = true, audioAllowed = false)
        assertFalse("not shown as on while it cannot work", missing.on)
        assertTrue(missing.note!!.contains("permission"))
        // He allows it and comes back: on, without a second tap, and nothing more to say.
        assertEquals(RowState(true), RowStates.followsMusic(stored = true, audioAllowed = true))
    }

    @Test fun `notifications - on only with notification access`() {
        assertEquals(RowState(false), RowStates.notifications(stored = false, access = true))
        val missing = RowStates.notifications(stored = true, access = false)
        assertFalse(missing.on); assertTrue(missing.note!!.contains("notification access"))
        assertEquals(RowState(true), RowStates.notifications(stored = true, access = true))
    }

    @Test fun `glass when you pin - on only when the service runs, and says where it is switched`() {
        val off = RowStates.pinGlass(enabledInSettings = false, running = false)
        assertFalse(off.on); assertEquals("Off in Accessibility", off.note)
        // Switched on in the phone's settings but the system has not started it (yet, or it was killed).
        val waiting = RowStates.pinGlass(enabledInSettings = true, running = false)
        assertFalse(waiting.on); assertTrue(waiting.note!!.contains("not started"))
        assertEquals(RowState(true, "On"), RowStates.pinGlass(enabledInSettings = true, running = true))
        // Running is the truth even if the list was not readable.
        assertTrue(RowStates.pinGlass(enabledInSettings = false, running = true).on)
    }

    @Test fun `a note for a place says what its permission is now`() {
        assertEquals("Needs location", RowStates.placeNote(fine = false, always = false))
        assertEquals("Needs location", RowStates.placeNote(fine = false, always = true))
        assertTrue(RowStates.placeNote(fine = true, always = false).contains("all the time"))
        assertEquals("Comes back at its place", RowStates.placeNote(fine = true, always = true))
    }

    @Test fun `lightning when it storms - on only with a position for the weather`() {
        assertEquals(RowState(false), RowStates.lightning(stored = false, placeKnown = true, locationAllowed = true))
        assertEquals(RowState(true), RowStates.lightning(stored = true, placeKnown = true, locationAllowed = false))
        val none = RowStates.lightning(stored = true, placeKnown = false, locationAllowed = false)
        assertFalse(none.on); assertTrue(none.note!!.contains("location"))
        val soon = RowStates.lightning(stored = true, placeKnown = false, locationAllowed = true)
        assertFalse(soon.on); assertEquals("No position yet", soon.note)
    }

    @Test fun `an account and the back itself`() {
        assertEquals("Signed in", RowStates.account(signedIn = true, signingIn = false))
        assertEquals("Not signed in", RowStates.account(signedIn = false, signingIn = false))
        assertEquals("Signing in…", RowStates.account(signedIn = false, signingIn = true))
        assertEquals("Shizuku is not running", RowStates.back(shizukuRunning = false, shizukuGranted = false, rearFound = false))
        assertNotNull(RowStates.back(shizukuRunning = true, shizukuGranted = false, rearFound = true))
        assertNotNull(RowStates.back(shizukuRunning = true, shizukuGranted = true, rearFound = false))
        assertNull(RowStates.back(shizukuRunning = true, shizukuGranted = true, rearFound = true))
    }

    @Test fun `no row is on with its requirement missing, and every such row says so in a few words`() {
        val missing = listOf(
            RowStates.followsMusic(true, false), RowStates.notifications(true, false), RowStates.pinGlass(false, false),
            RowStates.pinGlass(true, false), RowStates.lightning(true, false, false), RowStates.lightning(true, false, true),
        )
        for (m in missing) {
            assertFalse(m.on)
            assertTrue("'${m.note}' is a few words", m.note!!.isNotBlank() && m.note!!.split(' ').size <= 7)
        }
    }
}
