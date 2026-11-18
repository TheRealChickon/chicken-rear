package dev.kb896.aiusage.service

import dev.kb896.aiusage.rear.NoticeThrow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A notification thrown out on the back, done on the phone through the app's listener (NotificationRelay; here a fake
 * that keeps what it was asked): dismissed, or snoozed for the hour with its own key; one the phone does not let be
 * swiped away, a made-up one, or one already gone, left alone.
 */
class PhoneThrowTest {
    private class Fake(val there: Map<String, PhoneThrow.Facts>) : PhoneThrow.Listener {
        val cancelled = ArrayList<String>()
        val snoozed = ArrayList<Pair<String, Long>>()
        override fun find(key: String) = there[key]
        override fun cancel(key: String) { cancelled += key }
        override fun snooze(key: String, ms: Long) { snoozed += key to ms }
    }

    private val plain = PhoneThrow.Facts(ongoing = false, clearable = true, category = "msg", media = false)
    private val key = "0|com.example.chat|7|null|10191"

    @Test fun `snoozed with its own key for one hour`() {
        val f = Fake(mapOf(key to plain))
        assertEquals(PhoneThrow.Result.DONE, PhoneThrow.snooze(f, key))
        assertEquals(listOf(key to 3_600_000L), f.snoozed)
        assertEquals(emptyList<String>(), f.cancelled)
        // As the back sends it (NoticeShade.Effect.Snoozed's ms, through the bridge).
        val g = Fake(mapOf(key to plain))
        PhoneThrow.snooze(g, key, NoticeThrow.SNOOZE_MS)
        assertEquals(listOf(key to NoticeThrow.SNOOZE_MS), g.snoozed)
    }

    @Test fun `dismissed with its own key, and never snoozed for it`() {
        val f = Fake(mapOf(key to plain))
        assertEquals(PhoneThrow.Result.DONE, PhoneThrow.dismiss(f, key))
        assertEquals(listOf(key), f.cancelled)
        assertEquals(emptyList<Pair<String, Long>>(), f.snoozed)
    }

    @Test fun `one the phone does not let be swiped away is left as it is, either way`() {
        val fixed = mapOf(
            "ongoing" to plain.copy(ongoing = true),
            "uncleared" to plain.copy(clearable = false),
            "call" to plain.copy(category = "call"),
            "timer" to plain.copy(category = "stopwatch"),
            "alarm" to plain.copy(category = "alarm"),
            "player" to plain.copy(media = true),
            "service" to plain.copy(category = "service"),
        )
        val f = Fake(fixed)
        for (k in fixed.keys) {
            assertEquals(k, PhoneThrow.Result.REFUSED, PhoneThrow.snooze(f, k))
            assertEquals(k, PhoneThrow.Result.REFUSED, PhoneThrow.dismiss(f, k))
        }
        assertEquals(emptyList<String>(), f.cancelled)
        assertEquals(emptyList<Pair<String, Long>>(), f.snoozed)
    }

    @Test fun `a made-up one never reaches the phone, even if one of that key were there`() {
        val f = Fake(mapOf("demo:0" to plain, "demo:1" to plain))
        assertEquals(PhoneThrow.Result.REFUSED, PhoneThrow.dismiss(f, "demo:0"))
        assertEquals(PhoneThrow.Result.REFUSED, PhoneThrow.snooze(f, "demo:1"))
        assertEquals(PhoneThrow.Result.REFUSED, PhoneThrow.snooze(f, ""))
        assertEquals(emptyList<String>(), f.cancelled)
        assertEquals(emptyList<Pair<String, Long>>(), f.snoozed)
    }

    @Test fun `one already gone from the phone asks nothing of it`() {
        val f = Fake(emptyMap())
        assertEquals(PhoneThrow.Result.GONE, PhoneThrow.snooze(f, key))
        assertEquals(PhoneThrow.Result.GONE, PhoneThrow.dismiss(f, key))
        assertEquals(emptyList<Pair<String, Long>>(), f.snoozed)
    }

    @Test fun `the snooze's length is kept to sense`() {
        assertEquals(NoticeThrow.SNOOZE_MS, PhoneThrow.duration(0L))
        assertEquals(NoticeThrow.SNOOZE_MS, PhoneThrow.duration(-5L))
        assertEquals(60_000L, PhoneThrow.duration(5L))
        assertEquals(24L * 3_600_000L, PhoneThrow.duration(Long.MAX_VALUE))
        assertEquals(NoticeThrow.SNOOZE_MS, PhoneThrow.duration(NoticeThrow.SNOOZE_MS))
    }
}
