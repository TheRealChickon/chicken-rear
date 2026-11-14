package dev.kb896.aiusage.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File

/**
 * The clips the scene on the back can play (PanelConfig.sceneWake, sceneLoop): MP4 files in one
 * folder of the app's own on shared storage, `Android/data/<package>/files/clips`. The shell
 * that draws on the back reads the same folder (RearUserService.CLIPS_DIR), and so can a
 * computer: a file pushed there with adb is a clip, with nothing to register.
 *
 * A clip is 976x596 (the 17 Pro Max's rear panel), black where the screen should be off. One cut on a computer and
 * pushed with adb must arrive like that. One picked on the phone ([import]) is cut to that shape here and has its
 * near-black switched off on the way.
 *
 * A name says what a clip is for, and nothing depends on it: "x.wake.mp4" is listed first for
 * the wake, "x.loop.mp4" first for the background; any clip can be either.
 */
object ClipStore {
    const val WIDTH = 976
    const val HEIGHT = 596

    data class Clip(val file: String, val title: String, val path: String, val loop: Boolean, val wake: Boolean)

    fun dir(context: Context): File? = context.getExternalFilesDir("clips")?.also { it.mkdirs() }

    /** "night-drive.wake.mp4" reads "Night drive". */
    fun title(file: String): String =
        file.removeSuffix(".mp4").removeSuffix(".wake").removeSuffix(".loop")
            .replace('-', ' ').replace('_', ' ').trim()
            .replaceFirstChar { it.uppercase() }

    fun of(file: String, path: String) = Clip(file, title(file), path, loop = file.endsWith(".loop.mp4"), wake = file.endsWith(".wake.mp4"))

    fun list(context: Context): List<Clip> =
        dir(context)?.listFiles { f -> f.isFile && f.name.endsWith(".mp4") && f.length() > 0 }
            ?.sortedBy { it.name }?.map { of(it.name, it.path) }.orEmpty()

    /** The clips for one slot: the ones named for it first, then the rest. */
    fun ordered(clips: List<Clip>, forLoop: Boolean): List<Clip> =
        clips.sortedBy { if (forLoop) (if (it.loop) 0 else if (it.wake) 2 else 1) else (if (it.wake) 0 else if (it.loop) 2 else 1) }

    fun delete(context: Context, file: String): Boolean {
        if (file.contains('/')) return false
        thumbs.remove(file)
        return dir(context)?.let { File(it, file.removeSuffix(".mp4") + ".json").delete(); File(it, file).delete() } == true
    }

    private val thumbs = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    /** A still from the clip's middle third, small. Blocking: off the main thread. */
    fun thumb(clip: Clip): Bitmap? {
        thumbs[clip.file]?.let { return it }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(clip.path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: WIDTH
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: HEIGHT
            val matte = dev.kb896.aiusage.rear.SceneEngine.hasMatte(w, h)
            // A wake clip opens and closes on black and may cut through it: of a few moments, the
            // one with the most in it says best what the clip is.
            var best: Bitmap? = null
            var bestLight = -1L
            for (share in doubleArrayOf(0.62, 0.42, 0.8, 0.25)) {
                val frame = r.getScaledFrameAtTime((ms * share).toLong() * 1000, MediaMetadataRetriever.OPTION_CLOSEST, WIDTH / 2, if (matte) HEIGHT else HEIGHT / 2) ?: continue
                // A clip with depth has its matte stacked under the picture: the still is the top half.
                val picture = if (matte) Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height / 2) else frame
                var light = 0L
                var y = 0
                while (y < picture.height) { var x = 0; while (x < picture.width) { val px = picture.getPixel(x, y); light += (px shr 16 and 0xFF) + (px shr 8 and 0xFF) + (px and 0xFF); x += 16 }; y += 16 }
                if (light > bestLight) { bestLight = light; best = picture }
                if (light > 40L * 3 * (picture.width / 16) * (picture.height / 16)) break   // plenty to see: this one will do
            }
            val still = best ?: return null
            still.also { thumbs[clip.file] = it }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Near-black to exactly black: a level at or under [BLACK_T] of 255
     * becomes 0 and the rest is stretched back. As a colour cube for Media3's LUT effect.
     */
    internal fun crushCube(n: Int = 33): Array<Array<IntArray>> {
        fun level(i: Int) = i * 255f / (n - 1)
        return Array(n) { r -> Array(n) { g -> IntArray(n) { b ->
            val peak = maxOf(level(r), level(g), level(b))
            val gain = if (peak <= BLACK_T) 0f else ((peak - BLACK_T) / (255f - BLACK_T)) * 255f / peak
            fun out(v: Float) = Math.round(v * gain).coerceIn(0, 255)
            (0xFF shl 24) or (out(level(r)) shl 16) or (out(level(g)) shl 8) or out(level(b))
        } } }
    }

    private const val BLACK_T = 16f
    /** A picked video longer than this is cut to it: these are short clips, not films. */
    private const val MAX_MS = 20_000L

    /**
     * A video picked on the phone made into a clip: its first [MAX_MS], cut to the rear's shape
     * about its middle, scaled to 976x596, near-black switched off, no sound. [done] on the main
     * thread with the clip, or null and why not.
     */
    fun import(context: Context, source: Uri, name: String, done: (Clip?, String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val folder = dir(context) ?: return done(null, "No storage for clips")
        val safe = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "clip" }.take(32)
        var out = File(folder, "$safe.mp4")
        var n = 2
        while (out.exists()) out = File(folder, "$safe-${n++}.mp4")
        val target = out
        main.post {
            try {
                val item = EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(source).setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setEndPositionMs(MAX_MS).build(),
                    ).build(),
                )
                    .setRemoveAudio(true)
                    .setEffects(Effects(emptyList(), listOf(
                        Presentation.createForWidthAndHeight(WIDTH, HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                        SingleColorLut.createFromCube(crushCube()),
                    )))
                    .build()
                val encoders = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(9_000_000).setiFrameIntervalSeconds(0.5f).build(),
                    ).build()
                Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(encoders)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) =
                            done(of(target.name, target.path), null)

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            target.delete()
                            done(null, exportException.message ?: "The video could not be read")
                        }
                    })
                    .build()
                    .start(item, target.path)
            } catch (e: Throwable) {
                target.delete()
                done(null, e.message ?: "The video could not be read")
            }
        }
    }
}
