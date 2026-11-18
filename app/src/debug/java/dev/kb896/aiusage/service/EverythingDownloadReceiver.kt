package dev.kb896.aiusage.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.kb896.aiusage.DebugExtras
import dev.kb896.aiusage.circle.LanguageDownloads

/** The explicit ADB broadcast can start the same Wi-Fi and storage-checked queue without an activity. */
class EverythingDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "${context.packageName}.DOWNLOAD_EVERYTHING") return
        if (DebugExtras.of(context, intent, listOf("everything")).bool("everything")) {
            LanguageDownloads.start(context)
        }
    }
}
