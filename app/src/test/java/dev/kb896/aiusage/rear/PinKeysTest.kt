package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * The plumbing of the one-to-one pin: Xiaomi's 22 values per frame, its models read as they are (a .glb), and its
 * files found in its package as a zip. Nothing of Xiaomi's is in these tests: the model and the package are made here.
 */
class PinKeysTest {
    private fun keys(ms: Long, w: Float = 1200f, h: Float = 2608f) = PinKeys.at(ms, w, h, 800f, 300f)

    @Test fun `the 22 values at Xiaomi's own keyframes, on its own 1200 x 2608 screen`() {
        assertEquals(22, PinKeys.COUNT); assertEquals(22, PinKeys.NAMES.size)
        val start = keys(0)
        assertEquals(0f, start[PinKeys.BLUR], 0.001f); assertEquals(0f, start[PinKeys.SHEET_ALPHA], 0.001f)
        assertEquals(1.2f, start[PinKeys.SHEET_SCALE], 0.001f); assertEquals(600f, start[PinKeys.SHEET_X], 0.01f); assertEquals(1204f, start[PinKeys.SHEET_Y], 0.5f)
        assertEquals(0.1f, start[PinKeys.CORNER], 0.001f); assertEquals(0f, start[PinKeys.CARD_ALPHA], 0.001f)
        assertEquals(950f, start[PinKeys.CARD_W], 0.01f); assertEquals(600f, start[PinKeys.CARD_H], 0.01f); assertEquals(0.06f, start[PinKeys.CARD_SCALE], 0.001f)
        assertEquals(90f, start[PinKeys.CARD_ROT_X], 0.001f); assertEquals(-180f, start[PinKeys.CARD_ROT_Y], 0.001f); assertEquals(0f, start[PinKeys.CARD_ROT_Z], 0.001f)
        assertEquals(600f, start[PinKeys.CARD_X], 0.01f); assertEquals(72f, start[PinKeys.CARD_Y], 0.01f)
        assertEquals(0.01f, start[PinKeys.EDGE], 0.0001f); assertEquals(0f, start[PinKeys.FACE_ALPHA], 0.001f); assertEquals(50f, start[PinKeys.FACE_BLUR], 0.01f); assertEquals(0f, start[PinKeys.GLOW], 0.001f)
        // The sheet's own shape first: 800 x 300 fitted into 500.
        assertEquals(500f, start[PinKeys.SHEET_W], 0.01f); assertEquals(187.5f, start[PinKeys.SHEET_H], 0.01f)
        // Lifted: blurred behind, the sheet there, peeled, round-cornered, lower.
        val lifted = keys(PinBeam.CARD_AT + 250)
        assertTrue(lifted[PinKeys.BLUR] > 90f); assertTrue(lifted[PinKeys.SHEET_ALPHA] > 0.95f)
        // It sinks 200 px as it lifts (300 ms), and is on its way up to the card from 200 ms: nearly all the way down at 200.
        assertEquals(1304f + 100f - 200f * (1f / 27f), keys(PinBeam.CARD_AT)[PinKeys.SHEET_Y], 0.5f)
        // The card where the back screen sits, the sheet in it, its face come, its edge lit.
        val there = keys(PinBeam.LEAVE_AT)
        assertEquals(610f, there[PinKeys.CARD_X], 0.5f); assertEquals(382f, there[PinKeys.CARD_Y], 0.5f); assertEquals(0.68f, there[PinKeys.CARD_SCALE], 0.02f)
        assertEquals(382f, there[PinKeys.SHEET_Y], 1f); assertEquals(730f, there[PinKeys.SHEET_X], 1f)
        assertEquals(950f, there[PinKeys.SHEET_W], 12f); assertEquals(600f, there[PinKeys.SHEET_H], 8f)
        assertEquals(0f, there[PinKeys.SHEET_ALPHA], 0.001f); assertTrue(there[PinKeys.FACE_ALPHA] > 0.9f); assertTrue(there[PinKeys.FACE_BLUR] < 5f); assertTrue(there[PinKeys.GLOW] > 0.8f)
        // Gone into the top edge, turned right round, its edge thick.
        val gone = keys(PinBeam.LEAVE_AT + 680)
        assertEquals(180f, gone[PinKeys.CARD_ROT_Y], 0.01f); assertEquals(72f, gone[PinKeys.CARD_Y], 0.01f); assertEquals(0.06f, gone[PinKeys.CARD_SCALE], 0.001f)
        assertEquals(0f, gone[PinKeys.CARD_ALPHA], 0.001f); assertEquals(0.04f, gone[PinKeys.EDGE], 0.0001f)
    }

