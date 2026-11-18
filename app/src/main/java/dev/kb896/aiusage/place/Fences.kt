package dev.kb896.aiusage.place

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * THE WATCH: one Play-services geofence per PLACE (not per note), ENTER, DWELL (2 minutes) and EXIT, never expiring,
 * responsiveness 0, its id the place's key (SPEC section 4). Play services (a privileged system app) does the
 * watching, not our process: an arrival comes as a broadcast to [FenceReceiver], from which the service may be
 * started. No key or account of any kind is needed for this (it is not Google's Maps).
 *
 * There is no way to ask Play services which fences it holds, so each place is "owed" until a set succeeded, and
 * set again ([sync], same id: it replaces) at every point SPEC R16 names: the tap on Pin, the service's start (after
 * a reboot or an update), Location back on, the permission back, once a day. Each with the initial trigger its stored
 * state calls for (Arrivals.initial: an ENTER where he was outside, never at the tap on Pin; an EXIT where he was
 * inside, so an EXIT missed meanwhile is made up for). A place with no note left loses its fence.
 */
object Fences {
    private const val TAG = "AiUsagePlace"
    const val ACTION = "dev.kb896.aiusage.PLACE_FENCE"
    const val DWELL_MS = 120_000

    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(ACTION).setClass(context, FenceReceiver::class.java),
        // Mutable: Play services adds the event (transition, fences, the fix) to it.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun fence(p: Arrivals.Place): Geofence = Geofence.Builder()
        .setRequestId(p.key)
        .setCircularRegion(p.lat, p.lng, p.radius.toFloat())
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT)
        .setLoiteringDelay(DWELL_MS)
        .setNotificationResponsiveness(0)
        .build()

    /** Whether a fence can be set at all now: precise, all the time, Location on. */
    fun possible(context: Context) = PlacePermission.need(context) == PlacePermission.Need.NOTHING

    /** Play services' flag for [initial]. */
    fun trigger(initial: Arrivals.Initial): Int = when (initial) {
        Arrivals.Initial.NONE -> 0
        Arrivals.Initial.ENTER -> GeofencingRequest.INITIAL_TRIGGER_ENTER
        Arrivals.Initial.EXIT -> GeofencingRequest.INITIAL_TRIGGER_EXIT
    }

    /** [places] set, each with the initial trigger given with it; [done] with the keys that were set (on the main thread). */
    @SuppressLint("MissingPermission")
    fun set(context: Context, places: List<Pair<Arrivals.Place, Arrivals.Initial>>, done: (Set<String>) -> Unit) {
        val app = context.applicationContext
        if (places.isEmpty()) { done(emptySet()); return }
        if (!possible(app)) { done(emptySet()); return }
        val client = runCatching { LocationServices.getGeofencingClient(app) }.getOrNull() ?: run { done(emptySet()); return }
        val groups = places.groupBy { it.second }
        val ok = HashSet<String>()
        var left = groups.size
        for ((initial, group) in groups) {
            val req = GeofencingRequest.Builder()
                .setInitialTrigger(trigger(initial))
                .addGeofences(group.map { fence(it.first) })
                .build()
            val finish = { set: Boolean ->
                if (set) ok += group.map { it.first.key }
                left--
                if (left == 0) done(ok)
            }
            runCatching {
                client.addGeofences(req, intent(app))
                    .addOnSuccessListener { Log.i(TAG, "fences: ${group.size} set${when (initial) { Arrivals.Initial.ENTER -> ", with an initial entry"; Arrivals.Initial.EXIT -> ", with an initial exit"; Arrivals.Initial.NONE -> "" }}"); finish(true) }
                    .addOnFailureListener { e -> Log.w(TAG, "fences: ${group.size} not set (${(e as? com.google.android.gms.common.api.ApiException)?.statusCode ?: e.javaClass.simpleName})"); finish(false) }
            }.onFailure { Log.w(TAG, "fences: not set (${it.javaClass.simpleName})"); finish(false) }
        }
    }

    fun remove(context: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        runCatching { LocationServices.getGeofencingClient(context.applicationContext).removeGeofences(keys.toList()) }
        Log.i(TAG, "fences: ${keys.size} removed")
    }

    /**
     * The fences made what the notes say: owed places (or [all] of them) set again, by the rule for their initial
     * trigger; places with no note left removed and forgotten; each note's mark whole or hollow by the result.
     */
    fun sync(context: Context, why: String, all: Boolean = false) {
        val app = context.applicationContext
        val store = PlaceStore(app)
        val pins = dev.kb896.aiusage.data.ConfigStore(app).load().pins
        val places = PinList.places(pins)
        val before = store.load()
        val stray = before.visits.keys - places.keys
        if (stray.isNotEmpty()) remove(app, stray)
        val now = System.currentTimeMillis()
        val s = store.update { st ->
            val v = st.only(places.keys).visits.toMutableMap()
            for (k in places.keys) if (k !in v) v[k] = Arrivals.Visit(owed = true)
            st.copy(visits = v, refreshedAt = if (all) now else st.refreshedAt)
        }
        val todo = places.values.filter { all || s.visits[it.key]?.owed != false }
        if (todo.isEmpty()) { Places.marks(app); return }
        Log.i(TAG, "fences: ${todo.size} to set ($why)")
        set(app, todo.map { it to Arrivals.initial(s.visits[it.key] ?: Arrivals.Visit()) }) { ok ->
            store.update { st -> st.copy(visits = st.visits.mapValues { (k, v) -> if (k in ok) v.copy(owed = false) else if (todo.any { it.key == k }) v.copy(owed = true) else v }, unavailableSince = if (ok.isNotEmpty()) 0L else st.unavailableSince) }
            Places.marks(app)
        }
    }

    /** Play services dropped every fence (GEOFENCE_NOT_AVAILABLE: Location or its accuracy switched off): all are owed until Location is back. */
    fun dropped(context: Context) {
        val now = System.currentTimeMillis()
        PlaceStore(context).update { st -> st.copy(visits = st.visits.mapValues { it.value.copy(owed = true) }, unavailableSince = if (st.unavailableSince == 0L) now else st.unavailableSince) }
        Places.marks(context)
    }
}
