package dev.kb896.aiusage

import dev.kb896.aiusage.DebugLock.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A debug extra on an exported screen is honoured only with the shell's token: matching, fresh, once. The other test
 * doors of a debug build answer to the shell alone (DUMP, the socket's peer, debug.* properties, the stored settings).
 */
class DebugLockTest {
    private val t = "3f9c0a7d1e2b4c5a8f6e0d1c2b3a4958"
    private val now = 1_800_000_000_000L

    private fun v(given: String? = t, stored: String? = t, age: Long = 2_000L, asked: Boolean = true, debug: Boolean = true, used: Set<String> = emptySet()) =
        DebugLock.verdict(debug, asked, given, stored, now - age, now, used)

    @Test fun `the shell's own token, fresh, lets the extras in`() {
        assertEquals(Verdict.OPEN, v())
        assertTrue(v().open)
        assertEquals("a newline from echo is no difference", Verdict.OPEN, v(stored = "$t\n"))
        assertEquals("just written", Verdict.OPEN, v(age = 0L))
        assertEquals("a minute to the millisecond", Verdict.OPEN, v(age = DebugLock.MAX_AGE_MS))
        assertEquals("a file a little ahead of this clock", Verdict.OPEN, v(age = -DebugLock.AHEAD_MS))
    }

    @Test fun `a token that does not match is refused`() {
        assertEquals(Verdict.WRONG, v(given = "0".repeat(32)))
        assertEquals("one character off", Verdict.WRONG, v(given = t.dropLast(1) + "0"))
        assertEquals("the stored one's start only", Verdict.WRONG, v(given = t.take(20)))
        assertEquals("longer than the stored one", Verdict.WRONG, v(given = t + "00"))
        assertFalse(v(given = "0".repeat(32)).open)
    }

    @Test fun `too old or too far ahead is refused`() {
        assertEquals(Verdict.STALE, v(age = DebugLock.MAX_AGE_MS + 1))
        assertEquals(Verdict.STALE, v(age = 3_600_000L))
        assertEquals(Verdict.STALE, v(age = -DebugLock.AHEAD_MS - 1))
        assertEquals("no file time at all", Verdict.STALE, DebugLock.verdict(true, true, t, t, 0L, now))
    }

    @Test fun `missing on either side is refused`() {
        assertEquals("no token in the intent", Verdict.NO_TOKEN, v(given = null))
        assertEquals("an empty one", Verdict.NO_TOKEN, v(given = "  "))
        assertEquals("no file", Verdict.NO_FILE, v(stored = null))
        assertEquals("an empty file matches no empty token", Verdict.NO_TOKEN, v(given = "", stored = ""))
        assertEquals("an empty file", Verdict.SHORT, v(stored = ""))
        assertEquals("a short token, even matching", Verdict.SHORT, v(given = "abc", stored = "abc"))
    }

    @Test fun `a used token is refused, even if its file is still there`() {
        assertEquals(Verdict.USED, v(used = setOf(t)))
        assertEquals("another token is not affected", Verdict.OPEN, v(used = setOf("0".repeat(32))))
    }

    @Test fun `no debug extra asked for, or a release build, never opens`() {
        assertEquals(Verdict.NOT_ASKED, v(asked = false))
        assertEquals(Verdict.RELEASE, v(debug = false))
        assertFalse(v(asked = false).open)
        assertFalse(v(debug = false).open)
    }

    @Test fun `the file is spent when used and thrown away when stale, a wrong guess leaves the shell's own`() {
        assertTrue(DebugLock.discard(Verdict.OPEN))
        assertTrue(DebugLock.discard(Verdict.STALE))
        for (k in listOf(Verdict.WRONG, Verdict.NO_TOKEN, Verdict.NOT_ASKED, Verdict.RELEASE, Verdict.NO_FILE, Verdict.USED, Verdict.SHORT)) assertFalse(k.name, DebugLock.discard(k))
        assertEquals("only OPEN opens", listOf(Verdict.OPEN), Verdict.entries.filter { it.open })
    }

    @Test fun `same compares whole strings`() {
        assertTrue(DebugLock.same(t, t))
        assertFalse(DebugLock.same(t, t.uppercase()))
        assertFalse(DebugLock.same("", "a"))
        assertTrue(DebugLock.same("", ""))
    }

    @Test fun `no exported screen reads a debug extra past the lock`() {
        // The old way, `BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)`, let any app on the phone send them.
        val src = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val old = Regex("""BuildConfig\.DEBUG[^\n]*\b(get\w*Extra|hasExtra)\(""")
        val hits = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (old.containsMatchIn(line)) "${f.name}:${i + 1}" else null }
        }.toList()
        assertEquals("debug extras read without DebugExtras: $hits", emptyList<String>(), hits)
        for (f in listOf("dev/kb896/aiusage/ui/PinActivity.kt", "dev/kb896/aiusage/ui/place/PlacePinActivity.kt")) {
            val text = File(src, f).readText()
            assertTrue("$f takes its debug extras through the lock", text.contains("DebugExtras.of(this, intent, DEBUG_EXTRAS"))
        }
        assertEquals(listOf("rearpreview", "slow", "unpin", "demo", "test", "sound", "gl", "own", "plain", "full"), dev.kb896.aiusage.ui.PinActivity.DEBUG_EXTRAS)
    }

    @Test fun `the made-up touches' socket takes the shell and root alone`() {
        assertTrue("adb shell, and adbd for adb forward", DebugLock.fromShell(2000))
        assertTrue("root", DebugLock.fromShell(0))
        for (uid in listOf(10_123, 10_000, 1000, 1001, 2001, 1999, 99_000, -1)) assertFalse("uid $uid", DebugLock.fromShell(uid))
        assertEquals(2000, DebugLock.SHELL_UID)
    }

    @Test fun `the socket checks its peer before it reads a line`() {
        val text = File(src(), "dev/kb896/aiusage/rear/DemoTouchFeed.kt").readText()
        val check = text.indexOf("DebugLock.fromShell(")
        assertTrue("the peer's uid is checked", check > 0 && text.contains("peerCredentials.uid"))
        assertTrue("before the lines are read", check < text.indexOf("bufferedReader()"))
    }

    @Test fun `a mock position counts as real only in a debug build the shell switched`() {
        var asked = 0
        val one = { asked++; "1" }
        assertFalse("a real position is real", DebugLock.mockFix(isMock = false, debugBuild = true, prop = one))
        assertFalse("the shell's switch on", DebugLock.mockFix(isMock = true, debugBuild = true, prop = one))
        assertFalse("with a newline", DebugLock.mockFix(true, true) { "1\n" })
        assertTrue("no switch: a mock is a mock", DebugLock.mockFix(true, true) { "" })
        assertTrue("no property at all", DebugLock.mockFix(true, true) { null })
        assertTrue("anything but 1", DebugLock.mockFix(true, true) { "true" })
        asked = 0
        assertTrue("a release build: a mock is a mock, whatever the property", DebugLock.mockFix(isMock = true, debugBuild = false, prop = one))
        assertFalse(DebugLock.mockFix(isMock = false, debugBuild = false, prop = one))
        assertEquals("a release build never reads it", 0, asked)
        assertTrue("only the shell can set it", DebugLock.MOCK_FIXES_PROP.startsWith("debug."))
    }

    @Test fun `the panel's start takes the demo only as stored, in a debug build`() {
        var read = 0
        assertTrue(DebugLock.launchDemo(debugBuild = true, asked = true) { read++; true })
        assertFalse("another app's state with the demo on", DebugLock.launchDemo(debugBuild = true, asked = true) { read++; false })
        assertEquals(2, read)
        assertFalse("not asked: nothing read", DebugLock.launchDemo(debugBuild = true, asked = false) { read++; true })
        assertTrue("a release build as before (its panel never plays it)", DebugLock.launchDemo(debugBuild = false, asked = true) { read++; false })
        assertFalse(DebugLock.launchDemo(debugBuild = false, asked = false) { read++; true })
        assertEquals("read only in a debug build, when asked", 2, read)
    }

    @Test fun `every broadcast door of a debug build needs DUMP`() {
        val debug = manifest("src/debug/AndroidManifest.xml")
        val receivers = debug.getElementsByTagName("receiver").let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }
        assertTrue(receivers.isNotEmpty())
        for (r in receivers) {
            assertEquals(r.getAttribute("android:name") + " is exported only behind DUMP", "android.permission.DUMP", r.getAttribute("android:permission"))
        }
        for (tag in listOf("activity", "service", "provider")) assertEquals("no $tag in the debug manifest", 0, debug.getElementsByTagName(tag).length)
        val relay = File(src(), "dev/kb896/aiusage/service/NotificationRelay.kt").readText()
        assertTrue("DEBUG_UNBIND's receiver is registered with DUMP", relay.contains("IntentFilter(DEBUG_UNBIND), DEBUG_SENDER,") &&
            relay.contains("const val DEBUG_SENDER = \"android.permission.DUMP\""))
    }

    @Test fun `the exported doors are the ones looked at for the lock`() {
        // Each was checked for test-only ways in: a new exported component must be looked at too before it is added here.
        val main = manifest("src/main/AndroidManifest.xml")
        val found = sortedSetOf<String>()
        for (tag in listOf("activity", "receiver", "service", "provider")) {
            val l = main.getElementsByTagName(tag)
            for (i in 0 until l.length) {
                val e = l.item(i) as org.w3c.dom.Element
                if (e.getAttribute("android:exported") == "true") found += e.getAttribute("android:name") + " " + e.getAttribute("android:permission")
            }
        }
        assertEquals(sortedSetOf(
            ".rear.RearActivity ",
            ".remote.RemoteActivity ",
            ".remote.RemoteReceiver ",
            ".service.NotificationRelay android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            ".service.PinGlassService android.permission.BIND_ACCESSIBILITY_SERVICE",
            ".service.PortalTileService android.permission.BIND_QUICK_SETTINGS_TILE",
            ".ui.MainActivity ",
            ".ui.PinActivity ",
            // The pinned picture on the front: the shell's alone (DUMP), one number in, no debug extra (PinViewGate).
            ".ui.PinViewActivity android.permission.DUMP",
            ".ui.SignInReturnActivity ",
            // Rear camera: shell-only launch, real camera permission, no intent-driven debug modes.
            ".ui.camera.RearCameraActivity android.permission.DUMP",
            ".ui.place.PlacePinActivity ",
            "rikka.shizuku.ShizukuProvider android.permission.INTERACT_ACROSS_USERS_FULL",
        ), found)
        val camera = File(src(), "dev/kb896/aiusage/ui/camera/RearCameraActivity.kt").readText()
        assertTrue("the camera has no intent extras or permission prompt", listOf("getStringExtra", "getBooleanExtra",
            "getParcelableExtra", "requestPermissions", "RequestPermission", "DebugExtras").none { camera.contains(it) })
        assertTrue("camera permission, the configured rear display and keyguard are checked", camera.contains("Manifest.permission.CAMERA") &&
            camera.contains("display?.displayId == Panel.DISPLAY_ID") && camera.contains("isKeyguardLocked") &&
            camera.contains("if (!allowedHere())"))
        val view = File(src(), "dev/kb896/aiusage/ui/PinViewActivity.kt").readText()
        assertTrue("the picture's screen reads the note's time and nothing else of its intent",
            view.contains("getLongExtra(PinViewGate.EXTRA_AT") && listOf("getStringExtra", "getParcelableExtra", "intent?.data", "intent.data", "DebugExtras", "clipData").none { view.contains(it) })
        val rear = File(src(), "dev/kb896/aiusage/rear/RearActivity.kt").readText()
        assertTrue("the panel's start state goes through launchDemo", rear.contains("render(intent?.getStringExtra(EXTRA_STATE).orEmpty(), started = true)") && rear.contains("DebugLock.launchDemo("))
        assertTrue("with the stored settings read fresh in the back's own process", rear.contains("ConfigStore(applicationContext).loadFresh().demoMode"))
        val fixes = File(src(), "dev/kb896/aiusage/place/Fixes.kt").readText()
        assertTrue("a fix's mock goes through mockFix", fixes.contains("DebugLock.mockFix(l.isMock"))
    }

    private fun src(): File = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }

    private fun manifest(path: String): org.w3c.dom.Document {
        val f = listOf(path, "app/$path").map(::File).first { it.isFile }
        return javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
    }
}
