package dev.kb896.aiusage.service

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import dev.kb896.aiusage.rear.NavFace
import java.io.ByteArrayOutputStream

/**
 * A ROUTE BEING FOLLOWED, AS ITS APP POSTS IT: any app's ongoing notification of the category "navigation" (Google
 * Maps first; Waze, Petal Maps and others where they post one), read into the back's navigation page (rear/NavFace).
 *
 * Only what the notification says: its title, text and sub text as they are (a distance or an instruction is never
 * split, translated or worked out), Android 16's short critical text (the status bar chip's words), the large icon
 * (Maps' maneuver arrow), and Notification.ProgressStyle's progress, segments, points and icons. Missing is missing.
 *
 * THE KEYS, as Android 16 serialises ProgressStyle into the extras (Notification.ProgressStyle.addExtras and its
 * getProgressSegmentsAsBundleList / getProgressPointsAsBundleList in the Android 16 framework source): "android.progress" and
 * "android.progressMax" ints, "android.progressIndeterminate" and "android.styledByProgress" booleans,
 * "android.progressSegments" and "android.progressPoints" ArrayLists of Bundles ("length" or "position", "id",
 * "colorInt", "semanticStyle"), "android.progressTrackerIcon", "android.progressStartIcon", "android.progressEndIcon"
 * Icons; "android.shortCriticalText" a CharSequence. The four ProgressStyle keys are hidden in the SDK's android.jar.
 *
 * Pure but for Android's Bundle and Bitmap: the icons are loaded by the caller ([parse]'s loadIcon).
 */
object NavigationNotification {
    /** The live packet's kind ("kind" in the bundle IRearService.setLive takes): a route, beside the call's and timer's packet. */
    const val KIND = "navigation"
    const val SEGMENTS = "android.progressSegments"
    const val POINTS = "android.progressPoints"
    const val TRACKER = "android.progressTrackerIcon"
    const val START = "android.progressStartIcon"
    const val END = "android.progressEndIcon"
    const val STYLED = "android.styledByProgress"
    const val SHORT = "android.shortCriticalText"
    /** Android draws four points at most (ProgressStyle's MAX_PROGRESS_POINT_LIMIT). */
    const val MOST_POINTS = 4

    /** A route: the category "navigation", ongoing (or a foreground service's), and not a group's summary. */
    fun eligible(n: Notification): Boolean = n.category == Notification.CATEGORY_NAVIGATION &&
        n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0 &&
        n.flags and Notification.FLAG_GROUP_SUMMARY == 0

    fun parse(
        key: String, pkg: String, n: Notification, appIcon: Bitmap? = null,
        loadIcon: (Icon) -> Bitmap? = { null },
    ): NavFace.Model? {
        if (!eligible(n)) return null
        val x = n.extras ?: Bundle.EMPTY
        // A colour with no alpha is ProgressStyle's "default" (COLOR_DEFAULT; Android's sanitizeProgressColor takes any
        // such): the notification's own colour, as Android draws it; 0 (the page's neutral ink) where it has none.
        fun ink(c: Int) = if (c ushr 24 != 0) c else if (n.color ushr 24 != 0) n.color else 0
        val segmentRows = bundles(x, SEGMENTS)
        val parsedSegments = segmentRows.mapNotNull { b ->
            val length = integer(b, "length") ?: return@mapNotNull null
            if (length <= 0) null else NavFace.Segment(length, ink(integer(b, "colorInt") ?: 0))
        }
        // One bad segment and the route is not drawn at all: a shortened route would put the tracker in the wrong place.
        val segments = parsedSegments.takeIf { it.size == segmentRows.size && it.sumOf { s -> s.length.toLong() } <= Int.MAX_VALUE }.orEmpty()
        val max = integer(x, Notification.EXTRA_PROGRESS_MAX)?.takeIf { it > 0 }
        val rawProgress = integer(x, Notification.EXTRA_PROGRESS)
        // Held to the bar as Android draws it (ProgressStyle.createProgressModel: Math.clamp(progress, 0, max)).
        val fraction = if (!boolean(x, Notification.EXTRA_PROGRESS_INDETERMINATE, false) && max != null && rawProgress != null)
            rawProgress.coerceIn(0, max).toFloat() / max else null
        // The points Android draws: strictly inside the bar (one at either end is not shown), four at most.
        val points = bundles(x, POINTS).mapNotNull { b ->
            val position = integer(b, "position") ?: return@mapNotNull null
            if (max == null || position <= 0 || position >= max) null else NavFace.Point(position, ink(integer(b, "colorInt") ?: 0))
        }.take(MOST_POINTS)
        fun picture(k: String): Bitmap? = parcel<Icon>(x, k)?.let { runCatching { loadIcon(it) }.getOrNull() }
        val short = words(x, SHORT)
        val title = words(x, Notification.EXTRA_TITLE)
        val text = words(x, Notification.EXTRA_TEXT).ifEmpty { words(x, Notification.EXTRA_BIG_TEXT) }
        val eta = words(x, Notification.EXTRA_SUB_TEXT)
        @Suppress("DEPRECATION")
        val maneuver = runCatching { n.getLargeIcon()?.let(loadIcon) }.getOrNull() ?: parcel<Bitmap>(x, Notification.EXTRA_LARGE_ICON)
        // A launcher icon alone supplies no route guidance. An ETA or a readable maneuver can stand on its own.
        if (short.isEmpty() && title.isEmpty() && text.isEmpty() && eta.isEmpty() &&
            segments.isEmpty() && fraction == null && maneuver == null) return null
        return NavFace.Model(
            key = key, pkg = pkg,
            short = short, title = title, text = text,
            eta = eta,
            fraction = fraction, progressMax = max, segments = segments, points = points,
            styledByProgress = boolean(x, STYLED, true),
            appIcon = appIcon, maneuver = maneuver, tracker = picture(TRACKER), startIcon = picture(START), endIcon = picture(END),
        )
    }

