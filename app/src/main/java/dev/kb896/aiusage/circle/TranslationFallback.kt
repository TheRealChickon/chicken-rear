package dev.kb896.aiusage.circle

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.google.mlkit.common.sdkinternal.MLTaskExecutor
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.common.sdkinternal.ModelInfo
import com.google.mlkit.common.sdkinternal.SharedPrefManager
import com.google.mlkit.common.sdkinternal.ModelType
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Selects the direct Google URL already listed by ML Kit 17.0.3 when its redirector stalls. */
internal object TranslationFallback {
    suspend fun enqueue(c: Context, model: TranslateRemoteModel, wifiOnly: Boolean) =
        suspendCancellableCoroutine { continuation ->
            // Share the SDK's worker so a concurrent on-demand start can reuse the same download ID.
            MLTaskExecutor.getInstance().handler.post {
                if (!continuation.isActive) return@post
                try {
                    enqueueOnMlKitWorker(c, model, wifiOnly)
                    continuation.resume(Unit)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }

    private fun enqueueOnMlKitWorker(c: Context, model: TranslateRemoteModel, wifiOnly: Boolean) {
        val prefs = SharedPrefManager.getInstance(MlKitContext.getInstance())
        if (prefs.getDownloadingModelId(model) != null) return
        val archive = TranslationTransfer.modelFiles(setOf(model.language)).single()
        val resources = c.resources.openRawResource(com.google.mlkit.nl.translate.R.raw.translate_models_metadata)
        val catalog = resources.bufferedReader().use { JSONObject(it.readText()) }
        val key = archive.removeSuffix(".zip")
        val pin = catalog.getJSONObject("PKG_HIGH").optJSONObject(key)
            ?: catalog.getJSONObject("PKG_LOW").getJSONObject(key)
        val hash = pin.getString("HASH")
        val revision = pin.getInt("RV")
        require(hash.matches(Regex("[0-9a-f]{64}")) && revision > 0)
        val uri = Uri.parse("https://dl.google.com/translate/offline/v5/high/r$revision/$archive")
        val request = DownloadManager.Request(uri).apply {
            if (wifiOnly) setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            addRequestHeader("User-Agent", "TRANSLATE_OPM5_TEST_1")
        }
        val downloads = c.getSystemService(DownloadManager::class.java)
        val downloadId = downloads.enqueue(request)
        try {
            // RemoteModelManager attaches to this row, then verifies the SDK's own hash and installs the model.
            prefs.setDownloadingModelInfo(downloadId,
                ModelInfo(model.uniqueModelNameForPersist, uri, hash, ModelType.TRANSLATE))
        } catch (e: Exception) {
            downloads.remove(downloadId)
            throw e
        }
    }
}
