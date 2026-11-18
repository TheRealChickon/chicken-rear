package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.FaceCatalog.Checked
import dev.kb896.aiusage.rear.FaceCatalog.Content
import dev.kb896.aiusage.rear.FaceCatalog.Found
import dev.kb896.aiusage.rear.FaceCatalog.Item
import dev.kb896.aiusage.rear.FaceCatalog.Meta
import dev.kb896.aiusage.rear.FaceCatalog.Source
import dev.kb896.aiusage.rear.MamlCensus.Census
import dev.kb896.aiusage.rear.MamlCensus.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The face list's order and its rules (SPEC 3.4, 3.5), on made-up records in the shape of Xiaomi's. */
class FaceCatalogTest {
    private fun meta(id: String, hash: String = "h-$id", size: Long = 100, group: String = "watch", mtime: Long = 0, titles: Map<String, String> = mapOf("en_US" to "T-$id")) =
        Meta(id, hash, size, titles, group, false, "parent-$id", mtime)
    private fun pre(id: String, m: Meta? = meta(id)) = Found(id, Source.PREINSTALLED, m, Content("/p/$id.mrc", m?.size ?: 100), card = "/p/preview/$id.png")
    private fun dl(id: String, mtime: Long = 0, m: Meta? = meta(id, mtime = mtime), size: Long = m?.size ?: 100) =
        Found(id, Source.DOWNLOADED, m, Content("/d/$id.mrc", size), card = "/d/preview/$id.jpg")
    private val canvas = Census()
    private fun checked(vararg f: Found, census: Census = canvas): Map<String, Checked> =
        f.associate { it.content!!.path to Checked(it.meta?.hash ?: "x", census) }
    private fun allFaithful(vararg f: Found) = f.mapNotNull { it.meta?.hash }.associateWith { 0.9 }

    private fun build(found: List<Found>, catalog: List<Item> = emptyList(), rear: List<String> = emptyList(), checked: Map<String, Checked> = emptyMap(),
                      abilities: Set<Kind> = setOf(Kind.LOTTIE), failed: Map<String, String> = emptyMap(), faithful: Map<String, Double>? = null) =
        FaceCatalog.build(FaceCatalog.Input(found, catalog, rear, checked, abilities, failed,
            faithful ?: found.mapNotNull { it.meta?.hash }.associateWith { 0.9 }))

    @Test fun `the order - ours, his rear list, Xiaomi's catalog, other preinstalled, downloads newest first`() {
        val f = listOf(pre("a"), pre("b"), pre("c"), pre("z"), dl("old", mtime = 1), dl("new", mtime = 5), dl("his", mtime = 2))
        val list = build(f, catalog = listOf(Item("c", "watch", false), Item("a", "watch", false)), rear = listOf("his", "b"), checked = checked(*f.toTypedArray()))
        assertEquals(listOf("moon", "dust", "xiaomi:his", "xiaomi:b", "xiaomi:c", "xiaomi:a", "xiaomi:z", "xiaomi:new", "xiaomi:old"), list.map { it.key })
        assertTrue(list.all { it.offered })
        assertEquals("Moon", list.first().title)
        assertEquals(Source.OURS, list.first().source)
    }

    @Test fun `the same content twice - the first in the order stays`() {
        val a = pre("butterfly", meta("butterfly", hash = "same")); val copy = pre("copy", meta("copy", hash = "same"))
        val list = build(listOf(copy, a), catalog = listOf(Item("butterfly", "nature", false)), checked = checked(a, copy))
        assertTrue(list.first { it.key == "xiaomi:butterfly" }.offered)
        assertEquals("the same content as a face before it", list.first { it.key == "xiaomi:copy" }.leftOut)
    }

    @Test fun `a download still landing is not ready - no mrm yet, or the size short of the mrm's`() {
        val noMeta = dl("x", m = null)
        val short = dl("y", m = meta("y", size = 1000), size = 400)
        val list = build(listOf(noMeta, short), checked = checked(short))
        val x = list.first { it.key == "xiaomi:x" }; val y = list.first { it.key == "xiaomi:y" }
        assertTrue(x.pending && !x.offered)
        assertTrue(y.pending && y.leftOut!!.startsWith("still landing"))
        // Its .mrm written last, the sizes equal: offered.
        val ready = dl("y", m = meta("y", size = 1000), size = 1000)
        assertTrue(build(listOf(ready), checked = checked(ready)).first { it.key == "xiaomi:y" }.offered)
    }

    @Test fun `each rule leaves a face out with its own reason`() {
        fun one(f: Found, census: Census = canvas, sha: String? = f.meta?.hash, abilities: Set<Kind> = setOf(Kind.LOTTIE), item: Item? = null,
                failed: Map<String, String> = emptyMap(), faithful: Map<String, Double>? = null) =
            build(listOf(f), catalog = listOfNotNull(item), checked = mapOf(f.content!!.path to Checked(sha, census)), abilities = abilities, failed = failed, faithful = faithful).first { it.key.startsWith("xiaomi:") }
        assertEquals("the file is not Xiaomi's as listed", one(pre("a"), sha = "other").leftOut)
        assertTrue(one(pre("a"), sha = null).let { it.pending && it.leftOut == "not checked yet" })
        assertEquals("only with its case on", one(pre("n", meta("n", group = "nfc"))).leftOut)
        assertEquals("only with its case on", one(pre("n"), item = Item("n", "watch", true)).leftOut)
        assertTrue(one(pre("s", meta("s", group = "signature"))).leftOut!!.startsWith("a template"))
        assertTrue(one(pre("v"), census = Census(kinds = setOf(Kind.USER_VIDEO))).leftOut!!.startsWith("a template"))
        assertEquals("data the back cannot read (needs com.mi.health.provider.main)", one(pre("st"), census = Census(providers = setOf("com.mi.health.provider.main"))).leftOut)
        assertEquals("data the back cannot read (needs a sensor)", one(pre("se"), census = Census(sensors = true)).leftOut)
        assertTrue(one(pre("ok"), census = Census(providers = setOf("settings"))).offered)
        assertEquals("the renderer cannot draw pag", one(pre("p"), census = Census(kinds = setOf(Kind.PAG))).leftOut)
        assertEquals("the renderer cannot draw theme video", one(pre("tv"), census = Census(kinds = setOf(Kind.THEME_VIDEO))).leftOut)
        // The same face is offered BY ITSELF once the renderer can draw PAG.
        assertTrue(one(pre("p"), census = Census(kinds = setOf(Kind.PAG)), abilities = setOf(Kind.PAG)).offered)
        assertEquals("failed: an exception in load", one(pre("f"), failed = mapOf("h-f" to "an exception in load")).leftOut)
        assertTrue(one(pre("g"), faithful = mapOf("h-g" to 0.3)).leftOut!!.startsWith("does not look like Xiaomi's picture of it (0.30)"))
        assertTrue(one(pre("g"), faithful = emptyMap()).let { it.pending && it.leftOut == "not drawn yet" })
    }

