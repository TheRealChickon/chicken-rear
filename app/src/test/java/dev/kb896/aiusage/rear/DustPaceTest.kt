package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * THE DUST'S PACE (DustPace, DustDriver.quick, SceneEngine.playDust): every refresh of the rear (120 a second) where 60
 * would be seen to step, a finger and the phone's fast movement, as before; every other one at rest and while it reacts
 * slowly, its step then taken in steps of 1/120 s; no picture at 60 moves a mote further than the face's first 60
 * pictures could; the panel's rate not asked for.
 */
class DustPaceTest {
    private val period = FramePace.periodNsOf(FramePace.REAR_HZ)

    // -- the loop ----------------------------------------------------------------------------------------------------

    @Test fun `the dust's loop paces by DustDriver_quick from a fresh clock, and asks the panel for no rate`() {
        val rear = File("src/main/java/dev/kb896/aiusage/rear")
        val play = File(rear, "SceneEngine.kt").readText().substringAfter("private fun playDust(").substringBefore("private fun face(")
        assertTrue(play.contains("val pace = DustPace(REAR_PERIOD_NS)"))
        assertTrue(play.contains("count.picture(driver.touched, driver.quick, driver.active, "))
        // The next picture's time and the sleep from the clock read AFTER shown() and the log line (their time is not
        // slept on top), at the pace the picture just drawn asks for.
        // (And every refresh while the message island's outline moves over the face's tint: the colour keeps up with it.)
        val sleep = play.indexOf("val wait = pace.next(t, driver.quick || tintMoving) - t - DUST_LEAD_NS")
        assertTrue(play.contains("val tintMoving = inp.tint > 0f && veilFrame.moving"))
        val clock = play.indexOf("val t = System.nanoTime()")
        assertTrue(sleep > 0 && clock in 0 until sleep)
        assertTrue(clock > play.indexOf("DustPace.line(") && clock > play.indexOf("shown()"))
        assertFalse(play.contains("frameMs") || play.contains("clockNextNs"))
        // No rate asked of the panel: a vote of the dust's layer would turn off SurfaceFlinger's touch boost on the back
        // (pages dragged over the moving dust too) and a 60 would hold back the pictures drawn at 120.
        for (name in listOf("SceneEngine.kt", "ShellScene.kt", "DustPace.kt", "DustDriver.kt")) {
            val code = File(rear, name).readText().lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            assertFalse(name, code.contains("setFrameRate") || code.contains("clearFrameRate") || code.contains("panelRate"))
        }
    }

    // -- the pace, state by state ------------------------------------------------------------------------------------

    @Test fun `every refresh for a finger and the phone moved or turned fast, every other one at rest - the GL check's timeline`() {
        val tl = DustTimeline(DustTimeline.Pace.PHONE)
        val gaps = HashMap<String, MutableList<Long>>()
        val steps = HashMap<String, MutableSet<Int>>()
        var last = -1L
        tl.run { t, _, dt, n ->
            val state = when (t) {
                in 0L..4399L -> "rest"
                in 4417L..4999L -> "finger"
                in 5817L..7399L -> "stroke"
                in 9300L..10099L -> "tipped"
                in 10500L..10699L -> "moved"
                in 11100L..11800L -> "turned"
                else -> "between"
            }
            // Each picture steps the dust by the time since the last one (on time here: its gap).
            if (last >= 0L) { gaps.getOrPut(state) { ArrayList() } += t - last; assertEquals("at $t", (t - last).toFloat(), dt * 1000f, 1f) }
            last = t
            steps.getOrPut(state) { HashSet() } += n
        }
        println("dust pace: gaps by state ${gaps.mapValues { (_, v) -> v.groupingBy { it }.eachCount() }}, steps $steps")
        assertTrue(gaps["rest"]!!.all { it in 16L..17L }); assertEquals(setOf(1), steps["rest"])
        // A finger and the fast movements: every refresh, one step a picture, as before (the picture the finger lands
        // in was paced before it was heard).
        for (state in listOf("finger", "stroke", "moved", "turned")) {
            assertTrue("$state: ${gaps[state]}", gaps[state]!!.drop(1).all { it in 8L..9L })
            assertEquals(state, setOf(1), steps[state])
        }
        // The tip (15 degrees over 1.5 s): its slow part at 60 in two steps, its quick middle at 120, switched a few
        // times, not picture by picture.
        val tipped = gaps["tipped"]!!
        val switches = tipped.zipWithNext().count { (a, b) -> (a >= 16L) != (b >= 16L) }
        assertTrue("tipped: $tipped", tipped.count { it >= 16L } > 10 && tipped.count { it <= 9L } > 10 && switches <= 4)
        assertTrue(2 in steps["tipped"]!!)
    }

