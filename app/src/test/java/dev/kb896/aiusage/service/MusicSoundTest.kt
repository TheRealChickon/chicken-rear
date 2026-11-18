package dev.kb896.aiusage.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** The sound side alone (SPEC.md 8.10): the song's course against its own range, the volume rule, the FFT's bands. */
class MusicSoundTest {

    /** One tick of a steady song at [levelDb] dBFS of its own, heard at [volDb]. */
    private fun MusicSound.at(levelDb: Double, now: Long, volDb: Float = -25f, playing: Boolean = true): FloatArray =
        tick(Math.round((levelDb + volDb) * 100).toInt(), null, volDb, 2, playing, 48_000, now)

    @Test fun `the course against the song's own range - the worked examples`() {
        val intro = MusicSound().at(-27.0, 1_000L)
        assertEquals("an intro at -27 dBFS, fresh", 0.25f, intro[0], 0.02f)
        assertEquals("its ceiling", -12f, intro[4], 0.01f); assertEquals("its floor", -35f, intro[5], 0.01f)
        val verse = MusicSound().at(-18.0, 1_000L)
        assertEquals("a verse at -18 dBFS, fresh", 0.55f, verse[0], 0.03f)
        assertEquals("valid", 1f, verse[2], 0f)
        assertEquals("the song's own level, his volume taken off", -18f, verse[3], 0.01f)
    }

    @Test fun `a new track starts its range afresh, from the prior`() {
        val s = MusicSound()
        var t = 1_000L
        repeat(300) { s.at(-6.0, t); t += 33L }          // a loud song: its ceiling up at -6
        assertTrue(s.at(-18.0, t)[4] > -8f)
        s.newTrack(); t += 33L
        val fresh = s.at(-18.0, t)
        assertEquals("the new track's ceiling", -12f, fresh[4], 0.01f)
        assertEquals(0.55f, fresh[0], 0.03f)
    }

    @Test fun `a silence in the song leaves the range alone, and -9600 within 3 s of a real value is a valid silence`() {
        val s = MusicSound()
        var t = 1_000L
        repeat(60) { s.at(-20.0, t); t += 33L }
        val hiLo = s.at(-20.0, t).let { it[4] to it[5] }; t += 33L
        val quiet = s.at(-70.0, t); t += 33L
        assertEquals("under -60: the course 0", 0f, quiet[0], 0f)
        assertEquals("valid", 1f, quiet[2], 0f)
        assertEquals("the ceiling untouched", hiLo.first, quiet[4], 0f); assertEquals("the floor untouched", hiLo.second, quiet[5], 0f)
        val nothing = s.tick(-9600, null, -25f, 2, true, 48_000, t)
        assertEquals("nothing measured a moment after a real value: a silence in the song", 1f, nothing[2], 0f)
        assertEquals(0f, nothing[0], 0f)
        val later = s.tick(-9600, null, -25f, 2, true, 48_000, t + 3_100L)
        assertEquals("over 3 s of nothing: no level", 0f, later[2], 0f)
        val paused = MusicSound().tick(-9600, null, -25f, 2, false, 48_000, 1_000L)
        assertEquals("nothing measured with nothing before: no level", 0f, paused[2], 0f)
    }

    @Test fun `a Visualizer made anew keeps the range - its first readings, from a short window, do not move it`() {
        val s = MusicSound()
        var t = 1_000L
        repeat(150) { s.at(-18.0, t); t += 33L }
        val hi = s.at(-18.0, t)[4]; t += 33L
        s.newCapture(t)
        val first = s.at(-8.0, t)                       // a kick alone in a window of one buffer
        assertEquals("the ceiling kept", hi, first[4], 0.001f)
        t += 1_100L
        repeat(30) { s.at(-18.0, t); t += 33L }
        assertEquals("and after the first second it follows as before", hi, s.at(-18.0, t)[4], 0.001f)
    }

    @Test fun `a quiet part stays low - the ceiling holds 20 s, then creeps`() {
        val s = MusicSound()
        var t = 1_000L
        repeat((20_000 / 33)) { s.at(-10.0, t); t += 33L }
        val cs = ArrayList<Float>()
        repeat((40_000 / 33)) { cs += s.at(-20.0, t)[0]; t += 33L }
        assertTrue("a 10 dB quieter part right after: ${cs[30]}", cs[30] <= 0.02f)
        assertTrue("still low 20 s on: ${cs[20_000 / 33]}", cs[20_000 / 33] <= 0.02f)
        assertTrue("40 s on, crept a little: ${cs.last()}", cs.last() <= 0.25f)
    }

