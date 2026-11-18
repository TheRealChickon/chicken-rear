package dev.kb896.aiusage.rear.dots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Whether OpenAI's renderer can be had, read from the ChatGPT app's APKs without loading anything. The APKs here are
 * made up by the test (a "library" of a few bytes naming the entry points, a descriptor, a list of two characters):
 * nothing of OpenAI's is in this repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DotsSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val arm = listOf("arm64-v8a", "armeabi-v7a")
    private val characters = """{"schemaVersion":1,"characters":[{"id":"blue_beret","title":"Felipe","appearance":{}},{"id":"lime_frog","title":"Todd","appearance":{}}]}"""
    private fun lib(calls: List<String> = DotsSource.CALLS) = (listOf("\u007fELF") + calls.map { "Java_com_openai_orbit_characters_NativeBridge_$it\u0000" }).joinToString("").toByteArray()

    private fun apk(name: String, vararg entries: Triple<String, ByteArray, Boolean>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((path, bytes, stored) in entries) {
                val e = ZipEntry(path)
                if (stored) {
                    e.method = ZipEntry.STORED; e.size = bytes.size.toLong(); e.compressedSize = bytes.size.toLong()
                    e.crc = CRC32().apply { update(bytes) }.value
                }
                z.putNextEntry(e); z.write(bytes); z.closeEntry()
            }
        }
        return f
    }
    private fun assets(compat: Int = 3) = arrayOf(
        Triple(DotsSource.DESCRIPTOR, """{"kind":"orbit-resource-bundle","resourceCompatibilityVersion":$compat}""".toByteArray(), false),
        Triple(DotsSource.CHARACTERS, characters.toByteArray(), false),
    )

    @Test fun `none installed - nothing, and why`() {
        assertEquals(DotsSource.Found.Missing("ChatGPT is not installed"), DotsSource.find(null, emptyList(), arm))
        assertEquals(DotsSource.Found.Missing("ChatGPT is not installed"), DotsSource.find(5L, emptyList(), arm))
        val base = apk("base.apk", Triple(DotsSource.LIB, lib(), true), *assets())
        assertEquals(DotsSource.Found.Missing("not an arm64 phone"), DotsSource.find(5L, listOf(base), listOf("x86_64")))
        assertTrue(DotsSource.find(5L, listOf(File(tmp.root, "gone.apk")), arm) is DotsSource.Found.Missing)
    }

    @Test fun `the renderer in its arm64 split and the characters in its base - ready, from the split, by path`() {
        val base = apk("base.apk", *assets())
        val split = apk("split_config.arm64_v8a.apk", Triple(DotsSource.LIB, lib(), true))
        val found = DotsSource.find(2630719L, listOf(base, split), arm)
        assertTrue("$found", found is DotsSource.Found.Ready)
        found as DotsSource.Found.Ready
        assertEquals("${split.absolutePath}!/lib/arm64-v8a/liborbit_characters_jni.so", found.lib)
        assertEquals(listOf(DotsSource.Character("blue_beret", "Felipe"), DotsSource.Character("lime_frog", "Todd")), found.characters)
        assertFalse("a made-up library is not the one read on the phone", found.known)
        assertEquals(DotsSource.sha256(lib()), found.sha256)
    }

    @Test fun `a compressed library, an older bundle, no list, a changed renderer - each nothing, and why`() {
        val base = apk("base.apk", *assets())
        assertEquals(DotsSource.Found.Missing("its renderer is compressed in the APK"), DotsSource.find(5L, listOf(base, apk("z.apk", Triple(DotsSource.LIB, lib(), false))), arm))
        assertEquals(DotsSource.Found.Missing("its renderer is not in it"), DotsSource.find(5L, listOf(base), arm))
        assertEquals(
            DotsSource.Found.Missing("its characters' resources are of another version (2)"),
            DotsSource.find(5L, listOf(apk("b2.apk", *assets(compat = 2)), apk("s2.apk", Triple(DotsSource.LIB, lib(), true))), arm),
        )
        assertEquals(
            DotsSource.Found.Missing("its characters' resources are not in it"),
            DotsSource.find(5L, listOf(apk("s3.apk", Triple(DotsSource.LIB, lib(), true))), arm),
        )
        assertEquals(
            DotsSource.Found.Missing("its character list is not in it"),
            DotsSource.find(5L, listOf(apk("b4.apk", assets()[0]), apk("s4.apk", Triple(DotsSource.LIB, lib(), true))), arm),
        )
        val changed = DotsSource.find(5L, listOf(base, apk("s5.apk", Triple(DotsSource.LIB, lib(DotsSource.CALLS - "playSignature"), true))), arm)
        assertEquals(DotsSource.Found.Missing("its renderer has changed (no playSignature)"), changed)
    }

    @Test fun `a library checked before for this install is not read again, one of another size is`() {
        val base = apk("base.apk", *assets())
        // A "library" that would fail the entry-point check if it were read: so a Ready proves it was not.
        val bad = lib(DotsSource.CALLS - "render")
        val split = apk("s6.apk", Triple(DotsSource.LIB, bad, true))
        val sha = "ab".repeat(32)
        val same = DotsSource.find(5L, listOf(base, split), arm, DotsSource.Checked("k", bad.size.toLong(), sha))
        assertTrue("$same", same is DotsSource.Found.Ready)
        same as DotsSource.Found.Ready
        assertEquals(sha, same.sha256)
        assertEquals(bad.size.toLong(), same.libSize)
        val other = DotsSource.find(5L, listOf(base, split), arm, DotsSource.Checked("k", bad.size + 1L, sha))
        assertEquals(DotsSource.Found.Missing("its renderer has changed (no render)"), other)
    }

    @Test fun `the checked note is kept for exactly its install and read back whole`() {
        val c = DotsSource.Checked("2630718/1700000000000/base.apk, split.apk", 3_265_112L, "cd".repeat(32))
        assertEquals(c, DotsSource.Checked.parse(c.text()))
        assertEquals(c, DotsSource.checkedFor(c, c.key))
        assertEquals(null, DotsSource.checkedFor(c, "2630719/1700000000001/base.apk, split.apk"))
        assertEquals(null, DotsSource.checkedFor(null, c.key))
        assertEquals(null, DotsSource.Checked.parse("k\n12\nnot-a-sha\n"))
        assertEquals(null, DotsSource.Checked.parse(""))
    }

    @Test fun `a fresh read gives the library's size for the note`() {
        val base = apk("base.apk", *assets())
        val found = DotsSource.find(5L, listOf(base, apk("s7.apk", Triple(DotsSource.LIB, lib(), true))), arm) as DotsSource.Found.Ready
        assertEquals(lib().size.toLong(), found.libSize)
    }

    @Test fun `the build read on a phone is known by its checksum`() {
        val k = DotsSource.KNOWN.single()
        assertEquals(2630718L, k.versionCode)
        assertEquals(64, k.sha256.length)
    }

    private val a = "aa".repeat(32)
    private val b = "bb".repeat(32)
    private fun ready(known: Boolean) = DotsSource.Found.Ready("x.apk!/${DotsSource.LIB}", 5L, "cd".repeat(32), known, DotsSource.HEROES)

    @Test fun `with a signer on record, only a package it signed is loaded, whatever its library`() {
        assertEquals(ready(false), DotsSource.vouched(ready(false), setOf(a), setOf(a)))
        assertEquals(ready(true), DotsSource.vouched(ready(true), setOf(a), setOf(a)))
        assertEquals("a key rotated from the one on record", ready(false), DotsSource.vouched(ready(false), setOf(b, a), setOf(a)))
        val other = DotsSource.Found.Missing("ChatGPT is signed by another key than the one on record (an app in its name?)")
        assertEquals(other, DotsSource.vouched(ready(false), setOf(b), setOf(a)))
        assertEquals("OpenAI's own library in another's package", other, DotsSource.vouched(ready(true), setOf(b), setOf(a)))
    }

    @Test fun `before a record, only a library this app has read is loaded`() {
        assertEquals(ready(true), DotsSource.vouched(ready(true), setOf(b), emptySet()))
        assertEquals(
            DotsSource.Found.Missing("its renderer is not one this app has read, and ChatGPT's signer is not on record yet"),
            DotsSource.vouched(ready(false), setOf(a), emptySet()),
        )
        assertEquals(DotsSource.Found.Missing("ChatGPT's signer cannot be read"), DotsSource.vouched(ready(true), emptySet(), emptySet()))
        val missing = DotsSource.Found.Missing("ChatGPT is not installed")
        assertEquals("nothing found stays nothing", missing, DotsSource.vouched(missing, setOf(a), setOf(a)))
    }

    @Test fun `the signer goes on record once, from a known library alone`() {
        assertEquals(setOf(a), DotsSource.record(ready(true), setOf(a), emptySet()))
        assertEquals("a library only naming the entry points", null, DotsSource.record(ready(false), setOf(a), emptySet()))
        assertEquals("never written over", null, DotsSource.record(ready(true), setOf(b), setOf(a)))
        assertEquals(null, DotsSource.record(ready(true), emptySet(), emptySet()))
        assertEquals(null, DotsSource.record(DotsSource.Found.Missing("x"), setOf(a), emptySet()))
    }

    @Test fun `the record is read back whole, and nothing else is taken as a certificate`() {
        assertEquals(setOf(a, b), DotsSource.recorded(DotsSource.recordText(setOf(b, a))))
        assertEquals(setOf(a), DotsSource.recorded("${a.uppercase()}\r\n\nnot a certificate\n${"g".repeat(64)}\n${a.drop(1)}\n"))
        assertTrue(DotsSource.recorded(null).isEmpty())
        assertTrue(DotsSource.recorded("").isEmpty())
    }

    @Test fun `a package's signers are its certificates' SHA-256, past ones too, or all of several`() {
        val one = android.content.pm.Signature(byteArrayOf(1, 2, 3)); val two = android.content.pm.Signature(byteArrayOf(4, 5, 6))
        fun info(vararg now: android.content.pm.Signature, past: Array<android.content.pm.Signature>? = null) = android.content.pm.PackageInfo().apply {
            signingInfo = android.content.pm.SigningInfo().also { s ->
                org.robolectric.Shadows.shadowOf(s).setSignatures(arrayOf(*now))
                if (past != null) org.robolectric.Shadows.shadowOf(s).setPastSigningCertificates(past)
            }
        }
        assertEquals(setOf(DotsSource.sha256(byteArrayOf(1, 2, 3))), DotsSource.signersOf(info(one)))
        assertEquals("rotated", setOf(DotsSource.sha256(byteArrayOf(1, 2, 3)), DotsSource.sha256(byteArrayOf(4, 5, 6))), DotsSource.signersOf(info(two, past = arrayOf(one, two))))
        assertEquals("signed by two", 2, DotsSource.signersOf(info(one, two)).size)
        assertTrue("none", DotsSource.signersOf(android.content.pm.PackageInfo()).isEmpty())
    }

    /**
     * The phone's way in ([DotsSource.onPhone]) end to end, with the package installed by the test. OpenAI's library
     * is not in this repository, so "known" comes from the note of a library checked before ([DotsSource.Checked]) with
     * the known build's size and checksum, as the phone keeps it after its first read.
     */
    @Test fun `on the phone, the first known library puts its signer on record and a package in ChatGPT's name with another is refused`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        org.robolectric.shadows.ShadowBuild.setSupportedAbis(arrayOf("arm64-v8a"))
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        val record = File(context.filesDir, DotsSource.SIGNERS).also { it.delete() }
        val checked = File(context.filesDir, "dots-checked").also { it.delete() }
        val openAi = android.content.pm.Signature(byteArrayOf(7, 7, 7))
        val someone = android.content.pm.Signature(byteArrayOf(6, 6, 6))
        val known = DotsSource.KNOWN.single()
        var updated = 1_000L
        fun install(signer: android.content.pm.Signature, lib: ByteArray, version: Long): List<File> {
            updated++
            val apks = listOf(apk("base-$updated.apk", *assets()), apk("split-$updated.apk", Triple(DotsSource.LIB, lib, true)))
            pm.installPackage(android.content.pm.PackageInfo().apply {
                packageName = DotsSource.PACKAGE
                longVersionCode = version
                lastUpdateTime = updated
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    packageName = DotsSource.PACKAGE
                    sourceDir = apks[0].path
                    splitSourceDirs = arrayOf(apks[1].path)
                }
                signingInfo = android.content.pm.SigningInfo().also { org.robolectric.Shadows.shadowOf(it).setSignatures(arrayOf(signer)) }
            })
            return apks
        }
        fun installKnown(signer: android.content.pm.Signature) {
            val apks = install(signer, ByteArray(known.size.toInt()), known.versionCode)
            checked.writeText(DotsSource.Checked("${known.versionCode}/$updated/${apks.joinToString()}", known.size, known.sha256).text())
        }

        // An app in ChatGPT's name, before anything is on record: its library only names the entry points.
        install(someone, lib(), 2630800L)
        assertEquals(
            DotsSource.Found.Missing("its renderer is not one this app has read, and ChatGPT's signer is not on record yet"),
            DotsSource.onPhone(context),
        )
        assertFalse("nothing put on record", record.exists())

        // ChatGPT with the library read on the phone: loaded, and its signer goes on record.
        installKnown(openAi)
        val first = DotsSource.onPhone(context)
        assertTrue("$first", first is DotsSource.Found.Ready && first.known)
        assertEquals(setOf(DotsSource.sha256(openAi.toByteArray())), DotsSource.recorded(record.readText()))

        // ChatGPT updated, a library not read before: loaded, by its signer.
        install(openAi, lib(), 2630900L)
        val update = DotsSource.onPhone(context)
        assertTrue("$update", update is DotsSource.Found.Ready && !update.known)

        // ChatGPT gone, an app in its name: refused, with a library of its own or with OpenAI's.
        val refused = DotsSource.Found.Missing("ChatGPT is signed by another key than the one on record (an app in its name?)")
        install(someone, lib(), 2631000L)
        assertEquals(refused, DotsSource.onPhone(context))
        installKnown(someone)
        assertEquals(refused, DotsSource.onPhone(context))
        assertEquals("the record is not written over", setOf(DotsSource.sha256(openAi.toByteArray())), DotsSource.recorded(record.readText()))
        record.delete(); checked.delete()
    }

    @Test fun `the character wanted, else the first launch character it has, and the settings go round the eight`() {
        val list = DotsSource.parse(characters)
        assertEquals("lime_frog", DotsSource.pick(list, "lime_frog")?.id)
        assertEquals("blue_beret", DotsSource.pick(list, "no_such")?.id)
        assertEquals(null, DotsSource.pick(emptyList(), "blue_beret"))
        assertEquals(8, DotsSource.HEROES.size)
        assertEquals("alfred", DotsSource.nextHero("blue_beret").id)
        assertEquals("blue_beret", DotsSource.nextHero("lime_headphones").id)
        assertEquals("blue_beret", DotsSource.nextHero("not_a_hero").id)
        assertTrue(DotsSource.parse("not json").isEmpty())
    }
}
