package dev.kb896.aiusage.rear

object FaceCatalog {
    enum class Source { OURS, PREINSTALLED, DOWNLOADED }

    const val MOON = "moon"
    /** Dust in Light (DustField): our own, drawn by the scene's GL; no file. */
    const val DUST = "dust"
    const val DUST_TITLE = "Dust in Light"
    /** A face of ours (not one of Xiaomi's): always offered, never a file of his. */
    fun ours(key: String?): Boolean = key == MOON || key == DUST
    fun keyOf(id: String) = "xiaomi:$id"
    fun idOf(key: String): String? = key.removePrefix("xiaomi:").takeIf { key.startsWith("xiaomi:") }

    /** What a `.mrm` says (JSON): the face's id, its content's sha1 and size, titles by locale, group, parent theme. */
    data class Meta(
        val id: String,
        val hash: String?,
        val size: Long,
        val titles: Map<String, String>,
        val group: String?,
        val editable: Boolean,
        val parentId: String?,
        /** When the `.mrm` was written (ms): a download's time. */
        val mtime: Long = 0L,
    )

    /** A face's file on the phone: a `.mrc`, with its size as it is now (-1: not there). */
    data class Content(val path: String, val size: Long)

    /** One face as found on the phone, before the rules. */
    data class Found(
        val id: String,
        val source: Source,
        val meta: Meta?,
        /** The original `.mrc` (preinstalled or downloaded). */
        val content: Content?,
        /** His applied copy (`/data/system/theme/rearScreen/rearscreen_<id>_<applyId>.mrc`), if he has applied it. */
        val applied: Content? = null,
        /** His `editConfig`, if he has applied it. */
        val editConfig: String? = null,
        /** Xiaomi's own picture of the face (SPEC 2.4). */
        val card: String? = null,
    )

    /** An item of Xiaomi's catalog (`rearScreen.json` `products[].items[]`), in its order. */
    data class Item(val id: String, val group: String, val nfc: Boolean)

    /** What was learned of a face's content once (FaceScan's index, by path + size + mtime). */
    data class Checked(
        /** The content's sha1 as computed; null while not computed yet. */
        val sha1: String? = null,
        val census: MamlCensus.Census? = null,
    )

    data class Input(
        val found: List<Found>,
        /** Xiaomi's catalog, in its order. */
        val catalog: List<Item>,
        /** His rear list (`widget.json`), ids in Xiaomi's order. */
        val rearList: List<String>,
        /** By the content path that was read. */
        val checked: Map<String, Checked>,
        /** What the renderer can draw now (MamlHost). */
        val abilities: Set<MamlCensus.Kind>,
        /** By sha1: faces that failed to load or draw in this shell process, with why. */
        val failed: Map<String, String> = emptyMap(),
        /** By sha1: faces whose first frame was compared with Xiaomi's picture (score, 0 to 1). */
        val faithful: Map<String, Double> = emptyMap(),
        /** Our own picture of the moon (a frame of the clip), if made. */
        val moonCard: String? = null,
        /** Our own picture of the dust (drawn by the face's own formulas, DustField.render), if made. */
        val dustCard: String? = null,
    )

    data class Face(
        val key: String,
        val source: Source,
        val titles: Map<String, String>,
        val group: String?,
        /** What to draw: the original, else his applied copy. Null for ours. */
        val content: String?,
        val sha1: String?,
        val size: Long,
        val card: String?,
        val editConfig: String?,
        val editable: Boolean,
        val caseOnly: Boolean,
        val census: MamlCensus.Census?,
        /** Null: offered. Else the one reason it is left out. */
        val leftOut: String? = null,
        /** Left out only for now (a download still landing, a sha1 not yet computed): looked at again next scan. */
        val pending: Boolean = false,
    ) {
        val offered get() = leftOut == null
        val title: String get() = title(titles, key)
        val id8: String get() = (idOf(key) ?: key).take(8)
    }

    /** Below this, a face's first frame does not look like Xiaomi's own picture of it (Faithful). NOT CALIBRATED on the phone. */
    const val FAITHFUL_AT = 0.55

