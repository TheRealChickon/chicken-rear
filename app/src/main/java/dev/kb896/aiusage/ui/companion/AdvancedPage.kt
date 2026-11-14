package dev.kb896.aiusage.ui.companion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.RearMode
import dev.kb896.aiusage.service.Graph
import dev.kb896.aiusage.ui.Modal
import dev.kb896.aiusage.ui.Privacy
import dev.kb896.aiusage.ui.SettingsRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What almost nobody needs: the posture rules, Replace mode, keeping the app alive, the log.
 *
 * The posture rules come in two groups, because on Xiaomi's base they are two kinds of thing.
 * Those under "These act on Xiaomi's screen" light or dim Xiaomi's rear screen by themselves,
 * which fights its own screen management, so they start off (PanelConfig.companionStart).
 * "Off when lying on its back" only holds back this app's own wakes, so it stays on.
 * Always-on and "stay on in the hand" are not here: they relight the back after Xiaomi turns
 * it off, which is Xiaomi's AOD's job; they live on in Replace mode.
 */
object AdvancedPage {

    fun build(column: LinearLayout, host: CompanionHost) {
        val context = host.activity
        val config = host.config()

        val xiaomi = CompanionUi.card(column)
        SettingsRows.title(xiaomi, "These act on Xiaomi's screen")
        SettingsRows.note(xiaomi, "Off to start: each one lights or dims Xiaomi's rear screen by itself.")
        SettingsRows.toggle(xiaomi, "Wake when picked up", config.wakeOnLift) { on -> host.write { it.copy(wakeOnLift = on) } }
        SettingsRows.toggle(xiaomi, "Face down: back bright, front dimmed", config.tableMode) { on -> host.write { it.copy(tableMode = on) }; host.rebuild() }
        if (config.tableMode) {
            val under = SettingsRows.sub(xiaomi)
            SettingsRows.toggle(under, "Only while charging", config.tableChargingOnly) { on -> host.write { it.copy(tableChargingOnly = on) } }
        }
        SettingsRows.toggle(xiaomi, "Full brightness while the camera is open", config.cameraLight) { on -> host.write { it.copy(cameraLight = on) } }
        SettingsRows.toggle(xiaomi, "Switch the back off in a pocket", config.pocketMode) { on -> host.write { it.copy(pocketMode = on) } }

        val ours = CompanionUi.card(column)
        SettingsRows.title(ours, "These only hold back Chicken Rear")
        SettingsRows.toggle(ours, "Not while lying on its back", config.offOnBack) { on -> host.write { it.copy(offOnBack = on) } }
        SettingsRows.note(ours, "Chicken Rear never wakes the back in a pocket, or after you double tap it off.")

        val keep = CompanionUi.card(column)
        SettingsRows.title(keep, "Keep it running")
        SettingsRows.note(keep, "Advice only: nothing waits on these. Without them HyperOS may stop the app in the background.")
        val unrestricted = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
        if (unrestricted) SettingsRows.note(keep, "Battery: no restrictions.")
        else SettingsRows.navigation(keep, "Battery: no restrictions") {
            Privacy.disclose(context, Privacy.Access.BATTERY, next = "Next: this app's page. Set its battery use to no restrictions.") {
                open(host, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", context.packageName, null)))
            }
        }
        SettingsRows.navigation(keep, "Autostart") {
            Privacy.disclose(context, Privacy.Access.AUTOSTART, next = "Next: HyperOS's Autostart list. Turn it on for this app.") {
                open(host, Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            }
        }

        val help = CompanionUi.card(column)
        SettingsRows.title(help, "Help")
        SettingsRows.navigation(help, "Share the log", icon = R.drawable.ic_upload) { host.shareLog() }
        SettingsRows.navigation(help, "Privacy policy", link = true) { Privacy.openPolicy(context) }
    }

    private fun open(host: CompanionHost, intent: Intent) {
        try { host.activity.startActivity(intent) } catch (_: Exception) { host.toast("Could not open that settings page") }
    }
}
