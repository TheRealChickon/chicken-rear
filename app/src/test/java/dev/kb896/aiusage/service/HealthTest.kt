package dev.kb896.aiusage.service

import dev.kb896.aiusage.service.Health.Action
import dev.kb896.aiusage.service.Health.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {
    private val shellWell = Health.Shell(coverMade = true, dialerMissing = emptySet(), clockMissing = emptySet(), xiaomiMissing = emptyList())
    private val well = Health.Facts(
        shizukuUp = true, shizukuAllowed = true, shellBound = true, guardBound = true, waitedMs = 600_000L, shell = shellWell,
        listenerAllowed = true, listenerConnected = true, batteryUnrestricted = true, notificationsAllowed = true,
        pinByXiaomi = true, pinActivity = true, xiaomiRearVersion = dev.kb896.aiusage.rear.PinVia.BUILT_ON,
    )
    private fun keys(f: Health.Facts) = Health.problems(f).map { it.key }

    @Test fun `all well - nothing at all`() {
        assertEquals(emptyList<Key>(), keys(well))
        assertNull(Health.notice(Health.problems(well), true))
        assertEquals("not known is not wrong", emptyList<Key>(), keys(well.copy(shell = null, batteryUnrestricted = null)))
        assertEquals("our own pin chosen: Xiaomi's version does not matter", emptyList<Key>(), keys(well.copy(pinByXiaomi = false, xiaomiRearVersion = 1L)))
    }

    @Test fun `a restart's own waiting is no problem until its grace is over`() {
        val down = well.copy(shizukuUp = false, shizukuAllowed = false, shellBound = false, guardBound = false, waitedMs = 30_000L)
        assertEquals(emptyList<Key>(), keys(down))
        val late = down.copy(waitedMs = Health.GRACE_MS)
        assertEquals(listOf(Key.SHIZUKU_DOWN), keys(late))
        val p = Health.problems(late).single()
        assertEquals(Action.OPEN_SHIZUKU, p.action)
        assertTrue(p.what.contains("Xiaomi's screen"))
        assertEquals("only Shizuku is said, not what follows from it", listOf(Key.SHIZUKU_DOWN), keys(late.copy(listenerConnected = false)).filter { it != Key.LISTENER })
        assertEquals(listOf(Key.SHELL_DOWN), keys(well.copy(guardBound = false, waitedMs = Health.GRACE_MS)))
        assertEquals(emptyList<Key>(), keys(well.copy(guardBound = false, waitedMs = 10_000L)))
        assertEquals("not allowed is said at once: no waiting makes it come", listOf(Key.SHIZUKU_NOT_ALLOWED), keys(well.copy(shizukuAllowed = false, shellBound = false, guardBound = false, waitedMs = 0L)))
    }

    @Test fun `each thing he can fix has its button, each fall-back says it needs nothing`() {
        val all = well.copy(
            listenerAllowed = false, batteryUnrestricted = false, notificationsAllowed = false, xiaomiRearVersion = 426091420L,
            shell = Health.Shell(coverMade = false, coverError = "no SurfaceControl", dialerMissing = setOf("ACTION_REAR_SPEAKER"), clockMissing = setOf("action.timer_pause"), xiaomiMissing = listOf("timer/ripple.webp")),
        )
        val got = Health.problems(all).associateBy { it.key }
        assertEquals(setOf(Key.COVER, Key.LISTENER, Key.BATTERY, Key.XIAOMI_FILES, Key.PIN_OURS, Key.DIALER, Key.CLOCK, Key.CANNOT_WARN), got.keys)
        assertEquals(Action.LISTENER_ACCESS, got.getValue(Key.LISTENER).action)
        assertEquals(Action.BATTERY, got.getValue(Key.BATTERY).action)
        assertEquals(Action.NOTIFICATIONS, got.getValue(Key.CANNOT_WARN).action)
        for (k in listOf(Key.XIAOMI_FILES, Key.PIN_OURS, Key.DIALER, Key.CLOCK)) { assertFalse(k.name, got.getValue(k).warn); assertEquals("Nothing to do.", got.getValue(k).todo) }
        for (p in got.values) assertTrue("few words: ${p.what}", (p.what + " " + p.todo).length <= 110)
        assertEquals("the pin activity gone", listOf(Key.PIN_OURS), keys(well.copy(pinActivity = false)))
        assertEquals("Xiaomi's rear app not readable", listOf(Key.PIN_OURS), keys(well.copy(xiaomiRearVersion = null)))
        assertEquals("allowed but not connected, after the grace", listOf(Key.LISTENER), keys(well.copy(listenerConnected = false)))
        assertEquals(emptyList<Key>(), keys(well.copy(listenerConnected = false, waitedMs = 1_000L)))
        assertEquals("a cover made once is no problem", emptyList<Key>(), keys(well.copy(shell = shellWell.copy(coverError = "x"))))
    }

    @Test fun `one notification, only when he allows them and something is worth it`() {
        val shizuku = Health.problems(well.copy(shizukuUp = false, waitedMs = Health.GRACE_MS))
        val n = Health.notice(shizuku, true)!!
        assertEquals("The back shows Xiaomi's screen: Shizuku is not running.", n.title)
        assertEquals("Start it in Shizuku.", n.text)
        assertNull("not allowed to post: nothing", Health.notice(shizuku, false))
        assertNull("a fall-back alone is not worth it", Health.notice(Health.problems(well.copy(xiaomiRearVersion = 1L)), true))
        val two = Health.problems(well.copy(shizukuUp = false, waitedMs = Health.GRACE_MS, batteryUnrestricted = false))
        assertEquals("2 things need a look", Health.notice(two, true)!!.title)
        assertEquals("SHIZUKU_DOWN,BATTERY", Health.notice(two, true)!!.signature)
    }

    @Test fun `a change of the system or of a package the back leans on is seen`() {
        val a = Health.Stamp("Xiaomi/popsicle:17/OS4.0.5.0", mapOf("com.android.deskclock" to 1306002800L, "com.miui.home" to 801028593L))
        assertEquals("the first look has nothing to compare", emptyList<String>(), Health.changes(null, a))
        assertEquals(emptyList<String>(), Health.changes(a, a))
        val b = a.copy(fingerprint = "Xiaomi/popsicle:17/OS4.0.6.0", versions = a.versions + ("com.android.deskclock" to 1307000000L) + ("moe.shizuku.privileged.api" to null))
        val c = Health.changes(a, b)
        assertEquals(2, c.size)
        assertTrue(c[0].startsWith("the system"))
        assertEquals("com.android.deskclock 1306002800 to 1307000000", c[1])
        assertEquals(listOf("com.miui.home 801028593 to absent"), Health.changes(a, a.copy(versions = a.versions + ("com.miui.home" to null))))
        assertEquals(b, Health.decode(Health.encode(b)))
        assertNull(Health.decode(null)); assertNull(Health.decode(""))
        assertTrue(Health.WATCHED.containsAll(listOf("com.xiaomi.subscreencenter", "com.android.incallui", "com.android.deskclock", "com.android.camera", "com.miui.home", "moe.shizuku.privileged.api")))
    }
}
