package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * pin_show.pag's frames as pictures, decoded on a thread of their own a few frames ahead of the one asked for, at half
 * size ([SAMPLE]: the light has no edge sharper than its 40 px fall-off, and half the pixels decode in a quarter of
 * the time). A frame is put together as libpag does: a key frame starts from nothing, each patch replaces what is
 * under it, the "nothing new" mark keeps the frame before. Frames passed are let go.
 */
class VeilFrames(private val seq: PagSequence.Sequence) {
    private val n = seq.frames.size
    private val out = java.util.concurrent.atomic.AtomicReferenceArray<Bitmap?>(n)
    /** No light in it at all (an empty or transparent frame): nothing to lay over the back. */
    @Volatile private var blank = BooleanArray(n)
    @Volatile private var wanted = 0
    @Volatile private var stopped = false
    private val lock = Object()
    private val w = maxOf(1, seq.width / SAMPLE)
    private val h = maxOf(1, seq.height / SAMPLE)

    private val thread = Thread({
        val work = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(work)
        val replace = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val opts = BitmapFactory.Options().apply { inSampleSize = SAMPLE }
        var anything = false
        for (i in 0 until n) {
            synchronized(lock) { while (!stopped && i > wanted + AHEAD) lock.wait(50) }
            if (stopped) break
            val f = seq.frames[i]
            runCatching {
                if (!f.unchanged) {
                    if (f.key) { c.drawColor(0, PorterDuff.Mode.CLEAR); anything = false }
                    for (p in f.patches) {
                        val b = BitmapFactory.decodeByteArray(p.webp, 0, p.webp.size, opts) ?: continue
                        c.drawBitmap(b, (p.x / SAMPLE).toFloat(), (p.y / SAMPLE).toFloat(), replace)
                        b.recycle()
                        anything = true
                    }
                }
                if (anything) out.set(i, work.copy(Bitmap.Config.ARGB_8888, false)) else blank[i] = true
            }.onFailure { blank[i] = true }
        }
        work.recycle()
    }, "pin-veil").apply { isDaemon = true; start() }

    /**
     * Frame [i] (or the nearest one before it that is ready, so the light never stops for a late decode); null: no
     * light to lay over (frames before the first light, or nothing decoded yet). Frames before [i] are let go.
     */
    fun at(i: Int): Bitmap? {
        if (i !in 0 until n) return null
        if (i != wanted) synchronized(lock) { wanted = i; lock.notifyAll() }
        for (k in 0 until i - 1) out.getAndSet(k, null)?.recycle()
        var k = i
        while (k >= 0) {
            if (blank[k] && k == i) return null
            out.get(k)?.let { return it }
            k--
        }
        return null
    }

    fun close() {
        stopped = true
        synchronized(lock) { lock.notifyAll() }
        runCatching { thread.join(300) }
        for (k in 0 until n) out.getAndSet(k, null)?.recycle()
    }

    companion object {
        const val SAMPLE = 2
        /** How many frames are decoded ahead of the one shown. */
        const val AHEAD = 6
    }
}
