package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.ThreeFingers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What three fingers held do, by the setting, and only on a lit back of ours. */
class ThreeFingersActTest {

    private class Ran : ThreeFingersAct.Does {
        val calls = ArrayList<String>()
        override fun screenshot() { calls += "screenshot" }
        override fun recording() { calls += "recording" }
        override fun backOff() { calls += "backOff" }
    }

    @Test fun `each choice runs its one thing`() {
        val want = mapOf(
            ThreeFingers.SCREENSHOT to listOf("screenshot"),
            ThreeFingers.RECORDING to listOf("recording"),
            ThreeFingers.BACK_OFF to listOf("backOff"),
            ThreeFingers.NOTHING to emptyList(),
        )
        for ((choice, calls) in want) {
            val r = Ran()
            val said = ThreeFingersAct.run(choice, backOurs = true, does = r)
            assertEquals(choice.name, calls, r.calls)
            assertTrue(said.isNotBlank())
        }
    }

    @Test fun `on a back that is not lit or not ours nothing runs`() {
        for (choice in ThreeFingers.entries) {
            val r = Ran()
            val said = ThreeFingersAct.run(choice, backOurs = false, does = r)
            assertTrue(r.calls.isEmpty())
            assertTrue(said, said.startsWith("nothing"))
        }
    }

    private val rear = listOf("src/main/java/dev/kb896/aiusage/rear", "app/src/main/java/dev/kb896/aiusage/rear").map(::File).first { it.isDirectory }

    @Test fun `in the shell - the tile and three fingers take one screenshot path, the back off is the double tap and hold's`() {
        val shell = File(rear, "RearUserService.kt").readText()
        // One screenshot function, for both ways in, and one hand-over for it.
        assertEquals(1, Regex("""private fun screenshot\(""").findAll(shell).count())
        assertTrue(shell.contains("""screenshot("its tile")"""))
        assertTrue(shell.contains("""screenshot("three fingers", clearFirst = controlCenter.isUp)"""))
        assertTrue(shell.contains("handOverShot(path, name)"))
        // The recording is the tile's own toggle.
        assertTrue(shell.contains("""toggleRecording("three fingers")"""))
        assertTrue(shell.contains("""toggleRecording("its tile")"""))
        // The back off: one function, used by the double tap and hold and by three fingers.
        val off = shell.substringAfter("private fun backOffByHand()").substringBefore("\n    }")
        assertTrue(off, off.contains("lastDoubleTapAt = android.os.SystemClock.elapsedRealtime()"))
        assertTrue(off, off.contains("cmd power sleep --group-id"))
        assertEquals(2, Regex("""backOffByHand\(\)""").findAll(shell).count() - 1)
        // One log line a hold, said as "three fingers: …", and only on a lit back of ours.
        assertEquals(1, Regex("""Log\.i\(TAG, "three fingers: """).findAll(shell).count())
        assertTrue(shell.contains("val ours = faceWanted() && cover.isUp && s.lit && s.ours"))
    }
}
