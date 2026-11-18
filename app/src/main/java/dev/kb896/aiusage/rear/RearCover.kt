package dev.kb896.aiusage.rear

import android.graphics.PixelFormat
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

class RearCover(private val layerStack: () -> Int, private val who: String = "shell", private val size: () -> Pair<Int, Int>) {
    private var layer: SurfaceControl? = null
    private var saidUp = false
    /** Why the last try to make the layer failed; null while none has. For the health check (IRearService.health). */
    @Volatile var lastError: String? = null
        private set
    /** Whether the layer was made in this process. */
    val made: Boolean get() = layer != null
    @Volatile var isUp = false
        private set

    /** Up or away, now, on the caller's thread: one transaction. Safe to call again and again. */
    @Synchronized
    fun set(up: Boolean, why: String) {
        if (up == isUp && (layer != null || !up)) return
        if (up && layer == null) {
            val (w, h) = size()
            layer = runCatching {
                val sc = ShellLayer.create(
                    "cover", layerStack(), ShellLayer.Z_BASE, w, h,
                    trusted = false, visible = false, alpha = 1f, format = PixelFormat.RGBA_8888, opaque = true,
                )
                // A layer with no buffer shows nothing and stops no touch: one black picture, once.
                val s = Surface(sc)
                try {
                    val c = ShellLayer.lockPanelCanvas(s)
                    try { c.drawColor(0xFF000000.toInt()) } finally { s.unlockCanvasAndPost(c) }
                } finally { s.release() }
                sc
            }.onFailure { lastError = it.message ?: it.javaClass.simpleName; Log.w(TAG, "cover: no layer: ${it.message}") }.getOrNull() ?: return
            lastError = null
        }
        val sc = layer ?: return
        runCatching { SurfaceControl.Transaction().use { it.setVisibility(sc, up).setAlpha(sc, 1f).apply() } }
            .onFailure { Log.w(TAG, "cover: ${it.message}") }
        if (up != isUp) Log.i(TAG, "cover: ${if (up) "up" else "away"} — $why")
        // The restart's own line, once per process (service/RestartLog): the back is ours again after a start.
        if (up && !saidUp) { saidUp = true; Log.i(RESTART_TAG, "cover up ($who) — $why") }
        isUp = up
    }

    @Synchronized
    fun close() {
        ShellLayer.remove(layer); layer = null; isUp = false
    }

