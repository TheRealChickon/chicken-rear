package dev.kb896.aiusage.rear

/**
 * WHAT A XIAOMI REAR FACE NEEDS, read from its MAML manifest (`manifest.xml` inside the `.mrc`, a plain zip), so the
 * face list (FaceCatalog) can leave out what the back cannot draw or feed truly. Only the manifest is read, never the
 * whole file. A light tag scanner, not a full XML parser: Xiaomi's manifests are large and the census only needs tag
 * names and a few attributes.
 *
 * Pure: the manifest's text in, a [Census] out.
 */
object MamlCensus {
    enum class Kind {
        /** `PagView` / `PagImage`: libpag on a TextureView, hardware only. */
        PAG,
        /** A `Video` whose picture is the theme's own (its assets). */
        THEME_VIDEO,
        /** A `Video` bound to his own video (`@videoUri…`, `customVideo`): a template that needs his content. */
        USER_VIDEO,
        LOTTIE,
        /** `BlurContainer`: RenderEffect, hardware only. */
        BLUR,
        /** `ShaderElement`, `GLLayer`, `mgl`. */
        GL,
        /** `PhysicallyBasedRenderingElement`: Filament, which is missing from the APK. */
        PBR,
    }

    data class Census(
        val kinds: Set<Kind> = emptySet(),
        /** The authorities of every `ContentProviderBinder`. */
        val providers: Set<String> = emptySet(),
        val sensors: Boolean = false,
        /** The actions of every `BroadcastBinder`. */
        val broadcasts: Set<String> = emptySet(),
        val files: Boolean = false,
        val settings: Boolean = false,
        /** `useVariableUpdater`, each name as written (`DateTime.Minute`, `HyperMaterial`, `Battery`). */
        val updaters: Set<String> = emptySet(),
        /** The engine's own variables it reads ([BUILT_INS]), the ones it declares itself left out. */
        val variables: Set<String> = emptySet(),
        /** `frameRate` of its root: 0 draws nothing between changes. Null when not given. */
        val frameRate: Int? = null,
    )

    /** The engine's variables a face may read that the back has to feed (MamlFeed) or cannot. */
    val BUILT_INS = listOf(
        "battery_level", "battery_state", "call_missed_count", "sms_unread_count", "next_alarm_time", "__darkmode",
        "steps_count", "steps_goal", "steps_value",
    )

    /**
     * Of [BUILT_INS], those the back gives truly (MamlFeed): the battery's level and dark mode. Not `battery_state`:
     * Xiaomi's rear launcher sets it nowhere (no line of subscreencenter writes it), so a face reading it is left out
     * rather than fed a value its own phone never gives it.
     */
    val FED = setOf("battery_level", "__darkmode")

    private val TAG = Regex("""<([A-Za-z_][\w.]*)((?:[^>"']|"[^"]*"|'[^']*')*)>""")
    private val ATTR = Regex("""([\w:.]+)\s*=\s*("([^"]*)"|'([^']*)')""")
    private val AUTHORITY = Regex("""content://([\w.\-]+)""")
    private val VAR_REF = Regex("""#([A-Za-z_][\w]*)""")

    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    fun of(text: String): Census {
        val manifest = text.replace(COMMENT, "")
        val kinds = HashSet<Kind>()
        val providers = LinkedHashSet<String>()
        val broadcasts = LinkedHashSet<String>()
        val updaters = LinkedHashSet<String>()
        val declared = HashSet<String>()
        var sensors = false; var files = false; var settings = false
        var frameRate: Int? = null
        var root = true
        /** Videos and the VideoCommands that configure them: by name, whether his own. */
        val videos = HashMap<String, Boolean>()
        val videoUser = HashSet<String>()
        var anonymousVideos = 0
        for (m in TAG.findAll(manifest)) {
            val tag = m.groupValues[1]
            val attrs = attrs(m.groupValues[2])
            if (root && tag != "?xml" && !tag.startsWith("!")) {
                root = false
                attrs["frameRate"]?.toDoubleOrNull()?.let { frameRate = it.toInt() }
                attrs["useVariableUpdater"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { updaters += it }
            } else attrs["useVariableUpdater"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { updaters += it }
            when (tag) {
                "PagView", "PagImage" -> kinds += Kind.PAG
                "Lottie", "LottieView", "LottieImage" -> kinds += Kind.LOTTIE
                "BlurContainer" -> kinds += Kind.BLUR
                "ShaderElement", "GLLayer", "mgl", "Mgl", "MglElement" -> kinds += Kind.GL
                "PhysicallyBasedRenderingElement" -> kinds += Kind.PBR
                "Video" -> {
                    val name = attrs["name"]
                    val user = isUserVideo(attrs["path"]) || isUserVideo(attrs["src"]) || isUserVideo(attrs["srcExp"]) || name?.contains("custom", ignoreCase = true) == true
                    if (name != null) videos[name] = user else { anonymousVideos++; if (user) kinds += Kind.USER_VIDEO else kinds += Kind.THEME_VIDEO }
                }
                "VideoCommand" -> {
                    val target = attrs["target"]
                    if (target != null && attrs["command"] == "config" && isUserVideo(attrs["path"])) videoUser += target
                }
                "ContentProviderBinder" -> {
                    val uri = attrs["uri"] ?: attrs["uriExp"] ?: ""
                    providers += AUTHORITY.find(uri)?.groupValues?.get(1) ?: uri.trim('\'', ' ').ifEmpty { "?" }
                }
                "SensorBinder" -> sensors = true
                "BroadcastBinder" -> attrs["action"]?.let { broadcasts += it }
                "FileBinder" -> files = true
                "SettingsBinder" -> settings = true
                "Var", "Variable", "NumberVar", "StringVar" -> attrs["name"]?.let { declared += it }
            }
        }
        for ((name, user) in videos) kinds += if (user || name in videoUser) Kind.USER_VIDEO else Kind.THEME_VIDEO
        val reads = VAR_REF.findAll(manifest).map { it.groupValues[1] }.toSet()
        val builtIns = BUILT_INS.filter { it in reads && it !in declared }.toSet()
        return Census(kinds, providers, sensors, broadcasts, files, settings, updaters, builtIns, frameRate)
    }

    private fun isUserVideo(v: String?): Boolean = v != null && (v.contains("videoUri", ignoreCase = true) || v.contains("customVideo", ignoreCase = true))

    private fun attrs(s: String): Map<String, String> {
        if (s.isBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (a in ATTR.findAll(s)) out[a.groupValues[1]] = a.groupValues[3].ifEmpty { a.groupValues[4] }
        return out
    }

    /** What of [c] the back cannot read truly; empty when nothing. NEVER a made-up figure on the back. */
    fun unreadable(c: Census): List<String> = buildList {
        for (p in c.providers) if (p != "settings") add("needs $p")
        if (c.sensors) add("needs a sensor")
        for (v in c.variables) if (v !in FED) add("needs #$v")
    }
}
