package dev.kb896.aiusage.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchTest {
    @Test fun `nothing is sent before three characters, and the bias is the map's middle rounded to a tenth of a degree, never more`() {
        assertNull(PlaceSearch.url("ab", 48.0, 11.0, 14.0, "en"))
        assertNull(PlaceSearch.url("  ab  ", null, null, null, null))
        val u = PlaceSearch.url("Library", 12.34567, 34.56789, 14.6, "de")!!
        assertTrue(u, u.startsWith("https://photon.komoot.io/api/?q=Library&limit=4"))
        // Rounded to a tenth of a degree (the town, not the street) and a town's zoom: nothing finer leaves the phone.
        assertTrue(u, u.contains("&lat=12.3&lon=34.6&zoom=11"))
        assertFalse(u, Regex("""\d+\.\d{2,}""").containsMatchIn(u))
        assertTrue("a wide map keeps its own zoom", PlaceSearch.url("abc", -33.86882, 151.20929, 6.2, null)!!.contains("&lat=-33.9&lon=151.2&zoom=6"))
        assertTrue(u.endsWith("&lang=de"))
        assertFalse("a language Photon does not know is left out", PlaceSearch.url("abc", null, null, null, "zh")!!.contains("lang"))
        assertFalse("no bias without the map", PlaceSearch.url("abc", null, null, null, null)!!.contains("lat="))
    }

    @Test fun `the user agent names the app and no person`() {
        assertEquals("ChickenRear/1.2 (Android app; pin to location)", PlaceSearch.userAgent("1.2"))
    }

    @Test fun `Photon's answer becomes four hits at most, with a name and a line of where`() {
        val body = """
            {"type":"FeatureCollection","features":[
             {"type":"Feature","geometry":{"type":"Point","coordinates":[11.5,48.1]},"properties":{"name":"Gym One","street":"Main St","housenumber":"5","city":"Town","country":"Land"}},
             {"type":"Feature","geometry":{"type":"Point","coordinates":[11.6,48.2]},"properties":{"street":"Side St","housenumber":"9","city":"Town","country":"Land"}},
             {"type":"Feature","geometry":{"type":"Point","coordinates":[11.7,48.3]},"properties":{"name":"Town","city":"Town","country":"Land"}},
             {"type":"Feature","geometry":{"type":"Point","coordinates":[11.5,48.1]},"properties":{"name":"Gym One","street":"Main St","housenumber":"5","city":"Town","country":"Land"}},
             {"type":"Feature","geometry":{"type":"Point"},"properties":{"name":"broken"}},
             {"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"properties":{"name":"A"}},
             {"type":"Feature","geometry":{"type":"Point","coordinates":[1,3]},"properties":{"name":"B"}}
            ]}
        """.trimIndent()
        val hits = PlaceSearch.parse(body)
        assertEquals(4, hits.size)
        assertEquals(PlaceSearch.Hit("Gym One", "Main St 5, Town, Land", 48.1, 11.5), hits[0])
        assertEquals(PlaceSearch.Hit("Side St 9", "Town, Land", 48.2, 11.6), hits[1])
        assertEquals(PlaceSearch.Hit("Town", "Land", 48.3, 11.7), hits[2])
        assertEquals("A", hits[3].name)
        assertTrue(PlaceSearch.parse("not json").isEmpty())
        assertTrue(PlaceSearch.parse("{}").isEmpty())
    }

    @Test fun `one request a second at most`() {
        val g = PlaceSearch.Gate()
        assertEquals(0L, g.waitMs(10_000L))
        g.sent(10_000L)
        assertEquals(1_000L, g.waitMs(10_000L))
        assertEquals(400L, g.waitMs(10_600L))
        assertEquals(0L, g.waitMs(11_000L))
    }
}
