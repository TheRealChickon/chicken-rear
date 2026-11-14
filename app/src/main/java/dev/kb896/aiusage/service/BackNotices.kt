package dev.kb896.aiusage.service

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import dev.kb896.aiusage.rear.NoticeShade
import dev.kb896.aiusage.rear.ShellNotices
import java.io.ByteArrayOutputStream

/**
 * The notifications the shell's list over a guest shows (ShellNotices), kept in the app's process
 * and handed over whole with every change: the relay's picks, as they reach the panel. In memory
 * only, like the panel's own, and no more than [MAX].
 */
object BackNotices {
    private const val MAX = 12
    private const val ICON_PX = 96

    private var items: List<NoticeShade.Item> = emptyList()

    /** The keys now on the back, newest first. */
    fun keys(): List<String> = items.map { it.key }
    private val icons = HashMap<String, ByteArray>()

    /**
     * The panel's list, whole: the one list there is. What the relay has handed over since is only
     * so that a banner has something to show before the panel has said it has it.
     */
    @Synchronized
    fun replace(context: Context, next: List<NoticeShade.Item>) {
        if (next == items) return
        items = next.take(MAX)
        for (item in items) loadIcon(context, item.pkg)
        send(context, "")
    }

    private val colors = HashMap<String, Int>()

    /** The colour an app's icon is mostly: for a notification that names none of its own. 0 for a grey or empty icon. */
    @Synchronized
    fun iconColor(context: Context, pkg: String): Int = colors.getOrPut(pkg) {
        runCatching {
            val bmp = context.packageManager.getApplicationIcon(pkg).toBitmap(48, 48, Bitmap.Config.ARGB_8888)
            val px = IntArray(48 * 48); bmp.getPixels(px, 0, 48, 0, 0, 48, 48)
            dominant(px)
        }.getOrDefault(0)
    }

    /** The mean of the pixels that have a colour at all (opaque, neither grey nor nearly black), weighted by how strong it is. */
    internal fun dominant(px: IntArray): Int {
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0.0; var seen = 0
        for (p in px) {
            if ((p ushr 24) < 128) continue
            seen++
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            val hi = maxOf(pr, pg, pb); val lo = minOf(pr, pg, pb)
            if (hi < 50) continue
            val sat = (hi - lo) / hi.toDouble()
            if (sat < 0.25) continue
            r += pr * sat; g += pg * sat; b += pb * sat; n += sat
        }
        if (seen == 0 || n < seen * 0.04) return 0
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** An app's icon as the list sends it (PNG bytes), loaded if it was not yet: for the live card. */
    @Synchronized
    fun iconBytes(context: Context, pkg: String): ByteArray? { loadIcon(context, pkg); return icons[pkg] }

    private fun loadIcon(context: Context, pkg: String) {
        if (pkg !in icons) runCatching {
            val bmp = context.packageManager.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            icons[pkg] = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }

    /**
     * One that has just arrived, newest first, with its banner — or, [pop] false, into the list
     * only (skipped while locked, NoticeFilter.skipped). [wake]: the banner was to wake a dark
     * back, which the bridge does as it hands a held banner over (ShizukuBridge.setNotices).
     */
    @Synchronized
    fun posted(context: Context, item: NoticeShade.Item, pop: Boolean = true, wake: Boolean = false) {
        items = (listOf(item) + items.filter { it.key != item.key }).take(MAX)
        loadIcon(context, item.pkg)
        send(context, if (pop) item.key else "", wake)
    }

    /** What was already on the front as the relay connected, under whatever has arrived since: no banner. */
    @Synchronized
    fun seed(context: Context, found: List<NoticeShade.Item>) {
        val have = items.map { it.key }.toSet()
        val next = (items + found.filter { it.key !in have }).take(MAX)
        if (next == items) return
        items = next
        for (item in items) loadIcon(context, item.pkg)
        send(context, "")
    }

    @Synchronized
    fun removed(context: Context, key: String) {
        val next = items.filter { it.key != key }
        if (next.size == items.size) return
        items = next
        send(context, "")
    }

    /** Only these are on the front now. */
    @Synchronized
    fun keep(context: Context, keys: Set<String>) {
        val next = items.filter { it.key in keys || dev.kb896.aiusage.data.NoticeMatch.remote(it.key) }
        if (next.size == items.size) return
        items = next
        send(context, "")
    }

    private fun send(context: Context, pop: String, wake: Boolean = false) {
        Graph.of(context).bridge.setNotices(ShellNotices.encode(items, icons), pop, wake)
    }
}
