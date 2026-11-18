package dev.kb896.aiusage.ui.camera

import androidx.camera.core.CameraState
import androidx.camera.video.VideoRecordEvent.Finalize
import dev.kb896.aiusage.ui.camera.RearCameraPolicy.Lens
import dev.kb896.aiusage.ui.camera.RearCameraPolicy.Mode
import dev.kb896.aiusage.ui.camera.RearCameraPolicy.Wide
import dev.kb896.aiusage.ui.camera.RearCameraLayout as L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera page's decisions that need no camera, and where its controls stand on the back. */
class RearCameraPolicyTest {
    @Test fun `a pending camera explains why capture is unavailable until CameraX opens it`() {
        fun problem(type: CameraState.Type, error: Int?) = RearCameraPolicy.cameraProblem(type, error)
        assertEquals("Camera in use", problem(CameraState.Type.PENDING_OPEN, null))
        assertEquals("Camera in use", problem(CameraState.Type.OPENING, CameraState.ERROR_CAMERA_IN_USE))
        assertEquals("Camera in use", problem(CameraState.Type.PENDING_OPEN, CameraState.ERROR_MAX_CAMERAS_IN_USE))
        assertEquals("Camera not available", problem(CameraState.Type.CLOSED, CameraState.ERROR_STREAM_CONFIG))
        assertEquals("Camera not available", RearCameraPolicy.cameraProblem(CameraState.Type.CLOSED, null, "Camera not available"))
        assertNull(RearCameraPolicy.cameraProblem(CameraState.Type.OPENING, null, "Camera not available"))
        assertNull(problem(CameraState.Type.OPEN, null))
    }

    @Test fun `only usable finalizations can replace the recording thumbnail`() {
        for (error in listOf(Finalize.ERROR_NONE, Finalize.ERROR_SOURCE_INACTIVE, Finalize.ERROR_INSUFFICIENT_STORAGE,
            Finalize.ERROR_FILE_SIZE_LIMIT_REACHED, Finalize.ERROR_DURATION_LIMIT_REACHED)) {
            assertTrue(RearCameraPolicy.keepsVideo(error))
        }
        for (error in listOf(Finalize.ERROR_NO_VALID_DATA, Finalize.ERROR_ENCODING_FAILED, Finalize.ERROR_RECORDER_ERROR,
            Finalize.ERROR_INVALID_OUTPUT_OPTIONS, Finalize.ERROR_UNKNOWN)) {
            assertFalse(RearCameraPolicy.keepsVideo(error))
        }
    }

    @Test fun `Portrait only where the phone offers a portrait mode to apps, never a made-up one`() {
        assertEquals(listOf(Mode.VIDEO, Mode.PHOTO), RearCameraPolicy.modes(bokeh = false))
        assertEquals(listOf(Mode.VIDEO, Mode.PHOTO, Mode.PORTRAIT), RearCameraPolicy.modes(bokeh = true))
        assertEquals(listOf("Video", "Photo", "Portrait"), Mode.entries.map(RearCameraPolicy::word))
    }

    @Test fun `the wide view is the main camera zooming out where it can, else a wider back camera`() {
        assertEquals(Wide(null, 0.7f, 0.7f), RearCameraPolicy.wide(0.6f, emptyList()))
        assertEquals("exactly 0.7 is enough", Wide(null, 0.7f, 0.7f), RearCameraPolicy.wide(0.7f, emptyList()))
        assertEquals(Wide(null, 0.76f, 0.76f), RearCameraPolicy.wide(0.76f, emptyList()))
        assertNull("the main camera alone, no zoom out", RearCameraPolicy.wide(1f, emptyList()))
        assertNull(RearCameraPolicy.wide(Float.NaN, emptyList()))
        assertNull(RearCameraPolicy.wide(0f, emptyList()))
        val lens = Lens(3, back = true, intrinsic = 0.6f)
        assertEquals(Wide(3, 1f, 0.6f), RearCameraPolicy.wide(1f, listOf(lens)))
        assertNull("a front camera is no wide view of the back", RearCameraPolicy.wide(1f, listOf(lens.copy(back = false))))
        assertNull("a telephoto is not wide", RearCameraPolicy.wide(1f, listOf(lens.copy(intrinsic = 3f))))
        assertNull("the main camera's own field is not wider", RearCameraPolicy.wide(1f, listOf(lens.copy(intrinsic = 1f))))
        assertNull(RearCameraPolicy.wide(1f, listOf(lens.copy(intrinsic = Float.NaN))))
        assertNull(RearCameraPolicy.wide(1f, listOf(lens.copy(intrinsic = 0f))))
        // Of two wide ones, the one nearest the reference's 0.7.
        assertEquals(4, RearCameraPolicy.wide(1f, listOf(lens.copy(key = 2, intrinsic = 0.45f), lens.copy(key = 4, intrinsic = 0.72f)))!!.key)
        // The main camera's zoom wins over a camera of its own (no new start of the camera to switch).
        assertNull(RearCameraPolicy.wide(0.6f, listOf(lens))!!.key)
    }

