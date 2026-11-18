package dev.kb896.aiusage.circle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** All session creation, inference and native disposal belong to this serial worker. */
internal class PaddleOcr(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var engine: PaddleInference? = null
    @Volatile private var closed = false

    private fun submit(model: OcrModel, cb: (OcrResult) -> Unit, job: (PaddleInference,List<OcrAsset>) -> OcrResult) {
        if (closed) return
        worker.execute {
            if (closed) return@execute
            val state = OcrModels.status.value
            val result = try {
                if (engine == null && !state.readyFor(model)) OcrResult(emptyList(), emptyList(), valid = false)
                else {
                    val e = engine ?: PaddleInference(context,OcrModels.root(context)).also { engine = it }
                    val installed = (OcrModels.home(context) + state.installed).distinct().filter { it in state.installed }
                    job(e,installed)
                }
            } catch (e: Exception) {
                android.util.Log.w("CircleSearch","Text recognition failed",e)
                OcrResult(emptyList(), emptyList(), valid = false)
            }
            main.post { if (!closed) cb(result) }
        }
    }
    fun run(bmp: Bitmap,kx: Float,ky: Float, model: OcrModel, cb: (OcrResult) -> Unit) =
        submit(model,cb) { e,p -> e.read(bmp,p,kx,ky,model = model) }

    fun reread(bmp: Bitmap,rect: Rect,kx: Float,ky: Float,f: Float,model: OcrModel,cb: (List<Word>) -> Unit) =
        submit(model,{ cb(it.words) }) { e,p ->
            val r = Rect(rect)
            if (bmp.isRecycled || !r.intersect(0,0,bmp.width,bmp.height) || minOf(r.width(),r.height()) < 8)
                return@submit OcrResult(emptyList(), emptyList(), valid = false)
            val factor = minOf(f,sqrt(6_000_000f/(r.width().toLong()*r.height()))).coerceAtLeast(1f)
            val crop = Bitmap.createBitmap(bmp,r.left,r.top,r.width(),r.height())
            try {
                val big = Bitmap.createScaledBitmap(crop,(r.width()*factor).toInt(),(r.height()*factor).toInt(),true)
                try { e.read(big,p,kx*r.width()/big.width,ky*r.height()/big.height,r.left*kx,r.top*ky,model) }
                finally { if (big !== crop && big !== bmp) big.recycle() }
            } finally { if (crop !== bmp) crop.recycle() }
        }

    fun close() {
        closed = true
        worker.execute { engine?.close(); engine = null }
        worker.shutdown()
    }
}
