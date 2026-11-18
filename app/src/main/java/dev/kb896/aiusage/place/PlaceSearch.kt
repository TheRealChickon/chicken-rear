package dev.kb896.aiusage.place

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object PlaceSearch {
    const val HOST = "https://photon.komoot.io/api/"
    const val LEAST = 3
    const val QUIET_MS = 350L
    const val APART_MS = 1_000L
    const val MOST = 4
    const val CREDIT = "Search: Photon · © OpenStreetMap"
    /** The bias's zoom at most: a town's width, as wide as its rounded middle is coarse. */
    const val BIAS_ZOOM = 11

    data class Hit(val name: String, val line: String, val lat: Double, val lng: Double)

    /** The request for [q] near the map's middle ([lat], [lng] at [zoom], rounded to 0.1 degree and zoom [BIAS_ZOOM]: a bias, not a position). Null: not to be sent. */
    fun url(q: String, lat: Double?, lng: Double?, zoom: Double?, lang: String?): String? {
        val text = q.trim()
        if (text.length < LEAST) return null
        val b = StringBuilder(HOST).append("?q=").append(java.net.URLEncoder.encode(text, "UTF-8")).append("&limit=").append(MOST)
        if (lat != null && lng != null) {
            b.append("&lat=").append(String.format(java.util.Locale.ROOT, "%.1f", lat))
            b.append("&lon=").append(String.format(java.util.Locale.ROOT, "%.1f", lng))
            b.append("&zoom=").append((zoom?.toInt() ?: BIAS_ZOOM).coerceIn(1, BIAS_ZOOM))
        }
        // Photon knows a few languages; any other is its default (the local names).
        if (lang in setOf("en", "de", "fr", "it")) b.append("&lang=").append(lang)
        return b.toString()
    }

    fun userAgent(version: String) = "ChickenRear/$version (Android app; pin to location)"

    private val json = Json { ignoreUnknownKeys = true }

    /** Photon's GeoJSON into hits: a name (or the street and number), a second line of where, the point. */
    fun parse(body: String): List<Hit> = runCatching {
        val features = json.parseToJsonElement(body).jsonObject["features"] as? JsonArray ?: return emptyList()
        features.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val c = o["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
            val lng = c.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lat = c.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val p = o["properties"]?.jsonObject ?: JsonObject(emptyMap())
            fun s(k: String) = p[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val street = listOfNotNull(s("street"), s("housenumber")).joinToString(" ").ifEmpty { null }
            val name = s("name") ?: street ?: s("city") ?: return@mapNotNull null
            val line = listOfNotNull(street?.takeIf { it != name }, s("city")?.takeIf { it != name }, s("country")).distinct().joinToString(", ")
            Hit(name, line, lat, lng)
        }.distinctBy { "${it.name}|${it.line}" }.take(MOST)
    }.getOrDefault(emptyList())

    /** No more than one request in [APART_MS]: how long to wait before the next may go. Pure. */
    class Gate(private val apartMs: Long = APART_MS) {
        private var lastAt = Long.MIN_VALUE / 2
        @Synchronized fun waitMs(now: Long): Long = (lastAt + apartMs - now).coerceAtLeast(0L)
        @Synchronized fun sent(now: Long) { lastAt = now }
    }
}
