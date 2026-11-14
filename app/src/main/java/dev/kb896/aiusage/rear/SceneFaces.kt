package dev.kb896.aiusage.rear

/**
 * The faces a press and hold on the back goes through: the clips named "….loop.mp4" in the clips
 * folder (ClipStore), by name. Each is ONE shot or one picture; the user picks among them.
 */
object SceneFaces {
    fun list(files: Array<String>?): List<String> =
        files.orEmpty().filter { it.endsWith(".loop.mp4") && !it.contains('/') }.sorted()

    /** The face after [current], round to the first; null with none, or with only the one showing. */
    fun next(faces: List<String>, current: String): String? {
        if (faces.isEmpty()) return null
        val at = faces.indexOf(current)
        return faces[(at + 1) % faces.size].takeIf { it != current }
    }
}
