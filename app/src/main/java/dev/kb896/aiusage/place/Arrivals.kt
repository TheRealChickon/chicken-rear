package dev.kb896.aiusage.place

import kotlinx.serialization.Serializable

object Arrivals {
    /** A fence event's fix counts as clean up to this age. */
    const val EVENT_FIX_MS = 120_000L
    /** A fix the app asks for itself counts only up to this age (R13). */
    const val OWN_FIX_MS = 60_000L
    /** At or over this speed he is driving past (29 km/h). */
    const val DRIVING_MPS = 8f
    /** A position he could only have reached at this speed from the last fix seen is a jump (250 km/h). */
    const val JUMP_MPS = 70f
    /** A clean exit is a fix beyond the zone by its error, and by this at least. */
    const val EXIT_MARGIN_M = 50f
    /** Outside this long after a clean exit before he may arrive again; after a doubtful one, [AGAIN_DOUBTFUL_MS]. */
    const val AGAIN_CLEAN_MS = 10 * 60_000L
    const val AGAIN_DOUBTFUL_MS = 45 * 60_000L
    /** Chimes closer together than this are one. */
    const val CHIMES_APART_MS = 60_000L
    /** An event whose fix is older than this when it reaches the app shows its page without a sound. */
    const val STALE_MS = 10 * 60_000L
    /** A chime held for a call is played when it ends if the arrival is younger than this. */
    const val AFTER_CALL_MS = 10 * 60_000L

    /** The zones the slider offers, in metres. Never under 100 m: a smaller zone than a fix's error never fires by itself. */
    val ZONES = intArrayOf(100, 150, 200, 300, 500, 750, 1000, 1500, 2000)
    const val ZONE_DEFAULT = 150

    /** The nearest zone the slider has to [m]. */
    fun zone(m: Int): Int = ZONES.minByOrNull { kotlin.math.abs(it - m) } ?: ZONE_DEFAULT

    /** "150 m", "1.5 km". */
    fun zoneName(m: Int): String = if (m < 1000) "$m m" else if (m % 1000 == 0) "${m / 1000} km" else "${m / 1000}.${(m % 1000) / 100} km"

    /** A place: where he put the pin and its zone. */
    data class Place(val lat: Double, val lng: Double, val radius: Int) {
        /** The fence's id: the point to 5 decimals (about a metre) and the zone; the same place always has the same id. */
        val key: String get() = key(lat, lng, radius)
    }

    fun key(lat: Double, lng: Double, radius: Int): String =
        "p:" + String.format(java.util.Locale.ROOT, "%.5f,%.5f", lat, lng) + ",$radius"

    /**
     * A position. [ageMs] its age by the system's elapsed clock (a network fix's wall stamp can be wrong), [at] the wall
     * time it stood for (now minus its age), [speed] in m/s when the fix has one (GPS; a network fix has none).
     */
    data class Fix(val lat: Double, val lng: Double, val accuracy: Float, val speed: Float?, val ageMs: Long, val at: Long, val mock: Boolean = false)

    /** The last fix the app saw, of any place: for a jump. Kept on the phone only. */
    @Serializable
    data class Seen(val lat: Double, val lng: Double, val at: Long)

    /** Where he is as to a place. INSIDE is also what puts the place's notes on the back's notes page (Here); the other two keep them off it. */
    @Serializable
    enum class Where { UNKNOWN, INSIDE, OUTSIDE }

    /** What is known of one place (PlaceStore keeps it). */
    @Serializable
    data class Visit(
        val where: Where = Where.UNKNOWN,
        /** When he last left it (wall ms), 0 for not since it was pinned; [exitClean] whether that exit was a clean one. */
        val exitAt: Long = 0L,
        val exitClean: Boolean = true,
        /** When he last arrived there. */
        val arrivedAt: Long = 0L,
        /** An ENTER held for doubt since then; 0 for none. */
        val heldAt: Long = 0L,
        /** Its fence is to be set (again): not known to be held by Play services. */
        val owed: Boolean = true,
    )

    enum class Quality { CLEAN, NO_FIX, OLD, MOCK, LOOSE, OUTSIDE, DRIVING, JUMP }

    data class Result(val visit: Visit, val arrived: Boolean, val why: String)

