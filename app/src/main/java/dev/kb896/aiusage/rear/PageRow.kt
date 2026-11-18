package dev.kb896.aiusage.rear

/**
 * THE BACK'S PAGES LIE IN A ROW: home (the moon) | the live page | Claude usage.
 *
 * "Going right" is the finger moving right to left (what has always brought the usage page from the moon);
 * "going left" is the finger left to right (what has always left a page). The live page is whatever is
 * live: a call, a timer, what is playing, the charging page. With nothing live the row is home | usage, as
 * it always was. Left of home there is no page: that swipe on the moon is Xiaomi's Camera, as before.
 *
 * A live page comes up by itself when its thing starts, and can be sent away (RearNav, which decides every page
 * of the back; this file keeps what says "the same thing or a new one" and the settings' preview's row): the moon is back
 * and the timer runs on. SENT AWAY, IT STAYS AWAY: not back at a timer's tick or the next track. Back by
 * itself only for something new ([Token] says what counts): another timer, the timer running out, playing
 * started again after a stop, another call or its being answered. Back by hand: going right from home.
 */
object PageRow {
    enum class Page { HOME, LIVE, USAGE }

    /**
     * Where a swipe across leads from [at]: [goRight] for the finger right to left. [live]: there is a
     * live page (showing or sent away). Null: no page that way (left of home, right of usage).
     */
    fun next(at: Page, goRight: Boolean, live: Boolean): Page? = when (at) {
        Page.HOME -> if (!goRight) null else if (live) Page.LIVE else Page.USAGE
        Page.LIVE -> if (goRight) Page.USAGE else Page.HOME
        Page.USAGE -> if (goRight) null else if (live) Page.LIVE else Page.HOME
    }

    enum class Kind { CALL, NAVIGATION, TIMER, MUSIC }

    /**
     * What the live page is of, as far as "is this still the one he sent away" goes: equal tokens are
     * the same thing going on (a second less on the timer, the next track), unequal ones something new.
     */
    data class Token(val kind: Kind, val key: String, val phase: Long)

    /** A call: another call is new, and so is its being answered. */
    fun call(key: String, ringing: Boolean) = Token(Kind.CALL, key, if (ringing) 0L else 1L)

    /** A route (NavFace): each step of it is the same route (its notification's key stays); another key is a new route. */
    fun navigation(key: String) = Token(Kind.NAVIGATION, key, 0L)

    /** A timer: another timer (its key, or how long it is in all) is new, and so is its running out. Paused or going on: the same. */
    fun timer(key: String, totalMs: Long, over: Boolean) = Token(Kind.TIMER, key, ((totalMs + TIMER_STEP_MS / 2) / TIMER_STEP_MS) * 2L + if (over) 1L else 0L)

    /**
     * A timer's length is the same within this: the Clock's own figure wavers (seen with a 20-minute timer: 1199 s in
     * its first word, 1200 s 176 ms later; through two pauses and going on it then stayed 1200), and each change of
     * it would be "another timer" (the review's 30).
     */
    const val TIMER_STEP_MS = 5_000L

    /** What is playing: new each time playing starts after it had stopped ([starts] counts those). The next track: the same. */
    fun music(starts: Long) = Token(Kind.MUSIC, "", starts)

    /** The live page that shows: a call, then navigation, then a timer, then what is playing. */
    fun top(call: Token?, timer: Token?, music: Token?, navigation: Token? = null): Token? = call ?: navigation ?: timer ?: music

    /**
     * ONE STROKE, ONE PAGE. What the log showed when one swipe went two pages: the timer brought back, and 0.8 s
     * later the usage page swiped in, by a second landing of a finger: a second quick stroke, or one long stroke
     * whose contact broke on the way (a finger on a back it cannot see). A finger that LANDED before the page of the
     * last move had settled moves nothing: [SETTLE_MS] from that move. The reader gives one swipe per landing
     * already, however long or fast the stroke.
     *
     * [SETTLE_MS] WAS 700 AND DROPPED REAL SECOND SWIPES (the log said "a stroke ignored" twice, each some 0.7 s
     * after the move before it; a hand swipes about once every 0.7 to 1 s). It is the time of a page's slide now
     * (ShellPage.SLIDE_MS): a contact that breaks and lands again within it is still one stroke, and a second swipe
     * made on purpose is never that soon.
     */
    class Strokes {
        private var movedAt = Long.MIN_VALUE / 2

        /** Whether a stroke whose finger landed at [landedAt] may move along the row. */
        @Synchronized fun mayMove(landedAt: Long): Boolean = landedAt - movedAt >= SETTLE_MS

        /** A stroke moved the row (or opened the Camera) at [at]. */
        @Synchronized fun moved(at: Long) { movedAt = at }
    }

    const val SETTLE_MS = 250L
}
