package dev.kb896.aiusage.place

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat

/** App-side wake, permission, and low-power rechecks. All scheduling runs on the app main thread. */
object PlaceWatch {
    private const val TAG = "AiUsagePlace"
    private var receiver: BroadcastReceiver? = null
    private var modes: AudioManager.OnModeChangedListener? = null
    @Volatile private var wasOn: Boolean? = null
    @Volatile private var wasAllowed: Boolean? = null
    private const val CHECK_MS = 10 * 60_000L
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Volatile private var active: Context? = null
    private var periodic: Runnable? = null
    private var passive: AutoCloseable? = null
    private var looking = false
    private var lookedAt: Long? = null
    private var lastSource: String? = null

    /**
     * The FRONT display's own state, as last seen ([front]). Not the screen broadcasts: they follow the phone being
     * interactive, which on this phone it also is with the back alone lit (service/FrontScreen), so a back woken by
     * itself sent SCREEN_ON with the front off, and a front coming on while the back was lit sent none.
     */
    private var frontPower: dev.kb896.aiusage.service.PanelPower? = null

    fun start(context: Context) {
        val app = context.applicationContext
        active = app
        seedFront(dev.kb896.aiusage.service.FrontScreen.on(app))
        // Every start: same ids replace, an initial ENTER where he was outside, an EXIT where he was inside (Arrivals.initial).
        runCatching { Fences.sync(app, "the service started", all = true) }
        wasOn = PlacePermission.locationOn(app)
        wasAllowed = Fences.possible(app)
        look(app, "service start")
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = look(app, "Location switched")
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
        if (allowed) Fences.sync(app, why, all = true)
        Places.marks(app)
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

    private fun frontOn(context: Context) = look(context, "front woke")

    /** A screen turning on is not a while-in-use grant. Only a resumed app screen uses foreground access. */
    fun look(context: Context, source: String, foreground: Boolean = false, insideOnly: Boolean = false) {
        val app = context.applicationContext
        changed(app, "permission or Location changed")
        val background = Fences.possible(app)
        val pins = dev.kb896.aiusage.data.ConfigStore(app).load().pins
        if (pins.none { it.placed }) return
        val store = PlaceStore(app)
        val state = store.load()
        val inside = state.visits.values.any { it.where == Arrivals.Where.INSIDE }
        if (!background && !(foreground && PlacePermission.fine(app) && PlacePermission.locationOn(app))) {
            Places.check(app, source, null)
            return
        }
        if (insideOnly && !inside) return
        val wall = System.currentTimeMillis()
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (looking || lookedAt?.let { lastSource == source && elapsed - it < Places.LOOK_APART_MS } == true) return
        looking = true
        lookedAt = elapsed
        lastSource = source
        if (background) {
            if (wall - state.refreshedAt !in 0..Places.DAY_MS) Fences.sync(app, "daily refresh", all = true)
            else if (source != "service start" && state.visits.values.any { it.owed }) Fences.sync(app, "retry owed fences")
        }
        val gps = foreground && !Fixes.online(app) && wall - state.gpsAt >= Places.GPS_APART_MS
        if (gps) store.update { it.copy(gpsAt = wall) }
        Fixes.one(app, gpsToo = gps) { fix ->
            looking = false
            val permitted = Fences.possible(app) || (foreground && PlacePermission.fine(app) && PlacePermission.locationOn(app))
            Places.check(app, source, fix.takeIf { permitted }, considerArrivals = !insideOnly)
        }
    }

    internal fun visitsChanged(context: Context, inside: Boolean) {
        if (active == null) return
        main.post {
            val app = active ?: return@post
            if (app !== context) return@post
            if (!inside) {
                periodic?.let { main.removeCallbacks(it) }
                periodic = null
            } else if (periodic == null) {
                periodic = Runnable {
                    periodic = null
                    look(app, "periodic", insideOnly = true)
                    // A lookup can be throttled or find no permission.
                    visitsChanged(app, PlaceStore(app).load().visits.values.any { it.where == Arrivals.Where.INSIDE })
                }.also { main.postDelayed(it, CHECK_MS) }
            }
            if (inside && Fences.possible(app)) {
                if (passive == null) passive = runCatching {
                    Fixes.passive(app, { Places.check(app, "passive fused", it) }) {
                        stopPassive()
                        Log.i(TAG, "passive fused: unavailable")
                    }
                }.getOrNull()
            } else stopPassive()
        }
    }

    private fun stopPassive() {
        runCatching { passive?.close() }
        passive = null
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        active = null
        periodic?.let { main.removeCallbacks(it) }; periodic = null
        stopPassive()
        receiver?.let { runCatching { app.unregisterReceiver(it) } }; receiver = null
        modes?.let { runCatching { app.getSystemService(AudioManager::class.java).removeOnModeChangedListener(it) } }; modes = null
    }
}
