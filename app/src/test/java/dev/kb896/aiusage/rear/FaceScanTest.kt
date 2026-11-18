package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The faces kept in step from the files (FaceScan), on folders made here in the SHAPE of Xiaomi's (hand-written JSON;
 * no Xiaomi file is copied into the repo).
 */
class FaceScanTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private fun paths() = FaceScan.Paths(
        preinstalled = "$root/pre", downloads = "$root/dl", catalog = "$root/catalog.json",
        widgets = "$root/config/widget.json", runtime = "$root/rearScreen/runtime.json",
    )

    private fun mrc(f: File, manifest: String): File {
        f.parentFile.mkdirs()
        ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("manifest.xml")); z.write(manifest.toByteArray()); z.closeEntry() }
        return f
    }
    private fun sha1(f: File) = FaceScan.Reader().sha1(f)
    private fun mrm(f: File, id: String, mrc: File?, title: String, group: String = "watch", hash: String? = null, size: Long? = null) {
        f.parentFile.mkdirs()
        f.writeText("""{"localId":"$id","hash":"${hash ?: mrc?.let { sha1(it) } ?: ""}","size":${size ?: mrc?.length() ?: 0},
            "titles":{"zh_CN":"中","en_US":"$title"},"subResourceType":"$group","isRearScreenEditable":false,
            "parentResources":[{"localId":"parent-$id","resourceCode":"theme"}]}""")
    }
    private fun pre(id: String, title: String, manifest: String = "<Widget frameRate=\"0\"><Text text=\"$id\"/></Widget>", group: String = "watch") {
        val c = mrc(File("$root/pre/content/rearscreen/$id.mrc"), manifest)
        mrm(File("$root/pre/meta/rearscreen/$id.mrm"), id, c, title, group)
    }
    private fun download(id: String, title: String, manifest: String = "<Widget><Text text=\"$id\"/></Widget>", meta: Boolean = true) {
        val c = mrc(File("$root/dl/content/rearscreen/$id.mrc"), manifest)
        if (meta) mrm(File("$root/dl/meta/rearscreen/$id.mrm"), id, c, title)
    }

    private class Counting : FaceScan.Reader() {
        var reads = 0
        override fun text(f: File): String { reads++; return super.text(f) }
        override fun manifest(f: File): String? { reads++; return super.manifest(f) }
        override fun sha1(f: File): String { reads++; return super.sha1(f) }
    }

    private fun scan(s: FaceScan) = s.scan(emptySet(), faithful = emptyMap<String, Double>().withDefault { 0.9 }).let { list ->
        // Every face drawn once (the host's half): give each a fair score so only the file rules decide.
        s.scan(emptySet(), faithful = list.mapNotNull { it.sha1 }.associateWith { 0.9 })
    }

    @Test fun `the phone's faces in Xiaomi's order, a download and his list among them`() {
        root = tmp.newFolder()
        pre("stretch", "Stretch"); pre("carp", "Butterfly carp", "<Widget><PagView name=\"carp\"/></Widget>"); pre("case", "Liquid silicone", group = "nfc")
        File("$root/catalog.json").writeText("""{"products":[{"type":"watch","items":[{"resId":"case","resType":"nfc","isNFC":true},{"resId":"stretch","resType":"bigNumber"},{"resId":"carp","resType":"nature"}]}]}""")
        download("jelly", "Jellyfish", "<Widget><PagView/></Widget>")
        File("$root/config").mkdirs()
        File("$root/config/widget.json").writeText("""[{"id":1,"type":0,"extra":{"snapshotPath":"$root/snap/jelly","metaPath":"$root/rearScreen/jelly_1/jelly.mrm"},"path":"/data/system/theme/rearScreen/rearscreen_jelly_1.mrc","configPath":"$root/rearScreen/jelly_1/editConfig"},
            {"id":2,"type":0,"extra":{"snapshotPath":"$root/pre/preview/theme/parent-stretch/preview_rearscreen_0.png","metaPath":"$root/pre/meta/rearscreen/stretch.mrm"},"path":"$root/pre/content/rearscreen/stretch.mrc"}]""")
        val list = scan(FaceScan(paths()))
        assertEquals(listOf("moon", "dust", "xiaomi:jelly", "xiaomi:stretch", "xiaomi:case", "xiaomi:carp"), list.map { it.key })
        assertEquals(listOf("moon", "dust", "xiaomi:stretch"), FaceCatalog.offered(list).map { it.key })
        assertEquals("the renderer cannot draw pag", list.first { it.key == "xiaomi:jelly" }.leftOut)
        assertEquals("only with its case on", list.first { it.key == "xiaomi:case" }.leftOut)
        assertEquals("Stretch", list.first { it.key == "xiaomi:stretch" }.title)
        assertEquals("$root/pre/preview/theme/parent-stretch/preview_rearscreen_0.png", list.first { it.key == "xiaomi:stretch" }.card)
        // With PAG drawn, Jellyfish and Butterfly carp come in by themselves.
        val s = FaceScan(paths()); val first = s.scan(setOf(MamlCensus.Kind.PAG))
        val withPag = s.scan(setOf(MamlCensus.Kind.PAG), faithful = first.mapNotNull { it.sha1 }.associateWith { 0.9 })
        assertTrue(withPag.first { it.key == "xiaomi:jelly" }.offered && withPag.first { it.key == "xiaomi:carp" }.offered)
        assertEquals("$root/dl/preview/theme/jelly/preview_rearscreen_popsicle_0.jpg", withPag.first { it.key == "xiaomi:jelly" }.card)
    }

    @Test fun `a scan with nothing changed reads no file, and a new download is in the list by itself`() {
        root = tmp.newFolder()
        pre("stretch", "Stretch")
        File("$root/catalog.json").writeText("""{"products":[{"type":"bigNumber","items":[{"resId":"stretch","resType":"bigNumber"}]}]}""")
        val reader = Counting()
        val index = File(root, "index.json")
        val s = FaceScan(paths(), index, reader)
        s.scan(emptySet())
        assertTrue(reader.reads > 0)
        reader.reads = 0
        s.scan(emptySet())
        assertEquals("nothing changed: no file read", 0, reader.reads)
        // A new process with the index on disk reads no content again either (only the small JSON files once).
        val again = Counting(); FaceScan(paths(), index, again).scan(emptySet())
        assertEquals(2, again.reads)
        // A download lands: its .mrc first...
        val c = mrc(File("$root/dl/content/rearscreen/new.mrc"), "<Widget><Text/></Widget>")
        val landing = s.scan(emptySet())
        assertTrue(landing.first { it.key == "xiaomi:new" }.pending)
        // ...its .mrm last: ready.
        mrm(File("$root/dl/meta/rearscreen/new.mrm"), "new", c, "New one")
        val list = s.scan(emptySet()); val l2 = s.scan(emptySet(), faithful = list.mapNotNull { it.sha1 }.associateWith { 0.9 })
        assertTrue(l2.first { it.key == "xiaomi:new" }.offered)
        // Deleted: gone from the list.
        File("$root/dl/content/rearscreen/new.mrc").delete(); File("$root/dl/meta/rearscreen/new.mrm").delete()
        assertNull(s.scan(emptySet()).firstOrNull { it.key == "xiaomi:new" })
    }

    @Test fun `a file that is not what its mrm says is left out, and its sha1 is computed once`() {
        root = tmp.newFolder()
        val c = mrc(File("$root/pre/content/rearscreen/x.mrc"), "<Widget/>")
        mrm(File("$root/pre/meta/rearscreen/x.mrm"), "x", c, "X", hash = "0000")
        val reader = Counting()
        val s = FaceScan(paths(), null, reader)
        assertEquals("the file is not Xiaomi's as listed", s.scan(emptySet()).first { it.key == "xiaomi:x" }.leftOut)
        reader.reads = 0; s.scan(emptySet()); assertEquals(0, reader.reads)
        // Not hashed while the picker opens (sha1 = false): pending, nothing read.
        root = tmp.newFolder(); pre("y", "Y")
        val r2 = Counting(); val y = FaceScan(paths(), null, r2).scan(emptySet(), sha1 = false).first { it.key == "xiaomi:y" }
        assertTrue(y.pending); assertEquals("not checked yet", y.leftOut)
    }

    @Test fun `his list from runtime json when widget json is missing, highest position first`() {
        val w = FaceScan.runtimeOf("""[{"position":1,"metaPath":"/x/a_1/a.mrm","resLocalPath":"/p/a.mrc"},{"position":3,"metaPath":"/x/b_1/b.mrm","mamlEditConfigPath":"/x/b_1/editConfig"}]""")
        assertEquals(listOf("b", "a"), w.map { it.id })
        assertEquals("/x/b_1/editConfig", w.first().configPath)
    }

    @Test fun `when to scan - a burst of watch words waits 2 s, the poll only while lit`() {
        val s = FaceScan.Schedule()
        s.watched(1000); s.watched(1500)
        assertFalse(s.debounced(3000)); assertTrue(s.debounced(3500)); assertFalse("once", s.debounced(9000))
        assertNull("dark: nothing ticks", s.nextPoll(0, lit = false))
        assertEquals(0L, s.nextPoll(0, lit = true))
        assertFalse("the first look learns", s.polled(0, listOf(1L, 2L)))
        assertEquals(FaceScan.Schedule.POLL_MS, s.nextPoll(10, lit = true))
        assertNull(s.nextPoll(10, lit = false))
        assertFalse(s.polled(FaceScan.Schedule.POLL_MS, listOf(1L, 2L)))
        assertTrue("the download moved the folder's mtime", s.polled(2 * FaceScan.Schedule.POLL_MS, listOf(1L, 9L)))
    }
}
