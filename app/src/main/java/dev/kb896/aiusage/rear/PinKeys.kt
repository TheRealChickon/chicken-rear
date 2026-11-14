package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.PinBeam.CARD_AT
import dev.kb896.aiusage.rear.PinBeam.LEAVE_AT
import dev.kb896.aiusage.rear.PinBeam.LIFT_AT
import dev.kb896.aiusage.rear.PinBeam.Seg
import dev.kb896.aiusage.rear.PinBeam.Track
import dev.kb896.aiusage.rear.PinBeam.spr

/**
 * XIAOMI'S PIN ANIMATION AS ITS RENDERER IS FED IT: the 22 values of its `PinAnimationState`, frame by frame, for a
 * pin of text (read from its code on the phone).
 * Its states begin at [PinBeam.LIFT_AT], [PinBeam.CARD_AT] and [PinBeam.LEAVE_AT]; a value with several keyframes in
 * a state takes them one after the other; a keyframe with no easing of its own takes its state's spring.
 *
 * In Xiaomi's own units: pixels of its 1200 x 2608 front for places and sizes (scaled here to the screen given),
 * degrees, 0..1 for the rest, a blur radius of up to 100 and a blur sigma of up to 50. [PinBeam] is the same motion
 * as parts of the screen, for the plain drawing and for the hand-off to the back; a test holds the two together.
 *
 * Pure. For an image pin Xiaomi's code branches (the content keeps its own shape); only the text pin is here.
 */
object PinKeys {
    const val BLUR = 0; const val SHEET_ALPHA = 1; const val SHEET_W = 2; const val SHEET_H = 3; const val SHEET_SCALE = 4
    const val SHEET_X = 5; const val SHEET_Y = 6; const val PEEL = 7; const val CORNER = 8
    const val CARD_ALPHA = 9; const val CARD_W = 10; const val CARD_H = 11; const val CARD_SCALE = 12
    const val CARD_ROT_X = 13; const val CARD_ROT_Y = 14; const val CARD_ROT_Z = 15; const val CARD_X = 16; const val CARD_Y = 17
    const val EDGE = 18; const val FACE_ALPHA = 19; const val FACE_BLUR = 20; const val GLOW = 21
    const val COUNT = 22

    /** Xiaomi's names, in this order (its `PinAnimationState`'s fields). */
    val NAMES = listOf(
        "backgroundUiBlurRadius", "userImageOpacity", "userImageSizeX", "userImageSizeY", "userImageScale", "userImagePositionX", "userImagePositionY",
        "userImagePeelProgress", "cornerRadiusPercentage", "cardOpacity", "cardSizeX", "cardSizeY", "cardScale", "cardRotationX", "cardRotationY",
        "cardRotationZ", "cardPositionX", "cardPositionY", "cardEdgeThickness", "cardFrontTextureOpacity", "cardFrontTextureBlurSigma", "cardEdgeGlowProgress",
    )

    /** The content's sheet before it takes the card's shape: its picture fitted into this square (Xiaomi's 500 px). */
    const val SHEET_BOX = 500f

    private const val S1 = LIFT_AT; private const val S2 = CARD_AT; private const val S3 = LEAVE_AT

