package dev.kb896.aiusage.service

import dev.kb896.aiusage.data.NoticeAppsMode
import dev.kb896.aiusage.data.PanelConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which notifications reach the back (ALL_EXCEPT / ONLY), and the text rule for locked and unlocked. */
class NoticeFilterTest {

    private val picked = setOf("com.whatsapp", "com.tencent.mm")

    @Test fun `all but those picked`() {
        assertFalse(NoticeFilter.appAllowed("com.whatsapp", NoticeAppsMode.ALL_EXCEPT, picked))
        assertTrue(NoticeFilter.appAllowed("org.telegram.messenger", NoticeAppsMode.ALL_EXCEPT, picked))
    }

    @Test fun `all but nothing is every app`() {
        assertTrue(NoticeFilter.appAllowed("com.whatsapp", NoticeAppsMode.ALL_EXCEPT, emptySet()))
    }

    @Test fun `only those picked`() {
        assertTrue(NoticeFilter.appAllowed("com.tencent.mm", NoticeAppsMode.ONLY, picked))
        assertFalse(NoticeFilter.appAllowed("org.telegram.messenger", NoticeAppsMode.ONLY, picked))
    }

    @Test fun `only nothing is no app`() {
        assertFalse(NoticeFilter.appAllowed("com.whatsapp", NoticeAppsMode.ONLY, emptySet()))
    }

    @Test fun `the test notification shows whatever is picked`() {
        assertTrue(NoticeFilter.appAllowed("dev.chicken.rear.dev", NoticeAppsMode.ONLY, emptySet(), alwaysPass = true))
        assertTrue(NoticeFilter.appAllowed("dev.chicken.rear.dev", NoticeAppsMode.ALL_EXCEPT, setOf("dev.chicken.rear.dev"), alwaysPass = true))
    }

    @Test fun `a fresh install lets every app through`() {
        val c = PanelConfig.FRESH
        assertTrue(c.showNotifications)
        assertTrue(NoticeFilter.appAllowed("com.whatsapp", c))
    }

    @Test fun `unlocked, the text shows when it is asked for`() {
        assertTrue(NoticeFilter.textShown(locked = false, showText = true, textWhenLocked = false))
        assertFalse(NoticeFilter.textShown(locked = false, showText = false, textWhenLocked = true))
    }

    @Test fun `locked, the text shows only with its own second choice`() {
        assertFalse(NoticeFilter.textShown(locked = true, showText = true, textWhenLocked = false))
        assertTrue(NoticeFilter.textShown(locked = true, showText = true, textWhenLocked = true))
        assertFalse("the second choice alone shows nothing", NoticeFilter.textShown(locked = true, showText = false, textWhenLocked = true))
    }

    @Test fun `by default the text shows, locked or not, and its switches still hide it`() {
        // A notification is to be readable on the back without unlocking.
        for (c in listOf(PanelConfig.FRESH, PanelConfig.DEFAULT, PanelConfig.decode("{}"))) {
            assertTrue(NoticeFilter.textShown(locked = false, c))
            assertTrue(NoticeFilter.textShown(locked = true, c))
        }
        assertFalse(NoticeFilter.textShown(locked = true, PanelConfig.FRESH.copy(noticeTextWhenLocked = false)))
        assertFalse(NoticeFilter.textShown(locked = false, PanelConfig.FRESH.copy(showNotificationText = false)))
    }

    @Test fun `a secret notification stays off the back while locked, and only that kind`() {
        assertTrue(NoticeFilter.secret(locked = true, visibility = android.app.Notification.VISIBILITY_SECRET))
        assertFalse(NoticeFilter.secret(locked = false, visibility = android.app.Notification.VISIBILITY_SECRET))
        assertFalse(NoticeFilter.secret(locked = true, visibility = android.app.Notification.VISIBILITY_PRIVATE))
        assertFalse(NoticeFilter.secret(locked = true, visibility = android.app.Notification.VISIBILITY_PUBLIC))
    }

    @Test fun `skip while locked, only while locked and only when asked`() {
        assertTrue(NoticeFilter.skipped(locked = true, skipWhileLocked = true))
        assertFalse(NoticeFilter.skipped(locked = false, skipWhileLocked = true))
        assertFalse(NoticeFilter.skipped(locked = true, skipWhileLocked = false))
        assertFalse("off by default", PanelConfig.FRESH.noticeSkipLocked)
    }
}
