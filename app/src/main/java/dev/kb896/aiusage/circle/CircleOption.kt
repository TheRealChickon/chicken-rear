package dev.kb896.aiusage.circle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import dev.kb896.aiusage.service.Graph
import dev.kb896.aiusage.service.PinGlassService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object CircleOption {
    const val STANDALONE = "dev.chicken.circlesearch"

    data class State(
        val requested: Boolean = false,
        val standaloneInstalled: Boolean = false,
        val serviceEnabled: Boolean = false,
        val serviceBound: Boolean = false,
        val shizukuRunning: Boolean = false,
        val shizukuGranted: Boolean = false,
        val shellBound: Boolean = false,
        val launcherAvailable: Boolean = false,
        val listening: Boolean = false,
    ) {
        val available get() = !standaloneInstalled && serviceEnabled && serviceBound &&
            shizukuRunning && shizukuGranted && shellBound && launcherAvailable
        val active get() = available && requested && listening
        val hint get() = when {
            standaloneInstalled -> "Standalone Circle Search is installed"
            !serviceEnabled -> "Enable the accessibility service"
            !serviceBound -> "Waiting for accessibility"
            !shizukuRunning -> "Start Shizuku"
            !shizukuGranted -> "Allow Chicken Rear in Shizuku"
            !shellBound -> "Connecting to Shizuku"
            !launcherAvailable -> "Requires the HyperOS launcher"
            requested && !listening -> "Waiting for the gesture listener"
            else -> "Hold the navigation bar"
        }
    }

    private val current = MutableStateFlow(State())
    val changes = current.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private fun prefs(c: Context) = c.getSharedPreferences("circle_search", Context.MODE_PRIVATE)
    private fun uid(c: Context, pkg: String): Int? = try {
        c.packageManager.getApplicationInfo(pkg, 0).uid
    } catch (_: PackageManager.NameNotFoundException) { null }

    /** Current prerequisites and effective setting, also used by onboarding. */
    fun state(context: Context): State {
        val bridge = Graph.of(context).bridge
        val shell = bridge.status()
        return State(prefs(context).getBoolean("enabled", false), uid(context, STANDALONE) != null,
            PinGlassService.enabled(context), PinGlassService.running(), shell.running, shell.granted,
            bridge.shellBound(), uid(context, "com.miui.home") != null, bridge.circleListening)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (!enabled || state(context).available) prefs(context).edit().putBoolean("enabled", enabled).apply()
        refresh(context)
    }

    fun refresh(context: Context, reconnect: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { refresh(context, reconnect) }
            return
        }
        val next = state(context)
        val bridge = Graph.of(context).bridge
        val launcher = uid(context, "com.miui.home")
        bridge.syncCircle(next.available && next.requested, launcher ?: -1, reconnect)
        val applied = next.copy(listening = bridge.circleListening)
        PinGlassService.enableCircle(applied.active)
        current.value = applied
    }

    fun observePackages(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.data?.schemeSpecificPart in setOf(STANDALONE, "com.miui.home") &&
                    !(i.action == Intent.ACTION_PACKAGE_REMOVED && i.getBooleanExtra(Intent.EXTRA_REPLACING, false))) refresh(c)
            }
        }, filter, Context.RECEIVER_NOT_EXPORTED)
    }
}
