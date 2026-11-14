package dev.kb896.aiusage.ui.home

/**
 * WHAT A SETTINGS ROW SHOWS IS WHAT IS REAL NOW, not the stored wish. A row that leans on something outside the app
 * (a permission, a system switch, a position) is ON only while that thing is there; missing it, the row is off and
 * says in a few words what is missing ([note]); a tap then asks for it, and the row is on by itself when he comes
 * back with it given. Pure: the page reads the phone (HomeModel.refresh, every time it comes to the front) and asks
 * here.
 */
data class RowState(val on: Boolean, val note: String? = null)

object RowStates {
    private val OFF = RowState(false)
    private val ON = RowState(true)

    /** "Follows the music": needs the permission to hear the sound's level. */
    fun followsMusic(stored: Boolean, audioAllowed: Boolean): RowState = when {
        !stored -> OFF
        audioAllowed -> ON
        else -> RowState(false, "Needs permission. Tap to allow")
    }

    /** "Notifications": needs notification access for the relay. */
    fun notifications(stored: Boolean, access: Boolean): RowState = when {
        !stored -> OFF
        access -> ON
        else -> RowState(false, "Needs notification access. Tap to allow")
    }

    /** "Glass when you pin": the accessibility service, his to switch on in the phone's settings; on only when it runs. */
    fun pinGlass(enabledInSettings: Boolean, running: Boolean): RowState = when {
        running -> RowState(true, "On")
        enabledInSettings -> RowState(false, "On in Accessibility, not started yet")
        else -> RowState(false, "Off in Accessibility")
    }

    /** A note pinned for a place: what its line says, by the location permission as it is now. */
    fun placeNote(fine: Boolean, always: Boolean): String = when {
        !fine -> "Needs location"
        !always -> "Needs location “all the time”"
        else -> "Comes back at its place"
    }

    /** "Lightning when it storms": needs a position for the weather (remembered from the last time this page was open with location allowed). */
    fun lightning(stored: Boolean, placeKnown: Boolean, locationAllowed: Boolean): RowState = when {
        !stored -> OFF
        placeKnown -> ON
        locationAllowed -> RowState(false, "No position yet")
        else -> RowState(false, "Needs location. Tap to allow")
    }

    /** An account's row on the usage section: signed in or not, as the stored sign-in is now. */
    fun account(signedIn: Boolean, signingIn: Boolean): String = when {
        signingIn -> "Signing in…"
        signedIn -> "Signed in"
        else -> "Not signed in"
    }

    /**
     * What keeps the whole back from working, if anything: said once, at the top (the page's strip), since every
     * switch under it shows a setting that is not in force then.
     */
    fun back(shizukuRunning: Boolean, shizukuGranted: Boolean, rearFound: Boolean): String? = when {
        !shizukuRunning -> "Shizuku is not running"
        !shizukuGranted -> "Allow Chicken Rear in Shizuku"
        !rearFound -> "The back screen was not found"
        else -> null
    }
}