    /**
     * What the shell is sent ("kind" = [KIND]); with no route, the kind alone (the route has ended). The pictures as
     * PNG bytes through [png] (the relay's [Art] keeps the last of each so an unchanged one is not encoded again).
     */
    fun toBundle(m: NavFace.Model?, png: (String, Bitmap) -> ByteArray? = { _, b -> encode(b) }): Bundle = Bundle().apply {
        putString("kind", KIND)
        if (m == null) return@apply
        putString("key", m.key); putString("pkg", m.pkg)
        putString("short", m.short); putString("title", m.title); putString("text", m.text); putString("eta", m.eta)
        m.fraction?.let { putFloat("fraction", it) }; m.progressMax?.let { putInt("max", it) }
        putIntArray("lengths", m.segments.map { it.length }.toIntArray())
        putIntArray("segmentColors", m.segments.map { it.color }.toIntArray())
        putIntArray("positions", m.points.map { it.position }.toIntArray())
        putIntArray("pointColors", m.points.map { it.color }.toIntArray())
        putBoolean("styled", m.styledByProgress); putBoolean("demo", m.demo)
        for ((name, bitmap) in listOf("appIcon" to m.appIcon, "maneuver" to m.maneuver, "tracker" to m.tracker, "startIcon" to m.startIcon, "endIcon" to m.endIcon)) {
            bitmap?.let { png(name, it) }?.let { putByteArray(name, it) }
        }
    }

    /**
     * The route as the shell is told it. [images]: the pictures decoded last time, kept by their bytes, so the same arrow
     * sent again with the route's next step is not decoded again (the same Bitmap: an unchanged page compares equal).
     */
    fun fromBundle(b: Bundle, images: Images? = null): NavFace.Model? {
        if (b.getString("kind") != KIND) return null
        val key = b.getString("key")?.takeIf { it.isNotEmpty() } ?: return null
        val lengths = b.getIntArray("lengths") ?: intArrayOf(); val colors = b.getIntArray("segmentColors") ?: intArrayOf()
        val positions = b.getIntArray("positions") ?: intArrayOf(); val pointColors = b.getIntArray("pointColors") ?: intArrayOf()
        fun image(name: String): Bitmap? = b.getByteArray(name).let { bytes -> if (images != null) images.get(name, bytes) else bytes?.let { decode(it) } }
        return NavFace.Model(
            key = key, pkg = b.getString("pkg").orEmpty(),
            short = b.getString("short").orEmpty(), title = b.getString("title").orEmpty(), text = b.getString("text").orEmpty(),
            eta = b.getString("eta").orEmpty(),
            fraction = if (b.containsKey("fraction")) b.getFloat("fraction").takeIf { it.isFinite() && it in 0f..1f } else null,
            progressMax = b.getInt("max").takeIf { it > 0 },
            segments = if (lengths.size == colors.size) lengths.indices.map { NavFace.Segment(lengths[it], colors[it]) } else emptyList(),
            points = if (positions.size == pointColors.size) positions.indices.map { NavFace.Point(positions[it], pointColors[it]) } else emptyList(),
            styledByProgress = b.getBoolean("styled", true),
            appIcon = image("appIcon"), maneuver = image("maneuver"), tracker = image("tracker"), startIcon = image("startIcon"), endIcon = image("endIcon"),
            demo = b.getBoolean("demo"),
        )
    }

    private fun decode(bytes: ByteArray): Bitmap? = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

    /** The shell's side of [Art]: per picture slot, the last bytes and what they decoded to. */
    class Images {
        private val last = HashMap<String, Pair<ByteArray, Bitmap?>>()

        @Synchronized
        fun get(slot: String, bytes: ByteArray?): Bitmap? {
            if (bytes == null) { last.remove(slot); return null }
            last[slot]?.let { (b, bitmap) -> if (b.contentEquals(bytes)) return bitmap }
            return decode(bytes).also { last[slot] = bytes to it }
        }
    }

