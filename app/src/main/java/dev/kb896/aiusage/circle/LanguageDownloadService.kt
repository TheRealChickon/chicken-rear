package dev.kb896.aiusage.circle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Runs only while a user-requested Everything queue is active. */
class LanguageDownloadService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var observer: Job? = null
    private var latestStartId = 0
    private var wake: PowerManager.WakeLock? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == PAUSE) {
            LanguageDownloads.cancel()
            return START_NOT_STICKY
        }
        if (observer != null) return START_NOT_STICKY
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Language downloads",
            NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        notifications.cancel(PAUSED_ID)
        try {
            startForeground(PROGRESS_ID, notification("Preparing language packs…", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: IllegalStateException) {
            LanguageDownloads.startFailed("Android paused the download. Open Language downloads to resume.")
            stopSelf()
            return START_NOT_STICKY
        }
        wake?.let { if (it.isHeld) it.release() }
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:language-download")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        LanguageDownloads.runQueue(applicationContext, intent?.getBooleanExtra(WIFI_ONLY, true) ?: true)
        val progress = scope.launch(start = CoroutineStart.LAZY) {
            combine(LanguageDownloads.status, OcrModels.status) { queue, ocr ->
                val completed = queue.installedTranslations.orEmpty().size + ocr.installed.count { it in EverythingPlan.ocr }
                Triple(queue, LanguageDownloadPrompt.statusLine(queue, ocr), completed)
            }.collect { (queue, detail, completed) ->
                when (queue.phase) {
                    LanguageDownloads.Phase.DOWNLOADING, LanguageDownloads.Phase.STOPPING ->
                        notifications.notify(PROGRESS_ID, notification(detail.orEmpty(), completed))
                    LanguageDownloads.Phase.PAUSED -> {
                        notifications.notify(PAUSED_ID, notification(detail ?: "Open Language downloads to resume.", null))
                        finish()
                    }
                    else -> finish()
                }
            }
        }
        observer = progress
        progress.start()
        return START_NOT_STICKY
    }

    private fun finish() {
        observer?.cancel()
        observer = null
        stopSelfResult(latestStartId)
    }

    private fun notification(detail: String, completed: Int?): Notification {
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (completed == null) "Language downloads paused" else "Downloading language packs")
            .setContentText(detail).setStyle(Notification.BigTextStyle().bigText(detail))
            .setOnlyAlertOnce(true).setLocalOnly(true)
            .setOngoing(completed != null).setVisibility(Notification.VISIBILITY_PRIVATE)
        if (completed != null) {
            val pause = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(PAUSE),
                PendingIntent.FLAG_IMMUTABLE)
            builder.setProgress(EverythingPlan.packCount, completed, false)
                .addAction(Notification.Action.Builder(null, "Pause", pause).build())
        }
        return builder.build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        LanguageDownloads.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        if (observer != null) LanguageDownloads.cancel()
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "language_downloads"
        private const val PROGRESS_ID = 96
        private const val PAUSED_ID = 97
        private const val PAUSE = "pause-language-downloads"
        const val WIFI_ONLY = "wifi-only"
    }
}
