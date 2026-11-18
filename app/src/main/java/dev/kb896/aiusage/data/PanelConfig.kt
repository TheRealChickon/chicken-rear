package dev.kb896.aiusage.data

import dev.kb896.aiusage.Panel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A face for the panel's text other than its theme's own.
 *
 * Either one the app ships ([bundled], by its PanelType id) or files on the phone — a
 * system family's, or one imported into the app's own folder. The files are named here,
 * weights and all, rather than looked up again by the family's name: the rear process
 * builds the face from them directly, without scanning every font on the phone first.
 */
@Serializable
data class PanelFont(
    /** The family's own name, as the list shows it. */
    val name: String,
    val bundled: String? = null,
    val files: List<PanelFontFile> = emptyList(),
)

/** One file of a [PanelFont]: [index] into a collection, [variable] when it has a weight axis. */
@Serializable
data class PanelFontFile(
    val path: String,
    val index: Int = 0,
    val weight: Int = 400,
    val variable: Boolean = false,
)

/** Which window a ring reports on. */
@Serializable
enum class RingWindow { SESSION, WEEKLY_ALL, WEEKLY_SCOPED }

/**
 * How the media row draws the cover.
 *
 * [SQUARE] is the cover as the player supplied it. [VINYL] cuts it to a disc and turns
 * it while the track plays — a record rather than a thumbnail, and it stops dead when
 * the music does, which makes it a second readout of the transport state rather than
 * decoration that happens to move.
 */
enum class MediaArt { SQUARE, VINYL }

/**
 * What about that window the ring shows.
 *
 * [NONE] is a real choice, not an absence of one. The rings are drawn concentric with
 * the phone's camera lenses, which is lovely on a panel that wants them and clutter on
 * one that does not — a bare lens surround is a legitimate look, and there was no way
 * to ask for it.
 *
 * Appended rather than inserted: these are stored by name, and the settings list maps
 * position to entry, so order is load-bearing in two directions.
 */
@Serializable
enum class RingMetric { TIME_TO_RESET, UTILIZATION, NONE }

/**
 * What one ring reports.
 *
 * There is no account field. A ring belongs to the account in the same position — ring
 * one to the first account, ring two to the second — so reordering the accounts carries
 * the rings with them and there is nothing to keep in step by hand.
 *
 * That does cost something worth naming: both rings can no longer be pointed at one
 * account. It was possible and nobody asked for it, and it required a dropdown on the
 * main screen whose meaning ("which account does this ring belong to") was the hardest
 * thing on that screen to explain.
 */
@Serializable
data class RingSpec(
    val window: RingWindow = RingWindow.SESSION,
    val metric: RingMetric = RingMetric.TIME_TO_RESET,
)

/**
 * Where the rings sit on the panel, in panel units.
 *
 * These are measured against one specific phone's rear display and lens positions. They
 * are configurable because a different model in the same family has a different panel
 * and different lens centres, and nothing but a human eye on the actual device can
 * supply those numbers.
 *
 * Both rings share [cx], [radius] and [stroke] deliberately. The lenses are in a single
 * vertical column, so independent X or radius per ring can only ever make them disagree
 * — and two concentric rings that do not match read as a rendering fault rather than a
 * setting.
 */
@Serializable
data class RingLayout(
    val cx: Int = 150,
    val cyFirst: Int = 144,
    val cySecond: Int = 420,
    val radius: Int = 128,
    val stroke: Int = 25,
) {
    companion object {
        /**
         * Bounds for the controls that edit these.
         *
         * Generous rather than tight. They exist to stop a slider producing a value that
         * cannot be drawn at all, not to keep the rings where they look right on one
         * particular phone — the whole reason these are adjustable is that a different
         * rear display puts the lenses somewhere else.
         */
        val CX_RANGE = 0..300
        val CY_FIRST_RANGE = 0..300
        val CY_SECOND_RANGE = 280..572
        val RADIUS_RANGE = 60..200
        val STROKE_RANGE = 4..60

        /**
         * The rings for the phone this runs on, in panel units (the 17 Pro's 904x572;
         * the panel is scaled to the display it is on — see RearDialView.fitX).
         *
         * The 17 Pro Max ("popsicle"): Xiaomi's own rear-screen app keeps one geometry
         * per device, the width of the lens column — 104.5dp on the Pro, 105.2dp on the
         * Pro Max, both about 296px at the rear displays' 450dpi. So the lenses are the
         * same pixel size on both, and the Pro Max's display is simply wider and taller
         * round them; scaling the Pro's rings up would put them 12px too far right and
         * 8% too big. These are the Pro's pixels divided by the Pro Max's scale, with
         * the lens column centred on its taller display. A starting point measured from
         * the app, not from a Pro Max — Settings → Ring layout takes it from there.
         */
        fun forDevice(): RingLayout = when (android.os.Build.DEVICE) {
            "popsicle" -> RingLayout(cx = 139, cyFirst = 154, cySecond = 418, radius = 121, stroke = 24)
            else -> RingLayout()
        }
    }
}

/**
 * A note pinned to the back: its words, and when (wall clock, ms), which is also what names it.
 * With a place ([lat], [lng], [radius] m, [place] its name or none) it is a reminder for there ("Pin to location",
 * dev.kb896.aiusage.place): reaching the zone puts it on the back as a page that stands ([arrivedAt], 0 while it
 * waits) until he dismisses it. [watched]: the zone's fence is known to be set and the position may be read, so an
 * arrival can be noticed; false, its mark is hollow ("Needs location"). [shownAt]: when it was last news otherwise.
 * [here]: the phone is at its place now, by the app's own state of that place (dev.kb896.aiusage.place.Here; the
 * app keeps it as it keeps [watched], and it crosses to the back with the notes): the back's notes page lists a note
 * for a place only while it is set (Here.onBack). Never set on a plain note, which is always listed.
 * [image]: a PICTURE pinned (a picture shared to "Pin to back" or "Pin to location"): its file's name in the app's
 * external files folder, `pins/` (data/PinImage: the name only, never a path; the back reads the file itself), and
 * [text] is then its caption, which may be empty. Null for a note of words, and for every note stored before.
 */
@kotlinx.serialization.Serializable
data class Pin(
    val text: String,
    val at: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val shownAt: Long = 0L,
    val radius: Int? = null,
    val place: String? = null,
    val arrivedAt: Long = 0L,
    val watched: Boolean = false,
    val here: Boolean = false,
    val image: String? = null,
) {
    val placed: Boolean get() = lat != null && lng != null
    /** A picture's note (its words, if any, are the caption). */
    val pictured: Boolean get() = image != null
    val zone: Int get() = radius ?: 150
    /** When it last became news on the notes page (an arrival is not: it has a page of its own, [arrivedAt]). */
    val freshAt: Long get() = maxOf(at, shownAt)
}

/**
 * One account's appearance. Identity — which credential, which slug — is not here.
 *
 * Every field is nullable and null means "use the built-in", NOT "blank". That is what
 * lets a default change later and reach everyone who never overrode it; storing the
 * resolved value at first write would freeze today's defaults into every config ever
 * saved.
 *
 * @param accent drives this account's ring, heading, row labels and plan tag together
 * @param mascotBody the mascot's body colour
 * @param mascotAccent its hardware — headband, dumbbells, flag pole
 */
@Serializable
data class AccountStyle(
    val label: String? = null,
    val accent: Int? = null,
    val mascotBody: Int? = null,
    val mascotAccent: Int? = null,
    val showMascot: Boolean = true,
    /**
     * Which windows this account shows, in this order. Null means "whatever the panel's
     * default is" — the same null-means-inherit rule as the colours above, so a later
     * change to that default reaches anyone who never chose for themselves.
     *
     * Per account rather than per panel because the accounts are not alike: a scoped
     * weekly limit matters on the account that actually uses that model and is noise on
     * the other.
     */
    val barOrder: List<String>? = null,
)

