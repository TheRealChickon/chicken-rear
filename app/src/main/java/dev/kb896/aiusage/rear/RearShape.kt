package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

object RearShape {
    /** The radius a panel-sized layer at ([x], [y]) gets: the display's own wherever any of its edges is in view, none in its place. */
    fun cornerAt(rear: RearGeometry, x: Float, y: Float): Float = if (x != 0f || y != 0f) rear.cornerPx.toFloat() else 0f

    /**
     * [layer] (panel-sized) at ([x], [y]) in [t], with [cornerAt]'s radius. THE RADIUS ACTS ON THE LAYER'S CROP: the
     * picture is in a child of the layer (its buffer queue), so the crop is set with it.
     */
    fun place(t: SurfaceControl.Transaction, layer: SurfaceControl, rear: RearGeometry, x: Float, y: Float) {
        t.setCrop(layer, android.graphics.Rect(0, 0, rear.widthPx, rear.heightPx))
        ShellOverlay.roundCorners(t, layer, cornerAt(rear, x, y))
        t.setPosition(layer, x, y)
    }

    /**
     * [layer] (panel-sized) SCALED by [scale] about its top-left corner and put with that corner at ([x], [y]), its
     * corners rounded by [radius] in the layer's own space (SurfaceFlinger scales the radius with the layer: on the
     * screen it is [radius] times [scale]): the face as a card in the picker (ShellScene.Shape). The crop as in [place].
     * Scale 1 at (0, 0) with no radius is the whole back, in its place.
     */
    fun place(t: SurfaceControl.Transaction, layer: SurfaceControl, rear: RearGeometry, x: Float, y: Float, scale: Float, radius: Float) {
        t.setCrop(layer, android.graphics.Rect(0, 0, rear.widthPx, rear.heightPx))
        ShellOverlay.roundCorners(t, layer, radius)
        t.setScale(layer, scale, scale)
        t.setPosition(layer, x, y)
    }

    /** The display's shape as the layer over everything has it: clear, and black outside the rounded rectangle [w] x [h] of radius [r]. */
    fun drawMask(c: Canvas, w: Float, h: Float, r: Float) {
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        PageCorners.cut(c, w, h, r)
    }

    /** One upright strip of the mask: its left edge [x] on the back, [width] wide, the back's whole height. */
    data class Column(val x: Int, val width: Int)

    /**
     * Where the mask is not clear on a back [w] wide with corners of radius [r]: within [r] of its left and right edges
     * (each corner's arc lies in its own r x r square; between the corners the rounded rectangle's edge is the back's
     * own edge, so nothing of the mask is there). Two columns, left and right, [r] + [COLUMN_SPARE] wide; one the whole
     * width where those two would meet; none with no radius (the mask is then clear all over). Pure.
     */
    fun columns(w: Int, r: Int): List<Column> {
        if (w <= 0 || r <= 0) return emptyList()
        val cw = r + COLUMN_SPARE
        if (2 * cw >= w) return listOf(Column(0, w))
        return listOf(Column(0, cw), Column(w - cw, cw))
    }

    // ---- the layer over everything ----

