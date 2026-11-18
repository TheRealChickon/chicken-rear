package dev.kb896.aiusage.update

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal object UpdateApk {
    private const val HEX_SHA256 = "[a-fA-F0-9]{64}"
    private val hexSha256 = Regex(HEX_SHA256)
    private val checksumLine = Regex("""($HEX_SHA256) [ *](.+)""")
    private val signingFlags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
    private const val MIME = "application/vnd.android.package-archive"

    class VerificationFailed : IOException("The update could not be verified")

    fun download(context: Context, release: GitHubRelease): File {
        val expected = checksum(GitHubUpdates.checksums(release.checksums), release.apkName)
            ?: throw VerificationFailed()
        val dir = File(context.cacheDir, "updates")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the update cache")
        val cutoff = System.currentTimeMillis() - GitHubUpdates.DAY_MS
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        val file = File.createTempFile("chicken-rear-", ".apk", dir)
        var verified = false
        try {
            GitHubUpdates.asset(release.apk) { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                file.outputStream().use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > release.apkSize) throw VerificationFailed()
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
                if (total != release.apkSize || !matchesHash(digest.digest(), expected)) throw VerificationFailed()
            }
            val pm = context.packageManager
            val installed = pm.getPackageInfo(context.packageName, signingFlags)
            val archive = pm.getPackageArchiveInfo(file.absolutePath, signingFlags)
            if (!matchesPackage(installed, archive, release.version)) throw VerificationFailed()
            verified = true
            return file
        } finally {
            if (!verified) file.delete()
        }
    }

    fun checksum(body: String, name: String): String? =
        body.lineSequence().mapNotNull { checksumLine.matchEntire(it.trimEnd()) }
            .filter { it.groupValues[2] == name }.map { it.groupValues[1] }.singleOrNull()

    fun matchesHash(actual: ByteArray, expected: String): Boolean =
        hexSha256.matches(expected) && MessageDigest.isEqual(actual,
            ByteArray(32) { index -> expected.substring(index * 2, index * 2 + 2).toInt(16).toByte() })

    fun matchesPackage(installed: PackageInfo, archive: PackageInfo?, version: String): Boolean {
        if (archive == null || installed.packageName != archive.packageName || archive.versionName != version ||
            archive.longVersionCode <= installed.longVersionCode) return false
        val current = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val incoming = archive.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        return current.isNotEmpty() && current == incoming
    }

    fun installer(context: Context, apk: File): Intent {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.updates", apk), MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val flags = PackageManager.ResolveInfoFlags.of(
            (PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY).toLong())
        val installer = context.packageManager.queryIntentActivities(intent, flags).firstOrNull()?.activityInfo
            ?: throw IOException("No system package installer")
        return intent.setComponent(ComponentName(installer.packageName, installer.name))
    }
}

// Separate provider from pinned notes so the installer can be granted only the verified update URI.
class UpdateFileProvider : FileProvider()
