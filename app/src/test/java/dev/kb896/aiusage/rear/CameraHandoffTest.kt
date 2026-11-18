package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraHandoffTest {
    private val preview = "com.android.camera/.fragment.presentation.MainScreenSelfieActivity"
    private val fullPreview = "com.android.camera/com.android.camera.fragment.presentation.MainScreenSelfieActivity"

    private fun topOnRear(front: String, rear: String): String? = RearCover.focusOf(
        """
            mDisplayId=0
            mFocusedApp=ActivityRecord{abc123 u0 $front t10}
            mDisplayId=1
            mFocusedApp=ActivityRecord{def456 u0 $rear t11}
        """.trimIndent(),
        1,
    )

    @Test fun `both covers yield to the rear presentation even with no rear input focus`() {
        for (component in listOf(preview, fullPreview)) {
            val top = topOnRear(RearCover.CAMERA, component)
            assertEquals(component, top)
            assertFalse(RearCover.wanted(true, top))
        }
    }

    @Test fun `a camera activity on the front does not uncover the rear launcher`() {
        for (component in listOf(RearCover.CAMERA, preview, fullPreview)) {
            val top = topOnRear(component, RearCover.LAUNCHER)
            assertEquals(RearCover.LAUNCHER, top)
            assertTrue(RearCover.wanted(true, top))
        }
        assertTrue(RearCover.wanted(true, "com.example.camera/.MainScreenSelfieActivity"))
    }

    @Test fun `the rear viewfinder keeps the back when its presentation moves to the front`() {
        val top = topOnRear(preview, RearCover.CAMERA)
        assertEquals(RearCover.CAMERA, top)
        assertFalse(RearCover.wanted(true, top))
        assertFalse(CameraExit.left(preview, top))
        assertFalse(CameraExit.left(top, fullPreview))
    }

    @Test fun `our camera yields the covers and returning to the launcher restores them`() {
        val ours = topOnRear(RearCover.CAMERA, RearCameraRoute.COMPONENT)
        assertTrue(RearCameraRoute.isOurs(ours))
        assertFalse(RearCover.wanted(true, ours))
        assertTrue(CameraExit.left(ours, RearCover.LAUNCHER))
        assertTrue(RearCover.wanted(true, RearCover.LAUNCHER))
        val frontOnly = topOnRear(RearCameraRoute.COMPONENT, RearCover.LAUNCHER)
        assertTrue(RearCover.wanted(true, frontOnly))
    }

    @Test fun `camera exit restores the cover when the launcher returns or focus clears`() {
        for (component in listOf(RearCover.CAMERA, preview, fullPreview)) {
            for (next in listOf(RearCover.LAUNCHER, null)) {
                assertTrue(CameraExit.left(component, next))
                assertTrue(RearCover.wanted(true, next))
            }
        }
    }
}
