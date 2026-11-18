package dev.kb896.aiusage.place

import dev.kb896.aiusage.data.Pin

/**
 * The pinned notes as a list, with and without a place (SPEC section 6, R21). Pure.
 *
 * - The same words pinned again move up rather than standing twice; but a note for a place is never replaced by a
 *   plain one with the same words, and a note for one place not by one for another. A PICTURE'S NOTE is never the
 *   same note again (two pictures with the same caption, or none, are two notes), nor does it replace a note of words.
 * - A picture's file goes with its note however it goes (data/ConfigStore.save, data/PinImage.gone).
 * - At most [MOST]: plain notes go first, the oldest first; with only place notes the oldest place note goes.
 * - Notes at one place are one zone (one fence, one arrival, one page with "1 / 3").
 * - The list is his whole list (the settings show it all); which of it the back's notes page lists is Here's rule.
 */
object PinList {
    const val MOST = 20
    const val LONGEST = 600
    /** A place's name on a note: 40 characters at most. */
    const val NAME_MOST = 40

    /** Where a new note is for. */
    data class Where(val lat: Double, val lng: Double, val radius: Int, val name: String?) {
        val key: String get() = Arrivals.key(lat, lng, radius)
    }

    fun keyOf(pin: Pin): String? = if (pin.placed) Arrivals.key(pin.lat!!, pin.lng!!, pin.zone) else null

    fun placeOf(pin: Pin): Arrivals.Place? = if (pin.placed) Arrivals.Place(pin.lat!!, pin.lng!!, pin.zone) else null

    data class Added(val pins: List<Pin>, val dropped: List<Pin>)

    /** [pins] with [text] pinned at [now] in front, for [where] or for no place; [image]: a picture's note (its file's name), [text] its caption. */
    fun added(pins: List<Pin>, text: String, now: Long, where: Where? = null, image: String? = null): Added {
        val clean = text.trim().take(LONGEST)
        val pin = if (where == null) Pin(clean, now, image = image)
        else Pin(clean, now, where.lat, where.lng, radius = Arrivals.zone(where.radius), place = where.name?.trim()?.take(NAME_MOST)?.ifBlank { null }, image = image)
        val key = keyOf(pin)
        // The same words for the same place (or both for none) are that note again, moved up; any other stays. Never a picture's.
        val replaced = if (image != null) emptyList() else pins.filter { it.text == clean && keyOf(it) == key && it.image == null }
        var list = listOf(pin) + (pins - replaced.toSet())
        val dropped = ArrayList<Pin>()
        while (list.size > MOST) {
            // Plain notes go first, the oldest of them; then the oldest place note. Never the one just pinned.
            val rest = list.drop(1)
            val gone = rest.filter { !it.placed }.minByOrNull { it.at } ?: rest.minByOrNull { it.at }!!
            list = list - gone
            dropped += gone
        }
        return Added(list, dropped)
    }

    /**
     * [pins] with PICTURES pinned at once (a share of several: ui/PinShare), one note each: [pictures] their times and
     * files' names, the first the note in front; [caption] on the first alone (the words came with them all).
     */
    fun addedPictures(pins: List<Pin>, caption: String, pictures: List<Pair<Long, String>>, where: Where? = null): Added {
        var list = pins
        val dropped = ArrayList<Pin>()
        for (i in pictures.indices.reversed()) {
            val (at, name) = pictures[i]
            val a = added(list, if (i == 0) caption else "", at, where, image = name)
            list = a.pins; dropped += a.dropped
        }
        return Added(list, dropped)
    }

    /** The places that have notes, each once. */
    fun places(pins: List<Pin>): Map<String, Arrivals.Place> = pins.mapNotNull { p -> placeOf(p)?.let { it.key to it } }.toMap()

    /** [pins] with every note at [key] that waits marked as arrived at [now]; null when none waits there (none, or all already standing). */
    fun arrived(pins: List<Pin>, key: String, now: Long): List<Pin>? {
        if (pins.none { keyOf(it) == key && it.arrivedAt == 0L }) return null
        return pins.map { if (keyOf(it) == key && it.arrivedAt == 0L) it.copy(arrivedAt = now) else it }
    }

    /** A note at [key] stands on the back: no new arrival there until it is dismissed. */
    fun standing(pins: List<Pin>, key: String): Boolean = pins.any { keyOf(it) == key && it.arrivedAt != 0L }

    /** The notes standing on the back as arrivals, newest arrival first (then newest note). */
    fun arrivals(pins: List<Pin>): List<Pin> = pins.filter { it.placed && it.arrivedAt != 0L }.sortedWith(compareByDescending<Pin> { it.arrivedAt }.thenByDescending { it.at })

    /** "Not now" on the note pinned at [at]: every note at its place waits again (it returns at the next visit). Null when there is none standing. */
    fun later(pins: List<Pin>, at: Long): List<Pin>? {
        val key = pins.firstOrNull { it.at == at }?.let(::keyOf) ?: return null
        if (!standing(pins, key)) return null
        return pins.map { if (keyOf(it) == key) it.copy(arrivedAt = 0L) else it }
    }

    data class Binned(val pins: List<Pin>, val lastAt: String?)

    /** The bin on the note pinned at [at]: it is done and goes. [Binned.lastAt]: its place's key when it was the last note there (its fence goes too). */
    fun binned(pins: List<Pin>, at: Long): Binned {
        val gone = pins.firstOrNull { it.at == at } ?: return Binned(pins, null)
        val next = pins.filter { it.at != at }
        val key = keyOf(gone)
        return Binned(next, key?.takeIf { k -> next.none { keyOf(it) == k } })
    }

    /** Whether each note's mark is whole: watched where its place's fence is held and the position may be read. */
    fun watched(pins: List<Pin>, held: (String) -> Boolean): List<Pin> = pins.map { p ->
        val k = keyOf(p) ?: return@map if (p.watched) p.copy(watched = false) else p
        val w = held(k)
        if (w == p.watched) p else p.copy(watched = w)
    }

    /**
     * A place note's place moved or its zone changed (in the settings): that note waits at the new place, off the back's
     * notes page until the phone is known to be there (Here). The old key, for its fence (dropped when no note is left there).
     */
    fun moved(pins: List<Pin>, at: Long, lat: Double, lng: Double, radius: Int, name: String?): Pair<List<Pin>, String?> {
        val pin = pins.firstOrNull { it.at == at && it.placed } ?: return pins to null
        val old = keyOf(pin)
        val next = pins.map { if (it.at == at) it.copy(lat = lat, lng = lng, radius = Arrivals.zone(radius), place = name?.trim()?.take(NAME_MOST)?.ifBlank { null }, arrivedAt = 0L, watched = false, here = false, hereConfirmedAt = 0L) else it }
        return next to old
    }
}
