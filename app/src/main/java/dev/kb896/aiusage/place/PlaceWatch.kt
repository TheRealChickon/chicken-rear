package dev.kb896.aiusage.place

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The service's part in the places (UsagePollService calls [start], [front], [stop]): the fences set again as the
 * service starts (after a reboot, an update, a process restart), Location being switched on or off heard (a manifest
 * receiver does not get it), the look on waking, and a call's end for a chime held for it. No timer, no wake lock,
 * no position asked for with the screen off.
 */
object PlaceWatch {
    private const val TAG = "AiUsagePlace"
    private var receiver: BroadcastReceiver? = null
    private var modes: AudioManager.OnModeChangedListener? = null
    @Volatile private var wasOn: Boolean? = null
    @Volatile private var wasAllowed: Boolean? = null

    /**
     * The FRONT display's own state, as last seen ([front]). Not the screen broadcasts: they follow the phone being
     * interactive, which on this phone it also is with the back alone lit (service/FrontScreen), so a back woken by
     * itself sent SCREEN_ON with the front off, and a front coming on while the back was lit sent none.
     */
    private var frontPower: dev.kb896.aiusage.service.PanelPower? = null

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) { if (frontPower == null) seedFront(dev.kb896.aiusage.service.FrontScreen.on(app)) }
        // Every start: same ids replace, an initial ENTER where he was outside, an EXIT where he was inside (Arrivals.initial).
        runCatching { Fences.sync(app, "the service started", all = true) }
        wasOn = PlacePermission.locationOn(app)
        wasAllowed = Fences.possible(app)
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = changed(app, "Location switched")
            }
            runCatching {
                ContextCompat.registerReceiver(app, r, IntentFilter().apply {
                    addAction(LocationManager.MODE_CHANGED_ACTION)
                    addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
                receiver = r
            }
        }
        if (modes == null) runCatching {
            val audio = app.getSystemService(AudioManager::class.java)
            var inCall = ArrivalChime.inCall(app)
            val l = AudioManager.OnModeChangedListener { _ ->
                val now = ArrivalChime.inCall(app)
                if (inCall && !now) ArrivalChime.callEnded(app)
                inCall = now
            }
            audio.addOnModeChangedListener(app.mainExecutor, l)
            modes = l
        }
    }

    /** Location or the permission changed: back on, the owed fences are set; off, the marks go hollow. */
    fun changed(context: Context, why: String) {
        val app = context.applicationContext
        val on = PlacePermission.locationOn(app)
        val allowed = Fences.possible(app)
        if (on == wasOn && allowed == wasAllowed) return
        wasOn = on; wasAllowed = allowed
        Log.i(TAG, "place: $why — ${if (allowed) "fences can be set" else "fences cannot be set now"}")
        if (allowed) Fences.sync(app, why, all = true) else Places.marks(app)
    }

    /**
     * Something about the screens changed (the screen broadcasts, the front display's listener): if the FRONT has just
     * come on (off before, on now, by its own display state), [frontOn]. Anything else, nothing.
     */
    fun front(context: Context) {
        val app = context.applicationContext
        if (frontSeen(dev.kb896.aiusage.service.FrontScreen.on(app))) frontOn(app)
    }

    /** The front's state [on] as seen now: true only where it was off and is on (the first sight is no edge). */
    @Synchronized internal fun frontSeen(on: Boolean): Boolean {
        val p = frontPower ?: return false.also { seedFront(on) }
        return p.see(on) == dev.kb896.aiusage.service.PanelPower.Change.WOKE
    }

    /** The front taken as [on] from now (the service's start; a test). */
    @Synchronized internal fun seedFront(on: Boolean) { frontPower = dev.kb896.aiusage.service.PanelPower(on) }

    /** The front came on: the permission looked at again, then the look on waking (its own limits). */
    private fun frontOn(context: Context) {
        val app = context.applicationContext
        changed(app, "the permission or Location changed")
        Places.look(app, "the front came on")
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        receiver?.let { runCatching { app.unregisterReceiver(it) } }; receiver = null
        modes?.let { runCatching { app.getSystemService(AudioManager::class.java).removeOnModeChangedListener(it) } }; modes = null
    }
}
