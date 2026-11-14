package dev.kb896.aiusage.ui

import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.ui.Readiness.Feature
import dev.kb896.aiusage.ui.Readiness.State

/**
 * What the companion screen (CompanionScreen) shows for each feature, built from [Readiness]:
 * its switch, whether it is greyed, and — only when something it needs is missing — one line
 * and one button. Nothing here gates anything else: a feature that is missing something greys
 * out alone.
 *
 * [restricted]: notification access was asked for in Android's listener settings and is still
 * not there, which on a sideloaded app is Android's restricted-settings block. The notifications
 * line then says how to lift it and its button opens App info, instead of asking again for a
 * page that will only refuse again.
 *
 * Pure, so it is tested on the JVM (CompanionRowsTest).
 */
object CompanionRows {

    /** The one button under a missing need, and what it says. */
    enum class Fix(val button: String) {
        OPEN_SHIZUKU("Open Shizuku"),
        ALLOW_SHIZUKU("Allow in Shizuku"),
        ALLOW_NOTIFICATIONS("Allow"),
        OPEN_APP_INFO("Open app info"),
    }

    data class Row(
        val feature: Feature,
        val title: String,
        /** The switch: on unless the user turned the feature off. Missing needs leave it on. */
        val on: Boolean,
        /** On, but something it needs is missing: drawn dimmed, with [line] and [fix] under it. */
        val greyed: Boolean,
        val line: String?,
        val fix: Fix?,
    )

    data class Status(val text: String, val detail: String?, val fix: Fix?, val working: Boolean)

    const val RESTRICTED_LINE =
        "Android blocked this for a sideloaded app: App info > menu > Allow restricted settings."

    fun fixOf(action: Readiness.Action, restricted: Boolean): Fix? = when (action) {
        Readiness.Action.OPEN_SHIZUKU -> Fix.OPEN_SHIZUKU
        Readiness.Action.ALLOW_SHIZUKU -> Fix.ALLOW_SHIZUKU
        Readiness.Action.ALLOW_NOTIFICATIONS -> if (restricted) Fix.OPEN_APP_INFO else Fix.ALLOW_NOTIFICATIONS
        Readiness.Action.NONE -> null
    }

    fun row(feature: Feature, state: State, restricted: Boolean = false): Row = when (state) {
        State.Ready -> Row(feature, feature.label, on = true, greyed = false, line = null, fix = null)
        State.Off -> Row(feature, feature.label, on = false, greyed = false, line = null, fix = null)
        is State.Missing -> {
            val blocked = restricted && state.action == Readiness.Action.ALLOW_NOTIFICATIONS
            Row(
                feature, feature.label, on = true, greyed = true,
                line = if (blocked) RESTRICTED_LINE else "Needs ${state.reason}." + (state.note?.let { " $it" } ?: ""),
                fix = fixOf(state.action, restricted),
            )
        }
    }

    /** One row per feature, in [Feature] order. */
    fun rows(states: Map<Feature, State>, restricted: Boolean = false): List<Row> =
        Feature.entries.map { row(it, states[it] ?: State.Off, restricted) }

    /** The status line at the top: "Working", or the first missing thing and its one button. */
    fun status(states: Map<Feature, State>, restricted: Boolean = false): Status {
        val line = Readiness.line(states)
        if (line.working) return Status("Working", "Xiaomi's rear screen, with more on top.", fix = null, working = true)
        val fix = fixOf(line.action, restricted)
        val detail = if (fix == Fix.OPEN_APP_INFO) RESTRICTED_LINE else null
        return Status(line.text, detail, fix, working = false)
    }

    /** Whether [feature]'s switch is on in [config]. */
    fun isOn(config: PanelConfig, feature: Feature): Boolean = when (feature) {
        Feature.MORE_APPS -> config.moreApps
        Feature.CHARGE -> config.chargeAnim
        Feature.NOTICES -> config.showNotifications
        Feature.ANIMATIONS -> config.wakeAnim
    }

    /** [config] with [feature]'s switch set to [on], and nothing else changed. */
    fun switched(config: PanelConfig, feature: Feature, on: Boolean): PanelConfig = when (feature) {
        Feature.MORE_APPS -> config.copy(moreApps = on)
        Feature.CHARGE -> config.copy(chargeAnim = on)
        Feature.NOTICES -> config.copy(showNotifications = on)
        Feature.ANIMATIONS -> config.copy(wakeAnim = on)
    }
}
