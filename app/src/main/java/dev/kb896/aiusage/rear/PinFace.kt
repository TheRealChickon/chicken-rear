package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint

/**
 * The pinned notes on the back: OUR page for them, looking as Xiaomi's "Pinned message" page looks
 * (measured: NoticePop), since Xiaomi's own is under the cover now and never seen. Its warm ground, the mark and
 * "Pinned message" at the head, the time it was pinned in a chip, the note large in cream, the bin bottom right. With
 * more than one note: which of how many, where the handle is, and a swipe sideways goes from one to the next.
 *
 * A PICTURE'S NOTE (a picture shared to "Pin to back" or "Pin to location": Pin.image) is the same page with the
 * picture in the note's place ([PIC_LEFT] to [PIC_RIGHT], [PIC_TOP] to [PIC_BOTTOM]): fitted in its own shape (never
 * stretched, never more than twice its stored size), its corners rounded ([PIC_CORNER]), centred as the words are, its
 * caption (if any) under it as the note's words at a smaller size; chip, place mark, count and bin as for any note.
 * Its size is known from its file's name (data/PinImage.size) before it is decoded, so a picture that is not ready
 * leaves its place empty for that frame and the page does not move when it comes. The back's shell decodes pictures
 * off its drawing threads and hands them here ([pictures]); nothing here ever reads a file. A TAP ON THE PICTURE opens it
 * on the front, larger (rear/PictureTap, ui/PinViewActivity); [pictureAt] says where it is.
 *
 * In rear pixels of a 976x596 panel; to scale on any other.
 */
object PinFace {
    /** The picture place, rear pixels of the 976x596 page: clear of the head and chip above, the bin to the right, the handle below. */
    const val PIC_LEFT = 322f
    const val PIC_RIGHT = 800f
    const val PIC_TOP = 128f
    const val PIC_BOTTOM = 556f
    /** The picture's corners (the page's own cards' rounding). */
    const val PIC_CORNER = 28f
    /** Between the picture and its caption. */
    const val PIC_GAP = 18f
    /** A small picture is shown at most this many times its stored size (more would only be blur). */
    const val PIC_GROW = 2f

    /**
     * The decoded picture of a note's file name, if it is in memory now; null otherwise (and never a wait). The back's
     * shell sets its own (rear/NotePictures); elsewhere a caller passes the picture to [draw] itself.
     */
    @Volatile var pictures: (String) -> android.graphics.Bitmap? = { null }

    /**
     * Where a picture stored at [sw] x [sh] stands (left, top, right, bottom, rear pixels of 976x596) with a caption
     * [captionH] high (0 for none, else the gap and its lines) and [captionW] at its widest: fitted into the picture
     * place above the caption, picture and caption one block in the place's middle, centred on x 512 as the words are
     * unless that would put it under the lenses (then its left edge at [PIC_LEFT]). Pure.
     */
    fun picturePlace(sw: Int, sh: Int, captionH: Float = 0f, captionW: Float = 0f): FloatArray {
        val boxW = PIC_RIGHT - PIC_LEFT
        val boxH = (PIC_BOTTOM - PIC_TOP - captionH).coerceAtLeast(40f)
        val s = minOf(boxW / sw.coerceAtLeast(1), boxH / sh.coerceAtLeast(1), PIC_GROW)
        val dw = sw * s; val dh = sh * s
        val wide = maxOf(dw, captionW)
        val cx = minOf(maxOf(512f, PIC_LEFT + wide / 2f), PIC_RIGHT - wide / 2f).coerceAtLeast(PIC_LEFT + dw / 2f)
        val top = PIC_TOP + (PIC_BOTTOM - PIC_TOP - dh - captionH) / 2f
        return floatArrayOf(cx - dw / 2f, top, cx + dw / 2f, top + dh)
    }
    /** The bin: its centre and radius, for the finger as for the brush. */
    const val BIN_X = 876f
    const val BIN_Y = 496f
    const val BIN_R = 56f

    /** Whether a tap at [x], [y] (rear pixels of a 976x596 panel) is on the bin, generously. */
    fun onBin(x: Float, y: Float): Boolean = (x - BIN_X) * (x - BIN_X) + (y - BIN_Y) * (y - BIN_Y) <= (BIN_R + 14f) * (BIN_R + 14f)

