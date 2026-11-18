package dev.kb896.aiusage

object DebugLock {
    /** The intent extra the token comes in. */
    const val EXTRA_TOKEN = "debug_token"
    /** The token's file, in the app's external files folder. */
    const val FILE = "debug-token"
    /** A token older than this is not taken (and is thrown away). */
    const val MAX_AGE_MS = 60_000L
    /** A file stamped up to this far AHEAD of the app's clock is taken (two processes' clocks, the file system's rounding); further is not. */
    const val AHEAD_MS = 5_000L
    /** Shorter than this is no token: a guessable one would make the lock no lock (debug-intent.sh writes 32 hex digits). */
    const val MIN_LENGTH = 16

    enum class Verdict(val open: Boolean, val why: String) {
        OPEN(true, "the shell's token matches"),
        NOT_ASKED(false, "no debug extra came"),
        RELEASE(false, "not a debug build"),
        NO_TOKEN(false, "no debug_token came with them"),
        USED(false, "that token was used already"),
        NO_FILE(false, "files/$FILE is not there (never written, or used already)"),
        STALE(false, "files/$FILE is older than ${MAX_AGE_MS / 1000} s"),
        SHORT(false, "the token is too short to be one"),
        WRONG(false, "debug_token is not the one in files/$FILE"),
    }

    /**
     * Whether the debug extras of one intent are let in.
     * [asked]: any debug extra is on the intent. [given]: its [EXTRA_TOKEN]. [stored]: the token file's words (null: no
     * file), written at [storedAt] (the file's time, ms since the epoch); [now] the same clock. [used]: tokens this
     * process has let in already.
     */
    fun verdict(debugBuild: Boolean, asked: Boolean, given: String?, stored: String?, storedAt: Long, now: Long, used: Set<String> = emptySet()): Verdict {
        if (!asked) return Verdict.NOT_ASKED
        if (!debugBuild) return Verdict.RELEASE
        val g = given?.trim().orEmpty()
        if (g.isEmpty()) return Verdict.NO_TOKEN
        if (g in used) return Verdict.USED
        val s = stored?.trim() ?: return Verdict.NO_FILE
        val age = now - storedAt
        if (age > MAX_AGE_MS || age < -AHEAD_MS) return Verdict.STALE
        if (s.length < MIN_LENGTH || g.length < MIN_LENGTH) return Verdict.SHORT
        if (!same(s, g)) return Verdict.WRONG
        return Verdict.OPEN
    }

    /** Whether the token file is to be deleted after [v]: used (once only), or too old to be of use to anyone. A wrong guess leaves the shell's own in place. */
    fun discard(v: Verdict): Boolean = v == Verdict.OPEN || v == Verdict.STALE

    /** adb's shell (`adb shell`, and adbd, which `adb forward` connects from) runs as this uid. */
    const val SHELL_UID = 2000
    const val ROOT_UID = 0

    /**
     * Whether a peer of [uid] may use a debug build's test door that has no token (a local socket: rear/DemoTouchFeed).
     * The shell or root only. Any app on the phone has a uid of its own (10000 and up), and an abstract socket can be
     * reached by every one of them.
     */
    fun fromShell(uid: Int): Boolean = uid == SHELL_UID || uid == ROOT_UID

    /** The system property that lets a debug build take made-up positions as real ones: `adb shell setprop debug.chickenrear.mockfixes 1`. Apps cannot set a debug.* property; the shell can. */
    const val MOCK_FIXES_PROP = "debug.chickenrear.mockfixes"

    /**
     * Whether a position is taken as a mock, which never counts for a place (place/Fixes, place/Arrivals). A release
     * build: whenever it is one. A debug build: the same unless the shell set [MOCK_FIXES_PROP] to 1 ([prop] is read
     * only then). A debug build used to take every mock as real, so the app the phone's developer options name as its
     * mock location app could set off a place's arrival.
     */
    fun mockFix(isMock: Boolean, debugBuild: Boolean, prop: () -> String?): Boolean =
        isMock && !(debugBuild && prop()?.trim() == "1")

    fun launchDemo(debugBuild: Boolean, asked: Boolean, stored: () -> Boolean): Boolean = asked && (!debugBuild || stored())

    /** Equal, in a time that does not tell how much of a guess was right. */
    fun same(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8); val y = b.toByteArray(Charsets.UTF_8)
        var d = x.size xor y.size
        for (i in 0 until maxOf(x.size, y.size)) d = d or ((x.getOrElse(i) { 0 }.toInt()) xor (y.getOrElse(i) { 0 }.toInt()))
        return d == 0
    }
}

/**
 * One intent's debug extras, behind [DebugLock]: [bool], [float] and [string] give the intent's values when the shell's
 * token let them in, else the defaults (as in a release build). Made once per screen ([of]); the token is spent then.
 */
class DebugExtras private constructor(private val intent: android.content.Intent?, val open: Boolean) {
    fun bool(name: String, default: Boolean = false): Boolean = if (open && intent != null) intent.getBooleanExtra(name, default) else default
    fun float(name: String, default: Float): Float = if (open && intent != null) intent.getFloatExtra(name, default) else default
    fun string(name: String): String? = if (open) intent?.getStringExtra(name) else null

    /** Into the screen's saved state, so that a screen made again (the process gone meanwhile) keeps what it was let in to. */
    fun save(out: android.os.Bundle) { if (open) out.putBoolean(SAVED, true) }

    companion object {
        private const val TAG = "AiUsageDebug"
        private const val SAVED = "dev.chicken.rear.debug_open"
        /** Tokens this process let in: never twice, even where the file could not be deleted. */
        private val used = java.util.Collections.synchronizedSet(HashSet<String>())

        /**
         * [names]: the debug extras this screen takes. [saved]: the screen's saved state (a screen made again after it
         * was let in is let in again: only this app writes that state).
         */
        fun of(context: android.content.Context, intent: android.content.Intent?, names: Collection<String>, saved: android.os.Bundle? = null): DebugExtras {
            // A release build: nothing looked at, read or logged, as before the lock.
            if (!BuildConfig.DEBUG) return DebugExtras(null, false)
            if (saved?.getBoolean(SAVED, false) == true) return DebugExtras(intent, true)
            val present = names.filter { intent?.hasExtra(it) == true }
            val file = runCatching { context.getExternalFilesDir(null)?.let { java.io.File(it, DebugLock.FILE) } }.getOrNull()
            val stored = if (present.isEmpty()) null else file?.takeIf { it.isFile }?.let { f -> runCatching { f.readText().take(256) }.getOrNull() }
            val given = intent?.getStringExtra(DebugLock.EXTRA_TOKEN)
            val v = DebugLock.verdict(BuildConfig.DEBUG, present.isNotEmpty(), given, stored, file?.lastModified() ?: 0L, System.currentTimeMillis(), used)
            if (DebugLock.discard(v)) runCatching { file?.delete() }
            if (v.open) given?.trim()?.let(used::add)
            if (present.isNotEmpty()) android.util.Log.i(TAG, if (v.open) "debug extras ${present.joinToString()} let in: ${v.why}"
                else "debug extras ${present.joinToString()} ignored: ${v.why}; the normal path runs")
            return DebugExtras(intent, v.open)
        }
    }
}
