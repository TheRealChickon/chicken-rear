package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two dims (RearDim on the back, FrontDim on the front) are black colour layers with no buffer where the system
 * has them, and otherwise black buffers NO LARGER THAN THEIR DISPLAY (the front's was 4096 x 4096, 64 MiB). What
 * they show is the same: black at the same alpha over the same footprint.
 */
class DimLayersTest {

    class Layer

    @Suppress("unused")
    class BothBuilders {
        fun setColorLayer(): BothBuilders = this
        fun setEffectLayer(): BothBuilders = this
    }

    @Suppress("unused")
    class EffectOnly { fun setEffectLayer(): EffectOnly = this }

    class NoBuilder

    @Suppress("unused", "UNUSED_PARAMETER")
    class Tx { fun setColor(layer: Layer, rgb: FloatArray): Tx = this }

    class NoTx

    @Test fun `setColorLayer is preferred, and it fills black by itself`() {
        val call = ShellLayer.colourBuilderCall(BothBuilders::class.java)!!
        assertEquals("setColorLayer", call.first.name)
        assertTrue(call.second)
    }

    @Test fun `where there is only setEffectLayer it needs its colour set`() {
        val call = ShellLayer.colourBuilderCall(EffectOnly::class.java)!!
        assertEquals("setEffectLayer", call.first.name)
        assertFalse(call.second)
        assertFalse("an effect layer without setColor shows nothing", ShellLayer.colourUsable(call.second, canSetColour = false, ShellLayer.BLACK))
        assertTrue(ShellLayer.colourUsable(call.second, canSetColour = true, ShellLayer.BLACK))
    }

    @Test fun `neither, and the dims fall back to a buffer`() {
        assertNull(ShellLayer.colourBuilderCall(NoBuilder::class.java))
    }

    @Test fun `setColor is found by its hidden signature`() {
        assertNotNull(ShellLayer.colourCall(Tx::class.java, Layer::class.java))
        assertNull(ShellLayer.colourCall(NoTx::class.java, Layer::class.java))
    }

    @Test fun `a layer that is black by itself is usable for black only`() {
        assertTrue(ShellLayer.colourUsable(fillsBlack = true, canSetColour = false, rgb = floatArrayOf(0f, 0f, 0f)))
        assertFalse(ShellLayer.colourUsable(fillsBlack = true, canSetColour = false, rgb = floatArrayOf(0.1f, 0f, 0f)))
        assertTrue(ShellLayer.colourUsable(fillsBlack = true, canSetColour = true, rgb = floatArrayOf(0.1f, 0f, 0f)))
        assertEquals(listOf(0f, 0f, 0f), ShellLayer.BLACK.toList())
    }

    @Test fun `the front's square covers it either way up and is no larger than its longest side`() {
        for ((w, h) in listOf(1200 to 2608, 2608 to 1200, 1080 to 2400, 1440 to 3200)) {
            val side = FrontDim.coverSide(w, h)!!
            // A square at least as long as both sides covers the display upright and turned (its sides swap).
            assertTrue("covers $w x $h either way up", side >= w && side >= h)
            assertEquals("no larger than it needs", maxOf(w, h), side)
        }
        // 1200 x 2608: 2608 square is 25.9 MiB as a buffer (it was 64.0), and nothing as a colour layer.
        assertTrue(FrontDim.coverSide(1200, 2608)!!.toLong() * FrontDim.coverSide(1200, 2608)!! * 4 < 4096L * 4096 * 4 / 2)
        assertNull(FrontDim.coverSide(0, 2608))
        assertNull(FrontDim.coverSide(1200, -1))
    }

    private val rear = File("src/main/java/dev/kb896/aiusage/rear")

    @Test fun `no dim makes a buffer larger than its display - the 4096 square is a colour layer's crop at most`() {
        val front = File(rear, "FrontDim.kt").readText()
        // The only buffer the front dim makes is the cover side of the display as read.
        val creates = Regex("""ShellLayer\.create\("front-dim"[^)]*\)""").findAll(front).map { it.value }.toList()
        assertEquals(1, creates.size)
        assertTrue(creates.single(), creates.single().contains("side, side"))
        assertFalse("a buffer is never the fixed square", creates.single().contains("SIDE"))
        assertTrue("its buffer is made from the size read, never without one", front.contains("bufferLayer(side)") && front.contains("side == null) throw e"))
        // The back's dim: its buffer is the size the shell gives it (the rear display's), as before.
        val back = File(rear, "RearDim.kt").readText()
        // Its one buffer: name, stack, Z, then the w x h the shell gives it (layerStack() has its own brackets).
        val backCreates = Regex("""ShellLayer\.create\(\s*"dim",\s*layerStack\(\),\s*ShellLayer\.Z_DIM,\s*(\w+),\s*(\w+),""").findAll(back).toList()
        assertEquals(1, backCreates.size)
        assertEquals(listOf("w", "h"), backCreates.single().groupValues.drop(1))
        assertEquals("no other buffer is made there", 1, Regex("""ShellLayer\.create\(""").findAll(back).count())
        assertTrue(back.contains("val (w, h) = size()"))
    }

    @Test fun `both try the colour layer first, and an untrusted front dim is never shown by the buffer either`() {
        for (f in listOf("FrontDim.kt", "RearDim.kt")) {
            val s = File(rear, f).readText()
            val colour = s.indexOf("ShellLayer.createColour(")
            val buffer = s.indexOf("bufferLayer(")
            assertTrue("$f: colour first", colour in 0 until buffer)
            assertTrue("$f: Untrusted is not fallen back from", s.contains("e is ShellLayer.Untrusted"))
        }
    }

    @Test fun `the dims' alphas are as they were`() {
        assertEquals(0.75f, RearDim.MAX_ALPHA, 0f)
        assertEquals(0.88f, FrontDim.ALPHA, 0f)
    }
}
