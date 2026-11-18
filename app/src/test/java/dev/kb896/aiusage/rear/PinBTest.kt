package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PinBTest {
    /** frontAt('B', t) of the draft: t, blur, dim, card x, y, w, turn, opacity, glow, face, face blur, rim, content width, content corner, island w, h, opacity, its content. */
    private val draft = arrayOf(
            floatArrayOf(0f, 0.0244f, 0f, 600f, 72f, 57f, -180f, 0f, 0f, 0f, 50f, 0.01f, 1103.9015f, 24.0049f, 40f, 40f, 0f, 0f),
            floatArrayOf(50f, 29.725f, 0.0357f, 600f, 72f, 57f, -180f, 0f, 0f, 0f, 50f, 0.01f, 983.9109f, 29.945f, 40f, 40f, 0f, 0f),
            floatArrayOf(100f, 64.4495f, 0.0773f, 600f, 72f, 57f, -180f, 0f, 0f, 0f, 50f, 0.01f, 843.624f, 36.8899f, 40f, 40f, 0f, 0f),
            floatArrayOf(200f, 94.1329f, 0.113f, 600f, 72f, 57f, -180f, 0.0001f, 0f, 0f, 50f, 0.01f, 724.1868f, 42.83f, 40f, 40f, 0f, 0f),
            floatArrayOf(300f, 99.3844f, 0.1193f, 600f, 171.5929f, 57.7182f, -131.22f, 0.4853f, 0f, 0f, 50f, 0.01f, 723.5693f, 56.033f, 40f, 40f, 0f, 0f),
            floatArrayOf(400f, 99.975f, 0.12f, 600f, 248.6604f, 68.4909f, -92.16f, 0.8462f, 0f, 0f, 50f, 0.01f, 719.2463f, 65.1757f, 40f, 40f, 0f, 0f),
            floatArrayOf(600f, 100.0018f, 0.12f, 600f, 346.7708f, 240.8549f, -38.88f, 0.9938f, 0f, 0f, 50f, 0.01f, 684.6624f, 68.8498f, 40f, 40f, 0f, 0f),
            floatArrayOf(900f, 100f, 0.12f, 600f, 397.193f, 634.5091f, -4.86f, 1.0001f, 0f, 0f, 50f, 0.01f, 607.514f, 69.0013f, 40f, 40f, 0f, 0f),
            floatArrayOf(1050f, 100f, 0.12f, 600f, 399.987f, 645.9551f, -0.6075f, 1f, 0.0184f, 0.0363f, 48.184f, 0.01f, 600.3162f, 69.0002f, 40f, 40f, 0f, 0f),
            floatArrayOf(1100f, 100f, 0.12f, 600f, 400f, 646f, -0.18f, 1f, 0.157f, 0.2742f, 36.2919f, 0.01f, 600.0167f, 69.0001f, 40f, 40f, 0f, 0f),
            floatArrayOf(1200f, 100f, 0.12f, 600f, 400f, 595.1461f, 0f, 1f, 0.5229f, 0.7336f, 13.3203f, 0.01f, 600f, 69f, 40f, 40f, 0f, 0f),
            floatArrayOf(1300f, 100f, 0.12f, 600f, 400f, 570f, 0.7692f, 1f, 0.7831f, 0.9343f, 3.2871f, 0.01f, 600f, 69f, 40f, 40f, 0f, 0f),
            floatArrayOf(1500f, 100f, 0.12f, 600f, 400f, 634.8447f, 2.3f, 1f, 0.9749f, 1.001f, -0.051f, 0.01f, 600f, 69f, 40f, 40f, 0f, 0f),
            floatArrayOf(1700f, 25.6833f, 0.0308f, 600f, 380.3642f, 587.8922f, 37.6059f, 1f, 0.9783f, 1.0005f, -0.0243f, 0.0124f, 600f, 69f, 40f, 40f, 0f, 0f),
            floatArrayOf(1750f, 8.6867f, 0.0104f, 600f, 352.0611f, 520.2145f, 55.2616f, 1f, 0.9801f, 1.0003f, -0.0131f, 0.016f, 600f, 69f, 40f, 40f, 0f, 0f),
            floatArrayOf(1840f, 0.8279f, 0.001f, 600f, 236f, 264.5539f, 91.15f, 1f, 0.9839f, 1.0001f, -0.0034f, 0.0292f, 600f, 69f, 40f, 40f, 0f, 0f),
            floatArrayOf(1900f, 0.1142f, 0.0001f, 600f, 147.4328f, 139.1203f, 115.465f, 0.8264f, 0.9867f, 1f, -0.001f, 0.0357f, 600f, 69f, 40f, 40f, 0f, 0f),
            floatArrayOf(2000f, -0.006f, 0f, 600f, 84.883f, 65.3813f, 151.0079f, 0.134f, 0.9914f, 1f, 0f, 0.0396f, 600f, 69f, 40f, 40f, 0f, 0f),
            floatArrayOf(2060f, -0.0035f, 0f, 600f, 74.5448f, 57.7046f, 166.6918f, 0f, 0.994f, 1f, 0.0001f, 0.04f, 600f, 69f, 40f, 40f, 0f, 0f),
            floatArrayOf(2100f, -0.0017f, 0f, 600f, 72.5027f, 57.0238f, 174.0002f, 0f, 0.9955f, 1f, 0.0001f, 0.04f, 600f, 69f, 40f, 40f, 0f, 0f),
            floatArrayOf(2180f, -0.0003f, 0f, 600f, 72f, 57f, 180f, 0f, 0.998f, 1f, 0.0001f, 0.04f, 600f, 69f, 143.917f, 66.5094f, 1f, 0f),
            floatArrayOf(2300f, 0f, 0f, 600f, 72f, 57f, 180f, 0f, 0.9999f, 1f, 0f, 0.04f, 600f, 69f, 228.1121f, 87.9878f, 1f, 0.767f),
            floatArrayOf(2400f, 0f, 0f, 600f, 72f, 57f, 180f, 0f, 1f, 1f, 0f, 0.04f, 600f, 69f, 236.0759f, 90.0194f, 1f, 1f),
            floatArrayOf(3000f, 0f, 0f, 600f, 72f, 57f, 180f, 0f, 1f, 1f, 0f, 0.04f, 600f, 69f, 236f, 90f, 1f, 1f),
            floatArrayOf(4350f, 0f, 0f, 600f, 72f, 57f, 180f, 0f, 1f, 1f, 0f, 0.04f, 600f, 69f, 146.9091f, 67.2727f, 0.989f, 0.1667f),
            floatArrayOf(4420f, 0f, 0f, 600f, 72f, 57f, 180f, 0f, 1f, 1f, 0f, 0.04f, 600f, 69f, 40f, 40f, 0f, 0f),
    )

    private fun near(what: String, want: Float, got: Float) = assertTrue("$what: the draft has $want, built $got", abs(want - got) <= 0.01f * maxOf(1f, abs(want)) + 0.003f)

    @Test fun `the front is the draft's, value for value`() {
        val tl = PinB.Timeline()
        for (row in draft) {
            val t = row[0]
            val f = tl.at(t)
            near("blur at $t", row[1].coerceIn(0f, 100f), f.blur); near("dim at $t", row[2].coerceIn(0f, 1f), f.dim)
            near("card x at $t", row[3], f.cardX); near("card y at $t", row[4], f.cardY); near("card w at $t", row[5], f.cardW)
            near("turn at $t", row[6], f.rotY); near("card at $t", row[7].coerceIn(0f, 1f), f.cardOp)
            near("glow at $t", row[8].coerceIn(0f, 1.2f), f.glow); near("face at $t", row[9].coerceIn(0f, 1.2f), f.ftOp)
            near("face blur at $t", row[10].coerceIn(0f, 60f), f.ftSigma); near("rim at $t", row[11], f.edgeTh)
            near("content width at $t", row[12], f.sprW); near("content corner at $t", row[13], f.sprCorner)
            near("island w at $t", row[14], f.islandW); near("island h at $t", row[15], f.islandH)
            near("island at $t", row[16], f.islandOp); near("island content at $t", row[17], f.islandContent)
            assertEquals(f.cardW / PinB.ASPECT, f.cardH, 0.001f)
        }
    }

    @Test fun `Xiaomi's measured moments`() {
        val tl = PinB.Timeline()
        // The page frosts: radius 30 at 50 ms, 64 at 100, 94 at 200 (Xiaomi's recording).
        assertEquals(30f, tl.at(50f).blur, 1.5f); assertEquals(64f, tl.at(100f).blur, 1.5f); assertEquals(94f, tl.at(200f).blur, 1.5f)
        // Edge-on at about 400 ms on the way in, about 1840 on the way out.
        assertEquals(-90f, tl.at(406f).rotY, 3f); assertEquals(90f, tl.at(1838f).rotY, 3f)
        // The card at its place and size by 1100, the catch to 60% of 950 and back.
        assertEquals(PinB.CARD_W, tl.at(1100f).cardW, 0.5f)
        assertTrue("the catch: ${tl.at(1290f).cardW}", tl.at(1290f).cardW < PinB.CARD_W * 0.6f / 0.68f + 4f)
        assertEquals("and back", PinB.CARD_W, tl.at(1500f).cardW, 14f)
        // The page is sharp again by about 1750 and the card gone by 2060, all before the back begins.
        assertTrue(tl.at(1760f).blur < 8f)
        assertEquals(0f, tl.at(2060f).cardOp, 0.001f)
        assertEquals("no overlap: nothing of the card on the front as the back begins", 0f, tl.at(PinB.REAR_AT.toFloat()).cardOp, 0.0001f)
    }

    @Test fun `the content comes in as Xiaomi's words do and lifts off from where they are`() {
        val tl = PinB.Timeline()
        assertEquals("not there before the pin", 0f, tl.at(0f).sprOp, 0.02f)
        assertTrue("in within the first state (spring 1, 0.25): ${tl.at(200f).sprOp}", tl.at(200f).sprOp > 0.95f)
        assertEquals("from the middle of the screen", 1304f, tl.at(0f).sprY, 0.5f)
        assertEquals("gone into the card by 1230", 0f, tl.at(1240f).sprOp, 0.005f)
        val from = PinB.Timeline(PinB.Place(fromX = 400f, fromY = 2000f, fromW = 500f))
        assertEquals(400f, from.at(0f).sprX, 0.5f); assertEquals(2000f, from.at(0f).sprY, 0.5f); assertEquals(500f, from.at(0f).sprW, 0.5f)
        assertEquals("and in the card all the same", PinB.CARD_X, from.at(1150f).sprX, 1f)
        assertEquals(PinB.CARD_Y, from.at(1150f).sprY, 1f)
    }

    @Test fun `the hole the screen says is where the card comes from and goes, and the island's middle`() {
        val tl = PinB.Timeline(PinB.Place(holeX = 610f, holeY = 60f, holeR = 22f))
        assertEquals(610f, tl.at(0f).cardX, 0.01f); assertEquals(60f, tl.at(0f).cardY, 0.01f)
        assertEquals(610f, tl.at(2200f).cardX, 0.5f); assertEquals(60f, tl.at(2200f).cardY, 0.5f)
        assertEquals("the island starts at the hole's size", 46f, tl.at(PinB.ISLAND_AT.toFloat()).islandW, 0.5f)
        assertEquals(236f, tl.at(3000f).islandW, 0.5f); assertEquals(90f, tl.at(3000f).islandH, 0.5f)
        // A hole larger than the island's height (an emulator's): the island is still round it.
        val big = PinB.Timeline(PinB.Place(holeR = 60f)).at(3000f)
        assertTrue(big.islandH >= 122f && big.islandW >= big.islandH + 140f)
    }

    @Test fun `Xiaomi's order - the back is told, the sound with the island, the back after the front`() {
        assertTrue(PinB.TELL_AT < PinB.REAR_AT)
        assertTrue("told after the card has begun to fade", PinB.TELL_AT > 1_880L)
        assertEquals("the sound with the island", PinB.ISLAND_AT, PinB.CHIME_AT)
        assertTrue("asked for after the card has gone into the hole", PinB.CHIME_AT - dev.kb896.aiusage.service.PinChime.LEAD_MS >= 2_000L)
        assertTrue(PinB.CARD_GONE_MS >= 2_060L)
        assertEquals(0f, PinB.Timeline().at(PinB.FRONT_MS.toFloat()).islandOp, 0.001f)
    }

    @Test fun `nothing jumps between two frames`() {
        val tl = PinB.Timeline()
        var a = tl.at(0f)
        for (ms in 8..PinB.FRONT_MS.toInt() + 100 step 8) {
            val b = tl.at(ms.toFloat())
            assertTrue("blur at $ms", abs(b.blur - a.blur) < 12f)
            assertTrue("card place at $ms", abs(b.cardX - a.cardX) < 20f && abs(b.cardY - a.cardY) < 20f)
            assertTrue("card size at $ms", abs(b.cardW - a.cardW) < 30f)
            assertTrue("turn at $ms: ${a.rotY} -> ${b.rotY}", abs(b.rotY - a.rotY) < 12f)
            for ((n, x, y) in listOf(Triple("card", a.cardOp, b.cardOp), Triple("glow", a.glow, b.glow), Triple("face", a.ftOp, b.ftOp), Triple("content", a.sprOp, b.sprOp)))
                assertTrue("$n at $ms: $x -> $y", abs(y - x) < 0.2f)
            assertTrue("island at $ms", abs(b.islandW - a.islandW) < 40f)
            a = b
        }
    }

    @Test fun `the back is the draft's, and opaque and in focus before the notes page goes under it`() {
        val rows = listOf(
            floatArrayOf(0f, 0f, 0f, 100f, 0f), floatArrayOf(100f, 45.823f, 0f, 100f, 6f), floatArrayOf(200f, 81.458f, 0.5781f, 100f, 12f),
            floatArrayOf(330f, 100f, 0.9232f, 65.61f, 19f), floatArrayOf(400f, 100f, 0.9844f, 19.753f, 24f), floatArrayOf(600f, 100f, 1f, 0f, 36f),
            floatArrayOf(999f, 100f, 1f, 0f, 59f), floatArrayOf(1000f, 100f, 1f, 0f, -1f),
        )
        for (r in rows) {
            val b = PinB.back(r[0])
            near("old face blur at ${r[0]}", r[1], b.pageBlur); near("content at ${r[0]}", r[2], b.photoA); near("its blur at ${r[0]}", r[3], b.photoBlur)
            assertEquals("pin_show.pag's frame at ${r[0]}", r[4].toInt(), b.veil)
        }
        val land = PinB.back(ShellBeam.B_LAND_AT.toFloat())
        assertEquals(1f, land.photoA, 0.0001f); assertEquals(0f, land.photoBlur, 0.0001f)
        assertTrue(ShellBeam.B_LAND_AT < PinB.BACK_MS)
    }

    @Test fun `Xiaomi's engine - a spring keeps its speed, a chain plays in turn, nothing follows a spring`() {
        val rest = mapOf("a" to 0f, "b" to 0f, "c" to 0f)
        val s1 = PinB.State(0L, PinB.How.Spring(1f, 0.3f), mapOf("a" to listOf(PinB.Move(100f)),
            "b" to listOf(PinB.Move(10f, PinB.How.Tween(PinB.Ease::linear, 100)), PinB.Move(20f, PinB.How.Tween(PinB.Ease::linear, 100, 50))),
            "c" to listOf(PinB.Move(5f), PinB.Move(50f, PinB.How.Tween(PinB.Ease::linear, 10)))))
        val s2 = PinB.State(100L, PinB.How.Spring(1f, 0.3f), mapOf("a" to listOf(PinB.Move(0f))))
        val t = PinB.simulate(rest, listOf(s1, s2), 1000)
        val a = t.getValue("a")
        assertTrue("still rising just after its new target: it keeps its speed (${a[100]} -> ${a[110]})", a[110] > a[100])
        assertTrue(a[1000] < 1f)
        val b = t.getValue("b")
        assertEquals(10f, b[100], 0.2f); assertEquals("waits its 50 ms", 10f, b[149], 0.2f); assertEquals(20f, b[251], 0.3f)
        assertEquals("after a spring nothing follows", 5f, t.getValue("c")[1000], 0.05f)
    }
}
