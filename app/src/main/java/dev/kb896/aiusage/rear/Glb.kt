package dev.kb896.aiusage.rear

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A mesh out of a binary glTF file (.glb), as far as Xiaomi's pin animation's three models need it
 * (`assets/models/card-mesh-rounded.glb`, `card-edge-mesh.glb`, `user-image-mesh.glb` in its package, read on the
 * phone at run time: rear/XiaomiPin): the first primitive of the first mesh, its positions, normals, texture
 * coordinates, vertex colours and triangle indices, as plain arrays for a vertex buffer. No materials, no
 * animations, no scene graph (the three models are one mesh each, at the origin).
 *
 * Pure: bytes in, arrays out.
 */
object Glb {
    class Mesh(
        /** x, y, z per vertex. */
        val positions: FloatArray,
        /** x, y, z per vertex, or empty. */
        val normals: FloatArray,
        /** u, v per vertex, or empty. */
        val uvs: FloatArray,
        /** r, g, b, a per vertex (0..1), or empty. */
        val colors: FloatArray,
        /** Three per triangle. */
        val indices: IntArray,
        val name: String,
    ) {
        val vertices get() = positions.size / 3
        /** The smallest and largest x, y, z. */
        fun bounds(): Pair<FloatArray, FloatArray> {
            val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (i in positions.indices) { val a = i % 3; if (positions[i] < lo[a]) lo[a] = positions[i]; if (positions[i] > hi[a]) hi[a] = positions[i] }
            return lo to hi
        }
    }

    private const val MAGIC = 0x46546C67      // "glTF"
    private const val JSON = 0x4E4F534A       // "JSON"
    private const val BIN = 0x004E4942        // "BIN\0"

    /** The first mesh of [bytes]; null if it is no glb or has none. */
    fun mesh(bytes: ByteArray): Mesh? = runCatching { read(bytes) }.getOrNull()

    private fun read(bytes: ByteArray): Mesh? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 20 || b.getInt(0) != MAGIC) return null
        var at = 12
        var json: JsonObject? = null
        var bin: ByteBuffer? = null
        while (at + 8 <= bytes.size) {
            val len = b.getInt(at); val type = b.getInt(at + 4)
            if (len < 0 || at + 8 + len > bytes.size) return null
            when (type) {
                JSON -> json = Json.parseToJsonElement(String(bytes, at + 8, len, Charsets.UTF_8)).jsonObject
                BIN -> bin = ByteBuffer.wrap(bytes, at + 8, len).slice().order(ByteOrder.LITTLE_ENDIAN)
            }
            at += 8 + len + ((4 - len % 4) % 4)
        }
        val root = json ?: return null
        val data = bin ?: return null
        val accessors = root["accessors"]?.jsonArray ?: return null
        val views = root["bufferViews"]?.jsonArray ?: return null
        val mesh = root["meshes"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val prim = mesh["primitives"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val attrs = prim["attributes"]?.jsonObject ?: return null
        fun floats(name: String, want: Int): FloatArray {
            val i = attrs[name]?.jsonPrimitive?.int ?: return FloatArray(0)
            return accessor(accessors, views, data, i, want)
        }
        val positions = floats("POSITION", 3)
        if (positions.isEmpty()) return null
        val indices = prim["indices"]?.jsonPrimitive?.int?.let { i -> accessor(accessors, views, data, i, 1).let { f -> IntArray(f.size) { f[it].toInt() } } }
            ?: IntArray(positions.size / 3) { it }
        return Mesh(positions, floats("NORMAL", 3), floats("TEXCOORD_0", 2), floats("COLOR_0", 4), indices, mesh["name"]?.jsonPrimitive?.content.orEmpty())
    }

    /** Accessor [index] as floats, [want] numbers per element (a colour of three gets an alpha of 1; whole numbers that are "normalized" become 0..1). */
    private fun accessor(accessors: JsonArray, views: JsonArray, data: ByteBuffer, index: Int, want: Int): FloatArray {
        val a = accessors[index].jsonObject
        val view = views[a["bufferView"]!!.jsonPrimitive.int].jsonObject
        val count = a["count"]!!.jsonPrimitive.int
        val comp = a["componentType"]!!.jsonPrimitive.int
        val per = when (a["type"]!!.jsonPrimitive.content) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> return FloatArray(0) }
        val size = when (comp) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> return FloatArray(0) }
        val norm = a["normalized"]?.jsonPrimitive?.content == "true"
        val start = (view["byteOffset"]?.jsonPrimitive?.int ?: 0) + (a["byteOffset"]?.jsonPrimitive?.int ?: 0)
        val stride = view["byteStride"]?.jsonPrimitive?.int?.takeIf { it > 0 } ?: (per * size)
        val out = FloatArray(count * want)
        for (i in 0 until count) for (k in 0 until want) {
            out[i * want + k] = if (k >= per) 1f else {
                val p = start + i * stride + k * size
                when (comp) {
                    5126 -> data.getFloat(p)
                    5125 -> (data.getInt(p).toLong() and 0xFFFFFFFFL).toFloat()
                    5123 -> (data.getShort(p).toInt() and 0xFFFF).let { if (norm) it / 65535f else it.toFloat() }
                    5122 -> data.getShort(p).let { if (norm) maxOf(it / 32767f, -1f) else it.toFloat() }
                    5121 -> (data.get(p).toInt() and 0xFF).let { if (norm) it / 255f else it.toFloat() }
                    else -> data.get(p).let { if (norm) maxOf(it / 127f, -1f) else it.toFloat() }
                }
            }
        }
        return out
    }
}
