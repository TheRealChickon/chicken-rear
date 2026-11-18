package dev.kb896.aiusage.circle

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/** On-device language detection and translation (ML Kit), with a text cache so re-translating after a scroll is instant. */
object Tr {
    private val langId by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build())
    }
    private val translators = LinkedHashMap<String, Translator>(8, 0.75f, true)
    private val ready = HashSet<String>()
    private val cache = LruCache<String, String>(6000)

    /** Status line for the UI, e.g. while a language pack downloads. */
    var onStatus: ((String?) -> Unit)? = null

    fun name(code: String): String = Locale(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }
    fun other(t: String) = if (t == "en") "de" else "en"

    /** Script-based answer where the script settles it; null means "ask the language identifier". */
    fun script(text: String): String? {
        var han = 0; var kana = 0; var hangul = 0; var letters = 0
        for (c in text) {
            if (!c.isLetter()) continue
            letters++
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                else -> {}
            }
        }
        if (letters == 0) return "und"
        if (kana * 8 >= letters) return "ja"
        if (hangul * 3 >= letters) return "ko"
        if (han * 3 >= letters) return "zh"
        return when (OcrCatalog.scriptIn(text)) {
            "el" -> "el"
            "th" -> "th"
            "ta" -> "ta"
            "te" -> "te"
            "kannada" -> "kn"
            else -> null
        }
    }

    fun detect(text: String, cb: (String) -> Unit) {
        val s = script(text)
        if (s != null) { cb(s); return }
        langId.identifyLanguage(text)
            .addOnSuccessListener { tag -> cb(TranslateLanguage.fromLanguageTag(tag.substringBefore('-')) ?: "und") }
            .addOnFailureListener { cb("und") }
    }

    private fun translator(src: String, tgt: String): Translator {
        val key = "$src>$tgt"
        translators[key]?.let { return it }
        if (translators.size >= 8) {
            val old = translators.entries.first()
            old.value.close(); translators.remove(old.key)
        }
        return Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
            .also { translators[key] = it }
    }

    // ML Kit's native translate library aborts ("init_google ... Check failed") when two language pairs
    // initialise at the same time, so every translate job runs strictly one after another.
    private val queue = ArrayDeque<(done: () -> Unit) -> Unit>()
    private var running = false

    private fun enqueue(job: (done: () -> Unit) -> Unit) { queue.addLast(job); pump() }
    private fun pump() {
        if (running) return
        val job = queue.removeFirstOrNull() ?: return
        running = true
        job { running = false; pump() }
    }

    /** Drops queued work that has not started (the screen it was for is gone). */
    fun cancelPending() { queue.clear() }

    fun translate(text: String, src: String, tgt: String, cb: (String?) -> Unit) =
        translateIfActive(text, src, tgt, { true }, cb)

    private fun supports(src: String, tgt: String) = src != tgt &&
        TranslateLanguage.fromLanguageTag(src) != null && TranslateLanguage.fromLanguageTag(tgt) != null

    private fun translateIfActive(text: String, src: String, tgt: String, active: () -> Boolean, cb: (String?) -> Unit) {
        if (!active()) return
        if (!supports(src, tgt)) { cb(null); return }
        val key = "$src>$tgt"
        val ck = "$key|$text"
        cache.get(ck)?.let { cb(it); return }
        if (key !in ready) {
            fetch(src, tgt, key) { ok ->
                if (ok) translateIfActive(text, src, tgt, active, cb) else if (active()) cb(null)
            }
            return
        }
        enqueue { done ->
            if (!active()) { done(); return@enqueue }
            val hit = cache.get(ck)
            if (hit != null) { done(); cb(hit); return@enqueue }
            translator(src, tgt).translate(text)
                .addOnSuccessListener { cache.put(ck, it); done(); cb(it) }
                .addOnFailureListener { Dbg.log("translate $key failed: $it"); done(); cb(null) }
        }
    }

    private val downloads = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var app: Context? = null
    private val fetching = HashMap<String, MutableList<(Boolean) -> Unit>>()

    /** A missing pack can take minutes. Keep downloads outside the serial native inference queue. */
    private fun fetch(src: String, tgt: String, key: String, cb: (Boolean) -> Unit) {
        fetching[key]?.let { it.add(cb); return }
        fetching[key] = mutableListOf(cb)
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val status = Runnable { onStatus?.invoke("Preparing ${name(src)} → ${name(tgt)}…") }
        handler.postDelayed(status, 400)
        val context = checkNotNull(app)
        downloads.launch {
            var success = false
            try {
                val conditions = DownloadConditions.Builder().build()
                for (attempt in 0..2) {
                    try {
                        TranslationTransfer.await(context, setOf(src, tgt), client.downloadModelIfNeeded(conditions))
                        success = true
                        break
                    } catch (_: Exception) {
                        if (attempt < 2) delay(if (attempt == 0) 1_000 else 3_000)
                    }
                }
                if (success) ready.add(key)
            } finally {
                client.close()
                handler.removeCallbacks(status)
                onStatus?.invoke(if (success) null else "Language pack download paused. Try again.")
                fetching.remove(key)?.forEach { it(success) }
            }
        }
    }

    fun warm(context: Context) {
        app = context.applicationContext
        langId
    }

    /**
     * Translates every block of a screen. Each block gets its own language: decided by script where that
     * settles it, by the language identifier for longer text, and by the screen's majority language of the
     * same script for short labels. Blocks already in the target language stay as they are. If nearly
     * everything is already in [target] and the user did not pick it explicitly, the other of English/German
     * is used. [start] reports (majority language, effective target); [each] delivers translations as they finish.
     */
    fun screen(
        blocks: List<Block>, target: String, explicit: Boolean,
        start: (String, String) -> Unit, each: (Block, String) -> Unit, ready: () -> Unit = {},
        failed: () -> Unit = {}, done: () -> Unit = {}, active: () -> Boolean = { true },
        translateParagraph: ((String, String, String, (String?) -> Unit) -> Unit)? = null,
    ) {
        val useful = blocks.filter { b -> b.text.count { it.isLetter() } >= 2 }
        if (!active()) return
        if (useful.isEmpty()) { start("und", target); ready(); done(); return }
        val lang = arrayOfNulls<String>(useful.size)   // null = too short to tell
        var waiting = 0
        var scanned = false
        val finish = finish@{
            if (!active()) return@finish
            // An ambiguous short label may borrow a language only from the same writing system.
            val weight = HashMap<String, Int>()
            val byScript = mutableMapOf<Character.UnicodeScript, MutableMap<String, Int>>()
            fun dominant(text: String) = text.filter(Char::isLetter).groupingBy { Character.UnicodeScript.of(it.code) }
                .eachCount().maxByOrNull { it.value }?.key
            for ((i, b) in useful.withIndex()) {
                val code = lang[i]?.takeIf { it != "und" } ?: continue
                weight[code] = (weight[code] ?: 0) + b.text.length
                val script = dominant(b.text) ?: continue
                val same = byScript.getOrPut(script) { mutableMapOf() }
                same[code] = (same[code] ?: 0) + b.text.length
            }
            val majority = weight.maxByOrNull { it.value }?.key ?: "und"
            val foreign = weight.filter { it.key != target }.maxByOrNull { it.value }?.key
            val total = weight.values.sum().coerceAtLeast(1)
            val eff = if (!explicit && (weight[target] ?: 0) * 10 >= total * 7) other(target) else target
            Dbg.log("languages on screen: " + weight.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" } + " -> target $eff")
            start(if (majority == eff && foreign != null) foreign else majority, eff)
            val jobs = mutableListOf<Pair<Block, String>>()
            var skipped = 0
            for ((i, b) in useful.withIndex()) {
                // Longer text that is in no language the identifier knows is not text (a line cut off by the edge
                // of a list, a logo, a serial number): translating it only produces nonsense, so it stays.
                if (lang[i] == "und") { skipped++; continue }
                val l = lang[i] ?: byScript[dominant(b.text)]?.maxByOrNull { it.value }?.key ?: continue
                if (!supports(l, eff)) continue
                jobs.add(b to l)
            }
            if (skipped > 0) Dbg.log("$skipped block(s) in no recognisable language left as they are")
            val order = SelLogic.readingOrder(jobs.map { (b, _) ->
                intArrayOf(b.box.left, b.box.top, b.box.right, b.box.bottom)
            })
            var pending = jobs.size
            var submitted = false
            for (i in order) {
                if (!active()) break
                val (b, language) = jobs[i]
                val receive: (String?) -> Unit = { result ->
                    if (active()) {
                        if (result.isNullOrBlank()) failed()
                        else if (!result.filterNot { it.isWhitespace() }.equals(b.text.filterNot { it.isWhitespace() }, true))
                            each(b, result)
                    }
                    if (--pending == 0 && submitted) done()
                }
                if (translateParagraph == null) translateIfActive(b.text, language, eff, active, receive)
                else translateParagraph(b.text, language, eff, receive)
            }
            ready()
            submitted = true
            if (pending == 0) done()
        }
        for ((i, b) in useful.withIndex()) {
            val s = script(b.text)
            when {
                s != null -> lang[i] = s
                else -> {
                    waiting++
                    langId.identifyLanguage(b.text)
                        .addOnSuccessListener { tag ->
                            lang[i] = if (tag == "und" && b.text.length >= 20) "und"
                                else TranslateLanguage.fromLanguageTag(tag.substringBefore('-'))
                            if (--waiting == 0 && scanned) finish()
                        }
                        .addOnFailureListener { if (--waiting == 0 && scanned) finish() }
                }
            }
        }
        scanned = true
        if (waiting == 0) finish()
    }
}
