package dev.kb896.aiusage.circle

import android.content.Context
import android.icu.util.ULocale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

internal object OcrModels {
    data class State(
        val checked: Boolean = false,
        val installed: Set<OcrAsset> = emptySet(),
        val partial: Set<OcrAsset> = emptySet(),
        val task: List<OcrAsset> = emptyList(),
        val bytes: Long = 0,
        val error: String? = null,
        val busy: Boolean = false,
    ) {
        val ready get() = OcrCatalog.detector in installed && OcrCatalog.recognizers.any { it in installed }
        fun readyFor(model: OcrModel) = ready || model == OcrModel.ACCURATE && OcrCatalog.accurateReady(installed)
    }
    fun root(context: Context) = File(context.noBackupFilesDir, "circle-ocr/paddle-1")
    private val worker = Executors.newSingleThreadExecutor()
    private val current = MutableStateFlow(State())
    val status = current.asStateFlow()
    private var checking = false

    fun home(c: Context): List<OcrAsset> {
        val locales = c.resources.configuration.locales
        return (0 until locales.size()).mapNotNull {
            val locale = locales[it]
            val script = ULocale.addLikelySubtags(ULocale.forLocale(locale)).script
            OcrCatalog.forLocale(locale, script)
        }.distinct()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("circle_paddle_choices", Context.MODE_PRIVATE)
    fun model(c: Context) = OcrModel.saved(prefs(c).getString("model", null))
    fun selectModel(c: Context, model: OcrModel) {
        prefs(c).edit().putString("model", model.name).apply()
    }

    fun initial(c: Context): List<OcrAsset> {
        val missing = home(c).filterNot { prefs(c).getBoolean("removed-" + it.name, false) }
        return (listOf(OcrCatalog.detector) + missing).filterNot { it in current.value.installed }
    }
    fun offered(c: Context, name: String) = prefs(c).getBoolean("offered-$name", false)
    fun remember(c: Context, name: String) { prefs(c).edit().putBoolean("offered-$name", true).apply() }

    @Synchronized fun check(c: Context) {
        if (current.value.checked || checking) return
        checking = true
        val app = c.applicationContext
        worker.execute {
            val pack = OcrPack(root(app))
            val found = OcrCatalog.assets.filter { try { pack.verified(it) } catch (_: IOException) { false } }
            val partial = OcrCatalog.assets.filter { it !in found && pack.hasPartial(it) }
            current.value = State(checked = true, installed = found.toSet(), partial = partial.toSet())
            CircleOption.refresh(app)
        }
    }

    /** The dialog passes the exact set it priced. Installation and deletion share one writer. */
    @Synchronized fun download(
        c: Context, approved: List<OcrAsset>, bulk: Boolean = false, checkpoint: () -> Unit = {},
    ) {
        if (!bulk && LanguageDownloads.active) return
        val old = current.value
        val work = approved.distinct().filterNot { it in old.installed }
        if (!old.checked || old.busy || work.isEmpty() || !OcrCatalog.assets.containsAll(work)) return
        if (work.any { it in OcrCatalog.accuratePacks } && model(c) != OcrModel.ACCURATE) return
        current.value = old.copy(task = work, bytes = 0, error = null, busy = true)
        val app = c.applicationContext
        worker.execute {
            val pack = OcrPack(root(app))
            var complete = 0L
            try {
                for (asset in work) {
                    pack.install(asset, checkpoint = checkpoint) {
                        if (!bulk) current.value = current.value.copy(bytes = complete + it)
                    }
                    current.value = current.value.let {
                        it.copy(installed = it.installed + asset, partial = it.partial - asset)
                    }
                    prefs(app).edit().remove("removed-" + asset.name).apply()
                    complete += asset.bytes
                }
                current.value = current.value.copy(task = emptyList(), busy = false)
            } catch (e: IOException) {
                val failed = current.value
                val partial = OcrCatalog.assets.filter { it !in failed.installed && pack.hasPartial(it) }
                current.value = failed.copy(partial = partial.toSet(), busy = false,
                    error = "Download paused. ${e.localizedMessage ?: "Transfer interrupted"}")
            }
            CircleOption.refresh(app)
        }
    }

    @Synchronized fun remove(c: Context, asset: OcrAsset, bulk: Boolean = false) {
        if (!bulk && LanguageDownloads.active) return
        val old = current.value
        if (old.busy || asset !in old.installed && asset !in old.partial) return
        val app = c.applicationContext
        current.value = old.copy(busy = true, task = emptyList(), error = null)
        worker.execute {
            try {
                OcrPack(root(app)).remove(asset)
                prefs(app).edit().putBoolean("removed-" + asset.name, true).putBoolean("offered-" + asset.name, true).apply()
                current.value = current.value.copy(installed = old.installed - asset, partial = old.partial - asset, busy = false)
            } catch (e: IOException) {
                current.value = old.copy(task = emptyList(), error = "Could not remove " + asset.label)
            }
            CircleOption.refresh(app)
        }
    }
}
