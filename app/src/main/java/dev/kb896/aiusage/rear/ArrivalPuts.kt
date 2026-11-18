package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.place.PinList

/**
 * A DISMISSAL ON THE BACK IS NEVER UNDONE BY A FROZEN APP (SPEC R18). The shell takes an arrival off the back the moment
 * he puts it away (the bin: done; a stroke down: not now) and remembers which arrival that was (the note and its
 * arrivedAt). It tells the app; HyperOS may have the app frozen (while the Camera is open), so it tells it again with
 * every later payload that still carries that arrival, and the page does not come back meanwhile. A put that the
 * payload shows taken (the note gone, or not standing any more, or standing for a NEW arrival) is forgotten. Pure.
 */
class ArrivalPuts {
    data class Put(val at: Long, val arrivedAt: Long, val bin: Boolean)

    private val puts = LinkedHashMap<Long, Put>()

    @Synchronized fun bin(pin: Pin) { puts[pin.at] = Put(pin.at, pin.arrivedAt, bin = true) }
    @Synchronized fun later(pin: Pin) { puts[pin.at] = Put(pin.at, pin.arrivedAt, bin = false) }

    private fun hides(put: Put?, pin: Pin) = put != null && put.arrivedAt == pin.arrivedAt

    /** The notes that stand on the back as the payload has them, less what he has put away. */
    @Synchronized fun visible(pins: List<Pin>): List<Pin> = PinList.arrivals(pins).filter { !hides(puts[it.at], it) }

    /** What the app has not taken yet (to be told again); what it has taken is forgotten here. */
    @Synchronized fun untaken(pins: List<Pin>): List<Put> {
        val byAt = pins.associateBy { it.at }
        val left = ArrayList<Put>()
        val it = puts.values.iterator()
        while (it.hasNext()) {
            val put = it.next()
            val pin = byAt[put.at]
            val taken = pin == null || pin.arrivedAt != put.arrivedAt
            if (taken) it.remove() else left += put
        }
        return left
    }

    @Synchronized fun clear() = puts.clear()
}
