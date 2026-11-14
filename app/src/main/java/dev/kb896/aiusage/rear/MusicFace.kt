package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * What is playing, as the whole back: Xiaomi's own rear music page, copied.
 *
 * XIAOMI'S PAGE, READ FROM ITS OWN TEMPLATE (com.xiaomi.subscreencenter, assets/smart_assistant/music,
 * a MAML widget: manifest.xml and four Lottie buttons). On this 976x596 panel its measures come to:
 * - the ground: a very dark tone of the cover's colour (tone 4 of its palette), and three soft
 *   glows low on the page, most of each under the bottom edge: tone 20 at 0.7, tone 60 at 0.5 and
 *   the neighbouring hue's tone 70 at 0.5. While it plays they wander, a new place every second;
 *   paused they stand still.
 * - the cover, 193 px square with 36 px corners, its corner at (362, 65), right of the lenses
 *   (a track without one: the player app's own icon there; Xiaomi's draws a note; nothing is drawn by us in its place);
 * - the title under it, white, demibold, 58 px, one line; the artist under that, white at half
 *   strength, 41 px, one line; both 505 px wide at most, cut with an ellipsis;
 * - three buttons along the bottom, each 140 px, their centres at x 403, 626, 859 and y 534:
 *   back, pause or play, on. White glyphs, nothing around them.
 * No clock, no battery, no app name, no progress bar: there is none on Xiaomi's.
 *
 * APPROXIMATE: Xiaomi takes the tones from Google's HCT colour space (libMiMainColor, the
 * material_color_utilities palette). Here the same tones are taken in CIE LCh, which shares HCT's
 * tone exactly and its hue and chroma closely. And the glows are drawn with the fall-off of
 * Xiaomi's own glow picture (assets/circle.png), not through its blur.
 *
 * [button], [palette] and [Glow] are plain arithmetic, so a test holds them. In rear pixels of a
 * 976x596 panel; drawn to scale on any other.
 */
object MusicFace {
    // Xiaomi's measures: sr = 976 / 334, scaleY = 440 / 150, left margin 274.19 (see the class comment).
    const val ART_X = 361.9f
    const val ART_Y = 64.5f
    const val ART = 192.9f
    const val ART_R = 36.2f
    const val TEXT_W = 505.5f
    const val TITLE_CY = 347.6f
    const val TITLE_SIZE = 58.4f
    const val ARTIST_CY = 425.3f
    const val ARTIST_SIZE = 40.9f
    const val BTN = 140.3f
    const val BTN_Y = 463.5f
    private val BTN_X = floatArrayOf(332.6f, 556.2f, 788.5f)

    enum class Button(val command: String) { PREVIOUS("previous"), PLAY_PAUSE("playpause"), NEXT("next") }

    /** The button's centre x. */
    fun centre(b: Button): Float = BTN_X[b.ordinal] + BTN / 2f

    /** Which button a tap at [x], [y] (rear pixels of a 976x596 panel) is on; null for none. */
    fun button(x: Float, y: Float, layout: MusicLayout = MusicLayout.MUSIC): Button? {
        // The buttons are where the page's layout has them (higher for a video: MusicLayout), and so is a finger's room.
        if (y < layout.btnY - 6f || y > layout.btnY + BTN + 14f) return null
        return Button.entries.firstOrNull { kotlin.math.abs(x - centre(it)) <= BTN / 2f + 14f }
    }

    // ---- the colours ----

    /** The page's colours for one cover: the ground and the three glows. */
    data class Palette(val ground: Int, val glow1: Int, val glow2: Int, val glow3: Int)

    /**
     * The palette of [seed], the cover's main colour (0 for none, or a grey one: then quiet greys).
     * As Xiaomi's: the colour's own hue at a chroma of at least 48, its tones 4, 20 and 60, and
     * for the third glow the hue 60 degrees on at chroma 24, tone 70.
     */
    fun palette(seed: Int): Palette {
        if (seed == 0) return Palette(tone(0f, 0f, 4f), tone(0f, 0f, 20f), tone(0f, 0f, 60f), tone(0f, 0f, 70f))
        val lch = lch(seed)
        val chroma = maxOf(48f, lch[1])
        return Palette(tone(lch[2], chroma, 4f), tone(lch[2], chroma, 20f), tone(lch[2], chroma, 60f), tone(lch[2] + 60f, 24f, 70f))
    }

