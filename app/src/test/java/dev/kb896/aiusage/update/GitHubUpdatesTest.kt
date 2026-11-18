package dev.kb896.aiusage.update

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.time.Instant

class GitHubUpdatesTest {
    @Test fun comparesTheFullReleaseNumberNumerically() {
        fun parse(version: String) = requireNotNull(ReleaseVersion.parse(version))
        assertEquals(parse("v1.2.1-cr3"), parse("1.2.1-cr3"))
        assertTrue(parse("1.2.1-cr10") > parse("1.2.1-cr3"))
        assertTrue(parse("1.2.10-cr1") > parse("1.2.9-cr99"))
        assertTrue(parse("1.10.0-cr1") > parse("1.9.99-cr99"))
        assertTrue(parse("2.0.0-cr1") > parse("1.99.99-cr999"))
        for (invalid in listOf("1.2.1", "1.2.1-cr3-dev", "1.2.1-cr0", "1.2.1-cr03", "1.2.1-cr99999999999999999999")) {
            assertNull(invalid, ReleaseVersion.parse(invalid))
        }
    }

    @Test fun redirectsRequireExactApprovedHttpsHosts() {
        for (allowed in listOf("https://github.com/download", "https://objects.githubusercontent.com:443/path", "https://release-assets.githubusercontent.com/github-production-release-asset/1/a")) {
            assertTrue(allowed, GitHubUpdates.allowedAsset(URI(allowed)))
        }
        for (blocked in listOf(
            "https://release-assets.githubusercontent.com.attacker.test/path", "https://api.github.com/path",
            "https://github.com.attacker.test/path", "https://github.com@attacker.test/path",
            "https://user${'@'}github.com/path", "http://github.com/path", "https://github.com:444/path",
        )) {
            assertFalse(blocked, GitHubUpdates.allowedAsset(URI(blocked)))
        }
    }

    @Test fun honoursBothRateLimitsAndFallsBackToAMinute() {
        val now = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli()
        fun retry(status: Int, after: String? = null, remaining: String? = null, reset: String? = null) =
            GitHubUpdates.retryAt(status, after, remaining, reset, now)
        val tenMinutesLater = now + 600_000
        val reset = (tenMinutesLater / 1000).toString()
        assertEquals(tenMinutesLater, retry(403, "60", "0", reset))
        assertEquals(now + 1_200_000, retry(429, "1200", "0", reset))
        assertEquals(tenMinutesLater, retry(429, "Sat, 01 Jan 2000 00:10:00 GMT"))
        assertEquals(now + 60_000, retry(403, "bad", "1", reset))
        assertEquals(now + 60_000, retry(429))
        assertNull(retry(500, "60", "0", reset))
    }
}
