package dev.kb896.aiusage.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.PanelConfig
import dev.kb896.aiusage.service.Graph
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The companion screen builds in both themes with no Shizuku and no notification access (as on a
 * fresh install with Shizuku stopped): home, which is the Compose page, and Advanced under it,
 * and nothing throws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionScreenTest {
    @After fun reset() {
        dev.kb896.aiusage.ui.glass.Ink.dark = true
        ConfigStore(ApplicationProvider.getApplicationContext()).save(PanelConfig.FRESH)
    }

    private fun texts(v: View, into: MutableList<String> = ArrayList()): List<String> {
        if (v is TextView) into += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), into)
        return into
    }

    @Test fun `home and every page build in both themes`() {
        for (dark in listOf(false, true)) {
            dev.kb896.aiusage.ui.glass.Ink.dark = dark
            // Made while the activity is only created, as MainActivity.onCreate makes it: the
            // permission launcher must be registered before the activity starts.
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
            val activity = controller.get()
            ConfigStore(activity).save(PanelConfig.FRESH)
            val screen = CompanionScreen(activity, Graph.of(activity)) {}
            activity.setContentView(screen.root)
            controller.start().resume()
            screen.resume()
            // Home is the Compose page, on top; the older rows wait under it.
            fun composeViews(v: View): Int = (if (v is androidx.compose.ui.platform.ComposeView && v.visibility == View.VISIBLE) 1 else 0) +
                (if (v is ViewGroup) (0 until v.childCount).sumOf { composeViews(v.getChildAt(it)) } else 0)
            assertTrue("home is the Compose page", composeViews(screen.root) == 1)
            screen.open(CompanionScreen.Page.ADVANCED)
            assertTrue("Advanced hides home", composeViews(screen.root) == 0)
            assertTrue(texts(screen.root).toString(), texts(screen.root).any { it.contains("Advanced") })
            screen.open(CompanionScreen.Page.HOME)
            assertTrue("back home", composeViews(screen.root) == 1)
        }
    }
}
