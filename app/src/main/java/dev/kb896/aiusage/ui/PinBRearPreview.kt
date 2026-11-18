package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import dev.kb896.aiusage.rear.BlurLevels
import dev.kb896.aiusage.rear.PagSequence
import dev.kb896.aiusage.rear.PinB
import dev.kb896.aiusage.rear.PinBGl
import dev.kb896.aiusage.rear.PinFace
import dev.kb896.aiusage.rear.PinGl
import dev.kb896.aiusage.rear.VeilFrames

/**
 * A DEBUG BUILD'S LOOK AT DRAFT B'S BACK WITHOUT THE BACK (PinActivity `--ez rearpreview true`): the same drawing as
 * the shell's (rear/PinBGl.Rear over pin_show.pag's frames, rear/VeilFrames), at the rear panel's size, slowed by
 * [slow], over black, in the app's own window: for an emulator, which has no rear display and no shell. A made-up
 * note marked "Demo". Nothing is kept.
 */
class PinBRearPreview(context: Context, private val seq: PagSequence.Sequence, private val slow: Float, private val end: () -> Unit) :
    TextureView(context), TextureView.SurfaceTextureListener {
    private var thread: HandlerThread? = null
    @Volatile private var running = false

    init { isOpaque = false; surfaceTextureListener = this }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        if (running) return
        running = true
        val t = HandlerThread("pin-b-rear-preview").also { it.start() }
        thread = t
        Handler(t.looper).post { play(Surface(st), width, height) }
    }

    private fun play(surface: Surface, w: Int, h: Int) {
        val host = PinGl.Host(surface)
        if (!host.make()) { Log.w(TAG, "rear preview: no OpenGL"); Handler(Looper.getMainLooper()).post(end); return }
        val pic = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { PinFace.draw(Canvas(it), w.toFloat(), h.toFloat(), "A made-up note (Demo)", "now", 0, 1, false) }
        val bytes = PinBGl.rgba(pic); pic.recycle()
        val levels = PinBGl.Levels(w, h).also { it.putLevels(BlurLevels.levels(bytes, w, h, w / 976f)) }
        val rear = PinBGl.Rear(w, h, levels)
        val veil = VeilFrames(seq)
        var began = 0L; var shown: Bitmap? = null; var frames = 0
        val chor = Choreographer.getInstance()
        chor.postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(nanos: Long) {
                if (began == 0L) began = nanos
                val t = (nanos - began) / 1e6f / slow
                val b = PinB.back(t)
                val frame = if (b.veil >= 0) veil.at(b.veil) else null
                if (frame != null && frame !== shown) { rear.veil(frame); shown = frame }
                rear.draw(b, frame != null)
                host.swap(); frames++
                if (running && t < PinB.BACK_MS + 1_500f) chor.postFrameCallback(this)
                else {
                    Log.i(TAG, "rear preview: done, $frames pictures")
                    veil.close(); rear.release(); host.release(); surface.release()
                    Handler(Looper.getMainLooper()).post(end)
                }
            }
        })
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { running = false; thread?.quitSafely(); return true }

    private companion object { const val TAG = "AiUsagePin" }
}
