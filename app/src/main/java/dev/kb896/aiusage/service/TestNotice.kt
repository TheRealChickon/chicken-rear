package dev.kb896.aiusage.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.kb896.aiusage.R

/**
 * "Send a test notification" on the companion screen's Notifications page: one ordinary
 * notification from this app, so the user sees a banner on the back at once instead of waiting
 * for someone to message him.
 *
 * The relay drops this app's own notifications (its ongoing status one would loop onto the back
 * once a minute), so the test is told apart by its tag ([isTest]) and let through. On a channel
 * of its own at high importance, so it is one the front would alert for and the back wakes for
 * it as for a real message (NotificationAlert).
 */
object TestNotice {
    const val TAG = "chicken_rear_test"
    private const val CHANNEL = "test"
    private const val ID = 4242

    /** Whether a posted notification is the test one: this app's, with the test tag. */
    fun isTest(postedPackage: String, ownPackage: String, tag: String?): Boolean =
        postedPackage == ownPackage && tag == TAG

    /** Posts it. False when Android would not show it (notifications off for this app). */
    fun send(context: Context): Boolean {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return false
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Test notifications", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Sent when you tap \"Send a test notification\"." },
        )
        if (!mgr.areNotificationsEnabled()) return false
        val n = Notification.Builder(context, CHANNEL)
            .setContentTitle("Chicken Rear")
            .setContentText("A test notification. This is how they look on the back.")
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()
        // Cancelled first, so a second tap is a new arrival rather than an update the relay
        // would read as already seen.
        mgr.cancel(TAG, ID)
        mgr.notify(TAG, ID, n)
        return true
    }
}
