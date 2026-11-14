package dev.kb896.aiusage.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The broadcast door: `dev.chicken.rear.COMMAND`, for Home Assistant's
 * `command_broadcast_intent`, Tasker's and MacroDroid's Send Intent.
 *
 *   intent_package_name: dev.chicken.rear
 *   intent_class_name:   dev.kb896.aiusage.remote.RemoteReceiver
 *   intent_action:       dev.chicken.rear.COMMAND
 *   intent_extras:       token:<token>,cmd:page,name:usage
 *
 * EXPORTED, and the first thing in this app that is, on purpose: the panel's own receiver was
 * closed to the outside after any app could feed it fake notices, and it stays closed. What
 * comes in here is not believed — it is handed to RemoteDispatcher, which lets it through only
 * with a token (RemoteGate), and the component itself is disabled until remote control is
 * switched on (RemoteStore.enabled), so a phone that never uses this has no door at all.
 *
 * EVERY EXTRA IS READ AS A STRING. Home Assistant types them on the way out — digits become an
 * Integer, true and false a Boolean — so `getStringExtra` would quietly lose half of them.
 *
 * The answer set as the broadcast's result is only "taken" or why not (an ordered broadcast's
 * sender, Tasker's for one, can read it). How it went afterwards is in the app's log.
 */
class RemoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val extras = intent.extras
        val args = LinkedHashMap<String, String>()
        if (extras != null) for (key in extras.keySet()) {
            @Suppress("DEPRECATION") val value = extras.get(key) ?: continue
            if (key.length <= 40) args[key] = value.toString()
        }
        val token = args.remove(EXTRA_TOKEN)
        val cmd = args.remove(EXTRA_CMD)
        // A sender is named only if it chose to be (BroadcastOptions.setShareIdentityEnabled);
        // none of the usual callers does, and then this is null and the token is everything.
        val sender = runCatching { sentFromPackage }.getOrNull()
        val answer = RemoteDispatcher.submit(context, cmd, args, token, sender, via = "broadcast")
        if (isOrderedBroadcast) {
            resultCode = if (answer == RemoteDispatcher.TAKEN) 1 else 0
            resultData = answer
        }
    }

    companion object {
        /** Not derived from the application id: a debug build beside the release answers to the same words. */
        const val ACTION = "dev.chicken.rear.COMMAND"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_CMD = "cmd"
    }
}
