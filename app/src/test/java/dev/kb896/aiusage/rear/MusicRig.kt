package dev.kb896.aiusage.rear

import dev.kb896.aiusage.service.MusicSound
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object MusicRig {
    const val FS = 48_000
    const val MIX_BUF = 960
    const val WINDOW = 25

    fun db(x: Double) = 10.0.pow(x / 20.0)

    // ---- the instruments ----

    class Gen(seed: Long, val seconds: Double) {
        val n = (seconds * FS).toInt()
        val track = DoubleArray(n)
        private val rnd = java.util.Random(seed)
        fun noise(m: Int) = DoubleArray(m) { rnd.nextGaussian() }

        fun place(into: DoubleArray, sample: DoubleArray, at: Double, gain: Double = 1.0) {
            val i = (at * FS).toInt(); if (i >= into.size) return
            for (j in 0 until min(sample.size, into.size - i)) into[i + j] += gain * sample[j]
        }

        fun kick(): DoubleArray {
            val m = (0.35 * FS).toInt(); val hiss = Filters.hp(noise(m), 2000.0)
            var ph = 0.0
            return DoubleArray(m) { i -> val t = i.toDouble() / FS; ph += 2 * PI * (48 + 110 * exp(-t / 0.035)) / FS; sin(ph) * exp(-t / 0.16) + 0.25 * hiss[i] * exp(-t / 0.004) }
        }

        fun snare(): DoubleArray {
            val m = (0.25 * FS).toInt(); val body = Filters.lp(Filters.hp(noise(m), 900.0), 6000.0)
            return DoubleArray(m) { i -> val t = i.toDouble() / FS; 0.6 * body[i] * exp(-t / 0.09) + 0.5 * sin(2 * PI * 190 * t) * exp(-t / 0.05) }
        }

        fun hat(): DoubleArray {
            val m = (0.08 * FS).toInt(); val h = Filters.hp(Filters.hp(noise(m), 7000.0), 7000.0)
            return DoubleArray(m) { i -> h[i] * exp(-i.toDouble() / FS / 0.025) }
        }

        fun pad(t0: Double, t1: Double, root: Double): DoubleArray {
            val out = DoubleArray(n)
            for (i in (t0 * FS).toInt() until min(n, (t1 * FS).toInt())) {
                val t = i.toDouble() / FS
                var v = 0.0
                for ((k, ratio) in doubleArrayOf(1.0, 1.26, 1.5, 2.0).withIndex()) for (h in 1..4) v += 0.5.pow(h) * sin(2 * PI * root * ratio * h * t + k + h) / 4
                val env = min(1.0, (t - t0) / 0.8) * min(1.0, (t1 - t) / 0.4)
                out[i] = v * env * (0.85 + 0.15 * sin(2 * PI * 0.3 * t))
            }
            return out
        }

        fun bassline(t0: Double, t1: Double, kicks: DoubleArray): DoubleArray {
            val out = DoubleArray(n)
            val notes = doubleArrayOf(55.0, 55.0, 65.4, 49.0)
            var ph = 0.0
            val i0 = (t0 * FS).toInt(); val i1 = min(n, (t1 * FS).toInt())
            val seg = DoubleArray(i1 - i0) { j -> val t = (i0 + j).toDouble() / FS; ph += notes[(((t - t0) / 1.0).toInt()) % 4] / FS; 2 * (ph % 1.0) - 1 }
            val f = Filters.lp(Filters.lp(seg, 260.0), 260.0)
            for (j in f.indices) out[i0 + j] = f[j]
            for (k in kicks) { val j = (k * FS).toInt(); for (m in 0 until min((0.4 * FS).toInt(), n - j)) out[j + m] *= 1 - 0.7 * exp(-m.toDouble() / FS / 0.12) }
            return out
        }

        /** Noise whose low-pass rises from 300 Hz to 12 kHz over [t0]..[t1], at an even loudness. */
        fun riser(t0: Double, t1: Double): DoubleArray {
            val out = DoubleArray(n)
            val i0 = (t0 * FS).toInt(); val i1 = min(n, (t1 * FS).toInt())
            val x = noise(i1 - i0)
            val st = Filters.Biquad()
            for (j in x.indices) {
                if (j % 64 == 0) st.lowpass(300.0 * 40.0.pow(j.toDouble() / x.size))
                out[i0 + j] = st.run(x[j])
            }
            return out
        }

        fun pluck(t0: Double, t1: Double): DoubleArray {
            val out = DoubleArray(n)
            var i = 0; var x = t0
            while (x < t1) {
                val f = if (i % 2 == 1) 660.0 else 440.0
                place(out, DoubleArray((0.5 * FS).toInt()) { j -> val t = j.toDouble() / FS; sin(2 * PI * f * t) * exp(-t / 0.15) }, x)
                x += 0.75; i++
            }
            return out
        }

        /** [x] scaled so its RMS over [t0]..[t1] is [targetDb] dBFS. */
        fun norm(x: DoubleArray, targetDb: Double, t0: Double, t1: Double): DoubleArray {
            val r = rms(x, t0, t1) + 1e-12
            val g = db(targetDb) / r
            return DoubleArray(x.size) { x[it] * g }
        }

        fun rms(x: DoubleArray, t0: Double, t1: Double): Double {
            var s = 0.0; val i0 = (t0 * FS).toInt(); val i1 = min(x.size, (t1 * FS).toInt())
            for (i in i0 until i1) s += x[i] * x[i]
            return sqrt(s / max(1, i1 - i0))
        }

        fun add(x: DoubleArray) { for (i in 0 until n) track[i] += x[i] }

        fun sum(vararg xs: DoubleArray) = DoubleArray(n) { i -> xs.sumOf { it[i] } }

        /** A drop from [t0] to [t1]: a kick every 0.5 s, the bass ducked under it, a snare, hats, a pad; at [level] dBFS. */
        fun drop(t0: Double, t1: Double, level: Double): DoubleArray {
            val ks = generateSequence(t0) { it + 0.5 }.takeWhile { it < t1 - 1e-9 }.toList().toDoubleArray()
            val kk = DoubleArray(n); for (x in ks) place(kk, kick(), x)
            val hh = DoubleArray(n); var x = t0; while (x < t1) { place(hh, hat(), x + 0.125); x += 0.25 }
            val ss = DoubleArray(n); x = t0 + 0.5; while (x < t1) { place(ss, snare(), x); x += 1.0 }
            val d = sum(norm(kk, -11.0, t0, t1), norm(bassline(t0, t1, ks), -14.0, t0, t1), norm(hh, -22.0, t0, t1), norm(ss, -17.0, t0, t1), norm(pad(t0, t1 + 0.3, 233.0), -18.0, t0, t1))
            return norm(d, level, t0, t1)
        }

        /** A verse: pad, soft bass, hats, a soft kick every second; at [level] dBFS. */
        fun verse(t0: Double, t1: Double, level: Double): DoubleArray {
            val ks = generateSequence(t0) { it + 1.0 }.takeWhile { it < t1 - 1e-9 }.toList().toDoubleArray()
            val vh = DoubleArray(n); var x = t0; while (x < t1) { place(vh, hat(), x + 0.125); x += 0.25 }
            val vk = DoubleArray(n); for (k in ks) place(vk, kick(), k)
            val v = sum(norm(pad(t0, t1 + 0.4, 196.0), -25.0, t0 + 0.5, t1), norm(bassline(t0, t1, ks), -25.0, t0 + 0.5, t1), norm(vh, -34.0, t0, t1), norm(vk, -24.0, t0, t1))
            return norm(v, level, t0 + 0.5, t1)
        }

        fun silence(t0: Double, t1: Double) { for (i in (t0 * FS).toInt() until min(n, (t1 * FS).toInt())) track[i] = 0.0 }

        fun clip() { for (i in 0 until n) track[i] = track[i].coerceIn(-1.0, 1.0) }
    }

    /** The kick onsets of the main track's two drops. */
    val DROP_KICKS: List<Double> = generateSequence(20.25) { it + 0.5 }.takeWhile { it < 30.0 - 1e-9 }.toList() +
        generateSequence(38.0) { it + 0.5 }.takeWhile { it < 44.0 - 1e-9 }.toList()

    /** The sections of the main track, seconds. */
    val SECTIONS = linkedMapOf("intro" to (0.0 to 6.0), "verse" to (6.0 to 14.0), "build" to (14.0 to 20.0), "drop" to (20.25 to 30.0),
        "breakdown" to (30.0 to 38.0), "drop 2" to (38.0 to 44.0), "paused" to (44.0 to 48.0))
    const val PAUSE_S = 44.0

    /** SPEC.md 8.0: intro, verse, build, a breath, DROP, breakdown, drop 2, paused (48 s). */
    val mainTrack: DoubleArray by lazy {
        val g = Gen(7, 48.0)
        g.add(g.norm(g.pad(0.0, 6.4, 196.0), -27.0, 0.8, 6.0))
        g.add(g.verse(6.0, 14.0, -18.0))
        // The build: pad, riser, an accelerating snare roll; its loudness laid on from -28 to -12 dBFS.
        val sn = DoubleArray(g.n); var x = 14.0; var gap = 0.5
        while (x < 19.95) { g.place(sn, g.snare(), x); x += gap; gap = max(0.0625, gap * 0.9) }
        val raw = g.sum(g.norm(g.pad(14.0, 20.0, 196.0), -24.0, 14.5, 19.5), g.norm(g.riser(14.0, 20.0), -22.0, 14.0, 20.0), g.norm(sn, -22.0, 14.0, 19.9))
        g.add(rampTo(raw, 14.0, 20.0, -28.0, -12.0))
        g.add(g.drop(20.25, 30.0, -10.0))
        g.add(g.norm(g.sum(g.norm(g.pad(30.0, 38.4, 175.0), -26.0, 30.5, 38.0), g.norm(g.pluck(30.0, 38.0), -32.0, 30.0, 38.0)), -26.0, 30.5, 38.0))
        g.add(g.drop(38.0, 44.0, -10.0))
        g.silence(20.0, 20.25)
        g.silence(PAUSE_S, 48.0)
        g.clip()
        g.track
    }

    /** SPEC.md 8.0, "long quiet": 20 s of drop at -10 dBFS, then 40 s of a steady verse at -20. */
    val longQuiet: DoubleArray by lazy {
        val g = Gen(11, 60.0)
        g.add(g.drop(0.0, 20.0, -10.0))
        g.add(g.verse(20.0, 60.0, -20.0))
        g.clip()
        g.track
    }

    val previewSong: DoubleArray by lazy {
        val g = Gen(5, 10.0)
        g.add(g.norm(g.pad(0.0, 1.8, 196.0), -27.0, 0.6, 1.5))
        g.add(g.verse(1.5, 3.5, -18.0))
        val sn = DoubleArray(g.n); var x = 3.5; var gap = 0.25
        while (x < 5.15) { g.place(sn, g.snare(), x); x += gap; gap = max(0.0625, gap * 0.85) }
        val raw = g.sum(g.norm(g.pad(3.5, 5.2, 196.0), -24.0, 3.7, 5.1), g.norm(g.riser(3.5, 5.2), -22.0, 3.5, 5.2), g.norm(sn, -22.0, 3.5, 5.15))
        g.add(rampTo(raw, 3.5, 5.2, -28.0, -12.0))
        g.add(g.drop(5.35, 8.5, -10.0))
        g.add(g.norm(g.sum(g.norm(g.pad(8.5, 10.4, 175.0), -26.0, 8.7, 10.0), g.norm(g.pluck(8.5, 10.0), -32.0, 8.5, 10.0)), -26.0, 8.7, 10.0))
        g.silence(5.2, 5.35)
        g.clip()
        g.track
    }
    val PREVIEW_KICKS: List<Double> = generateSequence(5.35) { it + 0.5 }.takeWhile { it < 8.5 - 1e-9 }.toList()
    val PREVIEW_SECTIONS = linkedMapOf("intro" to (0.0 to 1.5), "verse" to (1.5 to 3.5), "build" to (3.5 to 5.2), "drop" to (5.35 to 8.5), "breakdown" to (8.5 to 10.0))

    /** [x] between [t0] and [t1] given the loudness running from [fromDb] to [toDb] (its own 0.25 s RMS evened out). */
    private fun rampTo(x: DoubleArray, t0: Double, t1: Double, fromDb: Double, toDb: Double): DoubleArray {
        val out = DoubleArray(x.size)
        val i0 = (t0 * FS).toInt(); val i1 = min(x.size, (t1 * FS).toInt())
        val win = (0.25 * FS).toInt()
        // The RMS in 0.25 s steps, then the gain read between them, so the loudness rises evenly.
        val steps = (i1 - i0 + win - 1) / win
        val gains = DoubleArray(steps) { s ->
            // A whole window each, the last one ending at the build's end (a few samples are no measure of loudness).
            val b = min(i1, i0 + s * win + win); val a = max(i0, b - win)
            var e = 0.0; for (i in a until b) e += x[i] * x[i]
            val r = sqrt(e / max(1, b - a)) + 1e-9
            db(fromDb + (toDb - fromDb) * (((a + b) / 2 - i0).toDouble() / (i1 - i0)).coerceIn(0.0, 1.0)) / r
        }
        for (i in i0 until i1) {
            val p = (i - i0).toDouble() / win - 0.5
            val s = p.toInt().coerceIn(0, steps - 1); val fr = (p - s).coerceIn(0.0, 1.0)
            out[i] = x[i] * (gains[s] * (1 - fr) + gains[min(steps - 1, s + 1)] * fr)
        }
        return out
    }

    // ---- the Visualizer ----

    /** AOSP's EffectVisualizer on the output mix, for [track] played at [volDb] (the mixer applies it first). */
    class Vis(track: DoubleArray, volDb: Double) {
        private val nbuf = track.size / MIX_BUF
        private val rmsSq = DoubleArray(nbuf)
        private val capture = ByteArray(nbuf * MIX_BUF)

        init {
            val g = db(volDb)
            for (b in 0 until nbuf) {
                var sq = 0.0; var peak = 0.0
                for (j in 0 until MIX_BUF) { val v = track[b * MIX_BUF + j] * g; sq += v * v; peak = max(peak, kotlin.math.abs(2 * v)) }
                rmsSq[b] = sq / MIX_BUF
                val scale = if (peak > 0) 0.99 / peak else 1.0
                for (j in 0 until MIX_BUF) {
                    val v = 2 * track[b * MIX_BUF + j] * g
                    capture[b * MIX_BUF + j] = Math.round(v * scale * 128 + 128).toInt().coerceIn(0, 255).toByte()
                }
            }
        }

        /** getMeasurementPeakRms().mRms at [atMs]: the mean of the last 25 buffers' mean squares, in mB. */
        fun rmsMb(atMs: Long): Int {
            val last = ((atMs * FS / 1000) / MIX_BUF).toInt().coerceAtMost(nbuf)
            if (last <= 0) return -9600
            var s = 0.0; val from = max(0, last - WINDOW)
            for (i in from until last) s += rmsSq[i]
            val r = sqrt(s / (last - from))
            return if (r * 32767 < 0.000016) -9600 else Math.round(2000 * log10(r)).toInt()
        }

        /** getWaveForm(1024) at [atMs]: the last whole buffers' samples. */
        fun wave(atMs: Long, size: Int = 1024): ByteArray {
            val end = (((atMs * FS / 1000) / MIX_BUF).toInt().coerceAtMost(nbuf)) * MIX_BUF
            if (end < size) return ByteArray(size) { 128.toByte() }
            return capture.copyOfRange(end - size, end)
        }
    }

    // ---- the chain ----

    /** One drawn frame of the chain: [t] ms into the track. */
    class Shot(val t: Long, val body: MusicFace.Body, val frame: MusicBody.Frame)

    /** The clock the app and the shell are given: the track's time plus this (0 means "never" to both). */
    const val CLOCK0 = 1_000L

    /**
     * Plays [track] at [volDb] through MusicSound (every 33 ms while [playing]) and a Body (every 16.7 ms) for [ms],
     * handing every frame to [each]; [packet] sees every packet sent. Frames start at [fromMs] (the app's clock).
     * [floor]: the settings' "Fluid size" the body is told (0, no floor, as the tests from before it).
     */
    fun chain(
        track: DoubleArray, volDb: Double, ms: Long, playing: (Long) -> Boolean = { true },
        sends: (Long) -> Boolean = { true }, packet: (Long, FloatArray) -> Unit = { _, _ -> }, sound: MusicSound = MusicSound(), floor: Int = 0, each: (Shot) -> Unit,
    ) {
        val vis = Vis(track, volDb)
        val body = MusicFace.Body().also { it.floor = floor }
        var nextTick = 0L
        var frame = 0
        while (true) {
            val t = Math.round(frame * 1000.0 / 60.0)
            if (t > ms) break
            while (nextTick <= t) {
                if (playing(nextTick) && sends(nextTick)) {
                    val p = sound.tick(vis.rmsMb(nextTick), vis.wave(nextTick), volDb.toFloat(), 2, playing(nextTick), FS, CLOCK0 + nextTick)
                    packet(nextTick, p)
                    body.hear(p, CLOCK0 + nextTick)
                }
                nextTick += 33L
            }
            val f = body.step(CLOCK0 + t, playing(t))
            each(Shot(t, body, f))
            frame++
        }
    }

    /** The two-colour palette of the worst case: orange LCh(60, 62, 52) against teal LCh(60, 38, 190), ground tone 4. */
    fun worstColours(f: MusicBody.Frame) = f.setColors(lch(4.0, 6.0, 52.0), lch(60.0, 62.0, 52.0), lch(60.0, 38.0, 190.0))

    fun lch(l: Double, c: Double, h: Double): Int {
        val hr = Math.toRadians(h); val a = c * cos(hr); val b = c * sin(hr)
        val fy = (l + 16) / 116; val fx = fy + a / 500; val fz = fy - b / 200
        fun inv(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) * 27 / 24389
        val x = inv(fx) * 0.95047; val y = inv(fy); val z = inv(fz) * 1.08883
        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        fun o(v: Double) = Math.round(255 * (if (v <= 0.0031308) 12.92 * v else 1.055 * max(v, 0.0).pow(1 / 2.4) - 0.055)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (o(r) shl 16) or (o(g) shl 8) or o(bl)
    }
}

/** RBJ biquads, enough for the made-up instruments. */
object Filters {
    class Biquad {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        fun lowpass(f: Double, q: Double = 0.7071) = set(f, q, low = true)
        fun highpass(f: Double, q: Double = 0.7071) = set(f, q, low = false)

        private fun set(f: Double, q: Double, low: Boolean): Biquad {
            val w = 2 * PI * min(f, MusicRig.FS * 0.45) / MusicRig.FS
            val alpha = sin(w) / (2 * q); val cw = cos(w); val a0 = 1 + alpha
            if (low) { b0 = (1 - cw) / 2 / a0; b1 = (1 - cw) / a0; b2 = b0 } else { b0 = (1 + cw) / 2 / a0; b1 = -(1 + cw) / a0; b2 = b0 }
            a1 = -2 * cw / a0; a2 = (1 - alpha) / a0
            return this
        }

        fun run(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    fun lp(x: DoubleArray, f: Double): DoubleArray { val b = Biquad().lowpass(f); return DoubleArray(x.size) { b.run(x[it]) } }
    fun hp(x: DoubleArray, f: Double): DoubleArray { val b = Biquad().highpass(f); return DoubleArray(x.size) { b.run(x[it]) } }
}
