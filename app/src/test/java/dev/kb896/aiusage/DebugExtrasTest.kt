package dev.kb896.aiusage

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The lock as a screen meets it ([DebugExtras.of]): the intent, the token file in the app's external files folder and
 * the screen's saved state. A unit test runs a debug build, so the lock is on here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugExtrasTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = listOf("demo", "unpin", "slow")
    private lateinit var file: File

    /** A token of its own for each use: the tokens let in are kept for the whole test process. */
    private fun token() = java.util.UUID.randomUUID().toString().replace("-", "")

    private fun written(t: String, age: Long = 1_000L): String {
        file.writeText("$t\n")
        assertTrue(file.setLastModified(System.currentTimeMillis() - age))
        return t
    }

    private fun intent(t: String?, vararg extras: Pair<String, Boolean>) = Intent(Intent.ACTION_SEND).apply {
        setClassName(context, "dev.kb896.aiusage.ui.PinActivity")
        putExtra(Intent.EXTRA_TEXT, "a note")
        for ((k, v) in extras) putExtra(k, v)
        if (t != null) putExtra(DebugLock.EXTRA_TOKEN, t)
    }

    @Before fun setUp() {
        assertTrue("a unit test runs a debug build", BuildConfig.DEBUG)
        file = File(context.getExternalFilesDir(null), DebugLock.FILE)
        file.delete()
    }

    @After fun tearDown() { file.delete() }

    @Test fun `the shell's fresh token lets the extras in and is spent`() {
        val t = written(token())
        val i = intent(t, "demo" to true).putExtra("slow", 4f)
        val d = DebugExtras.of(context, i, names)
        assertTrue(d.open)
        assertTrue(d.bool("demo"))
        assertEquals(4f, d.float("slow", 1f))
        assertFalse("the file is gone once used", file.exists())
    }

    @Test fun `the same token twice is refused, its file written again or not`() {
        val t = written(token())
        val i = intent(t, "demo" to true)
        assertTrue(DebugExtras.of(context, i, names).open)
        val again = DebugExtras.of(context, i, names)
        assertFalse("no file", again.open)
        assertFalse("its extras read as in a release build", again.bool("demo"))
        written(t)
        assertFalse("the same token in a new file", DebugExtras.of(context, i, names).open)
        assertTrue("a used token does not spend the file", file.exists())
    }

    @Test fun `a wrong token is refused and leaves the shell's file`() {
        val t = written(token())
        val d = DebugExtras.of(context, intent(token(), "unpin" to true), names)
        assertFalse(d.open)
        assertFalse(d.bool("unpin"))
        assertEquals("the shell's own is still there", t, file.readText().trim())
        assertTrue("and still works", DebugExtras.of(context, intent(t, "unpin" to true), names).open)
    }

    @Test fun `no token in the intent is refused`() {
        written(token())
        val d = DebugExtras.of(context, intent(null, "demo" to true), names)
        assertFalse(d.open)
        assertFalse(d.bool("demo"))
        assertTrue(file.exists())
    }

    @Test fun `a token over a minute old is refused and thrown away`() {
        val t = written(token(), age = DebugLock.MAX_AGE_MS + 1_000L)
        val d = DebugExtras.of(context, intent(t, "demo" to true), names)
        assertFalse(d.open)
        assertFalse(d.bool("demo"))
        assertFalse("a stale file goes", file.exists())
    }

    @Test fun `an intent with no debug extra leaves the file alone`() {
        val t = written(token())
        val plain = DebugExtras.of(context, intent(t), names)
        assertFalse(plain.open)
        assertTrue("the file is not spent", file.exists())
        val other = DebugExtras.of(context, intent(t, "not_a_debug_extra" to true), names)
        assertFalse("an extra this screen does not take asks for nothing", other.open)
        assertTrue(file.exists())
        assertTrue("the token is still good for the shell's own start", DebugExtras.of(context, intent(t, "demo" to true), names).open)
    }

    @Test fun `a screen made again keeps what it was let in to, and nothing more`() {
        val t = written(token())
        val i = intent(t, "demo" to true)
        val first = DebugExtras.of(context, i, names)
        val saved = Bundle().also(first::save)
        val again = DebugExtras.of(context, i, names, saved)
        assertTrue("re-opened from its saved state, with the token spent", again.open)
        assertTrue(again.bool("demo"))
        val refused = DebugExtras.of(context, intent(token(), "demo" to true), names)
        val nothing = Bundle().also(refused::save)
        assertTrue("a screen not let in saves nothing", nothing.isEmpty)
        assertFalse(DebugExtras.of(context, intent(null, "demo" to true), names, nothing).open)
    }
}
