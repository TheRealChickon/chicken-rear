package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStandbyTest {
    // As the phone printed it with the camera open on the front.
    private val standby = """
          mDisplayId=0
          mFocusedApp=ActivityRecord{259316961 u0 com.android.camera/.Camera t556}
          mDisplayId=1
          mFocusedApp=ActivityRecord{265786623 u0 com.android.camera/.fragment.presentation.MainScreenSelfieActivity t557}
    """.trimIndent()

    @Test fun `the camera's standby page on the rear is found`() {
        assertEquals(CameraStandby.ACTIVITY, CameraStandby.focused(standby, 1))
        assertTrue(CameraStandby.onRear(standby, 1))
    }

    @Test fun `the viewfinder moved to the rear is left alone`() {
        val moved = """
            mDisplayId=0
            mFocusedApp=ActivityRecord{165460609 u0 com.android.camera/.fragment.presentation.MainScreenSelfieActivity t559}
            mDisplayId=1
            mFocusedApp=ActivityRecord{73663593 u0 com.android.camera/.Camera t558}
        """.trimIndent()
        assertFalse(CameraStandby.onRear(moved, 1))
    }

    @Test fun `Xiaomi's launcher on the rear is not the standby page`() {
        val home = """
            mDisplayId=0
            mFocusedApp=ActivityRecord{259316961 u0 com.android.camera/.Camera t556}
            mDisplayId=1
            mFocusedApp=ActivityRecord{16462389 u0 com.xiaomi.subscreencenter/.SubScreenLauncher t15}
        """.trimIndent()
        assertFalse(CameraStandby.onRear(home, 1))
        assertEquals("com.xiaomi.subscreencenter/.SubScreenLauncher", CameraStandby.focused(home, 1))
    }

    @Test fun `a dump that does not say is not the standby page`() {
        assertFalse(CameraStandby.onRear("", 1))
        assertFalse(CameraStandby.onRear("mDisplayId=1\n  mFocusedApp=null", 1))
    }
}
