package dev.kb896.aiusage.ui

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.data.PanelTheme
import dev.kb896.aiusage.ui.glass.GlassButton
import dev.kb896.aiusage.ui.glass.GlassChip
import dev.kb896.aiusage.ui.glass.GlassIconButton
import dev.kb896.aiusage.ui.glass.GlassSegmented
import dev.kb896.aiusage.ui.liquid.LiquidToggle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRowsThemeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @After fun reset() { ConfigStore(context).save(PanelConfig()) }

    private fun find(root: View, type: Class<*>): View? {
        if (type.isInstance(root)) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), type)?.let { return it }
        return null
    }

    @Test fun `GLASS builds the glass controls and they report through the same callbacks`() {
        ConfigStore(context).save(PanelConfig(theme = PanelTheme.GLASS))
        val parent = LinearLayout(context)
        var toggled: Boolean? = null
        SettingsRows.toggle(parent, "T", false) { toggled = it }
        SettingsRows.choice(parent, "C", listOf("a", "b", "c"), 1) {}
        val nav = SettingsRows.navigationButton(parent, "N") {}
        SettingsRows.colorRow(parent, "K", 0xFF112233.toInt()) {}
        val switch = find(parent, LiquidToggle::class.java) as LiquidToggle
        assertNull(find(parent, Switch::class.java))
        assertNull(find(parent, Spinner::class.java))
        assertTrue(nav is GlassButton)
        assertEquals(0xFF112233.toInt(), (find(parent, GlassChip::class.java) as GlassChip).colour)
        switch.performClick()
        assertEquals(true, toggled)
        SettingsRows.reorderable(parent, "R", canMoveUp = true, onMoveUp = {}) {}
        SettingsRows.colorGrid(parent, listOf(SettingsRows.Swatch("S", 0xFF445566.toInt()) {}))
        // The reorder arrow is there and works, but plain: no glass button of its own (W4).
        assertNull(find(parent, GlassIconButton::class.java))
        val move = find(parent, android.widget.ImageButton::class.java) as android.widget.ImageButton
        assertEquals("Move R up", move.contentDescription)
        assertNull(move.background)
        assertEquals(View.VISIBLE, move.visibility)
        assertTrue(move.isEnabled)
        // The first row's arrow is hidden but keeps its room, so both rows keep one width.
        val first = LinearLayout(context)
        SettingsRows.reorderable(first, "F", canMoveUp = false, onMoveUp = {}) {}
        val inert = find(first, android.widget.ImageButton::class.java) as android.widget.ImageButton
        assertEquals(View.INVISIBLE, inert.visibility)
        assertFalse(inert.isEnabled)
        val chips = ArrayList<GlassChip>()
        fun chipsIn(v: View) { if (v is GlassChip) chips += v; if (v is android.view.ViewGroup) for (i in 0 until v.childCount) chipsIn(v.getChildAt(i)) }
        chipsIn(parent)
        assertTrue("colorRow and colorGrid chips: ${chips.size}", chips.size >= 2)
        assertTrue(chips.any { it.colour == 0xFF445566.toInt() })
    }

    /**
     * The companion screen's greyed feature (CompanionUi.missing) is one note and one glass button,
     * inked for whichever theme the phone is in; a feature that is fine adds nothing.
     */
    @Test fun `a missing need is one note and one button, in the light and the dark theme`() {
        val missing = CompanionRows.row(
            Readiness.Feature.MORE_APPS, Readiness.State.Missing("Shizuku running", Readiness.Action.OPEN_SHIZUKU),
        )
        val ready = CompanionRows.row(Readiness.Feature.CHARGE, Readiness.State.Ready)
        val host = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(dev.kb896.aiusage.ui.companion.CompanionHost::class.java),
        ) { _, _, _ -> null } as dev.kb896.aiusage.ui.companion.CompanionHost
        try {
            for (dark in listOf(false, true)) {
                dev.kb896.aiusage.ui.glass.Ink.dark = dark
                val card = dev.kb896.aiusage.ui.glass.GlassCard(context)
                dev.kb896.aiusage.ui.companion.CompanionUi.missing(card, missing, host)
                assertEquals(2, card.childCount)
                val note = card.getChildAt(0) as android.widget.TextView
                assertEquals("Needs Shizuku running.", note.text.toString())
                assertEquals(dev.kb896.aiusage.ui.glass.CardTitle.NOTE_TAG, note.tag)
                val button = card.getChildAt(1)
                assertTrue(button is GlassButton)
                assertEquals("Open Shizuku", (button as GlassButton).text.toString())
                card.ink()
                assertEquals(dev.kb896.aiusage.ui.glass.Ink.tertiary(0f), note.currentTextColor)
                val fine = dev.kb896.aiusage.ui.glass.GlassCard(context)
                dev.kb896.aiusage.ui.companion.CompanionUi.missing(fine, ready, host)
                assertEquals(0, fine.childCount)
            }
            // The two themes really ink the note differently.
            dev.kb896.aiusage.ui.glass.Ink.dark = false
            val light = dev.kb896.aiusage.ui.glass.Ink.tertiary(0f)
            dev.kb896.aiusage.ui.glass.Ink.dark = true
            assertTrue(light != dev.kb896.aiusage.ui.glass.Ink.tertiary(0f))
        } finally {
            dev.kb896.aiusage.ui.glass.Ink.dark = true
        }
    }

    /** W4: a note is one small dim line, never styled like the title above it. */
    @Test fun `a note is smaller and dimmer than a title`() {
        val parent = LinearLayout(context)
        val title = SettingsRows.title(parent, "Title")
        val note = SettingsRows.note(parent, "Short.")
        assertTrue(note.textSize < title.textSize)
        assertTrue((note.currentTextColor ushr 24) < (title.currentTextColor ushr 24))
        assertEquals(dev.kb896.aiusage.ui.glass.CardTitle.NOTE_TAG, note.tag)
        // A card's inking keeps it dim.
        val card = dev.kb896.aiusage.ui.glass.GlassCard(context)
        val inCard = SettingsRows.note(card, "Short.")
        card.ink()
        assertEquals(dev.kb896.aiusage.ui.glass.Ink.tertiary(0f), inCard.currentTextColor)
    }
}
