package dev.kb896.aiusage.rear

/**
 * The shell keeps the back lit only while the app keeps saying so.
 *
 * The app decides the level (service/StayOn) and the shell does the lighting: a screen wake lock on the rear
 * display, a wake when it is dark. The shell is uid 2000 and is never frozen; the app is, by Xiaomi's night
 * mode among others, and a frozen app takes nothing back. A level left standing then holds the whole phone
 * awake until morning. So the app says the level again every few seconds while it is above off
 * (StayOn.CONFIRM_MS), and the shell, not told for [SILENT_MS], lets the back go: the lock released, no wake
 * sent. An app that comes back says its level at once and has the back again.
 *
 * NOT "whenever the front is off": lying face down with the front off is lit by day, on purpose.
 *
 * AND NOT WHILE THE FRONT IS ON. HyperOS also freezes the app for as long as its Camera is open (its
 * "Immobulus" quick freeze), with the front on and him holding the phone: the back would go dark half a
 * minute into every photo session. With the front on the phone is awake whatever the back does, so a
 * silent app costs nothing there; the moment the front goes off, the back is let go.
 */
object StaySilence {
    const val SILENT_MS = 30_000L

    /** Whether a back held at [level] is let go, the app's last word being [silentForMs] old and the front on or off. */
    fun letGo(level: Int, silentForMs: Long, frontOn: Boolean): Boolean = level > 0 && !frontOn && silentForMs >= SILENT_MS
}
