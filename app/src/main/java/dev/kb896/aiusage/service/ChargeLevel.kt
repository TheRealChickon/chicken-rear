package dev.kb896.aiusage.service

/**
 * The battery as the charger goes in, from the sticky ACTION_BATTERY_CHANGED: its percent, and
 * whether the charger is a fast one. Pure: tested on the JVM (ChargeLevelTest).
 */
object ChargeLevel {

    /** BatteryManager's hidden extras: the charger's most current (µA) and voltage (µV). */
    const val EXTRA_MAX_CURRENT = "max_charging_current"
    const val EXTRA_MAX_VOLTAGE = "max_charging_voltage"

    /** The voltage assumed when the charger says none, as Android's own Settings assumes. */
    private const val DEFAULT_MICRO_VOLTS = 5_000_000L

    /**
     * Fast from this many watts up: Android's own line between "charging" and "charging rapidly"
     * (Settings' charging_speed thresholds put "fast" at 7.5 W and up; this asks for a real fast
     * charger, as the liquid's colour is about that).
     */
    const val FAST_WATTS = 15.0

    /** [level] of [scale] as a percent, 0..100; null when the battery did not say. */
    fun percent(level: Int, scale: Int): Int? {
        if (level < 0 || scale <= 0) return null
        return Math.round(level * 100f / scale).coerceIn(0, 100)
    }

    /** Whether a charger offering [maxMicroAmps] at [maxMicroVolts] is a fast one. Unknown: not. */
    fun fast(maxMicroAmps: Int, maxMicroVolts: Int): Boolean {
        if (maxMicroAmps <= 0) return false
        val volts = if (maxMicroVolts > 0) maxMicroVolts.toLong() else DEFAULT_MICRO_VOLTS
        val watts = maxMicroAmps.toLong() / 1_000_000.0 * (volts / 1_000_000.0)
        return watts >= FAST_WATTS
    }
}