    @Test fun `on the grid - one refresh on when quick, two otherwise, a late one steps further, a stall no more than 1_30 s`() {
        val p = DustPace(period)
        var now = 1_000_000_000L
        assertEquals(DustPace.FIRST_DT, p.picture(now), 0f)
        // At 60: every two refreshes of the rear, the step 1/60 s.
        repeat(10) {
            val due = p.next(now + 3_000_000L, quick = false)
            assertEquals(2 * period, due - now)
            now = due
            assertEquals(1f / 60f, p.picture(now + 500_000L), 1e-6f)
        }
        // Quick: every refresh, the step 1/120 s, one step; and back.
        repeat(4) {
            val due = p.next(now + 1_000_000L, quick = true)
            assertEquals(period, due - now)
            now = due
            val dt = p.picture(now)
            assertEquals(1f / 120f, dt, 1e-6f)
            assertEquals(1, DustPace.steps(dt, true))
        }
        assertEquals(2 * period, p.next(now + 1_000_000L, quick = false) - now)
        now = p.dueNs; p.picture(now)
        // Sent late, but within a refresh: still on the grid, the same step (its picture shows a refresh late at worst).
        val due = p.next(now + 2 * period + 5_000_000L, quick = false)
        assertEquals(now + 2 * period, due)
        now = due
        assertEquals(1f / 60f, p.picture(now + 5_000_000L), 1e-6f)
        // A stall (200 ms): the next is now, and its step the real time since, held to 1/30 s.
        val stalled = now + 200_000_000L
        assertEquals(stalled, p.next(stalled, quick = false))
        assertEquals(DustPace.MAX_DT, p.picture(stalled), 0f)
        // A late picture between the two: its step is the real time (here 1.5 pictures).
        val q = DustPace(period)
        q.picture(1_000_000_000L)
        val late = 1_000_000_000L + 3 * period + 4_000_000L
        assertEquals(late, q.next(late, quick = false))
        assertEquals((3 * period + 4_000_000L) / 1e9f, q.picture(late), 1e-6f)
        // Held: no step for the time held, the next picture starts afresh.
        q.hold()
        assertEquals(DustPace.FIRST_DT, q.picture(9_000_000_000L), 0f)
        // The steps: two of 1/120 s a picture at 60 while reacting, one at rest; a longer step in more, never past 4.
        assertEquals(1, DustPace.steps(1f / 60f, false)); assertEquals(2, DustPace.steps(1f / 60f, true))
        assertEquals(1, DustPace.steps((2 * period) / 1e9f, false)); assertEquals(2, DustPace.steps((2 * period) / 1e9f, true))
        assertEquals(4, DustPace.steps(DustPace.MAX_DT, true)); assertEquals(2, DustPace.steps(DustPace.MAX_DT, false))
        assertEquals(1, DustPace.steps(1f / 120f, true)); assertEquals(1, DustPace.steps(period / 1e9f, true))
    }

