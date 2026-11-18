package dev.kb896.aiusage.ui.place

import android.content.Context
import android.util.Log
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * THE PROMPT'S MAP, WITHOUT A KEY: MapLibre Native (BSD) drawing OpenFreeMap's public vector tiles (no registration,
 * no key, no cookies; its terms ask for the line [CREDIT], shown in the map's well). In TextureView mode, so it is a
 * view in our window like any other (clipped round, not blurred by the window's blur behind it).
 *
 * Tiles are fetched only while this map exists, i.e. while the prompt is open; MapLibre keeps the ones it fetched in
 * its own cache in the app's files, so without a network the prompt still opens where it was with what was seen
 * there. No position of his is given to it (no location layer): the map only shows where he moves it.
 *
 * The prompt talks to it through a few calls: the middle, the zoom, where a point lies on the screen, moving it.
 */
class PlaceMap(context: Context, lat: Double, lng: Double, zoom: Double, dark: Boolean) {
    val view: MapView
    @Volatile var map: MapLibreMap? = null
        private set

    var onMoveStarted: () -> Unit = {}
    var onMove: () -> Unit = {}
    var onIdle: () -> Unit = {}
    /** The first picture with every tile in it (or the style failed): the well's cover may go. */
    var onShown: () -> Unit = {}
    private var shown = false

    init {
        MapLibre.getInstance(context.applicationContext)
        // NOTHING IN THE LOG THAT SAYS WHERE: a tile's address (z/x/y) is a place to a few hundred metres, and MapLibre
        // says the address of a request that failed (offline). Its lines are passed on with every address taken out.
        org.maplibre.android.log.Logger.setLoggerDefinition(Quiet)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .camera(CameraPosition.Builder().target(LatLng(lat, lng)).zoom(zoom).build())
        view = MapView(context, options)
        view.onCreate(null)
        view.addOnDidFinishRenderingMapListener { fully -> if (fully && !shown) { shown = true; onShown() } }
        view.addOnDidFailLoadingMapListener { why -> Log.i(TAG, "map: the style did not load (${why.take(60)}) — the last tiles seen, if any"); if (!shown) { shown = true; onShown() } }
        view.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isCompassEnabled = false; isLogoEnabled = false; isAttributionEnabled = false
                isRotateGesturesEnabled = false; isTiltGesturesEnabled = false
            }
            m.addOnCameraMoveStartedListener { onMoveStarted() }
            m.addOnCameraMoveListener { onMove() }
            m.addOnCameraIdleListener { onIdle() }
            m.setStyle(Style.Builder().fromUri(if (dark) STYLE_DARK else STYLE_LIGHT))
        }
    }

    val lat: Double? get() = map?.cameraPosition?.target?.latitude
    val lng: Double? get() = map?.cameraPosition?.target?.longitude
    val zoom: Double? get() = map?.cameraPosition?.zoom

    /** The zone's radius in screen pixels at the map's middle, as the map is drawn now; 0 before it is. */
    fun radiusPx(metres: Int): Float {
        val m = map ?: return 0f
        val c = m.cameraPosition.target ?: return 0f
        val p = m.projection
        val a = p.toScreenLocation(c)
        val b = p.toScreenLocation(LatLng(c.latitude + metres / 111_195.0, c.longitude))
        return kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
    }

    fun fly(lat: Double, lng: Double, zoom: Double? = null, ms: Int = 650) {
        val m = map ?: return
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom ?: m.cameraPosition.zoom), ms)
    }

    fun zoomTo(zoom: Double, ms: Int = 300) { map?.animateCamera(CameraUpdateFactory.zoomTo(zoom), ms) }

    /** MapLibre's log, warnings and errors only, with any web address cut out. */
    private object Quiet : org.maplibre.android.log.LoggerDefinition {
        private fun clean(m: String?) = m.orEmpty().replace(Regex("https?://\\S+"), "<address>").take(200)
        override fun v(t: String?, m: String?) = Unit
        override fun v(t: String?, m: String?, e: Throwable?) = Unit
        override fun d(t: String?, m: String?) = Unit
        override fun d(t: String?, m: String?, e: Throwable?) = Unit
        override fun i(t: String?, m: String?) = Unit
        override fun i(t: String?, m: String?, e: Throwable?) = Unit
        override fun w(t: String?, m: String?) { Log.w(TAG, "map: ${clean(m)}") }
        override fun w(t: String?, m: String?, e: Throwable?) { Log.w(TAG, "map: ${clean(m)} (${e?.javaClass?.simpleName})") }
        override fun e(t: String?, m: String?) { Log.w(TAG, "map: ${clean(m)}") }
        override fun e(t: String?, m: String?, e: Throwable?) { Log.w(TAG, "map: ${clean(m)} (${e?.javaClass?.simpleName})") }
    }

    companion object {
        private const val TAG = "AiUsagePlace"
        const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
        const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
        /** OpenFreeMap's own words for its credit. */
        const val CREDIT = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"
    }
}
