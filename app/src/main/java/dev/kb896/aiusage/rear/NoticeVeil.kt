package dev.kb896.aiusage.rear

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.pow

/**
 * A live scene samples the page geometry and app colour on its own render thread. Both
 * renderers use the same uptime clock throughout opening, dragging and folding.
 *
 * NOTHING IS PASSED PER PICTURE. The island's thread (ShellNotices) says once when the island began, how wide its pill is
 * and in what colour ([begin]), when its time moves ([retime]: a finger held it aside) and when it is taken away ([end]); the
 * scene's thread [read]s it at each of its own pictures and works out the page's outline and the tint's strength from the
 * island's own timeline (MessageIsland.at) at that picture's time. So the colour moves with the outline the island's layer
 * shows, at the face's own rate, and with no lag behind it. Only a finger's place across ([dx]) is written as it moves.
 *
 * One writer (the island's thread) and one reader (the scene's thread); [read] allocates nothing. Pure: no Android.
 */
class NoticeVeil {
    /** One island's tint, fixed while it lasts: a new one, a new time or its end make another. */
    private class Spec(
        @Volatile var startMs: Long, val pillW: Float, val hold: Float,
        /** The face takes the colour ("Island · Tint" over a face the scene draws); false: an island is up, nothing to draw. */
        val tints: Boolean,
        /** The app's colour for a picture in sRGB values (the moon's), for one in linear light (the dust's motes), and for the beam's haze. */
        val srgb: FloatArray, val lin: FloatArray, val haze: FloatArray,
        /** Taken away at (uptime ms; Long.MAX_VALUE: not yet), the tint's strength then and the outline it had. */
        val endMs: Long = Long.MAX_VALUE, val endLevel: Float = 0f, val endShape: FloatArray? = null,
    )

    private val spec = AtomicReference<Spec?>(null)
    /** Where a finger holds the page across (page units): the outline moves with it. */
    @Volatile var dx = 0f

    /** What the scene draws with at one picture: filled in place by [read]. */
    class Frame {
        /** How much of the app's colour (0: none). */
        var amount = 0f
        val srgb = FloatArray(3)
        val lin = FloatArray(3)
        val haze = FloatArray(3)
        /** The page's outline (page units, 976 x 596): centre, size, corner, the soft edge's width. */
        var cx = 0f; var cy = 0f; var w = 0f; var h = 0f; var r = 0f; var feather = 1f
        /** Its outline or its strength changes from picture to picture (the island opening, folding, or the tint going). */
        var moving = false
        internal val island = MessageIsland.Frame()

        /** The dust's inputs take this tint (DustField.Inputs.tint and the rest; the scene's loop, the PC's renders). */
        fun into(inp: DustField.Inputs) {
            inp.tint = amount
            lin.copyInto(inp.tintCol); haze.copyInto(inp.tintHaze)
            inp.tintBox[0] = cx; inp.tintBox[1] = cy; inp.tintBox[2] = w / 2f; inp.tintBox[3] = h / 2f
            inp.tintShape[0] = r; inp.tintShape[1] = feather
        }
    }

    /**
     * An island of [colour] (the app's, ARGB) began at [startMs] (uptime), its pill [pillW] wide, holding [hold] ms; [tints]:
     * the face takes its colour (false: only that one is up, for the face's picture count, [VeilGaps]).
     */
    fun begin(colour: Int, startMs: Long, pillW: Float, hold: Long = MessageIsland.HOLD_MS, tints: Boolean = true) {
        val s = FloatArray(3); val l = FloatArray(3); val z = FloatArray(3)
        colours(if ((colour ushr 24) == 0) 0xFF9A9A9A.toInt() else colour, s, l, z)
        dx = 0f
        spec.set(Spec(startMs, pillW, hold.toFloat(), tints, s, l, z))
    }

    /** A drag can extend the hold at every refresh without replacing the message's colour or outline. */
    fun retime(startMs: Long) {
        val current = spec.get() ?: return
        if (current.endMs == Long.MAX_VALUE && current.startMs != startMs) current.startMs = startMs
    }

    /** Taken away at [nowMs]: the tint goes from where it is, in [FADE_MS], its outline kept where it was. */
    fun end(nowMs: Long) {
        val o = spec.get() ?: return
        if (o.endMs != Long.MAX_VALUE) return
        val f = Frame()
        val level = if (o.tints) levelAt(o, nowMs, f) else 0f
        spec.compareAndSet(o, if (level <= 0f) null else Spec(o.startMs, o.pillW, o.hold, true, o.srgb, o.lin, o.haze, nowMs, level, floatArrayOf(f.cx, f.cy, f.w, f.h, f.r, f.feather)))
    }

    /** Gone at once (the back dark: nothing is drawn anyway). */
    fun clear() { spec.set(null); dx = 0f }

    /** Whether an island is up, or its tint still going: the scene asks before anything else (one volatile read). */
    val up: Boolean get() = spec.get() != null

