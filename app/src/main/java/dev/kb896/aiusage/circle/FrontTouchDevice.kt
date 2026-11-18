package dev.kb896.aiusage.circle

import java.io.File

internal object FrontTouchDevice {
    private val EVENT = Regex("event[0-9]+")

    fun find(): String? {
        val entry = File("/sys/class/input").listFiles()?.firstOrNull {
            it.name.matches(EVENT) && runCatching { File(it, "device/name").readText().trim() == "Xiaomi_Touch_Input_0" }.getOrDefault(false)
        }
        if (entry != null) return "/dev/input/" + entry.name
        // Some kernels expose the input device name to getevent only.
        val listing = ProcessBuilder("timeout", "3", "getevent", "-p").start()
        try {
            var path: String? = null
            listing.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("add device ")) path = line.substringAfter(": ").trim()
                    if (path != null && line.trim().substringAfter("name:", "").trim() == "\"Xiaomi_Touch_Input_0\"") return path
                }
            }
        } finally { listing.destroy() }
        return null
    }
}
