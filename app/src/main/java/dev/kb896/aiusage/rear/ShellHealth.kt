package dev.kb896.aiusage.rear

import android.os.Bundle
import android.util.Log
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * THE SHELL'S HALF OF THE HEALTH CHECK (service/HealthCheck asks it through IRearService.health): what only the shell
 * can see, read and never changed. Run once some seconds after the shell starts, and again when the app says the phone
 * or a Xiaomi package the back leans on has changed.
 *
 *  - Xiaomi's rear templates and the pin's files, where XiaomiArt and XiaomiPin take them from (the factory APK of
 *    com.xiaomi.subscreencenter under /product): which are missing.
 *  - The commands the call page and the timer page send (the dialer's ACTION_REAR_*, the Clock's action.timer_*):
 *    those receivers are registered only while a call or a timer is on, so nothing can ask for them without one
 *    (the restart audit: "No receivers found" for all eight, with a call and a timer off). What CAN be read is
 *    whether the app's code still has those words in it: a command whose name is gone from the installed APK's code
 *    is surely gone, and its button falls back (the call's keys, the notification's own button). Whether the receiver
 *    is exported is not readable without a call or timer either; the words being there is no proof that it listens.
 *  - Whether this process could make the cover's layer.
 *
 * NEVER BY PRESSING A BUTTON OR WAITING ON A REAL CALL OR TIMER.
 */
object ShellHealth {
    private const val TAG = "AiUsageHealth"

    const val DIALER = "com.android.incallui"
    const val CLOCK = "com.android.deskclock"
    val DIALER_COMMANDS = listOf("ACTION_REAR_ANSWER_INCOMING_CALL", "ACTION_REAR_REJECT_INCOMING_CALL", "ACTION_REAR_HANG_UP_CALL", "ACTION_REAR_SPEAKER")
    val CLOCK_COMMANDS = listOf("action.timer_pause", "action.timer_continue", "action.timer_cancel", "action.timer_dismiss")
    private val XIAOMI_APKS = listOf("/product/app/subscreencenter/subscreencenter.apk", "/system/app/subscreencenter/subscreencenter.apk")

    /** The dialer's commands not found in its code; null while not looked at, or no code could be read (then: as before). */
    @Volatile var dialerMissing: Set<String>? = null
        private set
    @Volatile var clockMissing: Set<String>? = null
        private set
    /** Xiaomi's pictures and files not found ("phone/…", "timer/…", "pin/…"); null while not looked at. */
    @Volatile var xiaomiMissing: List<String>? = null
        private set
    @Volatile private var lookedAt = 0L

    /**
     * WHO STARTS THE SCAN (the scan reads the dialer's and the Clock's code, every byte of it): only the shell's own
     * first look, some seconds after it started ([first]), and the app asking to look again after a change ([ask] with
     * again). An ordinary ask from the app answers with what is known, null while nothing is: the app's look 1.5 s
     * after a shell is bound must not scan while the back starts its scene and clip decoder. Pure.
     */
    class Gate {
        var looked = false
            private set

        /** Whether the app's ask is to scan now. */
        fun ask(again: Boolean): Boolean = again.also { if (it) looked = true }

        /** Whether the shell's own first look is to scan (not when an ask has already). */
        fun first(): Boolean = (!looked).also { looked = true }
    }

    /** Whether the call page may send [command] to the dialer: not when the dialer's code no longer has it. */
    fun dialerTakes(command: String): Boolean = dialerMissing?.contains(command) != true
    fun clockTakes(command: String): Boolean = clockMissing?.contains(command) != true

    /** Look at all of it (blocking: an APK's code is read; a background thread). [exec] runs a shell command. */
    @Synchronized
    fun look(exec: (String) -> String) {
        val began = android.os.SystemClock.uptimeMillis()
        dialerMissing = apkPath(exec("pm path $DIALER"))?.let { missingIn(it, DIALER_COMMANDS, anchor(DIALER)) }
        clockMissing = apkPath(exec("pm path $CLOCK"))?.let { missingIn(it, CLOCK_COMMANDS, anchor(CLOCK)) }
        xiaomiMissing = XIAOMI_APKS.firstOrNull { java.io.File(it).canRead() }?.let { xiaomiMissingIn(it) } ?: listOf("the rear app's package")
        lookedAt = System.currentTimeMillis()
        Log.i(TAG, "health: dialer ${say(dialerMissing)}, Clock ${say(clockMissing)}, Xiaomi's files ${xiaomiMissing?.let { if (it.isEmpty()) "all there" else "missing ${it.joinToString()}" }} (${android.os.SystemClock.uptimeMillis() - began} ms)")
    }

