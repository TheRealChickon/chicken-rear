package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel
import kotlin.math.abs

class NoticePages(private val left: Float) {

    /** One page as it stands: [item] null is the logos' page. [dy] 0 is in place, -HEIGHT fully away above. */
    class Page(val item: NoticeShade.Item?, val dx: Float, val dy: Float, val alpha: Float)

    /** A frame: [pages] from the lowest up; [line] and [focus] for the logos along the edge; [empty] for none at all. */
    class Scene(
        val pages: List<Page>, val line: List<NoticeShade.Item>, val focus: Float, val empty: Boolean,
        /** "Dismissed" or "Snoozed 1 h" rising as a page is thrown; null when none. */
        val label: NoticeThrow.Label? = null,
    )

    /** The pull brings a page of logos first (the other reading). Set before a pull. */
    var logosFirst = false
    /** The buttons' words are drawn (false while the pinned apps' row stands at the foot): a button not seen is not pressed. */
    var words = true

    private var items: List<NoticeShade.Item> = emptyList()
    /** Swiped away here and not yet gone from the app's own list: not shown again meanwhile. */
    private val swiped = HashSet<String>()
    /** The page on top: a notification's key, [LOGOS], or null for the first there is. */
    private var at: String? = null

    private var pulling = false
    private var pullFrom = 0f
    private var pullAt = 0f
    private var lastPullY = 0f
    private var lastPullMs = 0L
    private var pullSpeed = 0f
    private var settleFrom = 0f
    private var settleTo = 0f
    private var settleAt = 0L
    /** How far a drag up has taken the whole pile towards shut. */
    private var closing = 0f
    /** How far short of its place the pile stood when the finger took it up; null while the finger has not. */
    private var heldFrom: Float? = null
    private val pullChase = ControlCenter.Chase()
    private val closeChase = ControlCenter.Chase()

    /** What a finger does: SIDE throws the page out, TURN sends the logos' page up, CLOSE takes the whole pile up. */
    private enum class Finger { NONE, UNDECIDED, SIDE, TURN, CLOSE }
    private var finger = Finger.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var lastMs = 0L
    private var speed = 0f
    private var fromBottom = false
    private var downMs = 0L
    /** The page that was on top as the finger landed: a lift acts on that one or on none (a notification that arrives meanwhile is not thrown out or pressed unseen). */
    private var fingerKey: String? = null
    /** The last one was thrown out: the pile goes with it, and no "nothing waiting" page comes in its place. */
    private var emptied = false

    /** The logos' page under the finger, or on its way (let go, or a logo tapped): [moving] at [from]..[to] over [under]. */
    private class Turn(val moving: String, val under: String?, val from: Float, val to: Float = from, val atMs: Long = -1L)
    private var turn: Turn? = null

    private var swipeDx = 0f
    /** Where the finger has gone across; [swipeDx] is where the page stands (less, for one that may not be thrown). */
    private var swipeRaw = 0f
    private var swiping = false
    private var springFrom = 0f
    private var springAt = -1L
    private class Leaving(val item: NoticeShade.Item, val fromDx: Float, val dir: Float, val atMs: Long)
    private var leaving: Leaving? = null
    /** The last throw, for its label: what it did and when. */
    private var thrown: NoticeThrow.Kind? = null
    private var thrownAt = -1L

    private fun sheets(): List<String> =
        if (logosFirst && items.isNotEmpty()) listOf(LOGOS) + items.map { it.key } else items.map { it.key }

    private fun top(): String? = sheets().let { s -> at?.takeIf { it in s } ?: s.firstOrNull() }

    private fun itemOf(key: String?): NoticeShade.Item? = items.firstOrNull { it.key == key }

    /** The list as the app has it now, newest first. The page on top stays the one it was, wherever it now stands. */
    fun items(next: List<NoticeShade.Item>) {
        // The page he is on stays the page he is on: with no page named yet it is the first there IS, not the first there will be.
        if (at == null && items.isNotEmpty() && !(logosFirst)) at = items.first().key
        swiped.retainAll(next.mapTo(HashSet()) { it.key })
        val merged = next.filter { it.key !in swiped }
        val was = items.indexOfFirst { it.key == at }
        items = merged
        if (at != null && at != LOGOS && merged.none { it.key == at }) {
            at = merged.getOrNull(was.coerceIn(0, (merged.size - 1).coerceAtLeast(0)))?.key
            swiping = false; swipeDx = 0f; springAt = -1L
        }
        val s = sheets()
        turn?.let { t -> if (t.moving !in s || (t.under != null && t.under !in s)) turn = null }
    }

