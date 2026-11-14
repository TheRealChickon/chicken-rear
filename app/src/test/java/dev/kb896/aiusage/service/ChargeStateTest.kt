package dev.kb896.aiusage.service

import android.os.BatteryManager
import dev.kb896.aiusage.rear.RearNav
import dev.kb896.aiusage.service.ChargeState.Is
import dev.kb896.aiusage.service.ChargeState.Page.Does
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** At full the charging page has no place on the back; held by a limit below it, the page stays and stands still. */
class ChargeStateTest {
    private val charging = BatteryManager.BATTERY_STATUS_CHARGING
    private val full = BatteryManager.BATTERY_STATUS_FULL
    private val not = BatteryManager.BATTERY_STATUS_NOT_CHARGING
    private val discharging = BatteryManager.BATTERY_STATUS_DISCHARGING

    @Test fun `what the charger is doing - one decision`() {
        assertEquals(Is.FILLING, ChargeState.of(true, charging, 57))
        assertEquals(Is.FILLING, ChargeState.of(true, charging, 99))
        // Full is the phone's own word, or 100, whichever comes first.
        assertEquals(Is.FULL, ChargeState.of(true, full, 100))
        assertEquals(Is.FULL, ChargeState.of(true, charging, 100))
        assertEquals(Is.FULL, ChargeState.of(true, full, 99))
        // On the charger and not charging below 100%: held (a charge limit, too warm).
        assertEquals(Is.HOLDING, ChargeState.of(true, not, 98))
        assertEquals(Is.HOLDING, ChargeState.of(true, discharging, 80))
        // No charger, whatever the status says.
        for (s in listOf(charging, full, not, discharging)) assertEquals(Is.OUT, ChargeState.of(false, s, 100))
    }

    @Test fun `filling to full - the page leaves once, and the charger coming out after that changes nothing`() {
        val p = ChargeState.Page()
        assertEquals(ChargeState.Page.Plug.PAGE, p.pluggedIn(Is.FILLING))
        var now = 0L
        for (level in 90..99) { assertEquals(Does.NOTHING, p.next(Is.FILLING, level, now)); now += 60_000 }
        assertEquals(Does.REST, p.next(Is.FULL, 100, now))
        assertFalse(p.on)
        for (i in 1..5) assertEquals(Does.NOTHING, p.next(Is.FULL, 100, now + i * 30_000))
        assertEquals(Does.NOTHING, p.next(Is.OUT, 100, now + 300_000))
    }

    @Test fun `the charger going in at full is the page's one short moment - under 100 it is the page to stay`() {
        val full = ChargeState.Page()
        assertEquals(ChargeState.Page.Plug.MOMENT, full.pluggedIn(Is.FULL))
        assertFalse("it does not stay: nothing is on after the moment", full.on)
        // And it is once per plugging in: sitting at 100% says nothing more.
        for (i in 1..10) assertEquals(Does.NOTHING, full.next(Is.FULL, 100, i * 30_000L))
        assertEquals(ChargeState.Page.Plug.PAGE, ChargeState.Page().pluggedIn(Is.FILLING))
        assertEquals("held by a limit: the page, standing still", ChargeState.Page.Plug.PAGE, ChargeState.Page().pluggedIn(Is.HOLDING))
        assertEquals(ChargeState.Page.Plug.NOTHING, ChargeState.Page().pluggedIn(Is.OUT))
    }

    @Test fun `reaching full is one moment - not one for each flip between 99 and 100`() {
        val p = ChargeState.Page()
        p.pluggedIn(Is.FILLING)
        var moments = 0
        var now = 0L
        // 98, 99, then 100 and a minute of wavering, then it sits at 100 for an hour.
        for (level in listOf(98, 99)) { if (p.next(Is.FILLING, level, now) == Does.REST) moments++; now += 60_000 }
        for (i in 0 until 10) { if (p.next(if (i % 2 == 0) Is.FULL else Is.FILLING, if (i % 2 == 0) 100 else 99, now) == Does.REST) moments++; now += 6_000 }
        for (i in 0 until 60) { if (p.next(Is.FULL, 100, now) == Does.REST) moments++; now += 60_000 }
        assertEquals(1, moments)
    }

