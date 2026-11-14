package dev.kb896.aiusage.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pocket mode left the always-on tab for General and works whenever the back is lit, so
 * the field is pocketMode now — stored under the key it always had, aodPocket, so a
 * switch somebody had already turned on is still on after the update.
 *
 * These are a stored config as it reads when it names its mode: one with no rearMode key is moved to
 * companion mode once (PanelConfigCompanionTest), so the blobs here name it. (They said Replace until that mode was
 * removed.)
 */
class PanelConfigPocketTest {
    private val lenient = Json { ignoreUnknownKeys = true }

    @Test fun `on by default, and a config from before it decodes on`() {
        assertTrue(PanelConfig().pocketMode)
        assertTrue(lenient.decodeFromString(PanelConfig.serializer(), "{}").pocketMode)
    }

    @Test fun `an aodPocket already on stays on`() {
        assertTrue(lenient.decodeFromString(PanelConfig.serializer(), """{"aodPocket":true}""").pocketMode)
        assertTrue(PanelConfig.decode("""{"rearMode":"COMPANION","aodPocket":true}""").pocketMode)
    }

    @Test fun `written under the same key, read back the same`() {
        val raw = PanelConfig.encode(PanelConfig(pocketMode = true))
        assertTrue(raw.contains("\"aodPocket\":true"))
        assertEquals(true, PanelConfig.decode(raw).pocketMode)
    }
}
