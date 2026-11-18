package dev.kb896.aiusage.rear

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MusicPeaksPreviewTest {
    @Test fun `the preview song's frames are written, and its drop has peaks`() {
        val out = File("build/music-peaks").apply { mkdirs() }
        val cols = listOf("t", "base", "peak", "kickAmp", "breath", "wave", "wph", "limit", "soft", "g", "glow", "tilt", "shift") +
            (0 until MusicBody.BANDS).map { "b$it" } + listOf("c", "kd", "w", "a", "e", "height", "lowest")
        val lines = ArrayList<String>()
        lines += cols.joinToString(",")
        val tops = ArrayList<Pair<Long, Float>>()
        MusicRig.chain(MusicRig.previewSong, -25.0, 10_000L) { s ->
            val f = s.frame
            fun v(x: Float) = "%.5f".format(java.util.Locale.ROOT, x)
            val h = MusicBody.height(f)
            tops += s.t to h
            lines += (listOf(s.t.toString()) + listOf(f.base, f.peak, f.kickAmp, f.breath, f.wave, f.wph, f.limit, f.soft, f.g, f.glow, f.tilt, f.shift).map(::v) +
                f.bands.map(::v) + listOf(s.body.c, s.body.kd, s.body.w, s.body.a, s.body.e, h, MusicBody.lowest(f)).map(::v)).joinToString(",")
        }
        File(out, "frames.csv").writeText(lines.joinToString("\n") + "\n")
        val (a, b) = MusicRig.PREVIEW_SECTIONS["drop"]!!
        val drop = tops.filter { it.first >= (a + 0.4) * 1000 && it.first < b * 1000 }.map { it.second }
        println("preview: ${lines.size - 1} frames; the drop's top ${drop.min()}..${drop.max()} px")
        assertTrue(drop.max() >= 0.45f * MusicBody.PAGE_H)
    }
}
