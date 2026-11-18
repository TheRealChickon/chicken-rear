package dev.kb896.aiusage.rear

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Whether a notification may be thrown out on the back crosses from the app to the shell with the list (ShellNotices.encode). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoticeClearableCodecTest {
    @Test fun `may it be thrown out goes with each notification, and a list of before says yes`() {
        val items = listOf(
            NoticeShade.Item("a", "p", "App", "From", "Text", clearable = true),
            NoticeShade.Item("b", "p", "App", "From", "Text", clearable = false),
        )
        assertEquals(items, ShellNotices.decode(ShellNotices.encode(items, emptyMap())))
        // From an app of before (no such array): every one was clearable, as its relay let no other in.
        val old = ShellNotices.encode(items, emptyMap()).apply { remove("clearable") }
        assertEquals(listOf(true, true), ShellNotices.decode(Bundle(old)).map { it.clearable })
    }
}
