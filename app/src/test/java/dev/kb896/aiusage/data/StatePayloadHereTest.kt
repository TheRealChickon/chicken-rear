package dev.kb896.aiusage.data

import dev.kb896.aiusage.place.Here
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Whether a note's place is here crosses to the back with the notes, in every payload (Pin.here; place/Here). */
class StatePayloadHereTest {
    // Made-up places, none of his.
    private val pins = listOf(
        Pin("plain", 5),
        Pin("towels", 4, 10.0, 20.0, radius = 150, place = "Gym", here = true, watched = true),
        Pin("milk", 3, 10.5, 20.5, radius = 300, here = false),
        Pin("standing", 2, 11.0, 21.0, radius = 150, arrivedAt = 99, here = false),
    )

    @Test fun `each note's here crosses whole, and the back lists by it`() {
        val back = StatePayload.decode(StatePayload.encode(DashboardState.EMPTY, PanelConfig.DEFAULT.copy(pins = pins))).config.pins
        assertEquals(pins, back)
        assertEquals(listOf("plain", "towels"), Here.onBack(back).map { it.text })
        // The note standing as an arrival is still in the payload for its own page (the shell's arrivals read the whole list).
        assertEquals(99L, back.single { it.text == "standing" }.arrivedAt)
    }

    @Test fun `a payload from before the flag - place notes are not here until the app says so, plain notes are listed`() {
        val raw = """{"fetchedAt":0,"accounts":[],"config":{"pins":[{"text":"towels","at":4,"lat":10.0,"lng":20.0},{"text":"plain","at":3}]}}"""
        val back = StatePayload.decode(raw).config.pins
        assertFalse(back[0].here)
        assertEquals(listOf("plain"), Here.onBack(back).map { it.text })
    }
}