    val count: Int get() = items.size

    fun beginPull(downY: Float, y: Float, now: Long) {
        pulling = true
        pullFrom = downY
        pullAt = y
        pullChase.snap(y - downY, now)
        lastPullY = y
        lastPullMs = now
        pullSpeed = 0f
        settleTo = 0f
        closing = 0f
        closeChase.reset()
        // Every opening starts at the newest.
        at = null; turn = null; swiping = false; swipeDx = 0f; springAt = -1L; emptied = false
    }

    fun movePull(y: Float, now: Long) {
        if (!pulling) return
        pullAt = y
        if (now > lastPullMs) pullSpeed = (y - lastPullY) / (now - lastPullMs)
        lastPullY = y
        lastPullMs = now
    }

    fun endPull(y: Float, now: Long): List<NoticeShade.Effect> {
        if (!pulling) return emptyList()
        movePull(y, now)
        pulling = false
        val from = ControlCenter.progress(false, pullChase.shown.takeIf { !it.isNaN() } ?: (pullAt - pullFrom))
        return settle(from, ControlCenter.release(false, pullAt - pullFrom, pullSpeed), now)
    }

    /** Shut, by its settle. */
    fun close(now: Long): List<NoticeShade.Effect> = settle(progress(now), false, now)

    fun reset() {
        pulling = false
        settleFrom = 0f; settleTo = 0f; settleAt = 0L
        closing = 0f; closeChase.reset()
        finger = Finger.NONE
        at = null; turn = null; leaving = null
        swiping = false; swipeDx = 0f; swipeRaw = 0f; springAt = -1L; emptied = false
        thrown = null; thrownAt = -1L
        swiped.clear()
    }

    private fun settle(from: Float, open: Boolean, now: Long): List<NoticeShade.Effect> {
        settleFrom = from
        settleTo = if (open) 1f else 0f
        settleAt = now
        closing = 0f
        closeChase.reset()
        return if (open) emptyList() else listOf(NoticeShade.Effect.ShutAt(now + NoticeShade.SETTLE_MS))
    }

    /** How far down the pile is, 0 (away above) to 1 (in place). */
    fun progress(now: Long): Float {
        if (pulling) return ControlCenter.progress(false, pullChase.to(pullAt - pullFrom, now))
        if (closing > 0f || closeChase.shown > 0f) return ControlCenter.progress(true, -closeChase.to(closing, now))
        val t = ((now - settleAt).toFloat() / NoticeShade.SETTLE_MS).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        return settleFrom + (settleTo - settleFrom) * eased
    }

    /** Open or being pulled: a finger landing now is its own (see NoticeShade.shown for why not while it settles shut). */
    fun shown(now: Long): Boolean = pulling || (settleTo > 0f && progress(now) > 0f)

    fun isShut(now: Long): Boolean = !pulling && finger == Finger.NONE && progress(now) <= 0f

    /**
     * THE PILE STANDS OVER THE WHOLE BACK, STILL: down all the way ([progress], the frame's own), nothing [moving], no
     * finger on it, and [s] (the frame) one page in its place, or the one line with nothing waiting. Each of these is
     * the pop-up's own picture, opaque edge to edge (NoticePop's ground), so nothing under the pile can be seen
     * (HiddenFrames). A finger landing on it ends this at once.
     */
    fun covers(progress: Float, moving: Boolean, s: Scene): Boolean =
        finger == Finger.NONE && !moving && progress >= 1f &&
            (if (s.pages.isEmpty()) s.empty else s.pages.all { it.dx == 0f && it.dy == 0f && it.alpha >= 1f })

    /** Something still moves of itself. */
    fun moving(now: Long): Boolean =
        pulling || now - settleAt < NoticeShade.SETTLE_MS || leaving != null ||
            (finger != Finger.NONE && closeChase.shown != closing) ||
            (turn?.let { it.atMs >= 0L && now - it.atMs < TURN_MS } ?: false) ||
            (springAt >= 0L && now - springAt < NoticeThrow.SPRING_MS) ||
            (thrownAt >= 0L && now - thrownAt < NoticeThrow.LABEL_MS)

