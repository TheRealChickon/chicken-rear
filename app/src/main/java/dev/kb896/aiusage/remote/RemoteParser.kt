package dev.kb896.aiusage.remote

/**
 * The outside's words, turned into a [RemoteCommand] or a reason why not.
 *
 * ONE PARSER FOR BOTH DOORS. A broadcast arrives as a command name and a bag of extras; a link
 * arrives as `chickenrear://<command>?<query>`. Both become the same name and the same map of
 * strings here, so a command cannot mean one thing from Home Assistant and another from Tasker.
 *
 * EVERYTHING IS A STRING ON THE WAY IN, on purpose. Home Assistant types its extras for you —
 * `ring:1` arrives as an Integer, `wake:true` as a Boolean, `value:0.63` as a String — and Tasker
 * has rules of its own. Callers hand over `toString()` of whatever they got, and the typing is
 * done once, here, where a test can pin it.
 *
 * Pure: no Android, no clock. Text is bounded here because it ends up in a payload that crosses
 * three processes on every update, and on a shell command line when the panel is placed.
 */
object RemoteParser {

    sealed interface Result {
        data class Ok(val command: RemoteCommand) : Result
        /** [reason] is for the log and the caller: short, and never echoing what was sent. */
        data class Bad(val reason: String) : Result
    }

    /** A link's parts: the command, its arguments, and the token it carried (null for none). */
    data class Link(val cmd: String, val args: Map<String, String>, val token: String?)

    const val SCHEME = "chickenrear"

    /** The longest a card's or a tile's text may be; longer is cut, not refused. */
    const val MAX_TITLE = 80
    const val MAX_TEXT = 240
    const val MAX_LABEL = 16
    const val MAX_VALUE = 12
    const val MAX_ID = 40
    /** A settings patch or a usage push: generous for JSON, far short of what a payload can carry. */
    const val MAX_JSON = 8_000

