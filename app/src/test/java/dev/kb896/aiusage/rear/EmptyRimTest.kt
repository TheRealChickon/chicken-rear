package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.PanelConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

class EmptyRimTest {
    private val path = EmptyRim.Path(976.0, 596.0, 97.0, 780.0)
    private fun play(depth: Double = 1.0, liftAt: Double = 1000.0) = EmptyRim.Play(path).apply { pull(depth); release(liftAt) }
    /** The frame [ms] of the draft's time after a full pull lifted at 1000 ms (the lift is the draft's 200 ms). */
    private fun draftAt(ms: Double) = play().at(1000.0 + ms - 200.0)

    @Test fun `the draft's moments - the heads meet at 750 ms, the light is gone by 1350, what is under back by 1500`() {
        assertEquals(0.750, EmptyRim.T_MEET, 0.005)
        assertEquals(1.350, EmptyRim.LIGHT_END, 0.005)
        assertEquals(1.5, EmptyRim.END_S, 0.0)
        // The run's spring: 98.5 % of the way 550 ms after the split, over by about 3 % at most.
        assertEquals(0.985, EmptyRim.spring(EmptyRim.T_ARRIVE), 1e-3)
        val peak = (0..1600).maxOf { EmptyRim.spring(it / 1000.0) }
        assertTrue("overshoot $peak", peak in 1.02..1.04)
    }

    @Test fun `the pool follows the finger, not a clock`() {
        val p = EmptyRim.Play(path)
        for (dy in listOf(0f, 40f, 100f, 180f, EmptyRim.PULL_FULL, 400f)) {
            p.pull(EmptyRim.pullOf(dy))
            val a = p.at(1000.0)!!; val b = p.at(5000.0)!!
            // Held, nothing moves with time: the same picture a second later and four seconds later.
            assertEquals(a.poolAmp, b.poolAmp, 0.0); assertEquals(a.dim, b.dim, 0.0)
            assertEquals(EmptyRim.pullOf(dy), a.poolAmp, 1e-9)
            assertFalse(a.running); assertEquals(0.0, a.heads, 0.0); assertEquals(0.0, a.fadeAll, 0.0)
            // Its spread and its leak in grow with it, as the draft's grew with its 200 ms; what is under dims with it.
            assertEquals(60 + 70 * a.poolAmp, a.poolSpread, 1e-9)
            assertEquals(1 - (1 - EmptyRim.DIM_TO) * a.poolAmp, a.dim, 1e-9)
        }
        // Deeper with the finger, never past full; the draft's ease over half the notifications' travel.
        assertTrue(EmptyRim.pullOf(50f) < EmptyRim.pullOf(120f))
        assertEquals(1.0, EmptyRim.pullOf(EmptyRim.PULL_FULL), 1e-9)
        assertEquals(1.0, EmptyRim.pullOf(2000f), 1e-9)
        // The draft's own curve: the finger's travel stands for its 200 ms.
        for (k in 0..10) assertEquals(EmptyRim.easeOutCubic(k / 10.0), EmptyRim.pullOf(EmptyRim.PULL_FULL * k / 10f), 1e-6)
    }

    @Test fun `the heads split from the pool at the lift`() {
        val p = play(liftAt = 1000.0)
        val at0 = p.at(1000.0)!!
        assertTrue(at0.running); assertEquals(0.0, at0.run, 1e-9); assertEquals(0.0, at0.heads, 1e-9)
        assertEquals(1.0, at0.poolAmp, 1e-9)
        // The heads fade in over 80 ms; the pool lets go with 110 ms.
        assertEquals(1.0, p.at(1080.0)!!.heads, 1e-6)
        assertEquals(exp(-1.0), p.at(1110.0)!!.poolAmp, 1e-6)
        // One each way, from the finger's place.
        val f = p.at(1100.0)!!
        assertTrue(f.sRight > path.s0 && f.sLeft < path.s0)
        // A second lift changes nothing.
        p.release(1300.0)
        assertEquals(f.run, p.at(1100.0)!!.run, 0.0)
    }

