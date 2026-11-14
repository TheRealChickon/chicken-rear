package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModeTest {

    @Test
    fun `a camera use lights it, and closing gives back what it was`() {
        val c = CameraMode()
        assertTrue(c.start(enabled = true, allowed = true, before = CameraMode.Before.AOD))
        assertTrue(c.lit)
        assertFalse(c.start(true, true, CameraMode.Before.LIT)) // said again: no change
        assertEquals(CameraMode.Before.AOD, c.before)
        assertEquals(CameraMode.Before.AOD, c.end())
        assertEquals(CameraMode.State.OFF, c.state)
        assertNull(c.end())
    }

    @Test
    fun `switched off or refused, nothing lights`() {
        val c = CameraMode()
        assertFalse(c.start(enabled = false, allowed = true, before = CameraMode.Before.LIT))
        assertFalse(c.start(enabled = true, allowed = false, before = CameraMode.Before.LIT))
        assertEquals(CameraMode.State.OFF, c.state)
        assertNull(c.end())
    }

    @Test
    fun `the double tap switches it off until the camera closes, and the next session lights again`() {
        val c = CameraMode()
        c.start(true, true, CameraMode.Before.DARK)
        assertTrue(c.doubleTap())
        assertEquals(CameraMode.State.DARK, c.state)
        assertFalse(c.lit)
        assertFalse(c.doubleTap())
        // the watch saying the back is free again, mid-session: still off
        assertFalse(c.start(true, true, CameraMode.Before.LIT))
        assertEquals(CameraMode.State.DARK, c.state)
        // closed: nothing to give back — it is already off
        assertNull(c.end())
        assertTrue(c.start(true, true, CameraMode.Before.DARK))
        assertTrue(c.lit)
    }

    @Test
    fun `no double tap outside camera mode`() {
        val c = CameraMode()
        assertFalse(c.doubleTap())
        c.aside()
        assertFalse(c.doubleTap())
    }

    @Test
    fun `the camera's own rear preview puts it aside, and the preview leaving lights it again`() {
        val c = CameraMode()
        c.start(true, true, CameraMode.Before.LIT)
        assertTrue(c.aside()) // was lit: the brightness and the rest to undo
        assertEquals(CameraMode.State.ASIDE, c.state)
        assertFalse(c.aside())
        assertTrue(c.start(true, true, CameraMode.Before.LIT))
        assertTrue(c.lit)
    }

    @Test
    fun `a session that starts on the preview is aside from the first, and ends with nothing to undo`() {
        val c = CameraMode()
        assertFalse(c.aside())
        assertEquals(CameraMode.State.ASIDE, c.state)
        assertNull(c.end())
        assertEquals(CameraMode.State.OFF, c.state)
    }

    @Test
    fun `switched off in the dark is not a reason to stand aside`() {
        val c = CameraMode()
        c.start(true, true, CameraMode.Before.LIT)
        c.doubleTap()
        assertFalse(c.aside())
        assertEquals(CameraMode.State.DARK, c.state)
    }

    @Test
    fun `the table's dim comes back after the camera, since the table is only covered`() {
        // Camera mode does not touch the table: face down stays a fact through a session, and
        // the posture goes from FULL back to the dim when it ends (RearPosture).
        val c = CameraMode()
        val s = RearPosture.Settings()
        fun level() = RearPosture.wanted(RearPosture.Facts(faceDown = true, cameraInUse = c.lit), s)
        assertEquals(RearLevel.DIM, level())
        c.start(true, true, CameraMode.Before.LIT)
        assertEquals(RearLevel.FULL, level())
        c.end()
        assertEquals(RearLevel.DIM, level())
    }
}