    /**
     * `chickenrear://page?name=usage&token=…` taken apart, or null when it is not one of ours.
     * The command is the host, and a path is part of it (`chickenrear://app/open?…` is
     * `app.open`), so either spelling works in a tool that insists on one.
     */
    fun link(uri: String): Link? {
        val prefix = "$SCHEME://"
        if (!uri.startsWith(prefix, ignoreCase = true)) return null
        val rest = uri.substring(prefix.length).substringBefore('#')
        val head = rest.substringBefore('?').trim('/')
        if (head.isEmpty()) return null
        val args = LinkedHashMap<String, String>()
        val query = rest.substringAfter('?', "")
        if (query.isNotEmpty()) for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('='))
            if (key.isEmpty()) continue
            args[key] = decode(pair.substringAfter('=', ""))
        }
        val token = args.remove("token")
        return Link(head.replace('/', '.').lowercase(), args, token)
    }

    private fun decode(s: String): String =
        try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    fun parse(cmd: String?, args: Map<String, String>): Result {
        val name = cmd?.trim()?.lowercase().orEmpty()
        if (name.isEmpty()) return Result.Bad("no command")
        fun arg(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> args[k]?.takeIf { it.isNotEmpty() } }
        fun flag(key: String, otherwise: Boolean): Boolean = truth(args[key]) ?: otherwise
        fun switch(otherwise: Switch?): Switch? =
            switchOf(arg("to", "state", "value", "mode")) ?: otherwise

        return when (name) {
            "wake" -> ok(RemoteCommand.Wake(guard = flag("guard", true)))
            "sleep" -> ok(RemoteCommand.Sleep)
            "lock" -> ok(RemoteCommand.Lock)
            "aod", "always_on" -> switch(Switch.TOGGLE)?.let { ok(RemoteCommand.AlwaysOn(it, now = flag("now", false))) }
                ?: Result.Bad("aod: on, off or toggle")
            "keep_awake" -> switch(Switch.TOGGLE)?.let { ok(RemoteCommand.KeepAwake(it)) } ?: Result.Bad("keep_awake: on, off or toggle")
            "table_mode" -> switch(Switch.TOGGLE)?.let { ok(RemoteCommand.TableMode(it)) } ?: Result.Bad("table_mode: on, off or toggle")
            "brightness" -> {
                val auto = truth(args["auto"])
                val raw = arg("value", "level")
                val value = raw?.let { number(it)?.toInt() }
                when {
                    raw != null && value == null -> Result.Bad("brightness: value is 1 to 255")
                    value == null && auto == null -> Result.Bad("brightness: value or auto")
                    else -> ok(RemoteCommand.Brightness(value?.coerceIn(1, 255), auto))
                }
            }
            "page" -> PageName.fromWire(arg("name", "page", "to")?.lowercase())
                ?.let { ok(RemoteCommand.Page(it, wake = flag("wake", true))) }
                ?: Result.Bad("page: clock, usage, media, home, next or prev")

            "app.open" -> pkg(arg("package", "pkg", "packageName"))?.let { ok(RemoteCommand.AppOpen(it)) }
                ?: Result.Bad("app.open: a package name")
            "app.send_current" -> ok(RemoteCommand.AppSendCurrent)
            "app.close" -> ok(RemoteCommand.AppAction("close"))
            "app.to_front" -> ok(RemoteCommand.AppAction("front"))
            "app.hide" -> ok(RemoteCommand.AppAction("panel"))
            "app.resume" -> ok(RemoteCommand.AppAction("resume"))
            "camera" -> ok(RemoteCommand.Camera)
            // MRSS's own words, so a macro written for it needs only its scheme changed.
            "switch" -> {
                val p = arg("packageName", "package")
                when {
                    p != null -> pkg(p)?.let { ok(RemoteCommand.AppOpen(it)) } ?: Result.Bad("switch: a package name")
                    truth(args["current"]) == true -> ok(RemoteCommand.AppSendCurrent)
                    else -> Result.Bad("switch: packageName or current=1")
                }
            }
            "return" -> ok(RemoteCommand.AppAction("front"))

            "card.show", "card" -> {
                val title = arg("title").orEmpty().take(MAX_TITLE)
                val text = arg("text", "message").orEmpty().take(MAX_TEXT)
                if (title.isBlank() && text.isBlank()) Result.Bad("card.show: a title or a text")
                else ok(RemoteCommand.CardShow(
                    id = id(arg("id")) ?: "card",
                    title = title, text = text,
                    app = arg("app").orEmpty().take(MAX_LABEL * 2),
                    icon = arg("icon").orEmpty().take(MAX_TITLE),
                    wake = flag("wake", false),
                    seconds = (arg("seconds", "timeout")?.let { number(it)?.toInt() } ?: 0).coerceIn(0, 60),
                ))
            }
            "card.clear" -> ok(RemoteCommand.CardClear(id(arg("id")).orEmpty()))
            "card.clear_all" -> ok(RemoteCommand.CardClear(""))

            "tile.set", "tile" -> {
                val tile = id(arg("id"))
                val value = arg("value", "text").orEmpty().take(MAX_VALUE)
                val label = arg("label", "name").orEmpty().take(MAX_LABEL)
                val color = arg("color", "colour")
                val parsed = color?.let { colour(it) }
                when {
                    tile == null -> Result.Bad("tile.set: an id")
                    value.isBlank() && label.isBlank() -> Result.Bad("tile.set: a value or a label")
                    color != null && parsed == null -> Result.Bad("tile.set: color is #RRGGBB")
                    else -> ok(RemoteCommand.TileSet(tile, label, value, arg("icon").orEmpty().take(MAX_ID), parsed, flag("tap", false)))
                }
            }
            "tile.remove" -> id(arg("id"))?.let { ok(RemoteCommand.TileRemove(it)) } ?: Result.Bad("tile.remove: an id")
            "tile.clear" -> ok(RemoteCommand.TileRemove(""))

            "ring.set", "ring" -> {
                val ring = ringOf(arg("ring", "id"))
                val value = arg("value")?.let { fraction(it) }
                val ends = arg("ends", "ends_at")?.let { epochMs(it) } ?: 0L
                val starts = arg("starts", "starts_at")?.let { epochMs(it) } ?: 0L
                val color = arg("color", "colour")
                val parsed = color?.let { colour(it) }
                when {
                    ring == null -> Result.Bad("ring.set: ring is 1 or 2")
                    arg("value") != null && value == null -> Result.Bad("ring.set: value is 0 to 1, or a percentage")
                    value == null && ends <= 0L -> Result.Bad("ring.set: a value, or ends for a countdown")
                    color != null && parsed == null -> Result.Bad("ring.set: color is #RRGGBB")
                    else -> ok(RemoteCommand.RingSet(
                        ring = ring, value = value, color = parsed,
                        label = arg("label").orEmpty().take(MAX_LABEL),
                        until = arg("until")?.let { epochMs(it) } ?: 0L,
                        startsAt = starts, endsAt = ends,
                    ))
                }
            }
            "ring.clear" -> {
                val raw = arg("ring", "id")
                val ring = ringOf(raw)
                if (raw != null && ring == null) Result.Bad("ring.clear: ring is 1 or 2") else ok(RemoteCommand.RingClear(ring ?: -1))
            }

            "scene.apply", "scene" -> arg("name", "scene")?.take(MAX_ID)?.let { ok(RemoteCommand.SceneApply(it)) }
                ?: Result.Bad("scene.apply: a name")
            "config.set" -> json(arg("json"))?.let { ok(RemoteCommand.ConfigSet(it)) } ?: Result.Bad("config.set: json, an object")
            "animation.play", "animation" ->
                ok(RemoteCommand.AnimationPlay((arg("seconds")?.let { number(it)?.toInt() } ?: 0).coerceIn(0, 600)))

            "usage.push" -> {
                val provider = id(arg("provider", "id"))
                val body = json(arg("json"))
                when {
                    provider == null -> Result.Bad("usage.push: a provider id")
                    body == null -> Result.Bad("usage.push: json, an object")
                    else -> ok(RemoteCommand.UsagePush(provider, body))
                }
            }

            "media" -> when (val a = arg("action", "command")?.lowercase()) {
                "playpause", "play_pause", "toggle" -> ok(RemoteCommand.Media("playpause"))
                "next" -> ok(RemoteCommand.Media("next"))
                "previous", "prev" -> ok(RemoteCommand.Media("previous"))
                else -> Result.Bad(if (a == null) "media: an action" else "media: playpause, next or previous")
            }
            "screenshot" -> ok(RemoteCommand.Screenshot)
            "record" -> ok(RemoteCommand.Record)
            "state" -> ok(RemoteCommand.State)
            else -> Result.Bad("unknown command")
        }
    }

    private fun ok(c: RemoteCommand): Result = Result.Ok(c)

    /** "1", "true", "on", "yes" and their opposites; null for anything else, and for nothing. */
    fun truth(s: String?): Boolean? = when (s?.trim()?.lowercase()) {
        "1", "true", "on", "yes" -> true
        "0", "false", "off", "no" -> false
        else -> null
    }

    fun switchOf(s: String?): Switch? = when (s?.trim()?.lowercase()) {
        "toggle" -> Switch.TOGGLE
        else -> truth(s)?.let { if (it) Switch.ON else Switch.OFF }
    }

    private fun number(s: String): Double? = s.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    /**
     * 0..1 as written, or a percentage: "63%" and anything over 1 ("63") are out of a hundred.
     * A caller sending 1 means full, which is the only reading a gauge's author would expect.
     */
    fun fraction(s: String): Float? {
        val t = s.trim()
        val percent = t.endsWith('%')
        val n = number(t.removeSuffix("%")) ?: return null
        if (n < 0) return null
        val f = if (percent || n > 1.0) n / 100.0 else n
        return f.coerceIn(0.0, 1.0).toFloat()
    }

    /** Seconds or milliseconds since 1970, told apart by size; null for anything that is not a time. */
    fun epochMs(s: String): Long? {
        val n = number(s)?.toLong() ?: return null
        if (n <= 0) return null
        return if (n < 100_000_000_000L) n * 1000 else n
    }

    /** `#RRGGBB`, `RRGGBB` or `#AARRGGBB`, as ARGB; opaque unless it says otherwise. */
    fun colour(s: String): Int? {
        val hex = s.trim().removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        val n = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) (n or 0xFF000000L).toInt() else n.toInt()
    }

    private fun ringOf(s: String?): Int? = when (s?.trim()?.lowercase()) {
        "1", "top", "upper", "first" -> 0
        "2", "bottom", "lower", "second" -> 1
        else -> null
    }

    /** Letters, digits, `_`, `-` and `.`; anything else is not an id (it becomes part of a key). */
    private fun id(s: String?): String? = s?.trim()?.take(MAX_ID)?.takeIf { it.isNotEmpty() && ID.matches(it) }

    /** A package name as the shell will accept one (RearUserService.PACKAGE_NAME), or null. */
    fun pkg(s: String?): String? = s?.trim()?.takeIf { it.length <= 255 && PACKAGE.matches(it) }

    private fun json(s: String?): String? {
        val t = s?.trim() ?: return null
        return t.takeIf { it.length <= MAX_JSON && it.startsWith('{') && it.endsWith('}') }
    }

    private val ID = Regex("""[A-Za-z0-9_.\-]+""")
    private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
}
