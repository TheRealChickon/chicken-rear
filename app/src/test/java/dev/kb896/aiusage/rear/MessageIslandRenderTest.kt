package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * THE MESSAGE ISLAND DRAWN by the app's own painters (IslandArt, on a software canvas: MessageIsland.pixel, the AGSL's
 * Kotlin twin) at the draft's moments, over black and over the dust face drawn by its own twin (DustField.render) with the
 * island's tint read as the scene reads it (NoticeVeil). Every message here is made up.
 *
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MessageIslandRenderTest {
    private val w = 976; private val h = 596
    private val green = 0xFF25D366.toInt()
    private val who = "Mia"
    private val said = "Running 10 min late, save me a seat"

    private fun black() = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }

    private fun art(look: MessageIsland.Look, icon: Bitmap? = null) = IslandArt().apply { set(who, said, green, icon, look) }

    private fun frame(a: IslandArt, t: Float) = MessageIsland.at(t, a.pillW, MessageIsland.Frame(), sweeps = a.look.sweep)

    /** The dust face's twin at its first picture, tinted as the scene would be at [t] ms into an island begun at 0. */
    private fun dust(veil: NoticeVeil?, t: Long, m: DustField.Motes = motes, s: DustField.State = state, inp: DustField.Inputs = DustField.Inputs()): Bitmap {
        val vf = NoticeVeil.Frame()
        if (veil != null && veil.read(t, vf)) vf.into(inp) else inp.tint = 0f
        val px = DustField.render(m, s, inp)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }
    private val motes = DustField.motes()
    private val state = DustField.initial(motes)

    private fun lum(c: Int) = 0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)

    private fun save(b: Bitmap, name: String, dir: File = File("build/message-island")) {
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `the production island shader compiles before a notification and accepts the frame uniforms`() {
        val installed = android.graphics.RuntimeShader(MessageIsland.AGSL)
        val values = MessageIsland.uniformArrays()
        for (i in values.indices) installed.setFloatUniform(MessageIsland.UNIFORM_NAMES[i], values[i])
    }

    @Test fun `a face outside the GL scene keeps its brightness under the colour wash`() {
        val a = art(MessageIsland.Look.TINT_OWN)
        val live = black().apply { eraseColor(Color.WHITE) }
        a.draw(Canvas(live), w.toFloat(), h.toFloat(), frame(a, 3_000f))
        val far = live.getPixel(250, 50)
        assertTrue(Color.green(far) > 225 && Color.green(far) > Color.red(far))
    }

    @Test fun `the pill - black glass with Xiaomi's light round it, its words inside, centred on what he sees`() {
        val a = art(MessageIsland.Look.TINT_OVER_FACE)
        val f = frame(a, 1_000f)
        val b = black()
        a.draw(Canvas(b), w.toFloat(), h.toFloat(), f)
        save(b, "pill-over-black")
        // Inside, under the words: black glass.
        val inside = b.getPixel(f.cx.toInt(), (f.cy + 50f).toInt())
        assertTrue("black glass: ${Integer.toHexString(inside)}", maxOf(Color.red(inside), Color.green(inside), Color.blue(inside)) < 40)
        // Round it: light in Xiaomi's colours, not grey: some red and some blue along the outline.
        var warm = 0; var cool = 0
        for (x in (f.cx - f.w / 2f).toInt() + 30 until (f.cx + f.w / 2f).toInt() - 30 step 4) {
            for (y in listOf((f.cy - f.h / 2f - 6f).toInt(), (f.cy + f.h / 2f + 6f).toInt())) {
                if (y !in 0 until h) continue
                val c = b.getPixel(x, y)
                if (Color.red(c) > Color.blue(c) + 12) warm++
                if (Color.blue(c) > Color.red(c) + 12) cool++
            }
        }
        assertTrue("warm light round it: $warm", warm > 10)
        assertTrue("cool light round it: $cool", cool > 10)
        // Its words: within the pill, never under the lenses, about its middle.
        var minX = w; var maxX = 0
        for (y in (f.cy - 40f).toInt()..(f.cy + 40f).toInt()) for (x in 0 until w) {
            val c = b.getPixel(x, y)
            if (Color.green(c) > 150 && Color.red(c) > 120) { minX = minOf(minX, x); maxX = maxOf(maxX, x) }
        }
        assertTrue("words drawn", maxX > minX)
        assertTrue("inside the pill: $minX..$maxX in ${f.cx - f.w / 2f}..${f.cx + f.w / 2f}", minX > f.cx - f.w / 2f && maxX < f.cx + f.w / 2f)
        assertTrue("clear of the lenses", minX > 296)
    }

    @Test fun `the open page's words are NoticePop's block, white, centred on x 636 and y 298`() {
        for (look in listOf(MessageIsland.Look.TINT_OVER_FACE, MessageIsland.Look.SWEEP)) {
            val a = art(look)
            val b = black()
            a.draw(Canvas(b), w.toFloat(), h.toFloat(), frame(a, MessageIsland.IN_MS + 2_800f))
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            for (y in 0 until h) for (x in 300 until w) {
                val c = b.getPixel(x, y)
                if (Color.red(c) > 200 && Color.green(c) > 200 && Color.blue(c) > 200) { minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
            }
            assertEquals("across ($look)", 636f, (minX + maxX) / 2f, 6f)
            assertEquals("down ($look)", 298f, (minY + maxY) / 2f, 14f)
        }
    }

    @Test fun `Island Tint - the live face keeps its light and takes the app's colour, darkened only behind the words`() {
        val veil = NoticeVeil()
        veil.begin(green, 0L, art(MessageIsland.Look.TINT_OVER_FACE).pillW)
        val t = MessageIsland.IN_MS + 2_000L
        val plain = dust(null, t)
        val tinted = dust(veil, t)
        val a = art(MessageIsland.Look.TINT_OVER_FACE)
        val shown = tinted.copy(Bitmap.Config.ARGB_8888, true)
        a.draw(Canvas(shown), w.toFloat(), h.toFloat(), frame(a, t.toFloat()))
        save(shown, "open-tint-over-dust")
        // Away from the words (the beam's corner, top right; the lower right): as much light as the face had, green.
        var plainL = 0f; var tintL = 0f; var g = 0L; var r = 0L
        for (y in 0 until h step 2) for (x in 330 until w step 2) {
            val far = (y < 150 || y > 460)
            if (!far) continue
            val p = plain.getPixel(x, y); val q = shown.getPixel(x, y)
            plainL += lum(p); tintL += lum(q)
            g += Color.green(q); r += Color.red(q)
        }
        println("message island: Tint — the face's light kept away from the words: ${"%.2f".format(java.util.Locale.ROOT, tintL / plainL)}")
        assertTrue("no heavy dimming: ${tintL / plainL}", tintL / plainL > 0.7f)
        assertTrue("the app's colour: green $g, red $r", g > r * 2)
    }

    @Test fun `Island Sweep - the live face at a fifth, one band of Xiaomi's light passing behind the words`() {
        val a = art(MessageIsland.Look.SWEEP)
        val plain = dust(null, 0L)
        val rest = plain.copy(Bitmap.Config.ARGB_8888, true)
        a.draw(Canvas(rest), w.toFloat(), h.toFloat(), frame(a, MessageIsland.SWEEP_TO + 300f))
        var p = 0f; var q = 0f
        for (y in 0 until 150 step 2) for (x in 330 until w step 2) { p += Color.green(plain.getPixel(x, y)); q += Color.green(rest.getPixel(x, y)) }
        assertEquals("a fifth of the face's values", 0.2f, q / p, 0.04f)
        val passing = plain.copy(Bitmap.Config.ARGB_8888, true)
        val f = frame(a, MessageIsland.SWEEP_FROM + 1_400f)
        a.draw(Canvas(passing), w.toFloat(), h.toFloat(), f)
        save(passing, "open-sweep-over-dust")
        val band = passing.getPixel(f.sweepX.toInt().coerceIn(300, w - 1), 120)
        assertTrue("the band's light: ${Integer.toHexString(band)}", Color.blue(band) > 25)
    }

    @Test fun `the morph - pill, page, pill - over the face, saved for a look`() {
        val a = art(MessageIsland.Look.TINT_OVER_FACE)
        val veil = NoticeVeil(); veil.begin(green, 0L, a.pillW)
        for (t in listOf(300L, 700L, 1_000L, 1_450L, 1_700L, 2_400L, 7_300L, 7_700L, 8_300L, 8_700L)) {
            val b = dust(veil, t)
            a.draw(Canvas(b), w.toFloat(), h.toFloat(), frame(a, t.toFloat()))
            save(b, "tint-%05d".format(t))
        }
    }

    @Test fun `the held tint follows moving motes across two frames`() {
        val veil = NoticeVeil().apply { begin(green, 0L, 520f) }
        val first = dust(veil, 3_000)
        val driver = DustDriver()
        repeat(30) { step ->
            DustField.step(motes, state, driver.next(1f / 60f, 3_000L + step * 16, 2), 1f / 120f, 2)
        }
        val later = dust(veil, 3_500)
        var changed = 0
        for (y in 30 until h step 3) for (x in 310 until w step 3) {
            if (kotlin.math.abs(Color.green(first.getPixel(x, y)) - Color.green(later.getPixel(x, y))) > 8) changed++
        }
        assertTrue("moving pixels during the hold: $changed", changed > 50)
    }
}
