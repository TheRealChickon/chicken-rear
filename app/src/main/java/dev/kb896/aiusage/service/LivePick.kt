package dev.kb896.aiusage.service

/**
 * Which of the notifications already on the front is the back's live page, when the relay connects and finds
 * several: a call before anything, then the Clock's own note for the rear screen (it says the timer whole: its
 * end, its length, paused or not), then any other going timer or stopwatch. Plain, so a test holds it.
 */
object LivePick {
    class Candidate(val key: String, val call: Boolean, val rearTimer: Boolean)

    /**
     * Whether a notification of a clock app on channel [id] is an ALARM's (ringing, snoozed, upcoming, a bedtime
     * reminder) and so no timer or stopwatch. Xiaomi's Clock has channels of its own for each, read from the phone:
     * `channel_id_deskclock_alarm`, `channel_id_deskclock_timer`, `channel_id_deskclock_stopwatch`,
     * `channel_back_screen_timer_id` (the rear's timer note), `1100_bedtimeReminder`. Google's Clock names its own
     * "Firing alarms and timers" apart from "Timers" and "Stopwatch"; an id with "alarm", "bedtime" or "firing" in it
     * and no "timer" is taken for an alarm's.
     */
    fun clockAlarm(id: String?): Boolean {
        val c = id.orEmpty().lowercase()
        // ("bedtimeReminder" has "timer" in its letters: asked first.)
        return "bedtime" in c || ("timer" !in c && ("alarm" in c || "firing" in c))
    }

    /**
     * WHETHER A CALL HAS BEEN ANSWERED IS THE PHONE'S TO SAY, NOT A NOTIFICATION'S. While he talked the
     * dialer had FOUR notifications up, among them its own note for the rear screen (id 100, tag "backscreen", no
     * running clock on it), and whichever spoke last was taken for the call: ringing, for the whole call. So: the
     * phone's audio mode ([mode], AudioManager.getMode, no permission) in a call or in communication = answered,
     * whatever notifications are up; ringing = ringing; and only where the mode says neither (a phone that tells an
     * app nothing) the notification's own running clock ([chronometer]) decides, as before.
     */
    fun callTalking(mode: Int, chronometer: Boolean): Boolean = when {
        CallHold.talking(mode) -> true
        mode == CallHold.MODE_RINGTONE -> false
        else -> chronometer
    }

    /**
     * Which of one app's notifications of a call is the call's own, when it has several up: the one whose clock
     * runs (the call going on) before one without, and among those an ongoing one before one that is not. So a
     * dialer's extra note (for the rear screen, for its recorder) does not stand for the call. The key of the best.
     */
    class CallNote(val key: String, val chronometer: Boolean, val ongoing: Boolean)
    fun callNote(all: List<CallNote>): String? = (all.firstOrNull { it.chronometer && it.ongoing } ?: all.firstOrNull { it.chronometer } ?: all.firstOrNull { it.ongoing } ?: all.firstOrNull())?.key

    fun first(all: List<Candidate>): String? =
        (all.firstOrNull { it.call } ?: all.firstOrNull { it.rearTimer } ?: all.firstOrNull())?.key
}
