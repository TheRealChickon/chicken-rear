package dev.kb896.aiusage.update

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateApkTest {
    @Test fun manifestMustNameTheExactApkAndTheDigestMustMatch() {
        val name = "chicken-rear-1.2.1-cr4.apk"
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val sha = MessageDigest.getInstance("SHA-256")
        assertEquals(expected, UpdateApk.checksum("the notes\n$expected  $name\r\n", name))
        assertEquals(expected, UpdateApk.checksum("$expected *$name", name))
        assertNull(UpdateApk.checksum("$expected  other.apk", name))
        assertNull(UpdateApk.checksum("$expected  $name\n$expected  $name", name))
        assertNull(UpdateApk.checksum("garbage  $name", name))
        assertTrue(UpdateApk.matchesHash(sha.digest("abc".toByteArray()), expected.uppercase()))
        assertFalse(UpdateApk.matchesHash(sha.digest("changed".toByteArray()), expected))
        assertFalse(UpdateApk.matchesHash(ByteArray(32), "0"))
    }

    @Test fun onlyTheExactCurrentSignerSetAndANewerMatchingPackageAreAccepted() {
        val version = "1.2.1-cr4"
        val pkg = "dev.chicken.rear"
        val one = Signature("1234")
        val two = Signature("5678")
        fun packageInfo(name: String, code: Long, vararg signers: Signature) = PackageInfo().apply {
            packageName = name
            longVersionCode = code
            versionName = if (code == 4L) version else "1.2.1-cr3"
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(*signers)) }
        }
        val installed = packageInfo(pkg, 3, one)
        assertTrue(UpdateApk.matchesPackage(installed, packageInfo(pkg, 4, one), version))
        assertFalse(UpdateApk.matchesPackage(installed, packageInfo(pkg, 4, two), version))
        assertFalse(UpdateApk.matchesPackage(installed, packageInfo(pkg, 4, one, two), version))
        assertFalse(UpdateApk.matchesPackage(installed, packageInfo(pkg, 4), version))
        assertFalse(UpdateApk.matchesPackage(installed, null, version))
        assertFalse(UpdateApk.matchesPackage(installed, packageInfo("$pkg.dev", 4, one), version))
        assertFalse(UpdateApk.matchesPackage(installed, packageInfo(pkg, 3, one), "1.2.1-cr3"))
        assertFalse(UpdateApk.matchesPackage(installed, packageInfo(pkg, 4, one), "1.2.1-cr5"))
        assertTrue(UpdateApk.matchesPackage(packageInfo(pkg, 3, one, two), packageInfo(pkg, 4, two, one), version))
    }
}
