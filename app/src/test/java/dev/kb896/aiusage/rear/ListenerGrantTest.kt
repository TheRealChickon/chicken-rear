package dev.kb896.aiusage.rear

import dev.kb896.aiusage.service.TestNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shell grants notification access to this app's own listener and to nothing else. */
class ListenerGrantTest {
    private val own = "dev.chicken.rear.dev"
    private val relay = "$own/dev.kb896.aiusage.service.NotificationRelay"

    @Test fun `our own listener gets the allow_listener command`() {
        assertEquals("cmd notification allow_listener $relay", ListenerGrant.command(relay, own))
        assertEquals(relay, ListenerGrant.normalised("  $relay ", own))
    }

    @Test fun `another app's listener is refused`() {
        assertNull(ListenerGrant.command("com.evil/com.evil.Listener", own))
        assertNull(ListenerGrant.command("dev.chicken.rear/dev.kb896.aiusage.service.NotificationRelay", own))
        assertNull(ListenerGrant.command("$own.evil/x.Y", own))
    }

    @Test fun `anything the shell would read as more than a name is refused`() {
        for (bad in listOf("$relay; rm -rf /", "$relay\$(id)", "$relay x", "'$relay'", "$own/a\$B", "$own/", "/x.Y", own, "")) {
            assertNull(bad, ListenerGrant.command(bad, own))
        }
    }

    @Test fun `the short class form is refused, since our namespace is not our package`() {
        assertNull(ListenerGrant.command("$own/.service.NotificationRelay", own))
    }

    @Test fun `allowed reads the colon list whole`() {
        val setting = "com.xiaomi.barrage/com.xiaomi.barrage.service.NotificationMonitorService:$relay"
        assertTrue(ListenerGrant.allowed(setting, relay))
        assertFalse(ListenerGrant.allowed("${relay}Extra", relay))
        assertFalse(ListenerGrant.allowed("null", relay))
        assertFalse(ListenerGrant.allowed(null, relay))
        assertFalse(ListenerGrant.allowed("", relay))
    }

    @Test fun `only our own tagged test notification gets past the relay's own-app rule`() {
        assertTrue(TestNotice.isTest(own, own, TestNotice.TAG))
        assertFalse(TestNotice.isTest(own, own, null))
        assertFalse(TestNotice.isTest(own, own, "usage"))
        assertFalse(TestNotice.isTest("com.other", own, TestNotice.TAG))
    }
}