    private fun say(missing: Set<String>?) = when {
        missing == null -> "not readable"
        missing.isEmpty() -> "has its commands"
        else -> "lacks ${missing.joinToString()}"
    }

    fun report(coverMade: Boolean, coverError: String?, autostart: List<String>? = null, searchOffline: List<String>? = null): Bundle = Bundle().apply {
        putLong("lookedAt", lookedAt)
        putBoolean("coverMade", coverMade)
        coverError?.let { putString("coverError", it) }
        dialerMissing?.let { putStringArray("dialerMissing", it.toTypedArray()) }
        clockMissing?.let { putStringArray("clockMissing", it.toTypedArray()) }
        xiaomiMissing?.let { putStringArray("xiaomiMissing", it.toTypedArray()) }
        autostart?.let { putStringArray("autostart", it.toTypedArray()) }
        searchOffline?.let { putStringArray("searchOffline", it.toTypedArray()) }
    }

    /** The APK's path from `pm path`'s output (its first "package:" line); null for none. Pure. */
    fun apkPath(out: String): String? = out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.takeIf { it.isNotEmpty() }

    /** The app's own classes as dex names them ("Lcom/android/incallui/"): code that lacks them is not the app's real code. */
    fun anchor(pkg: String) = "L" + pkg.replace('.', '/') + "/"

    /**
     * Which of [words] the code (classes*.dex) of [apk] lacks; null where its real code could not be read: no dex at
     * all (a stripped APK, the code only in the system's compiled copy), or none with [anchor] in it (a stub). So a
     * command is called gone only where the app's own code was read and does not have it.
     */
    fun missingIn(apk: String, words: List<String>, anchor: String? = null): Set<String>? = runCatching {
        ZipFile(apk).use { zip ->
            val dexes = zip.entries().asSequence().filter { DEX.matches(it.name) }.toList()
            if (dexes.isEmpty()) return@use null
            val all = words + listOfNotNull(anchor)
            val found = HashSet<Int>()
            for (e in dexes) {
                zip.getInputStream(e).use { found += ByteScan.found(it, all.map { w -> w.toByteArray() }) }
                if (found.size == all.size) break
            }
            if (anchor != null && all.lastIndex !in found) return@use null
            words.filterIndexed { i, _ -> i !in found }.toSet()
        }
    }.onFailure { Log.w(TAG, "health: $apk not read: ${it.message}") }.getOrNull()

    private val DEX = Regex("""classes\d*\.dex""")

    /** Which of Xiaomi's pictures and pin files the rear app's package [apk] lacks, as XiaomiArt and XiaomiPin would look for them. */
    fun xiaomiMissingIn(apk: String): List<String> = runCatching {
        ZipFile(apk).use { zip ->
            val out = ArrayList<String>()
            for ((template, names) in listOf("phone" to CallFace.ART, "timer" to TimerFace.ART)) {
                val entry = zip.getEntry("assets/smart_assistant/$template")
                if (entry == null) { out += "$template/"; continue }
                val inside = HashSet<String>()
                ZipInputStream(zip.getInputStream(entry)).use { z -> while (true) { inside += (z.nextEntry ?: break).name } }
                names.filter { it !in inside }.forEach { out += "$template/$it" }
            }
            XiaomiPin.FILES.filter { zip.getEntry(it) == null }.forEach { out += "pin/${it.substringAfterLast('/')}" }
            out
        }
    }.getOrElse { listOf("the rear app's package (${it.message})") }
}

/** Which of several byte strings occur in a stream, read once, in pieces. Pure. */
object ByteScan {
    fun found(input: InputStream, needles: List<ByteArray>): Set<Int> {
        val tables = needles.map { kmp(it) }
        val state = IntArray(needles.size)
        val hit = HashSet<Int>()
        val buf = ByteArray(64 * 1024)
        while (hit.size < needles.size) {
            val n = input.read(buf)
            if (n < 0) break
            for (k in 0 until n) {
                val b = buf[k]
                for (j in needles.indices) {
                    if (j in hit) continue
                    val needle = needles[j]
                    var s = state[j]
                    while (s > 0 && needle[s] != b) s = tables[j][s - 1]
                    if (needle[s] == b) s++
                    if (s == needle.size) { hit += j; s = 0 }
                    state[j] = s
                }
            }
        }
        return hit
    }

    private fun kmp(p: ByteArray): IntArray {
        val t = IntArray(p.size)
        var k = 0
        for (i in 1 until p.size) {
            while (k > 0 && p[k] != p[i]) k = t[k - 1]
            if (p[k] == p[i]) k++
            t[i] = k
        }
        return t
    }
}