    internal class Keeper(
        /** The back's layer stack, read now (dumpsys); null when it cannot be read. */
        private val backStack: () -> Int?,
        /** The back as it is known now; null when it cannot be had. */
        private val geometry: () -> RearGeometry?,
        /**
         * Lays the mask on that stack at that size IN PLACE OF the one made before, which goes only once the new one is
         * shown (no moment without the display's shape); false when it could not (the one before then left as it was).
         */
        private val make: (Int, RearGeometry) -> Boolean,
        /** Takes the mask made away. */
        private val drop: () -> Unit,
    ) {
        /** What the mask is made for now; null while there is none. */
        var madeFor: Pair<Int, RearGeometry>? = null; private set
        private var madeAt = 0L
        private var fails = 0
        /** The back's stack as last read. */
        private var stack: Int? = null
        /** Stacks a layer of ours went on that are not the back's (the front's): not asked about again until then. */
        private val notTheBack = HashMap<Int, Long>()
        /** Stacks layers of ours went on since the last look, not yet asked about. */
        private val seen = LinkedHashSet<Int>()

        /** A layer of ours was made on [layerStack]: whether that is news (a look is due now). */
        fun layerOn(layerStack: Int, now: Long): Boolean {
            if (layerStack == madeFor?.first) return false
            notTheBack[layerStack]?.let { if (now < it) return false }
            if (layerStack == stack && madeFor != null) return false
            seen += layerStack
            return true
        }

        /** One look at [now]: the mask made, moved or made anew as needed. Gives how long until the next look. */
        fun step(now: Long): Long {
            if (stack == null || madeFor == null || seen.isNotEmpty()) {
                val read = backStack() ?: return retry()
                stack = read
                for (s in seen) if (s != read) notTheBack[s] = now + NOT_THE_BACK_MS
                seen.clear()
            }
            val want = stack!! to (geometry() ?: return retry())
            if (madeFor == want) { fails = 0; return calm(now) }
            if (!make(want.first, want.second)) {
                // The one before is for another stack or size: not left up (none until the next try, as before a first).
                if (madeFor != null) { drop(); madeFor = null }
                return retry()
            }
            madeFor = want; madeAt = now; fails = 0
            return calm(now)
        }

        private fun retry(): Long = minOf(RETRY_FIRST_MS shl minOf(fails++, 6), RETRY_MAX_MS)
        private fun calm(now: Long): Long = if (now - madeAt < LOOK_SOON_FOR_MS) LOOK_SOON_MS else LOOK_LATER_MS
    }

    @Volatile private var keeper: Keeper? = null
    /** The keeper's own thread: the layers it makes (the mask's) are not news to it ([handOver]). */
    @Volatile private var keeperThread: Thread? = null
    /** The mask's layers ([columns]); empty while there is none. */
    @Volatile private var masks: List<SurfaceControl> = emptyList()
    /** The stack the mask is on, for [made]'s quick no (read on any thread). */
    @Volatile private var maskStack: Int? = null
    private val wake = Object()
    private var poked = false
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Int>()

    /**
     * The shell says how to find the back's layer stack ([layerStack], read afresh each time it is called: the pages'
     * own way) and what the back is ([geometry]). Called once the shell service is whole (RearUserService, last in its
     * construction); starts the one thread that keeps the mask. Not in the guard's process: its one picture is black
     * all over.
     */
    fun shapeTheBack(layerStack: () -> Int, geometry: () -> RearGeometry) {
        synchronized(this) {
            if (keeper != null) return
            keeper = Keeper(
                backStack = { runCatching { layerStack() }.onFailure { Log.w(TAG, "corners: the back's stack: ${it.message}") }.getOrNull() },
                geometry = { runCatching { geometry() }.onFailure { Log.w(TAG, "corners: the back's size: ${it.message}") }.getOrNull() },
                make = ::lay,
                drop = { masks.forEach { ShellLayer.remove(it) }; masks = emptyList(); maskStack = null },
            )
        }
        Thread({ keep() }, "rear-corners").apply { isDaemon = true; keeperThread = this; start() }
    }

    /**
     * A layer was made on [layerStack] (ShellLayer.create). The caller (the cover that must be up at once, a render
     * thread) never waits: a stack that is not the mask's is handed to the keeper, which asks about it on its thread.
     */
    internal fun made(layerStack: Int) {
        if (!handOver(layerStack, keeper != null, maskStack, Thread.currentThread() === keeperThread)) return
        pending += layerStack
        poke()
    }

    /**
     * Whether a layer made on [layerStack] is handed to the keeper: only once it runs ([keeping]), not on the mask's own
     * stack, and NEVER ONE THE KEEPER MADE ITSELF ([byKeeper], the mask's layers as it lays them): those poked it, so a
     * try that failed after making a layer was tried again at once, not after its wait (1 s doubling to a minute), and a
     * failure that lasted was tried back to back, each try a stack read and layers made and taken away. Pure.
     */
    internal fun handOver(layerStack: Int, keeping: Boolean, maskStack: Int?, byKeeper: Boolean): Boolean =
        keeping && !byKeeper && layerStack != maskStack

    private fun poke() = synchronized(wake) { poked = true; wake.notifyAll() }

    private fun keep() {
        val k = keeper ?: return
        while (true) {
            val wait = try {
                val now = android.os.SystemClock.uptimeMillis()
                while (true) { val s = pending.poll() ?: break; k.layerOn(s, now) }
                k.step(now)
            } catch (e: Throwable) {
                Log.w(TAG, "corners: ${e.message}")
                RETRY_MAX_MS
            }
            try {
                synchronized(wake) { if (!poked) wake.wait(wait); poked = false }
            } catch (_: InterruptedException) { return }
        }
    }

