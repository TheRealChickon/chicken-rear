package dev.kb896.aiusage.rear

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * THE FACES ON THE PHONE, KEPT IN STEP BY THEMSELVES (SPEC 3.6). The theme manager sends no broadcast for a download and
 * its providers are signature-only, so the shell reads the files where they lie, read-only: Xiaomi's catalog, the
 * preinstalled faces, his Theme Store downloads (shell only may read those), his rear list. A scan stats the folders,
 * reads again only what changed (an [Index] by path + size + mtime, with each content's sha1 and census, kept in
 * [indexFile]), and builds the list (FaceCatalog). Nothing of Xiaomi's is ever written or copied.
 *
 * WHEN (the shell's half, [Schedule]): as the shell starts, as the picker opens, as the back lights, and when a watch
 * says a folder changed (2 s debounce); and, because inotify through FUSE is NOT PROVEN to see the theme manager
 * writing its own Android/data, a stat of the download folders every 5 minutes WHILE THE BACK IS LIT. Nothing while dark.
 *
 * JVM only (java.io, zip, sha1): its tests run it on folders made for them.
 */
class FaceScan(
    private val paths: Paths = Paths(),
    private val indexFile: File? = null,
    /** How a file's bytes are read (a test counts them). */
    private val reader: Reader = Reader(),
) {
    data class Paths(
        val preinstalled: String = "/product/etc/precust_theme/theme/.data",
        val downloads: String = "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/theme/.data",
        val catalog: String = "/system/media/rearscreen/template/normal/rearScreen.json",
        val widgets: String = "/data/system/theme_magic/users/0/subscreencenter/config/widget.json",
        val runtime: String = "/data/system/theme_magic/users/0/rearScreen/runtime.json",
    ) {
        /** The folders whose change means a new scan: his list's, the applied copies', the downloads'. */
        val watched: List<String> get() = listOf(
            File(runtime).parent ?: "", File(widgets).parent ?: "", "/data/system/theme/rearScreen",
            "$downloads/content/rearscreen", "$downloads/meta/rearscreen",
        )
        /** Polled while lit (FUSE: the watch may never fire for the theme manager's own writes). */
        val polled: List<String> get() = listOf("$downloads/content/rearscreen", "$downloads/meta/rearscreen")
    }

    /** Every read of a file's bytes goes through here. */
    open class Reader {
        open fun text(f: File): String = f.readText()
        open fun manifest(f: File): String? = java.util.zip.ZipFile(f).use { z ->
            val e = z.getEntry("manifest.xml") ?: return null
            z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        open fun sha1(f: File): String {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    @Serializable
    data class Entry(
        val size: Long, val mtime: Long, val sha1: String? = null,
        val kinds: List<String> = emptyList(), val providers: List<String> = emptyList(), val sensors: Boolean = false,
        val broadcasts: List<String> = emptyList(), val files: Boolean = false, val settings: Boolean = false,
        val updaters: List<String> = emptyList(), val variables: List<String> = emptyList(), val frameRate: Int? = null,
        val censused: Boolean = false,
    ) {
        fun census(): MamlCensus.Census? = if (!censused) null else MamlCensus.Census(
            kinds.mapNotNull { k -> MamlCensus.Kind.values().firstOrNull { it.name == k } }.toSet(), providers.toSet(), sensors,
            broadcasts.toSet(), files, settings, updaters.toSet(), variables.toSet(), frameRate,
        )
        companion object {
            fun of(size: Long, mtime: Long, sha1: String?, c: MamlCensus.Census?) = Entry(
                size, mtime, sha1, c?.kinds?.map { it.name }.orEmpty().sorted(), c?.providers?.toList().orEmpty(), c?.sensors == true,
                c?.broadcasts?.toList().orEmpty(), c?.files == true, c?.settings == true, c?.updaters?.toList().orEmpty(),
                c?.variables?.toList().orEmpty(), c?.frameRate, c != null,
            )
        }
    }

    @Serializable
    data class Index(val contents: Map<String, Entry> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var index: Index = runCatching { indexFile?.takeIf { it.isFile }?.let { json.decodeFromString(Index.serializer(), it.readText()) } }.getOrNull() ?: Index()
    /** Parsed JSON files by path, with the size and mtime they were read at. */
    private val parsed = HashMap<String, Triple<Long, Long, Any?>>()
    /** How many files' bytes the last scan read (the log; a test). */
    var lastReads = 0
        private set

    private fun <T> cached(f: File, parse: (String) -> T): T? {
        if (!f.isFile) { parsed.remove(f.path); return null }
        val size = f.length(); val mtime = f.lastModified()
        parsed[f.path]?.let { (s, m, v) -> if (s == size && m == mtime) { @Suppress("UNCHECKED_CAST") return v as T? } }
        lastReads++
        val v = runCatching { parse(reader.text(f)) }.getOrNull()
        parsed[f.path] = Triple(size, mtime, v)
        return v
    }

    /**
     * One scan: the list as the files say now. [sha1] false: no content is hashed in this scan (the picker opening:
     * it must not wait on a new 87 MB download), and what is not checked yet stays pending.
     */
    fun scan(
        abilities: Set<MamlCensus.Kind>,
        failed: Map<String, String> = emptyMap(),
        faithful: Map<String, Double> = emptyMap(),
        moonCard: String? = null,
        sha1: Boolean = true,
        dustCard: String? = null,
    ): List<FaceCatalog.Face> {
        lastReads = 0
        val catalog = cached(File(paths.catalog), ::catalogOf).orEmpty()
        val rear = cached(File(paths.widgets), ::widgetsOf) ?: cached(File(paths.runtime), ::runtimeOf).orEmpty()
        val found = LinkedHashMap<String, FaceCatalog.Found>()
        fun ids(dir: String, ext: String) = File(dir).list().orEmpty().filter { it.endsWith(ext) }.map { it.removeSuffix(ext) }
        // Preinstalled.
        for (id in (ids("${paths.preinstalled}/meta/rearscreen", ".mrm") + ids("${paths.preinstalled}/content/rearscreen", ".mrc")).distinct().sorted()) {
            val meta = cached(File("${paths.preinstalled}/meta/rearscreen/$id.mrm")) { metaOf(it, 0L) }
            val c = File("${paths.preinstalled}/content/rearscreen/$id.mrc")
            found[id] = FaceCatalog.Found(id, FaceCatalog.Source.PREINSTALLED, meta, FaceCatalog.Content(c.path, if (c.isFile) c.length() else -1L),
                card = meta?.parentId?.let { "${paths.preinstalled}/preview/theme/$it/preview_rearscreen_0.png" })
        }
        // His downloads (a preinstalled face's .mrm copied there when he applied it is the preinstalled one).
        for (id in (ids("${paths.downloads}/meta/rearscreen", ".mrm") + ids("${paths.downloads}/content/rearscreen", ".mrc")).distinct().sorted()) {
            if (id in found) continue
            val mf = File("${paths.downloads}/meta/rearscreen/$id.mrm")
            val meta = cached(mf) { metaOf(it, mf.lastModified()) }
            val c = File("${paths.downloads}/content/rearscreen/$id.mrc")
            found[id] = FaceCatalog.Found(id, FaceCatalog.Source.DOWNLOADED, meta, FaceCatalog.Content(c.path, if (c.isFile) c.length() else -1L),
                card = "${paths.downloads}/preview/theme/$id/preview_rearscreen_popsicle_0.jpg")
        }
        // His rear list: his edit, Xiaomi's picture of his edit, his applied copy (kept when the download is gone).
        for (w in rear) {
            val base = found[w.id]
            val applied = w.path?.takeIf { it.startsWith("/data/system/theme/") }?.let { File(it) }?.let { FaceCatalog.Content(it.path, if (it.isFile) it.length() else -1L) }
            val edit = w.configPath?.takeIf { File(it).isFile }
            val snap = w.snapshot?.takeIf { File(it).isFile }
            found[w.id] = if (base != null) base.copy(applied = applied, editConfig = edit, card = snap ?: base.card)
            else FaceCatalog.Found(w.id, FaceCatalog.Source.DOWNLOADED, w.meta?.let { m -> cached(File(m)) { metaOf(it, 0L) } }, null, applied, edit, snap)
        }
        // What each content is, read once per path + size + mtime.
        val checked = HashMap<String, FaceCatalog.Checked>()
        val next = HashMap<String, Entry>()
        for (f in found.values) for (c in listOfNotNull(f.content, f.applied)) {
            val file = File(c.path)
            if (!file.isFile) continue
            val size = file.length(); val mtime = file.lastModified()
            var e = index.contents[c.path]?.takeIf { it.size == size && it.mtime == mtime }
            // Not ready (a download still landing) is not read at all: its .mrm is written last.
            val ready = f.source != FaceCatalog.Source.DOWNLOADED || c !== f.content || f.meta != null && (f.meta.size <= 0 || f.meta.size == size)
            if (e == null && ready) e = Entry.of(size, mtime, null, runCatching { reader.manifest(file) }.getOrNull()?.let { lastReads++; MamlCensus.of(it) })
            if (e != null && e.sha1 == null && sha1 && ready) { lastReads++; e = e.copy(sha1 = runCatching { reader.sha1(file) }.getOrNull()) }
            if (e != null) { next[c.path] = e; checked[c.path] = FaceCatalog.Checked(e.sha1, e.census()) }
        }
        if (next != index.contents) {
            index = Index(next)
            runCatching { indexFile?.let { it.parentFile?.mkdirs(); it.writeText(json.encodeToString(Index.serializer(), index)) } }
        }
        return FaceCatalog.build(FaceCatalog.Input(found.values.toList(), catalog, rear.map { it.id }, checked, abilities, failed, faithful, moonCard, dustCard))
    }

    /** One entry of his rear list. */
    data class Widget(val id: String, val path: String?, val configPath: String?, val snapshot: String?, val meta: String?)

    companion object {
        private val parser = Json { ignoreUnknownKeys = true }
        private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.str(k: String) = this[k].str()

        /** `rearScreen.json`: `products[].items[]`, in their order. */
        fun catalogOf(text: String): List<FaceCatalog.Item> {
            val root = parser.parseToJsonElement(text) as? JsonObject ?: return emptyList()
            val out = ArrayList<FaceCatalog.Item>()
            for (p in (root["products"] as? JsonArray).orEmpty()) {
                val po = p as? JsonObject ?: continue
                val group = po.str("type") ?: ""
                for (i in (po["items"] as? JsonArray).orEmpty()) {
                    val io = i as? JsonObject ?: continue
                    val id = io.str("resId") ?: continue
                    out += FaceCatalog.Item(id, io.str("resType") ?: group, (io["isNFC"] as? JsonPrimitive)?.booleanOrNull == true || group == "nfc")
                }
            }
            return out
        }

        private fun idFromMeta(path: String?): String? = path?.substringAfterLast('/')?.removeSuffix(".mrm")?.takeIf { it.isNotEmpty() }

        /** `widget.json`: his rear list, in its order. */
        fun widgetsOf(text: String): List<Widget> = (parser.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val extra = o["extra"] as? JsonObject
            val meta = extra?.str("metaPath") ?: extra?.str("mrmSnapshotPath")
            val id = idFromMeta(meta) ?: return@mapNotNull null
            Widget(id, o.str("path"), o.str("configPath"), extra?.str("snapshotPath"), meta)
        }

        /** `runtime.json` (what Xiaomi seeds its list from when `widget.json` is missing): highest `position` first. */
        fun runtimeOf(text: String): List<Widget> = (parser.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val meta = o.str("metaPath") ?: o.str("metaSnapshotPath")
            val id = idFromMeta(meta) ?: return@mapNotNull null
            (o["position"] as? JsonPrimitive)?.longOrNull to Widget(id, o.str("resLocalPath"), o.str("mamlEditConfigPath"), o.str("snapshotPreviewPath"), meta)
        }.sortedByDescending { it.first ?: 0L }.map { it.second }

        /** A `.mrm`. */
        fun metaOf(text: String, mtime: Long): FaceCatalog.Meta? {
            val o = parser.parseToJsonElement(text) as? JsonObject ?: return null
            val id = o.str("localId") ?: return null
            val titles = (o["titles"] as? JsonObject)?.mapNotNull { (k, v) -> v.str()?.let { k to it } }?.toMap().orEmpty()
            val parent = ((o["parentResources"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("localId")
            return FaceCatalog.Meta(id, o.str("hash"), (o["size"] as? JsonPrimitive)?.longOrNull ?: -1L, titles, o.str("subResourceType"),
                (o["isRearScreenEditable"] as? JsonPrimitive)?.booleanOrNull == true, parent, mtime)
        }
    }

    /**
     * WHEN TO SCAN, the shell's clock kept out (SPEC 3.6): a watch's word waits [DEBOUNCE_MS] for more; the poll of the
     * download folders' mtimes runs every [POLL_MS] while the back is lit and never while it is dark.
     */
    class Schedule {
        private var watchAt = 0L
        private var lastPoll = -1L
        private var stamps: List<Long>? = null

        /** A watch said something changed at [now]. */
        fun watched(now: Long) { watchAt = now }

        /** Whether the debounce is over at [now] (and so the scan runs): once per burst of words. */
        fun debounced(now: Long): Boolean {
            if (watchAt == 0L || now - watchAt < DEBOUNCE_MS) return false
            watchAt = 0L
            return true
        }

        /** When the next look is due, or null for none (dark: nothing of ours ticks). */
        fun nextPoll(now: Long, lit: Boolean): Long? = if (!lit) null else if (lastPoll < 0) now else maxOf(now, lastPoll + POLL_MS)

        /**
         * The poll at [now]: [mtimes] the polled folders' mtimes. True when they moved since the last poll (a scan is
         * due). The first look only learns them, unless [lighting] (every light-up scans anyway).
         */
        fun polled(now: Long, mtimes: List<Long>): Boolean {
            lastPoll = now
            val was = stamps
            stamps = mtimes
            return was != null && was != mtimes
        }

        companion object {
            const val DEBOUNCE_MS = 2_000L
            const val POLL_MS = 5 * 60_000L
        }
    }
}
