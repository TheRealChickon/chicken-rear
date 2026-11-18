package dev.kb896.aiusage.place

import android.content.Context
import android.util.Log
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.StatePayload
import dev.kb896.aiusage.service.Graph
import dev.kb896.aiusage.service.WeatherPoll

/** Reconcile visits and publish both rear pages from the same snapshot. */
object Places {
    private const val TAG = "AiUsagePlace"

    /** The config changed by [change] (null: nothing to change), saved and told to the back. */
    @Synchronized
    fun write(context: Context, why: String, change: (PanelConfig) -> PanelConfig?): PanelConfig? {
        val app = context.applicationContext
        val store = ConfigStore(app)
        val config = store.load()
        val next = change(config) ?: return null
        if (next == config) return null
        val removingPlace = config.pins.any { old ->
            old.placed && (old.here || old.arrivedAt != 0L) &&
                next.pins.none { it.at == old.at && (!old.here || it.here) && (old.arrivedAt == 0L || it.arrivedAt != 0L) }
        }
        store.save(next)
        // A pin to a location playing: saved, and told by its animation (TellHold).
        if (!removingPlace && !hold.mayTell(android.os.SystemClock.uptimeMillis())) { Log.i(TAG, "place: saved, the back told by the pin's animation ($why)"); return next }
        tell(app, next, why)
        return next
    }

    private fun tell(app: Context, config: PanelConfig, why: String) = teller(app, config, why)

    /** How the back is told what is stored: the payload over the shell's bridge. A test puts its own here. */
    @Volatile internal var teller: (Context, PanelConfig, String) -> Unit = { app, config, why ->
        runCatching {
            val graph = Graph.of(app)
            graph.bridge.update(StatePayload.encode(graph.repository.state.value, config, WeatherPoll.latest))
        }.onFailure { Log.i(TAG, "place: the back not told ($why): ${it.javaClass.simpleName}") }
    }

    /**
     * The service made sure of (only it has the shell's bridge; a fence's event is one of the few moments it may be
     * started from the background). A test puts its own here.
     */
    @Volatile internal var waker: (Context) -> Unit = { c ->
        runCatching { dev.kb896.aiusage.service.UsagePollService.start(c) }.onFailure { Log.i(TAG, "place: the service not started: ${it.javaClass.simpleName}") }
    }

    private fun wake(context: Context) = waker(context.applicationContext)

    private val hold = TellHold()
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * A pin to a location is about to be kept and its animation to play (ui/place/PlacePinActivity): nothing written
     * here is told to the back until that animation tells it ([toldByPin]), [HOLD_MS] at most. Departures are sent immediately.
     */
    fun holdForPin(context: Context) {
        val app = context.applicationContext
        val token = hold.hold(android.os.SystemClock.uptimeMillis(), HOLD_MS)
        main.postDelayed({
            synchronized(this) {
                if (hold.ranOut(token, android.os.SystemClock.uptimeMillis())) {
                    Log.i(TAG, "place: the pin's animation never told the back — told now")
                    tell(app, ConfigStore(app).load(), "the hold ran out")
                }
            }
        }, HOLD_MS)
    }

    /** The pin's animation (ui/PinActivity) tells the back now: what is stored, with all that was held meanwhile. */
    @Synchronized
    fun toldByPin(context: Context) {
        val app = context.applicationContext
        hold.release()
        tell(app, ConfigStore(app).load(), "the pin's animation")
    }

    /** Update both rear pages and the settings’ fence indicators. */
    @Synchronized
    fun marks(context: Context, why: String = "marks"): PanelConfig? =
        saveVisits(context.applicationContext, why, emptyList(), System.currentTimeMillis())

    fun withPresence(context: Context, pins: List<dev.kb896.aiusage.data.Pin>): List<dev.kb896.aiusage.data.Pin> =
        Here.visits(pins, PlaceStore(context).load().visits, System.currentTimeMillis())

