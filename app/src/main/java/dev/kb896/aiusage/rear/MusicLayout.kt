package dev.kb896.aiusage.rear

import android.graphics.Bitmap

/**
 * WHERE THE THINGS OF THE "WHAT IS PLAYING" PAGE STAND, in the units of a 976x596 back.
 *
 * [MUSIC] is Xiaomi's own rear music page (MusicFace's measures): a square cover, the title, who it is by, and the
 * three buttons with their centres at y 534.
 *
 * [VIDEO] is for a player whose picture is WIDE (a video's thumbnail: its width at least [WIDE] times its height).
 * Xiaomi's template has no such page; ours showed a video on the music page as it is, the thumbnail cut to a
 * square, which left the buttons at the very bottom. So for a video the picture keeps its own
 * shape (16:9, as high as it can be beside nothing else), and picture, title, channel and buttons are one block set
 * between like margins: the buttons' centres 48 units higher, at y 486, with as much under them as there is over
 * the picture.
 *
 * Which of the two is the picture's own shape, nothing else: no list of video apps, so a browser's video and a
 * video app are the same, and a player with a square picture (or none) keeps Xiaomi's page.
 */
data class MusicLayout(
    val artX: Float, val artY: Float, val artW: Float, val artH: Float,
    val titleCy: Float, val artistCy: Float,
    /** The top of the buttons' squares ([MusicFace.BTN] high; their glyphs are in the middle half). */
    val btnY: Float,
) {
    /** The buttons' centres' height. */
    val btnCy: Float get() = btnY + MusicFace.BTN / 2f

    /** The title's height when there is no line under it: between the two lines' places, so no hole is left. */
    val titleAloneCy: Float get() = (titleCy + artistCy) / 2f

    companion object {
        val MUSIC = MusicLayout(MusicFace.ART_X, MusicFace.ART_Y, MusicFace.ART, MusicFace.ART, MusicFace.TITLE_CY, MusicFace.ARTIST_CY, MusicFace.BTN_Y)
        val VIDEO = MusicLayout(MusicFace.ART_X, 60f, 320f, 180f, 300f, 364f, 416f)

        const val WIDE = 1.3f

        fun of(coverW: Int, coverH: Int): MusicLayout = if (coverW > 0 && coverH > 0 && coverW >= coverH * WIDE) VIDEO else MUSIC
        fun of(cover: Bitmap?): MusicLayout = if (cover == null) MUSIC else of(cover.width, cover.height)
    }
}