    @Test fun `they meet on the bottom edge under the middle of what he sees, together`() {
        val f = draftAt(EmptyRim.T_MEET * 1000)!!
        assertEquals(636.0, path.xy(path.sMeet)[0], 1e-9); assertEquals(596.0, path.xy(path.sMeet)[1], 1e-9)
        // Both 98.5 % of their own ways: within 1.5 % of each path of the point.
        assertEquals(0.985, f.run, 1e-3)
        val gapR = hypot(f.xyRight[0] - 636.0, f.xyRight[1] - 596.0)
        val gapL = hypot(f.xyLeft[0] - 636.0, f.xyLeft[1] - 596.0)
        assertTrue("right $gapR px, left $gapL px", gapR < 0.016 * path.lRight && gapL < 0.016 * path.lLeft)
        // On the bottom edge, coming from each side.
        assertEquals(596.0, f.xyRight[1], 1e-6); assertEquals(596.0, f.xyLeft[1], 1e-6)
        assertTrue(f.xyRight[0] > 636.0 && f.xyLeft[0] < 636.0)
        // The swell rises there from 60 ms before.
        assertTrue(draftAt(EmptyRim.T_MEET * 1000 + 60)!!.swell > draftAt(EmptyRim.T_MEET * 1000 - 50)!!.swell)
    }

    @Test fun `the light is gone by 1350 ms and what is under is back by 1500, then nothing is drawn`() {
        val gone = draftAt(1350.0)!!
        assertTrue(gone.dark)
        // (1350 ms is the draft's T_MEET + 600 ms, and its T_MEET is 750.0x ms: what is left at 1350.0 is under a millionth.)
        assertEquals(0.0, gone.fadeAll, 0.0); assertEquals(0.0, gone.heads, 0.0); assertEquals(0.0, gone.envelope, 1e-5)
        assertTrue(gone.swell < 1e-4)
        // Before it, still lit.
        assertFalse(draftAt(1200.0)!!.dark)
        // 95 % back by 1350, under one level of 255 by 1500 (as the shader gives it), and the play over.
        assertTrue(gone.dim >= 0.98)
        val last = draftAt(1499.0)!!
        assertTrue("left ${1 - EmptyRim.keepWithoutLight(last.dim)}", 1 - EmptyRim.keepWithoutLight(last.dim) < 1.0 / 255)
        assertNull(draftAt(1500.0))
        for (ms in listOf(1501.0, 2000.0, 60_000.0)) assertNull(draftAt(ms))
    }

    @Test fun `a short pull still plays from the lift, at the same times`() {
        val p = play(depth = 0.2, liftAt = 1000.0)
        assertEquals(0.2, p.at(1000.0)!!.poolAmp, 1e-9)
        // Its depth runs on to full in the draft's remaining pull time (160 ms here), the run as after a full pull.
        assertEquals(1.0, p.depth(1160.0), 1e-9)
        assertEquals(draftAt(500.0)!!.run, p.at(1300.0)!!.run, 1e-12)
        assertNull(p.at(1000.0 + 1300.0))
    }

    @Test fun `every frame moves a little - nothing jumps between two pictures at 120 Hz`() {
        val p = play(liftAt = 0.0)
        var prev = p.at(0.0)!!
        var ms = 0.0
        while (true) {
            ms += 1000.0 / 120
            val f = p.at(ms) ?: break
            assertTrue("dim at $ms", abs(f.dim - prev.dim) < 0.03)
            assertTrue("run at $ms", abs(f.run - prev.run) < 0.06)
            // (The heads' own fade-in is the draft's: ease-out over 80 ms, a third of it in the first picture.)
            assertTrue("heads at $ms", abs(f.heads - prev.heads) < 0.3)
            prev = f
        }
        assertTrue(ms in 1290.0..1310.0)
    }