    /**
     * The tint at [nowMs] (uptime) into [out]; false when there is none to draw (none up, or faded away), and the scene draws
     * its face as it always does. Allocates nothing.
     */
    fun read(nowMs: Long, out: Frame): Boolean {
        val s = spec.get()
        if (s == null || !s.tints) { out.amount = 0f; out.moving = false; return false }
        val level: Float
        if (nowMs >= s.endMs) {
            val k = 1f - MessageIsland.smooth(0f, FADE_MS.toFloat(), (nowMs - s.endMs).toFloat())
            level = s.endLevel * k
            // Faded away: nothing is up any more (unless a new one began meanwhile).
            if (level <= 0f) spec.compareAndSet(s, null)
            val e = s.endShape!!
            out.cx = e[0]; out.cy = e[1]; out.w = e[2]; out.h = e[3]; out.r = e[4]; out.feather = e[5]
            out.moving = level > 0f
        } else {
            level = levelAt(s, nowMs, out)
            val t = (nowMs - s.startMs).toFloat()
            out.moving = t < MessageIsland.IN_MS || t > MessageIsland.IN_MS + s.hold
        }
        out.cx += dx
        out.amount = level
        if (level <= 0f) { out.moving = false; return false }
        s.srgb.copyInto(out.srgb); s.lin.copyInto(out.lin); s.haze.copyInto(out.haze)
        return true
    }

    /** The tint's strength for [s] at [nowMs], and the outline into [out]. */
    private fun levelAt(s: Spec, nowMs: Long, out: Frame): Float {
        val f = MessageIsland.at((nowMs - s.startMs).toFloat(), s.pillW, out.island, s.hold)
        if (!f.shown) return 0f
        out.cx = f.cx; out.cy = f.cy; out.w = f.w; out.h = f.h; out.r = f.r
        out.feather = if (f.feather > 0f) f.feather else 1f
        return AMOUNT * f.tint
    }

    companion object {
        /** How much of the app's colour the face takes at the most: nearly all of its light, its brightest cores keep a little white. */
        const val AMOUNT = 0.85f
        /** A tinted mote's luminance for a luminance of 1: the motes' own light (DustField.NEAR_COL's is 0.955). */
        const val MOTE_LUMA = 0.95f
        /** Taken away, the tint goes in this long. */
        const val FADE_MS = 180L

        private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

        private fun linear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

        /**
         * The app's [colour] three ways: [srgb] as much light as the picture had there (its luminance 0.95, no channel over
         * 1.25: the moon multiplies it by its own luminance), [lin] the same in linear light ([MOTE_LUMA], none over 2: a mote's
         * colour), [haze] at its brightest channel 1 (the beam's haze must stay as faint as it is: DustShaderToeTest).
         */
        fun colours(colour: Int, srgb: FloatArray, lin: FloatArray, haze: FloatArray) {
            val c = floatArrayOf(((colour shr 16) and 0xFF) / 255f, ((colour shr 8) and 0xFF) / 255f, (colour and 0xFF) / 255f)
            val ls = c[0] * LUMA[0] + c[1] * LUMA[1] + c[2] * LUMA[2]
            for (k in 0..2) srgb[k] = c[k] / max(ls, 0.05f) * 0.95f
            val ms = max(srgb[0], max(srgb[1], srgb[2]))
            if (ms > 1.25f) for (k in 0..2) srgb[k] *= 1.25f / ms
            val l = FloatArray(3) { linear(c[it]) }
            val ll = l[0] * LUMA[0] + l[1] * LUMA[1] + l[2] * LUMA[2]
            for (k in 0..2) lin[k] = l[k] / max(ll, 0.02f) * MOTE_LUMA
            val ml = max(lin[0], max(lin[1], lin[2]))
            if (ml > 2f) for (k in 0..2) lin[k] *= 2f / ml
            val mh = max(l[0], max(l[1], l[2]))
            for (k in 0..2) haze[k] = if (mh > 0f) l[k] / mh else 1f
        }
    }
}

/**
 * THE FACE'S PICTURES WHILE A MESSAGE ISLAND IS UP (SceneEngine counts them; "no lag": the face must keep its pace as the
 * page opens and closes over it): how many, the longest gap between two and how many gaps were longer than [LATE_MS],
 * said in one line once the island has gone. Nothing is made but that line. Pure.
 */
class VeilGaps {
    private var up = false
    private var firstNs = 0L
    private var lastNs = 0L
    private var pictures = 0
    private var worstNs = 0L
    private var late = 0

    /** One picture of the face at [nowNs], [islandUp] or not: the line to log once an island has gone, else null. */
    fun picture(islandUp: Boolean, nowNs: Long): String? {
        if (islandUp) {
            if (!up) { up = true; firstNs = nowNs; lastNs = nowNs; pictures = 1; worstNs = 0L; late = 0; return null }
            val gap = nowNs - lastNs
            lastNs = nowNs; pictures++
            if (gap > worstNs) worstNs = gap
            if (gap > LATE_MS * 1_000_000L) late++
            return null
        }
        if (!up) return null
        up = false
        val span = (lastNs - firstNs) / 1e6
        val l = java.util.Locale.ROOT
        return "island: the face drew $pictures pictures in ${"%.0f".format(l, span)} ms while it was up " +
            "(${"%.1f".format(l, if (span > 0) pictures * 1000.0 / span else 0.0)} a second), the longest gap ${"%.1f".format(l, worstNs / 1e6)} ms, " +
            "$late gaps over $LATE_MS ms"
    }

    companion object {
        /** A gap longer than this is a stutter at any of the face's paces (120 or 60 pictures a second; a clip's 30). */
        const val LATE_MS = 40L
    }
}
