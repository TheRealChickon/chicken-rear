package dev.kb896.aiusage.place

import kotlinx.serialization.Serializable

@Serializable
data class Tile(val id: Long, val name: String, val lat: Double? = null, val lng: Double? = null, val radius: Int = Arrivals.ZONE_DEFAULT) {
    val set: Boolean get() = lat != null && lng != null
}

object Tiles {
    const val MOST = 8
    const val NAME_MOST = 24
    const val HOME = "Home"

    private fun clean(name: String) = name.trim().replace('\n', ' ').take(NAME_MOST)

    /** A new tile at the end; null for a blank name or a full row. Its id is [id] (the time it was made), one more while taken. */
    fun add(tiles: List<Tile>, name: String, id: Long, lat: Double? = null, lng: Double? = null, radius: Int = Arrivals.ZONE_DEFAULT): List<Tile>? {
        val n = clean(name)
        if (n.isEmpty() || tiles.size >= MOST) return null
        var i = id
        while (tiles.any { it.id == i }) i++
        return tiles + Tile(i, n, lat, lng, Arrivals.zone(radius))
    }

    fun rename(tiles: List<Tile>, id: Long, name: String): List<Tile> {
        val n = clean(name)
        if (n.isEmpty()) return tiles
        return tiles.map { if (it.id == id) it.copy(name = n) else it }
    }

    fun place(tiles: List<Tile>, id: Long, lat: Double, lng: Double, radius: Int): List<Tile> =
        tiles.map { if (it.id == id) it.copy(lat = lat, lng = lng, radius = Arrivals.zone(radius)) else it }

    fun remove(tiles: List<Tile>, id: Long): List<Tile> = tiles.filter { it.id != id }

    /** One place earlier ([by] -1) or later (+1) in the row. */
    fun move(tiles: List<Tile>, id: Long, by: Int): List<Tile> {
        val i = tiles.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in tiles.indices) return tiles
        val m = tiles.toMutableList()
        val t = m.removeAt(i); m.add(j, t)
        return m
    }

    /** "Home" is offered (a name only) when he has marked a home Wi-Fi and no tile is called Home. */
    fun offerHome(tiles: List<Tile>, homeWifiMarked: Boolean): Boolean = homeWifiMarked && tiles.none { it.name.equals(HOME, ignoreCase = true) }
}