    fun fingerDown(x: Float, y: Float, now: Long) {
        // What was still on its way is where it was going.
        turn = null; springAt = -1L; swiping = false; swipeDx = 0f; swipeRaw = 0f
        finger = Finger.UNDECIDED
        closeChase.snap(closing, now)
        heldFrom = null
        downX = x; downY = y; lastY = y; lastMs = now; speed = 0f
        downMs = now
        fingerKey = top()
        fromBottom = y >= Panel.HEIGHT - NoticeShade.BOTTOM_EDGE
    }

    fun fingerMove(x: Float, y: Float, now: Long) {
        if (finger == Finger.UNDECIDED) {
            val dx = abs(x - downX)
            val dy = abs(y - downY)
            if (maxOf(dx, dy) < EdgeGestures.SLOP) return
            finger = when {
                dx > dy && itemOf(top()) != null -> Finger.SIDE
                // The logos' page alone turns (up, to the pages under it). On a page, up or down, the finger is the
                // whole pile's: up takes it away, down does nothing; no page is turned to the next or the one before.
                top() == LOGOS && !fromBottom -> Finger.TURN
                else -> Finger.CLOSE
            }
            swiping = finger == Finger.SIDE
        }
        if (now > lastMs) speed = (y - lastY) / (now - lastMs)
        lastY = y
        lastMs = now
        val d = y - downY
        when (finger) {
            Finger.SIDE -> {
                swipeRaw = x - downX
                // One that may not be thrown gives a little under the finger, and no more.
                swipeDx = if (itemOf(fingerKey)?.clearable == false) NoticeThrow.give(swipeRaw) else swipeRaw
            }
            Finger.CLOSE -> {
                // The pile is the finger's from the first move up, taken from where it stands then: still settling in
                // after the pull, it stops there and does not jump back to its place first. From there it follows the
                // finger both ways, no lower than its place. Down before that, nothing: it goes on as it was.
                if (heldFrom == null && d < 0f) {
                    val from = (1f - progress(now)) * ControlCenter.TRAVEL
                    heldFrom = from
                    settleFrom = settleTo
                    closeChase.snap(from, now)
                }
                closing = heldFrom?.let { (it - d).coerceAtLeast(0f) } ?: 0f
            }
            Finger.TURN -> {
                // Up, the logos' page goes up and the newest is under it; down, nothing.
                val under = sheets().getOrNull(1)
                closing = 0f
                turn = if (d < 0f && top() == LOGOS && under != null) Turn(LOGOS, under, d.coerceAtLeast(-Panel.HEIGHT.toFloat())) else null
            }
            else -> Unit
        }
    }

