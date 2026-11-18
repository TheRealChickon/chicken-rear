package dev.kb896.aiusage.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ConfigStore keeps its last decode: every write is still seen by the very next load, from any instance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigStoreCacheTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun clear() = context.getSharedPreferences("panel_config", Context.MODE_PRIVATE).edit().clear().commit()
    @After fun reset() { clear() }

    @Test fun `nothing stored is the fresh install`() {
        clear()
        assertSame(PanelConfig.FRESH, ConfigStore(context).load())
    }

    @Test fun `repeated loads hand out one config, across instances`() {
        ConfigStore(context).save(PanelConfig(pins = listOf(Pin("kept", 1L))))
        val first = ConfigStore(context).load()
        assertSame(first, ConfigStore(context).load())
        assertSame(first, ConfigStore(context.applicationContext).load())
    }

    @Test fun `every write is seen by the next load`() {
        val store = ConfigStore(context)
        for (i in 0 until 20) {
            val wanted = PanelConfig(pins = listOf(Pin("note $i", i.toLong())), musicFollows = i % 2 == 0)
            store.save(wanted)
            val got = ConfigStore(context).load()
            assertEquals("note $i", got.pins.single().text)
            assertEquals(i % 2 == 0, got.musicFollows)
            assertEquals(PanelConfig.decode(PanelConfig.encode(wanted)), got)
        }
    }

    @Test fun `a cleared store goes back to the fresh install, and a write after it is seen`() {
        val store = ConfigStore(context)
        store.save(PanelConfig(pins = listOf(Pin("before", 1L))))
        assertEquals("before", store.load().pins.single().text)
        clear()
        assertSame(PanelConfig.FRESH, store.load())
        store.save(PanelConfig(pins = listOf(Pin("before", 1L))))
        assertEquals("before", store.load().pins.single().text)
    }

    @Test fun `what is loaded equals a fresh decode of what is stored`() {
        val store = ConfigStore(context)
        store.save(PanelConfig(pins = listOf(Pin("same", 3L))))
        assertEquals(PanelConfig.decode(store.raw()), store.load())
    }
}