    /** The cover's main colour: the mean of what has a colour in it, by how strong; 0 for a grey or missing cover. */
    fun seed(cover: Bitmap?): Int {
        cover ?: return 0
        return runCatching {
            val small = Bitmap.createScaledBitmap(cover, 32, 32, true)
            val px = IntArray(32 * 32); small.getPixels(px, 0, 32, 0, 0, 32, 32)
            dev.kb896.aiusage.service.BackNotices.dominant(px)
        }.getOrDefault(0)
    }

    /** [argb] as lightness (0..100), chroma and hue (degrees) in CIE LCh. */
    internal fun lch(argb: Int): FloatArray {
        fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
        val r = lin(Color.red(argb)); val g = lin(Color.green(argb)); val b = lin(Color.blue(argb))
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
        val l = 116.0 * f(y) - 16.0; val a = 500.0 * (f(x) - f(y)); val bb = 200.0 * (f(y) - f(z))
        val h = (Math.toDegrees(atan2(bb, a)) + 360.0) % 360.0
        return floatArrayOf(l.toFloat(), hypot(a, bb).toFloat(), h.toFloat())
    }

    /** The colour of [hue] at lightness [l], with as much of [chroma] as the screen can show there. */
    internal fun tone(hue: Float, chroma: Float, l: Float): Int {
        var lo = 0f; var hi = chroma
        var best = rgb(l, 0f, hue)!!
        rgb(l, chroma, hue)?.let { return it }
        repeat(12) {
            val mid = (lo + hi) / 2f
            val c = rgb(l, mid, hue)
            if (c != null) { best = c; lo = mid } else hi = mid
        }
        return best
    }

    /** LCh to a colour; null when the screen cannot show it. */
    private fun rgb(l: Float, c: Float, hue: Float): Int? {
        val h = Math.toRadians(hue.toDouble())
        val a = c * cos(h); val b = c * sin(h)
        val fy = (l + 16.0) / 116.0; val fx = fy + a / 500.0; val fz = fy - b / 200.0
        fun inv(t: Double) = if (t * t * t > 216.0 / 24389.0) t * t * t else (116.0 * t - 16.0) * 27.0 / 24389.0
        val x = inv(fx) * 0.95047; val y = inv(fy); val z = inv(fz) * 1.08883
        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        if (minOf(r, g, bl) < -0.0008 || maxOf(r, g, bl) > 1.0008) return null
        fun out(v: Double): Int { val u = v.coerceIn(0.0, 1.0); return Math.round(255.0 * (if (u <= 0.0031308) 12.92 * u else 1.055 * u.pow(1.0 / 2.4) - 0.055)).toInt().coerceIn(0, 255) }
        return Color.rgb(out(r), out(g), out(bl))
    }

    // ---- the glows: up and down, never across ----

    /**
     * Where the three glows are: their centres as fractions of the page's width and height.
     *
     * NOT XIAOMI'S CHAIN ANY MORE. Xiaomi's template sends each glow to a new random place once a second, in a
     * straight line, up to half the page's width away, and this copied it. That moved too much and cut and reversed.
     * So:
     *  - ACROSS, each glow keeps its place for good: the template's own rest places, 0.3 / 0.55 / 0.8 of the width.
     *  - UP AND DOWN, they breathe: two slow waves laid over each other (6.5 s and 10.3 s, so it does not repeat
     *    by the clock), each glow a little out of step with its neighbours. An eighth of the page's height.
     *  - WITH THE SOUND, where the app may hear it ([hear]: "Follows the music"), they rise with it, up to a
     *    fifth of the page's height more, HEAVILY SMOOTHED: half a second to rise, a second and a half to sink,
     *    so a beat is a swell and never a jump.
     *  - PAUSED, they sink to rest and the breath slows to a stop; playing again, it goes on from where it was.
     *
     * ONE CLOCK, ASKED EVERY FRAME. Nothing here starts over: not at a new track, not at a new cover, not when
     * the page was away and comes back. Time that passed unseen is not made up for (a step is never longer than
     * [MAX_STEP_MS]), so there is no leap after a pause in drawing either. A test holds that no glow moves more
     * in a frame than its fastest honest step, over a minute and over a change of track.
     */
    class Glow {
        private val at = floatArrayOf(X[0], REST_Y, X[1], REST_Y, X[2], REST_Y)
        private var last = 0L
        private var phase = 0.0
        private var alive = 0f
        private var level = 0f
        private var heard = 0f
        private var heardAt = Long.MIN_VALUE / 2

