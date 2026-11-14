package dev.kb896.aiusage.service

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.util.Log

/**
 * THE SOUND OF A PIN. WHAT XIAOMI'S OWN PLAYS, read on the phone: its package holds no chime at all. When its
 * animation has FINISHED (the card gone into the top edge) it posts a notification "Pinned" on its channel `pin`
 * (importance 3, the channel's sound `content://settings/system/notification_sound`, vibration off, not allowed
 * through Do Not Disturb), which the system shows as an island on the front and PLAYS AS THE PHONE'S OWN NOTIFICATION
 * SOUND (WaterDrop, unless changed). No vibration is asked for anywhere in its pin code.
 *
 * So ours plays that same sound, the phone's notification sound as it is set, as a notification's sound
 * (USAGE_NOTIFICATION: its volume, silent in the silent and vibrate modes), at the moment Xiaomi's would
 * ([dev.kb896.aiusage.rear.PinBeam.CHIME_AT]); not in Do Not Disturb, as a notification that may not pass it; and
 * no vibration, because Xiaomi's has none. Never for a demo or a test's pin unless that asks for it.
 */
object PinChime {
    private const val TAG = "AiUsagePin"
    private const val RINGER_NORMAL = 2          // AudioManager.RINGER_MODE_NORMAL
    private const val FILTER_ALL = 1             // NotificationManager.INTERRUPTION_FILTER_ALL
    private const val FILTER_UNKNOWN = 0

    /**
     * Whether a pin sounds: as the notification Xiaomi posts would. [ringerMode] is the phone's (silent and vibrate
     * are silent), [filter] its Do Not Disturb state (anything but "all" holds a notification's sound back; not
     * known counts as none), [demo] a made-up or a test's pin, which sounds only when [asked].
     */
    fun plays(ringerMode: Int, filter: Int, demo: Boolean, asked: Boolean): Boolean =
        (!demo || asked) && ringerMode == RINGER_NORMAL && (filter == FILTER_ALL || filter == FILTER_UNKNOWN)

    private var ringing: Ringtone? = null

    // ---- MADE READY AS THE PIN BEGINS, so that it sounds WHEN IT IS ASKED FOR ----
    // Measured on one pin (`dumpsys audio`'s player events against the animation's own clock): asked for
    // 2066 ms after the pin began, a Ringtone made then (two MediaPlayers, one after the other), "started" at 2108,
    // its track at 2140, its first decoded sound by 2291; and the file itself (WaterDrop_preview.ogg, 1045 ms)
    // is silent for its first 30 ms, with its attack at 34 to 41 ms. So the drop was heard 2.2 to 2.3 s in: a tenth to
    // a quarter of a second after the card had gone and the front was over.
    // Now a SoundPool holds the sound decoded from the pin's first moment, and [play] is asked for [LEAD_MS] before
    // the moment the attack is to be heard.

    private var pool: android.media.SoundPool? = null
    private var sound = 0
    private var soundOf: String? = null
    @Volatile private var ready = false

    /** The file's own silence before its attack (35 ms in WaterDrop) and a ready pool's way to the speaker: asked for this long before it is to be heard. */
    const val LEAD_MS = 60L

    /** The phone's notification sound decoded and waiting; called as the pin begins. The same sound as last time is kept. */
    fun prepare(context: Context) {
        val app = context.applicationContext
        val began = android.os.SystemClock.uptimeMillis()
        runCatching {
            val set = android.provider.Settings.System.getString(app.contentResolver, android.provider.Settings.System.NOTIFICATION_SOUND).orEmpty()
            if (pool != null && soundOf == set && ready) return
            pool?.release(); ready = false; sound = 0
            val p = android.media.SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            ).build()
            p.setOnLoadCompleteListener { _, _, status ->
                ready = status == 0
                Log.i(TAG, if (ready) "chime: ready ${android.os.SystemClock.uptimeMillis() - began} ms after it was asked to be" else "chime: the sound could not be made ready ($status)")
            }
            pool = p; soundOf = set
            // As the phone has it set: its own file where that is one, else through the settings' own address.
            val path = set.removePrefix("file://").takeIf { set.startsWith("file://") && java.io.File(it).canRead() }
            sound = if (path != null) p.load(path, 1) else app.contentResolver.openAssetFileDescriptor(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, "r")!!.use { p.load(it, 1) }
        }.onFailure { Log.w(TAG, "chime: not made ready: ${it.javaClass.simpleName}") }
    }

    /** The phone's notification sound, once, if a pin sounds now. Kept until it has played (the pin's screen is gone by then). */
    fun play(context: Context, demo: Boolean, asked: Boolean) {
        val app = context.applicationContext
        val mode = runCatching { app.getSystemService(android.media.AudioManager::class.java).ringerMode }.getOrDefault(RINGER_NORMAL)
        val filter = runCatching { app.getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter }.getOrDefault(FILTER_UNKNOWN)
        if (!plays(mode, filter, demo, asked)) {
            Log.i(TAG, "chime: not played (${if (demo && !asked) "a demo" else if (mode != RINGER_NORMAL) "the phone is on silent or vibrate" else "Do Not Disturb"})")
            return
        }
        // Ready: at once. Not ready (asked for before it was decoded, or it could not be): the slower way, late but heard.
        val p = pool
        if (p != null && ready && sound != 0 && runCatching { p.play(sound, 1f, 1f, 1, 0, 1f) }.getOrDefault(0) != 0) {
            Log.i(TAG, "chime: the phone's notification sound (ready beforehand)")
            return
        }
        runCatching {
            ringing?.stop()
            val tone = RingtoneManager.getRingtone(app, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI) ?: return
            tone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            ringing = tone
            tone.play()
            Log.i(TAG, "chime: the phone's notification sound (not ready beforehand: late)")
        }.onFailure { Log.w(TAG, "chime: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}") }
    }
}
