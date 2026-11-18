package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class MusicFloorTest {

    /** Every uniform of every frame, folded into one number (FNV-1a over the floats' bits), and a few sums to read. */
    class Print {
        var hash = 1469598103934665603L
        var n = 0
        var base = 0.0; var height = 0.0
        private fun mix(v: Float) { hash = (hash xor java.lang.Float.floatToRawIntBits(v).toLong()) * 1099511628211L }
        fun see(f: MusicBody.Frame) {
            for (v in floatArrayOf(f.base, f.peak, f.kickAmp, f.breath, f.wave, f.wph, f.limit, f.soft, f.g, f.glow, f.tilt, f.shift)) mix(v)
            for (b in f.bands) mix(b)
            base += f.base; height += MusicBody.height(f); n++
        }
        override fun toString() = "$n frames, hash $hash, base summed %.6f, height summed %.6f".format(base, height)
    }

    /** The uniforms of one frame other than the base, in a row (for frame-by-frame comparing). */
    private fun rest(f: MusicBody.Frame) = floatArrayOf(f.peak, f.kickAmp, f.breath, f.wave, f.wph, f.limit, f.soft, f.g, f.glow, f.tilt, f.shift) + f.bands

    /** Frames of the main track (MusicRig: intro, verse, build, two drops, a breakdown, paused from 44 s) at [floor]. */
    private fun main(floor: Int): List<Pair<MusicBody.Frame, FloatArray>> {
        val out = ArrayList<Pair<MusicBody.Frame, FloatArray>>()
        MusicRig.chain(MusicRig.mainTrack, -25.0, 48_000L, playing = { it < MusicRig.PAUSE_S * 1000 }, floor = floor) { s ->
            // A copy of what is needed: the Body hands out the same Frame each time.
            val f = MusicBody.Frame().also { c ->
                c.base = s.frame.base; c.peak = s.frame.peak; c.kickAmp = s.frame.kickAmp; c.breath = s.frame.breath; c.wave = s.frame.wave
                c.wph = s.frame.wph; c.limit = s.frame.limit; c.soft = s.frame.soft; c.g = s.frame.g; c.glow = s.frame.glow
                c.tilt = s.frame.tilt; c.shift = s.frame.shift; s.frame.bands.copyInto(c.bands)
            }
            // And today's base worked out from the body's own course (a, e), for the comparison at 0.
            out += f to floatArrayOf(MusicBody.PAGE_H - MusicBody.REST_PX - s.body.a * (MusicBody.LIFT0 + MusicBody.RISE * s.body.e), s.t.toFloat())
        }
        return out
    }

    private val seen = FloatArray(171) { 296f + it * 4f }

    /** The default (PanelConfig.MUSIC_FLOOR). */
    private val dflt = dev.kb896.aiusage.data.PanelConfig.MUSIC_FLOOR

    /**
     * How much the song moves the body at [floor], playing the main track (0 to 44 s), over what he sees: the surface's
     * spread (highest column minus lowest) on average, the columns' mean move a frame, and how much of the kick shows at
     * the middle (x 636, the kick's surface lift there over its own size, weighted by its size). And what of the page it
     * reaches: the frames with the title's middle row under the surface somewhere across the text, and the most light at
     * the cover's foot (y 257, in the worst colours: what shows in its rounded corners).
     */
    private class Moves(val spread: Double, val motion: Double, val kick: Double, val title: Int, val cover: Float) {
        override fun toString() = "spread %.1f px, motion %.2f px a frame, kick shown %.2f, the title in it in %d frames, light at the cover's foot %.4f".format(spread, motion, kick, title, cover)
    }

    private fun moves(floor: Int): Moves {
        var spread = 0.0; var n = 0; var motion = 0.0; var m = 0; var kickShown = 0.0; var kickOwn = 0.0
        var title = 0; var cover = 0f
        var prev: FloatArray? = null
        MusicRig.chain(MusicRig.mainTrack, -25.0, 44_000L, floor = floor) { s ->
            val f = s.frame
            val ys = FloatArray(seen.size) { MusicBody.surface(f, seen[it]) }
            spread += ys.max() - ys.min(); n++
            prev?.let { p -> motion += ys.indices.sumOf { abs(ys[it] - p[it]).toDouble() } / ys.size; m++ }
            prev = ys
            if (f.kickAmp > 1f) {
                val k = f.kickAmp
                val with = MusicBody.surface(f, MusicBody.DOME_X)
                f.kickAmp = 0f
                kickShown += MusicBody.surface(f, MusicBody.DOME_X) - with; kickOwn += k
                f.kickAmp = k
            }
            if ((0..62).any { MusicBody.surface(f, MusicBody.DOME_X - MusicFace.TEXT_W / 2f + it * 8f) < MusicFace.TITLE_CY }) title++
            MusicRig.worstColours(f)
            var x = MusicFace.ART_X
            while (x <= MusicFace.ART_X + MusicFace.ART) { cover = maxOf(cover, MusicBody.light(f, x, MusicFace.ART_Y + MusicFace.ART)); x += 8f }
        }
        return Moves(spread / n, motion / m, kickShown / kickOwn, title, cover)
    }

    private fun source(path: String): String =
        listOf("src/main/java/dev/kb896/aiusage/$path", "app/src/main/java/dev/kb896/aiusage/$path").map(::File).first { it.isFile }.readText()

    // -- 0: exactly as before --

    @Test fun `at 0 every frame is the build's before the setting, to the bit - a song, no level, a page away and back`() {
        // Recorded from the build before the setting (022d9c5) with these same three runs: every uniform of every frame.
        val a = Print()
        MusicRig.chain(MusicRig.mainTrack, -25.0, 48_000L, playing = { it < MusicRig.PAUSE_S * 1000 }, floor = 0) { a.see(it.frame) }
        // No level at all (the switch off, as the settings' preview): playing 20 s, then paused.
        val b = Print(); val quiet = MusicFace.Body().also { it.floor = 0 }
        var t = 1_000L
        while (t < 31_000L) { b.see(quiet.step(t, t < 21_000L)); t += 16L }
        // Packets, and the page away from 20 s to 25 s (heard meanwhile), back with the present, paused at 35 s.
        val c = Print(); val away = MusicFace.Body().also { it.floor = 30; it.floor = 0 }
        t = 1_000L
        while (t < 41_000L) {
            if (t % 33L < 16L) away.hear(floatArrayOf(if (t > 12_000L) 0.9f else 0.2f, if (t % 500L < 33L) 0.8f else 0f, 1f, 0f, 0f, 0f), t)
            if (t in 20_000L until 25_000L) { t += 16L; continue }
            c.see(away.step(t, t < 35_000L, if (t >= 20_000L) 1 else 0)); t += 16L
        }
        println("at 0: the song $a; no level $b; away and back $c")
        assertEquals("the song: $a", -1115345187815404414L, a.hash)
        assertEquals("no level: $b", -5022621519690490933L, b.hash)
        assertEquals("away and back: $c", 5298236410865822626L, c.hash)
        assertEquals(2881, a.n); assertEquals(1875, b.n); assertEquals(2188, c.n)
    }

    @Test fun `at 0 the base is where the course puts it, the same float as before`() {
        for ((f, today) in main(0)) assertEquals("at ${today[1]} ms", today[0], f.base, 0f)
    }

    // -- the rule, at any value --

    @Test fun `only the base changes - every other uniform is the same at 0, the default and 100, frame by frame`() {
        val zero = main(0); val atDflt = main(dflt); val full = main(100)
        assertEquals(zero.size, atDflt.size); assertEquals(zero.size, full.size)
        for (i in zero.indices) {
            val r = rest(zero[i].first)
            assertTrue("$dflt, at ${zero[i].second[1]} ms", r.contentEquals(rest(atDflt[i].first)))
            assertTrue("100, at ${zero[i].second[1]} ms", r.contentEquals(rest(full[i].first)))
        }
    }

    @Test fun `at the default the base never stands under 98 px, quiet, loud or paused, and stands on it when paused`() {
        assertEquals(30, dflt)
        val floorPx = MusicBody.floorPx(dflt.toFloat())
        assertEquals(98f, floorPx, 1e-3f)
        val floorY = MusicBody.PAGE_H - floorPx
        val zero = main(0); val atDflt = main(dflt)
        var held = 0; var untouched = 0; var knee = 0
        for (i in zero.indices) {
            val b0 = zero[i].first.base; val b = atDflt[i].first.base; val at = zero[i].second[1]
            assertTrue("at $at ms the base stands ${MusicBody.PAGE_H - b} px", MusicBody.PAGE_H - b >= floorPx - 1e-3f)
            when {
                // A base well over the floor (a loud part): untouched, to the bit.
                b0 <= floorY - MusicBody.FLOOR_KNEE -> { assertEquals("at $at ms", b0, b, 0f); untouched++ }
                // Well under it: on the floor.
                b0 >= floorY + MusicBody.FLOOR_KNEE -> { assertEquals("at $at ms", floorY, b, 1e-3f); held++ }
                // Near it, eased onto it: never more than a quarter of the knee over the plain higher of the two.
                else -> { assertTrue("at $at ms: $b against ${minOf(b0, floorY)}", b <= minOf(b0, floorY) && b >= minOf(b0, floorY) - MusicBody.FLOOR_KNEE / 4f - 1e-3f); knee++ }
            }
        }
        println("at $dflt, of ${zero.size} frames: $held on the floor, $untouched over it untouched, $knee in the knee")
        assertTrue("some loud frames stand over the floor untouched: $untouched", untouched >= 100)
        // Paused (from 3 s after the pause on): the calm body sits on the floor, its slow ripple about it.
        val paused = atDflt.filter { it.second[1] >= (MusicRig.PAUSE_S + 3) * 1000 }
        assertTrue(paused.isNotEmpty())
        for ((f, _) in paused) {
            assertEquals(floorPx, MusicBody.PAGE_H - f.base, 1e-3f)
            val mean = seen.map { MusicBody.PAGE_H - MusicBody.surface(f, it) }.average().toFloat()
            assertEquals(floorPx, mean, MusicBody.WAVE_REST + 0.5f)
        }
        // With no level at all, as the settings' preview plays it: never under the floor either.
        val body = MusicFace.Body().also { it.floor = dflt }
        var t = 1_000L
        while (t < 31_000L) { val f = body.step(t, t < 21_000L); assertTrue("no level, $t", MusicBody.PAGE_H - f.base >= floorPx - 1e-3f); t += 16L }
        assertEquals("paused with no level: on the floor", floorPx, MusicBody.PAGE_H - body.frame.base, 1e-3f)
    }

    @Test fun `the peaks, the kick, the breath and the wave stand on the raised base as they did on today's`() {
        val zero = main(0); val atDflt = main(dflt)
        var compared = 0
        for (i in zero.indices) {
            val f0 = zero[i].first; val f = atDflt[i].first
            for (x in seen) {
                val s0 = MusicBody.surface(f0, x); val s = MusicBody.surface(f, x)
                // Where the soft ceiling holds neither back, the surface over the base is the same to the hundredth.
                if (s0 - f0.limit >= f0.soft && s - f.limit >= f.soft) {
                    assertEquals("at ${zero[i].second[1]} ms, x $x", f0.base - s0, f.base - s, 0.01f); compared++
                }
            }
        }
        assertTrue("columns compared: $compared", compared >= 300_000)
    }

    @Test fun `at 100 the base stands at 140 px, and the song still moves the body as it does at 0`() {
        assertEquals(140f, MusicBody.floorPx(100f), 1e-3f)
        val floorY = MusicBody.PAGE_H - MusicBody.FLOOR_ROOM
        for ((f, today) in main(100)) assertTrue("at ${today[1]} ms: base y ${f.base}", f.base <= floorY + 1e-3f)
        // The top of the slider must not freeze it (the first build's 100 put the base at the ceiling: about a tenth of
        // the motion, the kick hardly seen, the title always in the body). The spread of the surface, its motion and the
        // kick at the middle are the 0 run's within a few hundredths at the top and at the default, and the body reaches
        // the title and the cover's foot about as it does at 0.
        val zero = moves(0); val top = moves(100); val mid = moves(dflt)
        println("how the song moves the body: at 0 $zero; at $dflt $mid; at 100 $top")
        for ((name, at) in listOf("100" to top, "the default" to mid)) {
            assertTrue("$name: $at against $zero", at.spread >= 0.97 * zero.spread)
            assertTrue("$name: $at against $zero", at.motion >= 0.97 * zero.motion)
            assertTrue("$name: $at against $zero", at.kick >= 0.97 * zero.kick)
            assertTrue("$name: $at against $zero", at.title <= 1.25 * zero.title)
            assertTrue("$name: $at against $zero", at.cover <= 1.1f * zero.cover)
        }
        // A full course and a full kick on every packet for a minute, at 100 and at 0: never over the ceiling, and the
        // light at the cover's foot within a few thousandths of 0's.
        fun loud(floor: Int): Pair<Float, Float> {
            val body = MusicFace.Body().also { it.floor = floor }
            var highest = 1e9f; var behindCover = 0f
            var t = 1_000L
            while (t < 61_000L) {
                if (t % 33L < 17L) body.hear(floatArrayOf(1f, 1f, 1f, 0f, 0f, 0f), t)
                val f = body.step(t, true)
                MusicRig.worstColours(f)
                for (x in MusicBodyTest.XS) highest = minOf(highest, MusicBody.surface(f, x))
                // The cover's foot (y 257), across its width.
                var x = MusicFace.ART_X
                while (x <= MusicFace.ART_X + MusicFace.ART) { behindCover = maxOf(behindCover, MusicBody.light(f, x, MusicFace.ART_Y + MusicFace.ART)); x += 8f }
                t += 17L
            }
            return highest to behindCover
        }
        val (top100, cover100) = loud(100); val (top0, cover0) = loud(0)
        println("loud for a minute: highest surface y %.1f at 100, %.1f at 0; light at the cover's foot %.4f at 100, %.4f at 0".format(top100, top0, cover100, cover0))
        assertTrue("the highest the surface came at 100: y $top100", top100 >= MusicBody.Y_LIMIT)
        assertTrue("the ceiling is under the cover's foot", MusicBody.Y_LIMIT > MusicFace.ART_Y + MusicFace.ART)
        assertTrue("light at the cover's foot: $cover100 at 100, $cover0 at 0", cover100 <= cover0 + 0.005f)
        // Quiet and paused at 100: the cover's foot as dark as at 0 (the first build's 100 left a faint glow there).
        val still = MusicFace.Body().also { it.floor = 100 }
        var t = 1_000L; while (t < 9_000L) { still.step(t, false); t += 16L }
        MusicRig.worstColours(still.frame)
        var glow = 0f; var x = MusicFace.ART_X
        while (x <= MusicFace.ART_X + MusicFace.ART) { glow = maxOf(glow, MusicBody.light(still.frame, x, MusicFace.ART_Y + MusicFace.ART)); x += 8f }
        println("paused at 100: light at the cover's foot %.5f".format(glow))
        assertTrue("paused at 100: $glow", glow <= 0.002f)
    }

    // -- motion: no jump anywhere, a new value glided to --

    @Test fun `at the default and 100 the song moves as at 0 - no column jumps, one body edge to edge, never over the ceiling`() {
        for (floor in listOf(dflt, 100)) {
            val r = MusicBodyTest.Run("the song at floor $floor")
            MusicRig.chain(MusicRig.mainTrack, -25.0, 48_000L, playing = { it < MusicRig.PAUSE_S * 1000 }, floor = floor) { r.see(it) }
            println(r)
            assertTrue(r.toString(), r.worstStep <= 1f && r.worstLight <= 0.08f)
            assertTrue(r.toString(), r.lowestS >= MusicBody.Y_LIMIT)
            assertTrue(r.toString(), r.worstDip <= 0.05f && r.worstFoot >= 0.40f && r.worstValley >= MusicBodyTest.FLOOR_PX)
        }
    }

    @Test fun `the base comes onto the floor eased, not with a stop - no harder a turn than the hardest today`() {
        fun worstTurn(floor: Int): Float {
            val bases = main(floor).map { it.first.base }
            var worst = 0f
            for (i in 1 until bases.size - 1) worst = maxOf(worst, abs(bases[i + 1] - 2 * bases[i] + bases[i - 1]))
            return worst
        }
        val today = worstTurn(0)
        // Where the floor meets the base: paused (46 px) at about 2, the playing base (70 to 140 px) from about 10 to 100.
        val floors = listOf(2, 5, 10, 20, dflt, 50, 85, 100)
        val said = "the base's hardest turn: %.3f px a frame squared at 0, ".format(today) + floors.joinToString { "%.3f at %d".format(worstTurn(it), it) }
        println(said)
        for (floor in floors) assertTrue(said, worstTurn(floor) <= today)
    }

    @Test fun `a new value is glided to in about half a second, and a first frame or a page back starts on it`() {
        val body = MusicFace.Body()
        val r = MusicBodyTest.Run("the floor changed while playing")
        var t = 1_000L
        fun play(until: Long, each: (MusicBody.Frame) -> Unit = {}) {
            while (t < until) {
                if (t % 33L < 16L) body.hear(floatArrayOf(0.3f, 0f, 1f, 0f, 0f, 0f), t)
                val f = body.step(t, true); r.see(MusicRig.Shot(t, body, f), check = false); each(f); t += 16L
            }
        }
        play(6_000L)
        val before = MusicBody.PAGE_H - body.frame.base
        // 0 to 100 at once (the slider dragged end to end): no column jumps, 90 % of the way in about half a second, and
        // the body says it is gliding until it is on it (the shell draws a page standing still for as long as it says so).
        body.floor = 100
        val target = MusicBody.PAGE_H - MusicBody.FLOOR_ROOM
        val way = MusicBody.FLOOR_ROOM - before
        val set = t; var most = -1L; var done = -1L
        play(9_000L) { f ->
            val left = abs(f.base - target)
            if (most < 0 && left <= 0.1f * way) most = t - set
            if (done < 0 && !body.floorGliding) { done = t - set; assertEquals("on it when the glide is done", target, f.base, MusicFace.Body.FLOOR_DONE_PX + 1e-3f) }
        }
        println("0 to 100 while playing: from $before px, 90 %% of the way after $most ms, the glide done after $done ms; $r".format())
        assertTrue("$most ms", most in 320L..520L)
        assertTrue("$done ms", done in 600L..1_500L)
        assertTrue(r.toString(), r.worstStep <= 1f)
        // Its first move is a glide, not the whole way: after one frame it has gone less than a tenth of it.
        val g = MusicFace.Body().also { it.floor = 0 }
        var u = 1_000L
        while (u < 3_000L) { g.step(u, false); u += 16L }
        assertFalse("settled: nothing to glide", g.floorGliding)
        g.floor = dflt
        assertTrue("a change is on its way", g.floorGliding)
        val rest = MusicBody.REST_PX
        assertTrue("after one frame: ${MusicBody.PAGE_H - g.step(u, false).base} px", MusicBody.PAGE_H - g.frame.base < rest + 0.1f * (MusicBody.floorPx(dflt.toFloat()) - rest))
        // A body's first frame, and the first after the page was away, stand on the floor at once.
        val fresh = MusicFace.Body().also { it.floor = dflt }
        assertFalse("no frame yet: nothing to draw for it", fresh.floorGliding)
        assertEquals(MusicBody.floorPx(dflt.toFloat()), MusicBody.PAGE_H - fresh.step(1_000L, false).base, 1e-3f)
        assertFalse(fresh.floorGliding)
        g.floor = 90
        assertEquals(MusicBody.floorPx(90f), MusicBody.PAGE_H - g.step(u + 10_000L, false, away = 1).base, 1e-3f)
        assertFalse(g.floorGliding)
    }

    @Test fun `a change while the page is covered is on it when uncovered, and a glide whose frames were held up is drawn to its end`() {
        // Paused and settled at the default: the back stands still, nothing is drawn.
        fun still(): Pair<MusicFace.Body, Long> {
            val b = MusicFace.Body().also { it.floor = dflt }
            var t = 1_000L
            while (t < 6_000L) { b.step(t, false); t += 16L }
            assertFalse(b.floorGliding)
            return b to t
        }
        val want = MusicBody.floorPx(90f)
        // 30 to 90 from the front while the notes lie over the music page: none of its frames is drawn meanwhile, and the
        // shell's tick that finds it covered stands the floor on its value. Uncovered 3 s later, its one frame drawn at
        // once is on it, and nothing is left to draw for it.
        val (covered, t0) = still()
        covered.floor = 90
        covered.floorUnseen()
        assertFalse(covered.floorGliding)
        assertEquals(want, MusicBody.PAGE_H - covered.step(t0 + 3_000L, false).base, 1e-3f)
        assertFalse(covered.floorGliding)
        // Without that (the first build): one frame after the gap moves the glide by one capped step only, part of the
        // way; with a fixed time to draw for, the frames had stopped there. The body says it is still on its way, so the
        // shell draws on until it is on it.
        val (held, t1) = still()
        held.floor = 90
        var u = t1 + 3_000L
        val first = MusicBody.PAGE_H - held.step(u, false).base
        assertTrue("part of the way after one late frame: $first px of $want", first < want - 10f)
        assertTrue(held.floorGliding)
        var frames = 0
        while (held.floorGliding) { u += 16L; held.step(u, false); frames++; assertTrue("$frames frames", frames < 120) }
        println("a glide held up 3 s: $first px after its first frame, on $want px after $frames more")
        assertEquals(want, MusicBody.PAGE_H - held.frame.base, MusicFace.Body.FLOOR_DONE_PX + 1e-3f)
    }

    @Test fun `the shell draws a still page until the glide is done, and stands a covered page on its floor`() {
        val service = source("rear/RearUserService.kt")
        val frame = service.substringAfter("private fun musicFrame(").substringBefore("\n    private var musicFrameAt")
        // Covered: the floor stood on at once; no fixed time after a change to look for it.
        val covered = frame.substringAfter("if (!liveSeen()) {").substringBefore("return\n")
        assertTrue(covered, covered.contains("musicBody.floorUnseen()"))
        assertFalse(covered, covered.contains("musicFloorAt"))
        // Seen: drawn on while the body says it glides (bounded by the change's time, never by it alone).
        val moving = frame.substringAfter("val moving =").substringBefore("\n        if (moving)")
        assertTrue(moving, moving.contains("musicBody.floorGliding && now - musicFloorAt < MUSIC_FLOOR_MS"))
        val cap = Regex("""MUSIC_FLOOR_MS = ([0-9_]+)L""").find(service)!!.groupValues[1].replace("_", "").toLong()
        assertTrue("the bound is well over the glide's time: $cap ms", cap >= 3_000L)
    }

    @Test fun `the value is kept within 0 to 100, and 0, 30 and 100 are 0, 98 and 140 px, rising all the way`() {
        val body = MusicFace.Body()
        body.floor = 150; assertEquals(100, body.floor)
        body.floor = -5; assertEquals(0, body.floor)
        assertEquals(0f, MusicBody.floorPx(0f), 0f)
        assertEquals(98f, MusicBody.floorPx(30f), 1e-3f)
        for (v in 1..100) assertTrue("$v", MusicBody.floorPx(v.toFloat()) > MusicBody.floorPx(v - 1f))
        assertEquals(140f, MusicBody.floorPx(100f), 1e-3f)
        assertEquals("the base at the song's loudest", MusicBody.REST_PX + MusicBody.LIFT0 + MusicBody.RISE, MusicBody.FLOOR_ROOM, 0f)
        // What he sees paused, settled: the calm surface's mean height over what he sees, at each.
        val said = listOf(0, 10, dflt, 70, 100).joinToString { v ->
            val b = MusicFace.Body().also { it.floor = v }
            var t = 1_000L; while (t < 9_000L) { b.step(t, false); t += 16L }
            "%d: base %.1f px, surface %.1f px".format(v, MusicBody.PAGE_H - b.frame.base, seen.map { MusicBody.PAGE_H - MusicBody.surface(b.frame, it) }.average())
        }
        println("paused and settled, over the foot: $said")
    }
}