    @Test fun `it is the same motion as the plain drawing's and the hand-off's - one truth for the card`() {
        for (ms in 0L..PinBeam.FRONT_MS step 10) {
            val k = keys(ms); val f = PinBeam.front(ms)
            assertEquals("turn at $ms", f.cardTurn, k[PinKeys.CARD_ROT_Y], 0.01f)
            assertEquals("scale at $ms", f.cardScale, k[PinKeys.CARD_SCALE], 0.001f)
            assertEquals("light at $ms", f.cardAlpha, k[PinKeys.CARD_ALPHA], 0.001f)
            assertEquals("across at $ms", f.cardX * 1200f, k[PinKeys.CARD_X], 0.05f)
            assertEquals("down at $ms", f.cardY * 2608f, k[PinKeys.CARD_Y], 0.05f)
            assertEquals("glow at $ms", f.glow, k[PinKeys.GLOW], 0.001f)
            assertEquals("face at $ms", f.face, k[PinKeys.FACE_ALPHA], 0.001f)
            assertEquals("blur at $ms", f.blur * 100f, k[PinKeys.BLUR], 0.05f)
        }
    }

    @Test fun `on another screen the places go with its width, and the lift is about its own middle`() {
        val k = keys(PinBeam.LEAVE_AT, 1080f, 2400f)
        assertEquals(610f * 0.9f, k[PinKeys.CARD_X], 0.5f); assertEquals(382f * 0.9f, k[PinKeys.CARD_Y], 0.5f); assertEquals(950f * 0.9f, k[PinKeys.CARD_W], 0.01f)
        assertEquals(382f * 0.9f, k[PinKeys.SHEET_Y], 1f)
        assertEquals(1200f - 90f, keys(0, 1080f, 2400f)[PinKeys.SHEET_Y], 0.5f)
        assertEquals(1200f + 90f - 180f * (1f / 27f), keys(PinBeam.CARD_AT, 1080f, 2400f)[PinKeys.SHEET_Y], 0.5f)
        var last = keys(0, 1080f, 2400f)
        for (ms in 8L..PinBeam.FRONT_MS step 8) { val n = keys(ms, 1080f, 2400f); assertTrue("the sheet does not jump at $ms: ${last[PinKeys.SHEET_Y]} -> ${n[PinKeys.SHEET_Y]}", abs(n[PinKeys.SHEET_Y] - last[PinKeys.SHEET_Y]) < 40f); last = n }
    }

    // ---- a model as Xiaomi's are (.glb) ----

