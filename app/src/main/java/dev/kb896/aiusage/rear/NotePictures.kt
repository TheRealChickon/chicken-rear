package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.kb896.aiusage.data.PinImage
import java.io.File
import java.util.concurrent.Executor

/**
 * THE PINNED PICTURES ON THE BACK, in the shell (the app keeps them as files: data/PinImageFiles). A page asks for a
 * note's picture as it draws ([get]): there it is, or null at once and it is read on a thread of its own, never on a
 * drawing thread and never waited for: that frame shows the note's picture place empty (rear/PinFace), and [ready] is
 * called when it has come, so the pages draw again with it. At most [most] are kept in memory (the note showing and
 * its two neighbours, for a turn), the one longest unused letting go first; a file that is not there (its note just
 * binned) is not asked for again for a while.
 *
 * [dir]: the app's `pins/` folder as this process reads it (the same path the debug files take).
 */
class NotePictures(
    private val dir: String,
    private val ready: () -> Unit,
    private val most: Int = MOST,
    private val work: Executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "note-pictures").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 } },
    private val clock: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {
    private val kept = LinkedHashMap<String, Bitmap>(8, 0.75f, true)
    private val asked = HashSet<String>()
    private val missing = HashMap<String, Long>()

    /** The picture of [name] if it is in memory (now the last used); else null now, and it is read off this thread. */
    @Synchronized
    fun get(name: String?): Bitmap? {
        if (name == null || !PinImage.valid(name)) return null
        kept[name]?.let { return it }
        ask(name)
        return null
    }

    /** Whether the picture of [name] is in memory now (nothing is asked for). */
    @Synchronized
    fun has(name: String?): Boolean = name != null && kept.containsKey(name)

    /** The pictures of [names] read ahead where they are not in memory (the neighbours of the note showing). */
    @Synchronized
    fun warm(names: Collection<String?>) {
        for (n in names) if (n != null && PinImage.valid(n) && !kept.containsKey(n)) ask(n)
    }

    /**
     * [picture], [name]'s, held elsewhere until now (the carousel's card of it: NoteArrangeArt.pictureOf), in memory again
     * as the last used, so the page it gives way to draws it from its first frame; nothing is read.
     */
    @Synchronized
    fun keep(name: String?, picture: Bitmap?) {
        if (name == null || picture == null || picture.isRecycled || !PinImage.valid(name)) return
        kept[name] = picture
        missing.remove(name)
        while (kept.size > most) kept.remove(kept.keys.first())
    }

    /** [name]'s picture let go (its note is gone). */
    @Synchronized
    fun forget(name: String?) { if (name != null) { kept.remove(name); missing.remove(name) } }

    /** How many are in memory (for the test and the log). */
    @Synchronized
    fun size(): Int = kept.size

    private fun ask(name: String) {
        val missed = missing[name]
        if (missed != null && clock() - missed < MISSING_MS) return
        if (!asked.add(name)) return
        work.execute {
            val picture = runCatching { decode(File(dir, name)) }.getOrNull()
            synchronized(this) {
                asked.remove(name)
                if (picture == null) missing[name] = clock()
                else {
                    missing.remove(name)
                    kept[name] = picture
                    while (kept.size > most) kept.remove(kept.keys.first())
                }
            }
            if (picture != null) runCatching { ready() }
        }
    }

    companion object {
        const val MOST = 3
        const val MISSING_MS = 5_000L
        /** A stored picture is small (data/PinImage.MAX_W x MAX_H); a file far larger than that is not one of ours to draw. */
        private const val LARGEST_BYTES = 8L * 1024 * 1024

        /** [f] decoded, smaller where it is larger than a stored picture can be; null where it is not there or not a picture. */
        fun decode(f: File): Bitmap? {
            if (!f.isFile || f.length() > LARGEST_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var s = 1
            while (bounds.outWidth / s > PinImage.MAX_W * 2 || bounds.outHeight / s > PinImage.MAX_H * 2) s *= 2
            return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }
    }
}
