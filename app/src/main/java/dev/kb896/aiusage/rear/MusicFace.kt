package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import dev.kb896.aiusage.color.mcu.ColorUtils
import dev.kb896.aiusage.color.mcu.Hct
import dev.kb896.aiusage.color.mcu.QuantizerCelebi
import dev.kb896.aiusage.color.mcu.Score
import dev.kb896.aiusage.color.mcu.TonalPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

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

    // ---- the colours: Xiaomi's way ----

    /** The page's colours for one cover: the ground, and the body's two colours (A at the left at rest, B at the right). */
    data class Palette(val ground: Int, val colA: Int, val colB: Int)

    /**
     * The palette of [seed], the cover's most prominent colour (0 for none, or a grey cover: then quiet greys).
     * As Xiaomi's, in HCT (Google's material-color-utilities, which its libMiMainColor uses): the seed's hue at a
     * chroma of at least 48, tone 4 for the ground and tone 60 for A; B is the same at the hue 20 degrees lower.
     */
    fun palette(seed: Int): Palette {
        if (seed == 0) return Palette(ColorUtils.argbFromLstar(4.0), ColorUtils.argbFromLstar(60.0), ColorUtils.argbFromLstar(60.0))
        val hct = Hct.fromInt(seed)
        val chroma = maxOf(48.0, hct.chroma)
        val main = TonalPalette.fromHueAndChroma(hct.hue, chroma)
        return Palette(main.tone(4), main.tone(60), TonalPalette.fromHueAndChroma(hct.hue - 20.0, chroma).tone(60))
    }

    /**
     * The cover's most prominent colour as Xiaomi finds it: Celebi's quantizer (128 colours) and Score, on the cover
     * scaled to 64 x 64; 0 for a missing cover or one with nothing colourful in it. SOME MILLISECONDS: never in a
     * frame. The shell takes it when the cover arrives ([colorsOf]), the settings' preview once (`remember`).
     */
    fun seed(cover: Bitmap?): Int {
        cover ?: return 0
        return runCatching {
            val small = Bitmap.createScaledBitmap(cover, SEED_PX, SEED_PX, true)
            val px = IntArray(SEED_PX * SEED_PX); small.getPixels(px, 0, SEED_PX, 0, 0, SEED_PX, SEED_PX)
            seedOf(px)
        }.getOrDefault(0)
    }

    /** [seed] of pixels already small. */
    fun seedOf(pixels: IntArray): Int = Score.best(QuantizerCelebi.quantize(pixels, 128))

    private const val SEED_PX = 64
    private val colorsLock = Any()
    private var colorsFor: Bitmap? = null
    private var colorsWere: Palette? = null

    /**
     * [palette] of [seed] of [picture], kept for the same picture (a track's metadata comes in several calls). Worked
     * out on the caller's thread and NOT under the painter's lock ([draw] is synchronized on this object): a frame on
     * the music thread never waits for a cover's colours.
     */
    fun colorsOf(picture: Bitmap?): Palette {
        synchronized(colorsLock) { if (picture === colorsFor) colorsWere?.let { return it } }
        val p = palette(seed(picture))
        synchronized(colorsLock) { colorsFor = picture; colorsWere = p }
        return p
    }

    // ---- the body: what the sound does to it, eased ----

    class Body(private val say: ((String) -> Unit)? = null) {
        /**
         * The settings' "Fluid size" (PanelConfig.musicFloor), kept within 0..100. 0 until told: the shell and the settings'
         * preview tell it the stored one (70 by default) with every change.
         */
        @Volatile var floor: Int = 0
            set(v) {
                val n = v.coerceIn(0, 100)
                if (n != field) say?.invoke("music body: the floor is now $n (%.0f px over the foot)".format(MusicBody.floorPx(n.toFloat())))
                field = n
            }

        /** Two one-pole stages in a row, each with its own time to rise and to fall. */
        private class Two(val up: Float, val down: Float) {
            var a = 0f; var b = 0f
            fun step(target: Float, dt: Float): Float {
                a += (target - a) * (1f - exp(-dt / (if (target > a) up else down)))
                b += (a - b) * (1f - exp(-dt / (if (a > b) up else down)))
                return b
            }
        }

        private val course = Two(0.09f, 0.30f)
        private val kick = Two(0.030f, 0.095f)
        /** Whether a level comes: back in about 1 s, gone over about 4 s (the spec's 1.2 s fall left 0.035 of the course after 6 s). */
        private val has = Two(0.25f, 1.0f)
        private val alive = Two(0.35f, 0.35f)
        private var packet: FloatArray? = null
        private var packetAt = Long.MIN_VALUE / 2
        private var kickHeld = 0f
        private var lastCourse = 0f
        private var drawnKick = 0f
        private var coursePx = 0f
        private var kickPx = 0f
        private var light = Float.NaN
        private var ph = 0.0
        private var ph2 = 0.0
        /** The travelling wave's phase, in lengths (slow while paused, never reset). */
        private var ph3 = 0.0
        private var last = 0L
        /** The bands last heard (0..1), when, and each one's smoother. */
        private val heard = FloatArray(MusicBody.BANDS)
        private var bandsAt = Long.MIN_VALUE / 2
        private val bandEase = Array(MusicBody.BANDS) { Two(BAND_UP_S, BAND_DOWN_S) }
        /** Whether bands come (the made-up ones crossed over to the heard ones and back). */
        private val known = Two(0.12f, 0.8f)
        /** The floor as drawn, px over the foot, glided to [floor]'s px (in px: the slider's curve is steep near 0). */
        private val floorEase = Two(FLOOR_S, FLOOR_S)

        /**
         * The floor as drawn is still on its way to [floor] (more than [FLOOR_DONE_PX] from it). The shell keeps drawing a
         * page that otherwise stands still (paused) while this is so: a fixed time after the change left a glide whose
         * frames were held up (the page covered meanwhile) part of the way, with no frame to come to finish it.
         */
        val floorGliding: Boolean
            @Synchronized get() {
                if (last == 0L) return false
                val want = MusicBody.floorPx(floor.toFloat())
                return maxOf(kotlin.math.abs(floorEase.a - want), kotlin.math.abs(floorEase.b - want)) > FLOOR_DONE_PX
            }

        /**
         * The page is not seen (covered in its place by usage, the notes or an arrival): the floor stands on [floor] at
         * once, as on a first frame, for a glide is for a change he watches. The shell calls it from a frame tick that
         * found the page covered, so the page is on its new size the moment it is drawn again.
         */
        @Synchronized fun floorUnseen() { val fl = MusicBody.floorPx(floor.toFloat()); floorEase.a = fl; floorEase.b = fl }

        /** The uniforms of the last [step]; the same object each time. */
        val frame = MusicBody.Frame()

        /** What the page wants of the light now, for the tests: the smoothed course, the drawn kick, whether a level comes, playing. */
        var c = 0f; private set
        var kd = 0f; private set
        var w = 0f; private set
        var a = 0f; private set
        var e = MusicBody.E_NOLEVEL; private set

        /** A packet from the app (service/MusicSound): { course, kick, valid, L, Hi, Lo }. */
        @Synchronized fun hear(v: FloatArray, now: Long) {
            // From another process: a packet that is not whole numbers is dropped (one NaN in a smoother stays NaN for good).
            if (v.size < 3 || !v[0].isFinite() || !v[1].isFinite() || !v[2].isFinite()) return
            if (arriving && now - packetAt > ARRIVING_MS) { arriving = false; say?.invoke("music sound: stopped arriving (the last ${now - packetAt} ms before this one)") }
            if (!arriving) say?.invoke("music sound: arriving (course %.2f, kick %.2f, valid %d)".format(v[0], v[1], v[2].toInt()))
            arriving = true
            packet = v.copyOf(); packetAt = now
            kickHeld = maxOf(kickHeld, v[1].coerceIn(0f, 1f))
            // The bands, when the packet has them all and each is a real 0..1 (-1: not known this tick).
            val b0 = dev.kb896.aiusage.service.MusicSound.BAND0
            if (v.size >= b0 + MusicBody.BANDS && (0 until MusicBody.BANDS).all { v[b0 + it].isFinite() && v[b0 + it] >= 0f }) {
                for (i in 0 until MusicBody.BANDS) heard[i] = v[b0 + i].coerceIn(0f, 1f)
                bandsAt = now
            }
            packets++
            if (v[2] >= 0.5f) { courseLo = minOf(courseLo, v[0]); courseHi = maxOf(courseHi, v[0]) }
        }

        /** The `away` of the last [step]. */
        private var awaySeen = 0

        /**
         * The uniforms for the picture at [now] (colours are set by the painter). Asked every frame while the page is
         * up. [away]: a count that changes each time the page was not up (ShellCard.hides); a change = the page
         * comes back, and the first frame shows the present.
         */
        @Synchronized fun step(now: Long, playing: Boolean, away: Int = awaySeen): MusicBody.Frame {
            val back = away != awaySeen && last != 0L
            awaySeen = away
            // The first frame, or the first back: on the floor as it is now (a glide is for a change he is watching).
            if (last == 0L || back) { val fl = MusicBody.floorPx(floor.toFloat()); floorEase.a = fl; floorEase.b = fl }
            val gapMs = if (last == 0L) 0L else (now - last).coerceAtLeast(0L)
            val dtMs = gapMs.coerceAtMost(MAX_STEP_MS)
            last = now
            demoFeed(now, playing)
            if (arriving && now - packetAt > ARRIVING_MS) { arriving = false; say?.invoke("music sound: stopped arriving (the last ${now - packetAt} ms ago)") }
            if (back) {
                // Nothing was drawn while away: those frames are run through now, toward what is heard now.
                kickHeld = 0f; kick.a = 0f; kick.b = 0f; drawnKick = 0f; kickPx = 0f
                val frames = (gapMs.coerceAtMost(CATCH_UP_MS) * 60L / 1_000L).toInt()
                repeat(frames) { advance(FRAME_S, now, playing) }
                // All has settled by then; the rest of the time away only turns the breath and the waves on (one clock).
                val rest = (gapMs - frames * 1_000L / 60L).coerceAtLeast(0L) / 1000.0
                ph += rest * a * (1f + 0.5f * e); ph2 += rest * a
                ph3 += rest * (0.3f + 0.7f * a) / WAVE_S
            }
            advance(dtMs / 1000f, now, playing)
            note(now, playing)
            return frame
        }

        /** One step of [dt] s with what is heard at [now]: every smoother, the step budget, the uniforms. */
        private fun advance(dt: Float, now: Long, playing: Boolean) {
            val p = packet
            val fresh = p != null && now - packetAt < FRESH_MS && p[2] >= 0.5f && playing
            w = has.step(if (fresh) 1f else 0f, dt)
            if (fresh) lastCourse = p!![0].coerceIn(0f, 1f).pow(1.6f)
            c = course.step(lastCourse, dt)
            kickHeld *= exp(-dt / KICK_HOLD_S)
            val k = kick.step(if (fresh) kickHeld else 0f, dt)
            a = alive.step(if (playing) 1f else 0f, dt)
            e = w * c + (1f - w) * MusicBody.E_NOLEVEL
            val kWant = w * k
            ph += dt * a * (1f + 0.5f * e)
            ph2 += (dt * a).toDouble()
            ph3 += dt * (0.3f + 0.7f * a) / WAVE_S

            // The peaks: each band eased toward what is heard, or toward the made-up spectrum while nothing is.
            val kn = known.step(if (fresh && now - bandsAt < FRESH_MS) 1f else 0f, dt)
            for (i in 0 until MusicBody.BANDS) {
                val target = kn * heard[i].pow(BAND_GAMMA) + (1f - kn) * made(i)
                frame.bands[i] = bandEase[i].step(target, dt).coerceIn(0f, 1f)
            }

            // The step budget: the course moves freely; the kick gets what is left of 10 px a frame.
            val courseNow = a * (MusicBody.LIFT0 + MusicBody.RISE * e)
            val scale = a * MusicBody.KICK_PX * (0.35f + 0.65f * minOf(1f, c / 0.8f))
            val frames = dt / FRAME_S
            val left = maxOf(0f, BUDGET_PX * frames - kotlin.math.abs(courseNow - coursePx) - RESERVE_PX * frames)
            var kickNow = kickPx + (kWant * scale - kickPx).coerceIn(-left, left)
            var next = if (scale > 1e-3f) kickNow / scale else kWant
            next = next.coerceIn(drawnKick - KICK_STEP * frames, drawnKick + KICK_STEP * frames).coerceIn(0f, 1f)
            drawnKick = next
            kickNow = drawnKick * scale
            coursePx = courseNow; kickPx = kickNow
            kd = drawnKick

            val f = frame
            // The base where the course has it, never lower than the floor (with no floor: exactly where the course has it).
            val floorY = MusicBody.PAGE_H - floorEase.step(MusicBody.floorPx(floor.toFloat()), dt)
            f.base = MusicBody.onFloor(MusicBody.PAGE_H - MusicBody.REST_PX - courseNow, floorY)
            f.peak = a * (MusicBody.PEAK0 + MusicBody.PEAK_E * e * e)
            f.kickAmp = kickNow
            f.breath = (a * (3f + 3f * e) * sin(2.0 * PI * ph / BREATH_S)).toFloat()
            f.wave = MusicBody.WAVE_REST * (1f - a) + a * (MusicBody.WAVE_PX + MusicBody.WAVE_E * e)
            f.wph = (2.0 * PI * (ph3 % 1.0)).toFloat()
            val g = (0.6f + 0.4f * a) * (0.42f + 0.50f * e + 0.22f * drawnKick)
            light = if (light.isNaN()) g else light + (g - light).coerceIn(-LIGHT_STEP * frames, LIGHT_STEP * frames)
            f.g = light
            f.glow = 0.11f * light
            f.tilt = cos(2.0 * PI * ph2 / TILT_S).toFloat()
            f.shift = (0.8 * sin(2.0 * PI * ph2 / SHIFT_S)).toFloat()
        }

        /** Band [i] of the made-up spectrum: each its own slow swell, never flat, never still (on the playing clock). */
        private fun made(i: Int): Float =
            (0.30 + 0.22 * sin(2.0 * PI * ph2 / MADE_S[i] + 1.7 * i) + 0.08 * sin(2.0 * PI * ph2 / (0.37 * MADE_S[i]) + i)).toFloat()

        // -- the shell's log: every two seconds while it plays, what the body did, as ranges --
        private var arriving = false
        private var packets = 0
        private var courseLo = 1f; private var courseHi = 0f
        private var notedAt = 0L
        private var hLo = Float.MAX_VALUE; private var hHi = 0f; private var hSum = 0.0; private var hN = 0
        private var gLo = Float.MAX_VALUE; private var gHi = 0f; private var kickMax = 0f; private var wSum = 0.0
        private var valleyLo = Float.MAX_VALUE; private var peakHi = 0f; private var bandsHeard = 0

        private fun note(now: Long, playing: Boolean) {
            if (say == null) return
            if (!playing) { notedAt = now; resetNotes(); return }
            val h = MusicBody.height(frame)
            hLo = minOf(hLo, h); hHi = maxOf(hHi, h); hSum += h; hN++
            gLo = minOf(gLo, frame.g); gHi = maxOf(gHi, frame.g); kickMax = maxOf(kickMax, frame.kickAmp); wSum += w
            valleyLo = minOf(valleyLo, MusicBody.lowest(frame)); peakHi = maxOf(peakHi, h - (MusicBody.PAGE_H - frame.base))
            if (now - bandsAt < FRESH_MS) bandsHeard++
            if (notedAt == 0L) notedAt = now
            if (now - notedAt < NOTE_MS || hN == 0) return
            say.invoke(
                "music body: height %.0f..%.0f px (mean %.0f), deepest valley %.0f px, peaks up to %.0f px over the base, bands heard %d/%d frames, light %.2f..%.2f, kick lift max %.0f px, have-level %.2f, packets %d (last %s ago), course %s"
                    .format(hLo, hHi, hSum / hN, valleyLo, peakHi, bandsHeard, hN, gLo, gHi, kickMax, wSum / hN, packets, if (packetAt < 0L) "never" else "${now - packetAt} ms", if (courseHi >= courseLo) "%.2f..%.2f".format(courseLo, courseHi) else "-")
            )
            say.invoke("music body uniforms: $frame")
            notedAt = now; resetNotes()
        }

        private fun resetNotes() {
            packets = 0; courseLo = 1f; courseHi = 0f
            hLo = Float.MAX_VALUE; hHi = 0f; hSum = 0.0; hN = 0; gLo = Float.MAX_VALUE; gHi = 0f; kickMax = 0f; wSum = 0.0
            valleyLo = Float.MAX_VALUE; peakHi = 0f; bandsHeard = 0
        }

        // -- a debug build's made-up song ("musicsound" in pop-demo, over a demo track only) --
        private var demoAt = 0L
        private var demoSent = 0L

        /** A made-up 20 s course fed to this body (4 s quiet, a 5 s build, an 8 s drop with kicks, 3 s quiet), then it ends. */
        @Synchronized fun demo(now: Long) { demoAt = now; demoSent = 0L; say?.invoke("music sound: a made-up course for 20 s (demo)") }

        /** The made-up course ends now (a track of his has come). */
        @Synchronized fun endDemo() { if (demoAt != 0L) { demoAt = 0L; say?.invoke("music sound: the made-up course ended") } }

        private fun demoFeed(now: Long, playing: Boolean) {
            if (demoAt == 0L) return
            val t = (now - demoAt) / 1000f
            if (t >= 20f || !playing) { endDemo(); return }
            if (now - demoSent < 33L) return
            val beat = (t / 0.5f).toInt() != ((demoSent - demoAt) / 500L).toInt() || demoSent == 0L
            demoSent = now
            val course = when { t < 4f -> 0.1f; t < 9f -> 0.2f + 0.7f * (t - 4f) / 5f; t < 17f -> 1f; else -> 0.1f }
            val k = if (t in 9f..17f && beat) 0.85f else 0f
            // A made-up spectrum as well: the bass bands up on each beat, the rest swelling at their own paces.
            val p = FloatArray(dev.kb896.aiusage.service.MusicSound.PACKET)
            p[0] = course; p[1] = k; p[2] = 1f
            val sinceBeat = (t % 0.5f)
            for (i in 0 until MusicBody.BANDS) {
                val swell = 0.5f + 0.5f * sin(t * (2.1f + 0.63f * i) + 1.3f * i)
                val thump = if (i < 3 && t in 9f..17f) 0.7f * exp(-sinceBeat / 0.12f) else 0f
                p[dev.kb896.aiusage.service.MusicSound.BAND0 + i] = (0.15f + 0.6f * course * swell + thump).coerceIn(0f, 1f)
            }
            hear(p, now)
        }

        companion object {
            /** A frame that comes late on a page that stayed up: no more than this is made up for (no leap). */
            const val MAX_STEP_MS = 50L
            /** A page back from away: this much of the time away at most is run through unseen (all settles within it). */
            const val CATCH_UP_MS = 8_000L
            /** A packet older than this is not followed (the app sends one every 33 ms). */
            const val FRESH_MS = 250L
            private const val ARRIVING_MS = 1_000L
            private const val NOTE_MS = 2_000L
            const val KICK_HOLD_S = 0.13f
            const val FRAME_S = 1f / 60f
            /** No point of the surface moves more than this in a 16.7 ms frame. */
            const val BUDGET_PX = 10f
            /** Of it, for the breath and the travelling wave. */
            private const val RESERVE_PX = 1f
            /** The drawn kick's and the light's largest change in a 16.7 ms frame. */
            private const val KICK_STEP = 0.2f
            const val LIGHT_STEP = 0.07f
            const val BREATH_S = 6.5
            /** The travelling wave goes one length ([MusicBody.WAVE_L]) in this long playing (about 3 times that paused). */
            const val WAVE_S = 4.6
            /** A band's two stages: 90 % of a rise in about 80 ms, of a fall in about 350 ms. */
            const val BAND_UP_S = 0.022f
            const val BAND_DOWN_S = 0.09f
            /** A heard band is shown as its value to this power: a hit stands out more than a band a little over its usual. */
            const val BAND_GAMMA = 1.4f
            /** The floor's glide to a new value: two stages of this, 90 % of the way in about 0.4 s, no overshoot. */
            const val FLOOR_S = 0.1f
            /** The glide is done this close to the new floor (about 1 s for the whole range). */
            const val FLOOR_DONE_PX = 0.05f
            /** The made-up spectrum's swells, one per band, seconds. */
            private val MADE_S = doubleArrayOf(3.1, 4.7, 2.6, 5.3, 3.9, 2.9, 6.1, 3.4, 4.3, 2.7, 5.7, 3.7)
            const val TILT_S = 61.0
            const val SHIFT_S = 23.0
        }
    }

    /**
     * The page's colours on their way from one cover's to the next: eased, each frame, so a new track is not a
     * cut from one ground and body to others. The same clock rule as [Body]: asked every frame.
     */
    class Fade {
        private var now: FloatArray? = null
        private var last = 0L
        /** The goal's colours as floats, worked out again only for another palette (a frame makes nothing). */
        private val goal = FloatArray(9)
        private var goalOf: Palette? = null
        /** The palette last given: given again, the same object, while its three colours have not changed. */
        private var gave: Palette? = null

        @Synchronized fun at(to: Palette, atMs: Long): Palette {
            if (to != goalOf) { floats(to, goal); goalOf = to }
            val cur = now ?: goal.copyOf().also { now = it }
            val dt = if (last == 0L) 0f else (atMs - last).coerceIn(0L, Body.MAX_STEP_MS) / 1000f
            last = atMs
            val k = 1f - exp(-dt / FADE_S)
            for (i in cur.indices) cur[i] += (goal[i] - cur[i]) * k
            val g = c(cur, 0); val a = c(cur, 3); val b = c(cur, 6)
            gave?.let { if (it.ground == g && it.colA == a && it.colB == b) return it }
            return Palette(g, a, b).also { gave = it }
        }

        private fun c(cur: FloatArray, o: Int) = Color.rgb(Math.round(cur[o]), Math.round(cur[o + 1]), Math.round(cur[o + 2]))

        private fun floats(p: Palette, f: FloatArray) {
            put(f, 0, p.ground); put(f, 3, p.colA); put(f, 6, p.colB)
        }

        private fun put(f: FloatArray, o: Int, c: Int) { f[o] = Color.red(c).toFloat(); f[o + 1] = Color.green(c).toFloat(); f[o + 2] = Color.blue(c).toFloat() }

        companion object { const val FADE_S = 0.45f }
    }

    // ---- the picture ----

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val matrix = Matrix()
    private var typed = false

    // ---- what a frame keeps from the last one (all under draw's lock): the same pixels, worked out once ----

    /**
     * One line's words as they are drawn (cut with an ellipsis to its room) and its middle's offset from the
     * baseline, kept while the words, the room and the size stay: the cut measures the line once per character cut
     * away, which in every frame was most of the page's own drawing time with a long title. The typeface is set
     * once ([typed]) and never changes.
     */
    private class Line {
        private var text: String? = null
        private var room = Float.NaN
        private var size = Float.NaN
        var shown = ""; private set
        var mid = 0f; private set

        fun set(s: String, room: Float, p: TextPaint): Line {
            if (s == text && room == this.room && p.textSize == size) return this
            shown = cut(s, room, p); mid = (p.ascent() + p.descent()) / 2f
            text = s; this.room = room; size = p.textSize
            return this
        }
    }
    private val titleLine = Line()
    private val artistLine = Line()

    /**
     * The cover's shader and where it was placed, kept for the same picture with the same pixels (a cover changed in
     * place has a new generation id) in the same place: one shader per cover, not one per frame. Its own paint, so the
     * shader is not set and taken off again each frame.
     */
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var coverShader: BitmapShader? = null
    private var shaderFor: Bitmap? = null
    private var shaderGen = 0
    private var shaderW = 0
    private var shaderH = 0
    private val shaderRect = RectF()

    private val BUTTONS = Button.entries.toTypedArray()


    /**
     * Draws the page over the whole canvas (opaque). [body]: the body's uniforms for this frame ([Body.step]).
     * [cover] is the track's own; without one [playerIcon], the player app's icon, stands in its place.
     * [sinceFlip]: ms since it went from playing to paused or back (the middle button's change);
     * [pressed] and [sincePress]: a back or on button just pressed, for its nudge.
     */
    @Synchronized
    fun draw(
        c: Canvas, w: Float, h: Float, title: String, artist: String, playing: Boolean, cover: Bitmap?, colors: Palette,
        body: MusicBody.Frame, sinceFlip: Long = Long.MAX_VALUE, pressed: Button? = null, sincePress: Long = Long.MAX_VALUE,
        playerIcon: Bitmap? = null,
        /** Where the picture, the lines and the buttons stand: Xiaomi's page, or the one for a wide picture (a video). */
        layout: MusicLayout = MusicLayout.of(cover),
    ) {
        val u = h / 596f
        if (!typed) {
            typed = true
            titlePaint.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            artistPaint.typeface = Typeface.create(Typeface.DEFAULT, 400, false)
        }
        // The ground and the body of light, the whole page (MusicBody: one shader on the shell's hardware canvas).
        body.setColors(colors.ground, colors.colA, colors.colB)
        MusicBody.draw(c, w, h, body)

        // The cover, or the note where there is none.
        rect.set(layout.artX * u, layout.artY * u, (layout.artX + layout.artW) * u, (layout.artY + layout.artH) * u)
        if (cover != null && cover.width > 0 && cover.height > 0) {
            var shader = coverShader
            if (shader == null || cover !== shaderFor || cover.generationId != shaderGen) {
                shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                coverShader = shader; shaderFor = cover; shaderGen = cover.generationId
                shaderW = 0; shaderH = 0            // placed below
                coverPaint.shader = shader
            }
            if (rect != shaderRect || cover.width != shaderW || cover.height != shaderH) {
                val k = maxOf(rect.width() / cover.width, rect.height() / cover.height)
                matrix.reset()
                matrix.setScale(k, k)
                matrix.postTranslate(rect.left + (rect.width() - cover.width * k) / 2f, rect.top + (rect.height() - cover.height * k) / 2f)
                shader.setLocalMatrix(matrix)
                shaderRect.set(rect); shaderW = cover.width; shaderH = cover.height
            }
            c.drawRoundRect(rect, ART_R * u, ART_R * u, coverPaint)
        } else if (playerIcon != null) {
            // No cover for this track: the player's own icon in its place, as the phone draws it. Never a drawn stand-in.
            art.shader = null
            c.drawBitmap(playerIcon, null, rect, art)
        }
        if (cover == null && shaderFor != null) { coverShader = null; shaderFor = null; coverPaint.shader = null }   // the last cover is not held

        // The title and who it is by.
        titlePaint.color = Color.WHITE; titlePaint.textSize = TITLE_SIZE * u
        artistPaint.color = 0x80FFFFFF.toInt(); artistPaint.textSize = ARTIST_SIZE * u
        val room = TEXT_W * u
        // (Xiaomi's page keeps the title's place with no line under it; the video's block closes the hole.)
        val titleCy = if (artist.isBlank() && layout !== MusicLayout.MUSIC) layout.titleAloneCy else layout.titleCy
        val t = titleLine.set(title, room, titlePaint)
        c.drawText(t.shown, layout.artX * u, titleCy * u - t.mid, titlePaint)
        if (artist.isNotBlank()) {
            val a = artistLine.set(artist, room, artistPaint)
            c.drawText(a.shown, layout.artX * u, layout.artistCy * u - a.mid, artistPaint)
        }

        // The buttons. Their glyphs are Xiaomi's, in a square of 120 units.
        val k = BTN * u / 120f
        val cy = layout.btnCy * u
        fill.color = Color.WHITE
        for (b in BUTTONS) {
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
            var side = -1
            while (side <= 1) {            // the left bar, then the right one
                val x0 = cx + side * 3f * 4.235f * k; val x1 = cx + side * 3f * 8.807f * k
                rect.set(minOf(x0, x1), cy - 33f * k, maxOf(x0, x1), cy + 33f * k)
                c.drawRoundRect(rect, 3.9f * k, 3.9f * k, fill)
                side += 2
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
