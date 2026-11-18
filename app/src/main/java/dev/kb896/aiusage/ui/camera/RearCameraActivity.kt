package dev.kb896.aiusage.ui.camera

import android.Manifest
import android.app.KeyguardManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.camera.view.PreviewView
import dev.kb896.aiusage.Panel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class RearCameraActivity : ComponentActivity() {
    private lateinit var page: RearCameraView
    private val main by lazy { ContextCompat.getMainExecutor(this) }
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var image: ImageCapture? = null
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var displayedSeconds = -1L

    private var mode = RearCameraPolicy.Mode.PHOTO
    private var wideOn = false
    private var flash = false
    private var bokeh = false
    private var mainId: String? = null
    private var wide: RearCameraPolicy.Wide? = null
    private var ids: List<String> = emptyList()
    private var cameraOpen = false
    private var previewStreaming = false
    private var zoomPending = false
    private val ready get() = cameraOpen && previewStreaming && !zoomPending
    private var problem: String? = null
    private var transientNote: String? = null
    private var finalizing = false
    private var capturing = false
    private var leaving = false
    private var generation = 0
    private var sound: android.media.MediaActionSound? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing of the intent is read: there is no way in but the shell's plain start.
        if (!allowedHere()) { finishAndRemoveTask(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        page = RearCameraView(this)
        setContentView(page)
        hideBars()
        page.onBack = { leave() }
        page.onMode = { m -> if (!busy() && !leaving && m != mode) { mode = m; bind() } }
        page.onFocus = { x, y -> focusAt(x, y) }
        page.onThumbnail = { openLast() }
        page.shutter.setOnClickListener { if (mode == RearCameraPolicy.Mode.VIDEO) toggleVideo() else takePhoto() }
        page.zoom.setOnClickListener {
            if (busy() || leaving) return@setOnClickListener
            wideOn = !wideOn
            prefs.edit().putBoolean(KEY_WIDE, wideOn).apply()
            // A zoom of the main camera needs no new start of the camera; another lens does.
            val w = wide
            if (w != null && w.key == null && ready) zoomTo(w) else bind()
        }
        page.flash.setOnClickListener { if (!busy() || recording != null) { flash = !flash; flashToMode(); showControls() } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = leave() })
        sound = runCatching {
            android.media.MediaActionSound().apply {
                load(android.media.MediaActionSound.SHUTTER_CLICK)
                load(android.media.MediaActionSound.START_VIDEO_RECORDING)
                load(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
            }
        }.getOrNull()
        wideOn = prefs.getBoolean(KEY_WIDE, RearCameraPolicy.WIDE_BY_DEFAULT)
        prefs.getString(KEY_LAST, null)?.let { thumbnail(Uri.parse(it)) }
        page.controlsEnabled(false)
        page.note("Opening camera…")
        page.preview.previewStreamState.observe(this) { state ->
            previewStreaming = state == PreviewView.StreamState.STREAMING
            showControls()
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val p = future.get().also { provider = it }
                val infos = p.availableCameraInfos
                ids = infos.map { idOf(it) }
                val back = CameraSelector.DEFAULT_BACK_CAMERA.filter(infos).firstOrNull() ?: error("no back camera")
                mainId = idOf(back)
                wide = RearCameraPolicy.wide(back.zoomState.value?.minZoomRatio ?: 1f,
                    infos.mapIndexed { i, info -> RearCameraPolicy.Lens(i, info.lensFacing == CameraSelector.LENS_FACING_BACK && idOf(info) != mainId, info.intrinsicZoomRatio) })
                Log.i(TAG, "back cameras: main ${mainId}, wide ${wide?.let { if (it.key == null) "by zoom ${it.zoom}" else "camera ${ids.getOrNull(it.key)} (${it.label})" } ?: "none for apps"}")
                // No wide view for apps: the main camera, and no chip (his choice is kept for a phone that has one).
                if (wide == null) wideOn = false
                // The viewfinder first; whether there is a portrait mode is asked meanwhile.
                page.preview.doOnLayout { bind() }
                val ext = ExtensionsManager.getInstanceAsync(this, p)
                ext.addListener({
                    if (isFinishing || isDestroyed) return@addListener
                    extensions = runCatching { ext.get() }.onFailure { Log.i(TAG, "no camera extensions: ${it.message}") }.getOrNull()
                    val had = bokeh
                    bokeh = runCatching { extensions?.isExtensionAvailable(selector(wideLens = false), ExtensionMode.BOKEH) == true }.getOrDefault(false)
                    Log.i(TAG, "portrait (bokeh) for apps: $bokeh")
                    if (bokeh != had) showControls()
                }, main)
            } catch (e: Exception) { fail("Camera not available", e) }
        }, main)
    }

    private fun allowedHere(): Boolean = runCatching {
        display?.displayId == Panel.DISPLAY_ID &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun idOf(info: CameraInfo): String = Camera2CameraInfo.from(info).cameraId

    /** The back camera in use: the main one, or the wide one where it is a camera of its own ([wideLens]). */
    private fun selector(wideLens: Boolean = RearCameraPolicy.useWide(mode, wideOn, wide)): CameraSelector {
        val id = if (wideLens) wide?.key?.let { ids.getOrNull(it) } ?: mainId else mainId
        return CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { list -> list.filter { idOf(it) == id } }.build()
    }

    private fun bind() {
        val p = provider ?: return
        if (isFinishing || isDestroyed || busy() || leaving) return
        if (!allowedHere()) { leave(); return }
        val gen = ++generation
        cameraOpen = false; previewStreaming = false; zoomPending = false; problem = null
        page.controlsEnabled(false); page.note("Opening camera…")
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        camera?.cameraControl?.enableTorch(false)
        p.unbindAll()
        camera = null; image = null; video = null
        if (mode == RearCameraPolicy.Mode.PORTRAIT && !bokeh) mode = RearCameraPolicy.Mode.PHOTO
        val rotation = UPRIGHT
        try {
            var chosen = selector()
            if (mode == RearCameraPolicy.Mode.PORTRAIT) chosen = extensions!!.getExtensionEnabledCameraSelector(chosen, ExtensionMode.BOKEH)
            val preview = Preview.Builder().setTargetRotation(rotation).build().also { it.setSurfaceProvider(page.preview.surfaceProvider) }
            val group = UseCaseGroup.Builder().addUseCase(preview)
            // What is taken is what the back shows: cut to the panel's shape, upright as the phone is.
            group.setViewPort(checkNotNull(page.preview.getViewPort(rotation)))
            if (mode == RearCameraPolicy.Mode.VIDEO) {
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))).build()
                group.addUseCase(VideoCapture.withOutput(recorder).also { it.targetRotation = rotation; video = it })
            } else {
                group.addUseCase(ImageCapture.Builder().setTargetRotation(rotation)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF).build().also { image = it })
            }
            val c = p.bindToLifecycle(this, chosen, group.build()).also { camera = it }
            if (!c.cameraInfo.hasFlashUnit()) flash = false
            flashToMode()
            val initialZoom = wide?.takeIf { it.key == null && RearCameraPolicy.useWide(mode, wideOn, it) }
            var needsInitialZoom = initialZoom != null
            zoomPending = needsInitialZoom
            c.cameraInfo.cameraState.observe(this) { state ->
                if (gen != generation) return@observe
                cameraOpen = state.type == CameraState.Type.OPEN && state.error == null
                state.error?.let { Log.w(TAG, "camera state: ${state.type} error ${it.code}") }
                problem = RearCameraPolicy.cameraProblem(state.type, state.error?.code, problem)
                if (cameraOpen && needsInitialZoom && initialZoom != null) {
                    needsInitialZoom = false
                    zoomTo(initialZoom)
                } else showControls()
            }
            showControls()
        } catch (e: Exception) {
            if (mode == RearCameraPolicy.Mode.PORTRAIT) {
                // Offered and then refused: no portrait at all, never a pretended one.
                Log.w(TAG, "portrait could not be set up: ${e.message}")
                bokeh = false; mode = RearCameraPolicy.Mode.PHOTO; bind()
            } else if (RearCameraPolicy.useWide(mode, wideOn, wide) && wide?.key != null) {
                Log.w(TAG, "ultra-wide could not be bound in $mode, opening the main camera", e)
                wideOn = false
                bind()
            } else fail("Camera not available", e)
        }
    }

    private fun showControls() {
        if (!::page.isInitialized) return
        page.showModes(mode, bokeh)
        page.shutter.video = mode == RearCameraPolicy.Mode.VIDEO
        page.shutter.recording = recording != null
        val canCapture = ready && !busy() && !leaving
        page.controlsEnabled(canCapture, canCapture || (mainId != null && problem == "Camera not available" && !busy() && !leaving))
        // While filming the shutter stops it and the flash is the torch; the rest waits.
        if (recording != null && !finalizing && !leaving) {
            page.shutter.isEnabled = true
            page.shutter.alpha = 1f
            page.flash.isEnabled = ready
        }
        // No "0.7×" in Portrait: the phone's portrait mode is the main camera's.
        page.zoom.visibility = if (wide == null || mode == RearCameraPolicy.Mode.PORTRAIT) android.view.View.GONE else android.view.View.VISIBLE
        page.zoom.text = RearCameraPolicy.zoomLabel(wideOn, wide)
        page.zoom.contentDescription = if (wideOn) "Ultra-wide, tap for the main camera" else "Main camera, tap for the ultra-wide"
        page.flash.visibility = if (camera?.cameraInfo?.hasFlashUnit() == true) android.view.View.VISIBLE else android.view.View.GONE
        page.flash.on = flash
        page.flash.contentDescription = if (flash) "Flash on" else "Flash off"
        page.note(transientNote ?: problem ?: if (ready) "" else "Opening camera…")
    }

    /** The main camera zoomed out to the wide view, or back to 1×, as [wideOn] says ([w]: a zoom of the main camera). */
    private fun zoomTo(w: RearCameraPolicy.Wide) {
        val c = camera ?: return
        val gen = generation
        zoomPending = true
        showControls()
        val z = c.cameraControl.setZoomRatio(if (RearCameraPolicy.useWide(mode, wideOn, w)) w.zoom else 1f)
        z.addListener({
            if (gen != generation || isDestroyed) return@addListener
            zoomPending = false
            if (runCatching { z.get() }.isFailure) {
                // Rebind at the camera's default zoom after either direction fails. A rejected
                // wide setting must not leave a wide label on the main view.
                Log.w(TAG, "zoom change refused, opening the main camera")
                if (wideOn) wide = null
                wideOn = false
                bind()
            } else showControls()
        }, main)
    }

    /** The phone's own camera sound, when it is to be heard (RearCameraPolicy.shutterHeard). */
    private fun click(kind: Int) {
        val audio = getSystemService(android.media.AudioManager::class.java)
        val heard = RearCameraPolicy.shutterHeard(android.media.MediaActionSound.mustPlayShutterSound(),
            audio?.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL)
        if (heard) runCatching { sound?.play(kind) }
    }

    /** The flash as the mode has it: the photo's flash, or the torch while filming. */
    private fun flashToMode() {
        image?.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        camera?.cameraControl?.enableTorch(flash && mode == RearCameraPolicy.Mode.VIDEO)
    }

    private fun focusAt(x: Float, y: Float) {
        if (!ready || leaving) return
        val c = camera ?: return
        runCatching {
            val point = page.preview.meteringPointFactory.createPoint(x, y)
            c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
        }
    }

    private fun takePhoto() {
        val capture = image ?: return
        if (busy() || !ready || leaving) return
        capturing = true; showControls(); page.blink(); click(android.media.MediaActionSound.SHUTTER_CLICK)
        val options = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values("IMG", "image/jpeg", "jpg")).build()
        try {
            capture.takePicture(options, main, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    capturing = false
                    val uri = result.savedUri
                    if (uri != null) kept(uri)
                    if (!isDestroyed) {
                        showControls()
                        if (uri == null) flashNote("Not saved")
                    }
                    leaveIfWaiting()
                }
                override fun onError(e: ImageCaptureException) {
                    capturing = false
                    Log.w(TAG, "photo not saved: ${e.imageCaptureError} ${e.message}")
                    if (!isDestroyed) flashNote("Not saved")
                    leaveIfWaiting()
                }
            })
        } catch (e: Exception) {
            capturing = false; flashNote("Not saved")
            Log.w(TAG, "photo not taken", e); leaveIfWaiting()
        }
    }

    private fun toggleVideo() {
        if (finalizing || capturing || leaving) return
        recording?.let { finalizing = true; it.stop(); showControls(); return }
        val recorder = video?.output ?: return
        if (!ready) return
        val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
            .setContentValues(values("VID", "video/mp4", "mp4")).build()
        try {
            var pending = recorder.prepareRecording(this, options)
            if (hasMicrophone()) pending = pending.withAudioEnabled()
            click(android.media.MediaActionSound.START_VIDEO_RECORDING)
            displayedSeconds = 0L
            recording = pending.start(main) { event ->
                when (event) {
                    is VideoRecordEvent.Status -> {
                        val seconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                        if (!isDestroyed && seconds != displayedSeconds) {
                            displayedSeconds = seconds
                            page.recording(clock(seconds))
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null; finalizing = false
                        val uri = event.outputResults.outputUri
                        val saved = uri != Uri.EMPTY && RearCameraPolicy.keepsVideo(event.error)
                        if (event.hasError()) Log.w(TAG, "film ended with error ${event.error}: ${event.cause?.message}")
                        if (saved) kept(uri) else if (uri != Uri.EMPTY) {
                            mediaWork.execute {
                                runCatching { contentResolver.delete(uri, null, null) }
                                    .onFailure { Log.w(TAG, "could not remove failed recording", it) }
                            }
                        }
                        if (!isDestroyed) {
                            click(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
                            page.recording(null)
                            if (event.error == VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR) bind()
                            showControls()
                            if (!saved) flashNote("Not saved")
                        }
                        leaveIfWaiting()
                    }
                    else -> Unit
                }
            }
            page.recording(clock(0))
            showControls()
        } catch (e: Exception) {
            recording = null; flashNote("Not recorded"); Log.w(TAG, "film not started", e)
        }
    }

    private fun clock(seconds: Long) = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

    private fun values(prefix: String, mime: String, ext: String) = ContentValues().apply {
        // The phone's own names (IMG_20261117_153012.jpg), in its own camera folder.
        put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())}.$ext")
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
    }

    /** A photo or film was kept: the round picture top right becomes it, now and next time. */
    private fun kept(uri: Uri) {
        prefs.edit().putString(KEY_LAST, uri.toString()).apply()
        thumbnail(uri)
    }

    private fun thumbnail(uri: Uri) {
        if (isDestroyed) return
        mediaWork.execute {
            // Only what this page took (the app may read its own); one deleted since shows nothing.
            val bitmap = runCatching { contentResolver.loadThumbnail(uri, Size(240, 240), null) }.getOrNull()
            main.execute update@{
                if (isDestroyed || !::page.isInitialized) return@update
                page.lastTaken(bitmap)
                shownLast = if (bitmap == null) null else uri
            }
        }
    }

    /** The photo or film the round picture shows. */
    private var shownLast: Uri? = null

    /**
     * A tap on the round picture: what it shows, opened ON THE FRONT in the phone's own viewer (the back is too small to
     * look at a photo, and the gallery is the front's). Only what this page took; read access lent for that one item.
     */
    private fun openLast() {
        val uri = shownLast ?: return
        runCatching {
            val type = contentResolver.getType(uri) ?: "image/*"
            val view = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            startActivity(view, options.toBundle())
        }
            .onSuccess { Log.i(TAG, "the last one taken opened on the front") }
            .onFailure { Log.w(TAG, "the last one taken could not be opened: ${it.message}"); flashNote("Cannot open it") }
    }

    private val clearNote = Runnable { transientNote = null; showControls() }
    private fun flashNote(text: String) {
        if (!::page.isInitialized) return
        transientNote = text; showControls(); page.removeCallbacks(clearNote); page.postDelayed(clearNote, 2_500L)
    }

    private fun hasMicrophone() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun busy() = recording != null || finalizing || capturing

    private fun fail(text: String, e: Exception) {
        cameraOpen = false; problem = text
        Log.w(TAG, "camera: $text", e)
        showControls()
    }

    private fun hideBars() { WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars()) }

    private val finishLeaving = Runnable { finishAndRemoveTask() }

    /** Back to the face; a photo or film being saved first (15 s at most, if its callback never came). */
    private fun leave() {
        if (leaving) return
        leaving = true
        if (!busy()) { finishAndRemoveTask(); return }
        recording?.let { if (!finalizing) { finalizing = true; it.stop() } }
        showControls()
        window.decorView.postDelayed(finishLeaving, 15_000L)
    }

    private fun leaveIfWaiting() { if (leaving && !busy()) finishAndRemoveTask() }

    override fun onResume() {
        super.onResume()
        if (!allowedHere()) { finishAndRemoveTask(); return }
        hideBars()
        flashToMode()
    }

    // NOT onPause: a page on the back may be paused while still seen (the front's app takes the top focus), and the
    // camera runs on while it is started (CameraX's lifecycle). Only not seen any more ends a film (onStop).

    override fun onStop() {
        camera?.cameraControl?.enableTorch(false)
        super.onStop()
        // Not seen any more (the back off, the phone locked, another page over it): the page closes once what is
        // being saved is kept, and the back comes back to its face when it is seen again.
        if (!isChangingConfigurations) leave()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!allowedHere()) { leave(); return }
        hideBars()
    }

    override fun onDestroy() {
        generation++
        if (::page.isInitialized) {
            page.removeCallbacks(clearNote)
            window.decorView.removeCallbacks(finishLeaving)
        }
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        recording?.close()
        camera?.cameraControl?.enableTorch(false)
        provider?.unbindAll()
        sound?.release(); sound = null
        super.onDestroy()
    }

    companion object {
        // CameraX can finish a save after its activity has closed.
        private val mediaWork = Executors.newSingleThreadExecutor()
        private const val TAG = "AiUsageRearCamera"
        private const val PREFS = "rear_camera"
        private const val KEY_LAST = "last_taken"
        private const val KEY_WIDE = "wide"
        /** The phone's natural orientation: the back's "up" (see the class's note). */
        private const val UPRIGHT = android.view.Surface.ROTATION_0
    }
}
