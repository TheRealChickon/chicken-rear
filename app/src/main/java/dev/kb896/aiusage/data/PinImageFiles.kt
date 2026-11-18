package dev.kb896.aiusage.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * The pinned pictures' files (the rules are [PinImage]'s): read once as a picture is shared, kept in the app's external
 * files folder `pins/`, handed to Xiaomi's own pin through a content address of our own, and deleted with their notes.
 *
 * READING: ImageDecoder, from the content address the share granted, ONCE, as it is shared (the address is never kept:
 * its grant ends with the screen that got it). Asked for the FRONT's size before anything is decoded
 * ([PinImage.fitFront]: 2400 px on its longest side at most), so a huge picture is never held whole (JPEG, HEIF and WebP
 * are decoded at a fraction of their size, PNG a row at a time); it is turned by its own orientation (EXIF) as
 * ImageDecoder does by itself, a GIF or an animated picture gives its first frame, the colours are made sRGB, an HDR
 * picture's gain map is let go. From that one decoded picture come BOTH copies: the back's, made smaller by halves
 * ([shrink], [PinImage.fit]) and kept as JPEG (quality [QUALITY]) or WebP where the picture has see-through parts, written
 * to a file of its own and then renamed, so the back never reads half a file; and THE FRONT'S ([PinImage.FRONT_DIR], the
 * app's private files, the same name), written the same way on a thread of its own just after, so the pin does not wait
 * for it ([writeFront]). Deleting a note's picture ([forget]) and the sweep ([sweep]) take both copies.
 */
object PinImageFiles {
    private const val TAG = "AiUsagePin"
    const val QUALITY = 90
    /** The FileProvider's authority, after the package (the manifest's `${applicationId}.pins`). */
    const val AUTHORITY_SUFFIX = ".pins"

    /** A picture read and kept: its file's name (the note's [Pin.image]), the time in it (the note's [Pin.at]), and the picture itself, for the pin's own drawing now. */
    class Imported(val name: String, val picture: Bitmap, val at: Long)

    fun dir(context: Context): File? = context.applicationContext.getExternalFilesDir(null)?.let { File(it, PinImage.DIR) }

    /** The front's larger copies: the app's PRIVATE files (never the external folder the back's shell reads). */
    fun frontDir(context: Context): File = File(context.applicationContext.filesDir, PinImage.FRONT_DIR)

    /** The file of [name], or null for a name that is not one of ours. */
    fun file(context: Context, name: String?): File? = if (!PinImage.valid(name)) null else dir(context)?.let { File(it, name!!) }

    /** The front's larger copy of [name] (it may not be there: a small picture, or one pinned before there were any), or null for a name that is not one of ours. */
    fun frontFile(context: Context, name: String?): File? = if (!PinImage.valid(name)) null else File(frontDir(context), name!!)