    companion object {
        private const val TAG = "AiUsageRear"
        const val CAMERA = "com.android.camera/.Camera"
        /** What reads the back's top app, for both processes that keep a cover. */
        const val READ = "dumpsys window displays | grep -E 'mDisplayId=|mFocusedApp='"

        /**
         * Whether the back is to be covered, given the app that is on top of it ([top]: its
         * activity as the window manager names it, "package/.Activity"; null for none or unknown).
         *
         * THE APP ON TOP, not the window with the input focus: only one display has that at a time
         * on this phone, so the Camera opened on the back while the front had the focus did not
         * "have the focus" there until the back was next touched, and stood under the cover
         * (measured).
         *
         * Covered: with no face of ours wanted, never. Otherwise always, except under an app that
         * is meant to be on the back ([meant]): AN ALLOWLIST. Unknown is covered: black for a moment
         * is right, Xiaomi's screen for a moment is the fault this is for.
         */
        fun wanted(faceWanted: Boolean, top: String?, allowed: Set<String> = emptySet()): Boolean {
            if (!faceWanted) return false
            val name = top?.trim().orEmpty()
            if (!name.contains('/')) return true
            return !meant(name, allowed)
        }

        /**
         * Whether the activity [name] ("package/.Activity") is meant to be seen on the back. Xiaomi's Camera
         * includes the presentation activity used by both rear standby and live preview. The camera controls
         * that transition within the activity while our layers stay aside. Our own package and [allowed] apps
         * can also take the back. Xiaomi's rear launcher stays covered.
         */
        fun meant(name: String, allowed: Set<String>): Boolean {
            val pkg = name.substringBefore('/')
            return when (pkg) {
                dev.kb896.aiusage.Panel.XIAOMI_REAR_APP -> false
                CAMERA_PKG, OWN -> true
                else -> pkg in allowed
            }
        }

        /**
         * The allowlist as the hint file gives it to the guard. The main process also steps away for the app it put
         * on the back itself (a guest, `guest.onTop`), and it knows that one BY ITS TASK, whatever package is on top in
         * it: a permission prompt, the system's chooser, a browser's custom tab opened in the guest's task. The guard
         * knows only packages, so while the main process steps away for the guest, the package on top of the back now
         * ([top]) is named to it as well; else the guard's cover (one below ours, over every app) would put the prompt
         * under black. Never Xiaomi's rear app: [meant] refuses it whatever a list says, and it is not written either.
         */
        fun hintAllow(allow: Set<String>, guestOnTop: Boolean, top: String?): Set<String> {
            if (!guestOnTop) return allow
            val name = top?.trim().orEmpty()
            if (!name.contains('/')) return allow
            val pkg = name.substringBefore('/')
            if (pkg == dev.kb896.aiusage.Panel.XIAOMI_REAR_APP || !Hint.isPackage(pkg)) return allow
            return allow + pkg
        }

        const val CAMERA_PKG = "com.android.camera"
        /** This app's package (the debug build's "dev.chicken.rear.dev"): its own pages on the back are meant. */
        const val OWN = dev.kb896.aiusage.BuildConfig.APPLICATION_ID
        /** The tag of the lines a restart is read by afterwards (service/RestartLog.TAG; the same string, both processes). */
        const val RESTART_TAG = "AiUsageRestart"

        /** The app on top of display [id], from [READ]'s output ("mFocusedApp=ActivityRecord{hash u0 pkg/.Activity t12}"); null for none found. */
        fun focusOf(dump: String, id: Int): String? {
            val at = Regex("mDisplayId=$id\\b").find(dump) ?: return null
            val rest = dump.substring(at.range.last + 1)
            val end = Regex("mDisplayId=\\d+").find(rest)?.range?.first ?: rest.length
            return Regex("mFocusedApp=ActivityRecord\\{\\S+ \\S+ (\\S+?)(?: t\\d+)?\\}").find(rest.substring(0, end))?.groupValues?.get(1)?.trim()
        }

        /**
         * Whether a line of the system's event log says the apps on a display may have changed
         * (an activity resumed, a task moved): the cue to read the back's top app again.
         */
        fun changed(line: String): Boolean = line.contains("wm_set_resumed_activity") || line.contains("wm_task_moved") || line.contains("wm_on_top_resumed_gained_called")

        /** Xiaomi's rear launcher named as resumed in such a line: it runs only on the back, so the back is its again, now. */
        fun launcherResumed(line: String): Boolean = line.contains("wm_set_resumed_activity") && line.contains(dev.kb896.aiusage.Panel.XIAOMI_REAR_APP + "/")
        const val LAUNCHER = "com.xiaomi.subscreencenter/.SubScreenLauncher"

        /** The hint file: how a new process (the main shell's, or the guard) is told what the back is, before it knows anything else. */
        const val HINT_FILE = "/data/local/tmp/chicken-rear-cover"

        /**
         * The main shell's process name (ShizukuBridge binds RearUserService with the suffix "rear"; Shizuku names its
         * user services "package:suffix", as the guard is "…:guard").
         */
        const val MAIN_PROCESS = "$OWN:rear"

        /**
         * Whether [cmdline] (the text of /proc/<pid>/cmdline, its arguments split by NULs; null where it could not be
         * read: no such process) is the main shell's. A pid the system gave to another process since is not.
         */
        fun isMainShell(cmdline: String?): Boolean = cmdline?.substringBefore('\u0000')?.trim() == MAIN_PROCESS

        /**
         * Whether the guard reads the back's top app from the window manager on its own clock (a `dumpsys` fork every
         * few seconds). Only while the main shell is not there: while it lives, the guard reads on the event log's lines
         * as before, and whenever the hint file changes ([guardReads]), which the main shell writes every time its own
         * cover goes up or away. That was 1440 forks an hour, lit or dark, for a cover that lies under the main one.
         */
        fun guardPolls(mainAlive: Boolean): Boolean = !mainAlive

        /** Whether the guard reads the top app at this tick: on its own clock ([guardPolls]), or because the hint file changed. */
        fun guardReads(mainAlive: Boolean, hintChanged: Boolean): Boolean = guardPolls(mainAlive) || hintChanged
    }

    data class Hint(val face: Boolean, val allow: Set<String>) {
        /** [pid]: the writing process's, null for none (a test's exact text); [ownPid] by default. */
        fun text(now: Long = System.currentTimeMillis(), pid: Int? = ownPid()): String {
            val by = if (pid != null && pid > 0) " pid=$pid" else ""
            return if (!face) "0 $now$by\n" else (listOf("1 $now$by") + allow.sorted()).joinToString("\n", postfix = "\n")
        }

        companion object {
            private val PKG = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
            private val PID = Regex("""pid=(\d+)""")

            private fun ownPid(): Int? = runCatching { android.os.Process.myPid() }.getOrNull()?.takeIf { it > 0 }

            /** The pid of the process that wrote [text] (its first line's "pid=<n>"); null for none, the older files. */
            fun writerOf(text: String?): Int? {
                val first = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
                return first.split(' ').firstNotNullOfOrNull { PID.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it > 0 }
            }
            /** How much newer than its own time a "0" file may be and still be the one written (the write itself). */
            const val TOUCHED_MS = 2_000L

            /** A package's name: dotted, or the system's own "android" (its chooser and resolver). */
            fun isPackage(name: String) = PKG.matches(name) || name == "android"

            /** [modifiedMs]: the file's last change (File.lastModified), null where not known. */
            fun parse(text: String?, modifiedMs: Long? = null): Hint {
                val lines = text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()
                val first = lines.firstOrNull()?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
                if (first.firstOrNull() == "0") {
                    val written = first.getOrNull(1)?.toLongOrNull()
                    val touched = written != null && modifiedMs != null && modifiedMs > written + TOUCHED_MS
                    return if (touched) Hint(true, emptySet()) else Hint(false, emptySet())
                }
                return Hint(true, lines.drop(1).filter { isPackage(it) }.toSet())
            }

            fun read(path: String = HINT_FILE): Hint = runCatching {
                val f = java.io.File(path)
                if (!f.exists()) null else parse(f.readText(), f.lastModified().takeIf { it > 0L })
            }.getOrNull() ?: parse(null)
        }
    }
}