/**
 * Bottom art. [imagePath] null means the built-in mark.
 *
 * Pan and zoom are fractions of the art REGION, not of the panel, so they survive a
 * change to where that region is.
 */
/**
 * How the back's glass answers what is behind it. NONE keeps one tint; GLOBAL reacts to
 * the whole background's brightness; SHARD lets each piece react to what is right behind
 * it, as the back always has.
 */
@Serializable
enum class GlassReactivity { NONE, GLOBAL, SHARD }

/**
 * The back's glass, chosen in Glass settings: one [color] for every piece, at [intensity]
 * (0 clear to 1, half opaque), reacting as [reactivity] says. Until it is chosen
 * (PanelConfig.glassStyle null) each piece keeps its own built-in tint.
 */
@Serializable
data class GlassStyle(
    val color: Int = 0xFFFFFFFF.toInt(),
    val intensity: Float = DEFAULT_INTENSITY,
    val reactivity: GlassReactivity = GlassReactivity.SHARD,
) {
    /** The colour at its intensity: up to half opaque. */
    fun tint(): Int =
        (color and 0x00FFFFFF) or (Math.round(intensity.coerceIn(0f, 1f) * MAX_ALPHA) shl 24)

    companion object {
        /** The most a piece of glass is ever tinted: half opaque. */
        const val MAX_ALPHA = 127.5f
        /** The main glass's built-in tint (Glass.TINT, 0x26 white) as an intensity. */
        const val DEFAULT_INTENSITY = 38f / MAX_ALPHA
        val DEFAULT = GlassStyle()
    }
}

/**
 * The App Portal's grid on the back: [rows] by [columns] apps a page. Pinned apps past one
 * page go on further pages, swiped sideways. Fewer cells are bigger cells (PortalSheet.layout).
 * Stored by name; [R3C4] — three rows of four — is the grid the portal was born with.
 */
@Serializable
enum class PortalGrid(val rows: Int, val columns: Int) {
    R3C4(3, 4), R3C3(3, 3), R2C3(2, 3), R2C2(2, 2);

    val perPage: Int get() = rows * columns

    /** As the front's choice says it: rows × columns. */
    val label: String get() = "$rows × $columns"
}

/**
 * Who owns the rear screen. [COMPANION], the default: Xiaomi's own rear
 * screen (com.xiaomi.subscreencenter's SubScreenLauncher) stays the base, untouched, and this
 * app only adds to it — more apps, the charging animation, notification banners, wake
 * animations — as shell layers over it. Nothing is placed, suspended or re-placed.
 * [REPLACE]: the old full panel, which took the back over from Xiaomi's app. GONE: no
 * screen sets it, and a stored one is read as COMPANION (PanelConfig.decode). The name stays so that
 * such a stored config still decodes.
 */
@Serializable
enum class RearMode { COMPANION, REPLACE }

/**
 * What three fingers held on the back do (PanelConfig.threeFingers). Stored by name: [SCREENSHOT] (the default) a
 * screenshot of the back into DCIM/Screenshots; [RECORDING] the recording of the back started, or stopped if it runs
 * (the control centre's own tile); [BACK_OFF] the back off as a double tap and hold puts it; [NOTHING].
 */
@Serializable
enum class ThreeFingers { SCREENSHOT, RECORDING, BACK_OFF, NOTHING }

/** How the clock over the scene is set. */
@Serializable
enum class SceneClockStyle { THIN, BOLD, STACKED }

/** Which apps' notifications reach the back: every app but [PanelConfig.noticeApps], or only those. */
@Serializable
enum class NoticeAppsMode { ALL_EXCEPT, ONLY }

/** The charging animation's look on the back (drawn in a later step: rear/ChargeStyle). */
@Serializable
enum class ChargeAnimStyle { LIQUID, RING, EDGE_GLOW, MINIMAL }

/** The animation played as the back wakes (drawn in a later step: rear/WakeStyle). */
@Serializable
enum class WakeAnimStyle { EDGE_GLOW, LIQUID_SWEEP, BLUR_REVEAL, RIPPLE, NONE }

/**
 * How a message arriving shows on the back (the settings' Notifications section, "Message style"): [FULL] the whole back
 * for a few seconds (rear/NoticePop, as before; the default); [TINT] and [SWEEP] the message island (rear/MessageIsland):
 * a small pill drops in at the top with the app's icon, who it is from and a line of the message, Xiaomi's light round
 * it, and grows into a page over the live face: [TINT] the face takes on the app's colour, [SWEEP] the face dimmed and
 * one broad band of Xiaomi's light passing behind the words.
 */
@Serializable
enum class MessageStyle { FULL, TINT, SWEEP }

/** Where on the back a pull opens the pinned apps in companion mode (PortalPull). */
@Serializable
enum class PortalPullEdge { TOP, BOTTOM_RIGHT }

/** What the panel's background is. See PanelConfig.effectiveBackdrop. */
@Serializable
enum class BackdropKind { COLOR, IMAGE, VIDEO, SHADER }

/**
 * How a video background plays. LINEAR runs once, forward, when the panel opens and holds
 * its last frame. YOYO runs forward when the panel opens and back to its start when it is
 * locked, double-tapped or goes into always-on. LINEAR_LOOP goes round and round; PING_PONG
 * goes forward and back, forward and back. Going backwards plays the reversed copy.
 */
@Serializable
enum class VideoPlayMode { LINEAR, YOYO, LINEAR_LOOP, PING_PONG }

/**
 * A video background: the original the user picked (kept as a document URI the app holds
 * permission for, so it can be edited again), the clip made from it for the panel, and how
 * it plays. The clip is already cropped, scaled to the rear display and trimmed — the
 * trim and crop are kept to reopen the editor where it was left.
 */
/**
 * The Color Bends shader's look (PanelConfig.shader "bends"): Vue Bits' demo settings until
 * chosen, in the app's purple. See ui.glass.ColorBends.
 */
@Serializable
data class BendsConfig(
    val color: Int = 0xFFB14CFF.toInt(),
    /** Degrees. */
    val rotation: Int = 90,
    val scale: Float = 1f,
    val frequency: Float = 1f,
    val bandWidth: Float = 6f,
)

/**
 * The liquid metal shader's look (PanelConfig.shader "metal"): metal-fx's presets, or a tint
 * of its own ("tint"), over Paper's "Backdrop" settings. See ui.glass.LiquidMetal.
 */
@Serializable
data class MetalConfig(
    /** "silver", "gold", "chromatic" or "tint". */
    val preset: String = "silver",
    /** The colour-burn tint for "tint": its alpha is how much. */
    val tint: Int = 0xCCC26BFF.toInt(),
    /** Stripe density, 1 to 10. */
    val repetition: Float = 1.5f,
    /** Which way the stripes run, in degrees. */
    val angle: Int = 90,
    /** The noise warping the stripes, 0 to 1. */
    val distortion: Float = 0.1f,
)

