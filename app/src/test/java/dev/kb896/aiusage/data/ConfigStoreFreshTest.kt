package dev.kb896.aiusage.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The back's screen runs in a process of its own (":rear") that reads the settings and does not write them: a plain
 * [ConfigStore.load] there keeps showing the settings as that process first read them, [ConfigStore.loadFresh] shows
 * what the app wrote since. The app's write is made here by writing the settings file itself, as another process does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigStoreFreshTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("panel_config", Context.MODE_PRIVATE)
    @After fun reset() { prefs.edit().clear().commit() }

    /** The settings file as the app's own process leaves it, holding [config]. */
    private fun writtenElsewhere(config: PanelConfig) {
        val file = File(context.dataDir, "shared_prefs/panel_config.xml")
        assertTrue("the settings file is where SharedPreferences keeps it", file.isFile)
        val blob = PanelConfig.encode(config).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        file.writeText("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"config\">$blob</string>\n</map>\n")
        assertTrue(file.setLastModified(System.currentTimeMillis() + 2_000L))
    }

    @Test fun `a fresh load sees what another process wrote since, a plain one does not`() {
        // Written and on disk before this "process" reads it (commit: no write of its own still on its way).
        assertTrue(prefs.edit().putString("config", PanelConfig.encode(PanelConfig(demoMode = false))).commit())
        val store = ConfigStore(context)
        assertFalse(store.load().demoMode)
        writtenElsewhere(PanelConfig(demoMode = true))
        assertFalse("a plain load keeps what this process read first", store.load().demoMode)
        assertTrue("a fresh load reads the file again", store.loadFresh().demoMode)
        assertTrue("and the loads after it have it too", store.load().demoMode)
    }

    @Test fun `a fresh load of an unchanged file is the same config`() {
        assertTrue(prefs.edit().putString("config", PanelConfig.encode(PanelConfig(demoMode = true))).commit())
        val store = ConfigStore(context)
        assertTrue(store.load().demoMode)
        assertTrue(store.loadFresh().demoMode)
        assertTrue(store.load() == store.loadFresh())
    }
}
