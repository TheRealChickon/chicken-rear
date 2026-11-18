package dev.kb896.aiusage.rear

import kotlin.math.abs

/**
 * What a finger on the back means, while a face of ours is what the back shows. The cover and the
 * scene are not trusted layers (RearCover, ShellScene), so no touch reaches Xiaomi's rear screen
 * under them, ever: the raw touchscreen is read instead, and this says what the finger asked for.
 * Nothing it says shows Xiaomi's screen; what a swipe does is the shell's to decide from the page it was made on:
 *
 * - on the scene: right to left the usage page, bottom up the pinned notes, left to right Xiaomi's
 *   Camera (opened directly, never by way of Xiaomi's launcher), down nothing (the top edge's
 *   pull is the notifications', and not seen here);
 * - on a page: back the way it came, and on the notes sideways from one note to the next.
 *
 * On the scene and on every page of ours:
 * - a TAP: the back at full brightness for a while. Never a page changed.
 * - a DOUBLE TAP ([Out.DoubleTap]): the back dimmed, or bright again if it was dim. The second tap is one that lands
 *   within [DOUBLE_MS] of the first one's lift and within [DOUBLE_SLOP] of it, and lifts before [HOLD_MS]: held
 *   longer on the scene it is the next thing.
 *
 * And on the scene alone:
 * - a PRESS AND HOLD ([PICKER_HOLD_MS], in place within [HOLD_SLOP]): the face picker (FacePicker, Xiaomi's own,
 *   copied; its 300 ms long-press timeout is raised past [TAP_MS], so a slow tap stays a tap and the first tap of a
 *   double tap never opens it) ([Out.FirstDown] as it lands, then [heldAlone] asked once the time is
 *   up; the shell asks only while the face is what shows, so a press on a live page's button is never eaten).
 * - a DOUBLE TAP, THE SECOND HELD ([HOLD_MS]): the back off, until he wakes it himself
 *   ([Out.SecondDown] as the second lands, then [held] asked once the time is up).
 *
 * And on the notes page alone:
 * - a PRESS AND HOLD ([NOTE_HOLD_MS]): the note lifted to be put in order (RearNav's NoteHold; [Out.NotesDown] as it
 *   lands, then [heldOnNotes] asked once the time is up). The moon's hold and double tap and hold are as they were.
 *
 * A swipe is one made on purpose ([TRAVEL] within [SWIPE_MS], clearly one way): the hand that
 * holds the phone rests on this screen, and a short or slow drift of it is nothing.
 *
 * TWO OR MORE FINGERS DOWN TOGETHER ([crowded], told by the shell as RearTouches says so): none of the above comes of
 * them. A finger that only rests there (the hand holding the phone) does not count: RearTouches lets it go.
 *
 * Pure: panel units in, as RearTouches gives them. One finger; a new landing starts over.
 */
class SceneTouch {

    enum class Mode {
        /** Not ours to read: something else of the shell's is up, or the top edge's pull has it. */
        NONE,
        /** The scene (or the bare cover) is what shows. */
        SCENE,
        /** The usage page is up. */
        USAGE,
        /** The pinned notes are up. */
        NOTES,
        /** The charging page is what shows: a tap and a double tap are read, its swipe is its own. */
        CHARGE,
        /** The face picker is up: its finger is its own (PickerTouch), and nothing here comes of it. */
        PICKER,
        /** A note's place reached, its page standing (Pin to location): its bin, a stroke down, and a double tap and hold is the back off. */
        ARRIVAL,
    }

    enum class Way { LEFT, RIGHT, UP, DOWN }

