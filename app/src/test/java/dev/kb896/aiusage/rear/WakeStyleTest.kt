package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.ChargeAnimStyle
import dev.kb896.aiusage.data.WakeAnimStyle
import dev.kb896.aiusage.rear.WakeStyle.Light
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wake animations' timelines over Xiaomi's rear, and when one plays. */
class WakeStyleTest {

    /** The 17 Pro Max's rear: 976 x 596, the lenses' 296 px on the left, its glass's corners. */
    private val safe = ChargeStyle.safeArea(976, 596, 296)
    private val corner = 60f
    private val drawing = WakeAnimStyle.entries.filter { it != WakeAnimStyle.NONE }

    private fun samples(style: WakeAnimStyle): List<Float> {
        val total = WakeStyle.durationMs(style).toFloat()
        return (0 until 200).map { it * total / 200f }
    }

    @Test fun `every style but None lasts 500 to 900 ms`() {
        for (s in drawing) {
            val d = WakeStyle.durationMs(s)
            assertTrue("$s lasts $d ms", d in WakeStyle.MIN_MS..WakeStyle.MAX_MS)
        }
        assertEquals(500L, WakeStyle.MIN_MS)
        assertEquals(900L, WakeStyle.MAX_MS)
    }

    @Test fun `None draws nothing at any time`() {
        assertEquals(0L, WakeStyle.durationMs(WakeAnimStyle.NONE))
        for (t in listOf(-1f, 0f, 1f, 100f, 500f, 900f, 5_000f)) {
            assertNull(WakeStyle.frame(WakeAnimStyle.NONE, t, safe, corner))
        }
    }

    @Test fun `each style draws from its first frame and nothing at or past its end`() {
        for (s in drawing) {
            val d = WakeStyle.durationMs(s).toFloat()
            assertNotNull("$s at 0", WakeStyle.frame(s, 0f, safe, corner))
            assertNull("$s before its start", WakeStyle.frame(s, -1f, safe, corner))
            assertNull("$s at its end", WakeStyle.frame(s, d, safe, corner))
            assertNull("$s past its end", WakeStyle.frame(s, d + 1_000f, safe, corner))
        }
    }

    @Test fun `each style ends fully transparent`() {
        for (s in drawing) {
            val d = WakeStyle.durationMs(s).toFloat()
            val last = WakeStyle.frame(s, d - 0.01f, safe, corner)!!
            assertEquals("$s's last frame", 0f, last.alpha, 0.001f)
            if (last is WakeStyle.Frame.Blur) {
                assertEquals("no blur left", 0, last.blurPx)
                assertEquals("no dim left", 0f, last.dim, 0.001f)
            }
        }
    }

    @Test fun `every frame of every style stays in the safe area`() {
        for (s in drawing) for (t in samples(s)) {
            val f = WakeStyle.frame(s, t, safe, corner) ?: continue
            val b = f.drawn
            assertTrue("$s at $t ms: $b left of the safe area", b.left >= safe.left - 0.001f)
            assertTrue("$s at $t ms: $b above it", b.top >= safe.top - 0.001f)
            assertTrue("$s at $t ms: $b right of it", b.right <= safe.right + 0.001f)
            assertTrue("$s at $t ms: $b below it", b.bottom <= safe.bottom + 0.001f)
            assertTrue("$s at $t ms: alpha ${f.alpha}", f.alpha in 0f..1f)
        }
    }

    @Test fun `the edge glow goes once round, its run never longer than the tail`() {
        var lastHead = 0f
        for (t in samples(WakeAnimStyle.EDGE_GLOW)) {
            val f = WakeStyle.frame(WakeAnimStyle.EDGE_GLOW, t, safe, corner) as WakeStyle.Frame.Edge
            assertTrue(f.headAt >= lastHead - 0.0001f)
            assertTrue(f.headAt - f.tailAt <= WakeStyle.EDGE_TAIL + 0.0001f)
            lastHead = f.headAt
        }
        assertEquals(1f, lastHead, 0.0001f)
    }

    @Test fun `the blur reveal starts blurred and comes in to sharp`() {
        val first = WakeStyle.frame(WakeAnimStyle.BLUR_REVEAL, 0f, safe, corner) as WakeStyle.Frame.Blur
        assertEquals(WakeStyle.BLUR_START_PX, first.blurPx)
        assertEquals(1f, first.alpha, 0.0001f)
        var last = first.blurPx
        for (t in samples(WakeAnimStyle.BLUR_REVEAL)) {
            val f = WakeStyle.frame(WakeAnimStyle.BLUR_REVEAL, t, safe, corner) as WakeStyle.Frame.Blur
            assertTrue(f.blurPx <= last)
            last = f.blurPx
        }
    }

