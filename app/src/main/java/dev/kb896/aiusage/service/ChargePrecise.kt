package dev.kb896.aiusage.service

/**
 * The battery's level past the whole percent, for the charging page's hundredths, FROM WHAT IS
 * REALLY KNOWN, never an invented tick.
 *
 * What the phone gives an app (the battery's own files are closed to the shell and to apps):
 * - the whole percent, each time it changes (ACTION_BATTERY_CHANGED);
 * - on a super fast charger, Xiaomi's own hundredths and how fast they run
 *   (miui.intent.action.ACTION_SOC_DECIMAL: "soc_decimal", 0..99, and "soc_decimal_rate",
 *   hundredths per second times ten as SystemUI reads it): used when it comes;
 * - otherwise THE MOMENT THE WHOLE PERCENT STEPS, which is the moment the hundredths are .00,
 *   and the time the last whole percent took, which is how fast they run.
 * So: no hundredths until one of the two is known (the page then shows the whole percent alone);
 * from a step on, .00 rising at the last percent's own pace, held under the next whole percent
 * until the battery says it; from Xiaomi's figure, that figure at its own rate.
 *
 * Pure: times are passed in (uptime, ms).
 */
class ChargePrecise {
    /** What the page is told: the hundredths as a part of one percent at [at], rising by [ratePerSec]; [fraction] under 0 for unknown. */
    data class Detail(
        val level: Int, val fraction: Float, val at: Long, val ratePerSec: Float,
        /** The hundredths are Xiaomi's own figure, not counted from a step. */
        val xiaomi: Boolean = false,
        /** ON THE CHARGER BUT NOT FILLING (a charge limit reached, full, paused): the figure stands, whole, and the fluid rests. */
        val holding: Boolean = false,
    )

    private var level = -1
    private var steppedAt = 0L          // when the level last went up by one, 0 if not seen
    private var percentMs = 0L          // how long the last whole percent took, 0 if not known
    private var decimal = -1f           // Xiaomi's own, as a part of one percent
    private var decimalAt = 0L
    private var decimalRate = 0f
    // The level back under the whole percent it had just reached (seen charging at 92 / 93: 93, 92, 93,
    // 92 within a minute): it stands just under that percent, and the next step up is not "a percent in twenty seconds".
    private var under = false
    private var wavered = false
    // NOT REALLY CHARGING: the phone says so (its battery status is not
    // "charging": full, not charging, a limit holding it), or it says "charging" and the level has not stepped for
    // far longer than its own pace (a limit that still reports charging). Then nothing counts up.
    private var filling = true

    /** The charger went in (or came out): what was known belongs to another charge. */
    fun reset() { level = -1; steppedAt = 0L; percentMs = 0L; decimal = -1f; decimalAt = 0L; decimalRate = 0f; under = false; wavered = false; filling = true }

    /**
     * Whether the battery is really filling, as the phone says ([charging]: its status is "charging", not "full" or
     * "not charging"). Going from holding to filling again nothing is known past the whole percent until it next
     * steps (or Xiaomi says): it picks up from the real value, without a jump.
     */
    fun filling(charging: Boolean, now: Long): Detail {
        if (charging && !filling) { steppedAt = 0L; percentMs = 0L; decimal = -1f; under = false; wavered = false }
        filling = charging
        return detail(now)
    }

    /** Stalled: it says it charges, and a percent that took [percentMs] has not come for more than twice that and a minute. */
    private fun stalled(now: Long) = steppedAt != 0L && percentMs > 0L && decimal < 0f && now - steppedAt > percentMs * 2 + STALL_GRACE_MS

    /** The whole percent as the battery reports it at [now]. */
    fun level(percent: Int, now: Long): Detail {
        if (level >= 0 && percent == level + 1) {
            // How long the last whole percent took: only from one true step to the next, not across a waver.
            if (steppedAt != 0L && !wavered) percentMs = now - steppedAt
            steppedAt = now
            under = false; wavered = false
            decimal = -1f                // Xiaomi's figure was for the percent before
        } else if (level >= 0 && percent == level - 1 && steppedAt != 0L && !under) {
            // BACK UNDER THE LINE IT HAD JUST CROSSED: it is just under it (.99, standing), and what was known of its pace is kept.
            under = true; wavered = true
            decimal = -1f
        } else if (percent != level) {
            steppedAt = 0L; percentMs = 0L; decimal = -1f; under = false; wavered = false       // a jump, or the first reading: nothing is known past it
        }
        level = percent
        return detail(now)
    }

    /** Xiaomi's own hundredths ([hundredths] 0..99) and their rate ([rate], hundredths in ten seconds), at [now]. */
    fun xiaomi(hundredths: Int, rate: Int, now: Long): Detail {
        if (hundredths in 0..99) { decimal = hundredths / 100f; decimalAt = now; decimalRate = rate.coerceAtLeast(0) / 100f / 10f }
        return detail(now)
    }

    fun detail(now: Long): Detail = when {
        // Not read yet (the charger's kind can be told before the first battery reading): NO LEVEL, never a 0 for the page.
        level < 0 -> Detail(UNKNOWN, -1f, now, 0f)
        // Holding: the whole percent alone, standing (no hundredths to pretend with), and said so for the fluid.
        !filling || stalled(now) -> Detail(level, -1f, now, 0f, holding = true)
        decimal >= 0f -> Detail(level, decimal, decimalAt, decimalRate, xiaomi = true)
        // Back under the line it had just crossed: SHOWN AT THE LINE, standing. Never a figure that runs backwards
        // (93.00, then 92.99, would be one): the percent it had reached, .00, until it truly steps.
        under -> Detail(level + 1, 0f, now, 0f)
        steppedAt != 0L -> Detail(level, 0f, steppedAt, if (percentMs > 0L) 1000f / percentMs else 0f)
        else -> Detail(level, -1f, now, 0f)
    }

    companion object {
        /** A [Detail.level] that is not known: the page keeps the level it has. */
        const val UNKNOWN = -1
        const val STALL_GRACE_MS = 60_000L
        const val ACTION_SOC_DECIMAL = "miui.intent.action.ACTION_SOC_DECIMAL"
        const val EXTRA_SOC_DECIMAL = "miui.intent.extra.soc_decimal"
        const val EXTRA_SOC_DECIMAL_RATE = "miui.intent.extra.soc_decimal_rate"
        const val ACTION_QUICK_CHARGE_TYPE = "miui.intent.action.ACTION_QUICK_CHARGE_TYPE"
        const val EXTRA_QUICK_CHARGE_TYPE = "miui.intent.extra.quick_charge_type"
        const val EXTRA_POWER_MAX = "miui.intent.extra.POWER_MAX"

        /** The charger in words, from Xiaomi's own type (0 ordinary, 1 fast, 2 and up its flash and super fast ones) and watts. */
        fun kind(type: Int, watts: Int): String {
            val name = when { type <= 0 -> ""; type == 1 -> "Fast charging"; else -> "Super fast charging" }
            return if (name.isEmpty()) "" else if (watts > 0) "$name · $watts W" else name
        }
    }
}
