package dev.kb896.aiusage.rear

object RearCameraRoute {
    const val CLASS = "dev.kb896.aiusage.ui.camera.RearCameraActivity"
    const val COMPONENT = "${dev.kb896.aiusage.BuildConfig.APPLICATION_ID}/$CLASS"

    /** How long the shell waits for ours to be on top of the back before it opens Xiaomi's instead. */
    const val LAUNCH_WAIT_MS = 2_500L
    const val LAUNCH_LOOK_MS = 150L

    enum class Route { OURS, XIAOMI }

    /** Which one, and why (said in the log). */
    fun route(enabled: Boolean, granted: Boolean, locked: Boolean, display: Int): Pair<Route, String> = when {
        display <= 0 -> Route.XIAOMI to "no rear display known"
        !enabled -> Route.XIAOMI to "\"Our camera on the back\" is off"
        !granted -> Route.XIAOMI to "the app has no camera permission"
        locked -> Route.XIAOMI to "the phone is locked"
        else -> Route.OURS to "ours"
    }

    /** What `am start` said: a start, not an error. Taking the back is proven only by [arrived]. */
    fun accepted(output: String): Boolean = output.contains("Starting: Intent") &&
        !output.contains("Error", ignoreCase = true) && !output.contains("Exception", ignoreCase = true)

    /** Whether the back's top app ([top], "package/.Activity" as the window manager names it) is our camera page. */
    fun isOurs(top: String?): Boolean = top?.trim()?.let { it == COMPONENT || it == "${dev.kb896.aiusage.BuildConfig.APPLICATION_ID}/.ui.camera.RearCameraActivity" } == true

    /** Whether the start has arrived: our page on top of the back. */
    fun arrived(top: String?): Boolean = isOurs(top)
}
