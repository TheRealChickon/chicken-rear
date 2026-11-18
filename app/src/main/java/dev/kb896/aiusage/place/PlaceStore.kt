package dev.kb896.aiusage.place

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the app keeps for its places, PRIVATE to it (a file in its own files folder, never in the payload the back is
 * sent, never logged): per place what Arrivals knows of it ([Arrivals.Visit]), the last fix seen (for a jump), the
 * quick tiles, the prompt's draft (so the prompt comes back as he left it after the permission page or a process
 * death: SPEC R3), where the map last stood (the prompt opens there without a network), and the chime's own times.
 */
class PlaceStore(context: Context) {
    private val file = java.io.File(context.applicationContext.filesDir, FILE)

    /** The prompt as he left it; [image]: the picture he shared to it (its kept file's name, data/PinImage), [text] then its caption. */
    @Serializable
    data class Draft(val text: String, val lat: Double? = null, val lng: Double? = null, val radius: Int = Arrivals.ZONE_DEFAULT, val name: String? = null, val tile: Long? = null, val image: String? = null)

    @Serializable
    data class Camera(val lat: Double, val lng: Double, val zoom: Double)

    @Serializable
    data class State(
        val visits: Map<String, Arrivals.Visit> = emptyMap(),
        val seen: Arrivals.Seen? = null,
        val tiles: List<Tile> = emptyList(),
        val draft: Draft? = null,
        val camera: Camera? = null,
        /** The last chime of an arrival (wall ms); one held for a call, 0 for none. */
        val chimedAt: Long = 0L,
        val chimeOwedAt: Long = 0L,
        /** The last look on waking, and the last day all fences were set again (wall ms). */
        val lookedAt: Long = 0L,
        val refreshedAt: Long = 0L,
        /** The last GPS try on waking with no data connection (at most one in 15 minutes). */
        val gpsAt: Long = 0L,
        /** Play services refused fences since this (wall ms): GEOFENCE_NOT_AVAILABLE, Location off. 0 for not. */
        val unavailableSince: Long = 0L,
    ) {
        /** The state with only the places in [keys] kept (a place with no note left is forgotten). */
        fun only(keys: Set<String>): State = if (visits.keys.all { it in keys }) this else copy(visits = visits.filterKeys { it in keys })
    }

    // ONE LOCK FOR EVERY INSTANCE: the fence's thread, the main thread and the chime's thread each make their own.
    fun load(): State = synchronized(LOCK) { runCatching { if (file.exists()) decode(file.readText()) else State() }.getOrDefault(State()) }

    fun save(s: State) = synchronized(LOCK) {
        runCatching {
            val tmp = java.io.File(file.parentFile, "$FILE.tmp")
            tmp.writeText(encode(s))
            tmp.renameTo(file)
        }
    }

    fun update(change: (State) -> State): State = synchronized(LOCK) {
        val next = change(load())
        save(next)
        next
    }

    companion object {
        const val FILE = "places.json"
        private val LOCK = Any()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
        fun encode(s: State): String = json.encodeToString(State.serializer(), s)
        fun decode(raw: String): State = runCatching { json.decodeFromString(State.serializer(), raw) }.getOrDefault(State())
    }
}
