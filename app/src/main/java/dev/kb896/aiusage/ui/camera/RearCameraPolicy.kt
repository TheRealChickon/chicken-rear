package dev.kb896.aiusage.ui.camera

import androidx.camera.core.CameraState
import androidx.camera.video.VideoRecordEvent.Finalize
import kotlin.math.abs

/**
 * The camera page's decisions that need no camera: which modes there are, which lens is the wide one, which finger
 * leaves. Pure; the activity asks.
 */
object RearCameraPolicy {
    enum class Mode { VIDEO, PHOTO, PORTRAIT }
    data class Lens(val key: Int, val back: Boolean, val intrinsic: Float)
    /** The wide view: a zoom of the main (logical) camera ([key] null), or another back camera ([key], at zoom 1). */
    data class Wide(val key: Int?, val zoom: Float, val label: Float)

    /** The ultra-wide's ratio the reference shows. */
    const val WIDE = 0.7f

    /** CameraX can wait for another client even when it reports no error. */
    fun cameraProblem(type: CameraState.Type, error: Int?, previous: String? = null): String? = when {
        error == CameraState.ERROR_CAMERA_IN_USE || error == CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Camera in use"
        error != null -> "Camera not available"
        type == CameraState.Type.PENDING_OPEN -> "Camera in use"
        type == CameraState.Type.CLOSING || type == CameraState.Type.CLOSED -> previous
        else -> null
    }

    /** These outcomes may publish a usable file. The caller also requires a real output URI. */
    fun keepsVideo(error: Int): Boolean = when (error) {
        Finalize.ERROR_NONE, Finalize.ERROR_FILE_SIZE_LIMIT_REACHED, Finalize.ERROR_DURATION_LIMIT_REACHED,
        Finalize.ERROR_INSUFFICIENT_STORAGE, Finalize.ERROR_SOURCE_INACTIVE -> true
        else -> false
    }

    /** "Portrait" only where the phone offers a portrait (bokeh) mode to apps: never a made-up one. */
    fun modes(bokeh: Boolean): List<Mode> = listOf(Mode.VIDEO, Mode.PHOTO) + if (bokeh) listOf(Mode.PORTRAIT) else emptyList()

    /** The word on the page. */
    fun word(mode: Mode): String = when (mode) {
        Mode.VIDEO -> "Video"
        Mode.PHOTO -> "Photo"
        Mode.PORTRAIT -> "Portrait"
    }

    /**
     * The wide view, if the phone gives one to apps: the main camera zooming out to the reference's 0.7 where
     * possible, or to its minimum supported ratio if it is still wide. Otherwise try an exposed back
     * camera with a wide intrinsic ratio, nearest 0.7. Null means no zoom chip.
     */
    fun wide(minZoom: Float, lenses: List<Lens>): Wide? {
        if (minZoom.isFinite() && minZoom > 0f && minZoom <= 0.85f) {
            val zoom = maxOf(minZoom, WIDE)
            return Wide(null, zoom, zoom)
        }
        return lenses.filter { it.back && it.intrinsic.isFinite() && it.intrinsic in 0.1f..0.85f }
            .minByOrNull { abs(it.intrinsic - WIDE) }?.let { Wide(it.key, 1f, it.intrinsic) }
    }

    /**
     * The chip's words: THE ZOOM IN USE, as the reference shows "0.7×" over its wide group picture (and as a single zoom
     * chip on a phone's camera reads): "0.7×" while the wide view is on, "1×" on the main camera; a tap goes to the other.
     * Empty: no wide view to be had (no chip).
     */
    fun zoomLabel(wideOn: Boolean, wide: Wide?): String = when {
        wide == null -> ""
        wideOn -> String.format(java.util.Locale.ROOT, "%.1f×", wide.label)
        else -> "1×"
    }

    /** The page opens on the wide view where there is one (a group in front of the back), until he chooses the main camera. */
    const val WIDE_BY_DEFAULT = true

    /**
     * THE WAY BACK TO THE FACE: a stroke from right to left on the picture, the way the page came in (the stroke left to
     * right on the face opened it), as every page of the back goes back the way it came. One finger, starting where he
     * sees (right of the lenses) and above the mode words, at least [LEAVE_TRAVEL] panel px, more across than up or down.
     * The mode words are tapped, never stroked, so the two never meet. A stroke that starts on a round button counts too
     * (the page takes it from the button once it is clearly a stroke: [strokeStarted]).
     */
    fun leaves(startX: Float, startY: Float, endX: Float, endY: Float, fingers: Int): Boolean =
        fingers == 1 && startX >= RearCameraLayout.SEEN_LEFT && startY < RearCameraLayout.MODES_TOP &&
            endX - startX <= -LEAVE_TRAVEL && abs(endY - startY) < abs(endX - startX) * 0.6f

