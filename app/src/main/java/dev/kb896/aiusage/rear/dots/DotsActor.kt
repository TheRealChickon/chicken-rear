package dev.kb896.aiusage.rear.dots

import dev.kb896.aiusage.data.MascotMood
import kotlin.math.sin

/**
 * WHEN OPENAI'S DOTS CHARACTER ACTS on the usage page (ChatGPT's vessel). The character's own motion is the ChatGPT
 * app's renderer's, never ours: between acts it breathes, blinks and sways as OpenAI drew it ([DotsFeed.picture]);
 * an act is ITS OWN SIGNATURE MOVE ([DotsFeed.signature]). What this decides is only when, by the same rules as our
 * Claude mascot (rear/MascotLive.Actor): it acts, rests, and acts again by what its account has earned.
 *
 * - The page opens: it acts once within half a second (our mascot lands as the page opens).
 * - Awake ([MascotMood.WALK] and anything not below): a long rest, 9 to 14 s, then its move.
 * - At work ([MascotMood.MUSCLE]): 6 to 9 s.
 * - Near the limit ([MascotMood.FLAG]): 3.5 to 5.5 s, restless.
 * - Spent ([MascotMood.SLEEP], [MascotMood.GHOST], [MascotMood.DEAD]): no acts, and OpenAI's own reduced motion
 *   ([Act.calm]), so it barely stirs.
 * - A window starts again (a level drops by [RESET_DROP] at once): its move, at once (our mascot throws confetti).
 * Never two acts within [ACT_MS] (its move takes about two seconds). The rests are the same for the same place in its
 * string of acts ([seed], like MascotLive's), so a test can hold them.
 *
 * Pure: no clock of its own, no Android. Not for two threads at once.
 */
class DotsActor(private val seed: Int = 0) {
    enum class Doing { ARRIVE, REST, ACT }

    /** What to tell the renderer now: [signature] true on exactly one call per act; [calm] while spent. One object, written anew at every call. */
    class Act {
        var signature = false
        var calm = false
        var doing = Doing.ARRIVE
    }

    private val act = Act()
    private var next = -1L
    private var actedAt = Long.MIN_VALUE / 2
    private var last: List<Float> = emptyList()
    private var n = 0
    private var started = false

    private fun chance(k: Int): Float {
        val x = sin((seed * 131 + n * 71 + k) * 12.9898) * 43758.5453
        return (x - Math.floor(x)).toFloat()
    }

    /** The rest after an act for [mood], in ms. */
    fun restMs(mood: String): Long {
        val (lo, hi) = when (mood) {
            MascotMood.FLAG -> 3_500L to 5_500L
            MascotMood.MUSCLE -> 6_000L to 9_000L
            else -> 9_000L to 14_000L
        }
        return lo + ((hi - lo) * chance(n)).toLong()
    }

    /** At [now], for [used] (each limit's level), [mood] (what the account has earned); [opened]: when the page came up (same clock). */
    fun at(used: List<Float>, mood: String, now: Long, opened: Long): Act {
        val spent = spent(mood)
        act.signature = false
        act.calm = spent
        if (!started) {
            started = true
            last = used
            next = if (spent) -1L else opened + FIRST_MS
        }
        val again = last.size == used.size && last.indices.any { last[it] - used[it] > RESET_DROP }
        last = used
        when {
            spent -> next = -1L
            next < 0L -> next = now + WAKE_MS                            // was spent, is awake again
            now - next > FAR_BEHIND_MS -> next = now + FIRST_MS          // the page was not drawn for long: no burst of acts
        }
        val due = !spent && (again || now >= next)
        if (due && now - actedAt >= ACT_MS) {
            act.signature = true
            actedAt = now
            n++
            next = now + ACT_MS + restMs(mood)
        } else if (due && again) {
            // A window started again just after an act: its move as soon as that one is over.
            next = actedAt + ACT_MS
        }
        act.doing = when {
            !started || (now < opened + FIRST_MS && n == 0) -> Doing.ARRIVE
            now - actedAt < ACT_MS -> Doing.ACT
            else -> Doing.REST
        }
        return act
    }

    /** The page went away: the next opening starts afresh. */
    fun reset() { started = false; next = -1L; last = emptyList(); n = 0; actedAt = Long.MIN_VALUE / 2 }

    companion object {
        /** Its first act after the page opens. */
        const val FIRST_MS = 450L
        /** No act while the last one plays (its signature move is about two seconds). */
        const val ACT_MS = 2_400L
        /** From spent to awake: its move this soon. */
        const val WAKE_MS = 600L
        /** A level falling by this much at once is a window starting again (as MascotLive.Actor.RESET_DROP). */
        const val RESET_DROP = 0.25f
        const val FAR_BEHIND_MS = 60_000L

        fun spent(mood: String): Boolean = mood == MascotMood.SLEEP || mood == MascotMood.GHOST || mood == MascotMood.DEAD
    }
}
