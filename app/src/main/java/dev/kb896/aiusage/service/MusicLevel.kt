package dev.kb896.aiusage.service

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dev.kb896.aiusage.data.ConfigStore

object MusicLevel {
    private const val TAG = "AiUsageMusic"
    private const val TICK_MS = 33L
    private const val VOLUME_MS = 500L

    fun allowed(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val thread by lazy { HandlerThread("music-level").apply { isDaemon = true; start() } }
    private val handler by lazy { Handler(thread.looper) }
    private var visualizer: Visualizer? = null
    private val measure = Visualizer.MeasurementPeakRms()
    private var wave = ByteArray(0)
    private var fsHz = 48_000
    private val sound = MusicSound()
    @Volatile private var playing = false
    @Volatile private var track: String? = null
    private var refused = ""

    /**
     * Something is playing, or has stopped (MediaWatcher), or a setting changed, or the back lit again: looked at
     * again. [trackKey]: which track (title and artist); a new one starts the song's own range afresh.
     */
    fun sync(context: Context, isPlaying: Boolean = playing, trackKey: String? = track) {
        playing = isPlaying
        val app = context.applicationContext
        listenForWake(app)
        val newTrack = trackKey != track
        track = trackKey
        handler.removeCallbacksAndMessages(null)
        handler.post {
            if (newTrack) sound.newTrack()
            probeStale = true
            tick(app)
        }
    }

    /** Called with the back lit again (PanelAwake): the same function each time, registered once. */
    @Volatile private var wake: (() -> Unit)? = null

    /** Registers, once, to be looked at again whenever the back can be seen again. */
    internal fun listenForWake(app: Context) {
        if (wake != null) return
        synchronized(this) {
            if (wake != null) return
            val w: () -> Unit = { sync(app) }
            wake = w
            PanelAwake.addWakeListener(w)
        }
    }

    /**
     * A debug build with a file `files/level-probe` in the app's shared folder measures whatever else holds (nothing
     * playing, the back dark, the switch off), to see whether the phone lets it; never without the permission.
     * The file is looked for once a second ([PROBE_MS]) and at every [sync], not at each of the thirty ticks a second.
     */
    private fun probe(app: Context): Boolean {
        if (!dev.kb896.aiusage.BuildConfig.DEBUG) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (probeStale || now - probedAt >= PROBE_MS) {
            probeStale = false
            probedAt = now
            probeFound = runCatching { java.io.File(app.getExternalFilesDir(null), "level-probe").isFile }.getOrDefault(false)
        }
        return probeFound
    }
    private const val PROBE_MS = 1_000L
    /** The probe file's last answer and when it was read (on the level's own thread only). */
    private var probeFound = false
    private var probedAt = 0L
    private var probeStale = true
    private var saidAt = 0L
    private var madeAt = 0L
    private var volumeAt = 0L
    private var volDb = 0f
    private var outType = AudioDeviceInfo.TYPE_UNKNOWN
    /** The media volume's step at the last reading, and whether an output came or went since. */
    private var volIndex = -1
    @Volatile private var outputsChanged = false
    private var outputsWatched = false

    private fun tick(app: Context) {
        val probing = probe(app)
        val want = allowed(app) && (probing || (playing && PanelAwake.isAwake() && runCatching { ConfigStore(app).load().musicFollows }.getOrDefault(false)))
        if (!want) { stop(); return }
        // The probe makes it anew every ten seconds: whether the phone lets it be MADE in the background is the question.
        if (probing && visualizer != null && android.os.SystemClock.uptimeMillis() - madeAt > 10_000L) stop()
        val v = visualizer ?: runCatching {
            Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[1]
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
                enabled = true
            }
        }.onFailure { say("the sound's level cannot be read: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}") }.getOrNull()?.also {
            visualizer = it
            madeAt = android.os.SystemClock.uptimeMillis()
            refused = ""
            wave = ByteArray(it.captureSize)
            fsHz = (it.samplingRate / 1000).takeIf { r -> r > 0 } ?: 48_000
            sound.newCapture(madeAt)           // (this runs on the level's own thread, as every tick does)
            Log.i(TAG, "level: following what is playing (capture ${wave.size} at $fsHz Hz)")
        }
        if (v != null) {
            val now = android.os.SystemClock.uptimeMillis()
            watchOutputs(app)
            val index = runCatching { app.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: -1
            if (now - volumeAt >= VOLUME_MS || index != volIndex || outputsChanged) { volumeAt = now; outputsChanged = false; readVolume(app) }
            val rms = if (runCatching { v.getMeasurementPeakRms(measure) == Visualizer.SUCCESS }.getOrDefault(false)) measure.mRms else -9600
            val shape = if (runCatching { v.getWaveForm(wave) == Visualizer.SUCCESS }.getOrDefault(false)) wave else null
            val packet = sound.tick(rms, shape, volDb, outType, playing || probing, fsHz, now)
            runCatching { Graph.of(app).bridge.setMusicSound(packet) }
            // Said every two seconds, as ranges: whether it is measured at all (in the background too), and what.
            if (now - saidAt >= 2_000L && sound.stats.count > 0) {
                saidAt = now
                Log.i(TAG, sound.stats.line(sound.volumeInEffect(volDb, outType), typeName(outType), sound.takenAsUnattenuated) + if (probing) ", probe" else "")
            }
            handler.postDelayed({ tick(app) }, TICK_MS)
        } else {
            // Not to be had now: asked again in a while, not thirty times a second.
            handler.postDelayed({ tick(app) }, 5_000L)
        }
    }

    /** His media volume on the output media goes to now, in dB (0 when it cannot be read: nothing is taken off then). */
    private fun readVolume(app: Context) {
        val am = app.getSystemService(AudioManager::class.java) ?: run { volDb = 0f; return }
        val type = runCatching {
            am.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).firstOrNull()?.type
        }.getOrNull() ?: AudioDeviceInfo.TYPE_UNKNOWN
        outType = type
        volDb = runCatching {
            val index = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            volIndex = index
            val db = am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, type)
            if (db.isFinite()) db.coerceIn(-96f, 0f) else -96f
        }.getOrDefault(0f)
    }

    /** An output coming or going (headphones, Bluetooth) is a volume change too: read at the next tick. Registered once. */
    private fun watchOutputs(app: Context) {
        if (outputsWatched) return
        outputsWatched = true
        runCatching {
            app.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { outputsChanged = true }
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { outputsChanged = true }
            }, handler)
        }
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "wired"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "bt"
        AudioDeviceInfo.TYPE_UNKNOWN -> "output unknown"
        else -> "output type $type"
    }

    private fun stop() {
        val v = visualizer ?: return
        visualizer = null
        runCatching { v.enabled = false; v.release() }
        Log.i(TAG, "level: not followed now")
    }

    private fun say(what: String) { if (what != refused) { refused = what; Log.w(TAG, "level: $what") } }
}
