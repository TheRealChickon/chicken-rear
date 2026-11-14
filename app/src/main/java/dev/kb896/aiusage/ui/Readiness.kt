package dev.kb896.aiusage.ui

/**
 * Companion mode's answer to "does it work": per feature, READY or MISSING with one plain
 * reason and one action — never one gate for everything.
 *
 * It replaces Setup.panelAllowed / Setup.allDone in companion mode. Those were a single AND of
 * every checkable step plus "Xiaomi's app out of the way", so one false (Xiaomi's rear app
 * simply being there, which in companion mode is the point) meant nothing worked, silently.
 * Here each feature asks only for what it uses:
 * - More apps, the charging animation and the wake animations draw as shell layers: Shizuku
 *   running, and this app allowed in it.
 * - Notifications need notification access, and Shizuku for their banner: it is a shell layer
 *   too, so without Shizuku nothing of ours shows on the back. Access is asked for first; with
 *   access there and Shizuku not, the row says so and notes that Xiaomi's own rear list still
 *   shows them (design 3.3). The test button reads the same state.
 * - The rear display itself, which every feature needs.
 * Battery optimisation and autostart are advice (Advanced), never an input here.
 *
 * Pure: the inputs are read by the caller (MainActivity), so it is tested on the JVM.
 */
object Readiness {

    enum class Feature(val label: String) {
        MORE_APPS("More apps"),
        CHARGE("Charging animation"),
        NOTICES("Notifications"),
        ANIMATIONS("Animations"),
    }

    /** What the one button under a missing feature does. */
    enum class Action { OPEN_SHIZUKU, ALLOW_SHIZUKU, ALLOW_NOTIFICATIONS, NONE }

    sealed interface State {
        /** Switched on, and everything it needs is there. */
        data object Ready : State
        /** Switched off by the user: nothing missing, nothing to say. */
        data object Off : State
        /** Switched on, and it needs [reason] (a plain noun phrase); [action] is the button that fixes it. */
        data class Missing(val reason: String, val action: Action, val note: String? = null) : State
    }

    data class Inputs(
        val shizukuRunning: Boolean,
        val shizukuGranted: Boolean,
        val rearDisplay: Boolean,
        val notificationAccess: Boolean,
        val moreApps: Boolean = true,
        val chargeAnim: Boolean = true,
        val notices: Boolean = true,
        val wakeAnim: Boolean = true,
        /** Read for the advice line only: whatever it says, no feature is held back by it. */
        val batteryUnrestricted: Boolean = true,
    )

    /** Every feature's state, in [Feature] order. */
    fun of(inputs: Inputs): Map<Feature, State> = Feature.entries.associateWith { state(it, inputs) }

    fun state(feature: Feature, inputs: Inputs): State {
        val on = when (feature) {
            Feature.MORE_APPS -> inputs.moreApps
            Feature.CHARGE -> inputs.chargeAnim
            Feature.NOTICES -> inputs.notices
            Feature.ANIMATIONS -> inputs.wakeAnim
        }
        if (!on) return State.Off
        if (!inputs.rearDisplay) return State.Missing("a rear display, and none is found", Action.NONE)
        if (feature == Feature.NOTICES && !inputs.notificationAccess) {
            return State.Missing("notification access", Action.ALLOW_NOTIFICATIONS)
        }
        // The banner on the back is a shell layer, as the other three are.
        val note = if (feature == Feature.NOTICES) NOTICES_WITHOUT_SHIZUKU else null
        return when {
            !inputs.shizukuRunning -> State.Missing("Shizuku running", Action.OPEN_SHIZUKU, note)
            !inputs.shizukuGranted -> State.Missing("this app allowed in Shizuku", Action.ALLOW_SHIZUKU, note)
            else -> State.Ready
        }
    }

    /** The Notifications row's note when only Shizuku is missing: what still works without it. */
    const val NOTICES_WITHOUT_SHIZUKU =
        "That is the banner on the back. Xiaomi's own rear list still shows them while locked."

    /** The status line: "Working", or the first thing missing with the features it holds back. */
    data class Line(val text: String, val action: Action, val working: Boolean)

    fun line(states: Map<Feature, State>): Line {
        val missing = states.entries.mapNotNull { (f, s) -> (s as? State.Missing)?.let { f to it } }
        if (missing.isEmpty()) return Line("Working", Action.NONE, working = true)
        // The first reason, and every feature it holds back: one line, one button.
        val first = missing.first().second
        // The same need is the same reason and button; a row's own note does not split them.
        val names = missing.filter { it.second.reason == first.reason && it.second.action == first.action }.map { it.first.label }
        val who = when (names.size) {
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
        val text = "$who ${if (names.size == 1) "needs" else "need"} ${first.reason}"
        return Line(text, first.action, working = false)
    }
}