        /** How strongly the glows shine, as a part of their own strength: a little more with the sound. */
        var strength = STRENGTH_QUIET
            private set

        /** The sound's level as the app hears it now, 0 (silence) to 1 (loud). Not told for [STALE_MS]: taken as not heard. */
        @Synchronized fun hear(level: Float, now: Long) { heard = level.coerceIn(0f, 1f); heardAt = now }

        /** The centres at [now] (x, y, x, y, x, y); the same array each time. Asked every frame while the page is up. */
        @Synchronized fun at(now: Long, playing: Boolean): FloatArray {
            val dt = if (last == 0L) 0f else (now - last).coerceIn(0L, MAX_STEP_MS) / 1000f
            last = now
            alive += ((if (playing) 1f else 0f) - alive) * (1f - kotlin.math.exp(-dt / ALIVE_S))
            phase += (dt * alive).toDouble()
            val target = if (playing && now - heardAt < STALE_MS) heard else 0f
            level += (target - level) * (1f - kotlin.math.exp(-dt / (if (target > level) ATTACK_S else RELEASE_S)))
            for (i in 0..2) {
                val wave = 0.62 * kotlin.math.sin(2.0 * Math.PI * phase / WAVE_1_S + STEP[i]) + 0.38 * kotlin.math.sin(2.0 * Math.PI * phase / WAVE_2_S + 1.7 * STEP[i])
                val breath = (0.5 + 0.5 * wave).toFloat()
                at[2 * i] = X[i]
                at[2 * i + 1] = REST_Y - alive * (BREATH * breath + LIFT * level)
            }
            strength = STRENGTH_QUIET + (1f - STRENGTH_QUIET) * level
            return at
        }

        companion object {
            /** The template's rest places across (hyper material on): they are kept. */
            val X = floatArrayOf(0.3f, 0.55f, 0.8f)
            /** At rest: the centre a fifth of the page's height under its foot (Xiaomi's lowest: 1.2). */
            const val REST_Y = 1.2f
            /** The breath's rise, and the sound's on top of it, as parts of the page's height (Xiaomi's highest is 0.8). */
            const val BREATH = 0.12f
            const val LIFT = 0.20f
            const val WAVE_1_S = 6.5
            const val WAVE_2_S = 10.3
            private val STEP = doubleArrayOf(0.0, 0.9, 1.9)
            /** To and from rest. */
            const val ALIVE_S = 0.7f
            /** The sound: slow to rise, slower to sink. */
            const val ATTACK_S = 0.45f
            const val RELEASE_S = 1.4f
            const val STALE_MS = 600L
            const val STRENGTH_QUIET = 0.9f
            /** Time that passed with nobody asking is not caught up with: no leap after the page was away. */
            const val MAX_STEP_MS = 50L
            /** The fastest a glow can honestly move, in page heights a second: the two waves at their steepest, the sound's rise at its steepest. */
            val TOP_SPEED: Float = (BREATH * 0.5 * (0.62 * 2.0 * Math.PI / WAVE_1_S + 0.38 * 2.0 * Math.PI / WAVE_2_S)).toFloat() + LIFT / ATTACK_S + (BREATH + LIFT) / ALIVE_S
        }
    }

    /**
     * The page's colours on their way from one cover's to the next: eased, each frame, so a new track is not a
     * cut from one ground and three glows to others (it was). The same clock rule as [Glow]: asked every frame.
     */
    class Fade {
        private var now: FloatArray? = null
        private var last = 0L

        @Synchronized fun at(to: Palette, atMs: Long): Palette {
            val goal = floats(to)
            val cur = now ?: goal.copyOf().also { now = it }
            val dt = if (last == 0L) 0f else (atMs - last).coerceIn(0L, Glow.MAX_STEP_MS) / 1000f
            last = atMs
            val k = 1f - kotlin.math.exp(-dt / FADE_S)
            for (i in cur.indices) cur[i] += (goal[i] - cur[i]) * k
            fun c(o: Int) = Color.rgb(Math.round(cur[o]), Math.round(cur[o + 1]), Math.round(cur[o + 2]))
            return Palette(c(0), c(3), c(6), c(9))
        }

