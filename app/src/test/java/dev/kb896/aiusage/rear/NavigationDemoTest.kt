package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The debug file's made-up route: "nav|distance|instruction|eta|progress" (and the line under it). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationDemoTest {
    @Test fun `the line's words go where Maps puts them, and it is always a demo, with no art of its own`() {
        val m = NavigationDemo.parse("nav|2 km|Turn right|08:16|0.55|toward Example Way".split('|'))!!
        assertTrue(m.demo)
        assertEquals("2 km", m.short); assertEquals("Turn right", m.title); assertEquals("toward Example Way", m.text)
        assertEquals("08:16", m.eta)
        assertEquals(0.55f, m.fraction!!, 0f)
        assertEquals("2 km${NavFace.DOT}Turn right\ntoward Example Way", NavFace.words(m))
        assertNull(m.appIcon); assertNull(m.maneuver); assertNull(m.tracker)
        // Five parts are enough.
        assertEquals("", NavigationDemo.parse("nav|2 km|Turn right|08:16|0.55".split('|'))!!.text)
    }

    @Test fun `no progress is no bar, and a progress out of 0 to 1 is no demo`() {
        val m = NavigationDemo.parse("nav|Follow the road|||".split('|'))!!
        assertNull(m.fraction); assertNull(m.progressMax); assertTrue(m.segments.isEmpty())
        assertTrue(NavFace.pieces(m, m.fraction).isEmpty())
        for (value in listOf("NaN", "Infinity", "-1", "1.1", "bad")) assertNull(NavigationDemo.parse("nav|a|b|c|$value".split('|')))
        assertNull(NavigationDemo.parse(listOf("nav")))
        assertNull(NavigationDemo.parse("nav|a|b|c|0.5|d|e".split('|')))
        assertNull(NavigationDemo.parse("timer|a|b|c|0.5".split('|')))
    }
}
