package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The log watcher: which processes on the phone are orphans of ours, and which are never touched. */
class LogWatchTest {
    // As a phone lists them (shortened): ours under init, ours with a living parent, and other shell scripts of the
    // user's with their own logcat and getevent.
    private val ps = """
          PID  PPID ARGS
         2448     1 logcat -b all -T 1 -v brief ActivityTaskManager:I *:S
         4461     1 logcat -b events -T 1 -v brief input_focus:I *:S
         4750     1 logcat -b all -T 1 -v brief ActivityTaskManager:I input_focus:I *:S
        10489     1 logcat -b all -T 1 -v brief ActivityTaskManager:I wm_set_resumed_activity:I wm_task_moved:I wm_on_top_resumed_gained_called:I *:S
        16835     1 dev.chicken.rear.dev:guard
        16878 16835 logcat -b events -T 1 -v brief wm_set_resumed_activity:I wm_task_moved:I wm_on_top_resumed_gained_called:I *:S
        23782     1 sh /data/local/tmp/gesture-a.sh
        23891 23782 getevent -l /dev/input/event7
        25264 25151 logcat -b all -T 1 -v brief ActivityTaskManager:I wm_set_resumed_activity:I wm_task_moved:I wm_on_top_resumed_gained_called:I *:S
        26017     1 sh /data/local/tmp/gesture-b.sh
        26037 26017 logcat -T 1 -v brief hyper_launcher_app:I *:S -e Starting long press detection|Long press condition met
        29125     1 logcat -b all -T 1 -v brief ActivityTaskManager:I MiuiSubscreenDoubleTapGesture:I *:S
        30001     1 logcat -T 1 -v brief hyper_launcher_app:I *:S -e Starting long press detection
        30002     1 logcat -b all -T 1 -v brief ActivityTaskManager:I *:S -e something
        30003     1 logcat -b main -T 1 -v brief ActivityTaskManager:I *:S
    """.trimIndent()

    @Test fun `only a watcher of ours, orphaned under init, is ended`() {
        assertEquals(listOf(2448, 4461, 4750, 10489, 29125), LogWatch.stale(ps))
    }

    @Test fun `nothing is ended where there is nothing of ours`() {
        assertEquals(emptyList<Int>(), LogWatch.stale(""))
        assertEquals(emptyList<Int>(), LogWatch.stale("  PID  PPID ARGS\n    1     0 init second_stage"))
        var killed = ""
        assertEquals(0, LogWatch.sweep { cmd -> if (cmd.startsWith("kill")) killed = cmd; "" })
        assertEquals("no kill was run", "", killed)
    }

    @Test fun `a sweep ends them by their ids`() {
        val ran = ArrayList<String>()
        assertEquals(5, LogWatch.sweep { cmd -> ran += cmd; if (cmd == LogWatch.PS) ps else "" })
        assertEquals(listOf(LogWatch.PS, "kill 2448 4461 4750 10489 29125"), ran)
    }

    @Test fun `a watcher is tied to its reader, and one it starts is one a later sweep would know`() {
        val cmd = LogWatch.command("events", listOf("wm_set_resumed_activity:I", "wm_task_moved:I"))
        assertTrue("logcat in the background, its end tied to the shell's", cmd.startsWith("logcat -b events -T 1 -v brief wm_set_resumed_activity:I wm_task_moved:I '*:S' & L=\$!"))
        assertTrue("the shell waits on its input, which is the reader's life", cmd.contains("while read _; do :; done; kill \$L"))
        // As ps shows it (the quotes gone), under init: known as ours.
        assertEquals(listOf(7), LogWatch.stale("7 1 logcat -b events -T 1 -v brief wm_set_resumed_activity:I wm_task_moved:I *:S"))
    }

    @Test fun `the system and event logs' watcher and a pin's short main-log watcher are ours, a main-log watcher of anything else is not`() {
        val focus = LogWatch.command("system,events", listOf("ActivityTaskManager:I", "wm_set_resumed_activity:I", "wm_task_moved:I", "wm_on_top_resumed_gained_called:I"))
        assertTrue(focus.startsWith("logcat -b system,events -T 1 -v brief ActivityTaskManager:I "))
        val pin = LogWatch.command("main", listOf("SubScreenCenter_PinAnimationSurfaceView:W", "SubScreenCenter_SmartAssistantPanel:W"))
        assertTrue(pin.startsWith("logcat -b main -T 1 -v brief SubScreenCenter_PinAnimationSurfaceView:W SubScreenCenter_SmartAssistantPanel:W '*:S'"))
        val listing = """
              PID  PPID ARGS
               11     1 logcat -b system,events -T 1 -v brief ActivityTaskManager:I wm_set_resumed_activity:I wm_task_moved:I wm_on_top_resumed_gained_called:I *:S
               12     1 logcat -b main -T 1 -v brief SubScreenCenter_PinAnimationSurfaceView:W SubScreenCenter_SmartAssistantPanel:W *:S
               13     1 logcat -b all -T 1 -v brief ActivityTaskManager:I wm_set_resumed_activity:I wm_task_moved:I wm_on_top_resumed_gained_called:I SubScreenCenter_PinAnimationSurfaceView:W SubScreenCenter_SmartAssistantPanel:W *:S
               14     1 logcat -b main -T 1 -v brief ActivityTaskManager:I *:S
               15  9000 logcat -b main -T 1 -v brief SubScreenCenter_PinAnimationSurfaceView:W SubScreenCenter_SmartAssistantPanel:W *:S
               16     1 logcat -b system -T 1 -v brief ActivityTaskManager:I *:S
        """.trimIndent()
        // 13: the build before this one's watcher (all logs, the pin's tags at W): an orphan of ours too.
        assertEquals(listOf(11, 12, 13), LogWatch.stale(listing))
    }

    @Test fun `only a buffer's name, or names by commas, is taken`() {
        LogWatch.command("system,events", listOf("wm_task_moved:I"))
        for (bad in listOf("", "main;reboot", "all events")) {
            assertTrue(bad, runCatching { LogWatch.command(bad, listOf("wm_task_moved:I")) }.isFailure)
        }
    }
}
