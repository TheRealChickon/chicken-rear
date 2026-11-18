package dev.kb896.aiusage.rear

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log

object CaptureStore {
    private const val TAG = "AiUsageRear"

    /** The last screenshot saved, and whether it is wanted gone (binned before its save was done). Under this object's lock. */
    private var lastShot: android.net.Uri? = null
    private var binLast = false

    /** The last screenshot out of the gallery, now or as soon as it is in. */
    fun deleteLastShot(context: Context) {
        val uri = synchronized(this) { lastShot.also { if (it == null) binLast = true; lastShot = null } } ?: return
        Thread({
            runCatching { context.applicationContext.contentResolver.delete(uri, null, null) }
                .onSuccess { Log.i(TAG, "capture: screenshot binned from the back") }
                .onFailure { Log.w(TAG, "capture: not binned: ${it.message}") }
        }, "capture-bin").apply { isDaemon = true; start() }
    }

    /** [file] into the gallery, off the caller's thread; closed after. [name]: the screenshot's (BackShots.name); null makes one. */
    fun save(context: Context, file: android.os.ParcelFileDescriptor, video: Boolean, name: String? = null) {
        Thread({
            runCatching { copy(context.applicationContext, file, video, name) }
                .onFailure { Log.w(TAG, "capture: not saved: ${it.message}") }
            runCatching { file.close() }
        }, "capture-store").apply { isDaemon = true; start() }
    }

    /**
     * The shell saved screenshot [name] itself while this app was frozen (ShotKeeper.Claim.SCAN): the media store asked
     * to look at its file (the public way to MediaProvider's scan, harmless if it is in already).
     */
    fun scanShellShot(context: Context, name: String) {
        if (!BackShots.isName(name)) return
        val path = BackShots.shellPath(name)
        android.media.MediaScannerConnection.scanFile(context.applicationContext, arrayOf(path), arrayOf(BackShots.MIME)) { _, uri ->
            Log.i(TAG, "capture: the shell had saved $name while the app was away — ${if (uri != null) "in the media store" else "the media store did not take it"}")
        }
    }

    private fun copy(context: Context, file: android.os.ParcelFileDescriptor, video: Boolean, name: String?) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            if (video) {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Back_$stamp.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Screen recordings")
            } else {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name?.takeIf { BackShots.isName(it) } ?: BackShots.name(System.currentTimeMillis()))
                put(MediaStore.MediaColumns.MIME_TYPE, BackShots.MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, BackShots.RELATIVE_PATH)
            }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("the media store took nothing")
        try {
            val copied = resolver.openOutputStream(uri)?.use { out ->
                java.io.FileInputStream(file.fileDescriptor).use { it.copyTo(out) }
            } ?: error("no stream")
            if (copied == 0L) error("nothing in it")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Log.i(TAG, "capture: ${if (video) "recording" else "screenshot"} of the back saved ($copied bytes)")
            if (!video) {
                // Binned on the back before it was in: out again at once.
                val bin = synchronized(this) { binLast.also { binLast = false; lastShot = if (it) null else uri } }
                if (bin) { resolver.delete(uri, null, null); Log.i(TAG, "capture: screenshot binned from the back") }
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
