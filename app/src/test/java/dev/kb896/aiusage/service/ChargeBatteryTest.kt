package dev.kb896.aiusage.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargeBatteryTest {
    private var sticky: Intent? = null
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
            assertEquals(null, receiver)
            assertTrue(filter!!.hasAction(Intent.ACTION_BATTERY_CHANGED))
            return sticky
        }
    }

    private fun battery(level: Int, status: Int = BatteryManager.BATTERY_STATUS_CHARGING) =
        Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
            .putExtra(BatteryManager.EXTRA_STATUS, status)

    @Test fun `each show reads Android again and full status never supplies a number`() {
        sticky = battery(84)
        assertEquals(84, ChargeBattery.read(context).message().getInt("level"))
        sticky = battery(50)
        val now = ChargeBattery.read(context)
        assertEquals(50, now.message().getInt("level"))
        val wrongBase = now.message(ChargePrecise.Detail(84, 0.2f, 0L, 0f))
        assertEquals(50, wrongBase.getInt("level"))
        assertTrue(wrongBase.getFloat("fraction") < 0f)
        sticky = battery(99, BatteryManager.BATTERY_STATUS_FULL)
        val full = ChargeBattery.read(context)
        assertEquals(ChargeState.Is.FULL, full.state)
        assertEquals(99, full.message().getInt("level"))
        assertTrue(full.detail().holding)
    }

    @Test fun `missing sticky level uses valid BatteryManager capacity else stays empty`() {
        val manager = shadowOf(context.getSystemService(BatteryManager::class.java))
        manager.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 50)
        sticky = battery(-1)
        assertEquals(50, ChargeBattery.read(context).level)
        sticky = battery(120)
        assertEquals(50, ChargeBattery.read(context).level)
        manager.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, Int.MIN_VALUE)
        assertEquals(ChargePrecise.UNKNOWN, ChargeBattery.read(context).level)
    }
}
