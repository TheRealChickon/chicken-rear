package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellHealthTest {
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun file(name: String, bytes: ByteArray) = File("build/shell-health").apply { mkdirs() }.let { File(it, name).apply { writeBytes(bytes) } }

    @Test fun `the scan starts only at the shell's own first look or when the app asks to look again`() {
        val gate = ShellHealth.Gate()
        assertEquals("the app's look 1.5 s after the bind: what is known, no scan", false, gate.ask(again = false))
        assertEquals(false, gate.looked)
        assertEquals("the shell's own look, some seconds after its start", true, gate.first())
        assertEquals("never twice by itself", false, gate.first())
        assertEquals(false, gate.ask(again = false))
        assertEquals("after a change the app asks again: a scan", true, gate.ask(again = true))
        val early = ShellHealth.Gate()
        assertEquals("asked again before the first look: a scan now", true, early.ask(again = true))
        assertEquals("and the first look does not scan twice", false, early.first())
    }

    @Test fun `words are found anywhere in a stream, across its pieces`() {
        val filler = ByteArray(64 * 1024 - 7) { 'x'.code.toByte() }
        val data = filler + "action.timer_pause".toByteArray() + filler + "ACTION_REAR_SPEAKER".toByteArray() + "aab".toByteArray()
        val needles = listOf("action.timer_pause", "ACTION_REAR_SPEAKER", "aab", "action.timer_cancel").map { it.toByteArray() }
        assertEquals(setOf(0, 1, 2), ByteScan.found(ByteArrayInputStream(data), needles))
        assertEquals("a partial match does not hide the real one", setOf(0), ByteScan.found(ByteArrayInputStream("aaab".toByteArray()), listOf("aab".toByteArray())))
    }

    @Test fun `the APK's path from pm path`() {
        assertEquals("/product/priv-app/InCallUI/InCallUI.apk", ShellHealth.apkPath("package:/product/priv-app/InCallUI/InCallUI.apk\n"))
        assertEquals("/data/app/x/base.apk", ShellHealth.apkPath("\npackage:/data/app/x/base.apk\npackage:/data/app/x/split.apk"))
        assertNull(ShellHealth.apkPath(""))
        assertNull(ShellHealth.apkPath("Error: package not found"))
    }

    @Test fun `a command gone from the app's code is missing, a stripped APK is not known`() {
        val dex1 = "dex\n035 com.android.incallui.ACTION_REAR_ANSWER_INCOMING_CALL ACTION_REAR_HANG_UP_CALL".toByteArray()
        val dex2 = "dex\n035 ACTION_REAR_REJECT_INCOMING_CALL".toByteArray()
        val apk = file("incallui.apk", zip(mapOf("classes.dex" to dex1, "classes2.dex" to dex2, "res/x.xml" to "ACTION_REAR_SPEAKER".toByteArray())))
        assertEquals("only the code counts", setOf("ACTION_REAR_SPEAKER"), ShellHealth.missingIn(apk.path, ShellHealth.DIALER_COMMANDS))
        val all = file("incallui2.apk", zip(mapOf("classes.dex" to (dex1 + dex2 + "ACTION_REAR_SPEAKER".toByteArray()))))
        assertEquals(emptySet<String>(), ShellHealth.missingIn(all.path, ShellHealth.DIALER_COMMANDS))
        val stripped = file("stripped.apk", zip(mapOf("AndroidManifest.xml" to ByteArray(10))))
        assertNull(ShellHealth.missingIn(stripped.path, ShellHealth.DIALER_COMMANDS))
        assertNull("no file", ShellHealth.missingIn("build/shell-health/none.apk", ShellHealth.CLOCK_COMMANDS))
        val anchor = ShellHealth.anchor(ShellHealth.DIALER)
        assertEquals("Lcom/android/incallui/", anchor)
        assertNull("a stub dex without the app's own classes is not its code", ShellHealth.missingIn(apk.path, ShellHealth.DIALER_COMMANDS, anchor))
        val real = file("incallui3.apk", zip(mapOf("classes.dex" to (dex1 + "Lcom/android/incallui/InCallActivity;".toByteArray()))))
        assertEquals(setOf("ACTION_REAR_REJECT_INCOMING_CALL", "ACTION_REAR_SPEAKER"), ShellHealth.missingIn(real.path, ShellHealth.DIALER_COMMANDS, anchor))
    }

    @Test fun `Xiaomi's pictures and the pin's files, as XiaomiArt and XiaomiPin look for them`() {
        val phone = zip(CallFace.ART.associateWith { ByteArray(4) })
        val timer = zip(TimerFace.ART.drop(1).associateWith { ByteArray(4) })
        val apk = file("subscreencenter.apk", zip(mapOf("assets/smart_assistant/phone" to phone, "assets/smart_assistant/timer" to timer) + XiaomiPin.FILES.drop(2).associateWith { ByteArray(4) }))
        val missing = ShellHealth.xiaomiMissingIn(apk.path)
        assertEquals(listOf("timer/${TimerFace.ART.first()}") + XiaomiPin.FILES.take(2).map { "pin/${it.substringAfterLast('/')}" }, missing)
        val full = file("full.apk", zip(mapOf("assets/smart_assistant/phone" to phone, "assets/smart_assistant/timer" to zip(TimerFace.ART.associateWith { ByteArray(4) })) + XiaomiPin.FILES.associateWith { ByteArray(4) }))
        assertEquals(emptyList<String>(), ShellHealth.xiaomiMissingIn(full.path))
        assertTrue(ShellHealth.xiaomiMissingIn("build/shell-health/none.apk").single().startsWith("the rear app's package"))
    }

    @Test fun `a button falls back only for what the look found gone`() {
        assertTrue("not looked at yet: as before", ShellHealth.dialerTakes("ACTION_REAR_SPEAKER"))
        assertTrue(ShellHealth.clockTakes("action.timer_pause"))
    }
}
