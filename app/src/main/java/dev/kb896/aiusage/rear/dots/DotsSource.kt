package dev.kb896.aiusage.rear.dots

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object DotsSource {
    const val PACKAGE = "com.openai.chatgpt"
    const val ABI = "arm64-v8a"
    const val LIB = "lib/$ABI/liborbit_characters_jni.so"
    const val CHARACTERS = "assets/reference-characters.json"
    const val DESCRIPTOR = "assets/orbit-bundled/descriptor.json"
    /** The resource bundle's `resourceCompatibilityVersion` this app's calls were read against. */
    const val RESOURCES = 3
    /** No such library is anywhere near this big; a bigger entry is not read. */
    private const val MAX_LIB = 16L * 1024 * 1024

    /** A ChatGPT build whose renderer was read and is known: its version code, the library's size and SHA-256. */
    data class Known(val versionCode: Long, val size: Long, val sha256: String)

    val KNOWN = listOf(Known(2630718L, 3_265_112L, "15a939407e565a0540fdfae3d28dfcae5bfcd79c73e5d3fb798d3bcd4155f9c8"))

    /** The renderer's entry points DotsService calls (com.openai.orbit.characters.NativeBridge). */
    val CALLS = listOf(
        "version", "resourceCompatibilityVersion", "prepareResources", "releaseResources", "presetAppearance", "create",
        "restoreAppearanceState", "setCacheDirectory", "setDisplayScale", "setReducedMotion", "setActive", "render",
        "playSignature", "lastError", "destroy",
    )

    data class Character(val id: String, val name: String)

    /** The launch characters ("hereCharacter" in its list), in its order and with its names: the ones the settings offer. */
    val HEROES = listOf(
        Character("blue_beret", "Felipe"), Character("alfred", "Alfred"), Character("purple_heart", "Jojo"), Character("lime_frog", "Todd"),
        Character("coral_monocle", "Iris"), Character("gus", "Thelma"), Character("blue_spectacles", "Josh"), Character("lime_headphones", "Iggy"),
    )

    /** The launch character after [id] in [HEROES], round. */
    fun nextHero(id: String): Character = HEROES[(HEROES.indexOfFirst { it.id == id } + 1).mod(HEROES.size)]

    sealed class Found {
        /** It can be had: load [lib] (an APK path with `!/` and the entry), read assets from the package. */
        data class Ready(
            val lib: String, val versionCode: Long, val sha256: String, val known: Boolean,
            val characters: List<Character>, val libSize: Long = 0L,
        ) : Found()
        data class Missing(val why: String) : Found()
    }

    /**
     * Whether OpenAI's renderer can be had. [versionCode]: the ChatGPT package's, null when it is not installed;
     * [apks]: its APK files (base first, then the splits); [abis]: the phone's (Build.SUPPORTED_ABIS).
     */
    fun find(versionCode: Long?, apks: List<File>, abis: List<String>, checked: Checked? = null): Found {
        if (versionCode == null || apks.isEmpty()) return Found.Missing("ChatGPT is not installed")
        if (ABI !in abis) return Found.Missing("not an arm64 phone")
        var lib: Pair<File, ByteArray?>? = null
        var libSize = 0L
        var stored = true
        var descriptor: String? = null
        var characters: String? = null
        for (apk in apks) {
            if (!apk.canRead()) continue
            runCatching {
                ZipFile(apk).use { zip ->
                    zip.getEntry(LIB)?.let { e ->
                        if (e.method != ZipEntry.STORED) stored = false
                        else if (lib == null && e.size in 1..MAX_LIB) {
                            libSize = e.size
                            // Checked before for exactly this install (same size): not read and hashed again.
                            lib = apk to (if (checked != null && checked.size == e.size) null else zip.getInputStream(e).use { it.readBytes() })
                        }
                    }
                    if (descriptor == null) zip.getEntry(DESCRIPTOR)?.let { e -> descriptor = zip.getInputStream(e).use { String(it.readBytes()) } }
                    if (characters == null) zip.getEntry(CHARACTERS)?.let { e -> characters = zip.getInputStream(e).use { String(it.readBytes()) } }
                }
            }
        }
        val (apk, bytes) = lib ?: return Found.Missing(if (!stored) "its renderer is compressed in the APK" else "its renderer is not in it")
        val compatible = descriptor?.let { runCatching { JSONObject(it).optInt("resourceCompatibilityVersion", -1) }.getOrDefault(-1) }
        if (compatible != RESOURCES) return Found.Missing(if (descriptor == null) "its characters' resources are not in it" else "its characters' resources are of another version ($compatible)")
        val list = characters?.let { parse(it) }.orEmpty()
        if (list.isEmpty()) return Found.Missing("its character list is not in it")
        val sha = if (bytes == null) checked!!.sha256 else sha256(bytes)
        val known = KNOWN.any { it.versionCode == versionCode && it.size == libSize && it.sha256 == sha }
        if (!known && bytes != null) {
            val missing = CALLS.filter { !contains(bytes, "Java_com_openai_orbit_characters_NativeBridge_$it\u0000".toByteArray()) }
            if (missing.isNotEmpty()) return Found.Missing("its renderer has changed (no ${missing.joinToString()})")
        }
        return Found.Ready("${apk.absolutePath}!/$LIB", versionCode, sha, known, list, libSize)
    }

    /**
     * A library already read and found good for one install of ChatGPT ([key]: its version code, update time and
     * APK paths): its size and SHA-256. Kept in a small file both of our processes read ([onPhone]), so the 3 MB
     * library is read and hashed once per ChatGPT update, not at every start of each process.
     */
    data class Checked(val key: String, val size: Long, val sha256: String) {
        fun text() = "$key\n$size\n$sha256\n"
        companion object {
            fun parse(text: String): Checked? = runCatching {
                val l = text.split('\n')
                Checked(l[0], l[1].toLong(), l[2].also { require(it.length == 64) })
            }.getOrNull()
        }
    }

    /** What [checked] says for [key]: itself if it was written for exactly this install, else nothing. */
    fun checkedFor(checked: Checked?, key: String): Checked? = checked?.takeIf { it.key == key }

    /**
     * [found] as it may be loaded, by who signed the package. [signers]: the SHA-256 of each certificate it is signed
     * with (its past ones too, where the key was rotated); [onRecord]: the ones kept on record ([record]), empty before
     * the first. With a record, the package must be signed by one of its certificates, whatever library it holds (an
     * app in ChatGPT's name may carry OpenAI's own library, and its own assets for it). Without one, only a [KNOWN]
     * library is loaded: a library merely naming the entry points could be anyone's.
     */
    fun vouched(found: Found, signers: Set<String>, onRecord: Set<String>): Found = when {
        found !is Found.Ready -> found
        signers.isEmpty() -> Found.Missing("ChatGPT's signer cannot be read")
        onRecord.isNotEmpty() ->
            if (signers.any { it in onRecord }) found else Found.Missing("ChatGPT is signed by another key than the one on record (an app in its name?)")
        found.known -> found
        else -> Found.Missing("its renderer is not one this app has read, and ChatGPT's signer is not on record yet")
    }

    /** The signers to keep on record after [found]: the package's, the first time a [KNOWN] library is found in it; else null (nothing to write). */
    fun record(found: Found, signers: Set<String>, onRecord: Set<String>): Set<String>? =
        if (found is Found.Ready && found.known && onRecord.isEmpty() && signers.isNotEmpty()) signers else null

    /** The record's text: one SHA-256 to a line. */
    fun recordText(signers: Set<String>): String = signers.sorted().joinToString("") { "$it\n" }

    /** The certificates in a record's [text] (anything that is not a SHA-256 is not one). */
    fun recorded(text: String?): Set<String> =
        text.orEmpty().lines().map { it.trim().lowercase() }.filter { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }.toSet()

    /** The SHA-256 of each certificate [info]'s package is signed with: all of them for a package signed by several, else its own and the ones it rotated from. */
    fun signersOf(info: android.content.pm.PackageInfo): Set<String> {
        val s = info.signingInfo ?: return emptySet()
        val certs = if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        return certs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    /** The characters in the app's list (its `characters`: id and title). */
    fun parse(json: String): List<Character> = runCatching {
        val a = JSONObject(json).getJSONArray("characters")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id"); if (id.isEmpty()) null else Character(id, o.optString("title", id))
        }
    }.getOrDefault(emptyList())

    /** [wanted] if the list has it, else the first launch character it has, else its first. */
    fun pick(characters: List<Character>, wanted: String): Character? =
        characters.firstOrNull { it.id == wanted } ?: HEROES.firstNotNullOfOrNull { h -> characters.firstOrNull { it.id == h.id } } ?: characters.firstOrNull()

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > hay.size) return false
        val first = needle[0]
        var i = 0
        val last = hay.size - needle.size
        while (i <= last) {
            if (hay[i] == first) {
                var j = 1
                while (j < needle.size && hay[i + j] == needle[j]) j++
                if (j == needle.size) return true
            }
            i++
        }
        return false
    }

    private var kept: Pair<String, Found>? = null

    /**
     * [find] for the package as this phone has it (PackageManager), [vouched] for by its signer; read again only when
     * ChatGPT was updated.
     */
    @Synchronized
    fun onPhone(context: android.content.Context): Found {
        val pm = context.packageManager
        // One read for the APKs and their signer, so that both are of the same install.
        val info = runCatching { pm.getPackageInfo(PACKAGE, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
            ?: return Found.Missing("ChatGPT is not installed")
        val ai = info.applicationInfo ?: return Found.Missing("ChatGPT is not installed")
        val apks = (listOfNotNull(ai.sourceDir) + ai.splitSourceDirs.orEmpty()).map { File(it) }
        val key = "${info.longVersionCode}/${info.lastUpdateTime}/${apks.joinToString()}"
        kept?.let { (k, f) -> if (k == key) return f }
        val file = File(context.filesDir, CHECKED)
        val before = checkedFor(runCatching { Checked.parse(file.readText()) }.getOrNull(), key)
        val found = find(info.longVersionCode, apks, android.os.Build.SUPPORTED_ABIS.toList(), before)
        if (found is Found.Ready && before == null) runCatching { file.writeText(Checked(key, found.libSize, found.sha256).text()) }
        val signers = signersOf(info)
        val onRecord = File(context.filesDir, SIGNERS)
        val recorded = recorded(runCatching { onRecord.readText() }.getOrNull())
        record(found, signers, recorded)?.let { s -> runCatching { onRecord.writeText(recordText(s)) } }
        return vouched(found, signers, recorded).also { kept = key to it }
    }

    private const val CHECKED = "dots-checked"
    /** ChatGPT's signing certificates on record ([record]), in the app's own files: only this app writes there. */
    const val SIGNERS = "dots-signer"
}
