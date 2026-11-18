package dev.kb896.aiusage.place

import android.content.Context
import android.util.Log
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.StatePayload
import dev.kb896.aiusage.service.Graph
import dev.kb896.aiusage.service.WeatherPoll

/**
 * What the app does with a place's news: a note pinned there, a fence's event, a look on waking, the dismissals from
 * the back. The rules are Arrivals', PinList's and Here's; here they are applied to what is stored, and the back is
 * told by the payload (an arrival is a STATE in it: the note's arrivedAt, so it stands also after a restart of the
 * shell). The log says what happened and why, never where.
 *
 * A NOTE FOR A PLACE IS ON THE BACK'S NOTES PAGE ONLY WHILE THE PHONE IS THERE (Here): every change of a place's state
 * (a fence's ENTER or EXIT, the look on waking, the fix at pin time, Fences.sync's new places) ends in [marks], which
 * writes each note's Pin.here from it and, when one changed, sends the payload again, so the back follows within
 * seconds. A fence's event that changed what the back shows also makes sure the service runs ([wake]): the broadcast
 * may have started this process with no bridge to the shell yet, and the service's start sends the payload.
 */
object Places {
    private const val TAG = "AiUsagePlace"
    private val lock = Any()

    /** The config changed by [change] (null: nothing to change), saved and told to the back. */
    fun write(context: Context, why: String, change: (PanelConfig) -> PanelConfig?): PanelConfig? {
        val app = context.applicationContext
        val store = ConfigStore(app)
        val next = synchronized(lock) {
            val config = store.load()
            val n = change(config) ?: return null
            if (n == config) return null
            store.save(n)
            n
        }
        // A pin to a location playing: saved, and told by its animation (TellHold).
        if (!hold.mayTell(android.os.SystemClock.uptimeMillis())) { Log.i(TAG, "place: saved, the back told by the pin's animation ($why)"); return next }
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
     * here is told to the back until that animation tells it ([toldByPin]), [HOLD_MS] at most.
     */
    fun holdForPin(context: Context) {
        val app = context.applicationContext
        val token = hold.hold(android.os.SystemClock.uptimeMillis(), HOLD_MS)
        main.postDelayed({
            if (hold.ranOut(token, android.os.SystemClock.uptimeMillis())) {
                Log.i(TAG, "place: the pin's animation never told the back — told now")
                tell(app, ConfigStore(app).load(), "the hold ran out")
            }
        }, HOLD_MS)
    }

    /** The pin's animation (ui/PinActivity) tells the back now: what is stored, with all that was held meanwhile. */
    fun toldByPin(context: Context) {
        val app = context.applicationContext
        hold.release()
        tell(app, ConfigStore(app).load(), "the pin's animation")
    }

    /**
     * Each place note's flags made what is stored now: its mark whole or hollow (a fence set and the position allowed:
     * Pin.watched) and whether the phone is at its place (Here: Pin.here). Saved and told to the back when one
     * changed ([write]); the new config then, null when nothing changed.
     */
    fun marks(context: Context, why: String = "marks"): PanelConfig? {
        val app = context.applicationContext
        val possible = Fences.possible(app)
        val visits = PlaceStore(app).load().visits
        val here = Here.keys(visits)
        var before: Set<Long> = emptySet()
        val next = write(app, why) { c ->
            before = c.pins.filter { it.here }.map { it.at }.toSet()
            c.copy(pins = Here.marked(PinList.watched(c.pins) { k -> possible && visits[k]?.owed == false }, here))
        }
        if (next != null) {
            val after = next.pins.filter { it.here }.map { it.at }.toSet()
            if (after != before) Log.i(TAG, "place: the back's notes follow ($why) — ${after.size} of ${next.pins.count { it.placed }} place note(s) there now")
        }
        return next
    }

    /** The places the phone is at now, by their keys (Here.keys of what PlaceStore holds): a note kept for one of them is on the back at once. */
    fun hereNow(context: Context): Set<String> = Here.keys(PlaceStore(context.applicationContext).load().visits)

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
            store.update { s -> s.copy(visits = s.visits + (place.key to (s.visits[place.key] ?: Arrivals.Visit()).copy(owed = place.key !in ok))) }
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
    internal fun settled(context: Context, place: Arrivals.Place, fix: Arrivals.Fix?, before: Arrivals.Visit = Arrivals.Visit()) {
        val app = context.applicationContext
        val store = PlaceStore(app)
        val t = System.currentTimeMillis()
        store.update { s ->
            val now = s.visits[place.key] ?: Arrivals.Visit()
            // Only if nothing has said more about the place in the meantime (Arrivals.settled's own rule).
            val next = Arrivals.settled(place, before, now, fix, s.seen, t) ?: return@update s
            if (now.where == Arrivals.Where.INSIDE && next.where == Arrivals.Where.OUTSIDE) Log.i(TAG, "place: found outside at pin time — left it (an exit missed)")
            s.copy(visits = s.visits + (place.key to next), seen = fix?.let { Arrivals.Seen(it.lat, it.lng, it.at) } ?: s.seen)
        }
        Log.i(TAG, "place: pinned, ${store.load().visits[place.key]?.where?.name?.lowercase() ?: "not known"} at pin time${if (fix == null) " (no fix in time)" else ""}")
        marks(app, "the state at pin time")
    }

    /** A fence's [event] for the places [keys], with the fix that caused it. */
    fun onFence(context: Context, event: Arrivals.Event, keys: List<String>, fix: Arrivals.Fix?) {
        val app = context.applicationContext
        val pins = ConfigStore(app).load().pins
        val places = PinList.places(pins)
        val now = System.currentTimeMillis()
        val arrived = ArrayList<String>()
        PlaceStore(app).update { s ->
            val v = s.visits.toMutableMap()
            for (k in keys) {
                val place = places[k] ?: continue
                val r = Arrivals.fence(event, place, v[k] ?: Arrivals.Visit(owed = false), fix, s.seen, now)
                v[k] = r.visit.copy(owed = false)
                val standing = PinList.standing(pins, k)
                Log.i(TAG, "place: ${event.name.lowercase()} — ${r.why}${if (r.arrived && standing) " (its page still stands: not again)" else ""}")
                if (r.arrived && !standing) arrived += k
            }
            s.copy(visits = v, seen = fix?.takeIf { !it.mock }?.let { Arrivals.Seen(it.lat, it.lng, it.at) } ?: s.seen)
        }
        // A fence of a place with no note left (its last note went while Play services was away): gone.
        (keys - places.keys).takeIf { it.isNotEmpty() }?.let { Fences.remove(app, it) }
        // The back follows: an arrival (its page, and its notes here), or the notes there coming or going (an EXIT
        // takes them off the notes page). Told by the payload; the service made sure of when it changed.
        if (arrived.isNotEmpty()) arrive(app, arrived, fix?.ageMs)
        else if (marks(app, "a fence's ${event.name.lowercase()}") != null) wake(app)
    }

    /** The look on waking (front on): one fix of ours, while a place note waits, at most once in 5 minutes. */
    fun look(context: Context, why: String) {
        val app = context.applicationContext
        val pins = ConfigStore(app).load().pins
        if (pins.none { it.placed && it.arrivedAt == 0L }) return
        if (!PlacePermission.fine(app) || !PlacePermission.locationOn(app)) return
        // Only with the FRONT on (its display's own state; the phone is "interactive" with the back alone lit too).
        if (!dev.kb896.aiusage.service.FrontScreen.on(app)) { Log.i(TAG, "place: no look ($why) — the front is off"); return }
        val store = PlaceStore(app)
        val now = System.currentTimeMillis()
        val s = store.load()
        if (now - s.lookedAt < LOOK_APART_MS) return
        // Once a day all fences are set again (fences can be lost without a word); owed ones every time.
        if (now - s.refreshedAt > DAY_MS) Fences.sync(app, "once a day", all = true)
        else if (s.visits.values.any { it.owed }) Fences.sync(app, "owed, on waking")
        val gps = !Fixes.online(app) && now - s.gpsAt >= GPS_APART_MS
        store.update { it.copy(lookedAt = now, gpsAt = if (gps) now else it.gpsAt) }
        Fixes.one(app, gpsToo = gps) { fix ->
            if (fix == null) { Log.i(TAG, "place: look on waking ($why) — no fix"); return@one }
            val places = PinList.places(ConfigStore(app).load().pins)
            val current = ConfigStore(app).load().pins
            val t = System.currentTimeMillis()
            val arrived = ArrayList<String>()
            store.update { st ->
                val v = st.visits.toMutableMap()
                for ((k, place) in places) {
                    val r = Arrivals.look(place, v[k] ?: Arrivals.Visit(), fix, st.seen, t)
                    v[k] = r.visit.copy(owed = v[k]?.owed ?: true)
                    if (r.arrived && !PinList.standing(current, k)) arrived += k
                    if (r.arrived || r.visit.where != (st.visits[k]?.where)) Log.i(TAG, "place: look on waking — ${r.why}")
                }
                st.copy(visits = v, seen = Arrivals.Seen(fix.lat, fix.lng, fix.at))
            }
            // Found inside or outside: the notes there come onto the back's notes page or leave it.
            if (arrived.isNotEmpty()) arrive(app, arrived, fix.ageMs) else marks(app, "a look on waking")
        }
    }

    /** He has reached [keys]: their notes stand on the back from now (and are on its notes page: he is there), and the chime, as the rules say. */
    private fun arrive(context: Context, keys: List<String>, fixAgeMs: Long?) {
        val now = System.currentTimeMillis()
        val here = hereNow(context)
        val next = write(context, "an arrival") { c -> keys.fold(c.pins) { p, k -> PinList.arrived(p, k, now) ?: p }.let { c.copy(pins = Here.marked(it, here)) } }
        if (next == null) return
        Log.i(TAG, "place: ARRIVED (${keys.size} place${if (keys.size == 1) "" else "s"}, ${PinList.arrivals(next.pins).size} note(s) stand on the back)")
        // Only the service has the shell's bridge; an arrival is one of the few moments it may be started from the background.
        wake(context)
        ArrivalChime.arrived(context, fixAgeMs)
    }

    /** "Not now" on the back for the note pinned at [at]: all at its place wait again. */
    fun later(context: Context, at: Long) {
        write(context, "not now") { c -> PinList.later(c.pins, at)?.let { c.copy(pins = it) } }
        Log.i(TAG, "place: not now — it returns at the next visit")
    }

    /** A pin's own animation tells the back by about 2 s (PinBeam.ARRIVE_AT, or at once through Xiaomi's); past this, the hold gives up. */
    const val HOLD_MS = 6_000L
    const val LOOK_APART_MS = 5 * 60_000L
    const val GPS_APART_MS = 15 * 60_000L
    const val DAY_MS = 24 * 60 * 60_000L
}
