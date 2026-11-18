package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import dev.kb896.aiusage.data.Pin

/**
 * The carousel of the pinned notes drawn (NoteCarousel says where each card is): over black, each card IS its note's
 * page (PinFace, the very picture the notes page shows, at its place in his order), with the back screen's own corners
 * at its size, the neighbours a little darker than the one in front. Every frame while it moves (the shell's
 * arrange tick). Each note's page is drawn once into a picture and kept while the carousel is up (the held one at the
 * page's own size, so it grows back into the page without a blur; the others at half); [clear] lets them go.
 */
class NoteArrangeArt {
    /**
     * [pictured]: drawn with its picture (a picture's note whose picture was not in memory yet is drawn again when it is);
     * [picture]: that picture, [image] its name, held while the carousel is up.
     */
    private class Kept(val key: String, val bmp: Bitmap, val shader: BitmapShader, val pictured: Boolean = true, val image: String? = null, val picture: Bitmap? = null)
    private val kept = HashMap<Long, Kept>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val r = RectF()

    /** [carousel]'s cards for [pins] (his order) on [c], [w] x [h], with the display's corner [corner] (px at the page's size). */
    @Synchronized
    fun draw(c: Canvas, w: Float, h: Float, carousel: NoteCarousel, pins: List<Pin>, corner: Float) {
        c.drawColor(0xFF000000.toInt())
        val byId = pins.withIndex().associateBy { it.value.at }
        for (p in carousel.placed(w, h)) {
            val at = byId[p.id] ?: continue
            val k = picture(at.value, at.index, pins.size, w, h, full = p.held)
            val cw = w * p.scale; val ch = h * p.scale
            r.set(p.cx - cw / 2f, p.cy - ch / 2f, p.cx + cw / 2f, p.cy + ch / 2f)
            m.setScale(cw / k.bmp.width, ch / k.bmp.height)
            m.postTranslate(r.left, r.top)
            k.shader.setLocalMatrix(m)
            paint.shader = k.shader
            val rr = corner * p.scale
            c.drawRoundRect(r, rr, rr, paint)
            if (p.dim > 0f) {
                shade.color = android.graphics.Color.argb((p.dim * 255f).toInt().coerceIn(0, 255), 0, 0, 0)
                c.drawRoundRect(r, rr, rr, shade)
            }
        }
        paint.shader = null
    }

    /** The held card's picture of [pin] (the page's own size), as the carousel draws it: for the test that it is the notes page's. */
    @Synchronized
    internal fun card(pin: Pin, index: Int, count: Int, w: Float, h: Float, now: Long): Bitmap = picture(pin, index, count, w, h, full = true, now = now).bmp

    private fun picture(pin: Pin, index: Int, count: Int, w: Float, h: Float, full: Boolean, now: Long = System.currentTimeMillis()): Kept {
        val stamp = PinFace.stamp(pin.freshAt, now)
        // The card is the notes page's own drawing of the note (PinFace.note): a place note keeps its name and its mark.
        val key = "$index/$count/$full/$stamp/${pin.text.hashCode()}/${pin.place}/${pin.placed}/${pin.watched}/${pin.image}/${w.toInt()}x${h.toInt()}"
        // A picture's card drawn before its picture was read is drawn again once it is (PinFace.pictures); one drawn with
        // it stays as it is, whatever the shell's few pictures in memory are by then.
        val old = kept[pin.at]
        old?.let { if (it.key == key && (it.pictured || runCatching { PinFace.pictures(pin.image!!) }.getOrNull() == null)) return it }
        val f = if (full) 1f else 0.5f
        val bw = (w * f).toInt().coerceAtLeast(1); val bh = (h * f).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(bw / w, bh / h)
        // THE PICTURE IT HAD: the shell keeps only a few in memory (NotePictures.MOST) and the carousel shows more notes than
        // that, so it may have let this card's go since the card was drawn. A card drawn again at another place (a note
        // carried past it, two swapped) keeps it: never drawn empty for the frames its picture would take to be read again.
        val picture = pin.image?.let { name -> runCatching { PinFace.pictures(name) }.getOrNull() ?: old?.takeIf { it.image == name }?.picture?.takeIf { !it.isRecycled } }
        PinFace.note(cv, w, h, pin, index, count, now, held = picture)
        return Kept(key, bmp, BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP), pin.image == null || picture != null, pin.image, picture)
            .also { kept[pin.at] = it }
    }

    /** The picture named [image] as a card holds it (null for none): the page the carousel gives way to has it from its first frame. */
    @Synchronized
    fun pictureOf(image: String?): Bitmap? = if (image == null) null else kept.values.firstOrNull { it.image == image }?.picture

    /**
     * The pictures let go (the carousel is down). Not recycled by hand: the last frame drawn may still hold them on the
     * render thread; the collector takes them.
     */
    @Synchronized
    fun clear() { kept.clear() }
}
