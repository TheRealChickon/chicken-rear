package dev.kb896.aiusage.ui

import dev.kb896.aiusage.ui.home.HomeSection
import dev.kb896.aiusage.ui.home.HomeSections
import dev.kb896.aiusage.ui.home.PreviewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals(listOf(HomeSection.CHARGING), HomeSections.of(PreviewMode.CHARGING))
        assertEquals(listOf(HomeSection.NOTIFICATIONS), HomeSections.of(PreviewMode.ALERT))
        assertEquals(listOf(HomeSection.CLAUDE), HomeSections.of(PreviewMode.USAGE))
        assertEquals(listOf(HomeSection.NOTES), HomeSections.of(PreviewMode.NOTES))
        assertEquals(
            listOf(HomeSection.ALWAYS_ON, HomeSection.ANIMATIONS, HomeSection.APPS, HomeSection.CAMERA, HomeSection.ADVANCED),
            HomeSections.of(PreviewMode.IDLE),
        )
    }

    @Test fun `Timer and Call have nothing to set, and Wake shows what Idle shows`() {
        assertTrue(HomeSections.of(PreviewMode.TIMER).isEmpty())
        assertTrue(HomeSections.of(PreviewMode.CALL).isEmpty())
        assertEquals(HomeSections.of(PreviewMode.IDLE), HomeSections.of(PreviewMode.WAKE))
    }
}
