package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The posture's switches (RearPosture): "Dim when face down" (stored as tableDim, so the old
 * "Stay bright" switch's written-out off does not carry over), "Off when lying on its back" and
 * "Stay on in the hand" — all on by default. The table's minutes limit is gone.
 *
 * These are a stored config as it reads when it names its mode: one with no rearMode key is moved to
 * companion mode once (PanelConfigCompanionTest), so the blobs here name it. (They said Replace until that mode was
 * removed.)
 */
class PanelConfigTableTest {
    private val STAMPED = """{"rearMode":"COMPANION"}"""

    @Test fun `all on by default, charging only off, and an empty config decodes the same`() {
        for (c in listOf(PanelConfig(), PanelConfig.decode(STAMPED))) {
            assertTrue(c.tableMode)
            assertFalse(c.tableChargingOnly)
            assertTrue(c.offOnBack)
            assertTrue(c.stayOnInHand)
            assertTrue(c.cameraLight)
        }
    }

    @Test fun `the old Stay bright switch's stored values are not the new switch's`() {
        val old = PanelConfig.decode("""{"rearMode":"COMPANION","tableMode":false,"tableMinutes":10}""")
        assertTrue(old.tableMode)
    }

    @Test fun `a stored minutes limit from before is ignored, and is no longer written`() {
        val old = PanelConfig.decode("""{"rearMode":"COMPANION","tableDim":true,"tableDimMinutes":5,"tableChargingOnly":true}""")
        assertTrue(old.tableMode)
        assertTrue(old.tableChargingOnly)
        assertFalse(PanelConfig.encode(old).contains("tableDimMinutes"))
    }

    @Test fun `written under their keys, read back the same`() {
        val raw = PanelConfig.encode(PanelConfig(tableMode = false, offOnBack = false, stayOnInHand = false))
        assertTrue(raw.contains("\"tableDim\":false"))
        assertTrue(raw.contains("\"offOnBack\":false"))
        assertTrue(raw.contains("\"stayOnInHand\":false"))
        val back = PanelConfig.decode(raw)
        assertFalse(back.tableMode)
        assertFalse(back.offOnBack)
        assertFalse(back.stayOnInHand)
        assertEquals(PanelConfig(tableMode = false, offOnBack = false, stayOnInHand = false).tableChargingOnly, back.tableChargingOnly)
    }
}
