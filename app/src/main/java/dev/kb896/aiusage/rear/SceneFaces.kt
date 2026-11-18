package dev.kb896.aiusage.rear

object SceneFaces {
    fun list(files: Array<String>?): List<String> =
        files.orEmpty().filter { it.endsWith(".loop.mp4") && !it.contains('/') }.sorted()
}
