package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Message style" (the settings' Notifications section): the full page as before by default, the message island with the
 * face tinted or swept as the other two. Kept with the config, read in the shell as each message comes, and the preview
 * shows the one chosen.
 */
class MessageStyleTest {
    @Test fun `the full page is the default, and a config from before the setting keeps it`() {
        assertEquals(MessageStyle.FULL, PanelConfig().messageStyle)
        assertEquals(MessageStyle.FULL, PanelConfig.decode("""{"title":"T","showNotifications":true}""").messageStyle)
    }

    @Test fun `each choice is kept as it was chosen`() {
        for (style in MessageStyle.entries) {
            val back = PanelConfig.decode(PanelConfig.encode(PanelConfig(messageStyle = style)))
            assertEquals(style, back.messageStyle)
        }
    }

    private val main = File("src/main/java/dev/kb896/aiusage")
    private fun src(path: String) = File(main, path).readText()

    @Test fun `the row is in the Notifications section, three choices in the setting's order, and the preview shows it`() {
        assertEquals(listOf("Full page", "Island · Tint", "Island · Sweep"), dev.kb896.aiusage.ui.home.MESSAGE_STYLES)
        assertEquals(listOf(MessageStyle.FULL, MessageStyle.TINT, MessageStyle.SWEEP), MessageStyle.entries.toList())
        val home = src("ui/home/HomeScreen.kt")
        val section = home.substring(home.indexOf("private fun Notifications(")).substringBefore("\n}\n")
        assertTrue(section.contains("Label(\"Message style\""))
        assertTrue(section.contains("Segmented(MESSAGE_STYLES, c.messageStyle.ordinal"))
        assertTrue(section.contains("it.copy(messageStyle = dev.kb896.aiusage.data.MessageStyle.entries[i])"))
        assertTrue("choosing one plays it in the preview", section.contains("model.mode = PreviewMode.ALERT"))
        assertTrue(home.contains("messageStyle = c.messageStyle,"))
        val preview = src("ui/home/RearPreview.kt")
        assertTrue(preview.contains("if (state.messageStyle == dev.kb896.aiusage.data.MessageStyle.FULL) Banner(") && preview.contains("else IslandBanner("))
    }

    @Test fun `the shell reads it from the config and gives it to each message as it comes`() {
        val service = src("rear/RearUserService.kt")
        assertTrue(service.contains("messageStyle = config.messageStyle"))
        val popUp = service.substring(service.indexOf("private fun popUp(")).substringBefore("\n    }\n")
        assertTrue(popUp.contains("notices.pop(item, style, faceTints = "))
        assertTrue("its time on the back is the style's own", popUp.contains("holdHandler.postDelayed(popEnds, ms)"))
        assertTrue(service.contains("rearShow.request(RearShow.Kind.BANNER, now, notices.popMs(messageStyle))"))
        assertTrue(service.contains("veil = scene.veil"))
    }
}
