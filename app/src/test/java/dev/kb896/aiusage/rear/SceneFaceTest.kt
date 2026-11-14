package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class SceneFaceTest {
    private val friday = GregorianCalendar(2026, Calendar.NOVEMBER, 13, 21, 7)

    @Test fun `the words at a moment, 24 hour`() {
        val w = SceneFace.words(SceneFace.Look(level = 82), friday)
        assertEquals("21", w.hours)
        assertEquals("07", w.minutes)
        assertEquals("FRI 13 NOV", w.date)
        assertEquals("82%", w.battery)
    }

    @Test fun `12 hour keeps two digits, for the clock's four boxes, and wraps midnight to 12`() {
        assertEquals("09", SceneFace.words(SceneFace.Look(hour24 = false), friday).hours)
        assertEquals("12", SceneFace.words(SceneFace.Look(hour24 = false), GregorianCalendar(2026, 0, 1, 0, 30)).hours)
    }

    @Test fun `an unknown battery has no words and is left out`() {
        assertEquals("", SceneFace.words(SceneFace.Look(level = -1), friday).battery)
        assertTrue(SceneFace.Look(clock = false, battery = true, level = -1).empty)
    }

    @Test fun `the key changes with the minute, the level and the look, and not otherwise`() {
        val look = SceneFace.Look(level = 50)
        val later = GregorianCalendar(2026, Calendar.NOVEMBER, 13, 21, 8)
        assertEquals(SceneFace.key(look, friday), SceneFace.key(look, GregorianCalendar(2026, Calendar.NOVEMBER, 13, 21, 7)))
        assertNotEquals(SceneFace.key(look, friday), SceneFace.key(look, later))
        assertNotEquals(SceneFace.key(look, friday), SceneFace.key(look.copy(level = 51), friday))
        assertNotEquals(SceneFace.key(look, friday), SceneFace.key(look.copy(date = false), friday))
    }

    @Test fun `no battery on the face unless asked for`() {
        assertEquals(false, SceneFace.Look().battery)
    }

    @Test fun `a clip's side file gives its clock's colours, and nonsense gives none`() {
        val place = SceneFace.Place.parse("""{"clock": {"x": 590, "y": 70, "hour": "#CFD3F7", "minute": "#4652DB"}}""")
        assertEquals(SceneFace.Place(0xFFCFD3F7.toInt(), 0xFF4652DB.toInt()), place)
        assertEquals(null, SceneFace.Place.parse("{}"))
        assertEquals(null, SceneFace.Place.parse("""{"clock": {"x": 590}}"""))
        // One colour given: both lines take it.
        assertEquals(0xFFCFD3F7.toInt(), SceneFace.Place.parse("""{"clock": {"hour": "#CFD3F7"}}""")!!.minute)
    }

    @Test fun `the clock stands in the bottom right corner, whole on the panel and clear of the corner's round`() {
        assertTrue(SceneFace.X + 2 * SceneFace.DIGIT_W <= 976f - 60f)
        assertTrue(SceneFace.Y > 596f / 2f)
        assertTrue(SceneFace.Y + SceneFace.LINE + SceneFace.DIGIT_H + 38f <= 596f - 40f)
    }
}