    @Test fun `white by default, Xiaomi's rear-glow colours as the one choice`() {
        assertFalse(PanelConfig().emptyPullXiaomi)
        assertTrue(PanelConfig.decode(PanelConfig.encode(PanelConfig().copy(emptyPullXiaomi = true))).emptyPullXiaomi)
        assertFalse(PanelConfig.decode(PanelConfig.encode(PanelConfig())).emptyPullXiaomi)
        assertTrue(EmptyRim.colours(false) === EmptyRim.WHITE)
        assertTrue(EmptyRim.colours(true) === EmptyRim.XIAOMI)
        // White: a hair warm of paper white, the same at every height; no colour of its own.
        for (y in listOf(0.0, 150.0, 298.0, 450.0, 596.0)) assertArrayEquals(doubleArrayOf(1.0, 0.975, 0.945), EmptyRim.colourAt(EmptyRim.WHITE, y, 596.0), 1e-6)
        // Xiaomi's: apricot at the top, lavender down the sides, blue at the bottom (pin_show.pag, measured).
        assertArrayEquals(EmptyRim.lin(0xF7BE98).map { it.toDouble() }.toDoubleArray().let { c -> c.map { it / c.max() }.toDoubleArray() }, EmptyRim.colourAt(EmptyRim.XIAOMI, 0.0, 596.0), 1e-6)
        assertArrayEquals(EmptyRim.lin(0x4B83FC).map { it.toDouble() }.toDoubleArray().let { c -> c.map { it / c.max() }.toDoubleArray() }, EmptyRim.colourAt(EmptyRim.XIAOMI, 596.0, 596.0), 1e-6)
        val side = EmptyRim.colourAt(EmptyRim.XIAOMI, 298.0, 596.0)
        assertTrue("lavender at the side: blue over red over green", side[2] >= side[0] && side[0] > side[1])
        // The shader is given exactly these.
        val u = EmptyRim.uniforms(path, draftAt(400.0)!!, EmptyRim.XIAOMI)
        assertArrayEquals(EmptyRim.XIAOMI.bottom, u["uBottom"], 0f)
        assertArrayEquals(EmptyRim.WHITE.top, EmptyRim.uniforms(path, draftAt(400.0)!!, EmptyRim.WHITE)["uSide"], 0f)
    }

    @Test fun `the shader is given every number it reads, its pass table rising from the lift to the meeting`() {
        val u = EmptyRim.uniforms(path, draftAt(400.0)!!, EmptyRim.WHITE)
        for (name in u.keys) assertTrue(name, Regex("uniform \\w+ $name[;\\[]").containsMatchIn(EmptyRim.FRAGMENT))
        assertEquals(EmptyRim.PULL_S.toFloat(), EmptyRim.PASS[0], 1e-6f)
        assertEquals(EmptyRim.T_MEET.toFloat(), EmptyRim.passTime(0.985).toFloat(), 1e-6f)
        for (i in 1 until EmptyRim.PASS.size) assertTrue(EmptyRim.PASS[i] >= EmptyRim.PASS[i - 1])
        // The corner is the display's own (RearGeometry), never a made-up one.
        assertEquals(97f, u["uR"]!![0], 0f)
        // The pull is kept on the straight top edge, never in a corner.
        assertEquals(97.0, EmptyRim.Path(976.0, 596.0, 97.0, 20.0).pullX, 0.0)
        assertEquals(976.0 - 97.0, EmptyRim.Path(976.0, 596.0, 97.0, 970.0).pullX, 0.0)
    }

    @Test fun `a lift that never comes - the pull plays on as lifted after a while, so nothing stays up for good`() {
        val p = EmptyRim.Play(path, landedMs = 1000.0).apply { pull(1.0) }
        assertFalse(p.at(1000.0 + EmptyRim.HOLD_MAX_MS - 1)!!.running)
        assertTrue(p.at(1000.0 + EmptyRim.HOLD_MAX_MS + 1)!!.running)
        assertNull(p.at(1000.0 + EmptyRim.HOLD_MAX_MS + 1 + 1300.0 + 50))
    }

