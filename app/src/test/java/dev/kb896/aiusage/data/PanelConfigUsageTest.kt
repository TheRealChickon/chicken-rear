package dev.kb896.aiusage.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The usage page's settings: the week alone by default, and ChatGPT's Dots character. */
class PanelConfigUsageTest {
    private val lenient = Json { ignoreUnknownKeys = true }

    @Test fun `the five hours are off by default, also for a config from before the switch`() {
        assertFalse(PanelConfig().usageFiveHours)
        assertFalse(lenient.decodeFromString(PanelConfig.serializer(), "{}").usageFiveHours)
        assertFalse(PanelConfig.decode("""{"rearMode":"COMPANION"}""").usageFiveHours)
    }

    @Test fun `ChatGPT's character is off until switched on, also for a config from before the switch`() {
        assertFalse(PanelConfig().usageDots)
        assertFalse(lenient.decodeFromString(PanelConfig.serializer(), "{}").usageDots)
        assertFalse(PanelConfig.decode("""{"rearMode":"COMPANION","chatGptDot":"lime_frog"}""").usageDots)
        assertTrue(PanelConfig.decode(PanelConfig.encode(PanelConfig(usageDots = true))).usageDots)
    }

    @Test fun `the switch and the character are kept`() {
        val raw = PanelConfig.encode(PanelConfig(usageFiveHours = true, chatGptDot = "lime_frog"))
        val back = PanelConfig.decode(raw)
        assertTrue(back.usageFiveHours)
        assertEquals("lime_frog", back.chatGptDot)
        assertEquals("blue_beret", PanelConfig().chatGptDot)
    }
}
