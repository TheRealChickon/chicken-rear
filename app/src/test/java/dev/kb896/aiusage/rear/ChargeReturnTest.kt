package dev.kb896.aiusage.rear

import dev.kb896.aiusage.service.ChargePrecise
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeReturnTest {
    private val service = File("src/main/java/dev/kb896/aiusage/rear/RearUserService.kt").readText()

    @Test fun `after showing 50 a swipe away and back must not reuse the original 84`() {
        val live = RearNav.next(RearNav.State(lit = true), RearNav.Input.LiveComes(RearNav.Live.CHARGE)).state
        assertEquals(RearNav.Seen.LIVE, RearNav.seen(live))
        val away = RearNav.next(live, RearNav.Input.Stroke(RearNav.Way.RIGHT))
        assertEquals(RearNav.Seen.MOON, RearNav.seen(away.state))
        val back = RearNav.next(away.state, RearNav.Input.Stroke(RearNav.Way.LEFT))
        assertEquals(RearNav.Live.CHARGE, back.state.live)
        assertEquals(RearNav.Anim.LIVE_IN, back.anim)

        val raise = service.substringAfter("private fun raiseCharge(").substringBefore("\n    }")
        assertFalse("Reopening seeded the figure from the original plug-in reading (84), discarding the live update (50)",
            raise.contains("chargeWantedLevel"))
        assertTrue("Reopening asks the app to read the current battery", raise.contains("requestChargeReading()"))

        val reading = ChargeReading()
        reading.refresh(100L)
        reading.update(110L, sample(84))
        reading.update(120L, sample(50))
        assertEquals(50, reading.sample?.detail?.level)

        reading.refresh(200L)
        assertNull("The first frame after reopening is empty until Android answers", reading.sample)
        reading.update(190L, sample(84))
        assertNull("A late pre-reopen reply cannot restore 84", reading.sample)
        reading.update(210L, sample(50))
        assertEquals(50, reading.sample?.detail?.level)
        reading.update(205L, sample(84))
        assertEquals(50, reading.sample?.detail?.level)
    }

    @Test fun `unknown clears the figure even after a valid sample`() {
        val reading = ChargeReading()
        reading.refresh(10L)
        reading.update(20L, sample(84))
        reading.update(30L, sample(ChargePrecise.UNKNOWN))
        assertNull(reading.sample)
        reading.update(25L, sample(84))
        assertNull(reading.sample)
        reading.update(40L, sample(0))
        assertEquals("Zero is a valid Android reading", 0, reading.sample?.detail?.level)
    }

    private fun sample(level: Int) = ChargeReading.Sample(ChargePrecise.Detail(level, -1f, 0L, 0f), "")

    @Test fun `a vertical dismissal is animated to the right as well`() {
        val tracks = service.substringAfter("private fun dragTracks(").substringBefore("\n    }")
        assertTrue("A charge drag must only use the right edge", tracks.contains("charge.peek(it)") && !tracks.contains("edgeOf(way)"))
        val live = RearNav.next(RearNav.State(lit = true), RearNav.Input.LiveComes(RearNav.Live.CHARGE)).state
        for (way in listOf(RearNav.Way.RIGHT, RearNav.Way.UP, RearNav.Way.DOWN)) {
            val exit = RearNav.next(live, RearNav.Input.Stroke(way))
            assertEquals(RearNav.Anim.LIVE_OUT, exit.anim)
            assertEquals(RearNav.Seen.MOON, RearNav.seen(exit.state))
            val back = RearNav.next(exit.state, RearNav.Input.Stroke(RearNav.Way.LEFT))
            assertEquals(RearNav.Anim.LIVE_IN, back.anim)
        }
        assertEquals(RearNav.Anim.USAGE_IN, RearNav.next(live, RearNav.Input.Stroke(RearNav.Way.LEFT)).anim)
        val charge = File("src/main/java/dev/kb896/aiusage/rear/ShellCharge.kt").readText()
        assertTrue(charge.contains("slide.peek(inPart, PageSlide.Edge.RIGHT)"))
        assertTrue(charge.contains("slide.leaveTo(PageSlide.Edge.RIGHT)"))
        assertTrue(charge.contains("slide.comeFrom(PageSlide.Edge.RIGHT)"))
    }
}
