package dev.kb896.aiusage.service

/**
 * A CALL WHOSE NOTIFICATION HAS GONE IS NOT YET A CALL THAT HAS ENDED. The phone's dialer takes its "incoming call"
 * notification down as he answers and posts its "call going on" one a moment later, under another key: seen with a
 * real call, the back's call page went out and came in again 0.2 s apart (the gap was about 0.9 s; the page waits 0.7
 * s for a notification to come back). Answering must only change the page's buttons and the line under the name.
 *
 * So when a call's notification goes, the relay asks here what the PHONE'S AUDIO MODE says (AudioManager.getMode,
 * no permission): ringing or in a call = the call is still on, the page stays (and, in a call, becomes the
 * answered one at once); back to normal, seen twice in a row = it has ended, for sure. The mode is only believed
 * if it said "a call" while this call's notification was still up ([seen]); a phone on which it says nothing is
 * given [BLIND_MS] of patience instead, and its end is not "for sure".
 *
 * Pure: the mode and the time are handed in. Not thread-safe (the relay's main thread).
 */
class CallHold {
    sealed interface Verdict {
        /** Still that call: look again in [againMs]. [talking]: it has been answered, show it so now. */
        data class Wait(val againMs: Long, val talking: Boolean) : Verdict
        /** Ended. [sure]: the phone itself says no call is on, so the page need not wait for a notification to come back. */
        data class Clear(val sure: Boolean) : Verdict
    }

    private var works = false
    private var since = 0L
    private var normals = 0
    /** The key of the call that is held, null for none. */
    var key: String? = null
        private set
    /** Whether the held call was still ringing when its notification went. */
    var ringing = false
        private set

    /** A call's notification is up (posted, or posted again): what the audio mode says meanwhile. Ends a hold. */
    fun seen(mode: Int) {
        if (calling(mode)) works = true
        key = null; normals = 0
    }

    /** The notification of the call that had the back has gone. */
    fun gone(now: Long, key: String, ringing: Boolean, mode: Int): Verdict {
        this.key = key; this.ringing = ringing; since = now; normals = 0
        if (calling(mode)) works = true
        return look(now, mode)
    }

    /** Asked again while a call is held. */
    fun look(now: Long, mode: Int): Verdict {
        if (key == null) return Verdict.Clear(sure = false)
        val verdict = when {
            !works -> if (now - since < BLIND_MS) Verdict.Wait(AGAIN_MS, talking = false) else Verdict.Clear(sure = false)
            calling(mode) -> {
                normals = 0
                if (now - since < MAX_MS) Verdict.Wait(AGAIN_MS, talking = talking(mode)) else Verdict.Clear(sure = false)
            }
            // "No call", once: it may be the moment between ringing and answered. Twice in a row: it is over.
            else -> if (++normals < 2) Verdict.Wait(AGAIN_MS, talking = false) else Verdict.Clear(sure = true)
        }
        if (verdict is Verdict.Clear) { key = null; works = false }
        return verdict
    }

    /** Nothing is held any more (another live thing has the back, the relay is going). */
    fun drop() { key = null; normals = 0 }

    companion object {
        // android.media.AudioManager's modes, by value (this class stays free of Android for its tests).
        const val MODE_NORMAL = 0
        const val MODE_RINGTONE = 1
        const val MODE_IN_CALL = 2
        const val MODE_IN_COMMUNICATION = 3
        const val MODE_CALL_SCREENING = 4
        const val MODE_CALL_REDIRECT = 5
        const val MODE_COMMUNICATION_REDIRECT = 6

        fun calling(mode: Int) = mode in MODE_RINGTONE..MODE_COMMUNICATION_REDIRECT
        fun talking(mode: Int) = mode == MODE_IN_CALL || mode == MODE_IN_COMMUNICATION || mode == MODE_CALL_REDIRECT || mode == MODE_COMMUNICATION_REDIRECT

        const val AGAIN_MS = 200L
        /** A call with no notification at all is held no longer than this: its page's buttons come from the notification. */
        const val MAX_MS = 5_000L
        /** Where the audio mode says nothing: this long for the next notification of the call. */
        const val BLIND_MS = 1_500L
    }
}