    /** The values, in Xiaomi's 1200 x 2608 pixels, [ms] after the pin began; [sheetW] x [sheetH] is the content's sheet as fitted into [SHEET_BOX]. */
    private fun tracks(sheetW: Float, sheetH: Float, cy: Float): Array<Track> {
        val cx = 600f
        return arrayOf(
            /* blur */ Track(0f, spr(S1, 100f, 0.95f, 0.3f), spr(S3 + 100, 0f, 0.95f, 0.35f)),
            /* sheet alpha */ Track(0f, spr(S1, 1f, 1f, 0.25f), Seg(S2 + 850, 0f, 200, PinBeam::cubicOut)),
            /* sheet w */ Track(sheetW, spr(S2, 950f, 0.95f, 0.4f)),
            /* sheet h */ Track(sheetH, spr(S2, 600f, 0.95f, 0.4f)),
            /* sheet scale */ Track(1.2f, spr(S1, 1.05f, 0.95f, 0.3f), Seg(S2 + 850, 0.65f, 200, PinBeam::cubicInOut), spr(S3, 0.25f, 0.95f, 0.35f)),
            /* sheet x */ Track(cx, Seg(S2, 730f, 930, PinBeam::cubicInOut), spr(S3, 650.5f, 0.95f, 0.35f)),
            /* sheet y */ Track(cy - 100f, Seg(S1, cy + 100f, 300, PinBeam::cubicOut), Seg(S2, 382f, 930, PinBeam::cubicInOut)),
            /* peel */ Track(0f, spr(S1, 1f, 0.95f, 0.3f), spr(S2, 0f, 0.95f, 0.4f)),
            /* corner */ Track(0.1f, spr(S1, 0.8f, 0.95f, 0.3f), spr(S2, 0f, 0.95f, 0.4f)),
            /* card alpha */ Track(0f, spr(S2, 1f, 0.95f, 0.4f), Seg(S3 + 380, 0f, 180, PinBeam::sineOut)),
            /* card w */ Track(950f),
            /* card h */ Track(600f),
            /* card scale */ Track(0.06f, Seg(S2, 0.68f, 900, PinBeam::quartInOut), Seg(S2 + 930, 0.6f, 150, PinBeam::sineOut), Seg(S2 + 1110, 0.68f, 400, PinBeam::cubicOut), Seg(S3, 0.06f, 630, PinBeam::quartInOut)),
            /* card rot x */ Track(90f),
            /* card rot y */ Track(-180f, Seg(S2, 0f, 1000, PinBeam::cubicOut), Seg(S2 + 1000, 10f, 1300, PinBeam::linear), Seg(S3, 180f, 680, PinBeam::sineInOut)),
            /* card rot z */ Track(0f),
            /* card x */ Track(cx, Seg(S2, 610f, 880, PinBeam::cubicOut), Seg(S3, cx, 630, PinBeam::sineOut)),
            /* card y */ Track(72f, Seg(S2, 382f, 880, PinBeam::cubicOut), Seg(S3, 72f, 680, PinBeam::quartInOut)),
            /* edge */ Track(0.01f, Seg(S3, 0.04f, 630, PinBeam::quartInOut)),
            /* face alpha */ Track(0f, spr(S2 + 850, 1f, 0.9f, 0.45f)),
            /* face blur */ Track(50f, spr(S2 + 850, 0f, 0.9f, 0.45f)),
            /* glow */ Track(0f, spr(S2 + 850, 1f, 0.9f, 0.65f)),
        )
    }

    /** The content's sheet, [w] x [h] as it is, fitted into [SHEET_BOX] keeping its shape. */
    fun sheet(w: Float, h: Float): Pair<Float, Float> = if (w <= 0f || h <= 0f) SHEET_BOX to SHEET_BOX else if (w >= h) SHEET_BOX to SHEET_BOX * h / w else SHEET_BOX * w / h to SHEET_BOX

    /**
     * All 22 values [ms] after the pin began, into [out], for a screen [screenW] wide (Xiaomi's pixels scaled by
     * `screenW / 1200`; heights go by the same scale, the middle of the screen by [screenH]). [contentW] x [contentH]
     * is the pinned content's own size, for its sheet's shape.
     */
    fun at(ms: Long, screenW: Float, screenH: Float, contentW: Float, contentH: Float, out: FloatArray = FloatArray(COUNT)): FloatArray {
        val (sw, sh) = sheet(contentW, contentH)
        val t = ms.coerceAtLeast(0L).toFloat()
        val u = screenW / 1200f
        // In Xiaomi's pixels, with the middle of THIS screen where its own middle (1304) is: the lift is about the
        // middle, the card's place is counted from the top.
        val tracks = tracks(sw, sh, screenH / 2f / u)
        for (i in 0 until COUNT) out[i] = tracks[i].at(t)
        for (i in intArrayOf(SHEET_W, SHEET_H, SHEET_X, SHEET_Y, CARD_W, CARD_H, CARD_X, CARD_Y)) out[i] *= u
        out[BLUR] = out[BLUR].coerceIn(0f, 100f)
        for (i in intArrayOf(SHEET_ALPHA, PEEL, CARD_ALPHA, FACE_ALPHA, GLOW)) out[i] = out[i].coerceIn(0f, 1f)
        out[FACE_BLUR] = out[FACE_BLUR].coerceIn(0f, 50f)
        return out
    }
}
