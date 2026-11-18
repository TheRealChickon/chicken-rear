package dev.kb896.aiusage.place

/**
 * The prompt's rules that need no screen (ui/place/PlacePinActivity draws them). Pure.
 */
object Prompt {
    /** The sheet (dp): 384 wide at most, 8 from the left, right and bottom; the top third stays free for the pin's card. */
    data class Box(val left: Float, val top: Float, val width: Float, val height: Float)

    const val SHEET_W = 384f
    const val SHEET_H = 560f
    const val MARGIN = 8f

    /** Where the sheet stands on a screen of [w] x [h] dp: never off it, never over its top third (a short screen gets a shorter sheet). */
    fun sheet(w: Float, h: Float): Box {
        val width = minOf(SHEET_W, w - 2 * MARGIN).coerceAtLeast(0f)
        val height = minOf(SHEET_H, h - MARGIN - h / 3f).coerceAtLeast(0f)
        return Box((w - width) / 2f, h - MARGIN - height, width, height)
    }

    /**
     * The place's name at the tap: the search result's first line when he came by search and stayed within the zone
     * ([named] where that result was), a tile's name for a tile; otherwise none. No reverse geocoding (it would send the
     * point somewhere).
     */
    fun name(name: String?, namedLat: Double?, namedLng: Double?, lat: Double, lng: Double, radius: Int): String? {
        if (name.isNullOrBlank() || namedLat == null || namedLng == null) return null
        return if (Arrivals.metres(namedLat, namedLng, lat, lng) <= radius) name.trim().take(PinList.NAME_MOST) else null
    }

    /** The slider's step for a zone in metres, and back. */
    fun step(radius: Int): Int = Arrivals.ZONES.indexOf(Arrivals.zone(radius)).coerceAtLeast(0)
    fun zoneAt(step: Int): Int = Arrivals.ZONES[step.coerceIn(0, Arrivals.ZONES.size - 1)]

    /** The ring at rest is [REST_DP] across: the zoom that gives a zone of [radiusPx] (as drawn now at [zoom]) that size. */
    const val REST_DP = 132f
    fun zoomFor(zoom: Double, radiusPx: Float, density: Float): Double {
        if (radiusPx <= 0f) return zoom
        val want = REST_DP / 2f * density
        return (zoom + kotlin.math.ln((want / radiusPx).toDouble()) / kotlin.math.ln(2.0)).coerceIn(2.0, 19.0)
    }
}
