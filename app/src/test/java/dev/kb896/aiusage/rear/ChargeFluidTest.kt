package dev.kb896.aiusage.rear

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The charging fluid's colour by level: red low, green well charged, never purple or blue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargeFluidTest {
    @Test fun `red when nearly empty, green when well charged, warm between`() {
        assertEquals(0xFFFA382E.toInt(), ChargeFluid.color(0.05f))
        assertEquals(0xFF1DCD3A.toInt(), ChargeFluid.color(0.85f))
        assertEquals(0xFF1DCD3A.toInt(), ChargeFluid.color(1f))
        val mid = ChargeFluid.color(0.4f)
        assertTrue("a yellow green in the middle", Color.green(mid) > 150 && Color.red(mid) > 150 && Color.blue(mid) < 90)
        for (i in 0..100) {
            val c = ChargeFluid.color(i / 100f)
            assertTrue("never blue or purple at $i", Color.blue(c) < 100)
        }
    }
}
