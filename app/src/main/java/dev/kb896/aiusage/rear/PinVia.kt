package dev.kb896.aiusage.rear

/**
 * A PIN GOES THROUGH XIAOMI'S OWN.
 * Our "Pin to back" keeps the note in OUR store and hands the words to Xiaomi's own pin
 * (`com.xiaomi.subscreencenter/.pin.PinReceiveActivity`), which plays its real animation on the front, its real
 * "Pinned" island and its sound. Nothing of ours is drawn on the front and no sound of ours is played.
 *
 * ON THE BACK, Xiaomi's own arrival is let through and OUR NOTES PAGE IS WHAT IT ENDS ON. XIAOMI SAYS EVERY STEP IN
 * THE LOG, and the back goes by what it says, not by a clock (a fixed time from the hand-over lifted the black on
 * Xiaomi's own face in one run of three, and came after the whole arrival in two others):
 *
 *   SubScreenCenter_PinAnimationSurfaceView  "start animation 0"   the front's first stage (200 ms)
 *                                            "start animation 1"   200 ms later (1300 ms)
 *                                            "start animation 2"   1500 ms after the first (550 ms)
 *                                            "finished"            2050 ms after the first: the front is over
 *   SubScreenCenter_SmartAssistantPanel      "Pin card enter animation start"   THE BACK'S ARRIVAL BEGINS (about 0.2 s
 *                                            after "finished": its own face, the fish and its clock, blurs away)
 *                                            "New view show animation start"          0.10 s in: the coloured glass
 *                                            "New view content show animation start"  0.30 s in: its own note's words
 *                                            "New view content show animation complete" 0.61 s in
 *                                            "Pag animation end"                      1.00 s in: the lit rim is gone
 * (Read from one pin with the log and a recording of the back side by side.)
 *
 * What the back does, in two parts:
 *  1. counted from the front's first stage ([zero] is [FRONT_FIRST_MS] before it): as the front ends our page DIPS
 *     TO BLACK ([DIP_AT] for [DIP_MS]), and under that black our cover and pages step aside ([SWITCH_AT]): Xiaomi's
 *     back is there, unseen;
 *  2. counted from Xiaomi's "enter": the black lifts ([LIFT_AFTER] for [BLOOM_MS]) ON ITS COLOURED GLASS, its own
 *     face never seen; OUR notes page fades in out of the glass ([OURS_AFTER] for [OURS_MS]) before Xiaomi's own
 *     note is sharp under it (Xiaomi sets the words elsewhere than we do: the two together were a double picture);
 *     under our picture the cover and the real notes page come back, and the layer is gone.
 *  Not heard by [ENTER_LATEST]: ours comes back under the black and the black lifts on OUR notes page. Xiaomi's own
 *  page is never shown standing.
 * Afterwards Xiaomi's own copy of the note is taken out of ITS list again, through its own function ([TIDY_AT]),
 * so that list stays as he had it and never fills (it holds nine, and refuses a pin when full): a note of words by its
 * words and its time ([tidy]), a PICTURE by its kind and its time ([tidyPicture]; it is handed to Xiaomi as a content
 * address of ours, data/PinImageFiles.uri, which Xiaomi's package alone is let read).
 *
 * Pure: time in, what the back's top layer is and whether ours has stepped aside under it.
 */
object PinVia {
    /** From our handing the words over to Xiaomi's first stage beginning, where it is not heard (143 to 180 ms in four runs). */
    const val FRONT_FIRST_MS = 165L
    /** Xiaomi's front animation over ("finished"). */
    const val FRONT_GONE_MS = FRONT_FIRST_MS + 2_050L

    const val DIP_AT = FRONT_FIRST_MS + 1_950L
    const val DIP_MS = 150L
    const val SWITCH_AT = DIP_AT + DIP_MS
    /** Where the back's arrival usually begins, and by when it is no longer waited for. */
    const val ENTER_USUAL = FRONT_GONE_MS + 200L
    const val ENTER_LATEST = FRONT_GONE_MS + 800L

    // After Xiaomi's "enter":
    const val LIFT_AFTER = 260L
    const val BLOOM_MS = 130L
    const val OURS_AFTER = LIFT_AFTER + BLOOM_MS
    const val OURS_MS = 220L
    /**
     * THE OTHER WAY, KEPT TO COMPARE (a debug build's `--ez full true`; not what a pin does): Xiaomi's arrival is let
     * through to its end, its own note with the lit rim too, and ours fades in over it only then. The two notes lie
     * over each other while it fades (Xiaomi's words are larger and at the left, ours in the middle).
     */
    const val FULL_OURS_AFTER = 950L
    fun oursAfter(full: Boolean) = if (full) FULL_OURS_AFTER else OURS_AFTER