    @Test fun `the safety rule - over -6 dBFS measured while the volume says -25 dB, the volume is taken as 0`() {
        val s = MusicSound()
        val p = s.tick(-400, null, -25f, 8, true, 48_000, 1_000L)
        assertTrue(s.takenAsUnattenuated)
        assertEquals("L is the measurement itself", -4f, p[3], 0.01f)
        assertEquals("and stays so on that output", 0f, s.volumeInEffect(-25f, 8), 0f)
        assertEquals("until the output changes", -25f, s.volumeInEffect(-25f, 2), 0f)
    }

    @Test fun `the FFT's bands follow the sampling rate - the same tone lands in the same band at 44_1 and 48 kHz`() {
        for (fs in listOf(44_100, 48_000)) {
            val fft = MusicSound.Fft(1024)
            fun power(hz: Double, lo: Double, hi: Double): Double {
                for (i in 0 until 1024) { fft.re[i] = sin(2 * PI * hz * i / fs) * fft.hann[i]; fft.im[i] = 0.0 }
                fft.run()
                return fft.band(fs.toDouble() / 1024, lo, hi)
            }
            val total = { hz: Double -> power(hz, 0.0, fs / 2.0) }
            for (bass in listOf(70.0, 100.0, 125.0)) assertTrue("$bass Hz at $fs Hz is bass", power(bass, MusicSound.BASS_HZ_LO, MusicSound.BASS_HZ_HI) > 0.8 * total(bass))
            for (mid in listOf(700.0, 1_000.0, 2_000.0)) assertTrue("$mid Hz at $fs Hz is mid", power(mid, MusicSound.MID_HZ_LO, MusicSound.MID_HZ_HI) > 0.95 * total(mid))
            assertTrue("1 kHz is no bass", power(1_000.0, MusicSound.BASS_HZ_LO, MusicSound.BASS_HZ_HI) < 0.001 * total(1_000.0))
        }
    }

    @Test fun `the log line says ranges`() {
        val s = MusicSound()
        var t = 1_000L
        for (l in listOf(-30.0, -20.0, -12.0)) repeat(20) { s.at(l, t); t += 33L }
        val line = s.stats.line(-25f, "speaker", false)
        assertTrue(line, line.startsWith("level: course ") && line.contains("..") && line.contains("dBFS (rms ") && line.contains("vol -25.0 dB speaker") && line.contains("valid 60/60"))
    }

    // -- the course against the mix as AOSP's Visualizer measures it: 20 ms buffers, the RMS of the last 25 --

    /**
     * Plays a song whose own level is [level] (dBFS, by the second) at his volume [vol] (dB, by the second; the mixer
     * applies it to each buffer) into [s] for [ms], every 33 ms, the volume READ as [read] has it (a lag is a late
     * read). [each] sees each packet with its time in ms. The clock goes on from one call to the next on the same [s].
     */
    private val clocks = HashMap<MusicSound, Long>()
    private fun play(s: MusicSound, level: (Double) -> Double, vol: (Double) -> Double, read: (Double) -> Double, ms: Long, each: (Long, FloatArray) -> Unit = { _, _ -> }) {
        val start = clocks[s] ?: 1_000L
        clocks[s] = start + ms + 33L
        var t = 0L
        while (t <= ms) {
            val last = (t / 20L).toInt()
            var sum = 0.0; var n = 0
            for (b in maxOf(0, last - 25) until last) { val at = b * 0.020; sum += 10.0.pow((level(at) + vol(at)) / 10.0); n++ }
            val mb = if (n == 0) -9600 else Math.round(1000.0 * log10(sum / n)).toInt()
            each(t, s.tick(mb, null, read(t / 1000.0).toFloat(), 2, true, 48_000, start + t))
            t += 33L
        }
    }

    /** A verse at -20 dBFS for 10 s, then a drop at -10 for the rest. */
    private val verseThenDrop = { at: Double -> if (at < 10.0) -20.0 else -10.0 }

    /** Five steps of 3 dB, 150 ms apart, from [from] at 30 s. */
    private fun steps(from: Double, by: Double): (Double) -> Double = { at -> from + by * ((at - 30.0) / 0.15 + 1).toInt().coerceIn(0, 5) * (if (at < 30.0) 0 else 1) }

