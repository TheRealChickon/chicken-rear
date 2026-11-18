package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.RearCameraRoute.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which camera the stroke from the left opens on the back, and what proves ours came. */
class RearCameraRouteTest {
    @Test fun `ours only with the setting on, the permission given, the phone unlocked and a back known`() {
        for (enabled in listOf(false, true)) for (granted in listOf(false, true)) for (locked in listOf(false, true)) for (display in -1..2) {
            val (route, why) = RearCameraRoute.route(enabled, granted, locked, display)
            val ours = enabled && granted && !locked && display > 0
            assertEquals("$enabled $granted $locked $display", if (ours) Route.OURS else Route.XIAOMI, route)
            assertTrue("every route says why", why.isNotBlank())
        }
        assertTrue(RearCameraRoute.route(true, false, false, 2).second.contains("permission"))
        assertTrue(RearCameraRoute.route(true, true, true, 2).second.contains("locked"))
        assertTrue(RearCameraRoute.route(false, true, false, 2).second.contains("off"))
    }

    @Test fun `a start is accepted only when am start said so and nothing else`() {
        assertTrue(RearCameraRoute.accepted("Starting: Intent { cmp=example.app/.Camera }"))
        assertTrue("brought back to the front", RearCameraRoute.accepted(
            "Starting: Intent { cmp=example.app/.Camera }\nWarning: Activity not started, its current task has been brought to the front"))
        for (out in listOf("", "Error: Activity not started, unable to resolve Intent", "java.lang.SecurityException: Permission Denial",
            "Starting: Intent { cmp=example.app/.Camera }\nError: Activity class does not exist.")) {
            assertFalse("'$out'", RearCameraRoute.accepted(out))
        }
    }

    @Test fun `our page is known by the name the window manager gives it, in either form`() {
        assertTrue(RearCameraRoute.isOurs(RearCameraRoute.COMPONENT))
        assertTrue(RearCameraRoute.isOurs(" ${RearCameraRoute.COMPONENT} "))
        assertTrue(RearCameraRoute.arrived(RearCameraRoute.COMPONENT))
        for (other in listOf(null, "", RearCover.LAUNCHER, RearCover.CAMERA, "${RearCover.OWN}/dev.kb896.aiusage.ui.MainActivity",
            "${RearCover.OWN}/dev.kb896.aiusage.ui.camera.RearCameraActivityX")) {
            assertFalse("$other", RearCameraRoute.isOurs(other))
        }
    }

    @Test fun `our page leaving the back lights the moon, as Xiaomi's Camera leaving does`() {
        assertTrue(CameraExit.left(RearCameraRoute.COMPONENT, RearCover.LAUNCHER))
        assertTrue(CameraExit.left(RearCameraRoute.COMPONENT, null))
        assertFalse(CameraExit.left(RearCameraRoute.COMPONENT, RearCameraRoute.COMPONENT))
        assertFalse("another page of ours is no camera", CameraExit.left("${RearCover.OWN}/dev.kb896.aiusage.ui.MainActivity", RearCover.LAUNCHER))
        assertTrue(CameraExit.left(RearCover.CAMERA, RearCover.LAUNCHER))
    }

    @Test fun `the covers step aside for our page, it takes its own touches`() {
        assertFalse(RearCover.wanted(true, RearCameraRoute.COMPONENT))
        assertFalse(RearCover.wanted(false, RearCameraRoute.COMPONENT))
    }
}