    fun title(titles: Map<String, String>, key: String): String =
        titles["en_US"] ?: titles["fallback"] ?: titles["zh_CN"] ?: titles.values.firstOrNull() ?: when (key) { MOON -> "Moon"; DUST -> DUST_TITLE; else -> (idOf(key) ?: key).take(8) }

    private val TEMPLATE_GROUPS = setOf("signature")
    private const val CASE_GROUP = "nfc"

    fun build(input: Input): List<Face> {
        val catalog = input.catalog.associateBy { it.id }
        val byId = input.found.associateBy { it.id }
        // THE ORDER (SPEC 3.5): ours; his rear list in Xiaomi's order; the rest of Xiaomi's catalog; preinstalled faces
        // not in it; downloads not in his list, newest first.
        val order = ArrayList<Found>()
        val placed = HashSet<String>()
        fun put(f: Found?) { if (f != null && placed.add(f.id)) order += f }
        for (id in input.rearList) put(byId[id])
        for (item in input.catalog) put(byId[item.id])
        for (f in input.found.filter { it.source == Source.PREINSTALLED }.sortedBy { it.id }) put(f)
        for (f in input.found.filter { it.source == Source.DOWNLOADED }.sortedByDescending { it.meta?.mtime ?: 0L }) put(f)

        val out = ArrayList<Face>(order.size + 2)
        out += Face(MOON, Source.OURS, mapOf("en_US" to "Moon"), null, null, null, 0L, input.moonCard, null, false, false, null)
        out += dust(input.dustCard)
        val seenSha = HashSet<String>()
        for (f in order) out += judge(f, catalog[f.id], input, seenSha)
        return out
    }

    private fun judge(f: Found, item: Item?, input: Input, seenSha: MutableSet<String>): Face {
        val meta = f.meta
        // What is drawn: the original, else his applied copy (a download he deleted after applying it).
        val original = f.content?.takeIf { it.size >= 0 }
        val use = original ?: f.applied?.takeIf { it.size >= 0 }
        val checked = use?.let { input.checked[it.path] }
        val sha1 = checked?.sha1 ?: meta?.hash
        val group = meta?.group ?: item?.group
        val caseOnly = item?.nfc == true || group == CASE_GROUP
        fun face(leftOut: String?, pending: Boolean = false) = Face(
            keyOf(f.id), f.source, meta?.titles.orEmpty(), group, use?.path, sha1, use?.size ?: -1L, f.card, f.editConfig,
            meta?.editable == true, caseOnly, checked?.census, leftOut, pending,
        )
        // 1. The same content twice: the first in the list's order stays.
        if (sha1 != null && !seenSha.add(sha1.lowercase())) return face("the same content as a face before it")
        // 2. Not ready: a download still landing (its .mrm is written last), or a face whose file is gone.
        if (use == null) return face(if (f.source == Source.DOWNLOADED) "not on the phone (yet)" else "its file is not there", pending = f.source == Source.DOWNLOADED)
        if (meta == null) return face("no .mrm (yet)", pending = true)
        if (use === original && f.source == Source.DOWNLOADED && meta.size > 0 && use.size != meta.size) return face("still landing (${use.size} of ${meta.size} bytes)", pending = true)
        // 3. The content against its .mrm's hash, once per file (FaceScan's index).
        if (checked?.sha1 == null) return face("not checked yet", pending = true)
        if (use === original && meta.hash != null && !checked.sha1.equals(meta.hash, ignoreCase = true)) return face("the file is not Xiaomi's as listed")
        // 4. Only with its case on, as on Xiaomi (Ask him, 6).
        if (caseOnly) return face("only with its case on")
        val census = checked.census ?: return face("its manifest could not be read")
        // 5. A template that needs his own picture or video, until he has made one in Xiaomi's editor (Ask him, 4).
        val template = MamlCensus.Kind.USER_VIDEO in census.kinds || group in TEMPLATE_GROUPS
        if (template && f.editConfig == null) return face("a template: needs his own picture or video in Xiaomi's editor")
        // 6. Data the back cannot read truly. NEVER a made-up figure on the back.
        MamlCensus.unreadable(census).firstOrNull()?.let { return face("data the back cannot read ($it)") }
        // 7. What the renderer cannot draw (MamlHost.abilities): offered by itself once it can.
        val cannot = census.kinds.filter { it != MamlCensus.Kind.USER_VIDEO || f.editConfig != null }
            .map { if (it == MamlCensus.Kind.USER_VIDEO) MamlCensus.Kind.THEME_VIDEO else it }
            .filter { it !in input.abilities }
        if (cannot.isNotEmpty()) return face("the renderer cannot draw ${cannot.joinToString { it.name.lowercase().replace('_', ' ') }}")
        // 8. Failed to load or draw in this process.
        input.failed[checked.sha1.lowercase()]?.let { return face("failed: $it") }
        // 9. Not faithful: its first frame does not look like Xiaomi's picture of it (a heuristic, Faithful).
        // Not drawn once yet: it is drawn offscreen and compared before it is offered (MamlHost.measure), once per sha1.
        val score = input.faithful[checked.sha1.lowercase()] ?: return face("not drawn yet", pending = true)
        if (score < FAITHFUL_AT) return face("does not look like Xiaomi's picture of it (%.2f)".format(java.util.Locale.ROOT, score))
        return face(null)
    }