    @Test fun `his volume moving in a drop leaves the drop a drop - down or up, read at once or a tick late`() {
        for ((name, vol) in listOf("down -20 to -35 dB" to steps(-20.0, -3.0), "up -35 to -20 dB" to steps(-35.0, 3.0))) for (lagMs in listOf(0L, 33L)) {
            val s = MusicSound()
            var lowest = 1f; var hiMost = -96f
            play(s, verseThenDrop, vol, { at -> vol(at - lagMs / 1000.0) }, 120_000L) { t, p ->
                if (t >= 12_000L) { lowest = minOf(lowest, p[0]); hiMost = maxOf(hiMost, p[4]) }
            }
            assertTrue("$name, read $lagMs ms late: the drop's course went down to $lowest", lowest >= 0.95f)
            assertTrue("$name, read $lagMs ms late: the ceiling went up to $hiMost dBFS", hiMost <= -9f)
        }
    }

    @Test fun `a loud mix turned down is not taken for an output that is never turned down`() {
        // At 0 dB a song at -3 dBFS; turned to -15 dB at 10 s and read at once: for half a second the RMS still has the
        // loud mix in it, over -6 dBFS with the volume said to be under -10 dB.
        val s = MusicSound()
        play(s, { -3.0 }, { at -> if (at < 10.0) 0.0 else -15.0 }, { at -> if (at < 10.0) 0.0 else -15.0 }, 14_000L)
        assertTrue("taken as not turned down", !s.takenAsUnattenuated)
        // The rule itself still holds after the settling: a mix plainly not turned down.
        play(s, { -3.0 }, { 0.0 }, { -15.0 }, 3_000L)
        assertTrue("a mix at -3 dBFS with the volume said to be -15 dB, for seconds", s.takenAsUnattenuated)
    }

    /** A verse at [verse] and a drop at [drop] dBFS by turns, 8 s each, the verse first. */
    private fun turns(verse: Double, drop: Double) = { at: Double -> if ((at / 8.0).toInt() % 2 == 0) verse else drop }

    @Test fun `a track quieter than the prior has its drops back within the first minute, its verses still low`() {
        val s = MusicSound()
        val drops = ArrayList<Float>(); val verses = ArrayList<Float>()
        play(s, turns(-34.0, -26.0), { -25.0 }, { -25.0 }, 64_000L) { t, p ->
            val inPart = (t % 8_000L) >= 1_500L        // a section, past its first 1.5 s (the RMS window and the rise)
            if (t >= 40_000L && inPart) { if ((t / 8_000L) % 2L == 1L) drops += p[0] else verses += p[0] }
        }
        assertTrue("the drops from 40 s: ${drops.min()}..${drops.max()}", drops.min() >= 0.9f)
        assertTrue("the verses from 40 s: ${verses.min()}..${verses.max()}", verses.max() <= 0.6f)
    }

    @Test fun `the prior is learnt from the last two tracks - a quieter source starts its drops as drops`() {
        val quiet = turns(-34.0, -26.0)
        val s = MusicSound()
        play(s, quiet, { -25.0 }, { -25.0 }, 40_000L)
        s.newTrack()
        assertEquals("a session that starts quiet: the next ceiling starts at that track's top", -26.0, s.priorCeiling, 0.3)
        // Its next track's first drop (8 to 16 s) is a drop from the start.
        val first = ArrayList<Float>()
        play(s, quiet, { -25.0 }, { -25.0 }, 16_000L) { t, p -> if (t in 9_500L until 16_000L) first += p[0] }
        assertTrue("the next quiet track's first drop: ${first.min()}..${first.max()}", first.min() >= 0.9f)

        val mixed = MusicSound()
        play(mixed, { -10.0 }, { -25.0 }, { -25.0 }, 40_000L); mixed.newTrack()
        play(mixed, quiet, { -25.0 }, { -25.0 }, 40_000L); mixed.newTrack()
        assertEquals("one quiet track after a loud one changes nothing", MusicSound.HI_PRIOR, mixed.priorCeiling, 0.01)
        play(mixed, quiet, { -25.0 }, { -25.0 }, 40_000L); mixed.newTrack()
        assertEquals("two in a row do", -26.0, mixed.priorCeiling, 0.3)

        val short = MusicSound()
        play(short, quiet, { -25.0 }, { -25.0 }, 20_000L); short.newTrack()
        assertEquals("a track heard 20 s teaches nothing", MusicSound.HI_PRIOR, short.priorCeiling, 0.01)
        val loud = MusicSound()
        play(loud, { -4.0 }, { -25.0 }, { -25.0 }, 40_000L); loud.newTrack()
        assertEquals("a louder source keeps the prior", MusicSound.HI_PRIOR, loud.priorCeiling, 0.01)
    }