        private fun floats(p: Palette) = FloatArray(12).also { f ->
            for ((n, c) in listOf(p.ground, p.glow1, p.glow2, p.glow3).withIndex()) { f[3 * n] = Color.red(c).toFloat(); f[3 * n + 1] = Color.green(c).toFloat(); f[3 * n + 2] = Color.blue(c).toFloat() }
        }

        companion object { const val FADE_S = 0.45f }
    }

    // ---- the picture ----

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val matrix = Matrix()
    private val glowShader = arrayOfNulls<RadialGradient>(3)
    private val glowRgb = IntArray(3)
    private val glowRadius = FloatArray(3)
    private var typed = false

    // The fall-off of Xiaomi's glow picture, centre to edge.
    private val GLOW_STOPS = floatArrayOf(0f, 0.24f, 0.34f, 0.43f, 0.53f, 0.62f, 0.72f, 0.81f, 0.91f, 1f)
    private val GLOW_ALPHA = floatArrayOf(1f, 0.98f, 0.90f, 0.765f, 0.56f, 0.333f, 0.16f, 0.051f, 0.004f, 0f)
    private val GLOW_STRENGTH = floatArrayOf(0.7f, 0.5f, 0.5f)

    /**
     * Draws the page over the whole canvas (opaque). [glow]: the three centres ([Glow.at]).
     * [cover] is the track's own; without one [playerIcon], the player app's icon, stands in its place.
     * [sinceFlip]: ms since it went from playing to paused or back (the middle button's change);
     * [pressed] and [sincePress]: a back or on button just pressed, for its nudge.
     */
    @Synchronized
    fun draw(
        c: Canvas, w: Float, h: Float, title: String, artist: String, playing: Boolean, cover: Bitmap?, colors: Palette,
        glow: FloatArray, sinceFlip: Long = Long.MAX_VALUE, pressed: Button? = null, sincePress: Long = Long.MAX_VALUE,
        playerIcon: Bitmap? = null,
        /** How strongly the glows shine, of their own strength ([Glow.strength]). */
        glowStrength: Float = 1f,
        /** Where the picture, the lines and the buttons stand: Xiaomi's page, or the one for a wide picture (a video). */
        layout: MusicLayout = MusicLayout.of(cover),
    ) {
        val u = h / 596f
        if (!typed) {
            typed = true
            titlePaint.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            artistPaint.typeface = Typeface.create(Typeface.DEFAULT, 400, false)
        }
        c.drawColor(colors.ground or (0xFF shl 24))

        // The glows: Xiaomi's picture of one is 1.5 page heights across.
        val radius = 0.75f * h
        val tints = intArrayOf(colors.glow1, colors.glow2, colors.glow3)
        for (i in 0..2) {
            val cx = glow[2 * i] * w; val cy = glow[2 * i + 1] * h
            if (cy - radius >= h) continue
            val rgb = tints[i] and 0xFFFFFF
            // Made once for a colour and a size, at the origin, and moved to where the glow is: the page is drawn
            // every frame, and three new gradients a frame was three too many.
            if (glowShader[i] == null || glowRgb[i] != rgb || glowRadius[i] != radius) {
                val stops = IntArray(GLOW_STOPS.size) { k -> (Math.round(255f * GLOW_ALPHA[k] * GLOW_STRENGTH[i]) shl 24) or rgb }
                glowShader[i] = RadialGradient(0f, 0f, radius, stops, GLOW_STOPS, Shader.TileMode.CLAMP)
                glowRgb[i] = rgb; glowRadius[i] = radius
            }
            matrix.setTranslate(cx, cy)
            glowShader[i]!!.setLocalMatrix(matrix)
            glowPaint.shader = glowShader[i]
            glowPaint.alpha = Math.round(255f * glowStrength.coerceIn(0f, 1f))
            c.drawRect(0f, 0f, w, h, glowPaint)
        }

        // The cover, or the note where there is none.
        rect.set(layout.artX * u, layout.artY * u, (layout.artX + layout.artW) * u, (layout.artY + layout.artH) * u)
        if (cover != null && cover.width > 0 && cover.height > 0) {
            val shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val k = maxOf(rect.width() / cover.width, rect.height() / cover.height)
            matrix.reset()
            matrix.setScale(k, k)
            matrix.postTranslate(rect.left + (rect.width() - cover.width * k) / 2f, rect.top + (rect.height() - cover.height * k) / 2f)
            shader.setLocalMatrix(matrix)
            art.shader = shader
            c.drawRoundRect(rect, ART_R * u, ART_R * u, art)
            art.shader = null
        } else if (playerIcon != null) {
            // No cover for this track: the player's own icon in its place, as the phone draws it. Never a drawn stand-in.
            art.shader = null
            c.drawBitmap(playerIcon, null, rect, art)
        }

        // The title and who it is by.
        titlePaint.color = Color.WHITE; titlePaint.textSize = TITLE_SIZE * u
        artistPaint.color = 0x80FFFFFF.toInt(); artistPaint.textSize = ARTIST_SIZE * u
        val room = TEXT_W * u
        // (Xiaomi's page keeps the title's place with no line under it; the video's block closes the hole.)
        val titleCy = if (artist.isBlank() && layout !== MusicLayout.MUSIC) layout.titleAloneCy else layout.titleCy
        c.drawText(cut(title, room, titlePaint), layout.artX * u, titleCy * u - (titlePaint.ascent() + titlePaint.descent()) / 2f, titlePaint)
        if (artist.isNotBlank()) c.drawText(cut(artist, room, artistPaint), layout.artX * u, layout.artistCy * u - (artistPaint.ascent() + artistPaint.descent()) / 2f, artistPaint)

        // The buttons. Their glyphs are Xiaomi's, in a square of 120 units.
        val k = BTN * u / 120f
        val cy = layout.btnCy * u
        fill.color = Color.WHITE
        for (b in Button.entries) {
            val cx = centre(b) * u
            when (b) {
                Button.PLAY_PAUSE -> {
                    // The change between the two, as Xiaomi's: the old one shrinks away, the new one grows in.
                    val t = (sinceFlip.coerceAtLeast(0L).toFloat() / FLIP_MS).coerceIn(0f, 1f)
                    val out = (1f - t / 0.4f).coerceIn(0f, 1f)
                    val into = ((t - 0.18f) / 0.82f).coerceIn(0f, 1f)
                    if (out > 0f) glyph(c, cx, cy, k * (0.5f + 0.5f * out), out, pause = !playing)
                    glyph(c, cx, cy, k * (0.5f + 0.5f * ease(into)), into, pause = playing)
                }
                else -> {
                    // A press: the triangle gives a little, the bar goes ahead and comes back.
                    val t = if (pressed == b) (sincePress.coerceAtLeast(0L).toFloat() / PRESS_MS).coerceIn(0f, 1f) else 1f
                    val bump = if (t < 0.3f) t / 0.3f else 1f - ease((t - 0.3f) / 0.7f)
                    skip(c, cx, cy, k, forward = b == Button.NEXT, bump)
                }
            }
        }
        fill.alpha = 255
    }

