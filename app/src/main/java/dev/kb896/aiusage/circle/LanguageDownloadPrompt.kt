package dev.kb896.aiusage.circle

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal object LanguageDownloadPrompt {
    fun choose(c: Context, onLocal: () -> Unit = {}, onChanged: () -> Unit = {}) {
        val choices = LanguageChoice.entries
        OcrPrompt.open(c, OcrPrompt.builder(c).setTitle("Circle to Search language packs")
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), LanguageDownloads.choice(c).ordinal) { d, i ->
                d.dismiss()
                val choice = choices[i]
                if (choice == LanguageChoice.ONLY_MINE) LanguageDownloads.useOnlyMyLanguages(c)
                onChanged()
                Handler(c.mainLooper).post {
                    if (choice == LanguageChoice.EVERYTHING) everything(c) else onLocal()
                }
            }
            .setNegativeButton("Later", null))
    }

    fun everything(c: Context) {
        val wifi = CheckBox(c).apply {
            text = "Wi-Fi only"
            isChecked = LanguageDownloads.wifiOnly(c)
        }
        val options = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (20 * resources.displayMetrics.density).toInt()
            setPadding(inset, 0, inset, 0)
            addView(wifi, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dialog = OcrPrompt.open(c, OcrPrompt.builder(c).setTitle("Everything")
            .setMessage("Checking language packs…")
            .setView(options)
            .setPositiveButton("Download", null)
            .setNeutralButton("Remove all", null)
            .setNegativeButton("Close", null)) ?: return
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val remove = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        LanguageDownloads.refresh(c)
        observe(dialog) {
            combine(OcrModels.status, LanguageDownloads.status) { ocr, languages -> ocr to languages }.collect { (ocr, languages) ->
                val installed = languages.installedTranslations
                val remaining = installed?.takeIf { ocr.checked }?.let { EverythingPlan.remaining(ocr.installed, it) }
                val free = LanguageDownloads.freeBytes(c)
                val missing = remaining?.requiredFreeBytes { LanguageDownloads.partialBytes(c, it) }?.minus(free)
                val completed = installed.orEmpty().size + ocr.installed.count { it in EverythingPlan.ocr }
                val complete = completed == EverythingPlan.packCount
                val active = languages.active
                val total = OcrPrompt.size(EverythingPlan.estimatedTotal)
                val storage = OcrPrompt.size(free)
                val headline = "About $total total · $storage free"
                val explanation = "${EverythingPlan.ocr.size} Fast OCR packs and ${EverythingPlan.translations.size} translation downloads. English translation is included."
                val progress = statusLine(languages, ocr)
                val footer = when {
                    active -> progress
                    missing != null && missing > 0 -> "Free up ${OcrPrompt.size(missing)} to leave 2 GB available."
                    complete -> "All language packs are ready."
                    languages.detail != null -> languages.detail
                    remaining != null && completed > 0 ->
                        "About ${OcrPrompt.size(remaining.downloadBytes { LanguageDownloads.partialBytes(c, it) })} left to download."
                    else -> "Keeps at least 2 GB free."
                }
                dialog.setMessage(listOfNotNull(headline, explanation, footer).joinToString("\n\n"))
                wifi.isEnabled = !active && !complete
                wifi.visibility = if (complete && !active) View.GONE else View.VISIBLE
                positive.text = when {
                    languages.phase == LanguageDownloads.Phase.DOWNLOADING -> "Cancel download"
                    complete -> "Use Fast"
                    remaining == null -> "Check again"
                    LanguageDownloads.pending(c) -> "Resume"
                    completed > 0 -> "Download missing"
                    else -> "Download"
                }
                positive.isEnabled = languages.phase == LanguageDownloads.Phase.DOWNLOADING ||
                    !active && !ocr.busy && (missing == null || missing <= 0)
                positive.setOnClickListener {
                    when {
                        active -> LanguageDownloads.cancel()
                        remaining == null -> LanguageDownloads.refresh(c)
                        else -> LanguageDownloads.start(c, wifi.isChecked)
                    }
                }
                remove.visibility = if (completed > 0 || ocr.partial.any { it in EverythingPlan.ocr }) View.VISIBLE else View.GONE
                remove.isEnabled = !active && !ocr.busy
                remove.setOnClickListener {
                    dialog.dismiss()
                    Handler(c.mainLooper).post {
                        OcrPrompt.open(c, OcrPrompt.builder(c).setTitle("Remove Everything?")
                            .setMessage("Remove all Fast recognition and downloaded translation packs, including incomplete downloads.")
                            .setNegativeButton("Keep packs", null)
                            .setPositiveButton("Remove all") { _, _ -> LanguageDownloads.remove(c) })
                    }
                }
            }
        }
    }

    fun statusLine(state: LanguageDownloads.State, ocr: OcrModels.State): String? {
        val count = state.installedTranslations.orEmpty().size + ocr.installed.count { it in EverythingPlan.ocr }
        return when (state.phase) {
            LanguageDownloads.Phase.DOWNLOADING -> {
                val header = "Downloading $count of ${EverythingPlan.packCount} packs"
                state.detail?.let { "$header · $it" } ?: header
            }
            LanguageDownloads.Phase.STOPPING -> "Pausing downloads…"
            LanguageDownloads.Phase.REMOVING -> "Removing language packs…"
            LanguageDownloads.Phase.PAUSED -> state.detail
            LanguageDownloads.Phase.IDLE -> null
        }
    }

    fun translations(c: Context) {
        LanguageDownloads.refresh(c)
        val list = android.widget.ArrayAdapter<String>(c, android.R.layout.simple_list_item_1)
        var tags = emptyList<String>()
        val dialog = OcrPrompt.open(c, OcrPrompt.builder(c).setTitle("Downloaded translations")
            .setAdapter(list) { d, index ->
                val tag = tags.getOrNull(index) ?: return@setAdapter
                d.dismiss()
                Handler(c.mainLooper).post {
                    OcrPrompt.open(c, OcrPrompt.builder(c).setTitle("Remove ${LanguageDownloads.languageName(tag)}?")
                        .setNegativeButton("Keep pack", null)
                        .setPositiveButton("Remove") { _, _ -> LanguageDownloads.remove(c, tag) })
                }
            }
            .setNegativeButton("Close", null)) ?: return
        observe(dialog) {
            LanguageDownloads.status.collect { state ->
                tags = state.installedTranslations.orEmpty().sortedBy(LanguageDownloads::languageName)
                list.clear()
                list.addAll(tags.map(LanguageDownloads::languageName))
                dialog.setTitle(when {
                    state.installedTranslations == null -> state.detail ?: "Checking translation packs…"
                    tags.isEmpty() -> "No downloaded translations"
                    else -> "Downloaded translations"
                })
            }
        }
    }

    private fun observe(dialog: AlertDialog, block: suspend CoroutineScope.() -> Unit) {
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { scope.cancel() }
        })
        scope.launch(block = block)
    }
}
