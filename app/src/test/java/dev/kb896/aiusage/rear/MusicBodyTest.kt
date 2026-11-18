package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MusicBodyTest {

    /** What a run of the chain did, frame by frame. */
    class Run(val name: String) {
        val t = ArrayList<Long>()
        val height = ArrayList<Float>()
        /** The kick's lift at the middle, px. */
        val kick = ArrayList<Float>()
        /** The body's mean height over what he sees: its mass, without the peaks' to and fro. */
        val mass = ArrayList<Float>()
        val light = ArrayList<Float>()
        val packets = ArrayList<Pair<Long, FloatArray>>()
        var worstStep = 0f; var worstStepAt = 0L
        var worstLight = 0f
        var lowestS = 1e9f
        var worstDip = 0f; var worstDipAt = 0L
        var worstValley = 1e9f; var valleyAt = 0L
        val crestCount = ArrayList<Pair<Long, Int>>()
        val spread = ArrayList<Float>()
        var worstFoot = 1f; var footAt = 0L
        /** The largest move of any column in a frame, in px per 16.7 ms. */
        var worstPx = 0f
        private var prevS: FloatArray? = null
        private var prevG = Float.NaN
        private var prevT = 0L
        private var n = 0

        fun see(shot: MusicRig.Shot, check: Boolean = true) {
            val f = shot.frame
            MusicRig.worstColours(f)
            t += shot.t; height += MusicBody.height(f); light += f.g
            kick += f.kickAmp
            mass += SEEN.map { MusicBody.PAGE_H - MusicBody.surface(f, it) }.average().toFloat()
            val low = MusicBody.lowest(f)
            if (low < worstValley) { worstValley = low; valleyAt = shot.t }
            spread += MusicBody.height(f) - low
            val s = FloatArray(XS.size) { MusicBody.surface(f, XS[it]) }
            lowestS = minOf(lowestS, s.min())
            prevS?.let { p ->
                val dt = (shot.t - prevT).coerceIn(1L, 50L)
                val bound = STEP_PX * dt / 16.7f
                val step = s.indices.maxOf { abs(s[it] - p[it]) }
                worstPx = maxOf(worstPx, step * 16.7f / dt)
                if (step / bound > worstStep) { worstStep = step / bound; worstStepAt = shot.t }
                worstLight = maxOf(worstLight, abs(f.g - prevG) / (dt / 16.7f))
            }
            prevS = s; prevG = f.g; prevT = shot.t
            if (check && n++ % 4 == 0) {
                val dip = dip(f)
                if (dip > worstDip) { worstDip = dip; worstDipAt = shot.t }
                crestCount += shot.t to crests(f)
                val foot = foot(f)
                if (foot < worstFoot) { worstFoot = foot; footAt = shot.t }
            }
        }

        fun mean(from: Double, to: Double): Float = t.indices.filter { t[it] >= from * 1000 && t[it] < to * 1000 }.map { height[it] }.average().toFloat()
        fun at(s: Double): Int = t.indexOfFirst { it >= s * 1000 }

        /** Crests of the frames checked between [from] and [to] s. */
        fun crestsIn(from: Double, to: Double) = crestCount.filter { it.first >= from * 1000 && it.first < to * 1000 }.map { it.second }
        fun spreadIn(from: Double, to: Double): Float = t.indices.filter { t[it] >= from * 1000 && t[it] < to * 1000 }.map { spread[it] }.average().toFloat()

        /** The light's largest step, per 16.7 ms frame (the bound is 0.08). */
        override fun toString() = "$name: worst step ${"%.2f".format(worstStep)} of the bound (${"%.1f".format(worstPx)} px a frame) at ${worstStepAt} ms, light step ${"%.3f".format(worstLight)}, " +
            "lowest s ${"%.1f".format(lowestS)}, deepest valley ${"%.1f".format(worstValley)} px at $valleyAt ms, worst dip under it ${"%.3f".format(worstDip)} at $worstDipAt ms, foot ${"%.2f".format(worstFoot)} at $footAt ms"
    }

    companion object {
        const val STEP_PX = 60f
        /** The deepest a valley between peaks ever comes: one body over the whole width. */
        const val FLOOR_PX = 40f
        /** Columns over the whole panel, for the steps and the ceiling. */
        val XS = FloatArray(245) { it * 4f }
        /** Columns he sees, for the one-body measures. */
        private val SEEN = FloatArray(171) { 296f + it * 4f }

        /**
         * Along every row through the body UNDER ITS DEEPEST VALLEY (the peaks are over it, and between two of them a row
         * through the peaks crosses the dark between them, as it should): the deepest dip between two maxima, as a part of
         * the lower one. That is the one mass the peaks stand on.
         */
        fun dip(f: MusicBody.Frame): Float {
            val under = MusicBody.PAGE_H - MusicBody.lowest(f) + 20f
            val rows = (0 until 149).map { r -> 2f + r * 4f }.filter { it >= under }.map { y -> FloatArray(SEEN.size) { MusicBody.light(f, SEEN[it], y) } }
            if (rows.isEmpty()) return 0f
            // The light box-smoothed along the row over 3 samples (12 px).
            val smooth = rows.map { row -> FloatArray(row.size) { i -> (row[maxOf(0, i - 1)] + row[i] + row[minOf(row.size - 1, i + 1)]) / 3f } }
            val peak = smooth.maxOf { it.max() }
            var worst = 0f
            for (row in smooth) {
                if (row.max() < 0.08f * peak) continue
                val maxima = (row.indices).filter { i -> (i == 0 || row[i] > row[i - 1]) && (i == row.size - 1 || row[i] >= row[i + 1]) }
                for (k in 0 until maxima.size - 1) {
                    val a = maxima[k]; val b = maxima[k + 1]
                    val low = (a..b).minOf { row[it] }
                    worst = maxOf(worst, 1f - low / maxOf(minOf(row[a], row[b]), 1e-9f))
                }
            }
            return worst
        }

        /** The surface's crests over what he sees with a prominence of [min] px or more. */
        fun crests(f: MusicBody.Frame, min: Float = 12f): Int {
            val v = FloatArray(341) { -MusicBody.surface(f, 296f + it * 2f) }
            var count = 0
            for (i in v.indices) {
                if (!((i == 0 || v[i] > v[i - 1]) && (i == v.size - 1 || v[i] >= v[i + 1]))) continue
                var leftMin = v[i]; var j = i - 1
                while (j >= 0 && v[j] <= v[i]) { leftMin = minOf(leftMin, v[j]); j-- }
                val leftOpen = j < 0
                var rightMin = v[i]; j = i + 1
                while (j < v.size && v[j] <= v[i]) { rightMin = minOf(rightMin, v[j]); j++ }
                val rightOpen = j >= v.size
                // As scipy's prominence: the base on each side runs to a higher point or to the end.
                val base = maxOf(if (leftOpen && i == 0) v[i] else leftMin, if (rightOpen && i == v.size - 1) v[i] else rightMin)
                if (v[i] - base >= min) count++
            }
            return count
        }

        /** The foot row (y 590): its least light over the whole width as a part of its most. */
        fun foot(f: MusicBody.Frame): Float {
            val row = FloatArray(245) { MusicBody.light(f, it * 4f, 590f) }
            return row.min() / maxOf(row.max(), 1e-9f)
        }

        fun run(name: String, track: DoubleArray, volDb: Double, ms: Long, check: Boolean = true, playing: (Long) -> Boolean = { true }, sends: (Long) -> Boolean = { true }): Run {
            val r = Run(name)
            MusicRig.chain(track, volDb, ms, playing, sends, packet = { t, p -> r.packets += t to p }) { r.see(it, check) }
            return r
        }

        val main: Run by lazy { run("the track at -25 dB", MusicRig.mainTrack, -25.0, 48_000L, playing = { it < MusicRig.PAUSE_S * 1000 }) }
    }

    // -- 8.1 and 8.2: one body, one mass from edge to edge --

    @Test fun `one body - no valley between the peaks comes under the floor, and under the valleys no row dips`() {
        val r = main
        println(r)
        assertTrue("the deepest valley: ${r.worstValley} px over the foot at ${r.valleyAt} ms", r.worstValley >= FLOOR_PX)
        assertTrue("row dip under the valleys ${r.worstDip} at ${r.worstDipAt} ms", r.worstDip <= 0.05f)
    }

    @Test fun `it has peaks - the drop's surface is not a bar, and the quiet parts still have shape`() {
        val r = main
        val said = StringBuilder()
        for ((part, range) in MusicRig.SECTIONS) {
            if (part == "paused") continue
            val (a, b) = range
            val c = r.crestsIn(a + 0.5, b)
            said.append("%s: crests %.1f per frame (%d%% with 2 or more), spread %.0f px; ".format(part, c.average(), 100 * c.count { it >= 2 } / maxOf(1, c.size), r.spreadIn(a + 0.5, b)))
        }
        println(said)
        // A drop: two or more peaks (12 px over the valleys beside them) in most frames, 60 px between its peaks and valleys.
        for (part in listOf("drop", "drop 2")) {
            val (a, b) = MusicRig.SECTIONS[part]!!
            val c = r.crestsIn(a + 0.5, b)
            assertTrue("$part: $said", c.count { it >= 2 } >= 0.6 * c.size)
            assertTrue("$part: $said", r.spreadIn(a + 0.5, b) >= 60f)
        }
        // The verse: peaks too, smaller. The quiet parts are calm, but never a flat line.
        MusicRig.SECTIONS["verse"]!!.let { (a, b) -> val c = r.crestsIn(a + 0.5, b); assertTrue("verse: $said", c.count { it >= 1 } >= 0.8 * c.size && r.spreadIn(a + 0.5, b) >= 30f) }
        for (part in listOf("intro", "breakdown")) {
            val (a, b) = MusicRig.SECTIONS[part]!!
            assertTrue("$part: $said", r.spreadIn(a + 0.5, b) >= 24f)
        }
    }

    @Test fun `the drop's peaks reach about half the page, the quiet parts stand about a sixth`() {
        val r = main
        val (a, b) = MusicRig.SECTIONS["drop"]!!
        val tops = r.t.indices.filter { r.t[it] >= (a + 0.5) * 1000 && r.t[it] < b * 1000 }.map { r.height[it] / MusicBody.PAGE_H }
        // Each beat (0.5 s) of the drop: does a peak reach 45 % of the page in it?
        val beats = tops.chunked(30).filter { it.size == 30 }
        val reach = beats.count { beat -> beat.max() >= 0.45f } * 100 / beats.size
        val intro = MusicRig.SECTIONS["intro"]!!.let { (x, y) -> r.mean(x + 0.5, y) } / MusicBody.PAGE_H
        val breakdown = MusicRig.SECTIONS["breakdown"]!!.let { (x, y) -> r.mean(x + 0.5, y) } / MusicBody.PAGE_H
        val said = "drop: tops %.2f..%.2f of the page, a peak at 0.45 or more in %d%% of its beats; intro's mean top %.2f, breakdown's %.2f".format(tops.min(), tops.max(), reach, intro, breakdown)
        println(said)
        assertTrue(said, reach >= 75)
        assertTrue(said, tops.max() <= 0.55f)
        assertTrue(said, intro in 0.11f..0.22f && breakdown in 0.11f..0.22f)
    }

    @Test fun `one mass from edge to edge - the foot row is lit everywhere`() {
        val r = main
        assertTrue("the foot's least light was ${r.worstFoot} of its most, at ${r.footAt} ms", r.worstFoot >= 0.40f)
    }

    /** How far the surface moved (the largest column, summed over frames) between [from] and [to] s. */
    private fun moves(r: Run, from: Double, to: Double): Float {
        val i0 = r.at(from); val i1 = r.at(to)
        var sum = 0f
        for (i in i0 until i1 - 1) sum += abs(r.spread[i + 1] - r.spread[i]) + abs(r.height[i + 1] - r.height[i])
        return sum
    }

    // -- 8.3: no jumps --

    @Test fun `no jumps through the whole track - no column moves more than 60 px a frame, the light not more than 0_08`() {
        val r = main
        assertTrue(r.toString(), r.worstStep <= 1f)
        assertTrue(r.toString(), r.worstLight <= 0.08f)
    }

    @Test fun `no jumps for packets made to jump - the course from 0 to 1 with a full kick and back, at random gaps`() {
        val body = MusicFace.Body()
        val r = Run("made to jump")
        val rnd = java.util.Random(3)
        var next = 1_000L; var up = false
        var t = 1_000L
        while (t < 61_000L) {
            while (next <= t) { up = !up; body.hear(floatArrayOf(if (up) 1f else 0f, if (up) 1f else 0f, 1f, 0f, 0f, 0f), next); next += 33L + rnd.nextInt(568) }
            r.see(MusicRig.Shot(t, body, body.step(t, true)), check = false)
            t += 17L
        }
        assertTrue(r.toString(), r.worstStep <= 1f)
        assertTrue(r.toString(), r.worstLight <= 0.08f)
        assertTrue("never above the ceiling: ${r.lowestS}", r.lowestS >= MusicBody.Y_LIMIT)
    }

    @Test fun `frames at uneven gaps keep the same bounds, and a gap of two seconds counts as 50 ms`() {
        val body = MusicFace.Body()
        val r = Run("uneven frames")
        val rnd = java.util.Random(9)
        var t = 1_000L; var sent = 1_000L
        var gaps = 0
        while (t < 40_000L) {
            while (sent <= t) { val beat = (sent / 500L) % 2L == 0L && sent % 500L < 33L; body.hear(floatArrayOf(if (sent > 10_000L) 1f else 0.1f, if (beat) 1f else 0f, 1f, 0f, 0f, 0f), sent); sent += 33L }
            r.see(MusicRig.Shot(t, body, body.step(t, true)), check = false)
            val gap = if (t in 20_000L..20_050L && gaps == 0) { gaps++; 2_000L } else 5L + rnd.nextInt(46)
            t += gap
        }
        assertTrue(r.toString(), r.worstStep <= 1f)
        assertTrue(r.toString(), r.worstLight <= 0.08f)
    }

    // -- 8.4: a kick is seen --

    @Test fun `one kick in a steady drop lifts the top at least 12 px within 120 ms and is gone within 600 ms`() {
        fun play(kickAt: Long?): List<Float> {
            val body = MusicFace.Body()
            val out = ArrayList<Float>()
            var t = 1_000L; var sent = 1_000L
            while (t < 13_000L) {
                while (sent <= t) { body.hear(floatArrayOf(1f, if (kickAt != null && sent == kickAt) 0.8f else 0f, 1f, 0f, 0f, 0f), sent); sent += 33L }
                out += MusicBody.height(body.step(t, true)); t += 16L
            }
            return out
        }
        val kickAt = 1_000L + 33L * 300L          // ten seconds in, on a packet
        val with = play(kickAt); val without = play(null)
        val i0 = ((kickAt - 1_000L) / 16L).toInt()
        val lift = (i0..i0 + 120 / 16).maxOf { with[it] - without[it] }
        val after = abs(with[i0 + 600 / 16] - without[i0 + 600 / 16])
        assertTrue("the kick lifted the top by $lift px within 120 ms", lift >= 12f)
        assertTrue("600 ms on it is $after px from where it would be", after <= 3f)
    }

    @Test fun `through the whole chain at his volume three in four of the drop's kicks lift the middle by 8 px`() {
        // The kick's own lift at the middle (x 636, where its splash is): the highest point is a peak's now, which comes
        // and goes with its band, so the kick is read where it acts.
        val r = main
        val lifts = MusicRig.DROP_KICKS.map { k ->
            val i0 = r.at(k)
            val before = r.kick[i0]
            (i0..r.at(k + 0.120)).maxOf { r.kick[it] } - before
        }
        val seen = lifts.count { it >= 8f }
        println("drop kicks lifting the middle 8 px or more within 120 ms: $seen of ${lifts.size}")
        assertTrue("kicks lifting 8 px or more: $seen of ${lifts.size} (${lifts.joinToString { "%.0f".format(it) }})", seen >= 0.75 * lifts.size)
    }

    @Test fun `in the intro and the breakdown hardly anything is taken for a kick`() {
        val r = main
        for (part in listOf("intro", "breakdown")) {
            val (a, b) = MusicRig.SECTIONS[part]!!
            val kicks = r.packets.count { (t, p) -> t >= a * 1000 && t < b * 1000 && p[1] >= 0.3f }
            val per10 = kicks * 10.0 / (b - a)
            assertTrue("$part: $kicks packets with a kick in ${b - a} s", per10 <= 2.0)
        }
    }

    // -- 8.5: the song's course is kept --

    @Test fun `the drop stands well above the verse and the breakdown, the intro is low`() {
        val r = main
        fun m(part: String) = MusicRig.SECTIONS[part]!!.let { (a, b) -> r.mean(a + 0.5, b) }
        val intro = m("intro"); val verse = m("verse"); val drop = m("drop"); val breakdown = m("breakdown")
        val said = "intro %.0f, verse %.0f, build %.0f, drop %.0f, breakdown %.0f, drop 2 %.0f px".format(intro, verse, m("build"), drop, breakdown, m("drop 2"))
        println(said)
        assertTrue(said, drop >= verse + 35f)
        assertTrue(said, drop >= breakdown + 50f)
        assertTrue(said, intro <= 0.22f * MusicBody.PAGE_H)
    }

    @Test fun `the build rises and never sinks back on the way up`() {
        // From 2 s into the build (the spec's 15 s was 2 s into the design's build, which began at 13 s; the spec's track
        // begins it at 14 s, 10 dB under the verse, and the body is still coming down from the verse at 15 s, as it must).
        val r = main
        val from = MusicRig.SECTIONS["build"]!!.first + 2.0; val to = MusicRig.SECTIONS["build"]!!.second
        // Its MASS (the mean height over what he sees: the peaks come and go on top of it, as they should).
        val avg = r.t.indices.map { i -> val lo = r.t[i] - 250; val hi = r.t[i] + 250; r.t.indices.filter { r.t[it] in lo..hi }.map { r.mass[it] }.average().toFloat() }
        var top = 0f; var worst = 0f
        for (i in r.at(from) until r.at(to)) { top = maxOf(top, avg[i]); worst = maxOf(worst, top - avg[i]) }
        val rise = avg[r.at(to) - 1] - avg[r.at(from)]
        println("the build's mass: rose $rise px, at most $worst px under its running top")
        assertTrue("it fell $worst px below its running top", worst <= 8f)
        assertTrue("from $from s to $to s it rose $rise px", rise >= 40f)
    }

    @Test fun `a long quiet part stays low for as long as it is quiet`() {
        val r = run("long quiet", MusicRig.longQuiet, -25.0, 60_000L, check = false)
        val drop = r.mean(2.0, 20.0); val early = r.mean(30.0, 40.0); val late = r.mean(50.0, 60.0)
        val said = "drop %.0f, quiet 30-40 s %.0f, quiet 50-60 s %.0f px".format(drop, early, late)
        println(said)
        assertTrue(said, late <= early + 15f)
        assertTrue(said, late <= drop - 40f)
    }

    @Test fun `his volume does not move the course - the same track at -10, -25 and -35 dB`() {
        val runs = listOf(-10.0, -25.0, -35.0).map { v ->
            val packets = ArrayList<Pair<Long, FloatArray>>()
            MusicRig.chain(MusicRig.mainTrack, v, 44_000L, packet = { t, p -> packets += t to p }) { }
            packets
        }
        var worst = 0f
        for (i in runs[0].indices) {
            if (runs[0][i].first < 2_000L) continue
            for (o in 1..2) worst = maxOf(worst, abs(runs[0][i].second[0] - runs[o][i].second[0]))
        }
        assertTrue("the course differs by up to $worst between volumes", worst <= 0.05f)
        val kicks = runs.map { p -> p.count { (t, v) -> v[1] >= 0.3f && MusicRig.DROP_KICKS.any { k -> t >= k * 1000 && t < (k + 0.5) * 1000 } } }
        assertTrue("kicks in the drops at each volume: $kicks", kicks.max() - kicks.min() <= 2)
    }

    // -- 8.6 to 8.8 --

    @Test fun `with no level it rests at a calm height and moves by itself, and eases out and back in without a jump`() {
        // Packets stop in the middle of the drop for 8 s, then come back.
        val gone = 24_000L until 32_000L
        val r = run("no level for 8 s", MusicRig.mainTrack, -25.0, 40_000L, playing = { it < MusicRig.PAUSE_S * 1000 }, sends = { it !in gone })
        var calm = true; val e = ArrayList<Float>()
        MusicRig.chain(MusicRig.mainTrack, -25.0, 32_000L, sends = { it !in gone }) { s -> if (s.t >= 30_000L) { e += s.body.e; if (abs(s.body.e - MusicBody.E_NOLEVEL) > 0.03f) calm = false } }
        assertTrue("E 6 to 8 s after the last packet: ${e.min()}..${e.max()}", calm)
        val at6 = r.height[r.at(30.0)]; val mean = r.mean(30.0, 32.0)
        println("no level: 6 s in the top is $at6 px, its mean over the next 2 s $mean px, spread ${r.spreadIn(30.0, 32.0)} px")
        assertTrue("6 s in the top is $at6 px, its mean over the next 2 s $mean px", at6 in 80f..150f && mean in 80f..150f)
        assertTrue(r.toString(), r.worstStep <= 1f && r.worstLight <= 0.08f)
        assertTrue(r.toString(), r.worstDip <= 0.05f && r.worstValley >= FLOOR_PX && r.worstFoot >= 0.4f)
        // Never flat and never still: it has shape, and it moves.
        assertTrue("its shape with no level: spread ${r.spreadIn(30.0, 32.0)} px", r.spreadIn(30.0, 32.0) >= 12f)
        assertTrue("it moves with no level", moves(r, 30.0, 32.0) >= 5f)
        // And packets that say there is no level: the same.
        val invalid = MusicFace.Body(); var t = 1_000L; var inv6 = 0f; val late = ArrayList<Float>()
        while (t < 9_000L) {
            if (t % 33L < 16L) invalid.hear(floatArrayOf(1f, 1f, 0f, 0f, 0f, 0f), t)
            val h = MusicBody.height(invalid.step(t, true))
            if (t in 7_000L..7_015L) inv6 = h
            if (t >= 7_000L) { late += h; assertEquals("E with only packets marked not valid", MusicBody.E_NOLEVEL, invalid.e, 0.03f) }
            t += 16L
        }
        assertTrue("its top 6 s in $inv6 px, its mean after ${late.average()} px", inv6 in 80f..150f && late.average() in 80.0..150.0)
    }

    @Test fun `paused from a drop it sinks low within 3 s and keeps only a slow ripple, and rises again without a jump`() {
        val r = main
        val p = MusicRig.PAUSE_S
        assertTrue("3 s after the pause the top is ${r.height[r.at(p + 3)]} px", r.height[r.at(p + 3)] <= 60f)
        // A calm low surface with a slow ripple: still moving, but slowly.
        val shapes = ArrayList<FloatArray>()
        MusicRig.chain(MusicRig.mainTrack, -25.0, 56_000L, playing = { it < MusicRig.PAUSE_S * 1000 }) { s -> if (s.t >= (p + 4) * 1000) shapes += FloatArray(XS.size) { MusicBody.surface(s.frame, XS[it]) } }
        var fastest = 0f; var travelled = 0f
        for (i in 0 until shapes.size - 1) { val m = XS.indices.maxOf { abs(shapes[i + 1][it] - shapes[i][it]) }; fastest = maxOf(fastest, m * 60f); travelled += m }
        val said = "paused, from 4 s on: fastest column ${"%.2f".format(fastest)} px a second, moved ${"%.1f".format(travelled)} px in ${shapes.size / 60f} s"
        println(said)
        assertTrue(said, fastest <= 4f)
        assertTrue(said, travelled >= 1f)
        // Playing again after the pause.
        val again = run("paused, then again", MusicRig.mainTrack, -25.0, 34_000L, playing = { it !in 26_000L until 30_000L })
        assertTrue(again.toString(), again.worstStep <= 1f && again.worstLight <= 0.08f && again.worstValley >= FLOOR_PX)
    }

    @Test fun `never above the ceiling - a full course and a full kick on every packet for a minute`() {
        val body = MusicFace.Body()
        var lowest = 1e9f
        var t = 1_000L
        while (t < 61_000L) {
            if (t % 33L < 17L) body.hear(floatArrayOf(1f, 1f, 1f, 0f, 0f, 0f), t)
            val f = body.step(t, true)
            for (x in XS) lowest = minOf(lowest, MusicBody.surface(f, x))
            t += 17L
        }
        assertTrue("the highest the surface came: y $lowest", lowest >= MusicBody.Y_LIMIT)
        assertTrue("and through the track", main.lowestS >= MusicBody.Y_LIMIT)
    }

    // -- 8.9: the clock --

    @Test fun `a page back from away shows the present on its first frame - no kick piled up, no sinking to where the music is`() {
        for (awayMs in listOf(10_000L, 600L, 60_000L)) {
            val leaves = 10_000L; val back = leaves + awayMs; val quiet = back - 2_000L
            fun packet(t: Long) = floatArrayOf(if (t < quiet) 0.95f else 0.2f, if (t < quiet && t % 500L < 33L) 0.9f else 0f, 1f, 0f, 0f, 0f)
            val seen = MusicFace.Body(); val away = MusicFace.Body()
            var t = 1_000L; var sent = 1_000L
            var worstH = 0f; var worstKick = 0f; var firstBack = Float.NaN; var firstSeen = Float.NaN
            val r = Run("back from $awayMs ms away")
            while (t < back + 3_000L) {
                while (sent <= t) { val p = packet(sent); seen.hear(p, sent); away.hear(p, sent); sent += 33L }
                val a = seen.step(t, true)
                val ha = MusicBody.height(a)
                if (t < leaves || t >= back) {
                    val b = away.step(t, true, if (t >= back) 1 else 0)
                    if (t >= back) r.see(MusicRig.Shot(t, away, b), check = false)
                    if (t in back..back + 400L) {
                        if (firstBack.isNaN()) { firstBack = MusicBody.height(b); firstSeen = ha }
                        // The course's part of the height (the breath and the slosh turn on their own clock: not the music).
                        worstH = maxOf(worstH, abs(b.base - a.base)); worstKick = maxOf(worstKick, b.kickAmp)
                    }
                }
                t += 16L
            }
            val said = "$awayMs ms away: first frame back $firstBack px high against $firstSeen px seen all along; within 400 ms the course's part ${"%.1f".format(worstH)} px apart, kick lift ${"%.1f".format(worstKick)} px"
            println(said)
            assertTrue(said, worstH <= 2f && worstKick <= 1f)
            // And from the first frame back on it moves as a body moves (the jump to the present is not drawn: the page was away).
            assertTrue(r.toString(), r.worstStep <= 1f)
        }
    }

    @Test fun `a packet with a NaN in it is dropped - the body goes on`() {
        for (bad in listOf(floatArrayOf(0.8f, Float.NaN, 1f, 0f, 0f, 0f), floatArrayOf(Float.NaN, 0f, 1f, 0f, 0f, 0f), floatArrayOf(0.8f, 0f, Float.NaN, 0f, 0f, 0f), floatArrayOf(Float.POSITIVE_INFINITY, 0f, 1f, 0f, 0f, 0f))) {
            for (badFirst in listOf(true, false)) {
                val body = MusicFace.Body(); val clean = MusicFace.Body()
                var t = 1_000L; var sent = 1_000L
                if (badFirst) { body.hear(bad, t); body.step(t, true); clean.step(t, true); t += 16L }
                while (t < 8_000L) {
                    while (sent <= t) {
                        val good = floatArrayOf(0.8f, 0f, 1f, 0f, 0f, 0f)
                        body.hear(if (sent == 3_013L) bad else good, sent); clean.hear(good, sent); sent += 33L
                    }
                    body.step(t, true); clean.step(t, true); t += 16L
                }
                val f = body.frame
                val said = "${bad.toList()} (first: $badFirst): base ${f.base}, kick ${f.kickAmp}, light ${f.g}, height ${MusicBody.height(f)} against ${MusicBody.height(clean.frame)}"
                assertTrue(said, f.base.isFinite() && f.kickAmp.isFinite() && f.g.isFinite() && MusicBody.height(f).isFinite())
                assertEquals(said, 1f, body.w, 0.02f)
                assertEquals(said, MusicBody.height(clean.frame), MusicBody.height(f), 1f)
            }
        }
    }

    @Test fun `a blink of the player's state starts nothing over, and a frame a minute late on a page that stayed up is no leap`() {
        val body = MusicFace.Body()
        val r = Run("blinks")
        var t = 1_000L
        while (t < 21_000L) {
            if (t % 33L < 16L) body.hear(floatArrayOf(0.7f, 0f, 1f, 0f, 0f, 0f), t)
            val playing = t !in 8_000L..8_040L && t !in 14_000L..14_020L
            r.see(MusicRig.Shot(t, body, body.step(t, playing)), check = false)
            t += 16L
        }
        assertTrue(r.toString(), r.worstStep <= 1f)
        val before = FloatArray(XS.size) { MusicBody.surface(body.frame, XS[it]) }
        val back = body.step(t + 60_000L, true)
        val moved = XS.indices.maxOf { abs(MusicBody.surface(back, XS[it]) - before[it]) }
        assertTrue("a minute away, the first frame back moved $moved px (50 ms of motion at most)", moved <= STEP_PX * 50f / 16.7f)
    }

    // -- the peaks' filter --

    @Test fun `a band rises fast and falls gently - 90 percent up in 60 to 100 ms, down to 10 percent in 250 to 400 ms`() {
        val body = MusicFace.Body()
        val b0 = dev.kb896.aiusage.service.MusicSound.BAND0
        fun packet(v: Float) = FloatArray(dev.kb896.aiusage.service.MusicSound.PACKET).also { p -> p[0] = 0.5f; p[2] = 1f; for (i in 0 until MusicBody.BANDS) p[b0 + i] = if (i == 4) v else 0f }
        var t = 1_000L; var sent = 1_000L
        fun until(ms: Long, v: Float, each: (Long, Float) -> Unit = { _, _ -> }) {
            while (t < ms) { while (sent <= t) { body.hear(packet(v), sent); sent += 33L }; val f = body.step(t, true); each(t, f.bands[4]); t += 4L }
        }
        until(4_000L, 0f)
        assertEquals("band 4 at rest", 0f, body.frame.bands[4], 0.01f)
        // The rise begins with the first packet that says 1.
        val rise0 = sent; var up90 = -1L
        until(5_000L, 1f) { at, v -> if (up90 < 0 && v >= 0.9f) up90 = at - rise0 }
        val fall0 = sent; var down10 = -1L
        until(7_000L, 0f) { at, v -> if (down10 < 0 && v <= 0.1f) down10 = at - fall0 }
        val said = "90 % up in $up90 ms, down to 10 % in $down10 ms"
        println(said)
        assertTrue(said, up90 in 60L..100L)
        assertTrue(said, down10 in 250L..400L)
        // The other bands did not move with it.
        assertEquals(0f, body.frame.bands[3], 0.01f)
    }

    @Test fun `a band's peak stands where its band is, mirrored about the middle, and the surface is one smooth curve`() {
        val f = MusicBody.Frame().apply { base = 500f; peak = 100f; wave = 0f }
        f.bands[6] = 1f
        val xs = (296..976).map { it.toFloat() }
        val s = xs.map { MusicBody.surface(f, it) }
        val top = s.min()
        val at = xs.filterIndexed { i, _ -> s[i] <= top + 0.01f }
        val c = MusicBody.DOME_X + 6 * MusicBody.BAND_STEP; val m = MusicBody.DOME_X - 6 * MusicBody.BAND_STEP
        assertTrue("its tops at ${at.min()}..${at.max()}, the band's places $m and $c", at.any { abs(it - c) <= 2f } && at.any { abs(it - m) <= 2f })
        // Smooth: no corner (the slope changes little from one px to the next) and no gap (the base under it all).
        var worstBend = 0f
        for (i in 1 until s.size - 1) worstBend = maxOf(worstBend, abs(s[i + 1] - 2 * s[i] + s[i - 1]))
        assertTrue("bend $worstBend px per px squared", worstBend <= 0.2f)
        assertTrue("the base under it all: ${s.max()}", s.max() <= 500f + 0.01f)
        // All bands at 1: about one flat height inside (the bumps fill each other's gaps; no comb, no bars).
        for (i in 0 until MusicBody.BANDS) f.bands[i] = 1f
        val inner = (336..936).map { MusicBody.surface(f, it.toFloat()) }
        assertTrue("all bands up: ${inner.min()}..${inner.max()}", inner.max() - inner.min() <= 5f)
    }

    // -- the picture: the Kotlin formula's own properties --

    @Test fun `the colours mix in linear light - where they meet is as bright as either`() {
        val f = MusicFace.Body().step(1_000L, true)
        MusicRig.worstColours(f)
        val lights = (0..244).map { MusicBody.light(f, it * 4f, 590f) }
        assertTrue("along the foot at rest: ${lights.min()}..${lights.max()}", lights.min() >= 0.97f * lights.max())
    }
}
