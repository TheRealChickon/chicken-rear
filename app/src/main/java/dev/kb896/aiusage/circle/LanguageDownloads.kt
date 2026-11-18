package dev.kb896.aiusage.circle

import android.content.Context
import android.content.Intent
import android.util.Log
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

internal enum class LanguageChoice(val label: String) {
    ONLY_MINE("Only my languages"), EVERYTHING("Everything");

    companion object {
        fun saved(value: String?) = entries.firstOrNull { it.name == value } ?: ONLY_MINE
    }
}

internal object EverythingPlan {
    const val STORAGE_MARGIN = 2_000_000_000L
    const val TRANSLATION_DOWNLOAD_ESTIMATE = 30_000_000L
    // Allow extra space for ML Kit's extracted files and its current download.
    const val TRANSLATION_STORAGE_ALLOWANCE = 40_000_000L
    val ocr = OcrCatalog.fastPacks
    val translations: List<String> by lazy {
        TranslateLanguage.getAllLanguages().filterNot { it == TranslateLanguage.ENGLISH }.distinct().sorted()
    }
    val OcrAsset.scratchBytes get() = maxOf(modelBytes, (bytes - bytes / 3) / 2 + 1)
    val ocrBytes = ocr.sumOf { it.bytes }
    val estimatedTotal get() = ocrBytes + translations.size * TRANSLATION_DOWNLOAD_ESTIMATE
    val packCount get() = ocr.size + translations.size

    data class Remaining(val ocr: List<OcrAsset>, val translations: List<String>) {
        fun downloadBytes(partialBytes: (OcrAsset) -> Long) =
            ocr.sumOf { it.bytes - partialBytes(it) } + translations.size * TRANSLATION_DOWNLOAD_ESTIMATE

        fun requiredFreeBytes(partialBytes: (OcrAsset) -> Long): Long {
            if (ocr.isEmpty() && translations.isEmpty()) return 0
            val ocrScratch = ocr.maxOfOrNull { it.scratchBytes } ?: 0
            val translationScratch = if (translations.isEmpty()) 0 else TRANSLATION_STORAGE_ALLOWANCE
            return STORAGE_MARGIN + ocr.sumOf { it.bytes - partialBytes(it) } + ocrScratch +
                translations.size * TRANSLATION_STORAGE_ALLOWANCE + translationScratch
        }
    }

    fun remaining(installedOcr: Set<OcrAsset>, installedTranslations: Set<String>) =
        Remaining(ocr.filterNot(installedOcr::contains), translations.filterNot(installedTranslations::contains))

    /** Transfer one pack at a time. Rebuild the queue from disk on each resume. */
    suspend fun transfer(
        remaining: Remaining,
        checkpoint: () -> Unit,
        installOcr: suspend (OcrAsset) -> Unit,
        installTranslation: suspend (String) -> Unit,
    ) {
        for (asset in remaining.ocr) {
            checkpoint()
            installOcr(asset)
        }
        for (language in remaining.translations) {
            checkpoint()
            installTranslation(language)
        }
        checkpoint()
    }
}

internal object LanguageDownloads {
    enum class Phase { IDLE, DOWNLOADING, STOPPING, PAUSED, REMOVING }

    data class State(
        val installedTranslations: Set<String>? = null,
        val phase: Phase = Phase.IDLE,
        val detail: String? = null,
    ) {
        val active get() = phase == Phase.DOWNLOADING || phase == Phase.STOPPING || phase == Phase.REMOVING
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val current = MutableStateFlow(State())
    val status = current.asStateFlow()
    val active get() = current.value.active
    private var refreshing = false
    private const val PENDING_TRANSLATION = "pending_translation"

    private fun prefs(c: Context) = c.getSharedPreferences("circle_language_downloads", Context.MODE_PRIVATE)
    fun choice(c: Context) = LanguageChoice.saved(prefs(c).getString("choice", null))
    fun pending(c: Context) = prefs(c).getBoolean("pending", false)
    fun wifiOnly(c: Context) = prefs(c).getBoolean("wifi_only", true)
    fun useOnlyMyLanguages(c: Context) {
        prefs(c).edit().putString("choice", LanguageChoice.ONLY_MINE.name).putBoolean("pending", false).apply()
        cancel()
    }

    fun freeBytes(c: Context) = c.noBackupFilesDir.usableSpace
    fun partialBytes(c: Context, asset: OcrAsset) = OcrPack(OcrModels.root(c)).partialBytes(asset)
    fun online(c: Context, wifiOnly: Boolean): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val network = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!wifiOnly || network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }

