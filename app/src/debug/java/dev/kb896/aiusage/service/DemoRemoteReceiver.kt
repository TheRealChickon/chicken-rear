package dev.kb896.aiusage.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.kb896.aiusage.data.ConfigWriter
import dev.kb896.aiusage.remote.RemoteScope
import dev.kb896.aiusage.remote.RemoteStore

/**
 * DEBUG BUILDS ONLY (declared in the debug manifest, guarded by DUMP as the other two are):
 * remote control switched on or off from adb, and a token made for a test, so a script can
 * drive the two doors without a hand on the settings.
 *
 *   adb shell am broadcast -a dev.chicken.rear.dev.DEMO_REMOTE -p dev.chicken.rear.dev --ez enable true
 *   adb shell am broadcast -a dev.chicken.rear.dev.DEMO_REMOTE -p dev.chicken.rear.dev --es token Test --es scopes all
 *
 * The token goes to the log (tag AiUsageDemo), which is the one place a test can read it —
 * and the reason this is not in a release: a secret in logcat is no secret.
 */
class DemoRemoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = RemoteStore(context)
        if (intent.hasExtra("enable")) {
            val on = intent.getBooleanExtra("enable", false)
            store.enabled = on
            ConfigWriter.update(context) { it.copy(remoteCards = on) }
            Log.i("AiUsageDemo", "remote control ${if (on) "on" else "off"}")
        }
        intent.getStringExtra("token")?.let { name ->
            val scopes = when (val s = intent.getStringExtra("scopes")) {
                null -> RemoteScope.DEFAULT
                "all" -> RemoteScope.entries.toSet()
                else -> s.split(',').mapNotNull { RemoteScope.fromWire(it.trim()) }.toSet()
            }
            val (info, secret) = store.create(name, scopes)
            Log.i("AiUsageDemo", "token ${info.name} ${info.id}: $secret")
        }
        if (intent.getBooleanExtra("log", false)) {
            for (e in store.log().take(20).reversed()) Log.i("AiUsageDemo", "log ${e.who}: ${e.command} — ${e.outcome}")
        }
    }
}
