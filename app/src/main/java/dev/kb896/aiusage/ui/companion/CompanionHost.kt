package dev.kb896.aiusage.ui.companion

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.rear.WakeStyle
import dev.kb896.aiusage.service.ChargeLevel
import dev.kb896.aiusage.service.Graph
import dev.kb896.aiusage.ui.CompanionRows
import dev.kb896.aiusage.ui.Readiness
import dev.kb896.aiusage.ui.SettingsRows
import dev.kb896.aiusage.ui.glass.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a companion sub-page asks of the screen that holds it (CompanionScreen). */
interface CompanionHost {
    val activity: ComponentActivity
    val graph: Graph
    fun config(): PanelConfig
    /** Saves at once and hands the shell the new config, as every setting is. */
    fun write(edit: (PanelConfig) -> PanelConfig)
    /** The page again, from the stored config and what is there now. */
    fun rebuild()
    /** The feature's row as the home screen has it: on, greyed, its line and button. */
    fun row(feature: Readiness.Feature): CompanionRows.Row
    /** Runs a missing need's one button. */
    fun fix(fix: CompanionRows.Fix)
    fun toast(text: String)
    fun askCameraPermission(permission: String)
    /** Asks for POST_NOTIFICATIONS (only where Android has it), then [then] with the answer. */
    fun askPostNotifications(then: (Boolean) -> Unit)
    /** Rear mode changed (Replace on or off): the activity shows the other screen. */
    fun modeChanged()
    /** The app's log and a short summary, to share. */
    fun shareLog()
}

/** The bits every page builds with: a card, and the one line and button under a missing need. */
object CompanionUi {

    fun card(parent: LinearLayout): GlassCard = GlassCard(parent.context).also {
        parent.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** A greyed feature's line and button, at the top of its page; nothing for one that is fine. */
    fun missing(parent: LinearLayout, row: CompanionRows.Row, host: CompanionHost) {
        if (!row.greyed) return
        row.line?.let { SettingsRows.note(parent, it) }
        row.fix?.let { fix -> SettingsRows.navigationButton(parent, fix.button) { host.fix(fix) } }
    }

    /** The rest of a page dimmed while its feature is greyed or off: still there, still settable. */
    fun dim(view: View, row: CompanionRows.Row) {
        view.alpha = if (row.greyed || !row.on) 0.55f else 1f
    }

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /**
     * "Play on the back": [preview] on the rear now, waking it if it is dark (the bridge's
     * declines hold). Needs Shizuku, as the feature does: without it, the feature's own button.
     */
    fun playOnBack(host: CompanionHost, feature: Readiness.Feature, preview: WakeStyle.Preview) {
        val bridge = host.graph.bridge
        if (!bridge.status().ready) {
            host.row(feature).fix?.let(host::fix) ?: host.toast("Shizuku first")
            return
        }
        host.activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { bridge.playWake(preview) }
            if (!ok) host.toast("The back did not light up: it may be switched off by hand, face down or in a pocket")
        }
    }

    /** The battery's percent now, from the sticky ACTION_BATTERY_CHANGED; 50 when it does not say. */
    fun batteryPercent(context: Context): Int {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return ChargeLevel.percent(
            i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1,
            i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1,
        ) ?: 50
    }
}
