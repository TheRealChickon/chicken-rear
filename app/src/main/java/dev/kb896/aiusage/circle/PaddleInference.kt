package dev.kb896.aiusage.circle

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Owned by one OCR worker. The detector is shared across all installed scripts. */
internal class PaddleInference(private val context: Context, private val root: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private fun options() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setInterOpNumThreads(1)
        setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
    }
    private val options = options()
    private val serverOptions = options().apply {
        // Release server activations between runs instead of retaining each resolution's arena.
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
    }
    private val detectors = mutableMapOf<OcrAsset, OrtSession>()
    private val sessions = LinkedHashMap<String, OrtSession>(4, .75f, true)
    private val dictionaries = mutableMapOf<String, List<String>>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scriptCheck = ScriptCheck()

    private fun session(pack: OcrAsset): OrtSession {
        if (pack == OcrCatalog.detector || pack == OcrCatalog.accurateDetector) {
            return detectors.getOrPut(pack) {
                val settings = if (pack == OcrCatalog.accurateDetector) serverOptions else options
                env.createSession(File(root, pack.fileName).path, settings)
            }
        }
        sessions[pack.name]?.let { return it }
        val accurate = OcrCatalog.accurateRecognizer.name
        if (pack.name != accurate) {
            val mobile = sessions.keys.filter { it != accurate }
            if (mobile.size >= 3) sessions.remove(mobile.first())?.close()
        }
        val graph = if (pack.name != "kannada") File(root, pack.fileName) else {
            // This operator graph references the verified Paddle weight file by byte offset.
            val to = File(root, "kannada-graph.onnx")
            val temp = File.createTempFile("graph-", ".tmp", root)
            try {
                context.assets.open("circle-ocr/kannada-graph.bin").use { src -> temp.outputStream().use { src.copyTo(it) } }
                Files.move(temp.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
            to
        }
        val settings = if (pack.name == accurate) serverOptions else options
        return env.createSession(graph.path, settings).also { sessions[pack.name] = it }
    }

    private fun labels(pack: OcrAsset): List<String> {
        val dictionary = if (pack == OcrCatalog.accurateRecognizer) "cjk" else pack.name
        return dictionaries.getOrPut(dictionary) {
            listOf("") + context.assets.open("circle-ocr/$dictionary.txt").bufferedReader().useLines { lines ->
                lines.map { if (it == "\\s") " " else it }.toList()
            } + " "
        }
    }

    private fun pixels(image: Bitmap, box: Rect, w: Int, h: Int): IntArray {
        val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            Canvas(scaled).drawBitmap(image, box, Rect(0, 0, w, h), paint)
            return IntArray(w*h).also { scaled.getPixels(it, 0, w, 0, 0, w, h) }
        } finally { scaled.recycle() }
    }

    private fun infer(model: OrtSession, data: FloatArray, h: Int, w: Int): Pair<LongArray, FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1,3,h.toLong(),w.toLong())).use { input ->
            model.run(mapOf(model.inputNames.first() to input)).use { result ->
                val tensor = result[0] as OnnxTensor
                val buffer = tensor.floatBuffer
                return tensor.info.shape to FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
    }

    private fun detect(image: Bitmap, detector: OcrAsset): List<Rect> {
        val longSide = if (detector == OcrCatalog.accurateDetector) 960f else 1440f
        val scale = minOf(1f, longSide / maxOf(image.width, image.height))
        fun side(n: Int) = ((n * scale / 32f).roundToInt().coerceAtLeast(1)) * 32
        val w = side(image.width)
        val h = side(image.height)
        val px = pixels(image, Rect(0,0,image.width,image.height), w,h)
        val data = FloatArray(px.size*3)
        for (i in px.indices) {
            val c = px[i]
            data[i] = ((c and 255)/255f - .485f)/.229f
            data[px.size+i] = (((c ushr 8) and 255)/255f - .456f)/.224f
            data[2*px.size+i] = (((c ushr 16) and 255)/255f - .406f)/.225f
        }
        val (shape, prob) = infer(session(detector), data,h,w)
        return regions(prob, shape.last().toInt(), shape[2].toInt(), image.width, image.height)
    }

    /** Screen-aligned DB polygons. Grow each connected foreground island by area/perimeter. */
    private fun regions(map: FloatArray, w: Int, h: Int, iw: Int, ih: Int): List<Rect> {
        val queue = IntArray(map.size)
        val out = ArrayList<Rect>()
        for (i in map.indices) {
            if (map[i] < .3f) continue
            var head = 0
            var tail = 0
            var sum = 0f
            fun visit(j: Int) {
                if (map[j] < .3f) return
                sum += map[j]
                map[j] = 0f
                queue[tail++] = j
            }
            visit(i)
            var left = w; var right = 0; var top = h; var bottom = 0
            while (head < tail) {
                val j = queue[head++]; val x = j % w; val y = j / w
                left = minOf(left,x); right = maxOf(right,x)
                top = minOf(top,y); bottom = maxOf(bottom,y)
                if (x > 0) visit(j-1)
                if (x+1 < w) visit(j+1)
                if (y > 0) visit(j-w)
                if (y+1 < h) visit(j+w)
            }
            val width = right-left+1; val height = bottom-top+1
            if (minOf(width,height) < 3 || tail < 10 || sum / tail < .6f) continue
            val pad = 1.5f * tail / (2*(width+height))
            val box = Rect(((left-pad)*iw/w).toInt().coerceAtLeast(0), ((top-pad)*ih/h).toInt().coerceAtLeast(0),
                ceil((right+1+pad)*iw/w).toInt().coerceAtMost(iw), ceil((bottom+1+pad)*ih/h).toInt().coerceAtMost(ih))
            if (!box.isEmpty) out.add(box)
        }
        return out.sortedByDescending { it.width().toLong()*it.height() }.take(512)
    }

    private fun recognize(pack: OcrAsset, px: IntArray, width: Int, box: Rect, ink: BooleanArray): Reading {
        val w = maxOf(320, (width+7)/8*8)
        val plane = 48*w
        val data = FloatArray(3*plane)
        for (y in 0 until 48) for (x in 0 until width) {
            val c = px[y*width+x]
            val i = y*w+x
            data[i] = (c and 255)/127.5f-1f
            data[plane+i] = ((c ushr 8) and 255)/127.5f-1f
            data[2*plane+i] = ((c ushr 16) and 255)/127.5f-1f
        }
        val (shape, scores) = infer(session(pack),data,48,w)
        return PaddleLayout.decode(scores,labels(pack),shape[1].toInt(),box,width,w,pack.name=="arabic",ink)
    }

    private data class Crop(
        val box: Rect, val width: Int, val pixels: IntArray, val ink: BooleanArray, val hint: String?,
        var best: Reading? = null, var recognizer: OcrAsset? = null, var settled: Boolean = false,
    )

    private fun crop(image: Bitmap, box: Rect): Crop {
        val width = ceil(48.0 * box.width() / box.height()).toInt().coerceIn(8,1600)
        val px = pixels(image,box,width,48)
        return Crop(box,width,px,scriptCheck.inkColumns(px,width),scriptCheck.match(px,width))
    }

    private fun accurateRegions(image: Bitmap): List<TextRegion> {
        val pack = OcrCatalog.accurateRecognizer
        return detect(image, OcrCatalog.accurateDetector).mapNotNull { box ->
            val crop = crop(image, box)
            // The server dictionary covers CJK and English. Only claim CJK regions here so
            // nearby Latin and Korean can keep their own full pipeline and dictionaries.
            if (crop.hint != null && crop.hint != "cjk") return@mapNotNull null
            val reading = recognize(pack,crop.pixels,crop.width,box,crop.ink)
            if (reading.confidence >= .65f && OcrCatalog.hasChineseOrJapanese(reading.text))
                TextRegion(box,reading) else null
        }
    }

    private fun covered(box: Rect, accurate: Rect): Boolean {
        val w = minOf(box.right, accurate.right) - maxOf(box.left, accurate.left)
        val h = minOf(box.bottom, accurate.bottom) - maxOf(box.top, accurate.top)
        return w >= box.width() * .7f && h >= box.height() * .6f
    }

    fun read(image: Bitmap, installed: Collection<OcrAsset>, kx: Float, ky: Float,
             dx: Float = 0f, dy: Float = 0f, model: OcrModel = OcrModel.FAST): OcrResult {
        val packs = if (OcrCatalog.detector in installed) installed.filter { it in OcrCatalog.recognizers }
            else emptyList()
        val accurate = model == OcrModel.ACCURATE && OcrCatalog.accurateReady(installed)
        val active = packs + if (accurate) listOf(OcrCatalog.accurateRecognizer) else emptyList()
        sessions.keys.filter { id -> active.none { it.name == id } }.forEach { sessions.remove(it)?.close() }
        if (!accurate) detectors.remove(OcrCatalog.accurateDetector)?.close()
        if (packs.isEmpty()) detectors.remove(OcrCatalog.detector)?.close()
        if (image.isRecycled) return OcrResult(emptyList(),emptyList())

        val server = if (accurate) {
            try {
                accurateRegions(image)
            } catch (e: OrtException) {
                // A pack can be removed from Settings while this search is starting.
                detectors.remove(OcrCatalog.accurateDetector)?.close()
                sessions.remove(OcrCatalog.accurateRecognizer.name)?.close()
                android.util.Log.w("CircleSearch", "Accurate text recognition unavailable", e)
                emptyList()
            }
        } else emptyList()
        val found = server.toMutableList()
        val hints = mutableListOf<String>()
        val fallback = linkedSetOf<String>()
        val mobileBoxes = if (packs.isEmpty()) emptyList() else detect(image, OcrCatalog.detector)
        // Keep crop memory bounded. Run each recognizer across a group of regions before
        // switching sessions, including when the user has installed all thirteen packs.
        val uncovered = mobileBoxes.filterNot { box -> server.any { covered(box,it.box) } }
        for (batch in uncovered.chunked(16)) {
            val crops = batch.map { crop(image,it) }
            for (hinted in listOf(true,false)) for (pack in packs) for (crop in crops) {
                val matches = OcrCatalog.matchesHint(pack.name,crop.hint)
                if (crop.settled || matches != hinted) continue
                val r = recognize(pack,crop.pixels,crop.width,crop.box,crop.ink)
                if (r.confidence > (crop.best?.confidence ?: -1f)) {
                    crop.best = r
                    crop.recognizer = pack
                }
                if (matches && r.confidence >= .98f) crop.settled = true
            }
            for (crop in crops) {
                val reading = crop.best ?: continue
                val script = OcrCatalog.scriptIn(reading.text)?.takeIf { reading.confidence >= .65f } ?: crop.hint
                if (script != null && packs.none { OcrCatalog.matchesHint(it.name,script) } &&
                    !(accurate && script == "cjk")) hints.add(script)
                else if (reading.confidence < .80f) hints.add("unknown")
                if (reading.confidence >= .48f && reading.text.isNotEmpty()) {
                    found.add(TextRegion(crop.box,reading))
                    if (model == OcrModel.ACCURATE) {
                        OcrCatalog.fallbackLabel(reading.text,checkNotNull(crop.recognizer))?.let(fallback::add)
                    }
                }
            }
        }
        val offers = hints.groupingBy { it }.eachCount().entries.sortedWith(
            compareBy<Map.Entry<String,Int>> { it.key == "unknown" }.thenByDescending { it.value }).map { it.key }
        val result = PaddleLayout.result(found,kx,ky,dx,dy,offers)
        return OcrResult(result.words,result.blocks,result.suggestedPacks,fallback.sorted())
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        detectors.values.forEach { it.close() }
        options.close()
        serverOptions.close()
    }
}
