package dev.kb896.aiusage.service

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.rear.NavFace
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val maps = "com.google.android.apps.maps"
    private fun note(title: String = "Turn right", text: String = "toward Example Way"): Notification =
        Notification.Builder(context, "route").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setCategory(Notification.CATEGORY_NAVIGATION).setOngoing(true)
            .setContentTitle(title).setContentText(text).setSubText("08:16").build()
            // Android 16's short critical text (setShortCriticalText), as it lands in the extras.
            .apply { extras.putCharSequence("android.shortCriticalText", "2 km") }

    private fun segment(length: Int, color: Int) = Bundle().apply { putInt("length", length); putInt("colorInt", color) }
    private fun point(position: Int, color: Int) = Bundle().apply { putInt("position", position); putInt("colorInt", color) }
    private fun route(): Notification = note().apply {
        extras.putInt(Notification.EXTRA_PROGRESS, 45)
        extras.putInt(Notification.EXTRA_PROGRESS_MAX, 100)
        extras.putParcelableArrayList("android.progressSegments", arrayListOf(segment(45, Color.GRAY), segment(20, Color.YELLOW), segment(35, Color.BLUE)))
        extras.putParcelableArrayList("android.progressPoints", arrayListOf(point(65, Color.WHITE)))
    }
    private fun parse(n: Notification) = NavigationNotification.parse("route", maps, n)
    private fun image(color: Int) = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun `reads the exact Android 16 serialized progress bundles`() {
        val m = parse(route())!!
        assertEquals("2 km", m.short)
        assertEquals("Turn right", m.title)
        assertEquals("toward Example Way", m.text)
        assertEquals("08:16", m.eta)
        assertEquals(0.45f, m.fraction!!, 0.0001f)
        assertEquals(100, m.progressMax)
        assertEquals(listOf(NavFace.Segment(45, Color.GRAY), NavFace.Segment(20, Color.YELLOW), NavFace.Segment(35, Color.BLUE)), m.segments)
        assertEquals(listOf(NavFace.Point(65, Color.WHITE)), m.points)
        assertTrue(m.styledByProgress)
        assertFalse(m.demo)
    }

    @Test fun `any ongoing navigation app qualifies but summaries and other notifications do not`() {
        assertNotNull(NavigationNotification.parse("waze", "com.waze", note()))
        assertNotNull(NavigationNotification.parse("petal", "com.huawei.maps.app", note()))
        assertNull(parse(note().apply { category = Notification.CATEGORY_MESSAGE }))
        assertNull(parse(note().apply { flags = flags and Notification.FLAG_ONGOING_EVENT.inv() }))
        // A foreground service's notification the user may swipe (Android 14 and later) is still the route.
        assertNotNull(parse(note().apply { flags = (flags and Notification.FLAG_ONGOING_EVENT.inv()) or Notification.FLAG_FOREGROUND_SERVICE }))
        assertNull(parse(note().apply { flags = flags or Notification.FLAG_GROUP_SUMMARY }))
    }

    @Test fun `missing facts remain missing without a fabricated bar maneuver or app icon`() {
        // No words and no bar: no page at all (a back with only an icon on it says nothing).
        assertNull(parse(note("", "").apply { extras.remove(NavigationNotification.SHORT); extras.remove(Notification.EXTRA_SUB_TEXT) }))
        val m = parse(note("Turn right", "").apply { extras.remove(NavigationNotification.SHORT) })!!
        assertEquals("", m.short); assertEquals("Turn right", m.title); assertEquals("", m.text)
        assertNull(m.fraction); assertNull(m.progressMax)
        assertTrue(m.segments.isEmpty()); assertTrue(m.points.isEmpty())
        assertNull(m.appIcon); assertNull(m.maneuver); assertNull(m.tracker); assertNull(m.startIcon); assertNull(m.endIcon)
    }

    @Test fun `localized text is kept whole without parsing units or losing separators`() {
        val m = parse(note("２００ m・右折", "中央通りへ\n次の交差点"))!!
        assertEquals("２００ m・右折", m.title)
        assertEquals("中央通りへ\n次の交差点", m.text)
    }

    @Test fun `progress is held to the bar as Android draws it, and indeterminate or no max is no tracker`() {
        // Android clamps the progress to 0..max (ProgressStyle.createProgressModel): the tracker at an end, not gone.
        assertEquals(0f, parse(route().apply { extras.putInt(Notification.EXTRA_PROGRESS, -1) })!!.fraction)
        assertEquals(1f, parse(route().apply { extras.putInt(Notification.EXTRA_PROGRESS, 101) })!!.fraction)
        for (max in listOf(0, -1)) assertNull(parse(route().apply { extras.putInt(Notification.EXTRA_PROGRESS_MAX, max) })!!.fraction)
        assertNull(parse(route().apply { extras.remove(Notification.EXTRA_PROGRESS) })!!.fraction)
        assertNull(parse(route().apply { extras.remove(Notification.EXTRA_PROGRESS_MAX) })!!.fraction)
        assertNull(parse(route().apply { extras.putBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, true) })!!.fraction)
        assertEquals(0f, parse(route().apply { extras.putInt(Notification.EXTRA_PROGRESS, 0) })!!.fraction)
        assertEquals(1f, parse(route().apply { extras.putInt(Notification.EXTRA_PROGRESS, 100) })!!.fraction)
    }

    @Test fun `ordinary measured progress works without inventing traffic segments`() {
        val m = parse(note().apply { extras.putInt(Notification.EXTRA_PROGRESS, 3); extras.putInt(Notification.EXTRA_PROGRESS_MAX, 4) })!!
        assertEquals(0.75f, m.fraction!!, 0f)
        assertTrue(m.segments.isEmpty())
    }

    @Test fun `a malformed segment drops the route geometry instead of shortening it`() {
        for (length in listOf(0, -1, Int.MAX_VALUE)) {
            val m = parse(route().apply { extras.putParcelableArrayList(NavigationNotification.SEGMENTS, arrayListOf(segment(40, Color.GRAY), segment(length, Color.BLUE))) })!!
            assertTrue(m.segments.isEmpty())
        }
    }

    @Test fun `a default colour is the notification's own colour, and stays default where it has none`() {
        val segments = arrayListOf(Bundle().apply { putInt("length", 100); putInt("color", Color.RED) })
        val m = parse(route().apply { extras.putParcelableArrayList(NavigationNotification.SEGMENTS, segments) })!!
        assertEquals(0, m.segments.single().color)
        assertNull(m.tracker)
        val coloured = parse(route().apply { color = 0xFF34A853.toInt(); extras.putParcelableArrayList(NavigationNotification.SEGMENTS, segments) })!!
        assertEquals(0xFF34A853.toInt(), coloured.segments.single().color)
        assertEquals(0xFF34A853.toInt(), parse(route().apply { color = 0xFF34A853.toInt(); extras.putParcelableArrayList(NavigationNotification.POINTS, arrayListOf(point(10, 0))) })!!.points.single().color)
        // Any colour with no alpha is the default too (Android's sanitizeProgressColor), and a transparent
        // notification colour is none.
        val clear = arrayListOf(segment(100, 0x00FF0000))
        assertEquals(0, parse(route().apply { extras.putParcelableArrayList(NavigationNotification.SEGMENTS, clear) })!!.segments.single().color)
        assertEquals(0, parse(route().apply { color = 0x0034A853; extras.putParcelableArrayList(NavigationNotification.SEGMENTS, clear) })!!.segments.single().color)
        // A colour of its own is kept.
        assertEquals(Color.YELLOW, parse(route().apply { color = 0xFF34A853.toInt() })!!.segments[1].color)
    }

    @Test fun `the text falls back to the big text, and the words are kept whole`() {
        val n = note(text = "").apply { extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "toward Example Way") }
        assertEquals("toward Example Way", parse(n)!!.text)
    }

    @Test fun `a picture whose pixels are the slot's last is not encoded again`() {
        val art = NavigationNotification.Art()
        val first = art.png("maneuver", image(Color.WHITE))!!
        assertSame(first, art.png("maneuver", image(Color.WHITE)))
        val other = art.png("maneuver", image(Color.BLACK))!!
        assertNotSame(first, other)
        assertNotSame(other, art.png("tracker", image(Color.BLACK)))
        art.clear()
        assertNotSame(other, art.png("maneuver", image(Color.BLACK)))
        // Bigger pictures go at 256 at most along their longer side.
        val big = art.png("x", Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888))!!
        val back = android.graphics.BitmapFactory.decodeByteArray(big, 0, big.size)
        assertEquals(256, back.width); assertEquals(128, back.height)
    }

    @Test fun `the debug shape says names and counts, never the words`() {
        val shape = NavigationNotification.shape(route().apply { extras.putString("Private address", "secret") })
        assertFalse(shape.contains("Private address"))
        assertTrue(shape.contains(NavigationNotification.SEGMENTS)); assertTrue(shape.contains("segments 3"))
        for (word in listOf("Turn right", "Example Way", "08:16", "2 km")) assertFalse(word, shape.contains(word))
    }

    @Test fun `malformed field types are ignored, and only the points Android draws are kept`() {
        val m = parse(route().apply {
            extras.putString(Notification.EXTRA_PROGRESS, "half")
            extras.putString(NavigationNotification.SEGMENTS, "not a list")
            extras.putParcelableArrayList(NavigationNotification.POINTS, arrayListOf(point(-1, Color.RED), point(101, Color.BLUE), point(0, Color.WHITE), point(100, Color.WHITE), point(40, Color.GREEN)))
        })!!
        assertNull(m.fraction); assertTrue(m.segments.isEmpty())
        // Out of the bar, or at either of its ends (Android does not show those): dropped.
        assertEquals(listOf(NavFace.Point(40, Color.GREEN)), m.points)
        // Four at most, the first four, as Android draws them.
        val many = parse(route().apply { extras.putParcelableArrayList(NavigationNotification.POINTS, ArrayList((1..6).map { point(it * 10, Color.WHITE) })) })!!
        assertEquals(listOf(10, 20, 30, 40), many.points.map { it.position })
    }

    @Test fun `real icon sources pass through unchanged and a failed icon loader remains empty`() {
        val app = image(Color.GREEN); val maneuver = image(Color.WHITE); val tracker = image(Color.BLUE)
        val start = image(Color.YELLOW); val end = image(Color.RED)
        val icons = listOf(maneuver, tracker, start, end).map(Icon::createWithBitmap)
        val n = Notification.Builder(context, "route").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setCategory(Notification.CATEGORY_NAVIGATION).setOngoing(true).setContentTitle("Turn right").setLargeIcon(icons[0]).build().apply {
                extras.putParcelable("android.progressTrackerIcon", icons[1]); extras.putParcelable("android.progressStartIcon", icons[2]); extras.putParcelable("android.progressEndIcon", icons[3])
            }
        val m = NavigationNotification.parse("route", maps, n, app) { icon -> listOf(maneuver, tracker, start, end)[icons.indexOf(icon)] }!!
        assertSame(app, m.appIcon); assertSame(maneuver, m.maneuver); assertSame(tracker, m.tracker); assertSame(start, m.startIcon); assertSame(end, m.endIcon)
        val failed = NavigationNotification.parse("route", maps, n) { throw IllegalStateException("unavailable") }!!
        assertNull(failed.maneuver); assertNull(failed.tracker); assertNull(failed.startIcon); assertNull(failed.endIcon)
    }

    @Suppress("DEPRECATION")
    @Test fun `legacy bitmap maneuver is retained`() {
        val maneuver = image(Color.WHITE)
        val m = parse(note().apply { extras.putParcelable(Notification.EXTRA_LARGE_ICON, maneuver) })!!
        assertSame(maneuver, m.maneuver)
    }

    @Test fun `transport preserves facts demo and supplied art through a parcel`() {
        val m = parse(route())!!.copy(appIcon = image(Color.GREEN), maneuver = image(Color.WHITE), tracker = image(Color.BLUE), startIcon = image(Color.YELLOW), endIcon = image(Color.RED), styledByProgress = false, demo = true)
        val packet = NavigationNotification.toBundle(m)
        assertEquals(NavigationNotification.KIND, packet.getString("kind"))
        val p = Parcel.obtain()
        try {
            p.writeBundle(packet); p.setDataPosition(0)
            val decoded = NavigationNotification.fromBundle(p.readBundle(javaClass.classLoader)!!)!!
            assertEquals(m.copy(appIcon = null, maneuver = null, tracker = null, startIcon = null, endIcon = null), decoded.copy(appIcon = null, maneuver = null, tracker = null, startIcon = null, endIcon = null))
            assertEquals(Color.BLUE, decoded.tracker!!.getPixel(4, 4))
            assertEquals(Color.WHITE, decoded.maneuver!!.getPixel(4, 4))
            assertTrue(NavigationNotification.samePacket(packet, NavigationNotification.toBundle(decoded)))
        } finally { p.recycle() }
        assertNull(NavigationNotification.fromBundle(NavigationNotification.toBundle(null)))
        assertEquals("navigation", NavigationNotification.toBundle(null).getString("kind"))
    }

    @Test fun `the shell decodes a picture once while its bytes stay the same, and the next step's page is the same page`() {
        val images = NavigationNotification.Images()
        val m = parse(route())!!.copy(maneuver = image(Color.WHITE), appIcon = image(Color.GREEN))
        val first = NavigationNotification.fromBundle(NavigationNotification.toBundle(m), images)!!
        val again = NavigationNotification.fromBundle(NavigationNotification.toBundle(m.copy(maneuver = image(Color.WHITE))), images)!!
        assertSame(first.maneuver, again.maneuver); assertSame(first.appIcon, again.appIcon)
        assertEquals(first, again)
        // The next step: another place on the route, the same pictures; a new arrow is decoded.
        val step = NavigationNotification.fromBundle(NavigationNotification.toBundle(m.copy(fraction = .5f)), images)!!
        assertSame(first.maneuver, step.maneuver); assertNotEquals(first, step)
        val turn = NavigationNotification.fromBundle(NavigationNotification.toBundle(m.copy(maneuver = image(Color.YELLOW))), images)!!
        assertNotSame(first.maneuver, turn.maneuver); assertEquals(Color.YELLOW, turn.maneuver!!.getPixel(2, 2))
        // A picture no longer sent is gone.
        assertNull(NavigationNotification.fromBundle(NavigationNotification.toBundle(m.copy(maneuver = null)), images)!!.maneuver)
    }

    @Test fun `identical data and independently encoded identical art are not a redraw`() {
        val m = parse(route())!!.copy(appIcon = image(Color.GREEN))
        assertTrue(NavigationNotification.samePacket(NavigationNotification.toBundle(m), NavigationNotification.toBundle(m.copy(appIcon = image(Color.GREEN)))))
        assertFalse(NavigationNotification.samePacket(NavigationNotification.toBundle(m), NavigationNotification.toBundle(m.copy(text = "Continue"))))
        assertFalse(NavigationNotification.samePacket(NavigationNotification.toBundle(m), NavigationNotification.toBundle(m.copy(appIcon = image(Color.BLUE)))))
    }

    @Test fun `selection keeps a usable route and falls back when its contents disappear`() {
        val routes = NavigationNotification.Routes()
        fun model(key: String, pkg: String = maps, n: Notification = note()) =
            NavigationNotification.parse(key, pkg, n)
        val empty = note("", "").apply {
            extras.remove(NavigationNotification.SHORT)
            extras.remove(Notification.EXTRA_SUB_TEXT)
        }
        routes.replace(listOfNotNull(model("b", "com.waze"), model("a"), model("0", n = empty)))
        assertEquals("a", routes.selected)
        routes.posted("b", model("b", "com.waze", note("Continue")))
        assertEquals("a", routes.selected)
        routes.posted("a", model("a", n = note("Turn left")))
        assertEquals("Turn left", routes.current()!!.title)
        // A still-ongoing update with no route facts cannot shadow Waze.
        routes.posted("a", model("a", n = empty))
        assertEquals("b", routes.selected)
        assertEquals("Continue", routes.current()!!.title)
        routes.posted("call", model("call", "phone", note().apply { category = Notification.CATEGORY_CALL }))
        assertEquals("b", routes.selected)
        routes.posted("b", model("b", "com.waze", note().apply { flags = 0 }))
        assertNull(routes.current())
        routes.replace(listOfNotNull(model("a")))
        assertEquals("a", routes.selected)
        routes.replace(emptyList())
        assertNull(routes.current())
    }

    @Test fun `an ETA or maneuver can survive missing instruction fields`() {
        val n = note("", "").apply { extras.remove(NavigationNotification.SHORT) }
        assertEquals("08:16", parse(n)!!.eta)
        n.extras.remove(Notification.EXTRA_SUB_TEXT)
        n.extras.putParcelable(Notification.EXTRA_LARGE_ICON, image(Color.WHITE))
        assertNotNull(parse(n)!!.maneuver)
    }

    @Test fun `different maneuver pixels with the same polynomial hash still update the image`() {
        val first = intArrayOf(0xff000010.toInt(), 0xff000040.toInt())
        val second = intArrayOf(first[0] + 1, first[1] - 31)
        assertEquals(first.contentHashCode(), second.contentHashCode())
        val art = NavigationNotification.Art()
        fun png(pixels: IntArray) = art.png("maneuver", Bitmap.createBitmap(pixels, 2, 1, Bitmap.Config.ARGB_8888))!!
        assertFalse(png(first).contentEquals(png(second)))
    }
}
