package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.kb896.aiusage.data.Account
import dev.kb896.aiusage.data.AccountStatus
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.Health
import dev.kb896.aiusage.data.MascotMood
import dev.kb896.aiusage.data.UsageSnapshot
import dev.kb896.aiusage.data.UsageWindow
import dev.kb896.aiusage.data.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ChatGPT beside Claude on the usage page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class UsageFaceAppsTest {
    private val now = 1_800_000_000_000L
    private fun status(a: Account, five: Float, week: Float) = AccountStatus(
        a, Health.OK,
        UsageSnapshot(listOf(UsageWindow(WindowKind.SESSION, "5 h", five, now + 3_600_000L), UsageWindow(WindowKind.WEEKLY_ALL, "Week", week, now + 86_400_000L)), now),
        null, MascotMood.WALK,
    )
    // A made-up "icon" for this test's arithmetic only: one colour nothing else on the page has.
    private val mark = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF00FFFF.toInt()) }
    private fun marks(m: UsageFace.Model, icon: (String) -> Bitmap? = { mark }): List<Int> {
        val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
        UsageFace.draw(Canvas(bmp), 976f, 596f, m, now, 10_000L, null, icon = icon)
        val xs = ArrayList<Int>()
        for (y in 0 until 596 step 2) for (x in 0 until 976 step 2) {
            val c = bmp.getPixel(x, y)
            if (Color.blue(c) > 150 && Color.green(c) > 150 && Color.red(c) < 60) xs += x
        }
        bmp.recycle()
        return xs
    }

    @Test fun `ChatGPT takes the second half of the page - by default its week alone, like Claude's`() {
        val m = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL, 0.3f, 0.5f), status(Account.CHATGPT, 0.37f, 0.62f))), now)
        assertEquals(2, m.columns.size)
        assertEquals(listOf("com.anthropic.claude", "com.openai.chatgpt"), m.columns.map { it.app })
        assertEquals(listOf(0.5f), m.columns[0].limits.map { it.used })
        assertEquals(listOf(0.62f), m.columns[1].limits.map { it.used })
        assertEquals(listOf(false, true), m.columns.map { it.chatGpt })
        assertEquals(MascotMood.WALK, m.columns[1].mood)
        assertEquals("one vessel each", listOf(0, 1), UsageFace.tanks(m).map { it.account })
        val five = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL, 0.3f, 0.5f), status(Account.CHATGPT, 0.37f, 0.62f))), now, fiveHours = true)
        assertEquals(listOf(0.37f, 0.62f), five.columns[1].limits.map { it.used })
        assertEquals("with the switch, two vessels each", listOf(0, 0, 1, 1), UsageFace.tanks(five).map { it.account })
    }

    @Test fun `the installed ChatGPT icon renders inside its usage vessel with or without Claude`() {
        for (accounts in listOf(listOf(Account.CHATGPT), listOf(Account.PERSONAL, Account.CHATGPT))) {
            for (five in listOf(false, true)) {
                val m = UsageFace.model(DashboardState(now, accounts.map { status(it, 0.3f, 0.5f) }), now, fiveHours = five)
                val week = UsageFace.tanks(m).last()
                val xs = marks(m) { pkg -> mark.takeIf { pkg == "com.openai.chatgpt" } }
                assertTrue(xs.size > 400)
                assertTrue(xs.all { it > week.vessel.left && it < week.vessel.right })
                assertTrue(marks(m) { null }.isEmpty())
            }
        }
    }

    @Test fun `Claude's app icon appears only in the mixed-provider header`() {
        for (accounts in listOf(listOf(Account.PERSONAL), listOf(Account.PERSONAL, Account.WORK))) {
            val m = UsageFace.model(DashboardState(now, accounts.map { status(it, 0.3f, 0.5f) }), now)
            assertTrue(marks(m).isEmpty())
        }
        val m = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL, 0.3f, 0.5f), status(Account.CHATGPT, 0.37f, 0.62f))), now)
        val left = UsageFace.tanks(m).first().vessel.left
        val xs = marks(m) { pkg -> mark.takeIf { pkg == Account.PERSONAL.app } }
        assertTrue(xs.isNotEmpty())
        assertTrue(xs.all { it >= left && it <= left + 40f })
    }
}
