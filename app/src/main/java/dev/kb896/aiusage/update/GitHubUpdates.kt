package dev.kb896.aiusage.update

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection

internal data class ReleaseVersion(val major: Long, val minor: Long, val patch: Long, val revision: Long) :
    Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion) =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.revision })

    companion object {
        private val pattern = Regex("""v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-cr([1-9][0-9]*)""")

        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val parts = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
            return ReleaseVersion(parts[0], parts[1], parts[2], parts[3])
        }
    }
}

internal data class GitHubRelease(val version: String, val apkSize: Long) {
    val apkName get() = "chicken-rear-$version.apk"
    val apk get() = asset(apkName)
    val checksums get() = asset("SHA256SUMS")

    private fun asset(name: String) =
        URI("https://github.com/${GitHubUpdates.REPO}/releases/download/v$version/$name")

    companion object {
        fun parse(body: String): GitHubRelease {
            val json = JSONObject(body)
            val tag = json.getString("tag_name")
            if (!tag.startsWith("v") || ReleaseVersion.parse(tag) == null ||
                json.optBoolean("draft") || json.optBoolean("prerelease")) {
                throw IOException("Invalid release")
            }
            val assets = json.getJSONArray("assets")
            fun size(name: String, url: URI): Long {
                val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .filter { it.optString("name") == name }
                val asset = matches.singleOrNull() ?: throw IOException("Release asset missing or ambiguous")
                if (asset.optString("browser_download_url") != url.toString()) {
                    throw IOException("Unexpected release asset URL")
                }
                return asset.getLong("size")
            }
            val release = GitHubRelease(tag.drop(1), 0)
            if (size("SHA256SUMS", release.checksums) !in 1..GitHubUpdates.MAX_CHECKSUM_BYTES) {
                throw IOException("Invalid SHA256SUMS size")
            }
            val apkSize = size(release.apkName, release.apk)
            if (apkSize !in 1..GitHubUpdates.MAX_APK_BYTES) throw IOException("Invalid APK size")
            return release.copy(apkSize = apkSize)
        }
    }
}

internal object GitHubUpdates {
    const val REPO = "TheRealChickon/chicken-rear"
    const val DAY_MS = 86_400_000L
    const val MAX_CHECKSUM_BYTES = 64L * 1024
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    private val latest = URI("https://api.github.com/repos/$REPO/releases/latest")
    private val assetHosts = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    private val redirectCodes = setOf(301, 302, 303, 307, 308)

    sealed interface Latest {
        data class Fresh(val body: String, val etag: String?) : Latest
        data object Cached : Latest
        data object Missing : Latest
    }

    data class CheckResult(val latest: Latest, val exhaustedUntil: Long? = null)

    class RateLimited(val until: Long) : IOException("GitHub rate limit")
    class AssetHostBlocked : IOException("GitHub redirected the download to an unapproved host")

    fun latest(etag: String?, now: () -> Long): CheckResult = request(latest, false, etag) { response, code ->
        val remaining = response.getHeaderField("X-RateLimit-Remaining")
        val reset = response.getHeaderField("X-RateLimit-Reset")
        val time = now()
        val result = when (code) {
            200 -> Latest.Fresh(response.inputStream.readTextBounded(1024 * 1024), response.getHeaderField("ETag"))
            304 -> Latest.Cached
            404 -> Latest.Missing
            else -> {
                val retryAt = retryAt(code, response.getHeaderField("Retry-After"), remaining, reset, time)
                if (retryAt != null) throw RateLimited(retryAt)
                throw IOException("GitHub API HTTP $code")
            }
        }
        CheckResult(result, resetAt(remaining, reset)?.takeIf { it > time })
    }

    fun checksums(uri: URI): String = asset(uri) { it.readTextBounded(MAX_CHECKSUM_BYTES.toInt()) }

    fun <T> asset(uri: URI, read: (InputStream) -> T): T = request(uri, true) { response, code ->
        if (code != 200) throw IOException("GitHub download HTTP $code")
        response.inputStream.use(read)
    }

    internal fun allowedAsset(uri: URI): Boolean =
        uri.scheme == "https" && uri.host in assetHosts && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.rawFragment == null

    private fun <T> request(initial: URI, asset: Boolean, etag: String? = null,
                            read: (HttpsURLConnection, Int) -> T): T {
        var uri = initial
        repeat(5) {
            if (asset && !allowedAsset(uri)) throw AssetHostBlocked()
            val response = uri.toURL().openConnection() as HttpsURLConnection
            try {
                response.instanceFollowRedirects = false
                response.useCaches = false
                response.connectTimeout = 15_000
                response.readTimeout = 20_000
                response.setRequestProperty("User-Agent", "Chicken-Rear-Updater")
                if (!asset) {
                    response.setRequestProperty("Accept", "application/vnd.github+json")
                    if (etag != null) response.setRequestProperty("If-None-Match", etag)
                }
                val code = response.responseCode
                if (!asset || code !in redirectCodes) return read(response, code)
                val location = response.getHeaderField("Location") ?: throw IOException("Missing download redirect")
                uri = uri.resolve(location)
            } finally {
                response.disconnect()
            }
        }
        throw IOException("Too many download redirects")
    }

    private fun InputStream.readTextBounded(limit: Int): String = use {
        val bytes = it.readNBytes(limit + 1)
        if (bytes.size > limit) throw IOException("GitHub response too large")
        bytes.toString(Charsets.UTF_8)
    }

    fun retryAt(code: Int, after: String?, remaining: String?, reset: String?, now: Long): Long? {
        if (code != 403 && code != 429) return null
        val retry = after?.trim()?.let { header ->
            header.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
                runCatching { Instant.ofEpochMilli(now).plusSeconds(seconds).toEpochMilli() }.getOrNull()
            } ?: runCatching {
                ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull()
        }
        return maxOf(retry ?: 0, resetAt(remaining, reset) ?: 0).takeIf { it > now } ?: (now + 60_000)
    }

    private fun resetAt(remaining: String?, reset: String?): Long? =
        if (remaining?.trim() == "0") reset?.trim()?.toLongOrNull()?.let {
            runCatching { Instant.ofEpochSecond(it).toEpochMilli() }.getOrNull()
        } else null
}