    fun fingerUp(x: Float, y: Float, now: Long): List<NoticeShade.Effect> {
        val was = finger
        finger = Finger.NONE
        return when (was) {
            Finger.SIDE -> {
                val dx = swipeRaw
                val shownDx = swipeDx
                val item = itemOf(top())
                swiping = false
                swipeDx = 0f; swipeRaw = 0f
                if (item == null) return emptyList()
                // Thrown out on purpose: far enough, the page that was there as the finger landed, and a stroke, not a
                // hand that rested and drifted (within a swipe's time of landing, as on every other page).
                val meant = item.key == fingerKey && NoticeThrow.meant(dx, downMs, now)
                if (meant && item.clearable) {
                    val next = after(item.key)
                    // The page goes the way the finger went; what that does is NoticeThrow's (RearNav.DISMISS_WAY).
                    val kind = NoticeThrow.kindOfDx(dx)
                    leaving = Leaving(item, shownDx, if (dx < 0f) -1f else 1f, now)
                    thrown = kind; thrownAt = now
                    swiped += item.key
                    items = items.filter { it.key != item.key }
                    at = next?.takeIf { it in sheets() }
                    val effect = if (kind == NoticeThrow.Kind.DISMISS) NoticeShade.Effect.Dismissed(item.key) else NoticeShade.Effect.Snoozed(item.key)
                    // The last one: the pile goes up with it in the same step (no grey "nothing waiting" page jumps in).
                    if (items.isEmpty()) { emptied = true; listOf(effect) + settle(progress(now), false, now) }
                    else listOf(effect)
                } else {
                    // Short, or one that may not be thrown: back to its place on a spring, and it stays.
                    springFrom = shownDx; springAt = now
                    if (meant) listOf(NoticeShade.Effect.Refused(item.key)) else emptyList()
                }
            }
            Finger.CLOSE -> letGo(now)
            Finger.TURN -> {
                val t = turn
                if (t == null) letGo(now) else {
                    // The logos' page: far enough or flicked, it goes up and the newest is on top; else it comes back.
                    val away = -t.from >= TURN_DY || speed < -ControlCenter.FLING
                    turn = Turn(t.moving, t.under, t.from, if (away) -Panel.HEIGHT.toFloat() else 0f, now)
                    at = if (away) t.under else t.moving
                    emptyList()
                }
            }
            Finger.UNDECIDED -> {
                val on = top()
                val item = itemOf(on)
                when {
                    on == LOGOS -> {
                        val apps = apps(items)
                        val i = logoBoxes(apps.size, left).indexOfFirst { b -> abs(x - b[0]) <= b[2] / 2f + LOGO_SLOP && abs(y - b[1]) <= b[2] / 2f + LOGO_SLOP }
                        val to = apps.getOrNull(i)?.key
                        if (to != null) { turn = Turn(LOGOS, to, 0f, -Panel.HEIGHT.toFloat(), now); at = to }
                        emptyList()
                    }
                    item == null -> close(now)
                    // A hand that rested there and lifts is no tap, and a page that came under the finger is not the one meant.
                    item.key != fingerKey || now - downMs > SceneTouch.TAP_MS -> emptyList()
                    else -> {
                        val a = if (words) actionAt(x, y, item.actions.size, left) else -1
                        listOf(if (a >= 0) NoticeShade.Effect.Action(item.key, a) else NoticeShade.Effect.Tapped(item.key))
                    }
                }
            }
            Finger.NONE -> emptyList()
        }
    }

    /** The whole pile let go: taken up, it shuts or comes back down by the stroke, from where it stands; never taken up, it goes on as it was. */
    private fun letGo(now: Long): List<NoticeShade.Effect> = when {
        closing > 0f -> settle(progress(now), ControlCenter.release(true, -closing, speed), now)
        // Dragged up and back down: let go while it still trails, it settles open (it stood part-way).
        !closeChase.shown.isNaN() && closeChase.shown > 0f -> settle(progress(now), true, now)
        // Down, or up and back with the pile in its place again: nothing (a flick up at the end does not shut it).
        else -> emptyList()
    }

    /** What takes [key]'s place when it is thrown out: the one under it, else the one before it. */
    private fun after(key: String): String? {
        val s = sheets()
        val i = s.indexOf(key)
        return s.getOrNull(i + 1) ?: s.getOrNull(i - 1)?.takeIf { it != LOGOS || items.size > 1 }
    }

    /** The frame at [now]. */
    fun scene(now: Long): Scene {
        leaving?.let { if (now - it.atMs >= NoticeShade.FLY_MS) leaving = null }
        turn?.let { if (it.atMs >= 0L && now - it.atMs >= TURN_MS) turn = null }
        if (springAt >= 0L && now - springAt >= NoticeThrow.SPRING_MS) springAt = -1L
        if (thrownAt >= 0L && now - thrownAt >= NoticeThrow.LABEL_MS) { thrownAt = -1L; thrown = null }

        val pages = ArrayList<Page>(3)
        val on = top()
        fun place(key: String?): Float = if (key == LOGOS) -1f else items.indexOfFirst { it.key == key }.toFloat()
        var focus = place(on)
        val t = turn
        if (t != null) {
            val dy = if (t.atMs < 0L) t.from else {
                val p = ((now - t.atMs).toFloat() / TURN_MS).coerceIn(0f, 1f)
                t.from + (t.to - t.from) * ease(p)
            }
            if (t.under != null) pages += Page(itemOf(t.under), 0f, 0f, 1f)
            pages += Page(itemOf(t.moving), 0f, dy, 1f)
            val part = (1f + dy / Panel.HEIGHT).coerceIn(0f, 1f)
            focus = if (t.under == null) place(t.moving) else place(t.moving) * part + place(t.under) * (1f - part)
        } else if (on != null) {
            val dx = when {
                swiping -> swipeDx
                // Back on a spring: it passes its place a little once and comes to rest in it.
                springAt >= 0L -> springFrom * (1f - NoticeThrow.spring((now - springAt).toFloat() / NoticeThrow.SPRING_MS))
                else -> 0f
            }
            if (dx != 0f) after(on)?.let { pages += Page(itemOf(it), 0f, 0f, 1f) }
            // NO FADE: a page aside is the whole page, its corners the display's (NoticePagesArt).
            pages += Page(itemOf(on), dx, 0f, 1f)
        }
        leaving?.let { l ->
            val p = ((now - l.atMs).toFloat() / NoticeShade.FLY_MS).coerceIn(0f, 1f)
            val dx = l.fromDx + (l.dir * travel - l.fromDx) * (1f - (1f - p) * (1f - p))
            pages += Page(l.item, dx, 0f, 1f)
        }
        // The last one thrown takes the pile up with it: its label is then on a layer of its own (ShellNotices), not on the pile.
        val label = if (emptied) null else thrown?.let { k -> NoticeThrow.labelAt(k, now - thrownAt) }
        return Scene(pages, items, focus, items.isEmpty() && leaving == null && !emptied, label)
    }

