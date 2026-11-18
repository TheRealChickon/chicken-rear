package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelConfigNavigationTest {
    @Test fun `navigation defaults on for fresh and older settings`() {
        assertTrue(PanelConfig().navigationOn)
        assertTrue(PanelConfig.FRESH.navigationOn)
        assertTrue(PanelConfig.decode("""{"rearMode":"COMPANION"}""").navigationOn)
    }

    @Test fun `navigation switch survives storage and the payload to the rear`() {
        for (enabled in listOf(true, false)) {
            val config = PanelConfig(navigationOn = enabled, musicFollows = true)
            val restored = PanelConfig.decode(PanelConfig.encode(config))
            assertEquals(enabled, restored.navigationOn)
            assertTrue(restored.musicFollows)
            val payload = StatePayload.encode(DashboardState.EMPTY, config)
            assertEquals(enabled, StatePayload.decode(payload).config.navigationOn)
        }
    }

    @Test fun `older payload enables navigation without changing stored music choice`() {
        val old = """{"fetchedAt":0,"config":{"rearMode":"COMPANION","musicFollows":true},"accounts":[]}"""
        val config = StatePayload.decode(old).config
        assertTrue(config.navigationOn)
        assertTrue(config.musicFollows)
    }
}