    @Test fun `a long picture does not throw the motion - a stalled picture steps the dust by its real time, at most two pictures' worth`() {
        val m = DustField.motes()
        /** Each picture's largest move of a mote on the panel (px), the pictures at their pace, picture [stallAt] sent 200 ms late. */
        fun run(stallAt: Int, finger: Boolean): FloatArray {
            val s = DustField.initial(m)
            val d = DustDriver()
            val p = DustPace(period)
            if (finger) d.finger.down(500f, 300f, 0L)
            var now = 2 * period
            val most = FloatArray(40)
            val was = FloatArray(m.count * 2); val at = FloatArray(2)
            for (k in 0 until 40) {
                if (k > 0) now = p.next(if (k == stallAt) now + 200_000_000L else now, d.quick)
                val dt = p.picture(now)
                val n = DustPace.steps(dt, d.active)
                if (finger) d.finger.move(500f, 300f, now / 1_000_000L)
                val inp = d.next(dt, now / 1_000_000L, n)
                DustField.step(m, s, inp, dt / n, n)
                for (i in 0 until m.count) {
                    DustField.seen(s.px[i] + s.dx[i], s.py[i] + s.dy[i], m.z[i], inp, at)
                    if (k > 0 && at[0] in 0f..DustField.W && at[1] in 0f..DustField.H) {
                        val step = hypot(at[0] - was[2 * i], at[1] - was[2 * i + 1])
                        if (step < 300f) most[k] = max(most[k], step)
                    }
                    was[2 * i] = at[0]; was[2 * i + 1] = at[1]
                }
            }
            return most
        }
        for (finger in listOf(false, true)) {
            val onTime = run(stallAt = -1, finger = finger); val late = run(stallAt = 20, finger = finger)
            val ratio = late[20] / onTime[20]
            println("dust${if (finger) ", a finger held (at 120)" else ", at rest (at 60)"}: a picture 200 ms late moves a mote at most ${"%.2f".format(late[20])} px, ${"%.2f".format(ratio)} times the picture on time (${"%.2f".format(onTime[20])} px)")
            // Further, by the real time (held to 1/30 s: two pictures' worth at 60, four at 120), and no more.
            if (finger) assertTrue("$finger: $ratio", ratio > 3f && ratio < 4.5f) else assertTrue("$finger: $ratio", ratio > 1.6f && ratio < 2.25f)
        }
    }

    // -- the look and the reactions, as before -----------------------------------------------------------------------

    /** The timeline at [pace]: the motes at its cues, and how far a mote went at most in a picture, at 60 and at 120. */
    private class Cues(
        val states: Map<Int, DustField.State>, val inputs: Map<Int, DustField.Inputs>, val pictures: Int,
        val fastest: Float, val fastestPx: Float, val at60Px: Float, val at60Ms: Long, val at120: Int,
    )

    private fun cues(pace: DustTimeline.Pace, rateUs: Int = DustMotion.RATE_US): Cues {
        val tl = DustTimeline(pace, rateUs)
        val m = tl.motes; val s = tl.state
        val states = HashMap<Int, DustField.State>(); val ins = HashMap<Int, DustField.Inputs>()
        val was = FloatArray(m.count * 2); val at = FloatArray(2)
        var fastest = 0f; var fastestPx = 0f; var at60 = 0f; var at60Ms = 0L; var at120 = 0; var pictures = 0
        tl.run { t, inp, dt, _ ->
            var most = 0f
            for (i in 0 until m.count) {
                DustField.seen(s.px[i] + s.dx[i], s.py[i] + s.dy[i], m.z[i], inp, at)
                if (pictures > 0 && at[0] in 0f..DustField.W && at[1] in 0f..DustField.H) {
                    val d = hypot(at[0] - was[2 * i], at[1] - was[2 * i + 1])
                    if (d < 200f) most = max(most, d)
                }
                was[2 * i] = at[0]; was[2 * i + 1] = at[1]
            }
            fastestPx = max(fastestPx, most); fastest = max(fastest, most / dt)
            if (dt > 0.012f) { if (most > at60) { at60 = most; at60Ms = t } } else at120++
            pictures++
            val cue = DustTimeline.CUES.firstOrNull { abs(it - t) < 2 }
            if (cue != null) {
                states[cue] = DustField.State(m.count).also { c ->
                    s.px.copyInto(c.px); s.py.copyInto(c.py); s.dx.copyInto(c.dx); s.dy.copyInto(c.dy); s.vx.copyInto(c.vx); s.vy.copyInto(c.vy)
                }
                ins[cue] = DustField.Inputs().also { c -> copy(inp, c) }
            }
        }
        return Cues(states, ins, pictures, fastest, fastestPx, at60, at60Ms, at120)
    }

