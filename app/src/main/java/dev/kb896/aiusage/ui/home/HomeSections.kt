package dev.kb896.aiusage.ui.home

/**
 * THE SETTINGS SHOW WHAT BELONGS TO THE PAGE HE HAS SELECTED.
 *
 * The row of choices under the preview (Idle, Charging, Message, Music, Navigation, Timer, Call, Usage, Notes) is the one
 * selector: it shows that page of the back in the preview, as it did, and now also only that page's sections
 * below it. Still one page, the preview sticky at its top; no new screen.
 *
 * EVERY SECTION BELONGS TO EXACTLY ONE CHOICE ([home]); none is unreachable (a test holds both). Where one could
 * sit under two, it is where he would look first:
 *  - Camera (keep the back in use while the camera is open) under Idle: there is no Camera choice, and it is about
 *    what the idle back does.
 *  - Apps on the back and Advanced under Idle, for the same reason.
 *  - "Three fingers held" (what three fingers held on the back do, rear/ThreeHold) under Idle: a gesture on the idle
 *    back, there being no gestures choice.
 *  - "Wake a dark back for it" stays inside Charging, "Light up the back" inside Message: each with its own page.
 *  - Timer and Call have nothing to set: they say so in one line.
 * "Wake" (offered only when a wake clip is chosen) is the idle face's own start and shows Idle's sections.
 */
enum class HomeSection { FACES, CLAUDE, NOTES, ALWAYS_ON, APPS, ANIMATIONS, MUSIC, NAVIGATION, CHARGING, NOTIFICATIONS, CAMERA, THREE_FINGERS, ADVANCED }

object HomeSections {
    /** The one choice a section is found under. */
    fun home(s: HomeSection): PreviewMode = when (s) {
        HomeSection.FACES, HomeSection.ALWAYS_ON, HomeSection.ANIMATIONS, HomeSection.APPS, HomeSection.CAMERA, HomeSection.THREE_FINGERS,
        HomeSection.ADVANCED -> PreviewMode.IDLE
        HomeSection.CHARGING -> PreviewMode.CHARGING
        HomeSection.NOTIFICATIONS -> PreviewMode.ALERT
        HomeSection.MUSIC -> PreviewMode.MUSIC
        HomeSection.NAVIGATION -> PreviewMode.NAVIGATION
        HomeSection.CLAUDE -> PreviewMode.USAGE
        HomeSection.NOTES -> PreviewMode.NOTES
    }

    /** The choice whose sections [mode] shows: itself, except Wake, which is Idle's. */
    fun shownAs(mode: PreviewMode): PreviewMode = if (mode == PreviewMode.WAKE) PreviewMode.IDLE else mode

    /** The sections under [mode], in the page's order; none for a page with nothing to set. */
    fun of(mode: PreviewMode): List<HomeSection> = ORDER.filter { home(it) == shownAs(mode) }

    /** Idle's first, as the page had them. */
    private val ORDER = listOf(
        HomeSection.FACES, HomeSection.ALWAYS_ON, HomeSection.ANIMATIONS, HomeSection.APPS, HomeSection.CAMERA, HomeSection.THREE_FINGERS,
        HomeSection.ADVANCED, HomeSection.CHARGING, HomeSection.NOTIFICATIONS, HomeSection.MUSIC, HomeSection.NAVIGATION, HomeSection.CLAUDE, HomeSection.NOTES,
    )
}
