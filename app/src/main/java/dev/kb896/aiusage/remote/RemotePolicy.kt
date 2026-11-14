package dev.kb896.aiusage.remote

/**
 * What a caller that MAY ask is still not given.
 *
 * The gate answers "is this Home Assistant". This answers "and may Home Assistant open the
 * banking app on the back of a phone lying on a café table". The portal's own answer to that is
 * BackLock: while the phone is locked the back opens no app without the phone's code — but that
 * guard lives in the sheet's taps, and a command reaches the shell's launch without passing it.
 * So the same rule is kept here, in front of every launch that comes from outside.
 *
 * Pure: the lock and the lists are handed in.
 */
object RemotePolicy {

    /** Which apps a command may open on the back. */
    enum class Apps(val wire: String, val label: String) {
        /** Only what is pinned to the portal, which is what a finger on the back can open too. */
        PINNED("pinned", "Pinned apps only"),
        /** Pinned, and the packages listed for this. */
        LISTED("listed", "Pinned apps and a list"),
        ANY("any", "Any app");

        companion object { fun fromWire(s: String?): Apps = entries.firstOrNull { it.wire == s } ?: PINNED }
    }

    data class Rules(
        val apps: Apps = Apps.PINNED,
        /** For [Apps.LISTED]. */
        val listed: Set<String> = emptySet(),
        /** Apps that may be opened while the phone is locked; empty is none, the default. */
        val whileLocked: Set<String> = emptySet(),
    )

    sealed interface Verdict {
        data object Allowed : Verdict
        data class Denied(val reason: String) : Verdict
    }

    const val NOT_PINNED = "that app is not allowed from outside"
    const val LOCKED = "the phone is locked"
    const val NOT_AN_APP = "not a package name"

    /**
     * [pkg] opened on the back by a command. [locked]: the phone is locked and the back has not
     * been given its code (the shell's word where it has said, the keyguard's otherwise).
     */
    fun open(pkg: String, pinned: List<String>, rules: Rules, locked: Boolean): Verdict {
        if (RemoteParser.pkg(pkg) == null) return Verdict.Denied(NOT_AN_APP)
        val allowed = when (rules.apps) {
            Apps.PINNED -> pkg in pinned
            Apps.LISTED -> pkg in pinned || pkg in rules.listed
            Apps.ANY -> true
        }
        if (!allowed) return Verdict.Denied(NOT_PINNED)
        if (locked && pkg !in rules.whileLocked) return Verdict.Denied(LOCKED)
        return Verdict.Allowed
    }

    /**
     * Sending whatever is on the front to the back, or bringing a hidden guest up again: the
     * same exposure as opening one, with no package to allow-list, so simply not while locked.
     * Closing a guest, or putting the panel over it, exposes nothing and is always allowed.
     */
    fun guest(which: String, locked: Boolean): Verdict = when {
        which == "close" || which == "panel" || which == "front" -> Verdict.Allowed
        locked -> Verdict.Denied(LOCKED)
        else -> Verdict.Allowed
    }
}