    @Test fun `the ripple starts at the camera island and grows`() {
        val first = WakeStyle.frame(WakeAnimStyle.RIPPLE, 0f, safe, corner) as WakeStyle.Frame.Ripple
        assertEquals(safe.left, first.cx, 0.001f)
        assertEquals(safe.centreY, first.cy, 0.001f)
        val later = WakeStyle.frame(WakeAnimStyle.RIPPLE, 400f, safe, corner) as WakeStyle.Frame.Ripple
        assertTrue(later.radius > first.radius)
    }

    @Test fun `the liquid rises from the bottom and leaves at the top`() {
        val early = WakeStyle.frame(WakeAnimStyle.LIQUID_SWEEP, 100f, safe, corner) as WakeStyle.Frame.Liquid
        val late = WakeStyle.frame(WakeAnimStyle.LIQUID_SWEEP, 600f, safe, corner) as WakeStyle.Frame.Liquid
        assertTrue(late.top < early.top)
        assertEquals(safe.bottom, early.bottom, 0.001f)
    }

    // -- when one plays -------------------------------------------------------------------------

    @Test fun `the display's states read as off, doze and on`() {
        assertEquals(Light.ON, WakeStyle.light(2))
        assertEquals(Light.OFF, WakeStyle.light(1))
        assertEquals(Light.DOZE, WakeStyle.light(3))
        assertEquals(Light.DOZE, WakeStyle.light(4))
        assertEquals(Light.DOZE, WakeStyle.light(6))
        assertEquals(Light.OTHER, WakeStyle.light(0))
        assertNull(WakeStyle.light(null))
    }

    @Test fun `a wake is off or AOD to on, and nothing else`() {
        assertTrue(WakeStyle.isWakeEdge(Light.OFF, Light.ON))
        assertTrue(WakeStyle.isWakeEdge(Light.DOZE, Light.ON))
        // Into AOD, or out of it to dark: AOD untouched.
        assertFalse(WakeStyle.isWakeEdge(Light.ON, Light.DOZE))
        assertFalse(WakeStyle.isWakeEdge(Light.OFF, Light.DOZE))
        assertFalse(WakeStyle.isWakeEdge(Light.DOZE, Light.OFF))
        // Lit and lit again (a refresh rate's change), or a first reading.
        assertFalse(WakeStyle.isWakeEdge(Light.ON, Light.ON))
        assertFalse(WakeStyle.isWakeEdge(null, Light.ON))
        assertFalse(WakeStyle.isWakeEdge(Light.OTHER, Light.ON))
    }

    @Test fun `skipped over an app, the camera, off, None or replace mode`() {
        val e = WakeAnimStyle.EDGE_GLOW
        assertNull(WakeStyle.skipWhy(companion = true, on = true, style = e, guestOnTop = false, cameraOnRear = false))
        assertNotNull(WakeStyle.skipWhy(companion = true, on = true, style = e, guestOnTop = true, cameraOnRear = false))
        assertNotNull(WakeStyle.skipWhy(companion = true, on = true, style = e, guestOnTop = false, cameraOnRear = true))
        assertNotNull(WakeStyle.skipWhy(companion = true, on = false, style = e, guestOnTop = false, cameraOnRear = false))
        assertNotNull(WakeStyle.skipWhy(companion = true, on = true, style = WakeAnimStyle.NONE, guestOnTop = false, cameraOnRear = false))
        assertNotNull(WakeStyle.skipWhy(companion = false, on = true, style = e, guestOnTop = false, cameraOnRear = false))
    }

    @Test fun `previews round-trip, and anything else is refused`() {
        for (s in WakeAnimStyle.entries) {
            val p = WakeStyle.Preview.Wake(s)
            assertEquals(p, WakeStyle.parsePreview(WakeStyle.encode(p)))
        }
        for (s in ChargeAnimStyle.entries) {
            val p = WakeStyle.Preview.Charge(s, 73)
            assertEquals(p, WakeStyle.parsePreview(WakeStyle.encode(p)))
        }
        assertEquals("charge:LIQUID:73", WakeStyle.encode(WakeStyle.Preview.Charge(ChargeAnimStyle.LIQUID, 73)))
        for (bad in listOf(null, "", "edge_glow", "SPARKLE", "charge:LIQUID", "charge:LIQUID:101", "charge:LIQUID:-1", "charge:PLASMA:50", "wake:EDGE_GLOW:1")) {
            assertNull("\"$bad\"", WakeStyle.parsePreview(bad))
        }
    }
}
