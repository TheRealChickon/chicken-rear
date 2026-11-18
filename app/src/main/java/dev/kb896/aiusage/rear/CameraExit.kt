package dev.kb896.aiusage.rear

/** Camera ownership ends when a different app takes the rear display or its focus clears. */
object CameraExit {
    fun left(was: String?, now: String?): Boolean = isCamera(was) && !isCamera(now)

    /** The top activity read from the rear display, including Xiaomi's live preview and our own camera. */
    fun isCamera(top: String?): Boolean = top?.trim()?.startsWith("${RearCover.CAMERA_PKG}/") == true || RearCameraRoute.isOurs(top)
}
