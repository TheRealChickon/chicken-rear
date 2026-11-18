package dev.kb896.aiusage.update

import android.content.SharedPreferences
import java.io.IOException

internal class UpdateChecks(
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetch: (String?, () -> Long) -> GitHubUpdates.CheckResult = GitHubUpdates::latest,
) {
    fun release(installed: ReleaseVersion): GitHubRelease? =
        cachedRelease()?.takeIf { ReleaseVersion.parse(it.version)?.let { latest -> latest > installed } == true }

    private fun cachedRelease(): GitHubRelease? = prefs.getString("release", null)?.let {
        runCatching { GitHubRelease.parse(it) }.getOrNull()
    }

    fun automaticDue(): Boolean = automaticDue(now())

    private fun automaticDue(time: Long) =
        time >= prefs.getLong("blockedUntil", 0) &&
            prefs.getLong("lastAttempt", -GitHubUpdates.DAY_MS) <= time - GitHubUpdates.DAY_MS

    fun check(manual: Boolean): Boolean = synchronized(checkLock) {
        val time = now()
        val blockedUntil = prefs.getLong("blockedUntil", 0)
        if (time < blockedUntil) {
            if (manual) throw GitHubUpdates.RateLimited(blockedUntil)
            return false
        }
        if (!manual && !automaticDue(time)) return false
        if (!prefs.edit().putLong("lastAttempt", time).commit()) throw IOException("Could not record update check")
        val etag = if (cachedRelease() != null) prefs.getString("etag", null) else null
        try {
            val result = fetch(etag, now)
            result.exhaustedUntil?.let { prefs.edit().putLong("blockedUntil", it).commit() }
            when (val latest = result.latest) {
                is GitHubUpdates.Latest.Fresh -> {
                    GitHubRelease.parse(latest.body)
                    prefs.edit().putString("release", latest.body).putString("etag", latest.etag).apply()
                }
                GitHubUpdates.Latest.Cached -> if (etag == null) throw IOException("GitHub returned an uncached response")
                GitHubUpdates.Latest.Missing -> prefs.edit().remove("release").remove("etag").apply()
            }
        } catch (limit: GitHubUpdates.RateLimited) {
            prefs.edit().putLong("blockedUntil", limit.until).commit()
            throw limit
        }
        true
    }

    companion object {
        private val checkLock = Any()
    }
}
