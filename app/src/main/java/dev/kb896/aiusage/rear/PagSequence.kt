package dev.kb896.aiusage.rear

object PagSequence {
    class Patch(val x: Int, val y: Int, val webp: ByteArray)

    class Frame(
        /** A key frame starts from nothing; else it is drawn over the frame before. */
        val key: Boolean,
        val patches: List<Patch>,
    ) {
        /** The exporter's mark for "nothing new" (one 1 x 1 picture): libpag shows the frame before. */
        val unchanged: Boolean get() = patches.size == 1 && webpSize(patches[0].webp)?.let { it.first == 1 && it.second == 1 } == true
        val empty: Boolean get() = patches.isEmpty()
    }

    class Sequence(val width: Int, val height: Int, val rate: Float, val frames: List<Frame>)

    private class Stream(val d: ByteArray, var p: Int) {
        var bit = 0
        fun align() { if (bit != 0) { p++; bit = 0 } }
        fun u8(): Int { align(); return d[p++].toInt() and 0xFF }
        fun u16(): Int { align(); val v = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8); p += 2; return v }
        fun u32(): Long { align(); var v = 0L; for (i in 0 until 4) v = v or ((d[p + i].toLong() and 0xFF) shl (8 * i)); p += 4; return v }
        fun f32(): Float = java.lang.Float.intBitsToFloat(u32().toInt())
        fun varuint(): Long {
            align()
            var v = 0L; var shift = 0
            while (true) {
                val b = d[p++].toInt() and 0xFF
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
                check(shift < 64) { "a number too long" }
            }
        }
        /** libpag's signed numbers: the sign in the lowest bit. */
        fun varint(): Int { val v = varuint(); return (if (v and 1L == 1L) -(v shr 1) else v shr 1).toInt() }
        fun bitbool(): Boolean {
            val v = (d[p].toInt() shr bit) and 1
            bit++
            if (bit == 8) { bit = 0; p++ }
            return v == 1
        }
        fun bytes(): ByteArray { val n = varuint().toInt(); check(n >= 0 && p + n <= d.size) { "bytes past the end" }; return d.copyOfRange(p, p + n).also { p += n } }
        fun tag(): Pair<Int, Long> {
            align()
            val h = u16()
            val code = h shr 6
            var length = (h and 0x3F).toLong()
            if (length == 0x3FL) length = u32()
            return code to length
        }
    }

    private const val END = 0
    private const val COMPOSITION_ATTRIBUTES = 3
    private const val BITMAP_COMPOSITION = 45
    private const val BITMAP_SEQUENCE = 46

    /** The file's one picture sequence; null when the file is not a PAG of that kind, or cannot be read. */
    fun parse(data: ByteArray): Sequence? = runCatching {
        if (data.size < 9 || data[0] != 'P'.code.toByte() || data[1] != 'A'.code.toByte() || data[2] != 'G'.code.toByte()) return null
        // 'U': not compressed. Anything else is not this kind of file.
        if (data[8] != 'U'.code.toByte()) return null
        val s = Stream(data, 9)
        var found: Sequence? = null
        while (s.p < data.size) {
            val (code, length) = s.tag()
            val end = s.p + length.toInt()
            if (code == END) break
            if (code == BITMAP_COMPOSITION) {
                s.varuint()                                    // its id
                while (s.p < end) {
                    val (c2, l2) = s.tag()
                    val e2 = s.p + l2.toInt()
                    if (c2 == END) break
                    if (c2 == COMPOSITION_ATTRIBUTES) {
                        s.varint(); s.varint(); s.varuint(); s.f32(); s.u8(); s.u8(); s.u8()
                    } else if (c2 == BITMAP_SEQUENCE && found == null) {
                        val w = s.varint(); val h = s.varint()
                        val rate = s.f32()
                        val count = s.varuint().toInt()
                        check(count in 0..10_000) { "too many frames" }
                        val keys = BooleanArray(count) { s.bitbool() }
                        s.align()
                        val frames = ArrayList<Frame>(count)
                        for (i in 0 until count) {
                            val n = s.varuint().toInt()
                            check(n in 0..1_000) { "too many patches" }
                            val patches = ArrayList<Patch>(n)
                            repeat(n) { val x = s.varint(); val y = s.varint(); patches += Patch(x, y, s.bytes()) }
                            frames += Frame(keys[i], patches)
                        }
                        found = Sequence(w, h, rate, frames)
                    }
                    s.align()
                    check(s.p <= e2) { "read past a tag" }
                    s.p = e2
                }
            }
            s.p = end
        }
        found
    }.getOrNull()

    /** A WebP picture's width and height from its own header, or null. */
    fun webpSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 30 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WEBP") return null
        fun u(i: Int) = b[i].toInt() and 0xFF
        return when (String(b, 12, 4, Charsets.US_ASCII)) {
            "VP8X" -> (1 + (u(24) or (u(25) shl 8) or (u(26) shl 16))) to (1 + (u(27) or (u(28) shl 8) or (u(29) shl 16)))
            "VP8 " -> ((u(26) or (u(27) shl 8)) and 0x3FFF) to ((u(28) or (u(29) shl 8)) and 0x3FFF)
            "VP8L" -> {
                val bits = u(21).toLong() or (u(22).toLong() shl 8) or (u(23).toLong() shl 16) or (u(24).toLong() shl 24)
                ((bits and 0x3FFF) + 1).toInt() to (((bits shr 14) and 0x3FFF) + 1).toInt()
            }
            else -> null
        }
    }
}
