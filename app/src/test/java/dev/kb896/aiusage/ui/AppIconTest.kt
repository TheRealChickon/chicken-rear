package dev.kb896.aiusage.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppIconTest {
    private val res = File("src/main/res")

    private fun colours(file: File): List<String> =
        Regex("""#([0-9A-Fa-f]{6,8})\b""").findAll(file.readText()).map { it.groupValues[1] }.toList()

    @Test
    fun `both launcher icons are the same adaptive icon with all three layers`() {
        val square = File(res, "mipmap-anydpi-v26/ic_launcher.xml").readText()
        val round = File(res, "mipmap-anydpi-v26/ic_launcher_round.xml").readText()
        assertEquals(square, round)
        for (layer in listOf("background", "foreground", "monochrome")) {
            assertTrue(layer, square.contains("<$layer android:drawable=\"@drawable/ic_launcher_$layer\""))
            assertTrue(layer, File(res, "drawable/ic_launcher_$layer.xml").readText().contains("<vector"))
        }
    }

    @Test
    fun `every colour in the icon is a grey`() {
        val files = listOf("background", "foreground", "monochrome").map { File(res, "drawable/ic_launcher_$it.xml") }
        for (file in files) {
            val found = colours(file)
            assertTrue(file.name, found.isNotEmpty())
            for (hex in found) {
                val rgb = hex.takeLast(6)
                assertTrue("${file.name}: #$hex is not a grey", rgb.substring(0, 2) == rgb.substring(2, 4) && rgb.substring(2, 4) == rgb.substring(4, 6))
            }
        }
    }

    @Test
    fun `the upstream's purple icon is gone`() {
        val stale = res.walkTopDown().filter { it.isFile && it.name.startsWith("ic_launcher_fg") }.toList()
        assertTrue(stale.toString(), stale.isEmpty())
        val values = res.walkTopDown().filter { it.isFile && it.parentFile.name.startsWith("values") }
        for (file in values) assertFalse(file.name, file.readText().contains("5906D4", ignoreCase = true))
    }

    @Test
    fun `the monochrome layer is one tone`() {
        val tones = colours(File(res, "drawable/ic_launcher_monochrome.xml")).map { it.takeLast(6).uppercase() }.toSet()
        assertEquals(setOf("FFFFFF"), tones)
    }
}
