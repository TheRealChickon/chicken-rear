package dev.kb896.aiusage.rear

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

object NoticeThrow {
    enum class Kind { DISMISS, SNOOZE }

    /** How long a snooze is: one hour, as Android counts it (NotificationListenerService.snoozeNotification). */
    const val SNOOZE_MS = 60L * 60L * 1000L

    /** What a sideways stroke going [way] does to a notification; null for up and down. */
    fun kindOf(way: RearNav.Way, dismissWay: RearNav.Way = RearNav.DISMISS_WAY): Kind? = when {
        way != RearNav.Way.LEFT && way != RearNav.Way.RIGHT -> null
        way == dismissWay -> Kind.DISMISS
        else -> Kind.SNOOZE
    }

    /** The same for a page dragged [dx] across (panel units, negative = the finger went right to left). */
    fun kindOfDx(dx: Float, dismissWay: RearNav.Way = RearNav.DISMISS_WAY): Kind =
        kindOf(if (dx < 0f) RearNav.Way.LEFT else RearNav.Way.RIGHT, dismissWay)!!

    /** The way a page goes for [kind]: -1 to the left, +1 to the right. */
    fun dirOf(kind: Kind, dismissWay: RearNav.Way = RearNav.DISMISS_WAY): Float {
        val dismissLeft = dismissWay == RearNav.Way.LEFT
        return if ((kind == Kind.DISMISS) == dismissLeft) -1f else 1f
    }

    // -- what may be thrown -------------------------------------------------------------------------------------

    /**
     * Whether a notification may be dismissed or snoozed from the back: CLEARABLE and NOT ONGOING (Android's own
     * test for a swipe in its shade), and not the state of something that goes on: a call, a timer or stopwatch, an
     * alarm, a player (a media session, or "transport"), a service or a progress. [category] is the notification's
     * (Notification.CATEGORY_*'s strings), [media]: it carries a media session. Such a one springs back and stays.
     */
    fun throwable(ongoing: Boolean, clearable: Boolean, category: String?, media: Boolean): Boolean =
        !ongoing && clearable && !media && category !in FIXED_CATEGORIES

    /** Notification.CATEGORY_CALL, _ALARM, _STOPWATCH, _TRANSPORT, _SERVICE, _PROGRESS, _NAVIGATION, as their strings. */
    val FIXED_CATEGORIES = setOf("call", "alarm", "stopwatch", "transport", "service", "progress", "navigation")

    /** A debug build's made-up notification (pop-demo's `notices|…` and pop-ups): logged, never sent to the phone. */
    const val DEMO_PREFIX = "demo:"
    fun isDemo(key: String): Boolean = key.startsWith(DEMO_PREFIX)

    /**
     * Whether a throw of [key] goes to the phone (dismissed or snoozed there). Never a debug build's made-up one, and
     * never by a MADE-UP FINGER ([madeUpFinger]: a debug build's DemoTouchFeed, rear-touch.py, the regression): a test's
     * stroke may land on a real pop-up of his that came in the middle of a run, and must not take his notification away.
     */
    fun toPhone(key: String, madeUpFinger: Boolean): Boolean = !isDemo(key) && !madeUpFinger

    // -- what is a throw ------------------------------------------------------------------------------------------------

    /**
     * A finger is AIMED at the pop-up up now when it landed at least this long after that pop-up came (or the next took
     * its place): one already on its way as it came was meant for the page under it. The live pages' own "it had only
     * just come" (RearUserService's LIVE_TAP_AFTER_MS): the pop-up's slide in (380 ms) and a reaction to it.
     */
    const val AIM_MS = 700L

    /** [downAt]: when the finger landed; [popAt]: when the pop-up up now came (0 for none ever). Both uptime ms. */
    fun aimed(popAt: Long, downAt: Long): Boolean = popAt > 0L && downAt - popAt >= AIM_MS

    /**
     * A sideways stroke that THROWS a page out (the pile's page or the pop-up): far enough (NoticeShade.DISMISS_DX, the
     * old swipe's 150 units) and made within a swipe's time of landing (SceneTouch.SWIPE_MS). Not a fling's 40 units
     * (PageDrag's measure for turning a page): a tap that rolls fast, or a resting thumb that drifts, is not a throw.
     */
    fun meant(travel: Float, downAt: Long, at: Long): Boolean =
        kotlin.math.abs(travel) >= NoticeShade.DISMISS_DX && at - downAt <= SceneTouch.SWIPE_MS

