package dev.kb896.aiusage.rear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The PAG reader on a made-up file of the same kind as Xiaomi's pin_show.pag (nothing of Xiaomi's is in the tests). */
class PagSequenceTest {
    private class Out {
        val b = ByteArrayOutputStream()
        fun u8(v: Int) = apply { b.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
        fun u32(v: Int) = apply { u16(v); u16(v shr 16) }
        fun f32(v: Float) = u32(java.lang.Float.floatToIntBits(v))
        fun varuint(v: Long): Out { var x = v; while (true) { val low = (x and 0x7F).toInt(); x = x ushr 7; if (x == 0L) { u8(low); return this }; u8(low or 0x80) } }
        fun varint(v: Int) = varuint(if (v < 0) ((-v).toLong() shl 1) or 1L else v.toLong() shl 1)
        fun bytes(d: ByteArray) = apply { varuint(d.size.toLong()); b.write(d) }
        fun raw(d: ByteArray) = apply { b.write(d) }
        fun tag(code: Int, body: ByteArray) = apply { if (body.size < 0x3F) u16((code shl 6) or body.size) else { u16((code shl 6) or 0x3F); u32(body.size) }; raw(body) }
        fun get(): ByteArray = b.toByteArray()
    }

    /** A made-up WebP header of [w] x [h] (VP8X), with [fill] bytes after it. */
    private fun webp(w: Int, h: Int, fill: Int = 6): ByteArray {
        val o = Out().raw("RIFF".toByteArray()).u32(22 + fill).raw("WEBP".toByteArray()).raw("VP8X".toByteArray()).u32(10).u8(0x10).u8(0).u8(0).u8(0)
        o.u8(w - 1).u8((w - 1) shr 8).u8((w - 1) shr 16).u8(h - 1).u8((h - 1) shr 8).u8((h - 1) shr 16)
        repeat(fill) { o.u8(it) }
        return o.get()
    }

    private fun pag(frames: List<Pair<Boolean, List<Triple<Int, Int, ByteArray>>>>, compression: Char = 'U'): ByteArray {
        val attrs = Out().varint(976).varint(596).varuint(frames.size.toLong()).f32(60f).u8(0xF2).u8(0xF2).u8(0xF2).get()
        val seq = Out().varint(976).varint(596).f32(60f).varuint(frames.size.toLong())
        var byte = 0; var bit = 0
        for ((key, _) in frames) { if (key) byte = byte or (1 shl bit); bit++; if (bit == 8) { seq.u8(byte); byte = 0; bit = 0 } }
        if (bit != 0) seq.u8(byte)
        for ((_, patches) in frames) { seq.varuint(patches.size.toLong()); for ((x, y, d) in patches) { seq.varint(x).varint(y).bytes(d) } }
        val block = Out().varuint(2).tag(3, attrs).tag(46, seq.get()).tag(0, ByteArray(0)).get()
        val body = Out().tag(45, block).tag(0, ByteArray(0)).get()
        return Out().raw("PAG".toByteArray()).u8(1).u32(body.size).u8(compression.code).raw(body).get()
    }

    @Test fun `a picture sequence is read frame by frame, patch by patch`() {
        val big = webp(976, 596, 200)
        val part = webp(599, 596)
        val mark = webp(1, 1)
        val frames = listOf(true to listOf(Triple(0, 0, mark)), false to emptyList(), true to listOf(Triple(245, 0, part)), true to listOf(Triple(0, 0, big)), false to listOf(Triple(0, 0, mark)))
        val s = PagSequence.parse(pag(frames))!!
        assertEquals(976, s.width); assertEquals(596, s.height); assertEquals(60f, s.rate, 0f)
        assertEquals(5, s.frames.size)
        assertTrue(s.frames[0].key); assertTrue(s.frames[0].unchanged)
        assertFalse(s.frames[1].key); assertTrue(s.frames[1].empty)
        assertEquals(245, s.frames[2].patches.single().x); assertArrayEquals(part, s.frames[2].patches.single().webp)
        assertArrayEquals("a patch longer than a short tag", big, s.frames[3].patches.single().webp)
        assertFalse(s.frames[3].unchanged); assertTrue(s.frames[4].unchanged)
        assertEquals(976 to 596, PagSequence.webpSize(big)); assertEquals(599 to 596, PagSequence.webpSize(part))
    }

    @Test fun `negative places and many frames`() {
        val frames = (0 until 60).map { i -> true to listOf(Triple(-i, i * 3, webp(10 + i, 20))) }
        val s = PagSequence.parse(pag(frames))!!
        assertEquals(60, s.frames.size)
        assertEquals(-59, s.frames[59].patches[0].x); assertEquals(177, s.frames[59].patches[0].y)
        assertTrue(s.frames.all { it.key })
    }

    @Test fun `anything else is not read`() {
        assertNull(PagSequence.parse(ByteArray(0)))
        assertNull(PagSequence.parse("not a pag at all".toByteArray()))
        assertNull("compressed", PagSequence.parse(pag(listOf(true to emptyList()), compression = 'Z')))
        assertNull("cut short", PagSequence.parse(pag(listOf(true to listOf(Triple(0, 0, webp(976, 596, 100))))).copyOf(60)))
        assertNull(PagSequence.webpSize(ByteArray(40)))
    }
}
