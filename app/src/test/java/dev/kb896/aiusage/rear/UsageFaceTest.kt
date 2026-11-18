package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import dev.kb896.aiusage.data.Account
import dev.kb896.aiusage.data.AccountStatus
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.Health
import dev.kb896.aiusage.data.MascotMood
import dev.kb896.aiusage.data.UsageSnapshot
import dev.kb896.aiusage.data.UsageWindow
import dev.kb896.aiusage.data.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The usage page: what each account shows, where its vessels stand, what its mascot does and when, and the picture. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class UsageFaceTest {
    private val now = 1_800_000_000_000L
    private fun status(a: Account, health: Health = Health.OK, connected: Boolean = true, session: Float? = 0.42f, week: Float? = 0.71f, fable: Float = 0.27f, mood: String = MascotMood.WALK) = AccountStatus(
        a, health,
        if (session == null && week == null) null else UsageSnapshot(
            listOfNotNull(
                session?.let { UsageWindow(WindowKind.SESSION, "All 5h", it, now + 134 * 60_000L) },
                week?.let { UsageWindow(WindowKind.WEEKLY_ALL, "All 7d", it, now + (3 * 24 + 4) * 3_600_000L) },
                UsageWindow(WindowKind.WEEKLY_SCOPED, "Fable 7d", fable, now + (3 * 24 + 4) * 3_600_000L),
            ), now,
        ),
        mascot = mood, connected = connected,
    )

    @Test fun `how long until a limit starts again, in words`() {
        assertEquals("", UsageFace.until(null, now))
        assertEquals("now", UsageFace.until(now - 5_000, now))
        assertEquals("12m", UsageFace.until(now + 12 * 60_000L + 30_000, now))
        assertEquals("2h14m", UsageFace.until(now + 134 * 60_000L, now))
        assertEquals("3d4h", UsageFace.until(now + (3 * 24 + 4) * 3_600_000L + 60_000, now))
    }

    @Test fun `by default an account is its week and each model's own week, with its mascot's mood`() {
        val m = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL), status(Account.WORK, session = 0.08f, week = 0.23f, mood = MascotMood.SLEEP))), now)
        assertEquals(listOf("Week", "Fable"), m.columns[0].limits.map { it.name })
        assertEquals(listOf(false, true), m.columns[0].limits.map { it.model })
        assertEquals("3d4h", m.columns[0].limits[0].until)
        assertEquals(MascotMood.SLEEP, m.columns[1].mood)
        assertTrue(m.columns.none { it.split })
    }

    @Test fun `with the switch, an account is its five hours, its week, and each model's own week`() {
        val m = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL), status(Account.WORK, session = 0.08f, week = 0.23f, mood = MascotMood.SLEEP))), now, fiveHours = true)
        assertEquals(listOf("5 h", "Week", "Fable"), m.columns[0].limits.map { it.name })
        assertEquals(listOf(true, false, false), m.columns[0].limits.map { it.five })
        assertEquals(listOf(false, false, true), m.columns[0].limits.map { it.model })
        assertEquals("2h14m", m.columns[0].limits[0].until)
        assertTrue(m.columns.all { it.split })
    }

    @Test fun `an account with no week at all shows its five hours rather than an empty vessel`() {
        val m = UsageFace.model(DashboardState(now, listOf(AccountStatus(Account.PERSONAL, Health.OK, UsageSnapshot(listOf(UsageWindow(WindowKind.SESSION, "All 5h", 0.3f, now + 60_000L)), now), mascot = MascotMood.WALK))), now)
        assertEquals(listOf("5 h"), m.columns[0].limits.map { it.name })
        assertEquals("", m.columns[0].note)
        assertEquals(listOf(0), UsageFace.tanks(m).map { it.which })
    }

    @Test fun `the made-up page follows the switch too`() {
        assertTrue(UsageFace.demo(now).columns.all { c -> c.limits.none { it.five } })
        assertTrue(UsageFace.demo(now, fiveHours = true).columns.all { it.split })
    }

    @Test fun `nobody signed in is one button`() {
        val none = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL, connected = false), status(Account.WORK, connected = false))), now)
        assertTrue(none.columns.isEmpty())
        assertEquals("Sign in", none.empty)
        assertTrue(UsageFace.onSignIn(639f, 312f))
        assertTrue(!UsageFace.onSignIn(639f, 120f) && !UsageFace.onSignIn(380f, 312f))
    }

    private val one = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL, session = 0.04f, week = 0.18f))), now)
    private val two = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL), status(Account.WORK, session = 0.93f, week = 0.6f, mood = MascotMood.FLAG))), now)
    private val oneFive = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL, session = 0.04f, week = 0.18f))), now, fiveHours = true)
    private val twoFive = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL), status(Account.WORK, session = 0.93f, week = 0.6f, mood = MascotMood.FLAG))), now, fiveHours = true)

    @Test fun `all models are the neutral fluid, a model its own colour, and none is purple`() {
        assertEquals(UsageFace.ALL, UsageFace.color(0)); assertEquals(UsageFace.ALL, UsageFace.color(1))
        assertEquals("Fable: the first model's amber", 0xFFF2A93B.toInt(), UsageFace.color(2))
        // An account without its five hours: its week neutral, its first model amber.
        assertEquals(UsageFace.ALL, UsageFace.color(one.columns[0], 0))
        assertEquals(UsageFace.MODELS[0], UsageFace.color(one.columns[0], 1))
        assertEquals(UsageFace.ALL, UsageFace.color(oneFive.columns[0], 1))
        assertEquals(UsageFace.MODELS[0], UsageFace.color(oneFive.columns[0], 2))
        for (c in UsageFace.MODELS.toList() + UsageFace.ALL) {
            assertFalse("purple", Color.blue(c) > 120 && Color.red(c) > 90 && Color.blue(c) - Color.green(c) > 60)
            assertTrue("not the mascot's own colour, which would hide it", c != OriginalUsage.CLAUDE)
        }
    }

    @Test fun `by default one vessel an account, holding the week and the models - one account the whole panel, two a half each`() {
        for ((name, m) in mapOf("one account" to one, "two accounts" to two)) {
            val tanks = UsageFace.tanks(m)
            assertEquals("$name: one vessel each account", m.columns.indices.toList(), tanks.map { it.account })
            for (t in tanks) assertEquals("$name: the week and Fable in it", listOf(0, 1), t.limits)
            for (t in tanks) assertEquals(1, t.which)
            for (t in tanks) assertTrue("$name: a head inside its vessel", t.vessel.contains(t.reading))
            val boxes = tanks.map { it.vessel }
            for (b in boxes) assertTrue("$name: right of the lenses, inside the panel", b.left >= 326f && b.right <= 956f && b.top >= 20f && b.bottom <= 580f)
            for (i in boxes.indices) for (j in i + 1 until boxes.size) assertFalse("$name: $i and $j overlap", RectF.intersects(boxes[i], boxes[j]))
            assertEquals(UsageFace.LEFT, tanks.minOf { it.vessel.left }, 0.5f)
            assertEquals(UsageFace.RIGHT, tanks.maxOf { it.vessel.right }, 0.5f)
            // Two accounts: the same width each, a half of the room less the gap.
            assertEquals(boxes.first().width(), boxes.last().width(), 0.5f)
            if (boxes.size == 1) assertEquals(UsageFace.RIGHT - UsageFace.LEFT, boxes[0].width(), 0.5f)
            for (t in tanks) assertTrue("$name: the mascot fits across any vessel", UsageFace.mascotHeight(t.vessel.width()) * 1.3f <= t.vessel.width())
        }
    }

    @Test fun `with the switch, two vessels an account, the week's holding the week and the models, filling the panel right of the lenses`() {
        for ((name, m) in mapOf("one account" to oneFive, "two accounts" to twoFive)) {
            val tanks = UsageFace.tanks(m)
            assertEquals("$name: the five hours and the week, each account", m.columns.size * 2, tanks.size)
            for (t in tanks.filter { it.which == 0 }) assertEquals(listOf(0), t.limits)
            for (t in tanks.filter { it.which == 1 }) assertEquals("the week and Fable in one vessel", listOf(1, 2), t.limits)
            for (t in tanks) assertTrue("$name: a head inside its vessel", t.vessel.contains(t.reading))
            val boxes = tanks.map { it.vessel }
            for (b in boxes) assertTrue("$name: right of the lenses, inside the panel", b.left >= 326f && b.right <= 956f && b.top >= 20f && b.bottom <= 580f)
            for (i in boxes.indices) for (j in i + 1 until boxes.size) assertFalse("$name: $i and $j overlap", RectF.intersects(boxes[i], boxes[j]))
            assertEquals(UsageFace.LEFT, tanks.minOf { it.vessel.left }, 0.5f)
            assertEquals(UsageFace.RIGHT, tanks.maxOf { it.vessel.right }, 0.5f)
            for (t in tanks) assertTrue("$name: the mascot fits across any vessel", UsageFace.mascotHeight(t.vessel.width()) * 1.3f <= t.vessel.width())
        }
    }

    @Test fun `the page is drawn with no rings, no purple, no mascot on a figure, and two colours in the week`() {
        File("build").mkdirs()
        for ((name, m) in mapOf("one" to one, "two" to two, "one-five" to oneFive, "two-five" to twoFive, "demo" to UsageFace.demo(now), "demo-five" to UsageFace.demo(now, fiveHours = true), "empty" to UsageFace.model(DashboardState.EMPTY, now))) {
            val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
            UsageFace.draw(Canvas(bmp), 976f, 596f, m, now, sinceShown = 5_000L)
            File("build/usage-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Nothing at all left of the vessels: the lenses' place is bare black (the rings are gone).
            for (y in 0 until 596 step 3) for (x in 0 until 320 step 3) assertEquals("$name: black at $x,$y", Color.BLACK, bmp.getPixel(x, y))
            var purple = 0
            for (y in 0 until 596 step 2) for (x in 0 until 976 step 2) {
                val p = bmp.getPixel(x, y)
                if (Color.blue(p) > 120 && Color.red(p) > 90 && Color.blue(p) - Color.green(p) > 60 && Color.red(p) - Color.green(p) > 30) purple++
            }
            assertEquals("$name: purple pixels", 0, purple)
            if (m.columns.isEmpty()) continue
            var mascot = 0
            for (t in UsageFace.tanks(m)) {
                for (y in t.reading.top.toInt() until t.reading.bottom.toInt() step 2) for (x in t.reading.left.toInt() until t.reading.right.toInt() step 2) {
                    assertFalse("$name: the mascot on a figure at $x,$y", bmp.getPixel(x, y) and 0xFFFFFF == OriginalUsage.CLAUDE and 0xFFFFFF)
                }
                for (y in t.reading.bottom.toInt() until t.vessel.bottom.toInt() step 2) for (x in t.vessel.left.toInt() until t.vessel.right.toInt() step 2) {
                    if (bmp.getPixel(x, y) and 0xFFFFFF == OriginalUsage.CLAUDE and 0xFFFFFF) mascot++
                }
            }
            assertTrue("$name: the mascot, in Claude's colour, in a vessel", mascot > 40)
        }
        // One account, week 18% and Fable 27%: in the week's vessel, amber above the neutral's surface, neutral below it.
        val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
        UsageFace.draw(Canvas(bmp), 976f, 596f, one, now, sinceShown = 5_000L)
        val week = UsageFace.tanks(one).first { it.which == 1 }.vessel
        val x = (week.right - 30f).toInt()
        assertEquals("Fable's amber, between the two surfaces", UsageFace.MODELS[0] and 0xFFFFFF, bmp.getPixel(x, (week.bottom - 0.23f * week.height()).toInt()) and 0xFFFFFF)
        assertEquals("all models' neutral, under its own surface", UsageFace.ALL and 0xFFFFFF, bmp.getPixel(x, (week.bottom - 0.08f * week.height()).toInt()) and 0xFFFFFF)
    }

    @Test fun `walking, hopping or in a cycle, the mascot is never on a figure, never outside the panel's room, never purple`() {
        File("build").mkdirs()
        val sheet = Bitmap.createBitmap(976 / 2 * 4, 596 / 2 * 4, Bitmap.Config.ARGB_8888)
        val onSheet = Canvas(sheet)
        var cell = 0
        for (mood in listOf(MascotMood.WALK, MascotMood.MUSCLE, MascotMood.FLAG, MascotMood.SLEEP, MascotMood.CONFETTI)) {
            // The week comes to matter more half way, so he hops over to it.
            for (step in 0 until 96) {
                val t = step * 250L
                val m = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL, mood = mood, fable = if (step < 48) 0.27f else 0.93f))), now)
                val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
                UsageFace.draw(Canvas(bmp), 976f, 596f, m, now + t, sinceShown = t)
                var body = 0
                for (y in 0 until 596 step 2) for (x in 0 until 976 step 2) {
                    val p = bmp.getPixel(x, y)
                    assertFalse("$mood at $t ms: purple or pink at $x,$y", Color.blue(p) > Color.green(p) + 40 && Color.red(p) > Color.green(p) + 30 && Color.blue(p) > 110)
                    if (p and 0xFFFFFF == OriginalUsage.CLAUDE and 0xFFFFFF) {
                        body++
                        assertTrue("$mood at $t ms: the mascot left of the vessels at $x,$y", x >= UsageFace.LEFT - 2)
                        assertTrue("$mood at $t ms: the mascot in a vessel's head at $x,$y", y >= UsageFace.TOP + UsageFace.HEAD)
                    }
                }
                assertTrue("$mood at $t ms: he is there ($body)", body > 60)
                if (mood == MascotMood.WALK && step % 6 == 0 && cell < 16) {
                    onSheet.drawBitmap(bmp, null, android.graphics.Rect(cell % 4 * 488, cell / 4 * 298, cell % 4 * 488 + 488, cell / 4 * 298 + 298), null)
                    cell++
                }
            }
        }
        File("build/usage-mascot-over-time.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
