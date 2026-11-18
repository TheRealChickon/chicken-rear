package dev.kb896.aiusage.ui.companion

import android.widget.LinearLayout
import dev.kb896.aiusage.ui.SettingsRows
import dev.kb896.aiusage.update.UpdateState
import dev.kb896.aiusage.update.UpdateState.Check
import dev.kb896.aiusage.update.UpdateState.Download

internal object UpdateRows {
    fun build(column: LinearLayout, state: UpdateState, check: () -> Unit, update: () -> Unit) {
        val card = CompanionUi.card(column)
        val checking = state.check == Check.CHECKING
        val downloading = state.download == Download.DOWNLOADING
        SettingsRows.navigation(card, if (checking) "Checking for updates…" else "Check for updates", onClick = check)
            .isEnabled = !checking && !downloading
        val checkNote = when (state.check) {
            Check.CURRENT -> "Chicken Rear is up to date."
            Check.LIMITED -> "GitHub is limiting checks. Try again later."
            Check.FAILED -> "Could not check GitHub. Try again."
            else -> null
        }
        checkNote?.let { SettingsRows.note(card, it) }
        val release = state.release ?: return
        val title = when (state.download) {
            Download.DOWNLOADING -> "Downloading ${release.version}…"
            Download.READY -> "Install ${release.version}"
            else -> "Update to ${release.version}"
        }
        SettingsRows.navigation(card, title, onClick = update).isEnabled = !checking && !downloading
        val downloadNote = when (state.download) {
            Download.FAILED -> "Could not download the update. Try again."
            Download.BLOCKED_HOST -> "GitHub’s download host is outside the allowed list."
            Download.UNVERIFIED -> "The update could not be verified."
            else -> null
        }
        downloadNote?.let { SettingsRows.note(card, it) }
    }
}