    private fun manager() = RemoteModelManager.getInstance()
    private fun model(tag: String) = TranslateRemoteModel.Builder(tag).build()

    private suspend fun installedTranslations(): Set<String> =
        withTimeout(20_000) {
            manager().getDownloadedModels(TranslateRemoteModel::class.java).resultWhenReady()
                .map { it.language }.filter { it in EverythingPlan.translations }.toSet()
        }

    fun refresh(c: Context) {
        OcrModels.check(c)
        if (active || refreshing) return
        refreshing = true
        val before = current.value
        scope.launch {
            try {
                val installed = installedTranslations()
                if (current.value === before) current.value = before.copy(installedTranslations = installed)
            } catch (_: Exception) {
                if (current.value === before) current.value = before.copy(detail = "Could not check translation packs. Try again.")
            } finally {
                refreshing = false
            }
        }
    }

    /** Shared by the confirmed UI and the token-gated debug receiver. */
    fun start(c: Context, wifiOnly: Boolean = true) {
        if (active || OcrModels.status.value.busy) return
        val app = c.applicationContext
        current.value = current.value.copy(phase = Phase.DOWNLOADING, detail = null)
        try {
            app.startForegroundService(Intent(app, LanguageDownloadService::class.java)
                .putExtra(LanguageDownloadService.WIFI_ONLY, wifiOnly))
        } catch (_: IllegalStateException) {
            startFailed("Android paused the download. Open Language downloads to resume.")
        }
    }

    fun startFailed(detail: String) {
        current.value = current.value.copy(phase = Phase.PAUSED, detail = detail)
        Log.i("AiUsageModels", detail)
    }

    fun runQueue(app: Context, wifiOnly: Boolean) {
        if (current.value.phase == Phase.STOPPING) startFailed("Download paused")
        if (current.value.phase != Phase.DOWNLOADING) return
        scope.launch {
            try {
                OcrModels.check(app)
                val installedOcr = OcrModels.status.first { it.checked }.installed
                val installed = installedTranslations()
                current.value = current.value.copy(installedTranslations = installed)
                val work = EverythingPlan.remaining(installedOcr, installed)
                if (current.value.phase == Phase.STOPPING) throw IOException("Download paused")
                fun verifySpace(required: Long) {
                    val missing = required - freeBytes(app)
                    if (missing > 0) throw IOException("Free up ${OcrPrompt.size(missing)} to download Everything.")
                }
                verifySpace(work.requiredFreeBytes { partialBytes(app, it) })
                prefs(app).edit().putString("choice", LanguageChoice.EVERYTHING.name)
                    .putBoolean("wifi_only", wifiOnly).putBoolean("pending", true).apply()
                OcrModels.selectModel(app, OcrModel.FAST)

                fun checkpoint() {
                    if (current.value.phase == Phase.STOPPING) throw IOException("Download paused")
                    if (!online(app, wifiOnly)) throw IOException(if (wifiOnly) "Connect to Wi-Fi to resume." else "Connect to the internet to resume.")
                }

                val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
                if (work.ocr.isNotEmpty() || work.translations.isNotEmpty()) {
                    EverythingPlan.transfer(work, ::checkpoint, installOcr = { asset ->
                        val scratch = with(EverythingPlan) { asset.scratchBytes }
                        verifySpace(EverythingPlan.STORAGE_MARGIN + asset.bytes - partialBytes(app, asset) + scratch)
                        current.value = current.value.copy(detail = "Text recognition · ${asset.label}")
                        OcrModels.download(app, listOf(asset), bulk = true, checkpoint = ::checkpoint)
                        val result = OcrModels.status.first { !it.busy }
                        if (asset !in result.installed) throw IOException(result.error ?: "Could not save ${asset.label}.")
                    }, installTranslation = { tag ->
                        verifySpace(EverythingPlan.STORAGE_MARGIN + 2 * EverythingPlan.TRANSLATION_STORAGE_ALLOWANCE)
                        current.value = current.value.copy(detail = "Translation · ${languageName(tag)}")
                        downloadTranslation(app, tag, wifiOnly, conditions)
                    })
                }
                prefs(app).edit().putBoolean("pending", false).remove(PENDING_TRANSLATION).apply()
                current.value = current.value.copy(phase = Phase.IDLE, detail = null)
                Log.i("AiUsageModels", "Everything complete: ${EverythingPlan.packCount} packs")
            } catch (e: Exception) {
                val message = when {
                    current.value.phase == Phase.STOPPING -> "Download paused"
                    e is IOException -> e.message
                    else -> "Could not finish the download. Try again."
                }
                startFailed(message ?: "Download paused. Try again.")
            }
        }
    }

