package dev.kb896.aiusage.rear

/**
 * A page of the back asked for by NAME, as an index of the pager as it stands right now.
 *
 * Indices are the pager's own business and they move: with no account connected there is no
 * usage page and the clock page is home; the media page is there only while something plays.
 * So nothing outside the panel may hold a page number — a command says "usage", and this says
 * which page that is at this moment, or that there is none.
 *
 * Pure, so the shifting is pinned by a test rather than by a phone with music playing.
 */
object NamedPage {

    const val CLOCK = "clock"
    const val USAGE = "usage"
    const val MEDIA = "media"
    const val HOME = "home"
    const val NEXT = "next"
    const val PREV = "prev"

    /**
     * [name] as a page index, or null when there is no such page now. [current] is the page
     * the pager is on, [pages] how many it has, [readoutGone] whether the usage page is absent
     * (no account connected), [clock], [usage] and [media] the indices those pages have when
     * they exist (RearDialView.PAGE_NOTICES, PAGE_READOUT and its mediaPage).
     */
    fun resolve(
        name: String, current: Int, pages: Int, readoutGone: Boolean,
        clock: Int, usage: Int, media: Int,
    ): Int? {
        if (pages <= 0) return null
        val last = pages - 1
        return when (name.trim().lowercase()) {
            CLOCK -> clock.takeIf { it <= last }
            USAGE -> usage.takeIf { !readoutGone && it <= last }
            MEDIA -> media.takeIf { it <= last }
            HOME -> (if (readoutGone) clock else usage).takeIf { it <= last }
            NEXT -> (current + 1).coerceAtMost(last)
            PREV -> (current - 1).coerceAtLeast(0)
            else -> null
        }
    }
}