    private fun copy(a: DustField.Inputs, b: DustField.Inputs) {
        a.wave.copyInto(b.wave); b.flow = a.flow; b.windX = a.windX; b.windY = a.windY; b.gustX = a.gustX; b.gustY = a.gustY
        b.fingerX = a.fingerX; b.fingerY = a.fingerY; b.push = a.push; b.fingerVX = a.fingerVX; b.fingerVY = a.fingerVY
        b.glowX = a.glowX; b.glowY = a.glowY; b.glow = a.glow; b.tiltX = a.tiltX; b.tiltY = a.tiltY; b.lagX = a.lagX; b.lagY = a.lagY
        b.spin = a.spin; b.gain = a.gain
    }

    /** How far apart the motes on the panel are seen at [cue] (px: mean, worst), and their pictures (levels: mean, worst). */
    private fun apart(a: Cues, b: Cues, cue: Int, picture: Boolean = false): FloatArray {
        val m = DustField.motes(); val p = FloatArray(2); val q = FloatArray(2)
        val sa = a.states[cue]!!; val sb = b.states[cue]!!
        var sum = 0.0; var worst = 0f; var n = 0
        for (i in 0 until m.count) {
            DustField.seen(sa.px[i] + sa.dx[i], sa.py[i] + sa.dy[i], m.z[i], a.inputs[cue]!!, p)
            DustField.seen(sb.px[i] + sb.dx[i], sb.py[i] + sb.dy[i], m.z[i], b.inputs[cue]!!, q)
            if (p[0] !in 0f..DustField.W || p[1] !in 0f..DustField.H) continue
            val d = hypot(p[0] - q[0], p[1] - q[1]); sum += d; worst = max(worst, d); n++
        }
        if (!picture) return floatArrayOf((sum / n).toFloat(), worst, 0f, 0f)
        val pa = DustField.render(m, sa, a.inputs[cue]!!); val pb = DustField.render(m, sb, b.inputs[cue]!!)
        var lev = 0.0; var lw = 0
        for (k in pa.indices) for (sh in intArrayOf(0, 8, 16)) { val d = abs(((pa[k] shr sh) and 255) - ((pb[k] shr sh) and 255)); lev += d; lw = maxOf(lw, d) }
        return floatArrayOf((sum / n).toFloat(), worst, (lev / (pa.size * 3)).toFloat(), lw.toFloat())
    }

    @Test fun `the reactions are as before - the finger's push and lane, the phone tipped, moved and turned, at the GL check's cues`() {
        val old = cues(DustTimeline.Pace.OLD)
        val now = cues(DustTimeline.Pace.PHONE)
        val flat = cues(DustTimeline.Pace.FLAT60)
        println("dust: ${old.pictures} pictures before (120 while reacting), ${now.pictures} now (${now.at120} of them at 120), ${flat.pictures} at 60 always, in 12 s")
        for (cue in DustTimeline.CUES) {
            val a = apart(old, now, cue, picture = true); val f = apart(old, flat, cue)
            println("dust at $cue ms, now vs before: motes ${"%.3f".format(a[0])} px apart on average, ${"%.2f".format(a[1])} at worst; the picture ${"%.4f".format(a[2])} levels on average, ${a[3].toInt()} at worst; one step a picture at 60: ${"%.3f".format(f[0])} / ${"%.2f".format(f[1])} px")
        }
        // At rest, a finger held and drawn across: the same pictures as before (the same steps).
        for (cue in listOf(2000, 4800, 6600)) assertTrue("$cue: ${apart(old, now, cue).toList()}", apart(old, now, cue)[1] < 0.01f)
        // The phone tipped, moved and turned, partly at 60 in steps of 1/120 s (the start of a move, the slow part of
        // the tip): within a tenth of a pixel (a sample's change reaches the dust at the next picture, up to 1/60 s on
        // where it was 1/120 s), and closer than one step a picture at 60 would be.
        for (cue in listOf(9400, 10700, 11500)) {
            val a = apart(old, now, cue); val f = apart(old, flat, cue)
            assertTrue("$cue: ${a.toList()} vs ${f.toList()}", a[0] < 0.05f && a[1] < 0.1f && a[1] < f[1])
        }
    }