    /**
     * Whether a pin is to go through Xiaomi's own: the setting ("Pin animation": Xiaomi's or Ours), never a demo, and
     * not where a debug build asks for our own. (Whether it CAN is found as it is tried: PinActivity falls back to ours.)
     */
    fun wanted(setting: Boolean, demo: Boolean, ownAsked: Boolean): Boolean = setting && !demo && !ownAsked

    const val BUILT_ON = 426091419L
    fun timedFor(versionCode: Long?): Boolean = versionCode == BUILT_ON

    /** Whether Xiaomi's pin has really begun is looked at here (heard, or its overlay window is there): if not, our own arrival plays. */
    const val CHECK_AT = 700L
    const val TIDY_AT = 6_000L

    // ---- what Xiaomi says in the log ----

    const val LOG_TAG = "SubScreenCenter_PinAnimationSurfaceView"
    const val REAR_TAG = "SubScreenCenter_SmartAssistantPanel"

    /** A stage of Xiaomi's front animation beginning or its end, [afterFirst] ms after its first stage began; or the back's arrival beginning. */
    enum class Heard(val afterFirst: Long) { STAGE0(0L), STAGE1(200L), STAGE2(1_500L), FINISHED(2_050L), ENTER(-1L) }

    private val stage = Regex("""start animation ([012])\s*$""")

    /** What one line of the log (brief format) says of Xiaomi's pin; null for any other line. */
    fun heard(line: String): Heard? = when {
        line.contains(REAR_TAG) -> if (line.contains("Pin card enter animation start")) Heard.ENTER else null
        !line.contains(LOG_TAG) -> null
        line.trimEnd().endsWith("] - finished") -> Heard.FINISHED
        else -> when (stage.find(line)?.groupValues?.get(1)) { "0" -> Heard.STAGE0; "1" -> Heard.STAGE1; "2" -> Heard.STAGE2; else -> null }
    }

    /** The time this timeline counts from, for a front stage [what] heard at [now]. */
    fun zero(what: Heard, now: Long): Long = now - what.afterFirst - FRONT_FIRST_MS

    enum class Veil { NONE, BLACK, NOTES }

    /** [veil] at [alpha] over the whole back; [xiaomi]: our cover and pages have stepped aside under it. */
    data class At(val veil: Veil, val alpha: Float, val xiaomi: Boolean)

    /**
     * The back [ms] after [zero]; [enter]: when Xiaomi said its arrival began, on the same count, or null while it
     * has not. [over]: nothing more to do after this.
     */
    fun at(ms: Long, enter: Long?, full: Boolean = false): At {
        if (ms < DIP_AT) return At(Veil.NONE, 0f, false)
        if (ms < SWITCH_AT) return At(Veil.BLACK, ease((ms - DIP_AT).toFloat() / DIP_MS), false)
        if (enter == null) {
            // Not said (yet): black over Xiaomi's back for as long as it may still come; then ours, under the black.
            if (ms < ENTER_LATEST) return At(Veil.BLACK, 1f, true)
            val d = ms - ENTER_LATEST
            return if (d < OURS_MS) At(Veil.BLACK, 1f - ease(d.toFloat() / OURS_MS), false) else At(Veil.NONE, 0f, false)
        }
        // Said before ours had stepped aside: its steps are where it says, and nothing is lifted before the switch.
        val lift = maxOf(enter + LIFT_AFTER, SWITCH_AT + 30L)
        val ours = maxOf(enter + oursAfter(full), lift + BLOOM_MS)
        val restore = ours + OURS_MS + 20L
        return when {
            ms < lift -> At(Veil.BLACK, 1f, true)
            ms < lift + BLOOM_MS -> At(Veil.BLACK, 1f - ease((ms - lift).toFloat() / BLOOM_MS), true)
            ms < ours -> At(Veil.NONE, 0f, true)
            ms < ours + OURS_MS -> At(Veil.NOTES, ease((ms - ours).toFloat() / OURS_MS), true)
            ms < restore -> At(Veil.NOTES, 1f, true)
            ms < restore + 180L -> At(Veil.NOTES, 1f, false)
            else -> At(Veil.NONE, 0f, false)
        }
    }

    /** After this nothing of a pin's hand-over is left on the back. */
    fun over(ms: Long, enter: Long?, full: Boolean = false): Boolean = ms >= SWITCH_AT && at(ms, enter, full).let { it.veil == Veil.NONE && !it.xiaomi }

    private fun ease(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }

    /**
     * One of Xiaomi's own pinned notes, as its list function gives it: its card id, words, its kind ("text" for a note
     * of words; another for a picture) and, for a picture, where Xiaomi has it.
     */
    data class Note(val id: String, val text: String, val type: String = "", val image: String = "") {
        /** A picture's card: its kind says so, or (no kind given) it has a picture; never a card of words. */
        val picture: Boolean get() {
            val t = type.trim().lowercase()
            if (t == "text") return false
            return PICTURE_KINDS.any { t.contains(it) } || image.isNotBlank()
        }
    }
    private val PICTURE_KINDS = listOf("image", "picture", "photo", "img", "pic")

