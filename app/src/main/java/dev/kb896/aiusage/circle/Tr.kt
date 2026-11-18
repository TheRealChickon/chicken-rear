package dev.kb896.aiusage.circle

import android.util.LruCache
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
        return null
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
            old.value.close(); ready.remove(old.key); translators.remove(old.key)
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

    fun translate(text: String, src: String, tgt: String, cb: (String?) -> Unit) {
        if (TranslateLanguage.fromLanguageTag(src) == null || src == tgt) { cb(null); return }
        val key = "$src>$tgt"
        val ck = "$key|$text"
        cache.get(ck)?.let { cb(it); return }
        enqueue { done ->
            val hit = cache.get(ck)
            if (hit != null) { done(); cb(hit); return@enqueue }
            val t = translator(src, tgt)
            val go = {
                t.translate(text)
                    .addOnSuccessListener { cache.put(ck, it); done(); cb(it) }
                    .addOnFailureListener { Dbg.log("translate $key failed: $it"); done(); cb(null) }
            }
            if (key in ready) go()
            else {
                onStatus?.invoke("Preparing ${name(src)} → ${name(tgt)}…")
                t.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { ready.add(key); onStatus?.invoke(null); go() }
                    .addOnFailureListener { Dbg.log("language pack $key failed: $it"); onStatus?.invoke("Language pack download failed"); done(); cb(null) }
            }
        }
    }

    fun warm() { langId }

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
    ) {
        val useful = blocks.filter { b -> b.text.count { it.isLetter() } >= 2 }
        if (useful.isEmpty()) { start("und", target); ready(); return }
        val lang = arrayOfNulls<String>(useful.size)   // null = too short to tell
        var waiting = 0
        var scanned = false
        val finish = {
            // Majority by amount of text, separately for Latin-script and other text.
            val weight = HashMap<String, Int>()
            for ((i, b) in useful.withIndex()) lang[i]?.takeIf { it != "und" }?.let { weight[it] = (weight[it] ?: 0) + b.text.length }
            val majority = weight.maxByOrNull { it.value }?.key ?: "und"
            // what the screen is mostly written in, apart from the language it is being translated into
            val foreign = weight.filter { it.key != target }.maxByOrNull { it.value }?.key
            fun nonLatin(l: String) = l == "zh" || l == "ja" || l == "ko"
            val latinMajority = weight.filter { !nonLatin(it.key) }.maxByOrNull { it.value }?.key
            val total = weight.values.sum().coerceAtLeast(1)
            val eff = if (!explicit && (weight[target] ?: 0) * 10 >= total * 7) other(target) else target
            Dbg.log("languages on screen: " + weight.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" } + " -> target $eff")
            start(if (majority == eff && foreign != null) foreign else majority, eff)
            var skipped = 0
            for ((i, b) in useful.withIndex()) {
                // Longer text that is in no language the identifier knows is not text (a line cut off by the edge
                // of a list, a logo, a serial number): translating it only produces nonsense, so it stays.
                if (lang[i] == "und") { skipped++; continue }
                val l = lang[i] ?: latinMajority ?: continue
                if (l == eff) continue
                translate(b.text, l, eff) { r ->
                    // Skip results that only differ in spacing or case (numbers, names, units).
                    if (r != null && r.isNotBlank() && !r.filterNot { it.isWhitespace() }.equals(b.text.filterNot { it.isWhitespace() }, true)) each(b, r)
                }
            }
            if (skipped > 0) Dbg.log("$skipped block(s) in no recognisable language left as they are")
            // Everything that was already known (cache) has been delivered by now; the rest follows one by one.
            ready()
        }
        for ((i, b) in useful.withIndex()) {
            val s = script(b.text)
            when {
                s != null -> lang[i] = s
                b.text.length >= 20 -> {
                    waiting++
                    langId.identifyLanguage(b.text)
                        .addOnSuccessListener { tag -> lang[i] = if (tag == "und") "und" else TranslateLanguage.fromLanguageTag(tag.substringBefore('-')); if (--waiting == 0 && scanned) finish() }
                        .addOnFailureListener { if (--waiting == 0 && scanned) finish() }
                }
            }
        }
        scanned = true
        if (waiting == 0) finish()
    }
}