    @Test fun `how far a mote goes in a picture - as before where it is drawn at 120, never past the face's first 60 pictures at 60`() {
        val old = cues(DustTimeline.Pace.OLD)
        val now = cues(DustTimeline.Pace.PHONE)
        println("dust: the fastest mote on the panel ${"%.0f".format(now.fastest)} px/s, ${"%.1f".format(now.fastestPx)} px a picture (before: ${"%.0f".format(old.fastest)} px/s, ${"%.1f".format(old.fastestPx)} px a picture); at 60 at most ${"%.2f".format(now.at60Px)} px a picture, at ${now.at60Ms} ms (before ${"%.2f".format(old.at60Px)} px)")
        // The finger's push: the same speed and the same way between two pictures as before, drawn every refresh.
        assertEquals(old.fastest, now.fastest, 0.02f * old.fastest)
        assertEquals(old.fastestPx, now.fastestPx, 0.02f * old.fastestPx)
        // At 60 never further than the face's first 60 pictures could step: its fast motes at the top of a breath with
        // the movement just under DustMotion.PACE_FAST (CURRENT_FAST + GUST_PACE + PACE_FAST, a picture's worth).
        val bound = (DustField.CURRENT_FAST + DustField.GUST_PACE + DustMotion.PACE_FAST) / 60f
        assertTrue("at 60: ${now.at60Px} px a picture, the bound $bound", now.at60Px < bound)
    }

