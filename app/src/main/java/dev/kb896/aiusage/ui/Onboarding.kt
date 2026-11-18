package dev.kb896.aiusage.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.widget.LinearLayout
import dev.kb896.aiusage.place.PlacePermission
import dev.kb896.aiusage.ui.companion.CompanionUi

internal object Onboarding {
    enum class Step { SHIZUKU, NOTIFICATIONS, OPTIONAL, BATTERY }
    enum class Action { MANAGER, GUIDE, AUTHORIZE, LISTENER, LOCATION, CAMERA, OVERLAY, POWER }

    fun build(column: LinearLayout, step: Step, running: Boolean, granted: Boolean, act: (Action) -> Unit,
              advance: () -> Unit, finish: () -> Unit) {
        val c = column.context
        fun card(title: String, why: String) = CompanionUi.card(column).also {
            SettingsRows.title(it, title)
            SettingsRows.note(it, why)
        }
        fun button(parent: LinearLayout, label: String, action: Action) {
            SettingsRows.navigation(parent, label) { act(action) }
        }
        fun allowed(yes: Boolean) = if (yes) "Allowed" else "Not allowed"
        when (step) {
            Step.SHIZUKU -> {
                SettingsRows.note(column, "Put faces, messages and place notes on your rear display.")
                val panel = card("Shizuku", "Start Shizuku again after every reboot.")
                val installed = try {
                    c.packageManager.getApplicationInfo(Setup.SHIZUKU_PACKAGE, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) { false }
                val manager = if (installed) "Installed" else "Not installed"
                val service = if (running) "running" else "stopped"
                SettingsRows.note(panel, "$manager · $service")
                button(panel, if (installed) "Open Shizuku" else "Get Shizuku", Action.MANAGER)
                button(panel, "Shizuku guide", Action.GUIDE)
                val access = if (running) allowed(granted) else "Start Shizuku to check access"
                SettingsRows.note(panel, "Chicken Rear · $access")
                if (running && !granted) button(panel, "Allow Chicken Rear", Action.AUTHORIZE)
            }
            Step.NOTIFICATIONS -> {
                val panel = card("Notification access", "Show incoming messages on the back.")
                val access = Setup.notificationAccess(c)
                SettingsRows.note(panel, allowed(access))
                button(panel, if (access) "Notification settings" else "Allow notifications", Action.LISTENER)
            }
            Step.OPTIONAL -> {
                SettingsRows.note(column, "Optional permissions")
                val location = card("Location", "Show a place note when you arrive.")
                SettingsRows.note(location, PlacePermission.state(PlacePermission.fine(c), PlacePermission.always(c),
                    PlacePermission.coarse(c), PlacePermission.locationOn(c)))
                button(location, "Location settings", Action.LOCATION)
                val camera = card("Camera", "Take photos with the rear display as your viewfinder.")
                SettingsRows.note(camera, allowed(c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED))
                button(camera, "Camera permission", Action.CAMERA)
                val overlay = card("Display over other apps", "Show the animation when earbuds connect.")
                SettingsRows.note(overlay, allowed(Settings.canDrawOverlays(c)))
                button(overlay, "Overlay permission", Action.OVERLAY)
            }
            Step.BATTERY -> {
                val panel = card("Battery", "HyperOS can freeze Chicken Rear. Choose “No restrictions”.")
                val exempt = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
                val restricted = c.getSystemService(ActivityManager::class.java).isBackgroundRestricted
                SettingsRows.note(panel, when {
                    restricted -> "Android restricts background use"
                    exempt -> "Android battery optimization is off"
                    else -> "Android battery optimization is on"
                })
                SettingsRows.note(panel, "Check the HyperOS setting in App info.")
                button(panel, "Open App info", Action.POWER)
            }
        }
        SettingsRows.navigationButton(column, if (step == Step.BATTERY) "Done" else "Continue", onClick = advance)
        if (step != Step.BATTERY) SettingsRows.navigationButton(column, "Skip setup", onClick = finish)
    }
}
