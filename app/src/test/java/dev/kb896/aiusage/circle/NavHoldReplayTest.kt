package dev.kb896.aiusage.circle

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Deterministic replay of synthetic navigation-bar and touch events. */
class NavHoldReplayTest {
    private class Replay {
        var now = 0L
        val sent = mutableListOf<String>()
        val gesture = NavHold { action, due -> sent += if (action == "ARM") "$action@$now:$due" else "$action@$now" }
        val touch = FrontTouches({ slot, x, y -> gesture.down(slot, x, y, now) },
            { slot, moved, age -> gesture.depart(slot, moved, age, now) })

        fun at(time: Int) {
            val timer = gesture.armTime()
            if (timer != null && timer <= time) {
                now = maxOf(now, timer)
                gesture.tick(now)
            }
            now = time.toLong()
        }
        fun log(time: Int, line: String) { at(time); gesture.launcher(line, now) }
        fun start(time: Int, x: Int = 600, y: Int = 2590, hot: Boolean = true) =
            log(time, "Starting long press detection at ($x.0, $y.0), in_hot_zone: $hot")
        fun mark(time: Int) = log(time, "Long press condition met, triggering")

        fun raw(key: String, value: String, stamp: Int? = now.toInt()) {
            val prefix = if (stamp == null) "" else String.format(Locale.ROOT, "[ %d.%03d000] ", 1000 + stamp / 1000, stamp % 1000)
            val type = if (key.startsWith("SYN")) "EV_SYN" else if (key == "BTN_TOUCH") "EV_KEY" else "EV_ABS"
            touch.read("$prefix$type       ${key.padEnd(20)} $value")
        }
        fun report(stamp: Int? = now.toInt()) = raw("SYN_REPORT", "00000000", stamp)
        fun slot(i: Int) = raw("ABS_MT_SLOT", i.toString(16))
        fun down(time: Int, i: Int = 0, x: Int = 600, y: Int = 2590) {
            at(time); slot(i); raw("ABS_MT_TRACKING_ID", "00000001")
            raw("ABS_MT_POSITION_X", (x * 100).toString(16))
            raw("ABS_MT_POSITION_Y", (y * 100).toString(16))
            report()
        }
        fun move(time: Int, x: Int = 600, y: Int = 2590, i: Int = 0, stamp: Int? = time) {
            at(time); slot(i); raw("ABS_MT_POSITION_X", (x * 100).toString(16), stamp)
            raw("ABS_MT_POSITION_Y", (y * 100).toString(16), stamp); report(stamp)
        }
        fun up(time: Int, i: Int = 0, stamp: Int? = time) {
            at(time); slot(i); raw("ABS_MT_TRACKING_ID", "ffffffff", stamp); report(stamp)
        }
        fun expect(vararg expected: String) { at(now.toInt() + 1000); assertEquals(expected.toList(), sent) }
    }

    @Test fun hiddenBarSwipesAndTapsNeverArmOnTheBlindMark() {
        for ((distance, duration) in listOf(148 to 52, 200 to 80, 312 to 94, 300 to 181, 450 to 500, 250 to 150)) {
            Replay().apply {
                down(0); start(15)
                for (t in 20..300 step 10) move(t, 600 + distance * t.coerceAtMost(duration) / duration)
                mark(315); up(700); expect("PREPARE@15")
            }
        }
        Replay().apply { down(0); start(5); up(60); mark(305); expect("PREPARE@5") }
    }

    @Test fun stillHoldDriftAndLateSwipeBoundaries() {
        for (travel in listOf(0, 2, 4, 6)) Replay().apply {
            down(0); start(3); move(100, 600 + travel); mark(303); up(850)
            expect("PREPARE@3", "ARM@230:300")
        }
        for (t in listOf(45, 120, 180, 229, 240, 299, 300, 350, 479, 480, 700)) Replay().apply {
            down(0); start(4); move(t, 607); mark(maxOf(305, t + 1)); up(900)
            when (t) {
                in 0..229 -> expect("PREPARE@4")
                in 230..479 -> expect("PREPARE@4", "ARM@230:300", "CANCEL@$t")
                else -> expect("PREPARE@4", "ARM@230:300")
            }
        }
    }

    @Test fun liftUsesKernelAgeIncludingDeliveryAfterDeadline() {
        for ((event, delivery) in listOf(239 to 239, 290 to 380, 299 to 320, 300 to 305, 330 to 460)) Replay().apply {
            down(0); start(8); up(delivery, stamp = event)
            if (event < 300) expect("PREPARE@8", "ARM@230:300", "CANCEL@$delivery")
            else expect("PREPARE@8", "ARM@230:300")
        }
        Replay().apply { down(0); start(8); up(245, stamp = null); expect("PREPARE@8", "ARM@230:300", "CANCEL@245") }
    }