    /**
     * Which of Xiaomi's notes to take out of its list after a pin of ours went through it: those MADE AS THIS PIN WAS
     * ([startedWall], the clock's time when we started Xiaomi's pin) that carry exactly the words we pinned. Never a
     * note he pinned there himself, at another time, even with the same words.
     *
     * XIAOMI'S NOTE IDS ARE THE TIME THEY WERE MADE: the clock's milliseconds cut to 32 bits (seen on the phone: a
     * pin made a note whose id was 171 ms after its start; two notes pinned 6 min 27 s apart had ids 387 217 apart).
     * Reading its list "before" instead raced with Xiaomi, which had the note in its list before the reading came
     * back, and nothing was taken out.
     */
    fun tidy(startedWall: Long, after: List<Note>, text: String): List<String> =
        after.filter { n -> !n.picture && n.text.trim() == text.trim() && n.id.toIntOrNull()?.let { (it - startedWall.toInt()) in -BORN_BEFORE_MS..BORN_AFTER_MS } == true }.map { it.id }
    const val BORN_BEFORE_MS = 500
    const val BORN_AFTER_MS = 4_000

    /**
     * After a PICTURE went through Xiaomi's pin (handed over at [startedWall]): THE ONE picture card made as this pin
     * was, known by its kind and its time, never by words (Xiaomi has no words of ours for a picture). Cards of words
     * are never taken, nor a picture card made at any other time; of two in the window (he would have had to share
     * one to Xiaomi's own pin in the same seconds) only the one nearest the moment Xiaomi usually makes it.
     */
    fun tidyPicture(startedWall: Long, after: List<Note>): List<String> {
        fun born(n: Note): Int? = n.id.toIntOrNull()?.let { it - startedWall.toInt() }
        return after.filter { n -> n.picture && born(n)?.let { it in -BORN_BEFORE_MS..BORN_AFTER_MS } == true }
            .minByOrNull { kotlin.math.abs(born(it)!! - USUAL_BORN_MS) }?.let { listOf(it.id) }.orEmpty()
    }
    /** How long after our hand-over Xiaomi's card usually carries as its id (171 ms seen for words). */
    const val USUAL_BORN_MS = 170

    /** For the log: the kinds (and whether each has a picture's address) of the cards made in a pin's window; never their words. */
    fun kindsNear(startedWall: Long, after: List<Note>): String =
        after.filter { n -> n.id.toIntOrNull()?.let { (it - startedWall.toInt()) in -BORN_BEFORE_MS..BORN_AFTER_MS } == true }
            .joinToString { "${it.type.ifBlank { "?" }.take(24)}${if (it.image.isNotBlank()) "+uri" else ""}" }.ifEmpty { "none" }

    /** Xiaomi's list as its function `getRearStickyNotes` prints it (each value in a one-element array): its notes, and how many more it takes. */
    fun notes(json: String): Pair<List<Note>, Int>? = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json.substring(json.indexOf('{')))
        fun one(e: kotlinx.serialization.json.JsonElement?): String? = when (e) {
            is kotlinx.serialization.json.JsonArray -> e.firstOrNull()?.let { one(it) }
            is kotlinx.serialization.json.JsonPrimitive -> e.content
            else -> null
        }
        val value = ((root as kotlinx.serialization.json.JsonObject)["androidAppfunctionsReturnValue"] as kotlinx.serialization.json.JsonArray).first() as kotlinx.serialization.json.JsonObject
        val items = (value["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { i ->
            val o = i as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            Note(one(o["card_id"]) ?: return@mapNotNull null, one(o["text"]).orEmpty(), one(o["type"]).orEmpty(), one(o["image_uri"]).orEmpty())
        }
        items to (one(value["remaining"])?.toIntOrNull() ?: (9 - items.size))
    }.getOrNull()

    const val PACKAGE = "com.xiaomi.subscreencenter"
    const val ACTIVITY = "com.xiaomi.subscreencenter.pin.PinReceiveActivity"
    private const val FUNCTIONS = "com.xiaomi.subscreencenter.appfunction.StickyNoteFunctions"
    const val LIST = "cmd app_function execute-app-function --package $PACKAGE --function '$FUNCTIONS#getRearStickyNotes' --parameters '{}' --timeout-duration 8"
    fun delete(id: String) = "cmd app_function execute-app-function --package $PACKAGE --function '$FUNCTIONS#deleteStickyNote' --parameters '{\"card_id\":\"${id.filter { it.isDigit() || it == '-' }}\",\"confirmed\":true}' --timeout-duration 8 --brief-yaml"
}