@Serializable
data class VideoConfig(
    val originalUri: String? = null,
    val clipPath: String? = null,
    /**
     * The real footage cut past the frame on each side, as a share of the panel — what
     * parallax moves into without enlarging the clip. 0 for clips cut before parallax.
     */
    val margin: Float = 0f,
    /** The clip played backwards, for YOYO's return and PING_PONG; null for clips cut before it existed. */
    val reversePath: String? = null,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Float = 1f,
    /** As the picture's: how much of the video shows over the panel's ground colour. */
    val opacity: Float = 1f,
    /** The old switch, before there were four ways to play: read only through [mode]. */
    val loop: Boolean = true,
    /** How it plays; null in configs saved before the choice. See [mode]. */
    val playback: VideoPlayMode? = null,
    /** The old switches, before the eases had lengths: read only through [easeInWallMs] / [easeOutWallMs]. */
    val easeIn: Boolean = false,
    val easeOut: Boolean = false,
    /**
     * How long, on screen, it takes to speed up from slow and to slow into a stop — at a
     * run's ends and at every stop and start. 0 for none, up to [MAX_EASE_MS]; null in
     * configs saved before there were lengths. See EaseCurve.
     */
    val easeInMs: Int? = null,
    val easeOutMs: Int? = null,
    /** The clip's middle frame's brightness, GlassLuminance.SIDE squared cells, row by row. */
    val luminance: List<Float> = emptyList(),
    /**
     * How many times a Linear run plays before it rests on its last frame: once, as it always
     * did, up to [MAX_PASSES]. The still the panel rests on is the clip's last frame, so a clip
     * is cut to END on the picture it should hold.
     */
    val passes: Int = 1,
    /** A Linear run plays again when the phone is laid on its face (the table), not only on a wake. */
    val playOnTable: Boolean = false,
    /** And when a notification arrives on a lit back. */
    val playOnNotice: Boolean = false,
) {
    /** [passes] as it can be played. */
    val playPasses: Int get() = passes.coerceIn(1, MAX_PASSES)

    val easeInWallMs: Int get() = easeInMs ?: if (easeIn) DEFAULT_EASE_MS else 0
    val easeOutWallMs: Int get() = easeOutMs ?: if (easeOut) DEFAULT_EASE_MS else 0

    /** How it was asked to play: the choice, or the old switch's meaning. */
    val mode: VideoPlayMode
        get() = playback ?: if (loop) VideoPlayMode.LINEAR_LOOP else VideoPlayMode.LINEAR

    companion object {
        /** What an ease that was simply on lasted: a second of clip, about two on screen. */
        const val DEFAULT_EASE_MS = 2000
        const val MAX_EASE_MS = 2500
        const val MAX_PASSES = 20
    }

    /** How it can play: going backwards needs the reversed copy, and without it the nearest forward way. */
    val playable: VideoPlayMode
        get() = when {
            reversePath != null -> mode
            mode == VideoPlayMode.YOYO -> VideoPlayMode.LINEAR
            mode == VideoPlayMode.PING_PONG -> VideoPlayMode.LINEAR_LOOP
            else -> mode
        }
}

@Serializable
data class ArtConfig(
    val imagePath: String? = null,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Float = 1f,
    /**
     * How much of the picture shows, 0..1, over the panel's ground colour — the colour
     * behind it. 1 is the picture alone, as it always was. Only the background's picture
     * reads it; the artwork is always whole.
     */
    val opacity: Float = 1f,
)

/**
 * Everything about the panel a user may change.
 *
 * Every field is defaulted, and the defaults are the values that were previously
 * compiled in, so a device with no stored config draws exactly what it drew before this
 * existed. That is not a convenience — it is what makes the config safe to add to a
 * payload a running panel is already consuming.
 *
 * This is the FULL surface, not just the part wired up first. Landing the whole schema
 * at once means later work adds controls rather than migrating configs already written
 * by a shipped build.
 *
 * Colours are per ACCOUNT, and are not named primary/secondary. Those names encode a
 * two-ness that is a current limitation rather than a decision: the panel is intended to
 * scroll more than two accounts, and "secondary" means nothing for the fourth.
 */