    private fun ease(t: Float) = t * t * (3f - 2f * t)

    private fun cut(s: String, room: Float, p: TextPaint): String {
        val one = s.replace('\n', ' ').trim()
        if (p.measureText(one) <= room) return one
        var n = one.length
        while (n > 1 && p.measureText(one.substring(0, n).trimEnd() + "…") > room) n--
        return one.substring(0, NoticePop.whole(one, n)).trimEnd() + "…"
    }

    /** Pause's two bars or play's triangle about ([cx], [cy]), [k] px to a unit, at [alpha]. */
    private fun glyph(c: Canvas, cx: Float, cy: Float, k: Float, alpha: Float, pause: Boolean) {
        fill.color = Color.WHITE; fill.alpha = Math.round(255f * alpha)
        if (pause) {
            for (side in intArrayOf(-1, 1)) {
                val x0 = cx + side * 3f * 4.235f * k; val x1 = cx + side * 3f * 8.807f * k
                rect.set(minOf(x0, x1), cy - 33f * k, maxOf(x0, x1), cy + 33f * k)
                c.drawRoundRect(rect, 3.9f * k, 3.9f * k, fill)
            }
        } else {
            curve(PLAY, cx + 2.572f * k, cy, 3f * k, 1f)
            c.drawPath(path, fill)
        }
    }

