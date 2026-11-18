package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * THE ISLAND'S SHADER ON A REAL HARDWARE CANVAS (an emulator or a phone, never the unit tests: they have no AGSL): it
 * compiles, takes its uniforms, and draws what its Kotlin twin (MessageIsland.pixel, drawn on a software canvas) draws,
 * at the pill with its light, the page opening, the page open in each look, the fold. The two pictures of each moment are
 * kept in the app's external files ("island-check") for a look.
 */
@RunWith(AndroidJUnit4::class)
class IslandShaderTest {
    private val w = 976; private val h = 596

    private fun gpu(reader: ImageReader, draw: (Canvas) -> Unit): Bitmap {
        val surface = reader.surface
        val c = surface.lockHardwareCanvas()
        try { c.drawColor(Color.BLACK); draw(c) } finally { surface.unlockCanvasAndPost(c) }
        var image = reader.acquireLatestImage()
        val until = System.currentTimeMillis() + 3_000L
        while (image == null && System.currentTimeMillis() < until) { Thread.sleep(10); image = reader.acquireLatestImage() }
        checkNotNull(image) { "no picture from the hardware canvas" }
        image.use { img ->
            val plane = img.planes[0]
            val full = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            return Bitmap.createBitmap(full, 0, 0, w, h)
        }
    }

    @Test fun theShaderCompilesAndDrawsWhatItsKotlinTwinDraws() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "island-check").apply { mkdirs() }
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2, HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN)
        val cases = listOf(
            "pill" to (MessageIsland.Look.TINT_OVER_FACE to 1_000f),
            "growing" to (MessageIsland.Look.TINT_OVER_FACE to 1_300f),
            "open-tint" to (MessageIsland.Look.TINT_OVER_FACE to MessageIsland.IN_MS + 1_000f),
            "open-own" to (MessageIsland.Look.TINT_OWN to MessageIsland.IN_MS + 1_000f),
            "open-sweep" to (MessageIsland.Look.SWEEP to MessageIsland.SWEEP_FROM + 1_400f),
            "folding" to (MessageIsland.Look.SWEEP to MessageIsland.IN_MS + MessageIsland.HOLD_MS + 400f),
        )
        val report = StringBuilder()
        try {
            for ((name, c) in cases) {
                val (look, t) = c
                // Words without an icon: the layer and the words are what is compared.
                val art = IslandArt().apply { set("Mia", "Running 10 min late, save me a seat", 0xFF25D366.toInt(), null, look) }
                val f = MessageIsland.at(t, art.pillW, MessageIsland.Frame(), sweeps = look.sweep)
                val hw = gpu(reader) { art.draw(it, w.toFloat(), h.toFloat(), f) }
                val sw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
                IslandArt().apply { set("Mia", "Running 10 min late, save me a seat", 0xFF25D366.toInt(), null, look) }.draw(Canvas(sw), w.toFloat(), h.toFloat(), f)
                File(dir, "$name-gpu.png").outputStream().use { hw.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(dir, "$name-cpu.png").outputStream().use { sw.compress(Bitmap.CompressFormat.PNG, 100, it) }
                // Level by level: the mean difference and how many pixels differ by more than 12 of 255 (text edges aside).
                var sum = 0L; var far = 0; var n = 0
                for (y in 0 until h step 2) for (x in 0 until w step 2) {
                    val a = hw.getPixel(x, y); val b = sw.getPixel(x, y)
                    val d = maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
                    sum += d; if (d > 12) far++; n++
                }
                val mean = sum.toFloat() / n
                val share = far.toFloat() / n
                report.append("$name: mean ${"%.2f".format(java.util.Locale.ROOT, mean)} levels, ${"%.3f".format(java.util.Locale.ROOT, share * 100)}% over 12\n")
                assertTrue("$name: mean difference $mean", mean < 2.5f)
                assertTrue("$name: $far pixels of $n differ by more than 12", share < 0.02f)
            }
        } finally {
            File(dir, "report.txt").writeText(report.toString())
            reader.close()
        }
    }
}
