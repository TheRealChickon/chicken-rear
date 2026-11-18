package dev.kb896.aiusage.circle

import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.system.measureNanoTime

/** Opt-in desktop run. PADDLE_OCR_BENCH contains desktop-native/, archives/ and fixtures/.
 * Use the matching Maven ORT JNI libraries, official tars and scripts/ocr/render_benchmark.py.
 * This exercises production installation, native inference, CTC and paragraph assembly. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaddleBenchmarkTest {
    @Test fun officialModelsOnRenderedScreens() {
        val path = System.getenv("PADDLE_OCR_BENCH")
        assumeNotNull(path)
        val dir = File(path!!)
        System.setProperty("onnxruntime.native.path", File(dir,"desktop-native").path)
        val app = RuntimeEnvironment.getApplication()
        val root = OcrModels.root(app)
        root.mkdirs()
        for (a in OcrCatalog.assets) {
            val part = File(root,"${a.name}.part")
            File(dir,"archives/${a.archive}").copyTo(part,overwrite = true)
            val pack = OcrPack(root) { _,_ -> error("The verified local archive must suffice") }
            pack.install(a) {}
            assertTrue(a.label,pack.verified(a))
        }
        val images = File(dir,"fixtures")
        val fast = listOf("det","latin","cjk","korean").map { n -> OcrCatalog.assets.single { it.name == n } }
        val accurate = fast + OcrCatalog.accuratePacks
        val expected = File(images,"labels.txt").readLines()
        val report = File(dir,"model-choice-results.tsv")
        val normalize = { s: String -> s.replace(Regex("(?<=[\\p{IsHan}\\p{IsHangul}\\p{IsHiragana}\\p{IsKatakana}])\\s+(?=[\\p{IsHan}\\p{IsHangul}\\p{IsHiragana}\\p{IsKatakana}])"),"") }
        report.bufferedWriter().use { out ->
            val screens = images.listFiles()!!.filter { it.extension == "png" }.sortedBy { it.name }
            for (model in OcrModel.entries) PaddleInference(app,root).use { engine ->
                for (file in screens) {
                    val image = BitmapFactory.decodeFile(file.path)
                    val times = mutableListOf<Long>()
                    var result = OcrResult(emptyList(), emptyList())
                    repeat(6) {
                        times += measureNanoTime {
                            result = engine.read(image,accurate,1f,1f,model = model)
                        } / 1_000_000
                    }
                    val lines = result.blocks.flatMap { it.lines }
                    val step = if (image.height == 1240) 95 else 215
                    val actual = expected.indices.map { i ->
                        lines.filter { kotlin.math.abs(it.box.centerY()-(70+i*step-12)) < step/2 }
                            .sortedBy { it.box.left }.joinToString(" ") { it.text }
                    }
                    val correct = expected.zip(actual).count { (a,b) -> normalize(a) == normalize(b) }
                    out.appendLine(listOf(model.name,file.name,correct,times.joinToString(","),
                        result.fastFallback.joinToString(","),actual.joinToString("|")).joinToString("\t"))
                    out.flush()
                    assertTrue("Recognition regression on ${file.name} for $model: $actual", correct >= 11)
                    assertTrue("No selectable text on ${file.name}", result.words.isNotEmpty())
                    if (model == OcrModel.FAST) assertNull(result.fallbackNotice)
                    else {
                        assertTrue(result.fallbackNotice.orEmpty(), "Latin" in result.fastFallback)
                        assertTrue(result.fallbackNotice.orEmpty(), "Korean" in result.fastFallback)
                    }
                    image.recycle()
                }
            }
            PaddleInference(app,root).use { engine ->
                val screen = BitmapFactory.decodeFile(File(images,"1080-42-light.png").path)
                val expectedFast = engine.read(screen,fast,1f,1f).words.map { it.text }
                for (missing in OcrCatalog.accuratePacks) {
                    val fallback = engine.read(screen,accurate - missing,1f,1f,model = OcrModel.ACCURATE)
                    assertEquals(expectedFast, fallback.words.map { it.text })
                    assertTrue(fallback.fallbackNotice.orEmpty(), "Chinese and Japanese" in fallback.fastFallback)
                }
                val serverOnly = engine.read(screen,OcrCatalog.accuratePacks,1f,1f,model = OcrModel.ACCURATE)
                assertTrue(serverOnly.words.any { OcrCatalog.hasChineseOrJapanese(it.text) })
                assertNull(serverOnly.fallbackNotice)
                assertTrue(engine.read(screen,OcrCatalog.accuratePacks,1f,1f).words.isEmpty())
                screen.recycle()
                // All optional dictionaries, the Kannada external graph and all official model files
                // must also execute on a real crop, including packs outside the six-screen comparison.
                val crop = BitmapFactory.decodeFile(File(images,"1080-42-light.png").path)
                val first = android.graphics.Bitmap.createBitmap(crop,20,0,400,95)
                for (asset in OcrCatalog.recognizers) engine.read(first,listOf(OcrCatalog.detector,asset),1f,1f)
                File(dir,"script-results.tsv").bufferedWriter().use { hints ->
                    for (sample in File(images,"scripts").listFiles()!!.sortedBy { it.name }) {
                        val screen = BitmapFactory.decodeFile(sample.path)
                        val a = OcrCatalog.assets.single { it.name == sample.nameWithoutExtension }
                        val missing = engine.read(screen,fast.take(2),1f,1f)
                        val installed = engine.read(screen,listOf(OcrCatalog.detector,a),1f,1f)
                        hints.appendLine(listOf(a.name,missing.suggestedPacks.joinToString(","),
                            installed.blocks.joinToString { it.text }).joinToString("\t"))
                        screen.recycle()
                    }
                }
                first.recycle()
                crop.recycle()
            }
        }
    }
}
