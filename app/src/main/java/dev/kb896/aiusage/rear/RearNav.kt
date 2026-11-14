package dev.kb896.aiusage.rear

/**
 * THE BACK'S NAVIGATION, ONE STATE MACHINE. Every state of the back and every input has exactly one
 * next state and one animation here, [next]; the shell (RearUserService) asks this for every stroke, tap, live
 * thing, pop-up and change of light, and then makes its layers what the state says ([State], [seen]). Nothing
 * else decides which page shows. The tests hold the whole table, and every sequence of three inputs from every
 * state that can be reached.
 *
 * WHAT THERE IS
 * - The ROW: home (the moon) | the live page | usage. One stroke, one page, both ways, from the middle of the back
 *   or from any edge alike (there is no Back gesture).
 * - The LIVE PAGE is one: of what is live, the highest of call > timer > music > charging ([Live]). The others have
 *   no page while a higher one is live, and get it when that one ends. Sent away (a stroke to the right on it) it
 *   is still one stroke right of home, and comes back by itself only for something NEW on top ([LiveComes]). What
 *   he sent away is kept per kind: music he sent away stays away when the timer that came over it ends.
 * - A POP-UP has the whole back for its time: a stroke only takes it away, a tap presses nothing under it.
 * - The NOTES lie over the row (up from home or a live page, down to leave); the notifications' pages (the SHADE)
 *   over everything of ours (down from the top edge's right half; their own strokes are NoticePages'); a POP-UP
 *   over all of it for its five seconds, and touches go through it to what is under.
 * - DARK, and OTHERS (Xiaomi's Camera or an app has the back): nothing of ours is touched or shown.
 *
 * WHAT IS UNDER STAYS UNDER: with usage, the notes or the shade up, the live page is in its place beneath them.
 * Leaving usage therefore shows the live page at once, by usage sliding off it, and never the moon in between
 * (an earlier bug: the live page was taken away while usage was up and brought back 320 ms after it had gone).
 *
 * Pure: no clock, no Android.
 */
object RearNav {
    enum class Row { HOME, LIVE, USAGE }

    /** What can be live, the lowest first: the highest that is live is the row's live page. */
    enum class Live { CHARGE, MUSIC, TIMER, CALL }

    enum class Way { LEFT, RIGHT, UP, DOWN }

    /** Where a stroke's finger landed. [TOP_EDGE] is the top edge's right half only, the notifications' pull. */
    enum class From { MIDDLE, TOP_EDGE, RIGHT_EDGE, BOTTOM_EDGE, LEFT_EDGE }

    data class State(
        /** The back is lit. */
        val lit: Boolean = false,
        /** The back is ours; false while Xiaomi's Camera or an app is on it. */
        val ours: Boolean = true,
        val row: Row = Row.HOME,
        /** What is live, a bit per [Live]. */
        val lives: Int = 0,
        /**
         * Which live things he has sent away, a bit per [Live]: EACH KIND FOR ITSELF (music he sent away is still
         * away when a timer over it ends; a timer's is not lost by sending a call away).
         */
        val sent: Int = 0,
        val notes: Boolean = false,
        val shade: Boolean = false,
        val pop: Boolean = false,
        /** How many notes are pinned, and which of them the notes page shows (0 the newest; always 0 while it is not up). */
        val pins: Int = 0,
        val pin: Int = 0,
    ) {
        /** The live page's thing: the highest that is live. */
        val live: Live? get() = top(lives)
        /** He sent the live page (the highest live thing's) away: it is one stroke right of home, and the moon shows. */
        val away: Boolean get() = live?.let { sent and (1 shl it.ordinal) != 0 } ?: false
        /** The live page is in the row and not sent away: it is in its place, seen or under something. */
        val liveShown: Boolean get() = lives != 0 && !away
        fun has(kind: Live): Boolean = lives and (1 shl kind.ordinal) != 0
    }

    /** What he sees, the topmost thing. */
    enum class Seen { DARK, OTHERS, POP, SHADE, NOTES, USAGE, LIVE, MOON }

