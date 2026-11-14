package dev.kb896.aiusage.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.data.StatePayload

/**
 * Notes with a place: a note pinned "here" comes back when he comes back
 * there. The system keeps the watch (LocationManager.addProximityAlert, no Play services), one per
 * note with a place; arriving, the note goes to the front of the back's notes as if just pinned
 * (the back shows it for a moment) and the phone says it too.
 *
 * Needs the location permission, and the "all the time" one for an arrival while the app is not in
 * use: asked for only when the first such note is made (HomeModel.pinHere), never before.
 */
object PlaceNotes {
    const val RADIUS_M = 150f
    const val ACTION = "dev.kb896.aiusage.PLACE_NOTE"
    const val EXTRA_AT = "at"
    private const val TAG = "AiUsagePlace"
    private const val CHANNEL = "place-notes"

    fun fine(context: Context) = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun always(context: Context) = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun intent(context: Context, at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, (at % Int.MAX_VALUE).toInt(),
        Intent(ACTION).setClass(context, PlaceReceiver::class.java).putExtra(EXTRA_AT, at),
        // Mutable: the system adds whether this is an arrival (KEY_PROXIMITY_ENTERING).
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** A watch for every note with a place; quiet without the permission (nothing is asked for from here). */
    fun sync(context: Context, pins: List<Pin> = ConfigStore(context).load().pins) {
        if (!fine(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        for (pin in pins.filter { it.placed }) runCatching {
            lm.addProximityAlert(pin.lat!!, pin.lng!!, RADIUS_M, -1L, intent(context, pin.at))
        }.onFailure { Log.w(TAG, "no watch for a note: ${it.message}") }
    }

    fun forget(context: Context, at: Long) {
        runCatching { context.getSystemService(LocationManager::class.java)?.removeProximityAlert(intent(context, at)) }
    }

    /** [pins] with the note of [at] marked as arrived at [now] and moved to the front; null when there is no such note. */
    fun arrived(pins: List<Pin>, at: Long, now: Long): List<Pin>? {
        val pin = pins.firstOrNull { it.at == at && it.placed } ?: return null
        return listOf(pin.copy(shownAt = now)) + pins.filter { it.at != at }
    }

    internal fun onArrived(context: Context, at: Long) {
        val store = ConfigStore(context)
        val config = store.load()
        val next = arrived(config.pins, at, System.currentTimeMillis()) ?: return
        store.save(config.copy(pins = next))
        val graph = Graph.of(context)
        runCatching { graph.bridge.update(StatePayload.encode(graph.repository.state.value, config.copy(pins = next), WeatherPoll.latest)) }
        // And on the phone, in case the back is not being looked at.
        runCatching {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            mgr.createNotificationChannel(NotificationChannel(CHANNEL, "Notes for a place", NotificationManager.IMPORTANCE_HIGH))
            if (mgr.areNotificationsEnabled()) mgr.notify(
                "place", (at % Int.MAX_VALUE).toInt(),
                Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("You are here").setContentText(next.first().text).setAutoCancel(true).build(),
            )
        }
        Log.i(TAG, "arrived at a note's place")
    }
}

class PlaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PlaceNotes.ACTION) return
        // Only an arrival: leaving the place says nothing.
        if (!intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)) return
        PlaceNotes.onArrived(context.applicationContext, intent.getLongExtra(PlaceNotes.EXTRA_AT, 0L))
    }
}
