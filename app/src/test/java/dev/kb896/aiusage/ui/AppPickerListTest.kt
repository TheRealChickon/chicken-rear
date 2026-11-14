package dev.kb896.aiusage.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPickerListTest {
    private val b = AppPicker.App("com.b", "Brave")
    private val c = AppPicker.App("com.c", "Calculator")
    private val m = AppPicker.App("com.m", "Maps")
    private val all = listOf(b, c, m)

    @Test fun `pinned first in their order on the back, the rest as they came`() {
        assertEquals(listOf(m, b, c), AppPicker.arranged(all, listOf("com.m", "com.b"), ""))
    }

    @Test fun `a pinned app no longer installed isn't listed`() {
        assertEquals(listOf(b, c, m), AppPicker.arranged(all, listOf("com.gone"), ""))
    }

    @Test fun `search matches any part of the name, in any case, and ignores spaces round it`() {
        assertEquals(listOf(c), AppPicker.arranged(all, emptyList(), " CALC "))
        assertEquals(listOf(b, m), AppPicker.arranged(all, listOf("com.b"), "a").filter { it != c })
    }

    @Test fun `nothing matching is an empty list`() {
        assertEquals(emptyList<AppPicker.App>(), AppPicker.arranged(all, emptyList(), "zzz"))
    }

    // ---- the notification app set (PanelConfig.noticeApps) ----

    @Test fun `the notification set's chosen apps come first by name, the rest as they came`() {
        assertEquals(listOf(c, m, b), AppPicker.chosenFirst(all, setOf("com.m", "com.c"), ""))
    }

    @Test fun `nothing chosen leaves the list as it came`() {
        assertEquals(all, AppPicker.chosenFirst(all, emptySet(), ""))
    }

    @Test fun `a chosen app no longer installed isn't listed`() {
        assertEquals(listOf(m, b, c), AppPicker.chosenFirst(all, setOf("com.gone", "com.m"), ""))
    }

    @Test fun `search narrows the notification set's list the same way`() {
        assertEquals(listOf(c), AppPicker.chosenFirst(all, setOf("com.m"), " calc "))
        assertEquals(listOf(m, b), AppPicker.chosenFirst(all, setOf("com.m"), "A").filter { it != c })
        assertEquals(emptyList<AppPicker.App>(), AppPicker.chosenFirst(all, setOf("com.m"), "zzz"))
    }

    @Test fun `a switch adds or removes one package and leaves the others`() {
        val set = setOf("com.b")
        assertEquals(setOf("com.b", "com.m"), AppPicker.toggled(set, "com.m", true))
        assertEquals(emptySet<String>(), AppPicker.toggled(set, "com.b", false))
        assertEquals(set, AppPicker.toggled(set, "com.b", true))
        assertEquals(set, AppPicker.toggled(set, "com.x", false))
    }
}
