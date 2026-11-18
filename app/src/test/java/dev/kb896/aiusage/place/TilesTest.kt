package dev.kb896.aiusage.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TilesTest {
    @Test fun `add, rename, place, move and remove`() {
        var t = Tiles.add(emptyList(), "Work", 10, 48.0, 11.0, 300)!!
        t = Tiles.add(t, " Gym ", 10)!!
        assertEquals(listOf(10L, 11L), t.map { it.id })
        assertEquals("Gym", t[1].name)
        assertFalse("a name alone has no position", t[1].set)
        t = Tiles.place(t, 11, 48.2, 11.2, 160)
        assertTrue(t[1].set); assertEquals(150, t[1].radius)
        t = Tiles.rename(t, 10, "Office")
        assertEquals("Office", t[0].name)
        assertEquals(t, Tiles.rename(t, 10, "   "))
        t = Tiles.move(t, 11, -1)
        assertEquals(listOf("Gym", "Office"), t.map { it.name })
        assertEquals(t, Tiles.move(t, 11, -1))
        t = Tiles.remove(t, 11)
        assertEquals(listOf("Office"), t.map { it.name })
        assertNull(Tiles.add(t, "  ", 20))
    }

    @Test fun `a full row takes no more`() {
        var t = emptyList<Tile>()
        for (i in 1..Tiles.MOST) t = Tiles.add(t, "T$i", i.toLong())!!
        assertNull(Tiles.add(t, "more", 99))
    }

    @Test fun `Home is offered from the home Wi-Fi as a name only, once`() {
        assertTrue(Tiles.offerHome(emptyList(), homeWifiMarked = true))
        assertFalse(Tiles.offerHome(emptyList(), homeWifiMarked = false))
        val t = Tiles.add(emptyList(), "home", 1)!!
        assertFalse(Tiles.offerHome(t, homeWifiMarked = true))
    }

    @Test fun `the store goes round, and a broken file is an empty store`() {
        val s = PlaceStore.State(
            visits = mapOf("p:1" to Arrivals.Visit(where = Arrivals.Where.OUTSIDE, exitAt = 5, owed = false)),
            tiles = listOf(Tile(1, "Gym", 1.0, 2.0, 200)),
            draft = PlaceStore.Draft("words", 1.0, 2.0, 300, "Gym", 1),
            camera = PlaceStore.Camera(1.0, 2.0, 15.0),
        )
        assertEquals(s, PlaceStore.decode(PlaceStore.encode(s)))
        assertEquals(PlaceStore.State(), PlaceStore.decode("{not json"))
        assertEquals(setOf("p:1"), s.only(setOf("p:1", "p:2")).visits.keys)
        assertTrue(s.only(emptySet()).visits.isEmpty())
    }
}