    /**
     * The mask itself, on [stack] at [rear]'s size: black outside its corners, clear inside, over everything of ours. One
     * layer per column ([columns]), each with a buffer THE BACK'S SIZE and the whole mask drawn into it as it always was,
     * CROPPED to its column: the display reads and blends only the crop. Not a narrow buffer with the mask moved into
     * it: moved by whole pixels the arcs still come out a few levels different on one to three pixels at the right
     * (the rasterizer's rounding depends on where on the canvas they are; measured with the host's software rasterizer,
     * Robolectric's Skia, the phone's GPU path not tried), and the back is to look exactly as it did.
     * Made hidden, each drawn once, then cropped and shown together in one transaction, in which the mask made before
     * (if any) is hidden: there is no frame without the display's shape. The one before is taken away after that.
     */
    private fun lay(stack: Int, rear: RearGeometry): Boolean = try {
        val cols = columns(rear.widthPx, rear.cornerPx)
        val old = masks
        val made = ArrayList<SurfaceControl>(cols.size)
        try {
            for (i in cols.indices) {
                val sc = ShellLayer.create(
                    if (cols.size == 1) "rear-corners" else if (i == 0) "rear-corners-left" else "rear-corners-right",
                    stack, ShellLayer.Z_CORNERS, rear.widthPx, rear.heightPx,
                    // Trusted: no touch is stopped by it. Not opaque: it is clear inside the corners.
                    trusted = true, visible = false, format = PixelFormat.RGBA_8888, opaque = false,
                )
                made += sc
                val s = Surface(sc)
                try {
                    val c = ShellLayer.lockPanelCanvas(s)
                    try { drawMask(c, rear.widthPx.toFloat(), rear.heightPx.toFloat(), rear.cornerPx.toFloat()) } finally { s.unlockCanvasAndPost(c) }
                } finally { s.release() }
            }
            if (made.isNotEmpty() || old.isNotEmpty()) SurfaceControl.Transaction().use { t ->
                for ((i, sc) in made.withIndex()) {
                    val col = cols[i]
                    t.setCrop(sc, android.graphics.Rect(col.x, 0, col.x + col.width, rear.heightPx)).setVisibility(sc, true)
                }
                for (sc in old) t.setVisibility(sc, false)
                t.apply()
            }
        } catch (e: Throwable) {
            made.forEach { ShellLayer.remove(it) }
            throw e
        }
        masks = made; maskStack = stack
        old.forEach { ShellLayer.remove(it) }
        if (made.isEmpty()) Log.i(TAG, "corners: no radius: nothing to lay (stack $stack, ${rear.widthPx}x${rear.heightPx})")
        else Log.i(TAG, "corners: the display's shape is over everything of ours (stack $stack, radius ${rear.cornerPx} px, ${rear.widthPx}x${rear.heightPx}, " +
            "shown in ${cols.size} column${if (cols.size == 1) "" else "s"} ${cols.joinToString { "${it.width}" }} px wide)")
        true
    } catch (e: Throwable) {
        Log.w(TAG, "corners: the display's shape could not be laid over the back (tried again): ${e.message}")
        false
    }

    /**
     * Pixels each column has past the radius: none of the mask is there (the arc stays inside its r x r square), kept
     * so a renderer's edge that spills a pixel past it is still in the column. Clear pixels, so nothing else changes.
     */
    internal const val COLUMN_SPARE = 2

    /** After a failed try: 1 s, doubling, at most a minute. */
    internal const val RETRY_FIRST_MS = 1_000L
    internal const val RETRY_MAX_MS = 60_000L
    /** Looked at again this often for [LOOK_SOON_FOR_MS] after it was made (a stand-in size replaced), then every [LOOK_LATER_MS]. */
    internal const val LOOK_SOON_MS = 3_000L
    internal const val LOOK_SOON_FOR_MS = 180_000L
    internal const val LOOK_LATER_MS = 60_000L
    /** A stack found not to be the back's is not asked about again for this long. */
    internal const val NOT_THE_BACK_MS = 600_000L
    private const val TAG = "AiUsageRear"
}
