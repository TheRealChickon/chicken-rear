package dev.kb896.aiusage.rear

import android.graphics.Canvas

/**
 * ONE NOTE TURNING TO THE NEXT on the notes page, drawn (the shell's NoteTurn moves it): the note that was showing
 * goes out to one side as the other comes in beside it; with none that way, this one goes a little aside over black
 * and comes back. [note] draws note number n in its own place on the canvas.
 */
object NoteTurnArt {
    /** With no note that way the notes page goes this part of its width aside and comes back. */
    const val GIVE_REACH = 0.14f
    /** [GIVE_REACH] as the turn's own measure (how far [draw]'s `at` goes before it comes back). */
    const val GIVE_PEAK = 1f

    /**
     * On [c], [w] by [h]: note [from] going out as note [to] comes in ([to] < 0: none that way, the page gives),
     * [dir] +1 the older one in from the right, -1 the newer from the left, [at] 0 the old note in place and 1 the new
     * one (before easing). [corner]: the display's, in the canvas's pixels.
     */
    fun draw(c: Canvas, w: Float, h: Float, corner: Float, from: Int, to: Int, dir: Int, at: Float, note: (Int) -> Unit) {
        val e = ShellPage.ease(at) * (if (to >= 0) 1f else GIVE_REACH)
        c.drawColor(0xFF000000.toInt())
        c.save()
        c.translate(-dir * e * w, 0f)
        // EACH NOTE OFF ITS PLACE HAS THE DISPLAY'S CORNERS (RearShape), over the black between them: the one going
        // out, the one coming in beside it, the one given aside at an end. In its place (e 0) the display's shape is enough.
        val aside = e != 0f
        note(from)
        if (aside) PageCorners.cut(c, w, h, corner)
        if (to >= 0) {
            c.translate(dir * w, 0f)
            note(to)
            if (aside) PageCorners.cut(c, w, h, corner)
        }
        c.restore()
    }
}
