package dev.kb896.aiusage.rear

/**
 * WHAT THE BACK FEEDS A XIAOMI FACE (MamlHost), because MAML's own receivers and alarms cannot register in a shell
 * process (proven by the probe): the time at each minute, the battery's level, dark mode. Only true sources; a face
 * that reads anything else is left out (MamlCensus.unreadable). His own edits (colour, show time…) are NOT mapped here:
 * Xiaomi's own code applies them (`WidgetEditSave.restoreMamlView`, from his `editConfig`, else from the face's
 * `var_config.xml` defaults), as its rear launcher does.
 *
 * Pure.
 */
object MamlFeed {
    /** MAML's variable names (`com.miui.maml.data.VariableNames`). */
    const val BATTERY_LEVEL = "battery_level"
    const val DARK_MODE = "__darkmode"
    /** The face is the picker's middle card (Xiaomi's `E2.i`: "selected"). */
    const val SELECTED = "selected"

    /** As MAML's own `BatteryVariableUpdater`: the intent's level, 100 at most; null when there is none (-1). */
    fun batteryLevel(level: Int): Double? = if (level < 0) null else if (level >= 100) 100.0 else level.toDouble()

    /** Dark mode as MAML's `DarkModeVariableUpdater` puts it: 1 or 0. */
    fun darkMode(night: Boolean): Double = if (night) 1.0 else 0.0

    /**
     * The next minute boundary after [nowMs] (wall clock, epoch ms): the face's clock changes there. Epoch minutes are
     * the local minutes in every zone this phone can be in (offsets are whole minutes, a summer-time change a whole
     * hour), so no zone is needed.
     */
    fun nextMinute(nowMs: Long): Long = (Math.floorDiv(nowMs, 60_000L) + 1) * 60_000L

    /** Milliseconds from [nowMs] to [nextMinute], at least [min] (a post never lands a hair before the minute). */
    fun untilNextMinute(nowMs: Long, min: Long = 5L): Long = (nextMinute(nowMs) - nowMs).coerceAtLeast(min) + 2L

    /** The commands Xiaomi's face host sends (`E2.i`): around the picker, and resume / pause with the light. */
    const val ENTER_EDIT = "enterEdit"
    const val EXIT_EDIT = "exitEdit"
    const val RESUME = "resume"
    const val PAUSE = "pause"
}
