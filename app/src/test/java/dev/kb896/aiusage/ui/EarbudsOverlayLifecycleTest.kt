package dev.kb896.aiusage.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.service.EarbudsGate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EarbudsOverlayLifecycleTest {
    @Test fun `only one window and removal releases it and notifies exactly once`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var ends = 0
        assertTrue(EarbudsOverlay.show(context, EarbudsGate.Way.APP_OVERLAY) { ends++ })
        assertFalse(EarbudsOverlay.show(context, EarbudsGate.Way.APP_OVERLAY) { ends += 100 })
        assertEquals(0, ends)
        // Even without frame rendering, the watchdog removes the window and completes independently of detachment.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(EarbudsOverlay.LONGEST_MS + 100))
        assertEquals(1, ends)
        assertTrue(EarbudsOverlay.show(context, EarbudsGate.Way.APP_OVERLAY) { ends++ })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(EarbudsOverlay.LONGEST_MS + 100))
        assertEquals(2, ends)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(2, ends)
    }
}
