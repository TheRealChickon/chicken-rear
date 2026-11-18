package dev.kb896.aiusage.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * THE FACES AS THE SHELL FOUND THEM (rear/FaceScan): the app cannot read his Theme Store downloads, so the shell writes
 * the list and a 488 x 298 copy of Xiaomi's own picture of each face into this app's files (`faces/`). Read here for the
 * settings' Faces section and Advanced's "Faces left out".
 */
object FacesStore {
    data class Face(val key: String, val title: String, val offered: Boolean, val pending: Boolean, val reason: String, val picture: File?)

    fun dir(context: android.content.Context): File? = context.getExternalFilesDir(null)?.let { File(it, "faces") }

    /** The list in the picker's order; ours alone (the moon, then the dust) when the shell has not written one yet. */
    fun read(context: android.content.Context): List<Face> {
        val d = dir(context)
        val text = d?.let { File(it, "faces.json") }?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        val list = text?.let { parse(it, d) }.orEmpty()
        return list.ifEmpty { OURS }
    }

    /** Our own faces, always offered: what the list is before the shell has written one (rear/FaceCatalog). */
    val OURS = listOf(
        Face(dev.kb896.aiusage.rear.FaceCatalog.MOON, "Moon", true, false, "", null),
        Face(dev.kb896.aiusage.rear.FaceCatalog.DUST, dev.kb896.aiusage.rear.FaceCatalog.DUST_TITLE, true, false, "", null),
    )

    fun parse(text: String, dir: File?): List<Face> = runCatching {
        (Json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val key = s("key") ?: return@mapNotNull null
            Face(key, s("title") ?: key, (o["offered"] as? JsonPrimitive)?.booleanOrNull == true, (o["pending"] as? JsonPrimitive)?.booleanOrNull == true,
                s("reason").orEmpty(), dir?.let { File(it, "${key.replace(':', '_')}.jpg") }?.takeIf { it.isFile })
        }
    }.getOrDefault(emptyList())
}