    @Test fun `a template with his own edit is offered, drawn with his edit`() {
        val s = pre("s", meta("s", group = "signature")).copy(editConfig = "/data/edit")
        val list = build(listOf(s), checked = checked(s))
        val face = list.first { it.key == "xiaomi:s" }
        assertTrue(face.offered); assertEquals("/data/edit", face.editConfig)
        // With his VIDEO it still needs a renderer that plays video.
        val v = pre("v").copy(editConfig = "/data/edit")
        assertEquals("the renderer cannot draw theme video", build(listOf(v), checked = checked(v, census = Census(kinds = setOf(Kind.USER_VIDEO)))).first { it.key == "xiaomi:v" }.leftOut)
    }

    @Test fun `an applied copy stands in when the download is gone, and a gone chosen face falls back to the moon`() {
        val gone = Found("j", Source.DOWNLOADED, meta("j"), Content("/d/j.mrc", -1), Content("/data/system/theme/rearScreen/rearscreen_j_1.mrc", 100), "/data/edit", "/data/snap")
        val list = build(listOf(gone), rear = listOf("j"), checked = mapOf("/data/system/theme/rearScreen/rearscreen_j_1.mrc" to Checked("h-j", canvas)))
        val j = list.first { it.key == "xiaomi:j" }
        assertTrue(j.offered); assertEquals("/data/system/theme/rearScreen/rearscreen_j_1.mrc", j.content); assertEquals("/data/snap", j.card)
        // Neither left: gone.
        val none = Found("k", Source.DOWNLOADED, meta("k"), Content("/d/k.mrc", -1))
        val list2 = build(listOf(none))
        assertTrue(!list2.first { it.key == "xiaomi:k" }.offered)
        assertEquals("moon", FaceCatalog.resolve(list2, "xiaomi:k").key)
        assertEquals("moon", FaceCatalog.resolve(list2, null).key)
        assertEquals("xiaomi:j", FaceCatalog.resolve(list, "xiaomi:j").key)
    }

    @Test fun `titles by locale - English, else fallback, else Chinese`() {
        assertEquals("Stretch", FaceCatalog.title(mapOf("zh_CN" to "延伸", "en_US" to "Stretch", "fallback" to "F"), "xiaomi:1"))
        assertEquals("F", FaceCatalog.title(mapOf("zh_CN" to "延伸", "fallback" to "F"), "xiaomi:1"))
        assertEquals("延伸", FaceCatalog.title(mapOf("zh_CN" to "延伸"), "xiaomi:1"))
        assertEquals("96af4ce9", FaceCatalog.title(emptyMap(), "xiaomi:96af4ce9-72b5"))
        assertNull(FaceCatalog.idOf("moon")); assertEquals("abc", FaceCatalog.idOf("xiaomi:abc"))
    }

    @Test fun `ours are two and first - the moon, then Dust in Light, always offered, with their own cards`() {
        val list = FaceCatalog.build(FaceCatalog.Input(emptyList(), emptyList(), emptyList(), emptyMap(), emptySet(), moonCard = "/m.png", dustCard = "/d.png"))
        assertEquals(listOf("moon", "dust"), list.map { it.key })
        val dust = list[1]
        assertEquals("Dust in Light", dust.title)
        assertEquals(Source.OURS, dust.source)
        assertTrue(dust.offered)
        assertEquals("/d.png", dust.card)
        assertNull(dust.content)
        // In the picker right after the moon (its order is the list's).
        assertEquals(1, FaceCatalog.offered(list).indexOfFirst { it.key == FaceCatalog.DUST })
        assertEquals("dust", FaceCatalog.resolve(list, "dust").key)
        assertTrue(FaceCatalog.ours("moon") && FaceCatalog.ours("dust") && !FaceCatalog.ours("xiaomi:abc") && !FaceCatalog.ours(null))
        assertNull(FaceCatalog.idOf("dust"))
        assertEquals("Dust in Light", FaceCatalog.title(emptyMap(), "dust"))
        // A scan never takes the choice from it (it is no file of his), and the moon stays the default.
        assertEquals(FaceCatalog.AfterScan.KEEP, FaceCatalog.afterScan("dust", list, list))
        assertEquals(FaceCatalog.AfterScan.KEEP, FaceCatalog.afterScan("dust", listOf(list[0]), list))
        assertEquals("moon", dev.kb896.aiusage.data.PanelConfig().face)
        assertEquals(listOf("moon", "dust"), dev.kb896.aiusage.data.FacesStore.OURS.map { it.key })
    }
}
