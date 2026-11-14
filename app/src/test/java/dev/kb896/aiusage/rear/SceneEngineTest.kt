package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneEngineTest {
    @Test fun `a clip at the rear's shape has no matte, one twice as tall has`() {
        assertFalse(SceneEngine.hasMatte(976, 596))
        assertTrue(SceneEngine.hasMatte(976, 1192))
        // Any size at those shapes, and a 16:9 file is not mistaken for one with a matte.
        assertFalse(SceneEngine.hasMatte(1920, 1080))
        assertTrue(SceneEngine.hasMatte(488, 596))
    }

    @Test fun `near-black is switched off exactly and everything visible is left whole`() {
        for (level in 0..7) assertEquals("level $level", 0f, SceneEngine.crushGain(level / 255f), 0f)
        for (level in 15..255 step 16) assertEquals("level $level", 1f, SceneEngine.crushGain(level / 255f), 0f)
        // What is left of a level between the two rounds to nothing an 8-bit panel can show as grey lift.
        var last = 0f
        for (level in 7..15) { val g = SceneEngine.crushGain(level / 255f); assertTrue(g >= last); last = g }
    }
}
