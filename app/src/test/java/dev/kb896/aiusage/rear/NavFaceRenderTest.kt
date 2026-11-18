package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NavFaceRenderTest {
    @Test fun `the page at its moments, and the tracker moving`() {
        val path = System.getenv("MAPS_NAV_RENDER_DIR")
        assumeTrue("MAPS_NAV_RENDER_DIR names the folder with the reference's pictures", !path.isNullOrBlank())
        val dir = File(path!!)
        fun picture(name: String): Bitmap = requireNotNull(BitmapFactory.decodeFile(File(dir, name).path)) { "$name is not in $dir" }
        val icon = picture("maps-icon-reference.png")
        val reference = NavFace.demo(appIcon = icon, maneuver = picture("maneuver-reference.png"), tracker = picture("tracker-reference.png"))
        fun save(name: String, m: NavFace.Model, at: Float? = m.fraction) {
            val b = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
            NavFace.draw(Canvas(b), 976f, 596f, m, at)
            File(dir, name).outputStream().use { assertTrue(b.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            b.recycle()
        }
        // As the reference: the tracker a little past the middle, slower traffic ahead of it, then the rest.
        save("navigation-reference-look.png", reference)
        // Maps' own split of the words (the distance as the title, the instruction as the text): one flowing line, and a
        // sub text of three parts, of which the pill takes the last.
        save("navigation-distance-title.png", reference.copy(short = "", title = "2 km", text = "Turn right toward Example Way", eta = "15 min · 4.2 km · 08:31"))
        // As the debug line shows it on the phone ("nav|2 km|Turn right toward Example Way|08:16|0.55"): no arrow or tracker of an app's.
        val line = requireNotNull(NavigationDemo.parse(listOf("nav", "2 km", "Turn right toward Example Way", "08:16", "0.55")))
        save("navigation-debug-line.png", line.copy(appIcon = icon))
        // Long words: smaller, three lines at most, above the arrow.
        save("navigation-long-words.png", reference.copy(short = "350 m", title = "At the roundabout, take the 2nd exit onto Main Street",
            text = "then continue toward the central station"))
        // A plain progress bar (no segments, as Android draws a ProgressStyle without any), and no bar at all (no progress given).
        save("navigation-plain-progress.png", reference.copy(segments = emptyList()))
        save("navigation-no-progress.png", reference.copy(segments = emptyList(), fraction = null, progressMax = null, tracker = null))

        // The tracker: told .40, then .47 a second later, then .52; each eased over NavFace.MOTION_MS, and standing between.
        val frames = File(dir, "frames").apply { deleteRecursively(); mkdirs() }
        val motion = NavFace.TrackerMotion()
        val told = listOf(0L to .40f, 1000L to .47f, 2000L to .52f)
        var next = 0
        val fps = 60
        for (frame in 0 until 3 * fps) {
            val now = frame * 1000L / fps
            while (next < told.size && told[next].first <= now) { motion.update(reference.copy(fraction = told[next].second), told[next].first); next++ }
            val b = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
            NavFace.draw(Canvas(b), 976f, 596f, reference, motion.value(now))
            File(frames, "%03d.png".format(frame)).outputStream().use { assertTrue(b.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            b.recycle()
        }
    }
}