    /** Dust in Light's entry ([DUST]), with its card if made. */
    fun dust(card: String? = null) = Face(DUST, Source.OURS, mapOf("en_US" to DUST_TITLE), null, null, null, 0L, card, null, false, false, null)

    /** The face to show for [key]: it, if offered; else the moon. */
    fun resolve(list: List<Face>, key: String?): Face =
        list.firstOrNull { it.key == key && it.offered } ?: list.first { it.key == MOON }

    /** What the picker and the settings offer, in order. */
    fun offered(list: List<Face>): List<Face> = list.filter { it.offered }

    /** Why a face is out when a native crash in it took the last shell process down (rule 8). */
    const val CRASHED = "it took the shell down"

    /**
     * WHICH CHOICE IS THE LATEST (PanelConfig.faceSeq): the phone's clock in milliseconds at the choice, never below the
     * last one plus one. The shell (a tap in the picker) and the app (a tap in the settings) each number their own; a
     * count kept on each side drifted apart (the shell's copy outlives the app's data and a reinstall, and the app is not
     * always there to be told), and a settings tap with a lower number than the shell's was dropped while the settings
     * showed it made. Both sides read the same clock, so the later tap wins wherever it was made.
     */
    fun nextSeq(last: Long, nowMs: Long): Long = maxOf(nowMs, last + 1)

    /** What the shell does with his choice after a scan ([afterScan]). */
    enum class AfterScan {
        /** Nothing changes. */
        KEEP,
        /** It is offered again (or for the first time in this process): the back shows it now. */
        SHOW_IT,
        /** It is out for now (failed in this process, not drawn yet): the moon shows, HIS CHOICE IS KEPT for when it is back. */
        SHOW_MOON,
        /** It is gone from the phone, or crashed the shell: the choice becomes the moon. */
        BACK_TO_MOON,
    }

    /**
     * His choice [chosen] against the list [now] (and the one before, [was]). A face that failed once in this process (a
     * slow first frame on a loaded phone, the engine's thread late) is out until the shell starts again, and the moon
     * shows meanwhile, but the choice stays his: it used to be written over with the moon for good. Only a face that is
     * gone from the phone, or took the shell down (it would again), takes the choice back to the moon.
     */
    fun afterScan(chosen: String, now: List<Face>, was: List<Face>): AfterScan {
        if (ours(chosen)) return AfterScan.KEEP
        val f = now.firstOrNull { it.key == chosen } ?: return AfterScan.BACK_TO_MOON
        if (f.leftOut == "failed: $CRASHED") return AfterScan.BACK_TO_MOON
        val wasOffered = was.any { it.key == chosen && it.offered }
        return when {
            f.offered && !wasOffered -> AfterScan.SHOW_IT
            !f.offered && wasOffered -> AfterScan.SHOW_MOON
            else -> AfterScan.KEEP
        }
    }
}