@Serializable
data class PanelConfig(
    /**
     * The whole app's look. See PanelTheme — it decides the face, the glyphs and whether
     * surfaces refract, and those are not separable settings.
     *
     * FIRST FIELD, and default GLASS: a new install starts on glass. Existing panels keep
     * theirs — configs are stored with their defaults written out (encodeDefaults), so a
     * phone that has saved anything since themes existed has its theme on record. It is
     * the BACK's theme only: the front screen is always glass (see UiTheme).
     */
    val theme: PanelTheme = PanelTheme.GLASS,
    /** The panel's face, when it is not the theme's own; null is the theme's. See PanelType. */
    val font: PanelFont? = null,
    val title: String = "CLAUDE USAGE",
    val background: Int = Panel.Palette.BG,
    /**
     * Ramp colours from mild to spent, ascending. Positions stay fixed and only the
     * colours are chosen: a user dragging stop positions can invert the ramp and make a
     * full bar look emptier than a half one.
     */
    val rampColors: List<Int> = Panel.RAMP_STOPS.map { it.second },
    val rings: List<RingSpec> = listOf(RingSpec(), RingSpec()),
    /**
     * The second ring's settings while one connected account has both — see RingOwners.
     * Its own, not the second account's, and kept while a second account has its ring back.
     */
    val pairedRing: RingSpec = RingSpec(metric = RingMetric.UTILIZATION),
    /**
     * The order accounts appear in, top to bottom, as indices into Account.entries.
     *
     * Stored as an order rather than by reordering the accounts list itself, because
     * every persisted store — tokens, history, alert thresholds — keys on the account's
     * own identity. Shuffling that list would renumber those keys and orphan them, which
     * is the same trap that made labels unusable as keys.
     */
    val accountOrder: List<Int> = emptyList(),
    val ringLayout: RingLayout = RingLayout(),
    /** Default window order, for accounts that have not chosen their own. */
    val barOrder: List<String> = listOf("session", "weekly_all", "weekly_scoped"),
    /**
     * A camera button in the usage page's bottom-right corner. It opens the system camera ON
     * THIS DISPLAY, so the camera's live viewfinder goes onto the back.
     *
     * OFF BY DEFAULT, for two reasons. The page is a readout and nothing else on it reacts to
     * a touch. And one stray tap there puts a live preview on the back, which camera mode
     * (cameraLight) is careful never to do on its own.
     *
     * STORED UNDER A NEW NAME (showCameraButton): this used to default to on, and every stored
     * config has that on written out (encodeDefaults). The new name lets the new default reach
     * phones that never touched the switch.
     */
    @SerialName("showCameraButton")
    val showCamera: Boolean = false,

    /**
     * A close button on the left, which ripples the readout away and leaves the mascot
     * walking the bottom of the panel.
     *
     * Not a dismissal — the panel stays alive, drawing almost nothing. Killing it would
     * mean the app could not bring it back on its own, which is the failure this whole
     * project is built around avoiding.
     */
    val showClose: Boolean = false,
    val closeColor: Int = Panel.Palette.WORK,
    /**
     * [showCamera] and [showClose] are the USAGE page's (the readout's corners); these are
     * the INFO page's — the clock page — each page its own, so either, both or neither
     * shows them. Off by default. The colours are the info page's own, falling back to
     * the usage page's (see [cameraInfoTint], [closeInfoTint]).
     */
    val showCameraInfo: Boolean = false,
    val showCloseInfo: Boolean = false,
    val cameraInfoColor: Int? = null,
    val closeInfoColor: Int? = null,
    /**
     * The masthead's colour. Separate from the accounts' accents because it names the
     * panel rather than any account on it.
     */
    val titleColor: Int = Panel.Palette.WORK,

    /**
     * Per-extra colours, each falling back to [extrasColor] when unset.
     *
     * Nullable overrides rather than three plain colours, so a config saved before these
     * existed keeps whatever [extrasColor] it was given instead of silently reverting to
     * the default green. The fallback is resolved at read time — see [clockTint] and its
     * neighbours.
     */
    val clockColor: Int? = null,
    val batteryColor: Int? = null,
    val dateColor: Int? = null,
    val cameraColor: Int? = null,
    /** The weather line's colour; null means "whatever the date is", which it was born with. */
    val weatherColor: Int? = null,
    /** The table lights' colour; null for the clock's. */
    val tableColor: Int? = null,
    /** Table lights at all: the margins glowing when the phone lies on its face. On by default, as they always were. */
    val showTableLights: Boolean = true,

    /** The date, under the clock. Off by default, like the other two. */
    val showDate: Boolean = false,

    /**
     * Weather on the panel's glass: [Weather.OFF], [Weather.RAIN], [Weather.STORM] or [Weather.SNOW].
     *
     * A STRING RATHER THAN A BOOLEAN PAIR, because it is one choice with three answers
     * and two switches would let somebody ask for rain and snow at once. Stored as the
     * shader's own names so the setting reads the same in the file as it does in the code.
     */
    val weather: String = "off",
    /** What was typed in the Place row. Kept verbatim so the field shows it back. */
    val weatherPlace: String = "",
    /** The geocoder's answer for [weatherPlace] as "name, country"; empty when unresolved. */
    val weatherPlaceLabel: String = "",
    val weatherLat: Double? = null,
    val weatherLon: Double? = null,
    /** Ask the phone where it is before each fetch. Needs the coarse permission. */
    val weatherUseDevice: Boolean = false,
    /**
     * The weather line's degrees: "auto", "c" or "f". Auto is resolved in the app process before the
     * config crosses to the back (see [StatePayload.encode]), so the back only ever sees "c" or "f".
     */
    val temperatureUnit: String = TemperatureUnit.AUTO,

    /**
     * Walk the panel through itself, on a loop, with nobody touching it.
     *
     * FOR FILMING, and that is the whole of its design. The panel lives on the back of a
     * phone, so showing what it does means either a hand in every shot or a surface that
     * demonstrates itself — and a hand reaching across a 904-unit display hides most of
     * what it came to show. On a loop it can be filmed in one take from a tripod.
     *
     * It also holds the display awake, because a demo that lets the screen doze after
     * thirty seconds is a demo that stops halfway through the first take.
     */
    val demoMode: Boolean = false,

    /**
     * Hold the rear display awake for as long as the panel is up.
     *
     * OFF BY DEFAULT, and that default is a measurement rather than a taste. Held awake
     * with music playing, the panel costs about 110% of one core, sustained — it is the
     * single largest thing this app does. Released, MIUI drops the rear screen into its
     * own always-on doze after ten seconds (see subscreen_display_time), where the panel
     * stops drawing entirely; the last frame stays lit and nothing is spent keeping it
     * there.
     *
     * On is for people who would rather the readout kept moving than the battery lasted,
     * which was the original behaviour and is a perfectly reasonable thing to want.
     */
    val keepPanelAwake: Boolean = false,
    /**
     * Always-on: instead of going dark, the panel settles into a dim, frozen page — no
     * animation, the backdrop faded to black — and sleeps only in a pocket (pocketMode). A double tap
     * on it raises the unlock slider; the swipe brings the full panel back.
     */
    val alwaysOn: Boolean = false,
    /**
     * In always-on, the battery level above the clock. On a charger it shows anyway, with its
     * charging glyph, in always-on and the table's dim alike (RearDialView.dimBattery).
     */
    val aodBattery: Boolean = false,
    /**
     * Pocket mode: the rear proximity sensor covered while the back is lit — a pocket, a
     * bag — switches the back off at once, and uncovered it comes back as it was, the
     * slider up. Whenever the back is lit, not only in always-on, where it began. Off by
     * default: on a phone whose sensor reads covered in the hand, it is the panel going
     * black for no visible reason. See PocketWatch.
     *
     * STORED AS aodPocket, its name from when it was an always-on sub-switch: renaming the
     * key would have quietly turned it off for everybody who had turned it on.
     */
    @SerialName("aodPocket")
    val pocketMode: Boolean = true,
    /**
     * How much of the background stays in always-on, 0 to 1. 0 is what always-on always did:
     * the background fades to black and only the clock is lit, which costs an OLED nearly
     * nothing. Above it, the picture — or a video's still frame — stays, dimmed by always-on's
     * veil like everything else there. A still costs no drawing (the page is frozen either
     * way); what it costs is the light of the pixels it leaves on.
     */
    val aodBackdrop: Float = 0f,
    /**
     * "Dim when face down" (table mode): laid on its face, the rear facing up, the back is lit
     * DIM — under RearPosture.DIM_VEIL_ALPHA's veil, the page animated as ever — for as long as it lies there,
     * charger or not. A touch gives normal brightness for thirty seconds, and so do a card (for
     * its time) and a call (while it rings or lasts); a camera in use gives full brightness.
     * Seen while the back is dark too: the poll service watches (TableWatch) and wakes the back.
     * See RearPosture and RearActivity.
     *
     * [tableChargingOnly], "Only while charging", off by default: face down with no charger in,
     * the back is off instead.
     *
     * STORED UNDER A NEW NAME (tableDim): this switch used to be "Stay bright face down", off by
     * default, and that value is written out in every stored config (encodeDefaults). The minutes
     * limit it once had on the battery (tableDimMinutes) is gone; a stored one is ignored.
     */
    @SerialName("tableDim")
    val tableMode: Boolean = true,
    val tableChargingOnly: Boolean = false,
    /**
     * "Off when lying on its back": the phone at rest on its back — the rear display against the
     * table, the front up, settled and still (LyingOnBack) — switches the back off, and picking
     * it up lights it again by itself. ON BY DEFAULT. Watched by the poll service (TableWatch).
     */
    val offOnBack: Boolean = true,
    /**
     * "Stay on in the hand": everywhere no other rule applies (not face down, not on its back,
     * not in a pocket, not switched off by the double tap) the back stays lit at its normal
     * brightness with no idle sleep. ON BY DEFAULT — the back is on unless something says off.
     * Off, the old idle timer (thirty seconds, then always-on or dark) runs. See RearPosture.
     */
    val stayOnInHand: Boolean = true,
    /**
     * Camera mode, "Light up while the camera is open": while any camera is in use on the
     * phone, the back is lit at FULL brightness with its ordinary animated clock page, never a
     * viewfinder. No dim, always-on, table dim or idle sleep while it lasts; afterwards the back
     * goes back to what it was doing. The camera app's own rear preview is left alone: the panel
     * stands aside and the keep-alive does not cover it. A double tap on the lit back switches
     * it off until the camera closes. ON BY DEFAULT. See CameraWatch and CameraMode.
     */
    val cameraLight: Boolean = true,
    /**
     * "Match the front notifications": a notification that leaves the front leaves the
     * back's list too, the list is brought in line with the front whenever the relay
     * connects, and a card swiped away on the back is cleared on the front. Off, the back
     * keeps its own list, as it always did.
     */
    val matchFrontNotifications: Boolean = false,
    /** In always-on, the clock on the clock page; off, it fades out with the mode. */
    val aodClock: Boolean = true,
    /** In always-on, the date line on the clock page (outside it, the Date switch decides). */
    val aodDate: Boolean = true,
    /** The weather line on the clock page — the reading and the place. Off leaves only the weather on the glass. */
    val showWeatherLine: Boolean = true,
    /** In always-on, the weather line — independent of [showWeatherLine], as the date's is of the Date switch. */
    val aodWeather: Boolean = true,
    /**
     * The whole panel drawn as if on a curved tube — scanlines, a shadow mask, a
     * vignette, the picture bent outwards — the terminal's look on 896.ro. Off by
     * default: it is a look, and it bends the picture. See CrtScreen.
     */
    val crt: Boolean = false,

    /**
     * Mirror other apps' notifications onto the panel.
     *
     * [showNotificationText] is a SEPARATE choice, because this display faces away from its
     * user: everything on it is shown to whoever is behind them rather than to them. Choosing
     * to see that a message arrived is a different decision from choosing to broadcast what it
     * said — and while the phone is locked, a third ([noticeTextWhenLocked]).
     *
     * ON by default since companion mode: the banner over Xiaomi's rear is one of
     * the four things the app is for, and off by default it never showed for anyone. A fresh
     * install also has the text on while unlocked (PanelConfig.FRESH); a stored config keeps
     * what it had.
     */
    val showNotifications: Boolean = true,
    /**
     * ON, and on while locked too ([noticeTextWhenLocked]): a notification can be read on the back without unlocking
     * the phone. Both stored under new names, so a phone that had the old defaults gets these. Only a notification
     * its app marked secret is kept off the back while locked.
     */
    @kotlinx.serialization.SerialName("showNotificationText2")
    val showNotificationText: Boolean = true,
    val notificationColor: Int? = null,

    /**
     * The strip's own ground, falling back to the panel's background.
     *
     * Separate because the strip is a thing laid OVER the panel — giving it a ground of
     * its own is how it stops reading as a hole cut in the readout. Null rather than a
     * copy of [background], so changing the panel's colour carries the strip with it
     * until someone deliberately parts them.
     */
    val notificationBg: Int? = null,

    /**
     * Media controls in place of the bottom artwork, while something is playing.
     *
     * Off by default like everything else that changes the panel, and for one reason
     * beyond consistency: this one TAKES A REGION the user has already decorated. The
     * artwork slot accepts a custom image, and quietly covering someone's picture the
     * first time they play a song is not a feature arriving, it is a picture going
     * missing.
     *
     * It also rides on notification access, granted for a different feature entirely
     * (see Setup's step and MediaWatcher's note). Turning on a media player as a side
     * effect of agreeing to mirror notifications is not something the user asked for.
     */
    @kotlinx.serialization.SerialName("showMedia2")
    val showMedia: Boolean = true,
    /** The media page's one colour, from before each part had its own: what they all start in. */
    val mediaColor: Int? = null,
    /**
     * The media page's parts, each coloured on its own: the playback ring and what goes
     * with it (times, scrubber, its icons), the volume ring and what goes with it, the
     * three transport buttons, the artist and the title. Null: the one media colour.
     */
    val mediaPlaybackColor: Int? = null,
    val mediaVolumeColor: Int? = null,
    val mediaButtonsColor: Int? = null,
    val mediaArtistColor: Int? = null,
    val mediaTitleColor: Int? = null,
    /** The lyrics round the record; null: the title's colour, which they had before. */
    val mediaLyricsColor: Int? = null,
    val mediaArt: MediaArt = MediaArt.SQUARE,


    /**
     * Synced lyrics, curved around the record on the media page.
     *
     * A SEPARATE opt-in from [showMedia], and the only setting in this file that is one
     * for a reason beyond taste: it is the single feature in the app that sends anything
     * about the user anywhere. Switching it on means each new track's title, artist,
     * album and length go to lrclib.net — see LyricsApi, which carries the rest of that
     * argument, and LyricsCache, which makes it once per recording rather than once per
     * play.
     *
     * So it cannot ride in on "show media", the way it would if it were merely another
     * part of the music row. Somebody who wanted a play button on the back of their phone
     * did not thereby ask to tell a stranger what they listen to. Off is the default and
     * off means no request is ever built.
     */
    val showLyrics: Boolean = false,

    /** The usage page's corner row: its time, battery (and [showDate]), each with a colour. */
    val showClock: Boolean = false,
    val showBattery: Boolean = false,
    /**
     * The clock page's own big clock, date line and battery over the clock — apart from the
     * corner row's switches above, which they once shared. Null until set, and then they
     * behave as the shared switches did: see [infoClockOn], [infoDateOn], [infoBatteryOn].
     * Their colours fall back to the corner row's ([infoClockTint] and the rest).
     */
    val infoClock: Boolean? = null,
    /**
     * The big clock's seconds. Off, the clock is hours and minutes, as always-on already
     * shows it (which drops them whatever this says).
     */
    val clockSeconds: Boolean = true,
    /**
     * The clock's face: [FACE_DIGITAL], the rolling figures, or [FACE_ANALOG], hands of glass
     * pills (PillClock). A string, as [weather] is, so the file reads as the setting does; an
     * unknown value is digital ([analogClock]).
     */
    val clockFace: String = FACE_DIGITAL,
    /**
     * Always-on's own face, [FACE_DIGITAL] or [FACE_ANALOG]; null until one is picked, which shows
     * the clock page's ([clockFace]) — the setting offers only the two. Going into always-on and out of it, a different face changes with the
     * gooey morph (ClockGoo). See [aodAnalogClock].
     */
    val aodClockFace: String? = null,
    /**
     * The digital face stood on end: hours, then minutes under them, then seconds, no colons
     * (the owner, 2026-11-09). On the clock page and in always-on alike, wherever the digital
     * face shows.
     */
    val clockVertical: Boolean = false,
    /**
     * Twelve hours with AM/PM, the American way, or twenty-four; null until picked, which follows
     * the phone's own (see [twelveHour]). The digital face's; the analog face has no need of it.
     */
    val clock12h: Boolean? = null,
    /**
     * How much of the clock's colour the clock's glass takes — the analog hands and the digital
     * glass figures — from 0, clear glass, to 1, nearly the colour itself (the owner,
     * 2026-11-09: some will want clearer glass, and at the top it should read as the colour).
     * [CLOCK_GLASS_TINT] is the look it had before there was a choice. The clock's alone; the
     * Glass settings' tint is the rest's.
     */
    val clockGlassTint: Float = CLOCK_GLASS_TINT,
    val infoDate: Boolean? = null,
    val infoBattery: Boolean? = null,
    val infoClockColor: Int? = null,
    val infoDateColor: Int? = null,
    val infoBatteryColor: Int? = null,
    /** Colour for both extras. Defaults to the panel's green rather than DIM: they are
     *  small and sit alone in a corner, and dim made them read as disabled. */
    val extrasColor: Int = Panel.Palette.WORK,
    val art: ArtConfig = ArtConfig(),
    /**
     * A picture behind the whole readout, cropped the same way the art is.
     *
     * The same shape of setting as [art] and deliberately the same type: both are "an
     * image, panned and zoomed into a region", and the region differing does not make
     * them different ideas. Null path means the flat [background] colour, which is what
     * every panel had before this existed.
     */
    val backdrop: ArtConfig = ArtConfig(),
    /** Which background the panel has; null in configs saved before there was a choice. See [effectiveBackdrop]. */
    val backdropKind: BackdropKind? = null,
    /** The shader background's colour (BackdropKind.SHADER): Silk's purple until one is chosen. */
    val shaderColor: Int = 0xFF5B21B6.toInt(),
    /** The shader's second colour, in the folds between the crests: black, as the original. */
    val shaderColor2: Int = 0xFF000000.toInt(),
    /** How often the shader's folds repeat: Silk's own 1.3 until chosen. */
    val shaderScale: Float = 1.3f,
    /** The shader's turn, in degrees: Silk's own 45 until chosen. */
    val shaderRotation: Int = 45,
    val video: VideoConfig = VideoConfig(),
    /**
     * Depth for the background (a picture, a video, the shader): it shifts a little as the
     * phone tilts, and as the panel's pages scroll. See Parallax.
     */
    val parallaxTilt: Boolean = false,
    val parallaxScroll: Boolean = false,
    /** The back's glass, once chosen in Glass settings; null keeps each piece's built-in tint. */
    val glassStyle: GlassStyle? = null,
    /** The Shader background's choice: "silk" (the front's own), "bends" (Color Bends) or "metal". */
    val shader: String = "silk",
    val bends: BendsConfig = BendsConfig(),
    val metal: MetalConfig = MetalConfig(),
    /** Appearance per account, by index. Short lists are fine — a missing entry is
     *  the same as an entry of all-nulls: use the built-ins. */
    val accounts: List<AccountStyle> = listOf(AccountStyle(), AccountStyle()),
    /**
     * App Portal: the apps pinned to the portal's grid on the back, in order, by package.
     * Packages rather than components: the launcher activity is asked for at launch time,
     * so an app that renames its main activity in an update still opens.
     */
    val portalApps: List<String> = emptyList(),
    /** The portal frame's colour (see PortalFrame). Default the app's purple. */
    val portalFrameColor: Int = 0xFFB14CFF.toInt(),
    /**
     * App Portal: a density of its own for an app on the back, by package, in dpi. Some apps
     * are unreadable at the rear's default density; the shell applies an entry only while
     * that app is the guest on top, and the display goes back to its default otherwise.
     * No entry means the default. See RearDensity for the bounds.
     */
    /**
     * App Portal: how wide an app kept right of the cameras is, in the rear's pixels, by package
     * (GuestWidth). None is the whole width right of the lenses.
     */
    /**
     * Notifications on the back keep their words while the phone is locked: off, a locked back
     * (BackLock) shows each as its app and "New notification", the rest after the code.
     */
    val lockedNoticeText: Boolean = false,
    val portalWidth: Map<String, Int> = emptyMap(),
    val portalDpi: Map<String, Int> = emptyMap(),
    /** App Portal: how many apps a page of the back's grid holds. See PortalGrid. */
    val portalGrid: PortalGrid = PortalGrid.R3C4,
    /** App Portal: the frame round a guest app on the back (PortalFrame). On, as it always was. */
    val portalBorder: Boolean = true,
    /**
     * App Portal: the apps' names under their icons on the back. Off, the icons grow into
     * the names' room (PortalSheet.icon).
     */
    val portalAppNames: Boolean = true,
    /** The portal's apps as a dome turned any way by a finger (PortalDome), not the grid. */
    val portalDome: Boolean = false,
    /** A notification pops up as a banner on the back as it arrives. The pull-down list is there either way. */
    val noticesOverApps: Boolean = true,
    /**
     * App Portal: apps kept right of the cameras on the rear ("Keep right of the cameras" in
     * the app's sheet on the front and under the DPI slider on the back), by package: each runs on
     * a private display (PrivateDisplay). Every other guest has the whole of display 1. Empty:
     * all fullscreen. Replaces the opt-out portalFullscreen, which
     * an older config may still carry — ignored.
     */
    val portalConfined: Set<String> = emptySet(),
    /**
     * App Portal: the "Send to back" tile is in Quick Settings, as far as the app was last
     * told — the add-tile request's answer, and PortalTileService's own added / removed /
     * listening. Ticks the Portal tab's second step (PortalGuide).
     */
    val portalTileAdded: Boolean = false,
    /** App Portal: the sheet has been opened on the back at least once (IRearEvents.onPortalOpened). The third step. */
    val portalSheetSeen: Boolean = false,
    /** App Portal: the Portal tab's guide card is down for good — finished, or dismissed early. */
    val portalGuideDone: Boolean = false,
    /**
     * Remote control is on, so cards put up from outside have the back's banner and list even
     * with [showNotifications] off: mirroring every notification and showing the one a home
     * automation sends are different things to want. Only this bit of remote control lives
     * here — the shell draws the banner and reads the config, nothing else. Tokens and rules
     * stay out of a config that crosses to the panel and the shell with every payload.
     */
    val remoteCards: Boolean = false,

    // -- companion mode -------------------------------------------------------
    //
    // A stored config written before these existed has no rearMode key, and decode() then
    // moves it to companion mode once (see [companionStart]); a config that has the key is
    // read as it is.

    /** Who owns the rear screen: see [RearMode]. Companion unless Replace is chosen in Advanced. */
    val rearMode: RearMode = RearMode.COMPANION,
    /**
     * Picked up off its back, the back is woken. Off by default: on Xiaomi's base it lit
     * Xiaomi's rear on every pick-up. "Off when lying on its back"
     * ([offOnBack]) now only declines this app's own wakes while it lies there.
     */
    val wakeOnLift: Boolean = false,
    /** The apps [noticeAppsMode] names: left out (ALL_EXCEPT) or the only ones (ONLY). Packages. */
    val noticeApps: Set<String> = emptySet(),
    val noticeAppsMode: NoticeAppsMode = NoticeAppsMode.ALL_EXCEPT,
    /** A banner keeps its words while the phone is locked; unlocked, [showNotificationText] decides. */
    @kotlinx.serialization.SerialName("noticeTextWhenLocked2")
    val noticeTextWhenLocked: Boolean = true,
    /** The charging animation on the back when the charger goes in. */
    val chargeAnim: Boolean = true,
    val chargeStyle: ChargeAnimStyle = ChargeAnimStyle.LIQUID,
    /** Also when the back is dark: it is woken for the animation (declines respected). */
    val chargeWhenDark: Boolean = true,
    /** An animation as the back wakes. */
    val wakeAnim: Boolean = true,
    val wakeStyle: WakeAnimStyle = WakeAnimStyle.EDGE_GLOW,
    /** More apps on the back (the Portal) over Xiaomi's rear screen: the tile, the pull, the sheet. */
    val moreApps: Boolean = true,
    /** Where the pull that opens the pinned apps starts in companion mode. */
    val portalPullEdge: PortalPullEdge = PortalPullEdge.TOP,
    /** The charging animation says the battery's percent. */
    val chargePercent: Boolean = true,
    /** A notification wakes a dark back for its banner (declines respected); off, it shows only on a lit back. */
    val noticeWake: Boolean = true,
    /**
     * No banner of ours while the phone is locked: Xiaomi's own rear list fills only then, and
     * with this on the two do not both show. Off by default.
     */
    val noticeSkipLocked: Boolean = false,

    /**
     * The notes pinned to the back ("Pin to back" from any app's text: ui.PinActivity), newest
     * first. Shown on the back's notes page (rear/PinFace), one swipe up from the scene: every plain note, and a note
     * for a place only while the phone is at that place ([Pin.here], dev.kb896.aiusage.place.Here.onBack). The
     * settings' own list shows them all.
     */
    val pins: List<Pin> = emptyList(),

    // -- the scene: clips and a clock over Xiaomi's rear screen --------------------
    //
    // A clip is a file in the app's clips folder (ClipStore), named here by its file name; ""
    // is none. As the back wakes [sceneWake] plays once; then [sceneLoop] repeats under the
    // clock. It takes every touch (SceneTouch): only a swipe on purpose shows Xiaomi's screen.

    /** Clips on the back at all. */
    val sceneOn: Boolean = true,
    /**
     * Played once as the back wakes; "" for none. NONE by default, under a new stored name so an older stored choice
     * is not read back: the back shows one face and nothing else, and a wake clip before it is a cut to another shot.
     */
    @kotlinx.serialization.SerialName("sceneWake2")
    val sceneWake: String = "",
    /**
     * The FACE: one clip that repeats after the wake clip; "" for none. ONE SHOT or one picture,
     * never shots cut together: a loop of several shots is restless to look at. It is changed with a press and hold
     * on the back (SceneTouch), which writes this. The default names one clip; no clip ships with the app (the README
     * says how to add one). Under a new stored name each time the default changed, so a stored choice of a face that
     * is gone does not come back.
     */
    @kotlinx.serialization.SerialName("sceneLoop5")
    val sceneLoop: String = "david-moon.loop.mp4",
    val face: String = "moon",
    /**
     * Which choice [face] is: the phone's clock at the choice (FaceCatalog.nextSeq), on the back or in the settings; a
     * payload with an older one than the shell's own is not followed (the shell keeps its copy in /data/local/tmp).
     */
    val faceSeq: Long = 0L,
    /**
     * The clock over the scene. OFF, under a new stored name so an older stored "on" is not read back. The switch
     * stays in the settings.
     */
    @kotlinx.serialization.SerialName("sceneClock2")
    val sceneClock: Boolean = false,
    /** The moon face's glitch: a twitch of the picture now and then (rear/SceneEffects). */
    /**
     * The back's brightness follows the light around it (rear/RearLight), and [rearLightBias] moves all of it:
     * -1 dimmer to 1 brighter.
     */
    val rearAdaptive: Boolean = true,
    val rearLightBias: Float = 0f,
    val sceneGlitch: Boolean = true,
    /**
     * The moon face's clouds stirred by the weather where the phone is (rear/SceneEffects). On, the weather is
     * fetched for the phone's own rough position (WeatherPoll), whatever the older weather settings say.
     */
    val sceneWeather: Boolean = true,
    val dustMovesWithPhone: Boolean = true,
    val sceneClockStyle: SceneClockStyle = SceneClockStyle.STACKED,
    /** The day and date under the clock. */
    val sceneDate: Boolean = true,
    /** The clock behind the clip's subject (a depth effect), where the clip carries a matte. */
    val sceneDepth: Boolean = true,
    /**
     * The battery's percent on the face, small. Off by default. Under a
     * new stored name, so the "true" an earlier build may have saved is not read back.
     */
    @kotlinx.serialization.SerialName("sceneBatteryOnFace")
    val sceneBattery: Boolean = false,
    /** Retained for compatibility with builds that had a camera standby setting. */
    val cameraKeepRear: Boolean = true,
    /** Open our rear camera from the moon once camera permission has been granted. */
    val cameraOwnRear: Boolean = true,
    /**
     * The back stays lit with the scene on it whenever the phone is in use in the hand or lies
     * face down, and is off in a pocket, lying on its back, and after a double tap on the back
     * until it is lit again by hand (service/StayOn). On by default.
     */
    val stayOn: Boolean = true,
    /**
     * Home detection (service/HomeNet, StayOn): at home, on the Wi-Fi marked as home ([homeNet]), the back stays
     * on for less time while the phone is in use, and off at night; lying face down is as everywhere. Off until
     * he switches it on.
     */
    val homeDetect: Boolean = false,
    /**
     * "Sleep at night" (service/NightRule): between 22:00 and 07:00, or while Do Not Disturb is on, with the
     * front off the back goes dark two minutes after the front did (or after a finger on it), is not lit again
     * by the phone being moved, and is not woken for a notification or the charger. A lit back keeps the whole
     * phone awake, and with it Xiaomi's own night mode out. On by default.
     */
    val sleepAtNight: Boolean = true,
    /**
     * "Pin animation" (the settings' Notes section): true = a pin goes through Xiaomi's own pin (rear/PinVia: its
     * animation, island and sound on the front, our notes page on the back at its end); false = our own animation,
     * draft B (rear/PinB, Xiaomi's look drawn by us; the plain rear/PinBeam where Xiaomi's pictures cannot be read).
     * Read as a pin begins, so a change holds from the next pin. Xiaomi's by default.
     */
    val pinByXiaomi: Boolean = true,
    /**
     * "Empty pull light" (the settings' Notifications section): the light that runs round the rim when the
     * notifications are pulled down with nothing waiting (rear/EmptyRim) is white (false, the default) or in Xiaomi's
     * own rear-glow colours (true). Read as a pull begins.
     */
    val emptyPullXiaomi: Boolean = false,
    /**
     * "Message style" (the settings' Notifications section): the full page (as before, the default) or the message
     * island with the face tinted or dimmed under a sweep of light ([MessageStyle]). Read as each message arrives.
     */
    val messageStyle: MessageStyle = MessageStyle.FULL,
    /**
     * "Follows the music" (service/MusicLevel): the music page's glows rise and fall with the sound. It needs the
     * permission to hear what is playing (RECORD_AUDIO: Android's only way), which is the user's to give, so OFF
     * until it is switched on; off, or without the permission, the glows breathe by themselves.
     */
    val musicFollows: Boolean = false,
    /** Ongoing navigation notifications may use the full rear page; requires notification access. */
    val navigationOn: Boolean = true,
    val musicFloor: Int = MUSIC_FLOOR,
    /**
     * "Earbuds animation" (the settings' Music section): when earbuds or a headset connect over Bluetooth, the
     * Xiaomi-like card on the front with Sony's picture of them and their level, and its moment on the back
     * (service/EarbudsWatch). On by default; it shows only once he has allowed a way onto the front.
     */
    val earbudsAnim: Boolean = true,
    val usageFiveHours: Boolean = false,
    /**
     * Which of OpenAI's own Dots characters stands in ChatGPT's vessel (rear/dots: drawn by the ChatGPT app's own
     * renderer, read from the phone at run time), by its id in that app's character list. Felipe by default.
     */
    val chatGptDot: String = "blue_beret",
    val usageDots: Boolean = false,
    /** The home Wi-Fi as HomeNet knows a network; "" when none is marked. */
    val homeNet: String = "",
    /** Lying face down and not touched for a while, the scene is dimmed. */
    val stayDimFaceDown: Boolean = true,
    val searchOffline: Boolean = true,
    val threeFingers: ThreeFingers = ThreeFingers.SCREENSHOT,
) {

    /**
     * This config as companion mode starts it: the rules that act on the rear screen by
     * themselves switched off, since on Xiaomi's base they light or dim Xiaomi's screen
     * rather than a panel of ours — always-on's relight after the lock and "stay on in the
     * hand" (they fight Xiaomi's own AOD off), "dim when face down", camera mode's full
     * brightness, the pocket's active sleep, and the wake on lift. "Off when lying on its back"
     * stays, as it only declines this app's wakes now. Everything else is kept as it was.
     */
    fun companionStart(): PanelConfig = copy(
        rearMode = RearMode.COMPANION,
        alwaysOn = false,
        stayOnInHand = false,
        tableMode = false,
        cameraLight = false,
        pocketMode = false,
        wakeOnLift = false,
    )

    /** Companion mode: Xiaomi's rear screen is the base, and nothing of ours is placed over it. */
    val companion: Boolean get() = rearMode == RearMode.COMPANION

    /** This account's appearance, or an all-defaults one if it has no entry. */
    /** [clockColor] or the shared extras colour. Same for the two below. */
    val clockTint: Int get() = clockColor ?: extrasColor

    /** The analog face is chosen; anything else is the digital one. */
    val analogClock: Boolean get() = clockFace == FACE_ANALOG

    /** The digital clock in twelve hours: as picked, or as the phone tells the time ([phoneIs24h]). */
    fun twelveHour(phoneIs24h: Boolean): Boolean = clock12h ?: !phoneIs24h

    /** Always-on shows the analog face: its own choice, or the clock page's. */
    val aodAnalogClock: Boolean get() = (aodClockFace ?: clockFace) == FACE_ANALOG
    /** The rings as drawn: what the user set, or the phone's own default while untouched. */
    val effectiveRingLayout: RingLayout get() = if (ringLayout == RingLayout()) RingLayout.forDevice() else ringLayout
    val batteryTint: Int get() = batteryColor ?: extrasColor
    val dateTint: Int get() = dateColor ?: extrasColor
    val weatherTint: Int get() = weatherColor ?: infoDateTint
    val infoClockTint: Int get() = infoClockColor ?: clockTint
    val infoDateTint: Int get() = infoDateColor ?: dateTint
    val infoBatteryTint: Int get() = infoBatteryColor ?: batteryTint
    /** The clock page's big clock: always there until switched off. */
    val infoClockOn: Boolean get() = infoClock ?: true
    /** The clock page's date line: the Date switch's until set. */
    val infoDateOn: Boolean get() = infoDate ?: showDate
    /** The battery over the big clock: until set, only with no readout and the Battery switch on. */
    fun infoBatteryOn(noReadout: Boolean): Boolean = infoBattery ?: (noReadout && showBattery)
    val tableTint: Int get() = tableColor ?: clockTint
    val cameraTint: Int get() = cameraColor ?: extrasColor
    val cameraInfoTint: Int get() = cameraInfoColor ?: cameraTint
    val closeInfoTint: Int get() = closeInfoColor ?: closeColor
    val notificationTint: Int get() = notificationColor ?: extrasColor
    val mediaTint: Int get() = mediaColor ?: extrasColor
    val mediaPlaybackTint: Int get() = mediaPlaybackColor ?: mediaTint
    val mediaVolumeTint: Int get() = mediaVolumeColor ?: mediaTint
    val mediaButtonsTint: Int get() = mediaButtonsColor ?: mediaTint
    val mediaArtistTint: Int get() = mediaArtistColor ?: mediaTint
    val mediaTitleTint: Int get() = mediaTitleColor ?: mediaTint
    val mediaLyricsTint: Int get() = mediaLyricsColor ?: mediaTitleTint
    val notificationGround: Int get() = notificationBg ?: background

    /**
     * The background in force. A config from before the choice existed had a picture or a
     * colour, and opens on whichever it had.
     */
    val effectiveBackdrop: BackdropKind
        get() = backdropKind ?: if (backdrop.imagePath != null) BackdropKind.IMAGE else BackdropKind.COLOR

    /**
     * Every colour the panel is drawn in, once each, in a stable order — the picker's
     * palette: "make this match that" is most of what choosing a colour is, and these are
     * the things there are to match. Resolved as drawn (an unset colour is the one it falls
     * back to), opaque, and told apart by their RGB alone.
     */
    fun usedColours(): List<Int> {
        val all = buildList {
            add(background)
            add(titleColor); add(closeColor); add(extrasColor)
            add(clockTint); add(batteryTint); add(dateTint); add(infoClockTint); add(infoDateTint); add(infoBatteryTint); add(weatherTint); add(tableTint)
            add(cameraTint); add(cameraInfoTint); add(closeInfoTint); add(notificationTint); add(notificationGround); add(mediaTint)
            addAll(rampColors)
            accounts.forEachIndexed { i, a ->
                add(a.accent ?: if (i == 0) Panel.Palette.PERSONAL else Panel.Palette.WORK)
                a.mascotBody?.let(::add)
                a.mascotAccent?.let(::add)
            }
        }
        return all.map { it or (0xFF shl 24) }.distinct()
    }

    fun styleFor(index: Int): AccountStyle = accounts.getOrElse(index) { AccountStyle() }

    /**
     * The windows this account shows, in order.
     *
     * NEVER EMPTY. An account with every bar switched off is a heading, a mascot and
     * nothing to stand on — indistinguishable on the panel from an account whose data
     * failed to load. The UI stops you turning the last one off; this is the backstop
     * for a config edited by hand or written by an older build.
     */
    fun barsFor(index: Int): List<String> {
        val chosen = styleFor(index).barOrder ?: barOrder
        return chosen.ifEmpty { barOrder }
    }

    /**
     * The account list with one entry edited, GROWN if it does not reach that far.
     *
     * A stored config can be shorter than the number of accounts — it was written when
     * there were fewer, or by hand. Editing account 1 of a one-entry list must add the
     * second rather than silently do nothing, which is what a plain map would do.
     */
    fun withStyle(index: Int, edit: (AccountStyle) -> AccountStyle): List<AccountStyle> {
        val grown = accounts.toMutableList()
        while (grown.size <= index) grown.add(AccountStyle())
        grown[index] = edit(grown[index])
        return grown
    }

    /**
     * Account indices in display order, with any the order does not mention appended.
     *
     * Tolerating a partial or stale order matters: one written when there were two
     * accounts must not hide a third, and a duplicate or out-of-range entry must not
     * drop a real account off the panel.
     */
    fun orderedAccounts(count: Int): List<Int> {
        val seen = LinkedHashSet<Int>()
        accountOrder.filter { it in 0 until count }.forEach { seen.add(it) }
        (0 until count).forEach { seen.add(it) }
        return seen.toList()
    }

    /** The ring list with one entry edited, grown if short — same reasoning. */
    fun withRing(index: Int, edit: (RingSpec) -> RingSpec): List<RingSpec> {
        val grown = rings.toMutableList()
        while (grown.size <= index) grown.add(RingSpec())
        grown[index] = edit(grown[index])
        return grown
    }

    companion object {
        val DEFAULT = PanelConfig()

        /** The clock's glass tint before it could be set: a wash of its colour, not the colour. */
        const val CLOCK_GLASS_TINT = 0.35f

        /** [musicFloor]'s default: the music page's body never under 70 % of the floor's range (98 px). */
        const val MUSIC_FLOOR = 30

        const val FACE_DIGITAL = "digital"
        const val FACE_ANALOG = "analog"

        /**
         * Lenient so a newer payload cannot crash an older :rear process mid-install — nor
         * wipe the config: a value an older build does not know (a new enum name, say) is
         * read as that field's default (coerceInputValues), not the whole config's.
         */
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }

        fun encode(config: PanelConfig): String = json.encodeToString(serializer(), config)

        /**
         * What a phone with no stored config starts on: companion mode, with the rules that
         * act on the rear screen by themselves off ([companionStart]). [DEFAULT] keeps every
         * field's own default (the Replace mode's posture rules on), which is what a stored
         * config's missing field reads as. The message text is on, locked or not.
         */
        val FRESH: PanelConfig = DEFAULT.companionStart().copy(showNotificationText = true)

        /** Whether [raw], a stored config, still says Replace mode: read as companion, but Xiaomi's rear app may be away. */
        fun storedReplace(raw: String?): Boolean = raw != null && Regex("\"$REAR_MODE_KEY\"\\s*:\\s*\"REPLACE\"").containsMatchIn(raw)

        /** The key whose absence marks a config written before companion mode. */
        const val REAR_MODE_KEY = "rearMode"

        /**
         * Never throws. A config that cannot be read is a config that was not set.
         *
         * A stored config with no [REAR_MODE_KEY] was written before companion mode existed:
         * it is moved to companion mode once ([companionStart]) — its posture rules were
         * defaults nobody chose (alwaysOn, tableDim and cameraLight among them). The next save writes rearMode out
         * (encodeDefaults), so this runs once, and a rule turned back on afterwards stays on.
         */
        fun decode(raw: String?): PanelConfig {
            if (raw.isNullOrBlank()) return DEFAULT
            return try {
                val element = json.parseToJsonElement(repurpled(raw))
                // A number out of its range (written by hand, or by a later build) is read as the nearest one in it.
                val config = json.decodeFromJsonElement(serializer(), element).let {
                    if (it.musicFloor in 0..100) it else it.copy(musicFloor = it.musicFloor.coerceIn(0, 100))
                }
                val stamped = (element as? kotlinx.serialization.json.JsonObject)?.containsKey(REAR_MODE_KEY) == true
                // And one that says Replace: that mode and its screen are gone. It is read as
                // companion mode, always, so nothing in the app can be in Replace mode again; the service
                // puts Xiaomi's rear app back where Replace had moved it away (UsagePollService).
                if (stamped && config.rearMode == RearMode.COMPANION) config else config.companionStart()
            } catch (e: Exception) { DEFAULT }
        }

        /**
         * The app's old colours become the purple wherever a stored config has them, in any field
         * and any account's: defaults are stored written out (encodeDefaults), so without this a
         * phone updating would keep the green and the yellow on its back. An exact match, so
         * any other colour somebody picked stays. In the text, for there being dozens of fields.
         */
        private fun repurpled(raw: String): String {
            if (OLD_COLORS.keys.none { raw.contains(it) }) return raw
            return OLD_COLOR_VALUE.replace(raw) { OLD_COLORS[it.value] ?: it.value }
        }

        private val OLD_COLORS = mapOf(
            0xFFB5BD68.toInt() to Panel.Palette.WORK,
            0xFF28FF85.toInt() to Panel.Palette.WORK,
            0xFFE7FF00.toInt() to 0xFFC26BFF.toInt(),
            0xFFFFFF78.toInt() to Panel.Palette.PERSONAL,
        ).entries.associate { (old, new) -> old.toString() to new.toString() }
        private val OLD_COLOR_VALUE = Regex("""(?<=[:\[,])-?\d+(?=[,\]\}])""")
    }
}
