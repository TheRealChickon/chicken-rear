package dev.kb896.aiusage.circle

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.content.Context
import android.view.WindowManager
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

internal object OcrPrompt {
    private var shown: AlertDialog? = null
    private var host: Context? = null
    fun mb(n: Long) = String.format(Locale.getDefault(), "%.1f MB", n / 1_000_000.0)
    fun size(n: Long) = if (n >= 1_000_000_000)
        String.format(Locale.getDefault(), "%.2f GB", n / 1_000_000_000.0) else mb(n)

    fun dismiss(context: Context) {
        if (host === context) shown?.dismiss()
    }
    internal fun open(c: Context, builder: AlertDialog.Builder): AlertDialog? {
        if (shown?.isShowing == true) return null
        val d = builder.create()
        if (c is AccessibilityService) d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        try { d.show() } catch (_: WindowManager.BadTokenException) {
            Toast.makeText(c, "Open text recognition in Settings › Idle.", Toast.LENGTH_SHORT).show()
            return null
        }
        shown = d
        host = c
        val lifecycle = c as? LifecycleOwner
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(lifecycle: LifecycleOwner) { d.dismiss() }
        }
        lifecycle?.lifecycle?.addObserver(observer)
        d.setOnDismissListener {
            lifecycle?.lifecycle?.removeObserver(observer)
            if (shown === d) { shown = null; host = null }
        }
        return d
    }
    internal fun builder(c: Context): AlertDialog.Builder {
        val dark = c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return AlertDialog.Builder(c, if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
            else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
    }

    /** A named recommendation, or a picker when confidence cannot identify the script. */
    fun suggest(c: Context, suggestions: List<String>, done: () -> Unit) {
        if (OcrModels.status.value.busy || LanguageDownloads.active) return
        val suggestion = suggestions.firstOrNull { !OcrModels.offered(c, it) } ?: return
        val pack = OcrCatalog.assets.find { it.name == suggestion }
        if (pack != null && pack in OcrModels.status.value.installed) return
        val opened = if (pack == null) picker(c, done) else show(c, listOf(pack), done = done)
        if (opened) OcrModels.remember(c, suggestion)
    }

    fun picker(c: Context, done: () -> Unit = {}): Boolean {
        if (LanguageDownloads.active) return false
        val options = OcrCatalog.recognizers.filterNot { it in OcrModels.status.value.installed }
        if (options.isEmpty()) return false
        val labels = options.map { "${it.label} · ${mb(it.bytes)}" }.toTypedArray()
        return open(c, builder(c).setTitle("Choose a text recognition pack")
            .setItems(labels) { dialog, i ->
                dialog.dismiss()
                android.os.Handler(c.mainLooper).post { show(c, listOf(options[i]), done = done) }
            }.setNegativeButton("Later", null)) != null
    }

    fun accurate(c: Context): Boolean {
        if (OcrModels.model(c) != OcrModel.ACCURATE) return false
        return show(c, OcrCatalog.accuratePacks, accurate = true)
    }

    fun show(c: Context, requested: List<OcrAsset>? = null, accurate: Boolean = false, done: () -> Unit = {}): Boolean {
        if (LanguageDownloads.active) return false
        val before = OcrModels.status.value
        if (accurate && OcrCatalog.accurateReady(before.installed)) return false
        if (requested == null && before.readyFor(OcrModels.model(c)) &&
            before.task.none { it !in OcrCatalog.accuratePacks }) return false
        OcrModels.check(c)
        val dialog = builder(c).setTitle("Circle to Search").setMessage("Checking text recognition files…")
            .setPositiveButton("Download", null).setNegativeButton("Later", null)
        if (requested != null && !accurate) dialog.setNeutralButton("Other language") { d, _ ->
            d.dismiss()
            android.os.Handler(c.mainLooper).post { picker(c, done) }
        }
        val d = open(c, dialog) ?: return false
        val yes = d.getButton(AlertDialog.BUTTON_POSITIVE)
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        d.window?.decorView?.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {}
            override fun onViewDetachedFromWindow(v: android.view.View) { scope.cancel() }
        })
        var approved: List<OcrAsset>? = null
        scope.launch {
            OcrModels.status.collect { state ->
                if (!state.checked) { yes.isEnabled = false; return@collect }
                val wanted = approved ?: when {
                    accurate -> OcrCatalog.accuratePacks
                    state.busy && state.task.isNotEmpty() -> state.task
                    requested != null -> (listOf(OcrCatalog.detector) + requested).distinct()
                    state.error != null && state.task.isNotEmpty() &&
                        state.task.none { it in OcrCatalog.accuratePacks } -> state.task
                    else -> OcrModels.initial(c)
                }
                val missing = wanted.filterNot { it in state.installed }
                val ready = if (accurate) OcrCatalog.accurateReady(state.installed) else state.ready
                if (!state.busy && ready && (missing.isEmpty() || requested == null && approved == null && state.error == null)) {
                    d.dismiss()
                    done()
                    return@collect
                }
                val choose = !accurate && !state.busy &&
                    OcrCatalog.recognizers.none { it in missing || it in state.installed }
                val error = state.error.takeIf { state.task.any { it in wanted } }
                yes.text = if (choose) "Choose language" else if (error != null) "Retry" else "Download"
                yes.isEnabled = !state.busy
                d.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = !state.busy
                d.getButton(AlertDialog.BUTTON_NEGATIVE).text = if (state.busy) "Hide" else "Later"
                d.setMessage(when {
                    state.busy -> "Downloading text recognition… ${mb(state.bytes)} of ${mb(state.task.sumOf { it.bytes })}"
                    choose -> "Choose the language you want to read."
                    else -> "Download ${missing.joinToString { it.label }} (${mb(missing.sumOf { it.bytes })}) from PaddlePaddle? Recognition works offline once saved." +
                        (error?.let { "\n\n$it" } ?: "")
                })
                yes.setOnClickListener {
                    if (choose) {
                        d.dismiss()
                        android.os.Handler(c.mainLooper).post { picker(c, done) }
                    } else {
                        approved = wanted
                        OcrModels.download(c, missing)
                    }
                }
            }
        }
        return true
    }
}
