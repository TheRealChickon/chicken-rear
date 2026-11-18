package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Pictures out of Xiaomi's own rear-screen templates, read from its package as it is installed on
 * the phone (com.xiaomi.subscreencenter: `assets/smart_assistant/<template>` is a zip of a MAML
 * widget with its pictures). The back's call page is meant to be Xiaomi's, so its glyphs are Xiaomi's
 * own files, taken where they lie, never copied into this app. Read once, kept; a picture that is
 * not there is simply missing from the map, and the page draws without it.
 *
 * A TEMPLATE NOT FOUND AT ALL IS NOT LOOKED FOR AGAIN AT ONCE: the charging page asks for the timer's water every
 * frame, and with Xiaomi's package unreadable (an update moving it) that was a zip opened sixty times a second. A miss
 * is remembered for [MISS_MS] ([missed]), then looked for again.
 */
object XiaomiArt {
    private const val TAG = "AiUsageRear"
    private val APKS = listOf("/product/app/subscreencenter/subscreencenter.apk", "/system/app/subscreencenter/subscreencenter.apk")
    private val kept = HashMap<String, Map<String, Bitmap>>()
    /** When a template (and the test's own file, if any) was last looked for and nothing was found (uptime). */
    private val missedAt = HashMap<String, Long>()
    const val MISS_MS = 60_000L

    /** Whether a miss at [missedAt] (0 for none) still stands at [now]: nothing is looked for again before [MISS_MS]. Pure. */
    fun missed(missedAt: Long, now: Long): Boolean = missedAt != 0L && now - missedAt in 0 until MISS_MS

    /** The pictures [names] (paths inside the template) of [template], by path. [apk] for a test's own copy. */
    @Synchronized
    fun load(template: String, names: Collection<String>, apk: String? = null): Map<String, Bitmap> {
        kept[template]?.let { return it }
        val key = "$template|${apk.orEmpty()}"
        val now = android.os.SystemClock.uptimeMillis()
        if (missed(missedAt[key] ?: 0L, now)) return emptyMap()
        val out = HashMap<String, Bitmap>()
        val file = (listOfNotNull(apk) + APKS).firstOrNull { java.io.File(it).canRead() }
        // Read in one pass: each wanted entry's bytes, then decoded.
        if (file != null) runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("assets/smart_assistant/$template") ?: return@use
                ZipInputStream(zip.getInputStream(entry)).use { inner ->
                    while (true) {
                        val e = inner.nextEntry ?: break
                        if (e.name !in names) continue
                        val bytes = inner.readBytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { out[e.name] = it }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Xiaomi's $template pictures not read: ${it.message}") }
        if (out.size < names.size) Log.w(TAG, "Xiaomi's $template: ${out.size} of ${names.size} pictures found")
        if (out.isEmpty()) missedAt[key] = now else missedAt.remove(key)
        return out.also { if (it.isNotEmpty()) kept[template] = it }
    }
}
