package dev.kb896.aiusage.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.service.EarbudsGate
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import dev.kb896.aiusage.service.EarbudsWatch
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class EarbudsOverlayRemovalTest {
    @Before @After fun resetLifecycle() {
        val active = EarbudsOverlay::class.java.getDeclaredField("active").apply { isAccessible = true }
        (active.get(EarbudsOverlay) as View?)?.let { view ->
            runCatching { view.context.getSystemService(WindowManager::class.java).removeViewImmediate(view) }
        }
        active.set(EarbudsOverlay, null)
        val front = EarbudsWatch::class.java.getDeclaredField("frontActive").apply { isAccessible = true }
        (front.get(null) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        val last = EarbudsWatch::class.java.getDeclaredField("lastStart").apply { isAccessible = true }
        (last.get(null) as java.util.concurrent.atomic.AtomicLong).set(0)
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @Test fun `removal before the first attach must release the slot`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var ends = 0
        assertTrue(EarbudsOverlay.show(context, EarbudsGate.Way.APP_OVERLAY) { ends++ })
        val field = EarbudsOverlay::class.java.getDeclaredField("active").apply { isAccessible = true }
        val view = field.get(EarbudsOverlay) as View
        assertFalse("regression requires removal before attachment", view.isAttachedToWindow)
        context.getSystemService(WindowManager::class.java).removeViewImmediate(view)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(EarbudsOverlay.LONGEST_MS + 100))
        assertEquals("completion even if no attach happened", 1, ends)
        assertTrue("new connection must be allowed", EarbudsOverlay.show(context, EarbudsGate.Way.APP_OVERLAY) { ends++ })
    }
    @Test fun `watchdog without a first vsync must release the connection gate`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofSeconds(20))
        assertTrue(EarbudsWatch.play(context, null, null, EarbudsGate.Way.APP_OVERLAY))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(EarbudsOverlay.LONGEST_MS + 100))
        val frontField = EarbudsWatch::class.java.getDeclaredField("frontActive").apply { isAccessible = true }
        val locked = (frontField.get(null) as java.util.concurrent.atomic.AtomicBoolean).get()
        assertFalse("watchdog must release gate without onDetached", locked)
        // The rear handoff rebases the ordinary connection cooldown; let it finish too.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(dev.kb896.aiusage.rear.EarbudsMotion.WHOLE_MS))
        assertTrue("another connection after timeout and cooldown",
            EarbudsWatch.play(context, null, null, EarbudsGate.Way.APP_OVERLAY))
    }

    @Test fun `external removal after attach does release the slot`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var ends = 0
        assertTrue(EarbudsOverlay.show(context, EarbudsGate.Way.APP_OVERLAY) { ends++ })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        val field = EarbudsOverlay::class.java.getDeclaredField("active").apply { isAccessible = true }
        val view = field.get(EarbudsOverlay) as View
        assertTrue("control reached attach", view.isAttachedToWindow)
        context.getSystemService(WindowManager::class.java).removeViewImmediate(view)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(EarbudsOverlay.LONGEST_MS + 100))
        assertEquals(1, ends)
        assertNull(field.get(EarbudsOverlay))
    }

    @Test fun `failed removal holds the slot until a retry confirms cleanup without attach`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val real = base.getSystemService(WindowManager::class.java)
        var attempts = 0
        val manager = object : WindowManager by real {
            override fun removeViewImmediate(view: View) {
                attempts++
                if (attempts == 1) throw IllegalStateException("Removal temporarily unavailable")
                real.removeViewImmediate(view)
            }
        }
        val context = object : ContextWrapper(base) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.WINDOW_SERVICE) manager else super.getSystemService(name)
        }
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofSeconds(20))
        var ends = 0
        assertTrue(EarbudsOverlay.show(context, EarbudsGate.Way.APP_OVERLAY) { ends++ })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(EarbudsOverlay.LONGEST_MS))
        assertEquals(1, attempts)
        assertEquals("no rear handoff while removal is unconfirmed", 0, ends)
        assertFalse(EarbudsOverlay.show(context, EarbudsGate.Way.APP_OVERLAY))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(2, attempts)
        assertEquals(1, ends)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals("completion and retries stop after confirmed removal", 1, ends)
        assertEquals(2, attempts)
        assertTrue(EarbudsOverlay.show(context, EarbudsGate.Way.APP_OVERLAY) { ends++ })
    }

}
