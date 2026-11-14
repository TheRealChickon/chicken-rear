package dev.kb896.aiusage.rear

/**
 * Notification access through the shell (IRearService.allowListener): `cmd notification
 * allow_listener`, which is what gets a sideloaded app past Android's restricted-settings block
 * without the user digging through App info first (Mi Back Screen does the same).
 *
 * At shell uid a grant for any component would hand notification access to whatever asked, so
 * the command is only built for one of [ownPackage]'s own components, and only from plain
 * package and class characters: it goes through `sh -c`, where `$`, quotes or a space would be
 * read as shell. Pure, so the refusals are tested on the JVM.
 */
object ListenerGrant {

    private val SAFE = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.]+$")

    /** The full component (package/fully.qualified.Class), or null if it is not ours or not plain. */
    fun normalised(component: String, ownPackage: String): String? {
        val c = component.trim()
        if (!SAFE.matches(c)) return null
        val (pkg, cls) = c.split('/', limit = 2)
        // The class in full: the short "pkg/.Relay" resolves against the package, and this
        // app's code namespace (dev.kb896.aiusage) is not its package on any build.
        if (pkg != ownPackage || cls.isEmpty() || cls.startsWith(".")) return null
        return c
    }

    /** The shell command for [component], or null when it is refused (see [normalised]). */
    fun command(component: String, ownPackage: String): String? =
        normalised(component, ownPackage)?.let { "cmd notification allow_listener $it" }

    /**
     * Whether [component] is in secure enabled_notification_listeners ([setting], colon
     * separated; "null" or blank when none). Compared whole, so a longer name that merely
     * starts with ours does not count.
     */
    fun allowed(setting: String?, component: String): Boolean =
        setting.orEmpty().split(':').map { it.trim() }.any { it == component }
}
