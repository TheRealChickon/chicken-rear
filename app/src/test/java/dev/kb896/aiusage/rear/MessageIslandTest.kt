package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class MessageIslandTest {
    private val pillW = 560f
    private fun at(t: Float, hold: Float = MessageIsland.HOLD_MS.toFloat()) = MessageIsland.at(t, pillW, MessageIsland.Frame(), hold)
    private val open = (MessageIsland.IN_MS + MessageIsland.HOLD_MS).toFloat()

    @Test fun `the pill drops in at the top of what he sees, widens to its words, and is centred on x 636`() {
        val first = at(0f)
        assertTrue(first.shown)
        // From above the top edge (the draft's -28), small (132 x 38).
        assertEquals(-28f, first.cy, 0.01f)
        assertEquals(MessageIsland.SMALL_W, first.w, 0.01f)
        assertEquals(MessageIsland.SMALL_H, first.h, 0.01f)
        assertEquals("a capsule", first.h / 2f, first.r, 0.01f)
        // Grown: its words' width, 128 high, its centre at y 84; centred on what he sees, never under the lenses.
        val full = at(1100f)
        assertEquals(pillW, full.w, 0.5f)
        assertEquals(MessageIsland.PILL_H, full.h, 0.5f)
        assertEquals(MessageIsland.PILL_CY, full.cy, 0.5f)
        for (t in 0..2000 step 4) {
            val f = at(t.toFloat())
            if (f.p > 0f) break
            assertEquals("centred on x 636 at $t ms", 636f, f.cx, 0.01f)
            assertTrue("clear of the lenses at $t ms", f.cx - f.w / 2f >= 296f)
        }
        // Its words and icon come with the widening, not before.
        assertEquals(0f, at(100f).alpha, 0.001f)
        assertEquals(1f, at(1100f).alpha, 0.001f)
    }

    @Test fun `it grows into the whole back, holds as the full pop-up does, folds back and sinks into the top edge`() {
        val page = at(MessageIsland.IN_MS.toFloat() + 10f)
        assertEquals(1f, page.p, 1e-4f)
        assertEquals(488f, page.cx, 0.01f); assertEquals(298f, page.cy, 0.01f)
        assertEquals(976f, page.w, 0.01f); assertEquals(596f, page.h, 0.01f)
        assertEquals(MessageIsland.PAGE_R, page.r, 0.01f)
        // Held: the full pop-up's five seconds.
        assertEquals(ShellNotices.HOLD_MS, MessageIsland.HOLD_MS)
        assertEquals(1f, at(open - 1f).p, 0f)
        // Folding from its end, the pill again 800 ms on, gone 1.96 s after the hold.
        assertTrue(at(open + 400f).p in 0.05f..0.95f)
        assertEquals(0f, at(open + MessageIsland.FOLD_MS + 1f).p, 0f)
        assertEquals(MessageIsland.PILL_H, at(open + MessageIsland.FOLD_MS + 100f).h, 0.5f)
        assertTrue(at(MessageIsland.total() - 30f).shown)
        assertFalse(at(MessageIsland.total().toFloat()).shown)
        assertEquals(MessageIsland.IN_MS + MessageIsland.HOLD_MS + MessageIsland.OUT_MS, MessageIsland.total())
        // As it sinks: smaller and up into the top edge.
        val sinking = at(MessageIsland.total() - 60f)
        assertTrue(sinking.cy < 0f && sinking.w < pillW / 2f)
    }

    @Test fun `the draft's times, less its first 0_45 s of plain face`() {
        // render.py B: drop .45-.92, grow .72-1.28, open 1.65-2.45, fold from 5.75 (its 3.3 s hold), exit .3 s after the fold.
        assertEquals(470f, MessageIsland.DROP_TO, 0f)
        assertEquals(270f, MessageIsland.GROW_FROM, 0f); assertEquals(830f, MessageIsland.GROW_TO, 0f)
        assertEquals(1200f, MessageIsland.OPEN_FROM, 0f); assertEquals(2000f, MessageIsland.OPEN_TO, 0f)
        assertEquals(800f, MessageIsland.FOLD_MS, 0f); assertEquals(1100f, MessageIsland.EXIT_AFTER, 0f)
        assertEquals(500f, MessageIsland.CONDENSE_MS, 0f); assertEquals(860f, MessageIsland.SINK_TO, 0f)
        // The light goes once round as it arrives (0 to 1.07 s), and rests at 14% while the pill is read.
        assertEquals(0f, at(0f).turn, 1e-5f)
        assertEquals(1f, at(1070f).turn, 1e-5f)
        assertEquals(0.14f, at(1199f).light, 0.01f)
        // Strong as it grows, gone by the time it is open; back round the folding page; gathered as it leaves.
        assertTrue(at(1300f).light > 0.3f)
        assertEquals(0f, at(MessageIsland.IN_MS + 100f).light, 0f)
        assertTrue(at(open + 300f).light > 0.2f)
        val exit0 = open + MessageIsland.EXIT_AFTER
        assertTrue(at(exit0 + 200f).light > 0.9f)
        assertEquals(1f - 0.28f, at(exit0 + 820f).turn, 1e-4f)
    }

    @Test fun `the pill's words and the page's never show together, and no line is stretched between them`() {
        var t = 0f
        var pageSeen = false
        while (t < MessageIsland.total()) {
            val f = at(t)
            if (f.shown) {
                assertFalse("both at $t ms: pill ${f.pillInk}, page ${f.pageInk}", f.pillInk > 0.001f && f.pageInk > 0.001f)
                // The page's words only once it is (nearly) all open, the pill's only while it is a pill (or nearly).
                if (f.pageInk > 0f) { assertTrue(f.p >= 0.98f); pageSeen = true }
                // The pill's words, while they show on a growing or folding shape, are never stretched with it (their scale is
                // the pill's own, 1 from the moment it starts to open) and stay inside it.
                if (f.pillInk > 0.01f && f.alpha > 0f && f.p > 0f) assertTrue("pill words outside at $t ms", f.w >= pillW - 0.01f && f.h >= MessageIsland.PILL_H - 0.01f)
                if (f.pillInk > 0.01f && f.alpha > 0f) assertTrue("pill words while it is half open at $t ms (p ${f.p})", f.p < 0.5f)
            }
            t += 1f
        }
        assertTrue(pageSeen)
        // Its 80 ms each way.
        assertEquals(1f, at(MessageIsland.OPEN_FROM).pillInk, 1e-5f)
        assertEquals(0f, at(MessageIsland.OPEN_FROM + MessageIsland.INK_MS).pillInk, 1e-5f)
        assertEquals(0f, at(open + MessageIsland.INK_MS).pageInk, 1e-5f)
    }

    @Test fun `no jump from one picture to the next at 120 Hz, and the held page draws nothing`() {
        val step = 1000f / 120f
        var prev = at(0f)
        var t = step
        var worst = 0f
        while (t < MessageIsland.total() - step) {
            val f = at(t)
            if (prev.shown && f.shown) {
                val moved = maxOf(abs(f.cx - prev.cx), abs(f.cy - prev.cy), abs(f.w - prev.w), abs(f.h - prev.h))
                worst = maxOf(worst, moved)
                assertTrue("a ${moved}px jump at $t ms", moved < 40f)
                assertTrue("light ${prev.light} -> ${f.light} at $t ms", abs(f.light - prev.light) < 0.12f)
            }
            prev = f; t += step
        }
        println("message island: the largest step of its shape in one picture at 120 Hz: $worst px")
        // Held: every picture the same as the last (Frame.same): the layer is not drawn again.
        val still = { t: Float -> MessageIsland.at(t, pillW, MessageIsland.Frame(), sweeps = false) }
        assertTrue(still(MessageIsland.IN_MS + 500f).same(still(open - 50f)))
        // (With the sweep, from the moment it has passed.)
        assertTrue(at(MessageIsland.SWEEP_TO + 10f).same(at(open - 50f)))
        // But the sweep moves during the first part of the hold.
        assertTrue(at(MessageIsland.SWEEP_FROM + 900f).sweep > 0f)
        assertFalse(at(MessageIsland.SWEEP_FROM + 900f).same(at(MessageIsland.SWEEP_FROM + 1500f)))
    }

    @Test fun `a message coming while it folds opens again from the nearest shape - no jump`() {
        for (t in listOf(open + 100f, open + 400f, open + 790f, open + 1200f, open + 1700f)) {
            val was = at(t)
            val from = MessageIsland.rejoin(t, pillW)
            assertTrue(from in 0f..MessageIsland.IN_MS.toFloat())
            val now = at(from)
            val d = maxOf(abs(now.cx - was.cx), abs(now.cy - was.cy), abs(now.w - was.w), abs(now.h - was.h))
            assertTrue("a $d px jump rejoining at $t ms (from $from)", d < 30f)
        }
        // Open or opening: its own time (the words change in place).
        assertEquals(1500f, MessageIsland.rejoin(1500f, pillW), 0f)
    }

    @Test fun `Xiaomi's palette and the light's three falloffs, as the draft has them`() {
        val hex = listOf("F7BE98", "E2A28C", "AE8ECE", "6E7FFE", "4B83FC", "8F8EF4", "BAA9DA", "E8C5BE", "F7BE98")
        for ((i, h) in hex.withIndex()) for (c in 0..2) assertEquals(h.substring(2 * c, 2 * c + 2).toInt(16) / 255f, MessageIsland.PALETTE[i][c], 1e-6f)
        // At full strength, just outside the outline: 1 - exp(-light * colour * 1.15), light = the halo times the head.
        val f = at(1000f)
        val out = FloatArray(3)
        val x = f.cx; val y = f.cy + f.h / 2f + 30f   // 30 below the pill's foot
        val d = MessageIsland.sdf(x, y, f.cx, f.cy, f.w, f.h, f.r)
        assertEquals(30f, d, 0.01f)
        MessageIsland.light(x, y, d, f, out)
        val halo = 0.32f * exp(-(30f / 13f) * (30f / 13f)) + 0.34f * exp(-1f) + 0.16f * exp(-30f / 95f)
        val light = f.light * halo                     // the lip is nothing out here, the head between 0.78 and 1
        val most = 1f - exp(-light * 1.15f)
        for (c in 0..2) assertTrue(out[c] in 0f..most + 1e-4f)
        assertTrue("some light there", out.max() > 0.02f)
        // No light at all once it is open.
        MessageIsland.light(x, y, d, at(MessageIsland.IN_MS + 100f), out)
        assertEquals(0f, out.max(), 0f)
    }

    @Test fun `the AGSL is the Kotlin formula - the same numbers in both`() {
        val agsl = MessageIsland.AGSL
        for (line in listOf(
            "0.32 * exp(-(e / 13.0) * (e / 13.0)) + 0.34 * exp(-e / 30.0) + 0.16 * exp(-e / 95.0)",
            "float interior = 0.075 + 0.745 * exp(-depth / 7.0);",
            "float lip = 0.23 * exp(-(d / 3.5) * (d / 3.5));",
            "float head = 0.78 + 0.22 * exp(-(hd / 0.12) * (hd / 0.12));",
            "float hot = c * c * c * 0.10;",
            "return float3(1.0) - exp(-light * col * 1.15);",
            "float blue = gs(dd + 34.0, 112.0); float warm = gs(dd - 105.0, 89.0); float bloom = gs(dd, 194.0);",
            "(0.20 * blue * P4 + 0.15 * warm * P0 + 0.016 * bloom * P6)",
            "float shadow = d > 0.0 ? 0.18 * exp(-d / 9.0) : 0.0;",
        )) assertTrue("the shader has: $line", agsl.contains(line))
        for (i in 0..8) assertTrue(agsl.contains("const float3 P$i = "))
        // Its uniforms are the ones IslandArt sets, one each.
        for (name in MessageIsland.UNIFORM_NAMES) assertEquals(name, 1, Regex("""uniform float\d? $name;""").findAll(agsl).count())
        assertEquals(MessageIsland.UNIFORM_NAMES.size, MessageIsland.uniformArrays().size)
    }

    @Test fun `Tint leaves the face as it is but for a soft shade behind the words - Sweep dims it to a fifth`() {
        val words = MessageIsland.layout(80f, 400f, true, 200f, 520f, 221f, 316f, 69.6f, 198f, 398f)
        val f = at(MessageIsland.IN_MS + 500f)
        val px = FloatArray(4); val s = FloatArray(6)
        val noWash = floatArrayOf(0f, 0f, 0f)
        // Far from the words: nothing laid over the live face at all.
        MessageIsland.pixel(320f, 40f, 1f, f, MessageIsland.Look.TINT_OVER_FACE, words, noWash, px, s)
        assertEquals(0f, px[3], 0.004f)
        // Behind the words: a shade, never more than the look's own.
        MessageIsland.pixel(636f, 300f, 1f, f, MessageIsland.Look.TINT_OVER_FACE, words, noWash, px, s)
        assertTrue(px[3] > 0.2f && px[3] <= MessageIsland.Look.TINT_OVER_FACE.shade + 0.004f)
        assertTrue("black shade", maxOf(px[0], px[1], px[2]) < 0.01f)
        // Sweep, the band past: four fifths black over the face.
        val rest = at(MessageIsland.SWEEP_TO + 200f)
        MessageIsland.pixel(320f, 40f, 1f, rest, MessageIsland.Look.SWEEP, words, noWash, px, s)
        assertEquals(0.8f, px[3], 0.01f)
        // The band, as it passes, is Xiaomi's light behind the words.
        val passing = at(MessageIsland.SWEEP_FROM + 1500f)
        MessageIsland.pixel(passing.sweepX, MessageIsland.MIDDLE, 1f, passing, MessageIsland.Look.SWEEP, words, noWash, px, s)
        assertTrue("light in the band", maxOf(px[0], px[1], px[2]) > 0.08f)
        // The pill itself is black glass while it is a pill.
        val pill = at(1100f)
        MessageIsland.pixel(pill.cx, pill.cy, 1f, pill, MessageIsland.Look.TINT_OVER_FACE, words, noWash, px, s)
        assertEquals(1f, px[3], 0.01f)
    }

    @Test fun `what can be drawn lies within its bounds, and the rest of the layer is clear`() {
        val b = FloatArray(4)
        val px = FloatArray(4); val s = FloatArray(6)
        val words = MessageIsland.layout(80f, 400f, true, 200f, 520f, 221f, 316f, 69.6f, 198f, 398f)
        for (t in listOf(150f, 600f, 1000f, open + 1600f)) {
            val f = at(t)
            MessageIsland.bounds(f, b)
            for (y in 0 until 596 step 7) for (x in 0 until 976 step 7) {
                if (x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3]) continue
                MessageIsland.pixel(x + 0.5f, y + 0.5f, 1f, f, MessageIsland.Look.SWEEP, words, floatArrayOf(0f, 0f, 0f), px, s)
                assertTrue("at $t ms, ($x, $y) outside its bounds: alpha ${px[3]}", px[3] < 1.5f / 255f)
            }
        }
        MessageIsland.bounds(at(1500f), b)
        assertEquals(listOf(0f, 0f, 976f, 596f), b.toList())
    }

    @Test fun `a picture's sums make nothing - the timeline and the tint's read allocate no memory`() {
        // The JVM's own count of what a thread allocated (com.sun.management.ThreadMXBean), by reflection: the Android
        // classpath the tests compile against has no java.lang.management.
        val bean = runCatching { Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null) }.getOrNull() ?: return
        val count = runCatching { Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType) }.getOrNull() ?: return
        fun allocated(id: Long) = count.invoke(bean, id) as Long
        val f = MessageIsland.Frame()
        val veil = NoticeVeil(); val vf = NoticeVeil.Frame()
        veil.begin(0xFF25D366.toInt(), 0L, pillW)
        // Warmed up first (class loading, the JIT's own first allocations).
        for (i in 0 until 20_000) { MessageIsland.at((i % 9000).toFloat(), pillW, f); veil.read((i % 9000).toLong(), vf) }
        val id = Thread.currentThread().id
        val before = allocated(id)
        var sum = 0f
        for (i in 0 until 50_000) {
            MessageIsland.at((i % 8900).toFloat(), pillW, f); sum += f.w
            if (veil.read((i % 8900).toLong(), vf)) sum += vf.amount
        }
        val made = allocated(id) - before
        println("message island: 50 000 pictures' sums made $made bytes ($sum)")
        assertTrue("$made bytes made", made < 4_096)
    }
}