    /**
     * Whether a finger that went down at ([startX], [startY]) and is now at ([x], [y]) has begun the stroke back: right to
     * left by more than [STROKE_SLOP], more across than up or down, from where [leaves] may count it. Taken from a button
     * under it then (the button's press is cancelled).
     */
    fun strokeStarted(startX: Float, startY: Float, x: Float, y: Float, fingers: Int): Boolean =
        fingers == 1 && startX >= RearCameraLayout.SEEN_LEFT && startY < RearCameraLayout.MODES_TOP &&
            startX - x > STROKE_SLOP && abs(y - startY) < abs(x - startX) * 0.6f

    const val STROKE_SLOP = 40f

    /** A tap on the picture (focus there): short and still. */
    fun tap(startX: Float, startY: Float, endX: Float, endY: Float, downMs: Long, fingers: Int): Boolean =
        fingers == 1 && downMs <= 350L && abs(endX - startX) < 24f && abs(endY - startY) < 24f && startX >= RearCameraLayout.SEEN_LEFT

    const val LEAVE_TRAVEL = 150f

    /**
     * Whether the shutter (and the film's start and stop) is heard: where the phone says it must be (some countries'
     * law, MediaActionSound.mustPlayShutterSound), else only with the ringer on, as the phone's own camera does by
     * default. The phone's own camera sound (MediaActionSound), never one of ours.
     */
    fun shutterHeard(mustPlay: Boolean, ringerNormal: Boolean): Boolean = mustPlay || ringerNormal

    /**
     * Which back camera a mode uses: Portrait the main one always (the phone's portrait mode is the main camera's), the
     * others the wide one when it is chosen and there is one.
     */
    fun useWide(mode: Mode, wideChosen: Boolean, wide: Wide?): Boolean = mode != Mode.PORTRAIT && wideChosen && wide != null
}

object RearCameraLayout {
    const val WIDTH = 976
    const val HEIGHT = 596
    const val SEEN_LEFT = 296
    /** The middle of what he sees: a line standing alone is centred here. */
    const val SEEN_CX = 636

    /** The right column's centre line. */
    const val COLUMN_X = 879
    data class Circle(val cx: Int, val cy: Int, val r: Int) {
        /** Its touch area: the circle's square, at least [min] across. */
        fun box(min: Int = 96): Box { val h = maxOf(r, min / 2); return Box(cx - h, cy - h, 2 * h, 2 * h) }
    }
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right get() = left + width
        val bottom get() = top + height
    }

    val thumbnail = Circle(COLUMN_X, 90, 56)
    val zoom = Circle(COLUMN_X, 236, 40)
    val flash = Circle(COLUMN_X, 358, 40)
    /** The shutter: a white ring (outer radius 61, 5 thick), 5 apart from the white disc inside (radius 51). */
    val shutter = Circle(COLUMN_X, 496, 61)
    const val SHUTTER_RING = 5
    const val SHUTTER_GAP = 5

    /** Each mode word's middle, across. */
    fun modeCx(mode: RearCameraPolicy.Mode): Int = when (mode) {
        RearCameraPolicy.Mode.VIDEO -> 431
        RearCameraPolicy.Mode.PHOTO -> 567
        RearCameraPolicy.Mode.PORTRAIT -> 714
    }
    /** The words' middle line, their size, and the room each word's touch area gets on either side and its height. */
    const val MODES_CY = 531
    const val MODES_TEXT = 33f
    const val MODES_PAD = 18
    const val MODES_HEIGHT = 66
    /** Above this a stroke may leave; on the words' band it may not. */
    const val MODES_TOP = 498

    /** The zoom chip's words. */
    const val ZOOM_TEXT = 26f
    /** The recording time, top centre of what he sees; a line that something failed, its middle. */
    const val TIME_CY = 44
    const val TIME_TEXT = 28f
    const val NOTE_CY = 298
    const val NOTE_TEXT = 30f

    /** Xiaomi's camera yellow, from the reference (its white read as white). */
    const val YELLOW = 0xFFEBC060.toInt()
    /** The round buttons' dark glass. */
    const val DARK = 0x59000000
    /** The record button's red. */
    const val RED = 0xFFFF3B30.toInt()

    /** The fixed controls' touch areas, for the tests that nothing overlaps or leaves the panel. */
    val controls: List<Box> get() = listOf(thumbnail.box(), zoom.box(), flash.box(), shutter.box(128))
}
