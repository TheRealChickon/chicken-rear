package dev.kb896.aiusage.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The pin's glass uses one screenshot. Interactive window retrieval belongs to Circle translation. */
class PinGlassTest {
    @Test fun `a picture is asked for only with the service on, and not twice within a second`() {
        assertFalse("off", PinGlassService.mayShoot(on = false, now = 10_000L, last = 0L))
        assertTrue("the first", PinGlassService.mayShoot(on = true, now = 10_000L, last = 0L))
        assertFalse("again 400 ms later", PinGlassService.mayShoot(on = true, now = 10_400L, last = 10_000L))
        assertFalse(PinGlassService.mayShoot(on = true, now = 10_999L, last = 10_000L))
        assertTrue("a second later", PinGlassService.mayShoot(on = true, now = 11_000L, last = 10_000L))
    }

    @Test fun `the shared service starts with no events or window flags and grants no gesture or key capability`() {
        val xml = listOf("src/main/res/xml/pin_glass.xml", "app/src/main/res/xml/pin_glass.xml").map(::File).first { it.isFile }.readText()
        fun attr(name: String) = Regex("""android:$name="([^"]*)"""").find(xml)?.groupValues?.get(1)
        assertEquals("true", attr("canTakeScreenshot"))
        assertEquals("no events at all", "", attr("accessibilityEventTypes"))
        assertEquals("Circle translation can request the foreground application window", "true", attr("canRetrieveWindowContent"))
        val src = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val glass = File(src, "dev/kb896/aiusage/service/PinGlassService.kt").readText()
        for (windowAccess in listOf("FLAG_RETRIEVE_INTERACTIVE_WINDOWS", "windowsOnAllDisplays", "takeScreenshotOfWindow", "rootInActiveWindow"))
            assertFalse("the pin's service code does not use $windowAccess", glass.contains(windowAccess))
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
        for (f in listOf("dev/kb896/aiusage/ui/PinActivity.kt", "dev/kb896/aiusage/ui/PinBeamView.kt", "dev/kb896/aiusage/ui/PinBView.kt", "dev/kb896/aiusage/service/PinGlassService.kt")) {
            val text = File(src, f).readText()
            for (never in listOf("performGlobalAction", "KEYCODE_BACK", "KEYCODE_HOME", "onBackPressed", "dispatchKeyEvent", "GLOBAL_ACTION")) assertFalse("$f must not send or take '$never'", text.contains(never))
        }
    }

    @Test fun `no window of the pin lets touches through - a touch ends it, in the activity and in the overlay`() {
        // An activity window with FLAG_NOT_TOUCHABLE over another app is an untrusted occluder from Android 12: the touch is
        // dropped, not passed on. The overlay's embedded window is touchable over the whole screen. So either a touch ends
        // the pin, or his touches go nowhere while it plays.
        val src = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        for (f in listOf("dev/kb896/aiusage/ui/PinActivity.kt", "dev/kb896/aiusage/ui/PinBView.kt", "dev/kb896/aiusage/ui/PinBeamView.kt", "dev/kb896/aiusage/service/PinGlassService.kt")) {
            val text = File(src, f).readText()
            assertFalse("$f must not make a pin's window not touchable", text.contains("FLAG_NOT_TOUCHABLE"))
            assertFalse("$f must not make a pin's view pass touches on", Regex("""touchEnds\s*=\s*false""").containsMatchIn(text))
        }
        val glass = File(src, "dev/kb896/aiusage/service/PinGlassService.kt").readText()
        assertTrue("draft B in the overlay ends at a touch", Regex("""PinBView\([^)]*touchEnds = true""").containsMatchIn(glass))
    }

    @Test fun `the pin's screen lets the picture go when it ends, and nothing writes it anywhere`() {
        val src = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val pin = File(src, "dev/kb896/aiusage/ui/PinActivity.kt").readText()
        assertTrue("recycled as the screen is destroyed", Regex("""override fun onDestroy\(\) \{\s*behind\?\.recycle\(\); behind = null""").containsMatchIn(pin))
        val glass = File(src, "dev/kb896/aiusage/service/PinGlassService.kt").readText()
        // Draft B frosts the page behind: in memory only, as textures (rear/PinBGl), never written anywhere.
        val b = File(src, "dev/kb896/aiusage/ui/PinBView.kt").readText()
        for (never in listOf(".compress(", "FileOutputStream", "MediaStore", "openFileOutput")) assertFalse("'$never' has no place near the picture", b.contains(never))
        for (text in listOf(pin, glass)) for (never in listOf(".compress(", "FileOutputStream", "MediaStore", "openFileOutput", "copyPixelsToBuffer"))
            assertFalse("'$never' has no place near the picture", text.contains(never))
    }
}