    fun seen(s: State): Seen = when {
        !s.lit -> Seen.DARK
        s.pop -> Seen.POP
        s.shade -> Seen.SHADE
        !s.ours -> Seen.OTHERS
        s.notes -> Seen.NOTES
        s.row == Row.USAGE -> Seen.USAGE
        s.row == Row.LIVE -> Seen.LIVE
        else -> Seen.MOON
    }

    sealed interface Input {
        /** A stroke on purpose. [sliding]: its finger landed before the page of the last move had settled (PageRow.Strokes). */
        data class Stroke(val way: Way, val from: From = From.MIDDLE, val sliding: Boolean = false) : Input
        data object Tap : Input
        data object DoubleTap : Input
        /** A finger held in place. */
        data object Hold : Input
        /** A double tap, the second held. */
        data object DoubleTapHold : Input
        /**
         * Something NEW of [kind] is live: it started, or it is another one (the next timer, a call answered, playing
         * again after a stop). [urgent]: a call, or a timer that has run out; it comes up over usage, the notes and the shade.
         */
        data class LiveComes(val kind: Live, val urgent: Boolean = false) : Input
        /** The same live thing goes on (a tick, the next track of the same playing). */
        data class LiveUpdates(val kind: Live) : Input
        data class LiveEnds(val kind: Live) : Input
        data object PopArrives : Input
        data object PopEnds : Input
        data object Dark : Input
        data object Lit : Input
        /** Xiaomi's Camera or an app has taken the back; and has left it. */
        data object OthersTake : Input
        data object OthersLeave : Input
        /** The usage page, the notes: left alone for their time. */
        data object UsageAlone : Input
        data object NotesAlone : Input
        /** A note was pinned from the phone: the notes show it for a moment (the newest, whichever he was on). */
        data object NotePinned : Input
        /**
         * The pinned notes are [count] now (a new one is always the newest, so more of them moves the one he is
         * reading one further back: he stays on it); [PinRemoved]: the note at [at] was taken away (its bin, or on the
         * phone): on that very note he sees the next older one, or the last there is.
         */
        data class PinsAre(val count: Int) : Input
        data class PinRemoved(val at: Int) : Input
        /** The shade has shut (its own stroke, its last page thrown out, nothing waiting). */
        data object ShadeShut : Input
    }

    /** The one animation of a step. What is under a thing that leaves is already in its place. */
    enum class Anim {
        NONE,
        /** The live page in from the right edge; out to it; another live thing in its place, no slide. */
        LIVE_IN, LIVE_OUT, LIVE_SWAP,
        USAGE_IN, USAGE_OUT,
        /** The notes up from the bottom edge; back down to it. */
        NOTES_IN, NOTES_OUT,
        /**
         * On the notes: the next OLDER note in from the right (the finger right to left, the stroke that on home goes
         * on to the timer and Claude); the next NEWER one in from the left; and, with none that way, the page giving
         * a little and coming back.
         */
        NOTE_OLDER, NOTE_NEWER, NOTE_END_OLDER, NOTE_END_NEWER,
        /** The notifications' pages down from the top; back up. */
        SHADE_IN, SHADE_OUT,
        POP_IN, POP_OUT,
        /** At once: the light, or the back given to another. */
        CUT,
    }

    /** What a step asks for besides the pages. */
    enum class Does {
        NOTHING,
        /** Xiaomi's Camera, started on the back. */
        OPEN_CAMERA,
        /** The page's own button under the finger, else the back bright for a while. */
        TAP,
        /** The back bright for a while, and no button: a pop-up is over the page. */
        BRIGHT,
        /** Dimmed, or bright again. */
        DIM,
        /** The next face, on the moon and the live pages (where there is more than one). */
        NEXT_FACE,
        /** The back off, until he wakes it. */
        BACK_OFF,
        /** The notifications' pages take it themselves (NoticePages). */
        SHADE_OWN,
    }

    data class Step(val state: State, val anim: Anim = Anim.NONE, val does: Does = Does.NOTHING)

    fun top(lives: Int): Live? = Live.values().lastOrNull { lives and (1 shl it.ordinal) != 0 }

    /** The row's page under usage, the notes or the shade once they go: the live page if it is there, else home. */
    private fun under(s: State): Row = if (s.liveShown) Row.LIVE else Row.HOME

