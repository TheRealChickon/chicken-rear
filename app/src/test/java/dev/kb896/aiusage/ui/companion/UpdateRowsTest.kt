package dev.kb896.aiusage.ui.companion

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.update.GitHubRelease
import dev.kb896.aiusage.update.UpdateModel
import dev.kb896.aiusage.update.UpdateState
import dev.kb896.aiusage.update.UpdateState.Check
import dev.kb896.aiusage.update.UpdateState.Download
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateRowsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val release = GitHubRelease("1.2.1-cr4", 100)

    private fun View.labels(): List<TextView> = when (this) {
        is TextView -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).labels() }
        else -> emptyList()
    }

    private fun render(state: UpdateState, check: () -> Unit = {}, install: () -> Unit = {}) =
        LinearLayout(context).apply { UpdateRows.build(this, state, check, install) }.labels()

    @Test fun idleAndCheckFeedbackNeverOfferAnUpdateWithoutARelease() {
        var checks = 0
        val row = render(UpdateState(), { checks++ }).single()
        assertEquals("Check for updates", row.text.toString())
        assertTrue(row.performClick())
        assertEquals(1, checks)
        assertFalse(render(UpdateState(check = Check.CHECKING)).single().isEnabled)
        assertTrue(render(UpdateState(check = Check.CURRENT)).any { it.text == "Chicken Rear is up to date." })
        assertTrue(render(UpdateState(check = Check.LIMITED)).any { "limiting" in it.text })
        assertTrue(render(UpdateState(check = Check.FAILED)).any { "Could not check" in it.text })
    }

    @Test fun oneUpdateRowReflectsDownloadVerificationAndInstallerStates() {
        var actions = 0
        fun rows(download: Download) = render(UpdateState(release, download = download), install = { actions++ })
        assertTrue(rows(Download.IDLE).single { it.text == "Update to 1.2.1-cr4" }.performClick())
        assertTrue(rows(Download.DOWNLOADING).any { it.text == "Downloading 1.2.1-cr4…" })
        assertTrue(rows(Download.DOWNLOADING).none { it.isEnabled })
        assertTrue(rows(Download.READY).single { it.text == "Install 1.2.1-cr4" }.performClick())
        assertTrue(rows(Download.UNVERIFIED).any { "could not be verified" in it.text })
        assertTrue(rows(Download.BLOCKED_HOST).any { "outside the allowed list" in it.text })
        assertTrue(rows(Download.FAILED).any { "Could not download" in it.text })
        assertEquals(2, actions)
    }

    @Test fun debugAndOtherPackageIdsNeverUseTheUpdater() {
        assertFalse(UpdateModel.enabled(true, "dev.chicken.rear.dev"))
        assertFalse(UpdateModel.enabled(true, "dev.chicken.rear"))
        assertFalse(UpdateModel.enabled(false, "dev.chicken.rear.dev"))
        assertTrue(UpdateModel.enabled(false, "dev.chicken.rear"))
    }
}
