package dev.kb896.aiusage.service

import android.app.NotificationManager
import android.content.Context
import dev.kb896.aiusage.BuildConfig
import dev.kb896.aiusage.data.PanelConfig
import java.io.File
import java.util.Calendar

/**
 * AT NIGHT THE BACK SLEEPS WITH THE PHONE ("Sleep at night", PanelConfig.sleepAtNight, on by default).
 *
 * A lit back keeps the whole phone
 * awake (its screen wake lock is on the rear's power group, and the phone as a whole neither suspends nor
 * dozes while any group is awake), so Xiaomi's night mode cannot begin. In one night the face-down rule lit the back
 * for 83 minutes, each time the phone was moved.
 *
 * The rule, for everyone and not only with home detection: in the night's hours (22:00 to 07:00) or while
 * Do Not Disturb is on, WITH THE FRONT OFF,
 *  - the back is lit at most [ON_MS] after the front went off or after a finger on the back, then dark,
 *    its wake lock let go (StayOn.level);
 *  - the phone being moved does not light it again: the significant-motion watch is not armed, and a
 *    posture that changes starts no new time (StayOn);
 *  - nothing of ours wakes it: not a notification, not the charger, not being picked up (ShizukuBridge.wake).
 * Only a finger on the back or the front coming on lights it. By day everything is as it was: face down is lit, on
 * purpose.
 */
object NightRule {
    /** How long the back may stay lit after the front went off, or after a finger on it, at night. */
    const val ON_MS = 2 * 60_000L

    /** Night, as far as this rule goes: the switch on, and the night's hours or Do Not Disturb. */
    fun night(switchOn: Boolean, hourOfDay: Int, doNotDisturb: Boolean): Boolean =
        switchOn && HomeNet.bedtime(hourOfDay, doNotDisturb)

    /** The back's time is up: night, the front off, and [quietForMs] since it went off or the back was touched. */
    fun timeUp(night: Boolean, frontOn: Boolean, quietForMs: Long): Boolean = night && !frontOn && quietForMs >= ON_MS

    /** A wake of our own (a notification, the charger, picked up) is not made: night, and the front off. */
    fun declinesWake(night: Boolean, frontOn: Boolean): Boolean = night && !frontOn

    /** Whether the phone being moved may be watched for, to look at its posture: not at night. */
    fun watchesMotion(night: Boolean): Boolean = !night

    /** Night now, read from the phone: the clock, Do Not Disturb, the switch; a debug build's override first. */
    fun now(context: Context, config: PanelConfig): Boolean {
        if ("night" in debugWords(context)) return true
        val dnd = runCatching {
            context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
                ?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
        }.getOrNull() == true
        return night(config.sleepAtNight, Calendar.getInstance().get(Calendar.HOUR_OF_DAY), dnd)
    }

    /**
     * A debug build's forced posture and night, from a file adb can write (`files/posture-override` in the
     * app's shared folder): words apart by spaces, "night" among them for the night whatever the clock says
     * ("facedown night"). Empty in a release build and without the file.
     */
    fun debugWords(context: Context): List<String> {
        if (!BuildConfig.DEBUG) return emptyList()
        val dir = context.getExternalFilesDir(null) ?: return emptyList()
        return runCatching {
            File(dir, "posture-override").takeIf { it.isFile }?.readText()?.lowercase()?.split(Regex("[\\s,]+"))?.filter { it.isNotEmpty() }
        }.getOrNull().orEmpty()
    }
}