    @Test fun `a slow tilt is drawn at 60 in steps of 1_120 s - no mote further a picture than the face's first 60 could`() {
        /**
         * The phone tipped by [tilt] degrees and back over [overS] s, from [fromMs] (the first breath of the idle act
         * tops at 6.9 s): the most a mote moves in a picture at 60 and at 120 (px), the reacting pictures at 60 and at
         * 120, and the pictures in all.
         */
        fun run(tilt: Double, overS: Float, fromMs: Long): FloatArray {
            val m = DustField.motes(); val s = DustField.initial(m)
            val d = DustDriver()
            val p = DustPace(period)
            val held = floatArrayOf(0f, 8.5f, -4.9f)
            val made = DustMotion.Made(held, tilt = Math.toRadians(tilt).toFloat(), turnS = overS)
            val a = FloatArray(3); val w = FloatArray(3)
            var nextUs = 0L; var now = 2 * period
            val was = FloatArray(m.count * 2); val at = FloatArray(2)
            var at60 = 0f; var at120 = 0f; var pictures = 0; var slow = 0; var quick = 0; var counted = 0
            val endNs = (fromMs + (overS * 1000f).toLong() + 1500L) * 1_000_000L
            while (now < endNs) {
                if (pictures > 0) now = p.next(now, d.quick)
                val dt = p.picture(now)
                val n = DustPace.steps(dt, d.active)
                val tMs = now / 1_000_000L
                while (nextUs <= now / 1000L) {
                    val ms = nextUs / 1000L
                    if (ms >= fromMs) made.at((ms - fromMs) / 1000f, a, w) else { held.copyInto(a); w.fill(0f) }
                    d.motion.gyro(w[0], w[1], w[2], nextUs * 1000L + 1); d.motion.accel(a[0], a[1], a[2], nextUs * 1000L + 1)
                    nextUs += DustMotion.RATE_US
                }
                val inp = d.next(dt, tMs, n)
                DustField.step(m, s, inp, dt / n, n)
                var most = 0f
                for (i in 0 until m.count) {
                    DustField.seen(s.px[i] + s.dx[i], s.py[i] + s.dy[i], m.z[i], inp, at)
                    if (pictures > 0 && at[0] in 0f..DustField.W && at[1] in 0f..DustField.H) {
                        val dd = hypot(at[0] - was[2 * i], at[1] - was[2 * i + 1]); if (dd < 200f) most = max(most, dd)
                    }
                    was[2 * i] = at[0]; was[2 * i + 1] = at[1]
                }
                pictures++
                if (tMs >= fromMs) {
                    counted++
                    if (dt > 0.012f) { at60 = max(at60, most); if (d.active) slow++ } else { at120 = max(at120, most); quick++ }
                }
            }
            return floatArrayOf(at60, at120, slow.toFloat(), quick.toFloat(), counted.toFloat())
        }
        // The face's first 60 pictures went up to its fast motes at the top of a breath with the movement just under
        // DustMotion.PACE_FAST: a picture's worth of that is the most a mote may step at 60.
        val bound = (DustField.CURRENT_FAST + DustField.GUST_PACE + DustMotion.PACE_FAST) / 60f
        val runs = listOf(
            Triple(8.0, 1.5f, 2000L),   // a slow tilt: all of it at 60, which drew it at 120 before
            Triple(12.0, 2.0f, 6000L),  // a slower tilt through the first breath: at 120 at the breath's top
            Triple(15.0, 1.0f, 2000L),  // a brisk one: at 120 but for its ends
        )
        val got = runs.map { (tilt, overS, fromMs) ->
            run(tilt, overS, fromMs).also { r ->
                println("dust, tipped $tilt degrees over $overS s from $fromMs ms: ${r[4].toInt()} pictures, ${r[2].toInt()} reacting at 60 (in two steps), ${r[3].toInt()} at 120; a mote at most ${"%.2f".format(r[0])} px a picture at 60, ${"%.2f".format(r[1])} at 120 (the bound at 60 ${"%.2f".format(bound)})")
                assertTrue("tipped $tilt over $overS: at 60 ${r[0]} px a picture", r[0] < bound)
            }
        }
        // The saving is there: the slow tilt reacts at 60 throughout; the breath's top and the brisk tilt go to 120.
        assertTrue(got[0][2] > 20f && got[0][3] == 0f)
        assertTrue(got[1][2] > 20f && got[1][3] > 10f)
        assertTrue(got[2][3] > 60f)
    }

    // -- the sensors' rate -------------------------------------------------------------------------------------------

