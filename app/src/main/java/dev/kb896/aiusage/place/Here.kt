package dev.kb896.aiusage.place

import dev.kb896.aiusage.data.Pin

object Here {
    /** The places the phone is at now, by their keys (Arrivals.key): those whose state is INSIDE. */
    fun keys(visits: Map<String, Arrivals.Visit>): Set<String> =
        visits.filterValues { it.where == Arrivals.Where.INSIDE }.keys

    /** Whether the place [key] is here by [visits]: inside it; outside or not known is not. */
    fun at(visits: Map<String, Arrivals.Visit>, key: String): Boolean = visits[key]?.where == Arrivals.Where.INSIDE

    /**
     * [pins] with each note's [Pin.here] made what [here] says: a note for a place is here when its place's key is in
     * it; a plain note never carries it. The same list (the same objects) where nothing changes.
     */
    fun marked(pins: List<Pin>, here: Set<String>): List<Pin> {
        var changed = false
        val out = pins.map { p ->
            val h = PinList.keyOf(p)?.let { it in here } ?: false
            if (h == p.here) p else { changed = true; p.copy(here = h) }
        }
        return if (changed) out else pins
    }

    /**
     * The notes the back's notes page lists, in their order: every plain note, a note for a place only while it is
     * [Pin.here]. [landing]: the note (by its Pin.at) a pin is landing on the back with right now, listed whatever its
     * place (a pin always ends on the notes page showing the note just pinned: rear/NoteLanding); 0 for none.
     */
    fun onBack(pins: List<Pin>, landing: Long = 0L): List<Pin> =
        if (pins.all { listed(it, landing) }) pins else pins.filter { listed(it, landing) }

    /** Whether the back's notes page lists [pin] ([onBack]). */
    fun listed(pin: Pin, landing: Long = 0L): Boolean = !pin.placed || pin.here || (landing != 0L && pin.at == landing)
}