    private fun glb(positions: FloatArray, uvs: FloatArray, colors: ByteArray, indices: ShortArray): ByteArray {
        val bin = ByteBuffer.allocate(positions.size * 4 + uvs.size * 4 + colors.size + indices.size * 2 + 8).order(ByteOrder.LITTLE_ENDIAN)
        val pAt = bin.position(); positions.forEach { bin.putFloat(it) }
        val uAt = bin.position(); uvs.forEach { bin.putFloat(it) }
        val cAt = bin.position(); bin.put(colors)
        while (bin.position() % 4 != 0) bin.put(0)
        val iAt = bin.position(); indices.forEach { bin.putShort(it) }
        while (bin.position() % 4 != 0) bin.put(0)
        val binLen = bin.position()
        val n = positions.size / 3
        var json = """{"asset":{"version":"2.0"},"meshes":[{"name":"made-up","primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1,"COLOR_0":2},"indices":3}]}],""" +
            """"accessors":[{"bufferView":0,"componentType":5126,"count":$n,"type":"VEC3"},{"bufferView":1,"componentType":5126,"count":$n,"type":"VEC2"},""" +
            """{"bufferView":2,"componentType":5121,"normalized":true,"count":$n,"type":"VEC4"},{"bufferView":3,"componentType":5123,"count":${indices.size},"type":"SCALAR"}],""" +
            """"bufferViews":[{"buffer":0,"byteOffset":$pAt,"byteLength":${positions.size * 4}},{"buffer":0,"byteOffset":$uAt,"byteLength":${uvs.size * 4}},""" +
            """{"buffer":0,"byteOffset":$cAt,"byteLength":${colors.size}},{"buffer":0,"byteOffset":$iAt,"byteLength":${indices.size * 2}}],"buffers":[{"byteLength":$binLen}]}"""
        while (json.length % 4 != 0) json += " "
        val out = ByteBuffer.allocate(12 + 8 + json.length + 8 + binLen).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(out.capacity())
        out.putInt(json.length).putInt(0x4E4F534A).put(json.toByteArray())
        out.putInt(binLen).putInt(0x004E4942).put(bin.array(), 0, binLen)
        return out.array()
    }

    @Test fun `a glb's first mesh - positions, texture places, colours, triangles - and its size`() {
        val bytes = glb(
            floatArrayOf(-0.95f, 0f, -0.6f, 0.95f, 0f, -0.6f, 0.95f, 0.02f, 0.6f, -0.95f, 0.02f, 0.6f),
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
            byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, -1, -1, -1, 127),
            shortArrayOf(0, 1, 2, 0, 2, 3),
        )
        val m = Glb.mesh(bytes)
        assertNotNull(m); m!!
        assertEquals("made-up", m.name); assertEquals(4, m.vertices)
        assertEquals(listOf(0, 1, 2, 0, 2, 3), m.indices.toList())
        assertEquals(0.95f, m.positions[3], 0.0001f); assertEquals(0.6f, m.positions[8], 0.0001f)
        assertEquals(listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), m.uvs.toList())
        assertEquals(1f, m.colors[0], 0.0001f); assertEquals(0f, m.colors[1], 0.0001f); assertEquals(127 / 255f, m.colors[15], 0.0001f)
        assertEquals(0, m.normals.size)
        val (lo, hi) = m.bounds()
        assertEquals(listOf(-0.95f, 0f, -0.6f), lo.toList()); assertEquals(listOf(0.95f, 0.02f, 0.6f), hi.toList())
    }

    @Test fun `what is no glb, or is cut short, is no mesh and no crash`() {
        assertNull(Glb.mesh(ByteArray(0))); assertNull(Glb.mesh("not a model at all, only words".toByteArray()))
        val whole = glb(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), ByteArray(12), shortArrayOf(0, 1, 2))
        for (cut in listOf(10, 19, 40, whole.size - 30, whole.size - 1)) assertNull("cut to $cut", Glb.mesh(whole.copyOf(cut)))
        assertNotNull(Glb.mesh(whole))
    }

    @Test fun `the files are found in the package as it lies on the phone, and a file that is missing is only missing`() {
        val apk = File.createTempFile("made-up-package", ".apk")
        try {
            val zipped = ByteArrayOutputStream()
            ZipOutputStream(zipped).use { z ->
                for ((name, body) in listOf(XiaomiPin.CARD to "card", XiaomiPin.EDGE to "edge", XiaomiPin.ENV to "env", "assets/other.txt" to "other")) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
            }
            apk.writeBytes(zipped.toByteArray())
            val got = XiaomiPin.bytes(XiaomiPin.FILES, listOf("/no/such/package.apk", apk.path))
            assertEquals(setOf(XiaomiPin.CARD, XiaomiPin.EDGE, XiaomiPin.ENV), got.keys)
            assertEquals("card", String(got.getValue(XiaomiPin.CARD)))
            assertTrue(XiaomiPin.bytes(XiaomiPin.FILES, listOf("/no/such/package.apk")).isEmpty())
        } finally { apk.delete() }
    }
}
