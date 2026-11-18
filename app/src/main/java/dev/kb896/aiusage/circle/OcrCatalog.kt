package dev.kb896.aiusage.circle

import java.util.Locale

internal data class OcrAsset(
    val name: String, val label: String, val archive: String,
    val bytes: Long, val sha256: String,
    val offset: Long, val modelBytes: Long, val modelSha: String,
) {
    val url get() = "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/$archive"
    val hubUrl get() = paddleHubRevisions[name]?.let { revision ->
        val repository = archive.removeSuffix("_infer.tar")
        val file = if (name == "kannada") "inference.pdiparams" else "inference.onnx"
        "https://huggingface.co/PaddlePaddle/$repository/resolve/$revision/$file"
    }
    val fileName get() = if (name == "kannada") "kannada.pdiparams" else "$name.onnx"
}

internal enum class OcrModel(val label: String) {
    FAST("Fast"), ACCURATE("Accurate");

    companion object {
        fun saved(value: String?) = entries.firstOrNull { it.name == value } ?: FAST
    }
}

internal object OcrCatalog {
    val assets = paddleDistributions
    val detector = assets.first()
    val accurateDetector = assets.first { it.name == "det-accurate" }
    val accurateRecognizer = assets.first { it.name == "cjk-accurate" }
    val accuratePacks = listOf(accurateDetector, accurateRecognizer)
    val recognizers = assets.filter { it != detector && it !in accuratePacks }

    val fastPacks = listOf(detector) + recognizers

    fun accurateMissing(installed: Collection<OcrAsset>) = accuratePacks.filterNot { it in installed }
    fun accurateReady(installed: Collection<OcrAsset>) = installed.containsAll(accuratePacks)

    fun fallbackLabel(text: String, recognizer: OcrAsset): String? {
        if (text.codePoints().noneMatch(Character::isLetter)) return null
        val script = scriptIn(text) ?: when {
            hasChineseOrJapanese(text) -> "cjk"
            text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN } -> "latin"
            else -> recognizer.name
        }
        return recognizers.firstOrNull { it.name == script }?.label ?: recognizer.label
    }

    fun hasChineseOrJapanese(text: String) = text.codePoints().anyMatch {
        when (Character.UnicodeScript.of(it)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
            else -> false
        }
    }
    fun accepts(url: String) = assets.any { it.url == url || it.hubUrl == url }

    fun forLocale(locale: Locale, likelyScript: String): OcrAsset? {
        val id = when (likelyScript) {
            "Latn" -> "latin"
            "Hans", "Hant", "Hani", "Jpan", "Kana", "Hira" -> "cjk"
            "Kore", "Hang" -> "korean"
            "Cyrl" -> if (locale.language in setOf("ru","uk","be")) "eslav" else "cyrillic"
            "Arab" -> "arabic"
            "Deva" -> "devanagari"
            "Grek" -> "el"
            "Thai" -> "th"
            "Taml" -> "ta"
            "Telu" -> "te"
            "Knda" -> "kannada"
            else -> return null
        }
        return assets.first { it.name == id }
    }

    /** Some dictionaries overlap. A partial reading can still identify the missing specialist. */
    fun scriptIn(text: String): String? {
        val counts = mutableMapOf<String,Int>()
        var letters = 0
        text.codePoints().filter(Character::isLetter).forEach { point ->
            letters++
            val pack = when (Character.UnicodeScript.of(point)) {
                Character.UnicodeScript.GREEK -> "el"
                Character.UnicodeScript.CYRILLIC -> "cyrillic"
                Character.UnicodeScript.ARABIC -> "arabic"
                Character.UnicodeScript.DEVANAGARI -> "devanagari"
                Character.UnicodeScript.THAI -> "th"
                Character.UnicodeScript.TAMIL -> "ta"
                Character.UnicodeScript.TELUGU -> "te"
                Character.UnicodeScript.KANNADA -> "kannada"
                Character.UnicodeScript.HANGUL -> "korean"
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "cjk"
                else -> return@forEach
            }
            counts[pack] = (counts[pack] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.takeIf { it.value >= 2 && it.value*3 >= letters }?.key
    }

    fun matchesHint(pack: String, hint: String?) = pack == hint ||
        pack == "eslav" && hint == "cyrillic" || pack == "en" && hint == "latin"
}
