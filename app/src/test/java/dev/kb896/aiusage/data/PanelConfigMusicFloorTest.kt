package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelConfigMusicFloorTest {

    @Test fun `30 by default, and a stored config from before it reads as 30`() {
        assertEquals(30, PanelConfig().musicFloor)
        assertEquals(30, PanelConfig.MUSIC_FLOOR)
        assertEquals(30, PanelConfig.decode("""{"rearMode":"COMPANION"}""").musicFloor)
        assertEquals(30, PanelConfig.decode("""{"rearMode":"COMPANION","musicFollows":true}""").musicFloor)
        assertEquals(30, PanelConfig.FRESH.musicFloor)
    }

    @Test fun `written out and read back the same, at 0, 30, 57, 70 and 100`() {
        for (v in listOf(0, 30, 57, 70, 100)) {
            val raw = PanelConfig.encode(PanelConfig(musicFloor = v))
            assertTrue(raw, raw.contains("\"musicFloor\":$v"))
            assertEquals(v, PanelConfig.decode(raw).musicFloor)
        }
    }

    @Test fun `a number out of its range is read as the nearest end, and the rest of the config is kept`() {
        val high = PanelConfig.decode("""{"rearMode":"COMPANION","musicFloor":150,"musicFollows":true}""")
        assertEquals(100, high.musicFloor)
        assertTrue("the rest kept", high.musicFollows)
        assertEquals(0, PanelConfig.decode("""{"rearMode":"COMPANION","musicFloor":-5}""").musicFloor)
        assertEquals(100, PanelConfig.decode(PanelConfig.encode(PanelConfig(musicFloor = 1000))).musicFloor)
    }

    @Test fun `the payload to the back carries it, and an older payload without it reads as 30`() {
        for (v in listOf(0, 30, 64, 100)) {
            val payload = StatePayload.encode(DashboardState.EMPTY, PanelConfig(musicFloor = v))
            assertEquals(v, StatePayload.decode(payload).config.musicFloor)
        }
        val old = """{"fetchedAt":0,"config":{"rearMode":"COMPANION","musicFollows":true},"accounts":[]}"""
        assertEquals(30, StatePayload.decode(old).config.musicFloor)
        // Out of range on the way over: read within it, as the stored one is.
        val bent = StatePayload.encode(DashboardState.EMPTY, PanelConfig()).replace("\"musicFloor\":30", "\"musicFloor\":250")
        assertEquals(100, StatePayload.decode(bent).config.musicFloor)
    }
}
