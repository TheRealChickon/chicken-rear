package dev.kb896.aiusage.ui

import dev.kb896.aiusage.ui.home.HomeSection
import dev.kb896.aiusage.ui.home.HomeSections
import dev.kb896.aiusage.ui.home.PreviewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The settings show the sections that belong to the page selected under the preview. */
class HomeSectionsTest {
    // The choices he can always select (Wake is offered only with a wake clip, and shows Idle's sections).
    private val choices = PreviewMode.entries.filter { it != PreviewMode.WAKE }

    @Test fun `every section is reachable under exactly one choice`() {
        for (s in HomeSection.entries) {
            val under = choices.filter { s in HomeSections.of(it) }
            assertEquals("$s is under $under", 1, under.size)
            assertEquals(HomeSections.home(s), under.single())
        }
        // And nothing is shown twice or left out: all the choices' sections together are all the sections, once each.
        val all = choices.flatMap { HomeSections.of(it) }
        assertEquals(HomeSection.entries.size, all.size)
        assertEquals(HomeSection.entries.toSet(), all.toSet())
    }

    @Test fun `each choice shows its own - Music the music, Charging the charging, Message the notifications, Usage the accounts, Notes the notes`() {
        assertEquals(listOf(HomeSection.MUSIC), HomeSections.of(PreviewMode.MUSIC))
        assertEquals(listOf(HomeSection.NAVIGATION), HomeSections.of(PreviewMode.NAVIGATION))
        assertEquals(listOf(HomeSection.CHARGING), HomeSections.of(PreviewMode.CHARGING))
        assertEquals(listOf(HomeSection.NOTIFICATIONS), HomeSections.of(PreviewMode.ALERT))
        assertEquals(listOf(HomeSection.CLAUDE), HomeSections.of(PreviewMode.USAGE))
        assertEquals(listOf(HomeSection.NOTES), HomeSections.of(PreviewMode.NOTES))
        assertEquals(
            listOf(HomeSection.FACES, HomeSection.ALWAYS_ON, HomeSection.ANIMATIONS, HomeSection.APPS, HomeSection.CAMERA, HomeSection.THREE_FINGERS, HomeSection.ADVANCED),
            HomeSections.of(PreviewMode.IDLE),
        )
    }

    private fun source(path: String): String =
        listOf("src/main/java/dev/kb896/aiusage/$path", "app/src/main/java/dev/kb896/aiusage/$path").map(::File).first { it.isFile }.readText()

    @Test fun `Fluid size is one row of the Music section - the brightness's slider from 0, its number, saved as it moves, the preview told`() {
        // A new setting goes into the section of its page: the music page's under Music, and only there.
        assertEquals(listOf(HomeSection.MUSIC), HomeSections.of(PreviewMode.MUSIC))
        val home = source("ui/home/HomeScreen.kt")
        assertTrue(home.contains("HomeSection.MUSIC -> Music(model)"))
        val music = home.substringAfter("private fun Music(model: HomeModel)").substringBefore("\n@Composable")
        assertEquals("one row of that name, in the whole page", 1, Regex("""Line\("Fluid size"\)""").findAll(home).count())
        assertTrue("in the Music section", music.contains("Line(\"Fluid size\")"))
        // The same slider as the back's brightness, from 0 to 1 for 0 to 100; each move written (saved and sent to the back).
        assertTrue(music, Regex("""Slide\(c\.musicFloor / 100f, "Fluid size", from = 0f""").containsMatchIn(music))
        assertTrue(music, music.contains("model.write { it.copy(musicFloor = Math.round(v * 100f).coerceIn(0, 100)) }"))
        // The number beside it.
        assertTrue(music, music.contains("Label(\"\${c.musicFloor}\""))
        // The preview of the back is told it, and its music page's body follows it.
        assertTrue(home.contains("musicFloor = c.musicFloor,"))
        assertTrue(source("ui/home/RearPreview.kt").contains("body.floor = state.musicFloor"))
        // A stock-looking slider: a plain well, a white knob, no colour of its own (none of the switch's "on" either).
        val slide = source("ui/home/HomeGlass.kt").substringAfter("fun Slide(").substringBefore("\n/** A row of choices")
        assertFalse(slide, slide.contains("Color(0x"))
        assertFalse(slide, slide.contains("tone.on"))
        assertTrue(slide, slide.contains("background(Color.White, CircleShape)"))
    }

    @Test fun `Three fingers held is one row of the Idle page - four plain choices, a screenshot first, saved as chosen`() {
        assertEquals(PreviewMode.IDLE, HomeSections.home(HomeSection.THREE_FINGERS))
        val home = source("ui/home/HomeScreen.kt")
        assertTrue(home.contains("HomeSection.THREE_FINGERS -> ThreeFingersHeld(model)"))
        val row = home.substringAfter("private fun ThreeFingersHeld(model: HomeModel)").substringBefore("\n@Composable")
        assertEquals("one row of that name, in the whole page", 1, Regex("""Label\("Three fingers held"""").findAll(home).count())
        // As the pin animation's choice: its label, then the plain segments (no colour of their own: HomeGlass.Segmented).
        assertTrue(row, row.contains("""Label("Three fingers held", size = 16, weight = 500)"""))
        assertTrue(row, row.contains("""Segmented(listOf("Screenshot", "Recording", "Back off", "Nothing"), c.threeFingers.ordinal"""))
        assertTrue(row, row.contains("model.write { it.copy(threeFingers = ThreeFingers.entries[i]) }"))
        assertFalse(row, row.contains("Color("))
        // The segments follow the choices' order, the default first.
        assertEquals(listOf("SCREENSHOT", "RECORDING", "BACK_OFF", "NOTHING"), dev.kb896.aiusage.data.ThreeFingers.entries.map { it.name })
    }

    @Test fun `navigation is a live preview with its own single settings section`() {
        assertEquals(listOf(HomeSection.NAVIGATION), HomeSections.of(PreviewMode.NAVIGATION))
        assertTrue(PreviewMode.NAVIGATION in dev.kb896.aiusage.ui.home.PREVIEW_LIVE)
        assertEquals(PreviewMode.NAVIGATION,
            dev.kb896.aiusage.ui.home.previewSwipe(PreviewMode.IDLE, PreviewMode.NAVIGATION, across = true, forward = true))
        assertEquals(PreviewMode.USAGE,
            dev.kb896.aiusage.ui.home.previewSwipe(PreviewMode.NAVIGATION, PreviewMode.NAVIGATION, across = true, forward = true))
        assertEquals(PreviewMode.IDLE,
            dev.kb896.aiusage.ui.home.previewSwipe(PreviewMode.NAVIGATION, PreviewMode.NAVIGATION, across = true, forward = false))
    }

    @Test fun `Timer and Call have nothing to set, and Wake shows what Idle shows`() {
        assertTrue(HomeSections.of(PreviewMode.TIMER).isEmpty())
        assertTrue(HomeSections.of(PreviewMode.CALL).isEmpty())
        assertEquals(HomeSections.of(PreviewMode.IDLE), HomeSections.of(PreviewMode.WAKE))
    }
}