    fun next(s: State, input: Input): Step {
        val step = raw(s, input)
        return step.copy(state = norm(step.state))
    }

    /** The table's step, with the note shown kept true to it: the newest whenever the notes are not up, never past the last. */
    private fun raw(s: State, input: Input): Step = table(s, input).let { it.copy(state = noted(it.state)) }

    private fun noted(s: State): State {
        val pins = s.pins.coerceAtLeast(0)
        val pin = if (!s.notes) 0 else s.pin.coerceIn(0, (pins - 1).coerceAtLeast(0))
        return if (pins == s.pins && pin == s.pin) s else s.copy(pins = pins, pin = pin)
    }

    /** The step as the table gives it, before [norm]: for the test that nothing ever needs mending. */
    internal fun unmended(s: State, input: Input): Step = raw(s, input)

    private fun table(s: State, i: Input): Step = when (i) {
        Input.Dark -> if (!s.lit) Step(s) else Step(
            // Dark: the scene is the face people wake to. Usage, the notes, the shade and a pop-up are gone.
            s.copy(lit = false, row = if (s.row == Row.USAGE) under(s) else s.row, notes = false, shade = false, pop = false), Anim.CUT,
        )
        Input.Lit -> if (s.lit) Step(s) else Step(s.copy(lit = true), Anim.CUT)
        Input.OthersTake -> if (!s.ours) Step(s) else Step(
            s.copy(ours = false, row = if (s.row == Row.USAGE) under(s) else s.row, notes = false, shade = false), Anim.CUT,
        )
        Input.OthersLeave -> if (s.ours) Step(s) else Step(s.copy(ours = true), Anim.CUT)

        is Input.LiveComes -> {
            val lives = s.lives or (1 shl i.kind.ordinal)
            // New of its kind: that kind is not "sent away" any more, whatever is on top.
            val sent = s.sent and (1 shl i.kind.ordinal).inv()
            if (top(lives) != i.kind) Step(s.copy(lives = lives, sent = sent))   // something higher is the live page
            else {
                val b = s.copy(lives = lives, sent = sent)
                val was = if (s.liveShown) s.live else null
                when {
                    !s.lit || !s.ours -> Step(b.copy(row = Row.LIVE))
                    s.shade -> if (i.urgent) Step(b.copy(row = Row.LIVE, shade = false, notes = false), Anim.SHADE_OUT)
                    else Step(b.copy(row = if (s.row == Row.HOME) Row.LIVE else s.row))
                    s.notes -> if (i.urgent) Step(b.copy(row = Row.LIVE, notes = false), Anim.NOTES_OUT) else Step(b.copy(row = Row.LIVE))
                    s.row == Row.USAGE -> if (i.urgent) Step(b.copy(row = Row.LIVE), Anim.USAGE_OUT) else Step(b)
                    s.row == Row.LIVE -> Step(b, if (was != i.kind) Anim.LIVE_SWAP else Anim.NONE)
                    else -> Step(b.copy(row = Row.LIVE), Anim.LIVE_IN)
                }
            }
        }
        is Input.LiveUpdates -> Step(s)
        is Input.LiveEnds -> {
            val lives = s.lives and (1 shl i.kind.ordinal).inv()
            val left = s.copy(lives = lives, sent = s.sent and (1 shl i.kind.ordinal).inv())
            when {
                lives == s.lives -> Step(s)
                s.live != i.kind -> Step(left)                                   // it had no page
                else -> {
                    // What is left takes the live page's place. It is NOT NEW: where he had sent the ended one away
                    // (the moon is what he chose), the next one is away too, and nothing comes up by itself.
                    val n = if (s.away && lives != 0) left.copy(sent = left.sent or (1 shl top(lives)!!.ordinal)) else left
                    if (!n.liveShown) Step(n.copy(row = if (s.row == Row.LIVE) Row.HOME else s.row), if (seen(s) == Seen.LIVE) Anim.LIVE_OUT else Anim.NONE)
                    else Step(n, if (seen(s) == Seen.LIVE) Anim.LIVE_SWAP else Anim.NONE)
                }
            }
        }

        Input.PopArrives -> if (!s.lit || s.pop) Step(s) else Step(s.copy(pop = true), Anim.POP_IN)
        Input.PopEnds -> if (!s.pop) Step(s) else Step(s.copy(pop = false), Anim.POP_OUT)
        Input.UsageAlone -> if (s.row != Row.USAGE) Step(s) else Step(s.copy(row = under(s)), if (seen(s) == Seen.USAGE) Anim.USAGE_OUT else Anim.NONE)
        Input.NotesAlone -> if (!s.notes) Step(s) else Step(s.copy(notes = false), if (seen(s) == Seen.NOTES) Anim.NOTES_OUT else Anim.NONE)
        Input.NotePinned -> if (!s.lit || !s.ours) Step(s) else if (s.notes) Step(s.copy(pin = 0)) else Step(
            s.copy(notes = true, pin = 0, row = if (s.row == Row.USAGE) under(s) else s.row), if (s.shade || s.pop) Anim.NONE else Anim.NOTES_IN,
        )
        // More notes: the new ones are in front, so the one he is reading is that many further back. Fewer, by another way than its bin: the last there is.
        is Input.PinsAre -> { val n = i.count.coerceAtLeast(0); Step(s.copy(pins = n, pin = if (s.notes && s.pin > 0 && n > s.pins) s.pin + (n - s.pins) else s.pin)) }
        is Input.PinRemoved -> if (i.at !in 0 until s.pins) Step(s) else Step(s.copy(pins = s.pins - 1, pin = if (i.at < s.pin) s.pin - 1 else s.pin))
        Input.ShadeShut -> if (!s.shade) Step(s) else Step(s.copy(shade = false), Anim.SHADE_OUT)

        // Under a pop-up nothing of the page is pressed: its buttons are not what he sees.
        Input.Tap -> touch(s, if (s.pop) Does.BRIGHT else Does.TAP)
        Input.DoubleTap -> touch(s, Does.DIM)
        // Held: the next face and the back off are the moon's and the live pages' (SceneTouch reads a hold only there).
        Input.Hold -> touch(s, if (holds(s)) Does.NEXT_FACE else Does.NOTHING)
        Input.DoubleTapHold -> touch(s, if (holds(s)) Does.BACK_OFF else Does.NOTHING)

        is Input.Stroke -> stroke(s, i)
    }

