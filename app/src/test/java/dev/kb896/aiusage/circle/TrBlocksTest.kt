package dev.kb896.aiusage.circle

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** The ground colour of a strip of text, counted in a plain array (was a boxed map that froze the translate tap). */
class TrBlocksTest {
    /** The rule as it was: the commonest colour to 4 bits a channel, then the middle of its bin. */
    private fun old(px: IntArray): Int {
        val n = HashMap<Int, Int>()
        for (c in px) { val k = (c shr 4 and 0xF0F0F); n[k] = (n[k] ?: 0) + 1 }
        val k = n.maxByOrNull { it.value }?.key ?: 0
        return (k shl 4) or 0x080808
    }

    @Test fun sameGroundAsBefore() {
        val r = Random(7)
        repeat(200) {
            val ground = 0xFF000000.toInt() or r.nextInt(0x1000000)
            val ink = 0xFF000000.toInt() or r.nextInt(0x1000000)
            val px = IntArray(500 + r.nextInt(3000)) { i -> if (r.nextFloat() < 0.35f) ink + r.nextInt(3) else if (i % 7 == 0) 0xFF000000.toInt() or r.nextInt(0x1000000) else ground }
            assertEquals(old(px), TrBlocks.commonest(px))
        }
    }

    @Test fun onlyTheFirstCountPixels() {
        val px = IntArray(100) { if (it < 30) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        assertEquals(0xF8F8F8, TrBlocks.commonest(px, 30) and 0xFFFFFF)
        assertEquals(0x080808, TrBlocks.commonest(px) and 0xFFFFFF)
    }

    @Test fun nothingCounted() { assertEquals(0x080808, TrBlocks.commonest(IntArray(4), 0)) }
}