    @Test fun `full, then used on the charger - back at 98, or after two minutes at 99, never with each flip between 99 and 100`() {
        val p = ChargeState.Page()
        p.pluggedIn(Is.FILLING)
        assertEquals(Does.REST, p.next(Is.FULL, 100, 0))
        // The level wavers 99, 100, 99, 100 within a minute: nothing comes or goes.
        var now = 10_000L
        for (i in 0 until 8) { assertEquals("flip $i", Does.NOTHING, p.next(if (i % 2 == 0) Is.FILLING else Is.FULL, if (i % 2 == 0) 99 else 100, now)); now += 8_000 }
        // It stays at 99 for two minutes: back.
        assertEquals(Does.NOTHING, p.next(Is.FILLING, 99, now)); val since = now
        assertEquals(Does.NOTHING, p.next(Is.FILLING, 99, since + ChargeState.Page.BACK_AFTER_MS - 1))
        assertEquals(Does.SHOW, p.next(Is.FILLING, 99, since + ChargeState.Page.BACK_AFTER_MS))
        assertTrue(p.on)
        // And full again: it leaves again.
        assertEquals(Does.REST, p.next(Is.FULL, 100, since + 600_000))
        // Down to 98 at once (the phone in heavy use on a weak charger): back without waiting.
        assertEquals(Does.SHOW, p.next(Is.HOLDING, 98, since + 601_000))
    }

    @Test fun `held by a charge limit below 100 - the page stays (its figures stand, ChargePrecise), it is not taken away`() {
        val p = ChargeState.Page()
        p.pluggedIn(Is.FILLING)
        var now = 0L
        for (level in 95..98) { assertEquals(Does.NOTHING, p.next(Is.FILLING, level, now)); now += 60_000 }
        // The limit holds it at 98: not charging, the charger in. For an hour.
        for (i in 0 until 60) { assertEquals(Does.NOTHING, p.next(Is.HOLDING, 98, now)); now += 60_000 }
        assertTrue(p.on)
        // What the page shows then is ChargePrecise's: nothing past the whole percent, holding.
        val precise = ChargePrecise()
        precise.level(97, 1_000L); precise.level(98, 61_000L)
        assertTrue(precise.filling(false, 62_000L).holding)
        assertTrue(precise.filling(false, 62_000L).fraction < 0f)
    }

    @Test fun `before the first battery reading no level is told - never a 0 for the page`() {
        val precise = ChargePrecise()
        // The charger's kind is told as the app starts, before any reading of the battery.
        val d = precise.detail(5_000L)
        assertEquals(ChargePrecise.UNKNOWN, d.level)
        assertTrue(d.level < 0 && d.fraction < 0f)
        // After a reading it is that reading.
        assertEquals(64, precise.level(64, 6_000L).level)
        assertEquals(64, precise.detail(7_000L).level)
        // And after the charger came out (reset) it is not known again.
        precise.reset()
        assertEquals(ChargePrecise.UNKNOWN, precise.detail(8_000L).level)
    }

    @Test fun `the charger out - whatever was, nothing is on, and the next one in starts afresh`() {
        val p = ChargeState.Page()
        p.pluggedIn(Is.FILLING)
        assertEquals(Does.NOTHING, p.next(Is.OUT, 60, 1_000L))
        assertFalse(p.on)
        // A reading under 100 with a charger and no word of it going in (the app was restarted on the charger): shown.
        assertEquals(Does.SHOW, p.next(Is.FILLING, 60, 2_000L))
    }

    @Test fun `on the back it ends as any live thing ends - the page slides out where it is seen, and nothing moves where it is not`() {
        val base = RearNav.State(lit = true, ours = true)
        val on = RearNav.next(base, RearNav.Input.LiveComes(RearNav.Live.CHARGE)).state
        assertEquals(RearNav.Seen.LIVE, RearNav.seen(on))
        // Full: charging is not live any more.
        val gone = RearNav.next(on, RearNav.Input.LiveEnds(RearNav.Live.CHARGE))
        assertEquals(RearNav.Anim.LIVE_OUT, gone.anim)
        assertEquals(RearNav.Seen.MOON, RearNav.seen(gone.state))
        // Under usage: usage stays, nothing slides, and back from it is the moon.
        val usage = RearNav.next(on, RearNav.Input.Stroke(RearNav.Way.LEFT)).state
        assertEquals(RearNav.Seen.USAGE, RearNav.seen(usage))
        val under = RearNav.next(usage, RearNav.Input.LiveEnds(RearNav.Live.CHARGE))
        assertEquals(RearNav.Anim.NONE, under.anim)
        assertEquals(RearNav.Seen.USAGE, RearNav.seen(under.state))
        assertEquals(RearNav.Seen.MOON, RearNav.seen(RearNav.next(under.state, RearNav.Input.Stroke(RearNav.Way.RIGHT)).state))
        // Under 100% again: it comes as a live thing comes.
        assertEquals(RearNav.Anim.LIVE_IN, RearNav.next(gone.state, RearNav.Input.LiveComes(RearNav.Live.CHARGE)).anim)
    }
}