    /** A hold is read on the moon and on the call's, timer's and music's pages; not on usage, the notes, the charging page. */
    private fun holds(s: State): Boolean = !s.pop && !s.notes && s.row != Row.USAGE && !(s.row == Row.LIVE && s.live == Live.CHARGE)

    private fun touch(s: State, does: Does): Step = when {
        !s.lit || !s.ours -> Step(s)
        s.shade -> Step(s, does = Does.SHADE_OWN)
        else -> Step(s, does = does)
    }

    private fun stroke(s: State, i: Input.Stroke): Step = when {
        // A dark back is woken by Xiaomi's own double tap, and another's back is theirs.
        !s.lit || !s.ours -> Step(s)
        // The pile's own strokes: next, the one before, thrown out, shut (which comes back as ShadeShut).
        s.shade -> Step(s, does = Does.SHADE_OWN)
        // Down from the top edge's right half: the notifications, over every page of ours. A pop-up up then is gone.
        i.from == From.TOP_EDGE && i.way == Way.DOWN -> Step(s.copy(shade = true, pop = false), Anim.SHADE_IN)
        // A POP-UP HAS THE WHOLE BACK: a stroke on it takes it away and does nothing to the page hidden under it
        // (it used to turn that page, or open the Camera, unseen).
        s.pop -> Step(s.copy(pop = false), Anim.POP_OUT)
        // One stroke, one page: a finger that landed while the last page was still sliding moves nothing.
        i.sliding -> Step(s)
        s.notes -> when (i.way) {
            Way.DOWN -> Step(s.copy(notes = false), Anim.NOTES_OUT)
            // Along the notes, newest first: "going right" (the finger right to left) is the next older one, the other
            // way the next newer; at either end the page gives and stays. It does not go round.
            Way.LEFT -> if (s.pin < s.pins - 1) Step(s.copy(pin = s.pin + 1), Anim.NOTE_OLDER) else Step(s, Anim.NOTE_END_OLDER)
            Way.RIGHT -> if (s.pin > 0) Step(s.copy(pin = s.pin - 1), Anim.NOTE_NEWER) else Step(s, Anim.NOTE_END_NEWER)
            Way.UP -> Step(s)
        }
        s.row == Row.USAGE -> when (i.way) {
            // Back along the row: the live page is under it, in its place; with none, the moon.
            Way.RIGHT -> Step(s.copy(row = under(s)), Anim.USAGE_OUT)
            else -> Step(s)
        }
        s.row == Row.LIVE -> when (i.way) {
            Way.LEFT -> Step(s.copy(row = Row.USAGE), Anim.USAGE_IN)
            Way.RIGHT -> Step(away(s), Anim.LIVE_OUT)
            // The charging page goes for any stroke but the one to usage; on the
            // others up is the notes, as on the moon, and down is nothing.
            Way.UP -> if (s.live == Live.CHARGE) Step(away(s), Anim.LIVE_OUT) else Step(s.copy(notes = true), Anim.NOTES_IN)
            Way.DOWN -> if (s.live == Live.CHARGE) Step(away(s), Anim.LIVE_OUT) else Step(s)
        }
        else -> when (i.way) {
            // Right of home: the live page if anything is live (sent away or not), else usage.
            Way.LEFT -> if (s.lives != 0) Step(s.copy(row = Row.LIVE, sent = s.sent and (1 shl s.live!!.ordinal).inv()), Anim.LIVE_IN) else Step(s.copy(row = Row.USAGE), Anim.USAGE_IN)
            // Left of home there is no page: Xiaomi's Camera.
            Way.RIGHT -> Step(s, does = Does.OPEN_CAMERA)
            Way.UP -> Step(s.copy(notes = true), Anim.NOTES_IN)
            Way.DOWN -> Step(s)
        }
    }