    @Test fun ambiguousMatchesAndMissingTouchReaderUseLauncherMarkPlusOneHundred() {
        Replay().apply { start(5); mark(305); expect("PREPARE@5", "ARM@305:405") }
        Replay().apply { down(0); start(5, hot = false); mark(305); expect("PREPARE@5", "ARM@305:405") }
        Replay().apply { down(0, x = 120); start(5); mark(305); expect("PREPARE@5", "ARM@305:405") }
        Replay().apply { down(0); start(5); at(100); gesture.readerEnded(); mark(305); expect("PREPARE@5", "ARM@305:405") }
        for (line in listOf("action: Some(Up)", "action: Some(Cancel)", "nav_long_press_ready=false", "Started(Drag)", "Home on_touch_event , action=Move, x=600, y=2529")) {
            Replay().apply { start(5); mark(305); log(380, line); expect("PREPARE@5", "ARM@305:405", "CANCEL@380") }
        }
        Replay().apply { start(5); log(25, "Started(Drag)"); mark(305); expect("PREPARE@5") }
    }

    @Test fun eachFingerKeepsItsOwnSlotAndMovement() {
        Replay().apply {
            down(0, 1); start(5); down(105, 0, 120, 555); move(150, 250, 555, 0); up(200, 0); mark(305)
            expect("PREPARE@5", "ARM@305:405")
        }
        Replay().apply {
            down(0, 1); start(5); down(105, 0, 120, 555); move(180, 640, i = 1); mark(305)
            expect("PREPARE@5")
        }
        Replay().apply {
            down(0, 0, 120, 555); down(400, 1); start(418); move(430, 260, 555, 0)
            mark(718); expect("PREPARE@418", "ARM@630:700")
        }
    }

    @Test fun launcherAndTouchMayArriveInEitherOrderOrRotated() {
        Replay().apply { start(0); down(40); mark(300); expect("PREPARE@0", "ARM@270:340") }
        Replay().apply { down(0); move(20, 660); up(53); start(600); mark(900); expect("PREPARE@600") }
        Replay().apply { down(0); start(400); at(401); mark(700); expect("PREPARE@400", "ARM@400:440") }
        for ((x, y) in listOf(1300 to 1190, 1307 to 9)) Replay().apply {
            down(0, x = 9, y = 1300); start(5, x, y); mark(305); expect("PREPARE@5", "ARM@230:300")
        }
        Replay().apply {
            down(0); up(60); start(400); down(405); mark(700)
            expect("PREPARE@400", "ARM@635:705")
        }
    }

    @Test fun staleLauncherMarkCannotArmNextTouch() {
        for (swipe in listOf(true, false)) Replay().apply {
            down(0); start(5); up(60); down(140); start(145); mark(305)
            if (swipe) move(354, 612)
            mark(445)
            if (swipe) expect("PREPARE@5", "PREPARE@145")
            else expect("PREPARE@5", "PREPARE@145", "ARM@370:440")
        }
        Replay().apply {
            start(5); mark(305); start(335); mark(635)
            expect("PREPARE@5", "ARM@305:405", "CANCEL@335", "PREPARE@335", "ARM@635:735")
        }
    }

    @Test fun mixedSequenceOnTheSameReader() {
        val random = java.util.Random(42)
        val replay = Replay()
        val expected = mutableListOf<String>()
        repeat(90) { index ->
            val t = 1000 + index * 1200
            val delay = random.nextInt(56) - 15
            if (index % 3 == 0) replay.down(t - 400, 1, 110, 800)
            if (delay < 0) replay.start(t + delay)
            replay.down(t)
            if (delay >= 0) replay.start(t + delay)
            expected += "PREPARE@${t + delay}"
            when (index % 4) {
                0 -> replay.up(t + 65)
                1 -> replay.move(t + 120, 730)
                2 -> replay.move(t + 182, 607)
                else -> {
                    replay.move(t + 140, 603)
                    expected += "ARM@${t + 230}:${t + 300}"
                }
            }
            replay.mark(t + delay + 300)
            replay.up(t + 750)
            if (index % 3 == 0) replay.up(t + 800, 1)
        }
        replay.expect(*expected.toTypedArray())
    }

    @Test fun droppedReportAndLostReleaseCannotStick() {
        Replay().apply {
            down(0); start(5); at(240); raw("SYN_DROPPED", "00000000"); report()
            move(600); start(605); move(650, 660); mark(905)
            expect("PREPARE@5", "ARM@230:300", "CANCEL@240", "PREPARE@605")
        }
        Replay().apply {
            down(0); start(5); at(245); raw("BTN_TOUCH", "UP"); report()
            expect("PREPARE@5", "ARM@230:300", "CANCEL@245")
        }
    }
}