    @Test fun `the chip says the zoom in use, as the reference does over its wide picture`() {
        val zoom = Wide(null, 0.7f, 0.7f)
        assertEquals("0.7×", RearCameraPolicy.zoomLabel(wideOn = true, wide = zoom))
        assertEquals("1×", RearCameraPolicy.zoomLabel(wideOn = false, wide = zoom))
        assertEquals("a wide camera's own ratio, never a made-up 0.7", "0.6×", RearCameraPolicy.zoomLabel(true, Wide(3, 1f, 0.6f)))
        assertEquals("no wide view, no chip", "", RearCameraPolicy.zoomLabel(true, null))
        assertTrue("it opens wide, as the reference", RearCameraPolicy.WIDE_BY_DEFAULT)
    }

    @Test fun `Portrait is the main camera's, the others take the wide view when chosen`() {
        val zoom = Wide(null, 0.7f, 0.7f)
        assertTrue(RearCameraPolicy.useWide(Mode.PHOTO, wideChosen = true, wide = zoom))
        assertTrue(RearCameraPolicy.useWide(Mode.VIDEO, wideChosen = true, wide = zoom))
        assertFalse(RearCameraPolicy.useWide(Mode.PORTRAIT, wideChosen = true, wide = zoom))
        assertFalse(RearCameraPolicy.useWide(Mode.PHOTO, wideChosen = false, wide = zoom))
        assertFalse(RearCameraPolicy.useWide(Mode.PHOTO, wideChosen = true, wide = null))
    }

    @Test fun `the stroke back is right to left on the picture, never on the mode words, never two fingers`() {
        assertTrue(RearCameraPolicy.leaves(780f, 220f, 560f, 240f, fingers = 1))
        assertTrue("from the right column too", RearCameraPolicy.leaves(900f, 360f, 700f, 380f, fingers = 1))
        assertFalse("on the words' band", RearCameraPolicy.leaves(780f, 540f, 560f, 540f, fingers = 1))
        assertFalse("two fingers", RearCameraPolicy.leaves(780f, 220f, 560f, 240f, fingers = 2))
        assertFalse("left to right", RearCameraPolicy.leaves(580f, 220f, 780f, 220f, fingers = 1))
        assertFalse("too short", RearCameraPolicy.leaves(780f, 220f, 690f, 220f, fingers = 1))
        assertFalse("more down than across", RearCameraPolicy.leaves(780f, 120f, 560f, 450f, fingers = 1))
        assertFalse("from behind the lenses", RearCameraPolicy.leaves(280f, 220f, 60f, 220f, fingers = 1))
        // Taken from a button once clearly begun, not before.
        assertFalse(RearCameraPolicy.strokeStarted(879f, 358f, 860f, 358f, fingers = 1))
        assertTrue(RearCameraPolicy.strokeStarted(879f, 358f, 820f, 362f, fingers = 1))
        assertFalse("the shutter's press is not taken on the words' band", RearCameraPolicy.strokeStarted(879f, 520f, 780f, 520f, fingers = 1))
        assertFalse("up is not a stroke back", RearCameraPolicy.strokeStarted(879f, 358f, 860f, 250f, fingers = 1))
    }

    @Test fun `a tap on the picture is short and still`() {
        assertTrue(RearCameraPolicy.tap(600f, 300f, 605f, 296f, downMs = 120L, fingers = 1))
        assertFalse(RearCameraPolicy.tap(600f, 300f, 640f, 300f, downMs = 120L, fingers = 1))
        assertFalse(RearCameraPolicy.tap(600f, 300f, 600f, 300f, downMs = 600L, fingers = 1))
        assertFalse(RearCameraPolicy.tap(200f, 300f, 200f, 300f, downMs = 100L, fingers = 1))
    }

    @Test fun `the shutter is heard where the phone must, else with the ringer on`() {
        assertTrue(RearCameraPolicy.shutterHeard(mustPlay = true, ringerNormal = false))
        assertTrue(RearCameraPolicy.shutterHeard(mustPlay = false, ringerNormal = true))
        assertFalse(RearCameraPolicy.shutterHeard(mustPlay = false, ringerNormal = false))
    }

    @Test fun `the controls stand where he sees, apart, and the words between the lenses and the shutter`() {
        val boxes = L.controls
        for (b in boxes) {
            assertTrue("$b right of the lenses", b.left >= L.SEEN_LEFT)
            assertTrue("$b on the panel", b.top >= 0 && b.right <= L.WIDTH && b.bottom <= L.HEIGHT)
        }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]; val b = boxes[j]
            assertFalse("$a and $b apart", a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
        }
        val shutter = L.shutter.box(128)
        val centres = Mode.entries.map(L::modeCx)
        assertEquals("in the reference's order", centres.sorted(), centres)
        assertTrue(centres.first() - 80 > L.SEEN_LEFT)
        assertTrue("Portrait's word ends before the shutter's touch area", centres.last() + 80 < shutter.left)
        assertTrue("the words' band is under where a stroke may leave", L.MODES_CY - L.MODES_HEIGHT / 2 >= L.MODES_TOP)
        assertTrue(L.MODES_CY + L.MODES_HEIGHT / 2 <= L.HEIGHT)
        // A line standing alone is centred on what he sees.
        assertEquals((L.SEEN_LEFT + L.WIDTH) / 2, L.SEEN_CX)
    }
}
