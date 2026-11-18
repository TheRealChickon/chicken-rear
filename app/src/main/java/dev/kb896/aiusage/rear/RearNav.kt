package dev.kb896.aiusage.rear

object RearNav {
    enum class Row { HOME, LIVE, USAGE }

    /** What can be live, the lowest first: the highest that is live is the row's live page. */
    enum class Live { CHARGE, MUSIC, TIMER, NAVIGATION, CALL }

    enum class Way { LEFT, RIGHT, UP, DOWN }

    val DISMISS_WAY: Way = Way.LEFT

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
        /** The pop-up's notification may not be dismissed or snoozed (ongoing, not clearable: NoticeThrow.throwable). False while no pop-up is up. */
        val popFixed: Boolean = false,
        /**
         * How many notes are pinned, and which of them the notes page shows (0 the first in his order, PinOrder: the
         * newest unless he put them in order; always 0 while it is not up).
         */
        val pins: Int = 0,
        val pin: Int = 0,
        /** The face picker is up, on home (only ever with [lit], [ours], home, no notes or shade; a pop-up may lie over it). */
        val picker: Boolean = false,
        /** The notes are being put in order: [pin] is the note he holds. Only on the notes, with two or more, nothing over them. */
        val arrange: Boolean = false,
        /** How many notes stand on the back as arrivals at their place, and which of them shows (0 the newest arrival). */
        val arrivals: Int = 0,
        val arrival: Int = 0,
    ) {
        /** The live page's thing: the highest that is live. */
        val live: Live? get() = top(lives)
        /** He sent the live page (the highest live thing's) away: it is one stroke right of home, and the moon shows. */
        val away: Boolean get() = live?.let { sent and (1 shl it.ordinal) != 0 } ?: false
        /** The live page is in the row and not sent away: it is in its place, seen or under something. */
        val liveShown: Boolean get() = lives != 0 && !away
        fun has(kind: Live): Boolean = lives and (1 shl kind.ordinal) != 0
        /** An arrival stands and is what the row shows: not while a call's page is in its place. */
        val arrivalUp: Boolean get() = arrivals > 0 && !(liveShown && live == Live.CALL)
    }

    /**
     * What he sees, the topmost thing. [MOON] is THE FACE, whichever it is (the moon or a Xiaomi face, FaceCatalog): the
     * name is kept from when there was one face.
     */
    enum class Seen { DARK, OTHERS, POP, PICKER, SHADE, ARRIVAL, NOTES, USAGE, LIVE, MOON }

    fun seen(s: State): Seen = when {
        !s.lit -> Seen.DARK
        s.pop -> Seen.POP
        s.picker -> Seen.PICKER
        s.shade -> Seen.SHADE
        !s.ours -> Seen.OTHERS
        s.arrivalUp -> Seen.ARRIVAL
        s.notes -> Seen.NOTES
        s.row == Row.USAGE -> Seen.USAGE
        s.row == Row.LIVE -> Seen.LIVE
        else -> Seen.MOON
    }

    sealed interface Input {
        /**
         * A stroke on purpose. [sliding]: its finger landed before the page of the last move had settled (PageRow.Strokes).
         * [aimed]: its finger landed on the pop-up that is up now, once that had been there a moment (NoticeThrow.aimed);
         * false when the pop-up came, or the next took its place, after the finger was already on its way. Said only of a
         * pop-up: with none up it means nothing.
         */
        data class Stroke(val way: Way, val from: From = From.MIDDLE, val sliding: Boolean = false, val aimed: Boolean = true) : Input
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
        /** A pop-up comes, or the next one takes the place of the one up; [fixed]: its notification may not be thrown out. */
        data class PopArrives(val fixed: Boolean = false) : Input
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
         *
         * [keep]: where the note he is reading now is in the new list, when the shell knows it ([pinsAre]): he stays on
         * it wherever it went. A note for a place comes onto the notes page and leaves it at its own place in his
         * order, not in front (dev.kb896.aiusage.place.Here), so the rule above would put another note under him.
         * -1 (or a place that is not there): by the rule above.
         */
        data class PinsAre(val count: Int, val keep: Int = -1) : Input
        data class PinRemoved(val at: Int) : Input
        /** The shade has shut (its own stroke, its last page thrown out, nothing waiting). */
        data object ShadeShut : Input
        /** The face picker shuts by itself, as its exit starts: [chosen] a face (a tap), or left without (a stroke up or down). */
        data class PickerShut(val chosen: Boolean) : Input

        /** A finger held still on the notes page (SceneTouch.heldOnNotes): the note shown is lifted to be put in order. */
        data object NoteHold : Input
        /** Arranging: the held note carried to place [to] (each note it passes is one of these). */
        data class NoteCarried(val to: Int) : Input
        /** Arranging: a tap on the note at [with]: the two in each other's places (the held one is at [with] after). */
        data class NoteSwapped(val with: Int) : Input
        /** Arranging: a tap on the held note or on the ground. The order as it stands is kept. */
        data object ArrangeDone : Input
        /**
         * The notes standing as arrivals are [count] now (a new arrival is the newest: he stays on the one he reads);
         * [ArrivalRemoved]: the one at [at] was put away by another way than a stroke (its bin, or on the phone).
         */
        data class ArrivalsAre(val count: Int) : Input
        data class ArrivalRemoved(val at: Int) : Input
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
        /**
         * The notes put in order: the page lifts into the carousel and back; the held note at another place (the notes
         * it passed make room); two notes swapping places, past each other. NoteCarousel moves them.
         */
        ARRANGE_IN, ARRANGE_OUT, NOTE_CARRIED, NOTE_SWAP,
        /** The notifications' pages down from the top; back up. */
        SHADE_IN, SHADE_OUT,
        /** An arrival up from the bottom edge; down to it (not now, or done); along the ones standing, and the give at an end. */
        ARRIVAL_IN, ARRIVAL_OUT, ARRIVAL_OLDER, ARRIVAL_NEWER, ARRIVAL_END_OLDER, ARRIVAL_END_NEWER,
        POP_IN, POP_OUT,
        /**
         * The pop-up thrown out sideways, to the left or the right, as the finger went (its notification dismissed or
         * snoozed: [Does]); and one that may not be thrown giving a little and coming back on a spring, nothing changed.
         */
        POP_THROWN_LEFT, POP_THROWN_RIGHT, POP_GIVES,
        /** The face shrinks into the picker's card; the picker's own exit plays (the card grows back to the face). */
        PICKER_IN, PICKER_OUT,
        /** At once: the light, or the back given to another. */
        CUT,
    }

    /** What a step asks for besides the pages. */
    enum class Does {
        NOTHING,
        /** The camera, started on the back: our camera page, or Xiaomi's Camera where ours may not be (RearCameraRoute). */
        OPEN_CAMERA,
        /** The page's own button under the finger, else the back bright for a while. */
        TAP,
        /** The back bright for a while, and no button: a pop-up is over the page. */
        BRIGHT,
        /** Dimmed, or bright again. */
        DIM,
        /** The back off, until he wakes it. */
        BACK_OFF,
        /** The notifications' pages take it themselves (NoticePages). */
        SHADE_OWN,
        /** The face picker takes it itself (PickerTouch). */
        PICKER_OWN,
        /** Held on the notes with nothing to put in order (one note): the page gives a little and back. */
        GIVE,
        /** The arrival he swiped down: not now, every note standing waits for his next visit (told to the app). */
        ARRIVAL_LATER,
        /** The pop-up's notification dismissed, on the phone too; snoozed for an hour (NoticeThrow.SNOOZE_MS). */
        DISMISS, SNOOZE,
    }

    data class Step(val state: State, val anim: Anim = Anim.NONE, val does: Does = Does.NOTHING)

    fun top(lives: Int): Live? = Live.values().lastOrNull { lives and (1 shl it.ordinal) != 0 }

    /** The row's page under usage, the notes or the shade once they go: the live page if it is there, else home. */
    private fun under(s: State): Row = if (s.liveShown) Row.LIVE else Row.HOME

    fun next(s: State, input: Input): Step {
        val step = raw(s, input)
        return step.copy(state = norm(step.state))
    }

    /**
     * What the table is told of the shell's new list of notes [next] (each by its Pin.at, in its order) while it is at
     * [s] and the notes page shows the note [shown] (its Pin.at; null when the notes page is not up): null for nothing
     * (as many as before, and his note where it was). He stays on the note he is reading wherever it is now
     * ([Input.PinsAre.keep]); gone from the list, by the count's rule. [fresh]: the list brings a note just pinned, in
     * front: on the front note he stays on the front, which is the new one (as ever; its landing shows it anyway).
     */
    fun pinsAre(s: State, shown: Long?, next: List<Long>, fresh: Boolean): Input.PinsAre? {
        val keep = if (shown == null || fresh && s.pin == 0) -1 else next.indexOf(shown)
        return if (next.size == s.pins && (keep < 0 || keep == s.pin)) null else Input.PinsAre(next.size, keep)
    }

    /** The table's step, with the note shown kept true to it: the newest whenever the notes are not up, never past the last. */
    private fun raw(s: State, input: Input): Step = table(s, input).let { it.copy(state = noted(it.state)) }

    private fun noted(s: State): State {
        val pins = s.pins.coerceAtLeast(0)
        val pin = if (!s.notes) 0 else s.pin.coerceIn(0, (pins - 1).coerceAtLeast(0))
        val arrivals = s.arrivals.coerceAtLeast(0)
        val arrival = s.arrival.coerceIn(0, (arrivals - 1).coerceAtLeast(0))
        // Arranging is the notes page's alone: anything that takes the page, or anything over it, ends it.
        val arrange = s.arrange && arranges(s.copy(pins = pins, arrivals = arrivals))
        // Whether the pop-up may be thrown is the pop-up's: with none up there is nothing to say.
        val fixed = s.popFixed && s.pop
        return if (pins == s.pins && pin == s.pin && arrange == s.arrange && arrivals == s.arrivals && arrival == s.arrival && fixed == s.popFixed) s
        else s.copy(pins = pins, pin = pin, arrange = arrange, arrivals = arrivals, arrival = arrival, popFixed = fixed)
    }

    /** Where arranging can be: the notes page seen, on a lit back that is ours, with two notes or more, no arrival standing over them. */
    private fun arranges(s: State): Boolean = s.notes && s.pins >= 2 && s.lit && s.ours && !s.pop && !s.shade && !s.arrivalUp

    /** The step as the table gives it, before [norm]: for the test that nothing ever needs mending. */
    internal fun unmended(s: State, input: Input): Step = raw(s, input)

    private fun table(s: State, i: Input): Step = when (i) {
        Input.Dark -> if (!s.lit) Step(s) else Step(
            // Dark: the scene is the face people wake to. Usage, the notes, the shade, a pop-up and the picker are gone.
            s.copy(lit = false, row = if (s.row == Row.USAGE) under(s) else s.row, notes = false, shade = false, pop = false, picker = false), Anim.CUT,
        )
        Input.Lit -> if (s.lit) Step(s) else Step(s.copy(lit = true), Anim.CUT)
        Input.OthersTake -> if (!s.ours) Step(s) else Step(
            s.copy(ours = false, row = if (s.row == Row.USAGE) under(s) else s.row, notes = false, shade = false, picker = false), Anim.CUT,
        )
        // The picker's own end, WHATEVER LIES OVER IT: the shell says it once it has shut or is gone, and a pop-up that
        // came in between (its shut posted, the pop-up's arrival handled first) must not keep it up in the table, or
        // every finger after the pop-up would go to a picker that is not there. Its exit plays under the pop-up.
        is Input.PickerShut -> if (!s.picker) Step(s) else Step(s.copy(picker = false), Anim.PICKER_OUT)
        Input.OthersLeave -> if (s.ours) Step(s) else Step(s.copy(ours = true), Anim.CUT)

        is Input.LiveComes -> {
            val lives = s.lives or (1 shl i.kind.ordinal)
            // New of its kind: that kind is not "sent away" any more, whatever is on top.
            val sent = s.sent and (1 shl i.kind.ordinal).inv()
            if (top(lives) != i.kind) Step(s.copy(lives = lives, sent = sent))   // something higher is the live page
            else {
                // On top: its page takes the back, and the picker (whose live card is the face's layer, under the live
                // pages) is dropped, the old face kept.
                val b = s.copy(lives = lives, sent = sent, picker = false)
                val was = if (s.liveShown) s.live else null
                when {
                    !s.lit || !s.ours -> Step(b.copy(row = Row.LIVE))
                    s.shade -> if (i.urgent) Step(b.copy(row = Row.LIVE, shade = false, notes = false), Anim.SHADE_OUT)
                    else Step(b.copy(row = if (s.row == Row.HOME) Row.LIVE else s.row))
                    s.notes -> if (i.urgent) Step(b.copy(row = Row.LIVE, notes = false), Anim.NOTES_OUT) else Step(b.copy(row = Row.LIVE))
                    s.row == Row.USAGE -> if (i.urgent) Step(b.copy(row = Row.LIVE), Anim.USAGE_OUT) else Step(b)
                    // Under a standing arrival a timer, music or the charger take their place unseen; a call comes over it.
                    s.arrivalUp && i.kind != Live.CALL -> Step(b.copy(row = Row.LIVE))
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

        // Over one that is up, the next takes its place in it (ShellNotices' banner), and says for itself whether it may be thrown.
        is Input.PopArrives -> if (!s.lit) Step(s) else if (s.pop) Step(s.copy(popFixed = i.fixed)) else Step(s.copy(pop = true, popFixed = i.fixed), Anim.POP_IN)
        Input.PopEnds -> if (!s.pop) Step(s) else Step(s.copy(pop = false), Anim.POP_OUT)
        Input.UsageAlone -> if (s.row != Row.USAGE) Step(s) else Step(s.copy(row = under(s)), if (seen(s) == Seen.USAGE) Anim.USAGE_OUT else Anim.NONE)
        Input.NotesAlone -> if (!s.notes) Step(s) else Step(s.copy(notes = false), if (seen(s) == Seen.NOTES) Anim.NOTES_OUT else Anim.NONE)
        Input.NotePinned -> if (!s.lit || !s.ours || s.arrivals > 0) Step(s) else if (s.notes) Step(s.copy(pin = 0, arrange = false), if (s.arrange) Anim.ARRANGE_OUT else Anim.NONE) else Step(
            s.copy(notes = true, pin = 0, row = if (s.row == Row.USAGE) under(s) else s.row, picker = false), if (s.shade || s.pop) Anim.NONE else Anim.NOTES_IN,
        )
        // More notes: the new ones are in front, so the one he is reading is that many further back. Fewer, by another way than its bin: the last there is.
        // Where the shell knows where his note went (keep), there: also with as many notes as before (one for a place came, another went).
        // A list that changes under the arranging ends it (the order as it stood is kept).
        is Input.PinsAre -> {
            val n = i.count.coerceAtLeast(0)
            val pin = when {
                !s.notes -> s.pin
                i.keep in 0 until n -> i.keep
                s.pin > 0 && n > s.pins -> s.pin + (n - s.pins)
                else -> s.pin
            }
            if (n == s.pins && pin == s.pin) Step(s) else Step(s.copy(pins = n, pin = pin, arrange = false), if (s.arrange) Anim.ARRANGE_OUT else Anim.NONE)
        }
        is Input.PinRemoved -> if (i.at !in 0 until s.pins) Step(s) else Step(s.copy(pins = s.pins - 1, pin = if (i.at < s.pin) s.pin - 1 else s.pin, arrange = false), if (s.arrange) Anim.ARRANGE_OUT else Anim.NONE)
        Input.ShadeShut -> if (!s.shade) Step(s) else Step(s.copy(shade = false), Anim.SHADE_OUT)
        is Input.ArrivalsAre -> arrivals(s, i.count.coerceAtLeast(0), if (s.arrivals > 0 && i.count > s.arrivals) s.arrival + (i.count - s.arrivals) else s.arrival)
        is Input.ArrivalRemoved -> if (i.at !in 0 until s.arrivals) Step(s) else arrivals(s, s.arrivals - 1, if (i.at < s.arrival) s.arrival - 1 else s.arrival)

        // Held on the notes page: two notes or more are lifted to be put in order; one gives a little; none, nothing.
        Input.NoteHold -> when {
            s.arrange || !s.lit || !s.ours || !s.notes || s.pop || s.shade || s.pins == 0 -> Step(s)
            s.pins == 1 -> Step(s, does = Does.GIVE)
            else -> Step(s.copy(arrange = true), Anim.ARRANGE_IN)
        }
        is Input.NoteCarried -> if (!s.arrange || i.to !in 0 until s.pins || i.to == s.pin) Step(s) else Step(s.copy(pin = i.to), Anim.NOTE_CARRIED)
        is Input.NoteSwapped -> if (!s.arrange || i.with !in 0 until s.pins || i.with == s.pin) Step(s) else Step(s.copy(pin = i.with), Anim.NOTE_SWAP)
        Input.ArrangeDone -> if (!s.arrange) Step(s) else Step(s.copy(arrange = false), Anim.ARRANGE_OUT)

        // Under a pop-up nothing of the page is pressed: its buttons are not what he sees.
        Input.Tap -> if (s.picker && !s.pop) touch(s, Does.PICKER_OWN) else touch(s, if (s.pop) Does.BRIGHT else Does.TAP)
        // In the picker a double tap, a hold and a double tap held are nothing.
        Input.DoubleTap -> if (s.picker) touch(s, Does.NOTHING) else touch(s, Does.DIM)
        // Held on the face: the picker (Xiaomi's, copied). Held anywhere else: nothing (a hold on the live pages was
        // "the next face", which with one face did nothing).
        Input.Hold -> if (s.lit && s.ours && seen(s) == Seen.MOON) Step(s.copy(picker = true), Anim.PICKER_IN) else touch(s, Does.NOTHING)
        // Off by hand, as everywhere: on a standing arrival too; not in the picker.
        Input.DoubleTapHold -> touch(s, if (!s.picker && (holds(s) || s.arrivalUp && !s.pop)) Does.BACK_OFF else Does.NOTHING)

        is Input.Stroke -> stroke(s, i)
    }

    /** A stroke on a pop-up that is up: nothing if not meant for it, thrown out sideways (or giving if fixed), else taken away. */
    private fun popStroke(s: State, i: Input.Stroke): Step = when {
        i.sliding || !i.aimed -> Step(s)
        else -> when (NoticeThrow.kindOf(i.way)) {
            null -> Step(s.copy(pop = false), Anim.POP_OUT)
            else -> if (s.popFixed) Step(s, Anim.POP_GIVES) else Step(
                s.copy(pop = false),
                if (i.way == Way.LEFT) Anim.POP_THROWN_LEFT else Anim.POP_THROWN_RIGHT,
                if (NoticeThrow.kindOf(i.way) == NoticeThrow.Kind.DISMISS) Does.DISMISS else Does.SNOOZE,
            )
        }
    }

    /** A double tap held is read on the moon and on the call's, timer's and music's pages; not on usage, the notes, the charging page. */
    private fun holds(s: State): Boolean = !s.pop && !s.notes && s.row != Row.USAGE && !(s.row == Row.LIVE && s.live == Live.CHARGE)

    private fun touch(s: State, does: Does): Step = when {
        !s.lit || !s.ours -> Step(s)
        s.shade -> Step(s, does = Does.SHADE_OWN)
        else -> Step(s, does = does)
    }

    private fun stroke(s: State, i: Input.Stroke): Step = when {
        // A dark back is woken by Xiaomi's own double tap, and another's back is theirs.
        !s.lit || !s.ours -> Step(s)
        // The pile's own strokes: thrown out sideways (the next comes up), shut by a stroke up (which comes back as
        // ShadeShut); down does nothing. It is never paged through up and down.
        s.shade -> Step(s, does = Does.SHADE_OWN)
        // The picker's own strokes, from any edge (it shuts by itself and says so: PickerShut). A pop-up over it: the
        // stroke is the pop-up's as on any page (taken away, thrown out, or nothing if not meant for it), but down from
        // the top edge only takes it away (the shade would drop the picker), and the picker is there again.
        s.picker -> if (s.pop) popStroke(s, i) else Step(s, does = Does.PICKER_OWN)
        // Down from the top edge's right half: the notifications, over every page of ours. A pop-up up then is gone.
        i.from == From.TOP_EDGE && i.way == Way.DOWN -> Step(s.copy(shade = true, pop = false), Anim.SHADE_IN)
        // A POP-UP HAS THE WHOLE BACK: a stroke on it takes it away and does nothing to the page hidden under it
        // (it used to turn that page, or open the Camera, unseen). Sideways it is THROWN OUT: dismissed one way,
        // snoozed for an hour the other ([DISMISS_WAY]); one that may not be thrown gives and stays.
        // A STROKE NOT MEANT FOR IT DOES NOTHING AT ALL: its finger landed before this pop-up came (or the next took its
        // place), or while the last page still slid. It stays its time, and nothing of his is dismissed or snoozed unseen.
        s.pop -> popStroke(s, i)
        // One stroke, one page: a finger that landed while the last page was still sliding moves nothing.
        i.sliding -> Step(s)
        // A STANDING ARRIVAL: down is "not now"; sideways the other notes standing; nothing else of the row is reached.
        s.arrivalUp -> when (i.way) {
            Way.DOWN -> Step(s.copy(arrivals = 0, arrival = 0), Anim.ARRIVAL_OUT, Does.ARRIVAL_LATER)
            Way.LEFT -> if (s.arrival < s.arrivals - 1) Step(s.copy(arrival = s.arrival + 1), Anim.ARRIVAL_OLDER) else Step(s, Anim.ARRIVAL_END_OLDER)
            Way.RIGHT -> if (s.arrival > 0) Step(s.copy(arrival = s.arrival - 1), Anim.ARRIVAL_NEWER) else Step(s, Anim.ARRIVAL_END_NEWER)
            Way.UP -> Step(s)
        }
        // Arranging: sideways is the carousel's own (the held note carried: NoteCarried); up nothing; down leaves as ever.
        s.arrange -> if (i.way == Way.DOWN) Step(s.copy(notes = false), Anim.NOTES_OUT) else Step(s)
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
            // (An arrival under the call: usage and the notes wait until it is dismissed.)
            Way.LEFT -> if (s.arrivals > 0) Step(s) else Step(s.copy(row = Row.USAGE), Anim.USAGE_IN)
            Way.RIGHT -> Step(away(s), Anim.LIVE_OUT)
            // The charging page goes for any stroke but the one to usage; on the
            // others up is the notes, as on the moon, and down is nothing.
            Way.UP -> if (s.live == Live.CHARGE) Step(away(s), Anim.LIVE_OUT) else if (s.arrivals > 0) Step(s) else Step(s.copy(notes = true), Anim.NOTES_IN)
            Way.DOWN -> if (s.live == Live.CHARGE) Step(away(s), Anim.LIVE_OUT) else Step(s)
        }
        else -> when (i.way) {
            // Right of home: the live page if anything is live (sent away or not), else usage.
            Way.LEFT -> if (s.lives != 0) Step(s.copy(row = Row.LIVE, sent = s.sent and (1 shl s.live!!.ordinal).inv()), Anim.LIVE_IN) else Step(s.copy(row = Row.USAGE), Anim.USAGE_IN)
            // Left of home there is no page: the camera (ours or Xiaomi's, RearCameraRoute), which then has the back (OthersTake).
            Way.RIGHT -> Step(s, does = Does.OPEN_CAMERA)
            Way.UP -> Step(s.copy(notes = true), Anim.NOTES_IN)
            Way.DOWN -> Step(s)
        }
    }

    /**
     * The arrivals standing are [n], [at] the one showing. The first comes up over the row (usage and the notes give
     * way to it); the last gone, what is under it shows. Seen or not, the count is the state.
     */
    private fun arrivals(s: State, n: Int, at: Int): Step {
        val next = s.copy(arrivals = n, arrival = if (n == 0) 0 else at.coerceIn(0, n - 1),
            notes = if (n > 0) false else s.notes, row = if (n > 0 && s.row == Row.USAGE) under(s) else s.row,
            // An arrival takes the back: the picker is dropped, the old face kept.
            picker = s.picker && n == 0)
        val shown = s.lit && s.ours && !s.pop && !s.shade
        val anim = when {
            s.arrivals == 0 && n > 0 && shown && next.arrivalUp -> Anim.ARRIVAL_IN
            s.arrivals > 0 && n == 0 && seen(s) == Seen.ARRIVAL -> Anim.ARRIVAL_OUT
            else -> Anim.NONE
        }
        return Step(next, anim)
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
        if (n.arrivals > 0 && (n.notes || n.row == Row.USAGE)) n = n.copy(notes = false, row = if (n.row == Row.USAGE) under(n) else n.row)
        n = noted(n)
        if (n.arrange && !arranges(n)) n = n.copy(arrange = false)
        if (!n.lit && (n.notes || n.shade || n.pop || n.row == Row.USAGE)) n = n.copy(notes = false, shade = false, pop = false, row = if (n.row == Row.USAGE) under(n) else n.row)
        if (!n.ours && (n.notes || n.shade || n.row == Row.USAGE)) n = n.copy(notes = false, shade = false, row = if (n.row == Row.USAGE) under(n) else n.row)
        if (n.popFixed && !n.pop) n = n.copy(popFixed = false)
        if (n.picker && !(n.lit && n.ours && n.row == Row.HOME && !n.notes && !n.shade && n.arrivals == 0)) n = n.copy(picker = false)
        return n
    }

    /** Every input there is, for the tests' table. */
    val INPUTS: List<Input> = buildList {
        for (sliding in listOf(false, true)) for (from in From.values()) for (way in Way.values()) add(Input.Stroke(way, from, sliding))
        // A stroke whose finger was on its way before the pop-up came (from the middle: the edges are the same for it).
        for (way in Way.values()) add(Input.Stroke(way, aimed = false))
        add(Input.Tap); add(Input.DoubleTap); add(Input.Hold); add(Input.DoubleTapHold)
        for (k in Live.values()) { add(Input.LiveComes(k)); add(Input.LiveComes(k, urgent = true)); add(Input.LiveUpdates(k)); add(Input.LiveEnds(k)) }
        add(Input.PopArrives()); add(Input.PopArrives(fixed = true)); add(Input.PopEnds); add(Input.Dark); add(Input.Lit); add(Input.OthersTake); add(Input.OthersLeave)
        add(Input.UsageAlone); add(Input.NotesAlone); add(Input.NotePinned); add(Input.ShadeShut)
        // One note, two, many (three), none; and one taken away at each place.
        for (n in 0..3) add(Input.PinsAre(n))
        // His note followed to where it is now: one more with it further back, as many with it moved, one fewer with it nearer.
        add(Input.PinsAre(3, keep = 1)); add(Input.PinsAre(2, keep = 0)); add(Input.PinsAre(2, keep = 1))
        for (at in 0..2) add(Input.PinRemoved(at))
        add(Input.PickerShut(true)); add(Input.PickerShut(false))
        // Putting them in order: held, carried to each place, swapped with each place, done.
        add(Input.NoteHold); add(Input.ArrangeDone)
        for (at in 0..2) { add(Input.NoteCarried(at)); add(Input.NoteSwapped(at)) }
        // Arrivals: none, one, two standing; one put away at each place.
        for (n in 0..2) add(Input.ArrivalsAre(n))
        for (at in 0..1) add(Input.ArrivalRemoved(at))
    }
}
