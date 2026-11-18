package dev.kb896.aiusage.place

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

/**
 * Play services' word that a fence was crossed (Fences), to this app alone (not exported). The work is done on a
 * thread of its own under goAsync, and goAsync is let go only once an arrival's chime has been heard
 * (ArrivalChime.heard), [HOLD_MS] after the broadcast came at most (SPEC section 5, R18): a finished broadcast leaves
 * nothing keeping the app thawed, and HyperOS freezes it.
 */
class FenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Fences.ACTION) return
        val app = context.applicationContext
        val event = runCatching { GeofencingEvent.fromIntent(intent) }.getOrNull() ?: return
        if (event.hasError()) {
            Log.i(TAG, "fences: Play services says ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) Fences.dropped(app)
            return
        }
        if (!Fences.possible(app)) return
        val kind = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> Arrivals.Event.ENTER
            Geofence.GEOFENCE_TRANSITION_DWELL -> Arrivals.Event.DWELL
            Geofence.GEOFENCE_TRANSITION_EXIT -> Arrivals.Event.EXIT
            else -> return
        }
        val keys = event.triggeringGeofences.orEmpty().mapNotNull { Fences.eventKey(kind, it.requestId) }
        if (keys.isEmpty()) return
        val fix = event.triggeringLocation?.let { Fixes.of(it) }
        val pending = goAsync()
        val began = android.os.SystemClock.uptimeMillis()
        Thread({
            try {
                Places.onFence(app, kind, keys, fix)
                if (!ArrivalChime.heard(left(began, android.os.SystemClock.uptimeMillis()))) Log.i(TAG, "fences: the chime still playing as the broadcast lets go")
            }
            catch (e: Throwable) { Log.w(TAG, "fences: the event not taken: ${e.javaClass.simpleName}") }
            finally { pending.finish() }
        }, "place-fence").apply { isDaemon = true; start() }
    }

    companion object {
        private const val TAG = "AiUsagePlace"
        /** The broadcast is held this long after it came at most (Android's own limit for goAsync is 10 s). */
        const val HOLD_MS = 3_000L

        /** How much longer the broadcast that came at [began] may wait for a chime at [now]. */
        fun left(began: Long, now: Long): Long = (HOLD_MS - (now - began)).coerceIn(0L, HOLD_MS)
    }
}
