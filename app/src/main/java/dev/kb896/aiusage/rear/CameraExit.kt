package dev.kb896.aiusage.rear

/**
 * Whether Xiaomi's Camera has just left the back: it had the back's focus and now something else has (Xiaomi's
 * launcher, nothing, the Camera's own standby page). Whoever closed it and however, the back is then ours again
 * and lit (RearUserService.cameraLeftBack). Plain, so a test holds it.
 */
object CameraExit {
    fun left(was: String?, now: String?): Boolean = isCamera(was) && !isCamera(now)

    /** The Camera's own page on the back: its main activity, not the standby page it parks there while open on the front. */
    fun isCamera(top: String?): Boolean = top?.trim() == RearCover.CAMERA
}