    /** Metres between two points (haversine). */
    fun metres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_008.8
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dp / 2) * kotlin.math.sin(dp / 2) + kotlin.math.cos(p1) * kotlin.math.cos(p2) * kotlin.math.sin(dl / 2) * kotlin.math.sin(dl / 2)
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun distance(place: Place, fix: Fix): Double = metres(place.lat, place.lng, fix.lat, fix.lng)

    /** A position he could only have reached from [last] faster than [JUMP_MPS]. */
    fun jump(fix: Fix, last: Seen?): Boolean {
        if (last == null) return false
        val dt = (fix.at - last.at) / 1000.0
        if (dt <= 0.0) return false
        return metres(last.lat, last.lng, fix.lat, fix.lng) / dt > JUMP_MPS
    }

    /** How good [fix] is as proof that he is in [place], for an event's fix ([maxAge] [EVENT_FIX_MS]) or one of ours ([OWN_FIX_MS]). */
    fun judge(place: Place, fix: Fix?, last: Seen?, maxAge: Long = EVENT_FIX_MS): Quality = when {
        fix == null -> Quality.NO_FIX
        fix.mock -> Quality.MOCK
        fix.ageMs > maxAge -> Quality.OLD
        fix.accuracy > place.radius -> Quality.LOOSE
        distance(place, fix) > place.radius + fix.accuracy -> Quality.OUTSIDE
        (fix.speed ?: 0f) >= DRIVING_MPS -> Quality.DRIVING
        jump(fix, last) -> Quality.JUMP
        else -> Quality.CLEAN
    }

    /** Clearly outside: beyond the zone by the fix's error, and by [EXIT_MARGIN_M] at least. */
    fun clearlyOut(place: Place, fix: Fix): Boolean = distance(place, fix) > place.radius + maxOf(EXIT_MARGIN_M.toDouble(), fix.accuracy.toDouble())

    /** He may arrive now: he was outside long enough after his last exit, or it was never known that he was inside. */
    fun mayArrive(v: Visit, now: Long): Boolean = when (v.where) {
        Where.INSIDE -> false
        Where.UNKNOWN -> true
        Where.OUTSIDE -> v.exitAt == 0L || now - v.exitAt >= if (v.exitClean) AGAIN_CLEAN_MS else AGAIN_DOUBTFUL_MS
    }

    private fun arrive(v: Visit, now: Long, why: String): Result =
        if (mayArrive(v, now)) Result(v.copy(where = Where.INSIDE, arrivedAt = now, heldAt = 0L), true, why)
        else Result(v.copy(where = Where.INSIDE, heldAt = 0L), false, "inside again too soon after leaving: not a new visit")

    enum class Event { ENTER, DWELL, EXIT }

    /**
     * A fence's event for [place]. A fence's ENTER and DWELL are transitions (no initial ENTER is set where he might
     * be inside, [initial]), so an ENTER while the state is not known is an arrival, as from outside. An initial EXIT
     * (set where he was inside) is an EXIT like any other: he has left.
     */
    fun fence(event: Event, place: Place, v: Visit, fix: Fix?, last: Seen?, now: Long): Result = when (event) {
        Event.ENTER -> when {
            v.where == Where.INSIDE -> Result(v.copy(heldAt = 0L), false, "entered while already inside")
            else -> when (val q = judge(place, fix, last)) {
                Quality.CLEAN -> arrive(v, now, "entered, a clean fix")
                else -> Result(v.copy(heldAt = now), false, "entered, held: ${q.name.lowercase().replace('_', ' ')}")
            }
        }
        // Still inside two minutes on: whatever held the ENTER, he is there.
        Event.DWELL -> if (v.where == Where.INSIDE) Result(v.copy(heldAt = 0L), false, "still inside")
        else arrive(v, now, if (v.heldAt != 0L) "a held entry confirmed by staying" else "stayed inside")
        Event.EXIT -> {
            val clean = fix != null && !fix.mock && fix.ageMs <= EVENT_FIX_MS && clearlyOut(place, fix)
            when (v.where) {
                // Already outside (a held ENTER and then out again): the held one is dropped without a trace.
                Where.OUTSIDE -> Result(v.copy(heldAt = 0L), false, "left again before it counted")
                else -> Result(v.copy(where = Where.OUTSIDE, exitAt = now, exitClean = clean, heldAt = 0L), false, if (clean) "left, a clean exit" else "left, a doubtful exit")
            }
        }
    }

    /** A look of our own on waking ([fix] one of ours, at most [OWN_FIX_MS] old). It confirms a held entry, finds a missed one, or settles a state not known. */
    fun look(place: Place, v: Visit, fix: Fix, last: Seen?, now: Long): Result {
        if (fix.mock || fix.ageMs > OWN_FIX_MS) return Result(v, false, "the fix does not count")
        if (jump(fix, last)) return Result(v, false, "the fix is a jump")
        val d = distance(place, fix)
        val inside = d <= place.radius && fix.accuracy <= place.radius && (fix.speed ?: 0f) < DRIVING_MPS
        return when {
            inside -> when (v.where) {
                Where.INSIDE -> Result(v.copy(heldAt = 0L), false, "still inside")
                Where.UNKNOWN -> if (v.heldAt != 0L) arrive(v, now, "a held entry confirmed on waking")
                else Result(v.copy(where = Where.INSIDE), false, "found inside, not known before: no arrival")
                Where.OUTSIDE -> arrive(v, now, if (v.heldAt != 0L) "a held entry confirmed on waking" else "found inside on waking")
            }
            clearlyOut(place, fix) -> when (v.where) {
                Where.INSIDE -> Result(v.copy(where = Where.OUTSIDE, exitAt = now, exitClean = true, heldAt = 0L), false, "found outside on waking: a clean exit")
                Where.UNKNOWN -> Result(v.copy(where = Where.OUTSIDE, heldAt = 0L), false, "found outside, not known before")
                Where.OUTSIDE -> Result(v.copy(heldAt = 0L), false, "still outside")
            }
            else -> Result(v, false, "the fix says neither")
        }
    }

    /** The state at pin time from one fix (or none in time): inside, outside or not known. Pinned while inside: no arrival now. */
    fun pinned(place: Place, fix: Fix?): Visit {
        if (fix == null || fix.mock || fix.ageMs > OWN_FIX_MS) return Visit(owed = true)
        return when {
            distance(place, fix) <= place.radius && fix.accuracy <= place.radius -> Visit(where = Where.INSIDE, owed = true)
            clearlyOut(place, fix) -> Visit(where = Where.OUTSIDE, owed = true)
            else -> Visit(owed = true)
        }
    }

    /**
     * The fix at pin time ([fix], one of ours; null: none in time) for a place whose state was [before] as the note was
     * kept and is [now] as the fix comes: the state it makes, or null where it says nothing (the state stays [now]).
     * - Not known (and no arrival there): the state from the fix ([pinned]), as for a new place.
     * - INSIDE as the note was kept, nothing said of the place since (but its fence's owed mark), and the fix finds him
     *   clearly outside by the look's own rules ([look]: one that counts, not a jump): he has left it, a clean exit. An
     *   EXIT missed while the place's fence was not held (Location off, a reboot, an update) would otherwise keep the
     *   place here, and its notes on the back's notes page, after he had gone.
     * - Anything else says nothing: OUTSIDE found inside is no arrival here (pinning never chimes; the fence's DWELL
     *   or the look on waking makes it one), and what a fence has said since the note was kept stands.
     */
    fun settled(place: Place, before: Visit, now: Visit, fix: Fix?, last: Seen?, t: Long): Visit? = when {
        now.where == Where.UNKNOWN && now.arrivedAt == 0L -> pinned(place, fix).copy(owed = now.owed)
        fix == null || before.where != Where.INSIDE || now.copy(owed = before.owed) != before -> null
        else -> look(place, now, fix, last, t).visit.takeIf { it.where == Where.OUTSIDE }
    }

    /** A fence's initial trigger: Play services' own look as the fence is added, and the event it sends from it. */
    enum class Initial { NONE, ENTER, EXIT }

    /**
     * The initial trigger of a place's fence as it is set (again: after a reboot, an update, Location back, once a day,
     * and at the tap on Pin), by what is stored of the place, so Play services makes sure of it as the fence is added:
     * - OUTSIDE: an ENTER if he is inside by then, so a place reached while the phone was off still arrives. At the
     *   tap on Pin never ([atPin]: pinning never chimes).
     * - INSIDE: an EXIT if he is outside by then. A fence set again has no state of its own, so an EXIT missed while it
     *   was not held is never sent after; the place would stay here, and its notes on the back, until he came back.
     * - Not known: none (an ENTER would chime at a restart where he already is; an EXIT would hold off his next arrival).
     */
    fun initial(v: Visit, atPin: Boolean = false): Initial = when (v.where) {
        Where.OUTSIDE -> if (atPin) Initial.NONE else Initial.ENTER
        Where.INSIDE -> Initial.EXIT
        Where.UNKNOWN -> Initial.NONE
    }

    enum class Sound { NOW, SILENT_STALE, AFTER_CALL, ONE_WITH_LAST }

    /**
     * How an arrival sounds. [fixAgeMs] the age of the fix that made it when it reached the app (null: not known, taken
     * as fresh); [inCall] a call is on; [lastChimeAt] the last chime's time. Whether the phone lets a notification's
     * sound through (silent, vibrate, Do Not Disturb) is PinChime's, after this.
     */
    fun sound(fixAgeMs: Long?, inCall: Boolean, lastChimeAt: Long, now: Long): Sound = when {
        fixAgeMs != null && fixAgeMs > STALE_MS -> Sound.SILENT_STALE
        now - lastChimeAt < CHIMES_APART_MS -> Sound.ONE_WITH_LAST
        inCall -> Sound.AFTER_CALL
        else -> Sound.NOW
    }

    /** The chime held for a call, at its end: played only while the arrival is under [AFTER_CALL_MS] old. */
    fun afterCall(owedAt: Long, now: Long): Boolean = owedAt != 0L && now - owedAt < AFTER_CALL_MS
}