    sealed interface Out {
        /** Down and up in place, at [x], [y]. */
        data class Tap(val x: Float, val y: Float, val on: Mode) : Out
        /**
         * The second tap of a double tap has lifted, in place and before [HOLD_MS]: no [Tap] for it.
         * [gapMs]: from the first one's lift to this one's landing; [apart]: how far from it, in
         * panel units; [pressMs]: how long this one was down. For the log, to tune by a real finger.
         */
        data class DoubleTap(val x: Float, val y: Float, val on: Mode, val gapMs: Long, val apart: Float, val pressMs: Long) : Out
        /** A swipe on purpose, [way], made on [on]. Once per finger. */
        data class Swipe(val way: Way, val on: Mode) : Out {
            /** When its finger landed, and when it became a swipe (the reader's clock). Not part of what a swipe is. */
            var downAt = 0L
            var at = 0L
            /** Where its finger landed, in panel units. */
            var fromX = 0f
            var fromY = 0f
        }
        /** The second of a double tap has landed on the scene: ask [held] in [HOLD_MS]. */
        data object SecondDown : Out
        /** A finger has landed on the scene, not as a double tap's second: ask [heldAlone] in [PICKER_HOLD_MS]. */
        data object FirstDown : Out
        /** A finger has landed on the notes page, not as a double tap's second: ask [heldOnNotes] in [NOTE_HOLD_MS]. */
        data object NotesDown : Out
    }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var done = false
    private var isDown = false
    private var second = false
    private var strayed = false
    /** Moved past [HOLD_SLOP] (or taken by another reader): not a hold for the picker. */
    private var holdStrayed = false
    private var tapAt = 0L
    private var tapX = 0f
    private var tapY = 0f
    private var gap = 0L
    private var apart = 0f

    /** The finger that is down is another reader's from here (PageDrag follows it): no swipe, tap or hold comes of it. */
    @Synchronized
    fun take() { done = true; strayed = true; holdStrayed = true }

    /** The tap just made was a button's: it is not the first of a double tap. */
    @Synchronized
    fun forget() { tapAt = 0L }

    /**
     * ANOTHER FINGER IS DOWN WITH THIS ONE (RearTouches.crowded; three fingers held are ThreeHold's): nothing comes of
     * it any more (no tap, double tap, swipe, picker hold, notes hold, or double tap and hold), and a tap made before
     * it is not the first of a double tap.
     */
    @Synchronized
    fun crowded() { take(); forget() }

    /**
     * Whether a finger that landed alone on the scene (not a double tap's second) is still held in
     * place (within [HOLD_SLOP]), [PICKER_HOLD_MS] after it landed. True once: that finger then means nothing more here.
     */
    @Synchronized
    fun heldAlone(now: Long): Boolean {
        if (!isDown || second || mode != Mode.SCENE || holdStrayed || done || now - downAt < PICKER_HOLD_MS) return false
        done = true
        return true
    }

    /**
     * Whether a finger that landed alone on the notes page is still held in place, [NOTE_HOLD_MS] after it landed.
     * True once: that finger then means nothing more to this reader (no tap as it lifts, no swipe: the carousel has it).
     */
    @Synchronized
    fun heldOnNotes(now: Long): Boolean {
        if (!isDown || second || mode != Mode.NOTES || strayed || done || now - downAt < NOTE_HOLD_MS) return false
        done = true
        return true
    }

    /**
     * Whether the second tap of a double tap is still held in place, [HOLD_MS] after it landed.
     * True once: that finger then means nothing more (no tap as it lifts).
     */
    @Synchronized
    fun held(now: Long): Boolean {
        if (!isDown || !second || (mode != Mode.SCENE && mode != Mode.ARRIVAL) || strayed || done || now - downAt < HOLD_MS) return false
        done = true
        return true
    }