    @Test fun `the sensors at 50 a second - at 25 the tilt moves in spurts and a turn's swirl lands elsewhere`() {
        assertEquals(20_000, DustMotion.RATE_US)
        /** A 15 degree tilt and back over a second at the phone's pace: how unevenly the tilt moves the near motes. */
        fun spurts(rateUs: Int): Float {
            val held = floatArrayOf(0f, 8.5f, -4.9f)
            val m = DustMotion()
            val made = DustMotion.Made(held, tilt = Math.toRadians(15.0).toFloat(), turnS = 1.0f)
            val a = FloatArray(3); val w = FloatArray(3)
            var nextUs = 0L; var tUs = 0L
            var wasX = 0f; var wasY = 0f
            val v = ArrayList<Float>()
            while (tUs < 2_000_000L) {
                val dt = if (m.quick()) 1f / DustPace.QUICK_HZ else 1f / DustPace.HZ
                tUs += Math.round(dt * 1e6)
                while (nextUs <= tUs) {
                    if (nextUs < 500_000L) { held.copyInto(a); w.fill(0f) } else made.at((nextUs - 500_000L) / 1e6f, a, w)
                    m.gyro(w[0], w[1], w[2], nextUs * 1000L + 1); m.accel(a[0], a[1], a[2], nextUs * 1000L + 1)
                    nextUs += rateUs
                }
                val n = DustPace.steps(dt, m.active)
                repeat(n) { m.step(dt / n) }
                // Its speed (px/s), picture by picture.
                if (tUs > 600_000L && tUs < 1_400_000L) v += hypot((m.tiltX - wasX) * DustMotion.TILT_NEAR_X, (m.tiltY - wasY) * DustMotion.TILT_NEAR_Y) / dt
                wasX = m.tiltX; wasY = m.tiltY
            }
            // How far each picture's speed strays from the mean of its neighbours', at most, as a share of it.
            var worst = 0f
            for (k in 1 until v.size - 1) { val around = (v[k - 1] + v[k + 1]) / 2f; if (around > 20f) worst = max(worst, abs(v[k] - around) / around) }
            return worst
        }
        val at50 = spurts(20_000); val at25 = spurts(40_000)
        val base = cues(DustTimeline.Pace.PHONE); val slow = cues(DustTimeline.Pace.PHONE, 40_000)
        val turned = apart(base, slow, 11500)
        println("dust: the tilt's speed strays from its neighbours' by up to ${"%.0f".format(100 * at50)}% at 50 samples a second, ${"%.0f".format(100 * at25)}% at 25; after a turn at 25 the motes are ${"%.2f".format(turned[0])} px elsewhere on average, ${"%.2f".format(turned[1])} at worst")
        assertTrue("$at25 vs $at50", at25 > 1.5f * at50)
        assertTrue(turned[1] > 2f)
    }

    // -- the log line ------------------------------------------------------------------------------------------------

    @Test fun `the log line says the rate it drew at, the panel's, what reacted and how, the CPU's time and the sensors'`() {
        val c = DustPace.Count()
        var full = false
        // 600 pictures: 150 of a finger and 70 of a fast move at 120, 96 of a slow one and 284 at rest at 60: 8.2 s.
        repeat(DustPace.DUST_LOG_EVERY) { k ->
            val touched = k < 150; val quick = k < 220; val active = k < 316
            full = c.picture(touched, quick, active, if (k == 0) 0L else if (quick) 8_333_333L else 16_666_667L, 950_000L, 700_000L, 40_000L, 60_000L)
        }
        assertTrue(full)
        assertEquals(150, c.finger); assertEquals(70, c.quick); assertEquals(96, c.slow); assertEquals(316, c.reacting)
        // The CPU's time split: the inputs, the motes' step, the drawing (the rest) and the swap add up to all of it.
        assertEquals(c.cpuNs, c.inputsNs + c.stepNs + c.drawNs + c.swapNs)
        val line = DustPace.line(c, threadNs = 640_000_000L, wallNs = 8_170_000_000L, panelHz = 120f, sensors = "the accelerometer 50 a second, the gyroscope 50, through the direct channel")
        println(line)
        assertEquals("dust: 600 pictures in 8.2 s (73 a second, the panel at 120 Hz), 316 reacting (a finger 150, the phone fast 70 at 120, slow 96 at 60), 950 µs each on the CPU (the inputs 40, the motes' step 60, the drawing 150, the swap 700; the thread 7.8% of a core), longest gap 16 ms; the accelerometer 50 a second, the gyroscope 50, through the direct channel", line)
        assertTrue(DustPace.line(c, -1L, 0L, 0f).contains("(73 a second, the panel's rate not known), 316 reacting"))
        assertTrue(DustPace.line(c, -1L, 0L, 0f).contains("950 µs each on the CPU (the inputs 40, the motes' step 60, the drawing 150, the swap 700), "))
        assertTrue(DustPace.line(c, -1L, 0L, 0f).endsWith("longest gap 16 ms"))
        c.reset(); assertEquals(0, c.pictures); assertEquals(0L, c.cpuNs); assertEquals(0, c.reacting); assertEquals(0L, c.drawNs)
    }
}
