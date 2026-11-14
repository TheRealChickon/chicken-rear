package dev.kb896.aiusage.remote

/**
 * What a token may be used for. A token carries a set of these, and every command belongs to
 * exactly one, so "Home Assistant may turn pages but not open my banking app on the back" is a
 * tick box rather than a hope.
 *
 * Stored by [wire], never by ordinal or name: these are written into the token list, and a
 * rename here must not quietly take a scope away from a token already handed out.
 */
enum class RemoteScope(val wire: String, val label: String) {
    DISPLAY("display", "Display: wake, sleep, pages, always-on, brightness"),
    APPS("apps", "Apps on the back: open, close, camera"),
    CONTENT("content", "Content: cards, tiles, ring gauges"),
    LOOK("look", "Look: scenes, settings, animations"),
    USAGE("usage", "Usage: push numbers for a provider"),
    MEDIA("media", "Media: play, pause, skip"),
    CAPTURE("capture", "Capture: screenshot, recording"),
    QUERY("query", "State: ask what the back is doing");

    companion object {
        fun fromWire(s: String?): RemoteScope? = entries.firstOrNull { it.wire == s }

        /** What a new token starts with: looking and showing, nothing that opens or changes things. */
        val DEFAULT: Set<RemoteScope> = setOf(DISPLAY, CONTENT, MEDIA, QUERY)
    }
}

/** On, off, or the other one: every switch a command can throw takes all three. */
enum class Switch { ON, OFF, TOGGLE;
    fun applied(current: Boolean): Boolean = when (this) { ON -> true; OFF -> false; TOGGLE -> !current }
}

/** A page of the back by NAME. Indices shift (no account, no music), so nothing outside the panel may hold one. */
enum class PageName(val wire: String) {
    CLOCK("clock"), USAGE("usage"), MEDIA("media"), HOME("home"), NEXT("next"), PREV("prev");
    companion object { fun fromWire(s: String?): PageName? = entries.firstOrNull { it.wire == s } }
}

/**
 * Everything the outside may ask of the back, already checked for shape: a command that exists
 * here has its arguments parsed, clamped and typed. Whether the caller MAY ask is RemoteGate's
 * and RemotePolicy's business, and doing it is RemoteDispatcher's. See RemoteParser for the
 * words these are spelled with.
 */
sealed interface RemoteCommand {
    val scope: RemoteScope
    /** The command's own name, for the log: never its arguments, which may be somebody's message. */
    val name: String

    // ---- display ----
    data class Wake(val guard: Boolean) : RemoteCommand { override val scope get() = RemoteScope.DISPLAY; override val name get() = "wake" }
    data object Sleep : RemoteCommand { override val scope get() = RemoteScope.DISPLAY; override val name get() = "sleep" }
    data object Lock : RemoteCommand { override val scope get() = RemoteScope.DISPLAY; override val name get() = "lock" }
    data class AlwaysOn(val to: Switch, val now: Boolean) : RemoteCommand { override val scope get() = RemoteScope.DISPLAY; override val name get() = "aod" }
    data class KeepAwake(val to: Switch) : RemoteCommand { override val scope get() = RemoteScope.DISPLAY; override val name get() = "keep_awake" }
    data class TableMode(val to: Switch) : RemoteCommand { override val scope get() = RemoteScope.DISPLAY; override val name get() = "table_mode" }
    /** [value] 1..255, or null to leave it; [auto] the rear light sensor's switch, or null to leave it. */
    data class Brightness(val value: Int?, val auto: Boolean?) : RemoteCommand { override val scope get() = RemoteScope.DISPLAY; override val name get() = "brightness" }
    data class Page(val page: PageName, val wake: Boolean) : RemoteCommand { override val scope get() = RemoteScope.DISPLAY; override val name get() = "page" }

