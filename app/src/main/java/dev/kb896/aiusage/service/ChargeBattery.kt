package dev.kb896.aiusage.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock

/** The app's reading of Android's battery, shared by the rear page and the settings preview. */
object ChargeBattery {
    data class Snapshot(val sampledAt: Long, val level: Int, val plugged: Boolean?, val status: Int, val fast: Boolean) {
        val state: ChargeState.Is get() = ChargeState.of(plugged == true, status, level)

        fun detail() = ChargePrecise.Detail(
            level, -1f, SystemClock.uptimeMillis(), 0f,
            holding = state != ChargeState.Is.FILLING,
        )

        fun message(detail: ChargePrecise.Detail = detail(), kind: String = "") = Bundle().apply {
            putLong("sampledAt", sampledAt)
            putInt("level", level)
            putFloat("fraction", if (detail.level == level) detail.fraction else -1f)
            putLong("at", detail.at)
            putFloat("rate", detail.ratePerSec)
            putString("kind", kind)
            putBoolean("holding", detail.holding)
        }
    }

    fun read(context: Context): Snapshot {
        val sampledAt = SystemClock.elapsedRealtimeNanos()
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val rawLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val stickyLevel = rawLevel.takeIf { it in 0..scale }?.let { ChargeLevel.percent(it, scale) }
        val level = stickyLevel ?: runCatching {
            context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()?.takeIf { it in 0..100 } ?: ChargePrecise.UNKNOWN
        return Snapshot(
            sampledAt, level,
            plugged = battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_PLUGGED) }
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 },
            status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1,
            fast = ChargeLevel.fast(
                battery?.getIntExtra(ChargeLevel.EXTRA_MAX_CURRENT, 0) ?: 0,
                battery?.getIntExtra(ChargeLevel.EXTRA_MAX_VOLTAGE, 0) ?: 0,
            ),
        )
    }
}
