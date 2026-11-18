package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Xiaomi template not found is not looked for again every frame: for a minute (XiaomiArt.missed). */
class XiaomiArtMissTest {
    @Test fun `a miss stands for a minute, then it is looked for again`() {
        assertFalse("no miss yet", XiaomiArt.missed(0L, 5_000L))
        assertTrue("a frame later", XiaomiArt.missed(5_000L, 5_016L))
        assertTrue("just under a minute", XiaomiArt.missed(5_000L, 5_000L + XiaomiArt.MISS_MS - 1L))
        assertFalse("a minute on", XiaomiArt.missed(5_000L, 5_000L + XiaomiArt.MISS_MS))
        assertFalse("a clock that went back looks again", XiaomiArt.missed(5_000L, 4_000L))
    }
}
