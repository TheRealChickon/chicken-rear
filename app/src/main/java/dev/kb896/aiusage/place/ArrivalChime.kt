package dev.kb896.aiusage.place

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import dev.kb896.aiusage.service.PinChime

/**
 * THE SOUND OF AN ARRIVAL (SPEC section 5, R18): the phone's notification sound once, by PinChime (which keeps the
 * silent and vibrate modes and Do Not Disturb), made ready and played at once, waiting for nothing else (not the
 * bridge, not the back). While music plays, the short "may duck" focus so it is heard over it. No vibration, no
 * notification. In a call: none now, one when the call ends if the arrival is under 10 minutes old. An arrival whose
 * fix was older than 10 minutes when it came shows its page silently; two within a minute are one chime.
 */
object ArrivalChime {
    private const val TAG = "AiUsagePlace"
    const val READY_MS = 500L

    fun inCall(context: Context): Boolean = runCatching {
        val m = context.getSystemService(AudioManager::class.java).mode
        m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE
    }.getOrDefault(false)

    fun arrived(context: Context, fixAgeMs: Long?) {
        val app = context.applicationContext
        val store = PlaceStore(app)
        val now = System.currentTimeMillis()
        when (Arrivals.sound(fixAgeMs, inCall(app), store.load().chimedAt, now)) {
            Arrivals.Sound.NOW -> { store.update { it.copy(chimedAt = now) }; play(app) }
            Arrivals.Sound.AFTER_CALL -> { store.update { it.copy(chimeOwedAt = now) }; Log.i(TAG, "chime: held for the call") }
            Arrivals.Sound.SILENT_STALE -> Log.i(TAG, "chime: none — the arrival is old news (its fix over 10 minutes old)")
            Arrivals.Sound.ONE_WITH_LAST -> Log.i(TAG, "chime: none — one played less than a minute ago")
        }
    }

    /** The call is over: the chime held for it, if the arrival is still fresh. */
    fun callEnded(context: Context) {
        val app = context.applicationContext
        val store = PlaceStore(app)
        val s = store.load()
        if (s.chimeOwedAt == 0L) return
        val now = System.currentTimeMillis()
        val standing = dev.kb896.aiusage.data.ConfigStore(app).load().pins.any {
            Arrivals.afterCall(Here.current(it, now).arrivedAt, now)
        }
        val due = standing && Arrivals.afterCall(s.chimeOwedAt, now)
        store.update { it.copy(chimeOwedAt = 0L, chimedAt = if (due) now else it.chimedAt) }
        if (due) play(app) else Log.i(TAG, "chime: the held arrival ended or expired")
    }

    /** A debug build's made-up arrival: the same sound by the same rules (silent, vibrate, Do Not Disturb kept), nothing stored. */
    fun demo(context: Context) = play(context.applicationContext, demo = true)

    /** How long after the sound was asked for it is taken as heard: focus and the broadcast are held this long. */
    const val HEARD_MS = 1_500L

    /**
     * The chimes being played now. A broadcast that started one (FenceReceiver) keeps its goAsync until [heard] says
     * it was heard: HyperOS freezes this app in the background, foreground service or not, and a frozen process
     * plays nothing (SPEC section 5, R18).
     */
    private val playing = Playing()

    /** Waits at most [ms] for every chime being played to have been heard; true if they were (or none was playing). */
    fun heard(ms: Long): Boolean = playing.await(ms)

    /** Chimes in flight, and a wait for them all to be over. Pure (threads only). */
    internal class Playing {
        private val lock = Object()
        private var count = 0
        fun begin() = synchronized(lock) { count++ }
        fun end() = synchronized(lock) { count = (count - 1).coerceAtLeast(0); lock.notifyAll() }
        fun await(ms: Long): Boolean = synchronized(lock) {
            val until = System.nanoTime() + ms.coerceAtLeast(0L) * 1_000_000L
            while (count > 0) {
                val left = (until - System.nanoTime()) / 1_000_000L
                if (left <= 0L) return@synchronized false
                lock.wait(left)
            }
            true
        }
    }

    /** Made ready, then played (at most [READY_MS] of waiting; past that, PinChime's slower way). Off the main thread; [heard] waits for it. */
    private fun play(app: Context, demo: Boolean = false) {
        playing.begin()
        Thread({ try { sound(app, demo) } finally { playing.end() } }, "place-chime").apply { isDaemon = true; start() }
    }

    private fun sound(app: Context, demo: Boolean) {
        PinChime.prepare(app)
        val until = android.os.SystemClock.uptimeMillis() + READY_MS
        while (!PinChime.isReady() && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(20)
        val audio = app.getSystemService(AudioManager::class.java)
        val focus = if (runCatching { audio.isMusicActive }.getOrDefault(false)) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build()).build() else null
        focus?.let { runCatching { audio.requestAudioFocus(it) } }
        PinChime.play(app, demo = demo, asked = demo)
        Log.i(TAG, "chime: an arrival")
        Thread.sleep(HEARD_MS)
        focus?.let { f -> runCatching { audio.abandonAudioFocusRequest(f) } }
    }
}