    /**
     * A note for [place] has just been kept: its state from one fix (the animation does not wait; [settled]), its fence
     * with the initial trigger its stored state calls for at the tap on Pin (Arrivals.initial: never an ENTER; an EXIT
     * where the place is thought here, so a place left while its EXIT was missed is made up for).
     */
    fun pinned(context: Context, place: Arrivals.Place) {
        val app = context.applicationContext
        val store = PlaceStore(app)
        val stored = store.update { s -> if (s.visits.containsKey(place.key)) s else s.copy(visits = s.visits + (place.key to Arrivals.Visit(owed = true))) }
        val before = stored.visits[place.key] ?: Arrivals.Visit(owed = true)
        Fences.set(app, listOf(place to Arrivals.initial(before, atPin = true))) { ok ->
            store.update { s ->
                val visit = s.visits[place.key] ?: return@update s
                s.copy(visits = s.visits + (place.key to visit.copy(owed = place.key !in ok)))
            }
            marks(app)
        }
        Fixes.one(app) { fix -> settled(app, place, fix, before) }
    }

    /**
     * The fix at pin time ([fix]; null: none in time) for a note just kept at [place], whose state was [before] as it
     * was kept: the place's state from it where nothing has said more about the place meanwhile (Arrivals.settled: a
     * new place inside, outside or not known; one thought here found clearly left). BEING THERE WHEN THE NOTE IS MADE
     * COUNTS AS BEING THERE: inside, the note is on the back from now ([marks]); outside or not known, it is not, once
     * its pin has landed (rear/NoteLanding).
     */
    @Synchronized
    internal fun settled(context: Context, place: Arrivals.Place, fix: Arrivals.Fix?, before: Arrivals.Visit = Arrivals.Visit()) {
        val app = context.applicationContext
        val places = PinList.places(ConfigStore(app).load().pins)
        if (place.key !in places) return
        val now = System.currentTimeMillis()
        PlaceStore(app).update { state ->
            val visits = state.visits.toMutableMap()
            val current = visits[place.key] ?: Arrivals.Visit()
            val result = when {
                current.where == Arrivals.Where.INSIDE && current.copy(owed = before.owed) == before ->
                    Arrivals.look(place, current, fix, state.seen, now)
                else -> {
                    val next = Arrivals.settled(place, before, current, fix, state.seen, now)
                    Arrivals.Result(next ?: current, false, if (next == null) "kept newer visit state" else "pin-time position")
                }
            }
            visits[place.key] = result.visit
            decision("pin time", place, current, result, fix)
            for ((key, other) in places) {
                if (key != place.key && visits[key]?.where == Arrivals.Where.INSIDE) {
                    val previous = visits.getValue(key)
                    val checked = Arrivals.look(other, previous, fix, state.seen, now)
                    visits[key] = checked.visit
                    decision("pin time", other, previous, checked, fix)
                }
            }
            state.copy(visits = visits, seen = seen(state, fix, Arrivals.OWN_FIX_MS))
        }
        marks(app, "the state at pin time")
    }

    /** A fence's fix can independently end a visit at any other place. */
    @Synchronized
    fun onFence(context: Context, event: Arrivals.Event, keys: List<String>, fix: Arrivals.Fix?) {
        val app = context.applicationContext
        val places = PinList.places(ConfigStore(app).load().pins)
        val now = System.currentTimeMillis()
        val arrived = ArrayList<String>()
        val source = "fence ${event.name.lowercase()}"
        PlaceStore(app).update { state ->
            val visits = state.visits.toMutableMap()
            for ((key, place) in places) {
                val previous = visits[key] ?: Arrivals.Visit()
                val result = when {
                    key !in keys && (fix == null || previous.where != Arrivals.Where.INSIDE) -> continue
                    key !in keys -> Arrivals.look(place, previous, fix, state.seen, now, Arrivals.EVENT_FIX_MS)
                    event == Arrivals.Event.EXIT -> Arrivals.fenceExit(place, previous, fix, state.seen, now)
                    else -> Arrivals.fence(event, place, previous, fix, state.seen, now)
                }
                visits[key] = result.visit
                decision(source, place, previous, result, fix)
                if (result.arrived) arrived += key
            }
            state.copy(visits = visits, seen = seen(state, fix, Arrivals.EVENT_FIX_MS))
        }
        Fences.remove(app, keys - places.keys)
        if (publish(app, source, arrived, fix?.ageMs) && arrived.isEmpty()) wake(app)
    }

