package dev.kb896.aiusage.circle

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Google owns the HTTP transfer. Watch the app's DownloadManager rows without replacing the SDK. */
internal object TranslationTransfer {
    suspend fun await(c: Context, tags: Set<String>, task: Task<Void>, paused: () -> Boolean = { false }) = supervisorScope {
        val finished = async { task.resultWhenReady(); true }
        val modelFiles = modelFiles(tags)
        var previous = emptyMap<Long, Long>()
        var lastProgress = SystemClock.elapsedRealtime()
        var warned = false
        try {
            while (withTimeoutOrNull(2_000) { finished.await() } == null) {
                if (paused()) throw IOException("Download paused")
                val rows = withContext(Dispatchers.IO) { modelProgress(c, modelFiles) }
                val now = SystemClock.elapsedRealtime()
                if (rows.any { (id, bytes) -> bytes > (previous[id] ?: -1) }) {
                    if (previous.values.none { it > 0 } && rows.values.any { it > 0 }) {
                        Log.i("AiUsageModels", "Translation ${tags.joinToString()}: Android started receiving bytes")
                    }
                    lastProgress = now
                    warned = false
                }
                previous = rows
                val idle = now - lastProgress
                if (!warned && idle >= 20_000) {
                    Log.i("AiUsageModels", "Translation ${tags.joinToString()}: waiting for Android's download retry")
                    warned = true
                }
                // Leave time for Android to resume the existing request before aborting this wait.
                if (idle >= 65_000) throw IOException("Translation transfer made no progress")
            }
        } finally {
            finished.cancel()
        }
    }

    // ML Kit's archives pair each language with English, using sorted names and the legacy Hebrew tag.
    internal fun modelFiles(tags: Set<String>): Set<String> = tags.filterNot { it == "en" }.map { tag ->
        val language = if (tag == "he") "iw" else tag
        listOf("en", language).sorted().joinToString("_", postfix = ".zip")
    }.toSet()

    private fun modelProgress(c: Context, modelFiles: Set<String>): Map<Long, Long> {
        val downloads = c.getSystemService(DownloadManager::class.java)
        return downloads.query(DownloadManager.Query())?.use { rows ->
            val uriColumn = rows.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            val bytesColumn = rows.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val idColumn = rows.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            buildMap {
                while (rows.moveToNext()) {
                    val uri = Uri.parse(rows.getString(uriColumn) ?: continue)
                    if (uri.host in setOf("dl.google.com", "redirector.gvt1.com") &&
                        uri.path.orEmpty().contains("/translate/offline/") && uri.lastPathSegment in modelFiles) {
                        put(rows.getLong(idColumn), rows.getLong(bytesColumn))
                    }
                }
            }
        }.orEmpty()
    }
}

internal suspend fun <T> Task<T>.resultWhenReady(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(task.exception ?: IOException("Model operation interrupted"))
    }
}
