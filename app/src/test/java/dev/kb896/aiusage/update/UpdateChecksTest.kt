package dev.kb896.aiusage.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.update.GitHubUpdates.CheckResult
import dev.kb896.aiusage.update.GitHubUpdates.Latest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateChecksTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("test-updates", Context.MODE_PRIVATE)
    private val installed = requireNotNull(ReleaseVersion.parse("1.2.1-cr3"))
    private val newer = GitHubRelease("1.2.1-cr4", 64)
    private val body = """
        {"tag_name":"v1.2.1-cr4","draft":false,"prerelease":false,"assets":[
          {"name":"SHA256SUMS","size":91,"browser_download_url":"${newer.checksums}"},
          {"name":"${newer.apkName}","size":64,"browser_download_url":"${newer.apk}"}
        ]}
    """.trimIndent()

    @Test fun dailyCheckAndConditionalManualCheckSurviveRecreation() {
        var time = 1_000_000_000L
        val etags = mutableListOf<String?>()
        fun model() = UpdateChecks(prefs, { time }) { etag, _ ->
            etags += etag
            CheckResult(if (etag == null) Latest.Fresh(body, "\"test\"") else Latest.Cached)
        }
        assertTrue(model().check(manual = false))
        assertEquals(newer, model().release(installed))
        assertNull(model().release(requireNotNull(ReleaseVersion.parse(newer.version))))
        time += GitHubUpdates.DAY_MS - 1
        assertFalse(model().check(manual = false))
        assertEquals(listOf<String?>(null), etags)
        assertTrue(model().check(manual = true))
        assertEquals(newer, model().release(installed))
        time += GitHubUpdates.DAY_MS
        assertTrue(model().check(manual = false))
        assertEquals(listOf(null, "\"test\"", "\"test\""), etags)
    }

    @Test fun aRateLimitBlocksEvenManualChecksUntilReset() {
        var time = 1_000_000_000L
        var requests = 0
        val waitUntil = time + 90_000
        fun model() = UpdateChecks(prefs, { time }) { _, _ ->
            requests++
            if (requests == 1) throw GitHubUpdates.RateLimited(waitUntil)
            CheckResult(Latest.Fresh(body, "\"test\""), exhaustedUntil = waitUntil + 30_000)
        }
        assertEquals(waitUntil, assertThrows(GitHubUpdates.RateLimited::class.java) { model().check(true) }.until)
        assertFalse(model().check(false))
        assertThrows(GitHubUpdates.RateLimited::class.java) { model().check(true) }
        assertEquals(1, requests)
        time = waitUntil
        assertTrue(model().check(true))
        assertThrows(GitHubUpdates.RateLimited::class.java) { model().check(true) }
        assertEquals(2, requests)
        assertEquals(newer, model().release(installed))
    }

    @Test fun unrelatedAssetsAndPrereleasesAreRejected() {
        assertEquals(newer, GitHubRelease.parse(body))
        for (invalid in listOf(
            body.replace(newer.apk.toString(), "https://github.com/other/repo/download.apk"),
            body.replace("SHA256SUMS", "other"),
            body.replace("\"prerelease\":false", "\"prerelease\":true"),
        )) {
            assertThrows(java.io.IOException::class.java) { GitHubRelease.parse(invalid) }
        }
    }
}