    /** What the lift of a finger that held the pop-up aside does. */
    enum class Lift {
        /** The pop-up it held is gone (its time) or another took its place: nothing at all, not even a stroke. */
        NOTHING,
        /** Short of a throw: back to its place on a spring. */
        BACK,
        /** A throw: the table says what (RearNav: thrown out, or given back for one that may not be). */
        THROW,
    }

    /**
     * [followed]: which pop-up the finger had (one more for each that came); [now]: which is up now, [up]: whether the
     * navigation still has one; [travel], [downAt], [at]: the stroke (as [meant]).
     */
    fun popLift(followed: Long, now: Long, up: Boolean, travel: Float, downAt: Long, at: Long): Lift = when {
        followed != now || !up -> Lift.NOTHING
        !meant(travel, downAt, at) -> Lift.BACK
        else -> Lift.THROW
    }

    /**
     * The stroke of the finger that landed at [downAt] was a made-up one, wholly or in part: a line of a debug build's
     * made-up feed (DemoTouchFeed) came at [madeUpLineAt], at or after the landing (0: never one). Uptime ms.
     */
    fun madeUp(madeUpLineAt: Long, downAt: Long): Boolean = madeUpLineAt != 0L && madeUpLineAt >= downAt

    // -- the label --------------------------------------------------------------------------------------------------

    const val DISMISSED = "Dismissed"
    const val SNOOZED = "Snoozed 1 h"
    fun words(kind: Kind): String = if (kind == Kind.DISMISS) DISMISSED else SNOOZED

    const val LABEL_MS = 600L
    /** In the pop-up's own pixels (a 976 x 596 back): centred on x 636, the middle of what he sees (NoticePop.CENTRE). */
    const val LABEL_X = NoticePop.CENTRE
    /** Where its line stands as it comes, and how far it rises: in the black head of the page, above any message. */
    const val LABEL_Y = 132f
    const val LABEL_RISE = 44f
    /** Its size: the pop-up's demibold, the size of a pop-up's head line and a little more. */
    const val LABEL_SIZE = 40f
    private const val LABEL_IN_MS = 90L
    private const val LABEL_OUT_MS = 220L

    /** The label at [t] ms after the throw: its words, its baseline (pop-up pixels) and its strength; null once over. */
    class Label(val words: String, val y: Float, val alpha: Float)

    fun labelAt(kind: Kind, t: Long): Label? {
        if (t < 0L || t >= LABEL_MS) return null
        val p = t.toFloat() / LABEL_MS
        // Rises the whole time, quickly at first (ease out), so it goes with the page that is thrown.
        val rise = 1f - (1f - p) * (1f - p)
        val alpha = when {
            t < LABEL_IN_MS -> t.toFloat() / LABEL_IN_MS
            t > LABEL_MS - LABEL_OUT_MS -> (LABEL_MS - t).toFloat() / LABEL_OUT_MS
            else -> 1f
        }.coerceIn(0f, 1f)
        return Label(words(kind), LABEL_Y - LABEL_RISE * rise, alpha)
    }

    // -- the spring a page settles with --------------------------------------------------------------------------

    /** A page let go short, or one that may not be thrown, comes back on a spring over this. */
    const val SPRING_MS = 420L
    /** The spring's damping ratio: under one, so it passes its place a little once and settles. */
    private const val ZETA = 0.72
    /** Its own period's speed: about two of its swings in [SPRING_MS]. */
    private const val OMEGA = 2.0 * PI * 2.1

    /**
     * How much of the way back a spring has gone at [p] (0..1 of [SPRING_MS]): 0 at the start, 1 at the end, a little
     * over 1 once in between (it passes its place and comes back). Exactly 1 at p = 1, so a page comes to rest where it
     * belongs.
     */
    fun spring(p: Float): Float {
        if (p <= 0f) return 0f
        if (p >= 1f) return 1f
        val t = p.toDouble()
        val wd = OMEGA * sqrt(1.0 - ZETA * ZETA)
        val raw = 1.0 - exp(-ZETA * OMEGA * t) * (cos(wd * t) + ZETA / sqrt(1.0 - ZETA * ZETA) * sin(wd * t))
        // What is left of the swing at the end is taken out evenly, so the end is exactly in place.
        val end = 1.0 - exp(-ZETA * OMEGA) * (cos(wd) + ZETA / sqrt(1.0 - ZETA * ZETA) * sin(wd))
        return (raw + (1.0 - end) * t).toFloat()
    }

    /** A page that may not be thrown follows the finger only this much, and no further than [GIVE_MOST]: it gives, it does not go. */
    const val GIVE = 0.35f
    const val GIVE_MOST = 90f
    fun give(dx: Float): Float = (dx * GIVE).coerceIn(-GIVE_MOST, GIVE_MOST)
}