    // ---- apps on the back ----
    data class AppOpen(val pkg: String) : RemoteCommand { override val scope get() = RemoteScope.APPS; override val name get() = "app.open" }
    data object AppSendCurrent : RemoteCommand { override val scope get() = RemoteScope.APPS; override val name get() = "app.send_current" }
    /** [which] is the shell's own word: "close", "front", "panel" or "resume" (IRearService.guestAction). */
    data class AppAction(val which: String) : RemoteCommand { override val scope get() = RemoteScope.APPS; override val name get() = "app.$which" }
    data object Camera : RemoteCommand { override val scope get() = RemoteScope.APPS; override val name get() = "camera" }

    // ---- content ----
    data class CardShow(
        val id: String, val title: String, val text: String, val app: String,
        /** An installed package (its icon), a built-in glyph's name, or empty for this app's own. */
        val icon: String, val wake: Boolean,
        /** How long the banner stays, in seconds; 0 for the usual. */
        val seconds: Int,
    ) : RemoteCommand { override val scope get() = RemoteScope.CONTENT; override val name get() = "card.show" }
    /** [id] empty: every card this API put up. */
    data class CardClear(val id: String) : RemoteCommand { override val scope get() = RemoteScope.CONTENT; override val name get() = "card.clear" }
    data class TileSet(
        val id: String, val label: String, val value: String, val icon: String,
        /** ARGB, or null for the panel's own colour. */
        val color: Int?, val tap: Boolean,
    ) : RemoteCommand { override val scope get() = RemoteScope.CONTENT; override val name get() = "tile.set" }
    /** [id] empty: all of them. */
    data class TileRemove(val id: String) : RemoteCommand { override val scope get() = RemoteScope.CONTENT; override val name get() = "tile.remove" }
    data class RingSet(
        /** 0 for the upper lens's ring, 1 for the lower. */
        val ring: Int,
        /** 0..1, or null for a countdown ([endsAt] and [startsAt]). */
        val value: Float?,
        val color: Int?, val label: String,
        /** Epoch ms after which the gauge lets go of the ring; 0 for until cleared. */
        val until: Long,
        /** A countdown the panel runs itself: full at [startsAt], empty at [endsAt] (epoch ms); 0 for none. */
        val startsAt: Long, val endsAt: Long,
    ) : RemoteCommand { override val scope get() = RemoteScope.CONTENT; override val name get() = "ring.set" }
    /** [ring] -1: both. */
    data class RingClear(val ring: Int) : RemoteCommand { override val scope get() = RemoteScope.CONTENT; override val name get() = "ring.clear" }

    // ---- look ----
    data class SceneApply(val scene: String) : RemoteCommand { override val scope get() = RemoteScope.LOOK; override val name get() = "scene.apply" }
    /** A JSON object of settings; what of it is taken is RemoteConfigPatch's to say. */
    data class ConfigSet(val json: String) : RemoteCommand { override val scope get() = RemoteScope.LOOK; override val name get() = "config.set" }
    /** Play the video background now, for [seconds] (0: as the clip is set to). */
    data class AnimationPlay(val seconds: Int) : RemoteCommand { override val scope get() = RemoteScope.LOOK; override val name get() = "animation.play" }

    // ---- usage ----
    data class UsagePush(val provider: String, val json: String) : RemoteCommand { override val scope get() = RemoteScope.USAGE; override val name get() = "usage.push" }

    // ---- media, capture, query ----
    /** [action] is MediaWatcher's own word: "playpause", "next" or "previous". */
    data class Media(val action: String) : RemoteCommand { override val scope get() = RemoteScope.MEDIA; override val name get() = "media" }
    data object Screenshot : RemoteCommand { override val scope get() = RemoteScope.CAPTURE; override val name get() = "screenshot" }
    /** Starts a recording of the back, or stops the one running: the shell's tile is one switch. */
    data object Record : RemoteCommand { override val scope get() = RemoteScope.CAPTURE; override val name get() = "record" }
    data object State : RemoteCommand { override val scope get() = RemoteScope.QUERY; override val name get() = "state" }
}