    /** The live page sent away: the moon, and its kind remembered as sent. */
    private fun away(s: State): State = s.copy(row = Row.HOME, sent = s.sent or (1 shl s.live!!.ordinal))

    /** What must hold of any state; [next] never gives one that needs mending (a test holds that). */
    fun norm(s: State): State {
        var n = s
        if (n.sent and n.lives.inv() != 0) n = n.copy(sent = n.sent and n.lives)
        if (n.row == Row.LIVE && !n.liveShown) n = n.copy(row = Row.HOME)
        if (n.row == Row.HOME && n.liveShown) n = n.copy(row = Row.LIVE)
        if (n.row == Row.USAGE && n.notes) n = n.copy(row = under(n))
        n = noted(n)
        if (!n.lit && (n.notes || n.shade || n.pop || n.row == Row.USAGE)) n = n.copy(notes = false, shade = false, pop = false, row = if (n.row == Row.USAGE) under(n) else n.row)
        if (!n.ours && (n.notes || n.shade || n.row == Row.USAGE)) n = n.copy(notes = false, shade = false, row = if (n.row == Row.USAGE) under(n) else n.row)
        return n
    }

    /** Every input there is, for the tests' table. */
    val INPUTS: List<Input> = buildList {
        for (sliding in listOf(false, true)) for (from in From.values()) for (way in Way.values()) add(Input.Stroke(way, from, sliding))
        add(Input.Tap); add(Input.DoubleTap); add(Input.Hold); add(Input.DoubleTapHold)
        for (k in Live.values()) { add(Input.LiveComes(k)); add(Input.LiveComes(k, urgent = true)); add(Input.LiveUpdates(k)); add(Input.LiveEnds(k)) }
        add(Input.PopArrives); add(Input.PopEnds); add(Input.Dark); add(Input.Lit); add(Input.OthersTake); add(Input.OthersLeave)
        add(Input.UsageAlone); add(Input.NotesAlone); add(Input.NotePinned); add(Input.ShadeShut)
        // One note, two, many (three), none; and one taken away at each place.
        for (n in 0..3) add(Input.PinsAre(n))
        for (at in 0..2) add(Input.PinRemoved(at))
    }
}
