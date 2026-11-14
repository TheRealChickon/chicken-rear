package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.util.zip.ZipFile

/**
 * WHAT XIAOMI'S OWN PIN ANIMATION IS DRAWN FROM, READ FROM ITS PACKAGE AS IT IS INSTALLED ON THE PHONE, at run time,
 * as [XiaomiArt] does for pictures: the three models and three pictures of `com.xiaomi.subscreencenter`'s renderer.
 * NOTHING OF IT IS IN THIS REPOSITORY OR THIS APP'S PACKAGE: a phone without that package has no such animation and
 * the pin falls back to the plain drawing.
 *
 *  - `assets/models/card-mesh-rounded.glb` the card (a rounded slab 1.9 x 1.2 x 0.02), `card-edge-mesh.glb` its rim
 *    (with vertex colours), `user-image-mesh.glb` the pinned content's sheet (a 2 x 2 plane of 153 vertices);
 *  - `assets/images/env-blur-3.png` what the card is lit by (its matcap), `xiaoai-light.png` the light that is added
 *    over the frame, `noise.png`.
 * Its shaders are SPIR-V for Vulkan and are not loaded: ours are written for OpenGL ES after them (rear/PinGl).
 */
object XiaomiPin {
    private const val TAG = "AiUsageRear"
    private val APKS = listOf("/product/app/subscreencenter/subscreencenter.apk", "/system/app/subscreencenter/subscreencenter.apk")
    const val CARD = "assets/models/card-mesh-rounded.glb"
    const val EDGE = "assets/models/card-edge-mesh.glb"
    const val SHEET = "assets/models/user-image-mesh.glb"
    const val ENV = "assets/images/env-blur-3.png"
    const val LIGHT = "assets/images/xiaoai-light.png"
    const val NOISE = "assets/images/noise.png"
    val FILES = listOf(CARD, EDGE, SHEET, ENV, LIGHT, NOISE)

    class Assets(val card: Glb.Mesh, val edge: Glb.Mesh, val sheet: Glb.Mesh, val env: Bitmap, val light: Bitmap, val noise: Bitmap?)

    /** The named entries of the first of [apks] that can be read, by name; what is not there is not in the map. Pure (a zip is a zip). */
    fun bytes(names: Collection<String>, apks: List<String> = APKS): Map<String, ByteArray> {
        val file = apks.firstOrNull { java.io.File(it).canRead() } ?: return emptyMap()
        val out = HashMap<String, ByteArray>()
        runCatching {
            ZipFile(file).use { zip -> for (n in names) zip.getEntry(n)?.let { e -> if (e.size in 1..MOST) out[n] = zip.getInputStream(e).use { it.readBytes() } } }
        }
        return out
    }

    @Volatile private var kept: Assets? = null
    @Volatile private var tried = false

    /** All of it, read once and kept; null where it is not to be had ([apk]: a test's or the emulator's own copy first). */
    @Synchronized
    fun load(apk: String? = null): Assets? {
        kept?.let { return it }
        if (tried && apk == null) return null
        tried = true
        val began = android.os.SystemClock.uptimeMillis()
        val got = bytes(FILES, listOfNotNull(apk) + APKS)
        fun mesh(n: String) = got[n]?.let { Glb.mesh(it) }
        fun picture(n: String) = got[n]?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        val card = mesh(CARD); val edge = mesh(EDGE); val sheet = mesh(SHEET); val env = picture(ENV); val light = picture(LIGHT)
        if (card == null || edge == null || sheet == null || env == null || light == null) {
            Log.i(TAG, "Xiaomi's pin models and pictures are not all to be had (${got.size} of ${FILES.size} files): the plain drawing")
            return null
        }
        Log.i(TAG, "Xiaomi's pin: card ${card.vertices} vertices, rim ${edge.vertices}, sheet ${sheet.vertices}; pictures ${env.width} x ${env.height} and ${light.width} x ${light.height}; read in ${android.os.SystemClock.uptimeMillis() - began} ms")
        return Assets(card, edge, sheet, env, light, picture(NOISE)).also { kept = it }
    }

    private const val MOST = 8L * 1024 * 1024
}
