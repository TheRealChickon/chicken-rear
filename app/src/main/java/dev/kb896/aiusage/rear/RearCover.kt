package dev.kb896.aiusage.rear

import android.graphics.PixelFormat
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

/**
 * The cover: one opaque black layer of the shell's own over Xiaomi's rear screen, up for as long
 * as that screen would otherwise be what the back shows.
 *
 * Until now Xiaomi's screen was the base and the scene was laid over it while it played: whenever
 * the scene was not up (ended, restarting, stepped aside for a swipe, a clip that could not be
 * read, the first moment after a wake) Xiaomi's screen was simply there. Now THIS is the base.
 * Under the scene and every page of ours, over Xiaomi's launcher and everything of Xiaomi's on the
 * back, there at all times, lit or dark: whatever of ours is not drawn, the back is black (and an
 * OLED pixel that is black is off), never Xiaomi's.
 *
 * NOT TRUSTED, so no touch reaches Xiaomi's screen under it either (as ShellScene).
 *
 * It steps away for exactly what is meant to be seen on the back in place of our face: Xiaomi's
 * Camera opened from the back, an app of ours put there ("Apps on the back"), a page of this app's
 * own. Not for Xiaomi's launcher, nor the Camera's blank standby page.
 * [wanted] says which, from the app that is on top of the back.
 *
 * Xiaomi's launcher is not suspended, hidden or stopped: it runs under the cover as it always did.
 */
class RearCover(private val layerStack: () -> Int, private val size: () -> Pair<Int, Int>) {
    private var layer: SurfaceControl? = null
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
                    val c = s.lockCanvas(null)
                    try { c.drawColor(0xFF000000.toInt()) } finally { s.unlockCanvasAndPost(c) }
                } finally { s.release() }
                sc
            }.onFailure { Log.w(TAG, "cover: no layer: ${it.message}") }.getOrNull() ?: return
        }
        val sc = layer ?: return
        runCatching { SurfaceControl.Transaction().use { it.setVisibility(sc, up).setAlpha(sc, 1f).apply() } }
            .onFailure { Log.w(TAG, "cover: ${it.message}") }
        if (up != isUp) Log.i(TAG, "cover: ${if (up) "up" else "away"} — $why")
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
         * is meant to be on the back: any activity that is not Xiaomi's launcher's and not the
         * Camera's standby page. Unknown is covered: black for a moment is right, Xiaomi's screen
         * for a moment is the fault this is for.
         */
        fun wanted(faceWanted: Boolean, top: String?): Boolean {
            if (!faceWanted) return false
            val name = top?.trim().orEmpty()
            if (!name.contains('/')) return true
            val pkg = name.substringBefore('/')
            if (pkg == dev.kb896.aiusage.Panel.XIAOMI_REAR_APP) return true
            if (pkg == "com.android.camera" && name.endsWith(CameraStandby.CLASS)) return true
            return false
        }

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
    }
}
