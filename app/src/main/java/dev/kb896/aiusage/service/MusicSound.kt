package dev.kb896.aiusage.service

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class MusicSound {

    // -- the course: the song's own ceiling and floor, dBFS; NaN until the track's first sound --
    private var hi = Double.NaN
    private var lo = Double.NaN
    private var hiAt = 0L
    private var loAt = 0L
    private var lastRealAt = Long.MIN_VALUE / 2
    private var firstAt = 0L
    private var captureAt = Long.MIN_VALUE / 2
    private var lastAt = 0L

    // -- the kick --
    private var fullMean = Double.NaN
    private var ratioMean = Double.NaN
    private var kickRange = KICK_RANGE_MIN
    private var fft: Fft? = null

    // -- the bands: each one's own recent mean and variance (dB of its mean power per bin); NaN until its first capture --
    private val bandMean = DoubleArray(BANDS) { Double.NaN }
    private val bandVar = DoubleArray(BANDS) { BAND_DEV_MIN_DB * BAND_DEV_MIN_DB }
    /** The bins each band starts at (the last entry: one past the top band's end), for [bandsFor]'s rate and size. */
    private var bandBins = IntArray(0)
    private var bandsFor = 0L

    // -- the volume: taken as 0 dB for an output whose mix is plainly not turned down (Bluetooth absolute volume) --
    private var outType = Int.MIN_VALUE
    private var unattenuated = false
    /** The volume and output of the last tick, and when either last changed. */
    private var volWas = Float.NaN
    private var typeWas = Int.MIN_VALUE
    private var volAt = Long.MIN_VALUE / 2
    /** The last course worked out, held while the volume settles. */
    private var cWas = 0.0

    // -- the ceiling's start: the prior, the last tracks' tops, and this track's --
    private var prior = HI_PRIOR
    private val tops = ArrayDeque<Double>()
    /** Whether a loud part of this track has reached its ceiling (else it still stands where the prior put it). */
    private var reached = false
    /** The loudest this track has been, and for how long it has had a level, both outside its first second and a settling. */
    private var top = Double.NaN
    private var heardMs = 0.0

    /** The last kick reading's parts, for the tests: dB fuller than the last 0.3 s, and the bass's rise against the mids. */
    internal var lastOnset = 0.0; internal var lastBassRise = 0.0; internal var lastShare = 0.0

    /** What the 2 s log line says. */
    val stats = Stats()

    /**
     * A new track: its own range, measured from its first sound. The one that ends teaches the prior if it was heard
     * long enough ([TOP_HEARD_MS]): the next ceiling starts at the loudest of the last two such tracks, never over
     * [HI_PRIOR] (a louder source keeps the old start) nor under [PRIOR_LOWEST]. One quiet track among loud ones
     * changes nothing; two in a row, or a session that starts quiet, do.
     */
    fun newTrack() {
        if (!top.isNaN() && heardMs >= TOP_HEARD_MS) { tops.addLast(top); while (tops.size > 2) tops.removeFirst() }
        prior = if (tops.isEmpty()) HI_PRIOR else min(HI_PRIOR, tops.max()).coerceAtLeast(PRIOR_LOWEST)
        hi = Double.NaN; lo = Double.NaN; reached = false; top = Double.NaN; heardMs = 0.0
    }

    /** The ceiling a new track starts from now. */
    val priorCeiling: Double get() = prior

    /** The Visualizer was made anew at [now] (its RMS window starts empty again). */
    fun newCapture(now: Long) { captureAt = now }

    /** The volume that is taken off the measurement for [volDb] on output [type], after the safety rule. */
    fun volumeInEffect(volDb: Float, type: Int): Float {
        if (type != outType) { outType = type; unattenuated = false }
        return if (unattenuated) 0f else volDb
    }

    /**
     * One tick. [rmsMb]: `getMeasurementPeakRms`'s RMS in millibels (-9600 = nothing); [wave]: the 8-bit capture, or
     * null when `getWaveForm` failed; [volDb]: his media volume on [outType] in dB (0 = not turned down); [fsHz]:
     * the capture's sampling rate; [now]: ms on one clock. Returns the packet (a new array).
     */
    fun tick(rmsMb: Int, wave: ByteArray?, volDb: Float, outType: Int, playing: Boolean, fsHz: Int, now: Long): FloatArray {
        val dt = if (lastAt == 0L) 1.0 / 30.0 else ((now - lastAt).coerceIn(0L, 100L)) / 1000.0
        lastAt = now
        // His volume or the output changed: the RMS (a mean over the last 0.5 to 1 s) still holds the mix as it was.
        if (!volWas.isNaN() && (kotlin.math.abs(volDb - volWas) > 0.05f || outType != typeWas)) volAt = now
        volWas = volDb; typeWas = outType
        val volSettling = now - volAt < VOL_SETTLE_MS
        var vol = volumeInEffect(volDb, outType).toDouble()
        // A song cannot be louder than full scale: over -6 dBFS with his volume said to be under -10 dB, the mix was
        // not turned down for this output (Bluetooth absolute volume, offload with the volume in the hardware). Not
        // while the volume settles: a loud mix just turned down is still in the RMS then.
        if (!volSettling && rmsMb > -600 && rmsMb > -9600 && vol < -10.0) { unattenuated = true; vol = 0.0 }

        val out = FloatArray(PACKET)
        for (i in 0 until BANDS) out[BAND0 + i] = -1f
        val level: Double
        if (rmsMb <= -9600) {
            // Nothing measured: a silence IN the song if real values came a moment ago; else no level at all
            // (offload, another output, the capture not running yet).
            if (playing && now - lastRealAt < NO_PCM_MS) level = -96.0
            else { stats.add(out, rmsMb, wave != null); return out }
        } else {
            lastRealAt = now
            level = rmsMb / 100.0 - vol
        }
        out[2] = 1f
        out[3] = level.toFloat()
        var c = 0.0
        if (level >= SILENT_DB) {
            val fresh = hi.isNaN()
            if (fresh) firstAt = now
            val settling = now - firstAt < SETTLE_MS
            // A Visualizer made anew (the back lit again) has the same short window, and his volume just changed
            // gives a level off by up to the change: the range found is kept, not moved by those readings.
            val anew = !settling && (now - captureAt < SETTLE_MS || volSettling)
            if (settling && (fresh || !volSettling)) {
                // The ceiling starts at the first sound or the prior, whichever is higher. NOT THE SPEC'S 4 dB OVER THE FIRST
                // SOUND: the ceiling rises to a louder part within 0.3 s but comes down only after 20 s, so a start set
                // too high costs 20 s of a squashed course; a song that opens with its drop (the rig's "long quiet")
                // read 0.67 of its drop for those 20 s and the quiet verse after it only 30 px under it.
                hi = max(level, prior); lo = level - 8.0; hiAt = now; loAt = now
            }
            val follow = if (anew) 0.0 else 1.0 - exp(-dt / UP_S)
            if (anew) { hiAt = now; loAt = now }
            if (!settling && !anew) {
                top = if (top.isNaN()) level else max(top, level)
                heardMs += dt * 1000.0
                if (level >= hi - 1.0) reached = true
            }
            // The ceiling: up with a louder song at once; down only after a 20 s hold, and slowly. One that no loud part
            // has reached while the prior is a guess (a first track quieter than it) comes down faster, but never
            // under the loudest part heard.
            if (level >= hi - 1.0) hiAt = now
            if (level > hi) hi += (level - hi) * follow
            else if (now - hiAt > HOLD_MS) hi =
                if (reached || tops.isNotEmpty()) max(level, hi - CREEP_DB_S * dt)
                else max(if (top.isNaN()) level else max(level, top), hi - PRIOR_FALL_DB_S * dt)
            // The floor, its mirror image.
            if (level <= lo + 1.0) loAt = now
            if (level < lo) lo += (level - lo) * follow
            else if (now - loAt > HOLD_MS) lo = min(level, lo + CREEP_DB_S * dt)
            val span = (hi - lo).coerceIn(SPAN_MIN, SPAN_MAX)
            // While the volume settles the course stands where it was (the level is not to be believed then).
            c = if (volSettling && !fresh) cWas else (1.0 - (hi - level) / span).coerceIn(0.0, 1.0)
        } else if (volSettling) c = cWas
        cWas = c
        out[0] = c.toFloat()
        out[4] = if (hi.isNaN()) -96f else hi.toFloat()
        out[5] = if (lo.isNaN()) -96f else lo.toFloat()
        out[1] = kick(wave, c, fsHz, dt).toFloat()
        if (level < SILENT_DB) for (i in 0 until BANDS) out[BAND0 + i] = 0f
        else if (wave != null) bands(fsHz, dt, out)
        stats.add(out, rmsMb, wave != null)
        return out
    }

    /** The power of the capture just transformed (0 = none this tick: no capture, a flat one, a size not a power of two). */
    private var power = 0.0

    /** The capture's spectrum into [fft]; false if there is none to take. */
    private fun transform(wave: ByteArray?): Boolean {
        power = 0.0
        if (wave == null || wave.isEmpty() || wave.all { it == 128.toByte() }) return false
        val n = wave.size
        val f = fft?.takeIf { it.n == n } ?: (if (Integer.bitCount(n) == 1) Fft(n).also { fft = it } else return false)
        var mean = 0.0
        for (b in wave) mean += ((b.toInt() and 0xFF) - 128) / 128.0
        mean /= n
        var p = 0.0
        for (i in 0 until n) {
            val x = ((wave[i].toInt() and 0xFF) - 128) / 128.0 - mean
            p += x * x
            f.re[i] = x * f.hann[i]; f.im[i] = 0.0
        }
        f.run()
        power = p
        return true
    }

    /**
     * The bands of the capture [transform] has just taken, each against its own recent range, into [out] from
     * [BAND0]. A flat capture (all 128: nothing in it) gives zeros.
     */
    private fun bands(fsHz: Int, dt: Double, out: FloatArray) {
        val f = fft
        if (power <= 0.0 || f == null) { for (i in 0 until BANDS) out[BAND0 + i] = 0f; return }
        val key = fsHz.toLong() * 100_000L + f.n
        if (key != bandsFor || bandBins.size != BANDS + 1) {
            bandsFor = key
            val bin = fsHz.toDouble() / f.n
            bandBins = IntArray(BANDS + 1)
            for (j in 0..BANDS) {
                val at = Math.round(BAND_EDGES_HZ[j] / bin).toInt().coerceIn(1, f.n / 2)
                bandBins[j] = if (j == 0) at else max(at, bandBins[j - 1] + 1).coerceAtMost(f.n / 2 + 1)
            }
        }
        for (i in 0 until BANDS) {
            val a = bandBins[i]; val b = min(bandBins[i + 1], f.n / 2 + 1)
            var e = 0.0
            for (j in a until b) e += f.re[j] * f.re[j] + f.im[j] * f.im[j]
            val db = 10.0 * log10(e / max(1, b - a) + 1e-12)
            if (bandMean[i].isNaN()) bandMean[i] = db
            // Where it is against its own last second: in its own deviations (never taken as under BAND_DEV_MIN_DB).
            val dev = kotlin.math.sqrt(bandVar[i]).coerceAtLeast(BAND_DEV_MIN_DB)
            val z = (db - bandMean[i]) / dev
            out[BAND0 + i] = (BAND_REST + BAND_PER_DEV * z).coerceIn(0.0, 1.0).toFloat()
            // Then this moment joins them.
            val k = 1.0 - exp(-dt / BAND_MEAN_S)
            val off = db - bandMean[i]
            bandMean[i] += off * k
            bandVar[i] += (off * off - bandVar[i]) * k
        }
    }

    private fun kick(wave: ByteArray?, c: Double, fsHz: Int, dt: Double): Double {
        if (!transform(wave) || c <= 0.0) return 0.0
        val n = wave!!.size
        val f = fft!!
        val bin = fsHz.toDouble() / n
        val bass = f.band(bin, BASS_HZ_LO, BASS_HZ_HI)
        val mid = f.band(bin, MID_HZ_LO, MID_HZ_HI)
        val ratio = 10.0 * log10((bass + 1e-9) / (mid + 1e-9))
        val share = bass / (f.band(bin, 1.0, fsHz / 2.0) + 1e-9)
        val full = 10.0 * log10(power / n + 1e-9)
        if (fullMean.isNaN()) { fullMean = full; ratioMean = ratio }
        // Fuller than the last 0.3 s (1 / crest factor: a kick fills its peak-normalised buffer)…
        val onset = max(0.0, full - fullMean - FULL_OVER_DB)
        // …and the bass a real part of what is heard (a pad, a pluck, a hat or a snare alone is not a kick).
        // NOT THE SPEC'S GATE (the bass against the mids above their 0.3 s mean, at least 0.6): on the test rig that
        // let a pad's beating and a pluck's onset through as kicks (40 packets in 14 s of intro and breakdown) and
        // took 40 % off every kick that had a snare on it. The bass's share of the capture's power parts them:
        // at most 0.12 in the intro and the breakdown, at least 0.46 at nine kicks in ten (MusicBodyTest, the rig).
        val gate = ((share - SHARE_NONE) / (SHARE_FULL - SHARE_NONE)).coerceIn(0.0, 1.0)
        kickRange = max(onset, max(kickRange * exp(-dt / KICK_RANGE_S), KICK_RANGE_MIN))
        val k = min(onset / kickRange, 1.0) * gate
        lastOnset = onset; lastBassRise = ratio - ratioMean; lastShare = share
        val a = 1.0 - exp(-dt / MEAN_S)
        fullMean += (full - fullMean) * a
        ratioMean += (ratio - ratioMean) * a
        return k
    }

    /** A radix-2 FFT of a fixed size, with its Hann window and twiddles made once. */
    internal class Fft(val n: Int) {
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val hann = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / (n - 1)) }
        private val cosT = DoubleArray(n / 2) { cos(-2.0 * PI * it / n) }
        private val sinT = DoubleArray(n / 2) { sin(-2.0 * PI * it / n) }
        private val rev = IntArray(n).also { r ->
            val bits = Integer.numberOfTrailingZeros(n)
            for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
        }

        fun run() {
            for (i in 0 until n) {
                val j = rev[i]
                if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var size = 2
            while (size <= n) {
                val half = size / 2; val step = n / size
                var start = 0
                while (start < n) {
                    var k = 0
                    for (j in start until start + half) {
                        val wr = cosT[k]; val wi = sinT[k]
                        val l = j + half
                        val tr = re[l] * wr - im[l] * wi
                        val ti = re[l] * wi + im[l] * wr
                        re[l] = re[j] - tr; im[l] = im[j] - ti
                        re[j] += tr; im[j] += ti
                        k += step
                    }
                    start += size
                }
                size *= 2
            }
        }

        /** The power in the bins from [loHz] to [hiHz] (each end rounded to its nearest bin; bins of [bin] Hz). */
        fun band(bin: Double, loHz: Double, hiHz: Double): Double {
            val a = max(1, (loHz / bin).roundToInt()); val b = min(n / 2, (hiHz / bin).roundToInt())
            var e = 0.0
            for (j in a..b) e += re[j] * re[j] + im[j] * im[j]
            return e
        }
    }

    /** The range of what was sent over the last two seconds, for the log line. */
    class Stats {
        private var n = 0; private var valid = 0; private var waveOk = 0; private var kicks = 0
        private var cLo = 1f; private var cHi = 0f; private var lLo = 0f; private var lHi = -200f
        private var rmsLo = 0; private var rmsHi = -9600; private var kMax = 0f
        private var hi = Float.NaN; private var lo = Float.NaN
        private var bandsOk = 0; private var bandSum = 0.0; private var bandMax = 0f

        fun add(p: FloatArray, rmsMb: Int, waveOk: Boolean) {
            n++
            if (waveOk) this.waveOk++
            rmsLo = min(rmsLo, rmsMb); rmsHi = max(rmsHi, rmsMb)
            if (p[2] < 0.5f) return
            valid++
            cLo = min(cLo, p[0]); cHi = max(cHi, p[0])
            lLo = min(lLo, p[3]); lHi = max(lHi, p[3])
            if (p[1] >= 0.3f) kicks++
            if (p.size >= PACKET && p[BAND0] >= 0f) {
                bandsOk++
                for (i in 0 until BANDS) { bandSum += p[BAND0 + i]; bandMax = max(bandMax, p[BAND0 + i]) }
            }
            kMax = max(kMax, p[1])
            hi = p[4]; lo = p[5]
        }

        val count: Int get() = n

        /** The line, and the range starts again. [vol]: the volume taken off, [out]: the output's name. */
        fun line(vol: Float, out: String, unattenuated: Boolean): String {
            fun f(v: Float, d: Int = 1) = if (v.isNaN()) "-" else "%.${d}f".format(v)
            val s = if (valid == 0) "level: no course (nothing measured inside a song)" else
                "level: course ${f(cLo, 2)}..${f(cHi, 2)}, L ${f(lLo)}..${f(lHi)} dBFS"
            val line = "$s (rms $rmsLo..$rmsHi mB, vol ${f(vol)} dB $out${if (unattenuated) ", taken as not turned down" else ""})" +
                (if (valid > 0) ", Hi ${f(hi)} Lo ${f(lo)} S ${f((hi - lo).coerceIn(SPAN_MIN.toFloat(), SPAN_MAX.toFloat()))}" else "") +
                ", kicks $kicks (k max ${f(kMax, 2)}), waveform ok $waveOk/$n, valid $valid/$n" +
                ", bands $bandsOk/$n (mean ${if (bandsOk > 0) f((bandSum / (bandsOk * BANDS)).toFloat(), 2) else "-"}, max ${f(bandMax, 2)})"
            bandsOk = 0; bandSum = 0.0; bandMax = 0f
            n = 0; valid = 0; waveOk = 0; kicks = 0; cLo = 1f; cHi = 0f; lLo = 0f; lHi = -200f; rmsLo = 0; rmsHi = -9600; kMax = 0f
            return line
        }
    }

    /** Whether the safety rule has the volume at 0 for the current output. */
    val takenAsUnattenuated: Boolean get() = unattenuated

    companion object {
        /** Where the first ceiling of a track sits: a loudness-normalised master's (-14 LUFS) loud parts. */
        const val HI_PRIOR = -12.0
        /** The lowest a prior learnt from quieter tracks goes. */
        const val PRIOR_LOWEST = -30.0
        /** A track heard this long (with a level, outside its settling) teaches the prior its top. */
        const val TOP_HEARD_MS = 30_000.0
        const val HOLD_MS = 20_000L
        const val CREEP_DB_S = 0.10
        /** How fast a ceiling no loud part has reached comes down after its hold, to the loudest part heard (prior a guess). */
        const val PRIOR_FALL_DB_S = 1.0
        /** After his volume or the output changes: the RMS window (up to 1 s) and a reading's lag. */
        const val VOL_SETTLE_MS = 1_200L
        const val UP_S = 0.15
        const val SPAN_MIN = 10.0
        const val SPAN_MAX = 20.0
        /** Under this the song is silent (a gap in it): the course 0, the ceiling and floor untouched. */
        const val SILENT_DB = -60.0
        /** The Visualizer's RMS window (25 mixer buffers, 0.5 to 1 s) is full this long after its first value. */
        const val SETTLE_MS = 1_000L
        /** -9600 this long after a real value, while playing: no level (not a silence in the song). */
        const val NO_PCM_MS = 3_000L

        const val BASS_HZ_LO = 47.0
        const val BASS_HZ_HI = 141.0
        const val MID_HZ_LO = 516.0
        const val MID_HZ_HI = 2484.0
        const val FULL_OVER_DB = 0.8
        /** The bass band's share of the capture's power under which nothing is a kick, and from which a kick is whole. */
        const val SHARE_NONE = 0.15
        const val SHARE_FULL = 0.35
        const val KICK_RANGE_S = 3.0
        const val KICK_RANGE_MIN = 2.0
        const val MEAN_S = 0.3

        /** The packet's size and where its bands start. */
        const val BANDS = 12
        const val BAND0 = 6
        const val PACKET = BAND0 + BANDS
        /** The bands' edges, Hz: about evenly in pitch from the kick's fundamental to the top of the hats. */
        val BAND_EDGES_HZ = doubleArrayOf(40.0, 90.0, 150.0, 250.0, 400.0, 630.0, 1_000.0, 1_600.0, 2_500.0, 4_000.0, 6_300.0, 10_000.0, 16_000.0)
        /** A band's own recent past: its mean and spread over about this long (one-pole), seconds. */
        const val BAND_MEAN_S = 1.2
        /** A band as usual reads this; each of its own deviations over that adds this; its deviation never under this, dB. */
        const val BAND_REST = 0.2
        const val BAND_PER_DEV = 0.35
        const val BAND_DEV_MIN_DB = 4.0
    }
}
