package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellLayerTest {

    class Layer

    @Suppress("UNUSED_PARAMETER")
    class Both {
        fun setTrustedOverlay(layer: Layer, value: Int): Both = this
        fun setTrustedOverlay(layer: Layer, value: Boolean): Both = this
    }

    @Suppress("UNUSED_PARAMETER")
    class OnlyBoolean {
        fun setTrustedOverlay(layer: Layer, value: Boolean): OnlyBoolean = this
    }

    class Neither

    @Test fun `the int overload is preferred, with ENABLED`() {
        val call = ShellLayer.trustedOverlayCall(Both::class.java, Layer::class.java)!!
        assertEquals(Int::class.javaPrimitiveType, call.first.parameterTypes[1])
        assertEquals(2, call.second)
    }

    @Test fun `older firmware gets the boolean overload, with true`() {
        val call = ShellLayer.trustedOverlayCall(OnlyBoolean::class.java, Layer::class.java)!!
        assertEquals(Boolean::class.javaPrimitiveType, call.first.parameterTypes[1])
        assertEquals(true, call.second)
    }

    @Test fun `neither, and there is nothing to call`() {
        assertNull(ShellLayer.trustedOverlayCall(Neither::class.java, Layer::class.java))
    }

    @Test fun `the display's shape is over everything, the frame under it, the sheet under that, the blocker under the sheet`() {
        assertEquals(Int.MAX_VALUE, ShellLayer.Z_CORNERS)
        assertEquals(Int.MAX_VALUE - 1, ShellLayer.Z_FRAME)
        assertTrue(ShellLayer.Z_FRAME > ShellLayer.Z_SHEET)
        assertTrue(ShellLayer.Z_SHEET > ShellLayer.Z_BLOCKER)
        // Nothing of ours shares the top with it.
        val zs = ShellLayer::class.java.declaredFields.filter { it.name.startsWith("Z_") && it.name != "Z_CORNERS" }.map { it.isAccessible = true; it.getInt(null) }
        assertTrue(zs.isNotEmpty() && zs.all { it < ShellLayer.Z_CORNERS })
    }

    @Test fun `the picker directly over the face, under every page, the rest in its old order`() {
        assertEquals(ShellLayer.Z_SCENE + 1, ShellLayer.Z_PICKER)
        assertTrue(ShellLayer.Z_CHARGE_PAGE > ShellLayer.Z_PICKER)
        assertTrue(ShellLayer.Z_MUSIC > ShellLayer.Z_CHARGE_PAGE)
        assertTrue(ShellLayer.Z_LIVE > ShellLayer.Z_MUSIC)
        assertTrue(ShellLayer.Z_USAGE > ShellLayer.Z_LIVE)
        assertTrue(ShellLayer.Z_WAKE > ShellLayer.Z_USAGE)
        assertTrue(ShellLayer.Z_SCENE > ShellLayer.Z_BLOCKER)
        assertEquals(ShellLayer.Z_BLOCKER, ShellLayer.Z_BASE)
        // Every layer of ours has a place of its own but the pairs that share one on purpose.
        val all = listOf(ShellLayer.Z_FRAME, ShellLayer.Z_SHEET, ShellLayer.Z_PANES, ShellLayer.Z_BANNER, ShellLayer.Z_CHARGE, ShellLayer.Z_WAKE,
            ShellLayer.Z_USAGE, ShellLayer.Z_LIVE, ShellLayer.Z_MUSIC, ShellLayer.Z_CHARGE_PAGE, ShellLayer.Z_PICKER, ShellLayer.Z_SCENE,
            ShellLayer.Z_BLOCKER, ShellLayer.Z_COVER, ShellLayer.Z_CHEVRON, ShellLayer.Z_STRIP, ShellLayer.Z_APP)
        assertEquals(all.sortedDescending(), all)
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun `the banner over the charge over the wake, all over the blocker`() {
        assertTrue(ShellLayer.Z_BANNER > ShellLayer.Z_CHARGE)
        assertTrue(ShellLayer.Z_CHARGE > ShellLayer.Z_WAKE)
        assertTrue(ShellLayer.Z_WAKE > ShellLayer.Z_BLOCKER)
        assertTrue(ShellLayer.Z_BLOCKER > ShellLayer.Z_COVER)
        assertTrue(ShellLayer.Z_COVER > ShellLayer.Z_CHEVRON)
        assertTrue(ShellLayer.Z_CHEVRON > ShellLayer.Z_STRIP)
        assertTrue(ShellLayer.Z_STRIP > ShellLayer.Z_APP)
    }
}
