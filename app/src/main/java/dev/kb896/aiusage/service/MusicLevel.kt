package dev.kb896.aiusage.service

import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dev.kb896.aiusage.data.ConfigStore

/**
 * How loud what is playing is, for the back's music page to rise and fall with ("Follows the music"). OFF UNTIL HE
 * SWITCHES IT ON.
 *
 * WHERE THE LEVEL COMES FROM, AND WHY IT NEEDS A PERMISSION. Android tells an app nothing about another app's
 * sound without `RECORD_AUDIO`: a media session gives the track, its state and its position, never its level; the
 * player's audio session id is not given to other apps; and `android.media.audiofx.Visualizer`, the one API that
 * measures what is being played (here on the output mix, session 0, with its peak and RMS measurement), refuses to
 * be made without that permission. So there is nothing to be had without it and it is the user's to give: the
 * settings ask for it when the switch is turned on, and nowhere else. NOT read through the shell process, which has
 * the permission by its uid: that would be going round the phone's rule (as with the position). NOTHING IS RECORDED
 * OR KEPT: one number, twenty times a second, goes to the back while its music page is lit.
 *
 * Only while all of this holds: the switch on, the permission given, something playing, and the back lit
 * (PanelAwake). Anything refused (the permission taken back, the system declining in the background) is said once
 * in the log, and the page breathes by itself as it does without this.
 */
object MusicLevel {
    private const val TAG = "AiUsageMusic"
    private const val TICK_MS = 50L
    /** The RMS that counts as silence and as loud, in millibels below full scale. */
    const val QUIET_MB = -5000
    const val LOUD_MB = -900

    /** A measured RMS (millibels, 0 = full scale, -9600 = nothing) as a level from 0 to 1, even steps in decibels. */
    fun level(rmsMilliBel: Int): Float = ((rmsMilliBel - QUIET_MB).toFloat() / (LOUD_MB - QUIET_MB)).coerceIn(0f, 1f)

    fun allowed(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val thread by lazy { HandlerThread("music-level").apply { isDaemon = true; start() } }
    private val handler by lazy { Handler(thread.looper) }
    private var visualizer: Visualizer? = null
    private val measure = Visualizer.MeasurementPeakRms()
    @Volatile private var playing = false
    private var refused = ""

    /** Something is playing, or has stopped (MediaWatcher), or a setting changed: looked at again. */
    fun sync(context: Context, isPlaying: Boolean = playing) {
        playing = isPlaying
        val app = context.applicationContext
        handler.removeCallbacksAndMessages(null)
        handler.post { tick(app) }
    }

    /**
     * A debug build with a file `files/level-probe` in the app's shared folder measures whatever else holds (nothing
     * playing, the back dark, the switch off), to see whether the phone lets it; never without the permission.
     */
    private fun probe(app: Context) = dev.kb896.aiusage.BuildConfig.DEBUG &&
        runCatching { java.io.File(app.getExternalFilesDir(null), "level-probe").isFile }.getOrDefault(false)
    private var saidAt = 0L
    private var madeAt = 0L
    private var loudest = -9600

    private fun tick(app: Context) {
        val probing = probe(app)
        val want = allowed(app) && (probing || (playing && PanelAwake.isAwake() && runCatching { ConfigStore(app).load().musicFollows }.getOrDefault(false)))
        if (!want) { stop(); return }
        // The probe makes it anew every ten seconds: whether the phone lets it be MADE in the background is the question.
        if (probing && visualizer != null && android.os.SystemClock.uptimeMillis() - madeAt > 10_000L) stop()
        val v = visualizer ?: runCatching {
            Visualizer(0).apply {
                enabled = false
                measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
                enabled = true
            }
        }.onFailure { say("the sound's level cannot be read: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}") }.getOrNull()?.also {
            visualizer = it
            madeAt = android.os.SystemClock.uptimeMillis()
            refused = ""
            Log.i(TAG, "level: following what is playing")
        }
        if (v != null) {
            val ok = runCatching { v.getMeasurementPeakRms(measure) == Visualizer.SUCCESS }.getOrDefault(false)
            if (ok) runCatching { Graph.of(app).bridge.setMusicLevel(level(measure.mRms)) }
            // Said every two seconds: that it is measured at all (in the background too) is what has to be seen on the phone.
            val now = android.os.SystemClock.uptimeMillis()
            if (ok) loudest = maxOf(loudest, measure.mRms)
            if (now - saidAt >= 2_000L) {
                saidAt = now
                Log.i(TAG, if (ok) "level: ${"%.2f".format(level(loudest))} at most in the last two seconds (rms $loudest mB)${if (probing) ", probe" else ""}" else "level: no measurement given")
                loudest = -9600
            }
            handler.postDelayed({ tick(app) }, TICK_MS)
        } else {
            // Not to be had now: asked again in a while, not twenty times a second.
            handler.postDelayed({ tick(app) }, 5_000L)
        }
    }

    private fun stop() {
        val v = visualizer ?: return
        visualizer = null
        runCatching { v.enabled = false; v.release() }
        Log.i(TAG, "level: not followed now")
    }

    private fun say(what: String) { if (what != refused) { refused = what; Log.w(TAG, "level: $what") } }
}