    @Test fun `the light is over at 1350 ms of the draft, a new pull may play it then`() {
        val p = play(liftAt = 1000.0)
        assertFalse(EmptyRim.Play(path).lightOver(60_000.0))
        assertFalse(p.lightOver(1000.0 + 1140.0))
        assertTrue(p.lightOver(1000.0 + 1160.0))
    }

    @Test fun `the phone's uniforms are written into arrays made once, the same numbers as by name`() {
        val out = EmptyRim.uniformArrays()
        val kept = out.toList()
        for (ms in listOf(100.0, 400.0, 900.0)) {
            val f = draftAt(ms)!!
            EmptyRim.fill(path, f, EmptyRim.XIAOMI, out)
            val byName = EmptyRim.uniforms(path, f, EmptyRim.XIAOMI)
            assertEquals(EmptyRim.UNIFORM_NAMES.toList(), byName.keys.toList())
            EmptyRim.UNIFORM_NAMES.forEachIndexed { i, n -> assertArrayEquals(n, byName[n], out[i], 0f) }
        }
        for (i in out.indices) assertTrue(out[i] === kept[i])
        // The fill itself makes nothing: no vararg, no map, no pair (read from its source).
        val src = File("src/main/java/dev/kb896/aiusage/rear/EmptyRim.kt").readText()
        val body = src.substringAfter("fun fill(").substringBefore("\n    }\n")
        assertFalse(body, Regex("floatArrayOf|doubleArrayOf|arrayOf|linkedMapOf| to ").containsMatchIn(body))
        assertFalse(src, src.contains("fun FloatArray.put(vararg"))
    }

    @Test fun `a play that fails ends - it does not hold the shade up or start the same failing thread again`() {
        // (The shell's GL thread cannot run here: its source is read.) The catch ends the play, so the finally neither
        // restarts the thread (it does so only for a play still standing) nor skips telling that it ended.
        val src = File("src/main/java/dev/kb896/aiusage/rear/ShellEmptyRim.kt").readText()
        val caught = src.substringAfter("} catch (e: Throwable) {").substringBefore("} finally {")
        assertTrue(caught, caught.contains("synchronized(lock) { play = null }"))
        assertTrue(src.contains("if (play != null && thread == null) thread = Thread"))
    }

    @Test fun `the frames for the PC render`() {
        val dir = File("build/empty-rim").apply { mkdirs() }
        File(dir, "rim.frag").writeText(EmptyRim.FRAGMENT)
        File(dir, "rim.vert").writeText(EmptyRim.VERTEX)
        // As the draft's renders: corners of 101 px, the pull at x 780; its contact-sheet's moments, and the run every 50 ms.
        val draft = EmptyRim.Path(976.0, 596.0, 101.0, 780.0)
        val times = (listOf(0, 80, 160, 250, 350, 450, 550, 650, 750, 900, 1100, 1350) + (0..1450 step 50)).distinct().sorted()
        for ((name, c) in listOf("neutral" to EmptyRim.WHITE, "xiaomi" to EmptyRim.XIAOMI)) {
            val sb = StringBuilder()
            for (ms in times) {
                val p = EmptyRim.Play(draft)
                // Before 200 ms the finger is pulling: the draft's clock stands in for it.
                val f = if (ms < 200) p.apply { pull(EmptyRim.easeOutCubic(ms / 200.0)) }.at(0.0)!!
                else p.apply { pull(1.0); release(0.0) }.at(ms - 200.0) ?: continue
                sb.append("frame $ms\n")
                for ((k, v) in EmptyRim.uniforms(draft, f, c)) sb.append(k).append(' ').append(v.joinToString(" ")).append('\n')
            }
            File(dir, "frames-$name.txt").writeText(sb.toString())
        }
        assertNotNull(File(dir, "rim.frag").takeIf { it.length() > 1000 })
    }
}
