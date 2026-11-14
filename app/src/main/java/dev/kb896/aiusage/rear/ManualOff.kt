package dev.kb896.aiusage.rear

import android.content.Context
import java.io.File

/**
 * MANUAL OFF, the back's highest rule (RearPosture): the double tap on the lit back switched it
 * off, and it stays off until the user lights it himself — the system's double tap to wake on
 * the dark back, the power button, the app's "Show on the back". Meanwhile nothing automatic
 * lights it: the bridge declines every wake (ShizukuBridge.wake), pocket mode's way out does
 * not light it, and the panel takes no hold (RearActivity.holdScreen).
 *
 * A FILE, so it outlives either process and both read the same answer: the panel (:rear) sets
 * it and clears it on a lit edge it did not ask for; the app's process reads it before every
 * wake and clears it for "Show on the back" and, as a backstop, on the rear lighting up while
 * it is set (nothing automatic can have lit it then). Its presence is the latch.
 */
class ManualOff(private val file: File) {

    val on: Boolean get() = file.exists()

    /** True if it changed. */
    fun set(on: Boolean): Boolean {
        if (on == file.exists()) return false
        return runCatching { if (on) file.createNewFile() else file.delete() }.getOrDefault(false)
    }

    companion object {
        const val FILE = "rear-manual-off"
        fun of(context: Context) = ManualOff(File(context.filesDir, FILE))

        /**
         * COMPANION MODE has no panel to set the latch, so the shell reads it off the rear: the back
         * went from lit to dark (off or dozing) within [DOUBLE_TAP_DARK_MS] of a double tap on it,
         * over Xiaomi's screen or a guest of ours. Not when the double tap was left to the app on
         * top ([guarded], BackGuard), which then did not switch the back off. Times in one clock
         * (elapsedRealtime); [doubleTapAt] 0 for none yet. Pure (ManualOffTest).
         */
        fun byDoubleTap(wasLit: Boolean, nowLit: Boolean, doubleTapAt: Long, now: Long, guarded: Boolean): Boolean =
            wasLit && !nowLit && !guarded && doubleTapAt > 0L && now - doubleTapAt in 0L..DOUBLE_TAP_DARK_MS

        /** How soon after the second tap the back must go dark for it to count as switched off by hand. */
        const val DOUBLE_TAP_DARK_MS = 1_500L
    }
}