    /** How far a page goes sideways to be gone: the width of what he sees. */
    private val travel: Float get() = Panel.WIDTH - left

    companion object {
        /** The logos' page, among the pages' keys. */
        const val LOGOS = "\u0000logos"

        /** A stroke this long sends the logos' page up; shorter, it comes back (a flick sends it whatever its length). */
        const val TURN_DY = 100f
        /** The logos' page's way up or back once let go: about the pop-up's own way in. */
        const val TURN_MS = 320L

        /** The pop-up's own easing as it comes in. */
        fun ease(p: Float): Float = 1f - (1f - p) * (1f - p) * (1f - p)

        // A page's buttons: up to [ACTIONS] plain words side by side at its foot, centred in what he sees. Fixed
        // places, so a finger finds them without a word being measured (ShellNotices draws them there).
        const val ACTIONS = 2
        /** A word's room across, and where the words stand (their middle line) and from where up a finger is on them. */
        const val WORD_W = 250f
        const val WORD_Y = 492f
        const val WORD_TOP = 452f

        /** The middle of word [i] of [count], across. */
        fun wordX(i: Int, count: Int, left: Float): Float {
            val cx = (left + Panel.WIDTH) / 2f
            val n = minOf(count, ACTIONS)
            return cx + (i - (n - 1) / 2f) * WORD_W
        }

        /** Which button a finger at [x], [y] is on, of [count]; -1 for none. */
        fun actionAt(x: Float, y: Float, count: Int, left: Float): Int {
            val n = minOf(count, ACTIONS)
            if (n <= 0 || y < WORD_TOP) return -1
            for (i in 0 until n) if (abs(x - wordX(i, n, left)) <= WORD_W / 2f) return i
            return -1
        }

        /** The logos' page: one logo per app that has something waiting, the newest first, [LOGOS_MOST] at most. */
        fun apps(items: List<NoticeShade.Item>): List<NoticeShade.Item> = items.distinctBy { it.pkg }.take(LOGOS_MOST)

        const val LOGOS_MOST = 8
        private const val LOGOS_ROW = 4
        const val LOGO = 104f
        private const val LOGO_GAP = 40f
        private const val LOGO_SLOP = 14f

        /** Where the [n] logos stand: each its middle across, its middle down, its size. Rows of four, centred in what he sees. */
        fun logoBoxes(n: Int, left: Float): List<FloatArray> {
            val cx = (left + Panel.WIDTH) / 2f
            val rows = (n + LOGOS_ROW - 1) / LOGOS_ROW
            val out = ArrayList<FloatArray>(n)
            for (k in 0 until n) {
                val row = k / LOGOS_ROW
                val inRow = if (row == rows - 1) n - row * LOGOS_ROW else LOGOS_ROW
                val col = k % LOGOS_ROW
                out += floatArrayOf(
                    cx + (col - (inRow - 1) / 2f) * (LOGO + LOGO_GAP),
                    Panel.HEIGHT / 2f + (row - (rows - 1) / 2f) * (LOGO + LOGO_GAP),
                    LOGO,
                )
            }
            return out
        }
    }
}
