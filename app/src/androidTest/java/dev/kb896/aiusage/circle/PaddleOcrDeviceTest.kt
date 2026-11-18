package dev.kb896.aiusage.circle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureNanoTime

/** Uses the APK's JNI, Android fonts and packs previously approved in Settings. */
@RunWith(AndroidJUnit4::class)
class PaddleOcrDeviceTest {
    @Test fun mixedTextIsSelectableOnAFullSizeScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = OcrModels.root(context)
        val verifier = OcrPack(root)
        val cjk = OcrCatalog.recognizers.single { it.name == "cjk" }
        assumeTrue("Install the Chinese and Japanese pack in Settings first",
            verifier.verified(OcrCatalog.detector) && verifier.verified(cjk))
        val bitmap = Bitmap.createBitmap(1200,2608,Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 42f }
            repeat(12) { canvas.drawText("设置 Settings",55f,70f+215f*it,paint) }
            val times = mutableListOf<Long>()
            PaddleInference(context,root).use { engine ->
                repeat(6) {
                    lateinit var result: OcrResult
                    times += measureNanoTime { result = engine.read(bitmap,listOf(OcrCatalog.detector,cjk),1f,1f) } / 1_000_000
                    val first = result.words.filter { it.line == 0 }
                    assertEquals(listOf("设","置","Settings"),first.map { it.text })
                    assertTrue(first[0].box.right <= first[1].box.left)
                    assertTrue(first.last().characters.size == "Settings".length)
                    assertTrue(first.all { w -> Rect(0,0,1200,2608).contains(w.box) })
                }
            }
            Log.i("CircleSearch", "1200×2608 ms, cold then warm: $times")
        } finally { bitmap.recycle() }
    }
}
