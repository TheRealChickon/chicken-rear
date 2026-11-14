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
    private fun marks(m: UsageFace.Model): List<Int> {
        val bmp = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888)
        UsageFace.draw(Canvas(bmp), 976f, 596f, m, now, 10_000L, null) { mark }
        val xs = ArrayList<Int>()
        for (y in 0 until 596 step 2) for (x in 0 until 976 step 2) {
            val c = bmp.getPixel(x, y)
            if (Color.blue(c) > 150 && Color.green(c) > 150 && Color.red(c) < 60) xs += x
        }
        return xs
    }

    @Test fun `ChatGPT takes the second half of the page, in the same vessels, with a mascot of its own`() {
        val m = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL, 0.3f, 0.5f), status(Account.CHATGPT, 0.37f, 0.62f))), now)
        assertEquals(2, m.columns.size)
        assertEquals(listOf("com.anthropic.claude", "com.openai.chatgpt"), m.columns.map { it.app })
        assertEquals(listOf(0.37f, 0.62f), m.columns[1].limits.map { it.used })
        assertEquals(MascotMood.WALK, m.columns[1].mood)
        val tanks = UsageFace.tanks(m)
        assertEquals("two vessels each", listOf(0, 0, 1, 1), tanks.map { it.account })
    }

    @Test fun `two apps together are told apart by their icons, one in each account's first vessel`() {
        val m = UsageFace.model(DashboardState(now, listOf(status(Account.PERSONAL, 0.3f, 0.5f), status(Account.CHATGPT, 0.37f, 0.62f))), now)
        val xs = marks(m)
        val tanks = UsageFace.tanks(m)
        assertTrue("an icon in each half", xs.any { it < tanks[2].vessel.left } && xs.any { it >= tanks[2].vessel.left })
        // Inside the first vessel of each account, in its corner, clear of the lenses.
        assertTrue(xs.all { x -> tanks.filter { it.which == 0 }.any { x >= it.vessel.left && x <= it.vessel.left + 40f } })
        assertTrue(xs.min() >= 296)
    }

    @Test fun `one app alone needs no icon - Claude by itself, two Claude accounts, ChatGPT by itself`() {
        for (accounts in listOf(listOf(Account.PERSONAL), listOf(Account.PERSONAL, Account.WORK), listOf(Account.CHATGPT))) {
            val m = UsageFace.model(DashboardState(now, accounts.map { status(it, 0.3f, 0.5f) }), now)
            assertTrue("$accounts", marks(m).isEmpty())
        }
    }
}
