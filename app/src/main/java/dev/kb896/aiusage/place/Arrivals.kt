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
    /** The gap between the entry and exit radii, before adding the fix’s error. */
    const val EXIT_MARGIN_M = 100
    const val CONFIRM_FOR_MS = 3 * 60 * 60_000L
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
        /** Wall time of the fix that last confirmed this visit, and the last accepted transition. */
        val confirmedAt: Long = 0L,
        val changedAt: Long = 0L,
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
        !accurate(place, fix) -> Quality.LOOSE
        distance(place, fix) > place.radius -> Quality.OUTSIDE
        (fix.speed ?: 0f) >= DRIVING_MPS -> Quality.DRIVING
        jump(fix, last) -> Quality.JUMP
        else -> Quality.CLEAN
    }

    private fun accurate(place: Place, fix: Fix): Boolean =
        fix.accuracy.isFinite() && fix.accuracy in 0f..place.radius.toFloat()

    fun clearlyOut(place: Place, fix: Fix): Boolean =
        accurate(place, fix) && distance(place, fix) > place.radius + EXIT_MARGIN_M + fix.accuracy

    fun freshConfirmation(at: Long, now: Long): Boolean = at > 0L && now - at in 0 until CONFIRM_FOR_MS

    private fun confirm(v: Visit, at: Long): Visit =
        v.copy(confirmedAt = maxOf(v.confirmedAt, at), heldAt = 0L)

    private fun leave(v: Visit, now: Long, at: Long, clean: Boolean) =
        v.copy(where = Where.OUTSIDE, exitAt = now, exitClean = clean, heldAt = 0L, confirmedAt = 0L, changedAt = at)

    private fun superseded(v: Visit, fix: Fix): Boolean =
        fix.at < maxOf(v.confirmedAt, v.changedAt)

    /** He may arrive now: he was outside long enough after his last exit, or it was never known that he was inside. */
    fun mayArrive(v: Visit, now: Long): Boolean = when (v.where) {
        Where.INSIDE -> false
        Where.UNKNOWN -> true
        Where.OUTSIDE -> v.exitAt == 0L || now - v.exitAt >= if (v.exitClean) AGAIN_CLEAN_MS else AGAIN_DOUBTFUL_MS
    }

    private fun arrive(v: Visit, now: Long, at: Long, why: String): Result {
        val inside = confirm(v.copy(where = Where.INSIDE, changedAt = at), at)
        return if (mayArrive(v, now)) Result(inside.copy(arrivedAt = now), true, why)
        else Result(inside, false, "inside again too soon after leaving: no new arrival")
    }

    enum class Event { ENTER, DWELL, EXIT }

    fun fence(event: Event, place: Place, v: Visit, fix: Fix?, last: Seen?, now: Long): Result {
        if (fix != null && (superseded(v, fix) || (event != Event.ENTER && fix.mock))) return Result(v, false, "ignored mock or superseded fix")
        val at = fix?.at ?: now
        return when (event) {
            Event.ENTER -> when (val quality = judge(place, fix, last)) {
                Quality.CLEAN -> if (v.where == Where.INSIDE) Result(confirm(v, at), false, "confirmed inside")
                else arrive(v, now, at, "entered, a clean fix")
                else -> Result(if (v.where == Where.INSIDE) v else v.copy(heldAt = now), false, "entry held: ${quality.name.lowercase()}")
            }
            Event.DWELL -> when {
                fix != null && judge(place, fix, last) != Quality.CLEAN -> Result(v, false, "dwell fix did not confirm inside")
                v.where != Where.INSIDE && (fix != null || (v.heldAt > 0L && now - v.heldAt in 0..STALE_MS)) ->
                    arrive(v, now, at, "entered, confirmed by dwell")
                fix != null -> Result(confirm(v, at), false, "confirmed inside by dwell")
                else -> Result(v, false, "dwell without current evidence")
            }
            // Production delivers EXIT only from the expanded fence. A fix, when supplied, must independently
            // clear the uncertainty band for an INSIDE visit (see [fenceExit]).
            Event.EXIT -> if (v.where == Where.OUTSIDE) Result(v.copy(heldAt = 0L), false, "already outside")
            else {
                val clean = fix != null && fix.ageMs <= EVENT_FIX_MS && clearlyOut(place, fix)
                Result(leave(v, now, at, clean), false, if (clean) "left, a clean fence exit" else "left, a doubtful fence exit")
            }
        }
    }

    fun fenceExit(place: Place, v: Visit, fix: Fix?, last: Seen?, now: Long): Result =
        if (v.where == Where.INSIDE && fix != null) look(place, v, fix, last, now, EVENT_FIX_MS)
        else fence(Event.EXIT, place, v, fix, last, now)

    /** Reconcile a visit using the position we have, even if the fence has said nothing. */
    fun look(place: Place, v: Visit, fix: Fix?, last: Seen?, now: Long, maxAge: Long = OWN_FIX_MS): Result {
        val unusable = when {
            fix == null -> "no fix"
            fix.mock -> "mock fix"
            fix.ageMs > maxAge -> "old fix"
            superseded(v, fix) -> "superseded fix"
            !accurate(place, fix) -> "poor accuracy"
            jump(fix, last) -> "position jump"
            else -> null
        }
        if (fix == null || unusable != null) return unconfirmed(v, now, unusable ?: "no fix")
        return when {
            distance(place, fix) <= place.radius && (fix.speed ?: 0f) < DRIVING_MPS -> when (v.where) {
                Where.INSIDE -> Result(confirm(v, fix.at), false, "confirmed inside")
                Where.UNKNOWN -> if (v.heldAt != 0L) arrive(v, now, fix.at, "entered, a held entry confirmed")
                else Result(confirm(v.copy(where = Where.INSIDE, changedAt = fix.at), fix.at), false, "found inside")
                Where.OUTSIDE -> arrive(v, now, fix.at, "entered, found inside")
            }
            clearlyOut(place, fix) -> when (v.where) {
                Where.INSIDE -> Result(leave(v, now, fix.at, clean = true), false, "left, fix beyond exit radius and accuracy")
                else -> Result(v.copy(where = Where.OUTSIDE, heldAt = 0L, changedAt = fix.at), false, "confirmed outside")
            }
            else -> unconfirmed(v, now, "fix did not confirm inside or outside")
        }
    }

    private fun unconfirmed(v: Visit, now: Long, why: String): Result =
        if (v.where == Where.INSIDE && !freshConfirmation(v.confirmedAt, now))
            Result(v.copy(where = Where.UNKNOWN, confirmedAt = 0L, heldAt = 0L), false, "visit expired: $why")
        else Result(v, false, why)

    /** The state at pin time from one fix (or none in time): inside, outside or not known. Pinned while inside: no arrival now. */
    fun pinned(place: Place, fix: Fix?): Visit {
        if (fix == null || fix.mock || fix.ageMs > OWN_FIX_MS) return Visit(owed = true)
        return when {
            distance(place, fix) <= place.radius && accurate(place, fix) -> Visit(where = Where.INSIDE, confirmedAt = fix.at, changedAt = fix.at)
            clearlyOut(place, fix) -> Visit(where = Where.OUTSIDE, changedAt = fix.at)
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
