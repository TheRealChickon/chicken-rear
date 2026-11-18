package dev.kb896.aiusage.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.PinImageFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Pin to back" given a picture: while it is read nothing of the pin is drawn, and the screen must not hold the phone's
 * touches meanwhile. A touch ends it; the picture is pinned all the same. Made-up pictures and addresses only.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinPictureReadTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clean() { PinImageFiles.dir(context)?.deleteRecursively(); ConfigStore(context).save(PanelConfig.DEFAULT) }

    @After fun reader() { PinActivity.importPicture = PinImageFiles::import }

    @Test fun `a touch while a shared picture is read ends the empty screen, and the picture is pinned all the same`() {
        val started = CountDownLatch(1); val go = CountDownLatch(1)
        // A slow read (a picture from the network): held until the test lets it go.
        PinActivity.importPicture = { c, _, at ->
            started.countDown(); go.await(5, TimeUnit.SECONDS)
            PinImageFiles.save(c, Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(0, 255, 0)); setHasAlpha(false) }, at)
        }
        val share = Intent(Intent.ACTION_SEND).setClassName(context, PinActivity::class.java.name).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://dev.test.pictures/one.png"))
        val screen = Robolectric.buildActivity(PinActivity::class.java, share).setup().get()
        assertTrue("the read has begun", started.await(5, TimeUnit.SECONDS))
        assertFalse(screen.isFinishing)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 500f, 900f, 0)
        assertTrue("the touch is taken", screen.dispatchTouchEvent(down))
        down.recycle()
        assertTrue("a touch ends the screen while nothing of the pin is drawn", screen.isFinishing)
        assertTrue("nothing pinned yet", ConfigStore(context).load().pins.isEmpty())
        go.countDown()
        val until = System.currentTimeMillis() + 5_000L
        while (ConfigStore(context).load().pins.isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(10L)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val pins = ConfigStore(context).load().pins
        assertEquals("the picture is pinned once it is read", 1, pins.size)
        val name = pins.single().image
        assertNotNull(name)
        assertTrue(File(PinImageFiles.dir(context), name!!).isFile)
    }
}
