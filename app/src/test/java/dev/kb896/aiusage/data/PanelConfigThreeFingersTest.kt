package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelConfigThreeFingersTest {

    @Test fun `a screenshot by default, and a stored config from before it reads as one`() {
        assertEquals(ThreeFingers.SCREENSHOT, PanelConfig().threeFingers)
        assertEquals(ThreeFingers.SCREENSHOT, PanelConfig.FRESH.threeFingers)
        assertEquals(ThreeFingers.SCREENSHOT, PanelConfig.decode("""{"rearMode":"COMPANION"}""").threeFingers)
        assertEquals(ThreeFingers.SCREENSHOT, PanelConfig.decode("""{"rearMode":"COMPANION","musicFollows":true}""").threeFingers)
    }

    @Test fun `each choice is written out by name and read back the same`() {
        for (c in ThreeFingers.entries) {
            val raw = PanelConfig.encode(PanelConfig(threeFingers = c))
            assertTrue(raw, raw.contains("\"threeFingers\":\"${c.name}\""))
            assertEquals(c, PanelConfig.decode(raw).threeFingers)
        }
        assertEquals(listOf("SCREENSHOT", "RECORDING", "BACK_OFF", "NOTHING"), ThreeFingers.entries.map { it.name })
    }

    @Test fun `a choice this build does not know reads as a screenshot, and the rest of the config is kept`() {
        val c = PanelConfig.decode("""{"rearMode":"COMPANION","threeFingers":"SOMETHING_LATER","musicFollows":true}""")
        assertEquals(ThreeFingers.SCREENSHOT, c.threeFingers)
        assertTrue(c.musicFollows)
    }

    @Test fun `the payload to the back carries it, and an older payload without it reads as a screenshot`() {
        for (v in ThreeFingers.entries) {
            val payload = StatePayload.encode(DashboardState.EMPTY, PanelConfig(threeFingers = v))
            assertEquals(v, StatePayload.decode(payload).config.threeFingers)
        }
        val old = """{"fetchedAt":0,"config":{"rearMode":"COMPANION","musicFollows":true},"accounts":[]}"""
        assertEquals(ThreeFingers.SCREENSHOT, StatePayload.decode(old).config.threeFingers)
    }
}
