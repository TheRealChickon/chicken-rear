package dev.kb896.aiusage.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pin's glass takes ONE picture of the screen per pin through an accessibility service that can do nothing else.
 */
class PinGlassTest {
    @Test fun `a picture is asked for only with the service on, and not twice within a second`() {
        assertFalse("off", PinGlassService.mayShoot(on = false, now = 10_000L, last = 0L))
        assertTrue("the first", PinGlassService.mayShoot(on = true, now = 10_000L, last = 0L))
        assertFalse("again 400 ms later", PinGlassService.mayShoot(on = true, now = 10_400L, last = 10_000L))
        assertFalse(PinGlassService.mayShoot(on = true, now = 10_999L, last = 10_000L))
        assertTrue("a second later", PinGlassService.mayShoot(on = true, now = 11_000L, last = 10_000L))
    }

    @Test fun `the service asks the phone for the picture and for nothing else - no events, no window content, no gestures, no keys`() {
        val xml = listOf("src/main/res/xml/pin_glass.xml", "app/src/main/res/xml/pin_glass.xml").map(::File).first { it.isFile }.readText()
        fun attr(name: String) = Regex("""android:$name="([^"]*)"""").find(xml)?.groupValues?.get(1)
        assertEquals("true", attr("canTakeScreenshot"))
        assertEquals("no events at all", "", attr("accessibilityEventTypes"))
        assertEquals("false", attr("canRetrieveWindowContent"))
        for (never in listOf("canPerformGestures", "canRequestFilterKeyEvents", "canRequestTouchExplorationMode", "canControlMagnification", "canRequestFingerprintGestures", "accessibilityFlags", "packageNames"))
            assertEquals("$never is not asked for", null, attr(never))
    }

    @Test fun `where the words are - our own extra first, then Circle Search's, and only a rectangle with something in it`() {
        val ours = intArrayOf(120, 1500, 760, 1580); val theirs = intArrayOf(40, 900, 600, 960)
        assertEquals(ours.toList(), dev.kb896.aiusage.ui.PinActivity.rectOf(ours, theirs)!!.toList())
        assertEquals(theirs.toList(), dev.kb896.aiusage.ui.PinActivity.rectOf(null, theirs)!!.toList())
        assertEquals("an empty one is passed over", theirs.toList(), dev.kb896.aiusage.ui.PinActivity.rectOf(intArrayOf(5, 5, 5, 90), theirs)!!.toList())
        assertEquals(null, dev.kb896.aiusage.ui.PinActivity.rectOf(null, null))
        assertEquals(null, dev.kb896.aiusage.ui.PinActivity.rectOf(intArrayOf(1, 2, 3), intArrayOf(9, 9, 1, 1)))
    }

    @Test fun `the pin's screen says it can play above an overlay, takes Circle Search's extras by their names, and never sends a key`() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.isFile }.readText()
        assertTrue(Regex("""<meta-data android:name="dev\.chicken\.pin\.DRAWS_OVER_OVERLAYS" android:value="true" />""").containsMatchIn(manifest))
        assertEquals("dev.chicken.extra.SELECTION_RECT", dev.kb896.aiusage.ui.PinActivity.EXTRA_SELECTION_RECT)
        assertEquals("dev.chicken.extra.FROM_OVERLAY", dev.kb896.aiusage.ui.PinActivity.EXTRA_FROM_OVERLAY)
        val src = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        for (f in listOf("dev/kb896/aiusage/ui/PinActivity.kt", "dev/kb896/aiusage/ui/PinBeamView.kt", "dev/kb896/aiusage/service/PinGlassService.kt")) {
            val text = File(src, f).readText()
            for (never in listOf("performGlobalAction", "KEYCODE_BACK", "KEYCODE_HOME", "onBackPressed", "dispatchKeyEvent", "GLOBAL_ACTION")) assertFalse("$f must not send or take '$never'", text.contains(never))
        }
    }

    @Test fun `the pin's screen lets the picture go when it ends, and nothing writes it anywhere`() {
        val src = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val pin = File(src, "dev/kb896/aiusage/ui/PinActivity.kt").readText()
        assertTrue("recycled as the screen is destroyed", Regex("""override fun onDestroy\(\) \{\s*behind\?\.recycle\(\); behind = null""").containsMatchIn(pin))
        val glass = File(src, "dev/kb896/aiusage/service/PinGlassService.kt").readText()
        for (text in listOf(pin, glass)) for (never in listOf(".compress(", "FileOutputStream", "MediaStore", "openFileOutput", "copyPixelsToBuffer"))
            assertFalse("'$never' has no place near the picture", text.contains(never))
    }
}
