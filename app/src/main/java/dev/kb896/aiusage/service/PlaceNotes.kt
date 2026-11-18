package dev.kb896.aiusage.service

import android.content.Context
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.place.Fences
import dev.kb896.aiusage.place.PinList
import dev.kb896.aiusage.place.PlacePermission

/**
 * Notes with a place ("Pin to location"): the old names, kept for their callers, on the new watch
 * (dev.kb896.aiusage.place: Play services' geofences per place, the arrival as a page that stands). The system's
 * proximity alert this used (LocationManager.addProximityAlert, a position asked for on our account every half hour,
 * through Xiaomi's provider) is gone (SPEC R12), and with it the "You are here" notification and its channel.
 */
object PlaceNotes {
    const val RADIUS_M = 150f

    fun fine(context: Context) = PlacePermission.fine(context)
    fun always(context: Context) = PlacePermission.always(context)

    /** The fences made what the notes say (owed ones set, a place with no note left forgotten). */
    fun sync(context: Context, @Suppress("UNUSED_PARAMETER") pins: List<Pin> = ConfigStore(context).load().pins) =
        Fences.sync(context, "asked")

    /** A note was removed (its [at]): its place's fence goes when it was the last note there. */
    fun forget(context: Context, @Suppress("UNUSED_PARAMETER") at: Long) = Fences.sync(context, "a note was removed")

    /** [pins] with the note pinned at [at] arrived at [now]: it and every note at its place stand on the back; null when it has no place or is gone. */
    fun arrived(pins: List<Pin>, at: Long, now: Long): List<Pin>? {
        val key = pins.firstOrNull { it.at == at }?.let(PinList::keyOf) ?: return null
        return PinList.arrived(pins, key, now)
    }
}
