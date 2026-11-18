package dev.kb896.aiusage.ui

import android.content.Intent
import android.net.Uri

/**
 * What a "Pin to back" or "Pin to location" was given (ui/PinActivity, ui/place/PlacePinActivity):
 *
 *   ACTION_PROCESS_TEXT text/plain             -> [Words] (the text-selection menu), as ever
 *   ACTION_SEND text/plain, EXTRA_TEXT         -> [Words], as ever
 *   ACTION_SEND image/any, EXTRA_STREAM          -> [Pictures] of one; an EXTRA_TEXT with it is its caption
 *   ACTION_SEND_MULTIPLE image/any, EXTRA_STREAM -> [Pictures], ONE NOTE EACH for the first [dev.kb896.aiusage.data.PinImage.MOST_AT_ONCE]
 *                                                (the rest are not pinned: [Pictures.left]); the caption on the first
 *
 * Only `content:` addresses of OTHER apps are taken (the share's grant is for those): a `file:` address, or one of this
 * app's own providers, could make it read one of its own files on another app's word. The addresses are read once by
 * the screen that got them and never kept.
 */
object PinShare {
    sealed interface What
    /** Words to pin. */
    data class Words(val text: String) : What
    /** Pictures to pin, one note each (in this order: the first is the note in front), with [caption] on the first. */
    data class Pictures(val uris: List<Uri>, val caption: String, val left: Int = 0) : What
    data object Nothing : What

    fun of(intent: Intent?, most: Int = dev.kb896.aiusage.data.PinImage.MOST_AT_ONCE): What {
        val words = (PinActivity.textOf(intent) ?: if (intent?.action == Intent.ACTION_SEND_MULTIPLE) runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim() }.getOrNull() else null).orEmpty()
        val type = intent?.type.orEmpty().lowercase()
        val pictures = when {
            intent == null || !type.startsWith("image/") -> emptyList()
            intent.action == Intent.ACTION_SEND -> listOfNotNull(stream(intent) ?: clip(intent).firstOrNull())
            intent.action == Intent.ACTION_SEND_MULTIPLE -> streams(intent).ifEmpty { clip(intent) }
            else -> emptyList()
        }.filter { it.scheme.equals("content", ignoreCase = true) && !ours(it.authority) }.distinct()
        return when {
            pictures.isNotEmpty() -> Pictures(pictures.take(most), words, (pictures.size - most).coerceAtLeast(0))
            words.isNotBlank() -> Words(words)
            else -> Nothing
        }
    }

    /** An authority of this app's own providers (all are named after its package). */
    private fun ours(authority: String?): Boolean {
        // ("10@authority": another user's provider, as the system writes it.)
        val a = authority?.lowercase()?.substringAfter('@') ?: return true
        val own = dev.kb896.aiusage.BuildConfig.APPLICATION_ID.lowercase()
        return a.split(';').any { it == own || it.startsWith("$own.") }
    }

    private fun stream(intent: Intent): Uri? = runCatching { intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) }.getOrNull()

    private fun streams(intent: Intent): List<Uri> = runCatching { intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) }.getOrNull().orEmpty().filterNotNull()

    private fun clip(intent: Intent): List<Uri> {
        val c = intent.clipData ?: return emptyList()
        return (0 until c.itemCount).mapNotNull { c.getItemAt(it)?.uri }
    }
}