    /** The on (or back) button: a small triangle and a bar beside it; [bump] 0..1 is how far into its press it is. */
    private fun skip(c: Canvas, cx: Float, cy: Float, k: Float, forward: Boolean, bump: Float) {
        fill.color = Color.WHITE; fill.alpha = 255
        val way = if (forward) 1f else -1f
        val tx = if (forward) -5.291f else 2.291f          // the triangle's place in Xiaomi's square, from its middle
        val bx = if (forward) 16.17f else -19.17f
        curve(NEXT, cx + (tx + way * 4.3f * bump) * k, cy, 3f * k * (1f - 0.1f * bump), way)
        c.drawPath(path, fill)
        val x = cx + (bx + way * 12f * bump) * k
        rect.set(x - 4.43f * k, cy - (23.76f - 4.6f * bump) * k, x + 4.43f * k, cy + (23.76f - 4.6f * bump) * k)
        c.drawRoundRect(rect, 2.6f * k, 2.6f * k, fill)
    }

    /** A closed curve from Xiaomi's points (each: place, tangent in, tangent out), about ([cx], [cy]), mirrored when [way] is -1. */
    private fun curve(points: FloatArray, cx: Float, cy: Float, k: Float, way: Float) {
        path.rewind()
        val n = points.size / 6
        fun px(i: Int, o: Int) = cx + way * (points[6 * i] + (if (o >= 0) points[6 * i + o] else 0f)) * k
        fun py(i: Int, o: Int) = cy + (points[6 * i + 1] + (if (o >= 0) points[6 * i + o + 1] else 0f)) * k
        path.moveTo(px(0, -1), py(0, -1))
        for (i in 0 until n) {
            val j = (i + 1) % n
            path.cubicTo(px(i, 4), py(i, 4), px(j, 2), py(j, 2), px(j, -1), py(j, -1))
        }
        path.close()
    }

    const val FLIP_MS = 470f
    const val PRESS_MS = 620f

    // Xiaomi's glyphs (play.json, forward.json): for each point its place, its tangent in and its tangent out.
    private val PLAY = floatArrayOf(
        9.413f, -0.725f, 0.2f, 0.463f, -0.229f, -0.53f, 7.175f, -2.491f, 1.339f, 0.825f, 0f, 0f, -5.103f, -10.05f, 0f, 0f, -1.457f, -0.897f,
        -7.889f, -11.344f, 0.601f, -0.051f, -0.524f, 0.045f, -9.204f, -10.609f, 0.312f, -0.422f, -0.358f, 0.485f, -9.562f, -7.558f, 0f, -1.711f, 0f, 0f,
        -9.562f, 7.558f, 0f, 0f, 0f, 1.711f, -9.204f, 10.609f, -0.358f, -0.485f, 0.312f, 0.422f, -7.889f, 11.344f, -0.524f, -0.045f, 0.601f, 0.051f,
        -5.103f, 10.05f, -1.457f, 0.897f, 0f, 0f, 7.175f, 2.491f, 0f, 0f, 1.339f, -0.825f, 9.413f, 0.725f, -0.229f, 0.53f, 0.2f, -0.463f,
    )
    private val NEXT = floatArrayOf(
        6.179f, -0.47f, 0.129f, 0.3f, -0.148f, -0.343f, 4.729f, -1.614f, 0.868f, 0.534f, 0f, 0f, -3.386f, -6.61f, 0f, 0f, -0.944f, -0.581f,
        -5.192f, -7.448f, 0.389f, -0.033f, -0.339f, 0.029f, -6.044f, -6.972f, 0.202f, -0.274f, -0.232f, 0.314f, -6.276f, -4.996f, 0f, -1.109f, 0f, 0f,
        -6.276f, 4.996f, 0f, 0f, 0f, 1.109f, -6.044f, 6.972f, -0.232f, -0.314f, 0.202f, 0.274f, -5.192f, 7.448f, -0.339f, -0.029f, 0.389f, 0.033f,
        -3.386f, 6.61f, -0.944f, 0.581f, 0f, 0f, 4.729f, 1.614f, 0f, 0f, 0.868f, -0.534f, 6.179f, 0.47f, -0.148f, 0.343f, 0.129f, -0.3f,
    )
}