    /** [modeNow] is asked as a finger lands, and holds for that finger. */
    @Synchronized
    fun feed(e: RearTouches.Event, modeNow: () -> Mode): List<Out> = when (e) {
        is RearTouches.Down -> {
            mode = modeNow()
            downX = e.x; downY = e.y; downAt = e.atMs
            done = false; isDown = true; strayed = false; holdStrayed = false
            gap = e.atMs - tapAt
            apart = maxOf(abs(e.x - tapX), abs(e.y - tapY))
            // The picker's finger is the picker's: nothing of it here, and no tap of it is the first of a double tap.
            if (mode == Mode.PICKER) { tapAt = 0L; done = true }
            second = mode != Mode.NONE && mode != Mode.PICKER && tapAt > 0L && gap <= DOUBLE_MS && apart <= DOUBLE_SLOP
            when {
                second && (mode == Mode.SCENE || mode == Mode.ARRIVAL) -> listOf(Out.SecondDown)
                second -> emptyList()
                mode == Mode.SCENE -> listOf(Out.FirstDown)
                mode == Mode.NOTES -> listOf(Out.NotesDown)
                else -> emptyList()
            }
        }
        is RearTouches.Move -> {
            val dx = e.x - downX
            val dy = e.y - downY
            if (maxOf(abs(dx), abs(dy)) >= SLOP) strayed = true
            if (maxOf(abs(dx), abs(dy)) >= HOLD_SLOP) holdStrayed = true
            val way = when {
                done || mode == Mode.NONE || mode == Mode.PICKER || e.atMs - downAt > SWIPE_MS -> null
                -dx >= TRAVEL && -dx > abs(dy) * 1.5f -> Way.LEFT
                dx >= TRAVEL && dx > abs(dy) * 1.5f -> Way.RIGHT
                -dy >= TRAVEL && -dy > abs(dx) * 1.5f -> Way.UP
                dy >= TRAVEL && dy > abs(dx) * 1.5f -> Way.DOWN
                else -> null
            }
            if (way == null) emptyList() else { done = true; listOf(Out.Swipe(way, mode).also { it.downAt = downAt; it.at = e.atMs; it.fromX = downX; it.fromY = downY }) }
        }
        is RearTouches.Up -> {
            val out: List<Out> = when {
                done || mode == Mode.NONE || mode == Mode.PICKER -> emptyList()
                // The second of a double tap, lifted before it is a hold: the two are one thing, and not the first of a third.
                second && maxOf(abs(e.x - downX), abs(e.y - downY)) < TAP_SLOP && e.atMs - downAt < HOLD_MS -> {
                    tapAt = 0L
                    listOf(Out.DoubleTap(e.x, e.y, mode, gap, apart, e.atMs - downAt))
                }
                maxOf(abs(e.x - downX), abs(e.y - downY)) < TAP_SLOP && e.atMs - downAt <= TAP_MS -> {
                    tapAt = e.atMs; tapX = e.x; tapY = e.y
                    listOf(Out.Tap(e.x, e.y, mode))
                }
                else -> emptyList()
            }
            done = false; isDown = false; second = false; mode = Mode.NONE
            out
        }
    }

    companion object {
        /**
         * A swipe on purpose: a good centimetre of the back (the panel is 904 units across some
         * 50 mm), clearly one way, and made within [SWIPE_MS] of landing.
         */
        const val TRAVEL = 150f
        const val SWIPE_MS = 700L
        const val SLOP = 16f
        /**
         * A tap is down no longer than this. A real second tap of a double tap was down 303 ms (the log): a finger on
         * the back is slower than one on the front, so not 350.
         */
        const val TAP_MS = 450L
        /**
         * A tap may wander this far between landing and lifting: a real fingertip rolls a millimetre
         * or two as it lifts (the panel is 904 units across some 50 mm, so this is 1.7 mm). [SLOP],
         * under a millimetre, stays the measure of a finger HELD in place.
         */
        const val TAP_SLOP = 30f
        /**
         * The second tap of a double tap: it lands this soon after the first one lifted (Android's own
         * double tap allows 300 ms; a thumb on a screen it cannot see gets a little more), and this
         * near it (some 9 mm: the back is tapped blind).
         */
        const val DOUBLE_MS = 400L
        const val DOUBLE_SLOP = 160f
        /** And held this long, it switches the back off. */
        const val HOLD_MS = 550L
        /**
         * A finger alone held this long, in place: the face picker. Xiaomi's own is 300 (ViewConfiguration's long-press
         * timeout on the phone), but HERE A TAP MAY BE DOWN UP TO [TAP_MS] (450: his own taps on the back were down
         * 303 ms), so at 300 a slow tap, and the first tap of a double tap (the dim) or of a double tap held (off), opened
         * the picker instead. It must be longer than a tap, as the notes' hold is ([NOTE_HOLD_MS]); a test holds that.
         */
        const val PICKER_HOLD_MS = 500L
        /** Held in place for the picker: Xiaomi's touch slop, 22 px = 20 units. Kept apart from [SLOP], which other readers use. */
        const val HOLD_SLOP = 20f
        /** On the notes page, held this long in place: the note lifted to be put in order. Longer than a tap ([TAP_MS]). */
        const val NOTE_HOLD_MS = 500L
    }
}
