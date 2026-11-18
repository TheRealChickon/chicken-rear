package dev.kb896.aiusage.place

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/** Play services watches entry/dwell at the chosen radius and exit at a wider radius. */
object Fences {
    private const val TAG = "AiUsagePlace"
    const val ACTION = "dev.kb896.aiusage.PLACE_FENCE"
    const val DWELL_MS = 120_000

    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(ACTION).setClass(context, FenceReceiver::class.java),
        // Mutable: Play services adds the event (transition, fences, the fix) to it.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private const val EXIT_PREFIX = "exit:"

    internal fun eventKey(event: Arrivals.Event, id: String): String? =
        if (event == Arrivals.Event.EXIT) id.takeIf { it.startsWith(EXIT_PREFIX) }?.removePrefix(EXIT_PREFIX)
        else id.takeUnless { it.startsWith(EXIT_PREFIX) }

    private fun fence(p: Arrivals.Place, exit: Boolean): Geofence = Geofence.Builder()
        .setRequestId(if (exit) EXIT_PREFIX + p.key else p.key)
        .setCircularRegion(p.lat, p.lng, (p.radius + if (exit) Arrivals.EXIT_MARGIN_M else 0).toFloat())
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(if (exit) Geofence.GEOFENCE_TRANSITION_EXIT else Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL)
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
        val groups = listOf(false, true).flatMap { exit ->
            places.groupBy { (_, initial) ->
                if (exit && initial == Arrivals.Initial.EXIT || !exit && initial == Arrivals.Initial.ENTER) initial else Arrivals.Initial.NONE
            }.map { (initial, group) -> Triple(exit, initial, group.map { it.first }) }
        }
        val successes = HashMap<String, Int>()
        var left = groups.size
        for ((exit, initial, group) in groups) {
            val req = GeofencingRequest.Builder()
                .setInitialTrigger(trigger(initial))
                .addGeofences(group.map { fence(it, exit) })
                .build()
            val finish = { set: Boolean ->
                if (set) group.forEach { successes[it.key] = (successes[it.key] ?: 0) + 1 }
                left--
                if (left == 0) done(successes.filterValues { it == 2 }.keys)
            }
            runCatching {
                client.addGeofences(req, intent(app))
                    .addOnSuccessListener { Log.i(TAG, "fences: ${group.size} ${if (exit) "exit" else "entry"} fences set"); finish(true) }
                    .addOnFailureListener { e -> Log.w(TAG, "fences: ${group.size} not set (${(e as? com.google.android.gms.common.api.ApiException)?.statusCode ?: e.javaClass.simpleName})"); finish(false) }
            }.onFailure { Log.w(TAG, "fences: not set (${it.javaClass.simpleName})"); finish(false) }
        }
    }

    fun remove(context: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        runCatching { LocationServices.getGeofencingClient(context.applicationContext).removeGeofences(keys.flatMap { listOf(it, EXIT_PREFIX + it) }) }
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