    /** The front's copies are written here, one at a time, after their back copies (a test puts in its own to wait for them). */
    @Volatile internal var frontWork: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "pin-front-copy").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    /**
     * The picture at [uri] read once, scaled and kept under a name made from [at] (made unique against the folder);
     * null where it cannot be read or kept (said in the log, without the address). OFF THE MAIN THREAD: a picture may
     * come from another app's storage, or from the network through it.
     */
    fun import(context: Context, uri: Uri, at: Long): Imported? = runCatching {
        importFrom(context, ImageDecoder.createSource(context.applicationContext.contentResolver, uri), at)
    }.onFailure { Log.w(TAG, "pin: a shared picture could not be read (${it.javaClass.simpleName})") }.getOrNull()

    /**
     * [source] decoded ONCE, at the front's size ([decodeFront]); the back's copy made from it ([shrink]) and kept, and the
     * front's copy kept beside it where it is larger ([save], [writeFront]). The back's copy is what comes back.
     */
    fun importFrom(context: Context, source: ImageDecoder.Source, at: Long): Imported {
        val large = decodeFront(source)
        val (w, h) = PinImage.fit(large.width, large.height) ?: throw java.io.IOException("a picture with no size")
        val back = shrink(large, w, h)
        return save(context, back, at, front = large.takeIf { it !== back && PinImage.frontWorth(it.width, it.height, back.width, back.height) })
    }

    /**
     * [read] (a shared picture's [import]) run on [io] for a screen's coroutine; where that coroutine is cancelled
     * meanwhile (the screen went while the picture was read: no note will take it), what the read made is handed to
     * [drop] (its file deleted) and the cancellation goes on. A blocking read is not cut short, so what it wrote is
     * known when it returns: a picture read for a screen that has gone never outlives it.
     */
    suspend fun <T : Any> readOrDrop(io: kotlin.coroutines.CoroutineContext, read: () -> T?, drop: (T) -> Unit): T? {
        var made: T? = null
        try {
            kotlinx.coroutines.withContext(io) { made = read() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            made?.let { runCatching { drop(it) } }
            throw e
        }
        return made
    }

    /** [source] decoded at its stored size ([PinImage.fit]), in software memory, sRGB, its first frame. */
    fun decode(source: ImageDecoder.Source): Bitmap = decodeAt(source) { w, h -> PinImage.fit(w, h) }

    /** [source] decoded at the front's size ([PinImage.fitFront]), as [decode] is otherwise. */
    fun decodeFront(source: ImageDecoder.Source): Bitmap = decodeAt(source) { w, h -> PinImage.fitFront(w, h) }

    private fun decodeAt(source: ImageDecoder.Source, size: (Int, Int) -> Pair<Int, Int>?): Bitmap = ImageDecoder.decodeBitmap(source) { d, info, _ ->
        val (w, h) = size(info.size.width, info.size.height) ?: throw java.io.IOException("a picture with no size")
        d.setTargetSize(w, h)
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        // A picture cut short: what was read of it, not nothing.
        d.setOnPartialImageListener { true }
    }

    /**
     * [picture] made [w] x [h], by halves ([PinImage.halvings]), each step filtered: the back's copy from the front's.
     * [picture] itself where it is that size already; it is never changed (the steps between are let go).
     */
    fun shrink(picture: Bitmap, w: Int, h: Int): Bitmap {
        var at = picture
        for ((sw, sh) in PinImage.halvings(picture.width, picture.height, w, h)) {
            val next = Bitmap.createScaledBitmap(at, sw, sh, true)
            if (at !== picture && at !== next) at.recycle()
            at = next
        }
        return at
    }

    /**
     * [picture] kept under a new name from [at] (the next free millisecond if a kept picture has that time). [front]: the
     * same picture larger, kept as the front's copy under the same name, on [frontWork] after this has returned.
     */
    fun save(context: Context, picture: Bitmap, at: Long, front: Bitmap? = null): Imported {
        val folder = dir(context) ?: throw java.io.IOException("no external files folder")
        folder.mkdirs()
        // An HDR picture's gain map would double the file and is not shown on the back.
        runCatching { if (picture.hasGainmap()) picture.gainmap = null }
        val alpha = picture.hasAlpha()
        // Its time is its note's name too: never one a kept picture has already, whatever its size.
        val taken = folder.list().orEmpty().mapNotNullTo(HashSet()) { it.substringBefore('-', "").toLongOrNull() }
        var t = at
        while (t in taken) t++
        val name = PinImage.name(t, picture.width, picture.height, alpha)
        val part = File(folder, "$name.part")
        part.outputStream().use { out ->
            check(picture.compress(if (alpha) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG, QUALITY, out)) { "not written" }
        }
        if (!part.renameTo(File(folder, name))) { part.delete(); throw java.io.IOException("not renamed") }
        if (front != null) {
            val app = context.applicationContext
            runCatching { frontWork.execute { writeFront(app, name, front, alpha) } }
                .onFailure { Log.w(TAG, "pin: the front's copy was not written (${it.javaClass.simpleName}); the front shows the back's") }
        }
        return Imported(name, picture, t)
    }

    /**
     * THE FRONT'S COPY of [name], [front], kept in the app's private files: written to a file of its own, then renamed. Its
     * note may have gone while it was written ([forget] deletes the back's copy before the front's): it looks for the
     * back's copy after its own is in place, and where that has gone, deletes its own, so no front copy outlives its note.
     * A failure costs only sharpness: the front shows the back's copy then.
     */
    internal fun writeFront(context: Context, name: String, front: Bitmap, alpha: Boolean) {
        runCatching {
            runCatching { if (front.hasGainmap()) front.gainmap = null }
            val folder = frontDir(context).apply { mkdirs() }
            val part = File(folder, "$name.part")
            part.outputStream().use { out ->
                check(front.compress(if (alpha) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG,
                    if (alpha) QUALITY else PinImage.FRONT_QUALITY, out)) { "not written" }
            }
            val kept = File(folder, name)
            if (!part.renameTo(kept)) { part.delete(); throw java.io.IOException("not renamed") }
            if (file(context, name)?.isFile != true) {
                kept.delete()
                Log.i(TAG, "pin: a front copy let go as it was written — its note had gone meanwhile")
            }
        }.onFailure { Log.w(TAG, "pin: the front's copy could not be kept (${it.javaClass.simpleName}); the front shows the back's") }
    }

    /** The kept picture [name] decoded (a small file: a few ms), or null. [most]: the longest side wanted, a thumbnail's. */
    fun read(context: Context, name: String?, most: Int = 0): Bitmap? {
        val f = file(context, name)?.takeIf { it.isFile } ?: return null
        return runCatching {
            val o = BitmapFactory.Options()
            if (most > 0) {
                val (w, h) = PinImage.size(name) ?: (0 to 0)
                var s = 1
                while (maxOf(w, h) / (s * 2) >= most) s *= 2
                o.inSampleSize = s
            }
            BitmapFactory.decodeFile(f.path, o)
        }.getOrNull()
    }

    /**
     * The files of [names] deleted (their notes are gone), BOTH COPIES: the back's first (a front copy being written
     * looks for it: [writeFront]), then the front's. The ones Xiaomi's pin was let read, no longer.
     */
    fun forget(context: Context, names: Collection<String>) {
        for (n in names) {
            file(context, n)?.let { f ->
                runCatching { context.applicationContext.revokeUriPermission(uriOf(context, f), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                if (f.delete()) Log.i(TAG, "pin: a pinned picture's file deleted with its note")
            }
            frontFile(context, n)?.let { runCatching { it.delete() } }
        }
    }

    /**
     * As the app starts: files no note of [pins] has, nor [keep] (the place prompt's), and not just being read, deleted,
     * in both folders (the back's and the front's).
     */
    fun sweep(context: Context, pins: List<Pin>, keep: Set<String>, now: Long = System.currentTimeMillis()) {
        var gone = 0
        for (folder in listOfNotNull(dir(context), frontDir(context))) {
            val files = folder.listFiles().orEmpty().filter { it.isFile }
            // Half-written files of a read that never finished (the process ended mid-write).
            for (f in files) if (f.name.endsWith(".part") && now - f.lastModified() >= PinImage.YOUNG_MS) f.delete()
            val orphans = PinImage.orphans(files.map { it.name to it.lastModified() }, pins, keep, now)
            for (n in orphans) File(folder, n).delete()
            gone += orphans.size
        }
        if (gone > 0) Log.i(TAG, "pin: $gone picture file(s) with no note swept")
    }

    /** A file far larger than a front copy can be is not one of ours to show. */
    private const val VIEW_MOST_BYTES = 40L * 1024 * 1024

    /**
     * The picture of [name] for the front's viewer (ui/PinViewActivity): its front copy where there is one, else the
     * back's (a picture pinned before the front copies, or one no larger than its back copy); null where neither can be
     * read. Never decoded beyond [most] on its longest side. Off the main thread.
     */
    fun readForView(context: Context, name: String?, most: Int = 4096): Bitmap? {
        val f = frontFile(context, name)?.takeIf { it.isFile && it.length() in 1..VIEW_MOST_BYTES }
            ?: file(context, name)?.takeIf { it.isFile && it.length() in 1..VIEW_MOST_BYTES } ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var s = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / s > most) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }.getOrNull()
    }

    /** The content address of kept picture [name] (our FileProvider), for Xiaomi's pin to read; null for none. */
    fun uri(context: Context, name: String?): Uri? = file(context, name)?.takeIf { it.isFile }?.let { runCatching { uriOf(context, it) }.getOrNull() }

    private fun uriOf(context: Context, f: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(context.applicationContext, context.packageName + AUTHORITY_SUFFIX, f)
}
