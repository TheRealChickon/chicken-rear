package dev.kb896.aiusage.rear

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class EarbudsEdgeTest {
    private val portrait = EarbudsEdge.Geometry(1200f, 2608f, 170f, 170f, 170f, 170f)
    private val landscape = EarbudsEdge.Geometry(2608f, 1200f, 170f, 170f, 170f, 170f)
    private val uneven = EarbudsEdge.Geometry(1200f, 2608f, 130f, 150f, 170f, 190f)
    private val scratch = FloatArray(2)
    private fun rgba(g: EarbudsEdge.Geometry, t: Float, x: Float, y: Float): FloatArray = FloatArray(4).also {
        EarbudsEdge.light(EarbudsEdge.at(t), g, x, y, it, scratch)
    }

    @Test fun `all four edges bloom together then live and recede into the glass`() {
        assertTrue(EarbudsEdge.IN_MS in 250L..350L)
        assertTrue(EarbudsEdge.RECEDE_MS - EarbudsEdge.IN_MS in 900L..1200L)
        assertEquals(400L, EarbudsEdge.RUN_MS - EarbudsEdge.RECEDE_MS)
        assertTrue(EarbudsEdge.RUN_MS in 1600L..1900L)
        assertEquals(0f, EarbudsEdge.at(0f).amp, 0f)
        assertEquals(1f, EarbudsEdge.at(300f).amp, 0f)
        assertEquals(1f, EarbudsEdge.at(1350f).amp, 0f)
        assertEquals(0f, EarbudsEdge.at(1750f).amp, 0f)
        assertTrue(EarbudsEdge.at(1550f).spread < EarbudsEdge.at(1350f).spread)
        for (g in listOf(portrait, landscape)) {
            for (t in listOf(120f, 300f, 850f, 1350f)) {
                for ((x,y) in listOf(0.5f to g.h/2, g.w-0.5f to g.h/2, g.w/2 to 0.5f, g.w/2 to g.h-0.5f)) {
                    assertTrue("every edge at $t", rgba(g,t,x,y)[3] > 0.12f)
                }
            }
        }
    }

    @Test fun `geometry respects independent corners current size and measured fallback`() {
        val g = EarbudsEdge.Geometry(1200f, 2608f, 130f, 150f, 170f, 180f)
        for ((x, y) in listOf(130f to 0f, 0f to 130f, 1200f to 150f, 1050f to 0f,
            1200f to 2438f, 1030f to 2608f, 180f to 2608f, 0f to 2428f)) {
            g.sample(x, y, scratch); assertEquals("on boundary ($x,$y)", 0f, scratch[0], 0.0001f)
        }
        val fallback = EarbudsEdge.Geometry(600f, 1304f, 0f, Float.NaN, -1f, Float.POSITIVE_INFINITY)
        assertEquals(85f, fallback.tl, 0f); assertEquals(85f, fallback.tr, 0f)
        assertEquals(85f, fallback.br, 0f); assertEquals(85f, fallback.bl, 0f)
        assertEquals(portrait.band, landscape.band, 0f)
        val area = 2f * portrait.w * portrait.strip + 2f * portrait.strip * (portrait.h - 2f * portrait.strip)
        assertTrue("deep swells still shade less than half the display", area / (portrait.w * portrait.h) < 0.47f)
        assertEquals(EarbudsEdge.BAND, portrait.strip, 0f)
    }

    @Test fun `centre and outside glass stay clear and every channel is zero before and after`() {
        for (g in listOf(portrait, landscape, uneven)) {
            for (i in 0..212) {
                val t = i * (1000f / 120f)
                assertArrayEquals(FloatArray(4), rgba(g,t,g.w/2,g.h/2), 0f)
                assertArrayEquals(FloatArray(4), rgba(g,t,0f,0f), 0f)
                assertArrayEquals(FloatArray(4), rgba(g,t,g.band,g.h/2), 0f)
            }
            for (t in listOf(-100f, 0f, EarbudsEdge.RUN_MS.toFloat(), EarbudsEdge.RUN_MS + 100f)) {
                for (y in 0..g.h.toInt() step 47) for (x in 0..g.w.toInt() step 43) {
                    assertArrayEquals("all dark at $t", FloatArray(4), rgba(g,t,x.toFloat(),y.toFloat()), 0f)
                }
            }
        }
    }

    @Test fun `no frame jump at 120 Hz and all output is finite premultiplied light`() {
        val px = FloatArray(4); val prev = FloatArray(4)
        val frame = EarbudsEdge.Frame()
        for (g in listOf(portrait, landscape, uneven)) for (y in 0..g.h.toInt() step 61) for (x in 0..g.w.toInt() step 59) {
            prev.fill(0f)
            for (i in 0..212) {
                EarbudsEdge.light(EarbudsEdge.at(i * (1000f / 120f), frame), g, x.toFloat(), y.toFloat(), px, scratch)
                for (c in 0..3) {
                    assertTrue(px[c].isFinite()); assertTrue(px[c] in 0f..1f)
                    if (c < 3) assertTrue(px[c] <= px[3] + 1e-6f)
                    assertTrue("step ${abs(px[c] - prev[c])} at $i ($x,$y)", abs(px[c] - prev[c]) < 0.045f)
                    prev[c] = px[c]
                }
            }
        }
    }

    @Test fun `lobes vary deeply along the edge and with time without rigid mirroring`() {
        for (g in listOf(portrait, landscape)) {
            for (t in listOf(300f, 700f, 1100f, 1350f)) {
                val left = (250..g.h.toInt()-250 step 20).map { rgba(g,t,70f,it.toFloat())[3] }
                val right = (250..g.h.toInt()-250 step 20).map { rgba(g,t,g.w-70f,it.toFloat())[3] }
                assertTrue("left-right balance $t", left.average() / right.average() in 0.45..2.2)
                assertTrue("not mirrored", left.zip(right).map { abs(it.first-it.second) }.average() > 0.05)
            }
            // Measure the visible reach across the full perimeter, including landscape's long horizontal edges.
            fun depths(t: Float) = (0..3).flatMap { edge ->
                listOf(0.20f,0.35f,0.50f,0.65f,0.80f).map { u ->
                    (0..215).last { distance ->
                        val d=distance.toFloat()
                        val (x,y) = when (edge) {
                            0 -> d to g.h*u
                            1 -> g.w-d to g.h*u
                            2 -> g.w*u to d
                            else -> g.w*u to g.h-d
                        }
                        rgba(g,t,x,y)[3] > 0.06f
                    }
                }
            }
            for (t in listOf(300f,700f,1100f,1350f)) {
                val reach = depths(t)
                assertTrue("deep swells reach 10–15 percent inward", reach.max() in 120..180)
                assertTrue("some areas remain thin", reach.min() < 55)
                assertTrue("no even-width band", reach.max() > reach.min()*3)
            }
            assertTrue("lobes change their depth over time", depths(350f).zip(depths(1250f)).maxOf { abs(it.first-it.second) } > 50)

        }
    }

    @Test fun `soft tails have no inner boundary and palette stays vivid over white`() {
        for (t in listOf(300f, 850f, 1350f)) {
            for (y in listOf(350f, 1000f, 2000f)) {
                val fade = (0..216).map { rgba(portrait,t,it.toFloat(),y)[3] }
                assertTrue("no inner bright ridge", fade.zipWithNext().all { it.second <= it.first + 0.001f })
                assertEquals(0f, fade.last(), 0f)
                assertTrue("no cut at support", fade[215] < 0.0001f)
                val px = rgba(portrait,t,3f,y)
                val white = (0..2).map { px[it] + 1f-px[3] }
                assertTrue("colour remains saturated on white", white.max()-white.min() > 0.25f)
            }
        }
    }

    @Test fun `angle seam and nearest edge transitions remain continuous inside deep corners`() {
        // Deep glow crosses a corner's radius; colour must not jump as the nearest boundary changes.
        for (g in listOf(portrait, landscape, uneven)) for (t in listOf(300f,850f,1350f)) {
            for (d in listOf(1f,40f,100f,169f,190f,215f)) {
                for ((x,y) in listOf(d to g.h/2, d to d, g.w-d to d, g.w-d to g.h-d, d to g.h-d)) {
                    val a=rgba(g,t,x-0.01f,y-0.01f); val b=rgba(g,t,x+0.01f,y+0.01f)
                    for (c in 0..3) assertEquals("no spatial seam at ($x,$y)",a[c],b[c],0.002f)
                }
            }
        }
    }

    @Test fun `every real and quarter speed frame has bounded spatial luminance gradients`() {
        // Quartic F has max |F'| = (16f / 9f); amp/spread <= 1. Angular variation
        // must fit the same total gradient budget as the narrowest radial lobe.
        val slope = EarbudsEdge.PEAK * (16f / 9f) / EarbudsEdge.MIN_REACH
        val f = EarbudsEdge.Frame()
        val a = FloatArray(4); val b = FloatArray(4); val c = FloatArray(4)
        fun luminance(px: FloatArray, bg: FloatArray) =
            .2126f * (px[0] + bg[0]*(1-px[3])) +
                .7152f * (px[1] + bg[1]*(1-px[3])) + .0722f * (px[2] + bg[2]*(1-px[3]))
        val backgrounds = listOf(floatArrayOf(14/255f,15/255f,18/255f),floatArrayOf(245/255f,245/255f,247/255f))
        val small = EarbudsEdge.Geometry(600f,1304f,85f,85f,85f,85f)
        val smallWide = EarbudsEdge.Geometry(1304f,600f,85f,85f,85f,85f)
        for (g in listOf(portrait,landscape,uneven,small,smallWide)) {
            val strip = kotlin.math.ceil(g.strip).toInt()
            fun axes(size: Int) = ((0 until size-1 step (12*g.k).toInt()) +
                listOf(strip-1,strip,size-strip-1,size/2-1,size/2)).distinct()
            val points = ArrayList<Pair<Float,Float>>()
            for (y in axes(g.h.toInt())) for (x in axes(g.w.toInt())) {
                val px=x+.5f; val py=y+.5f
                g.sample(px,py,scratch)
                if (scratch[0] < 0 || scratch[0] > g.band+2) continue
                g.sample(px+1,py,scratch); if (scratch[0]<0) continue
                g.sample(px,py+1,scratch); if (scratch[0]<0) continue
                points.add(px to py)
            }
            var worst=0f
            val threshold=slope/g.k+0.00002f
            for (i in 0 until 516) {
                EarbudsEdge.at(i*1000f/240f,f)
                for ((x,y) in points) {
                    EarbudsEdge.light(f,g,x,y,a,scratch)
                    if (scratch[0]/g.k >= EarbudsEdge.MAX_REACH) assertEquals(0f,a[3],0f)
                    EarbudsEdge.light(f,g,x+1,y,b,scratch)
                    EarbudsEdge.light(f,g,x,y+1,c,scratch)
                    for (bg in backgrounds) {
                        val base=luminance(a,bg)
                        val delta=maxOf(abs(luminance(b,bg)-base),abs(luminance(c,bg)-base))
                        worst=maxOf(worst,delta)
                        if (delta>threshold) fail("spatial step $delta > $threshold at frame $i ($x,$y), ${g.w}x${g.h}")
                    }
                    if (x>=strip && x<g.w-strip && y>=strip && y<g.h-strip) assertEquals(0f,a[3],0f)
                }
            }
            println("Siri spatial ${g.w}x${g.h}: 516 frames, two backgrounds, max=$worst threshold=$threshold points=${points.size}")
        }
    }

    @Test fun `periodic field joins with equal values and derivatives and no bezel core`() {
        val a=FloatArray(4); val b=FloatArray(4); val c=FloatArray(4); val e=FloatArray(4)
        val pi=kotlin.math.PI.toFloat()
        val epsilon=.002f
        for (i in 0 until 516) {
            val f=EarbudsEdge.at(i*1000f/240f)
            for (d in 0..216 step 8) {
                EarbudsEdge.shade(f,-pi,d.toFloat(),a); EarbudsEdge.shade(f,pi,d.toFloat(),b)
                for (channel in 0..3) assertEquals("wrap value",a[channel],b[channel],.00001f)
                // Centred derivatives compare the same tangent, not two different secants.
                EarbudsEdge.shade(f,-pi-epsilon,d.toFloat(),a); EarbudsEdge.shade(f,-pi+epsilon,d.toFloat(),b)
                EarbudsEdge.shade(f,pi-epsilon,d.toFloat(),c); EarbudsEdge.shade(f,pi+epsilon,d.toFloat(),e)
                for (channel in 0..3) assertEquals("wrap derivative",
                    (b[channel]-a[channel])/(2*epsilon),(e[channel]-c[channel])/(2*epsilon),.001f)
            }
            for (j in 0..120) {
                val angle=-pi+j*2*pi/120
                EarbudsEdge.shade(f,angle,0f,a); EarbudsEdge.shade(f,angle,.01f,b)
                EarbudsEdge.shade(f,angle,EarbudsEdge.MAX_REACH-.01f,c)
                EarbudsEdge.shade(f,angle,EarbudsEdge.MAX_REACH,e)
                for (channel in 0..3) {
                    assertEquals("flat bezel, no thin core",a[channel],b[channel],.00001f)
                    assertEquals("flat zero tail",0f,c[channel],.00001f)
                    assertEquals(0f,e[channel],0f)
                }
            }
        }
    }

    @Test fun `warmup costs no visible time and frame storage is reused`() {
        val c = EarbudsEdge.Clock()
        assertEquals(0f, c.at(10_000L), 0f); assertFalse(c.running)
        assertEquals(0f, c.at(10_090L), 0f); assertTrue(c.running)
        assertEquals(8f, c.at(10_098L), 0f)
        val f = EarbudsEdge.Frame()
        assertSame(f, EarbudsEdge.at(100f, f))
        assertSame(f, EarbudsEdge.at(200f, f))
    }
}