    /** "09:41" for one pinned today, "12 Nov" for an older one. */
    fun stamp(at: Long, now: Long): String {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = now
        val day = c.get(java.util.Calendar.DAY_OF_YEAR); val year = c.get(java.util.Calendar.YEAR)
        c.timeInMillis = at
        return if (c.get(java.util.Calendar.DAY_OF_YEAR) == day && c.get(java.util.Calendar.YEAR) == year) {
            "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        } else "${c.get(java.util.Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(java.util.Calendar.MONTH)]}"
    }

    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val ground = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val head = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()
    private var typed = false
    private val shot = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shotMatrix = android.graphics.Matrix()
    private val shotFrom = RectF()

    @Synchronized
    fun draw(c: Canvas, w: Float, h: Float, text: String, stamp: String, index: Int, count: Int, placed: Boolean = false,
             place: String? = null, hollow: Boolean = false, arrival: Boolean = false,
             image: String? = null, picture: android.graphics.Bitmap? = null) {
        val u = h / 596f
        type()
        val accent = NoticePop.PIN
        ground.shader = LinearGradient(0f, 0f, 0f, h, NoticePop.shades(accent), NoticePop.stops(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, ground)
        val gold = NoticePop.head(accent)
        val cream = NoticePop.ink(accent)

        // The head: the mark (a bookmark with two lines on it) and "Pinned message", centred on x 512.
        head.textSize = 40f * u; head.color = gold
        val title = if (arrival) place?.takeIf { it.isNotBlank() }?.let { chipName(it, 28) } ?: "Pinned message" else "Pinned message"
        val tw = head.measureText(title)
        // With nothing pinned the page is three lines and no note: all of them about the middle of what he sees
        // (x 636, NoticePop.CENTRE). With a note it keeps Xiaomi's own places (x 512), the page it is a copy of.
        val mid = if (count <= 0) NoticePop.CENTRE else 512f
        var x = mid * u - (36f * u + 15f * u + tw) / 2f
        path.rewind()
        val mx = x; val my = 59f * u; val mw = 36f * u; val mh = 43f * u; val r = 7f * u
        if (arrival) {
            // An arrival's head: the map pin (the chip's drop, large) where the bookmark stands.
            mapPin(c, mx + mw / 2f, my + 15f * u, 15f * u, gold, NoticePop.shade(accent, 120f), hollow)
        } else {
            path.addRoundRect(mx, my, mx + mw, my + mh * 0.82f, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
            path.moveTo(mx, my + mh * 0.8f); path.lineTo(mx, my + mh); path.lineTo(mx + mw / 2f, my + mh * 0.8f); path.lineTo(mx + mw, my + mh); path.lineTo(mx + mw, my + mh * 0.8f); path.close()
            fill.color = gold
            c.drawPath(path, fill)
            stroke.color = NoticePop.shade(accent, 120f); stroke.strokeWidth = 4.2f * u
            c.drawLine(mx + 8f * u, my + 12f * u, mx + mw - 8f * u, my + 12f * u, stroke)
            c.drawLine(mx + 8f * u, my + 21f * u, mx + mw - 16f * u, my + 21f * u, stroke)
        }
        c.drawText(title, x + 36f * u + 15f * u, (80f + 40f * 0.36f) * u, head)

        if (count <= 0) {
            // Set as a pop-up's message is (its demibold, near its size), with its one line of help under it at a
            // size that reads at arm's length (they were 46 px regular and 24 px, about x 512: left of the middle).
            words.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            words.color = cream; words.textSize = 62f * u
            c.drawText("Nothing pinned", mid * u, 296f * u, words)
            words.typeface = Typeface.create(Typeface.DEFAULT, 400, false)
            small.color = gold; small.alpha = 200; small.textSize = 32f * u
            c.drawText("Select text, then Pin to back", mid * u, 352f * u, small)
            small.alpha = 255
            return
        }

        // The time it was pinned, in its chip, top right. A note waiting for its place: the map pin and the place's name
        // instead (the pin alone without a name); an arrival: the time he arrived, and its pin is in the head.
        small.textSize = 34f * u
        val chip = if (placed && !arrival) place?.takeIf { it.isNotBlank() }?.let { chipName(it, 12) } ?: "" else stamp
        val sw = small.measureText(chip)
        val markIn = placed && !arrival
        val mark = if (markIn) PLACE_W * u else 0f
        rect.set(920f * u - sw - (if (chip.isEmpty()) 30f else 44f) * u - mark, 54f * u, 920f * u, 108f * u)
        fill.color = 0xFF2C2120.toInt()
        c.drawRoundRect(rect, 27f * u, 27f * u, fill)
        small.color = 0xFF988C8A.toInt()
        if (chip.isNotEmpty()) c.drawText(chip, rect.centerX() + mark / 2f, 93f * u, small)
        if (markIn) {
            // A map pin: a drop with a hole in it, in the chip's own grey; hollow while its place is not watched.
            val px = if (chip.isEmpty()) rect.centerX() else rect.left + 22f * u + PLACE_W * u / 2f - 6f * u
            mapPin(c, px, 76f * u, 10f * u, 0xFF988C8A.toInt(), 0xFF2C2120.toInt(), hollow)
        }

        // The note: the pin's size for a short one, smaller as it runs to more lines. A picture's: the picture, and its caption under it.
        words.color = cream
        if (image != null || picture != null) drawPicture(c, u, text, image, picture)
        else {
            val (size, broken) = NoticePop.fit(text, SIZES, ROOM * u) { s, t -> words.textSize = s * u; words.measureText(t) }
            words.textSize = size * u
            val widest = broken.maxOfOrNull { words.measureText(it) } ?: 0f
            val cx = maxOf(512f * u, 322f * u + widest / 2f)
            var base = if (broken.size == 1) 250f else 250f - (SIZES[0] - size) * 0.55f
            for (line in broken) { c.drawText(line, cx, base * u, words); base += size * 1.2f }
        }

        // The bin, bottom right.
        fill.color = 0x38FFFFFF
        c.drawCircle(BIN_X * u, BIN_Y * u, BIN_R * u, fill)
        stroke.color = 0xFFFFFFFF.toInt(); stroke.strokeWidth = 4.6f * u
        val bx = BIN_X * u; val by = BIN_Y * u; val s = u
        c.drawLine(bx - 21f * s, by - 15f * s, bx + 21f * s, by - 15f * s, stroke)
        path.rewind()
        path.moveTo(bx - 8f * s, by - 15f * s); path.lineTo(bx - 6f * s, by - 23f * s); path.lineTo(bx + 6f * s, by - 23f * s); path.lineTo(bx + 8f * s, by - 15f * s)
        path.moveTo(bx - 16f * s, by - 15f * s); path.lineTo(bx - 13f * s, by + 22f * s); path.lineTo(bx + 13f * s, by + 22f * s); path.lineTo(bx + 16f * s, by - 15f * s)
        c.drawPath(path, stroke)
        c.drawLine(bx - 5f * s, by - 4f * s, bx - 5f * s, by + 12f * s, stroke)
        c.drawLine(bx + 5f * s, by - 4f * s, bx + 5f * s, by + 12f * s, stroke)

        // Which of how many, where the pin page has its handle; the handle itself for one alone.
        if (count > 1) {
            small.textSize = 22f * u; small.color = cream; small.alpha = 200
            c.drawText("${index + 1} / $count", 636f * u, 584f * u, small)
            small.alpha = 255
        } else {
            fill.color = cream; fill.alpha = 200
            rect.set(604f * u, 576f * u, 668f * u, 581f * u)
            c.drawRoundRect(rect, 3f * u, 3f * u, fill)
            fill.alpha = 255
        }
    }

    /**
     * Note [index] of [count] on the notes page as the back shows it ([note] null: an empty note): its time, or for a
     * place note its place's name and its mark, hollow while not watched. EVERY notes page of the shell (the page, the
     * beam's landing, the veil over Xiaomi's pin) draws through this one, so a place note looks the same on all. A
     * picture's note has its picture from [pictures] (or its place empty while it is not decoded); with no [note],
     * [text] and [image] are the pin's own (the veil before the note is in the list). [held]: the note's picture as the
     * caller still holds it, for when [pictures] has let it go (the carousel's card drawn again at another place).
     */
    fun note(c: Canvas, w: Float, h: Float, note: dev.kb896.aiusage.data.Pin?, index: Int, count: Int, now: Long = System.currentTimeMillis(), text: String? = null, image: String? = null,
             held: android.graphics.Bitmap? = null) {
        val img = if (note != null) note.image else image
        draw(c, w, h, note?.text ?: text.orEmpty(), note?.let { stamp(it.freshAt, now) } ?: if (text != null || image != null) stamp(now, now) else "", index, count,
            placed = note?.placed == true, place = note?.place, hollow = note != null && note.placed && !note.watched,
            image = img, picture = img?.let { runCatching { pictures(it) }.getOrNull() ?: held?.takeIf { h -> !h.isRecycled } })
    }

    private fun type() {
        if (typed) return
        typed = true
        head.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        words.typeface = Typeface.create(Typeface.DEFAULT, 400, false)
        small.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }

    /** A picture's caption as the page sets it, at the page's scale [u]: its size, its lines, its widest line (in units of [u]). [words]' size is left at its size. */
    private fun captionOf(caption: String, u: Float): Triple<Float, List<String>, Float> {
        val words0 = caption.trim()
        if (words0.isEmpty()) return Triple(0f, emptyList(), 0f)
        val fitted = NoticePop.fit(words0, CAPTION_SIZES, CAPTION_ROOM * u) { s, t -> words.textSize = s * u; words.measureText(t) }
        words.textSize = fitted.first * u
        return Triple(fitted.first, fitted.second, (fitted.second.maxOfOrNull { words.measureText(it) } ?: 0f) / u)
    }

    private fun captionHeight(size: Float, lines: Int) = if (lines == 0) 0f else PIC_GAP + lines * size * 1.2f

    /**
     * WHERE A PICTURE'S NOTE HAS ITS PICTURE on the page (left, top, right, bottom, rear pixels of the 976x596 page), as
     * [draw] puts it for [caption] and the picture named [image] (its stored size, from the name): the same caption,
     * measured the same way. Null for a note with no picture of ours. The back's shell asks it where a tap landed
     * (rear/PictureTap: a tap on the picture opens it on the front), and the front's viewer where its picture comes from
     * (ui/PinViewMotion.origin).
     */
    @Synchronized
    fun pictureAt(caption: String, image: String?): FloatArray? {
        val (sw, sh) = dev.kb896.aiusage.data.PinImage.size(image) ?: return null
        type()
        val (size, broken, widest) = captionOf(caption, 1f)
        return picturePlace(sw, sh, captionHeight(size, broken.size), widest)
    }

    /** A picture's note: the picture in its place, rounded, and its caption (if any) under it, centred with it. */
    private fun drawPicture(c: Canvas, u: Float, caption: String, image: String?, picture: android.graphics.Bitmap?) {
        val (sw, sh) = picture?.takeIf { !it.isRecycled }?.let { it.width to it.height }
            ?: dev.kb896.aiusage.data.PinImage.size(image) ?: (dev.kb896.aiusage.data.PinImage.MAX_W to dev.kb896.aiusage.data.PinImage.MAX_H)
        val (size, broken, widest) = captionOf(caption, u)
        val captionH = captionHeight(size, broken.size)
        val at = picturePlace(sw, sh, captionH, widest)
        rect.set(at[0] * u, at[1] * u, at[2] * u, at[3] * u)
        if (picture != null && !picture.isRecycled) {
            // A shader of its own each time (nothing here holds a picture past its drawing: the shell keeps the few it keeps).
            val shader = android.graphics.BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shotFrom.set(0f, 0f, picture.width.toFloat(), picture.height.toFloat())
            shotMatrix.setRectToRect(shotFrom, rect, android.graphics.Matrix.ScaleToFit.FILL)
            shader.setLocalMatrix(shotMatrix)
            shot.shader = shader
            c.drawRoundRect(rect, PIC_CORNER * u, PIC_CORNER * u, shot)
            shot.shader = null
        }
        if (broken.isEmpty()) return
        val cx = (at[0] + at[2]) / 2f * u
        var base = at[3] + PIC_GAP + size * 0.95f
        for (line in broken) { c.drawText(line, cx, base * u, words); base += size * 1.2f }
    }

    /** The map pin: a drop with a hole in it, centred on its round part at [px], [py]; [hollow] its outline only. */
    private fun mapPin(c: Canvas, px: Float, py: Float, pr: Float, color: Int, hole: Int, hollow: Boolean) {
        path.rewind()
        path.addCircle(px, py, pr, Path.Direction.CW)
        path.moveTo(px - pr * 0.86f, py + pr * 0.5f); path.lineTo(px, py + pr * 2.1f); path.lineTo(px + pr * 0.86f, py + pr * 0.5f); path.close()
        if (hollow) {
            stroke.color = color; stroke.strokeWidth = pr * 0.26f
            c.drawCircle(px, py, pr * 0.87f, stroke)
            c.drawLine(px - pr * 0.72f, py + pr * 0.62f, px, py + pr * 1.95f, stroke)
            c.drawLine(px + pr * 0.72f, py + pr * 0.62f, px, py + pr * 1.95f, stroke)
            return
        }
        fill.color = color
        c.drawPath(path, fill)
        fill.color = hole
        c.drawCircle(px, py, pr * 0.42f, fill)
    }

    /** A place's name cut to [most] characters, then an ellipsis. */
    fun chipName(name: String, most: Int): String { val n = name.trim(); return if (n.length <= most) n else n.take(most).trimEnd() + "…" }

    /**
     * An arrival's page may stand for hours on an OLED: after 10 minutes it moves by 2 px now and then (a new place
     * every minute, round a small square), [sinceMs] after it came. (0, 0) before.
     */
    fun drift(sinceMs: Long): Pair<Float, Float> {
        if (sinceMs < 10 * 60_000L) return 0f to 0f
        return when (((sinceMs / 60_000L) % 4L).toInt()) { 0 -> 2f to 0f; 1 -> 2f to 2f; 2 -> 0f to 2f; else -> 0f to 0f }
    }

    private val SIZES = floatArrayOf(90f, 70f, 56f, 46f)
    private const val ROOM = 470f
    /** A picture's caption: one line at 40 px or two at 34, in the room the words have. */
    private val CAPTION_SIZES = floatArrayOf(40f, 34f)
    private const val CAPTION_ROOM = 470f
    /** The room a place's mark takes in the chip. */
    private const val PLACE_W = 30f
}
