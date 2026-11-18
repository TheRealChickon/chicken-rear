package dev.kb896.aiusage.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Camera mode is on by default. The usage page's Camera button is off by default (it puts a
 * viewfinder on the back), stored under a new key so the old written-out "on" does not carry over.
 *
 * These are a stored config as it reads when it names its mode: one with no rearMode key is moved to
 * companion mode once (PanelConfigCompanionTest), so the blobs here name it. (They said Replace until that mode was
 * removed.)
 */
class PanelConfigCameraTest {
    @Test fun `our camera preference defaults on and an explicit opt out survives storage`() {
        assertTrue(PanelConfig().cameraOwnRear)
        assertTrue(PanelConfig.decode("{}").cameraOwnRear)
        assertFalse(PanelConfig.decode(PanelConfig.encode(PanelConfig(cameraOwnRear = false))).cameraOwnRear)
    }

    @Test fun `camera mode is on by default, also in a config written before it`() {
        assertTrue(PanelConfig().cameraLight)
        assertTrue(PanelConfig.decode("""{"rearMode":"COMPANION"}""").cameraLight)
        assertFalse(PanelConfig.decode(PanelConfig.encode(PanelConfig(cameraLight = false))).cameraLight)
    }

    @Test fun `the usage page's camera button starts off`() {
        assertFalse(PanelConfig().showCamera)
        assertFalse(PanelConfig.decode("{}").showCamera)
    }

    @Test fun `the old default-on camera button value does not carry over`() {
        assertFalse(PanelConfig.decode("""{"showCamera":true}""").showCamera)
    }

    @Test fun `switched on under the new key, read back on`() {
        val raw = PanelConfig.encode(PanelConfig(showCamera = true))
        assertTrue(raw.contains("\"showCameraButton\":true"))
        assertTrue(PanelConfig.decode(raw).showCamera)
    }
}
