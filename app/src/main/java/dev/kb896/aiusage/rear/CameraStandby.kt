package dev.kb896.aiusage.rear

/**
 * Xiaomi's Camera, open on the front screen, starts a page of its own on the rear display:
 * com.android.camera/.fragment.presentation.MainScreenSelfieActivity. On the back it is a blank
 * standby page (a swipe on it moves the viewfinder there), and it covers Xiaomi's rear launcher
 * for as long as the camera is open. The same activity on the FRONT display is the other half of
 * a viewfinder already moved to the back, and com.android.camera/.Camera on the rear is that
 * viewfinder itself: neither is this.
 *
 * Pure: reads `dumpsys window displays | grep -E 'mDisplayId=|mFocusedApp='`.
 */
object CameraStandby {
    const val ACTIVITY = "com.android.camera/.fragment.presentation.MainScreenSelfieActivity"
    /** Its class name alone, as a log line about it has it. */
    const val CLASS = "MainScreenSelfieActivity"

    private val displayLine = Regex("""mDisplayId=(\d+)""")
    private val focusLine = Regex("""mFocusedApp=ActivityRecord\{\S+ u\d+ (\S+)""")

    /** The focused activity on [displayId], as "package/.Class", or null when the dump does not say. */
    fun focused(dump: String, displayId: Int): String? {
        var inDisplay = false
        for (line in dump.lineSequence()) {
            val id = displayLine.find(line)?.groupValues?.get(1)
            if (id != null) inDisplay = id == displayId.toString()
            if (inDisplay) focusLine.find(line)?.let { return it.groupValues[1].trimEnd('}') }
        }
        return null
    }

    /** Whether the camera's standby page is what the rear display [displayId] shows. */
    fun onRear(dump: String, displayId: Int): Boolean = focused(dump, displayId) == ACTIVITY
}