    @Test fun `once the prior is learnt it is trusted - a long quiet intro keeps the verse after it where it was`() {
        fun verseAfter(s: MusicSound): Float {
            val v = ArrayList<Float>()
            play(s, { at -> if (at < 30.0) -27.0 else if (at < 38.0) -18.0 else -10.0 }, { -25.0 }, { -25.0 }, 46_000L) { t, p -> if (t in 31_500L until 38_000L) v += p[0] }
            return v.max()
        }
        val guess = verseAfter(MusicSound())
        val learnt = MusicSound().also { play(it, turns(-20.0, -10.0), { -25.0 }, { -25.0 }, 40_000L); it.newTrack() }
        val trusted = verseAfter(learnt)
        assertEquals("the prior after a loud track", MusicSound.HI_PRIOR, learnt.priorCeiling, 0.01)
        assertTrue("the verse after a 30 s intro: $trusted with the prior learnt ($guess while it is a guess)", trusted <= 0.8f)
    }


    /** An 8-bit capture of 1024 samples, peak-normalised as the Visualizer's: [x] gives sample i's value at [fs]. */
    private fun capture(fs: Int, x: (Int) -> Double): ByteArray {
        val v = DoubleArray(1024) { x(it) }
        val peak = v.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        return ByteArray(1024) { Math.round(v[it] / peak * 0.99 * 128 + 128).toInt().coerceIn(0, 255).toByte() }
    }

    /** The band a tone of [hz] falls in. */
    private fun bandOf(hz: Double) = (0 until MusicSound.BANDS).first { hz >= MusicSound.BAND_EDGES_HZ[it] && hz < MusicSound.BAND_EDGES_HZ[it + 1] }

    @Test fun `the packet carries twelve bands - unknown without a capture, nothing in a silence`() {
        val s = MusicSound()
        val none = s.tick(-4300, null, -25f, 2, true, 48_000, 1_000L)
        assertEquals(MusicSound.PACKET, none.size)
        for (i in 0 until MusicSound.BANDS) assertEquals("band $i with no capture", -1f, none[MusicSound.BAND0 + i], 0f)
        val flat = s.tick(-4300, ByteArray(1024) { 128.toByte() }, -25f, 2, true, 48_000, 1_033L)
        for (i in 0 until MusicSound.BANDS) assertEquals("band $i of a flat capture", 0f, flat[MusicSound.BAND0 + i], 0f)
        val rnd = java.util.Random(1)
        val silent = s.tick(-9000, capture(48_000) { rnd.nextGaussian() }, -25f, 2, true, 48_000, 1_066L)
        for (i in 0 until MusicSound.BANDS) assertEquals("band $i in a silence of the song", 0f, silent[MusicSound.BAND0 + i], 0f)
        val gone = MusicSound().tick(-9600, null, -25f, 2, false, 48_000, 1_000L)
        assertEquals("no level: the bands not known", -1f, gone[MusicSound.BAND0], 0f)
    }

    @Test fun `a band that hits peaks against its own past, a steady one stands at its middle, at 44_1 and 48 kHz`() {
        for (fs in listOf(44_100, 48_000)) {
            val s = MusicSound()
            val rnd = java.util.Random(4)
            // A steady chord (220, 1000 and 5000 Hz) with a little noise, for three seconds…
            fun steady(i: Int, extra: Double = 0.0, extraHz: Double = 0.0) =
                sin(2 * PI * 220.0 * i / fs) + 0.7 * sin(2 * PI * 1_000.0 * i / fs) + 0.5 * sin(2 * PI * 5_000.0 * i / fs) + 0.05 * rnd.nextGaussian() + extra * sin(2 * PI * extraHz * i / fs)
            var t = 1_000L
            var last = FloatArray(0)
            repeat(90) { last = s.tick(-4300, capture(fs) { steady(it) }, -25f, 2, true, fs, t); t += 33L }
            val held = bandOf(1_000.0)
            val said = (0 until MusicSound.BANDS).joinToString { "%.2f".format(last[MusicSound.BAND0 + it]) }
            assertEquals("a band holding a steady note ($said)", MusicSound.BAND_REST.toFloat(), last[MusicSound.BAND0 + held], 0.15f)
            // …then a bass hit at 60 Hz: its band peaks, the steady ones stay about where they were.
            val hit = s.tick(-4000, capture(fs) { steady(it, 4.0, 60.0) }, -25f, 2, true, fs, t)
            val bass = bandOf(60.0)
            val hitSaid = (0 until MusicSound.BANDS).joinToString { "%.2f".format(hit[MusicSound.BAND0 + it]) }
            assertTrue("at $fs Hz the 60 Hz band on a hit: $hitSaid", hit[MusicSound.BAND0 + bass] >= 0.9f)
            assertTrue("the 5 kHz band on it: $hitSaid", hit[MusicSound.BAND0 + bandOf(5_000.0)] <= 0.45f)
        }
    }
}
