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
    /** Its rear light for a new pin (a baked picture sequence: rear/PagSequence), which "Pin animation: Ours" lays over the back. */
    const val PAG = "assets/pin_show.pag"
    /** What the models' path reads. */
    private val MODEL_FILES = listOf(CARD, EDGE, SHEET, ENV, LIGHT, NOISE)
    /** Every file of Xiaomi's that a pin of ours may read (the health check looks for each). */
    val FILES = MODEL_FILES + PAG

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
        val got = bytes(MODEL_FILES, listOfNotNull(apk) + APKS)
        fun mesh(n: String) = got[n]?.let { Glb.mesh(it) }
        fun picture(n: String) = got[n]?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        val card = mesh(CARD); val edge = mesh(EDGE); val sheet = mesh(SHEET); val env = picture(ENV); val light = picture(LIGHT)
        if (card == null || edge == null || sheet == null || env == null || light == null) {
            Log.i(TAG, "Xiaomi's pin models and pictures are not all to be had (${got.size} of ${MODEL_FILES.size} files): the plain drawing")
            return null
        }
        Log.i(TAG, "Xiaomi's pin: card ${card.vertices} vertices, rim ${edge.vertices}, sheet ${sheet.vertices}; pictures ${env.width} x ${env.height} and ${light.width} x ${light.height}; read in ${android.os.SystemClock.uptimeMillis() - began} ms")
        return Assets(card, edge, sheet, env, light, picture(NOISE)).also { kept = it }
    }

    /** What draft B's front is lit by: Xiaomi's environment picture and its light picture. */
    class Light(val env: Bitmap, val light: Bitmap)

    @Volatile private var keptLight: Light? = null
    @Volatile private var triedLight = false

    /** The two pictures alone (draft B ray-marches its own slab, so the models are not needed), read once and kept; null where not to be had. */
    @Synchronized
    fun light(apk: String? = null): Light? {
        keptLight?.let { return it }
        if (triedLight && apk == null) return null
        triedLight = true
        val got = bytes(listOf(ENV, LIGHT), listOfNotNull(apk) + APKS)
        fun picture(n: String) = got[n]?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        val env = picture(ENV); val light = picture(LIGHT)
        if (env == null || light == null) { Log.i(TAG, "Xiaomi's pin pictures are not to be had: the plain drawing"); return null }
        return Light(env, light).also { keptLight = it }
    }

    @Volatile private var keptVeil: PagSequence.Sequence? = null
    @Volatile private var triedVeil = false

    /** pin_show.pag's frames as WebP patches (not yet decoded), read once and kept (about 1 MB); null where not to be had. */
    @Synchronized
    fun veil(apk: String? = null): PagSequence.Sequence? {
        keptVeil?.let { return it }
        if (triedVeil && apk == null) return null
        triedVeil = true
        val seq = bytes(listOf(PAG), listOfNotNull(apk) + APKS)[PAG]?.let { PagSequence.parse(it) }
        if (seq == null || seq.frames.isEmpty()) { Log.i(TAG, "Xiaomi's pin_show.pag is not to be had or not read: the plain arrival on the back"); return null }
        Log.i(TAG, "Xiaomi's pin_show.pag: ${seq.width} x ${seq.height}, ${seq.frames.size} frames at ${seq.rate}")
        return seq.also { keptVeil = it }
    }

    private const val MOST = 8L * 1024 * 1024
}