    /** The same packet as [a] (pictures by their bytes): not sent again, and the page not drawn again for nothing. */
    @Suppress("DEPRECATION")
    fun samePacket(a: Bundle?, b: Bundle): Boolean {
        if (a == null || a.keySet() != b.keySet()) return false
        return a.keySet().all { key ->
            val left = a.get(key); val right = b.get(key)
            when {
                left is ByteArray && right is ByteArray -> left.contentEquals(right)
                left is IntArray && right is IntArray -> left.contentEquals(right)
                else -> left == right
            }
        }
    }

    /**
     * The pictures' PNG bytes, one slot per picture ("maneuver", "tracker", …): an app posts its notification again for
     * every step of the route with the same arrow drawn afresh, so a picture whose pixels are those of the slot's last
     * one is not encoded again.
     */
    class Art {
        private data class Picture(val width: Int, val height: Int, val pixels: IntArray, val png: ByteArray)
        private val last = HashMap<String, Picture>()

        fun png(slot: String, bitmap: Bitmap): ByteArray? {
            val small = fit(bitmap) ?: return null
            val width = small.width
            val height = small.height
            val pixels = runCatching {
                IntArray(width * height).also { small.getPixels(it, 0, width, 0, 0, width, height) }
            }.getOrNull() ?: return encode(small)
            val previous = last[slot]
            if (previous != null && width == previous.width && height == previous.height &&
                pixels.contentEquals(previous.pixels)) return previous.png
            return encode(small)?.also { last[slot] = Picture(width, height, pixels, it) }
        }

        fun clear() = last.clear()
    }

    /**
     * Keep the selected usable route while it goes on. When it ends, pick the first usable alternative by key.
     * A notification that contains only its app icon, or whose route has been cleared, has no candidate.
     */
    class Routes {
        private val models = HashMap<String, NavFace.Model>()
        var selected: String? = null
            private set

        fun replace(all: List<NavFace.Model>) {
            models.clear()
            for (model in all) models[model.key] = model
            choose()
        }

        fun posted(key: String, model: NavFace.Model?) {
            if (model == null) models.remove(key) else models[key] = model
            choose()
        }

        fun contains(key: String): Boolean = key in models

        fun current(): NavFace.Model? = models[selected]

        private fun choose() {
            if (selected !in models) selected = models.keys.minOrNull()
        }
    }

    /** Only fixed framework field names and presence/counts, even if an app supplies arbitrary extra names. */
    fun shape(n: Notification): String {
        val x = n.extras ?: Bundle.EMPTY
        val names = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT, SHORT, Notification.EXTRA_PROGRESS, Notification.EXTRA_PROGRESS_MAX,
            Notification.EXTRA_PROGRESS_INDETERMINATE, SEGMENTS, POINTS, STYLED, TRACKER, START, END)
            .filter { key -> runCatching { x.containsKey(key) }.getOrDefault(false) }.joinToString(" ")
        return "extras present: $names; segments ${bundles(x, SEGMENTS).size}, points ${bundles(x, POINTS).size}, " +
            "large icon ${n.getLargeIcon() != null}, tracker ${parcel<Icon>(x, TRACKER) != null}"
    }

    private fun words(b: Bundle, key: String): String = runCatching { b.getCharSequence(key)?.toString()?.trim().orEmpty() }.getOrDefault("")
    @Suppress("DEPRECATION")
    private fun integer(b: Bundle, key: String): Int? = runCatching { b.get(key) as? Int }.getOrNull()
    @Suppress("DEPRECATION")
    private fun boolean(b: Bundle, key: String, fallback: Boolean): Boolean = runCatching { b.get(key) as? Boolean ?: fallback }.getOrDefault(fallback)
    @Suppress("DEPRECATION")
    private inline fun <reified T> parcel(b: Bundle, key: String): T? = runCatching { b.get(key) as? T }.getOrNull()
    @Suppress("DEPRECATION")
    private fun bundles(b: Bundle, key: String): List<Bundle> = runCatching {
        val raw = b.get(key) as? ArrayList<*> ?: return@runCatching emptyList()
        if (raw.size > 100 || raw.any { it !is Bundle }) emptyList() else raw.filterIsInstance<Bundle>()
    }.getOrDefault(emptyList())

    /** At most 256 pixels along its longer side: what the back's page needs of an icon, and small across the binder. */
    private fun fit(bitmap: Bitmap): Bitmap? = runCatching {
        if (bitmap.isRecycled) return null
        val scale = minOf(1f, 256f / maxOf(bitmap.width, bitmap.height))
        if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
    }.getOrNull()

    private fun encode(bitmap: Bitmap): ByteArray? = runCatching {
        val small = fit(bitmap) ?: return null
        ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()
}