    /** Recheck every inside place, plus possible arrivals when a screen or the app wakes. */
    @Synchronized
    fun check(context: Context, source: String, fix: Arrivals.Fix?, considerArrivals: Boolean = false) {
        val app = context.applicationContext
        val places = PinList.places(ConfigStore(app).load().pins)
        if (places.isEmpty()) return
        val now = System.currentTimeMillis()
        val arrived = ArrayList<String>()
        PlaceStore(app).update { state ->
            val visits = state.visits.toMutableMap()
            for ((key, place) in places) {
                val previous = visits[key] ?: Arrivals.Visit()
                if ((!considerArrivals || fix == null) && previous.where != Arrivals.Where.INSIDE) continue
                val result = Arrivals.look(place, previous, fix, state.seen, now)
                visits[key] = result.visit
                decision(source, place, previous, result, fix)
                if (result.arrived) arrived += key
            }
            state.copy(visits = visits, seen = seen(state, fix, Arrivals.OWN_FIX_MS))
        }
        publish(app, source, arrived, fix?.ageMs)
    }

    private fun seen(state: PlaceStore.State, fix: Arrivals.Fix?, maxAge: Long): Arrivals.Seen? =
        if (fix != null && !fix.mock && fix.ageMs <= maxAge && fix.at > (state.seen?.at ?: 0L))
            Arrivals.Seen(fix.lat, fix.lng, fix.at)
        else state.seen

    private fun decision(source: String, place: Arrivals.Place, before: Arrivals.Visit, result: Arrivals.Result, fix: Arrivals.Fix?) {
        val transition = when {
            before.where != Arrivals.Where.INSIDE && result.visit.where == Arrivals.Where.INSIDE -> "enter"
            before.where == Arrivals.Where.INSIDE && result.visit.where != Arrivals.Where.INSIDE -> "leave"
            else -> "stay ${result.visit.where.name.lowercase()}"
        }
        val distance = fix?.let { Arrivals.distance(place, it).takeIf(Double::isFinite) }
        val metres = distance?.let { Math.round(it / 10) * 10 }
        val accuracy = fix?.accuracy?.takeIf(Float::isFinite)?.let { Math.round(it) }
        Log.i(TAG, "place: $transition via $source — ${result.why}; distance=${metres?.let { "${it}m" } ?: "unknown"} accuracy=${accuracy?.let { "${it}m" } ?: "unknown"}")
    }

    private fun publish(app: Context, source: String, arrived: List<String>, fixAgeMs: Long?): Boolean {
        if (arrived.isEmpty()) return marks(app, source) != null
        val now = System.currentTimeMillis()
        val next = saveVisits(app, source, arrived, now) ?: return false
        if (next.pins.any { it.arrivedAt == now }) {
            wake(app)
            ArrivalChime.arrived(app, fixAgeMs)
        }
        return true
    }

    private fun saveVisits(app: Context, source: String, arrived: List<String>, now: Long): PanelConfig? {
        val visits = PlaceStore(app).load().visits
        val possible = Fences.possible(app)
        PlaceWatch.visitsChanged(app, visits.values.any { it.where == Arrivals.Where.INSIDE })
        return write(app, source) { config ->
            val pins = arrived.fold(config.pins) { list, key -> PinList.arrived(list, key, now) ?: list }
            val watched = PinList.watched(pins) { possible && visits[it]?.owed == false }
            config.copy(pins = Here.visits(watched, visits, now))
        }
    }

    /** "Not now" on the back for the note pinned at [at]: all at its place wait again. */
    fun later(context: Context, at: Long) {
        write(context, "not now") { c -> PinList.later(c.pins, at)?.let { c.copy(pins = it) } }
        Log.i(TAG, "place: not now — it returns at the next visit")
    }

    /** A pin's own animation tells the back by about 2 s (PinBeam.ARRIVE_AT, or at once through Xiaomi's); past this, the hold gives up. */
    const val HOLD_MS = 6_000L
    const val LOOK_APART_MS = 60_000L
    const val GPS_APART_MS = 15 * 60_000L
    const val DAY_MS = 24 * 60 * 60_000L
}
