package dev.kb896.aiusage.place

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * WHERE A POSITION OF OURS COMES FROM (SPEC R13): Play services' fused provider, the source the fences use, never
 * LocationManager (on this ROM its network provider is Xiaomi's/AMap) and never a last-known position. First a fix
 * Play services already has (passive, at most 60 s old: costs nothing, shows no location mark); only without one,
 * one balanced request of 8 s at most. A fix counts only by its real age (elapsed clock) and when it is not a mock.
 * Needs ACCESS_FINE_LOCATION; quiet (null) without it. Nothing is logged with a position.
 */
object Fixes {
    private const val TAG = "AiUsagePlace"
    const val PASSIVE_MS = 2_000L
    const val BALANCED_MS = 8_000L
    const val GPS_MS = 15_000L

    /** [l] as Arrivals reads a fix; age by the elapsed clock. */
    fun of(l: Location, nowWall: Long = System.currentTimeMillis()): Arrivals.Fix {
        val age = ((SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)
        return Arrivals.Fix(
            l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 9_999f, if (l.hasSpeed()) l.speed else null, age, nowWall - age,
            // A debug build takes a mock as real only when the shell says so (DebugLock.MOCK_FIXES_PROP): else the
            // phone's mock location app, whichever it is, could set off an arrival.
            mock = dev.kb896.aiusage.DebugLock.mockFix(l.isMock, dev.kb896.aiusage.BuildConfig.DEBUG) { shellProp(dev.kb896.aiusage.DebugLock.MOCK_FIXES_PROP) },
        )
    }

    /** A system property's value (a debug build's switch from the shell); null where it cannot be read. */
    private fun shellProp(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull()

    /**
     * One fix by the rules, given to [done] on the main thread (null: none in time, no permission, Play services
     * missing). [gpsToo]: with nothing from the network (no data connection), one GPS try of 15 s.
     */
    @SuppressLint("MissingPermission")
    fun one(context: Context, gpsToo: Boolean = false, done: (Arrivals.Fix?) -> Unit) {
        val app = context.applicationContext
        if (!PlacePermission.fine(app)) { done(null); return }
        val client = runCatching { LocationServices.getFusedLocationProviderClient(app) }.getOrNull() ?: run { done(null); return }
        fun ask(priority: Int, ms: Long, maxAge: Long, next: (() -> Unit)?) {
            val req = CurrentLocationRequest.Builder().setPriority(priority).setDurationMillis(ms).setMaxUpdateAgeMillis(maxAge).build()
            runCatching {
                client.getCurrentLocation(req, null)
                    .addOnSuccessListener { l -> val f = l?.let { of(it) }; if (f != null && f.ageMs <= Arrivals.OWN_FIX_MS && !f.mock) done(f) else if (next != null) next() else done(null) }
                    .addOnFailureListener { e -> Log.i(TAG, "fix: ${e.javaClass.simpleName}"); if (next != null) next() else done(null) }
            }.onFailure { if (next != null) next() else done(null) }
        }
        val gps: (() -> Unit)? = if (gpsToo) ({ ask(Priority.PRIORITY_HIGH_ACCURACY, GPS_MS, 0L, null) }) else null
        ask(Priority.PRIORITY_PASSIVE, PASSIVE_MS, Arrivals.OWN_FIX_MS) {
            ask(Priority.PRIORITY_BALANCED_POWER_ACCURACY, BALANCED_MS, 0L, gps)
        }
    }

    /** A passive fused feed while an inside place needs checking. Closing it also ignores queued callbacks. */
    @SuppressLint("MissingPermission")
    fun passive(context: Context, done: (Arrivals.Fix) -> Unit, failed: () -> Unit): AutoCloseable {
        val client = LocationServices.getFusedLocationProviderClient(context)
        var open = true
        val listener = com.google.android.gms.location.LocationListener { location ->
            if (open && Fences.possible(context)) done(of(location))
        }
        val request = com.google.android.gms.location.LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 30_000L)
            .setMinUpdateIntervalMillis(15_000L)
            .setMaxUpdateAgeMillis(Arrivals.OWN_FIX_MS)
            .build()
        client.requestLocationUpdates(request, context.mainExecutor, listener)
            .addOnFailureListener { if (open) failed() }
        return AutoCloseable {
            open = false
            client.removeLocationUpdates(listener)
        }
    }

    /** Whether the phone has a working data connection now (for the GPS try on waking). */
    fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(true)
}