    private suspend fun downloadTranslation(c: Context, tag: String, wifiOnly: Boolean, conditions: DownloadConditions) {
        prefs(c).edit().putString(PENDING_TRANSLATION, tag).apply()
        for (attempt in 0..2) {
            try {
                val remote = model(tag)
                if (attempt > 0) {
                    withTimeout(20_000) { TranslationFallback.enqueue(c, remote, wifiOnly) }
                    Log.i("AiUsageModels", "Translation $tag: trying Google's direct model endpoint")
                }
                TranslationTransfer.await(c, setOf(tag), manager().download(remote, conditions)) {
                    current.value.phase == Phase.STOPPING
                }
                current.value = current.value.copy(
                    installedTranslations = current.value.installedTranslations.orEmpty() + tag,
                )
                prefs(c).edit().remove(PENDING_TRANSLATION).apply()
                Log.i("AiUsageModels", "Translation $tag ready (${current.value.installedTranslations.orEmpty().size}/${EverythingPlan.translations.size})")
                return
            } catch (e: Exception) {
                val stopping = current.value.phase == Phase.STOPPING
                // Give Android's resumable row time to recover before recycling the missing model.
                if (tag in installedTranslations()) {
                    current.value = current.value.copy(
                        installedTranslations = current.value.installedTranslations.orEmpty() + tag,
                    )
                    prefs(c).edit().remove(PENDING_TRANSLATION).apply()
                    return
                }
                withTimeout(20_000) { manager().deleteDownloadedModel(model(tag)).resultWhenReady() }
                prefs(c).edit().remove(PENDING_TRANSLATION).apply()
                if (stopping) throw IOException("Download paused", e)
                if (attempt == 2) throw IOException("Download paused at ${languageName(tag)} translation. Resume to try again.", e)
                Log.i("AiUsageModels", "Translation $tag: retry ${attempt + 1}/2")
                delay(if (attempt == 0) 1_000 else 3_000)
                if (current.value.phase == Phase.STOPPING) throw IOException("Download paused")
                prefs(c).edit().putString(PENDING_TRANSLATION, tag).apply()
            }
        }
    }

    fun cancel() {
        if (current.value.phase == Phase.DOWNLOADING) current.value = current.value.copy(phase = Phase.STOPPING)
    }

    fun remove(c: Context, language: String? = null) {
        if (active || OcrModels.status.value.busy) return
        if (language != null && language !in EverythingPlan.translations) return
        val app = c.applicationContext
        current.value = current.value.copy(phase = Phase.REMOVING, detail = null)
        scope.launch {
            try {
                if (language == null) {
                    for (asset in EverythingPlan.ocr) {
                        OcrModels.remove(app, asset, bulk = true)
                        val result = OcrModels.status.first { !it.busy }
                        if (asset in result.installed || asset in result.partial) {
                            throw IOException("Could not remove ${asset.label}.")
                        }
                    }
                }
                val installed = installedTranslations()
                val pending = prefs(app).getString(PENDING_TRANSLATION, null)
                    ?.takeIf { it in EverythingPlan.translations }
                val targets = if (language == null) installed + listOfNotNull(pending) else installed.intersect(setOf(language))
                current.value = current.value.copy(installedTranslations = installed)
                for (tag in targets) {
                    withTimeout(20_000) { manager().deleteDownloadedModel(model(tag)).resultWhenReady() }
                    current.value = current.value.copy(
                        installedTranslations = current.value.installedTranslations.orEmpty() - tag,
                    )
                }
                if (language == null) {
                    prefs(app).edit().putBoolean("pending", false).remove(PENDING_TRANSLATION)
                        .putString("choice", LanguageChoice.ONLY_MINE.name).apply()
                }
                current.value = current.value.copy(phase = Phase.IDLE, detail = null)
            } catch (_: Exception) {
                current.value = current.value.copy(phase = Phase.PAUSED, detail = "Could not remove every selected pack. Try again.")
            }
        }
    }

    fun languageName(tag: String): String = java.util.Locale.forLanguageTag(tag).displayLanguage

}
