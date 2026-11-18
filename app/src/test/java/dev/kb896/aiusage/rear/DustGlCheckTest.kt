package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale

/**
 * THE GL CHECK'S INPUTS (src/test/gl/dust-gl.c runs them on the PC's GPU): the dust face's own shaders, its motes, the
 * timeline's pictures as the phone draws them (DustTimeline at the phone's pace: 120 a second for a finger and a fast
 * move, else 60, a reacting picture's step at 60 in two; at rest, a finger held, a finger drawn across, the phone
 * tipped, moved and turned through DustMotion as the two sensors would give them), each picture as the very arrays DustGl uploads (DustField.packUpdate, packDraw), and the
 * Kotlin twin's pictures at six cues, so the GPU's pictures can be held against the twin's pixel by pixel; and the
 * moon's shaders, for its cost beside the dust's. Written to build/dust-gl. Nothing here needs a GPU.
 */
class DustGlCheckTest {
    @Test fun `the shaders, the motes, a timeline's pictures and the twin's pictures at its cues`() {
        val out = File("build/dust-gl").apply { mkdirs() }
        out.listFiles()?.filter { it.name.startsWith("twin-") || it.name.startsWith("gl-") }?.forEach { it.delete() }
        File(out, "update.vert").writeText(DustField.UPDATE_VS)
        File(out, "update.frag").writeText(DustField.UPDATE_FS)
        File(out, "draw.vert").writeText(DustField.DRAW_VS)
        File(out, "draw.frag").writeText(DustField.DRAW_FS)
        File(out, "haze.vert").writeText(DustField.HAZE_VS)
        File(out, "haze.frag").writeText(DustField.HAZE_FS)
        File(out, "moon.vert").writeText(SceneEngine.VERTEX)
        File(out, "moon.frag").writeText(SceneEngine.FRAGMENT)

        val tl = DustTimeline(DustTimeline.Pace.PHONE)
        val m = tl.motes; val s0 = DustField.initial(m)
        val bin = ByteBuffer.allocate(4 + m.count * 10 * 4).order(ByteOrder.LITTLE_ENDIAN)
        bin.putInt(m.count)
        for (i in 0 until m.count) { bin.putFloat(m.z[i]); bin.putFloat(m.b[i]); bin.putFloat(m.sig[i]); bin.putFloat(0f) }
        for (i in 0 until m.count) { bin.putFloat(s0.px[i]); bin.putFloat(s0.py[i]); bin.putFloat(s0.dx[i]); bin.putFloat(s0.dy[i]); bin.putFloat(s0.vx[i]); bin.putFloat(s0.vy[i]) }
        File(out, "motes.bin").writeBytes(bin.array())

        // The timeline, 12 s at the phone's pace: each picture as DustGl.frame takes it.
        val inU = FloatBuffer.allocate(4 * DustField.IN_U); val inD = FloatBuffer.allocate(4 * DustField.IN_D)
        val lines = StringBuilder()
        var pictures = 0; var reacting = 0; var twoSteps = 0
        tl.run { t, inp, dt, steps ->
            pictures++
            if (tl.driver.active) reacting++
            if (steps > 1) twoSteps++
            val cue = DustTimeline.CUES.firstOrNull { abs(it - t) < 4 } ?: -1
            DustField.packUpdate(inp, dt / steps, steps, inU); DustField.packDraw(inp, inD)
            lines.append(String.format(Locale.ROOT, "F %.9g %d %.9g %d\n", dt, steps, inp.gain, cue))
            lines.append("U"); for (k in 0 until inU.capacity()) lines.append(' ').append(String.format(Locale.ROOT, "%.9g", inU.get(k))); lines.append('\n')
            lines.append("D"); for (k in 0 until inD.capacity()) lines.append(' ').append(String.format(Locale.ROOT, "%.9g", inD.get(k))); lines.append('\n')
            if (cue >= 0) {
                val px = DustField.render(m, tl.state, inp)
                val rgba = ByteArray(px.size * 4)
                for (p in px.indices) { rgba[4 * p] = (px[p] shr 16).toByte(); rgba[4 * p + 1] = (px[p] shr 8).toByte(); rgba[4 * p + 2] = px[p].toByte(); rgba[4 * p + 3] = -1 }
                File(out, String.format(Locale.ROOT, "twin-%05d.rgba", cue)).writeBytes(rgba)
            }
        }
        File(out, "frames.txt").writeText(lines.toString())
        println("dust GL check: $pictures pictures, $reacting of them reacting, $twoSteps stepped in two, ${DustTimeline.CUES.size} cues, in ${out.absolutePath}")
        assertEquals(DustTimeline.CUES.size, out.listFiles()!!.count { it.name.startsWith("twin-") })
        // 12 s: 1138 pictures as it drew them before; the slow part of the tip now at 60, in two steps.
        assertEquals(1126, pictures)
        assertTrue(reacting in 700..900)
        assertTrue(twoSteps in 5..100)
    }

    private fun abs(v: Long) = if (v < 0) -v else v
}
