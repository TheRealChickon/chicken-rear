package dev.kb896.aiusage.service

import dev.kb896.aiusage.data.NoticeAppsMode
import dev.kb896.aiusage.data.PanelConfig

/**
 * Which notifications reach the back, and with which words. Pure: tested on the JVM
 * (NoticeFilterTest); NotificationRelay and the shell's banner ask it.
 */
object NoticeFilter {

    /**
     * Whether [pkg]'s notifications reach the back: every app but those picked (ALL_EXCEPT, the
     * default: nothing picked is everything), or only those picked (ONLY: nothing picked is none).
     * [alwaysPass]: the settings' own test notification, which shows whatever is picked — it is
     * there to see the banner at once.
     */
    fun appAllowed(pkg: String, mode: NoticeAppsMode, apps: Set<String>, alwaysPass: Boolean = false): Boolean {
        if (alwaysPass) return true
        return when (mode) {
            NoticeAppsMode.ALL_EXCEPT -> pkg !in apps
            NoticeAppsMode.ONLY -> pkg in apps
        }
    }

    fun appAllowed(pkg: String, config: PanelConfig, alwaysPass: Boolean = false): Boolean =
        appAllowed(pkg, config.noticeAppsMode, config.noticeApps, alwaysPass)

    /**
     * Whether the message's text is shown. The back faces away from its user, so the text is
     * its own choice ([showText]), and while the phone is [locked] a second one again
     * ([textWhenLocked], off by default): locked, a banner says whose it is and "New notification".
     */
    fun textShown(locked: Boolean, showText: Boolean, textWhenLocked: Boolean): Boolean =
        showText && (!locked || textWhenLocked)

    fun textShown(locked: Boolean, config: PanelConfig): Boolean =
        textShown(locked, config.showNotificationText, config.noticeTextWhenLocked)

    /**
     * Whether a banner is left out while the phone is [locked]: "Skip while locked (Xiaomi shows
     * them)" ([skipWhileLocked], off by default), as Xiaomi's own rear list fills only then.
     */
    /**
     * A notification its app marked secret (Notification.VISIBILITY_SECRET, -1) stays off the back
     * while the phone is locked, as it stays off the lock screen. The only visibility that is
     * obeyed: private ones show their words, which is what the text's switches are for.
     */
    fun secret(locked: Boolean, visibility: Int): Boolean = locked && visibility == -1

    fun skipped(locked: Boolean, skipWhileLocked: Boolean): Boolean = locked && skipWhileLocked

    /** The headline and text a [locked] back shows when its words are hidden. */
    const val LOCKED_TITLE = "New notification"
}
